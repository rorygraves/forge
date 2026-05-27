package io.forge.git.branch

import io.forge.core.{BranchName, FeatureId, PieceId, PrNumber, Sha}
import io.forge.core.manifest.{Piece, PieceStatus}
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

/** PR-C C7 — preflight rows per v1.2 §15. Each test fixes one command + one worktree/branch state and asserts the
  * resulting [[PreflightReport]]. The §15 rows that require richer than "clean worktree" are exercised here too: spec /
  * resume `--after-human-push` / resume `--run-fixup` need branch / PR-head checks beyond the worktree state.
  */
class BranchManagerPreflightSuite extends CatsEffectSuite:

  private val feature = FeatureId("stripe-webhook")
  private val piece = PieceId("p1")
  private val designBranch = BranchName(s"forge/${feature.value}/design")
  private val pieceBranch = BranchName(s"forge/${feature.value}/${piece.value}")
  private val localHead = Sha("aaaa111")
  private val otherSha = Sha("bbbb222")

  private def pieceWith(pr: Option[PrNumber], status: PieceStatus = PieceStatus.InProgress): Piece =
    Piece(
      id = piece,
      order = 1,
      title = "piece one",
      summary = "do the thing",
      specPath = ".forge/specs/sample/pieces/p1.md",
      acceptanceHash = "sha256:abc",
      status = status,
      baseSha = Some(Sha("c0ffee0")),
      prNumber = pr,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )

  private def headRefOidJson(sha: Sha): ujson.Value =
    ujson.Obj("headRefOid" -> ujson.Str(sha.value))

  // -------- forge new --------

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

  // -------- forge spec — §15: clean + on the design branch --------

  test("forge spec — clean + on design branch passes"):
    val manifest = BranchManagerFixture.sampleManifest(feature)
    val git = FakeGitClient.builder.isWorktreeClean(true).currentBranch(designBranch).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Spec(feature), Some(manifest))
    yield
      assert(report.allPassed, s"expected pass, got $report")
      assertEquals(report.checks.map(_.id), Vector("worktree.clean", "branch.matches-design"))

  test("forge spec — dirty worktree fails (escapable), branch still checked"):
    val manifest = BranchManagerFixture.sampleManifest(feature)
    val git = FakeGitClient.builder.isWorktreeClean(false).currentBranch(designBranch).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Spec(feature), Some(manifest))
    yield
      val clean = report.checks.collectFirst { case f: PreflightCheck.Failed if f.id == "worktree.clean" => f }
      assert(clean.exists(_.escapableViaForce), s"expected escapable clean failure, got $report")
      assert(report.checks.exists(_.id == "branch.matches-design"))

  test("forge spec — on the wrong branch fails NOT escapable"):
    val manifest = BranchManagerFixture.sampleManifest(feature)
    val git = FakeGitClient.builder.isWorktreeClean(true).currentBranch(BranchName("main")).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Spec(feature), Some(manifest))
    yield
      val branchFail = report.failures.find(_.id == "branch.matches-design")
      assert(branchFail.isDefined, s"expected branch.matches-design failure, got $report")
      assertEquals(branchFail.get.escapableViaForce, false)
      assert(branchFail.get.reason.contains("design branch is"))

  test("forge spec — missing manifest fails hard"):
    val git = FakeGitClient.builder.build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Spec(feature), None)
    yield
      val f = report.failures.head
      assertEquals(f.id, "manifest.present")
      assertEquals(f.escapableViaForce, false)

  // -------- forge run --------

  test("forge run — clean required"):
    val git = FakeGitClient.builder.isWorktreeClean(true).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.Run(feature), manifest = None)
    yield assert(report.allPassed)

  // -------- forge resume --after-human-push — §15: clean + piece branch + PR head == local HEAD --------

  test("forge resume --after-human-push — all checks pass when clean, on piece branch, PR head == local HEAD"):
    val manifest = BranchManagerFixture
      .sampleManifest(feature)
      .copy(pieces = Vector(pieceWith(pr = Some(PrNumber(42)))))
    val git = FakeGitClient.builder
      .isWorktreeClean(true)
      .currentBranch(pieceBranch)
      .currentSha(localHead)
      .build
    val gh = FakeGhClient.builder.prView(headRefOidJson(localHead)).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, gh, cache)
      report <- bm.preflight(ForgeCommand.ResumeAfterHumanPush(feature, piece), Some(manifest))
    yield
      assert(report.allPassed, s"expected all-pass, got $report")
      assertEquals(
        report.checks.map(_.id),
        Vector("worktree.clean", "branch.matches-piece", "pr.head-matches-local")
      )

  test("forge resume --after-human-push — wrong branch fails NOT escapable"):
    val manifest = BranchManagerFixture
      .sampleManifest(feature)
      .copy(pieces = Vector(pieceWith(pr = Some(PrNumber(42)))))
    val git = FakeGitClient.builder
      .isWorktreeClean(true)
      .currentBranch(BranchName("main"))
      .currentSha(localHead)
      .build
    val gh = FakeGhClient.builder.prView(headRefOidJson(localHead)).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, gh, cache)
      report <- bm.preflight(ForgeCommand.ResumeAfterHumanPush(feature, piece), Some(manifest))
    yield
      val branchFail = report.failures.find(_.id == "branch.matches-piece")
      assert(branchFail.exists(!_.escapableViaForce), s"expected hard branch failure, got $report")

  test("forge resume --after-human-push — PR head != local HEAD fails NOT escapable"):
    val manifest = BranchManagerFixture
      .sampleManifest(feature)
      .copy(pieces = Vector(pieceWith(pr = Some(PrNumber(42)))))
    val git = FakeGitClient.builder
      .isWorktreeClean(true)
      .currentBranch(pieceBranch)
      .currentSha(otherSha)
      .build
    val gh = FakeGhClient.builder.prView(headRefOidJson(localHead)).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, gh, cache)
      report <- bm.preflight(ForgeCommand.ResumeAfterHumanPush(feature, piece), Some(manifest))
    yield
      val headFail = report.failures.find(_.id == "pr.head-matches-local")
      assert(headFail.exists(!_.escapableViaForce), s"expected hard head-mismatch failure, got $report")
      assert(headFail.get.reason.contains("Push the local commits"))

  test("forge resume --after-human-push — piece without prNumber recorded fails hard"):
    val manifest = BranchManagerFixture
      .sampleManifest(feature)
      .copy(pieces = Vector(pieceWith(pr = None, status = PieceStatus.Pending).copy(baseSha = None)))
    val git = FakeGitClient.builder
      .isWorktreeClean(true)
      .currentBranch(pieceBranch)
      .currentSha(localHead)
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeAfterHumanPush(feature, piece), Some(manifest))
    yield
      val f = report.failures.find(_.id == "pr.recorded")
      assert(f.exists(!_.escapableViaForce), s"expected hard pr.recorded failure, got $report")

  test("forge resume --after-human-push — missing manifest fails hard"):
    val git = FakeGitClient.builder.build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeAfterHumanPush(feature, piece), None)
    yield assertEquals(report.failures.head.id, "manifest.present")

  // -------- forge resume --run-fixup — §15: clean + piece branch --------

  test("forge resume --run-fixup — clean + on piece branch passes"):
    val manifest = BranchManagerFixture.sampleManifest(feature)
    val git = FakeGitClient.builder.isWorktreeClean(true).currentBranch(pieceBranch).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeRunFixup(feature, piece), Some(manifest))
    yield
      assert(report.allPassed, s"expected pass, got $report")
      assertEquals(report.checks.map(_.id), Vector("worktree.clean", "branch.matches-piece"))

  test("forge resume --run-fixup — wrong branch fails NOT escapable"):
    val manifest = BranchManagerFixture.sampleManifest(feature)
    val git = FakeGitClient.builder.isWorktreeClean(true).currentBranch(BranchName("main")).build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeRunFixup(feature, piece), Some(manifest))
    yield
      val branchFail = report.failures.find(_.id == "branch.matches-piece")
      assert(branchFail.exists(!_.escapableViaForce), s"expected hard branch failure, got $report")

  test("forge resume --run-fixup — missing manifest fails hard"):
    val git = FakeGitClient.builder.build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      report <- bm.preflight(ForgeCommand.ResumeRunFixup(feature, piece), None)
    yield assertEquals(report.failures.head.id, "manifest.present")

  // -------- forge resume --commit-human-fix — BM6 (unchanged) --------

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

  // -------- read-only / lock-only / no-check rows --------

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
