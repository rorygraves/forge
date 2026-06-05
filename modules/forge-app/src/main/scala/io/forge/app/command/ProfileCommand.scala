package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.agents.{RepoFile, RepoProfilerInput}
import io.forge.app.config.ForgeConfig
import io.forge.app.orchestrator.ConnectorFactory
import io.forge.app.reviewer.{RealReviewerCall, ReviewerCall, ReviewerLimits, ReviewerOutcome}
import io.forge.core.RolePairing
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.{Determinism, FileProfileStore, ProfileStore, RepoProfile}

import scala.concurrent.duration.*

/** Task 3.0.3 — `forge profile`: the §7.11 `RepoProfiler` sensor as an out-of-band command. Reads the repo's own
  * documents + build/CI config, asks the **reviewer-side** connector for `config.mode` (under the same-CLI story this
  * is the mode's own connector, used for a reviewer one-shot — design-3.5 D1; the resolved [[RolePairing.reviewer]]
  * `Cli`) to perceive them into a [[RepoProfile]], and writes the result to the committed `.forge/profile.json` (via
  * [[FileProfileStore]]) for human review before first use (it lands in a normal diff).
  *
  * The body is split into an injectable [[run]] taking a [[ReviewerCall]] + [[ProfileStore]] so the perception →
  * persist → render pipeline is unit-testable without a real CLI; [[execute]] wires the real connector + store. A live
  * Claude/Codex capture is exercised opt-in in `forge-it`.
  *
  * **Determinism note (A5):** producing the profile is the *agentic* step (a sensor); everything downstream consumes it
  * deterministically (§11.0). This command never auto-runs mid-feature — it is the explicit operator action that
  * populates the profile a later `forge run` binds to.
  */
object ProfileCommand:

  /** Default wall-clock cap for the profiling one-shot — the §18 `reviewer.wallClockCapSec` default (180s = 3 min);
    * profiling is a single reviewer-side call, so it follows the reviewer cap. `execute` overrides it with the resolved
    * `config.reviewer.wallClockCapSec` (S4-5 closed); `run`'s default keeps test callers cap-agnostic.
    */
  private val ProfileCap: FiniteDuration = 180.seconds

  /** Per-file content cap so a large `CLAUDE.md` or generated build file can't blow the prompt. Truncated files carry a
    * trailing marker so the sensor can tell a cut from the real end.
    */
  private val MaxFileChars: Int = 16384

  /** Top-level build files worth showing the sensor, in probe order. Only the ones that exist are read. */
  private val BuildFileCandidates: Vector[String] = Vector(
    "build.sbt",
    "package.json",
    "Cargo.toml",
    "pom.xml",
    "build.gradle",
    "build.gradle.kts",
    "go.mod",
    "pyproject.toml",
    "requirements.txt",
    "Makefile"
  )

  def execute(paths: ForgePaths, config: ForgeConfig): IO[ExitCode] =
    buildReviewerCall(paths, config).flatMap(rc =>
      run(paths, rc, new FileProfileStore(paths), config.reviewer.wallClockCapSec.seconds)
    )

  /** Injectable body: gather the repo inputs, run the sensor under the wall-clock cap, persist + render. */
  def run(
      paths: ForgePaths,
      reviewer: ReviewerCall,
      store: ProfileStore,
      cap: FiniteDuration = ProfileCap
  ): IO[ExitCode] =
    gatherInput(paths).flatMap { input =>
      reviewer.profileRepo(input, ReviewerLimits(cap)).flatMap {
        case ReviewerOutcome.Settled(profile) =>
          store.save(profile) >> Console[IO].println(summary(paths, profile)).as(ExitCode.Success)
        case ReviewerOutcome.Timeout =>
          Console[IO]
            .errorln(
              s"forge profile: the profiler did not settle within ${cap.toSeconds}s — no profile written."
            )
            .as(ExitCode(1))
        case ReviewerOutcome.AdapterFailure(err) =>
          Console[IO]
            .errorln(s"forge profile: the profiler failed (${err.getMessage}) — no profile written.")
            .as(ExitCode(1))
      }
    }

  /** Build the reviewer-side connector for `config.mode` and wrap it in the wall-clock boundary. Under the same-CLI
    * story (design-3.5 D1) the reviewer is the mode's own connector, so we resolve the pairing once and build the
    * single connector its [[RolePairing.reviewer]] names — no longer constructing both CLIs to pick the non-driver.
    */
  private def buildReviewerCall(paths: ForgePaths, config: ForgeConfig): IO[ReviewerCall] =
    ConnectorFactory
      .build(RolePairing.of(config.mode).reviewer, paths, config)
      .map(connector => new RealReviewerCall(connector))

  /** Read the repo facts the §7.11 `RepoProfiler` perceives: the agent docs, top-level build files, and CI workflow
    * files. All reads are best-effort — a missing file is simply absent from the input.
    */
  private[command] def gatherInput(paths: ForgePaths): IO[RepoProfilerInput] =
    IO.blocking {
      val root = paths.repoRoot
      RepoProfilerInput(
        repoName = root.last,
        agentsDoc = readOpt(root / "AGENTS.md"),
        claudeDoc = readOpt(root / "CLAUDE.md"),
        buildFiles = BuildFileCandidates.flatMap(leaf => readFile(root / leaf, leaf)),
        workflowFiles = workflowFiles(root)
      )
    }

  private def workflowFiles(root: os.Path): Vector[RepoFile] =
    val dir = root / ".github" / "workflows"
    if !os.exists(dir) || !os.isDir(dir) then Vector.empty
    else
      os.list(dir)
        .filter(p => os.isFile(p) && (p.last.endsWith(".yml") || p.last.endsWith(".yaml")))
        .toVector
        .sortBy(_.last)
        .flatMap(p => readFile(p, s".github/workflows/${p.last}"))

  private def readOpt(p: os.Path): Option[String] =
    if os.exists(p) && os.isFile(p) then Some(cap(os.read(p))) else None

  private def readFile(p: os.Path, relPath: String): Option[RepoFile] =
    if os.exists(p) && os.isFile(p) then Some(RepoFile(relPath, cap(os.read(p)))) else None

  private def cap(content: String): String =
    if content.length <= MaxFileChars then content
    else content.take(MaxFileChars) + "\n… (truncated)"

  private def summary(paths: ForgePaths, profile: RepoProfile): String =
    val autofixable = profile.commands.count(c => c.autofix && c.determinism == Determinism.Deterministic)
    val cmdLines =
      if profile.commands.isEmpty then "  (none)"
      else
        profile.commands
          .map { c =>
            val tier = if c.autofix && c.determinism == Determinism.Deterministic then " [local autofix]" else ""
            s"  - ${c.kind.asString}: ${c.argv.mkString(" ")}$tier"
          }
          .mkString("\n")
    s"""forge profile: wrote ${paths.profileFile} (hash ${profile.contentHash}).
       |  build tool: ${profile.buildTool}
       |  merge strategy: ${profile.workflow.mergeStrategy.toString.toLowerCase}
       |  required CI checks: ${ciChecks(profile)}
       |  commands ($autofixable safe for local autofix):
       |$cmdLines
       |
       |Review the committed .forge/profile.json before the next `forge run`.""".stripMargin

  private def ciChecks(profile: RepoProfile): String =
    val checks = profile.workflow.ciRequiredChecks
    if checks.isEmpty then "(none)" else checks.mkString(", ")
