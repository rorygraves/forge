package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.kernel.Deferred
import cats.effect.std.Console
import io.forge.app.cli.DaemonCommand
import io.forge.app.lock.{FileProcessLock, LockAcquireResult, LockMetadata}
import io.forge.core.InstanceName
import io.forge.daemon.{BudgetPolicy, Daemon, DaemonAddress, DaemonClient, DaemonSocketServer, DaemonState, JsonRpc}
import io.forge.instance.{FileInstanceStore, Instance, InstanceStore}

import java.net.InetAddress
import java.time.Instant
import scala.util.control.NonFatal

/** Task 4.1.3 — handlers for the Phase-4 daemon supervisor commands (`daemon start | stop | status`).
  *
  * Instance-scoped like [[InstanceCommands]]: `Main` routes them here without loading
  * [[io.forge.app.config.ForgeConfig]], installing reviewer assets, or taking the per-checkout lock. The target
  * instance resolves via the shared [[InstanceResolver]] (explicit `--instance`, else the sole instance).
  *
  *   - **start** runs the foreground supervisor: it acquires the **instance** lock ([[Instance.lockFile]]) — the daemon
  *     liveness authority (§6.3.1) — boots [[DaemonState]] from the durable log, and serves the socket until a
  *     `shutdown` RPC (or SIGINT) stops it. A second live `start` for the same instance is refused (`Held` → exit 2). A
  *     stale lock from a crashed daemon is reclaimed (`acceptStale = true`) — the crash-recovery restart path (4.1.5).
  *   - **stop** / **status** are JSON-RPC client round-trips to a running daemon over its socket. A connect failure
  *     means no daemon is running (exit 1); these never take the lock.
  */
