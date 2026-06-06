package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.cli.WorkerCommand
import io.forge.daemon.{DaemonClient, JsonRpc}
import io.forge.instance.{FileInstanceStore, Instance, InstanceError}

/** Task 4.2.1 — the hidden, daemon-spawned **worker** entrypoint (`forge worker`), the forge-app half of the B4
  * worker-process boundary spike.
  *
  * The daemon supervisor (4.2.5) spawns this as a real child OS process via the `forge-daemon` `WorkerSpawner`, passing
  * the instance, the worker id it was assigned, the repo, and the feature on argv. The worker then **phones home** to
  * the instance's Unix-domain socket over the existing JSON-RPC transport: it registers itself, reports a status, and
  * exports a couple of events (B3 event export). For the 4.2.1 spike that is the whole body — it proves a real
  * forge-spawned process can reach the daemon and feed the instance log. The **real** `Orchestrator` feature loop
  * (build over the isolated clone's re-rooted `ForgePaths`, export each appended `Action` as a `worker.event`) lands in
  * Task 4.2.3; this scaffolds the connection so that drop-in has a proven channel.
  *
  * Instance-scoped like [[DaemonCommands]]: `Main` routes here without config / assets / a per-checkout lock. The
  * instance is resolved by its **explicit** name (the daemon never relies on the sole-instance fallback when spawning).
  */
object WorkerCommands:

  /** The status the spike reports after registering — a placeholder FSM-state name (the real loop reports the live
    * `FsmState` name in 4.2.3).
    */
  private val SpikeStatus: String = "Refining"

  def run(home: os.Path, command: WorkerCommand): IO[ExitCode] =
    new FileInstanceStore(home).load(command.instance).flatMap {
      case Left(InstanceError.NoSuchInstance(name)) =>
        Console[IO]
          .errorln(s"forge worker: no such instance '${name.value}' (the daemon should have created it).")
          .as(ExitCode(1))
      case Left(err) =>
        Console[IO].errorln(s"forge worker: instance error: $err").as(ExitCode(1))
      case Right(instance) => phoneHome(instance, command)
    }

  /** Connect to the daemon socket and export the worker's lifecycle (register → status → a couple of events). Uses
    * [[DaemonClient.callWithRetry]] for the register so a worker that out-races the daemon binding the socket retries
    * briefly rather than failing; subsequent calls reuse the plain [[DaemonClient.call]] (the daemon is up by then). A
    * connect failure means no daemon is listening (exit 1); a JSON-RPC failure response is surfaced (exit 1).
    */
  private def phoneHome(instance: Instance, command: WorkerCommand): IO[ExitCode] =
    val socket = instance.socketFile
    val flow =
      for
        _ <- expectOk(
          DaemonClient.callWithRetry(
            socket,
            JsonRpc.Request(
              1L,
              "register-worker",
              ujson.Obj(
                "workerId" -> command.workerId,
                "repo" -> command.repo,
                "feature" -> command.feature.value
              )
            )
          ),
          "register-worker"
        )
        _ <- expectOk(
          DaemonClient.call(
            socket,
            JsonRpc.Request(2L, "worker-status", ujson.Obj("workerId" -> command.workerId, "status" -> SpikeStatus))
          ),
          "worker-status"
        )
        _ <- exportEvent(socket, 3L, command.workerId, "spawned")
        _ <- exportEvent(socket, 4L, command.workerId, "heartbeat")
        _ <- Console[IO].println(
          s"forge worker '${command.workerId}': registered with daemon for instance " +
            s"'${command.instance.value}' (feature ${command.feature.value})."
        )
      yield ExitCode.Success
    flow.handleErrorWith { _ =>
      Console[IO]
        .errorln(
          s"forge worker '${command.workerId}': could not reach the daemon at $socket " +
            s"(is `forge daemon start --instance ${command.instance.value}` running?)."
        )
        .as(ExitCode(1))
    }

  /** Export one synthetic per-feature event for the spike — a minimal `Action`-shaped object the daemon carries
    * verbatim into the worker's exported-feed tail (the real loop exports the actual appended `Action` JSON in 4.2.3).
    */
  private def exportEvent(socket: os.Path, id: Long, workerId: String, kind: String): IO[Unit] =
    expectOk(
      DaemonClient.call(
        socket,
        JsonRpc.Request(
          id,
          "worker-event",
          ujson.Obj("workerId" -> workerId, "event" -> ujson.Obj("seq" -> ujson.Num((id - 3).toDouble), "kind" -> kind))
        )
      ),
      "worker-event"
    )

  /** Raise on a JSON-RPC failure response so [[phoneHome]]'s `handleErrorWith` reports it; a success is discarded. */
  private def expectOk(call: IO[JsonRpc.Response], method: String): IO[Unit] =
    call.flatMap {
      case _: JsonRpc.Response.Success => IO.unit
      case JsonRpc.Response.Failure(_, err) =>
        IO.raiseError(new RuntimeException(s"$method failed: ${err.message}"))
    }
