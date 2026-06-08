package io.forge.app.command

import io.forge.core.{FeatureId, WorkstreamId}
import io.forge.instance.{InstanceEvent, RebuildInstanceState}
import io.forge.tui.CockpitSnapshot

/** Slice 4.4 Task 4.4.1 — couples the cockpit's [[CockpitSnapshot.fromStatusJson]] parser to the **real producer**,
  * `InstanceState.toStatusJson`, rather than a hand-built JSON fixture (CLAUDE.md "capture real external shapes" — here
  * the shape is internal but still drifts if the two are written from the same mental model independently).
  *
  * It folds a faithful instance-log event sequence (`RebuildInstanceState.step`, the daemon's own incremental fold) for
  * a workstream with two containerised/host workers — one of them flagged for attention by its FSM status — plus a B2
  * reservation, renders `toStatusJson`, and asserts the cockpit model parsed back out of it. If `toStatusJson` ever
  * changes a key the parser reads, this test fails where the forge-tui unit suite (a static fixture) would not.
  */
class CockpitStatusWireSuite extends munit.FunSuite:

  private val ws1 = WorkstreamId("ws-1")

  private val state =
    Vector[InstanceEvent](
      InstanceEvent.DaemonStarted(4242L),
      InstanceEvent.WorkstreamCreated(ws1, "ship the adventure generator"),
      InstanceEvent.WorkerSpawned(
        "w-1",
        ws1,
        "/repos/szork",
        FeatureId("adventure-gen"),
        "/checkout/w-1",
        pid = Some(123L)
      ),
      InstanceEvent
        .WorkerSpawned(
          "w-2",
          ws1,
          "/repos/toast",
          FeatureId("retry-config"),
          "/checkout/w-2",
          containerId = Some("abcdef0123456789")
        ),
      InstanceEvent.WorkerStatus("w-1", "PieceImplementing"),
      InstanceEvent.WorkerStatus("w-2", "NeedsHumanIntervention"),
      InstanceEvent.BudgetGrant("w-1", Some(ws1), "res-1", BigDecimal("5.00"))
    ).foldLeft(RebuildInstanceState.empty)(RebuildInstanceState.step)

  private val snapshot = CockpitSnapshot.fromStatusJson("llm4s", state.toStatusJson)

  test("parses the instance summary off the real toStatusJson"):
    assertEquals(snapshot.instanceName, "llm4s")
    assertEquals(snapshot.bootCount, 1)
    assertEquals(snapshot.outstandingUsd, 5.0) // the single granted reservation
    assertEquals(snapshot.committedUsd, 0.0)

  test("folds both spawned workers into the workstream, ordered"):
    assertEquals(snapshot.workstreams.size, 1)
    val ws = snapshot.workstreams.head
    assertEquals(ws.id, "ws-1")
    assertEquals(ws.status, "Active") // worker.spawned advanced Planning → Active
    assertEquals(ws.goal, "ship the adventure generator")
    assertEquals(ws.workers.map(_.workerId), Vector("w-1", "w-2"))
    assertEquals(ws.outstandingUsd, 5.0)

  test("renders liveness from the real pid / containerId keys"):
    val ws = snapshot.workstreams.head
    assertEquals(ws.workers.find(_.workerId == "w-1").map(_.liveness), Some("live(pid=123)"))
    assertEquals(ws.workers.find(_.workerId == "w-2").map(_.liveness), Some("live(container=abcdef012345)"))

  test("resolves the attention projection from the real status mapping"):
    // NeedsHumanIntervention is the FSM status AttentionReason.forStatus flags; w-1 (PieceImplementing) is clear.
    assertEquals(snapshot.attentionCount, 1)
    val flagged = snapshot.workstreams.head.workers.find(_.attentionReason.isDefined)
    assertEquals(flagged.map(_.workerId), Some("w-2"))
    assertEquals(flagged.flatMap(_.attentionReason), Some("needs-human-intervention"))

  test("no loose workers when every worker belongs to a workstream"):
    assert(snapshot.looseWorkers.isEmpty, snapshot.looseWorkers.toString)
    assertEquals(snapshot.workerCount, 2)
    assertEquals(snapshot.liveCount, 2)
