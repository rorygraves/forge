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
