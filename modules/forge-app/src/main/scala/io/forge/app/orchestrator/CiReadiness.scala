package io.forge.app.orchestrator

import io.forge.app.config.CiConfig
import io.forge.core.CiPolicy
import io.forge.core.pr.{CheckConclusion, CheckResult, CheckRollup, CheckState, PrSnapshot}
import io.forge.git.branch.protection.RequiredChecksOverlay

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** §8 CI-readiness policy — the pure decision the orchestrator runs on every PR-watcher poll while a piece is in
  * `PieceAwaitingCi`. The FSM itself is `CiPolicy`-agnostic (`F9CiReadinessOrderingSuite`): it decides
  * Ready/Failed/Pending purely from `snapshot.requiredChecks.required` (`Fsm.ciOutcome`). This object owns the policy
  * that *populates* that `required` set, plus the discovery-timeout / `minimumExpectedChecks` / `stableGreenPolls`
  * machinery that the pure FSM cannot express (it has no clock and no cross-poll memory).
  *
  * The decoder (`PrSnapshotDecoder`) always emits `required = Vector.empty` and puts every observed check under
  * `observed`; this object reads `observed` and produces a `CheckRollup` whose `required` set the orchestrator stamps
  * onto the snapshot before forwarding `PrSnapshotUpdated` to the FSM.
  *
  * Cross-poll state ([[CiDiscoveryState]]) is the consecutive-green count only; the discovery-window anchor lives in
  * the orchestrator (it owns the monotonic clock) and reaches this function as the `elapsed` argument.
  */
