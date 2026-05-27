package io.forge.git.branch

import cats.data.EitherT
import cats.effect.{Clock, IO}
import io.forge.core.{BranchName, FeatureId, PieceId, PrNumber, Sha}
import io.forge.core.manifest.Manifest
import io.forge.git.branch.protection.{BranchProtectionCache, CacheKey, RequiredChecksOverlay}
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
      case ForgeCommand.Spec(_) => preflightCleanOnly("spec")
      case ForgeCommand.Run(_) => preflightCleanOnly("run")
      case ForgeCommand.ResumeAfterHumanPush(_, _) => preflightCleanOnly("resume:after-human-push")
      case ForgeCommand.ResumeRunFixup(_, _) => preflightCleanOnly("resume:run-fixup")
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
    git.checkout(branch, Some(base.base)).map(_.left.map(BranchError.GitFailure(_)).map(_ => branch))

  override def createPieceBranch(
      feature: FeatureId,
      piece: PieceId,
      branchPrefix: String,
      base: BaseSnapshot
  ): IO[Either[BranchError, (BranchName, Sha)]] =
    val branch = BranchNaming.pieceBranch(branchPrefix, feature, piece)
    git.checkout(branch, Some(base.base)).map(_.left.map(BranchError.GitFailure(_)).map(_ => (branch, base.sha)))

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
            else if autoUpdate then
              gh.prUpdateBranch(pr).map {
                case Right(_) => Right(BaseFreshness.Updated)
                case Left(err) => Left(promoteGhError(err))
              }
            else IO.pure(Right(BaseFreshness.Behind(expectedBaseSha, observed)))
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
          case Right(json) =>
            val required = json.fold(Set.empty[String])(parseRequiredChecks)
            clock.realTimeInstant.flatMap { now =>
              val overlay = RequiredChecksOverlay(required, now)
              cache.put(key, overlay).as(Right(overlay))
            }
          case Left(_: GhError.Unauthorized) =>
            // Pragmatic fallback per C6: caller lacks `admin:repo`, treat as unprotected. Slice 4 logs the
            // `harness.protection_unauthorized` audit event; this is intentionally not a [[BranchError]].
            clock.realTimeInstant.flatMap { now =>
              val overlay = RequiredChecksOverlay(Set.empty, now)
              cache.put(key, overlay).as(Right(overlay))
            }
          case Left(err: GhError.RateLimited) => IO.pure(Left(BranchError.RateLimited(err.retryAfter)))
          case Left(err) => IO.pure(Left(BranchError.GhFailure(err)))
        }
    }

  // --- preflight helpers ----------------------------------------------------

  private def preflightCleanOnly(name: String): IO[PreflightReport] =
    git.isWorktreeClean.map {
      case Right(true) => PreflightReport(Vector(PreflightCheck.Passed("worktree.clean")))
      case Right(false) =>
        PreflightReport(
          Vector(
            PreflightCheck.Failed(
              id = "worktree.clean",
              reason = s"`forge $name` requires a clean worktree (`git status --porcelain` non-empty)",
              escapableViaForce = true
            )
          )
        )
      case Left(err) =>
        PreflightReport(
          Vector(
            PreflightCheck.Failed(
              id = "worktree.clean",
              reason = s"unable to read worktree status: ${err.message}",
              escapableViaForce = false
            )
          )
        )
    }

  private def preflightCommitHumanFix(
      feature: FeatureId,
      piece: PieceId,
      manifest: Option[Manifest]
  ): IO[PreflightReport] =
    // BM6: current branch must match the derived piece branch. Worktree dirtiness is allowed (the human's
    // unstaged fix IS the input to this command).
    manifest match
      case None =>
        IO.pure(
          PreflightReport(
            Vector(
              PreflightCheck.Failed(
                id = "manifest.present",
                reason = "`forge resume --commit-human-fix` requires a loaded manifest",
                escapableViaForce = false
              )
            )
          )
        )
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

  // --- ujson plumbing -------------------------------------------------------

  private def parseBaseRefOid(json: ujson.Value): Either[String, Sha] =
    try
      json.obj.get("baseRefOid") match
        case Some(ujson.Str(s)) => Sha.fromString(s).left.map(msg => s"baseRefOid not a SHA: $msg")
        case Some(other) => Left(s"baseRefOid expected String, got $other")
        case None => Left("baseRefOid missing")
    catch case e: Throwable => Left(s"baseRefOid parse: ${e.getMessage}")

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
