package io.forge.agents

import cats.effect.IO
import cats.syntax.all.*
import io.forge.core.{QuestionMechanism, SchemaMechanism}

import scala.concurrent.duration.*

/** §7.1 Claude driver/reviewer adapter.
  *
  * v1 covers the streaming + headless driver methods using [[Subprocess]] + [[StreamingDriver]] + [[ClaudeEventParser]]
  * end-to-end. The reviewer methods (`reviewDesign` / `reviewPr` / `refine`) are stubbed — they have a one-shot
  * lifecycle (spawn, parse `structured_output`, exit) that's distinct enough to live in its own layer. They will land
  * with the Slice 1 follow-up.
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

  def runStreamingSpec(systemPromptPath: os.Path): IO[StreamingSession] =
    spawnStreaming(ClaudeConnector.streamingSpecArgv(binary, systemPromptPath))

  def resumeStreamingSpec(sessionId: String): IO[StreamingSession] =
    spawnStreaming(ClaudeConnector.resumeStreamingSpecArgv(binary, sessionId))

  def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] =
    spawnStreaming(ClaudeConnector.headlessArgv(binary, prompt.systemPromptPath, prompt.body)).widen

  def runFixup(prompt: FixupPrompt): IO[AgentSession] =
    spawnStreaming(ClaudeConnector.headlessArgv(binary, prompt.systemPromptPath, prompt.body)).widen

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

  private def spawnStreaming(argv: List[String]): IO[StreamingSession] =
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

  /** argv for `runStreamingSpec` — bidirectional streaming, prompt comes via stdin. */
  def streamingSpecArgv(binary: String, systemPromptPath: os.Path): List[String] =
    (binary :: IsolationFlags) ++ OutputFlags ++ StreamingInputFlags ++
      List("--system-prompt-file", systemPromptPath.toString)

  /** argv for `resumeStreamingSpec`. Resume already encodes the prior system prompt in the session state, so no
    * `--system-prompt-file` is supplied here.
    */
  def resumeStreamingSpecArgv(binary: String, sessionId: String): List[String] =
    (binary :: IsolationFlags) ++ OutputFlags ++ StreamingInputFlags ++ List("--resume", sessionId)

  /** argv for headless (`-p <prompt>`) one-shot runs. No `--input-format stream-json` — the prompt is on argv and the
    * CLI should not wait on stdin.
    */
  def headlessArgv(binary: String, systemPromptPath: os.Path, prompt: String): List[String] =
    (binary :: "-p" :: prompt :: IsolationFlags) ++ OutputFlags ++
      List("--system-prompt-file", systemPromptPath.toString)
