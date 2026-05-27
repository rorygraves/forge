package io.forge.git.branch

import io.forge.core.{BranchName, PrNumber}
import io.forge.git.cli.GhError
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

/** PR-C C7 — `createPr` happy path + error promotion. */
class BranchManagerCreatePrSuite extends CatsEffectSuite:

  private val head = BranchName("forge/feat/p1")
  private val base = BranchName("main")

  test("happy — gh.prCreate returns a PR number"):
    val seen = scala.collection.mutable.ArrayBuffer.empty[(String, String, BranchName, BranchName)]
    val gh = FakeGhClient.builder.prCreate { (t, b, base, head) =>
      seen += ((t, b, base, head))
      cats.effect.IO.pure(Right(PrNumber(4291)))
    }.build
    val git = FakeGitClient.builder.currentBranch(head).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, gh, cache)
      r <- bm.createPr("piece title", "body", base)
    yield
      assertEquals(r, Right(PrNumber(4291)))
      assertEquals(seen.toList, List(("piece title", "body", base, head)))

  test("transient gh error → BranchError.GhFailure preserves underlying detail"):
    val gh = FakeGhClient.builder
      .prCreate(Left(GhError.Transient(1, "boom")))
      .build
    val git = FakeGitClient.builder.currentBranch(head).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, gh, cache)
      r <- bm.createPr("title", "body", base)
    yield r match
      case Left(BranchError.GhFailure(GhError.Transient(1, "boom"))) => ()
      case other => fail(s"expected GhFailure(Transient), got $other")

  test("parse failure from gh → BranchError.GhFailure(ParseFailure) — url regex miss is preserved"):
    val parseErr = GhError.ParseFailure("pr-create-url", IllegalStateException("no match"), "?")
    val gh = FakeGhClient.builder.prCreate(Left(parseErr)).build
    val git = FakeGitClient.builder.currentBranch(head).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, gh, cache)
      r <- bm.createPr("title", "body", base)
    yield r match
      case Left(BranchError.GhFailure(GhError.ParseFailure(stage, _, _))) =>
        assertEquals(stage, "pr-create-url")
      case other => fail(s"expected GhFailure(ParseFailure), got $other")
