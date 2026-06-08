package io.forge.tui

/** Slice 4.4 Task 4.4.1 — coverage for the pure [[CockpitSnapshot.fromStatusJson]] parse of the daemon `status` JSON
  * (the shape `io.forge.instance.InstanceState.toStatusJson` emits). Asserts the multi-workstream fold, the attention
  * projection resolving onto the right worker, the spend totals, loose (workstreamless) workers, and tolerance of
  * absent optional fields.
  */
class CockpitSnapshotSuite extends munit.FunSuite:

  // Mirrors InstanceState.toStatusJson: a top-level workers[] keyed by id, workstreams[] referencing them by id, each
  // workstream carrying its own committed/outstanding + an attention[] projection.
  private val status: ujson.Value =
    ujson.Obj(
      "schemaVersion" -> ujson.Num(5),
      "bootCount" -> ujson.Num(3),
      "committedUsd" -> ujson.Num(45.67),
      "outstandingUsd" -> ujson.Num(100.0),
      "workers" -> ujson.Arr(
        ujson.Obj(
          "workerId" -> ujson.Str("w-1"),
          "repo" -> ujson.Str("/repos/szork"),
          "feature" -> ujson.Str("adventure-gen"),
          "status" -> ujson.Str("PieceImplementing"),
          "eventCount" -> ujson.Num(42),
          "live" -> ujson.Bool(true),
          "workstreamId" -> ujson.Str("ws-1"),
          "pid" -> ujson.Num(12345)
        ),
        ujson.Obj(
          "workerId" -> ujson.Str("w-2"),
          "repo" -> ujson.Str("/repos/toast"),
          "feature" -> ujson.Str("retry-config"),
          "status" -> ujson.Str("NeedsHumanIntervention"),
          "eventCount" -> ujson.Num(7),
          "live" -> ujson.Bool(true),
          "workstreamId" -> ujson.Str("ws-1"),
          "containerId" -> ujson.Str("abcdef0123456789")
        ),
        // A loose worker — registered directly, claimed by no workstream.
        ujson.Obj(
          "workerId" -> ujson.Str("w-9"),
          "repo" -> ujson.Str("/repos/loose"),
          "feature" -> ujson.Str("solo"),
          "status" -> ujson.Str("FeatureDone"),
          "eventCount" -> ujson.Num(3),
          "live" -> ujson.Bool(false),
          "exitCode" -> ujson.Num(0)
        )
      ),
      "workstreams" -> ujson.Arr(
        ujson.Obj(
          "workstreamId" -> ujson.Str("ws-1"),
          "goal" -> ujson.Str("ship the adventure generator"),
          "status" -> ujson.Str("Active"),
          "committedUsd" -> ujson.Num(45.67),
          "outstandingUsd" -> ujson.Num(50.0),
          "workers" -> ujson.Arr(ujson.Str("w-1"), ujson.Str("w-2")),
          "attention" -> ujson.Arr(
            ujson.Obj("workerId" -> ujson.Str("w-2"), "reason" -> ujson.Str("needs-human-intervention"))
          )
        )
      )
    )

  test("parses instance summary fields"):
    val s = CockpitSnapshot.fromStatusJson("llm4s", status)
    assertEquals(s.instanceName, "llm4s")
    assertEquals(s.bootCount, 3)
    assertEquals(s.committedUsd, 45.67)
    assertEquals(s.outstandingUsd, 100.0)

  test("folds workers into their workstream by id"):
    val s = CockpitSnapshot.fromStatusJson("llm4s", status)
    assertEquals(s.workstreams.size, 1)
    val ws = s.workstreams.head
    assertEquals(ws.id, "ws-1")
    assertEquals(ws.goal, "ship the adventure generator")
    assertEquals(ws.status, "Active")
    assertEquals(ws.committedUsd, 45.67)
    assertEquals(ws.outstandingUsd, 50.0)
    assertEquals(ws.workers.map(_.workerId), Vector("w-1", "w-2"))

  test("resolves the attention projection onto the flagged worker only"):
    val s = CockpitSnapshot.fromStatusJson("llm4s", status)
    val ws = s.workstreams.head
    assertEquals(ws.workers.find(_.workerId == "w-1").flatMap(_.attentionReason), None)
    assertEquals(ws.workers.find(_.workerId == "w-2").flatMap(_.attentionReason), Some("needs-human-intervention"))
    assertEquals(s.attentionCount, 1)

  test("renders liveness from pid / containerId / exitCode"):
    val s = CockpitSnapshot.fromStatusJson("llm4s", status)
    val ws = s.workstreams.head
    assertEquals(ws.workers.find(_.workerId == "w-1").map(_.liveness), Some("live(pid=12345)"))
    // container id is truncated to 12 chars
    assertEquals(ws.workers.find(_.workerId == "w-2").map(_.liveness), Some("live(container=abcdef012345)"))
    assertEquals(s.looseWorkers.headOption.map(_.liveness), Some("exited(0)"))

  test("collects workstreamless workers as loose"):
    val s = CockpitSnapshot.fromStatusJson("llm4s", status)
    assertEquals(s.looseWorkers.map(_.workerId), Vector("w-9"))
    assertEquals(s.workerCount, 3)
    assertEquals(s.liveCount, 2)

  test("tolerates an empty / fieldless status object"):
    val s = CockpitSnapshot.fromStatusJson("empty", ujson.Obj())
    assertEquals(s.bootCount, 0)
    assertEquals(s.committedUsd, 0.0)
    assert(s.workstreams.isEmpty)
    assert(s.looseWorkers.isEmpty)
    assertEquals(s.workerCount, 0)
