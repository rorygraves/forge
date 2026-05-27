package io.forge.git.branch

import io.forge.core.{BranchName, FeatureId, PieceId, Sha}
import io.forge.git.cli.GitError
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

/** PR-C C7 — `createPieceBranch` captures `(branchName, baseSha)` from the `BaseSnapshot` it was handed. The
  * orchestrator (Slice 4, carry-forward **S2-5**) is responsible for the atomic manifest write that pairs `baseSha`
  * with `status = InProgress` before transitioning — *not* this method. The trait-level docstring names S2-5 so the
  * future reader doesn't expect this code to do the persistence.
  */
class BranchManagerCreatePieceBranchSuite extends CatsEffectSuite:

  private val feature = FeatureId("stripe-webhook")
  private val piece = PieceId("p1")
  private val base = BaseSnapshot(BranchName("main"), Sha("abc1234"))

  test("createPieceBranch — derives `<prefix>/<feature>/<piece>` and returns baseSha verbatim"):
    val seen = scala.collection.mutable.ArrayBuffer.empty[(BranchName, Option[BranchName])]
    val git = FakeGitClient.builder.checkout { (b, f) =>
      seen += ((b, f))
      cats.effect.IO.pure(Right(()))
    }.build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.createPieceBranch(feature, piece, "forge", base)
    yield
      assertEquals(r, Right((BranchName("forge/stripe-webhook/p1"), base.sha)))
      assertEquals(seen.toList, List((BranchName("forge/stripe-webhook/p1"), Some(BranchName("main")))))

  test("createPieceBranch — git checkout failure surfaces as BranchError.GitFailure"):
    val git = FakeGitClient.builder
      .checkout(Left(GitError.Transient(1, "branch already exists")))
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.createPieceBranch(feature, piece, "forge", base)
    yield r match
      case Left(BranchError.GitFailure(_: GitError.Transient)) => ()
      case other => fail(s"expected GitFailure(Transient), got $other")

  test("createDesignBranch — derives `<prefix>/<feature>/design`"):
    val seen = scala.collection.mutable.ArrayBuffer.empty[BranchName]
    val git = FakeGitClient.builder.checkout { (b, _) =>
      seen += b
      cats.effect.IO.pure(Right(()))
    }.build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.createDesignBranch(feature, "forge", base)
    yield
      assertEquals(r, Right(BranchName("forge/stripe-webhook/design")))
      assertEquals(seen.toList, List(BranchName("forge/stripe-webhook/design")))
