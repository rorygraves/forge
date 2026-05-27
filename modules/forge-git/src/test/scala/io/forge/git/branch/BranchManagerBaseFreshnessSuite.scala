package io.forge.git.branch

import io.forge.core.{PrNumber, Sha}
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

/** PR-C C7 — `baseFreshness` happy/autoUpdate/refuse paths per v1.2 §9 + BM2. */
class BranchManagerBaseFreshnessSuite extends CatsEffectSuite:

  private val pr = PrNumber(42)
  private val expected = Sha("abc1234")
  private val observed = Sha("def5678")
  private val postUpdate = Sha("9999999")

  private def baseRefOidJson(sha: Sha): ujson.Value =
    ujson.Obj("baseRefName" -> ujson.Str("main"), "baseRefOid" -> ujson.Str(sha.value))

  test("up-to-date — observed matches expected → UpToDate (no update-branch call)"):
    val gh = FakeGhClient.builder.prView(baseRefOidJson(expected)).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      r <- bm.baseFreshness(pr, expected, autoUpdate = true)
    yield assertEquals(r, Right(BaseFreshness.UpToDate))

  test("behind + autoUpdate=true → prUpdateBranch invoked, baseRefOid re-read → Updated(newBaseSha)"):
    val updates = scala.collection.mutable.ArrayBuffer.empty[PrNumber]
    val viewIdx = new java.util.concurrent.atomic.AtomicInteger(0)
    val gh = FakeGhClient.builder
      // First prView returns the stale `observed`; second (after prUpdateBranch) returns the post-update SHA.
      .prView { (_: PrNumber) =>
        cats.effect.IO.delay {
          val i = viewIdx.getAndIncrement()
          if i == 0 then Right(baseRefOidJson(observed))
          else Right(baseRefOidJson(postUpdate))
        }
      }
      .prUpdateBranch { p =>
        updates += p
        cats.effect.IO.pure(Right(()))
      }
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      r <- bm.baseFreshness(pr, expected, autoUpdate = true)
    yield
      assertEquals(r, Right(BaseFreshness.Updated(postUpdate)))
      assertEquals(updates.toList, List(pr))

  test("behind + autoUpdate=true + post-update re-read parse failure → ParseFailure tagged 'post-update'"):
    val viewIdx = new java.util.concurrent.atomic.AtomicInteger(0)
    val gh = FakeGhClient.builder
      .prView { (_: PrNumber) =>
        cats.effect.IO.delay {
          val i = viewIdx.getAndIncrement()
          if i == 0 then Right(baseRefOidJson(observed))
          else Right(ujson.Obj("baseRefName" -> ujson.Str("main"))) // no baseRefOid
        }
      }
      .prUpdateBranch(Right(()))
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      r <- bm.baseFreshness(pr, expected, autoUpdate = true)
    yield r match
      case Left(BranchError.ParseFailure(stage, _)) =>
        assert(stage.contains("post-update"), s"stage was '$stage'")
      case other => fail(s"expected post-update ParseFailure, got $other")

  test("behind + autoUpdate=false → Behind(expected, observed); no update call"):
    val updates = scala.collection.mutable.ArrayBuffer.empty[PrNumber]
    val gh = FakeGhClient.builder
      .prView(baseRefOidJson(observed))
      .prUpdateBranch { p =>
        updates += p
        cats.effect.IO.pure(Right(()))
      }
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      r <- bm.baseFreshness(pr, expected, autoUpdate = false)
    yield
      assertEquals(r, Right(BaseFreshness.Behind(expected, observed)))
      assertEquals(updates.toList, Nil)

  test("malformed payload — missing baseRefOid → BranchError.ParseFailure"):
    val gh = FakeGhClient.builder.prView(ujson.Obj("baseRefName" -> ujson.Str("main"))).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      r <- bm.baseFreshness(pr, expected, autoUpdate = true)
    yield r match
      case Left(BranchError.ParseFailure(stage, detail)) =>
        assert(stage.contains("baseRefOid"))
        assert(detail.contains("missing"))
      case other => fail(s"expected ParseFailure, got $other")
