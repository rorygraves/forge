package io.forge.app.orchestrator

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, PrNumber}
import io.forge.core.paths.ForgePaths
import io.forge.git.watcher.{BaselineCursor, PollBaseline}

import java.time.Instant

/** Task 1.4.10-d2c (S4-1) — round-trip + degradation behaviour for the persisted poll cursor. */
class PollBaselineStoreSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-poll-baseline-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val feat = FeatureId("feat")

  private def cursorAt(s: String, ids: String*): PollBaseline =
    PollBaseline(Some(BaselineCursor(Instant.parse(s), ids.toSet)), None, Set.empty)

  tempFixture.test("missing file → PollBaseline.empty"): root =>
    val store = new PollBaselineStore(new ForgePaths(root))
    assertEquals(store.load(feat, PrNumber(1)).unsafeRunSync(), PollBaseline.empty)

  tempFixture.test("save then load round-trips the cursor"): root =>
    val store = new PollBaselineStore(new ForgePaths(root))
    val b = cursorAt("2026-05-29T12:00:00Z", "c1", "c2")
    val out = (store.save(feat, PrNumber(7), b) *> store.load(feat, PrNumber(7))).unsafeRunSync()
    assertEquals(out, b)

  tempFixture.test("saving one PR preserves the others' cursors"): root =>
    val store = new PollBaselineStore(new ForgePaths(root))
    val b1 = cursorAt("2026-05-29T12:00:00Z", "a")
    val b2 = cursorAt("2026-05-29T13:00:00Z", "b")
    val (out1, out2) = (for
      _ <- store.save(feat, PrNumber(1), b1)
      _ <- store.save(feat, PrNumber(2), b2)
      _ <- store.save(feat, PrNumber(1), b1) // re-save 1; must not clobber 2
      o1 <- store.load(feat, PrNumber(1))
      o2 <- store.load(feat, PrNumber(2))
    yield (o1, o2)).unsafeRunSync()
    assertEquals(out1, b1)
    assertEquals(out2, b2)

  tempFixture.test("a PR with no persisted entry → PollBaseline.empty even when the file exists"): root =>
    val store = new PollBaselineStore(new ForgePaths(root))
    val out = (store.save(feat, PrNumber(1), cursorAt("2026-05-29T12:00:00Z", "a")) *>
      store.load(feat, PrNumber(99))).unsafeRunSync()
    assertEquals(out, PollBaseline.empty)

  tempFixture.test("malformed file → PollBaseline.empty (degrade, do not raise)"): root =>
    val paths = new ForgePaths(root)
    val store = new PollBaselineStore(paths)
    val file = paths.pollBaselineFile(feat)
    os.makeDir.all(file / os.up)
    os.write.over(file, "{ this is not valid json")
    assertEquals(store.load(feat, PrNumber(1)).unsafeRunSync(), PollBaseline.empty)
