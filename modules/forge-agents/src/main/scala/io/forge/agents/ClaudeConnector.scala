package io.forge.agents

import cats.effect.IO
import cats.syntax.all.*
import io.forge.core.{QuestionMechanism, SchemaMechanism}

import scala.concurrent.duration.*

/** §7.1 Claude driver/reviewer adapter.
  *
  * v1 covers the **headless** driver methods (`runHeadlessImplementation`, `runFixup`) end-to-end via [[Subprocess]] +
  * [[StreamingDriver]] + [[ClaudeEventParser]]. The streaming methods (`runStreamingSpec`, `resumeStreamingSpec`) are
  * stubbed — see their docstrings; the blocker is a trait-level design point shared with `CodexConnector`. The reviewer
  * methods (`reviewDesign` / `reviewPr` / `refine`) are also stubbed, awaiting the Layer 5 one-shot lifecycle.
  *
  * Flags pinned in Slice 0 (`docs/slice-0/slice-0-report.md` §2.1):
  *
  *   - Isolation: `--setting-sources project,local --strict-mcp-config`. NOT `--bare` — `--bare` also disables OAuth,
  *     which would force every user to provide `ANTHROPIC_API_KEY` even when they have a logged-in Claude session.
  *   - Streaming I/O: `--input-format stream-json --output-format stream-json --verbose`. With these the CLI reads user
  *     messages as JSON on stdin and emits events as JSON on stdout — matching what `StreamingDriver` + parser expect.
  *   - System prompt: `--system-prompt-file <path>`. The CLI reads the file and uses it as the system prompt; no
  *     prepend needed at the Forge layer (unlike Codex — §7.10(a)).
  *   - Resume: `--resume <session-id>`. Session id is preserved across resume (§6.1).
  *
  * Headless one-shot driver runs (`runHeadlessImplementation`, `runFixup`) use `-p '<prompt>'` to pass the prompt as a
  * positional argument and the same stream-json output flags so we can drain events the same way.
  */
final class ClaudeConnector(
    binary: String = "claude",
    cwd: Option[os.Path] = None,
    extraEnv: Map[String, String] = Map.empty,
    initTimeout: FiniteDuration = 30.seconds
) extends Connector:

  val name: String = "claude"
  val questionMechanism: QuestionMechanism = QuestionMechanism.Native
  val schemaMechanism: SchemaMechanism = SchemaMechanism.Native

  // --- driver methods ----

  /** **Stub — same root cause as `CodexConnector.runStreamingSpec`.**
    *
    * Runtime probe against Claude CLI 2.1.150: with `-p --input-format stream-json --output-format stream-json
    * --verbose --system-prompt-file <path>`, **the CLI emits the `init` event only after the first user-message JSON
    * frame arrives on stdin**, not at spawn time. Empty stdin → CLI exits silently with no events at all.
    *
    * That conflicts with the §7.1 trait's synchronous `def sessionId: String` accessor and the
    * `StreamingDriver.fromSubprocess` model (block on Init, then return a session ready for `send`). Both connectors
    * really want an initial user message at spawn time, which the trait doesn't carry.
    *
    * Lands when the trait grows an initial-message parameter (forge-design-1.2 territory — see roadmap). Until then
    * this method raises so callers can't silently rely on a half-working session.
    *
    * The pieces needed when this re-enables — the `-p` flag, the `encodeUserMessageJson` wire-shape, the JSON frame
    * schema — are already in this module and unit-tested.
    */
  def runStreamingSpec(systemPromptPath: os.Path): IO[StreamingSession] =
    IO.raiseError(
      NotImplementedError(
        "ClaudeConnector.runStreamingSpec — Claude `--input-format stream-json` emits init only after the first " +
          "user message; the §7.1 trait can't deliver a session with a populated sessionId without an initial " +
          "message. Same blocker as CodexConnector; resolves with the slice-1 follow-up + trait extension."
      )
    )

  /** Same blocker as [[runStreamingSpec]]. */
  def resumeStreamingSpec(sessionId: String): IO[StreamingSession] =
    IO.raiseError(
      NotImplementedError(
        "ClaudeConnector.resumeStreamingSpec — same trait-level blocker as runStreamingSpec; see its docstring."
      )
    )

  def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] =
    spawnHeadless(ClaudeConnector.headlessArgv(binary, prompt.systemPromptPath, prompt.body)).widen

  def runFixup(prompt: FixupPrompt): IO[AgentSession] =
    spawnHeadless(ClaudeConnector.headlessArgv(binary, prompt.systemPromptPath, prompt.body)).widen

  // --- reviewer methods (Layer 5 follow-up) ----

  def reviewDesign(input: DesignReviewInput): IO[DesignReview] =
    IO.raiseError(NotImplementedError("ClaudeConnector.reviewDesign — Layer 5 follow-up"))

  def reviewPr(input: PrReviewInput): IO[PrReview] =
    IO.raiseError(NotImplementedError("ClaudeConnector.reviewPr — Layer 5 follow-up"))

  def refine(input: RefineInput): IO[RefineResult] =
    IO.raiseError(NotImplementedError("ClaudeConnector.refine — Layer 5 follow-up"))

  // --- telemetry ----

  def costFrom(event: AgentEvent): Option[Cost] = event match
    case AgentEvent.CostUpdate(c) => Some(c)
    case _ => None

  // --- private helpers ----

  /** Headless one-shot: prompt is on argv, the CLI doesn't read stdin. `send()` on the returned session is a protocol
    * error (writes a stray byte to an unused stdin) but we don't actively guard against it here — the orchestrator's
    * headless paths don't call `send` on what they treat as one-shot.
    */
  private def spawnHeadless(argv: List[String]): IO[StreamingSession] =
    StreamingDriver.fromSubprocess(
      Subprocess.spawn(argv, cwd = cwd, env = extraEnv),
      ClaudeEventParser.parse,
      initTimeout
    )

