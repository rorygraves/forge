package io.forge.git.cli.fake

import io.forge.core.{BranchName, PrNumber}
import io.forge.git.cli.GhError
import munit.CatsEffectSuite

/** PR-A A4 — smoke for `FakeGhClient.builder`. Validates the canned-response surface and the "unconfigured" default so
  * a test using a method the builder didn't stub gets a clear failure instead of a silent pass.
  */
class FakeGhClientSuite extends CatsEffectSuite:

  test("unconfigured prView → Transient error naming the method"):
    val gh = FakeGhClient.builder.build
    gh.prView(PrNumber(1), Vector("state")).map {
      case Left(GhError.Transient(_, msg)) => assert(msg.contains("prView"))
      case other => fail(s"expected unconfigured prView error, got $other")
    }

  test("prView with a single canned json → returns that json"):
    val payload = ujson.Obj("state" -> ujson.Str("OPEN"))
    val gh = FakeGhClient.builder.prView(payload).build
    gh.prView(PrNumber(42), Vector("state")).map {
      case Right(j) => assertEquals(j("state").str, "OPEN")
      case other => fail(s"expected the canned payload, got $other")
    }

  test("prViewSequence — each call pops the next response, then exhausts"):
    val first = ujson.Obj("state" -> ujson.Str("OPEN"))
    val second = ujson.Obj("state" -> ujson.Str("MERGED"))
    val gh = FakeGhClient.builder
      .prViewSequence(Vector(Right(first), Right(second)))
      .build
    for
      r1 <- gh.prView(PrNumber(1), Vector("state"))
      r2 <- gh.prView(PrNumber(1), Vector("state"))
      r3 <- gh.prView(PrNumber(1), Vector("state"))
    yield
      assertEquals(r1.toOption.map(_("state").str), Some("OPEN"))
      assertEquals(r2.toOption.map(_("state").str), Some("MERGED"))
      assert(r3.isLeft, "third call should be the exhaustion error")

  test("prCreate with canned PR number"):
    val gh = FakeGhClient.builder.prCreate(PrNumber(99)).build
    gh.prCreate("t", "b", BranchName("main"), BranchName("feat/p1")).map {
      case Right(pr) => assertEquals(pr, PrNumber(99))
      case other => fail(s"expected PR 99, got $other")
    }

  test("apiBranchProtection with None → returns None (unprotected)"):
    val gh = FakeGhClient.builder.apiBranchProtection(Right(None)).build
    gh.apiBranchProtection(BranchName("main")).map {
      case Right(None) => ()
      case other => fail(s"expected Right(None), got $other")
    }

  test("apiBranchProtection with a payload → returns Some(payload)"):
    val payload = ujson.Obj("contexts" -> ujson.Arr(ujson.Str("ci")))
    val gh = FakeGhClient.builder.apiBranchProtection(Right(Some(payload))).build
    gh.apiBranchProtection(BranchName("main")).map {
      case Right(Some(j)) => assertEquals(j("contexts")(0).str, "ci")
      case other => fail(s"expected payload, got $other")
    }

  test("unconfigured prForBranch → Transient error naming the method"):
    val gh = FakeGhClient.builder.build
    gh.prForBranch(BranchName("feat/p1")).map {
      case Left(GhError.Transient(_, msg)) => assert(msg.contains("prForBranch"))
      case other => fail(s"expected unconfigured prForBranch error, got $other")
    }

  test("prForBranch with a canned Some → returns that PR"):
    val gh = FakeGhClient.builder.prForBranch(Some(PrNumber(7))).build
    gh.prForBranch(BranchName("feat/p1")).map {
      case Right(Some(pr)) => assertEquals(pr, PrNumber(7))
      case other => fail(s"expected Some(7), got $other")
    }

  test("prForBranch with None → no open PR for the branch"):
    val gh = FakeGhClient.builder.prForBranch(None).build
    gh.prForBranch(BranchName("feat/p1")).map {
      case Right(None) => ()
      case other => fail(s"expected Right(None), got $other")
    }
