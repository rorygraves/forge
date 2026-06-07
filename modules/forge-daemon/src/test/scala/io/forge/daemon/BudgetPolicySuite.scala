package io.forge.daemon

import io.forge.core.{FeatureId, WorkstreamId}
import io.forge.instance.{BudgetReservation, RebuildInstanceState, WorkerRecord}

/** Task 4.3.5 (B2) — the pure aggregate-authorization decision: a reservation is granted iff `committed + outstanding +
  * estimate` stays within both the per-workstream and the per-instance cap, counting all *other* workers' outstanding
  * reservations (the holder's own is excluded so a re-reserve is not double-charged).
  */
class BudgetPolicySuite extends munit.FunSuite:

  private val ws = WorkstreamId("ws-1")
  private val feature = FeatureId("feat")
  private val policy = BudgetPolicy(perWorkstreamCapUsd = BigDecimal(20), perInstanceCapUsd = BigDecimal(50))

  private def worker(id: String) =
    WorkerRecord(id, "/repo", feature, "Running", Vector.empty, workstreamId = Some(ws))

  private val baseState = RebuildInstanceState.empty.copy(workers = Vector(worker("w1"), worker("w2")))

  test("grants when the estimate stays within both caps"):
    assert(policy.authorize(baseState, "w1", Some(ws), BigDecimal(8)))

  test("refuses when the per-instance cap would be exceeded"):
    val state = baseState.copy(committedUsd = BigDecimal(48))
    assert(!policy.authorize(state, "w1", Some(ws), BigDecimal(5)))

  test("refuses when the per-workstream cap would be exceeded even though the instance cap is fine"):
    val state = baseState.copy(committedByWorkstream = Map(ws.value -> BigDecimal(18)))
    assert(!policy.authorize(state, "w1", Some(ws), BigDecimal(5)))
    // a smaller estimate that fits the workstream cap is granted
    assert(policy.authorize(state, "w1", Some(ws), BigDecimal(2)))

  test("another worker's outstanding reservation counts against the cap"):
    val state = baseState.copy(reservations = Map("w2" -> BudgetReservation("res-2", Some(ws), BigDecimal(18))))
    assert(!policy.authorize(state, "w1", Some(ws), BigDecimal(5)))

  test("the reserving worker's own current reservation is excluded (re-reserve is not double-charged)"):
    val state = baseState.copy(reservations = Map("w1" -> BudgetReservation("res-1", Some(ws), BigDecimal(18))))
    // w1 re-reserving 8 sees only its own (excluded) reservation ⇒ 0 + 8 ≤ 20, granted
    assert(policy.authorize(state, "w1", Some(ws), BigDecimal(8)))

  test("a worker with no workstream is bound only by the per-instance cap"):
    val state = baseState.copy(committedByWorkstream = Map(ws.value -> BigDecimal(100)))
    // the saturated workstream is irrelevant — the worker has none
    assert(policy.authorize(state, "w1", None, BigDecimal(40)))
    assert(!policy.authorize(state, "w1", None, BigDecimal(60)))

  test("the unlimited policy grants any realistic estimate"):
    val state = baseState.copy(committedUsd = BigDecimal(1_000_000))
    assert(BudgetPolicy.unlimited.authorize(state, "w1", Some(ws), BigDecimal(1_000_000)))
