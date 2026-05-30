package io.forge.app.command

import cats.effect.{Clock, ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.lock.{FileProcessLock, ForceReleaseResult, LockMetadata}
import io.forge.app.orchestrator.{Orchestrator, OrchestratorBuilder}
import io.forge.core.{BranchName, FeatureId}
import io.forge.core.fsm.{Feature, FsmState, ResumeHint, UserCommand}
import io.forge.core.manifest.{FileManifestStore, Manifest}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.RebuildError
import io.forge.git.branch.{ForgeCommand, RealBranchManager}
import io.forge.git.branch.protection.InMemoryBranchProtectionCache
import io.forge.git.cli.{RealGhClient, RealGitClient}
import io.forge.specs.FileSpecStore

/** Task 1.4.9 I3 — per-command handlers.
  *
  * Object names mirror the §17 command set (`new_` avoids the `new` keyword). Task 1.4.9 lands shells; Task 1.4.10
  * (orchestrator) and Task 1.4.13 (`forge spec` REPL / reconcile) wire the real bodies. The exception is [[unlock]] —
  * the §13 `forge unlock --force` recovery body is self-contained ([[FileProcessLock.forceRelease]]) and ships fully
  * here so the recovery path is runnable from day one.
  *
  * A shell prints a `not yet implemented` line to stderr and exits `70` (`EX_SOFTWARE`) — a deliberately non-zero,
  * non-usage code so a script that mistakes a shell for a finished command fails loudly rather than reporting success.
  */
private[command] object Handlers:
  def notImplemented(label: String, landingTask: String): IO[ExitCode] =
    Console[IO].errorln(s"$label: not yet implemented (lands in $landingTask)").as(ExitCode(70))

// --- state-changing (Task 1.4.10 / Task 1.4.13) -----------------------------------

// Braces (not a significant-indentation `:`) because `new_:` would lex as one operator identifier (`varid '_' op`).
object new_ {
  def run(ctx: StateChangingContext, command: ForgeCommand.New): IO[ExitCode] =
    NewFeature.scaffold(ctx.paths, ctx.config, command.feature)
}

object spec:
  def run(ctx: StateChangingContext, command: ForgeCommand.Spec): IO[ExitCode] =
    SpecRepl.execute(ctx.paths, ctx.config, command.feature)

object run:
  def run(ctx: StateChangingContext, command: ForgeCommand.Run): IO[ExitCode] =
    RunFeature.execute(ctx.paths, ctx.config, command.feature)

object resume:
  def run(ctx: StateChangingContext, command: ForgeCommand): IO[ExitCode] =
    ResumeFeature.execute(ctx.paths, ctx.config, command)

/** Task 1.4.10-d2c — `forge new <feature>` (§11.1 step 1): preflight, cut the design branch, seed a `Drafting`
  * manifest. Does **not** spawn the spec driver — that is the interactive `forge spec` REPL (Task 1.4.13). The body is
  * an injectable `scaffold(...)` so the duplicate-feature guard and manifest-load paths are unit-testable without git;
  * the full happy path (real `git`/`gh`) is covered opt-in in `forge-it`.
  */
object NewFeature:

  def scaffold(paths: ForgePaths, config: io.forge.app.config.ForgeConfig, featureId: FeatureId): IO[ExitCode] =
    val specStore = new FileSpecStore(paths)
    // Guard on the manifest file directly (not `ManifestStore.load`, which collapses "absent" and "malformed" into one
    // error): an existing feature must not be silently re-scaffolded, and a corrupt manifest must not be overwritten.
    IO.blocking(os.exists(paths.manifest(featureId))).flatMap {
      case true =>
        Console[IO]
          .errorln(s"forge new ${featureId.value}: feature already exists (manifest present at that path).")
          .as(ExitCode(1))
      case false =>
        buildBranchManager(paths, config).flatMap { bm =>
          val base = BranchName(config.baseBranch)
          bm.preflight(ForgeCommand.New(featureId), None).flatMap { report =>
            if !report.allPassed then Console[IO].errorln(preflightMessage(featureId, report)).as(ExitCode(1))
            else
              bm.syncBase(base).flatMap {
                case Left(err) => fail(featureId, err.message)
                case Right(snapshot) =>
                  bm.createDesignBranch(featureId, config.branchPrefix, snapshot).flatMap {
                    case Left(err) => fail(featureId, err.message)
                    case Right(branch) =>
                      specStore.saveManifest(featureId, seedManifest(featureId, config)).flatMap {
                        case Left(e) => fail(featureId, s"could not write manifest: $e")
                        case Right(_) =>
                          Console[IO]
                            .println(
                              s"forge new ${featureId.value}: created design branch ${branch.value}. " +
                                s"Next: forge spec ${featureId.value}"
                            )
                            .as(ExitCode.Success)
                      }
                  }
              }
          }
        }
    }

  private def fail(featureId: FeatureId, detail: String): IO[ExitCode] =
    Console[IO].errorln(s"forge new ${featureId.value}: $detail").as(ExitCode(1))

  private def buildBranchManager(
      paths: ForgePaths,
      config: io.forge.app.config.ForgeConfig
  ): IO[io.forge.git.branch.BranchManager] =
    import scala.concurrent.duration.*
    InMemoryBranchProtectionCache(ttl = config.github.branchProtectionTtlSec.seconds).map { cache =>
      new RealBranchManager(new RealGitClient(paths.repoRoot), new RealGhClient(paths.repoRoot), cache, Clock[IO])
    }

  private def seedManifest(featureId: FeatureId, config: io.forge.app.config.ForgeConfig): Manifest =
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = featureId.value,
      baseBranch = BranchName(config.baseBranch),
      branchPrefix = config.branchPrefix,
      mode = config.mode,
      designPr = None,
      pieces = Vector.empty
    )

  private def preflightMessage(featureId: FeatureId, report: io.forge.git.branch.PreflightReport): String =
    val reasons = report.failures.map(f => s"  - ${f.id}: ${f.reason}").mkString("\n")
    s"forge new ${featureId.value}: preflight failed:\n$reasons"

