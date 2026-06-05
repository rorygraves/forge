package io.forge.instance

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, InstanceName}
import io.forge.core.paths.ForgePaths

/** Task 4.0.2 — `FileInstanceStore` behaviour over a real temp `home`.
  *
  * Covers the on-disk layout (`instances/<name>/{config.json,repos.json,workers/}`), create/load/list, repo
  * registration with its three validation gates, round-trip of the persisted JSON, and every [[InstanceError]] variant
  * the store can return. Mirrors `FileSpecStoreSuite`'s FunFixture-over-tmp-dir idiom.
  */
class FileInstanceStoreSuite extends munit.FunSuite:

  private val Name: InstanceName = InstanceName("demo")

  private val tempHome = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-instance-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** A throwaway directory that looks like a git working tree (has a `.git` entry). */
  private def fakeGitRepo(parent: os.Path, name: String): os.Path =
    val repo = parent / name
    os.makeDir.all(repo / ".git")
    repo

  // --- create / load / list --------------------------------------------------

  tempHome.test("create lays down the instance directory + config + empty registry + workers/"): home =>
    val store = new FileInstanceStore(home)
    val instance = store.create(Name).unsafeRunSync().fold(e => fail(s"create failed: $e"), identity)

    assertEquals(instance.dir, ForgePaths.instanceDir(Name, home))
    assert(os.exists(instance.configFile), "config.json should exist")
    assert(os.exists(instance.reposFile), "repos.json should exist")
    assert(os.isDir(instance.workersDir), "workers/ should exist")

    val config = InstanceConfig.fromJson(os.read(instance.configFile))
    assertEquals(config, InstanceConfig(InstanceConfig.CurrentSchemaVersion, Name))
    assertEquals(RepoRegistry.fromJson(os.read(instance.reposFile)), RepoRegistry.empty)

  tempHome.test("create twice fails with DuplicateInstance"): home =>
    val store = new FileInstanceStore(home)
    assert(store.create(Name).unsafeRunSync().isRight)
    assertEquals(store.create(Name).unsafeRunSync(), Left(InstanceError.DuplicateInstance(Name)))

  tempHome.test("load resolves a created instance and rejects an unknown one"): home =>
    val store = new FileInstanceStore(home)
    val created = store.create(Name).unsafeRunSync().toOption.get
    assertEquals(store.load(Name).unsafeRunSync(), Right(created))

    val missing = InstanceName("ghost")
    assertEquals(store.load(missing).unsafeRunSync(), Left(InstanceError.NoSuchInstance(missing)))

  tempHome.test("list returns only real instance directories, ignoring stray dirs"): home =>
    val store = new FileInstanceStore(home)
    assertEquals(store.list.unsafeRunSync(), Vector.empty)

    store.create(InstanceName("alpha")).unsafeRunSync()
    store.create(InstanceName("beta")).unsafeRunSync()
    // A stray directory with no config.json must not be counted as an instance.
    os.makeDir.all(ForgePaths.instancesRoot(home) / "not-an-instance")

    assertEquals(store.list.unsafeRunSync().map(_.value).sorted, Vector("alpha", "beta"))

  // --- addRepo / listRepos ---------------------------------------------------

  tempHome.test("addRepo registers a git working tree and listRepos returns it"): home =>
    val store = new FileInstanceStore(home)
    val instance = store.create(Name).unsafeRunSync().toOption.get
    val repo = fakeGitRepo(home, "project")

    val entry = store.addRepo(instance, repo).unsafeRunSync().fold(e => fail(s"addRepo failed: $e"), identity)
    assertEquals(entry, RegisteredRepo(repo.toString))
    assertEquals(entry.asPath, repo)
    assertEquals(store.listRepos(instance).unsafeRunSync(), Right(Vector(RegisteredRepo(repo.toString))))

  tempHome.test("addRepo persists across a fresh store (round-trips through repos.json)"): home =>
    val instance = new FileInstanceStore(home).create(Name).unsafeRunSync().toOption.get
    val repo = fakeGitRepo(home, "project")
    new FileInstanceStore(home).addRepo(instance, repo).unsafeRunSync()

    // A brand-new store reading the same home sees the registered repo.
    assertEquals(
      new FileInstanceStore(home).listRepos(instance).unsafeRunSync(),
      Right(Vector(RegisteredRepo(repo.toString)))
    )

  tempHome.test("addRepo registers repos in registration order"): home =>
    val store = new FileInstanceStore(home)
    val instance = store.create(Name).unsafeRunSync().toOption.get
    val a = fakeGitRepo(home, "a")
    val b = fakeGitRepo(home, "b")
    store.addRepo(instance, a).unsafeRunSync()
    store.addRepo(instance, b).unsafeRunSync()
    assertEquals(
      store.listRepos(instance).unsafeRunSync(),
      Right(Vector(RegisteredRepo(a.toString), RegisteredRepo(b.toString)))
    )

  tempHome.test("addRepo rejects a non-existent path with RepoNotFound"): home =>
    val store = new FileInstanceStore(home)
    val instance = store.create(Name).unsafeRunSync().toOption.get
    val missing = home / "nope"
    assertEquals(store.addRepo(instance, missing).unsafeRunSync(), Left(InstanceError.RepoNotFound(missing)))

  tempHome.test("addRepo rejects a directory that is not a git working tree with NotAGitRepo"): home =>
    val store = new FileInstanceStore(home)
    val instance = store.create(Name).unsafeRunSync().toOption.get
    val plain = home / "plain"
    os.makeDir.all(plain)
    assertEquals(store.addRepo(instance, plain).unsafeRunSync(), Left(InstanceError.NotAGitRepo(plain)))

  tempHome.test("addRepo rejects a duplicate registration with DuplicateRepo"): home =>
    val store = new FileInstanceStore(home)
    val instance = store.create(Name).unsafeRunSync().toOption.get
    val repo = fakeGitRepo(home, "project")
    assert(store.addRepo(instance, repo).unsafeRunSync().isRight)
    assertEquals(store.addRepo(instance, repo).unsafeRunSync(), Left(InstanceError.DuplicateRepo(repo)))

  // --- malformed registry ----------------------------------------------------

  tempHome.test("a corrupt repos.json surfaces Malformed rather than silently resetting"): home =>
    val store = new FileInstanceStore(home)
    val instance = store.create(Name).unsafeRunSync().toOption.get
    os.write.over(instance.reposFile, "{ not json")
    store.listRepos(instance).unsafeRunSync() match
      case Left(InstanceError.Malformed(file, _)) => assertEquals(file, instance.reposFile)
      case other => fail(s"expected Malformed, got $other")

  // --- worker re-root target (B1 wiring handoff to 4.0.4) --------------------

  tempHome.test("workerDir is instances/<name>/workers/<feature> — the B1 localRoot target"): home =>
    val instance = Instance.at(Name, home)
    val feature = FeatureId("image-creds-dedup")
    assertEquals(instance.workerDir(feature), instance.dir / "workers" / "image-creds-dedup")
    // and ForgePaths re-roots the local-runtime family under it (the seam Task 4.0.4 wires).
    val paths = ForgePaths(repoRoot = home / "clone", home = home, localRootOpt = Some(instance.workerDir(feature)))
    assertEquals(paths.featureLog(feature), instance.workerDir(feature) / ".forge" / "log" / "image-creds-dedup.jsonl")
