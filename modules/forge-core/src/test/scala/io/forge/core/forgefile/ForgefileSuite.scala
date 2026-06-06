package io.forge.core.forgefile

import cats.effect.unsafe.implicits.global
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.*

/** Phase-4 Slice 4.3, Task 4.3.2 — the normative `Forgefile` model + parser + the RepoProfile validation hook +
  * [[FileForgefileStore]]. Always-on, no Docker, `<60s`: pure parsing + a real-temp-dir store round-trip (the
  * `FileStateCache`/`FileProfileStore` idiom).
  */
class ForgefileSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-forgefile-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  // --- parser: happy path ---

  test("parse reads the image and pinned tools, ignoring comments and blank lines"):
    val text =
      """# Forgefile — normative pinning
        |image eclipse-temurin:21-jdk
        |
        |  tool sbt 1.10.7
        |tool scala 3.7.1
        |""".stripMargin
    val parsed = Forgefile.parse(text)
    assertEquals(parsed.map(_.image), Right("eclipse-temurin:21-jdk"))
    assertEquals(parsed.map(_.tools), Right(Vector(ToolPin("sbt", "1.10.7"), ToolPin("scala", "3.7.1"))))

  test("parse accepts an image with no tools"):
    assertEquals(Forgefile.parse("image busybox:1.36"), Right(Forgefile("busybox:1.36", Vector.empty)))

  test("tool() looks a pin up by name"):
    val f = Forgefile.parse("image x\ntool sbt 1.10.7").toOption.get
    assertEquals(f.tool("sbt"), Some(ToolPin("sbt", "1.10.7")))
    assertEquals(f.tool("nope"), None)

  // --- parser: errors ---

  test("parse rejects a Forgefile with no image"):
    assert(Forgefile.parse("tool sbt 1.10.7").isLeft)

  test("parse rejects a second image with a line number"):
    val err = Forgefile.parse("image a\nimage b").left.toOption.get
    assert(err.contains("line 2"), err)
    assert(err.contains("more than once"), err)

  test("parse rejects an image with the wrong argument count"):
    assert(Forgefile.parse("image").isLeft)
    assert(Forgefile.parse("image a b").isLeft)

  test("parse rejects a duplicate tool name"):
    val err = Forgefile.parse("image x\ntool sbt 1.0\ntool sbt 2.0").left.toOption.get
    assert(err.contains("line 3"), err)
    assert(err.contains("more than once"), err)

  test("parse rejects a tool with the wrong argument count"):
    assert(Forgefile.parse("image x\ntool sbt").isLeft)
    assert(Forgefile.parse("image x\ntool sbt 1 2").isLeft)

  test("parse rejects an unknown directive with a line number"):
    val err = Forgefile.parse("image x\nfrom ubuntu").left.toOption.get
    assert(err.contains("line 2"), err)
    assert(err.contains("unknown directive 'from'"), err)

  // --- validate: perception, not pinning ---

  private def profile(buildTool: String, commandArgvs: Vector[String]*): RepoProfile =
    RepoProfile(
      schemaVersion = RepoProfile.CurrentSchemaVersion,
      buildTool = buildTool,
      commands = commandArgvs.zipWithIndex.map { (argv, _) =>
        RepoCommand(CommandKind.Build, argv, Determinism.Deterministic, required = true, autofix = false)
      }.toVector,
      commitIdentity = CommitIdentity("Forge", "forge@example.com"),
      workflow = WorkflowProfile(reviewRequired = true, Vector.empty, BranchModel.PrBased, MergeStrategy.Squash)
    )

  test("validate returns no warnings when every perceived tool is pinned"):
    val f = Forgefile("img", Vector(ToolPin("sbt", "1.10.7"), ToolPin("scalafmt", "3.8.3")))
    val p = profile("sbt", Vector("scalafmt", "--check"))
    assertEquals(Forgefile.validate(f, p), Vector.empty)

  test("validate warns about a perceived build tool that is not pinned"):
    val f = Forgefile("img", Vector.empty)
    val warnings = Forgefile.validate(f, profile("sbt"))
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("'sbt'"), warnings.head)

  test("validate warns once per distinct unpinned tool, deduping repeats"):
    val f = Forgefile("img", Vector.empty)
    // build tool sbt + two commands both fronted by `sbt` → one warning for sbt, one for npm.
    val p = profile("sbt", Vector("sbt", "test"), Vector("npm", "run", "lint"))
    val warnings = Forgefile.validate(f, p)
    assertEquals(warnings.size, 2)
    assert(warnings.exists(_.contains("'sbt'")))
    assert(warnings.exists(_.contains("'npm'")))

  // --- FileForgefileStore: the committed-source-of-truth read policy ---

  tempFixture.test("FileForgefileStore returns None when no Forgefile is committed"): root =>
    val store = new FileForgefileStore(new ForgePaths(repoRoot = root))
    assertEquals(store.load().unsafeRunSync(), None)

  tempFixture.test("FileForgefileStore parses a committed Forgefile"): root =>
    val paths = new ForgePaths(repoRoot = root)
    os.write(paths.forgefile, "image busybox:1.36\ntool sbt 1.10.7")
    assertEquals(store(root).load().unsafeRunSync(), Some(Forgefile("busybox:1.36", Vector(ToolPin("sbt", "1.10.7")))))

  tempFixture.test("FileForgefileStore fails loudly on a malformed Forgefile (not a silent None)"): root =>
    val paths = new ForgePaths(repoRoot = root)
    os.write(paths.forgefile, "tool sbt 1.10.7") // no image
    intercept[IllegalArgumentException](store(root).load().unsafeRunSync())

  private def store(root: os.Path): FileForgefileStore = new FileForgefileStore(new ForgePaths(repoRoot = root))
