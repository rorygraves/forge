package io.forge.app.command

import cats.effect.{IO, Ref}
import io.forge.core.FeatureId
import io.forge.daemon.{DaemonClient, JsonRpc}

/** Task 4.2.3 — the worker→daemon **control seam** (B3 event export): the three RPCs a `forge worker` process uses to
  * phone home to the instance socket — `register-worker`, `worker-status`, `worker-event`.
  *
  * Lifted out of [[WorkerCommands]] (where the 4.2.1 spike inlined them) so the feed exporter ([[WorkerFeedExporter]])
  * and the worker loop ([[WorkerLoop]]) depend on this small interface rather than on `DaemonClient` directly, and a
  * unit test can drive either against a fake reporter or — as the exporter suite does — a real [[DaemonReporter]]
  * pointed at an in-process daemon.
  *
  * The `register` uses [[DaemonClient.callWithRetry]] so a worker that out-races the daemon binding its socket retries
  * briefly; `status` / `event` use the plain [[DaemonClient.call]] (the daemon is up once registration succeeds). A
  * JSON-RPC failure response is raised as an `IO` error so the worker loop's degrade path reports it.
  */
trait WorkerReporter:

  /** `register-worker` — seed the worker's record in the instance log (its repo + assigned feature). */
  def register(repo: String, feature: FeatureId): IO[Unit]

  /** `worker-status` — mirror the worker's latest FSM-state name into the instance log. */
  def status(status: String): IO[Unit]

  /** `worker-event` — export one per-feature action verbatim (the full §19 [[io.forge.core.log.Action]] JSON). */
  def event(event: ujson.Value): IO[Unit]

object WorkerReporter:

  /** Build a [[DaemonReporter]] phoning home over `socket` as `workerId`. Allocates the request-id counter (a fresh
    * connection per call means the id need only be locally unique for response correlation, but a monotonic counter
    * keeps the on-wire trace readable).
    */
  def daemon(socket: os.Path, workerId: String): IO[WorkerReporter] =
    Ref.of[IO, Long](0L).map(new DaemonReporter(socket, workerId, _))

  /** Phones home over the instance Unix-domain socket via [[DaemonClient]]. */
  private final class DaemonReporter(socket: os.Path, workerId: String, ids: Ref[IO, Long]) extends WorkerReporter:

    private def nextId: IO[Long] = ids.updateAndGet(_ + 1L)

    def register(repo: String, feature: FeatureId): IO[Unit] =
      nextId.flatMap { id =>
        expectOk(
          DaemonClient.callWithRetry(
            socket,
            JsonRpc.Request(
              id,
              "register-worker",
              ujson.Obj("workerId" -> workerId, "repo" -> repo, "feature" -> feature.value)
            )
          ),
          "register-worker"
        )
      }

    def status(status: String): IO[Unit] =
      nextId.flatMap { id =>
        expectOk(
          DaemonClient.call(
            socket,
            JsonRpc.Request(id, "worker-status", ujson.Obj("workerId" -> workerId, "status" -> status))
          ),
          "worker-status"
        )
      }

    def event(event: ujson.Value): IO[Unit] =
      nextId.flatMap { id =>
        expectOk(
          DaemonClient.call(
            socket,
            JsonRpc.Request(id, "worker-event", ujson.Obj("workerId" -> workerId, "event" -> event))
          ),
          "worker-event"
        )
      }

  /** Raise on a JSON-RPC failure response so the caller's `handleErrorWith` reports it; a success is discarded. */
  private def expectOk(call: IO[JsonRpc.Response], method: String): IO[Unit] =
    call.flatMap {
      case _: JsonRpc.Response.Success => IO.unit
      case JsonRpc.Response.Failure(_, err) =>
        IO.raiseError(new RuntimeException(s"$method failed: ${err.message}"))
    }
