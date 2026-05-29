package io.forge.app.orchestrator

import cats.effect.IO
import io.forge.agents.{DesignReviewInput, PrReviewInput, RefineInput}
import io.forge.core.{PieceId, PrNumber}
import io.forge.core.fsm.{Feature, FsmEvent}

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
