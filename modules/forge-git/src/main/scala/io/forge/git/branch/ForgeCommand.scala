package io.forge.git.branch

import io.forge.core.{FeatureId, PieceId}

/** PR-C C1 — sealed ADT covering every v1.2 §15 row that `BranchManager.preflight` needs to dispatch on. Slice 3 only
  * consumes the BranchManager-relevant subset (the rows whose preflight reads worktree / branch state); Slice 4 wires
  * the CLI layer to construct the rest from this enum.
  *
  * Read-only commands are folded into a single [[ForgeCommand.ReadOnly]] case because their preflight is uniformly
  * "nothing required, log-only intent" — `forge status`, `replay`, `rebuild-state` differ only in their callers.
  */
sealed trait ForgeCommand extends Product with Serializable:
  /** Audit-log identifier per §15 ("`forge new`" → `"new"`, etc.). Used by Slice 4's preflight-bypass log. */
  def name: String

object ForgeCommand:
  final case class New(feature: FeatureId) extends ForgeCommand:
    val name = "new"

  final case class Spec(feature: FeatureId) extends ForgeCommand:
    val name = "spec"

  final case class Run(feature: FeatureId) extends ForgeCommand:
    val name = "run"

  final case class ResumeAfterHumanPush(feature: FeatureId, piece: PieceId) extends ForgeCommand:
    val name = "resume:after-human-push"

  final case class ResumeCommitHumanFix(feature: FeatureId, piece: PieceId) extends ForgeCommand:
    val name = "resume:commit-human-fix"

  final case class ResumeRunFixup(feature: FeatureId, piece: PieceId) extends ForgeCommand:
    val name = "resume:run-fixup"

  final case class Reconcile(feature: FeatureId) extends ForgeCommand:
    val name = "reconcile"

  final case class RefreshCache(feature: FeatureId) extends ForgeCommand:
    val name = "refresh-cache"

  /** `forge status` / `replay` / `rebuild-state` — all read-only, no branch-state requirements. The `kind` exists so
    * the Slice-4 audit log can distinguish callers without expanding the BranchManager surface.
    */
  final case class ReadOnly(kind: ReadOnlyKind) extends ForgeCommand:
    def name: String = s"read-only:${kind.tag}"

  case object UnlockForce extends ForgeCommand:
    val name = "unlock-force"

  final case class Abandon(feature: FeatureId) extends ForgeCommand:
    val name = "abandon"

  enum ReadOnlyKind(val tag: String):
    case Status extends ReadOnlyKind("status")
    case Replay extends ReadOnlyKind("replay")
    case RebuildState extends ReadOnlyKind("rebuild-state")
