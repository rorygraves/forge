package io.forge.app.bootstrap

import cats.effect.unsafe.implicits.global
import io.forge.core.paths.ForgePaths

class AssetInstallerSuite extends munit.FunSuite:

  // --- fixtures --------------------------------------------------------------

  /** Per-test temp roots — sbt's working dir is the project base, so we sandbox under `target/` to keep noise out of
    * the repo root.
    */
  private def tempPaths(): (os.Path, ForgePaths) =
    val tmp = os.temp.dir(prefix = "asset-installer-")
    val repoRoot = tmp / "repo"
    val home = tmp / "home"
    os.makeDir.all(repoRoot)
    os.makeDir.all(home)
    (tmp, ForgePaths(repoRoot, home))

  private def allExpectedDestinations(paths: ForgePaths): Vector[os.Path] =
    Vector(
      paths.userSchemasDir / "design-review.json",
      paths.userSchemasDir / "code-review.json",
      paths.userSchemasDir / "refine.json",
      paths.userPromptsDir / "design-review.claude.md",
      paths.userPromptsDir / "design-review.codex.md",
      paths.userPromptsDir / "code-review.claude.md",
      paths.userPromptsDir / "code-review.codex.md",
      paths.userPromptsDir / "refine.claude.md",
      paths.userPromptsDir / "refine.codex.md",
      paths.userTemplatesDir / "pr-body.md.hbs",
      paths.userTemplatesDir / "decomposition.md.hbs",
      paths.userTemplatesDir / "spec-answers.md.hbs",
      paths.userTemplatesDir / "design-review-r1-answers.md.hbs",
      paths.userTemplatesDir / "design-pr-feedback-r1-answers.md.hbs",
      paths.userTemplatesDir / "impl-answers.md.hbs",
      paths.userTemplatesDir / "fixup-r1-answers.md.hbs"
    )

  // --- happy paths -----------------------------------------------------------

  test("first run installs every shipped asset under ~/.forge/"):
    val (_, paths) = tempPaths()
    val result = AssetInstaller.installIfMissing(paths).unsafeRunSync()

    val installed = result match
      case Right(v) => v
      case Left(err) => fail(s"expected install success, got ${err.detail}")

    val expected = allExpectedDestinations(paths)
    assertEquals(installed.size, expected.size, s"installed count differs from expected (${installed.map(_.dest)})")
    installed.foreach: rec =>
      assertEquals(rec.action, AssetInstaller.Action.Installed, s"${rec.dest} should be Installed")
      assert(os.exists(rec.dest), s"${rec.dest} should exist after install")
      assert(os.read(rec.dest).nonEmpty, s"${rec.dest} should be non-empty")

    expected.foreach: dest =>
      assert(installed.exists(_.dest == dest), s"expected $dest in installed set")

  test("re-running on a populated ~/.forge/ skips every asset"):
    val (_, paths) = tempPaths()
    val first = AssetInstaller.installIfMissing(paths).unsafeRunSync().toOption.get
    val expectedContents = first.map(rec => rec.dest -> os.read.bytes(rec.dest)).toMap

    val second = AssetInstaller.installIfMissing(paths).unsafeRunSync().toOption.get
    assertEquals(second.size, first.size)
    second.foreach: rec =>
      assertEquals(rec.action, AssetInstaller.Action.Skipped, s"${rec.dest} should be Skipped on re-run")
      assertEquals(
        os.read.bytes(rec.dest).toVector,
        expectedContents(rec.dest).toVector,
        s"${rec.dest} contents should be preserved across re-run"
      )

  test("partial pre-population: pre-existing files report Skipped, missing files report Installed"):
    val (_, paths) = tempPaths()
    // Pre-populate the design-review schema with a customised body. The installer must NOT overwrite it.
    val customised = "{\"custom\": true}\n"
    os.makeDir.all(paths.userSchemasDir)
    os.write(paths.userSchemasDir / "design-review.json", customised)

    val result = AssetInstaller.installIfMissing(paths).unsafeRunSync().toOption.get

    val designReview = result.find(_.dest == paths.userSchemasDir / "design-review.json").get
    assertEquals(designReview.action, AssetInstaller.Action.Skipped)
    assertEquals(os.read(paths.userSchemasDir / "design-review.json"), customised)

    val others = result.filterNot(_.dest == paths.userSchemasDir / "design-review.json")
    others.foreach: rec =>
      assertEquals(rec.action, AssetInstaller.Action.Installed, s"${rec.dest} expected Installed")
      assert(os.exists(rec.dest))

  test("write failure (parent path is a file, not a dir) surfaces WriteFailed"):
    val (_, paths) = tempPaths()
    // Plant the userForgeDir as a regular file so makeDir.all fails inside installOne.
    os.makeDir.all(paths.userForgeDir / os.up)
    os.write(paths.userForgeDir, "not a directory")

    val result = AssetInstaller.installIfMissing(paths).unsafeRunSync()
    result match
      case Left(_: AssetInstaller.WriteFailed) => () // expected
      case Left(other) => fail(s"expected WriteFailed, got ${other.getClass.getSimpleName}: ${other.detail}")
      case Right(v) => fail(s"expected failure, got success with ${v.size} records")

  test("existing destination that is a directory surfaces InvalidExistingDestination (not silent Skip)"):
    val (_, paths) = tempPaths()
    // Plant a directory at one of the leaf destinations — e.g. someone ran `mkdir refine.json` by accident,
    // or a previous install corrupted under a race. os.exists returns true but reading it as a file later
    // would explode; the installer must surface that here, not claim success.
    val target = paths.userSchemasDir / "refine.json"
    os.makeDir.all(target)
    assert(os.isDir(target), "fixture sanity: target should be a directory")

    val result = AssetInstaller.installIfMissing(paths).unsafeRunSync()
    result match
      case Left(AssetInstaller.InvalidExistingDestination(dest, kind)) =>
        assertEquals(dest, target)
        assertEquals(kind, "directory")
      case Left(other) =>
        fail(s"expected InvalidExistingDestination, got ${other.getClass.getSimpleName}: ${other.detail}")
      case Right(v) =>
        fail(s"expected failure, got success with ${v.size} records")
