package io.forge.core.pr

import io.forge.core.*

import java.time.Instant
import upickle.default.{read, write}

/** PR-B B7 — codec round-trip for §6 `PrSnapshot` and its supporting enums (`PrState`, `ReviewDecision`, `CheckState`,
  * `CheckConclusion`, `CheckResult`, `CheckRollup`, `PrComment`). One representative value per case; full state-space
  * coverage is the FSM property suite's job (PR-F F1+).
  */
class PrSnapshotSuite extends munit.FunSuite:

  private def roundTrip[A: upickle.default.ReadWriter](a: A): Unit =
    val json = write(a)
    val parsed = read[A](json)
    assertEquals(parsed, a, s"round-trip failed for: $json")

  test("PrState — each wire form maps to the expected case"):
    assertEquals(PrState.fromString("OPEN"), Right(PrState.Open))
    assertEquals(PrState.fromString("CLOSED"), Right(PrState.Closed))
    assertEquals(PrState.fromString("MERGED"), Right(PrState.Merged))
    assert(PrState.fromString("PENDING").isLeft)

  test("PrState — round-trip every variant"):
    roundTrip(PrState.Open)
    roundTrip(PrState.Closed)
    roundTrip(PrState.Merged)

  test("ReviewDecision — round-trip every variant"):
    roundTrip(ReviewDecision.Approved)
    roundTrip(ReviewDecision.ChangesRequested)
    roundTrip(ReviewDecision.ReviewRequired)

  test("CheckState — round-trip every variant (mirrors GraphQL CheckStatusState)"):
    Seq(
      CheckState.Queued,
      CheckState.InProgress,
      CheckState.Completed,
      CheckState.Pending,
      CheckState.Requested,
      CheckState.Waiting
    ).foreach(roundTrip(_))

  test("CheckState.fromString — all 6 GraphQL states decode; unknown rejected"):
    assertEquals(CheckState.fromString("QUEUED"), Right(CheckState.Queued))
    assertEquals(CheckState.fromString("IN_PROGRESS"), Right(CheckState.InProgress))
    assertEquals(CheckState.fromString("COMPLETED"), Right(CheckState.Completed))
    assertEquals(CheckState.fromString("PENDING"), Right(CheckState.Pending))
    assertEquals(CheckState.fromString("REQUESTED"), Right(CheckState.Requested))
    assertEquals(CheckState.fromString("WAITING"), Right(CheckState.Waiting))
    assert(CheckState.fromString("UNKNOWN").isLeft)

  test("CheckConclusion — round-trip every variant (mirrors GraphQL CheckConclusionState)"):
    Seq(
      CheckConclusion.Success,
      CheckConclusion.Failure,
      CheckConclusion.Cancelled,
      CheckConclusion.Skipped,
      CheckConclusion.TimedOut,
      CheckConclusion.ActionRequired,
      CheckConclusion.Neutral,
      CheckConclusion.Stale,
      CheckConclusion.StartupFailure
    ).foreach(roundTrip(_))

  test("CheckConclusion.fromString — all 9 GraphQL conclusions decode; unknown rejected"):
    assertEquals(CheckConclusion.fromString("SUCCESS"), Right(CheckConclusion.Success))
    assertEquals(CheckConclusion.fromString("FAILURE"), Right(CheckConclusion.Failure))
    assertEquals(CheckConclusion.fromString("STARTUP_FAILURE"), Right(CheckConclusion.StartupFailure))
    assert(CheckConclusion.fromString("UNKNOWN").isLeft)

  test("CheckRollup — round-trip with required + observed checks"):
    val rollup = CheckRollup(
      required = Vector(CheckResult("build", CheckState.Completed, Some(CheckConclusion.Success))),
      observed = Vector(CheckResult("docs", CheckState.InProgress, None))
    )
    roundTrip(rollup)

  test("PrComment — round-trip top-level and inline"):
    val top = PrComment("c1", "alice", "lgtm", Instant.parse("2026-05-26T10:00:00Z"), None, None)
    val inline = PrComment(
      "c2",
      "bob",
      "nit",
      Instant.parse("2026-05-26T10:01:00Z"),
      Some("src/Foo.scala"),
      Some(42)
    )
    roundTrip(top)
    roundTrip(inline)

  test("PrSnapshot — round-trip a populated value"):
    val snap = PrSnapshot(
      number = PrNumber(4291),
      state = PrState.Open,
      mergedAt = None,
      mergeCommit = None,
      requiredChecks = CheckRollup(
        Vector(CheckResult("ci", CheckState.Completed, Some(CheckConclusion.Success))),
        Vector.empty
      ),
      reviewDecision = Some(ReviewDecision.Approved),
      unseenComments = Vector.empty,
      mergeable = Some(true)
    )
    roundTrip(snap)

  test("PrSnapshot — round-trip a merged-state value with mergeCommit + mergedAt"):
    val snap = PrSnapshot(
      number = PrNumber(4291),
      state = PrState.Merged,
      mergedAt = Some(Instant.parse("2026-05-26T12:00:00Z")),
      mergeCommit = Some(Sha("abcdef0123456789abcdef0123456789abcdef01")),
      requiredChecks = CheckRollup.empty,
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = None
    )
    roundTrip(snap)
