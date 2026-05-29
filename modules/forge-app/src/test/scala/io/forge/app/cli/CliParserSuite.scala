package io.forge.app.cli

import io.forge.core.{FeatureId, PieceId}
import io.forge.git.branch.ForgeCommand
import io.forge.git.branch.ForgeCommand.ReadOnlyKind

/** Task 1.4.9 I2/I3 — the two-phase argv parser. */
class CliParserSuite extends munit.FunSuite:

  // --- phase 1: command class + global flags --------------------------------

  test("phase1 classifies a state-changing connector command") {
    val Right(inv) = CliParser.phase1(List("new", "my-feat")): @unchecked
    assertEquals(inv.name, "new")
    assertEquals(inv.commandClass, CommandClass.StateChanging)
    assert(inv.needsConnector)
    assertEquals(inv.repoRoot, None)
    assertEquals(inv.rest, Vector("my-feat"))
  }

  test("phase1 marks refresh-cache / abandon state-changing but connector-free") {
    assert(CliParser.phase1(List("refresh-cache", "f")).exists(i => !i.needsConnector))
    assert(CliParser.phase1(List("abandon", "f")).exists(i => !i.needsConnector))
  }

  test("phase1 classifies read-only commands without a connector") {
    List("status", "tail", "rebuild-state").foreach { name =>
      val Right(inv) = CliParser.phase1(List(name)): @unchecked
      assertEquals(inv.commandClass, CommandClass.ReadOnly, name)
      assert(!inv.needsConnector, name)
    }
  }

  test("phase1 classifies unlock as the recovery class") {
    val Right(inv) = CliParser.phase1(List("unlock", "--force")): @unchecked
    assertEquals(inv.commandClass, CommandClass.UnlockForce)
  }

  test("phase1 extracts --repo-root from the front") {
    val Right(inv) = CliParser.phase1(List("--repo-root", "/tmp/x", "run", "feat")): @unchecked
    assertEquals(inv.repoRoot, Some("/tmp/x"))
    assertEquals(inv.name, "run")
    assertEquals(inv.rest, Vector("feat"))
  }

  test("phase1 extracts --repo-root from anywhere in the args") {
    val Right(inv) = CliParser.phase1(List("run", "feat", "--repo-root", "/x")): @unchecked
    assertEquals(inv.repoRoot, Some("/x"))
    assertEquals(inv.rest, Vector("feat"))
  }

  test("phase1 rejects a valueless --repo-root") {
    assertEquals(CliParser.phase1(List("--repo-root")), Left(CliError.MissingFlagValue("--repo-root")))
  }

  test("phase1 rejects an empty arg list and an unknown command") {
    assertEquals(CliParser.phase1(Nil), Left(CliError.NoCommand))
    assertEquals(CliParser.phase1(List("frobnicate")), Left(CliError.UnknownCommand("frobnicate")))
  }

  // --- phase 2: concrete ForgeCommand ---------------------------------------

  test("phase2 builds the feature-only commands") {
    assertEquals(CliParser.phase2("new", Vector("my-feat")), Right(ForgeCommand.New(FeatureId("my-feat"))))
    assertEquals(CliParser.phase2("spec", Vector("my-feat")), Right(ForgeCommand.Spec(FeatureId("my-feat"))))
    assertEquals(CliParser.phase2("run", Vector("my-feat")), Right(ForgeCommand.Run(FeatureId("my-feat"))))
    assertEquals(CliParser.phase2("abandon", Vector("my-feat")), Right(ForgeCommand.Abandon(FeatureId("my-feat"))))
  }

  test("phase2 requires and validates the feature id") {
    assertEquals(CliParser.phase2("new", Vector.empty), Left(CliError.MissingFeatureId("new")))
    CliParser.phase2("new", Vector("Bad_Id!")) match
      case Left(CliError.InvalidFeatureId("Bad_Id!", _)) => ()
      case other => fail(s"expected InvalidFeatureId, got $other")
  }

  test("phase2 maps the read-only kinds (incl. the new tail, not replay)") {
    assertEquals(CliParser.phase2("status", Vector.empty), Right(ForgeCommand.ReadOnly(ReadOnlyKind.Status)))
    assertEquals(CliParser.phase2("tail", Vector("f")), Right(ForgeCommand.ReadOnly(ReadOnlyKind.Tail)))
    assertEquals(
      CliParser.phase2("rebuild-state", Vector("f")),
      Right(ForgeCommand.ReadOnly(ReadOnlyKind.RebuildState))
    )
  }

  test("phase2 requires --force on unlock") {
    assertEquals(CliParser.phase2("unlock", Vector("--force")), Right(ForgeCommand.UnlockForce))
    assertEquals(CliParser.phase2("unlock", Vector.empty), Left(CliError.UnlockRequiresForce))
  }

  test("phase2 builds each resume variant from its hint flag") {
    assertEquals(
      CliParser.phase2("resume", Vector("feat", "--after-human-push", "p1")),
      Right(ForgeCommand.ResumeAfterHumanPush(FeatureId("feat"), PieceId("p1")))
    )
    assertEquals(
      CliParser.phase2("resume", Vector("feat", "--commit-human-fix", "p2")),
      Right(ForgeCommand.ResumeCommitHumanFix(FeatureId("feat"), PieceId("p2")))
    )
    assertEquals(
      CliParser.phase2("resume", Vector("feat", "--run-fixup", "p3")),
      Right(ForgeCommand.ResumeRunFixup(FeatureId("feat"), PieceId("p3")))
    )
  }

  test("phase2 rejects resume with zero or multiple hint flags") {
    CliParser.phase2("resume", Vector("feat")) match
      case Left(_: CliError.BadResumeHint) => ()
      case other => fail(s"expected BadResumeHint for no hint, got $other")
    CliParser.phase2("resume", Vector("feat", "--run-fixup", "p1", "--commit-human-fix", "p2")) match
      case Left(_: CliError.BadResumeHint) => ()
      case other => fail(s"expected BadResumeHint for two hints, got $other")
  }

  test("phase2 validates the resume piece id") {
    CliParser.phase2("resume", Vector("feat", "--run-fixup", "nope")) match
      case Left(CliError.InvalidPieceId("nope", _)) => ()
      case other => fail(s"expected InvalidPieceId, got $other")
  }

  test("featureOf extracts the bound feature, None for read-only / unlock") {
    assertEquals(CliParser.featureOf(ForgeCommand.Run(FeatureId("feat"))), Some(FeatureId("feat")))
    assertEquals(
      CliParser.featureOf(ForgeCommand.ResumeRunFixup(FeatureId("feat"), PieceId("p1"))),
      Some(FeatureId("feat"))
    )
    assertEquals(CliParser.featureOf(ForgeCommand.ReadOnly(ReadOnlyKind.Status)), None)
    assertEquals(CliParser.featureOf(ForgeCommand.UnlockForce), None)
  }
