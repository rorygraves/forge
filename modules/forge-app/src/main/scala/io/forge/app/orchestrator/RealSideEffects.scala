package io.forge.app.orchestrator

import cats.data.EitherT
import cats.effect.IO
import io.forge.agents.{Connector, DesignReviewInput, FixupPrompt, ImplementationPrompt, PrReviewInput, RefineInput}
import io.forge.app.config.ForgeConfig
import io.forge.core.{BranchName, PieceId, PrNumber, Sha}
import io.forge.core.fsm.{Feature, FsmEvent, SessionPhase, SettleOutcome}
import io.forge.core.manifest.Piece
import io.forge.core.paths.ForgePaths
import io.forge.core.pr.{CheckResult, CheckRollup, PrSnapshot, PrState}
import io.forge.core.profile.{ClaudeMdProposal, CommitIdentity, RepoCommand}
import io.forge.git.branch.{BranchError, BranchManager}
import io.forge.git.branch.protection.RequiredChecksOverlay
import io.forge.git.cli.{CommitResult, GhClient, GhError, GitClient, GitError, StatusEntry}
import io.forge.git.worktree.{WorktreeSafety, WorktreeSafetyClassifier}
import io.forge.specs.{
  ChangeCollector,
  Classification,
  DocSync,
  DocSyncError,
  FileChange,
  FileChangeKind,
  HandlebarsLite,
  SpecStore,
  SpecStoreError
}

import java.time.Instant

/** Task 1.4.10-d2b — the real [[SideEffects]] implementation. The -d1 loop engine drives the §11 lifecycle entirely
  * through this seam; this is where the abstract events become actual driver spawns, reviewer-input assembly, and
  * git/gh mutations.
  *
  * Collaborators are injected (constructed once per run by `Main`, d2c): the [[Connector]] (J3 — one per `Mode`, shared
  * across every driver call and reviewer one-shot), [[BranchManager]] for §9 branch/PR orchestration, the [[GitClient]]
  * commit/status seam (Task 1.4.10-d2a), the [[GhClient]] for PR diffs, the [[ChangeCollector]] for the §10.1 staging
  * trichotomy, and the [[SpecStore]] / [[DocSync]] §4 source-of-truth surface.
  *
  * **Manifest mutations stay with the FSM.** `commitDesignAndOpenPr` / `advancePieceBranch` / `classifyCommitOpenPr`
  * return events (`DesignPrSnapshotUpdated` / `BranchCreated` / `PrOpened`); `Fsm.transition` performs the
  * `manifest.designPr` / `baseSha` / `prNumber` mutation and the [[Orchestrator]] persists it atomically (S2-5). The
  * side effect never writes `manifest.json` itself.
  *
  * **`Deny`/`Ask` from the staging classifier become `Left(reason)`**, which the loop maps to `FsmEvent.HarnessError` →
  * `NeedsHumanIntervention` with the phase-appropriate hint (`ResolveLocalImplementationChanges` pre-PR via
  * `classifyCommitOpenPr`; `RunAnotherFixup` post-PR via `classifyCommitPush`).
  */
