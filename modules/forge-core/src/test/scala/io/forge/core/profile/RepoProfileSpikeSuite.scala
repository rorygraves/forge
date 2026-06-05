package io.forge.core.profile

import cats.effect.unsafe.implicits.global
import io.forge.core.FeatureId
import io.forge.core.paths.ForgePaths

/** Phase-3 spike proof (design-3.0 / forge-design-1.7 pending). No LLM, no network — exercises the *whole* contract
  * surface the 1.7 spec needs (RepoProfile model, command tiering, profile hash, the §19 `profile.snapshot` shape,
  * classification kinds, deterministic routing) against the **real dogfood-#2/#5 strings**, default-on under 60s.
  *
  * The load-bearing assertion is `the $1.78 collapse`: a real scalafmt CI failure routes to a local `sbt scalafmtAll`
  * (no driver turn) rather than the 2-round / $1.78 / 12-min fix-up that dogfood #2 actually paid.
  */
class RepoProfileSpikeSuite extends munit.FunSuite:

  private def fixture(name: String): RepoProfile =
    RepoProfile.fromJson(os.read(os.resource / "profiles" / s"$name.json"))

  private val szork = fixture("szork")
  private val forge = fixture("forge")

  private val classifier = new RuleBasedFailureClassifier

  // The real failing-check log dogfood #2 never piped to the driver. Note it *also* carries `[error]` lines, which
  // would match the compile-error rule — the test proves format precedence wins.
  private val scalafmtLog =
    """[error] /home/runner/work/szork/szork/backend/src/main/scala/szork/media/MusicGeneration.scala isn't formatted properly!
      |[error] --- a/backend/src/main/scala/szork/media/MusicGeneration.scala
      |[error] +++ b/backend/src/main/scala/szork/media/MusicGeneration.scala
      |[error] scalafmt: 1 file must be formatted
      |[error] (backend / scalafmtCheckAll) scalafmt: 1 file must be formatted""".stripMargin

  private val compileErrorLog =
    """[error] -- [E007] Type Mismatch Error: MediaNetworkConfig.scala:42:18
      |[error] 42 |  val readTimeoutMs: Int = "30s"
      |[error]    |                           ^^^^^
      |[error]    |                           Found:    ("30s" : String)
      |[error]    |                           Required: Int
      |[error] one error found""".stripMargin

  // dogfood #5's exact string.
  private val rateLimitLog = "GraphQL: API rate limit already exceeded for installation"

  // --- model expresses both real repos ---

  test("szork + forge fixtures round-trip byte-stably through the codec"):
    for p <- List(szork, forge) do
      assertEquals(p.schemaVersion, RepoProfile.CurrentSchemaVersion)
      assertEquals(RepoProfile.fromJson(RepoProfile.toJson(p)), p)

  test("both repos declare a deterministic, in-place format command (the routable one)"):
    for p <- List(szork, forge) do
      val fmt = p.command(CommandKind.Format)
      assert(fmt.isDefined, "expected a Format command")
      assertEquals(fmt.map(_.argv), Some(Vector("sbt", "scalafmtAll")))
      assert(fmt.exists(c => c.autofix && c.determinism == Determinism.Deterministic))

  test("szork carries the dogfood-#2 workflow shape (backend+frontend required, squash, PR-based)"):
    assertEquals(szork.workflow.ciRequiredChecks, Vector("backend", "frontend"))
    assertEquals(szork.workflow.mergeStrategy, MergeStrategy.Squash)
    // szork is a normal PR repo → pr_based (the post-P0 default). It must NOT be trunk_based, which would let Forge
    // push straight to main with no PR.
    assertEquals(szork.workflow.branchModel, BranchModel.PrBased)

  // --- classification of the real logs ---

  test("real scalafmt failure → DeterministicFix(Format), format precedence beats the [error] lines"):
    val c = classifier.classify(scalafmtLog, szork)
    assertEquals(c.kind, FailureKind.DeterministicFix)
    assertEquals(c.suggested, Some(CommandKind.Format))
    assert(c.confidence >= 0.9)
    assert(c.evidence.toLowerCase.contains("must be formatted"))

  test("real Scala compile error → CodeFix"):
    val c = classifier.classify(compileErrorLog, szork)
    assertEquals(c.kind, FailureKind.CodeFix)

  test("dogfood-#5 rate-limit string → RateLimit"):
    val c = classifier.classify(rateLimitLog, szork)
    assertEquals(c.kind, FailureKind.RateLimit)

  test("unrecognised log → Unknown with low confidence (conservative, escalates)"):
    val c = classifier.classify("some output we have never seen before\n", szork)
    assertEquals(c.kind, FailureKind.Unknown)
    assert(c.confidence < 0.5)

  // --- the load-bearing routing proof: the $1.78 collapse ---

  test("scalafmt failure routes to a local `sbt scalafmtAll` — no driver turn"):
    val c = classifier.classify(scalafmtLog, szork)
    val route = FailureRouting.route(c, szork, scalafmtLog)
    route match
      case FixupRoute.RunLocalCommand(cmd) =>
        assertEquals(cmd.argv, Vector("sbt", "scalafmtAll"))
        assertEquals(cmd.kind, CommandKind.Format)
      case other =>
        fail(s"expected a local scalafmtAll run (the dogfood-#2 collapse), got $other")

  test("compile error routes to a driver fix-up WITH the full failing log piped in"):
    val c = classifier.classify(compileErrorLog, szork)
    val route = FailureRouting.route(c, szork, compileErrorLog)
    route match
      case FixupRoute.DriverFixup(log) =>
        assertEquals(log, compileErrorLog) // the dogfood-#4 ask: real log, not the `gh pr checks` summary
      case other => fail(s"expected DriverFixup carrying the log, got $other")

  test("rate-limit routes to BackOff, not a fix-up (the dogfood-#5 false-NHI fix)"):
    val c = classifier.classify(rateLimitLog, szork)
    FailureRouting.route(c, szork, rateLimitLog) match
      case FixupRoute.BackOff(_) => ()
      case other => fail(s"expected BackOff, got $other")

  test("DeterministicFix with no profile remedy falls back to a driver fix-up (not a bad local run)"):
    val profileWithoutFormat = szork.copy(commands = szork.commands.filterNot(_.kind == CommandKind.Format))
    val c = classifier.classify(scalafmtLog, profileWithoutFormat)
    FailureRouting.route(c, profileWithoutFormat, scalafmtLog) match
      case FixupRoute.DriverFixup(_) => ()
      case other => fail(s"expected DriverFixup fallback, got $other")

  // --- profile hash + §19 snapshot shape ---

  test("contentHash is stable across re-serialisation"):
    assertEquals(szork.contentHash, RepoProfile.fromJson(RepoProfile.toJson(szork)).contentHash)
    assertEquals(szork.contentHash.length, 16)

  test("szork and forge hash differently (distinct commit identity / workflow)"):
    assertNotEquals(szork.contentHash, forge.contentHash)

  test("profile.snapshot action carries the hash + schemaVersion"):
    val draft = ProfileSnapshot.draft(FeatureId("extract-media-network-config"), szork)
    assertEquals(draft.kind, "profile.snapshot")
    assertEquals(draft.payload("hash").str, szork.contentHash)
    assertEquals(draft.payload("schemaVersion").num.toInt, RepoProfile.CurrentSchemaVersion)

  // --- store round-trip (atomic file seam) ---

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-profile-store-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  tempFixture.test("FileProfileStore: save then load returns the same profile; absent ⇒ None"): root =>
    val store = new FileProfileStore(new ForgePaths(repoRoot = root))
    assertEquals(store.load().unsafeRunSync(), None)
    store.save(szork).unsafeRunSync()
    assertEquals(store.load().unsafeRunSync(), Some(szork))
