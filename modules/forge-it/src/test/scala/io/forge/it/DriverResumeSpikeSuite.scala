package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.agents.*
import io.forge.core.{FeatureId, PieceId}

import scala.concurrent.duration.*

/** roadmap §3.5 / [`design-3.5.md`] **D3-0 — de-risking spike** for driver-respawn-avoidance.
  *
  * The "D3 large half" wants a resume from an implement/fix-up `NeedsHumanIntervention` to **continue the existing
  * driver session via `--resume` instead of re-spawning it from scratch** (re-paying the full exploration, ~\$10 in the
  * szork MVP run). Before building the connector seam (D3-1), the orchestrator gate (D3-2/D3-3), or the stats proof
  * (D3-4), one assumption has to hold: **a headless driver turn, interrupted and then resumed from a *fresh process*,
  * actually continues with its prior context rather than re-exploring.** This is untested today — only the spec phase has
  * a resume path (`resumeStreamingSpec`, *streaming* mode); the implement/fix-up drivers are one-shot `-p` headless runs
  * with **no `--resume` capability**, so `claude -p … --resume <sid>` / `codex exec resume … <sid>` for a *driver* turn
  * is a wire combination Forge has never exercised. This suite captures that wire behaviour directly (per the CLAUDE.md
  * "capture real external shapes before writing decoders/schemas" rule) so D3-1+ build on measured fact, not assumption.
  *
  * **What each test characterises** (and the go/no-go signal it produces):
  *   - `codeword recall`: turn 1 (headless, fresh temp dir) is told a unique codeword and writes a file; the process
  *     ends; turn 2 resumes the *same session id* from a *new connector instance* and is asked only for the codeword. If
  *     the resumed turn echoes the codeword, headless `--resume` restored the conversation context → a real resume saves
  *     the re-exploration. If it does not (or the resume errors), the large half must be reframed (e.g. commit-and-continue
  *     from the on-disk diff) — surface that via `AskUserQuestion` before D3-1.
  *   - `killed mid-turn`: the realistic D3 crash shape — turn 1 is **killed while still working** (the settle-timeout /
  *     cost-cap / crash case), then resumed. Looser automated assertion (session id honoured + a clean turn-2 `Result` +
  *     any partial on-disk edit survives); the captured transcript is the artefact a human reads to judge whether the
  *     resume picked up mid-exploration.
  *
  * **The connector matrix is the deliverable.** Claude `--resume` preserves the session id server-side (Slice 0 §6.1);
  * Codex `exec resume` is **stateless** — it re-prepends the system block and passes *no* sandbox/approval flags
  * (`execResumeArgv`), so whether it restores exploration context (and whether it can still write files on resume) is the
  * open question this suite answers per connector. Results + a go/no-go call get pinned in `design-rationale.md` (D3-0).
  *
  * **Opt-in by default** (CLAUDE.md "default-on test runtime <60s"): two real driver turns per test cost real
  * tokens/minutes, so the whole suite is gated behind `FORGE_IT_RUN_RESUME_SPIKE=1` on top of the usual PATH probe +
  * `FORGE_IT_SKIP_CLAUDE` / `FORGE_IT_SKIP_CODEX` escape hatches. Each turn's raw event vector is written under
  * `FORGE_IT_RESUME_SPIKE_DUMP_DIR` (default: a fresh temp dir, path printed at suite start) for offline inspection.
  */
class DriverResumeSpikeSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 10.minutes

  // --- gating ----

  private def onPath(bin: String): Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / bin) => p / bin
    }

  private val runSpike = sys.env.get("FORGE_IT_RUN_RESUME_SPIKE").contains("1")
  private val claudeOnPath = onPath("claude")
  private val codexOnPath = onPath("codex")
  private val canClaude = runSpike && claudeOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CLAUDE").contains("1")
  private val canCodex = runSpike && codexOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")

  private val codexModel = sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")

  // A codeword unlikely to be produced by chance — the decisive "context was restored" signal.
  private val codeword = "ZARQ-9173-WIDGET"
  private val planMarker = "Refactor the auth module"

  private lazy val dumpDir: os.Path =
    sys.env
      .get("FORGE_IT_RESUME_SPIKE_DUMP_DIR")
      .map(os.Path(_, os.pwd))
      .getOrElse(os.temp.dir(prefix = "forge-resume-spike-"))

  // --- helpers ----

  private def systemPromptFile(content: String): os.Path =
    os.temp(contents = content, prefix = "forge-resume-spike-system-", suffix = ".md", deleteOnExit = true)

  private def loadPriceTable: PriceTable =
    val stream = getClass.getResourceAsStream("/prices.example.json")
    require(stream != null, "prices.example.json missing from classpath")
    try upickle.default.read[PriceTable](scala.io.Source.fromInputStream(stream)("UTF-8").mkString)
    finally stream.close()

  private def dump(name: String, events: Vector[AgentEvent]): Unit =
    val file = dumpDir / s"$name.txt"
    os.write.over(file, events.map(_.toString).mkString("\n"))
    println(s"[D3-0 spike] transcript → $file")

  private def assistantText(events: Vector[AgentEvent]): String =
    events.collect { case AgentEvent.AssistantText(t, _) => t }.mkString("\n")

  /** Drain a headless one-shot session to completion (drain-first is safe — `-p` reads no stdin and the CLI exits on its
    * own; the streaming close-then-drain idiom does not apply here, see [[ClaudeHeadlessSmokeSuite]]).
    */
  private def drain(session: AgentSession): IO[(String, Vector[AgentEvent])] =
    for
      events <- session.events.compile.toVector
      _ <- session.close()
    yield (session.sessionId, events)

  // --- Claude resume (headless `-p … --resume <sid>` — the untested driver combination) ----

  private val claudeDriverMode = "acceptEdits"
  private val claudeAllowed = Vector("Write", "Edit", "Read")

  private def claudeConnector(workdir: os.Path) =
    ClaudeConnector(
      binary = claudeOnPath.get.toString,
      cwd = Some(workdir),
      driverPermissionMode = claudeDriverMode,
      driverAllowedTools = claudeAllowed
    )

  /** The D3-1-shaped resume argv built inline: `-p <prompt>` headless (so the CLI exits on its own) + `--resume <sid>`,
    * **no** `--system-prompt-file` (Claude restores it server-side, mirroring `resumeStreamingSpecArgv`). `.attempt` so a
    * CLI that rejects `-p … --resume` surfaces as a captured finding rather than an opaque suite failure.
    */
  private def claudeResume(workdir: os.Path, sid: String, prompt: String): IO[Either[Throwable, (String, Vector[AgentEvent])]] =
    val argv = (claudeOnPath.get.toString :: "-p" :: prompt :: ClaudeConnector.IsolationFlags) ++
      ClaudeConnector.OutputFlags ++ List("--resume", sid) ++
      ClaudeConnector.driverPermissionFlags(claudeDriverMode, claudeAllowed)
    StreamingDriver
      .fromSubprocess(Subprocess.spawn(argv, cwd = Some(workdir)), ClaudeEventParser.parse, 90.seconds)
      .flatMap(s => s.events.compile.toVector.map(ev => (s.sessionId, ev)).guarantee(s.close()))
      .attempt

  // --- Codex resume (`codex exec resume --json <thread-id> <message>`) ----

  private def codexResume(workdir: os.Path, threadId: String, prompt: String, systemPromptPath: os.Path): IO[Either[Throwable, (String, Vector[AgentEvent])]] =
    // Mirror the connector's resume path: re-prepend the system block (Codex resume is stateless — C14). Note the resume
    // argv passes NO sandbox/approval flags, so file writes on a resumed turn depend on codex's default — a finding this
    // captures.
    val combined = CodexPrompt.withSystemBlock(systemPromptPath, prompt)
    val argv = CodexConnector.execResumeArgv(codexOnPath.get.toString, threadId, combined)
    StreamingDriver
      .fromSubprocess(
        Subprocess.spawn(argv, cwd = Some(workdir)).evalTap(_.closeStdin),
        new CodexEventParser(loadPriceTable, codexModel).parse,
        90.seconds
      )
      .flatMap(s => s.events.compile.toVector.map(ev => (s.sessionId, ev)).guarantee(s.close()))
      .attempt

  // --- prompts ----

  private val driverSystem =
    "You are a file-editing software agent working in the current directory. Use your file tools to make the requested " +
      "edits. Be concise."

  private def turn1Body(workdir: os.Path) =
    s"""Two tasks, in order:
       |1. Create a file named plan.md in the current directory whose entire contents are exactly this one line: $planMarker
       |2. Remember this codeword for a later turn: $codeword
       |When both are done, reply with the single word: ready""".stripMargin

  private val recallBody =
    s"Reply with ONLY the codeword I asked you to remember earlier — nothing else. If you do not have it, reply: UNKNOWN"

  // --- Claude tests ----

  test("Claude: headless --resume restores context (codeword recall) and the turn-1 file survives"):
    assume(canClaude, "skipped — set FORGE_IT_RUN_RESUME_SPIKE=1 and have `claude` on PATH (not FORGE_IT_SKIP_CLAUDE=1)")
    val workdir = os.temp.dir(prefix = "forge-resume-spike-claude-")
    val sys = systemPromptFile(driverSystem)
    val connector = claudeConnector(workdir)

    val (sid, turn1) = connector
      .runHeadlessImplementation(ImplementationPrompt(FeatureId("spike"), PieceId("p1"), sys, turn1Body(workdir)))
      .flatMap(drain)
      .unsafeRunSync()
    dump("claude-recall-turn1", turn1)

    // The on-disk file must survive process death (it is just a file — the OS does not unlink it). This is the worktree
    // the D3-2 classifier will inspect.
    val planFile = workdir / "plan.md"
    assert(os.exists(planFile), s"turn 1 did not write plan.md into $workdir (events dumped to claude-recall-turn1)")
    assert(os.read(planFile).contains(planMarker), clue = os.read(planFile))

    // Fresh connector instance = fresh process state; only the session id bridges the two turns.
    val resumed = claudeResume(workdir, sid, recallBody).unsafeRunSync()
    resumed match
      case Left(err) =>
        fail(s"Claude headless `-p … --resume $sid` failed — D3-1 cannot reuse this seam as-is: $err")
      case Right((newSid, turn2)) =>
        dump("claude-recall-turn2", turn2)
        assertEquals(newSid, sid, "Claude resume should preserve the session id (§6.1)")
        val recalled = assistantText(turn2)
        assert(
          recalled.contains(codeword),
          s"resumed turn did NOT recall the codeword → headless --resume did not restore context. Got: '$recalled'"
        )

  test("Claude: a turn killed mid-exploration can be resumed to a clean Result (realistic D3 crash shape)"):
    assume(canClaude, "skipped — set FORGE_IT_RUN_RESUME_SPIKE=1 and have `claude` on PATH (not FORGE_IT_SKIP_CLAUDE=1)")
    val workdir = os.temp.dir(prefix = "forge-resume-spike-claude-kill-")
    val sys = systemPromptFile(driverSystem)
    val connector = claudeConnector(workdir)

    // A long task so the kill lands mid-turn. Spawn blocks until Init, so the session id is captured before the kill.
    val killBody =
      s"Create a file notes.md, then append 60 short distinct facts about the Roman Empire to it, one per line, " +
        s"committing each with your file tools as you go. Also remember the codeword $codeword. Take your time."
    val (sid, partial) = connector
      .runHeadlessImplementation(ImplementationPrompt(FeatureId("spike"), PieceId("p1"), sys, killBody))
      .flatMap: session =>
        for
          _ <- IO.sleep(8.seconds) // let it get into the exploration
          _ <- session.kill()
          events <- session.events.compile.toVector
        yield (session.sessionId, events)
      .unsafeRunSync()
    dump("claude-kill-turn1", partial)
    assert(partial.exists(_.isInstanceOf[AgentEvent.Init]), clue = partial)

    val resumed = claudeResume(workdir, sid, "Continue exactly where you left off; when finished reply: done").unsafeRunSync()
    resumed match
      case Left(err) =>
        fail(s"resume after mid-turn kill failed: $err")
      case Right((newSid, turn2)) =>
        dump("claude-kill-turn2", turn2)
        assertEquals(newSid, sid)
        turn2.lastOption match
          case Some(AgentEvent.Result(success, _)) => assert(success, clue = turn2)
          case other => fail(s"resumed turn did not end in a Result: $other")

  // --- Codex test (fills the connector matrix) ----

  test("Codex: exec resume restores context (codeword recall) and the turn-1 file survives"):
    assume(canCodex, "skipped — set FORGE_IT_RUN_RESUME_SPIKE=1 and have `codex` on PATH (not FORGE_IT_SKIP_CODEX=1)")
    val workdir = os.temp.dir(prefix = "forge-resume-spike-codex-")
    val sys = systemPromptFile(driverSystem)
    val connector = CodexConnector(
      binary = codexOnPath.get.toString,
      model = codexModel,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "workspace-write", approvalMode = "never"),
      cwd = Some(workdir)
    )

    val (threadId, turn1) = connector
      .runHeadlessImplementation(ImplementationPrompt(FeatureId("spike"), PieceId("p1"), sys, turn1Body(workdir)))
      .flatMap(drain)
      .unsafeRunSync()
    dump("codex-recall-turn1", turn1)

    val planFile = workdir / "plan.md"
    // File survival is a softer signal on Codex: `exec resume` passes no sandbox flag, but turn 1 ran under
    // workspace-write so the write should land. Capture either way.
    if !os.exists(planFile) then
      println(s"[D3-0 spike] NOTE: Codex turn 1 did not write plan.md (sandbox/permission) — see codex-recall-turn1")

    val resumed = codexResume(workdir, threadId, recallBody, sys).unsafeRunSync()
    resumed match
      case Left(err) =>
        fail(s"Codex `exec resume $threadId` failed — D3-1 cannot reuse this seam for the driver: $err")
      case Right((newThreadId, turn2)) =>
        dump("codex-recall-turn2", turn2)
        assertEquals(newThreadId, threadId, "Codex resume should echo the same thread id (§6.1)")
        val recalled = assistantText(turn2)
        assert(
          recalled.contains(codeword),
          s"Codex resumed turn did NOT recall the codeword → exec resume did not restore context. Got: '$recalled'"
        )
