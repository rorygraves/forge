package io.forge.core.profile

/** Task 3.2 — the pure `ConventionDeltas` merge logic the orchestrator applies to the committed profile. The learner is
  * additive and deduped: a fresh command lands; a command the profile already declares (same `(kind, argv)`) is
  * dropped, so re-running the learner is idempotent and never duplicates a gate command.
  */
class ConventionDeltasSuite extends munit.FunSuite:

  private val baseProfile = RepoProfile(
    schemaVersion = RepoProfile.CurrentSchemaVersion,
    buildTool = "sbt",
    commands = Vector(
      RepoCommand(
        CommandKind.Build,
        Vector("sbt", "compile"),
        Determinism.Deterministic,
        required = true,
        autofix = false
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

  private val formatCmd =
    RepoCommand(
      CommandKind.Format,
      Vector("sbt", "scalafmtAll"),
      Determinism.Deterministic,
      required = true,
      autofix = true
    )

  test("freshCommands keeps a genuinely new command"):
    val deltas = ConventionDeltas(Vector(formatCmd), None, "add format gate")
    assertEquals(deltas.freshCommands(baseProfile), Vector(formatCmd))

  test("freshCommands drops a command already declared (deduped by kind+argv)"):
    val deltas = ConventionDeltas(Vector(baseProfile.commands.head), None, "noop")
    assertEquals(deltas.freshCommands(baseProfile), Vector.empty[RepoCommand])

  test("applyTo appends fresh commands, leaving existing ones in place"):
    val updated = ConventionDeltas(Vector(formatCmd), None, "add format").applyTo(baseProfile)
    assertEquals(updated.commands, baseProfile.commands :+ formatCmd)
    assertEquals(updated.buildTool, baseProfile.buildTool)
    assertEquals(updated.workflow, baseProfile.workflow)

  test("applyTo is a no-op (==) when there is nothing fresh — the orchestrator can skip the save"):
    val deltas = ConventionDeltas(Vector(baseProfile.commands.head), None, "noop")
    assertEquals(deltas.applyTo(baseProfile), baseProfile)

  test("applyTo with an empty addCommands is a no-op even when a CLAUDE.md edit is proposed"):
    val deltas = ConventionDeltas(Vector.empty, Some(ClaudeMdProposal("why", "run scalafmtAll")), "doc only")
    assertEquals(deltas.applyTo(baseProfile), baseProfile)
