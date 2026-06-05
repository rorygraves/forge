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
  //
  // Phase-4 B1 (forge-design-2.0.md §4.3) splits the per-repo `.forge/` tree into two roots: the COMMITTED family
  // (specs / config / profile / overrides / per-repo prices — versioned, merged via PR) stays anchored at
  // `repoRoot/.forge`; the LOCAL-RUNTIME family (log / state / lock — gitignored, worker-owned) re-roots under
  // `localRoot/.forge`. With the default `localRoot = repoRoot` both still sit under `repoRoot/.forge` (v1 layout).

  private val committedPaths = Vector(
    paths.featureSpecDir(feature),
    paths.design(feature),
    paths.manifest(feature),
    paths.decomposition(feature),
    paths.pieceSpec(feature, piece),
    paths.auditDir(feature),
    paths.audit(feature, "x.md"),
    paths.configFile,
    paths.profileFile,
    paths.overridesDir,
    paths.overrideFile("claude"),
    paths.pricesRepo
  )

  private def localRuntimePaths(p: ForgePaths) = Vector(
    p.featureLog(feature),
    p.stateFile(feature),
    p.pollBaselineFile(feature),
    p.lockFile,
    p.lockMetadataFile
  )

  test("every committed path is strictly under repoRoot/.forge"):
    val root = repoRoot / ".forge"
    committedPaths.foreach: p =>
      assert(p.startsWith(root), s"$p is not under $root")

  test("with default localRoot, local-runtime paths are also under repoRoot/.forge (v1 layout unchanged)"):
    val root = repoRoot / ".forge"
    localRuntimePaths(paths).foreach: p =>
      assert(p.startsWith(root), s"$p is not under $root")

  // --- B1 re-root: localRoot re-homes only the local-runtime family --------------

  private val instanceLocalRoot = os.root / "home" / "alice" / ".forge" / "instances" / "demo" / "workers" / "f1"
  private val reRooted = ForgePaths(repoRoot, home, localRootOpt = Some(instanceLocalRoot))

  test("B1: local-runtime paths re-root under localRoot/.forge"):
    val localRoot = instanceLocalRoot / ".forge"
    assertEquals(reRooted.featureLog(feature), localRoot / "log" / "stripe-webhook.jsonl")
    assertEquals(reRooted.stateFile(feature), localRoot / "state" / "stripe-webhook.json")
    assertEquals(reRooted.pollBaselineFile(feature), localRoot / "state" / "stripe-webhook.poll-baselines.json")
    assertEquals(reRooted.lockFile, localRoot / "state" / ".lock")
    assertEquals(reRooted.lockMetadataFile, localRoot / "state" / ".lock.json")

  test("B1: committed paths stay anchored at repoRoot/.forge even when re-rooted"):
    val root = repoRoot / ".forge"
    // The committed accessors do not depend on `paths`'s instance — re-read them off `reRooted`.
    val committedReRooted = Vector(
      reRooted.featureSpecDir(feature),
      reRooted.manifest(feature),
      reRooted.configFile,
      reRooted.profileFile,
      reRooted.overridesDir,
      reRooted.pricesRepo
    )
    committedReRooted.foreach: p =>
      assert(p.startsWith(root), s"$p is not under $root")
    // and none of them leak into the re-rooted local dir
    committedReRooted.foreach: p =>
      assert(!p.startsWith(instanceLocalRoot / ".forge"), s"$p must not be under the local re-root")

  test("B1: localRoot defaults to repoRoot — re-rooted-with-repoRoot equals the v1 layout"):
    val defaultLocal = ForgePaths(repoRoot, home)
    val explicitLocal = ForgePaths(repoRoot, home, localRootOpt = Some(repoRoot))
    assertEquals(defaultLocal.featureLog(feature), explicitLocal.featureLog(feature))
    assertEquals(defaultLocal.lockFile, explicitLocal.lockFile)
    assertEquals(defaultLocal.localForgeDir, defaultLocal.repoForgeDir)

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
