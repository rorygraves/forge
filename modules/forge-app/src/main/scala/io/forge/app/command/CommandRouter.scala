package io.forge.app.command

import cats.effect.{ExitCode, IO}
import io.forge.git.branch.ForgeCommand
import io.forge.git.branch.ForgeCommand.ReadOnlyKind

/** Task 1.4.9 I3 — thin dispatch table from a parsed [[ForgeCommand]] to its handler.
  *
  * Two entry points keyed by command class, mirroring [[io.forge.app.Main]]'s per-class resource branching: the type of
  * the context a handler receives is fixed by which entry point `Main` calls, so a read-only command can never be
  * handed a [[StateChangingContext]]. `unlock --force` dispatches directly from `Main` (it short-circuits before any
  * context that the router would carry) and so has no row here.
  */
object CommandRouter:

  def stateChanging(command: ForgeCommand, ctx: StateChangingContext): IO[ExitCode] = command match
    case c: ForgeCommand.New => new_.run(ctx, c)
    case c: ForgeCommand.Spec => spec.run(ctx, c)
    case c: ForgeCommand.Run => run.run(ctx, c)
    case c: ForgeCommand.Reconcile => reconcile.run(ctx, c)
    case c: ForgeCommand.RefreshCache => refreshCache.run(ctx, c)
    case c: ForgeCommand.Abandon => abandon.run(ctx, c)
    case c: ForgeCommand.ResumeAfterHumanPush => resume.run(ctx, c)
    case c: ForgeCommand.ResumeCommitHumanFix => resume.run(ctx, c)
    case c: ForgeCommand.ResumeRunFixup => resume.run(ctx, c)
    case ForgeCommand.ReadOnly(_) | ForgeCommand.UnlockForce =>
      // Unreachable: Main routes read-only / unlock through their own class branches. Defensive guard against a
      // future mis-route rather than a silent no-op.
      IO.raiseError(new IllegalStateException(s"non-state-changing command '${command.name}' routed as state-changing"))

  def readOnly(command: ForgeCommand.ReadOnly, ctx: ReadOnlyContext): IO[ExitCode] = command.kind match
    case ReadOnlyKind.Status => status.run(ctx)
    case ReadOnlyKind.Tail => tail.run(ctx)
    case ReadOnlyKind.RebuildState => rebuildState.run(ctx)
