package io.forge.app.orchestrator

import cats.data.EitherT
import cats.effect.IO
import io.forge.agents.{Connector, DesignReviewInput, FixupPrompt, ImplementationPrompt, PrReviewInput, RefineInput}
import io.forge.app.config.ForgeConfig
import io.forge.core.{PieceId, PrNumber}
import io.forge.core.fsm.{Feature, FsmEvent, SessionPhase, SettleOutcome}
import io.forge.core.manifest.Piece
import io.forge.core.paths.ForgePaths
import io.forge.core.pr.{CheckRollup, PrSnapshot, PrState}
import io.forge.git.branch.{BranchError, BranchManager}
import io.forge.git.branch.protection.RequiredChecksOverlay
import io.forge.git.cli.{GhClient, GhError, GitClient, GitError, StatusEntry}
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
    config: ForgeConfig
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

  private def writeFailures(feature: Feature, piece: PieceId): IO[Unit] =
    feature.manifest.pieces.find(_.id == piece).flatMap(_.prNumber) match
      case None => IO.unit // no PR yet (CI-failed before PR open is not a real path); nothing to report
      case Some(pr) =>
        gh.prChecks(pr).flatMap { res =>
          val report = res.fold(e => s"(could not fetch CI checks via gh: ${e.message})", _.trim)
          IO.blocking(
            os.write.over(failuresFile(feature.id, piece), failuresMd(piece, pr, report), createFolders = true)
          )
        }

  private def failuresMd(piece: PieceId, pr: PrNumber, checksReport: String): String =
    s"""# Piece ${piece.value} — failures to address (PR #${pr.value})
       |
       |These CI checks on the piece's PR did not pass (`gh pr checks`):
       |
       |```
       |$checksReport
       |```
       |
       |Make the smallest change that makes the failing check(s) pass. For a formatting
       |check, run the project's formatter on the changed files (a targeted format is
       |fine — do NOT run the full test suite). Then stop; Forge commits and re-runs CI.
       |""".stripMargin

  private def resumeDesign(feature: Feature, message: String): IO[ActiveSession] =
    IO.fromOption(feature.designSessionId)(
      new IllegalStateException("resume design driver: feature.designSessionId is empty")
    ).flatMap(connector.resumeStreamingSpec(_, promptPath("specify"), message))
      .map(ActiveSession(SessionPhase.DesignRevision, _))

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
      _ <- et(git.commit(s"[design] ${feature.manifest.title}"))(gitErr)
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
          _ <- et(git.commit(s"feat(${feature.id.value}): ${p.title}"))(gitErr)
          _ <- et(branchManager.pushCurrentBranch())(branchErr)
          body <- EitherT.liftF[IO, String, String](piecePrBody(feature, p))
          pr <- et(branchManager.createPr(p.title, body, feature.manifest.baseBranch))(branchErr)
        yield FsmEvent.PrOpened(piece, pr)).value

  override def classifyCommitPush(feature: Feature, piece: PieceId, pr: PrNumber): IO[Either[String, FsmEvent]] =
    pieceOf(feature, piece) match
      case None => IO.pure(Left(s"piece not found in manifest: ${piece.value}"))
      case Some(p) =>
        (for
          included <- classifyChanges
          _ <- stageChanges(included)
          _ <- et(git.commit(s"fix(${feature.id.value}): ${p.title}"))(gitErr)
          _ <- et(branchManager.pushCurrentBranch())(branchErr)
        yield settled(SessionPhase.Fixup)).value

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
