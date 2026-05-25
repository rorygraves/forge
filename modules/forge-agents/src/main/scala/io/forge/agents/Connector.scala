package io.forge.agents

import cats.effect.IO
import io.forge.core.{QuestionMechanism, SchemaMechanism}

/** §7.1 — driver + reviewer over a single CLI.
  *
  * Both `ClaudeConnector` and `CodexConnector` implement this; the orchestrator picks driver vs reviewer based on the
  * feature's `Mode`.
  */
trait Connector:

  /** "claude" or "codex". Recorded in the action log as the `actor` field. */
  def name: String

  // --- driver methods ----

  /** Long-running spec-phase session, attached to stdout for events and stdin for `send` / `answerQuestion`.
    *
    * v1.2 §7.1: the initial user message is part of the spawn call because both pinned CLIs emit the init / `thread_id`
    * event only after the first user message arrives (Slice 1 finding F1/F2 — see `design-rationale.md` C11). The
    * returned session therefore has a populated `sessionId` by the time the IO completes.
    */
  def runStreamingSpec(
      systemPromptPath: os.Path,
      initialUserMessage: String
  ): IO[StreamingSession]

  /** Resume a previously closed spec session by its CLI session id, supplying the message that will drive the resumed
    * turn. Same rationale as `runStreamingSpec`: both pinned CLIs need a user message at spawn to produce events.
    * Returns a fresh `StreamingSession` whose `sessionId` is the new (post-resume) id — §6.1 invariant:
    * `feature.designSessionId` is updated to the *new* id (pinned CLIs guarantee `oldSessionId == newSessionId`).
    */
  def resumeStreamingSpec(
      sessionId: String,
      message: String
  ): IO[StreamingSession]

  /** Headless implementation run; settle-bounded by the orchestrator. */
  def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession]

  /** Headless fix-up; a fresh session, no resume from prior implementation. */
  def runFixup(prompt: FixupPrompt): IO[AgentSession]

  /** Which mechanism this connector uses for driver questions (§7.2/§7.3). */
  def questionMechanism: QuestionMechanism

  // --- reviewer methods ----

  /** Review a design markdown. */
  def reviewDesign(input: DesignReviewInput): IO[DesignReview]

  /** Review a PR diff. */
  def reviewPr(input: PrReviewInput): IO[PrReview]

  /** Refine the plan after a piece merges (§14.3). */
  def refine(input: RefineInput): IO[RefineResult]

  /** Schema enforcement (§7.4/§7.5). */
  def schemaMechanism: SchemaMechanism

  // --- telemetry ----

  /** Extract a `Cost` from an event, when the event carries cost info. Per-connector because each CLI emits its own
    * shape.
    */
  def costFrom(event: AgentEvent): Option[Cost]
