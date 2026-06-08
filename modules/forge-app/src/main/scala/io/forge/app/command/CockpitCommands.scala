package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import cats.effect.unsafe.implicits.global
import io.forge.app.cli.CockpitCommand
import io.forge.daemon.{DaemonClient, JsonRpc}
import io.forge.instance.{FileInstanceStore, Instance}
import io.forge.tui.{CockpitSnapshot, ForgeCockpit}

import scala.concurrent.Future

/** Slice 4.4 Task 4.4.1 — handler for `forge cockpit [--instance <name>]`: the operator cockpit TUI.
  *
  * Instance-scoped like [[WorkstreamCommands]] / [[DaemonCommands]]: `Main` routes it here without loading
  * [[io.forge.app.config.ForgeConfig]], installing reviewer assets, or taking the per-checkout lock. It resolves the
  * target instance ([[InstanceResolver]]), seeds a [[CockpitSnapshot]] from one `status` JSON-RPC round-trip to a
  * **running daemon**, then runs [[ForgeCockpit]] with a per-tick reload thunk that re-fetches `status` — so the fleet
  * view tracks the live daemon within the poll interval. The daemon is the sole writer of the instance state (§6.3.1),
  * so the cockpit only ever *reads*; quitting it leaves the daemon and every worker running. A connect failure means no
  * daemon is running (exit 1), exactly as the workstream client commands report it.
  *
  * The live B3 `subscribe` feed (push updates rather than poll) is Task 4.4.2; per-worker drill-down + control actions
  * are Tasks 4.4.3/4.4.4.
  */
object CockpitCommands:

  def run(home: os.Path, command: CockpitCommand): IO[ExitCode] =
    val store = new FileInstanceStore(home)
    InstanceResolver.resolve(store, command.instance, "cockpit").flatMap {
      case Left(code) => IO.pure(code)
      case Right(resolved) => launch(resolved)
    }

  private def launch(instance: Instance): IO[ExitCode] =
    val name = instance.name.value
    fetch(instance).attempt.flatMap {
      case Right(Some(initial)) =>
        // The per-tick reload bridges its read-only status IO into the `Future` the termflow `Cmd.FCmd` expects, via
        // the global CE runtime (mirrors `TuiCommand`). A transport failure mid-session (the daemon stopped) folds to
        // `None`, which keeps the last good frame rather than crashing the cockpit.
        val reload: () => Future[Option[CockpitSnapshot]] =
          () => fetch(instance).attempt.map(_.toOption.flatten).unsafeToFuture()
        // The termflow runtime loop owns the calling thread until the user quits; run it on the blocking pool.
        IO.blocking(ForgeCockpit.run(initial, reload)).as(ExitCode.Success)
      case Right(None) =>
        Console[IO].errorln(s"forge cockpit: daemon for instance '$name' returned an error status").as(ExitCode(1))
      case Left(_) => notRunning(name, instance)
    }

  /** One `status` round-trip → a parsed fleet snapshot. A delivered RPC *error* response decodes to `None` (the daemon
    * answered but unhappily); a transport failure (no daemon) raises and is caught by the caller's `.attempt`.
    */
  private def fetch(instance: Instance): IO[Option[CockpitSnapshot]] =
    DaemonClient.call(instance.portFile, JsonRpc.Request(1L, "status")).map {
      case JsonRpc.Response.Success(_, result) => Some(CockpitSnapshot.fromStatusJson(instance.name.value, result))
      case JsonRpc.Response.Failure(_, _) => None
    }

  private def notRunning(name: String, instance: Instance): IO[ExitCode] =
    Console[IO]
      .errorln(
        s"forge cockpit: no daemon appears to be running for instance '$name' " +
          s"(no daemon port file at ${instance.portFile}). Start one with `forge daemon start`."
      )
      .as(ExitCode(1))
