package io.forge.instance

import io.forge.core.{FeatureId, WorkstreamId}

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
    // A 4.1-style registered worker is not a spawned process: no pid ⇒ not live.
    assertEquals(workers.head("live").bool, false)
    assertEquals(json("workstreams").arr.size, 0)

  // --- Task 4.2.4 — workstreams + spawn/exit + attention ---------------------

  private val ws1 = WorkstreamId("ws-1")

  test("workstream.created seeds a Planning workstream with no workers"):
    val state = RebuildInstanceState.fold(Vector(rec(0, InstanceEvent.WorkstreamCreated(ws1, "add auth"))))
    assertEquals(state.workstream(ws1), Some(Workstream(ws1, "add auth", WorkstreamStatus.Planning, Vector.empty)))

  test("workstream.status advances the lifecycle, last write wins"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws1, "add auth")),
        rec(1, InstanceEvent.WorkstreamStatusChanged(ws1, WorkstreamStatus.Active)),
        rec(2, InstanceEvent.WorkstreamStatusChanged(ws1, WorkstreamStatus.Done))
      )
    )
    assertEquals(state.workstream(ws1).map(_.status), Some(WorkstreamStatus.Done))

  test("workstream.status for an uncreated workstream is dropped"):
    val state = RebuildInstanceState.fold(
      Vector(rec(0, InstanceEvent.WorkstreamStatusChanged(ws1, WorkstreamStatus.Active)))
    )
    assertEquals(state.workstreams, Vector.empty)

  test("worker.spawned seeds the worker with its spawn fields and joins the workstream ordering"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws1, "add auth")),
        rec(1, InstanceEvent.WorkerSpawned("w1", ws1, "/repos/szork", feature, "/clone/w1", 4242L))
      )
    )
    val w = state.worker("w1").getOrElse(fail("w1 should exist"))
    assertEquals(w.workstreamId, Some(ws1))
    assertEquals(w.checkoutRoot, Some("/clone/w1"))
    assertEquals(w.pid, Some(4242L))
    assertEquals(w.status, WorkerRecord.RegisteredStatus)
    assert(w.live, "a spawned worker with a pid and no exit is live")
    assertEquals(state.workstream(ws1).map(_.workers), Some(Vector("w1")))

  test("worker.exited clears liveness but keeps the record"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws1, "add auth")),
        rec(1, InstanceEvent.WorkerSpawned("w1", ws1, "/repos/szork", feature, "/clone/w1", 4242L)),
        rec(2, InstanceEvent.WorkerExited("w1", 0))
      )
    )
    val w = state.worker("w1").getOrElse(fail("w1 should exist"))
    assertEquals(w.exitCode, Some(0))
    assert(!w.live, "an exited worker is not live")

  test("a re-spawn clears a prior exitCode and does not double-append to the ordering"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws1, "add auth")),
        rec(1, InstanceEvent.WorkerSpawned("w1", ws1, "/repos/szork", feature, "/clone/w1", 1L)),
        rec(2, InstanceEvent.WorkerExited("w1", 1)),
        rec(3, InstanceEvent.WorkerSpawned("w1", ws1, "/repos/szork", feature, "/clone/w1", 2L))
      )
    )
    val w = state.worker("w1").getOrElse(fail("w1 should exist"))
    assertEquals(w.pid, Some(2L))
    assertEquals(w.exitCode, None)
    assert(w.live)
    assertEquals(state.workstream(ws1).map(_.workers), Some(Vector("w1")))

  test("attention projects only workers in a human-needing state, in workstream order"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws1, "add auth")),
        rec(1, InstanceEvent.WorkerSpawned("w1", ws1, "/repo", feature, "/clone/w1", 1L)),
        rec(2, InstanceEvent.WorkerSpawned("w2", ws1, "/repo", FeatureId("other"), "/clone/w2", 2L)),
        rec(3, InstanceEvent.WorkerSpawned("w3", ws1, "/repo", FeatureId("third"), "/clone/w3", 3L)),
        rec(4, InstanceEvent.WorkerStatus("w1", "PieceImplementing")), // progressing — no attention
        rec(5, InstanceEvent.WorkerStatus("w2", "NeedsHumanIntervention")),
        rec(6, InstanceEvent.WorkerStatus("w3", "PieceAwaitingMerge"))
      )
    )
    assertEquals(
      state.attention(ws1),
      Vector(
        WorkerAttention("w2", AttentionReason.NeedsHumanIntervention),
        WorkerAttention("w3", AttentionReason.MergeGate)
      )
    )

  test("toStatusJson renders workstreams with their attention projection"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws1, "add auth")),
        rec(1, InstanceEvent.WorkstreamStatusChanged(ws1, WorkstreamStatus.Active)),
        rec(2, InstanceEvent.WorkerSpawned("w1", ws1, "/repo", feature, "/clone/w1", 7L)),
        rec(3, InstanceEvent.WorkerStatus("w1", "DesignNeedsHumanInput"))
      )
    )
    val ws = state.toStatusJson("workstreams").arr
    assertEquals(ws.size, 1)
    assertEquals(ws.head("workstreamId").str, "ws-1")
    assertEquals(ws.head("status").str, "Active")
    assertEquals(ws.head("workers").arr.map(_.str).toVector, Vector("w1"))
    val attention = ws.head("attention").arr
    assertEquals(attention.size, 1)
    assertEquals(attention.head("workerId").str, "w1")
    assertEquals(attention.head("reason").str, "driver-question")
    // The worker also carries its spawn fields in the worker list.
    val w = state.toStatusJson("workers").arr.head
    assertEquals(w("workstreamId").str, "ws-1")
    assertEquals(w("pid").num.toLong, 7L)
    assertEquals(w("live").bool, true)

  test("the new event variants round-trip through (kindOf, payloadOf) → decode"):
    val events: Vector[InstanceEvent] = Vector(
      InstanceEvent.WorkstreamCreated(ws1, "add auth"),
      InstanceEvent.WorkstreamStatusChanged(ws1, WorkstreamStatus.Active),
      InstanceEvent.WorkerSpawned("w1", ws1, "/repos/szork", feature, "/clone/w1", 4242L),
      InstanceEvent.WorkerExited("w1", 0)
    )
    events.foreach { e =>
      assertEquals(InstanceEvent.decode(InstanceEvent.kindOf(e), InstanceEvent.payloadOf(e)), Some(e))
    }

  test("an unknown workstream.status name decodes to None (forward-compat skip)"):
    val rec0 = InstanceLogRecord(
      0,
      at0,
      InstanceEvent.WorkstreamStatusKind,
      ujson.Obj("workstreamId" -> "ws-1", "status" -> "Hibernating")
    )
    assertEquals(rec0.event, None)
