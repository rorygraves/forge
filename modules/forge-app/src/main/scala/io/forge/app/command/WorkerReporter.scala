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

  /** `broker-credentials` — request the short-lived, host-isolated credentials (O6, Task 4.3.3) this worker needs to
    * operate on `repo`: the env entries (a scoped `gh` token + any configured agent auth) the worker sets in its
    * **own** process environment before driving git/connectors inside the container. A broker refusal (a required
    * secret unconfigured) is raised as an `IO` error so the worker's degrade path reports it. The optional-credential
    * `missing` list is dropped here (the worker fails loudly downstream if its driver's auth is absent); only the env
    * map matters to the caller.
    */
  def brokerCredentials(repo: String): IO[Map[String, String]]

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

    def brokerCredentials(repo: String): IO[Map[String, String]] =
      nextId.flatMap { id =>
        DaemonClient
          .call(
            socket,
            JsonRpc.Request(id, "broker-credentials", ujson.Obj("workerId" -> workerId, "repo" -> repo))
          )
          .flatMap {
            case JsonRpc.Response.Success(_, body) => IO.fromEither(parseCredentials(body))
            case JsonRpc.Response.Failure(_, err) =>
              IO.raiseError(new RuntimeException(s"broker-credentials failed: ${err.message}"))
          }
      }

  /** Decode the `broker-credentials` success body (`{env: {<key>: <secret>}, missing: [...]}`) into the env map the
    * worker injects. A non-object `env`, or a non-string value, is a contract violation surfaced as a `Left` (the
    * caller raises it) rather than silently dropping a credential. The `missing` field is ignored here.
    */
  private[command] def parseCredentials(body: ujson.Value): Either[Throwable, Map[String, String]] =
    body.objOpt.flatMap(_.get("env")) match
      case Some(env) =>
        env.objOpt match
          case Some(obj) =>
            obj.toSeq.foldLeft[Either[Throwable, Map[String, String]]](Right(Map.empty)) {
              case (Right(acc), (k, v)) =>
                v.strOpt match
                  case Some(s) => Right(acc + (k -> s))
                  case None => Left(new RuntimeException(s"broker-credentials: non-string value for env key '$k'"))
              case (left, _) => left
            }
          case None => Left(new RuntimeException("broker-credentials: 'env' is not an object"))
      case None => Left(new RuntimeException("broker-credentials: response missing 'env'"))

  /** Raise on a JSON-RPC failure response so the caller's `handleErrorWith` reports it; a success is discarded. */
  private def expectOk(call: IO[JsonRpc.Response], method: String): IO[Unit] =
    call.flatMap {
      case _: JsonRpc.Response.Success => IO.unit
      case JsonRpc.Response.Failure(_, err) =>
        IO.raiseError(new RuntimeException(s"$method failed: ${err.message}"))
    }