final class RealSideEffects(
    connector: Connector,
    branchManager: BranchManager,
    git: GitClient,
    gh: GhClient,
    changeCollector: ChangeCollector,
    specStore: SpecStore,
    docSync: DocSync,
    paths: ForgePaths,
    config: ForgeConfig,
    // D4 — the §6.5 `RepoProfile.commitIdentity` resolved **once per run** (build-time, by `OrchestratorBuilder`).
    // `Some` for a profiled run with `adapt.enabled` (every Forge commit is authored as that identity, default
    // `forge[bot]`); `None` for an unprofiled / `adapt.enabled = false` run (ambient git identity — pre-D4 behaviour).
    commitIdentity: Option[CommitIdentity] = None
) extends SideEffects:

  import RealSideEffects.statusToFileChanges

  private val cli: String = connector.name

  // --- driver-session launches ----------------------------------------------

  override def launchSpec(feature: Feature): IO[ActiveSession] =
    specStore.loadDesign(feature.id).flatMap { designE =>
      val brief = designE.toOption.map(_.trim).filter(_.nonEmpty).getOrElse(feature.manifest.title)
      connector
        .runStreamingSpec(promptPath("specify"), specMessage(feature, brief))
        .map(ActiveSession(SessionPhase.Spec, _))
    }

  override def resumeDesignRevision(feature: Feature, round: Int): IO[ActiveSession] =
    resumeDesign(feature, revisionMessage(round))

  override def resumeDesignFeedback(feature: Feature, pr: PrNumber, round: Int): IO[ActiveSession] =
    resumeDesign(feature, feedbackMessage(feature, round))

  override def launchImplement(feature: Feature, piece: PieceId): IO[ActiveSession] =
    connector
      .runHeadlessImplementation(
        ImplementationPrompt(feature.id, piece, promptPath("implement"), implementBody(feature, piece))
      )
      .map(ActiveSession(SessionPhase.Implement, _))

  override def launchFixup(feature: Feature, piece: PieceId, attempt: Int): IO[ActiveSession] =
    // §11.6 / gap #12: before spawning the fix-up driver, capture the piece PR's failing CI checks into
    // `pieces/<p>.failures.md` so the driver knows WHAT to fix instead of running blind. Best-effort — a gh hiccup
    // writes a note rather than aborting the fix-up.
    writeFailures(feature, piece) >>
      connector
        .runFixup(FixupPrompt(feature.id, piece, attempt, promptPath("fixup"), fixupBody(feature, piece, attempt)))
        .map(ActiveSession(SessionPhase.Fixup, _))

  override def resumeImplement(feature: Feature, piece: PieceId, sessionId: String): IO[ActiveSession] =
    connector
      .resumeHeadlessDriver(sessionId, promptPath("implement"), resumeMessage(feature, piece))
      .map(ActiveSession(SessionPhase.Implement, _, resumed = true))

  override def resumeFixup(feature: Feature, piece: PieceId, sessionId: String): IO[ActiveSession] =
    connector
      .resumeHeadlessDriver(sessionId, promptPath("fixup"), resumeMessage(feature, piece))
      .map(ActiveSession(SessionPhase.Fixup, _, resumed = true))

  /** D3-3 continuation nudge for a resumed implement/fix-up driver. The CLI restores the prior conversation (C19), so
    * this only points the driver back at the in-flight task with absolute paths (C19 watch item 3: cheap models
    * mis-resolve relative paths). It deliberately re-states "do not commit — Forge will commit".
    */
  private def resumeMessage(feature: Feature, piece: PieceId): String =
    s"""Continue your in-flight work on piece `${piece.value}` of feature `${feature.id.value}`; your prior session was
       |interrupted and has been resumed with its context intact. Pick up where you left off against the piece spec
       |(${relPath(paths.pieceSpec(feature.id, piece))}) and finish making the acceptance criteria pass. Do not commit —
       |Forge will commit your changes after you settle.""".stripMargin

  private def writeFailures(feature: Feature, piece: PieceId): IO[Unit] =
    feature.manifest.pieces.find(_.id == piece).flatMap(_.prNumber) match
      case None => IO.unit // no PR yet (CI-failed before PR open is not a real path); nothing to report
      case Some(pr) =>
        for
          checksRes <- gh.prChecks(pr)
          checks = checksRes.fold(e => s"(could not fetch CI checks via gh: ${e.message})", _.trim)
          // dogfood number four: also pipe the real failing-step log from gh run view log-failed so the fix-up driver
          // sees the actual scalafmt error, not just the gh pr checks summary. Best-effort.
          log <- failingRunLog(pr)
          _ <- IO.blocking(
            os.write.over(failuresFile(feature.id, piece), failuresMd(piece, pr, checks, log), createFolders = true)
          )
        yield ()

  /** Best-effort fetch of the failing Actions run's log-failed output for the piece PR: read the PR's
    * statusCheckRollup, find the first failed check's actions run id, then pull and ANSI strip its log. None on any
    * gap, such as no PR snapshot, no failed Actions check, or a gh failure; the failures.md then carries only the gh pr
    * checks summary, exactly as 1.6.
    */
  private def failingRunLog(pr: PrNumber): IO[Option[String]] =
    gh.prView(pr, Vector("statusCheckRollup")).flatMap {
      case Left(_) => IO.pure(None)
      case Right(json) =>
        RealSideEffects.failingRunId(json) match
          case None => IO.pure(None)
          case Some(runId) => gh.runViewLogFailed(runId).map(_.toOption.map(GhClient.stripAnsi))
    }

  private def failuresMd(piece: PieceId, pr: PrNumber, checksReport: String, failingLog: Option[String]): String =
    val logSection = failingLog.map(_.trim).filter(_.nonEmpty) match
      case Some(l) =>
        s"""
           |Failing step log (`gh run view --log-failed`):
           |
           |```
           |$l
           |```
           |""".stripMargin
      case None => ""
    s"""# Piece ${piece.value} — failures to address (PR #${pr.value})
       |
       |These CI checks on the piece's PR did not pass (`gh pr checks`):
       |
       |```
       |$checksReport
       |```
       |$logSection
       |Make the smallest change that makes the failing check(s) pass. For a formatting
       |check, run the project's formatter on the changed files (a targeted format is
       |fine — do NOT run the full test suite). Then stop; Forge commits and re-runs CI.
       |""".stripMargin

  private def resumeDesign(feature: Feature, message: String): IO[ActiveSession] =
    IO.fromOption(feature.designSessionId)(
      new IllegalStateException("resume design driver: feature.designSessionId is empty")
    ).flatMap(connector.resumeStreamingSpec(_, promptPath("specify"), message))
      .map(ActiveSession(SessionPhase.DesignRevision, _, resumed = true))

  // --- reviewer-input assembly ----------------------------------------------

  override def designReviewInput(feature: Feature, round: Int): IO[Either[String, DesignReviewInput]] =
    et(specStore.loadDesign(feature.id))(specErr).map(md => DesignReviewInput(feature.id, round, md)).value

  override def prReviewInput(feature: Feature, piece: PieceId, pr: PrNumber): IO[Either[String, PrReviewInput]] =
    (for
      spec <- et(specStore.loadPieceSpec(feature.id, piece))(specErr)
      diff <- et(gh.prDiff(pr))(ghErr)
    yield PrReviewInput(feature.id, piece, pr, spec, diff, changedFilesFromDiff(diff))).value

  override def refineInput(feature: Feature, piece: PieceId): IO[Either[String, RefineInput]] =
    et(specStore.loadDesign(feature.id))(specErr)
      .map(md => RefineInput(feature.id, piece, md, upickle.default.write(feature.manifest)))
      .value

  // --- §11 git/gh side effects ----------------------------------------------

  override def coherencePostCheck(feature: Feature): IO[Either[String, FsmEvent]] =
    (for
      _ <- EitherT(IO.blocking(checkPieceSpecs(feature)))
      _ <- et(docSync.renderDecomposition(feature.id))(docErr)
    yield settled(SessionPhase.Spec)).value

  override def updateDesignAssets(feature: Feature): IO[Either[String, FsmEvent]] =
    et(docSync.writeDecomposition(feature.id))(docErr).map(_ => settled(SessionPhase.DesignRevision)).value

  override def repushDesignFeedback(feature: Feature, pr: PrNumber, round: Int): IO[Either[String, FsmEvent]] =
    (for
      _ <- et(docSync.writeDecomposition(feature.id))(docErr)
      head <- et(git.currentSha)(gitErr)
      _ <- et(branchManager.tagSnapshot(snapshotTag(feature, round), head))(branchErr)
      _ <- et(branchManager.pushCurrentBranch(forceWithLease = true))(branchErr)
    yield settled(SessionPhase.DesignRevision)).value

  override def commitDesignAndOpenPr(feature: Feature): IO[Either[String, FsmEvent]] =
    (for
      _ <- et(docSync.writeDecomposition(feature.id))(docErr)
      _ <- et(git.stage(Vector(relPath(paths.featureSpecDir(feature.id)))))(gitErr)
      _ <- assertHeadIs(feature.manifest.designBranch)
      _ <- et(git.commit(s"[design] ${feature.manifest.title}", commitIdentity))(gitErr)
      _ <- et(branchManager.pushCurrentBranch())(branchErr)
      body <- EitherT.liftF[IO, String, String](designPrBody(feature))
      pr <- et(branchManager.createPr(s"[design] ${feature.manifest.title}", body, feature.manifest.baseBranch))(
        branchErr
      )
    yield FsmEvent.DesignPrSnapshotUpdated(openSnapshot(pr))).value

  override def requiredChecksOverlay(feature: Feature): IO[Either[String, RequiredChecksOverlay]] =
    branchManager
      .requiredChecksOverlay(feature.id, feature.manifest.baseBranch, feature.branchProtectionCacheEpoch)
      .map(_.left.map(branchErr))

  override def advancePieceBranch(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]] =
    (for
      snap <- et(branchManager.syncBase(feature.manifest.baseBranch))(branchErr)
      res <- et(branchManager.createPieceBranch(feature.id, piece, feature.manifest.branchPrefix, snap))(branchErr)
    yield FsmEvent.BranchCreated(piece, res._1, res._2)).value

  override def classifyCommitOpenPr(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]] =
    pieceOf(feature, piece) match
      case None => IO.pure(Left(s"piece not found in manifest: ${piece.value}"))
      case Some(p) =>
        (for
          included <- classifyChanges
          _ <- et(docSync.writeDecomposition(feature.id))(docErr)
          _ <- stageChanges(included)
          _ <- assertHeadIs(feature.manifest.pieceBranch(piece))
          _ <- et(git.commit(s"feat(${feature.id.value}): ${p.title}", commitIdentity))(gitErr)
          _ <- et(branchManager.pushCurrentBranch())(branchErr)
          // Idempotency (roadmap §3.5 driver-respawn-avoidance, Unit A): if a prior post-settle pass already opened the
          // PR for this branch and crashed before the `PrOpened` transition persisted, re-running here must NOT call
          // `gh pr create` again (it would fail "a pull request already exists …" → NHI, stranding the resume). Look up
          // the open PR for the piece branch first; reuse it when present, otherwise open it. `git commit`
          // (NothingToCommit is a clean Right) and `git push` are already idempotent, so the lookup closes the last gap.
          existing <- et(gh.prForBranch(feature.manifest.pieceBranch(piece)))(ghErr)
          pr <- existing match
            case Some(open) => EitherT.pure[IO, String](open)
            case None =>
              EitherT
                .liftF[IO, String, String](piecePrBody(feature, p))
                .flatMap(body => et(branchManager.createPr(p.title, body, feature.manifest.baseBranch))(branchErr))
        yield FsmEvent.PrOpened(piece, pr)).value

  override def classifyCommitPush(feature: Feature, piece: PieceId, pr: PrNumber): IO[Either[String, FsmEvent]] =
    pieceOf(feature, piece) match
      case None => IO.pure(Left(s"piece not found in manifest: ${piece.value}"))
      case Some(p) =>
        (for
          included <- classifyChanges
          _ <- stageChanges(included)
          _ <- assertHeadIs(feature.manifest.pieceBranch(piece))
          _ <- et(git.commit(s"fix(${feature.id.value}): ${p.title}", commitIdentity))(gitErr)
          _ <- et(branchManager.pushCurrentBranch())(branchErr)
        yield settled(SessionPhase.Fixup)).value

  override def commitToTrunk(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]] =
    // design-3.3 W3 — the trunk sibling of `classifyCommitOpenPr`: classify the working tree, commit the piece STRAIGHT
    // TO THE TRUNK BRANCH, push, and emit `CommittedToTrunk` (no `createPr`, no PR tail). `assertHeadIs(baseBranch)` is
    // the trunk analogue of the PR path's `assertHeadIs(pieceBranch)` guard: under `TrunkBased` the driver works on the
    // trunk branch directly, so a HEAD that drifted off it (a concurrent git in the worktree) must not be committed.
    // `committedAt` and `observedAt` coincide here — the commit just landed and is observed synchronously; the FSM
    // stores the former on the piece's `mergedAt` and seeds `Refining.startedAt` from the latter (see `CommittedToTrunk`).
    //
    // NOTE (D5 carry-forward): under `TrunkBased`, `advancePieceBranch` should sync the trunk branch rather than cut a
    // `forge/<feat>/<piece>` branch; wiring that is part of the carried-forward live `TrunkBased`-repo demonstration
    // (there is no real trunk fixture repo to validate against yet — design-3.3-trunk D5).
    pieceOf(feature, piece) match
      case None => IO.pure(Left(s"piece not found in manifest: ${piece.value}"))
      case Some(p) =>
        (for
          included <- classifyChanges
          _ <- et(docSync.writeDecomposition(feature.id))(docErr)
          _ <- stageChanges(included)
          _ <- assertHeadIs(feature.manifest.baseBranch)
          _ <- et(git.commit(s"feat(${feature.id.value}): ${p.title}", commitIdentity))(gitErr)
          sha <- et(git.currentSha)(gitErr)
          _ <- et(branchManager.pushCurrentBranch())(branchErr)
          now <- EitherT.liftF[IO, String, Instant](IO.realTimeInstant)
        yield FsmEvent.CommittedToTrunk(piece, sha, committedAt = now, observedAt = now)).value

  // --- §8.2 classified failure routing --------------------------------------

  override def fetchFailingLog(runId: String): IO[Either[String, String]] =
    gh.runViewLogFailed(runId).map(_.left.map(ghErr).map(GhClient.stripAnsi))

  override def runLocalAutofixAndPush(
      feature: Feature,
      piece: PieceId,
      command: RepoCommand
  ): IO[Either[String, Unit]] =
    // §8.2 row 1: run the repo's own formatter/codegen, commit its output on the piece branch, push. A separate
    // style commit, rather than an amend, is used deliberately: szork-style squash-merge collapses it on merge, it
    // needs no force-push and so no lease race, and it is an honest, auditable record of the autofix. It is NOT a
    // fix-up round: no driver, no LLM, no attempts increment, since the loop never transitions on a Right here.
    (for
      _ <- runCommand(command.argv)
      status <- et(git.status())(gitErr)
      changedPaths = status.map(_.path).distinct
      _ <- EitherT.cond[IO](
        changedPaths.nonEmpty,
        (),
        s"autofix '${command.argv.mkString(" ")}' rewrote nothing — the failure is not a formatting issue"
      )
      _ <- et(git.stage(changedPaths))(gitErr)
      _ <- assertHeadIs(feature.manifest.pieceBranch(piece))
      committed <- et(git.commit(s"style(${feature.id.value}): ${command.argv.mkString(" ")}", commitIdentity))(gitErr)
      _ <- EitherT.cond[IO](
        committed == CommitResult.Committed,
        (),
        s"autofix '${command.argv.mkString(" ")}' staged no commit"
      )
      _ <- et(branchManager.pushCurrentBranch())(branchErr)
    yield ()).value

  override def runLocalFormatGate(
      feature: Feature,
      piece: PieceId,
      commands: Vector[RepoCommand]
  ): IO[Either[String, Unit]] =
    // §8.3 shift-left: run each required deterministic Format autofix command on the working tree, in place, before the
    // piece commit. The driver's changes are already present uncommitted; the formatter rewrites them so the eventual
    // classify → stage → commit (`classifyCommitOpenPr`) picks up the conformant version and the piece commit is
    // format-clean before it reaches CI (dogfood #3). No commit/push here — there is no PR yet, so unlike the §8.2 CI
    // autofix there is nothing to amend or force-push. Best-effort: commands run in order, short-circuiting to a Left on
    // the first non-zero exit; the caller ignores the Left and proceeds (the failure, if real, surfaces at the CI gate).
    commands.foldLeft(EitherT.pure[IO, String](()))((acc, cmd) => acc.flatMap(_ => runCommand(cmd.argv))).value

  override def runLocalBuildGate(
      feature: Feature,
      piece: PieceId,
      commands: Vector[RepoCommand]
  ): IO[Either[String, Unit]] =
    // §8.3 shift-left Build gate: run each required deterministic non-autofix Build command on the working tree, in
    // order, short-circuiting to a `Left` carrying the FULL captured output (not the 500-char `runCommand` tail) on the
    // first non-zero exit, so the pre-PR fix-up driver sees the real compile error. No commit/push: the gate is a check.
    val _ = (feature, piece)
    commands.foldLeft(EitherT.pure[IO, String](()))((acc, cmd) => acc.flatMap(_ => runCommandFull(cmd.argv))).value

  override def writeBuildFailures(feature: Feature, piece: PieceId, failureLog: String): IO[Unit] =
    // §8.3 — pre-PR sibling of `writeFailures`: no PR exists, so there are no `gh pr checks` to fetch — just persist the
    // captured local Build gate log to `pieces/<p>.failures.md`, the same channel the fix-up prompt reads.
    IO.blocking(
      os.write.over(failuresFile(feature.id, piece), buildFailuresMd(piece, failureLog), createFolders = true)
    )

  override def launchBuildFixup(feature: Feature, piece: PieceId, attempt: Int): IO[ActiveSession] =
    // §8.3 — the pre-PR Build fix-up driver. Unlike `launchFixup`, NO `writeFailures` (no PR / no CI checks): the Build
    // gate already wrote `pieces/<p>.failures.md` via `writeBuildFailures`, and `fixupBody` points the driver at it.
    connector
      .runFixup(FixupPrompt(feature.id, piece, attempt, promptPath("fixup"), fixupBody(feature, piece, attempt)))
      .map(ActiveSession(SessionPhase.Fixup, _))

  private def buildFailuresMd(piece: PieceId, failureLog: String): String =
    s"""# Piece ${piece.value} — local build failed (pre-PR)
       |
       |The repo's deterministic build command failed on your changes **before** the PR was opened. Fix the underlying
       |error (a compile / type error, not a formatting issue) so the build passes, then stop; Forge re-runs the build
       |gate and opens the PR once it is green.
       |
       |```
       |${failureLog.trim}
       |```
       |""".stripMargin

  // --- §11.7 / D9 ConventionLearner PR -------------------------------------

  override def openConventionsPr(feature: Feature, proposal: ClaudeMdProposal): IO[Either[String, PrNumber]] =
    // §11.7: open the learner's proposed CLAUDE.md edit as a PR for human approval — branch from base, append the
    // proposed convention to CLAUDE.md, commit, push, `gh pr create`. NEVER merges it (the human does). Idempotent: a
    // conventions PR already open for the branch is reused rather than re-`gh pr create`d. Runs out of band after the
    // feature reached FeatureDone, so it must not disturb the merged piece history — it branches cleanly from base.
    val branch = BranchName(s"${feature.manifest.branchPrefix}/conventions/${feature.id.value}")
    (for
      existing <- et(gh.prForBranch(branch))(ghErr)
      pr <- existing match
        case Some(open) => EitherT.pure[IO, String](open)
        case None =>
          for
            snap <- et(branchManager.syncBase(feature.manifest.baseBranch))(branchErr)
            _ <- et(git.checkout(branch, Some(snap.sha.value)))(gitErr)
            _ <- EitherT.liftF[IO, String, Unit](appendConventionToClaudeMd(proposal))
            _ <- et(git.stage(Vector("CLAUDE.md")))(gitErr)
            _ <- assertHeadIs(branch)
            committed <- et(git.commit(s"docs(${feature.id.value}): convention learned by forge", commitIdentity))(
              gitErr
            )
            _ <- EitherT.cond[IO](
              committed == CommitResult.Committed,
              (),
              "convention edit staged no commit (CLAUDE.md already carried it)"
            )
            _ <- et(branchManager.pushCurrentBranch())(branchErr)
            opened <- et(
              branchManager.createPr(
                conventionsPrTitle(feature),
                conventionsPrBody(feature, proposal),
                feature.manifest.baseBranch
              )
            )(branchErr)
          yield opened
    yield pr).value

  /** Append the proposed convention to the repo's CLAUDE.md (creating it if absent). Idempotent-ish: if the exact
    * addition is already present the subsequent `git commit` is a `NothingToCommit` no-op, which [[openConventionsPr]]
    * surfaces as a `Left` rather than opening an empty PR.
    */
  private def appendConventionToClaudeMd(proposal: ClaudeMdProposal): IO[Unit] =
    IO.blocking {
      val target = paths.repoRoot / "CLAUDE.md"
      val existing = if os.exists(target) && os.isFile(target) then os.read(target).stripLineEnd else ""
      val addition = s"## Convention (learned by forge)\n\n${proposal.suggestedAddition.stripLineEnd}\n"
      val combined = if existing.isEmpty then addition else s"$existing\n\n$addition"
      os.write.over(target, combined)
    }

  private def conventionsPrTitle(feature: Feature): String =
    s"[forge] convention learned from ${feature.id.value}"

  private def conventionsPrBody(feature: Feature, proposal: ClaudeMdProposal): String =
    s"""Forge's ConventionLearner proposes adding the convention below to `CLAUDE.md`, mined while driving
       |`${feature.id.value}` to completion (§11.7). **Forge opened this PR but will not merge it — review and merge if
       |you agree.**
       |
       |## Rationale
       |${proposal.rationale.stripLineEnd}
       |
       |## Proposed addition
       |${proposal.suggestedAddition.stripLineEnd}
       |""".stripMargin

  /** Run a profile autofix or gate command argv in the repo root. A non-zero exit yields a Left with the tail of its
    * combined output so the caller can surface why the autofix failed before falling back to a driver fix-up.
    */
  private def runCommand(argv: Vector[String]): EitherT[IO, String, Unit] =
    EitherT(IO.blocking {
      val res = os.proc(argv).call(cwd = paths.repoRoot, check = false, stderr = os.Pipe, stdout = os.Pipe)
      if res.exitCode == 0 then Right(())
      else
        val tail = (res.out.text() + res.err.text()).takeRight(500).trim
        Left(s"local command '${argv.mkString(" ")}' failed (exit ${res.exitCode}): $tail")
    })

  /** Like [[runCommand]] but the `Left` carries the **full** combined stdout+stderr, not the 500-char tail — the §8.3
    * Build gate hands this log to a fix-up driver, which needs the whole compile error, not a truncated suffix.
    */
  private def runCommandFull(argv: Vector[String]): EitherT[IO, String, Unit] =
    EitherT(IO.blocking {
      val res = os.proc(argv).call(cwd = paths.repoRoot, check = false, stderr = os.Pipe, stdout = os.Pipe)
      if res.exitCode == 0 then Right(())
      else
        val full = (res.out.text() + res.err.text()).trim
        Left(s"local command '${argv.mkString(" ")}' failed (exit ${res.exitCode}):\n$full")
    })

  // --- D3-3 worktree-safety classification ----------------------------------

  /** D3-3 (roadmap §3.5) — classify the piece worktree for resume safety. `expectedBranch` is the piece branch;
    * `expectedHead` is **where Forge last left the branch**:
    *   - implement phase (`prNumber = None`, the driver runs before the post-settle commit) → the piece `baseSha`;
    *   - fix-up phase (`prNumber = Some`, the branch already carries Forge's pushed commits) → the **remote PR head**
    *     (`gh pr view --json headRefOid`). Requiring local HEAD == remote head also catches an un-pushed operator
    *     commit.
    *
    * A missing `baseSha` (branch not created) or a `gh`/SHA-parse failure resolves to `Left` → the loop routes to NHI
    * (conservative). The classifier itself is conservative on any further ambiguity (wrong branch, detached HEAD,
    * unmerged rows). See [[WorktreeSafetyClassifier]].
    */
  override def classifyPieceWorktree(feature: Feature, piece: PieceId): IO[Either[String, WorktreeSafety]] =
    pieceOf(feature, piece) match
      case None => IO.pure(Left(s"piece not found in manifest: ${piece.value}"))
      case Some(p) =>
        p.baseSha match
          case None => IO.pure(Left(s"piece ${piece.value} has no recorded baseSha; branch not created"))
          case Some(base) =>
            val branch = feature.manifest.pieceBranch(piece)
            expectedHead(p, base).flatMap {
              case Left(reason) => IO.pure(Left(reason))
              case Right(head) =>
                WorktreeSafetyClassifier.classifyWorktree(git, branch, head).map(_.left.map(gitErr))
            }

  /** The commit the worktree HEAD is expected to sit at (see [[classifyPieceWorktree]]). */
  private def expectedHead(p: Piece, base: Sha): IO[Either[String, Sha]] =
    p.prNumber match
      case None => IO.pure(Right(base))
      case Some(pr) =>
        gh.prView(pr, Vector("headRefOid")).map {
          case Left(err) => Left(s"resume gate: could not read PR #${pr.value} head: ${err.message}")
          case Right(json) =>
            json.objOpt.flatMap(_.get("headRefOid")).flatMap(_.strOpt) match
              case Some(s) => Sha.fromString(s).left.map(e => s"resume gate: PR #${pr.value} headRefOid invalid: $e")
              case None => Left(s"resume gate: PR #${pr.value} response missing headRefOid")
        }

  // --- staging helpers ------------------------------------------------------

  /** §10.1 — read the working tree, classify it, and yield the includable change set or a `Left(reason)` for
    * `Deny`/`Ask` (the loop routes the latter into `NeedsHumanIntervention`).
    */
  private def classifyChanges: EitherT[IO, String, Vector[FileChange]] =
    for
      status <- et(git.status(includeIgnored = true))(gitErr)
      // `--ignored` is needed to surface the force-included `.forge/specs` source of truth (§10.1 rule 4, CC4), but it
      // also lists EVERY pre-existing ignored path in the repo (`target/`, `node_modules/`, `.env`, `.idea`,
      // `.forge/{state,log,config.json}`, build/cache dirs, …). Those are not the driver's change set; left in, the
      // ChangeCollector denies them and a single denial blocks the whole stage. Keep only non-ignored changes plus
      // ignored paths under `.forge/specs` (the carve-out Forge actually intends to commit).
      changes = statusToFileChanges(paths.repoRoot, status)
        .filter(fc => !fc.gitIgnored || fc.path.startsWith(paths.specsRoot))
      cls <- EitherT.liftF[IO, String, Classification](
        changeCollector.classify(paths.repoRoot, changes, config.staging)
      )
      included <- EitherT.fromEither[IO](cls match
        case Classification.Allow(inc) => Right(inc)
        case Classification.Deny(denied) => Left(denyReason(denied))
        case Classification.Ask(asked, _) => Left(askReason(asked))
      )
    yield included

  private def stageChanges(included: Vector[FileChange]): EitherT[IO, String, Unit] =
    et(git.stage(included.flatMap(fileChangePaths).map(relPath)))(gitErr).map(_ => ())

  /** Repo-relative changed-file list from a unified diff's `diff --git a/<x> b/<y>` headers (the `b/` destination). */
  private def changedFilesFromDiff(diff: String): Vector[String] =
    diff.linesIterator
      .filter(_.startsWith("diff --git "))
      .flatMap(_.split(' ').lastOption)
      .map(_.stripPrefix("b/"))
      .filter(_.nonEmpty)
      .toVector
      .distinct

  private def fileChangePaths(fc: FileChange): Vector[os.Path] =
    fc.kind match
      case FileChangeKind.Renamed(from) => Vector(from, fc.path)
      case _ => Vector(fc.path)

  private def denyReason(denied: Vector[(FileChange, String)]): String =
    "staging denied: " + denied.map((c, r) => s"${relPath(c.path)} ($r)").mkString("; ")

  private def askReason(asked: Vector[(FileChange, ?)]): String =
    "staging needs a human decision for: " + asked.map((c, _) => relPath(c.path)).mkString(", ")

  // --- PR-body / message assembly -------------------------------------------

  /** §11.4 step 6 — render `pr-body.md.hbs`; a missing or malformed template degrades to a plain body so a template
    * glitch never blocks a PR open.
    */
  private def piecePrBody(feature: Feature, piece: Piece): IO[String] =
    specStore.loadPieceSpec(feature.id, piece.id).map(_.getOrElse("")).flatMap { spec =>
      IO.blocking {
        val tmpl = paths.userTemplatesDir / "pr-body.md.hbs"
        if os.exists(tmpl) then
          HandlebarsLite.render(os.read(tmpl), prBodyContext(feature, piece, spec)) match
            case Right(rendered) => rendered
            case Left(_) => fallbackPieceBody(feature, piece, spec)
        else fallbackPieceBody(feature, piece, spec)
      }
    }

  private def prBodyContext(feature: Feature, piece: Piece, spec: String): HandlebarsLite.Value.Obj =
    import HandlebarsLite.Value.*
    Obj(
      Map(
        "feature" -> Obj(
          Map(
            "id" -> Str(feature.id.value),
            "title" -> Str(feature.manifest.title),
            "designPr" -> feature.manifest.designPr.map(p => Str(p.value.toString)).getOrElse(Absent)
          )
        ),
        "piece" -> Obj(
          Map(
            "id" -> Str(piece.id.value),
            "order" -> Num(piece.order),
            "title" -> Str(piece.title),
            "summary" -> Str(piece.summary),
            "spec" -> Str(spec),
            "attempts" -> Num(piece.attempts)
          )
        ),
        "auditSummary" -> Str(auditSummary(piece)),
        "mergedPieces" -> Arr(
          feature.manifest.merged.map(m => Obj(Map("id" -> Str(m.id.value), "title" -> Str(m.title))))
        )
      )
    )

  /** v1 audit one-liner. The full audit-log rollup the §11.4 template envisages is Phase-2 work. */
  private def auditSummary(piece: Piece): String =
    s"Implemented by the Forge $cli driver" +
      (if piece.attempts > 0 then s" after ${piece.attempts} fix-up attempt(s)." else ".")

  private def fallbackPieceBody(feature: Feature, piece: Piece, spec: String): String =
    s"""# ${piece.title}
       |
       |Piece `${piece.id.value}` of feature `${feature.id.value}`.
       |
       |## Summary
       |
       |${piece.summary}
       |
       |## Spec
       |
       |$spec
       |""".stripMargin

  private def designPrBody(feature: Feature): IO[String] =
    specStore.loadDesign(feature.id).map {
      case Right(md) if md.trim.nonEmpty => md
      case _ => s"Design PR for feature `${feature.id.value}` — ${feature.manifest.title}."
    }

  private def specMessage(feature: Feature, brief: String): String =
    s"""We are starting a new feature for this repository: ${feature.manifest.title}.
       |
       |$brief
       |
       |Follow your instructions to design the feature and decompose it into pieces.""".stripMargin

  private def revisionMessage(round: Int): String =
    s"""The design reviewer requested changes (round $round); the review is appended to design.md under
       |"## Reviewer Review (round $round)". Revise the design (and the manifest / piece specs if the decomposition
       |changed) to address every blocker, then re-emit the updated files.""".stripMargin

  private def feedbackMessage(feature: Feature, round: Int): String =
    val bundle = relPath(paths.audit(feature.id, s"design-pr-feedback-round-$round.md"))
    s"""Human feedback on the design PR has been written to $bundle. Update the design assets to address it,
       |then stop so Forge can re-push the design branch.""".stripMargin

  private def implementBody(feature: Feature, piece: PieceId): String =
    s"""Implement piece `${piece.value}` of feature `${feature.id.value}`.
       |
       |Piece spec: ${relPath(paths.pieceSpec(feature.id, piece))}
       |Feature design: ${relPath(paths.design(feature.id))}
       |
       |Make the acceptance criteria in the piece spec pass. Do not commit — Forge will commit your changes
       |after you settle.""".stripMargin

  private def fixupBody(feature: Feature, piece: PieceId, attempt: Int): String =
    s"""Fix up piece `${piece.value}` of feature `${feature.id.value}` (attempt $attempt).
       |
       |Failures to address: ${relPath(failuresFile(feature.id, piece))}
       |Piece spec: ${relPath(paths.pieceSpec(feature.id, piece))}
       |
       |Make the smallest change that resolves the recorded failures. Do not commit — Forge will commit.""".stripMargin

  // --- small helpers --------------------------------------------------------

  private def promptPath(method: String): os.Path = paths.userPromptsDir / s"$method.$cli.md"

  private def relPath(p: os.Path): String = p.relativeTo(paths.repoRoot).toString

  /** `pieces/<p>.failures.md` (§11.5) — derived from the piece-spec accessor so no `.forge` literal lives here. */
  private def failuresFile(feature: io.forge.core.FeatureId, piece: PieceId): os.Path =
    paths.pieceSpec(feature, piece) / os.up / s"${piece.value}.failures.md"

  private def snapshotTag(feature: Feature, round: Int): String =
    s"${feature.manifest.branchPrefix}/_snapshots/${feature.id.value}/design-r$round"

  private def pieceOf(feature: Feature, piece: PieceId): Option[Piece] =
    feature.manifest.pieces.find(_.id == piece)

  private def settled(phase: SessionPhase): FsmEvent = FsmEvent.Settled(phase, SettleOutcome.Clean)

  private def openSnapshot(pr: PrNumber): PrSnapshot =
    PrSnapshot(pr, PrState.Open, None, None, CheckRollup(Vector.empty, Vector.empty), None, Vector.empty, Some(true))

  private def checkPieceSpecs(feature: Feature): Either[String, Unit] =
    val missing = feature.manifest.pieces.filterNot(p => os.exists(paths.pieceSpec(feature.id, p.id)))
    if missing.isEmpty then Right(())
    else Left(s"spec coherence: missing piece spec(s): ${missing.map(_.id.value).mkString(", ")}")

  private def et[E, A](io: IO[Either[E, A]])(render: E => String): EitherT[IO, String, A] =
    EitherT(io.map(_.left.map(render)))

  /** Pre-commit safety (dogfood #3 finding #2): refuse to `git commit` unless `HEAD` is the branch Forge expects to be
    * on. Forge checks out the design / piece branch, then a *long* driver session runs before the matching commit/push
    * — a window in which a concurrent external git operation in the shared driving worktree can move `HEAD`. In dogfood
    * #3 an operator excursion left `HEAD` on `main`, so Forge's piece commit landed on `main` and was pushed straight
    * to the shared remote, bypassing the PR. Asserting `HEAD == expected` immediately before every `git.commit` turns
    * that silent corruption into a `Left` → `HarnessError` → `NeedsHumanIntervention`: no commit is made, so nothing
    * reaches the wrong branch. A detached `HEAD` (empty `git branch --show-current`) surfaces as the
    * `git.currentBranch` `ParseFailure`, which is also refused here.
    */
  private def assertHeadIs(expected: BranchName): EitherT[IO, String, Unit] =
    et(git.currentBranch)(gitErr).flatMap { actual =>
      EitherT.cond[IO](
        actual == expected,
        (),
        s"refusing to commit: HEAD is '${actual.value}' but Forge expected the '${expected.value}' branch — the " +
          "driving worktree was moved off the expected branch (concurrent git in the worktree?); no commit was made"
      )
    }

  private def specErr(e: SpecStoreError): String = e match
    case SpecStoreError.NotFound(p) => s"spec not found: $p"
    case SpecStoreError.Malformed(p, c) => s"spec malformed $p: ${c.getMessage}"
    case SpecStoreError.IoFailure(p, c) => s"spec io error $p: ${c.getMessage}"

  private def docErr(e: DocSyncError): String = e match
    case DocSyncError.TemplateMissing(p) => s"template missing: $p"
    case DocSyncError.TemplateMalformed(p, c) => s"template malformed $p: ${c.getMessage}"
    case DocSyncError.RenderFailure(c) => s"decomposition render failure: ${c.getMessage}"
    case DocSyncError.SpecStoreFailure(s) => specErr(s)

  private def gitErr(e: GitError): String = e.message
  private def ghErr(e: GhError): String = e.message
  private def branchErr(e: BranchError): String = e.message

