package io.forge.core.log

import io.forge.core.{FeatureId, PieceId}

import java.time.Instant
import upickle.default.{read, write}

/** PR-B B7 — codec round-trip + wire-name assertion for `Action`.
  *
  * The in-memory field is `at: Instant`; the §19 NDJSON line writes `"ts"`. The `@key("ts")` annotation on `Action.at`
  * is the only thing standing between the §6 in-memory shape and the §19 on-disk shape.
  */
class ActionSuite extends munit.FunSuite:

  private val seq = 142L
  private val ts = Instant.parse("2026-05-25T15:42:18.341Z")
  private val feature = FeatureId("stripe-webhook")
  private val piece = PieceId("p2")

  private val sample = Action(
    seq = seq,
    at = ts,
    feature = feature,
    piece = Some(piece),
    actor = Some("claude"),
    role = Some("driver"),
    kind = "fsm.transition",
    payload = ujson.Obj(
      "from" -> "PieceImplementing",
      "to" -> "PieceAwaitingCi",
      "prNumber" -> 4291,
      "branch" -> "forge/stripe-webhook/p2"
    )
  )

  test("Action — round-trip preserves every field"):
    val json = write(sample)
    val parsed = read[Action](json)
    assertEquals(parsed, sample)

  test("Action — wire form uses 'ts' (not 'at') per §19"):
    val json = ujson.read(write(sample))
    assert(json.obj.contains("ts"), s"expected wire key 'ts' in: $json")
    assert(!json.obj.contains("at"), s"unexpected wire key 'at' in: $json")
    assertEquals(json("ts").str, ts.toString)

  test("Action — wire shape matches the §19 example fields"):
    val json = ujson.read(write(sample))
    assertEquals(json("seq").num.toLong, seq)
    assertEquals(json("ts").str, ts.toString)
    assertEquals(json("feature").str, "stripe-webhook")
    assertEquals(json("piece").str, "p2")
    assertEquals(json("actor").str, "claude")
    assertEquals(json("role").str, "driver")
    assertEquals(json("kind").str, "fsm.transition")
    assertEquals(json("payload")("from").str, "PieceImplementing")

  test("Action — None for piece/actor/role survives the round-trip"):
    val harnessOnly = sample.copy(piece = None, actor = None, role = None, kind = "harness.error")
    val json = write(harnessOnly)
    val parsed = read[Action](json)
    assertEquals(parsed, harnessOnly)

  test("ActionDraft.stamp produces an Action with the supplied seq + at"):
    val draft = ActionDraft(
      feature = feature,
      piece = Some(piece),
      actor = Some("claude"),
      role = Some("driver"),
      kind = "fsm.transition",
      payload = ujson.Obj("k" -> "v")
    )
    val stamped = draft.stamp(99L, ts)
    assertEquals(stamped.seq, 99L)
    assertEquals(stamped.at, ts)
    assertEquals(stamped.feature, feature)
    assertEquals(stamped.kind, "fsm.transition")

  test("Action.from delegates to ActionDraft.stamp"):
    val draft = ActionDraft(feature, None, None, None, "user.command", ujson.Obj())
    assertEquals(Action.from(draft, 1L, ts), draft.stamp(1L, ts))
