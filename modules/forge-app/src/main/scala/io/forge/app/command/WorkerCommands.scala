package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.cli.WorkerCommand
import io.forge.app.config.{ConfigError, ForgeConfigLoader}
import io.forge.app.lock.{FileProcessLock, LockAcquireResult, LockMetadata}
import io.forge.core.paths.ForgePaths
import io.forge.daemon.DaemonAddress
import io.forge.instance.{FileInstanceStore, Instance, InstanceError}

import java.net.InetAddress
import java.time.Instant
import scala.util.control.NonFatal

/** The hidden, daemon-spawned **worker** entrypoint (`forge worker`), the forge-app half of the B4 worker-process
  * boundary.
  *
  * The daemon supervisor (4.2.5) spawns this as a real child OS process via the `forge-daemon` `WorkerSpawner`, passing
  * the instance, the worker id it was assigned, the repo, and the feature on argv. The worker resolves its **isolated
  * clone** (`Instance.workerCheckout(workerId)`, provisioned by the supervisor via [[WorkerProvisioner]] before spawn —
  * §4.3 / O10), re-roots its [[ForgePaths]] so the local-runtime family lives under the worker dir (B1), and runs the
  * frozen v1 feature loop over it while exporting its action feed back to the daemon over the instance socket
  * ([[WorkerLoop]] → [[WorkerFeedExporter]], B3).
  *
  * Instance-scoped like [[DaemonCommands]]: `Main` routes here without config / assets / a per-checkout lock; the
  * worker loads the clone's config and installs assets itself. The instance is resolved by its **explicit** name (the
  * daemon never relies on the sole-instance fallback when spawning). Every reachable failure degrades to a non-zero
  * exit with a diagnostic, never a crash, so a supervised worker's exit code is meaningful.
  */
