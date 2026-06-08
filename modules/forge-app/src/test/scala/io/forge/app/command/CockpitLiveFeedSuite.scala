package io.forge.app.command

import cats.effect.{IO, Resource}
import cats.effect.kernel.{Deferred, Ref}
import io.forge.core.InstanceName
import io.forge.daemon.{Daemon, DaemonClient, DaemonState, JsonRpc}
import io.forge.instance.{FileInstanceStore, Instance}
import io.forge.tui.CockpitSnapshot
import munit.CatsEffectSuite

import java.util.UUID
import scala.concurrent.duration.*

/** Slice 4.4 Task 4.4.2 — [[CockpitLiveFeed]] against a **real daemon over a real socket** (mirroring
  * `DaemonWorkerSubscribeSuite`'s `instance` / `served` harness, per the repo's "I/O contracts need integration tests"
  * discipline — a socket+fiber bridge can't be proven with a fake).
  *
  * Proves the three exit-criterion behaviours of the live feed: (1) a fleet mutation recorded against the daemon
  * refreshes the shared snapshot the cockpit renders; (2) cancelling the feed (the operator detaching) leaves the
  * daemon running — the cockpit is a read-only client (§6.3.1); (3) the feed reconnects on drop, resuming refreshes
  * against a restarted daemon.
  */
class CockpitLiveFeedSuite extends CatsEffectSuite:

  /** A short `/tmp`-rooted instance (macOS caps `sun_path` at 104 bytes), created on disk and cleaned up after. */
  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"clf-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  /** Boot a daemon serving its socket for the body, tearing the serve down (via the shutdown signal) afterwards. */
  private def served[A](inst: Instance)(body: DaemonState => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.portFile, state, shutdown)
        .background
        .use(_ => body(state).guarantee(shutdown.complete(()).void))
    yield result

  private def registerReq(id: Long, workerId: String, repo: String, feature: String): JsonRpc.Request =
    JsonRpc.Request(id, "register-worker", ujson.Obj("workerId" -> workerId, "repo" -> repo, "feature" -> feature))

  private def statusReq(id: Long, workerId: String, status: String): JsonRpc.Request =
    JsonRpc.Request(id, "worker-status", ujson.Obj("workerId" -> workerId, "status" -> status))

  /** Poll `latest` until `p` holds, or fail after `attempts` × `delay` (~5s — the happy path resolves in well under a
    * second once the debounced refresh lands).
    */
  private def waitFor(
      latest: Ref[IO, Option[CockpitSnapshot]],
      attempts: Int = 200,
      delay: FiniteDuration = 25.millis
  )(p: CockpitSnapshot => Boolean): IO[CockpitSnapshot] =
    latest.get.flatMap {
      case Some(s) if p(s) => IO.pure(s)
      case _ if attempts <= 0 => IO.raiseError(new AssertionError("cockpit snapshot predicate not met in time"))
      case _ => IO.sleep(delay) *> waitFor(latest, attempts - 1, delay)(p)
    }

  // Short feed timings so the suite stays deterministic and fast (default-on, <60s).
  private def follow(inst: Instance, latest: Ref[IO, Option[CockpitSnapshot]]) =
    CockpitLiveFeed.follow(inst, latest, settle = 50.millis, reconnectDelay = 100.millis)

  test("a recorded fleet mutation refreshes the shared cockpit snapshot off the subscribe feed") {
    instance.use { inst =>
      served(inst) { _ =>
        for
          latest <- Ref.of[IO, Option[CockpitSnapshot]](None)
          found <- follow(inst, latest).background.use { _ =>
            DaemonClient.callWithRetry(inst.portFile, registerReq(1L, "w1", "/repo", "add-feature")) *>
              DaemonClient.callWithRetry(inst.portFile, statusReq(2L, "w1", "Refining")) *>
              waitFor(latest)(_.allWorkers.exists(w => w.workerId == "w1" && w.status == "Refining"))
          }
        yield assert(
          found.allWorkers.exists(w => w.workerId == "w1" && w.status == "Refining"),
          s"expected the live feed to surface w1=Refining, got ${found.allWorkers}"
        )
      }
    }
  }

  test("detaching the feed (cancelling it) leaves the daemon running") {
    instance.use { inst =>
      served(inst) { _ =>
        for
          latest <- Ref.of[IO, Option[CockpitSnapshot]](None)
          // Run the feed long enough to observe a worker, then close the background scope (the operator quitting).
          _ <- follow(inst, latest).background.use { _ =>
            DaemonClient.callWithRetry(inst.portFile, registerReq(1L, "w1", "/repo", "add-feature")) *>
              waitFor(latest)(_.allWorkers.nonEmpty)
          }
          // The feed never wrote and never tore the daemon down: a fresh status round-trip still answers.
          after <- DaemonClient.callWithRetry(inst.portFile, JsonRpc.Request(9L, "status"))
        yield assert(
          after.isInstanceOf[JsonRpc.Response.Success],
          s"expected the daemon to still answer after the feed detached, got $after"
        )
      }
    }
  }

  test("the feed reconnects on drop and resumes refreshing against a restarted daemon") {
    instance.use { inst =>
      Ref.of[IO, Option[CockpitSnapshot]](None).flatMap { latest =>
        follow(inst, latest).background.use { _ =>
          for
            // First daemon: register w1 and confirm the feed surfaces it, then let this daemon stop (scope close).
            _ <- served(inst) { _ =>
              DaemonClient.callWithRetry(inst.portFile, registerReq(1L, "w1", "/repo", "add-feature")) *>
                waitFor(latest)(_.allWorkers.exists(_.workerId == "w1"))
            }
            // The feed's subscribe stream has now dropped; it loops, reconnecting. A second daemon (same instance log)
            // boots and registers w2 — the feed must reconnect and reflect it.
            _ <- served(inst) { _ =>
              DaemonClient.callWithRetry(inst.portFile, registerReq(2L, "w2", "/repo", "second-feature")) *>
                waitFor(latest)(_.allWorkers.exists(_.workerId == "w2"))
            }
          yield ()
        }
      }
    }
  }
