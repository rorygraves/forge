package io.forge.git.branch

import io.forge.core.{FeatureId, Sha}
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

/** PR-C C7 — snapshot tag local+remote operations + retention prune per v1.2 §11.3 step 4. */
class BranchManagerSnapshotTagSuite extends CatsEffectSuite:

  private val feature = FeatureId("stripe-webhook")
  private val sha = Sha("abc1234")

  test("tagSnapshot — delegates to git.tag"):
    val seen = scala.collection.mutable.ArrayBuffer.empty[(String, Sha)]
    val git = FakeGitClient.builder.tag { (n, s) =>
      seen += ((n, s))
      cats.effect.IO.pure(Right(()))
    }.build
    val name = BranchNaming.snapshotTag("forge", feature, "design", 1)
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.tagSnapshot(name, sha)
    yield
      assertEquals(r, Right(()))
      assertEquals(seen.toList, List((name, sha)))

  test("pushTag / deleteRemoteTag — thin wrappers"):
    val pushed = scala.collection.mutable.ArrayBuffer.empty[String]
    val deleted = scala.collection.mutable.ArrayBuffer.empty[String]
    val git = FakeGitClient.builder
      .pushTag { n =>
        pushed += n
        cats.effect.IO.pure(Right(()))
      }
      .deleteRemoteTag { n =>
        deleted += n
        cats.effect.IO.pure(Right(()))
      }
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      a <- bm.pushTag("forge/_snapshots/x/design-r1")
      b <- bm.deleteRemoteTag("forge/_snapshots/x/design-r1")
    yield
      assertEquals(a, Right(()))
      assertEquals(b, Right(()))
      assertEquals(pushed.toList, List("forge/_snapshots/x/design-r1"))
      assertEquals(deleted.toList, List("forge/_snapshots/x/design-r1"))

  test("pruneSnapshotTags — local-only, keep last 3"):
    val tags = (1 to 5).map(n => BranchNaming.snapshotTag("forge", feature, "design", n)).toVector
    val deletedLocal = scala.collection.mutable.ArrayBuffer.empty[String]
    val deletedRemote = scala.collection.mutable.ArrayBuffer.empty[String]
    val git = FakeGitClient.builder
      .listTags(tags)
      .deleteLocalTag { n =>
        deletedLocal += n
        cats.effect.IO.pure(Right(()))
      }
      .deleteRemoteTag { n =>
        deletedRemote += n
        cats.effect.IO.pure(Right(()))
      }
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.pruneSnapshotTags(feature, "forge", retention = 3, alsoRemote = false)
    yield
      // Keep r3, r4, r5 (highest 3 by round); drop r1 and r2.
      assertEquals(r, Right(Vector(tags(0), tags(1))))
      assertEquals(deletedLocal.toList, List(tags(0), tags(1)))
      assertEquals(deletedRemote.toList, Nil)

  test("pruneSnapshotTags — alsoRemote=true also deletes remote tags"):
    val tags = (1 to 4).map(n => BranchNaming.snapshotTag("forge", feature, "design", n)).toVector
    val deletedLocal = scala.collection.mutable.ArrayBuffer.empty[String]
    val deletedRemote = scala.collection.mutable.ArrayBuffer.empty[String]
    val git = FakeGitClient.builder
      .listTags(tags)
      .deleteLocalTag { n =>
        deletedLocal += n
        cats.effect.IO.pure(Right(()))
      }
      .deleteRemoteTag { n =>
        deletedRemote += n
        cats.effect.IO.pure(Right(()))
      }
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.pruneSnapshotTags(feature, "forge", retention = 3, alsoRemote = true)
    yield
      assertEquals(r, Right(Vector(tags(0))))
      assertEquals(deletedLocal.toList, List(tags(0)))
      assertEquals(deletedRemote.toList, List(tags(0)))

  test("pruneSnapshotTags — foreign tags in listing are ignored"):
    val mine = (1 to 2).map(n => BranchNaming.snapshotTag("forge", feature, "design", n)).toVector
    val foreign = Vector("v1.0.0", "release/2026.5", "forge/_snapshots/other-feature/design-r1")
    val git = FakeGitClient.builder
      .listTags(Right(mine ++ foreign))
      .deleteLocalTag(Right(()))
      .build
    for
      cache <- BranchManagerFixture.freshCache
      bm = BranchManagerFixture.manager(git, FakeGhClient.builder.build, cache)
      r <- bm.pruneSnapshotTags(feature, "forge", retention = 1, alsoRemote = false)
    yield
    // r1 and r2 both belong to this feature; retain r2 (highest), prune r1.
    assertEquals(r, Right(Vector(mine(0))))
