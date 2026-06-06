package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.cli.WorkstreamCommand
import io.forge.core.{FeatureId, InstanceName, WorkstreamId}
import io.forge.daemon.{DaemonClient, JsonRpc}
import io.forge.instance.{FileInstanceStore, Instance, InstanceStore}

/** Task 4.2.4 — handlers for the Phase-4 operator workstream/worker client commands (`workstream new | workstream list`
  * and `worker list`).
  *
  * Instance-scoped like [[DaemonCommands]]: `Main` routes them here without loading
  * [[io.forge.app.config.ForgeConfig]], installing reviewer assets, or taking the per-checkout lock. Each is a JSON-RPC
  * client round-trip to a **running daemon** over its socket — the daemon is the sole writer of the instance state
  * (§6.3.1), so `workstream new` cannot mutate the instance log directly; it asks the daemon to (`create-workstream`).
  * The list commands read the daemon's `status` snapshot. A connect failure means no daemon is running (exit 1); these
  * never take the instance lock.
  */
object WorkstreamCommands:

  def run(home: os.Path, command: WorkstreamCommand): IO[ExitCode] =
    val store = new FileInstanceStore(home)
    command match
      case WorkstreamCommand.New(instance, goal) => newWorkstream(store, instance, goal)
      case WorkstreamCommand.List(instance) => listWorkstreams(store, instance)
      case WorkstreamCommand.WorkerList(instance) => listWorkers(store, instance)
      case WorkstreamCommand.SpawnWorker(instance, ws, repo, feature) =>
        spawnWorker(store, instance, ws, repo, feature)

  // --- workstream new --------------------------------------------------------

  private def newWorkstream(store: InstanceStore, instance: Option[InstanceName], goal: String): IO[ExitCode] =
    withDaemon(store, instance, "workstream new") { resolved =>
      val req = JsonRpc.Request(1L, "create-workstream", ujson.Obj("goal" -> goal))
      DaemonClient.call(resolved.socketFile, req).attempt.flatMap {
        case Right(JsonRpc.Response.Success(_, result)) =>
          val id = result.objOpt.flatMap(_.get("workstreamId")).flatMap(_.strOpt).getOrElse("?")
          Console[IO]
            .println(s"forge workstream new: created $id (goal: $goal) in instance '${resolved.name.value}'")
            .as(ExitCode.Success)
        case Right(JsonRpc.Response.Failure(_, err)) =>
          Console[IO].errorln(s"forge workstream new: ${err.message}").as(ExitCode(1))
        case Left(_) => notRunning("workstream new", resolved)
      }
    }

  // --- workstream spawn (ask the daemon to spawn a worker) -------------------

  /** `forge workstream spawn <ws> --repo <path> --feature <id>` — a `spawn-worker` RPC. The repo path is resolved
    * against the operator's `os.pwd` to an absolute source path here (the daemon clones from it; its own cwd differs),
    * then the daemon's supervisor provisions the clone + launches the `forge worker` child. A daemon-side refusal (an
    * unknown/closed workstream, a clone/spawn failure) comes back as an error response → exit 1.
    */
  private def spawnWorker(
      store: InstanceStore,
      instance: Option[InstanceName],
      ws: WorkstreamId,
      repo: String,
      feature: FeatureId
  ): IO[ExitCode] =
    withDaemon(store, instance, "workstream spawn") { resolved =>
      val absRepo = os.Path(repo, os.pwd).toString
      val req = JsonRpc.Request(
        1L,
        "spawn-worker",
        ujson.Obj("workstreamId" -> ws.value, "repo" -> absRepo, "feature" -> feature.value)
      )
      DaemonClient.call(resolved.socketFile, req).attempt.flatMap {
        case Right(JsonRpc.Response.Success(_, result)) =>
          val id = result.objOpt.flatMap(_.get("workerId")).flatMap(_.strOpt).getOrElse("?")
          Console[IO]
            .println(
              s"forge workstream spawn: spawned $id into ${ws.value} " +
                s"(feature ${feature.value}, repo $absRepo) in instance '${resolved.name.value}'"
            )
            .as(ExitCode.Success)
        case Right(JsonRpc.Response.Failure(_, err)) =>
          Console[IO].errorln(s"forge workstream spawn: ${err.message}").as(ExitCode(1))
        case Left(_) => notRunning("workstream spawn", resolved)
      }
    }

  // --- workstream list / worker list (read the status snapshot) ---------------

  private def listWorkstreams(store: InstanceStore, instance: Option[InstanceName]): IO[ExitCode] =
    withStatus(store, instance, "workstream list")((resolved, snapshot) =>
      Console[IO].println(renderWorkstreams(resolved, snapshot)).as(ExitCode.Success)
    )

  private def listWorkers(store: InstanceStore, instance: Option[InstanceName]): IO[ExitCode] =
    withStatus(store, instance, "worker list")((resolved, snapshot) =>
      Console[IO].println(renderWorkers(resolved, snapshot)).as(ExitCode.Success)
    )

  /** Resolve the instance, call `status`, and hand the snapshot to `render`; map connect/RPC failures to exit codes. */
  private def withStatus(store: InstanceStore, instance: Option[InstanceName], label: String)(
      render: (Instance, ujson.Value) => IO[ExitCode]
  ): IO[ExitCode] =
    withDaemon(store, instance, label) { resolved =>
      DaemonClient.call(resolved.socketFile, JsonRpc.Request(1L, "status")).attempt.flatMap {
        case Right(JsonRpc.Response.Success(_, result)) => render(resolved, result)
        case Right(JsonRpc.Response.Failure(_, err)) =>
          Console[IO].errorln(s"forge $label: ${err.message}").as(ExitCode(1))
        case Left(_) => notRunning(label, resolved)
      }
    }

  private def renderWorkstreams(instance: Instance, snapshot: ujson.Value): String =
    val obj = snapshot.objOpt.getOrElse(ujson.Obj().obj)
    val workstreams = obj.get("workstreams").flatMap(_.arrOpt).getOrElse(scala.collection.mutable.ArrayBuffer.empty)
    if workstreams.isEmpty then s"forge workstream list: no workstreams in instance '${instance.name.value}'"
    else
      val header = s"forge workstreams in '${instance.name.value}' (${workstreams.size}):"
      val lines = workstreams.toVector.flatMap(_.objOpt).map { ws =>
        val id = ws.get("workstreamId").flatMap(_.strOpt).getOrElse("?")
        val goal = ws.get("goal").flatMap(_.strOpt).getOrElse("")
        val st = ws.get("status").flatMap(_.strOpt).getOrElse("?")
        val workers = ws.get("workers").flatMap(_.arrOpt).map(_.size).getOrElse(0)
        val attention = ws.get("attention").flatMap(_.arrOpt).getOrElse(scala.collection.mutable.ArrayBuffer.empty)
        val base = s"  - $id  status=$st  workers=$workers  goal=$goal"
        if attention.isEmpty then base
        else
          val flags = attention.toVector.flatMap(_.objOpt).map { a =>
            val wid = a.get("workerId").flatMap(_.strOpt).getOrElse("?")
            val reason = a.get("reason").flatMap(_.strOpt).getOrElse("?")
            s"$wid ($reason)"
          }
          s"$base\n      attention: ${flags.mkString(", ")}"
      }
      (header +: lines).mkString("\n")

  private def renderWorkers(instance: Instance, snapshot: ujson.Value): String =
    val obj = snapshot.objOpt.getOrElse(ujson.Obj().obj)
    val workers = obj.get("workers").flatMap(_.arrOpt).getOrElse(scala.collection.mutable.ArrayBuffer.empty)
    if workers.isEmpty then s"forge worker list: no workers in instance '${instance.name.value}'"
    else
      val header = s"forge workers in '${instance.name.value}' (${workers.size}):"
      val lines = workers.toVector.flatMap(_.objOpt).map { w =>
        val id = w.get("workerId").flatMap(_.strOpt).getOrElse("?")
        val feature = w.get("feature").flatMap(_.strOpt).getOrElse("?")
        val st = w.get("status").flatMap(_.strOpt).getOrElse("?")
        val ws = w.get("workstreamId").flatMap(_.strOpt).getOrElse("-")
        val live = w.get("live").flatMap(_.boolOpt).getOrElse(false)
        val pid = w.get("pid").flatMap(_.numOpt).map(_.toLong.toString).getOrElse("-")
        val liveness = if live then s"live(pid=$pid)" else "not-live"
        s"  - $id  workstream=$ws  feature=$feature  status=$st  $liveness"
      }
      (header +: lines).mkString("\n")

  // --- shared ----------------------------------------------------------------

  /** Resolve the target instance (explicit `--instance`, else the sole instance) and run `body`. Resolution diagnostics
    * + exit codes come from the shared [[InstanceResolver]], exactly as [[DaemonCommands]] uses it.
    */
  private def withDaemon(store: InstanceStore, instance: Option[InstanceName], label: String)(
      body: Instance => IO[ExitCode]
  ): IO[ExitCode] =
    InstanceResolver.resolve(store, instance, label).flatMap {
      case Left(code) => IO.pure(code)
      case Right(resolved) => body(resolved)
    }

  private def notRunning(label: String, instance: Instance): IO[ExitCode] =
    Console[IO]
      .errorln(
        s"forge $label: no daemon appears to be running for instance '${instance.name.value}' " +
          s"(no socket at ${instance.socketFile}). Start one with `forge daemon start`."
      )
      .as(ExitCode(1))
