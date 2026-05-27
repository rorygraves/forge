package io.forge.git.branch

import io.forge.core.{BranchName, FeatureId, PieceId}

/** PR-C C7 — coverage for [[BranchNaming]] BM7 derivations. Pure helpers; no IO. */
class BranchNamingSuite extends munit.FunSuite:

  private val feature = FeatureId("stripe-webhook")
  private val piece = PieceId("p1")

  test("designBranch — `<prefix>/<feature>/design`"):
    assertEquals(BranchNaming.designBranch("forge", feature), BranchName("forge/stripe-webhook/design"))

  test("pieceBranch — `<prefix>/<feature>/<piece>`"):
    assertEquals(BranchNaming.pieceBranch("forge", feature, piece), BranchName("forge/stripe-webhook/p1"))

  test("snapshotTag — design round 3"):
    assertEquals(BranchNaming.snapshotTag("forge", feature, "design", 3), "forge/_snapshots/stripe-webhook/design-r3")

  test("snapshotTagPrefix — the head every snapshot tag shares"):
    assertEquals(BranchNaming.snapshotTagPrefix("forge", feature), "forge/_snapshots/stripe-webhook/")

  test("parseSnapshotRound — happy path"):
    val tag = BranchNaming.snapshotTag("forge", feature, "design", 7)
    assertEquals(BranchNaming.parseSnapshotRound(tag, "forge", feature), Some(("design", 7)))

  test("parseSnapshotRound — foreign tag (wrong prefix) → None"):
    assertEquals(BranchNaming.parseSnapshotRound("origin/main", "forge", feature), None)

  test("parseSnapshotRound — wrong feature → None"):
    val tag = BranchNaming.snapshotTag("forge", FeatureId("other"), "design", 1)
    assertEquals(BranchNaming.parseSnapshotRound(tag, "forge", feature), None)

  test("parseSnapshotRound — missing -r suffix → None"):
    assertEquals(BranchNaming.parseSnapshotRound("forge/_snapshots/stripe-webhook/design", "forge", feature), None)

  test("parseSnapshotRound — non-numeric round → None"):
    assertEquals(BranchNaming.parseSnapshotRound("forge/_snapshots/stripe-webhook/design-rabc", "forge", feature), None)
