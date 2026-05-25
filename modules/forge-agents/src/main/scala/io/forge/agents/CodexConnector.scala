package io.forge.agents

import cats.effect.IO
import cats.syntax.all.*
import io.forge.core.{QuestionMechanism, SchemaMechanism}

import scala.concurrent.duration.*

/** §7.1 Codex driver/reviewer adapter.
  *
  * v1 covers the **headless** driver methods (`runHeadlessImplementation`, `runFixup`) end-to-end, plus full argv
  * construction and telemetry. The streaming methods (`runStreamingSpec`, `resumeStreamingSpec`) are stubbed — see each
  * method's docstring; same trait-level blocker as `ClaudeConnector`. The reviewer methods (`reviewDesign` / `reviewPr`
  * / `refine`) are stubbed, awaiting the Layer 5 one-shot lifecycle.
  *
  * Slice 0 (`docs/slice-0/slice-0-report.md` §2.2) pinned:
  *
  *   - `codex exec --json` writes JSONL to stdout (parsed by [[CodexEventParser]]). One process per `exec` call.
  *   - `codex exec resume <thread-id>` preserves the original `thread_id`.
  *   - No `--system-prompt-file` flag: the system prompt rides in the user prompt via [[CodexPrompt.withSystemBlock]]
  *     (§7.10(a)).
  *   - `codex exec resume` rejects `--sandbox`, `--output-schema`, `--add-dir`, `-a/--ask-for-approval`, `-C/--cd`.
  *     Session settings are sticky (§7.10(c)).
  *   - Isolation: `--ignore-user-config --ignore-rules` plus an explicit `--sandbox`.
  *   - Stderr leaks `"Reading additional input from stdin..."` even when the prompt is on argv — the StreamingDriver
  *     filters those into its stderr buffer rather than the event stream.
  *
  * Cost is computed in [[CodexEventParser]] using the per-model [[PriceTable]] (§7.10(b)).
  */
final class CodexConnector(
    binary: String = "codex",
    model: String,
    priceTable: PriceTable,
    sessionSettings: CodexSessionSettings,
    cwd: Option[os.Path] = None,
    extraEnv: Map[String, String] = Map.empty,
    initTimeout: FiniteDuration = 30.seconds
) extends Connector:

  val name: String = "codex"
  val questionMechanism: QuestionMechanism = QuestionMechanism.HaltWithQuestion
  val schemaMechanism: SchemaMechanism = SchemaMechanism.Native

  // --- driver methods ----

  /** **Stub — same root cause as `ClaudeConnector.runStreamingSpec`.**
    *
    * Codex's `exec` model spawns one process per turn (Slice 0 §2.2): a streaming-spec session that accepts multiple
    * `send(...)` calls maps onto N invocations of `codex exec resume <thread-id>` rather than a single long-lived
    * process. That multi-turn-as-one-shots facade is non-trivial — it has to lazily capture the thread id from the
    * first turn (so the trait's synchronous `sessionId: String` accessor stays honest), serialise turns under a mutex,
    * and merge per-turn event streams.
    *
    * Underlying blocker: **`runStreamingSpec(systemPromptPath)` takes no initial user message, but both `codex exec`
    * (positional prompt required) and `claude -p --input-format stream-json` (init emitted only after first JSON frame
    * on stdin — runtime-verified) need one before they produce a session id.** The §7.1 trait needs to grow an
    * initial-message parameter for either connector to honour it cleanly; see `docs/design-rationale.md` C11 +
    * `docs/slice-1/slice-1-findings.md` for the proposed shape.
    *
    * Lands when the trait extension lands in forge-design-1.2.
    */
  def runStreamingSpec(systemPromptPath: os.Path): IO[StreamingSession] =
    IO.raiseError(
      NotImplementedError(
        "CodexConnector.runStreamingSpec — Codex `exec` requires an initial user message that the §7.1 trait " +
          "doesn't carry. Same blocker as ClaudeConnector; resolves with the forge-design-1.2 trait extension."
      )
    )

  /** Same blocker as [[runStreamingSpec]]. */
  def resumeStreamingSpec(sessionId: String): IO[StreamingSession] =
    IO.raiseError(
      NotImplementedError(
        "CodexConnector.resumeStreamingSpec — same trait-level blocker as runStreamingSpec; see its docstring."
      )
    )

  def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] =
    spawnHeadless(prompt.systemPromptPath, prompt.body).widen

  def runFixup(prompt: FixupPrompt): IO[AgentSession] =
    spawnHeadless(prompt.systemPromptPath, prompt.body).widen

  // --- reviewer methods (Layer 5 follow-up) ----

  def reviewDesign(input: DesignReviewInput): IO[DesignReview] =
    IO.raiseError(NotImplementedError("CodexConnector.reviewDesign — Layer 5 follow-up"))

  def reviewPr(input: PrReviewInput): IO[PrReview] =
    IO.raiseError(NotImplementedError("CodexConnector.reviewPr — Layer 5 follow-up"))

  def refine(input: RefineInput): IO[RefineResult] =
    IO.raiseError(NotImplementedError("CodexConnector.refine — Layer 5 follow-up"))

  // --- telemetry ----

  def costFrom(event: AgentEvent): Option[Cost] = event match
    case AgentEvent.CostUpdate(c) => Some(c)
    case _ => None

  // --- private helpers ----

  private def spawnHeadless(systemPromptPath: os.Path, userBody: String): IO[StreamingSession] =
    IO.delay(CodexPrompt.withSystemBlock(systemPromptPath, userBody))
      .flatMap: combined =>
        val argv = CodexConnector.execArgv(binary, model, sessionSettings, combined)
        val parser = new CodexEventParser(priceTable, model)
        StreamingDriver.fromSubprocess(
          Subprocess.spawn(argv, cwd = cwd, env = extraEnv),
          parser.parse,
          initTimeout
        )

object CodexConnector:

  /** Isolation flags applied to every `exec` spawn. */
  val IsolationFlags: List[String] = List("--ignore-user-config", "--ignore-rules")

  /** JSON event output. */
  val JsonOutputFlags: List[String] = List("--json")

  /** Build the argv for a `codex exec` invocation. The combined prompt (system block + user body) is the last
    * positional argument, matching the shape Slice 0 §2.2 documented. Session-scoped settings are translated into flag
    * pairs.
    */
  def execArgv(
      binary: String,
      model: String,
      settings: CodexSessionSettings,
      combinedPrompt: String
  ): List[String] =
    val sandbox = List("--sandbox", settings.sandbox)
    val approval = List("--ask-for-approval", settings.approvalMode)
    val addDirs = settings.addDirs.toList.flatMap(d => List("--add-dir", d.toString))
    val schema = settings.outputSchema.toList.flatMap(p => List("--output-schema", p.toString))
    val cd = settings.workingDirectory.toList.flatMap(p => List("-C", p.toString))
    val modelArgs = List("-m", model)
    (binary :: "exec" :: IsolationFlags) ++
      JsonOutputFlags ++
      modelArgs ++
      sandbox ++
      approval ++
      addDirs ++
      schema ++
      cd ++
      List(combinedPrompt)

  /** Build the argv for `codex exec resume`. Per §7.10(c) this rejects every session-scoped flag, so we only pass the
    * JSON output flag, the thread id, and the new user message.
    */
  def execResumeArgv(binary: String, threadId: String, userMessage: String): List[String] =
    (binary :: "exec" :: "resume" :: JsonOutputFlags) ++ List(threadId, userMessage)
