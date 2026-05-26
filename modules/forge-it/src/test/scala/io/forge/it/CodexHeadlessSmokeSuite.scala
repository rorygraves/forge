package io.forge.it

import cats.effect.unsafe.implicits.global
import io.forge.agents.*
import io.forge.core.{FeatureId, PieceId}

import scala.concurrent.duration.*

/** Slice 1 §17 / design-2.1 PR-C C1 — mirror of [[ClaudeHeadlessSmokeSuite]] for the Codex CLI.
  *
  * Runs `runHeadlessImplementation` against a trivial prompt and asserts the full event pipeline (Subprocess +
  * StreamingDriver + CodexEventParser + CodexConnector) produces `Init + AssistantText + CostUpdate + Result`. The
  * `CostUpdate` USD must be >0 — Codex emits token counts only (Slice 0 §2.2) and the connector converts via the
  * per-call [[PriceTable]] (§7.10(b)). The suite loads the shipped `prices.example.json` so a real CLI run with a real
  * model name produces non-zero USD; a missing price entry would surface here as USD == 0.
  *
  * Skipped when:
  *   - `codex` is not on `PATH`
  *   - `FORGE_IT_SKIP_CODEX=1` (escape hatch for CI / offline runs — C7 gating)
  *
  * Slow by design — Codex first runs take ~5–15s on a warm machine.
  */
class CodexHeadlessSmokeSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 180.seconds

  private val codexOnPath: Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / "codex") => p / "codex"
    }
  private val skipFlag = sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")
  private val canRun = codexOnPath.isDefined && !skipFlag

  // Default model targets the maintainer's account tier (ChatGPT subscription); FORGE_IT_CODEX_MODEL lets users on a
  // different tier override (e.g. OpenAI API tier accounts must set FORGE_IT_CODEX_MODEL=gpt-5-codex). The Slice 1
  // design-2.1 pin was `gpt-5-codex` — switched to `gpt-5.3-codex` after codex 0.133's ChatGPT-tier rejection of
  // `gpt-5-codex` surfaced as silent IT failures whose true cause was being swallowed by CodexEventParser dropping
  // `turn.failed` (now fixed) and CodexStreamingSession silently closing on first-turn failure (now fixed). The
  // override must still be an entry in `prices.example.json` or the CostUpdate assertion below fails when usdFor
  // returns None → 0.
  private val model: String =
    sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")

  private def loadPriceTable: PriceTable =
    val stream = getClass.getResourceAsStream("/prices.example.json")
    require(stream != null, "prices.example.json missing from classpath")
    try
      val raw = scala.io.Source.fromInputStream(stream)("UTF-8").mkString
      upickle.default.read[PriceTable](raw)
    finally stream.close()

  test("Codex headless trivial prompt: full event pipeline produces Init + AssistantText + CostUpdate + Result"):
    assume(canRun, "skipped — `codex` not on PATH or FORGE_IT_SKIP_CODEX=1")
    val systemPromptPath = os.temp(
      contents = "You are a terse assistant. Reply with one word and stop.",
      prefix = "forge-it-system-",
      suffix = ".md",
      deleteOnExit = true
    )
    val connector = CodexConnector(
      binary = codexOnPath.get.toString,
      model = model,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "read-only", approvalMode = "never")
    )
    val program = connector
      .runHeadlessImplementation(
        ImplementationPrompt(
          featureId = FeatureId("smoke-1"),
          pieceId = PieceId("p1"),
          systemPromptPath = systemPromptPath,
          body = "Say 'ok' and stop."
        )
      )
      .flatMap: session =>
        for
          events <- session.events.compile.toVector
          _ <- session.close()
        yield (session.sessionId, events)

    val (threadId, events) = program.unsafeRunSync()

    // Codex thread id is a UUID-shaped string (Slice 0 §2.2; transcripts/04-codex-headless.jsonl).
    assert(threadId.nonEmpty, clue = threadId)

    // First emitted event is Init carrying that thread id.
    assertEquals(events.headOption, Some(AgentEvent.Init(threadId)))

    // At least one assistant-text event flowed (Codex doesn't emit tool_use as item.completed in v1; parser keeps
    // only agent_message → AssistantText for non-halt text).
    assert(events.exists(_.isInstanceOf[AgentEvent.AssistantText]), clue = events)

    // CostUpdate emitted with usd > 0 — proves the price table looked the model up and didn't fall through to 0.
    val costs = events.collect { case AgentEvent.CostUpdate(c) => c }
    assert(costs.nonEmpty, clue = events)
    costs.foreach: c =>
      assertEquals(c.provider, "openai")
      assertEquals(c.model, model)
      assert(c.usd > BigDecimal(0), clue = c)
      assert(c.outputTokens > 0, clue = c)

    // Stream terminates with a Result (Codex emits success=true; durationMs is 0 since the parser doesn't measure
    // wall-clock, §CodexEventParser docs).
    events.last match
      case AgentEvent.Result(success, _) => assertEquals(success, true)
      case other => fail(s"expected Result as last event, got $other")