/** Task 1.4.10-d2c — `forge run <feature>`: load the manifest (for `mode`), build the real [[Orchestrator]], drive it
  * to a loop-terminal state, and render that state (with the `forge resume` recovery hint on NHI). The headless
  * complement to the interactive `forge spec`.
  */
object RunFeature:

  def execute(paths: ForgePaths, config: io.forge.app.config.ForgeConfig, featureId: FeatureId): IO[ExitCode] =
    new FileManifestStore(paths).load(featureId).flatMap {
      case Left(failure) => Console[IO].errorln(manifestLoadMessage(featureId, failure)).as(ExitCode(1))
      case Right(manifest) =>
        OrchestratorBuilder.build(manifest.mode, paths, config).flatMap { case (orch, _) =>
          orch.run(featureId).flatMap { terminal =>
            val rendered = TerminalReport.render(terminal)
            val emit =
              if rendered.exitCode == ExitCode.Success then Console[IO].println(rendered.message)
              else Console[IO].errorln(rendered.message)
            emit.as(rendered.exitCode)
          }
        }
    }

  private def manifestLoadMessage(featureId: FeatureId, failure: RebuildError.ManifestLoadFailed): String =
    s"forge run ${featureId.value}: cannot load manifest — ${failure.cause.getMessage}. " +
      s"Has the feature been designed yet? Run: forge spec ${featureId.value}"

/** Task 1.4.13 M5 / M8 — shared driver for the `UserCommand`-injecting commands (`forge resume` / `forge abandon`):
  * load the manifest for its `mode`, build the real [[Orchestrator]], apply the derived command via
  * [[Orchestrator.applyUserCommand]], and render the outcome. The command derivation is the only per-command difference
  * (see [[ResumeFeature.deriveResume]] / [[AbandonFeature.deriveAbandon]]); both reuse the same load → build → apply →
  * render pipeline so the §11.0 / §11.5 persist invariant lives in one place.
  */
