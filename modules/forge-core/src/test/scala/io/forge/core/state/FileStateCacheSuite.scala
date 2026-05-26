package io.forge.core.state

import cats.effect.unsafe.implicits.global
import io.forge.core.fsm.{Feature, FsmFixtures, FsmState}
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import upickle.default.read

/** PR-E E5 — FileStateCache behaviour: read/write round-trip, atomic write (no leftover temp file), verifyAgainstLog
  * returning `Consistent` when the cache matches and `Rewritten` when it diverges.
  *
  * Uses real temp directories under `os.temp.dir()` — no mocks. The FSM-side rebuild path uses a real `FileActionLog`
  * and `FileManifestStore` so the end-to-end pipeline runs.
  */
class FileStateCacheSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-state-cache-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def freshFeature(state: FsmState = FsmState.Drafting): Feature =
    FsmFixtures.featureIn(state)

  // --- E5: load/save round-trip ---

  tempFixture.test("save then load returns the same Feature value"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val original = freshFeature(FsmState.InteractiveSpec).copy(designSessionId = Some("sess-d1"))
    val roundtripped = (cache.save(FeatureA, original) *> cache.load(FeatureA)).unsafeRunSync()
    assertEquals(roundtripped, Some(original))

  tempFixture.test("load on a missing cache returns None"): root =>
    val cache = new FileStateCache(new ForgePaths(repoRoot = root))
    assertEquals(cache.load(FeatureA).unsafeRunSync(), None)

  tempFixture.test("save overwrites an existing cache"): root =>
    val cache = new FileStateCache(new ForgePaths(repoRoot = root))
    val first = freshFeature(FsmState.Drafting)
    val second = freshFeature(FsmState.DesignReviewing(1))
    cache.save(FeatureA, first).unsafeRunSync()
    cache.save(FeatureA, second).unsafeRunSync()
    assertEquals(cache.load(FeatureA).unsafeRunSync(), Some(second))

  // --- E5: atomic write (no leftover temp file after save completes) ---

  tempFixture.test("save leaves no temp file alongside the target"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    cache.save(FeatureA, freshFeature()).unsafeRunSync()
    val target = paths.stateFile(FeatureA)
    val parent = target / os.up
    val siblings = os.list(parent).filter(_ != target)
    assert(
      siblings.isEmpty,
      s"expected no sibling files after atomic save, got: ${siblings.map(_.last).mkString(", ")}"
    )

  tempFixture.test("save's on-disk file is a valid uPickle round-trip of the Feature"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val original = freshFeature(FsmState.DesignReady).copy(designSessionId = None)
    cache.save(FeatureA, original).unsafeRunSync()
    val onDiskBytes = Files.readAllBytes(paths.stateFile(FeatureA).toNIO)
    val onDiskText = new String(onDiskBytes, StandardCharsets.UTF_8)
    assertEquals(read[Feature](onDiskText), original)

  // --- E5: verifyAgainstLog — Consistent path (clean replay) ---

  tempFixture.test("verifyAgainstLog returns Consistent when cache matches the rebuild"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val manifestStore = new FileManifestStore(paths)
    val log = FileActionLog(paths).unsafeRunSync()
    // Seed manifest.json on disk so manifestStore.load succeeds. We use a single-piece pending manifest.
    val manifest = FsmFixtures.manifest(Vector(piecePending(P1, 1)))
    seedManifest(paths, manifest)
    // Cache the seed Feature (no log entries yet — Feature.initial is the same as the rebuild's result on an empty log).
    val seed = Feature.initial(FeatureA, manifest)
    cache.save(FeatureA, seed).unsafeRunSync()
    val result = cache.verifyAgainstLog(FeatureA, manifestStore, log).unsafeRunSync()
    result match
      case Right(VerifyResult.Consistent(f)) => assertEquals(f, seed)
      case other => fail(s"expected Consistent, got $other")
    // No harness.cache_invalidated entry on the log — verifyAgainstLog only writes on divergence.
    assert(log.replay(FeatureA).unsafeRunSync().isEmpty, "no log entries expected on a clean Consistent path")

  // --- E5: verifyAgainstLog — Rewritten path (stale cache) ---

  tempFixture.test("verifyAgainstLog returns Rewritten and logs harness.cache_invalidated when cache diverges"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val manifestStore = new FileManifestStore(paths)
    val log = FileActionLog(paths).unsafeRunSync()
    val manifest = FsmFixtures.manifest(Vector(piecePending(P1, 1)))
    seedManifest(paths, manifest)
    // Cache a Feature with stale state — log is empty so the rebuild yields the Drafting seed; cache has DesignReady.
    val staleFeature = Feature.initial(FeatureA, manifest).copy(state = FsmState.DesignReady)
    cache.save(FeatureA, staleFeature).unsafeRunSync()

    val result = cache.verifyAgainstLog(FeatureA, manifestStore, log).unsafeRunSync()
    result match
      case Right(VerifyResult.Rewritten(rebuilt)) =>
        assertEquals(rebuilt.state, FsmState.Drafting: FsmState)
      case other => fail(s"expected Rewritten, got $other")

    // Cache file should now reflect the rebuild (state = Drafting), not the stale value.
    val reloaded = cache.load(FeatureA).unsafeRunSync()
    assertEquals(reloaded.map(_.state), Some(FsmState.Drafting: FsmState))

    // A harness.cache_invalidated entry should have been appended to the log.
    val replayed = log.replay(FeatureA).unsafeRunSync()
    assertEquals(replayed.size, 1)
    assertEquals(replayed.head.kind, "harness.cache_invalidated")
    assertEquals(replayed.head.payload("reason").str, "cache_diverged")

  tempFixture.test("verifyAgainstLog returns Rewritten with reason=cache_missing when no cache file exists"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val manifestStore = new FileManifestStore(paths)
    val log = FileActionLog(paths).unsafeRunSync()
    val manifest = FsmFixtures.manifest(Vector(piecePending(P1, 1)))
    seedManifest(paths, manifest)
    // No cache.save call — verifyAgainstLog runs against a missing cache.
    val result = cache.verifyAgainstLog(FeatureA, manifestStore, log).unsafeRunSync()
    result match
      case Right(VerifyResult.Rewritten(rebuilt)) =>
        assertEquals(rebuilt, Feature.initial(FeatureA, manifest))
      case other => fail(s"expected Rewritten, got $other")
    val replayed = log.replay(FeatureA).unsafeRunSync()
    assertEquals(replayed.size, 1)
    assertEquals(replayed.head.payload("reason").str, "cache_missing")

  // --- E5: verifyAgainstLog surfaces RebuildError on the Either left ---

  tempFixture.test("verifyAgainstLog returns Left(ManifestLoadFailed) when manifest.json is missing"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val manifestStore = new FileManifestStore(paths)
    val log = FileActionLog(paths).unsafeRunSync()
    // No manifest seeded — manifestStore.load should fail.
    val result = cache.verifyAgainstLog(FeatureA, manifestStore, log).unsafeRunSync()
    result match
      case Left(RebuildError.ManifestLoadFailed(id, _)) =>
        assertEquals(id, FeatureA)
      case other => fail(s"expected ManifestLoadFailed, got $other")

  // --- helpers ---

  private def seedManifest(paths: ForgePaths, manifest: io.forge.core.manifest.Manifest): Unit =
    val path = paths.manifest(FeatureA)
    os.makeDir.all(path / os.up)
    os.write(path, io.forge.core.manifest.Manifest.toJson(manifest))
