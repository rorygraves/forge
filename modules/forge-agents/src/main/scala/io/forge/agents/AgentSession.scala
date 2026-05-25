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
  * NOTE — two design gaps surfaced during slice-1 wiring that the v1.1 trait doesn't yet model:
  *
  *   1. **Initial user message at spawn time.** Both Claude (`-p --input-format stream-json`) and Codex (`exec`)
  *      require a user message before emitting init / thread_id. The trait's synchronous `sessionId: String` accessor
  *      can't be honored unless `runStreamingSpec` takes the initial message. Until that lands in a forge-design-1.2
  *      revision, `ClaudeConnector.runStreamingSpec` and `CodexConnector.runStreamingSpec` raise `NotImplementedError`.
  *      2. **`AskUserQuestion` → `tool_result` answer path.** §7.2 step 4 says the orchestrator sends the answer back
  *      "on stdin as the `tool_result`." That's a different JSON frame than a plain user message — it carries the
  *      outstanding `tool_use_id` from the `AskUserQuestion` event. `send(input: String)` here is the plain
  *      user-message path only; sending a free-text answer this way will be interpreted as a new user message, not the
  *      awaited tool_result. The Q&A flow needs either an additional `answerQuestion(toolUseId, answer)` method on this
  *      trait, or a richer ADT for the send payload. Pending design alignment.
  */
trait StreamingSession extends AgentSession:
  /** Send a user message on stdin. Translates into an `AgentEvent.UserMessage` mirror event so the action log captures
    * every prompt. Plain user-message path only — see the trait's class-level note about `AskUserQuestion` answers.
    */
  def send(input: String): IO[Unit]
