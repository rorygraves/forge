package io.forge.app.cli

import io.forge.app.cli.DaemonCommand
import io.forge.core.{FeatureId, InstanceName, PieceId}
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

  test("phase1 classifies profile as a state-changing connector command (feature-less)") {
    val Right(inv) = CliParser.phase1(List("profile")): @unchecked
    assertEquals(inv.name, "profile")
    assertEquals(inv.commandClass, CommandClass.StateChanging)
    assert(inv.needsConnector)
    assertEquals(inv.rest, Vector.empty)
  }

  test("phase1 classifies read-only commands without a connector") {
    List("status", "tail", "rebuild-state", "stats", "tui").foreach { name =>
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

  test("phase2 builds the feature-less profile command (and it binds to no feature)") {
    assertEquals(CliParser.phase2("profile", Vector.empty), Right(ForgeCommand.Profile))
    assertEquals(CliParser.featureOf(ForgeCommand.Profile), None)
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
    assertEquals(CliParser.phase2("stats", Vector("f")), Right(ForgeCommand.ReadOnly(ReadOnlyKind.Stats)))
    assertEquals(CliParser.phase2("tui", Vector("f")), Right(ForgeCommand.ReadOnly(ReadOnlyKind.Tui)))
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

  // --- Task 4.0.3: instance commands ----------------------------------------

  test("phase1 classifies the instance commands as the connector-free Instance class") {
    List("init-instance", "add-repo", "list-repos").foreach { name =>
      val Right(inv) = CliParser.phase1(List(name)): @unchecked
      assertEquals(inv.commandClass, CommandClass.Instance, name)
      assert(!inv.needsConnector, name)
    }
  }

  test("parseInstance builds init-instance from a positional name") {
    assertEquals(
      CliParser.parseInstance("init-instance", Vector("demo")),
      Right(InstanceCommand.InitInstance(InstanceName("demo")))
    )
  }

  test("parseInstance rejects a missing or invalid instance name") {
    assertEquals(
      CliParser.parseInstance("init-instance", Vector.empty),
      Left(CliError.MissingInstanceName("init-instance"))
    )
    CliParser.parseInstance("init-instance", Vector("Bad Name")) match
      case Left(CliError.InvalidInstanceName("Bad Name", _)) => ()
      case other => fail(s"expected InvalidInstanceName, got $other")
  }

  test("parseInstance builds add-repo with an optional --instance, in either order") {
    assertEquals(
      CliParser.parseInstance("add-repo", Vector("/path/to/repo")),
      Right(InstanceCommand.AddRepo(None, "/path/to/repo"))
    )
    assertEquals(
      CliParser.parseInstance("add-repo", Vector("/path/to/repo", "--instance", "demo")),
      Right(InstanceCommand.AddRepo(Some(InstanceName("demo")), "/path/to/repo"))
    )
    // The --instance value must not be mistaken for the positional <path> when the flag precedes it.
    assertEquals(
      CliParser.parseInstance("add-repo", Vector("--instance", "demo", "/path/to/repo")),
      Right(InstanceCommand.AddRepo(Some(InstanceName("demo")), "/path/to/repo"))
    )
  }

  test("parseInstance requires a <path> for add-repo") {
    assertEquals(CliParser.parseInstance("add-repo", Vector.empty), Left(CliError.MissingRepoPath("add-repo")))
    // With only --instance present there is still no positional path.
    assertEquals(
      CliParser.parseInstance("add-repo", Vector("--instance", "demo")),
      Left(CliError.MissingRepoPath("add-repo"))
    )
  }

  test("parseInstance builds list-repos with and without --instance") {
    assertEquals(CliParser.parseInstance("list-repos", Vector.empty), Right(InstanceCommand.ListRepos(None)))
    assertEquals(
      CliParser.parseInstance("list-repos", Vector("--instance", "demo")),
      Right(InstanceCommand.ListRepos(Some(InstanceName("demo"))))
    )
  }

  test("parseInstance rejects a valueless --instance flag") {
    assertEquals(
      CliParser.parseInstance("list-repos", Vector("--instance")),
      Left(CliError.MissingFlagValue("--instance"))
    )
    assertEquals(
      CliParser.parseInstance("add-repo", Vector("/repo", "--instance")),
      Left(CliError.MissingFlagValue("--instance"))
    )
  }

  // --- Task 4.1.3: daemon commands ------------------------------------------

  test("phase1 classifies daemon as the connector-free Daemon class") {
    val Right(inv) = CliParser.phase1(List("daemon", "start")): @unchecked
    assertEquals(inv.commandClass, CommandClass.Daemon)
    assert(!inv.needsConnector)
    assertEquals(inv.rest, Vector("start"))
  }

  test("parseDaemon builds each subcommand, with and without --instance, in either order") {
    assertEquals(CliParser.parseDaemon(Vector("start")), Right(DaemonCommand.Start(None)))
    assertEquals(CliParser.parseDaemon(Vector("stop")), Right(DaemonCommand.Stop(None)))
    assertEquals(CliParser.parseDaemon(Vector("status")), Right(DaemonCommand.Status(None)))
    assertEquals(
      CliParser.parseDaemon(Vector("start", "--instance", "demo")),
      Right(DaemonCommand.Start(Some(InstanceName("demo"))))
    )
    // --instance value must not be mistaken for the subcommand positional when the flag leads.
    assertEquals(
      CliParser.parseDaemon(Vector("--instance", "demo", "status")),
      Right(DaemonCommand.Status(Some(InstanceName("demo"))))
    )
  }

  test("parseDaemon rejects a missing or unknown subcommand") {
    assertEquals(CliParser.parseDaemon(Vector.empty), Left(CliError.MissingDaemonSubcommand))
    // Only --instance present → still no subcommand positional.
    assertEquals(CliParser.parseDaemon(Vector("--instance", "demo")), Left(CliError.MissingDaemonSubcommand))
    assertEquals(CliParser.parseDaemon(Vector("restart")), Left(CliError.UnknownDaemonSubcommand("restart")))
  }

  test("parseDaemon rejects a valueless --instance flag") {
    assertEquals(CliParser.parseDaemon(Vector("start", "--instance")), Left(CliError.MissingFlagValue("--instance")))
  }

  // --- Task 4.0.4: extractInstance on the feature-command path ----------------

  test("extractInstance pulls --instance out and preserves the remaining positional in either order") {
    // Feature command form: `forge run my-feat --instance demo` (flag trails the feature).
    assertEquals(
      CliParser.extractInstance(Vector("my-feat", "--instance", "demo")),
      Right((Some(InstanceName("demo")), Vector("my-feat")))
    )
    // And `forge run --instance demo my-feat` (flag leads): the value must not be mistaken for the feature.
    assertEquals(
      CliParser.extractInstance(Vector("--instance", "demo", "my-feat")),
      Right((Some(InstanceName("demo")), Vector("my-feat")))
    )
  }

  test("extractInstance returns the rest unchanged when no --instance is present") {
    assertEquals(CliParser.extractInstance(Vector("my-feat")), Right((None, Vector("my-feat"))))
    assertEquals(CliParser.extractInstance(Vector.empty), Right((None, Vector.empty)))
  }

  test("extractInstance rejects a valueless or invalid --instance") {
    assertEquals(
      CliParser.extractInstance(Vector("my-feat", "--instance")),
      Left(CliError.MissingFlagValue("--instance"))
    )
    CliParser.extractInstance(Vector("--instance", "Bad Name", "my-feat")) match
      case Left(CliError.InvalidInstanceName("Bad Name", _)) => ()
      case other => fail(s"expected InvalidInstanceName, got $other")
  }
