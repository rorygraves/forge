package io.forge.core.log

import io.forge.core.{FeatureId, PieceId}

import java.time.Instant
import upickle.default.{read, write}

/** PR-D D3 — every §19 `kind` payload round-trips through uPickle as a `ujson.Value`.
  *
  * `Action.payload` is `ujson.Value` (raw JSON tree) because the §19 `kind` enum is open at the wire level and
  * different `kind`s carry different payload shapes. Per-kind interpretation lives in `Feature.foldEvents`; this suite
  * only proves the codec preserves the bytes.
  */
class ActionPayloadCodecSuite extends munit.FunSuite:

  private val feature = FeatureId("stripe-webhook")
  private val piece = PieceId("p2")
  private val ts = Instant.parse("2026-05-25T15:42:18.341Z")

  private def actionFor(kind: String, payload: ujson.Value, p: Option[PieceId] = Some(piece)): Action =
    Action(
      seq = 1L,
      at = ts,
      feature = feature,
      piece = p,
      actor = Some("claude"),
      role = Some("driver"),
      kind = kind,
      payload = payload
    )

  private def roundTrip(action: Action): Unit =
    val json = write(action)
    val parsed = read[Action](json)
    assertEquals(parsed, action, s"round-trip failed for kind=${action.kind}")

  // --- §19 kinds ---

  test("fsm.transition round-trip"):
    roundTrip(
      actionFor(
        "fsm.transition",
        ujson.Obj(
          "from" -> ujson.Str("PieceImplementing"),
          "to" -> ujson.Str("PieceAwaitingCi"),
          "prNumber" -> ujson.Num(4291),
          "branch" -> ujson.Str("forge/stripe-webhook/p2")
        )
      )
    )

  test("<actor>.spawn round-trip (claude.spawn)"):
    roundTrip(
      actionFor(
        "claude.spawn",
        ujson.Obj("sessionId" -> ujson.Str("sess-1"), "role" -> ujson.Str("driver"))
      )
    )

  test("<actor>.resume round-trip (claude.resume)"):
    roundTrip(
      actionFor(
        "claude.resume",
        ujson.Obj("oldSessionId" -> ujson.Str("sess-1"), "newSessionId" -> ujson.Str("sess-1"))
      )
    )

  test("<actor>.user_message round-trip"):
    roundTrip(
      actionFor("claude.user_message", ujson.Obj("preview" -> ujson.Str("/done")))
    )

  test("<actor>.assistant_text round-trip"):
    roundTrip(
      actionFor("claude.assistant_text", ujson.Obj("preview" -> ujson.Str("done"), "tokens" -> ujson.Num(42)))
    )

  test("<actor>.tool_use round-trip"):
    roundTrip(
      actionFor(
        "claude.tool_use",
        ujson.Obj("name" -> ujson.Str("Read"), "summary" -> ujson.Str("design.md (lines 1–50)"))
      )
    )

  test("<actor>.ask_user_question round-trip — Native form carries toolUseId"):
    roundTrip(
      actionFor(
        "claude.ask_user_question",
        ujson.Obj(
          "questionMechanism" -> ujson.Str("Native"),
          "phase" -> ujson.Str("design"),
          "answerFile" -> ujson.Str(".forge/specs/feat/audit/spec-answers.md"),
          "toolUseId" -> ujson.Str("tu-001"),
          "question" -> ujson.Str("Anything else?"),
          "answer" -> ujson.Str("yes")
        )
      )
    )

  test("<actor>.ask_user_question round-trip — HaltWithQuestion form omits toolUseId"):
    roundTrip(
      actionFor(
        "codex.ask_user_question",
        ujson.Obj(
          "questionMechanism" -> ujson.Str("HaltWithQuestion"),
          "phase" -> ujson.Str("design"),
          "answerFile" -> ujson.Str(".forge/specs/feat/audit/spec-answers.md"),
          "toolUseId" -> ujson.Null,
          "question" -> ujson.Str("OK?"),
          "answer" -> ujson.Str("y")
        )
      )
    )

  test("<actor>.halt_respawn round-trip"):
    roundTrip(
      actionFor("codex.halt_respawn", ujson.Obj("respawnCount" -> ujson.Num(2), "phase" -> ujson.Str("design")))
    )

  test("<actor>.schema_invalid round-trip"):
    roundTrip(
      actionFor(
        "claude.schema_invalid",
        ujson.Obj("method" -> ujson.Str("designReview"), "validatorError" -> ujson.Str("missing 'verdict' field"))
      )
    )

  test("<actor>.process_retry round-trip"):
    roundTrip(
      actionFor(
        "claude.process_retry",
        ujson.Obj("method" -> ujson.Str("designReview"), "attempt" -> ujson.Num(2), "reason" -> ujson.Str("exit 1"))
      )
    )

  test("audit.piece_merged round-trip"):
    roundTrip(
      actionFor(
        "audit.piece_merged",
        ujson.Obj(
          "p" -> ujson.Str("p2"),
          "prNumber" -> ujson.Num(4291),
          "mergeCommit" -> ujson.Str("0123456abcdef"),
          "mergedAt" -> ujson.Str("2026-05-25T15:42:00Z")
        )
      )
    )

  test("gh.poll round-trip"):
    roundTrip(
      actionFor(
        "gh.poll",
        ujson.Obj("pr" -> ujson.Num(4291), "decision" -> ujson.Str("APPROVED")),
        p = Some(piece)
      )
    )

  test("gh.action round-trip"):
    roundTrip(
      actionFor(
        "gh.action",
        ujson.Obj(
          "verb" -> ujson.Str("pr-create"),
          "args" -> ujson.Arr(ujson.Str("--base"), ujson.Str("main"))
        )
      )
    )

  test("review.anchor_demoted round-trip"):
    roundTrip(
      actionFor(
        "review.anchor_demoted",
        ujson.Obj("file" -> ujson.Str("src/Foo.scala"), "line" -> ujson.Num(42))
      )
    )

  test("review.invalid_verdict round-trip"):
    roundTrip(
      actionFor("review.invalid_verdict", ujson.Obj("reason" -> ujson.Str("missing decision")))
    )

  test("user.command round-trip"):
    roundTrip(
      actionFor(
        "user.command",
        ujson.Obj("cmd" -> ujson.Str("resume"), "hint" -> ujson.Str("RunAnotherFixup")),
        p = None
      )
    )

  test("harness.error round-trip"):
    roundTrip(
      actionFor(
        "harness.error",
        ujson.Obj("kind" -> ujson.Str("log_truncated"), "droppedBytes" -> ujson.Num(73))
      )
    )

  test("harness.preflight_bypassed round-trip"):
    roundTrip(
      actionFor("harness.preflight_bypassed", ujson.Obj("flag" -> ujson.Str("--force")), p = None)
    )

  test("harness.refinery_failed round-trip"):
    roundTrip(
      actionFor("harness.refinery_failed", ujson.Obj("reason" -> ujson.Str("driver exit 2")))
    )

  test("harness.session_killed round-trip"):
    roundTrip(
      actionFor(
        "harness.session_killed",
        ujson.Obj(
          "reason" -> ujson.Str("settle_timeout"),
          "phase" -> ujson.Str("Implement"),
          "sessionId" -> ujson.Str("sess-99")
        )
      )
    )

  test("harness.cache_invalidated round-trip"):
    roundTrip(
      actionFor(
        "harness.cache_invalidated",
        ujson.Obj("cache" -> ujson.Str("branch-protection"), "trigger" -> ujson.Str("resume")),
        p = None
      )
    )

  test("harness.missing_session_id round-trip"):
    roundTrip(
      actionFor(
        "harness.missing_session_id",
        ujson.Obj("phase" -> ujson.Str("design"), "hint" -> ujson.Str("ReopenDesign")),
        p = None
      )
    )

  test("harness.rate_limited round-trip"):
    roundTrip(
      actionFor(
        "harness.rate_limited",
        ujson.Obj("status" -> ujson.Num(429), "retryAfter" -> ujson.Num(60)),
        p = None
      )
    )

  test("harness.price_missing round-trip"):
    roundTrip(
      actionFor(
        "harness.price_missing",
        ujson.Obj("provider" -> ujson.Str("codex"), "model" -> ujson.Str("gpt-5.3-codex")),
        p = None
      )
    )

  test("cost.update round-trip"):
    roundTrip(
      actionFor(
        "cost.update",
        ujson.Obj(
          "provider" -> ujson.Str("claude"),
          "model" -> ujson.Str("claude-opus-4-7"),
          "inputTokens" -> ujson.Num(1024),
          "outputTokens" -> ujson.Num(256),
          "usd" -> ujson.Num(0.42),
          "featureTotalUsd" -> ujson.Num(1.20),
          "pieceTotalUsd" -> ujson.Num(0.42),
          "turnTotalUsd" -> ujson.Num(0.42)
        )
      )
    )

  // --- Edge cases ---

  test("payload — nested object round-trip"):
    roundTrip(
      actionFor(
        "gh.poll",
        ujson.Obj(
          "snapshot" -> ujson.Obj(
            "number" -> ujson.Num(4291),
            "state" -> ujson.Str("MERGED"),
            "checks" -> ujson.Arr(ujson.Obj("name" -> ujson.Str("build"), "status" -> ujson.Str("ok")))
          )
        )
      )
    )

  test("payload — empty object survives the round-trip"):
    roundTrip(actionFor("user.command", ujson.Obj(), p = None))

  test("payload — null leaf survives the round-trip"):
    roundTrip(
      actionFor("claude.ask_user_question", ujson.Obj("toolUseId" -> ujson.Null))
    )
