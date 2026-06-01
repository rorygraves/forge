package io.forge.git.worktree

/** D3-2 (roadmap §3.5 / [[docs/design-3.5.md]]) — the three-way classification of a piece worktree's git state, used to
  * gate driver respawn-avoidance. Produced by [[WorktreeSafetyClassifier]].
  *
  * The §3.5 exit criterion turns `RestartRecovery`'s blanket "worktree may have uncommitted changes" refusal into a
  * checkable gate. A mid-exploration crash on an implement/fix-up driver leaves the worktree in one of three shapes:
  *
  *   - [[Clean]] — no uncommitted changes; the worktree matches the commit the orchestrator last left the branch at.
  *     Safe to resume onto (nothing to clobber).
  *   - [[DriverUncommittedOnly]] — uncommitted working-tree edits only (modifications / adds / deletions / untracked),
  *     on the expected branch at the expected HEAD, with no merge/rebase conflict in progress. This is the *expected*
  *     mid-exploration state: the driver authored edits but the orchestrator had not yet committed them. Safe to resume
  *     onto (the D3-1 `--resume` seam continues the session with these edits intact, per design-rationale C19).
  *   - [[UnexpectedDivergence]] — anything else: a different branch, detached HEAD, commits beyond the expected HEAD
  *     (operator/other committed work), an in-progress merge/rebase conflict, or a git read that failed. Unsafe — D3-3
  *     routes this back to NHI rather than resuming.
  *
  * D3-3 resume is **default-on once safe** (the 2026-06-01 decision), so this classifier carries the safety burden and
  * is deliberately conservative: any ambiguity (including a git read it could not resolve) resolves to
  * [[UnexpectedDivergence]]. Note that uncommitted edits are treated as driver-authored by assumption — the worktree is
  * Forge's and the operator is not expected to hand-edit it mid-run; "operator edits" only become unsafe once they are
  * *committed* (which moves HEAD) or change the branch.
  */
enum WorktreeSafety:
  case Clean
  case DriverUncommittedOnly
  case UnexpectedDivergence

  /** Both [[Clean]] and [[DriverUncommittedOnly]] are safe for D3-3 to resume the driver session onto;
    * [[UnexpectedDivergence]] falls back to NHI / fresh spawn.
    */
  def safeToResume: Boolean = this match
    case WorktreeSafety.Clean | WorktreeSafety.DriverUncommittedOnly => true
    case WorktreeSafety.UnexpectedDivergence => false
