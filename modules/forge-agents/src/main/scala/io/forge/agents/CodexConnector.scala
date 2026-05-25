package io.forge.agents

import cats.effect.IO
import cats.syntax.all.*
import io.forge.core.{QuestionMechanism, SchemaMechanism}

import scala.concurrent.duration.*

/** §7.1 Codex driver/reviewer adapter.
  *
  * Layer 4 scope: real implementations for the **headless** driver methods (`runHeadlessImplementation`, `runFixup`)
  * and full argv construction + telemetry. The streaming methods (`runStreamingSpec`, `resumeStreamingSpec`) are
  * intentionally stubbed in this PR — see the comment on each method. The reviewer methods are stubbed as Layer 5
  * follow-up.
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

  /** **Stub.** Codex's `exec` model spawns one process per turn (Slice 0 §2.2), so a streaming-spec session that
    * accepts multiple `send(...)` calls maps onto N invocations of `codex exec resume <thread-id>` rather than a single
    * long-lived process. That multi-turn-as-one-shots facade is non-trivial — it has to lazily capture the thread id
    * from the first turn (so the trait's synchronous `sessionId: String` accessor stays honest), serialise turns under
    * a mutex, and merge per-turn event streams.
    *
    * Open design question that has to be resolved before we build it: where does the **initial** user message come
    * from? `runStreamingSpec(systemPromptPath: os.Path)` doesn't take one; Claude's interactive mode doesn't need one;
    * Codex `exec` requires one. The minimum-friction option is to extend the trait with an optional initial message;
    * the alternative is for the orchestrator to send a tiny "ready?" priming turn whose response is discarded —
    * wasteful and visible in `audit/spec-answers.md`.
    *
    * Lands in the slice-1 follow-up alongside the Layer 5 reviewer one-shots, once the integration tests confirm the
    * exact `exec resume` behaviour.
    */
  def runStreamingSpec(systemPromptPath: os.Path): IO[StreamingSession] =
    IO.raiseError(
      NotImplementedError(
        "CodexConnector.runStreamingSpec — Codex's one-process-per-turn `exec` model needs a multi-turn facade " +
          "that the v1 trait doesn't yet support. See the docstring; lands with the slice-1 follow-up."
      )
    )

  /** **Stub.** Same shape as [[runStreamingSpec]] — needs the multi-turn-as-one-shots facade to spawn `codex exec
    * resume <id> <msg>` per `send(...)` call. See [[runStreamingSpec]]'s docstring.
    */
  def resumeStreamingSpec(sessionId: String): IO[StreamingSession] =
    IO.raiseError(
      NotImplementedError(
        "CodexConnector.resumeStreamingSpec — same multi-turn facade is needed as runStreamingSpec; see docstring."
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
