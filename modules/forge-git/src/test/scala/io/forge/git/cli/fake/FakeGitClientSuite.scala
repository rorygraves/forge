package io.forge.git.cli.fake

import cats.effect.IO
import io.forge.core.{BranchName, Sha}
import io.forge.git.cli.{FastForwardResult, GitError}
import munit.CatsEffectSuite

/** PR-A A4 — smoke for `FakeGitClient.builder`. Mirrors the [[FakeGhClientSuite]] shape. */
class FakeGitClientSuite extends CatsEffectSuite:

  private val sha = Sha("abc1234")

  test("unconfigured currentBranch → Transient error naming the method"):
    val git = FakeGitClient.builder.build
    git.currentBranch.map {
      case Left(GitError.Transient(_, msg)) => assert(msg.contains("currentBranch"))
      case other => fail(s"expected unconfigured error, got $other")
    }

  test("currentBranch canned → returns that branch"):
    val git = FakeGitClient.builder.currentBranch(BranchName("main")).build
    git.currentBranch.map(r => assertEquals(r, Right(BranchName("main"))))

  test("isWorktreeClean canned"):
    val git = FakeGitClient.builder.isWorktreeClean(true).build
    git.isWorktreeClean.map(r => assertEquals(r, Right(true)))

  test("fastForwardBase — AlreadyUpToDate"):
    val git = FakeGitClient.builder.fastForwardBase(FastForwardResult.AlreadyUpToDate(sha)).build
    git.fastForwardBase(BranchName("main")).map(r => assertEquals(r, Right(FastForwardResult.AlreadyUpToDate(sha))))

  test("fastForwardBase — LocallyDiverged (BM1 trigger)"):
    val local = Sha("abc1234")
    val remote = Sha("def5678")
    val git = FakeGitClient.builder
      .fastForwardBase(FastForwardResult.LocallyDiverged(local, remote))
      .build
    git.fastForwardBase(BranchName("main")).map {
      case Right(FastForwardResult.LocallyDiverged(l, r)) =>
        assertEquals(l, local); assertEquals(r, remote)
      case other => fail(s"expected LocallyDiverged, got $other")
    }

  test("push — ForceLeaseRejected canned (§11.3 step 5)"):
    val git = FakeGitClient.builder
      .push(Left(GitError.ForceLeaseRejected(BranchName("forge/feat/design"), "stale info")))
      .build
    git.push(BranchName("forge/feat/design"), forceWithLease = true).map {
      case Left(_: GitError.ForceLeaseRejected) => ()
      case other => fail(s"expected ForceLeaseRejected, got $other")
    }

  test("checkout — captures arguments via function variant"):
    val seen = scala.collection.mutable.ArrayBuffer.empty[(BranchName, Option[String])]
    val git = FakeGitClient.builder.checkout { (branch, startPoint) =>
      seen += ((branch, startPoint))
      IO.pure(Right(()))
    }.build
    git
      .checkout(BranchName("forge/feat/p1"), Some("abc1234"))
      .map { r =>
        assertEquals(r, Right(()))
        assertEquals(seen.toList, List((BranchName("forge/feat/p1"), Some("abc1234"))))
      }
