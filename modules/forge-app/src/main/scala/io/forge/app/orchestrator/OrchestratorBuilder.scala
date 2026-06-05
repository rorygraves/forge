package io.forge.app.orchestrator

import cats.effect.{Clock, IO}
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.RealSessionMonitor
import io.forge.app.reviewer.{RealReviewerCall, RetryingReviewerCall}
import io.forge.core.{Cli, Mode, RolePairing}
import io.forge.core.log.{ActionLog, FileActionLog}
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.{CommitIdentity, FileProfileStore}
import io.forge.core.state.FileStateCache
import io.forge.git.branch.RealBranchManager
import io.forge.git.branch.protection.InMemoryBranchProtectionCache
import io.forge.git.cli.{RealGhClient, RealGitClient}
import io.forge.git.watcher.{PRWatcherConfig, RealPRWatcher}
import io.forge.specs.{DefaultChangeCollector, FileDocSync, FileSpecStore}

import scala.concurrent.duration.*

/** Task 1.4.10-d2c (J3 `Main` wiring) — constructs a fully-real [[Orchestrator]] for one `forge run` invocation.
  *
  * This is the assembly point where every Slice 1–3 collaborator the loop engine (-d1) drove through the
  * [[SideEffects]] / `SessionMonitor` / `PRWatcher` / `ReviewerCall` seams becomes its production implementation:
  *
  *   - the [[io.forge.agents.Connector]] (J3 — one per `Mode`, built by [[ConnectorFactory]], shared across every
  *     driver call and reviewer one-shot);
  *   - [[RealGitClient]] / [[RealGhClient]] over the repo root, [[RealBranchManager]] over them + an
  *     [[InMemoryBranchProtectionCache]] (on-disk cache persistence is a post-v1 item), and [[RealPRWatcher]] tuned
  *     from the §18 `pollIntervalMs` / `github` block;
  *   - [[RealReviewerCall]] wrapped in [[RetryingReviewerCall]] so §7.6 process-failure retries fire transparently
  *     (S4-5), with the retry budgets drawn from the `claude` vs `codex` §18 block selected by `mode`;
  *   - the §4 `File*` persistence triad ([[FileSpecStore]] / [[FileManifestStore]] / [[FileStateCache]]) + the
  *     append-only [[FileActionLog]], plus [[FileDocSync]] and the [[DefaultChangeCollector]].
  *
  * **`mode` comes from the manifest, not config**, for an existing feature: mode is fixed at `forge new` and persisted
  * (§6 — "mid-feature mode switching is unsupported"), so the caller passes the loaded `manifest.mode`.
  *
  * **Resource lifetime.** The connector holds no long-lived resource of its own (its subprocesses are acquired per-call
  * via `Resource`), so the builder returns a bare `IO[Orchestrator]` rather than a `Resource`; the [[ActionLog]] is
  * returned alongside so `Main` can fold its lifecycle into the same lock bracket if a future increment needs an
  * explicit flush. v1's `FileActionLog` is append-on-write with no buffered handle, so no flush is required today.
  */
object OrchestratorBuilder:

  /** Build the orchestrator + its action log for `mode`. The action log is surfaced so the caller can reuse the same
    * instance for pre-loop bookkeeping (e.g. a `forge new` scaffold entry) without constructing a second handle.
    */
  def build(mode: Mode, paths: ForgePaths, config: ForgeConfig): IO[(Orchestrator, ActionLog)] =
    val pairing = RolePairing.of(mode)
    for
      connector <- ConnectorFactory.build(pairing.driver, paths, config)
      protectionCache <- InMemoryBranchProtectionCache(ttl = config.github.branchProtectionTtlSec.seconds)
      log <- FileActionLog(paths)
      commitIdentity <- resolveCommitIdentity(paths, config)
    yield
      val git = new RealGitClient(paths.repoRoot)
      val gh = new RealGhClient(paths.repoRoot)
      val branchManager = new RealBranchManager(git, gh, protectionCache, Clock[IO])
      val watcher = new RealPRWatcher(gh, watcherConfig(config))
      val specStore = new FileSpecStore(paths)
      val docSync = new FileDocSync(paths, specStore)
      val changeCollector = new DefaultChangeCollector
      val manifestStore = new FileManifestStore(paths)
      val stateCache = new FileStateCache(paths)
      val profileStore = new io.forge.core.profile.FileProfileStore(paths)
      val (reviewRetries, refineRetries) = retryBudgets(pairing, config)
      val reviewer = new RetryingReviewerCall(new RealReviewerCall(connector), reviewRetries, refineRetries)
      val monitor = new RealSessionMonitor
      val sideEffects =
        new RealSideEffects(
          connector,
          branchManager,
          git,
          gh,
          changeCollector,
          specStore,
          docSync,
          paths,
          config,
          commitIdentity
        )
      val orchestrator = new Orchestrator(
        sideEffects,
        monitor,
        watcher,
        reviewer,
        specStore,
        manifestStore,
        log,
        stateCache,
        paths,
        config,
        profileStore = profileStore
      )
      (orchestrator, log)

  /** D4 — resolve the §6.5 `RepoProfile.commitIdentity` once at build time so every Forge commit in the run is authored
    * as that identity (default `forge[bot]`). `None` when `adapt.enabled` is false or the repo is unprofiled (ambient
    * git identity — pre-D4 behaviour). This is a cheap, idempotent profile *read* (no sensor / LLM); it mirrors
    * `Orchestrator.resolveProfile`'s `profileStore.load()`, but is **best-effort** (`.attempt` → `None`): the
    * authoritative §6.5 malformed-`profile.json` error is the orchestrator's to raise, not the builder's, so a bad
    * profile degrades the commit author to ambient rather than failing construction before the orchestrator can report.
    */
  private def resolveCommitIdentity(paths: ForgePaths, config: ForgeConfig): IO[Option[CommitIdentity]] =
    if !config.adapt.enabled then IO.pure(None)
    else new FileProfileStore(paths).load().attempt.map(_.toOption.flatten.map(_.commitIdentity))

  /** §18 → [[PRWatcherConfig]]. `pollIntervalMs` drives the inter-poll cadence; the rate-limit back-off reuses the
    * `github.rateLimitBackoffMs` knob so the watcher and the gh client agree on how long to pause after a 429. A
    * transient `gh` server / network blip (e.g. an HTTP 503) reuses the same back-off — both are "GitHub is momentarily
    * unavailable, pause and retry" intervals (dogfood finding #5).
    */
  private def watcherConfig(config: ForgeConfig): PRWatcherConfig =
    PRWatcherConfig(
      pollInterval = config.pollIntervalMs.millis,
      rateLimitBackoff = config.github.rateLimitBackoffMs.millis,
      transientBackoff = config.github.rateLimitBackoffMs.millis
    )

  /** §7.6 retry budgets, selected by the resolved pairing's driver `Cli` — the Claude block governs a Claude-driver
    * run, the Codex block a Codex-driver run (the reviewer shares the driver's CLI per §7.1 / design-3.5 D1, so one
    * block covers both). Reads the pairing rather than re-matching `Mode` (Task 3.5.2).
    */
  private def retryBudgets(pairing: RolePairing, config: ForgeConfig): (Int, Int) = pairing.driver match
    case Cli.Claude => (config.claude.reviewProcessRetries, config.claude.refineProcessRetries)
    case Cli.Codex => (config.codex.reviewProcessRetries, config.codex.refineProcessRetries)
