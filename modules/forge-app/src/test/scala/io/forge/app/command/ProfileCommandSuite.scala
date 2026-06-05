package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import io.forge.app.reviewer.{FakeReviewerConnector, RealReviewerCall, ReviewerCall}
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.*

/** Task 3.0.3 — `forge profile` handler (`ProfileCommand.run`): the perception → persist → render pipeline, exercised
  * with a [[FakeReviewerConnector]] (so no real CLI) wrapped in the real [[RealReviewerCall]] boundary and a
  * [[FileProfileStore]] over a temp repo. The reviewer-side connector selection + asset wiring is covered separately
  * (`RoleSuite`, `AssetInstallerSuite`); a live capture is opt-in in `forge-it`.
  */
class ProfileCommandSuite extends munit.FunSuite:

  private def tempPaths(): ForgePaths =
    val tmp = os.temp.dir(prefix = "profile-cmd-")
    val repoRoot = tmp / "repo"
    os.makeDir.all(repoRoot)
    ForgePaths(repoRoot, tmp / "home")

  private val sampleProfile: RepoProfile =
    RepoProfile(
      schemaVersion = RepoProfile.CurrentSchemaVersion,
      buildTool = "sbt",
      commands = Vector(
        RepoCommand(
          CommandKind.Format,
          Vector("sbt", "scalafmtAll"),
          Determinism.Deterministic,
          required = true,
          autofix = true
        )
      ),
      commitIdentity = CommitIdentity("forge[bot]", "forge@users.noreply.github.com"),
      workflow = WorkflowProfile(
        reviewRequired = true,
        ciRequiredChecks = Vector("backend"),
        BranchModel.TrunkBased,
        MergeStrategy.Squash
      )
    )

  private def reviewerReturning(profile: RepoProfile): ReviewerCall =
    val connector = FakeReviewerConnector.make(profileRepoIO = IO.pure(profile)).unsafeRunSync()
    new RealReviewerCall(connector)

  test("a settled profile is written to .forge/profile.json and the command succeeds"):
    val paths = tempPaths()
    val store = new FileProfileStore(paths)
    val exit = ProfileCommand.run(paths, reviewerReturning(sampleProfile), store).unsafeRunSync()
    assertEquals(exit, ExitCode.Success)
    val loaded = store.load().unsafeRunSync()
    assertEquals(loaded, Some(sampleProfile))
    assert(os.exists(paths.profileFile), "profile.json should exist after a successful profile run")

  test("a reviewer adapter failure exits non-zero and writes nothing"):
    val paths = tempPaths()
    val store = new FileProfileStore(paths)
    val connector =
      FakeReviewerConnector
        .make(profileRepoIO = IO.raiseError(new io.forge.agents.StructuredOutputMalformed("bad")))
        .unsafeRunSync()
    val exit = ProfileCommand.run(paths, new RealReviewerCall(connector), store).unsafeRunSync()
    assertEquals(exit, ExitCode(1))
    assert(!os.exists(paths.profileFile), "no profile.json should be written on failure")

  test("gatherInput reads AGENTS.md / CLAUDE.md / README.md / build files / workflow files when present"):
    val paths = tempPaths()
    val root = paths.repoRoot
    os.write(root / "AGENTS.md", "agents guide")
    os.write(root / "CLAUDE.md", "claude guide")
    os.write(root / "README.md", "use pnpm; run `pnpm test`")
    os.write(root / "build.sbt", "scalaVersion := \"3.7.1\"")
    os.makeDir.all(root / ".github" / "workflows")
    os.write(root / ".github" / "workflows" / "ci.yml", "name: CI")
    os.write(root / ".github" / "workflows" / "ignore.txt", "not a workflow")
    val input = ProfileCommand.gatherInput(paths).unsafeRunSync()
    assertEquals(input.repoName, root.last)
    assertEquals(input.agentsDoc, Some("agents guide"))
    assertEquals(input.claudeDoc, Some("claude guide"))
    assertEquals(input.readmeDoc, Some("use pnpm; run `pnpm test`")) // P2
    assertEquals(input.buildFiles.map(_.path), Vector("build.sbt"))
    assertEquals(input.workflowFiles.map(_.path), Vector(".github/workflows/ci.yml"))

  test("gatherInput leaves absent files out of the input"):
    val paths = tempPaths()
    val input = ProfileCommand.gatherInput(paths).unsafeRunSync()
    assertEquals(input.agentsDoc, None)
    assertEquals(input.claudeDoc, None)
    assertEquals(input.readmeDoc, None)
    assertEquals(input.buildFiles, Vector.empty)
    assertEquals(input.workflowFiles, Vector.empty)
