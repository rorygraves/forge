package io.forge.agents

/** §7.5 — failure modes a reviewer one-shot can surface. Distinguishes **process-level** failures (network blip, CLI
  * crash, sandbox launch error — retryable by the orchestrator's `reviewProcessRetries` / `refineProcessRetries`
  * wrapper, §7.6) from **adapter-level** failures (schema-conformant output missing or malformed, domain mapping
  * impossible — NOT retried; surface to the orchestrator and let §7.5 / §14.2 routing handle them).
  *
  * The connector layer raises these into the IO; the orchestrator's retry wrapper switches on the trait at the call
  * boundary.
  */
sealed trait ReviewerError extends RuntimeException:
  def message: String
  override def getMessage: String = message

/** Process-level failure: subprocess crashed, exited non-zero before producing structured output, was killed, etc.
  * Retryable by `reviewProcessRetries`.
  */
final case class ReviewerProcessFailure(detail: String) extends RuntimeException with ReviewerError:
  def message: String = detail

/** Configuration / setup failure: the connector was built without the reviewer assets it needs (`reviewerAssets =
  * None`), or some other up-front precondition for a reviewer call is missing. **Not retryable** — retries won't
  * conjure schema files out of thin air. Distinct from [[ReviewerProcessFailure]] specifically so the orchestrator's
  * `reviewProcessRetries` wrapper does not burn its retry budget on configuration mistakes.
  */
final case class ReviewerNotConfigured(detail: String) extends RuntimeException with ReviewerError:
  def message: String = detail

/** Adapter-level failure: the CLI exited successfully and emitted something, but the structured output is missing
  * (Claude: no `structured_output` field on the result envelope; Codex: no `item.completed{agent_message}` with a
  * `text` payload) or didn't parse as JSON. Not retried — §7.5.
  */
final case class StructuredOutputMissing(detail: String) extends RuntimeException with ReviewerError:
  def message: String = detail

/** Adapter-level failure: the structured output parsed as JSON but didn't match the expected Scala-side shape (missing
  * required field, wrong type, unknown enum value). Not retried — §7.5.
  */
final case class StructuredOutputMalformed(detail: String) extends RuntimeException with ReviewerError:
  def message: String = detail

/** Adapter-level failure on the §7.2 `tool_result` reply path: `ClaudeConnector.answerQuestion` was invoked with
  * `toolUseId = None` even though `QuestionMechanism = Native` requires `Some(id)` (v1.2 §7.1, design-rationale C12).
  * Almost always means `ClaudeEventParser` failed to capture the originating `AskUserQuestion` event's `tool_use_id` —
  * a parser regression rather than a transient process failure. Not retried; surfaces directly so the orchestrator can
  * route it as `NeedsHumanIntervention` and the action log records what was missing.
  */
final case class MissingToolUseId(detail: String) extends RuntimeException:
  override def getMessage: String = detail
