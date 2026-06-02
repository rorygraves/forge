package io.forge.agents

import cats.effect.IO
import io.forge.core.{QuestionMechanism, SchemaMechanism}
import io.forge.core.cost.Cost
import io.forge.core.profile.RepoProfile

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
    *
    * `systemPromptPath` is the same driver system-prompt file passed to the original `runStreamingSpec` spawn. It is
    * carried here — symmetric with `runStreamingSpec` — so each connector can honour §7.10(a) on its own terms:
    * `ClaudeConnector` ignores it (the CLI restores the system prompt server-side via `--resume`); `CodexConnector`
    * re-prepends it via [[CodexPrompt.withSystemBlock]], because each `codex exec resume` is a fresh invocation that
    * remembers nothing the adapter does not pass in. v1.3 §7.10(a) (forge-design-1.3) resolves design-rationale C14 by
    * widening this trait rather than pushing the framing responsibility up into the orchestrator's resume message.
    */
  def resumeStreamingSpec(
      sessionId: String,
      systemPromptPath: os.Path,
      message: String
  ): IO[StreamingSession]

  /** Headless implementation run; settle-bounded by the orchestrator. */
  def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession]

  /** Headless fix-up; a fresh session, no resume from prior implementation. */
  def runFixup(prompt: FixupPrompt): IO[AgentSession]

  /** Resume a previously-interrupted **headless** implement/fix-up driver session by its CLI session id, with a
    * continuation `message`, returning a fresh one-shot [[AgentSession]]. The driver-side analogue of
    * [[resumeStreamingSpec]]: same `(sessionId, systemPromptPath, message)` shape, but for the one-shot `-p` / `exec`
    * headless drivers ([[runHeadlessImplementation]] / [[runFixup]]) rather than the streaming spec phase.
    * Phase-agnostic — the orchestrator knows whether it is resuming an implement- or fix-up-phase NHI; the connector
    * only needs the id.
    *
    * **D3 (driver-respawn-avoidance, design-3.5 D3-1).** A resume from an implement/fix-up `NeedsHumanIntervention`
    * (mid-exploration crash) continues the *existing* driver session via the CLI's resume (`claude --resume <sid>` /
    * `codex exec resume <thread-id>`) rather than re-spawning it from scratch and re-paying the full exploration (~\$10
    * / 2.18M tokens in the szork run). The D3-0 spike (design-rationale **C19**) proved both pinned CLIs restore
    * conversation context across process death; this is the clean tested seam built on that finding. **Dead code until
    * D3-3** wires the orchestrator gate (a worktree-safety classifier decides when resume is safe).
    *
    * `systemPromptPath` is the same driver system-prompt file passed to the original headless spawn, carried for the
    * same §7.10(a) reason as [[resumeStreamingSpec]]: `ClaudeConnector` ignores it (the CLI restores the system prompt
    * server-side on resume); `CodexConnector` re-prepends it via [[CodexPrompt.withSystemBlock]] because each `codex
    * exec resume` is a fresh, stateless invocation. §6.1 invariant on the pinned CLIs: the returned session's
    * `sessionId` equals the input id.
    */
  def resumeHeadlessDriver(sessionId: String, systemPromptPath: os.Path, message: String): IO[AgentSession]

  /** Which mechanism this connector uses for driver questions (§7.2/§7.3). */
  def questionMechanism: QuestionMechanism

  // --- reviewer methods ----

  /** Review a design markdown. */
  def reviewDesign(input: DesignReviewInput): IO[DesignReview]

  /** Review a PR diff. */
  def reviewPr(input: PrReviewInput): IO[PrReview]

  /** Refine the plan after a piece merges (§14.3). */
  def refine(input: RefineInput): IO[RefineResult]

  // --- sensor methods (§7.11, Phase 3) ----

  /** `RepoProfiler` sensor (§7.11): perceive the repo (build tool, gate commands tiered by determinism, workflow,
    * commit identity) into a [[RepoProfile]]. A reviewer-side one-shot exactly like the review methods — Native schema
    * (`~/.forge/schemas/repo-profile.json`) + `repo-profile.<cli>.md` system prompt + a [[ReviewDecoders.repoProfile]]
    * decode. Run out-of-band (`forge profile`), never mid-feature; the profile it produces is a versioned input the FSM
    * consumes deterministically (§11.0). The output is wall-clock-capped orchestrator-side via the `ReviewerCall`
    * boundary.
    */
  def profileRepo(input: RepoProfilerInput): IO[RepoProfile]

  /** `FailureClassifier` sensor (§7.11): perceive a gate failure (`failureLog` + `profile`) into a [[Classification]].
    * A reviewer-side one-shot exactly like [[profileRepo]] — Native schema (`~/.forge/schemas/failure-classifier.json`)
    * + `failure-classifier.<cli>.md` system prompt + a [[ReviewDecoders.failureClassification]] decode. Consulted by
    * the orchestrator **only** when the deterministic [[io.forge.core.profile.RuleBasedFailureClassifier]] can't pin
    * the failure (`Unknown`) and `adapt.llmClassifierOnUnknown` is set — the §7.11 cost lever. The proposal is a
    * read-only input the spine routes on deterministically (§8.2); the core never re-invokes it on replay.
    * Wall-clock-capped orchestrator-side via the `ReviewerCall` boundary.
    */
  def classifyFailure(input: FailureClassifierInput): IO[io.forge.core.profile.Classification]

  /** Schema enforcement (§7.4/§7.5). */
  def schemaMechanism: SchemaMechanism

  // --- telemetry ----

  /** Extract a `Cost` from an event, when the event carries cost info. Per-connector because each CLI emits its own
    * shape.
    */
  def costFrom(event: AgentEvent): Option[Cost]
