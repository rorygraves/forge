package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.cli.CliParser
import io.forge.app.config.ForgeConfig
import io.forge.core.{FeatureId, PieceId}
import io.forge.core.fsm.{Feature, FsmState}
import io.forge.core.log.Action
import io.forge.core.manifest.{FileManifestStore, Manifest, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache

import upickle.default as upickle

/** Task 1.4.13 M4 — `forge status [<feature>]`. Per §2.5 polish: current state, current piece, last action, and budget
  * remaining at a glance.
  *
  * **Read-only (§15).** Status never acquires the lock and never mutates state. It reads the rebuildable
  * [[FileStateCache]] directly (the §11.0 fast path) rather than running [[io.forge.core.state.RebuildState]] — a
  * `forge run` may be mid-flight, and verifying-against-log would write. When no cache is present yet (a freshly `forge
  * new`'d feature, or post-`rebuild-state` deletion) the report renders from the manifest alone and points the operator
  * at `forge run` / `forge rebuild-state`. The "last action" line decodes the final NDJSON line of
  * `.forge/log/<feature>.jsonl` in place — it does **not** call `ActionLog.replay`, whose repair-on-read could write.
  *
  * With no feature argument it prints a one-line-per-feature overview across `.forge/specs/`. The §2.5 golden-file
  * formatting polish is Task 1.4.15 O2; this is the v1 rendering it builds on.
  */
object StatusReport:

  private[command] enum Result:
    case Rendered(text: String)
    case FeatureNotFound(id: FeatureId)

  def run(paths: ForgePaths, config: ForgeConfig, args: Vector[String]): IO[ExitCode] =
    CliParser.optionalFeature(args) match
      case Left(err) => Console[IO].errorln(s"forge: ${err.message}").as(ExitCode(64))
      case Right(None) => overview(paths).flatMap(text => Console[IO].println(text).as(ExitCode.Success))
      case Right(Some(id)) =>
        describe(paths, config, id).flatMap {
          case Result.Rendered(text) => Console[IO].println(text).as(ExitCode.Success)
          case Result.FeatureNotFound(missing) =>
            Console[IO]
              .errorln(s"forge status ${missing.value}: no such feature (no manifest at ${paths.manifest(missing)}).")
              .as(ExitCode(1))
        }

  /** Single-feature report. Reads the manifest (authoritative title/mode/pieces), the optional state cache, and the
    * last logged action.
    */
  private[command] def describe(paths: ForgePaths, config: ForgeConfig, id: FeatureId): IO[Result] =
    IO.blocking(os.exists(paths.manifest(id))).flatMap {
      case false => IO.pure(Result.FeatureNotFound(id))
      case true =>
        new FileManifestStore(paths).load(id).flatMap {
          case Left(failure) =>
            IO.pure(
              Result.Rendered(s"feature ${id.value}: manifest present but unreadable — ${failure.cause.getMessage}")
            )
          case Right(manifest) =>
            for
              cached <- new FileStateCache(paths).load(id)
              last <- lastAction(paths, id)
            yield Result.Rendered(renderFeature(manifest, cached, last, config))
        }
    }

  /** No-feature overview: one summary line per feature directory under `.forge/specs/`. */
  private[command] def overview(paths: ForgePaths): IO[String] =
    val cache = new FileStateCache(paths)
    IO.blocking {
      if !os.exists(paths.specsRoot) then Vector.empty[FeatureId]
      else
        os.list(paths.specsRoot)
          .filter(p => os.isDir(p) && os.exists(p / "manifest.json"))
          .map(p => FeatureId(p.last))
          .toVector
          .sortBy(_.value)
    }.flatMap {
      case ids if ids.isEmpty => IO.pure(s"no features found under ${paths.specsRoot}.")
      case ids => summaryLines(ids, cache).map(_.mkString("\n"))
    }

  /** One `id: <state>` line per feature, reading each one's state cache (absent cache → a "run forge run" note). */
  private def summaryLines(ids: Vector[FeatureId], cache: FileStateCache): IO[Vector[String]] =
    ids.foldLeft(IO.pure(Vector.empty[String])) { (accIO, id) =>
      for
        acc <- accIO
        cached <- cache.load(id)
      yield acc :+ s"${id.value}: ${cached.map(f => stateLabel(f.state)).getOrElse("no state cache (run forge run)")}"
    }

  /** Pure render of a single feature's status block — unit-testable without I/O. */
  private[command] def renderFeature(
      manifest: Manifest,
      cached: Option[Feature],
      lastAction: Option[Action],
      config: ForgeConfig
  ): String =
    val state = cached.map(_.state)
    val stateLine = state.map(stateLabel).getOrElse("no state cache yet — run `forge run` or `forge rebuild-state`")
    val pieceLine = state.flatMap(pieceOf) match
      case Some(p) =>
        val title = manifest.pieces.find(_.id == p).map(pp => s" — ${pp.title}").getOrElse("")
        s"${p.value}$title"
      case None => pieceSummary(manifest)
    val lastLine = lastAction match
      case Some(a) => s"${a.kind} @ ${a.at}"
      case None => "— (no actions logged)"
    val budgetLine = cached match
      case Some(f) =>
        f"feature $$${f.cost.feature.toDouble}%.2f / $$${config.maxFeatureCostUsd}%.2f · " +
          f"piece $$${f.cost.piece.toDouble}%.2f / $$${config.maxPieceCostUsd}%.2f"
      case None => "— (no cost recorded yet)"
    s"""feature ${manifest.featureId.value} — "${manifest.title}"  [${manifest.mode}]
       |  state:   $stateLine
       |  piece:   $pieceLine
       |  last:    $lastLine
       |  budget:  $budgetLine""".stripMargin

  /** Manifest piece-count summary, shown when no single piece is active. */
  private def pieceSummary(manifest: Manifest): String =
    if manifest.pieces.isEmpty then "— (no pieces decomposed yet)"
    else
      val merged = manifest.pieces.count(_.status == PieceStatus.Merged)
      val inProgress = manifest.pieces.count(_.status == PieceStatus.InProgress)
      val pending = manifest.pieces.count(_.status == PieceStatus.Pending)
      s"— (${manifest.pieces.size} pieces: $merged merged, $inProgress in progress, $pending pending)"

  /** The active piece id a state carries, if any. */
  private def pieceOf(s: FsmState): Option[PieceId] = s match
    case FsmState.PieceImplementing(p) => Some(p)
    case FsmState.PieceAwaitingCi(p, _) => Some(p)
    case FsmState.PieceAwaitingReview(p, _) => Some(p)
    case FsmState.PieceCiFailed(p, _, _) => Some(p)
    case FsmState.PieceReviewFailed(p, _, _) => Some(p)
    case FsmState.PieceFixingUp(p, _, _) => Some(p)
    case FsmState.PieceAwaitingMerge(p, _) => Some(p)
    case FsmState.Refining(p, _, _) => Some(p)
    case _ => None

  /** A compact human label for each FSM state. */
  private[command] def stateLabel(s: FsmState): String = s match
    case FsmState.Drafting => "drafting (spec not started)"
    case FsmState.InteractiveSpec => "interactive spec in progress"
    case FsmState.DesignReviewing(round) => s"design review round $round"
    case FsmState.DesignNeedsHumanInput(round, questions) =>
      s"design review round $round — awaiting human answers (${questions.size} question(s))"
    case FsmState.DesignAwaitingMerge(pr) => s"design PR #${pr.value} open — awaiting merge"
    case FsmState.DesignPrFeedback(pr, round) => s"design PR #${pr.value} feedback round $round"
    case FsmState.DesignReady => "design merged — ready to implement"
    case FsmState.PieceImplementing(p) => s"implementing piece ${p.value}"
    case FsmState.PieceAwaitingCi(p, pr) => s"piece ${p.value} PR #${pr.value} — awaiting CI"
    case FsmState.PieceAwaitingReview(p, pr) => s"piece ${p.value} PR #${pr.value} — awaiting review/merge"
    case FsmState.PieceCiFailed(p, pr, attempt) => s"piece ${p.value} PR #${pr.value} — CI failed (attempt $attempt)"
    case FsmState.PieceReviewFailed(p, pr, attempt) =>
      s"piece ${p.value} PR #${pr.value} — review requested changes (attempt $attempt)"
    case FsmState.PieceFixingUp(p, pr, attempt) =>
      s"piece ${p.value} PR #${pr.value} — fix-up running (attempt $attempt)"
    case FsmState.PieceAwaitingMerge(p, pr) => s"piece ${p.value} PR #${pr.value} — green, awaiting merge"
    case FsmState.Refining(p, pr, _) => s"refining after merge of piece ${p.value} (PR #${pr.value})"
    case FsmState.PlanningUpdate(reason, _) => s"planning update proposed — $reason"
    case FsmState.NeedsHumanIntervention(reason, _) => s"needs human intervention — $reason"
    case FsmState.FeatureDone => "✓ complete (all pieces merged and refined)"
    case FsmState.Abandoned(reason) => s"abandoned — $reason"

  /** Decode the last non-blank NDJSON line of the feature log as an [[Action]] without rewriting the file. A malformed
    * tail decodes to `None` (status degrades to "no actions logged" rather than failing).
    */
  private[command] def lastAction(paths: ForgePaths, id: FeatureId): IO[Option[Action]] =
    IO.blocking {
      val p = paths.featureLog(id)
      if !os.exists(p) then None
      else
        os.read
          .lines(p)
          .reverseIterator
          .map(_.trim)
          .find(_.nonEmpty)
          .flatMap(line => scala.util.Try(upickle.read[Action](line)).toOption)
    }
