package io.forge.instance

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, InstanceName}

import java.nio.charset.StandardCharsets
import upickle.default.{read, write}

/** Task 4.1.2 — `FileInstanceStateCache` behaviour: atomic save/load round-trip (no leftover temp), tolerant load
  * (missing / malformed / stale-schema → None), and `verifyAgainstLog` rebuilding from the canonical log. Mirrors
  * `FileStateCacheSuite` over real temp dirs.
  */
class FileInstanceStateCacheSuite extends munit.FunSuite:

  private val Name: InstanceName = InstanceName("demo")
  private val feature = FeatureId("image-creds-dedup")

  private val tempHome = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-instance-cache-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def instance(home: os.Path): Instance = Instance.at(Name, home)

  private def sampleState: InstanceState =
    RebuildInstanceState.empty.copy(
      bootCount = 2,
      workers = Vector(WorkerRecord("w1", "/repos/szork", feature, "Refining", Vector(ujson.Obj("k" -> "v"))))
    )

  // --- load/save round-trip --------------------------------------------------

  tempHome.test("save then load returns the same state"): home =>
    val cache = new FileInstanceStateCache(instance(home))
    val out = (cache.save(sampleState) *> cache.load).unsafeRunSync()
    assertEquals(out, Some(sampleState))

  tempHome.test("load on a missing cache returns None"): home =>
    assertEquals(new FileInstanceStateCache(instance(home)).load.unsafeRunSync(), None)

  tempHome.test("save overwrites an existing cache"): home =>
    val cache = new FileInstanceStateCache(instance(home))
    cache.save(RebuildInstanceState.empty).unsafeRunSync()
    cache.save(sampleState).unsafeRunSync()
    assertEquals(cache.load.unsafeRunSync(), Some(sampleState))

  tempHome.test("save leaves no temp file alongside the target"): home =>
    val cache = new FileInstanceStateCache(instance(home))
    cache.save(sampleState).unsafeRunSync()
    val target = instance(home).instanceStateFile
    val siblings = os.list(target / os.up).filter(_ != target)
    assert(siblings.isEmpty, s"expected no sibling files, got: ${siblings.map(_.last).mkString(", ")}")

  tempHome.test("on-disk file is a valid uPickle round-trip of the state"): home =>
    val cache = new FileInstanceStateCache(instance(home))
    cache.save(sampleState).unsafeRunSync()
    val text = new String(os.read.bytes(instance(home).instanceStateFile), StandardCharsets.UTF_8)
    assertEquals(read[InstanceState](text), sampleState)

  tempHome.test("a stale-schema cache loads as None (rebuildable, never authoritative)"): home =>
    val inst = instance(home)
    val file = inst.instanceStateFile
    os.makeDir.all(file / os.up)
    os.write.over(file, write(sampleState.copy(schemaVersion = 999), indent = 2))
    assertEquals(new FileInstanceStateCache(inst).load.unsafeRunSync(), None)

  tempHome.test("a malformed cache loads as None"): home =>
    val inst = instance(home)
    val file = inst.instanceStateFile
    os.makeDir.all(file / os.up)
    os.write.over(file, "{ this is not json")
    assertEquals(new FileInstanceStateCache(inst).load.unsafeRunSync(), None)

  // --- verifyAgainstLog ------------------------------------------------------

  tempHome.test("verifyAgainstLog returns Consistent when the cache matches the rebuild"): home =>
    val inst = instance(home)
    val cache = new FileInstanceStateCache(inst)
    val log = FileInstanceLog(inst).unsafeRunSync()
    log
      .appendAll(Vector(InstanceEvent.DaemonStarted(1), InstanceEvent.WorkerRegistered("w1", "/r", feature)))
      .unsafeRunSync()
    val rebuilt = log.replay.map(RebuildInstanceState.fold).unsafeRunSync()
    cache.save(rebuilt).unsafeRunSync()

    cache.verifyAgainstLog(log).unsafeRunSync() match
      case InstanceVerifyResult.Consistent(s) => assertEquals(s, rebuilt)
      case other => fail(s"expected Consistent, got $other")

  tempHome.test("verifyAgainstLog rewrites a missing/diverged cache from the log"): home =>
    val inst = instance(home)
    val cache = new FileInstanceStateCache(inst)
    val log = FileInstanceLog(inst).unsafeRunSync()
    log
      .appendAll(
        Vector(
          InstanceEvent.DaemonStarted(1),
          InstanceEvent.WorkerRegistered("w1", "/r", feature),
          InstanceEvent.WorkerStatus("w1", "Refining")
        )
      )
      .unsafeRunSync()

    // No cache on disk yet → Rewritten, and the returned state matches a fresh rebuild.
    val expected = log.replay.map(RebuildInstanceState.fold).unsafeRunSync()
    cache.verifyAgainstLog(log).unsafeRunSync() match
      case InstanceVerifyResult.Rewritten(s) => assertEquals(s, expected)
      case other => fail(s"expected Rewritten, got $other")
    assertEquals(cache.load.unsafeRunSync(), Some(expected))

  tempHome.test("rebuild-from-log alone reconstructs the view (the §6.4 canonical-log invariant)"): home =>
    val inst = instance(home)
    val log = FileInstanceLog(inst).unsafeRunSync()
    log
      .appendAll(
        Vector(
          InstanceEvent.DaemonStarted(1),
          InstanceEvent.WorkerRegistered("w1", "/repos/szork", feature),
          InstanceEvent.WorkerStatus("w1", "PieceImplementing"),
          InstanceEvent.WorkerEvent("w1", ujson.Obj("seq" -> 0, "kind" -> "fsm.transition"))
        )
      )
      .unsafeRunSync()

    // A second log handle over the same files (simulating a daemon restart) rebuilds the identical state.
    val freshLog = FileInstanceLog(inst).unsafeRunSync()
    val rebuilt = freshLog.replay.map(RebuildInstanceState.fold).unsafeRunSync()
    assertEquals(rebuilt.bootCount, 1)
    val w = rebuilt.worker("w1").getOrElse(fail("w1 should exist"))
    assertEquals(w.status, "PieceImplementing")
    assertEquals(w.events.size, 1)
