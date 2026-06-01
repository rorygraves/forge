package io.forge.git.cli

import io.forge.core.PrNumber

/** Roadmap §3.5 driver-respawn-avoidance (Unit A) — `RealGhClient.parsePrList` against the real `gh pr list --head <b>
  * --state open --json number` stdout shape (a JSON array of `{ "number": <int> }` objects, `[]` when nothing matches;
  * verified against `gh 2.93.0`). The parser is the one chokepoint that turns `gh`'s wire shape into
  * `Option[PrNumber]`, so the suite pins the empty / single / malformed cases rather than synthetic strings.
  */
class RealGhClientPrListSuite extends munit.FunSuite:

  test("empty array (no matching PR) → Right(None)"):
    assertEquals(RealGhClient.parsePrList("[]"), Right(None))

  test("single-element array → Right(Some(number))"):
    assertEquals(RealGhClient.parsePrList("""[{"number":7}]"""), Right(Some(PrNumber(7))))

  test("first element wins when gh returns more than one"):
    assertEquals(RealGhClient.parsePrList("""[{"number":12},{"number":34}]"""), Right(Some(PrNumber(12))))

  test("a non-array payload → ParseFailure"):
    RealGhClient.parsePrList("""{"number":7}""") match
      case Left(GhError.ParseFailure(stage, _, _)) => assertEquals(stage, "pr-list")
      case other => fail(s"expected ParseFailure, got $other")

  test("first element missing a numeric number → ParseFailure"):
    RealGhClient.parsePrList("""[{"state":"OPEN"}]""") match
      case Left(GhError.ParseFailure(stage, _, _)) => assertEquals(stage, "pr-list")
      case other => fail(s"expected ParseFailure, got $other")

  test("invalid JSON → ParseFailure"):
    RealGhClient.parsePrList("not json") match
      case Left(GhError.ParseFailure(stage, _, _)) => assertEquals(stage, "pr-list")
      case other => fail(s"expected ParseFailure, got $other")
