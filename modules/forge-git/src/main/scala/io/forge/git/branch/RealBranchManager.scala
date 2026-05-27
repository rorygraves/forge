package io.forge.git.branch

import cats.data.EitherT
import cats.effect.{Clock, IO}
import io.forge.core.{BranchName, FeatureId, PieceId, PrNumber, Sha}
import io.forge.core.manifest.Manifest
import io.forge.git.branch.protection.{BranchProtectionCache, CacheKey, OverlaySource, RequiredChecksOverlay}
import io.forge.git.cli.{FastForwardResult, GhClient, GhError, GitClient, GitError}

/** Concrete [[BranchManager]] composed over [[GhClient]] / [[GitClient]] / [[BranchProtectionCache]].
  *
  * Every public method is small and routes through one of the private classifier helpers below. The aim is that a
  * Slice-4 reviewer can tell at a glance what each method does: how it talks to gh/git, how it maps subprocess errors
  * into [[BranchError]], and where the FSM-shaped vocabulary belongs (which is *outside* this file — BranchManager
  * surfaces typed outcomes; FSM transitions are the orchestrator's vocabulary).
  */
final class RealBranchManager(
    git: GitClient,
    gh: GhClient,
    cache: BranchProtectionCache,
    clock: Clock[IO]
) extends BranchManager:

  override def preflight(command: ForgeCommand, manifest: Option[Manifest]): IO[PreflightReport] =
    command match
      case ForgeCommand.New(_) => preflightCleanOnly("new")
      case ForgeCommand.Spec(feature) => preflightSpec(feature, manifest)
      case ForgeCommand.Run(_) => preflightCleanOnly("run")
      case ForgeCommand.ResumeAfterHumanPush(feature, piece) => preflightAfterHumanPush(feature, piece, manifest)
      case ForgeCommand.ResumeRunFixup(feature, piece) => preflightRunFixup(feature, piece, manifest)
      case ForgeCommand.ResumeCommitHumanFix(feature, piece) => preflightCommitHumanFix(feature, piece, manifest)
      case ForgeCommand.Reconcile(_) => IO.pure(PreflightReport(Vector.empty))
      case ForgeCommand.RefreshCache(_) => IO.pure(PreflightReport(Vector.empty))
      case ForgeCommand.ReadOnly(_) => IO.pure(PreflightReport(Vector.empty))
      case ForgeCommand.UnlockForce => IO.pure(PreflightReport(Vector.empty))
      case ForgeCommand.Abandon(_) => IO.pure(PreflightReport(Vector.empty))

  override def syncBase(base: BranchName): IO[Either[BranchError, BaseSnapshot]] =
    git.fastForwardBase(base).map {
      case Right(FastForwardResult.Updated(sha)) => Right(BaseSnapshot(base, sha))
      case Right(FastForwardResult.AlreadyUpToDate(sha)) => Right(BaseSnapshot(base, sha))
      case Right(FastForwardResult.LocallyDiverged(local, remote)) => Left(BranchError.BaseDiverged(local, remote))
      case Left(err) => Left(BranchError.GitFailure(err))
    }

  override def createDesignBranch(
      feature: FeatureId,
      branchPrefix: String,
      base: BaseSnapshot
  ): IO[Either[BranchError, BranchName]] =
    val branch = BranchNaming.designBranch(branchPrefix, feature)
    // Cut from the captured `BaseSnapshot.sha`, not `base.base`. The base ref may have moved between syncBase
    // (which read the SHA) and this checkout; cutting from the SHA pins the new branch to the commit syncBase
    // returned, matching the trait's BM7 contract.
    git.checkout(branch, Some(base.sha.value)).map(_.left.map(BranchError.GitFailure(_)).map(_ => branch))

  override def createPieceBranch(
      feature: FeatureId,
      piece: PieceId,
      branchPrefix: String,
      base: BaseSnapshot
  ): IO[Either[BranchError, (BranchName, Sha)]] =
    val branch = BranchNaming.pieceBranch(branchPrefix, feature, piece)
    // See `createDesignBranch` — cutting from `base.sha` guarantees the returned `baseSha` matches the commit the
    // branch was actually created from, so the Slice-4 orchestrator's `manifest.pieces[i].baseSha` persistence
    // (carry-forward S2-5) is consistent with the on-disk branch.
    git.checkout(branch, Some(base.sha.value)).map(_.left.map(BranchError.GitFailure(_)).map(_ => (branch, base.sha)))

  override def baseFreshness(
      pr: PrNumber,
      expectedBaseSha: Sha,
      autoUpdate: Boolean
  ): IO[Either[BranchError, BaseFreshness]] =
    gh.prView(pr, Vector("baseRefName", "baseRefOid")).flatMap {
      case Left(err) => IO.pure(Left(promoteGhError(err)))
      case Right(json) =>
        parseBaseRefOid(json) match
          case Left(detail) => IO.pure(Left(BranchError.ParseFailure("baseFreshness.baseRefOid", detail)))
          case Right(observed) =>
            if observed == expectedBaseSha then IO.pure(Right(BaseFreshness.UpToDate))
            else if autoUpdate then runUpdateBranchAndReread(pr)
            else IO.pure(Right(BaseFreshness.Behind(expectedBaseSha, observed)))
    }

  /** After `gh pr update-branch`, the PR's `baseRefOid` advances; we re-read it so [[BaseFreshness.Updated]] can carry
    * the new value. Surfacing the post-update SHA lets the Slice-4 orchestrator persist `manifest.pieces[i].baseSha`
    * (S2-5) — otherwise the next readiness pass compares the same stale `expectedBaseSha` and re-triggers
    * `update-branch` on every poll.
    */
  private def runUpdateBranchAndReread(pr: PrNumber): IO[Either[BranchError, BaseFreshness]] =
    gh.prUpdateBranch(pr).flatMap {
      case Left(err) => IO.pure(Left(promoteGhError(err)))
      case Right(_) =>
        gh.prView(pr, Vector("baseRefOid")).map {
          case Left(err) => Left(promoteGhError(err))
          case Right(json) =>
            parseBaseRefOid(json) match
              case Left(detail) =>
                Left(BranchError.ParseFailure("baseFreshness.baseRefOid.post-update", detail))
              case Right(newSha) => Right(BaseFreshness.Updated(newSha))
        }
    }

  override def pushCurrentBranch(forceWithLease: Boolean): IO[Either[BranchError, Unit]] =
    val program = for
      branch <- EitherT(git.currentBranch).leftMap(BranchError.GitFailure(_): BranchError)
      _ <- EitherT(git.push(branch, force = false, forceWithLease = forceWithLease))
        .leftMap(promoteGitError)
    yield ()
    program.value

  override def createPr(title: String, body: String, base: BranchName): IO[Either[BranchError, PrNumber]] =
    val program = for
      head <- EitherT(git.currentBranch).leftMap(BranchError.GitFailure(_): BranchError)
      pr <- EitherT(gh.prCreate(title, body, base, head)).leftMap(promoteGhError)
    yield pr
    program.value

  override def updatePrBranch(pr: PrNumber): IO[Either[BranchError, Unit]] =
    gh.prUpdateBranch(pr).map(_.left.map(promoteGhError))

  override def tagSnapshot(name: String, sha: Sha): IO[Either[BranchError, Unit]] =
    git.tag(name, sha).map(_.left.map(BranchError.GitFailure(_)))

  override def pushTag(name: String): IO[Either[BranchError, Unit]] =
    git.pushTag(name).map(_.left.map(BranchError.GitFailure(_)))

  override def deleteRemoteTag(name: String): IO[Either[BranchError, Unit]] =
    git.deleteRemoteTag(name).map(_.left.map(BranchError.GitFailure(_)))

  override def pruneSnapshotTags(
      feature: FeatureId,
      branchPrefix: String,
      retention: Int,
      alsoRemote: Boolean
  ): IO[Either[BranchError, Vector[String]]] =
    val pattern = BranchNaming.snapshotTagPrefix(branchPrefix, feature) + "*"
    git.listTags(Some(pattern)).flatMap {
      case Left(err) => IO.pure(Left(BranchError.GitFailure(err)))
      case Right(tags) =>
        val parsed =
          tags.flatMap(t => BranchNaming.parseSnapshotRound(t, branchPrefix, feature).map(p => (t, p._1, p._2)))
        // Keep the `retention` newest by round number (independent of kind so all rounds compete fairly), then return
        // the pruned subset in ascending round order so audit logs read "deleted r1, r2" rather than the reverse.
        val sortedAscending = parsed.sortBy(_._3)
        val keep = retention.max(0)
        val toDelete = sortedAscending.dropRight(keep).map(_._1)
        val program = toDelete.foldLeft(EitherT.rightT[IO, BranchError](Vector.empty[String])) { (acc, name) =>
          for
            pruned <- acc
            _ <- EitherT(git.deleteLocalTag(name)).leftMap(BranchError.GitFailure(_): BranchError)
            _ <-
              if alsoRemote then EitherT(git.deleteRemoteTag(name)).leftMap(BranchError.GitFailure(_): BranchError)
              else EitherT.rightT[IO, BranchError](())
          yield pruned :+ name
        }
        program.value
    }

  override def requiredChecksOverlay(
      feature: FeatureId,
      base: BranchName,
      epoch: Long
  ): IO[Either[BranchError, RequiredChecksOverlay]] =
    val key = CacheKey(feature, base, epoch)
    cache.get(key).flatMap {
      case Some(hit) => IO.pure(Right(hit))
      case None =>
        gh.apiBranchProtection(base).flatMap {
          case Right(Some(json)) =>
            val required = parseRequiredChecks(json)
            storeOverlay(key, RequiredChecksOverlay(required, _, OverlaySource.Protected))
          case Right(None) =>
            // 404 from `gh api …/branch-protection/required_status_checks` — no protection on this base.
            storeOverlay(key, RequiredChecksOverlay(Set.empty, _, OverlaySource.Unprotected))
          case Left(_: GhError.Unauthorized) =>
            // Pragmatic fallback per C6: caller lacks `admin:repo`, treat as unprotected. Slice 4 logs the
            // `harness.protection_unauthorized` audit event off [[OverlaySource.Unauthorized]]; this is
            // intentionally not a [[BranchError]] so a missing repo-admin permission doesn't block `forge run`.
            storeOverlay(key, RequiredChecksOverlay(Set.empty, _, OverlaySource.Unauthorized))
          case Left(err: GhError.RateLimited) => IO.pure(Left(BranchError.RateLimited(err.retryAfter)))
          case Left(err) => IO.pure(Left(BranchError.GhFailure(err)))
        }
    }

  private def storeOverlay(
      key: CacheKey,
      build: java.time.Instant => RequiredChecksOverlay
  ): IO[Either[BranchError, RequiredChecksOverlay]] =
    clock.realTimeInstant.flatMap { now =>
      val overlay = build(now)
      cache.put(key, overlay).as(Right(overlay))
    }

  // --- preflight helpers ----------------------------------------------------

  private def preflightCleanOnly(name: String): IO[PreflightReport] =
    worktreeCleanCheck(name).map(c => PreflightReport(Vector(c)))

  private def preflightSpec(feature: FeatureId, manifest: Option[Manifest]): IO[PreflightReport] =
    // §15: clean worktree + on the design branch.
    manifest match
      case None => IO.pure(PreflightReport(Vector(missingManifestFailure("spec"))))
      case Some(m) =>
        val expected = BranchNaming.designBranch(m.branchPrefix, feature)
        for
          clean <- worktreeCleanCheck("spec")
          onBranch <- onExpectedBranchCheck(expected, label = "design")
        yield PreflightReport(Vector(clean, onBranch))

  private def preflightRunFixup(
      feature: FeatureId,
      piece: PieceId,
      manifest: Option[Manifest]
  ): IO[PreflightReport] =
    // §15: clean worktree + on the piece branch.
    manifest match
      case None => IO.pure(PreflightReport(Vector(missingManifestFailure("resume:run-fixup"))))
      case Some(m) =>
        val expected = BranchNaming.pieceBranch(m.branchPrefix, feature, piece)
        for
          clean <- worktreeCleanCheck("resume:run-fixup")
          onBranch <- onExpectedBranchCheck(expected, label = "piece")
        yield PreflightReport(Vector(clean, onBranch))

  private def preflightAfterHumanPush(
      feature: FeatureId,
      piece: PieceId,
      manifest: Option[Manifest]
  ): IO[PreflightReport] =
    // §15: clean worktree + on the piece branch + PR head == local HEAD. The piece must already have a PR
    // recorded (the after-human-push flow only makes sense after a piece's PR exists), so a missing
    // `prNumber` is a hard failure.
    manifest match
      case None => IO.pure(PreflightReport(Vector(missingManifestFailure("resume:after-human-push"))))
      case Some(m) =>
        val expectedBranch = BranchNaming.pieceBranch(m.branchPrefix, feature, piece)
        val prOpt = m.pieces.find(_.id == piece).flatMap(_.prNumber)
        for
          clean <- worktreeCleanCheck("resume:after-human-push")
          onBranch <- onExpectedBranchCheck(expectedBranch, label = "piece")
          headCheck <- prOpt match
            case None => IO.pure(missingPrFailure(piece))
            case Some(pr) => prHeadMatchesLocalHeadCheck(pr)
        yield PreflightReport(Vector(clean, onBranch, headCheck))

  private def preflightCommitHumanFix(
      feature: FeatureId,
      piece: PieceId,
      manifest: Option[Manifest]
  ): IO[PreflightReport] =
    // BM6 / §15: current branch must match the derived piece branch. Worktree dirtiness is allowed (the human's
    // unstaged fix IS the input to this command), so no `worktree.clean` check.
    manifest match
      case None =>
        IO.pure(PreflightReport(Vector(missingManifestFailure("resume:commit-human-fix"))))
      case Some(m) =>
        val expected = BranchNaming.pieceBranch(m.branchPrefix, feature, piece)
        git.currentBranch.map {
          case Right(current) if current == expected =>
            PreflightReport(Vector(PreflightCheck.Passed("branch.matches-piece")))
          case Right(current) =>
            PreflightReport(
              Vector(
                PreflightCheck.Failed(
                  id = "branch.matches-piece",
                  reason = s"You are on '${current.value}', the active piece branch is '${expected.value}'. " +
                    s"Switch branches and retry, or use `--after-human-push` if your fix is already pushed.",
                  escapableViaForce = false
                )
              )
            )
          case Left(err) =>
            PreflightReport(
              Vector(
                PreflightCheck.Failed(
                  id = "branch.matches-piece",
                  reason = s"unable to read current branch: ${err.message}",
                  escapableViaForce = false
                )
              )
            )
        }

  // --- preflight check building blocks ----------------------------------

  private def worktreeCleanCheck(commandName: String): IO[PreflightCheck] =
    git.isWorktreeClean.map {
      case Right(true) => PreflightCheck.Passed("worktree.clean")
      case Right(false) =>
        PreflightCheck.Failed(
          id = "worktree.clean",
          reason = s"`forge $commandName` requires a clean worktree (`git status --porcelain` non-empty)",
          escapableViaForce = true
        )
      case Left(err) =>
        PreflightCheck.Failed(
          id = "worktree.clean",
          reason = s"unable to read worktree status: ${err.message}",
          escapableViaForce = false
        )
    }

  /** "Current branch matches expected" check. `label` is the suffix on the check id (`branch.matches-design`,
    * `branch.matches-piece`) and the human-readable noun in the failure message ("the active design branch is …"). Not
    * escapable via `--force` — being on the wrong branch corrupts the operation entirely, mirroring the BM6 precedent
    * for `resume --commit-human-fix`.
    */
  private def onExpectedBranchCheck(expected: BranchName, label: String): IO[PreflightCheck] =
    git.currentBranch.map {
      case Right(current) if current == expected =>
        PreflightCheck.Passed(s"branch.matches-$label")
      case Right(current) =>
        PreflightCheck.Failed(
          id = s"branch.matches-$label",
          reason = s"You are on '${current.value}', the active $label branch is '${expected.value}'. " +
            s"Switch branches and retry.",
          escapableViaForce = false
        )
      case Left(err) =>
        PreflightCheck.Failed(
          id = s"branch.matches-$label",
          reason = s"unable to read current branch: ${err.message}",
          escapableViaForce = false
        )
    }

  /** §15 after-human-push: PR head SHA from `gh pr view --json headRefOid` must equal `git rev-parse HEAD`. Both gh and
    * git errors degrade to a failed check rather than escaping as a [[BranchError]] — preflight is a reporting surface,
    * not an FSM action.
    */
  private def prHeadMatchesLocalHeadCheck(pr: PrNumber): IO[PreflightCheck] =
    gh.prView(pr, Vector("headRefOid")).flatMap {
      case Left(err) =>
        IO.pure(
          PreflightCheck.Failed(
            id = "pr.head-matches-local",
            reason = s"unable to read PR #${pr.value} head SHA: ${err.message}",
            escapableViaForce = false
          )
        )
      case Right(json) =>
        parseHeadRefOid(json) match
          case Left(detail) =>
            IO.pure(
              PreflightCheck.Failed(
                id = "pr.head-matches-local",
                reason = s"unable to parse PR #${pr.value} headRefOid: $detail",
                escapableViaForce = false
              )
            )
          case Right(prHead) =>
            git.currentSha.map {
              case Right(localHead) if localHead == prHead =>
                PreflightCheck.Passed("pr.head-matches-local")
              case Right(localHead) =>
                PreflightCheck.Failed(
                  id = "pr.head-matches-local",
                  reason = s"PR #${pr.value} head (${prHead.value}) does not match local HEAD (${localHead.value}). " +
                    "Push the local commits before retrying with `--after-human-push`.",
                  escapableViaForce = false
                )
              case Left(err) =>
                PreflightCheck.Failed(
                  id = "pr.head-matches-local",
                  reason = s"unable to read local HEAD: ${err.message}",
                  escapableViaForce = false
                )
            }
    }

  private def missingManifestFailure(commandName: String): PreflightCheck.Failed =
    PreflightCheck.Failed(
      id = "manifest.present",
      reason = s"`forge $commandName` requires a loaded manifest",
      escapableViaForce = false
    )

  private def missingPrFailure(piece: PieceId): PreflightCheck.Failed =
    PreflightCheck.Failed(
      id = "pr.recorded",
      reason = s"piece '${piece.value}' has no PR recorded in the manifest — " +
        "`--after-human-push` requires a piece whose PR was already created",
      escapableViaForce = false
    )

  // --- ujson plumbing -------------------------------------------------------

  private def parseBaseRefOid(json: ujson.Value): Either[String, Sha] =
    parseOidField(json, "baseRefOid")

  private def parseHeadRefOid(json: ujson.Value): Either[String, Sha] =
    parseOidField(json, "headRefOid")

  private def parseOidField(json: ujson.Value, field: String): Either[String, Sha] =
    try
      json.obj.get(field) match
        case Some(ujson.Str(s)) => Sha.fromString(s).left.map(msg => s"$field not a SHA: $msg")
        case Some(other) => Left(s"$field expected String, got $other")
        case None => Left(s"$field missing")
    catch case e: Throwable => Left(s"$field parse: ${e.getMessage}")

  /** Parse a branch-protection JSON payload into the required-check name set.
    *
    * Supports two wire shapes:
    *   - `gh api repos/.../branches/<base>/protection/required_status_checks` →
    *     `{"contexts":[...],"checks":[{"context":"name",...},...]}`. We union `contexts` and `checks[].context`.
    *   - An older / alternative shape with only `contexts`.
    */
  private def parseRequiredChecks(json: ujson.Value): Set[String] =
    val contexts = json.obj.get("contexts") match
      case Some(ujson.Arr(values)) => values.toVector.collect { case ujson.Str(s) => s }.toSet
      case _ => Set.empty[String]
    val checks = json.obj.get("checks") match
      case Some(ujson.Arr(values)) =>
        values.toVector
          .collect { case obj: ujson.Obj =>
            obj.obj.get("context") match
              case Some(ujson.Str(s)) => Some(s)
              case _ => None
          }
          .flatten
          .toSet
      case _ => Set.empty[String]
    contexts ++ checks

  // --- error promotion ------------------------------------------------------

  private def promoteGhError(err: GhError): BranchError = err match
    case GhError.RateLimited(retryAfter, _) => BranchError.RateLimited(retryAfter)
    case other => BranchError.GhFailure(other)

  private def promoteGitError(err: GitError): BranchError = err match
    case GitError.ForceLeaseRejected(branch, _) => BranchError.ForceLeaseRejected(branch)
    case other => BranchError.GitFailure(other)
