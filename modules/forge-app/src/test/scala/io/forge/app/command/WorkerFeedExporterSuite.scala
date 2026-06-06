package io.forge.app.command

import cats.effect.{IO, Ref, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.{FeatureId, InstanceName, PieceId, PrNumber}
import io.forge.core.fsm.FsmState
import io.forge.core.log.{ActionDraft, FileActionLog}
import io.forge.core.paths.ForgePaths
import io.forge.daemon.{Daemon, DaemonState}
import io.forge.instance.{FileInstanceLog, FileInstanceStore, Instance, InstanceState, RebuildInstanceState}
import munit.CatsEffectSuite

import java.time.Instant
import java.util.UUID

/** Task 4.2.3 — the worker's feed exporter ([[WorkerFeedExporter]]): a read-side tail of the per-feature action log
  * that pushes each new `Action` to the daemon as a `worker-event` + a `worker-status` on each `fsm.transition`.
  *
  * Driven against a **real** in-process daemon (the `served` idiom from `WorkerCommandSuite`) over the real
  * [[WorkerReporter.daemon]] RPC seam, so the assertion is end-to-end: write actions to a log file, drain, and rebuild
  * the worker's record from the instance log alone.
  */
class WorkerFeedExporterSuite extends CatsEffectSuite:

  private val featureId: FeatureId = FeatureId.fromString("add-feature").toOption.get

  /** A short `/tmp`-rooted home (macOS `sun_path` ≤ 104B) with a created instance, plus a separate checkout dir whose
    * `ForgePaths` the action log is written through. Cleaned up after.
    */
  private val fixture: Resource[IO, (Instance, ForgePaths)] =
    Resource
      .make {
        val home = os.Path("/tmp") / s"fe-${UUID.randomUUID().toString.take(8)}"
        new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
          case Right(inst) =>
            val checkout = home / "checkout"
            IO.blocking(os.makeDir.all(checkout)).as((home, inst, new ForgePaths(checkout, home)))
          case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
        }
      } { case (home, _, _) => IO.blocking(os.remove.all(home)).void }
      .map { case (_, inst, paths) => (inst, paths) }

  private def served[A](inst: Instance)(body: => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.socketFile, state, shutdown)
        .background
        .use(_ => body.guarantee(shutdown.complete(()).void))
    yield result

  private def reporterFor(inst: Instance): IO[WorkerReporter] =
    WorkerReporter.daemon(inst.socketFile, "w1").flatTap(_.register("/repo", featureId))

  /** Three drafts: a non-transition action, then two transitions — a singleton `to` and a parameterized `to` — so both
    * the event export and the two state-name extraction branches are exercised.
    */
  private def seedDrafts: Vector[ActionDraft] =
    val poll = ActionDraft(featureId, None, Some("harness"), None, "gh.poll", ujson.Obj("status" -> "pending"))
    val toSpec = transition(FsmState.Drafting, FsmState.InteractiveSpec)
    val toRefining =
      transition(FsmState.InteractiveSpec, FsmState.Refining(PieceId("p1"), Some(PrNumber(7)), Instant.EPOCH))
    Vector(poll, toSpec, toRefining)

  private def transition(from: FsmState, to: FsmState): ActionDraft =
    ActionDraft(
      featureId,
      None,
      None,
      None,
      "fsm.transition",
      ujson.Obj("from" -> upickle.default.writeJs[FsmState](from), "to" -> upickle.default.writeJs[FsmState](to))
    )

  private def rebuiltWorker(inst: Instance): IO[InstanceState] =
    FileInstanceLog(inst).flatMap(_.replay).map(RebuildInstanceState.fold)

  test("drainOnce exports each new action as a worker-event + a worker-status per fsm.transition") {
    fixture.use { case (inst, paths) =>
      served(inst) {
        for
          log <- FileActionLog(paths)
          _ <- log.appendAll(featureId, seedDrafts)
          reporter <- reporterFor(inst)
          watermark <- Ref.of[IO, Long](WorkerFeedExporter.InitialWatermark)
          _ <- WorkerFeedExporter.drainOnce(paths.featureLog(featureId), reporter, watermark)
          high <- watermark.get
          rebuilt <- rebuiltWorker(inst)
        yield
          val w = rebuilt.worker("w1").getOrElse(fail("worker w1 missing"))
          // All three actions exported, carried verbatim (their §19 `kind` survives the round-trip).
          assertEquals(w.events.map(_.obj("kind").str), Vector("gh.poll", "fsm.transition", "fsm.transition"))
          // The latest worker-status is the last transition's `to` state name (parameterized → bare case name).
          assertEquals(w.status, "Refining")
          // Watermark advanced to the highest exported seq (0-based; 3 appended ⇒ 0,1,2).
          assertEquals(high, 2L)
      }
    }
  }

  test("drainOnce is idempotent: a second drain with no new appends exports nothing") {
    fixture.use { case (inst, paths) =>
      served(inst) {
        for
          log <- FileActionLog(paths)
          _ <- log.appendAll(featureId, seedDrafts)
          reporter <- reporterFor(inst)
          watermark <- Ref.of[IO, Long](WorkerFeedExporter.InitialWatermark)
          _ <- WorkerFeedExporter.drainOnce(paths.featureLog(featureId), reporter, watermark)
          _ <- WorkerFeedExporter.drainOnce(paths.featureLog(featureId), reporter, watermark)
          rebuilt <- rebuiltWorker(inst)
        yield assertEquals(rebuilt.worker("w1").map(_.events.size), Some(3))
      }
    }
  }

  test("drainOnce picks up only the appends since the last watermark") {
    fixture.use { case (inst, paths) =>
      served(inst) {
        for
          log <- FileActionLog(paths)
          _ <- log.appendAll(featureId, seedDrafts.take(1))
          reporter <- reporterFor(inst)
          watermark <- Ref.of[IO, Long](WorkerFeedExporter.InitialWatermark)
          _ <- WorkerFeedExporter.drainOnce(paths.featureLog(featureId), reporter, watermark)
          _ <- log.appendAll(featureId, seedDrafts.drop(1))
          _ <- WorkerFeedExporter.drainOnce(paths.featureLog(featureId), reporter, watermark)
          rebuilt <- rebuiltWorker(inst)
        yield
          val w = rebuilt.worker("w1").getOrElse(fail("worker w1 missing"))
          assertEquals(w.events.size, 3)
          assertEquals(w.status, "Refining")
      }
    }
  }

  test("readActions tolerates a partially-flushed trailing line (stops at the valid prefix)") {
    fixture.use { case (_, paths) =>
      for
        log <- FileActionLog(paths)
        _ <- log.appendAll(featureId, seedDrafts.take(2))
        path = paths.featureLog(featureId)
        _ <- IO.blocking(os.write.append(path, "{\"seq\": 99, \"this is half a line"))
        actions <- WorkerFeedExporter.readActions(path)
      yield assertEquals(actions.map(_.seq), Vector(0L, 1L))
    }
  }

  test("transitionStatus is None for a non-transition action, the `to` name for a transition") {
    fixture.use { case (_, paths) =>
      for
        log <- FileActionLog(paths)
        stamped <- log.appendAll(featureId, seedDrafts)
      yield
        assertEquals(WorkerFeedExporter.transitionStatus(stamped(0)), None) // gh.poll
        assertEquals(WorkerFeedExporter.transitionStatus(stamped(1)), Some("InteractiveSpec")) // singleton `to`
        assertEquals(WorkerFeedExporter.transitionStatus(stamped(2)), Some("Refining")) // parameterized `to`
    }
  }
