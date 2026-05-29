package io.forge.app

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import io.forge.app.cli.{CliError, CliParser, CommandClass, Invocation}
import io.forge.app.command.{unlock, CommandRouter, ReadOnlyContext, StateChangingContext, UnlockForceContext}
import io.forge.app.config.{ConfigError, ForgeConfig, ForgeConfigLoader}
import io.forge.app.lock.{FileProcessLock, LockAcquireResult, LockMetadata}
import io.forge.core.paths.ForgePaths
import io.forge.git.branch.ForgeCommand

import java.net.InetAddress
import java.time.Instant
import scala.util.control.NonFatal

/** Task 1.4.9 I2 — the `forge` entry point. Routes the §17 command set (`new | spec | run | status | resume | reconcile
  * \| refresh-cache | abandon | rebuild-state | unlock --force | tail`) through a **two-phase boot** so each command
  * only pays for the resources it needs:
  *
  *   1. **Phase-1 argv parse** ([[CliParser.phase1]]) — global `--repo-root` + command *class*, no per-feature args. 2.
  *      **Resolve `repoRoot`** (parsed flag or `os.pwd`) and build [[ForgePaths]]; existence-check only. 3. **`unlock
  *      --force` short-circuit** — recovery must work even when `config.json` is missing/corrupt, so this command loads
  *      no config, installs no assets, constructs no connector, and acquires no lock. 4. **Load [[ForgeConfig]]** for
  *      every other command. 5–6. **Install reviewer assets + construct the connector** for connector-bound commands —
  *      *deferred to Task 1.4.10*, where the orchestrator handlers need them. The `Invocation.needsConnector` flag
  *      already classifies the set so the wiring drops in without re-deriving it. 7. **Acquire the process lock** for
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

  // --- read-only: config, no lock -------------------------------------------

  private def runReadOnly(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    loadConfig(paths).flatMap {
      case Left(code) => IO.pure(code)
      case Right(config) =>
        CliParser.phase2(invocation.name, invocation.rest) match
          case Left(err) => usageError(err)
          case Right(command: ForgeCommand.ReadOnly) =>
            CommandRouter.readOnly(command, ReadOnlyContext(paths, config, invocation.rest))
          case Right(other) =>
            IO.raiseError(new IllegalStateException(s"read-only class produced non-read-only command '${other.name}'"))
    }

  // --- state-changing: config + lock ----------------------------------------

  private def runStateChanging(invocation: Invocation, paths: ForgePaths): IO[ExitCode] =
    loadConfig(paths).flatMap {
      case Left(code) => IO.pure(code)
      case Right(config) =>
        CliParser.phase2(invocation.name, invocation.rest) match
          case Left(err) => usageError(err)
          case Right(command) =>
            // Task 1.4.10: for invocation.needsConnector commands, install reviewer assets + construct the connector
            // here and fold them (plus the action log) into the Resource bracket below.
            lockMetadata(command).flatMap { metadata =>
              new FileProcessLock(paths).acquire(metadata, acceptStale = false).use {
                case LockAcquireResult.Acquired =>
                  CommandRouter.stateChanging(command, StateChangingContext(paths, config, invocation.rest))
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
