package io.forge.git.watcher

import io.forge.core.pr.*
import io.forge.core.{PrNumber, Sha}

import java.time.Instant

/** PR-B B5 — fixture-driven coverage for [[PrSnapshotDecoder]].
  *
  * Every fixture under `src/test/resources/gh-pr-view/` is loaded via the classpath and decoded against
  * [[PollBaseline.empty]] unless a specific case overrides it. The suite is the roadmap §2.3 "fake-`gh` unit coverage"
  * core; the design-2.3 PR-B B5 checklist enumerates the exact set.
  */
class PrSnapshotDecoderSuite extends munit.FunSuite:

  private val DefaultBot = "forge-bot"

  private def loadFixture(name: String): ujson.Value =
    val url = getClass.getResource(s"/gh-pr-view/$name")
    assert(url != null, s"fixture missing: /gh-pr-view/$name")
    val raw = scala.io.Source.fromURL(url, "UTF-8").mkString
    ujson.read(raw)

  private def decodeFixture(
      name: String,
      baseline: PollBaseline = PollBaseline.empty,
      botLogin: String = DefaultBot
  ): Either[DecodeError, DecodedSnapshot] =
    PrSnapshotDecoder.decode(loadFixture(name), baseline, botLogin)

  // --- happy-path fixtures ----------------------------------------------------

  test("open-no-checks: fresh OPEN PR decodes with empty rollup and headSha from commits[-1].oid"):
    decodeFixture("open-no-checks.json") match
      case Right(DecodedSnapshot(snap, head)) =>
        assertEquals(snap.number, PrNumber(4291))
        assertEquals(snap.state, PrState.Open)
        assertEquals(snap.mergedAt, None)
        assertEquals(snap.mergeCommit, None)
        assertEquals(snap.mergeable, Some(true))
        assertEquals(snap.reviewDecision, None)
        assertEquals(snap.requiredChecks, CheckRollup.empty)
        assertEquals(snap.unseenComments, Vector.empty[PrComment])
        assertEquals(head, Sha("abc1234567890abc1234567890abc1234567890a"))
      case other => fail(s"expected Right, got $other")

  test("open-checks-running: in-progress checks land under observed, no conclusions"):
    decodeFixture("open-checks-running.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(snap.requiredChecks.required, Vector.empty[CheckResult])
        assertEquals(
          snap.requiredChecks.observed,
          Vector(
            CheckResult("build", CheckState.InProgress, None),
            CheckResult("lint", CheckState.Queued, None)
          )
        )
        assertEquals(snap.mergeable, None)
      case other => fail(s"expected Right, got $other")

  test("open-checks-mixed: success + failure + neutral conclusions land under observed"):
    decodeFixture("open-checks-mixed.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(
          snap.requiredChecks.observed,
          Vector(
            CheckResult("build", CheckState.Completed, Some(CheckConclusion.Success)),
            CheckResult("tests", CheckState.Completed, Some(CheckConclusion.Failure)),
            CheckResult("docs", CheckState.Completed, Some(CheckConclusion.Neutral))
          )
        )
        assertEquals(snap.reviewDecision, Some(ReviewDecision.ReviewRequired))
      case other => fail(s"expected Right, got $other")

  test("open-changes-requested: comments AND reviews fold into unseenComments"):
    decodeFixture("open-changes-requested.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(snap.reviewDecision, Some(ReviewDecision.ChangesRequested))
        // Comment + review both unseen at baseline=empty
        assertEquals(snap.unseenComments.size, 2)
        assert(
          snap.unseenComments.exists(c => c.author == "alice" && c.body.contains("Foo.scala")),
          s"missed comment body: ${snap.unseenComments}"
        )
        assert(
          snap.unseenComments.exists(c => c.author == "alice" && c.body.contains("Blocking on")),
          s"missed review body: ${snap.unseenComments}"
        )
        snap.unseenComments.foreach { c =>
          assertEquals(c.path, None)
          assertEquals(c.line, None)
        }
      case other => fail(s"expected Right, got $other")

  test("open-mergeable-conflicting: CONFLICTING → Some(false), mergeStateStatus=DIRTY ignored (CI6)"):
    decodeFixture("open-mergeable-conflicting.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(snap.mergeable, Some(false))
        // CI6 trap: state is OPEN even though mergeStateStatus says DIRTY
        assertEquals(snap.state, PrState.Open)
        assertEquals(snap.mergedAt, None)
      case other => fail(s"expected Right, got $other")

  test("closed-not-merged: CLOSED with null mergedAt/mergeCommit → state Closed, no merge fields"):
    decodeFixture("closed-not-merged.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(snap.state, PrState.Closed)
        assertEquals(snap.mergedAt, None)
        assertEquals(snap.mergeCommit, None)
      case other => fail(s"expected Right, got $other")

  test("merged: state MERGED + non-null mergedAt + mergeCommit.oid populated"):
    decodeFixture("merged.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(snap.state, PrState.Merged)
        assertEquals(snap.mergedAt, Some(Instant.parse("2026-05-26T12:00:00Z")))
        assertEquals(snap.mergeCommit, Some(Sha("5555555555555555555555555555555555555555")))
      case other => fail(s"expected Right, got $other")

  test("merged-stale-mergestate (CI6 trap): state still MERGED — mergeStateStatus value irrelevant"):
    decodeFixture("merged-stale-mergestate.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        // The fixture's mergeStateStatus is "CLEAN" (a non-MERGED value); the decoder must still report Merged because
        // it derives terminal state from `state` + `mergedAt` only (design-rationale CI6).
        assertEquals(snap.state, PrState.Merged)
        assertEquals(snap.mergedAt, Some(Instant.parse("2026-05-26T13:00:00Z")))
        assertEquals(snap.mergeCommit, Some(Sha("7777777777777777777777777777777777777777")))
      case other => fail(s"expected Right, got $other")

  // --- baseline + bot filtering (RL2 / S3-7) ---------------------------------

  test("open-with-comments at baseline=None, botLogin=forge-bot: alice + bob visible, forge-bot dropped"):
    decodeFixture("open-with-comments.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        val authors = snap.unseenComments.map(_.author)
        val ids = snap.unseenComments.map(_.id).toSet
        assert(!authors.contains("forge-bot"), s"bot author leaked: $authors")
        assertEquals(
          ids,
          Set("IC_kwDOAB000001", "IC_kwDOAB000002", "IC_kwDOAB000003")
        )
      case other => fail(s"expected Right, got $other")

  test("open-with-comments at baseline=Some(09:30): only the 10:00 entry from bob survives"):
    val baseline = PollBaseline(
      lastSeenCommentAt = Some(Instant.parse("2026-05-26T09:30:00Z")),
      lastSeenReviewAt = None,
      Set.empty
    )
    decodeFixture("open-with-comments.json", baseline = baseline) match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(snap.unseenComments.map(_.id), Vector("IC_kwDOAB000003"))
        assertEquals(snap.unseenComments.map(_.author), Vector("bob"))
      case other => fail(s"expected Right, got $other")

  test("open-with-comments at baseline=Some(09:00): two later alice comments + bob's survive"):
    val baseline = PollBaseline(
      lastSeenCommentAt = Some(Instant.parse("2026-05-26T09:00:00Z")),
      lastSeenReviewAt = None,
      Set.empty
    )
    decodeFixture("open-with-comments.json", baseline = baseline) match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(
          snap.unseenComments.map(_.id),
          Vector("IC_kwDOAB000002", "IC_kwDOAB000003")
        )
      case other => fail(s"expected Right, got $other")

  test("open-with-comments at baseline=Some(10:00): equality excluded → bob's also dropped"):
    val baseline = PollBaseline(
      lastSeenCommentAt = Some(Instant.parse("2026-05-26T10:00:00Z")),
      lastSeenReviewAt = None,
      Set.empty
    )
    decodeFixture("open-with-comments.json", baseline = baseline) match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(snap.unseenComments, Vector.empty[PrComment])
      case other => fail(s"expected Right, got $other")

  test("custom botLogin filters that login (not 'forge-bot')"):
    decodeFixture("open-with-comments.json", botLogin = "alice") match
      case Right(DecodedSnapshot(snap, _)) =>
        val authors = snap.unseenComments.map(_.author)
        assert(!authors.contains("alice"), s"alice should be filtered: $authors")
        // bob survives; forge-bot (no longer the bot) surfaces
        assertEquals(snap.unseenComments.map(_.author).toSet, Set("bob", "forge-bot"))
      case other => fail(s"expected Right, got $other")

  // --- empty-body filter (review round 1) ------------------------------------

  test("open-empty-approval: APPROVED review with empty body is filtered (no spurious unseen comment)"):
    decodeFixture("open-empty-approval.json") match
      case Right(DecodedSnapshot(snap, _)) =>
        // The review IS empty-bodied + APPROVED. The FSM uses unseenComments.nonEmpty as a "human override" signal,
        // so an empty approval must NOT surface. reviewDecision still carries the approval state.
        assertEquals(snap.unseenComments, Vector.empty[PrComment])
        assertEquals(snap.reviewDecision, Some(ReviewDecision.Approved))
      case other => fail(s"expected Right, got $other")

  test("inline empty-body comment is filtered too (robustness)"):
    val json = ujson.read("""
      {
        "number": 1, "state": "OPEN", "mergedAt": null, "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [],
        "comments": [
          {
            "id": "IC_empty",
            "body": "",
            "createdAt": "2026-05-26T10:00:00Z",
            "author": { "login": "alice" }
          },
          {
            "id": "IC_filled",
            "body": "this one carries signal",
            "createdAt": "2026-05-26T10:05:00Z",
            "author": { "login": "alice" }
          }
        ],
        "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(snap.unseenComments.size, 1)
        assertEquals(snap.unseenComments.head.id, "IC_filled")
      case other => fail(s"expected Right, got $other")

  // --- negative cases ---------------------------------------------------------

  test("malformed-missing-state → MissingField('state')"):
    decodeFixture("malformed-missing-state.json") match
      case Left(DecodeError.MissingField("state")) => ()
      case other => fail(s"expected MissingField(state), got $other")

  test("malformed-unknown-check-state → UnknownEnumValue naming the offending entry"):
    decodeFixture("malformed-unknown-check-state.json") match
      case Left(DecodeError.UnknownEnumValue(field, observed, known)) =>
        assertEquals(field, "statusCheckRollup[0].state")
        assertEquals(observed, "MYSTERY_NEW_STATE")
        assert(known.contains("IN_PROGRESS"), s"known values: $known")
      case other => fail(s"expected UnknownEnumValue, got $other")

  test("malformed-shape → MalformedShape on statusCheckRollup"):
    decodeFixture("malformed-shape.json") match
      case Left(DecodeError.MalformedShape("statusCheckRollup", "array", "object")) => ()
      case other => fail(s"expected MalformedShape, got $other")

  // --- root-level shape / inline JSON cases ----------------------------------

  test("non-object root JSON → MalformedShape('<root>', 'object', _)"):
    val rootArr = ujson.read("""[1, 2, 3]""")
    PrSnapshotDecoder.decode(rootArr, PollBaseline.empty, DefaultBot) match
      case Left(DecodeError.MalformedShape("<root>", "object", "array")) => ()
      case other => fail(s"expected MalformedShape, got $other")

  test("missing 'number' field → MissingField('number')"):
    val json = ujson.read("""
      {
        "state": "OPEN", "mergedAt": null, "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [], "comments": [], "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Left(DecodeError.MissingField("number")) => ()
      case other => fail(s"expected MissingField(number), got $other")

  test("empty commits array → MissingField('commits[-1].oid')"):
    val json = ujson.read("""
      {
        "number": 1, "state": "OPEN", "mergedAt": null, "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [], "comments": [], "reviews": [],
        "commits": []
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Left(DecodeError.MissingField("commits[-1].oid")) => ()
      case other => fail(s"expected MissingField(commits[-1].oid), got $other")

  test("invalid sha at mergeCommit.oid → MalformedShape"):
    val json = ujson.read("""
      {
        "number": 1, "state": "MERGED",
        "mergedAt": "2026-05-26T12:00:00Z",
        "mergeCommit": { "oid": "not-a-sha" },
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [], "comments": [], "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Left(DecodeError.MalformedShape("mergeCommit.oid", "sha", "not-a-sha")) => ()
      case other => fail(s"expected MalformedShape(mergeCommit.oid), got $other")

  test("invalid mergedAt format → MalformedShape on mergedAt"):
    val json = ujson.read("""
      {
        "number": 1, "state": "MERGED",
        "mergedAt": "yesterday",
        "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [], "comments": [], "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Left(DecodeError.MalformedShape("mergedAt", "ISO instant", "yesterday")) => ()
      case other => fail(s"expected MalformedShape(mergedAt), got $other")

  test("unknown reviewDecision value → UnknownEnumValue"):
    val json = ujson.read("""
      {
        "number": 1, "state": "OPEN", "mergedAt": null, "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": "DICTATOR_DECREED",
        "statusCheckRollup": [], "comments": [], "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Left(DecodeError.UnknownEnumValue("reviewDecision", "DICTATOR_DECREED", known)) =>
        assert(known.contains("APPROVED"))
      case other => fail(s"expected UnknownEnumValue(reviewDecision), got $other")

  test("StatusContext-shaped check entry (context+state) decodes via the fallback"):
    // §6 / Slice 0 §4.1 — the rollup is a heterogeneous union of CheckRun and StatusContext. CheckRun uses
    // `name` + `status`; StatusContext uses `context` + `state`. The decoder accepts either spelling so external CI
    // integrations (the StatusContext side) don't need a separate code path.
    val json = ujson.read("""
      {
        "number": 1, "state": "OPEN", "mergedAt": null, "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [
          { "__typename": "StatusContext", "context": "ci/external", "state": "PENDING" }
        ],
        "comments": [], "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Right(DecodedSnapshot(snap, _)) =>
        assertEquals(
          snap.requiredChecks.observed,
          Vector(CheckResult("ci/external", CheckState.Pending, None))
        )
      case other => fail(s"expected Right, got $other")

  test("comments[].id is required — missing id surfaces MissingField"):
    val json = ujson.read("""
      {
        "number": 1, "state": "OPEN", "mergedAt": null, "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [],
        "comments": [
          {
            "body": "no id",
            "createdAt": "2026-05-26T10:00:00Z",
            "author": { "login": "alice" }
          }
        ],
        "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Left(DecodeError.MissingField("comments[0].id")) => ()
      case other => fail(s"expected MissingField(comments[0].id), got $other")

  test("malformed createdAt on a comment → MalformedShape with the path"):
    val json = ujson.read("""
      {
        "number": 1, "state": "OPEN", "mergedAt": null, "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [],
        "comments": [
          {
            "id": "IC_kwDOAB1",
            "body": "x",
            "createdAt": "tomorrow",
            "author": { "login": "alice" }
          }
        ],
        "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    PrSnapshotDecoder.decode(json, PollBaseline.empty, DefaultBot) match
      case Left(DecodeError.MalformedShape("comments[0].createdAt", "ISO instant", "tomorrow")) => ()
      case other => fail(s"expected MalformedShape on comments[0].createdAt, got $other")
