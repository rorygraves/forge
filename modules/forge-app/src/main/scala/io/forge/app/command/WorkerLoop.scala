package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.bootstrap.AssetInstaller
import io.forge.app.config.ForgeConfig
import io.forge.app.orchestrator.OrchestratorBuilder
import io.forge.core.FeatureId
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths

/** Task 4.2.3 — the worker's body: run the frozen v1 feature loop over the isolated clone and export its feed (B3).
  *
  * The composition the daemon-spawned `forge worker` process runs once it has resolved its re-rooted clone
  * [[ForgePaths]] (the [[WorkerProvisioner]] / supervisor-assigned checkout, §4.3 / B1):
  *
  *   1. **register** with the daemon ([[WorkerReporter.register]]) so the instance log carries the worker before any
  *      event;
  *   1. **load the manifest** for the assigned feature — a clone with no designed feature degrades to a terminal status
  *      + exit (the [[io.forge.app.command.RunFeature]] contract), reachable without a connector or real CLIs;
  *   1. **install the reviewer assets** the connector reads (idempotent first-run install under `~/.forge`, the §step-5
  *      `Main` install the worker entrypoint skips at the routing layer);
  *   1. **build the real [[io.forge.app.orchestrator.Orchestrator]]** over the clone paths + config and `orch.run` it,
  *      with the [[WorkerFeedExporter]] tailing the worker's own per-feature log concurrently and pushing each appended
  *      `Action` as a `worker-event` + a `worker-status` on each FSM-state change (the worker is the **sole writer** of
  *      its log, §6.3.1; the export is a read-side tail);
  *   1. **flush + report the terminal state** — a final drain catches anything written between the last tick and
  *      completion, then an explicit `worker-status` names the terminal FSM state.
  *
  * The real tie-together (the daemon spawning this as a child process on a freshly-provisioned clone, driving a feature
  * to a terminal state, surviving a daemon restart) is proven **live** in the Task 4.2.6 dogfood; the feed exporter and
  * the reporter are the unit-tested seams ([[WorkerFeedExporterSuite]]).
  */
object WorkerLoop:

  /** Run the worker loop for `feature` over the clone `paths`, exporting its feed to `reporter`. `repo` is the
    * registered repo label carried into the worker record at registration.
    */
  def run(
      paths: ForgePaths,
      config: ForgeConfig,
      reporter: WorkerReporter,
      repo: String,
      feature: FeatureId
  ): IO[ExitCode] =
    reporter.register(repo, feature) *>
      new FileManifestStore(paths).load(feature).flatMap {
        case Left(failure) => noManifest(reporter, feature, failure)
        case Right(manifest) => drive(paths, config, reporter, feature, manifest.mode)
      }

  /** A clone with no designed feature: report the loop-terminal `Abandoned`-shaped status and exit 1. The worker has
    * nothing to drive (the supervisor assigned a feature that was never designed in this clone); surfacing it as a
    * status keeps the instance-log view consistent rather than crashing.
    */
  private def noManifest(
      reporter: WorkerReporter,
      feature: FeatureId,
      failure: io.forge.core.state.RebuildError.ManifestLoadFailed
  ): IO[ExitCode] =
    reporter.status("Abandoned") *>
      Console[IO]
        .errorln(
          s"forge worker ${feature.value}: no manifest in the clone — ${failure.cause.getMessage}. " +
            s"The assigned feature has not been designed in this checkout."
        )
        .as(ExitCode(1))

  /** Build + run the orchestrator with the feed exporter tailing concurrently, then flush + report the terminal state.
    */
  private def drive(
      paths: ForgePaths,
      config: ForgeConfig,
      reporter: WorkerReporter,
      feature: FeatureId,
      mode: io.forge.core.Mode
  ): IO[ExitCode] =
    installAssets(paths) *>
      OrchestratorBuilder.build(mode, paths, config).flatMap { case (orch, _) =>
        val logFile = paths.featureLog(feature)
        for
          watermark <- cats.effect.Ref.of[IO, Long](WorkerFeedExporter.InitialWatermark)
          terminal <- WorkerFeedExporter
            .follow(logFile, reporter, watermark)
            .background
            .use(_ => orch.run(feature))
          _ <- WorkerFeedExporter.drainOnce(logFile, reporter, watermark)
          _ <- reporter.status(WorkerFeedExporter.fsmStateName(terminal.state))
          rendered = TerminalReport.render(terminal)
          _ <-
            if rendered.exitCode == ExitCode.Success then Console[IO].println(rendered.message)
            else Console[IO].errorln(rendered.message)
        yield rendered.exitCode
      }

  /** First-run install of the reviewer assets the connector reads from `~/.forge/{schemas,prompts,templates}`
    * (idempotent). A failure degrades to a logged warning rather than aborting — the orchestrator will surface the
    * missing-schema error with full context if the connector actually needs them.
    */
  private def installAssets(paths: ForgePaths): IO[Unit] =
    AssetInstaller.installIfMissing(paths).flatMap {
      case Right(_) => IO.unit
      case Left(err) => Console[IO].errorln(s"forge worker: reviewer asset install failed: ${err.detail}")
    }
