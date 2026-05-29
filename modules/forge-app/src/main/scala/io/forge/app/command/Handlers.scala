package io.forge.app.command

import cats.effect.{Clock, ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.lock.{FileProcessLock, ForceReleaseResult, LockMetadata}
import io.forge.app.orchestrator.OrchestratorBuilder
import io.forge.core.{BranchName, FeatureId}
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
    Handlers.notImplemented(s"forge spec ${command.feature.value}", "Task 1.4.13")

object run:
  def run(ctx: StateChangingContext, command: ForgeCommand.Run): IO[ExitCode] =
    RunFeature.execute(ctx.paths, ctx.config, command.feature)

object resume:
  def run(ctx: StateChangingContext, command: ForgeCommand): IO[ExitCode] =
    Handlers.notImplemented(s"forge ${command.name}", "Task 1.4.10")

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

object reconcile:
  def run(ctx: StateChangingContext, command: ForgeCommand.Reconcile): IO[ExitCode] =
    Handlers.notImplemented(s"forge reconcile ${command.feature.value}", "Task 1.4.13")

object refreshCache:
  def run(ctx: StateChangingContext, command: ForgeCommand.RefreshCache): IO[ExitCode] =
    Handlers.notImplemented(s"forge refresh-cache ${command.feature.value}", "Task 1.4.10")

object abandon:
  def run(ctx: StateChangingContext, command: ForgeCommand.Abandon): IO[ExitCode] =
    Handlers.notImplemented(s"forge abandon ${command.feature.value}", "Task 1.4.10")

// --- read-only (Task 1.4.10 / Task 1.4.13) ----------------------------------------

object status:
  def run(ctx: ReadOnlyContext): IO[ExitCode] =
    Handlers.notImplemented("forge status", "Task 1.4.13")

object tail:
  def run(ctx: ReadOnlyContext): IO[ExitCode] =
    Handlers.notImplemented("forge tail", "Task 1.4.13")

object rebuildState:
  def run(ctx: ReadOnlyContext): IO[ExitCode] =
    Handlers.notImplemented("forge rebuild-state", "Task 1.4.13")

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
