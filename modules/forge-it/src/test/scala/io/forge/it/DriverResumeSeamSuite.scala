package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.agents.*
import io.forge.core.{FeatureId, PieceId}

import scala.concurrent.duration.*

/** roadmap §3.5 / [`design-3.5.md`] **D3-1 — headless driver resume connector seam** (real-CLI IT).
  *
  * D3-0 (`DriverResumeSpikeSuite`) proved — against real CLIs — that a headless driver turn resumed from a *fresh
  * process* restores conversation context on **both** connectors, by building the resume argv inline. D3-1 promotes that
  * inline prototype into a first-class connector method, [[Connector.resumeHeadlessDriver]] (`claude -p … --resume <sid>`
  * / `codex exec resume <thread-id>`). This suite is the "fake-CLI must mirror real-CLI" pair for the fake-CLI unit tests
  * in `ClaudeConnectorSuite` / `CodexConnectorSuite`: it drives the **actual method** end to end against the real
  * binaries so the seam is grounded on measured behaviour, not the unit fakes.
  *
  * **What it pins** (beyond the spike, which only built argv inline):
  *   - both connectors' `resumeHeadlessDriver` recall a codeword planted in turn 1 → the method restores context;
  *   - a resumed turn **edits a file** → the realistic D3 shape (resume continues exploration *and writes*), and in
  *     particular **resolves design-rationale C19 watch item (1)**: Codex's `execResumeArgv` carries **no** `--sandbox`
  *     flag (§7.10(c) — resume rejects session-scoped flags), and the D3-0 spike's resumed turn was read-only, so
  *     whether a *resumed* Codex turn can still write was untested. This suite makes it write and asserts the file.
  *
  * **Opt-in by default** (CLAUDE.md "default-on test runtime <60s"): two real driver turns per test cost real
  * tokens/minutes, so the whole suite is gated behind `FORGE_IT_RUN_RESUME_SEAM=1` on top of the usual PATH probe +
  * `FORGE_IT_SKIP_CLAUDE` / `FORGE_IT_SKIP_CODEX` escape hatches.
  */
class DriverResumeSeamSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 10.minutes

  // --- gating ----

  private def onPath(bin: String): Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / bin) => p / bin
    }

  private val runSeam = sys.env.get("FORGE_IT_RUN_RESUME_SEAM").contains("1")
  private val claudeOnPath = onPath("claude")
  private val codexOnPath = onPath("codex")
  private val canClaude = runSeam && claudeOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CLAUDE").contains("1")
  private val canCodex = runSeam && codexOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")

  private val codexModel = sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")
  private val codeword = "ZARQ-9173-WIDGET"

  // --- helpers ----

  private def systemPromptFile(content: String): os.Path =
    os.temp(contents = content, prefix = "forge-resume-seam-system-", suffix = ".md", deleteOnExit = true)

  private def loadPriceTable: PriceTable =
    val stream = getClass.getResourceAsStream("/prices.example.json")
    require(stream != null, "prices.example.json missing from classpath")
    try upickle.default.read[PriceTable](scala.io.Source.fromInputStream(stream)("UTF-8").mkString)
    finally stream.close()

  /** Drain a headless one-shot session to completion (drain-first is safe — `-p` / `exec` reads no further stdin and the
    * CLI exits on its own after the turn).
    */
  private def drain(session: AgentSession): IO[(String, Vector[AgentEvent])] =
    for
      events <- session.events.compile.toVector
      _ <- session.close()
    yield (session.sessionId, events)

  private val driverSystem =
    "You are a file-editing software agent. Use your file tools to make the requested edits. Be concise."

  /** Codex `exec` refuses to run in an untrusted, non-git directory; real driver runs are inside the repo worktree, so
    * mirror that here (D3-0 / C19 watch item (2)).
    */
  private def gitInit(dir: os.Path): Unit =
    def run(args: String*): Unit = { val _ = os.proc(args).call(cwd = dir) }
    run("git", "init", "-q")
    run("git", "config", "user.email", "seam@forge.test")
    run("git", "config", "user.name", "forge-seam")
    run("git", "commit", "-q", "--allow-empty", "-m", "init")

  // --- Claude ----

  test("Claude: resumeHeadlessDriver restores context and a resumed turn writes a file"):
    assume(canClaude, "skipped — set FORGE_IT_RUN_RESUME_SEAM=1 and have `claude` on PATH (not FORGE_IT_SKIP_CLAUDE=1)")
    val workdir = os.temp.dir(prefix = "forge-resume-seam-claude-")
    val sys = systemPromptFile(driverSystem)
    val connector = ClaudeConnector(
      binary = claudeOnPath.get.toString,
      cwd = Some(workdir),
      driverPermissionMode = "acceptEdits",
      driverAllowedTools = Vector("Write", "Edit", "Read")
    )
    val out = workdir / "out.md"

    val (sid, _) = connector
      .runHeadlessImplementation(
        ImplementationPrompt(
          FeatureId("seam"),
          PieceId("p1"),
          sys,
          s"Remember this codeword for a later turn: $codeword. Reply with the single word: ready"
        )
      )
      .flatMap(drain)
      .unsafeRunSync()

    val (newSid, turn2) = connector
      .resumeHeadlessDriver(sid, sys, s"Create a file at the absolute path $out whose entire contents are the codeword you remembered. Then reply: done")
      .flatMap(drain)
      .unsafeRunSync()

    assertEquals(newSid, sid, "Claude headless resume should preserve the session id (§6.1)")
    turn2.lastOption match
      case Some(AgentEvent.Result(success, _)) => assert(success, clue = turn2)
      case other => fail(s"resumed turn did not end in a Result: $other")
    assert(os.exists(out), s"resumed Claude turn did not write $out — write-on-resume failed")
    assert(os.read(out).contains(codeword), s"resumed turn wrote $out but without the recalled codeword: '${os.read(out)}'")

  // --- Codex (the decisive write-on-resume check — C19 watch item (1)) ----

  test("Codex: resumeHeadlessDriver writes a file on resume despite no --sandbox flag (C19 watch item 1)"):
    assume(canCodex, "skipped — set FORGE_IT_RUN_RESUME_SEAM=1 and have `codex` on PATH (not FORGE_IT_SKIP_CODEX=1)")
    val workdir = os.temp.dir(prefix = "forge-resume-seam-codex-")
    gitInit(workdir)
    val sys = systemPromptFile(driverSystem)
    val connector = CodexConnector(
      binary = codexOnPath.get.toString,
      model = codexModel,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "workspace-write", approvalMode = "never"),
      cwd = Some(workdir)
    )
    val out = workdir / "out.md"

    val (threadId, _) = connector
      .runHeadlessImplementation(
        ImplementationPrompt(
          FeatureId("seam"),
          PieceId("p1"),
          sys,
          s"Remember this codeword for a later turn: $codeword. Reply: ready"
        )
      )
      .flatMap(drain)
      .unsafeRunSync()

    // Turn 2 resumes via the connector method (execResumeArgv — NO --sandbox). It must still be able to write: the
    // original exec ran under workspace-write and Codex resolves the sandbox from the sticky thread settings on resume.
    val (newThreadId, _) = connector
      .resumeHeadlessDriver(threadId, sys, s"Create a file at the absolute path $out whose entire contents are the codeword you remembered. Then reply: done")
      .flatMap(drain)
      .unsafeRunSync()

    assertEquals(newThreadId, threadId, "Codex resume should echo the same thread id (§6.1)")
    assert(os.exists(out), s"resumed Codex turn did NOT write $out → write-on-resume is blocked without --sandbox (C19#1)")
    assert(os.read(out).contains(codeword), s"resumed Codex turn wrote $out but without the recalled codeword: '${os.read(out)}'")
