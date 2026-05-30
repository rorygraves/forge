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
      case Right(DecodedSnapshot(snap, head, next)) =>
        assertEquals(snap.number, PrNumber(4291))
        assertEquals(snap.state, PrState.Open)
        assertEquals(snap.mergedAt, None)
        assertEquals(snap.mergeCommit, None)
        assertEquals(snap.mergeable, Some(true))
        assertEquals(snap.reviewDecision, None)
        assertEquals(snap.requiredChecks, CheckRollup.empty)
        assertEquals(snap.unseenComments, Vector.empty[PrComment])
        assertEquals(head, Sha("abc1234567890abc1234567890abc1234567890a"))
        // Empty comments + reviews → cursor unchanged from input (still empty).
        assertEquals(next, PollBaseline.empty)
      case other => fail(s"expected Right, got $other")

  test("open-fresh-no-reviews: reviewDecision='' (empty string) decodes as None (PR-G IT finding)"):
    // Real `gh pr view --json reviewDecision` returns `""` (not null) on brand-new PRs with no reviews — surfaced
    // by `BranchManagerIntegrationSuite` in PR-G against the sacrificial test repo. The decoder treats empty string
    // identically to null so the orchestrator's first poll on a freshly-opened piece PR doesn't fail with
    // `UnknownEnumValue(reviewDecision, "")`.
    decodeFixture("open-fresh-no-reviews.json") match
      case Right(DecodedSnapshot(snap, _, _)) =>
        assertEquals(snap.reviewDecision, None)
        assertEquals(snap.state, PrState.Open)
        assertEquals(snap.mergeable, Some(true))
      case other => fail(s"expected Right, got $other")

  // build's conclusion is "" (gh's real in-progress shape, MVP-run finding) and lint's is null — both decode to None.
  test("open-checks-running: in-progress checks (conclusion '' or null) land under observed, no conclusions"):
    decodeFixture("open-checks-running.json") match
      case Right(DecodedSnapshot(snap, _, _)) =>
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
      case Right(DecodedSnapshot(snap, _, _)) =>
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
      case Right(DecodedSnapshot(snap, _, next)) =>
        assertEquals(snap.reviewDecision, Some(ReviewDecision.ChangesRequested))
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
        // Cursor advances to the only comment and the only review respectively.
        assertEquals(
          next.commentCursor,
          Some(BaselineCursor(Instant.parse("2026-05-26T10:00:00Z"), Set("IC_kwDOAB000200")))
        )
        assertEquals(
          next.reviewCursor,
          Some(BaselineCursor(Instant.parse("2026-05-26T10:01:00Z"), Set("PRR_kwDOAB000050")))
        )
      case other => fail(s"expected Right, got $other")

  test("open-mergeable-conflicting: CONFLICTING → Some(false), mergeStateStatus=DIRTY ignored (CI6)"):
    decodeFixture("open-mergeable-conflicting.json") match
      case Right(DecodedSnapshot(snap, _, _)) =>
        assertEquals(snap.mergeable, Some(false))
        assertEquals(snap.state, PrState.Open)
        assertEquals(snap.mergedAt, None)
      case other => fail(s"expected Right, got $other")

  test("closed-not-merged: CLOSED with null mergedAt/mergeCommit → state Closed, no merge fields"):
    decodeFixture("closed-not-merged.json") match
      case Right(DecodedSnapshot(snap, _, _)) =>
        assertEquals(snap.state, PrState.Closed)
        assertEquals(snap.mergedAt, None)
        assertEquals(snap.mergeCommit, None)
      case other => fail(s"expected Right, got $other")

  test("merged: state MERGED + non-null mergedAt + mergeCommit.oid populated"):
    decodeFixture("merged.json") match
      case Right(DecodedSnapshot(snap, _, _)) =>
        assertEquals(snap.state, PrState.Merged)
        assertEquals(snap.mergedAt, Some(Instant.parse("2026-05-26T12:00:00Z")))
        assertEquals(snap.mergeCommit, Some(Sha("5555555555555555555555555555555555555555")))
      case other => fail(s"expected Right, got $other")

  test("merged-stale-mergestate (CI6 trap): state still MERGED — mergeStateStatus value irrelevant"):
    decodeFixture("merged-stale-mergestate.json") match
      case Right(DecodedSnapshot(snap, _, _)) =>
        assertEquals(snap.state, PrState.Merged)
        assertEquals(snap.mergedAt, Some(Instant.parse("2026-05-26T13:00:00Z")))
        assertEquals(snap.mergeCommit, Some(Sha("7777777777777777777777777777777777777777")))
      case other => fail(s"expected Right, got $other")

  // --- baseline + bot filtering (RL2 / S3-7) ---------------------------------

  test("open-with-comments at baseline=None, botLogin=forge-bot: alice + bob visible, forge-bot dropped"):
    decodeFixture("open-with-comments.json") match
      case Right(DecodedSnapshot(snap, _, next)) =>
        val authors = snap.unseenComments.map(_.author)
        val ids = snap.unseenComments.map(_.id).toSet
        assert(!authors.contains("forge-bot"), s"bot author leaked: $authors")
        assertEquals(
          ids,
          Set("IC_kwDOAB000001", "IC_kwDOAB000002", "IC_kwDOAB000003")
        )
        // nextBaseline.commentCursor covers ALL entries (bot included) — the cursor records observation, not signal.
        assertEquals(
          next.commentCursor,
          Some(BaselineCursor(Instant.parse("2026-05-26T10:30:00Z"), Set("IC_kwDOAB000004")))
        )
      case other => fail(s"expected Right, got $other")

  test("open-with-comments at cursor=Some(09:30,{IC_002}): only the strictly-later bob comment survives"):
    val baseline = PollBaseline(
      commentCursor = Some(BaselineCursor(Instant.parse("2026-05-26T09:30:00Z"), Set("IC_kwDOAB000002"))),
      reviewCursor = None,
      Set.empty
    )
    decodeFixture("open-with-comments.json", baseline = baseline) match
      case Right(DecodedSnapshot(snap, _, _)) =>
        assertEquals(snap.unseenComments.map(_.id), Vector("IC_kwDOAB000003"))
        assertEquals(snap.unseenComments.map(_.author), Vector("bob"))
      case other => fail(s"expected Right, got $other")

  test("open-with-comments at cursor=Some(09:00,{IC_001}): two later non-bot comments survive"):
    val baseline = PollBaseline(
      commentCursor = Some(BaselineCursor(Instant.parse("2026-05-26T09:00:00Z"), Set("IC_kwDOAB000001"))),
      reviewCursor = None,
      Set.empty
    )
    decodeFixture("open-with-comments.json", baseline = baseline) match
      case Right(DecodedSnapshot(snap, _, _)) =>
        assertEquals(
          snap.unseenComments.map(_.id),
          Vector("IC_kwDOAB000002", "IC_kwDOAB000003")
        )
      case other => fail(s"expected Right, got $other")

  test("open-with-comments at cursor=Some(10:30,{IC_004}): equality+id-in-seenIds → no new signal"):
    val baseline = PollBaseline(
      commentCursor = Some(BaselineCursor(Instant.parse("2026-05-26T10:30:00Z"), Set("IC_kwDOAB000004"))),
      reviewCursor = None,
      Set.empty
    )
    decodeFixture("open-with-comments.json", baseline = baseline) match
      case Right(DecodedSnapshot(snap, _, _)) =>
        assertEquals(snap.unseenComments, Vector.empty[PrComment])
      case other => fail(s"expected Right, got $other")

  test("custom botLogin filters that login (not 'forge-bot')"):
    decodeFixture("open-with-comments.json", botLogin = "alice") match
      case Right(DecodedSnapshot(snap, _, _)) =>
        val authors = snap.unseenComments.map(_.author)
        assert(!authors.contains("alice"), s"alice should be filtered: $authors")
        assertEquals(snap.unseenComments.map(_.author).toSet, Set("bob", "forge-bot"))
      case other => fail(s"expected Right, got $other")

  // --- same-second tie-breaker (round-2 high finding) ------------------------

  test("same-second comment with a NEW id surfaces even when cursor.at equals createdAt"):
    // The reviewer's concrete worry: a comment posted in the same second as the cursor was previously dropped by the
    // strict isAfter filter. The id tie-breaker rescues it — IC_kwDOAB000B is not in cursor.seenIds, so it surfaces.
    val sameSecond = Instant.parse("2026-05-26T11:00:00Z")
    val json = ujson.read(s"""
      {
        "number": 200, "state": "OPEN", "mergedAt": null, "mergeCommit": null,
        "mergeable": "MERGEABLE", "reviewDecision": null,
        "statusCheckRollup": [],
        "comments": [
          {
            "id": "IC_kwDOAB000A",
            "body": "first poll already saw this",
            "createdAt": "$sameSecond",
            "author": { "login": "alice" }
          },
          {
            "id": "IC_kwDOAB000B",
            "body": "second poll: same second, NEW id — must surface",
            "createdAt": "$sameSecond",
            "author": { "login": "bob" }
          }
        ],
        "reviews": [],
        "commits": [{ "oid": "1111111111111111111111111111111111111111" }]
      }
    """)
    val baseline = PollBaseline(
      commentCursor = Some(BaselineCursor(sameSecond, Set("IC_kwDOAB000A"))),
      reviewCursor = None,
      Set.empty
    )
    PrSnapshotDecoder.decode(json, baseline, DefaultBot) match
      case Right(DecodedSnapshot(snap, _, next)) =>
        assertEquals(snap.unseenComments.map(_.id), Vector("IC_kwDOAB000B"))
        // nextBaseline accumulates both ids at the watermark so the next poll skips both.
        assertEquals(
          next.commentCursor,
          Some(BaselineCursor(sameSecond, Set("IC_kwDOAB000A", "IC_kwDOAB000B")))
        )
      case other => fail(s"expected Right, got $other")

  // --- empty-body filter (review round 1) ------------------------------------

  test("open-empty-approval: APPROVED review with empty body is filtered (no spurious unseen comment)"):
    decodeFixture("open-empty-approval.json") match
      case Right(DecodedSnapshot(snap, _, next)) =>
        assertEquals(snap.unseenComments, Vector.empty[PrComment])
        assertEquals(snap.reviewDecision, Some(ReviewDecision.Approved))
        // The empty-body review is still observed for cursor purposes (so the next poll doesn't re-evaluate it).
        assertEquals(
          next.reviewCursor,
          Some(BaselineCursor(Instant.parse("2026-05-26T11:00:00Z"), Set("PRR_kwDOAB000123")))
        )
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
      case Right(DecodedSnapshot(snap, _, _)) =>
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
      case Right(DecodedSnapshot(snap, _, _)) =>
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
