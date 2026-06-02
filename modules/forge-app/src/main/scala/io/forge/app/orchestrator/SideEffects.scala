package io.forge.app.orchestrator

import cats.effect.IO
import io.forge.agents.{DesignReviewInput, PrReviewInput, RefineInput}
import io.forge.core.{PieceId, PrNumber}
import io.forge.core.fsm.{Feature, FsmEvent}
import io.forge.core.profile.RepoCommand
import io.forge.git.branch.protection.RequiredChecksOverlay
import io.forge.git.worktree.WorktreeSafety

/** The orchestrator's effectful boundary (Task 1.4.10 J1/J2). Everything the loop engine cannot do with the pure
  * `Fsm.transition` + the atomic persist triad + the already-injectable `SessionMonitor` / `PRWatcher` / `ReviewerCall`
  * lives behind this seam:
  *
  *   - **driver-session launches** — need prompt/asset assembly and the `Connector` (constructed once per `Mode`, J3);
  *   - **reviewer-input assembly** — `designReview` needs `design.md`; `prReview` needs the `gh pr diff` + changed
  *     files; `refine` needs `design.md` + the manifest JSON;
  *   - **§11 git/gh/worktree mutations** — commit (`do not commit — Forge will commit`, §11.4 step 6), push,
  *     `createPr`, snapshot-tag + force-push-with-lease, and `syncBase` + `createPieceBranch`.
  *
  * The **engine increment (-d1)** depends only on this trait; the **real implementation** — the `git add`/`commit` seam
  * in forge-git, `ChangeCollector` wiring, `Connector` lifetime (J3), and prompt templating — lands in Task 1.4.10-d2.
  * A scripted [[io.forge.app.orchestrator.* fake]] drives the -d1 J5 e2e suites without a worktree.
  *
  * The "effect" methods return `IO[Either[String, FsmEvent]]`: `Right(event)` is fed straight back into
  * `Fsm.transition`; `Left(reason)` is mapped by the loop to `FsmEvent.HarnessError(reason)` — the §11 "side effects
  * bracket the FSM transition; on failure route to `NeedsHumanIntervention`" contract (e.g. `ChangeCollector` `Deny`,
  * push rejected, `createPr` failed).
  */