private object UserCommandDriver:

  def run(
      paths: ForgePaths,
      config: io.forge.app.config.ForgeConfig,
      featureId: FeatureId,
      commandLabel: String,
      derive: Feature => Either[String, UserCommand]
  ): IO[ExitCode] =
    new FileManifestStore(paths).load(featureId).flatMap {
      case Left(failure) =>
        Console[IO]
          .errorln(
            s"forge $commandLabel ${featureId.value}: cannot load manifest — ${failure.cause.getMessage}. " +
              s"Does the feature exist? Run `forge status` to list features."
          )
          .as(ExitCode(1))
      case Right(manifest) =>
        OrchestratorBuilder.build(manifest.mode, paths, config).flatMap { case (orch, _) =>
          orch.applyUserCommand(featureId, derive).flatMap(renderOutcome(featureId, commandLabel, _))
        }
    }

  private def renderOutcome(
      featureId: FeatureId,
      commandLabel: String,
      outcome: Orchestrator.CommandOutcome
  ): IO[ExitCode] =
    outcome match
      case Orchestrator.CommandOutcome.Driven(terminal) =>
        val rendered = TerminalReport.render(terminal, commandLabel)
        val emit =
          if rendered.exitCode == ExitCode.Success then Console[IO].println(rendered.message)
          else Console[IO].errorln(rendered.message)
        emit.as(rendered.exitCode)
      case Orchestrator.CommandOutcome.Rejected(_, reason) =>
        Console[IO].errorln(s"forge $commandLabel ${featureId.value}: $reason").as(ExitCode(1))

/** Task 1.4.13 M5 — `forge resume <feature> --<hint> <piece>`. The CLI flag + piece select *which* recovery to run; the
  * authoritative [[ResumeHint]] (carrying the PR number) comes from the feature's persisted `NeedsHumanIntervention`
  * state, so the flag is a safety check against resuming the wrong way. Applies `UserCommand.Resume(hint)` and drives
  * the feature forward to its next loop-terminal state — the recovery complement to `forge run`. A flag that names a
  * different hint than the one the feature awaits, or a feature not in NHI, is rejected without mutation.
  */
object ResumeFeature:

  def execute(paths: ForgePaths, config: io.forge.app.config.ForgeConfig, command: ForgeCommand): IO[ExitCode] =
    UserCommandDriver.run(paths, config, resumeFeatureOf(command), "resume", deriveResume(command, _))

  private def resumeFeatureOf(command: ForgeCommand): FeatureId = command match
    case c: ForgeCommand.ResumeAfterHumanPush => c.feature
    case c: ForgeCommand.ResumeCommitHumanFix => c.feature
    case c: ForgeCommand.ResumeRunFixup => c.feature
    case other =>
      // CliParser only routes the three resume variants here; an `other` is a router bug, not operator input.
      throw new IllegalStateException(s"ResumeFeature handed a non-resume command: ${other.name}")

  /** Build `UserCommand.Resume` from the rebuilt feature: the feature must be in NHI and the CLI flag must name the
    * same hint kind + piece the NHI carries. The stored hint (with its PR number) is the one applied.
    */
  private[command] def deriveResume(command: ForgeCommand, feature: Feature): Either[String, UserCommand] =
    feature.state match
      case FsmState.NeedsHumanIntervention(_, hint) => matchHint(command, hint).map(UserCommand.Resume(_))
      case other =>
        val id = resumeFeatureOf(command)
        Left(
          s"feature is not awaiting resume (current state: $other). " +
            s"Run `forge run ${id.value}` to continue, or `forge status ${id.value}` to inspect."
        )

  private def matchHint(command: ForgeCommand, stored: ResumeHint): Either[String, ResumeHint] =
    (command, stored) match
      case (ForgeCommand.ResumeAfterHumanPush(_, p), h: ResumeHint.ResumeAfterHumanPush) if h.p == p => Right(h)
      case (ForgeCommand.ResumeCommitHumanFix(_, p), h: ResumeHint.CommitAndPushHumanFix) if h.p == p => Right(h)
      case (ForgeCommand.ResumeRunFixup(_, p), h: ResumeHint.RunAnotherFixup) if h.p == p => Right(h)
      case (c, s) =>
        Left(
          s"the requested `--${c.name.stripPrefix("resume:")}` does not match what this feature awaits. " +
            s"Its current recovery is: ${TerminalReport.recovery(resumeFeatureOf(c), s)}"
        )

