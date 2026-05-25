package io.forge.agents

import io.forge.core.Question

/** §7.1 — events emitted by a driver session's stdout. The orchestrator translates these into action-log entries (§19);
  * the connector itself is actor-agnostic, so no `actor` field lives here.
  *
  * Slice 1 will extend this enum as we observe what each CLI actually emits.
  */
enum AgentEvent:
  /** First event of every session; carries the CLI-assigned session id. */
  case Init(sessionId: String)

  /** A user message written via `StreamingSession.send`. Summary only. */
  case UserMessage(summary: String)

  /** Assistant text chunk. Token count is the CLI's estimate. */
  case AssistantText(text: String, outputTokens: Int)

  /** A tool call (Read/Write/Edit/Bash/...). */
  case ToolUse(tool: String, summary: String)

  /** Driver question — mechanism-agnostic per v1.2 §7.1. `toolUseId` is `Some(id)` for Native (Claude's
    * `AskUserQuestion` tool use; the orchestrator routes the answer back as a `tool_result` carrying that id), `None`
    * for `HaltWithQuestion` (the question came from a final-output JSON envelope, §7.3 — there is no wire-level tool
    * use to reference, and `CodexConnector.answerQuestion` ignores the field and re-spawns the driver with the answer
    * in the prompt body).
    */
  case AskUserQuestion(question: Question, toolUseId: Option[String])

  /** Cost increment (§12). */
  case CostUpdate(cost: Cost)

  /** Final event of a turn. `success` follows the CLI's own definition. */
  case Result(success: Boolean, durationMs: Long)