object CiReadiness:

  /** A single synthetic all-success check stamped under `required` when `CiPolicy.None` skips gating, so the FSM's
    * `ciOutcome` reads Ready regardless of what (if anything) actually ran. The name is `forge:`-prefixed so it can
    * never collide with a real GitHub check name.
    */
  private val SkippedCheck: CheckResult =
    CheckResult("forge:ci-skipped", CheckState.Completed, Some(CheckConclusion.Success))

  /** Mirrors `Fsm.isBadConclusion` — a "required check failed" conclusion that should route the piece to a fix-up. */
  private def isBad(c: CheckConclusion): Boolean = c match
    case CheckConclusion.Failure | CheckConclusion.TimedOut | CheckConclusion.Cancelled |
        CheckConclusion.StartupFailure | CheckConclusion.ActionRequired =>
      true
    case _ => false

  /** §8.2 — true when a forwarded rollup represents a *required-check failure* (mirrors `Fsm.ciOutcome == Failed`). The
    * orchestrator branches on this to run the Phase-3 classified routing before handing the snapshot to the FSM.
    */
  def isFailure(rollup: CheckRollup): Boolean = rollup.required.exists(_.conclusion.exists(isBad))

  /** §8.2 — the first failing required check in a rollup (the one whose run log the classifier reads). */
  def firstFailingCheck(rollup: CheckRollup): Option[CheckResult] =
    rollup.required.find(_.conclusion.exists(isBad))

  private def isSuccess(c: CheckResult): Boolean = c.conclusion.contains(CheckConclusion.Success)

  /** A check that has not finished — any non-`Completed` state (`Queued` / `InProgress` / `Pending` / `Requested` /
    * `Waiting`). While *any* observed check is still pending, CI has not gone quiescent, so a required check that is
    * absent from `observed` may yet register (it can be gated behind a slow upstream job, e.g. `needs:[test]`). The §8
    * rule-2 "never appeared" verdict therefore waits for the run to settle rather than firing at the discovery-window
    * edge (carry-forward F3).
    */
  private def isPending(c: CheckResult): Boolean = c.state != CheckState.Completed

  /** @param profileRequiredChecks
    *   the §3.3-Tier-2 sensed required-check set from `WorkflowProfile.ciRequiredChecks`, unioned into the required
    *   names alongside branch protection and the config overlay. The orchestrator passes `Set.empty` for an unprofiled
    *   run, a profile with no declared checks, or `adapt.workflowGate = false` — so an absent/empty value is
    *   byte-identical to the pre-3.3 behaviour.
    */
  def evaluate(
      policy: CiPolicy,
      ci: CiConfig,
      overlay: RequiredChecksOverlay,
      profileRequiredChecks: Set[String],
      snapshot: PrSnapshot,
      state: CiDiscoveryState,
      elapsed: FiniteDuration
  ): CiDecision =
    val observed = snapshot.requiredChecks.observed
    policy match
      // §8 `None` — skip CI gating entirely; the orchestrator logs `ci.skipped`. Stamp a single synthetic
      // all-success required check so the FSM reads Ready immediately, ignoring whatever (if anything) ran.
      case CiPolicy.None =>
        CiDecision.Forward(CheckRollup(required = Vector(SkippedCheck), observed = observed))

      case CiPolicy.BranchProtectionThenObserved =>
        val timeout = ci.checkDiscoveryTimeoutSec.seconds
        val discoveryElapsed = elapsed > timeout
        val requiredNames = overlay.required ++ ci.requiredChecksOverlay.toSet ++ profileRequiredChecks

        if requiredNames.nonEmpty then
          // Branch-protection / config-overlay / sensed-profile union names the required set. Promote the matching
          // observed checks.
          val observedNames = observed.map(_.name).toSet
          val missing = (requiredNames -- observedNames).toVector.sorted
          if missing.nonEmpty then
            // §8 rule 2: a required check that never appeared → human push needed. But "never" is only knowable once
            // CI has settled: while any observed check is still pending, the missing required check may be gated behind
            // it (e.g. `needs:[test]`) and yet to register. Block only past the discovery window *and* with no pending
            // observed check; otherwise keep polling (carry-forward F3).
            if discoveryElapsed && !observed.exists(isPending) then
              CiDecision.Blocked(s"required check '${missing.head}' never appeared (source: ${overlay.source})")
            else CiDecision.KeepPolling(state.copy(consecutiveGreen = 0))
          else greenGate(ci, observed.filter(c => requiredNames.contains(c.name)), observed, state)
        else
        // Observed fallback (no branch protection, no config overlay): wait `checkDiscoveryTimeoutSec` for >=1 check,
        // then require all observed checks green for `stableGreenPolls` consecutive polls.
        if discoveryElapsed && observed.isEmpty then
          // §8 rule 1.
          CiDecision.Blocked("no CI checks discovered")
        else if discoveryElapsed && observed.size < ci.minimumExpectedChecks then
          // §8 rule 3.
          CiDecision.Blocked(
            s"only ${observed.size} CI checks observed, expected at least ${ci.minimumExpectedChecks}"
          )
        else if observed.isEmpty then CiDecision.KeepPolling(state.copy(consecutiveGreen = 0))
        else greenGate(ci, observed, observed, state)

  /** The shared green/failed/pending decision over the chosen `required` set, mirroring `Fsm.ciOutcome` but with the
    * `stableGreenPolls` consecutive-green debounce the FSM cannot track. A failure forwards immediately (no debounce —
    * the FSM routes it straight to `PieceCiFailed`); all-success debounces; anything else resets the green count.
    */
  private def greenGate(
      ci: CiConfig,
      required: Vector[CheckResult],
      observed: Vector[CheckResult],
      state: CiDiscoveryState
  ): CiDecision =
    if required.exists(c => c.conclusion.exists(isBad)) then
      CiDecision.Forward(CheckRollup(required = required, observed = observed))
    else if required.forall(isSuccess) then
      val next = state.consecutiveGreen + 1
      if next >= ci.stableGreenPolls then CiDecision.Forward(CheckRollup(required = required, observed = observed))
      else CiDecision.KeepPolling(state.copy(consecutiveGreen = next))
    else CiDecision.KeepPolling(state.copy(consecutiveGreen = 0))

/** Cross-poll CI-discovery state owned by `CiReadiness`: the count of consecutive all-green polls toward
  * `stableGreenPolls`. The discovery-window anchor (first-poll monotonic stamp) lives in the orchestrator.
  */
final case class CiDiscoveryState(consecutiveGreen: Int)
object CiDiscoveryState:
  val initial: CiDiscoveryState = CiDiscoveryState(consecutiveGreen = 0)

/** The §8 per-poll decision. */
enum CiDecision:
  /** The required set is known — hand the stamped rollup to the FSM, which decides Ready (all green) vs Failed (any bad
    * conclusion) via `ciOutcome`. Covers both the green-ready and the red-failed cases uniformly.
    */
  case Forward(rollup: CheckRollup)

  /** Not resolved yet — keep polling with the advanced discovery state (e.g. still inside the discovery window, or
    * counting toward `stableGreenPolls`).
    */
  case KeepPolling(next: CiDiscoveryState)

  /** §8 discovery rules 1-3 gave up — the orchestrator emits `FsmEvent.CiReadinessBlocked(reason)`. */
  case Blocked(reason: String)
