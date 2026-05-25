package io.forge.agents

import cats.effect.IO
import cats.syntax.all.*
import io.forge.core.{QuestionMechanism, SchemaMechanism}
import ujson.Value

import scala.concurrent.duration.*

/** §7.1 Codex driver/reviewer adapter.
  *
  * v1 covers the **headless** driver methods (`runHeadlessImplementation`, `runFixup`) end-to-end, plus full argv
  * construction and telemetry, and the **Layer 5 reviewer one-shots** (`reviewDesign` / `reviewPr` / `refine`) via the
  * `--output-schema` Native schema mechanism (§7.4). The streaming methods (`runStreamingSpec`, `resumeStreamingSpec`)
  * match the v1.2 §7.1 trait shape but remain stubbed pending Task #6 of the Slice 1 trait-shape PR.
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
  *
  * **Reviewer one-shots** use `codex exec --json --output-schema <path>` (Slice 0 §3.2). The schema-conformant payload
  * lives in the `item.completed{agent_message}.text` event as a JSON string. Each reviewer call builds its own
  * [[CodexSessionSettings]] with the appropriate schema; sticky-settings (§7.10(c)) doesn't bite because reviewer
  * one-shots are independent `exec` invocations, never resumes.
  */
final class CodexConnector(
    binary: String = "codex",
    model: String,
    priceTable: PriceTable,
    sessionSettings: CodexSessionSettings,
    cwd: Option[os.Path] = None,
    extraEnv: Map[String, String] = Map.empty,
    initTimeout: FiniteDuration = 30.seconds,
    reviewerAssets: Option[ReviewerAssets] = None,
    reviewerSandbox: String = "read-only",
    reviewerApprovalMode: String = "never",
    reviewerTimeout: FiniteDuration = 5.minutes
) extends Connector:

  val name: String = "codex"
  val questionMechanism: QuestionMechanism = QuestionMechanism.HaltWithQuestion
  val schemaMechanism: SchemaMechanism = SchemaMechanism.Native

  // --- driver methods ----

  /** v1.2 §7.1 stub — Task #6 builds the multi-process facade that maps the trait's "one streaming session" model onto
    * Codex's one-process-per-turn `exec` reality (Slice 0 §2.2): the first turn spawns `codex exec --json
    * <combined-prompt>` and captures the thread id from the Init event; subsequent `send` / `answerQuestion` calls open
    * fresh `codex exec resume --json <thread-id>` subprocesses, draining their events into the same channel.
    */
  def runStreamingSpec(systemPromptPath: os.Path, initialUserMessage: String): IO[StreamingSession] =
    IO.raiseError(
      NotImplementedError(
        "CodexConnector.runStreamingSpec — implementation lands with Task #6 of the Slice 1 trait-shape PR."
      )
    )

  /** v1.2 §7.1 stub — same as [[runStreamingSpec]]; lands with Task #6. */
  def resumeStreamingSpec(sessionId: String, message: String): IO[StreamingSession] =
    IO.raiseError(
      NotImplementedError(
        "CodexConnector.resumeStreamingSpec — implementation lands with Task #6 of the Slice 1 trait-shape PR."
      )
    )

  def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] =
    spawnHeadless(prompt.systemPromptPath, prompt.body).widen

  def runFixup(prompt: FixupPrompt): IO[AgentSession] =
    spawnHeadless(prompt.systemPromptPath, prompt.body).widen

  // --- reviewer methods ----

  def reviewDesign(input: DesignReviewInput): IO[DesignReview] =
    runReviewer(
      assets => assets.designReview,
      ReviewerPrompts.designReviewBody(input),
      ReviewDecoders.designReview
    )

  def reviewPr(input: PrReviewInput): IO[PrReview] =
    runReviewer(
      assets => assets.prReview,
      ReviewerPrompts.prReviewBody(input),
      ReviewDecoders.prReview
    )

  def refine(input: RefineInput): IO[RefineResult] =
    runReviewer(
      assets => assets.refine,
      ReviewerPrompts.refineBody(input),
      ReviewDecoders.refineResult
    )

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

  /** Shared reviewer-one-shot path. Builds a per-call [[CodexSessionSettings]] with the right `--output-schema` (§7.10
    * sticky-settings doesn't bite because reviewer calls are independent exec invocations), prepends the reviewer-role
    * system prompt via [[CodexPrompt.withSystemBlock]] (§7.10(a)), spawns the subprocess, drains the JSONL stdout
    * looking for the `item.completed{agent_message}` text payload, and decodes it via the shared [[ReviewDecoders]]
    * into the per-method domain type.
    */
  private def runReviewer[A](
      pick: ReviewerAssets => ReviewerAssets.PerMethod,
      body: String,
      decode: Value => Either[String, A]
  ): IO[A] =
    reviewerAssets match
      case None =>
        IO.raiseError(
          ReviewerNotConfigured(
            "CodexConnector reviewer one-shot called but no ReviewerAssets configured; pass " +
              "reviewerAssets at construction so the connector can locate schema + system-prompt files. " +
              "Not retried — see §7.6 / AdapterError."
          )
        )
      case Some(assets) =>
        val per = pick(assets)
        val reviewerSettings = CodexSessionSettings(
          sandbox = reviewerSandbox,
          outputSchema = Some(per.schema),
          addDirs = Vector.empty,
          approvalMode = reviewerApprovalMode,
          workingDirectory = None
        )
        for
          combined <- IO.delay(CodexPrompt.withSystemBlock(per.systemPrompt, body))
          argv = CodexConnector.execArgv(binary, model, reviewerSettings, combined)
          payload <- Subprocess
            .spawn(argv, cwd = cwd, env = extraEnv)
            .use(sp => CodexConnector.collectReviewerPayload(sp, reviewerTimeout))
          decoded <- IO.fromEither(
            decode(payload).left.map(detail => StructuredOutputMalformed(detail))
          )
        yield decoded

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

  // --- reviewer helpers (extracted for unit testing) ----

  /** Drain stdout from a `codex exec --json --output-schema ...` invocation, locate the
    * `item.completed{type:agent_message}.text` field (Slice 0 §3.2, `transcripts/06-codex-schema.jsonl`), and return
    * its parsed-JSON contents.
    *
    *   - Missing agent_message before exit → [[StructuredOutputMissing]] (adapter error, §7.5).
    *   - agent_message text not valid JSON → [[StructuredOutputMissing]].
    *   - Subprocess crash / non-zero exit / timeout → [[ReviewerProcessFailure]] (retryable, §7.6).
    *
    * If multiple `agent_message` events appear in a turn we take the **last** one — the schema-constrained final
    * message — and ignore any narration text that preceded it. Slice 0 transcripts only ever showed one, but multiple
    * is allowed by Codex's stream model.
    */
  def collectReviewerPayload(sp: Subprocess, timeout: FiniteDuration): IO[Value] =
    val drainStdout = sp.stdout.compile.toVector
    val drainStderr = sp.stderr.compile.toVector
    val collect =
      for
        stdoutFiber <- drainStdout.start
        stderrFiber <- drainStderr.start
        exit <- sp.waitFor
        stdoutLines <- stdoutFiber.joinWithNever
        stderrLines <- stderrFiber.joinWithNever
      yield (exit, stdoutLines, stderrLines)
    collect
      .timeoutTo(
        timeout,
        sp.kill() *> IO.raiseError(
          ReviewerProcessFailure(s"Codex reviewer process exceeded timeout of $timeout — killed")
        )
      )
      .flatMap: (exit, stdoutLines, stderrLines) =>
        if exit != 0 then
          IO.raiseError(
            ReviewerProcessFailure(
              s"Codex reviewer process exited $exit; stderr tail: ${stderrLines.takeRight(20).mkString("\n")}"
            )
          )
        else
          IO.fromEither(
            extractAgentMessageText(stdoutLines).left.map(StructuredOutputMissing(_))
          )

  /** Extract the **last** `item.completed{type:agent_message}.text` payload from a Codex JSONL stream and parse it as
    * JSON. Returns `Left` when no agent_message line is present, when the field is missing, or when the text isn't
    * valid JSON. Helper is `def` (not on a parser instance) so tests can hit it directly.
    */
  def extractAgentMessageText(lines: Vector[String]): Either[String, Value] =
    val agentMessages = lines.iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        try
          val v = ujson.read(line)
          val isAgentMessage =
            v.objOpt.flatMap(_.get("type")).flatMap(_.strOpt).contains("item.completed") &&
              v.objOpt
                .flatMap(_.get("item"))
                .flatMap(_.objOpt)
                .flatMap(_.get("type"))
                .flatMap(_.strOpt)
                .contains("agent_message")
          if !isAgentMessage then None
          else
            v.obj
              .get("item")
              .flatMap(_.objOpt)
              .flatMap(_.get("text"))
              .flatMap(_.strOpt)
        catch case _: ujson.ParsingFailedException => None
      }
      .toVector
    agentMessages.lastOption match
      case None => Left("no agent_message event found in Codex stdout")
      case Some(text) =>
        try Right(ujson.read(text))
        catch
          case e: ujson.ParsingFailedException =>
            Left(s"Codex agent_message text was not valid JSON: ${e.getMessage}")
