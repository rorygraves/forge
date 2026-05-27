package io.forge.git.branch

import io.forge.core.{BranchName, FeatureId, PieceId}
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

/** PR-C C7 — preflight rows per v1.2 §15 and the BM6 piece-branch-match check. Each test fixes one command + one
  * worktree/branch state and asserts the resulting [[PreflightReport]].
  */
class BranchManagerPreflightSuite extends CatsEffectSuite:

  private val feature = FeatureId("stripe-webhook")
  private val piece = PieceId("p1")

  test("forge new — clean worktree passes"):
    val git = FakeGitClient.builder.isWorktreeClean(true).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.New(feature), manifest = None)
    yield
      assert(report.allPassed, s"expected pass, got $report")
      assertEquals(report.checks.size, 1)
      assertEquals(report.checks.head.id, "worktree.clean")

  test("forge new — dirty worktree fails, escapable via --force"):
    val git = FakeGitClient.builder.isWorktreeClean(false).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.New(feature), manifest = None)
    yield
      assert(!report.allPassed)
      assertEquals(report.hardFailures, Vector.empty)
      val f = report.failures.head
      assertEquals(f.id, "worktree.clean")
      assertEquals(f.escapableViaForce, true)
      assert(f.reason.contains("clean worktree"))

  test("forge spec — same clean-worktree rule with command-name interpolation"):
    val git = FakeGitClient.builder.isWorktreeClean(false).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Spec(feature), manifest = None)
    yield assert(report.failures.head.reason.contains("forge spec"))

  test("forge run — clean required"):
    val git = FakeGitClient.builder.isWorktreeClean(true).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Run(feature), manifest = None)
    yield assert(report.allPassed)

  test("forge resume --after-human-push — clean required"):
    val git = FakeGitClient.builder.isWorktreeClean(false).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeAfterHumanPush(feature, piece), manifest = None)
    yield assert(!report.allPassed)

  test("forge resume --run-fixup — clean required"):
    val git = FakeGitClient.builder.isWorktreeClean(true).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeRunFixup(feature, piece), manifest = None)
    yield assert(report.allPassed)

  test("forge resume --commit-human-fix — on the matching piece branch passes (BM6)"):
    val manifest = BranchManagerFixture.sampleManifest(feature)
    val expected = BranchNaming.pieceBranch(manifest.branchPrefix, feature, piece)
    val git = FakeGitClient.builder.currentBranch(expected).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeCommitHumanFix(feature, piece), Some(manifest))
    yield
      assert(report.allPassed, s"expected pass, got $report")
      assertEquals(report.checks.head.id, "branch.matches-piece")

  test("forge resume --commit-human-fix — branch mismatch fails, NOT escapable (BM6)"):
    val manifest = BranchManagerFixture.sampleManifest(feature)
    val git = FakeGitClient.builder.currentBranch(BranchName("some/other")).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeCommitHumanFix(feature, piece), Some(manifest))
    yield
      val f = report.failures.head
      assertEquals(f.id, "branch.matches-piece")
      assertEquals(f.escapableViaForce, false)
      assert(f.reason.contains("Switch branches"), s"reason was: ${f.reason}")

  test("forge resume --commit-human-fix — missing manifest fails hard"):
    val git = FakeGitClient.builder.build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeCommitHumanFix(feature, piece), None)
    yield
      val f = report.failures.head
      assertEquals(f.id, "manifest.present")
      assertEquals(f.escapableViaForce, false)

  test("forge reconcile — no checks (no preflight requirements per §15)"):
    val git = FakeGitClient.builder.build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Reconcile(feature), None)
    yield
      assertEquals(report.checks, Vector.empty)
      assert(report.allPassed)

  test("forge refresh-cache — no checks"):
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.RefreshCache(feature), None)
    yield assert(report.allPassed)

  test("forge status (read-only) — no checks"):
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ReadOnly(ForgeCommand.ReadOnlyKind.Status), None)
    yield assert(report.allPassed)

  test("forge unlock --force — no checks (lock-specific)"):
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.UnlockForce, None)
    yield assert(report.allPassed)

  test("forge abandon — no checks"):
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Abandon(feature), None)
    yield assert(report.allPassed)
