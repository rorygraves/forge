package io.forge.git.branch

/** PR-C C2 — the result of [[BranchManager.preflight]]: an ordered vector of named §15 checks. Each check is either
  * [[PreflightCheck.Passed]] or [[PreflightCheck.Failed]] with `escapableViaForce` indicating whether the §15 footnote
  * ("`--force` available everywhere; usage logged as `harness.preflight_bypassed`") lets the Slice-4 caller proceed.
  *
  * The report is a value, not an exception. Slice 4 reads `report.allPassed`, decides whether `--force` applies, logs
  * the bypassed check if so, and either proceeds or surfaces `NeedsHumanIntervention` with the failure messages.
  */
final case class PreflightReport(checks: Vector[PreflightCheck]):
  def allPassed: Boolean = checks.forall(_.isPassed)

  /** Subset that failed regardless of escape route. */
  def failures: Vector[PreflightCheck.Failed] = checks.collect { case f: PreflightCheck.Failed => f }

  /** Subset that failed AND cannot be escaped via `--force`. If empty, `--force` is enough to proceed. */
  def hardFailures: Vector[PreflightCheck.Failed] =
    failures.filterNot(_.escapableViaForce)

sealed trait PreflightCheck extends Product with Serializable:
  /** Stable id used in audit-log entries (e.g. `"worktree.clean"`, `"branch.matches-piece"`). */
  def id: String
  def isPassed: Boolean

object PreflightCheck:
  final case class Passed(id: String) extends PreflightCheck:
    val isPassed = true

  /** A failing check.
    *
    * @param id
    *   stable identifier — matches the [[Passed]] flavour so audit-log filters can group across runs.
    * @param reason
    *   human-readable explanation surfaced verbatim in `NeedsHumanIntervention` messages.
    * @param escapableViaForce
    *   §15 footnote — `true` for soft requirements (e.g. "clean worktree") that the operator can bypass with `--force`
    *   after being warned. `false` for invariants that `--force` cannot rescue (e.g. piece-branch mismatch under
    *   `--commit-human-fix` per BM6).
    */
  final case class Failed(id: String, reason: String, escapableViaForce: Boolean) extends PreflightCheck:
    val isPassed = false
