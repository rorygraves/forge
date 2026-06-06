package io.forge.instance

import io.forge.core.FeatureId

import java.time.Instant

/** Task 4.1.2 — the pure instance-log fold. Asserts the projection (boot count, worker upsert, latest status, exported
  * feed tail) plus the no-op skips for unknown / log-level `kind`s. No I/O — fixture records straight into the fold.
  */
class RebuildInstanceStateSuite extends munit.FunSuite:

  private val at0 = Instant.parse("2026-06-06T00:00:00Z")
  private val feature = FeatureId("image-creds-dedup")

  /** Stamp an event with a monotonic seq (the `at` is irrelevant to the fold). */
  private def rec(seq: Long, event: InstanceEvent): InstanceLogRecord =
    InstanceEvent.toDraft(event).stamp(seq, at0.plusSeconds(seq))

  test("empty log folds to the empty state"):
    assertEquals(RebuildInstanceState.fold(Vector.empty), RebuildInstanceState.empty)

  test("daemon.started bumps the boot count once per record"):
    val state = RebuildInstanceState.fold(
      Vector(rec(0, InstanceEvent.DaemonStarted(111)), rec(1, InstanceEvent.DaemonStarted(222)))
    )
    assertEquals(state.bootCount, 2)
    assertEquals(state.workers, Vector.empty)

  test("worker.registered seeds a record with the registered status and an empty feed"):
    val state = RebuildInstanceState.fold(
      Vector(rec(0, InstanceEvent.WorkerRegistered("w1", "/repos/szork", feature)))
    )
    assertEquals(
      state.worker("w1"),
      Some(WorkerRecord("w1", "/repos/szork", feature, WorkerRecord.RegisteredStatus, Vector.empty))
    )

  test("worker.status updates the latest status, last write wins"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repos/szork", feature)),
        rec(1, InstanceEvent.WorkerStatus("w1", "PieceImplementing")),
        rec(2, InstanceEvent.WorkerStatus("w1", "Refining"))
      )
    )
    assertEquals(state.worker("w1").map(_.status), Some("Refining"))

  test("worker.event appends to the worker's exported feed in order"):
    val e0 = ujson.Obj("seq" -> 0, "kind" -> "user.command")
    val e1 = ujson.Obj("seq" -> 1, "kind" -> "fsm.transition")
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repos/szork", feature)),
        rec(1, InstanceEvent.WorkerEvent("w1", e0)),
        rec(2, InstanceEvent.WorkerEvent("w1", e1))
      )
    )
    assertEquals(state.worker("w1").map(_.events), Some(Vector(e0, e1)))

  test("status / event for an unregistered worker are dropped (no record invented)"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerStatus("ghost", "Refining")),
        rec(1, InstanceEvent.WorkerEvent("ghost", ujson.Obj("k" -> "v")))
      )
    )
    assertEquals(state.workers, Vector.empty)

  test("re-registration updates repo/feature but preserves accumulated status + feed"):
    val ev = ujson.Obj("seq" -> 0)
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repos/old", feature)),
        rec(1, InstanceEvent.WorkerStatus("w1", "PieceImplementing")),
        rec(2, InstanceEvent.WorkerEvent("w1", ev)),
        rec(3, InstanceEvent.WorkerRegistered("w1", "/repos/new", FeatureId("other-feature")))
      )
    )
    val w = state.worker("w1").getOrElse(fail("w1 should exist"))
    assertEquals(w.repo, "/repos/new")
    assertEquals(w.feature, FeatureId("other-feature"))
    assertEquals(w.status, "PieceImplementing")
    assertEquals(w.events, Vector(ev))

  test("an unknown / log-level kind decodes to None and is skipped by the fold"):
    val truncationMarker =
      InstanceLogRecord(0, at0, FileInstanceLog.TruncationRecoveryKind, ujson.Obj("kind" -> "log_truncated"))
    val futureBudget = InstanceLogRecord(1, at0, "budget.reserved", ujson.Obj("amountUsd" -> 5))
    assertEquals(truncationMarker.event, None)
    assertEquals(futureBudget.event, None)
    val state = RebuildInstanceState.fold(
      Vector(rec(0, InstanceEvent.DaemonStarted(1)), truncationMarker, futureBudget)
    )
    assertEquals(state.bootCount, 1)
    assertEquals(state.workers, Vector.empty)

  test("toStatusJson summarises boots + workers with an event count"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.DaemonStarted(1)),
        rec(1, InstanceEvent.WorkerRegistered("w1", "/repos/szork", feature)),
        rec(2, InstanceEvent.WorkerStatus("w1", "Refining")),
        rec(3, InstanceEvent.WorkerEvent("w1", ujson.Obj("k" -> "v")))
      )
    )
    val json = state.toStatusJson
    assertEquals(json("bootCount").num.toInt, 1)
    val workers = json("workers").arr
    assertEquals(workers.size, 1)
    assertEquals(workers.head("workerId").str, "w1")
    assertEquals(workers.head("status").str, "Refining")
    assertEquals(workers.head("eventCount").num.toInt, 1)
