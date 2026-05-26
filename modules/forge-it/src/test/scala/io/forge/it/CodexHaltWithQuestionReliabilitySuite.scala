package io.forge.it

import cats.effect.unsafe.implicits.global
import io.forge.agents.*

import scala.concurrent.duration.*

/** Slice 1 §17 / design-2.1 PR-C C4 — Codex `HaltWithQuestion` (§7.3) reliability sample.
  *
  * Codex has no native question primitive, so the design relies on prompt-engineering Codex to emit a strict JSON
  * envelope (`{status:"needs_human", question, options, allowFreeText, severity}`) as its final output when blocked.
  * The [[HaltWithQuestion.tryParse]] parser converts that envelope into [[AgentEvent.AskUserQuestion]] with `toolUseId
  * \= None`. §16 sets the gating bar at **≥19/20** successful detections — anything below means the prompt-engineering
  * convention isn't load-bearing enough and the design needs to narrow scope (§16.1).
  *
  * This suite runs the contrived ambiguous prompt against the real Codex CLI ≥20 times and asserts the ratio. On miss
  * the test fails with the observed rate so the reviewer can decide whether to (a) tighten the system prompt, (b)
  * narrow the §16.1 scope claim, or (c) widen `maxHaltRespawns` to amortise misses across retries.
  *
  * Skipped when:
  *   - `codex` is not on `PATH`
  *   - `FORGE_IT_SKIP_CODEX=1` (escape hatch for CI / offline runs — C7 gating)
  *
  * **Very slow by design** — 20 runs × ~5–15s each ≈ 2–5 minutes wall-clock. Default `munitTimeout` is set well above
  * the worst-case observed runtime on a cold machine.
  *
  * **Opt-in by default.** Even on a machine with `codex` on PATH this suite skips unless `FORGE_IT_RUN_RELIABILITY=1`
  * is set, so `sbt "project forge-it" test` doesn't spend minutes hitting the model 20× on every run. CI / on-demand
  * validation flips the gate. The other PR-C suites (smoke, streaming-spec) run unconditionally when the binary is
  * present — they're each <30s.
  */
class CodexHaltWithQuestionReliabilitySuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 600.seconds

  private val codexOnPath: Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / "codex") => p / "codex"
    }
  private val skipFlag = sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")
  private val reliabilityOptIn = sys.env.get("FORGE_IT_RUN_RELIABILITY").contains("1")
  private val canRun = codexOnPath.isDefined && !skipFlag && reliabilityOptIn

  // FORGE_IT_CODEX_MODEL lets users on a different account tier override the default — see CodexHeadlessSmokeSuite.
  private val model: String =
    sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")

  private def loadPriceTable: PriceTable =
    val stream = getClass.getResourceAsStream("/prices.example.json")
    require(stream != null, "prices.example.json missing from classpath")
    try
      val raw = scala.io.Source.fromInputStream(stream)("UTF-8").mkString
      upickle.default.read[PriceTable](raw)
    finally stream.close()

  /** §7.3 halt-envelope system prompt. The wording mirrors the convention sketched in `forge-design-1.2.md` §7.3: Codex
    * is told that on ambiguity, the SOLE output must be a single JSON object matching the schema, with no surrounding
    * prose. Concrete schema is given inline so the model doesn't have to invent field names — a loose-worded prompt was
    * tried in Slice 0 exploration and produced ~6/20 valid envelopes; spelling out the JSON literally lifted that to
    * ≥19/20 (the §16 bar).
    */
  private val haltSystemPrompt: String =
    """You are an automated assistant. If the user's request is ambiguous and requires human
      |judgement before you can proceed, you MUST NOT guess. Instead, output a SINGLE JSON object
      |as your only response, with no prose before or after it, exactly matching this schema:
      |
      |{"status":"needs_human","question":"<one short question>","options":["<option1>","<option2>"],
      | "allowFreeText":true,"severity":"clarifying"}
      |
      |The "status" field MUST be the literal string "needs_human". Do not emit any other JSON
      |envelope. Do not narrate before the JSON. Do not add a markdown code fence. Output the raw
      |JSON object and stop.
      |""".stripMargin

  /** Deliberately under-specified. Pick-from-ambiguous prompts reliably defeat the model's tendency to assume; a "build
    * me an app" or "what's the weather" would be answered without halting. This phrasing leaves the choice of domain
    * completely open, which any sensible model interprets as needing clarification.
    */
  private val ambiguousUserPrompt: String =
    "Choose for me. Then tell me what you chose."

  private val samples: Int = 20
  private val passingThreshold: Int = 19

  test(
    s"C4: HaltWithQuestion reliability ≥ $passingThreshold/$samples on ambiguous prompt + halt-envelope system prompt"
  ):
    assume(
      canRun,
      "skipped — `codex` not on PATH, or FORGE_IT_SKIP_CODEX=1, or FORGE_IT_RUN_RELIABILITY!=1 (this suite is " +
        "opt-in: 20 real-CLI runs ≈ 2–5 minutes, so it's gated off the default forge-it test run)."
    )
    val sys = os.temp(contents = haltSystemPrompt, prefix = "forge-it-halt-sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = codexOnPath.get.toString,
      model = model,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "read-only", approvalMode = "never")
    )

    val results: Vector[Boolean] = (1 to samples).iterator.map { _ =>
      val events = connector
        .runStreamingSpec(sys, ambiguousUserPrompt)
        .flatMap: session =>
          // close() FIRST, then drain. CodexStreamingSession keeps its channel open across turns (more turns may
          // come), so drain-before-close would block forever. close() blocks on the turn mutex (the current turn's
          // stdout drains naturally and the subprocess exits), then closes the channel; drain returns. Same idiom
          // as the other PR-C suites + CodexConnectorSuite's facade tests.
          for
            _ <- session.close()
            es <- session.events.compile.toVector
          yield es
        .unsafeRunSync()
      // Success = at least one AskUserQuestion event in the turn (the parser only emits it when tryParse succeeds —
      // see CodexEventParser.parseItemCompleted), which IS the gating bar §7.3 / §16 cares about.
      events.exists(_.isInstanceOf[AgentEvent.AskUserQuestion])
    }.toVector

    val passes = results.count(identity)
    assert(
      passes >= passingThreshold,
      clue = s"HaltWithQuestion reliability $passes/$samples < $passingThreshold/$samples threshold (§16). " +
        s"Per-run outcomes: ${results.zipWithIndex.map((b, i) => s"#${i + 1}=${if b then "halt" else "miss"}").mkString(", ")}"
    )
