package io.forge.git.branch

import io.forge.core.BranchName
import io.forge.git.cli.GitError
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

/** PR-C C7 — `pushCurrentBranch(forceWithLease)` flag wiring and §11.3 step 5 force-lease rejection mapping. */
class BranchManagerForcePushSuite extends CatsEffectSuite:

  private val branch = BranchName("forge/feat/design")

  test("force-with-lease happy path — flag propagates, Right(())"):
    val seen = scala.collection.mutable.ArrayBuffer.empty[(BranchName, Boolean, Boolean)]
    val git = FakeGitClient.builder
      .currentBranch(branch)
      .push { (b, force, lease) =>
        seen += ((b, force, lease))
        cats.effect.IO.pure(Right(()))
      }
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.pushCurrentBranch(forceWithLease = true)
    yield
      assertEquals(r, Right(()))
      assertEquals(seen.toList, List((branch, false, true)))

  test("regular push (no flags) — both flags false"):
    val seen = scala.collection.mutable.ArrayBuffer.empty[(BranchName, Boolean, Boolean)]
    val git = FakeGitClient.builder
      .currentBranch(branch)
      .push { (b, force, lease) =>
        seen += ((b, force, lease))
        cats.effect.IO.pure(Right(()))
      }
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.pushCurrentBranch()
    yield
      assertEquals(r, Right(()))
      assertEquals(seen.toList, List((branch, false, false)))

  test("force-with-lease rejected → BranchError.ForceLeaseRejected (§11.3 step 5)"):
    val git = FakeGitClient.builder
      .currentBranch(branch)
      .push(Left(GitError.ForceLeaseRejected(branch, "! [rejected] (stale info)")))
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.pushCurrentBranch(forceWithLease = true)
    yield assertEquals(r, Left(BranchError.ForceLeaseRejected(branch)))
