package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.manifest.ManifestPatch
import io.forge.core.pr.{CheckRollup, PrSnapshot, PrState}
import io.forge.core.review.{DesignReviewVerdict, PrReviewVerdict, RefineVerdict}

import java.time.Instant
import upickle.default.{read, write}

/** PR-B B7 — codec round-trip for `FsmEvent` and its supporting enums (`SessionPhase`, `SettleOutcome`,
  * `PlanningChoice`, `BudgetScope`, `UserCommand`).
  */
class FsmEventSuite extends munit.FunSuite:

  private def roundTrip[A: upickle.default.ReadWriter](a: A): Unit =
    val json = write(a)
    val parsed = read[A](json)
    assertEquals(parsed, a, s"round-trip failed for: $json")

  private val p1 = PieceId("p1")
  private val prNumber = PrNumber(4291)
  private val sha = Sha("abcdef0123456789abcdef0123456789abcdef01")
  private val ts = Instant.parse("2026-05-26T12:00:00Z")
  private val snap = PrSnapshot(
    number = prNumber,
    state = PrState.Open,
    mergedAt = None,
    mergeCommit = None,
    requiredChecks = CheckRollup.empty,
    reviewDecision = None,
    unseenComments = Vector.empty,
    mergeable = None
  )

  // --- supporting enums ---

  test("SessionPhase — every variant round-trips"):
    Seq(
      SessionPhase.Spec,
      SessionPhase.DesignRevision,
      SessionPhase.Implement,
      SessionPhase.Fixup,
      SessionPhase.DesignReview,
      SessionPhase.CodeReview,
      SessionPhase.Refine
    ).foreach(roundTrip(_))

  test("SettleOutcome — every variant round-trips"):
    Seq(
      SettleOutcome.Clean,
      SettleOutcome.HitQuestionLimit,
      SettleOutcome.AdapterError("schema invalid: foo")
    ).foreach(roundTrip(_))

  test("PlanningChoice — every variant round-trips"):
    val patch = ManifestPatch("edit", Vector.empty)
    Seq(
      PlanningChoice.Accept,
      PlanningChoice.Reject,
      PlanningChoice.EditAndAccept(patch)
    ).foreach(roundTrip(_))

  test("BudgetScope — every variant round-trips"):
    Seq(BudgetScope.Feature, BudgetScope.Piece(p1), BudgetScope.Harness).foreach(roundTrip(_))

  test("UserCommand — every variant round-trips"):
    Seq(
      UserCommand.New(FeatureId("hello"), Mode.ClaudeDriver),
      UserCommand.Resume(ResumeHint.AbortOrAbandon),
      UserCommand.Abandon("done with this"),
      UserCommand.Done
    ).foreach(roundTrip(_))

  // --- FsmEvent ---

  test("FsmEvent — session lifecycle variants round-trip"):
    roundTrip[FsmEvent](FsmEvent.SessionSpawned("claude", "driver", "sess-1", Some(p1)))
    roundTrip[FsmEvent](FsmEvent.SessionResumed("claude", "driver", "sess-1", "sess-1", Some(p1)))
    roundTrip[FsmEvent](FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.Clean))
    roundTrip[FsmEvent](FsmEvent.SettleTimeout(SessionPhase.Implement, "wall-clock 1800s"))
    roundTrip[FsmEvent](FsmEvent.TurnBudgetBreached(SessionPhase.Spec, "$2 cap exceeded"))

  test("FsmEvent — design-phase variants round-trip"):
    roundTrip[FsmEvent](FsmEvent.DesignReviewReceived(round = 1, verdict = DesignReviewVerdict.Approve))
    roundTrip[FsmEvent](
      FsmEvent.DesignReviewReceived(round = 2, verdict = DesignReviewVerdict.RequestChanges(Vector("x")))
    )
    roundTrip[FsmEvent](FsmEvent.DesignReviewClarified(round = 1))
    roundTrip[FsmEvent](FsmEvent.DesignPrSnapshotUpdated(snap))

  test("FsmEvent — implementation-phase variants round-trip"):
    roundTrip[FsmEvent](FsmEvent.BranchCreated(p1, BranchName("forge/feat/p1"), sha))
    roundTrip[FsmEvent](FsmEvent.PrOpened(p1, prNumber))
    roundTrip[FsmEvent](FsmEvent.PrSnapshotUpdated(p1, snap))
    roundTrip[FsmEvent](FsmEvent.CodeReviewVerdict(p1, PrReviewVerdict.Approve))
    roundTrip[FsmEvent](FsmEvent.CheckDiscoveryComplete(p1, prNumber))
    roundTrip[FsmEvent](
      FsmEvent.Merged(
        piece = p1,
        prNumber = prNumber,
        mergeCommit = sha,
        mergedAt = ts,
        observedAt = ts.plusSeconds(5)
      )
    )

  test("FsmEvent — refinery/planning variants round-trip"):
    roundTrip[FsmEvent](FsmEvent.RefineOutcome(RefineVerdict.NoChange))
    roundTrip[FsmEvent](
      FsmEvent.PlanningDecision("reorder", ManifestPatch("r", Vector.empty), PlanningChoice.Accept)
    )

  test("FsmEvent — cross-cutting variants round-trip"):
    roundTrip[FsmEvent](FsmEvent.BudgetBreached(BudgetScope.Feature, "feature cap $50 hit"))
    roundTrip[FsmEvent](
      FsmEvent.RequiredSessionIdMissing("design phase has no session", ResumeHint.ReopenDesign(None))
    )
    roundTrip[FsmEvent](FsmEvent.UserCommandReceived(UserCommand.Done))
    roundTrip[FsmEvent](FsmEvent.HarnessError("flock failed"))
