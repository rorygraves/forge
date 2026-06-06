package io.forge.app

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import io.forge.app.bootstrap.AssetInstaller
import io.forge.app.cli.{CliError, CliParser, CommandClass, Invocation}
import io.forge.app.command.{
  unlock,
  CommandRouter,
  DaemonCommands,
  InstanceCommands,
  InstancePaths,
  ReadOnlyContext,
  StateChangingContext,
  UnlockForceContext,
  WorkerCommands
}
import io.forge.app.config.{ConfigError, ForgeConfig, ForgeConfigLoader}
import io.forge.app.lock.{FileProcessLock, LockAcquireResult, LockMetadata}
import io.forge.core.{FeatureId, InstanceName}
import io.forge.core.paths.ForgePaths
import io.forge.git.branch.ForgeCommand
import io.forge.instance.InstanceError

import java.net.InetAddress
import java.time.Instant
import scala.util.control.NonFatal

/** Task 1.4.9 I2 — the `forge` entry point. Routes the §17 command set (`new | spec | run | status | resume | reconcile
  * \| refresh-cache | abandon | rebuild-state | unlock --force | tail | stats | tui`) through a **two-phase boot** so
  * each command only pays for the resources it needs:
  *
  *   1. **Phase-1 argv parse** ([[CliParser.phase1]]) — global `--repo-root` + command *class*, no per-feature args. 2.
  *      **Resolve `repoRoot`** (parsed flag or `os.pwd`) and build [[ForgePaths]]; existence-check only. 3. **`unlock
  *      --force` short-circuit** — recovery must work even when `config.json` is missing/corrupt, so this command loads
  *      no config, installs no assets, constructs no connector, and acquires no lock. 4. **Load [[ForgeConfig]]** for
  *      every other command. 5. **Install reviewer assets** ([[AssetInstaller.installIfMissing]]) for connector-bound
  *      commands (`Invocation.needsConnector`) so the connector finds its `~/.forge/{schemas,prompts}` on first run;
  *      idempotent. 6. **Construct the connector** — built on demand inside the orchestrator handlers
  *      ([[io.forge.app.orchestrator.OrchestratorBuilder]]) under the lock bracket. 7. **Acquire the process lock** for
  *      state-changing commands (§15); read-only commands never lock. 8. **Phase-2 parse** ([[CliParser.phase2]]) into
  *      a concrete [[ForgeCommand]] — run *before* lock acquisition here so a usage error never grabs the lock and the
  *      parsed feature id is available for the lock metadata. 9. **Dispatch** via [[CommandRouter]]. 10. **On exit**
  *      the `Resource` bracket releases the lock. Task 1.4.10 adds the connector + action log to the same bracket
  *      (log-flush → connector-close → lock-release ordering).
  *
  * Exit codes: `0` success; `2` lock held / stale (§13); `64` usage error (`EX_USAGE`); `66` bad `--repo-root`; `70`
  * not-yet-implemented shell; `78` config error (`EX_CONFIG`).
  *
  * Extends [[IOApp]] (not `IOApp.Simple`, as the design-1.4 sketch read) because the boot sequence needs both the argv
  * list and a non-zero [[ExitCode]] — neither of which `IOApp.Simple` exposes.
  */
