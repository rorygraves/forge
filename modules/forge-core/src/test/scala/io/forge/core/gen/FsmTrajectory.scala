package io.forge.core.gen

import io.forge.core.*
import io.forge.core.fsm.*
import io.forge.core.log.ActionDraft
import io.forge.core.manifest.PieceStatus
import io.forge.core.pr.*
import io.forge.core.review.{DesignReviewVerdict, PrReviewVerdict, RefineVerdict}

import java.time.Instant

/** PR-F F0 — deterministic driver that walks the FSM through the §11 lifecycle for a feature. Used by the property
  * suites for F1 (replay round-trip), F3 (design-before-implement), F4 (merged piece never re-selected), F5 / F6
  * (session-id lifecycle).
  *
  * `runHappyPath(initial)` applies a fixed sequence of `FsmEvent`s designed to drive the feature from `Drafting`
  * through to `FeatureDone`, exercising every §6.1 projection on the way. Each call returns the (feature, drafts) pair
  * plus the running list of `FsmState`s visited so properties can assert lifecycle facts (e.g., "every
  * `PieceImplementing` was preceded by `DesignReady` in the trace").
  *
  * The driver is **deterministic** rather than generated: F1's replay round-trip needs the produced drafts to be the
  * exact log a real orchestrator would write, and the §6.1 projection rules are dense enough that a Gen-driven walker
  * would mostly produce no-op event sequences. The shape that gets sampled across properties is the input `Feature`
  * (via `genInitialFeature`), not the event sequence.
  */
object FsmTrajectory:

  /** A single FSM step result with the events that drove it. The properties walk these in order. */
  final case class Step(event: FsmEvent, fromState: FsmState, toState: FsmState, drafts: Vector[ActionDraft])

  /** The aggregate result of a trajectory. `finalFeature` is what `Fsm.transition` produced after applying every event;
    * `allDrafts` is the flat list of action drafts (in order) — F1 stamps these into `Action`s and folds them back;
    * `states` is the visited state sequence including the seed; `events` is the input event list.
    */
  final case class Run(
      seed: Feature,
      finalFeature: Feature,
      allDrafts: Vector[ActionDraft],
      states: Vector[FsmState],
      events: Vector[FsmEvent],
      steps: Vector[Step]
  )

  /** Apply `events` in order via `Fsm.transition`, returning the trajectory. */
  def apply(seed: Feature, events: Vector[FsmEvent]): Run =
    val (finalF, steps) = events.foldLeft((seed, Vector.empty[Step])):
      case ((f, acc), ev) =>
        val (next, drafts) = Fsm.transition(f, ev)
        (next, acc :+ Step(ev, f.state, next.state, drafts))
    Run(
      seed = seed,
      finalFeature = finalF,
      allDrafts = steps.flatMap(_.drafts),
      states = seed.state +: steps.map(_.toState),
      events = events,
      steps = steps
    )

  // ---------------------------------------------------------------------------
  // Happy-path event sequences (legal §11 lifecycles)
  // ---------------------------------------------------------------------------

  private val DesignSid: String = "sid-design-1"
  private def pieceSid(p: PieceId): String = s"sid-piece-${p.value}"
  private val DesignPr: PrNumber = PrNumber(9000)
  private val Sha40: Sha = Sha("c" * 40)
  private val Sha40b: Sha = Sha("d" * 40)
  private val Epoch: Instant = Instant.parse("2026-05-26T12:00:00Z")

  /** Build the event sequence that walks `seed` from `Drafting` through `FeatureDone`. Each pending piece is taken
    * through `BranchCreated → PrOpened → CI ready → review approve → Merged → Refining(NoChange)`. Pieces already
    * marked merged in the seed manifest are skipped (the FSM's `nextPending` selector won't re-select them, which is
    * what F4 asserts).
    */
  def happyPathEvents(seed: Feature): Vector[FsmEvent] =
    val designEvents: Vector[FsmEvent] = Vector(
      FsmEvent.SessionSpawned(actor = "claude", role = "spec-driver", sessionId = DesignSid, piece = None),
      FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.Clean),
      FsmEvent.DesignReviewReceived(round = 1, verdict = DesignReviewVerdict.Approve),
      FsmEvent.DesignPrSnapshotUpdated(
        snapshot = openSnapshot(DesignPr)
      ),
      FsmEvent.DesignPrSnapshotUpdated(
        snapshot = mergedSnapshot(DesignPr)
      )
    )
    val pendingPieces = seed.manifest.pieces.filter(_.status == PieceStatus.Pending).map(_.id)
    val pieceEvents: Vector[FsmEvent] = pendingPieces.zipWithIndex.flatMap: (p, i) =>
      val pr = PrNumber(9100 + i)
      Vector[FsmEvent](
        FsmEvent.BranchCreated(piece = p, branchName = BranchName(s"forge/p/${p.value}"), baseSha = Sha40),
        FsmEvent.SessionSpawned(actor = "claude", role = "piece-driver", sessionId = pieceSid(p), piece = Some(p)),
        FsmEvent.PrOpened(piece = p, prNumber = pr),
        FsmEvent.PrSnapshotUpdated(piece = p, snapshot = successfulCi(pr)),
        FsmEvent.CodeReviewVerdict(piece = p, verdict = PrReviewVerdict.Approve),
        FsmEvent.Merged(
          piece = p,
          prNumber = pr,
          mergeCommit = Sha40b,
          mergedAt = Epoch.plusSeconds(i.toLong * 10),
          observedAt = Epoch.plusSeconds(i.toLong * 10 + 1)
        ),
        FsmEvent.RefineOutcome(RefineVerdict.NoChange)
      )
    designEvents ++ pieceEvents

  /** A trajectory that runs the happy path on `seed`. */
  def happyPath(seed: Feature): Run = apply(seed, happyPathEvents(seed))

  // ---------------------------------------------------------------------------
  // PR snapshot helpers (mirror FsmFixtures shape but parameterised for property use)
  // ---------------------------------------------------------------------------

  def openSnapshot(prNumber: PrNumber): PrSnapshot =
    PrSnapshot(
      number = prNumber,
      state = PrState.Open,
      mergedAt = None,
      mergeCommit = None,
      requiredChecks = CheckRollup.empty,
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = Some(true)
    )

  def mergedSnapshot(prNumber: PrNumber): PrSnapshot =
    openSnapshot(prNumber).copy(
      state = PrState.Merged,
      mergedAt = Some(Epoch),
      mergeCommit = Some(Sha40b)
    )

  def successfulCi(prNumber: PrNumber): PrSnapshot =
    openSnapshot(prNumber).copy(
      requiredChecks = CheckRollup(
        required = Vector(CheckResult("ci", CheckState.Completed, Some(CheckConclusion.Success))),
        observed = Vector.empty
      )
    )

  def failedCi(prNumber: PrNumber): PrSnapshot =
    openSnapshot(prNumber).copy(
      requiredChecks = CheckRollup(
        required = Vector(CheckResult("ci", CheckState.Completed, Some(CheckConclusion.Failure))),
        observed = Vector.empty
      )
    )

  def humanCommentSnapshot(prNumber: PrNumber): PrSnapshot =
    openSnapshot(prNumber).copy(
      unseenComments = Vector(
        PrComment("c1", "alice", "needs work", Epoch.minusSeconds(1), None, None)
      )
    )

  def changesRequestedSnapshot(prNumber: PrNumber): PrSnapshot =
    openSnapshot(prNumber).copy(reviewDecision = Some(ReviewDecision.ChangesRequested))
