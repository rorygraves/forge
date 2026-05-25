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

/** Spec phase + design revision sessions are interactive: the orchestrator writes user input mid-session via `send`.
  *
  * NOTE — forge-design-1.2 §7.1 extends this trait with `answerQuestion(toolUseId, answer)` (and the companion
  * `Connector.runStreamingSpec(systemPrompt, initialUserMessage)` / `resumeStreamingSpec(sessionId, message)`
  * signatures). The code change to that shape is the next slice-1 PR; until it lands, this trait still carries only
  * `send(input)` and `ClaudeConnector.runStreamingSpec` / `CodexConnector.runStreamingSpec` raise `NotImplementedError`
  * rather than ship against the stale shape. Reasons (verified empirically against Claude CLI 2.1.150 and via the Codex
  * `exec` model):
  *
  *   1. **Initial user message at spawn time.** Both Claude (`-p --input-format stream-json`) and Codex (`exec`)
  *      require a user message before emitting init / thread_id; the parameterless `runStreamingSpec` here can't return
  *      a session with a populated `sessionId`. 2. **`AskUserQuestion` → `tool_result` answer path.** Sending a
  *      free-text answer through `send(input)` is interpreted by the CLI as a new user message, leaving the deferred
  *      tool use dangling. The wire-shape `tool_result` carries the outstanding `tool_use_id` from the
  *      `AskUserQuestion` event, so the answer needs its own trait entry (`answerQuestion(toolUseId, answer)` in v1.2).
  */
trait StreamingSession extends AgentSession:
  /** Send a user message on stdin. Translates into an `AgentEvent.UserMessage` mirror event so the action log captures
    * every prompt. Plain user-message path only — see the trait's class-level note about `AskUserQuestion` answers.
    */
  def send(input: String): IO[Unit]
