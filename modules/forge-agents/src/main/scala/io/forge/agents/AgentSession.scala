package io.forge.agents

import cats.effect.IO
import fs2.Stream

/** §7.1 — every driver subprocess (streaming or headless) is wrapped in an `AgentSession`. The orchestrator's
  * `SessionMonitor` calls `kill()` uniformly when a settle timeout or per-turn cost cap fires, so both streaming and
  * headless are equally killable.
  */
trait AgentSession:

  /** The CLI-assigned session id, captured from the first `Init` event. The orchestrator stores this as
    * `feature.designSessionId` / `feature.currentPieceSessionId` (see §6.1 invariants).
    */
  def sessionId: String

  /** stdout-to-events stream. Terminates with a `Result` event. */
  def events: Stream[IO, AgentEvent]

  /** Graceful shutdown after the current turn has settled. */
  def close(): IO[Unit]

  /** Hard kill: SIGTERM → 5s grace → SIGKILL. Must not orphan child processes (Slice 0 capability check).
    */
  def kill(): IO[Unit]

/** Spec phase + design revision sessions are interactive: the orchestrator writes user input mid-session via `send`
  * (plain user message) or `answerQuestion` (reply to a deferred `AskUserQuestion`, §7.2 `tool_result` path).
  *
  * The two methods are kept distinct because the wire shapes are different on Claude: `send` writes a `user`-role JSON
  * frame; `answerQuestion` writes a `tool_result` frame carrying the outstanding `tool_use_id` from the
  * `AskUserQuestion` event. Routing a free-text answer through `send` would be interpreted by the CLI as a new user
  * message rather than the awaited tool result, leaving the deferred tool use dangling. On Codex the distinction is a
  * no-op (the §7.3 HaltWithQuestion path re-spawns the driver with the answer in the prompt body); the trait method
  * exists so the orchestrator's Q&A code is mechanism-agnostic.
  */
trait StreamingSession extends AgentSession:
  /** Send a plain user message on stdin. Translates into an `AgentEvent.UserMessage` mirror event so the action log
    * captures every prompt.
    */
  def send(input: String): IO[Unit]

  /** Reply to a deferred `AgentEvent.AskUserQuestion`. The `toolUseId` is the value carried on that event:
    *
    *   - **Native (Claude):** `Some(id)`. The adapter emits the corresponding `tool_result` JSON frame. `None` is an
    *     adapter error here — it would mean the parser failed to capture the id, which `ClaudeConnector.answerQuestion`
    *     surfaces rather than silently route as a new user message.
    *   - **HaltWithQuestion (Codex):** typically `None`; the value is ignored. The adapter re-spawns the driver with
    *     the answer in the prompt body (§7.3 step 3). The orchestrator passes through whatever was on the originating
    *     event, so this method is safe to call with either.
    */
  def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit]
