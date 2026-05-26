package io.forge.core.paths

import io.forge.core.{FeatureId, PieceId}

class ForgePathsSuite extends munit.FunSuite:

  // --- fixtures --------------------------------------------------------------

  private val repoRoot = os.root / "tmp" / "repo"
  private val home = os.root / "home" / "alice"
  private val paths = ForgePaths(repoRoot, home)
  private val feature = FeatureId("stripe-webhook")
  private val piece = PieceId("p3")

  // --- golden-path tests (every method) --------------------------------------

  test("featureSpecDir is under repoRoot/.forge/specs/<feature>"):
    assertEquals(paths.featureSpecDir(feature), repoRoot / ".forge" / "specs" / "stripe-webhook")

  test("design = featureSpecDir / design.md"):
    assertEquals(paths.design(feature), repoRoot / ".forge" / "specs" / "stripe-webhook" / "design.md")

  test("manifest = featureSpecDir / manifest.json"):
    assertEquals(paths.manifest(feature), repoRoot / ".forge" / "specs" / "stripe-webhook" / "manifest.json")

  test("decomposition = featureSpecDir / decomposition.md"):
    assertEquals(paths.decomposition(feature), repoRoot / ".forge" / "specs" / "stripe-webhook" / "decomposition.md")

  test("pieceSpec = featureSpecDir / pieces / <p>.md"):
    assertEquals(
      paths.pieceSpec(feature, piece),
      repoRoot / ".forge" / "specs" / "stripe-webhook" / "pieces" / "p3.md"
    )

  test("auditDir = featureSpecDir / audit"):
    assertEquals(paths.auditDir(feature), repoRoot / ".forge" / "specs" / "stripe-webhook" / "audit")

  test("audit(name) = auditDir / name"):
    assertEquals(
      paths.audit(feature, "spec-answers.md"),
      repoRoot / ".forge" / "specs" / "stripe-webhook" / "audit" / "spec-answers.md"
    )

  test("featureLog = repoRoot/.forge/log/<feature>.jsonl"):
    assertEquals(paths.featureLog(feature), repoRoot / ".forge" / "log" / "stripe-webhook.jsonl")

  test("stateFile = repoRoot/.forge/state/<feature>.json"):
    assertEquals(paths.stateFile(feature), repoRoot / ".forge" / "state" / "stripe-webhook.json")

  test("lockFile = repoRoot/.forge/state/.lock"):
    assertEquals(paths.lockFile, repoRoot / ".forge" / "state" / ".lock")

  test("lockMetadataFile = repoRoot/.forge/state/.lock.json"):
    assertEquals(paths.lockMetadataFile, repoRoot / ".forge" / "state" / ".lock.json")

  test("pricesUser = home/.forge/prices.json"):
    assertEquals(paths.pricesUser, home / ".forge" / "prices.json")

  test("pricesRepo = repoRoot/.forge/prices.json"):
    assertEquals(paths.pricesRepo, repoRoot / ".forge" / "prices.json")

  // --- enclosing-directory invariants ----------------------------------------

  test("every per-repo path is strictly under repoRoot/.forge"):
    val perRepo = Vector(
      paths.featureSpecDir(feature),
      paths.design(feature),
      paths.manifest(feature),
      paths.decomposition(feature),
      paths.pieceSpec(feature, piece),
      paths.auditDir(feature),
      paths.audit(feature, "x.md"),
      paths.featureLog(feature),
      paths.stateFile(feature),
      paths.lockFile,
      paths.lockMetadataFile,
      paths.pricesRepo
    )
    val root = repoRoot / ".forge"
    perRepo.foreach: p =>
      assert(p.startsWith(root), s"$p is not under $root")

  test("pricesUser is strictly under home/.forge"):
    assert(paths.pricesUser.startsWith(home / ".forge"), s"${paths.pricesUser} not under ${home / ".forge"}")

  test("default home falls back to os.home"):
    val defaultPaths = ForgePaths(repoRoot)
    assertEquals(defaultPaths.pricesUser, os.home / ".forge" / "prices.json")

  // --- smell-test enforcement: no `.forge` string segment outside this helper ---
  //
  // PR-A A4 graduates this from a smell test (AGENTS.md grep recipe) to a build-enforced
  // rule. Production code that needs a path under `.forge/...` must call a `ForgePaths`
  // method; test fixtures (anything under `src/test/`) are exempt because they sometimes
  // need to document expected on-disk paths verbatim (e.g. ManifestPatchSuite's
  // `Piece.specPath` fixture).
  //
  // The regex matches `".forge` (open quote + literal `.forge`) without requiring a
  // trailing `/`, so it catches BOTH the legacy `".forge/log"` form AND the idiomatic
  // os-lib quoted-segment form `repoRoot / ".forge" / "state"` — exactly the shape
  // Slice 2+ call sites are likeliest to reach for.

  test("no `.forge` string literal lives outside ForgePaths.scala in production sources"):
    val modulesDir = os.pwd / "modules"
    assert(os.exists(modulesDir), s"expected modules/ under ${os.pwd}; sbt working dir misconfigured?")

    val literalRe = """"\.forge""".r
    val offenders = os
      .walk(modulesDir)
      .filter(_.last.endsWith(".scala"))
      .filterNot(_.segments.contains("target"))
      .filterNot(_.segments.contains("test")) // src/test/... is allowed
      .filterNot(_.last == "ForgePaths.scala") // the helper itself
      .flatMap: file =>
        os.read
          .lines(file)
          .zipWithIndex
          .collect:
            case (line, idx) if literalRe.findFirstIn(line).isDefined =>
              s"${file.relativeTo(os.pwd)}:${idx + 1}: $line"

    if offenders.nonEmpty then
      fail(
        "Production sources must not hardcode `.forge` string segments — use ForgePaths.\n" +
          offenders.mkString("\n")
      )
