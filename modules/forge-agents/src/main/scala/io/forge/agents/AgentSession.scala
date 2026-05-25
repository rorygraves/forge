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
  */
trait StreamingSession extends AgentSession:
  /** Send a user message on stdin. Translates into an `AgentEvent.UserMessage` mirror event so the action log captures
    * every prompt.
    */
  def send(input: String): IO[Unit]