object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    CliParser.phase1(args) match
      case Left(err) => usageError(err)
      case Right(invocation) =>
        resolveRepoRoot(invocation).flatMap {
          case Left(code) => IO.pure(code)
          case Right(paths) => dispatch(invocation, paths)
        }

  private def dispatch(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    invocation.commandClass match
      case CommandClass.UnlockForce => runUnlock(invocation, paths)
      case CommandClass.ReadOnly => runReadOnly(invocation, paths)
      case CommandClass.StateChanging => runStateChanging(invocation, paths)
      case CommandClass.Instance => runInstance(invocation, paths)
      case CommandClass.Daemon => runDaemon(invocation, paths)
      case CommandClass.Worker => runWorker(invocation, paths)

  // --- step 2: repo-root ----------------------------------------------------

  private def resolveRepoRoot(invocation: Invocation): IO[Either[ExitCode, ForgePaths]] =
    IO.blocking {
      try
        val root = invocation.repoRoot.map(os.Path(_, os.pwd)).getOrElse(os.pwd)
        if os.exists(root) && os.isDir(root) then Right(new ForgePaths(root))
        else Left(s"--repo-root '$root' does not exist or is not a directory")
      catch case NonFatal(t) => Left(s"invalid --repo-root: ${t.getMessage}")
    }.flatMap {
      case Right(paths) => IO.pure(Right(paths))
      case Left(message) => Console[IO].errorln(s"forge: $message").as(Left(ExitCode(66)))
    }

  // --- step 3: unlock --force (no config, no lock) --------------------------

  private def runUnlock(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    CliParser.phase2(invocation.name, invocation.rest) match
      case Left(err) => usageError(err)
      case Right(_) => unlock.run(UnlockForceContext(paths))

  // --- instance: no config, no per-checkout lock ----------------------------

  /** Phase-4 instance-registry commands (`init-instance` / `add-repo` / `list-repos`, Task 4.0.3). Instance-scoped, so
    * they load no config, install no assets, and never take the per-checkout lock — only `paths.home` (the `~/.forge`
    * anchor) matters; `repoRoot` is irrelevant (it defaulted to `os.pwd` in step 2 and is unused here). The mutating
    * commands take an instance-level lock inside [[InstanceCommands]].
    */
  private def runInstance(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    CliParser.parseInstance(invocation.name, invocation.rest) match
      case Left(err) => usageError(err)
      case Right(command) => InstanceCommands.run(paths.home, command)

  // --- daemon: no config, no per-checkout lock ------------------------------

  /** Phase-4 daemon supervisor commands (`daemon start | stop | status`, Task 4.1.3). Instance-scoped like the registry
    * commands: no config, no assets, no per-checkout lock — `start` takes the *instance* lock inside
    * [[DaemonCommands]]; `stop` / `status` are JSON-RPC client calls. Only `paths.home` matters; `repoRoot` is
    * irrelevant.
    */
  private def runDaemon(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    CliParser.parseDaemon(invocation.rest) match
      case Left(err) => usageError(err)
      case Right(command) => DaemonCommands.run(paths.home, command)

  // --- worker: hidden, daemon-spawned (Task 4.2.1) --------------------------

  /** Phase-4 hidden worker entrypoint (`forge worker`, Slice 4.2). Instance-scoped like the daemon commands at the
    * routing layer — no config / assets / per-checkout lock here (the real feature loop in 4.2.3 takes its own
    * re-rooted per-worker lock); only `paths.home` matters. For the 4.2.1 spike the handler phones home to the instance
    * socket and exits.
    */
  private def runWorker(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    CliParser.parseWorker(invocation.rest) match
      case Left(err) => usageError(err)
      case Right(command) => WorkerCommands.run(paths.home, command)

  // --- read-only: config, no lock -------------------------------------------

  private def runReadOnly(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    loadConfig(paths).flatMap {
      case Left(code) => IO.pure(code)
      case Right(config) =>
        CliParser.extractInstance(invocation.rest) match
          case Left(err) => usageError(err)
          case Right((instance, rest)) =>
            CliParser.phase2(invocation.name, rest) match
              case Left(err) => usageError(err)
              case Right(command: ForgeCommand.ReadOnly) =>
                // Re-root the local-runtime family (log/state) under the instance worker dir when one is in play (B1,
                // Task 4.0.4) so `tail` / `rebuild-state` / `stats` read the same files a re-rooted `run` wrote. The
                // feature comes from the cleaned positional args (read-only commands carry it on `rest`, not the
                // `ForgeCommand`); a feature-less overview (`status`) leaves the paths repo-local.
                val feature = CliParser.optionalFeature(rest).toOption.flatten
                resolveLocalPaths(paths, instance, feature).flatMap {
                  case Left(code) => IO.pure(code)
                  case Right(localPaths) =>
                    CommandRouter.readOnly(command, ReadOnlyContext(localPaths, config, rest))
                }
              case Right(other) =>
                IO.raiseError(
                  new IllegalStateException(s"read-only class produced non-read-only command '${other.name}'")
                )
    }

  // --- state-changing: config + lock ----------------------------------------

  private def runStateChanging(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    loadConfig(paths).flatMap {
      case Left(code) => IO.pure(code)
      case Right(config) =>
        installAssetsIfNeeded(invocation, paths).flatMap {
          case Left(code) => IO.pure(code)
          case Right(()) => runStateChangingWith(invocation, paths, config)
        }
    }

  /** Step 5 — first-run install of the reviewer assets the connector reads from `~/.forge/{schemas,prompts,templates}`.
    * Only connector-bound commands (`needsConnector`) pay for it; idempotent (existing files are skipped). A failure is
    * a config-class error (`78`) — the connector cannot bind its reviewer schemas/prompts without these files.
    */
  private def installAssetsIfNeeded(invocation: Invocation, paths: ForgePaths): IO[Either[ExitCode, Unit]] =
    if !invocation.needsConnector then IO.pure(Right(()))
    else
      AssetInstaller.installIfMissing(paths).flatMap {
        case Left(err) =>
          Console[IO].errorln(s"forge: reviewer asset install failed: ${err.detail}").as(Left(ExitCode(78)))
        case Right(assets) =>
          val installed = assets.count(_.action == AssetInstaller.Action.Installed)
          val note =
            if installed > 0 then
              Console[IO].errorln(s"forge: installed $installed reviewer asset(s) under ${paths.userForgeDir}")
            else IO.unit
          note.as(Right(()))
      }

  private def runStateChangingWith(invocation: Invocation, paths: ForgePaths, config: ForgeConfig): IO[ExitCode] =
    CliParser.extractInstance(invocation.rest) match
      case Left(err) => usageError(err)
      case Right((instance, rest)) =>
        CliParser.phase2(invocation.name, rest) match
          case Left(err) => usageError(err)
          case Right(command) =>
            // Re-root the local-runtime family (log/state/lock) under the instance worker dir when one is in play (B1,
            // Task 4.0.4): the committed family (specs/config) stays at `repoRoot/.forge`, only the gitignored runtime
            // moves. The lock itself is taken on the *re-rooted* paths, so two instances driving the same checkout
            // serialize per-worker, not per-checkout. A feature-less command (`profile`) is never re-rooted.
            resolveLocalPaths(paths, instance, CliParser.featureOf(command)).flatMap {
              case Left(code) => IO.pure(code)
              case Right(localPaths) => stateChangingUnderLock(command, localPaths, config, rest)
            }

  /** Acquire the §13 process lock on `paths` and run the state-changing handler inside the lock bracket. Reviewer
    * assets are installed earlier (step 5, `installAssetsIfNeeded`); the connector + action log are constructed on
    * demand inside the orchestrator handlers (`OrchestratorBuilder`), which run under this lock.
    */
  private def stateChangingUnderLock(
      command: ForgeCommand,
      paths: ForgePaths,
      config: ForgeConfig,
      rest: Vector[String]
  ): IO[ExitCode] =
    lockMetadata(command).flatMap { metadata =>
      new FileProcessLock(paths).acquire(metadata, acceptStale = false).use {
        case LockAcquireResult.Acquired =>
          CommandRouter.stateChanging(command, StateChangingContext(paths, config, rest))
        case LockAcquireResult.Held(holder) =>
          Console[IO].errorln("forge: another process holds the lock." + holderSuffix(holder)).as(ExitCode(2))
        case LockAcquireResult.Stale(holder) =>
          Console[IO]
            .errorln(
              "forge: a stale lock from a prior crashed run is present." + holderSuffix(Some(holder)) +
                " Run `forge unlock --force` to clear it."
            )
            .as(ExitCode(2))
      }
    }

  // --- instance local-runtime re-root (B1, Task 4.0.4) ----------------------

  /** Resolve the B1 local-runtime root for a feature command via [[InstancePaths]] and translate its outcome into the
    * `Main` boundary: a re-root prints a one-line stderr note (so the operator sees where log/state/lock landed) and
    * yields the re-rooted paths; the v1 case yields the base paths untouched; an explicit `--instance` that names a
    * missing instance is an instance-class failure (exit 1), the same code [[InstanceCommands]] uses.
    */
  private def resolveLocalPaths(
      base: ForgePaths,
      instance: Option[InstanceName],
      feature: Option[FeatureId]
  ): IO[Either[ExitCode, ForgePaths]] =
    InstancePaths.resolve(base, instance, feature).flatMap {
      case Right(InstancePaths.Resolved(paths, Some(inst))) =>
        Console[IO]
          .errorln(s"forge: instance '${inst.name.value}' — local runtime under ${paths.localForgeDir}")
          .as(Right(paths))
      case Right(InstancePaths.Resolved(paths, None)) => IO.pure(Right(paths))
      case Left(InstanceError.NoSuchInstance(name)) =>
        Console[IO]
          .errorln(s"forge: no such instance '${name.value}' (create it with `forge init-instance ${name.value}`)")
          .as(Left(ExitCode(1)))
      case Left(err) =>
        Console[IO].errorln(s"forge: instance error: $err").as(Left(ExitCode(1)))
    }

  private def loadConfig(paths: ForgePaths): IO[Either[ExitCode, ForgeConfig]] =
    ForgeConfigLoader.load(paths).flatMap {
      case Right(config) => IO.pure(Right(config))
      case Left(err) => Console[IO].errorln(configErrorMessage(err)).as(Left(ExitCode(78)))
    }

  private def lockMetadata(command: ForgeCommand): IO[LockMetadata] =
    IO.blocking {
      val hostname =
        try InetAddress.getLocalHost.getHostName
        catch case NonFatal(_) => "unknown"
      LockMetadata(
        pid = ProcessHandle.current().pid(),
        hostname = hostname,
        startedAt = Instant.now(),
        command = s"forge ${command.name}",
        feature = CliParser.featureOf(command)
      )
    }

  private def configErrorMessage(err: ConfigError): String = err match
    case ConfigError.Malformed(path, cause) => s"forge: malformed config at $path: ${cause.getMessage}"
    case ConfigError.IoFailure(path, cause) => s"forge: could not read config at $path: ${cause.getMessage}"

  private def holderSuffix(meta: Option[LockMetadata]): String = meta match
    case Some(m) => s" Holder: pid=${m.pid} host=${m.hostname} command='${m.command}' started=${m.startedAt}."
    case None => ""

  private def usageError(err: CliError): IO[ExitCode] =
    Console[IO].errorln(s"forge: ${err.message}").as(ExitCode(64))