object DaemonCommands:

  def run(home: os.Path, command: DaemonCommand): IO[ExitCode] =
    val store = new FileInstanceStore(home)
    command match
      case DaemonCommand.Start(instance, container) => start(store, instance, container)
      case DaemonCommand.Stop(instance) => stop(store, instance)
      case DaemonCommand.Status(instance) => status(store, instance)

  // --- start (foreground supervisor, holds the instance lock) ----------------

  private def start(store: InstanceStore, instance: Option[InstanceName], container: Boolean): IO[ExitCode] =
    InstanceResolver.resolve(store, instance, "daemon start").flatMap {
      case Left(code) => IO.pure(code)
      case Right(resolved) =>
        lockMetadata("daemon start").flatMap { metadata =>
          new FileProcessLock(resolved.lockFile, resolved.lockMetadataFile)
            .acquire(metadata, acceptStale = true)
            .use {
              case LockAcquireResult.Acquired => runForeground(resolved, container)
              case LockAcquireResult.Held(holder) =>
                Console[IO]
                  .errorln(
                    s"forge daemon start: a daemon already holds the '${resolved.name.value}' instance lock." +
                      holderSuffix(holder)
                  )
                  .as(ExitCode(2))
              case LockAcquireResult.Stale(holder) =>
                // Unreachable with acceptStale = true (Stale is upgraded to Acquired); defensive branch.
                Console[IO]
                  .errorln(
                    s"forge daemon start: a stale '${resolved.name.value}' instance lock is present." +
                      holderSuffix(Some(holder))
                  )
                  .as(ExitCode(2))
            }
        }
    }

  /** Boot the daemon state from the durable log, build the worker supervisor, **reconcile** any workers that survived a
    * prior crash (§6.4), then serve the socket until a `shutdown` RPC (or SIGINT) stops it — with the supervisor's §6.2
    * cadence sweep running in the background for the serving lifetime. The instance lock is held by the caller's
    * `Resource` bracket for the whole of this; on return (clean stop or cancel) the lock + socket are released. Spawned
    * worker *processes* are intentionally **not** killed on stop — they survive the daemon's restart (see
    * [[RealSupervisor]]).
    */
  private def runForeground(instance: Instance, container: Boolean): IO[ExitCode] =
    for
      _ <- Console[IO].println(
        s"forge daemon: starting supervisor for instance '${instance.name.value}'" +
          (if container then " (containerised workers)…" else "…")
      )
      shutdown <- Deferred[IO, Unit]
      state <- DaemonState.boot(instance, ProcessHandle.current().pid())
      supervisor <-
        if container then RealSupervisor.buildContainer(instance, state) else RealSupervisor.build(instance, state)
      boots <- state.snapshot.map(_.bootCount)
      live <- state.snapshot.map(_.workers.count(_.live))
      _ <-
        if live > 0 then Console[IO].println(s"forge daemon: reconciling $live worker(s) from the instance log…")
        else IO.unit
      _ <- supervisor.reconcile
      // A containerised daemon binds all interfaces so a worker can reach it over `host.docker.internal`; a host-only
      // daemon binds loopback. The ephemeral port is logged once it binds (see `onBound`).
      bindHost = if container then DaemonSocketServer.AllInterfaces else DaemonSocketServer.Loopback
      _ <- supervisor
        .superviseLoop(RealSupervisor.DefaultCadence)
        .background
        .use(_ =>
          Daemon.serveUntilShutdown(
            instance.portFile,
            state,
            shutdown,
            supervisor,
            RealCredentialBroker.default,
            BudgetPolicy.default,
            bindHost = bindHost,
            onBound = port =>
              val where =
                if container then s"0.0.0.0:$port (workers reach it at ${DaemonAddress.DockerHost}:$port)"
                else s"${DaemonAddress.Loopback}:$port"
              Console[IO].println(s"forge daemon: listening on $where (boot #$boots).")
          )
        )
      _ <- Console[IO].println("forge daemon: stopped.")
    yield ExitCode.Success

  // --- stop ------------------------------------------------------------------

  private def stop(store: InstanceStore, instance: Option[InstanceName]): IO[ExitCode] =
    InstanceResolver.resolve(store, instance, "daemon stop").flatMap {
      case Left(code) => IO.pure(code)
      case Right(resolved) =>
        DaemonClient.call(resolved.portFile, JsonRpc.Request(1L, "shutdown")).attempt.flatMap {
          case Right(_: JsonRpc.Response.Success) =>
            Console[IO]
              .println(s"forge daemon: stop requested for instance '${resolved.name.value}'.")
              .as(ExitCode.Success)
          case Right(JsonRpc.Response.Failure(_, err)) =>
            Console[IO].errorln(s"forge daemon stop: ${err.message}").as(ExitCode(1))
          case Left(_) => notRunning("daemon stop", resolved)
        }
    }

  // --- status ----------------------------------------------------------------

  private def status(store: InstanceStore, instance: Option[InstanceName]): IO[ExitCode] =
    InstanceResolver.resolve(store, instance, "daemon status").flatMap {
      case Left(code) => IO.pure(code)
      case Right(resolved) =>
        DaemonClient.call(resolved.portFile, JsonRpc.Request(1L, "status")).attempt.flatMap {
          case Right(JsonRpc.Response.Success(_, result)) =>
            Console[IO].println(renderStatus(resolved, result)).as(ExitCode.Success)
          case Right(JsonRpc.Response.Failure(_, err)) =>
            Console[IO].errorln(s"forge daemon status: ${err.message}").as(ExitCode(1))
          case Left(_) => notRunning("daemon status", resolved)
        }
    }

  /** Human-readable rendering of the daemon's `status` snapshot (the `InstanceState.toStatusJson` shape). For 4.1.3 the
    * worker list is normally empty (`register-worker` is 4.1.4); the lines are already shaped for it.
    */
  private def renderStatus(instance: Instance, result: ujson.Value): String =
    val obj = result.objOpt.getOrElse(ujson.Obj().obj)
    val bootCount = obj.get("bootCount").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
    val workers = obj.get("workers").flatMap(_.arrOpt).getOrElse(scala.collection.mutable.ArrayBuffer.empty)
    val header = s"forge daemon '${instance.name.value}': running (boot #$bootCount, ${workers.size} worker(s))"
    val workerLines = workers.toVector.flatMap(_.objOpt).map { w =>
      val id = w.get("workerId").flatMap(_.strOpt).getOrElse("?")
      val feature = w.get("feature").flatMap(_.strOpt).getOrElse("?")
      val st = w.get("status").flatMap(_.strOpt).getOrElse("?")
      val events = w.get("eventCount").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
      s"  - $id  feature=$feature  status=$st  events=$events"
    }
    (header +: workerLines).mkString("\n")

  // --- shared ----------------------------------------------------------------

  private def notRunning(label: String, instance: Instance): IO[ExitCode] =
    Console[IO]
      .errorln(
        s"forge $label: no daemon appears to be running for instance '${instance.name.value}' " +
          s"(no daemon port file at ${instance.portFile}). Start one with `forge daemon start`."
      )
      .as(ExitCode(1))

  private def lockMetadata(label: String): IO[LockMetadata] =
    IO.blocking {
      val hostname =
        try InetAddress.getLocalHost.getHostName
        catch case NonFatal(_) => "unknown"
      LockMetadata(
        pid = ProcessHandle.current().pid(),
        hostname = hostname,
        startedAt = Instant.now(),
        command = s"forge $label",
        feature = None
      )
    }

  private def holderSuffix(meta: Option[LockMetadata]): String = meta match
    case Some(m) => s" Holder: pid=${m.pid} host=${m.hostname} command='${m.command}' started=${m.startedAt}."
    case None => ""
