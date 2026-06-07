package io.forge.instance

import io.forge.core.{FeatureId, WorkstreamId}

import java.time.Instant

/** Task 4.3.5 (B2) — the budget-reservation half of the instance-log fold: the four `budget.*` event codecs round-trip,
  * grants/finalizes maintain the outstanding-reservation table, and the exported `cost.update` fan-in accumulates the
  * committed-spend totals (per-instance + per-workstream). Pure fold over fixture records, no I/O.
  */
class BudgetReservationSuite extends munit.FunSuite:

  private val at0 = Instant.parse("2026-06-06T00:00:00Z")
  private val feature = FeatureId("feat")
  private val ws = WorkstreamId("ws-1")

  private def rec(seq: Long, event: InstanceEvent): InstanceLogRecord =
    InstanceEvent.toDraft(event).stamp(seq, at0.plusSeconds(seq))

  /** An exported `cost.update` action (the §19 wire shape the worker feed exports) carrying a per-turn `usd` delta. */
  private def costUpdate(usd: Double): ujson.Value =
    ujson.Obj("kind" -> "cost.update", "payload" -> ujson.Obj("usd" -> usd))

  /** A `seq`-stamped exported `cost.update` — the real feed-export shape (the full per-feature `Action` carries `seq`),
    * used to exercise the at-least-once dedup (Task 4.3.6).
    */
  private def costUpdateSeq(seq: Long, usd: Double): ujson.Value =
    // `seq` as a JSON number — the real `writeJs[Action]` shape (a bare `Long` would render as a string here).
    ujson.Obj("seq" -> ujson.Num(seq.toDouble), "kind" -> "cost.update", "payload" -> ujson.Obj("usd" -> usd))

  // --- codec round-trip ------------------------------------------------------

  test("the four budget.* events round-trip through (decode ∘ payloadOf)"):
    val events: Vector[InstanceEvent] = Vector(
      InstanceEvent.BudgetReserve("w1", Some(ws), BigDecimal(8)),
      InstanceEvent.BudgetReserve("w1", None, BigDecimal(8)),
      InstanceEvent.BudgetGrant("w1", Some(ws), "res-1", BigDecimal(8)),
      InstanceEvent.BudgetRefuse("w1", Some(ws), BigDecimal(8), "cap reached"),
      InstanceEvent.BudgetFinalize("w1", "res-1", BigDecimal(3.5))
    )
    events.foreach { e =>
      val decoded = InstanceEvent.decode(InstanceEvent.kindOf(e), InstanceEvent.payloadOf(e))
      assertEquals(decoded, Some(e), s"round-trip failed for $e")
    }

  // --- reservation table -----------------------------------------------------

  test("budget.grant records an outstanding reservation; budget.reserve/refuse are audit-only"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repo", feature)),
        rec(1, InstanceEvent.BudgetReserve("w1", Some(ws), BigDecimal(8))),
        rec(2, InstanceEvent.BudgetGrant("w1", Some(ws), "res-1", BigDecimal(8))),
        rec(3, InstanceEvent.BudgetReserve("w2", Some(ws), BigDecimal(5))),
        rec(4, InstanceEvent.BudgetRefuse("w2", Some(ws), BigDecimal(5), "cap reached"))
      )
    )
    assertEquals(state.reservations.keySet, Set("w1"))
    assertEquals(state.outstandingUsd(), BigDecimal(8))
    assertEquals(state.outstandingUsdForWorkstream(ws), BigDecimal(8))
    // excluding the holder zeroes its own contribution (the re-reserve case)
    assertEquals(state.outstandingUsd(excludingWorker = Some("w1")), BigDecimal(0))

  test("a fresh grant for the same worker replaces the prior reservation (no leak across sessions)"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repo", feature)),
        rec(1, InstanceEvent.BudgetGrant("w1", Some(ws), "res-1", BigDecimal(8))),
        rec(2, InstanceEvent.BudgetGrant("w1", Some(ws), "res-2", BigDecimal(8)))
      )
    )
    assertEquals(state.reservations.size, 1)
    assertEquals(state.reservations("w1").reservationId, "res-2")
    assertEquals(state.outstandingUsd(), BigDecimal(8))

  test("budget.finalize clears the matching reservation; a stale reservationId is a no-op"):
    val base = Vector(
      rec(0, InstanceEvent.WorkerRegistered("w1", "/repo", feature)),
      rec(1, InstanceEvent.BudgetGrant("w1", Some(ws), "res-1", BigDecimal(8)))
    )
    val finalized =
      RebuildInstanceState.fold(base :+ rec(2, InstanceEvent.BudgetFinalize("w1", "res-1", BigDecimal(3))))
    assertEquals(finalized.reservations, Map.empty[String, BudgetReservation])
    val stale = RebuildInstanceState.fold(base :+ rec(2, InstanceEvent.BudgetFinalize("w1", "res-OLD", BigDecimal(3))))
    assertEquals(stale.reservations.keySet, Set("w1"))

  test("worker.exited releases any reservation the worker still held"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repo", feature)),
        rec(1, InstanceEvent.BudgetGrant("w1", Some(ws), "res-1", BigDecimal(8))),
        rec(2, InstanceEvent.WorkerExited("w1", 0))
      )
    )
    assertEquals(state.reservations, Map.empty[String, BudgetReservation])

  // --- cost.update fan-in ----------------------------------------------------

  test("exported cost.update fans its usd delta into committed totals (instance + workstream)"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws, "goal")),
        rec(1, InstanceEvent.WorkerSpawned("w1", ws, "/repo", feature, "/clone", pid = Some(42))),
        rec(2, InstanceEvent.WorkerEvent("w1", costUpdate(2.0))),
        rec(3, InstanceEvent.WorkerEvent("w1", costUpdate(3.5))),
        // a non-cost event does not move committed
        rec(4, InstanceEvent.WorkerEvent("w1", ujson.Obj("kind" -> "fsm.transition")))
      )
    )
    assertEquals(state.committedUsd, BigDecimal(5.5))
    assertEquals(state.committedUsdForWorkstream(ws), BigDecimal(5.5))
    // the feed tail still carries all three exported events
    assertEquals(state.worker("w1").map(_.events.size), Some(3))

  test("a replayed cost.update (seq <= high-water) is dropped whole — committed spend is not double-counted"):
    // The at-least-once feed export re-sends already-folded actions on a worker resume (the exporter watermark
    // re-seeds at -1 on every worker process start) or on a crash between the daemon ack and the watermark set.
    // The fold dedups on the exported per-feature `seq`, so a re-export neither re-appends the feed nor re-commits.
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws, "goal")),
        rec(1, InstanceEvent.WorkerSpawned("w1", ws, "/repo", feature, "/clone", pid = Some(42))),
        rec(2, InstanceEvent.WorkerEvent("w1", costUpdateSeq(0, 2.0))),
        rec(3, InstanceEvent.WorkerEvent("w1", costUpdateSeq(1, 3.5))),
        // worker resumes → re-exports its whole on-disk log from seq 0: both are replays and must be dropped
        rec(4, InstanceEvent.WorkerEvent("w1", costUpdateSeq(0, 2.0))),
        rec(5, InstanceEvent.WorkerEvent("w1", costUpdateSeq(1, 3.5))),
        // a genuinely new turn (seq 2) still folds
        rec(6, InstanceEvent.WorkerEvent("w1", costUpdateSeq(2, 1.0)))
      )
    )
    assertEquals(state.committedUsd, BigDecimal(6.5))
    assertEquals(state.committedUsdForWorkstream(ws), BigDecimal(6.5))
    // the feed tail carries each distinct action exactly once (seq 0, 1, 2) — no duplicate entries
    assertEquals(state.worker("w1").map(_.events.size), Some(3))
    assertEquals(state.worker("w1").flatMap(_.lastExportedSeq), Some(2L))

  test("exportedSeq reads the seq from a REAL writeJs[Action] export (pins the production serialization shape)"):
    // Guard against deriving the seq shape from intuition rather than reality (upickle can render a `Long` as a JSON
    // string in some construction paths, which would silently disable the dedup). The exporter uses exactly this
    // `writeJs[Action]`, so assert exportedSeq reads the seq it actually produces — and that a re-export dedups.
    val action = io.forge.core.log.Action(
      seq = 7L,
      at = at0,
      feature = feature,
      piece = None,
      actor = Some("driver"),
      role = None,
      kind = "cost.update",
      payload = ujson.Obj("usd" -> 1.25)
    )
    val exported = upickle.default.writeJs[io.forge.core.log.Action](action)
    assertEquals(InstanceEvent.exportedSeq(exported), Some(7L))
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repo", feature)),
        rec(1, InstanceEvent.WorkerEvent("w1", exported)),
        rec(2, InstanceEvent.WorkerEvent("w1", exported)) // re-export of the same action → deduped
      )
    )
    assertEquals(state.committedUsd, BigDecimal(1.25))
    assertEquals(state.worker("w1").flatMap(_.lastExportedSeq), Some(7L))

  test("a seqless exported event is applied (degenerate shape) but does not advance the high-water mark"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repo", feature)),
        rec(1, InstanceEvent.WorkerEvent("w1", costUpdate(2.0))),
        rec(2, InstanceEvent.WorkerEvent("w1", costUpdate(3.0)))
      )
    )
    assertEquals(state.committedUsd, BigDecimal(5.0))
    assertEquals(state.worker("w1").flatMap(_.lastExportedSeq), None)

  test("cost.update for a worker with no workstream counts only against the instance total"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkerRegistered("w1", "/repo", feature)),
        rec(1, InstanceEvent.WorkerEvent("w1", costUpdate(4.0)))
      )
    )
    assertEquals(state.committedUsd, BigDecimal(4.0))
    assertEquals(state.committedByWorkstream, Map.empty[String, BigDecimal])

  // --- status JSON -----------------------------------------------------------

  test("status JSON exposes committed + outstanding totals (instance and per-workstream)"):
    val state = RebuildInstanceState.fold(
      Vector(
        rec(0, InstanceEvent.WorkstreamCreated(ws, "goal")),
        rec(1, InstanceEvent.WorkerSpawned("w1", ws, "/repo", feature, "/clone", pid = Some(42))),
        rec(2, InstanceEvent.BudgetGrant("w1", Some(ws), "res-1", BigDecimal(8))),
        rec(3, InstanceEvent.WorkerEvent("w1", costUpdate(2.0)))
      )
    )
    val json = state.toStatusJson
    assertEquals(json.obj("committedUsd").num, 2.0)
    assertEquals(json.obj("outstandingUsd").num, 8.0)
    val wsJson = json.obj("workstreams").arr.head.obj
    assertEquals(wsJson("committedUsd").num, 2.0)
    assertEquals(wsJson("outstandingUsd").num, 8.0)