/** Task 1.4.13 M8 — `forge abandon <feature>`. Applies `UserCommand.Abandon(reason)` — the only path to `Abandoned`
  * (§11.0 / §21) — and persists the terminal transition. An already-terminal feature is rejected.
  */
object AbandonFeature:

  /** v1 has no `--reason` flag (the CLI parses the feature only); the operator's intent is captured generically. */
  private val Reason = "abandoned by operator (forge abandon)"

  def execute(paths: ForgePaths, config: io.forge.app.config.ForgeConfig, featureId: FeatureId): IO[ExitCode] =
    UserCommandDriver.run(paths, config, featureId, "abandon", deriveAbandon)

  private[command] def deriveAbandon(feature: Feature): Either[String, UserCommand] =
    feature.state match
      case FsmState.FeatureDone => Left("feature is already complete; nothing to abandon")
      case _: FsmState.Abandoned => Left("feature is already abandoned")
      case _ => Right(UserCommand.Abandon(Reason))

/** Task 1.4.13 M7 — `forge refresh-cache <feature>`. The manual branch-protection cache-invalidation command (§15):
  * applies `UserCommand.RefreshCache`, which bumps `branchProtectionCacheEpoch` only — no lifecycle state mutation. The
  * bump flows through the same load → build → apply → persist pipeline as `resume` / `abandon`; a terminal feature is
  * rejected (a completed or abandoned feature has no live cache worth invalidating).
  */
object RefreshCacheFeature:

  def execute(paths: ForgePaths, config: io.forge.app.config.ForgeConfig, featureId: FeatureId): IO[ExitCode] =
    UserCommandDriver.run(paths, config, featureId, "refresh-cache", deriveRefreshCache)

  private[command] def deriveRefreshCache(feature: Feature): Either[String, UserCommand] =
    feature.state match
      case FsmState.FeatureDone => Left("feature is already complete; nothing to refresh")
      case _: FsmState.Abandoned => Left("feature is abandoned; nothing to refresh")
      case _ => Right(UserCommand.RefreshCache)

object reconcile:
  def run(ctx: StateChangingContext, command: ForgeCommand.Reconcile): IO[ExitCode] =
    Handlers.notImplemented(s"forge reconcile ${command.feature.value}", "Task 1.4.13")

object refreshCache:
  def run(ctx: StateChangingContext, command: ForgeCommand.RefreshCache): IO[ExitCode] =
    RefreshCacheFeature.execute(ctx.paths, ctx.config, command.feature)

object abandon:
  def run(ctx: StateChangingContext, command: ForgeCommand.Abandon): IO[ExitCode] =
    AbandonFeature.execute(ctx.paths, ctx.config, command.feature)

// --- read-only (Task 1.4.13) ------------------------------------------------------

object status:
  def run(ctx: ReadOnlyContext): IO[ExitCode] =
    StatusReport.run(ctx.paths, ctx.config, ctx.args)

object tail:
  def run(ctx: ReadOnlyContext): IO[ExitCode] =
    TailCommand.run(ctx.paths, ctx.args)

object rebuildState:
  def run(ctx: ReadOnlyContext): IO[ExitCode] =
    RebuildStateCommand.run(ctx.paths, ctx.args)

// --- unlock --force (§13 — fully implemented) -------------------------------------

object unlock:
  def run(ctx: UnlockForceContext): IO[ExitCode] =
    new FileProcessLock(ctx.paths).forceRelease.flatMap {
      case ForceReleaseResult.Released =>
        Console[IO].println("forge unlock --force: released the on-disk lock.").as(ExitCode.Success)
      case ForceReleaseResult.NoLockPresent =>
        Console[IO].println("forge unlock --force: no lock present (nothing to release).").as(ExitCode.Success)
      case ForceReleaseResult.LiveHolderRefused(meta) =>
        Console[IO]
          .errorln("forge unlock --force: refused — a live process still holds the lock." + holderSuffix(meta))
          .as(ExitCode(2))
    }

  private def holderSuffix(meta: Option[LockMetadata]): String = meta match
    case Some(m) => s" Holder: pid=${m.pid} host=${m.hostname} command='${m.command}' started=${m.startedAt}."
    case None => ""