trait SideEffects:

  // --- driver-session launches (the returned ActiveSession is stored in currentDriverSession) ---

  /** §11.1 step 2 — spawn the spec driver (`runStreamingSpec`). */
  def launchSpec(feature: Feature): IO[ActiveSession]

  /** §11.2 step 12 — resume the design session as a `DesignRevision` driver after a `request_changes` verdict. */
  def resumeDesignRevision(feature: Feature, round: Int): IO[ActiveSession]

  /** §11.3 — resume the design session as a `DesignRevision` driver to apply post-merge-PR human feedback. */
  def resumeDesignFeedback(feature: Feature, pr: PrNumber, round: Int): IO[ActiveSession]

  /** §11.4 step 2 — spawn the headless implementation driver (`runHeadlessImplementation`). */
  def launchImplement(feature: Feature, piece: PieceId): IO[ActiveSession]

  /** §11.6 — spawn a fresh headless fix-up driver (`runFixup`). */
  def launchFixup(feature: Feature, piece: PieceId, attempt: Int): IO[ActiveSession]

  /** D3-3 (roadmap §3.5 driver-respawn-avoidance) — resume the interrupted implement driver via the D3-1
    * `Connector.resumeHeadlessDriver(sessionId, …)` seam instead of re-spawning from scratch (and re-paying the
    * exploration, gap #10). Called by the loop's restart resume-gate when `feature.currentPieceSessionId` is durable
    * and [[classifyPieceWorktree]] reports the worktree safe.
    */
  def resumeImplement(feature: Feature, piece: PieceId, sessionId: String): IO[ActiveSession]

  /** D3-3 — the fix-up-phase analogue of [[resumeImplement]]: resume the interrupted fix-up driver by its durable
    * session id rather than spawning a fresh `runFixup`.
    */
  def resumeFixup(feature: Feature, piece: PieceId, sessionId: String): IO[ActiveSession]

  /** D3-3 — classify the piece's worktree for resume safety (the D3-2 `WorktreeSafetyClassifier` over the live
    * `GitClient`). `Right(Clean | DriverUncommittedOnly)` is safe to resume onto; `Right(UnexpectedDivergence)` and
    * `Left(reason)` (a git read that failed, or the piece has no recorded branch) both route the loop's resume-gate
    * back to `NeedsHumanIntervention`. Conservative by construction — see
    * [[io.forge.git.worktree.WorktreeSafetyClassifier]].
    */
  def classifyPieceWorktree(feature: Feature, piece: PieceId): IO[Either[String, WorktreeSafety]]

  // --- reviewer-input assembly ---

  def designReviewInput(feature: Feature, round: Int): IO[Either[String, DesignReviewInput]]
  def prReviewInput(feature: Feature, piece: PieceId, pr: PrNumber): IO[Either[String, PrReviewInput]]
  def refineInput(feature: Feature, piece: PieceId): IO[Either[String, RefineInput]]

  // --- §11 git/gh side effects (sub-phase III post-settle, post-verdict, and branch advance) ---

  /** §11.1 step 7 — coherence post-check; success → `Settled(Spec, Clean)`. */
  def coherencePostCheck(feature: Feature): IO[Either[String, FsmEvent]]

  /** §11.2 steps 9-10 — update `design.md` from the settled revision (no PR yet); success → re-review (no-op event). */
  def updateDesignAssets(feature: Feature): IO[Either[String, FsmEvent]]

  /** §11.3 steps 3-5 — update assets, snapshot-tag, force-push-with-lease; success → re-await the design PR. */
  def repushDesignFeedback(feature: Feature, pr: PrNumber, round: Int): IO[Either[String, FsmEvent]]

  /** §11.2 step 13 — commit design assets + audit snapshot, `gh pr create`; success → `DesignPrSnapshotUpdated(open)`.
    */
  def commitDesignAndOpenPr(feature: Feature): IO[Either[String, FsmEvent]]

  /** §8.1 — the branch-protection required-checks overlay for this feature's base branch (cache-first, keyed by
    * feature/base/`branchProtectionCacheEpoch`). The orchestrator's `CiReadiness` gate unions this with
    * `config.ci.requiredChecksOverlay`. `Left(reason)` on a non-degradable `gh` failure (rate-limit / other); the
    * `Unauthorized`/`Unprotected` fallbacks degrade to an empty overlay (carried in `RequiredChecksOverlay.source`),
    * not a `Left`.
    */
  def requiredChecksOverlay(feature: Feature): IO[Either[String, RequiredChecksOverlay]]

  /** §11.4 step 1 — `syncBase` + `createPieceBranch`; success → `BranchCreated(piece, branch, baseSha)`. */
  def advancePieceBranch(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]]

  /** §11.4 step 6 — `ChangeCollector` classify → commit → push → `createPr`; success → `PrOpened(piece, prNumber)`;
    * `Deny` → `Left` (the loop maps it to `HarnessError` → `ResolveLocalImplementationChanges`).
    */
  def classifyCommitOpenPr(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]]

  /** §11.6 — `ChangeCollector` classify → commit → push (the PR already exists); success → `Settled(Fixup, Clean)`;
    * `Deny` → `Left` (the loop maps it to `HarnessError` → `RunAnotherFixup`).
    */
  def classifyCommitPush(feature: Feature, piece: PieceId, pr: PrNumber): IO[Either[String, FsmEvent]]

  /** §8.2 — pull the real failing log for the run, ANSI stripped. This is what the FailureRouter classifies so it sees
    * the real scalafmt error line rather than the gh pr checks summary; see dogfood number four. A Left reason on a gh
    * failure means the caller falls back to the 1.6 blind fix up.
    */
  def fetchFailingLog(runId: String): IO[Either[String, String]]

  /** §8.2 row 1 — run the repo's own deterministic in place autofix command such as sbt scalafmtAll, commit its result
    * on the piece branch, and push. No driver turn, no LLM, no attempts increment; this is the dogfood number two
    * collapse. A Left reason when the command fails, rewrites nothing, or the push is rejected means the caller falls
    * back to a driver fix up so the failure is never silently dropped.
    */
  def runLocalAutofixAndPush(feature: Feature, piece: PieceId, command: RepoCommand): IO[Either[String, Unit]]

  /** §8.3 (Task 3.1.3) — the local format gate, shift-left: run the repo's `required` deterministic in-place `Format`
    * autofix `commands` on the working tree BEFORE the §11.4-step-6 piece commit, so the driver's non-conformant output
    * is rewritten in place and the eventual classify → commit picks up the conformant version — the piece commit is
    * format-clean before it ever reaches CI (dogfood #3, zero round-trip; no PR exists yet, so there is nothing to
    * amend or push here). The orchestrator only calls this with a non-empty, pre-filtered `commands` list and records
    * the `profile.local_gate` audit action itself. Best-effort and additive: a `Left` (a formatter that exits non-zero,
    * e.g. on a syntax error the build will also catch) is ignored by the caller and the commit proceeds exactly as 1.6.
    */
  def runLocalFormatGate(feature: Feature, piece: PieceId, commands: Vector[RepoCommand]): IO[Either[String, Unit]]