object RealSideEffects:

  /** Wire-level statusCheckRollup conclusion values that count as a failure, mirroring CheckConclusion and
    * CiReadiness.isBad; kept as raw strings so the helper can scan the gh JSON without a full decode.
    */
  private val BadConclusions: Set[String] =
    Set("FAILURE", "TIMED_OUT", "CANCELLED", "STARTUP_FAILURE", "ACTION_REQUIRED")

  /** First failed Actions check's run id from a gh pr view statusCheckRollup payload: the actions run id segment of its
    * detailsUrl. None when the rollup is absent, has no failed Actions CheckRun, or that check carries no parseable run
    * id.
    */
  def failingRunId(json: ujson.Value): Option[String] =
    json.objOpt
      .flatMap(_.get("statusCheckRollup"))
      .flatMap(_.arrOpt)
      .flatMap { arr =>
        arr.iterator
          .flatMap(_.objOpt)
          .find(o => o.get("conclusion").flatMap(_.strOpt).exists(BadConclusions.contains))
          .flatMap(_.get("detailsUrl"))
          .flatMap(_.strOpt)
          .flatMap(CheckResult.runIdFrom)
      }

  /** Pure projection of `git status --porcelain -z [--ignored]` rows into the [[FileChange]] set the
    * [[ChangeCollector]] classifies. `gitIgnored` carries the `!!` bit §10.1 rule 4 needs (the classifier is git-less).
    * A `Renamed` row carries the source path; otherwise the index/worktree status chars pick add / delete / modify.
    */
  def statusToFileChanges(repoRoot: os.Path, entries: Vector[StatusEntry]): Vector[FileChange] =
    entries.map(toFileChange(repoRoot, _))

  private def toFileChange(repoRoot: os.Path, e: StatusEntry): FileChange =
    val abs = repoRoot / os.RelPath(e.path)
    val kind =
      e.origPath match
        case Some(from) => FileChangeKind.Renamed(repoRoot / os.RelPath(from))
        case None =>
          if e.index == 'D' || e.worktree == 'D' then FileChangeKind.Deleted
          else if e.index == 'A' || (e.index == '?' && e.worktree == '?') || e.ignored then FileChangeKind.Added
          else FileChangeKind.Modified
    FileChange(abs, kind, gitIgnored = e.ignored)
