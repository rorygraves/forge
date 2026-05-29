package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.lock.{FileProcessLock, ForceReleaseResult, LockMetadata}
import io.forge.git.branch.ForgeCommand

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
    Handlers.notImplemented(s"forge new ${command.feature.value}", "Task 1.4.10")
}

object spec:
  def run(ctx: StateChangingContext, command: ForgeCommand.Spec): IO[ExitCode] =
    Handlers.notImplemented(s"forge spec ${command.feature.value}", "Task 1.4.13")

object run:
  def run(ctx: StateChangingContext, command: ForgeCommand.Run): IO[ExitCode] =
    Handlers.notImplemented(s"forge run ${command.feature.value}", "Task 1.4.10")

object resume:
  def run(ctx: StateChangingContext, command: ForgeCommand): IO[ExitCode] =
    Handlers.notImplemented(s"forge ${command.name}", "Task 1.4.10")

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
