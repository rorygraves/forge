package io.forge.app.command

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, InstanceName}
import io.forge.core.paths.ForgePaths
import io.forge.instance.{FileInstanceStore, Instance, InstanceError}

/** Task 4.0.4 — the B1 local-runtime re-root resolver, over a real temp `home`.
  *
  * Asserts the split that B1 promises: when `--instance` re-roots, the **committed** family (specs / config / profile)
  * stays anchored at `repoRoot/.forge` while the **local-runtime** family (log / state / lock) moves under the
  * instance's `workers/<feature>/.forge`. And asserts the v1 guarantee: no flag (or no feature) yields the base paths
  * untouched. Mirrors `InstanceCommandsSuite`'s tmp-`home` fixture.
  */
class InstancePathsSuite extends munit.FunSuite:

  private val tempHome = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-instance-paths-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val feature = FeatureId("my-feat")

  private def basePaths(home: os.Path): ForgePaths =
    new ForgePaths(repoRoot = home / "checkout", home = home)

  private def createInstance(home: os.Path, name: InstanceName): Instance =
    new FileInstanceStore(home).create(name).unsafeRunSync() match
      case Right(inst) => inst
      case Left(err) => fail(s"could not create instance: $err")

  // --- v1 (no re-root) -------------------------------------------------------

  tempHome.test("no --instance ⇒ v1: base paths returned untouched, registry never consulted"): home =>
    val base = basePaths(home)
    val resolved = InstancePaths.resolve(base, instance = None, feature = Some(feature)).unsafeRunSync()
    assertEquals(resolved, Right(InstancePaths.Resolved(base, None)))
    // The instance registry root must not have been created as a side effect of a v1 resolve.
    assert(!os.exists(ForgePaths.instancesRoot(home)), "v1 resolve must not touch ~/.forge/instances")

  tempHome.test("--instance but no feature ⇒ v1 (nothing per-feature to re-root)"): home =>
    val base = basePaths(home)
    createInstance(home, InstanceName("demo"))
    val resolved = InstancePaths.resolve(base, instance = Some(InstanceName("demo")), feature = None).unsafeRunSync()
    assertEquals(resolved, Right(InstancePaths.Resolved(base, None)))

  // --- explicit --instance re-root -------------------------------------------

  tempHome.test("explicit --instance + feature ⇒ re-root local family under workers/<feature>, committed stays put"):
    home =>
      val base = basePaths(home)
      val inst = createInstance(home, InstanceName("demo"))

      val Right(InstancePaths.Resolved(p, Some(reportedInst))) =
        InstancePaths.resolve(base, Some(InstanceName("demo")), Some(feature)).unsafeRunSync(): @unchecked

      assertEquals(reportedInst, inst)

      // Local-runtime family re-roots under the instance's per-feature worker dir.
      val workerForge = inst.workerDir(feature) / ".forge"
      assertEquals(p.localForgeDir, workerForge)
      assertEquals(p.featureLog(feature), workerForge / "log" / "my-feat.jsonl")
      assertEquals(p.stateFile(feature), workerForge / "state" / "my-feat.json")
      assertEquals(p.pollBaselineFile(feature), workerForge / "state" / "my-feat.poll-baselines.json")
      assertEquals(p.lockFile, workerForge / "state" / ".lock")
      assertEquals(p.lockMetadataFile, workerForge / "state" / ".lock.json")

      // Committed family is anchored at the repo checkout, exactly as in v1 — it merges back via PR.
      assertEquals(p.repoRoot, base.repoRoot)
      assertEquals(p.repoForgeDir, base.repoRoot / ".forge")
      assertEquals(p.manifest(feature), base.repoRoot / ".forge" / "specs" / "my-feat" / "manifest.json")
      assertEquals(p.configFile, base.repoRoot / ".forge" / "config.json")
      assertEquals(p.profileFile, base.repoRoot / ".forge" / "profile.json")
      // Per-user assets stay anchored at home.
      assertEquals(p.home, home)
      assertEquals(p.userForgeDir, home / ".forge")

  tempHome.test("explicit --instance naming a missing instance ⇒ NoSuchInstance"): home =>
    val base = basePaths(home)
    val resolved = InstancePaths.resolve(base, Some(InstanceName("ghost")), Some(feature)).unsafeRunSync()
    assertEquals(resolved, Left(InstanceError.NoSuchInstance(InstanceName("ghost"))))