object ClaudeConnector:

  /** Isolation flags applied to every spawn. */
  val IsolationFlags: List[String] = List(
    "--setting-sources",
    "project,local",
    "--strict-mcp-config"
  )

  /** Output-shape flags every spawn shares — stream-json events on stdout, verbose so non-result events flow too. */
  val OutputFlags: List[String] = List(
    "--output-format",
    "stream-json",
    "--verbose"
  )

  /** Input-shape flag for bidirectional streaming spawns: read user messages as JSON on stdin. NOT used by `-p`
    * one-shot mode — `-p` takes the prompt on argv, and adding `--input-format stream-json` makes the CLI block on
    * stdin that will never arrive.
    */
  val StreamingInputFlags: List[String] = List(
    "--input-format",
    "stream-json"
  )

  /** argv for `runStreamingSpec` — bidirectional streaming, prompt comes via stdin as stream-json frames.
    *
    * `-p`/`--print` is mandatory: `claude --help` says `--input-format`, `--output-format`, and
    * `--include-partial-messages` ALL "only work with --print". Without it the CLI enters interactive/TUI mode and
    * either ignores or rejects the streaming flags. Verified empirically — the first version of this argv (without
    * `-p`) hit the StreamingDriver init timeout because no events were emitted.
    */
  def streamingSpecArgv(binary: String, systemPromptPath: os.Path): List[String] =
    (binary :: "-p" :: IsolationFlags) ++ OutputFlags ++ StreamingInputFlags ++
      List("--system-prompt-file", systemPromptPath.toString)

  /** argv for `resumeStreamingSpec`. Resume already encodes the prior system prompt in the session state, so no
    * `--system-prompt-file` is supplied here. `-p` still required for the same reason as `streamingSpecArgv`.
    */
  def resumeStreamingSpecArgv(binary: String, sessionId: String): List[String] =
    (binary :: "-p" :: IsolationFlags) ++ OutputFlags ++ StreamingInputFlags ++ List("--resume", sessionId)

  /** argv for headless (`-p <prompt>`) one-shot runs. No `--input-format stream-json` — the prompt is on argv and the
    * CLI should not wait on stdin.
    */
  def headlessArgv(binary: String, systemPromptPath: os.Path, prompt: String): List[String] =
    (binary :: "-p" :: prompt :: IsolationFlags) ++ OutputFlags ++
      List("--system-prompt-file", systemPromptPath.toString)

  /** Wire encoder for streaming-spec / resume sessions: wraps a plain-text user message as the JSON frame
    * `--input-format stream-json` expects. Confirmed against Claude CLI 2.1.150:
    *
    * {{{
    *   {"type":"user","message":{"role":"user","content":"<text>"}}
    * }}}
    *
    * Public so connector tests and the slice-1 follow-up (Q&A tool_result path) can reuse the JSON shaping logic.
    */
  def encodeUserMessageJson(text: String): String =
    ujson.write(
      ujson.Obj(
        "type" -> "user",
        "message" -> ujson.Obj("role" -> "user", "content" -> text)
      )
    )
