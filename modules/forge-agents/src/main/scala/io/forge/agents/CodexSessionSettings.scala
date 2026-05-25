package io.forge.agents

/** §7.10(c) — Codex CLI flags that are session-scoped and sticky across `codex exec resume`. Slice 0 (report §2.2)
  * confirmed that the resume subcommand rejects all of these:
  *
  * --sandbox, --output-schema, --add-dir, -a/--ask-for-approval, -C/--cd
  *
  * The orchestrator's rule (§17 Slice 1, §11.6): any phase that needs different settings than the original spawn must
  * spawn a fresh session rather than resume. `CodexConnector` builds this value once at spawn time and uses
  * `isCompatibleForResume` to gate the decision.
  */
final case class CodexSessionSettings(
    sandbox: String,
    outputSchema: Option[os.Path],
    addDirs: Vector[os.Path],
    approvalMode: String,
    workingDirectory: Option[os.Path]
):
  /** Two instances are compatible for `codex exec resume` iff every sticky flag matches — i.e. structural equality.
    * Anything else requires a fresh `codex exec` spawn. Named-method form so call sites read as intent rather than as a
    * plain `==`.
    */
  def isCompatibleForResume(other: CodexSessionSettings): Boolean = this == other

object CodexSessionSettings:
  /** Driver-side defaults, derived from §18 `codex.driverSandbox` config. Driver streaming sessions do not use
    * `--output-schema` (no schema- constrained output); reviewer one-shots build their own settings per call (§7.10(c)
    * point 2: reviewer calls are independent one-shots, not resumes, so they're unaffected by stickiness).
    */
  def driver(sandbox: String, approvalMode: String): CodexSessionSettings =
    CodexSessionSettings(
      sandbox = sandbox,
      outputSchema = None,
      addDirs = Vector.empty,
      approvalMode = approvalMode,
      workingDirectory = None
    )
