package io.forge.core.property

import io.forge.core.*
import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}
import io.forge.core.log.{Action, ActionDraft}
import io.forge.core.profile.*

import java.time.Instant
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** Task 3.0.2 — the **replayability invariant** for Phase-3 repo adaptation: the `RepoProfile` reaches the orchestrator
  * only as a read-only *input* to the §8.2 router, and `Fsm.transition` (hence `Replay.foldEvents`, which encodes the
  * same §6.1 projection rules) never reads a `ProfileStore`. So a replay reproduces a run *without consulting any
  * profile*, and the run stays replayable even though classification is agentic.
  *
  * Two complementary checks:
  *
  *   - **R1 (round-trip inertness).** The two §19 profile actions — `profile.snapshot` (emitted once at feature start)
  *     and `profile.failure_classified` (emitted before acting on each classified gate failure) — are audit-only no-op
  *     projections in `Replay` (the default branch of `Replay.step`). Folding a happy-path trajectory with both actions
  *     injected reproduces exactly the same final `Feature` as folding it without them — including every §6.1
  *     session-id / round / epoch projection. If either action ever started perturbing a projection, this property
  *     would break.
  *
  *   - **R2 (structural guard).** No source in the `io.forge.core.fsm` package references the `io.forge.core.profile`
  *     package. The pure FSM cannot import `ProfileStore` / `RepoProfile`, so it cannot read the profile even by
  *     accident — the invariant is enforced at the type level, not just by the round-trip above. Mirrors the
  *     `ForgePathsSuite` literal-sweep idiom.
  */
class ProfileReplayInvarianceSuite extends ScalaCheckSuite:

  /** A minimal, realistic profile (mirrors `OrchestratorCiRoutingSuite`'s `scalafmtProfile`) — enough for the rules
    * classifier + router to produce a real `Classification` / `FixupRoute` for the injected
    * `profile.failure_classified`.
    */
  private val profile: RepoProfile = RepoProfile(
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
    commitIdentity = CommitIdentity("Forge", "forge@example.com"),
    workflow = WorkflowProfile(
      reviewRequired = true,
      ciRequiredChecks = Vector("backend"),
      branchModel = BranchModel.TrunkBased,
      mergeStrategy = MergeStrategy.Squash
    )
  )

  /** The real dogfood-#2 failing line, classified + routed exactly as the orchestrator would on a live gate failure. */
  private val classifiedDraft: PieceId => ActionDraft = piece =>
    val log = "[error] scalafmt: 1 files must be formatted (/repo)"
    val classification = new RuleBasedFailureClassifier().classify(log, profile)
    val route = FailureRouting.route(classification, profile, log)
    FailureClassifiedAction.draft(FeatureId("ignored"), piece, "ci", classification, route, source = "rules")

  /** §8.3 (Task 3.1.3) `profile.local_gate` — the orchestrator's local-format-gate audit record. A new `profile.*`
    * kind, so R1 must prove it is inert at replay too (the orchestrator owns the draft; here we mirror its shape).
    */
  private val localGateDraft: PieceId => ActionDraft = piece =>
    ActionDraft(
      FeatureId("ignored"),
      Some(piece),
      actor = None,
      role = None,
      kind = "profile.local_gate",
      payload = ujson.Obj(
        "gate" -> ujson.Str("local"),
        "kind" -> ujson.Str("format"),
        "commands" -> ujson.Arr(ujson.Str("sbt scalafmtAll"))
      )
    )

  property("R1 — profile.snapshot + profile.failure_classified + profile.local_gate are inert at replay") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      val baseDrafts = run.allDrafts
      // A piece for the classified action's top-level `piece` tag — audit-only, so any id is inert; pick a real one
      // when the manifest has pieces so the row is representative.
      val piece = seed.manifest.pieces.headOption.map(_.id).getOrElse(PieceId("p1"))
      // Inject the snapshot at feature start (as `run` does) and a classified-failure mid-stream, then re-stamp the
      // whole stream so `seq` stays strictly increasing.
      val augmented =
        ProfileSnapshot.draft(seed.id, profile) +:
          (baseDrafts.headOption.toVector ++
            Vector(localGateDraft(piece), classifiedDraft(piece)) ++ baseDrafts.drop(1))

      val seed0 = Feature.initial(seed.id, run.finalFeature.manifest)
      (Feature.foldEvents(seed0, stampDrafts(baseDrafts)), Feature.foldEvents(seed0, stampDrafts(augmented))) match
        case (Right(base), Right(aug)) =>
          (aug.feature == base.feature) :| s"profile actions perturbed replay: base=${base.feature}; aug=${aug.feature}"
        case (b, a) =>
          falsified :| s"foldEvents returned Left (base=$b, aug=$a)"
    }
  }

  test("R2 — no io.forge.core.fsm source references the io.forge.core.profile package"):
    val fsmDir = os.pwd / "modules" / "forge-core" / "src" / "main" / "scala" / "io" / "forge" / "core" / "fsm"
    assert(os.exists(fsmDir), s"expected the fsm package under ${os.pwd}; sbt working dir misconfigured?")

    val markers = Vector("core.profile", "ProfileStore", "RepoProfile", "FailureClassifier", "FailureRouting")
    val offenders = os
      .walk(fsmDir)
      .filter(_.last.endsWith(".scala"))
      .flatMap: file =>
        os.read
          .lines(file)
          .zipWithIndex
          .collect:
            case (line, idx) if markers.exists(line.contains) =>
              s"${file.relativeTo(os.pwd)}:${idx + 1}: ${line.trim}"

    assert(
      offenders.isEmpty,
      s"the pure FSM must not read the profile machinery (replayability invariant); found:\n${offenders.mkString("\n")}"
    )

  // --- helpers (copied from F1ReplayRoundTripSuite) --------------------------

  private def stampDrafts(drafts: Vector[ActionDraft]): Vector[Action] =
    val base = Instant.parse("2026-05-26T12:00:00Z")
    drafts.zipWithIndex.map: (d, i) =>
      d.stamp(seq = i.toLong + 1L, at = base.plusSeconds(i.toLong))
