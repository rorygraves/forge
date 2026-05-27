package io.forge.git.branch

import io.forge.core.{BranchName, Sha}
import io.forge.git.cli.{FastForwardResult, GitError}
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

/** PR-C C7 — `BranchManager.syncBase` mapping of [[FastForwardResult]] into typed [[BranchError]] outcomes. The
  * `RealGitClient.fastForwardBase` algorithm is exercised separately in `RealGitClientFastForwardSuite`; this suite
  * pins the BranchManager-side translation.
  */
class BranchManagerSyncBaseSuite extends CatsEffectSuite:

  private val main = BranchName("main")
  private val sha1 = Sha("abc1234")
  private val sha2 = Sha("def5678")

  test("Updated → BaseSnapshot with the remote sha"):
    val git = FakeGitClient.builder.fastForwardBase(FastForwardResult.Updated(sha1)).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.syncBase(main)
    yield assertEquals(r, Right(BaseSnapshot(main, sha1)))

  test("AlreadyUpToDate → BaseSnapshot with the same sha"):
    val git = FakeGitClient.builder.fastForwardBase(FastForwardResult.AlreadyUpToDate(sha1)).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.syncBase(main)
    yield assertEquals(r, Right(BaseSnapshot(main, sha1)))

  test("LocallyDiverged → BranchError.BaseDiverged (BM1)"):
    val git = FakeGitClient.builder.fastForwardBase(FastForwardResult.LocallyDiverged(sha1, sha2)).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.syncBase(main)
    yield assertEquals(r, Left(BranchError.BaseDiverged(sha1, sha2)))

  test("underlying GitError → BranchError.GitFailure wrapper"):
    val git = FakeGitClient.builder
      .fastForwardBase(Left(GitError.Transient(128, "fatal: not a git repository")))
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.syncBase(main)
    yield r match
      case Left(BranchError.GitFailure(GitError.Transient(128, _))) => ()
      case other => fail(s"expected GitFailure(Transient), got $other")
