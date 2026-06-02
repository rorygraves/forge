package io.forge.app.orchestrator

import io.forge.app.config.AdaptConfig
import io.forge.core.profile.*

/** Task 3.1.2 — the §8.2 router's pure decision over the real dogfood logs (the effectful fetch/run lives in the
  * orchestrator). Grounds the contract that a scalafmt failure + a Format-autofix profile collapses to a
  * `RunLocalCommand`, that the `adapt.autofix = false` opt-out degrades it to a `DriverFixup`, and that a code-error
  * log routes to a driver fix-up regardless.
  */
class FailureRouterSuite extends munit.FunSuite:

  private val router = new FailureRouter(new RuleBasedFailureClassifier)

  private def profile(commands: RepoCommand*): RepoProfile =
    RepoProfile(
      schemaVersion = RepoProfile.CurrentSchemaVersion,
      buildTool = "sbt",
      commands = commands.toVector,
      commitIdentity = CommitIdentity("Forge", "forge@example.com"),
      workflow = WorkflowProfile(true, Vector("backend"), BranchModel.TrunkBased, MergeStrategy.Squash)
    )

  private val scalafmtCmd =
    RepoCommand(
      CommandKind.Format,
      Vector("sbt", "scalafmtAll"),
      Determinism.Deterministic,
      required = true,
      autofix = true
    )

  private val scalafmtLog = "[error] scalafmt: 1 files must be formatted (/repo)"

  test("scalafmt failure + Format-autofix profile → RunLocalCommand(sbt scalafmtAll), source=rules"):
    val r = router.route(profile(scalafmtCmd), scalafmtLog, AdaptConfig())
    assertEquals(r.source, "rules")
    assertEquals(r.classification.kind, FailureKind.DeterministicFix)
    r.route match
      case FixupRoute.RunLocalCommand(cmd) => assertEquals(cmd.argv, Vector("sbt", "scalafmtAll"))
      case other => fail(s"expected RunLocalCommand, got $other")

  test("adapt.autofix=false degrades RunLocalCommand → DriverFixup carrying the log"):
    val r = router.route(profile(scalafmtCmd), scalafmtLog, AdaptConfig(autofix = false))
    r.route match
      case FixupRoute.DriverFixup(log) => assertEquals(log, scalafmtLog)
      case other => fail(s"expected DriverFixup, got $other")

  test("DeterministicFix without a matching autofix command falls back to DriverFixup"):
    // profile declares no Format command → the failure can't collapse to a local run.
    val r = router.route(profile(), scalafmtLog, AdaptConfig())
    assertEquals(r.classification.kind, FailureKind.DeterministicFix)
    assert(r.route.isInstanceOf[FixupRoute.DriverFixup], r.route.toString)

  test("compile error → DriverFixup(full log)"):
    val r = router.route(profile(scalafmtCmd), "[error] type mismatch; found Int required String", AdaptConfig())
    assertEquals(r.classification.kind, FailureKind.CodeFix)
    assert(r.route.isInstanceOf[FixupRoute.DriverFixup], r.route.toString)

  test("rate limit → BackOff (not a fix-up)"):
    val r = router.route(profile(scalafmtCmd), "GraphQL: API rate limit already exceeded", AdaptConfig())
    assertEquals(r.classification.kind, FailureKind.RateLimit)
    assert(r.route.isInstanceOf[FixupRoute.BackOff], r.route.toString)
