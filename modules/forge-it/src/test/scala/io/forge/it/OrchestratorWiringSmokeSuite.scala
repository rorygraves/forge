package io.forge.it

import cats.effect.unsafe.implicits.global
import io.forge.app.bootstrap.AssetInstaller
import io.forge.app.config.ForgeConfig
import io.forge.app.orchestrator.OrchestratorBuilder
import io.forge.core.Mode
import io.forge.core.paths.ForgePaths

/** Task 1.4.10-d2c — opt-in **construction smoke** for the production `forge run` wiring in a real filesystem.
  *
  * Proves the real install → build path end-to-end: [[AssetInstaller]] copies the shipped reviewer + driver assets into
  * a throwaway `~/.forge`, then [[OrchestratorBuilder]] assembles a fully-real [[io.forge.app.orchestrator.Orchestrator]]
  * for both `Mode`s (so [[io.forge.app.orchestrator.ConnectorFactory]] resolves the just-installed asset paths and the
  * Codex branch loads its price table). It deliberately does **not** drive a feature through real `claude` / `codex` /
  * `git` / `gh` — that is the Task 1.4.16 MVP gate. The terminal-feature drive over a fully-real orchestrator is covered
  * (CLI-free) by `forge-app`'s `OrchestratorBuilderSuite`; this suite adds the real-`~/.forge` integration delta.
  *
  * **Opt-in by default** (<60s budget, and to avoid writing a throwaway `~/.forge` on every `forge-it` run): runs only
  * when `FORGE_IT_RUN_WIRING_SMOKE=1`. Needs no GitHub repo and no CLI on PATH — connector subprocesses are acquired
  * per-call, and no call is made here.
  */
class OrchestratorWiringSmokeSuite extends munit.FunSuite:

  private val optIn: Boolean = sys.env.get("FORGE_IT_RUN_WIRING_SMOKE").contains("1")

  test("AssetInstaller → OrchestratorBuilder constructs a real orchestrator for both modes"):
    assume(optIn, "skipped — set FORGE_IT_RUN_WIRING_SMOKE=1 (cheap, no GitHub / CLI needed)")

    val home = os.temp.dir(prefix = "forge-it-wiring-home-", deleteOnExit = true)
    val repoRoot = os.temp.dir(prefix = "forge-it-wiring-repo-", deleteOnExit = true)
    val paths = ForgePaths(repoRoot = repoRoot, home = home)

    // 1. Real first-run asset install into the throwaway ~/.forge.
    AssetInstaller.installIfMissing(paths).unsafeRunSync() match
      case Left(err) => fail(s"AssetInstaller failed: $err")
      case Right(installed) => assert(installed.nonEmpty, "expected assets to be installed on first run")

    // The asset paths ConnectorFactory binds must now exist on disk.
    assert(os.exists(paths.userSchemasDir / "design-review.json"), "design-review schema not installed")
    for cli <- Vector("claude", "codex"); method <- Vector("design-review", "code-review", "refine") do
      assert(os.exists(paths.userPromptsDir / s"$method.$cli.md"), s"prompt $method.$cli.md not installed")

    // 2. Build a fully-real orchestrator for each mode — exercises ConnectorFactory (incl. the Codex price-table load)
    //    and the whole git/gh/watcher/reviewer assembly. Construction succeeding is the assertion.
    for mode <- Vector(Mode.ClaudeDriver, Mode.CodexDriver) do
      val built = OrchestratorBuilder.build(mode, paths, ForgeConfig.Default).attempt.unsafeRunSync()
      built match
        case Left(err) => fail(s"OrchestratorBuilder.build($mode) raised: $err")
        case Right((orch, _)) => assert(orch != null, s"null orchestrator for $mode")