object WorkerCommands:

  def run(home: os.Path, command: WorkerCommand): IO[ExitCode] =
    if command.container then runContainerised(home, command)
    else
      new FileInstanceStore(home).load(command.instance).flatMap {
        case Left(InstanceError.NoSuchInstance(name)) =>
          Console[IO]
            .errorln(s"forge worker: no such instance '${name.value}' (the daemon should have created it).")
            .as(ExitCode(1))
        case Left(err) =>
          Console[IO].errorln(s"forge worker: instance error: $err").as(ExitCode(1))
        case Right(instance) => runOnClone(home, instance, command)
      }

  /** The 4.2 **host-process** path: resolve the worker's re-rooted clone paths from the loaded [[Instance]] (the
    * instance dir exists on the host); the daemon is reached over loopback at the port the daemon wrote to the instance
    * port file (resolved per-RPC by the reporter, so the first `register` rides out the start-up race), no credential
    * brokering (the worker inherits the daemon's environment). An absent port file / unreachable daemon degrades to a
    * non-zero exit via [[degrade]] rather than crashing.
    */
  private def runOnClone(home: os.Path, instance: Instance, command: WorkerCommand): IO[ExitCode] =
    drive(
      home,
      command,
      checkout = instance.workerCheckout(command.workerId),
      workerRoot = instance.workerRoot(command.workerId),
      daemon = Left(instance.portFile),
      brokerCredentials = false
    )

  /** The 4.3 **containerised** path: the instance dir does not exist inside the container, so the daemon passes the
    * in-container worker root and the daemon's TCP address explicitly (`--worker-root` + `--socket
    * host.docker.internal:<port>`); the worker brokers its credentials over that connection (O6) rather than inheriting
    * host credentials. Both flags are required in container mode — a `--container` invocation missing them, or with an
    * unparseable `--socket` address, is a malformed spawn and degrades to a non-zero exit.
    */
  private def runContainerised(home: os.Path, command: WorkerCommand): IO[ExitCode] =
    (command.workerRoot, command.socket) match
      case (Some(rootRaw), Some(socketRaw)) =>
        DaemonAddress.parse(socketRaw) match
          case Right(daemon) =>
            val workerRoot = os.Path(rootRaw)
            drive(
              home,
              command,
              checkout = workerRoot / "checkout",
              workerRoot = workerRoot,
              daemon = Right(daemon),
              brokerCredentials = true
            )
          case Left(reason) =>
            Console[IO]
              .errorln(s"forge worker '${command.workerId}': invalid --socket '$socketRaw' ($reason).")
              .as(ExitCode(1))
      case _ =>
        Console[IO]
          .errorln(
            s"forge worker '${command.workerId}': --container requires both --worker-root and --socket " +
              "(the daemon passes the in-container worker root and the host.docker.internal:<port> daemon address)."
          )
          .as(ExitCode(1))

  /** Shared body for both topologies: build the re-rooted clone [[ForgePaths]], load the clone's config, hold the
    * per-worker lock, and drive the feature loop (brokering credentials first when `brokerCredentials`). A missing
    * checkout (the supervisor should have provisioned/mounted it) or a malformed clone config degrades to a non-zero
    * exit; an unreachable daemon / loop error degrades via [[degrade]].
    */
  private def drive(
      home: os.Path,
      command: WorkerCommand,
      checkout: os.Path,
      workerRoot: os.Path,
      daemon: Either[os.Path, DaemonAddress],
      brokerCredentials: Boolean
  ): IO[ExitCode] =
    IO.blocking(os.exists(checkout)).flatMap {
      case false =>
        Console[IO]
          .errorln(
            s"forge worker '${command.workerId}': no clone at $checkout " +
              s"(the daemon should have provisioned/mounted it before spawn)."
          )
          .as(ExitCode(1))
      case true =>
        val paths = new ForgePaths(checkout, home, localRootOpt = Some(workerRoot))
        ForgeConfigLoader.load(paths).flatMap {
          case Left(err) =>
            Console[IO].errorln(s"forge worker '${command.workerId}': ${configErrorMessage(err)}").as(ExitCode(78))
          case Right(config) =>
            underWorkerLock(paths, command) {
              val reporter = daemon match
                case Left(portFile) => WorkerReporter.daemon(portFile, command.workerId)
                case Right(address) => WorkerReporter.daemon(address, command.workerId)
              val endpoint = daemon.fold(_.toString, _.render)
              reporter
                .flatMap(r => WorkerLoop.run(paths, config, r, command.repo, command.feature, brokerCredentials))
                .handleErrorWith(degrade(command, endpoint))
            }
        }
    }

  /** Hold the §13 per-worker process lock on the worker's re-rooted [[ForgePaths]] for the whole loop. The contract
    * puts the per-worker lock on the **worker process** (not the daemon), so it guards the worker's re-rooted
    * log/state/lock family against a duplicate `forge worker` for the same id — a manual invocation or a spawn that
    * slipped past the supervisor's idempotency guard. Mirrors `Main.stateChangingUnderLock` (the lock the routing layer
    * deliberately skips for the worker entrypoint, since the worker resolves its own re-rooted paths): `Acquired` runs
    * the loop; `Held` / `Stale` refuse with a diagnostic and exit 2 (§13), never sharing the log with a second writer.
    */
  private def underWorkerLock(paths: ForgePaths, command: WorkerCommand)(loop: IO[ExitCode]): IO[ExitCode] =
    lockMetadata(command).flatMap { metadata =>
      new FileProcessLock(paths).acquire(metadata, acceptStale = false).use {
        case LockAcquireResult.Acquired => loop
        case LockAcquireResult.Held(holder) =>
          Console[IO]
            .errorln(
              s"forge worker '${command.workerId}': another process holds the worker lock.${holderSuffix(holder)}"
            )
            .as(ExitCode(2))
        case LockAcquireResult.Stale(holder) =>
          Console[IO]
            .errorln(
              s"forge worker '${command.workerId}': a stale lock from a prior crashed worker is present." +
                s"${holderSuffix(Some(holder))} Run `forge unlock --force` against the worker root to clear it."
            )
            .as(ExitCode(2))
      }
    }

  /** Lock-holder metadata for the worker process (pid / host / start / command / feature) — the §13 "who holds it?"
    * diagnostic, mirroring `Main.lockMetadata`.
    */
  private def lockMetadata(command: WorkerCommand): IO[LockMetadata] =
    IO.blocking {
      val hostname =
        try InetAddress.getLocalHost.getHostName
        catch case NonFatal(_) => "unknown"
      LockMetadata(
        pid = ProcessHandle.current().pid(),
        hostname = hostname,
        startedAt = Instant.now(),
        command = s"forge worker ${command.workerId}",
        feature = Some(command.feature)
      )
    }

  private def holderSuffix(meta: Option[LockMetadata]): String = meta match
    case Some(m) => s" Holder: pid=${m.pid} host=${m.hostname} command='${m.command}' started=${m.startedAt}."
    case None => ""

  /** A loop error that escapes [[WorkerLoop]] — most often an unreachable daemon (the reporter's RPCs raise, or its
    * port file is absent) — degrades to exit 1 with the daemon-down diagnostic, never a crash. `endpoint` describes
    * where the worker tried to reach the daemon (the loopback `host:port` for a host worker,
    * `host.docker.internal:<port>` for a containerised one, or the port-file path when no daemon had bound).
    */
  private def degrade(command: WorkerCommand, endpoint: String)(error: Throwable): IO[ExitCode] =
    Console[IO]
      .errorln(
        s"forge worker '${command.workerId}': could not complete (${error.getMessage}). " +
          s"Is `forge daemon start --instance ${command.instance.value}` running at $endpoint?"
      )
      .as(ExitCode(1))

  private def configErrorMessage(err: ConfigError): String = err match
    case ConfigError.Malformed(path, cause) => s"malformed clone config at $path: ${cause.getMessage}"
    case ConfigError.IoFailure(path, cause) => s"could not read clone config at $path: ${cause.getMessage}"
