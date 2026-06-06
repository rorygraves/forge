package io.forge.app.command

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, InstanceName}
import io.forge.instance.{FileInstanceStore, Instance}

/** Task 4.2.2 — [[WorkerProvisioner]] against a real `git` binary, in a throwaway local repo (no network).
  *
  * Asserts the O10 + B1 contract a daemon-spawned worker process sits on:
  *   - a fresh isolated **working** clone lands at `workers/<workerId>/checkout/`, with the source's committed
  *     `.forge/specs/` travelling into it (the committed family anchors at the clone);
  *   - the returned `ForgePaths` re-roots the **local-runtime** family (log / state / lock) under the worker root
  *     *outside* the clone, while `repoRoot` is the clone;
  *   - the registered source is left untouched (isolation);
  *   - a pre-existing checkout refuses (`CheckoutExists`) rather than clobbering, and a non-repo source surfaces
  *     `CloneFailed`.
  *
  * Mirrors `RealGitClientCommitSuite`: skips itself if `git` isn't on `PATH`, builds a fresh seed repo per test,
  * runtime well under the <60s default-on budget.
  */
class WorkerProvisionerSuite extends munit.FunSuite:

  private def hasGit: Boolean =
    try os.proc("git", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  override def munitTests(): Seq[munit.Test] =
    if hasGit then super.munitTests()
    else Seq.empty

  /** A throwaway temp `home` (the `~/.forge/instances` anchor) plus a seed source repo carrying a committed
    * `.forge/specs/<feat>/manifest.json`, so the committed family's travel into the clone can be asserted.
    */
  private val fixture = FunFixture[(os.Path, os.Path)](
    setup = _ =>
      val home = os.temp.dir(prefix = "forge-worker-prov-home-")
      val source = os.temp.dir(prefix = "forge-worker-prov-src-")
      os.proc("git", "-c", "init.defaultBranch=main", "init").call(cwd = source, stderr = os.Pipe)
      os.proc("git", "config", "user.email", "t@example.com").call(cwd = source, stderr = os.Pipe)
      os.proc("git", "config", "user.name", "t").call(cwd = source, stderr = os.Pipe)
      os.write(source / "README.md", "seed\n")
      os.write(source / ".forge" / "specs" / "feat" / "manifest.json", "{}\n", createFolders = true)
      os.proc("git", "add", "-A").call(cwd = source, stderr = os.Pipe)
      os.proc("git", "commit", "-m", "seed").call(cwd = source, stderr = os.Pipe)
      (home, source)
    ,
    teardown = (home, source) =>
      if os.exists(home) then os.remove.all(home)
      if os.exists(source) then os.remove.all(source)
  )

  private def createInstance(home: os.Path): Instance =
    new FileInstanceStore(home).create(InstanceName("test")).unsafeRunSync() match
      case Right(inst) => inst
      case Left(err) => fail(s"could not create instance: $err")

  fixture.test("provisions an isolated clone at workers/<id>/checkout with the committed family"): (home, source) =>
    val instance = createInstance(home)
    val paths = WorkerProvisioner.provision(instance, "w1", source, home = home).unsafeRunSync() match
      case Right(p) => p
      case Left(err) => fail(s"provision failed: ${err.message}")

    val checkout = instance.workerCheckout("w1")
    assert(os.exists(checkout / ".git"), s"checkout must be a git repo: $checkout")
    assertEquals(paths.repoRoot, checkout, "repoRoot must be the clone")
    // The source's committed .forge/specs travelled into the clone (the committed family anchors at repoRoot).
    assert(os.exists(checkout / "README.md"), "clone must contain the seed file")
    assertEquals(paths.manifest(FeatureId("feat")), checkout / ".forge" / "specs" / "feat" / "manifest.json")
    assert(os.exists(paths.manifest(FeatureId("feat"))), "committed manifest must be present in the clone")

  fixture.test("re-roots the local-runtime family under the worker root, outside the clone"): (home, source) =>
    val instance = createInstance(home)
    val paths = WorkerProvisioner.provision(instance, "w1", source, home = home).unsafeRunSync().toOption.get

    val workerRoot = instance.workerRoot("w1")
    val checkout = instance.workerCheckout("w1")
    // log / state / lock all land under workers/w1/.forge (the localRoot), NOT under the clone.
    assertEquals(paths.localRoot, workerRoot)
    assert(paths.featureLog(FeatureId("feat")).startsWith(workerRoot), "feature log must re-root under the worker root")
    assert(!paths.featureLog(FeatureId("feat")).startsWith(checkout), "feature log must NOT live in the clone")
    assert(paths.lockFile.startsWith(workerRoot), "lock must re-root under the worker root")
    // home (per-user assets) stays anchored where it was passed.
    assertEquals(paths.home, home)

  fixture.test("leaves the registered source untouched (isolation)"): (home, source) =>
    val instance = createInstance(home)
    val beforeSha = os.proc("git", "rev-parse", "HEAD").call(cwd = source).out.text().trim
    val _ = WorkerProvisioner.provision(instance, "w1", source, home = home).unsafeRunSync().toOption.get
    val afterSha = os.proc("git", "rev-parse", "HEAD").call(cwd = source).out.text().trim
    assertEquals(afterSha, beforeSha, "the registered source must not be mutated by provisioning")
    val clean = os.proc("git", "status", "--porcelain").call(cwd = source).out.text().trim
    assertEquals(clean, "", "the registered source worktree must stay clean")

  fixture.test("two workers get distinct isolated clones"): (home, source) =>
    val instance = createInstance(home)
    val p1 = WorkerProvisioner.provision(instance, "w1", source, home = home).unsafeRunSync().toOption.get
    val p2 = WorkerProvisioner.provision(instance, "w2", source, home = home).unsafeRunSync().toOption.get
    assertNotEquals(p1.repoRoot, p2.repoRoot)
    assert(os.exists(p1.repoRoot / ".git") && os.exists(p2.repoRoot / ".git"))

  fixture.test("a pre-existing checkout refuses with CheckoutExists (no clobber)"): (home, source) =>
    val instance = createInstance(home)
    val checkout = instance.workerCheckout("w1")
    os.makeDir.all(checkout)
    os.write(checkout / "sentinel", "do not clobber\n")
    val result = WorkerProvisioner.provision(instance, "w1", source, home = home).unsafeRunSync()
    assertEquals(result, Left(WorkerProvisioner.WorkerProvisionError.CheckoutExists(checkout)))
    assert(os.exists(checkout / "sentinel"), "the pre-existing checkout must be left untouched")

  fixture.test("a non-repo source surfaces CloneFailed"): (home, _) =>
    val instance = createInstance(home)
    val notARepo = os.temp.dir(prefix = "forge-worker-prov-notrepo-")
    try
      val result = WorkerProvisioner.provision(instance, "w1", notARepo, home = home).unsafeRunSync()
      assert(result.isLeft, s"expected CloneFailed, got $result")
      assert(
        result.swap.toOption.exists(_.isInstanceOf[WorkerProvisioner.WorkerProvisionError.CloneFailed]),
        s"expected CloneFailed, got $result"
      )
    finally if os.exists(notARepo) then os.remove.all(notARepo)
