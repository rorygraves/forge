package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.agents.*
import io.forge.core.{FeatureId, PieceId}

import scala.concurrent.duration.*

/** Slice 1 §17 — first real-CLI integration: run the Claude driver headless against a trivial prompt and assert the
  * connector's event pipeline produces what we expect. Proves the full stack (Subprocess + StreamingDriver +
  * ClaudeEventParser + ClaudeConnector) works against the actual `claude` binary, not just transcript replays.
  *
  * Skipped when:
  *   - `claude` is not on `PATH`
  *   - `FORGE_IT_SKIP_CLAUDE=1` (escape hatch for CI / offline runs)
  *
  * Slow by design — typical first run takes 5–20s depending on cache state.
  */
class ClaudeHeadlessSmokeSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 120.seconds

  private val claudeOnPath: Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / "claude") => p / "claude"
    }
  private val skipFlag = sys.env.get("FORGE_IT_SKIP_CLAUDE").contains("1")
  private val canRun = claudeOnPath.isDefined && !skipFlag

  private val uuidRegex =
    """^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""".r

  test("Claude headless trivial prompt: full event pipeline produces Init + AssistantText + CostUpdate + Result"):
    assume(canRun, "skipped — `claude` not on PATH or FORGE_IT_SKIP_CLAUDE=1")
    val systemPromptPath = os.temp(
      contents = "You are a terse assistant. Reply with one word and stop.",
      prefix = "forge-it-system-",
      suffix = ".md",
      deleteOnExit = true
    )
    val connector = ClaudeConnector(binary = claudeOnPath.get.toString)
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

    val (sessionId, events) = program.unsafeRunSync()

    // Session id must be a UUID per Slice 0 §2.1.
    assert(uuidRegex.matches(sessionId), clue = sessionId)

    // First emitted event is Init carrying that session id.
    assertEquals(events.headOption, Some(AgentEvent.Init(sessionId)))

    // At least one assistant text or tool-use event flowed.
    assert(
      events.exists {
        case _: AgentEvent.AssistantText => true
        case _: AgentEvent.ToolUse => true
        case _ => false
      },
      clue = events
    )

    // CostUpdate emitted with usd > 0 (Slice 0 confirmed Claude reports total_cost_usd on every result event).
    val costs = events.collect { case AgentEvent.CostUpdate(c) => c }
    assert(costs.nonEmpty, clue = events)
    costs.foreach: c =>
      assertEquals(c.provider, "anthropic")
      assert(c.usd > BigDecimal(0), clue = c)
      assert(c.outputTokens > 0, clue = c)

    // Stream terminates with a successful Result.
    events.last match
      case AgentEvent.Result(success, durationMs) =>
        assertEquals(success, true)
        assert(durationMs > 0, clue = durationMs)
      case other => fail(s"expected Result as last event, got $other")

  test("Claude streaming spec is currently stubbed (pending §7.1 trait extension)"):
    // Documents the design point surfaced by review of the original streaming wiring: with `-p --input-format
    // stream-json`, Claude only emits `init` AFTER the first user-message JSON frame arrives on stdin (verified
    // empirically against Claude CLI 2.1.150). The §7.1 trait's synchronous `sessionId: String` accessor cannot be
    // honored at spawn time; the orchestrator needs an initial-message parameter that the v1 trait doesn't carry —
    // a forge-design-1.2 change. Same blocker as CodexConnector.runStreamingSpec.
    val connector = ClaudeConnector(binary = claudeOnPath.map(_.toString).getOrElse("claude"))
    val r1 = connector.runStreamingSpec(os.Path("/tmp/x.md")).attempt.unsafeRunSync()
    assert(
      r1.left.exists(_.getMessage.contains("emits init only after the first")),
      clue = r1
    )
