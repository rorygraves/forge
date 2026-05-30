package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.agents.{
  AgentEvent,
  AgentSession,
  Connector,
  DesignReview,
  DesignReviewInput,
  FixupPrompt,
  ImplementationPrompt,
  PrReview,
  PrReviewInput,
  RefineInput,
  RefineResult,
  StreamingSession
}
import io.forge.app.config.ForgeConfig
import io.forge.core.*
import io.forge.core.cost.Cost
import io.forge.core.fsm.{FsmEvent, FsmState, SessionPhase}
import io.forge.core.manifest.Manifest
import io.forge.core.paths.ForgePaths
import io.forge.core.pr.PrState
import io.forge.git.branch.{BaseFreshness, BaseSnapshot, BranchError, BranchManager, ForgeCommand, PreflightReport}
import io.forge.git.branch.protection.RequiredChecksOverlay
import io.forge.git.cli.{CommitResult, FastForwardResult, GhClient, GhError, GitClient, GitError, StatusEntry}
import io.forge.specs.{DefaultChangeCollector, DocSync, DocSyncError, SpecStore, SpecStoreError}

import fs2.Stream

import scala.collection.mutable.ArrayBuffer

import OrchestratorTestKit.*

/** Task 1.4.10-d2b — unit cover for the real `SideEffects` wiring against fakes for the git/gh/`Connector` seams. The
  * `Connector` itself is exercised end-to-end with real CLIs in d2c (`forge-it`); here we pin the pure assembly: the
  * `StatusEntry → FileChange` projection, the §11.4 classify→commit→push→createPr ordering, the `Deny` → `Left`
  * routing, and reviewer-input assembly.
  */
class RealSideEffectsSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-rse-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")

  private def oneePieceManifest: Manifest =
    mkManifest(featureId, Vector(piecePending(p1, 1)))

  private def sut(
      repoRoot: os.Path,
      calls: ArrayBuffer[String] = ArrayBuffer.empty,
      status: Vector[StatusEntry] = Vector.empty,
      diff: String = "",
      pieceSpec: String = "piece spec body",
      design: String = "the design",
      prNumber: PrNumber = PrNumber(42),
      connector: Connector = new FakeConnector
  ): RealSideEffects =
    val paths = ForgePaths(repoRoot, repoRoot / "home")
    new RealSideEffects(
      connector = connector,
      branchManager = new FakeBranchManager(calls, prNumber),
      git = new FakeGitClient(calls, status),
      gh = new FakeGhClient(diff),
      changeCollector = new DefaultChangeCollector,
      specStore = new FakeSpecStore(design, Map(p1 -> pieceSpec)),
      docSync = new FakeDocSync,
      paths = paths,
      config = ForgeConfig.Default
    )

  private def feature(state: FsmState) =
    featureAt(featureId, oneePieceManifest, state)

  // --- statusToFileChanges (pure) -------------------------------------------

  test("statusToFileChanges maps every porcelain kind, carrying gitIgnored and rename source"):
    import io.forge.specs.FileChangeKind
    val repo = os.Path("/repo")
    val entries = Vector(
      StatusEntry('M', ' ', "src/A.scala", None, ignored = false),
      StatusEntry(' ', 'D', "src/B.scala", None, ignored = false),
      StatusEntry('?', '?', "new.txt", None, ignored = false),
      StatusEntry('R', ' ', "dst.scala", Some("old.scala"), ignored = false),
      StatusEntry('!', '!', "target/x.class", None, ignored = true)
    )
    val out = RealSideEffects.statusToFileChanges(repo, entries)
    assertEquals(out(0).kind, FileChangeKind.Modified)
    assertEquals(out(0).path, repo / "src" / "A.scala")
    assertEquals(out(0).gitIgnored, false)
    assertEquals(out(1).kind, FileChangeKind.Deleted)
    assertEquals(out(2).kind, FileChangeKind.Added)
    assertEquals(out(3).kind, FileChangeKind.Renamed(repo / "old.scala"))
    assertEquals(out(4).kind, FileChangeKind.Added)
    assertEquals(out(4).gitIgnored, true)

  // --- classifyCommitOpenPr -------------------------------------------------

  tempFixture.test("classifyCommitOpenPr: Allow → PrOpened, staging before commit before push before createPr"): repo =>
    val calls = ArrayBuffer.empty[String]
    val status = Vector(StatusEntry('M', ' ', "src/Main.scala", None, ignored = false))
    val se = sut(repo, calls = calls, status = status, prNumber = PrNumber(7))
    val ev = se.classifyCommitOpenPr(feature(FsmState.PieceImplementing(p1)), p1).unsafeRunSync()
    assertEquals(ev, Right(FsmEvent.PrOpened(p1, PrNumber(7))))
    val staged = calls.indexWhere(_.startsWith("git.stage"))
    val committed = calls.indexWhere(_.startsWith("git.commit"))
    val pushed = calls.indexWhere(_.startsWith("bm.push"))
    val pr = calls.indexWhere(_.startsWith("bm.createPr"))
    assert(staged >= 0 && committed > staged && pushed > committed && pr > pushed, calls.mkString(" | "))
    assert(calls.exists(_ == "git.commit(feat(feat): Piece p1)"), calls.mkString(" | "))

  tempFixture.test("classifyCommitOpenPr: a denied path → Left, no commit / createPr"): repo =>
    val calls = ArrayBuffer.empty[String]
    val status = Vector(StatusEntry('?', '?', ".env", None, ignored = false))
    val se = sut(repo, calls = calls, status = status)
    val ev = se.classifyCommitOpenPr(feature(FsmState.PieceImplementing(p1)), p1).unsafeRunSync()
    assert(ev.isLeft, s"expected Left, got $ev")
    assert(ev.left.exists(_.contains(".env")), ev.toString)
    assert(!calls.exists(_.startsWith("git.commit")), calls.mkString(" | "))
    assert(!calls.exists(_.startsWith("bm.createPr")), calls.mkString(" | "))

  // --- classifyCommitPush (fix-up) ------------------------------------------

  tempFixture.test("classifyCommitPush: Allow → Settled(Fixup, Clean), no createPr"): repo =>
    val calls = ArrayBuffer.empty[String]
    val status = Vector(StatusEntry('M', ' ', "src/Main.scala", None, ignored = false))
    val se = sut(repo, calls = calls, status = status)
    val ev = se.classifyCommitPush(feature(FsmState.PieceFixingUp(p1, PrNumber(7), 1)), p1, PrNumber(7)).unsafeRunSync()
    assertEquals(ev, Right(FsmEvent.Settled(SessionPhase.Fixup, io.forge.core.fsm.SettleOutcome.Clean)))
    assert(calls.exists(_ == "git.commit(fix(feat): Piece p1)"), calls.mkString(" | "))
    assert(!calls.exists(_.startsWith("bm.createPr")), calls.mkString(" | "))

  // --- advancePieceBranch ---------------------------------------------------

  tempFixture.test("advancePieceBranch: syncBase + createPieceBranch → BranchCreated"): repo =>
    val se = sut(repo)
    val ev = se.advancePieceBranch(feature(FsmState.DesignReady), p1).unsafeRunSync()
    ev match
      case Right(FsmEvent.BranchCreated(piece, _, baseSha)) =>
        assertEquals(piece, p1)
        assertEquals(baseSha, BaseSha)
      case other => fail(s"expected BranchCreated, got $other")

  // --- commitDesignAndOpenPr ------------------------------------------------

  tempFixture.test("commitDesignAndOpenPr → DesignPrSnapshotUpdated(open) with the new PR number"): repo =>
    val se = sut(repo, prNumber = PrNumber(99))
    val ev = se.commitDesignAndOpenPr(feature(FsmState.DesignReviewing(1))).unsafeRunSync()
    ev match
      case Right(FsmEvent.DesignPrSnapshotUpdated(snap)) =>
        assertEquals(snap.number, PrNumber(99))
        assertEquals(snap.state, PrState.Open)
      case other => fail(s"expected DesignPrSnapshotUpdated, got $other")

  // --- prReviewInput --------------------------------------------------------

  tempFixture.test("prReviewInput pulls the gh diff, piece spec, and parses changed files"): repo =>
    val diff = "diff --git a/src/X.scala b/src/X.scala\n@@ -1 +1 @@\n-old\n+new\n"
    val se = sut(repo, diff = diff, pieceSpec = "X spec")
    val res = se.prReviewInput(feature(FsmState.PieceAwaitingReview(p1, PrNumber(5))), p1, PrNumber(5)).unsafeRunSync()
    res match
      case Right(in) =>
        assertEquals(in.diff, diff)
        assertEquals(in.pieceSpec, "X spec")
        assertEquals(in.changedFiles, Vector("src/X.scala"))
      case other => fail(s"expected Right, got $other")

  // --- designReviewInput / refineInput --------------------------------------

  tempFixture.test("designReviewInput carries the round and design markdown"): repo =>
    val se = sut(repo, design = "DESIGN")
    val res = se.designReviewInput(feature(FsmState.DesignReviewing(2)), 2).unsafeRunSync()
    assertEquals(res.map(i => (i.round, i.designMarkdown)), Right((2, "DESIGN")))

  // --- C14 / N5: resume carries the driver system-prompt path -----------------

  tempFixture.test("resumeDesignRevision passes the spec driver prompt path so Codex can re-prepend §7.10(a)"): repo =>
    val resumeCalls = ArrayBuffer.empty[(String, os.Path, String)]
    val paths = ForgePaths(repo, repo / "home")
    val se = sut(repo, connector = new FakeConnector(resumeCalls))
    val withSession = feature(FsmState.DesignReviewing(1)).copy(designSessionId = Some("sid-1"))
    se.resumeDesignRevision(withSession, 1).unsafeRunSync()
    assertEquals(resumeCalls.size, 1)
    val (sid, promptPath, _) = resumeCalls.head
    assertEquals(sid, "sid-1")
    // Same file `launchSpec` spawned with — `<userPromptsDir>/specify.<cli>.md` — so the resumed Codex turn re-prepends
    // the identical driver framing instead of trusting session memory (design-rationale C14, closed by v1.3 §7.10(a)).
    assertEquals(promptPath, paths.userPromptsDir / "specify.claude.md")

  // ===========================================================================
  // Fakes
  // ===========================================================================

  private final class FakeConnector(resumeCalls: ArrayBuffer[(String, os.Path, String)] = ArrayBuffer.empty)
      extends Connector:
    val name = "claude"
    def runStreamingSpec(systemPromptPath: os.Path, initialUserMessage: String): IO[StreamingSession] =
      IO.raiseError(new NotImplementedError)
    def resumeStreamingSpec(sessionId: String, systemPromptPath: os.Path, message: String): IO[StreamingSession] =
      IO.delay(resumeCalls += ((sessionId, systemPromptPath, message))) *> IO.pure(RealSideEffectsSuite.NoopStreaming)
    def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] =
      IO.raiseError(new NotImplementedError)
    def runFixup(prompt: FixupPrompt): IO[AgentSession] = IO.raiseError(new NotImplementedError)
    def questionMechanism: QuestionMechanism = QuestionMechanism.Native
    def reviewDesign(input: DesignReviewInput): IO[DesignReview] = IO.raiseError(new NotImplementedError)
    def reviewPr(input: PrReviewInput): IO[PrReview] = IO.raiseError(new NotImplementedError)
    def refine(input: RefineInput): IO[RefineResult] = IO.raiseError(new NotImplementedError)
    def schemaMechanism: SchemaMechanism = SchemaMechanism.Native
    def costFrom(event: AgentEvent): Option[Cost] = None

  private final class FakeGitClient(calls: ArrayBuffer[String], statusEntries: Vector[StatusEntry]) extends GitClient:
    def currentBranch: IO[Either[GitError, BranchName]] = IO.pure(Right(BranchName("forge/feat/p1")))
    def currentSha: IO[Either[GitError, Sha]] = IO.pure(Right(HeadSha))
    def fetch(remote: String): IO[Either[GitError, Unit]] = IO.pure(Right(()))
    def fastForwardBase(base: BranchName): IO[Either[GitError, FastForwardResult]] =
      IO.pure(Right(FastForwardResult.AlreadyUpToDate(BaseSha)))
    def checkout(branch: BranchName, startPoint: Option[String]): IO[Either[GitError, Unit]] = IO.pure(Right(()))
    def push(branch: BranchName, force: Boolean, forceWithLease: Boolean): IO[Either[GitError, Unit]] =
      IO.pure(Right(()))
    def tag(name: String, sha: Sha): IO[Either[GitError, Unit]] = IO.pure(Right(()))
    def pushTag(name: String): IO[Either[GitError, Unit]] = IO.pure(Right(()))
    def deleteRemoteTag(name: String): IO[Either[GitError, Unit]] = IO.pure(Right(()))
    def deleteLocalTag(name: String): IO[Either[GitError, Unit]] = IO.pure(Right(()))
    def listTags(pattern: Option[String]): IO[Either[GitError, Vector[String]]] = IO.pure(Right(Vector.empty))
    def isWorktreeClean: IO[Either[GitError, Boolean]] = IO.pure(Right(false))
    def stage(paths: Vector[String]): IO[Either[GitError, Unit]] =
      IO { calls += s"git.stage(${paths.mkString(",")})"; Right(()) }
    def status(includeIgnored: Boolean): IO[Either[GitError, Vector[StatusEntry]]] = IO.pure(Right(statusEntries))
    def commit(message: String): IO[Either[GitError, CommitResult]] =
      IO { calls += s"git.commit($message)"; Right(CommitResult.Committed) }
    def branchExistsLocal(name: BranchName): IO[Either[GitError, Boolean]] = IO.pure(Right(true))
    def branchExistsRemote(name: BranchName): IO[Either[GitError, Boolean]] = IO.pure(Right(false))

  private final class FakeBranchManager(calls: ArrayBuffer[String], pr: PrNumber) extends BranchManager:
    def preflight(command: ForgeCommand, manifest: Option[Manifest]): IO[PreflightReport] =
      IO.raiseError(new NotImplementedError)
    def syncBase(base: BranchName): IO[Either[BranchError, BaseSnapshot]] =
      IO.pure(Right(BaseSnapshot(base, BaseSha)))
    def createDesignBranch(
        feature: FeatureId,
        branchPrefix: String,
        base: BaseSnapshot
    ): IO[Either[BranchError, BranchName]] = IO.pure(Right(BranchName("forge/feat/design")))
    def createPieceBranch(
        feature: FeatureId,
        piece: PieceId,
        branchPrefix: String,
        base: BaseSnapshot
    ): IO[Either[BranchError, (BranchName, Sha)]] =
      IO.pure(Right((BranchName(s"forge/${feature.value}/${piece.value}"), base.sha)))
    def baseFreshness(pr: PrNumber, expectedBaseSha: Sha, autoUpdate: Boolean): IO[Either[BranchError, BaseFreshness]] =
      IO.pure(Right(BaseFreshness.UpToDate))
    def pushCurrentBranch(forceWithLease: Boolean): IO[Either[BranchError, Unit]] =
      IO { calls += s"bm.push(forceWithLease=$forceWithLease)"; Right(()) }
    def createPr(title: String, body: String, base: BranchName): IO[Either[BranchError, PrNumber]] =
      IO { calls += s"bm.createPr($title)"; Right(pr) }
    def updatePrBranch(pr: PrNumber): IO[Either[BranchError, Unit]] = IO.pure(Right(()))
    def tagSnapshot(name: String, sha: Sha): IO[Either[BranchError, Unit]] =
      IO { calls += s"bm.tag($name)"; Right(()) }
    def pushTag(name: String): IO[Either[BranchError, Unit]] = IO.pure(Right(()))
    def deleteRemoteTag(name: String): IO[Either[BranchError, Unit]] = IO.pure(Right(()))
    def pruneSnapshotTags(
        feature: FeatureId,
        branchPrefix: String,
        retention: Int,
        alsoRemote: Boolean
    ): IO[Either[BranchError, Vector[String]]] = IO.pure(Right(Vector.empty))
    def requiredChecksOverlay(
        feature: FeatureId,
        base: BranchName,
        epoch: Long
    ): IO[Either[BranchError, RequiredChecksOverlay]] = IO.raiseError(new NotImplementedError)

  private final class FakeGhClient(diff: String) extends GhClient:
    def prView(pr: PrNumber, fields: Vector[String]): IO[Either[GhError, ujson.Value]] =
      IO.raiseError(new NotImplementedError)
    def prCreate(title: String, body: String, base: BranchName, head: BranchName): IO[Either[GhError, PrNumber]] =
      IO.raiseError(new NotImplementedError)
    def prUpdateBranch(pr: PrNumber): IO[Either[GhError, Unit]] = IO.pure(Right(()))
    def prDiff(pr: PrNumber): IO[Either[GhError, String]] = IO.pure(Right(diff))
    def apiBranchProtection(base: BranchName): IO[Either[GhError, Option[ujson.Value]]] = IO.pure(Right(None))

  private final class FakeSpecStore(design: String, pieceSpecs: Map[PieceId, String]) extends SpecStore:
    def loadManifest(feature: FeatureId): IO[Either[SpecStoreError, Manifest]] =
      IO.pure(Right(oneePieceManifest))
    def saveManifest(feature: FeatureId, manifest: Manifest): IO[Either[SpecStoreError, Unit]] = IO.pure(Right(()))
    def loadDesign(feature: FeatureId): IO[Either[SpecStoreError, String]] = IO.pure(Right(design))
    def saveDesign(feature: FeatureId, body: String): IO[Either[SpecStoreError, Unit]] = IO.pure(Right(()))
    def loadDecomposition(feature: FeatureId): IO[Either[SpecStoreError, String]] = IO.pure(Right(""))
    def saveDecomposition(feature: FeatureId, body: String): IO[Either[SpecStoreError, Unit]] = IO.pure(Right(()))
    def loadPieceSpec(feature: FeatureId, piece: PieceId): IO[Either[SpecStoreError, String]] =
      IO.pure(pieceSpecs.get(piece).toRight(SpecStoreError.NotFound(os.pwd / s"${piece.value}.md")))
    def savePieceSpec(feature: FeatureId, piece: PieceId, body: String): IO[Either[SpecStoreError, Unit]] =
      IO.pure(Right(()))

  private final class FakeDocSync extends DocSync:
    def renderManifest(manifest: io.forge.core.manifest.Manifest): IO[Either[DocSyncError, String]] =
      IO.pure(Right("decomp"))
    def renderDecomposition(feature: FeatureId): IO[Either[DocSyncError, String]] = IO.pure(Right("decomp"))
    def writeDecomposition(feature: FeatureId): IO[Either[DocSyncError, Unit]] = IO.pure(Right(()))

object RealSideEffectsSuite:
  /** A bare resumed session; the C14 call-site test only inspects the captured resume arguments, never the session. */
  private object NoopStreaming extends StreamingSession:
    override val sessionId: String = "sid-1"
    override val events: Stream[IO, AgentEvent] = Stream.empty
    override def close(): IO[Unit] = IO.unit
    override def kill(): IO[Unit] = IO.unit
    override def send(input: String): IO[Unit] = IO.unit
    override def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] = IO.unit
