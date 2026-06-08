package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.effect.unsafe.implicits.global
import io.forge.app.cli.CockpitCommand
import io.forge.instance.{FileInstanceStore, Instance}
import io.forge.tui.{CockpitSnapshot, ForgeCockpit}

/** Slice 4.4 Task 4.4.1/4.4.2 — handler for `forge cockpit [--instance <name>]`: the operator cockpit TUI.
  *
  * Instance-scoped like [[WorkstreamCommands]] / [[DaemonCommands]]: `Main` routes it here without loading
  * [[io.forge.app.config.ForgeConfig]], installing reviewer assets, or taking the per-checkout lock. It resolves the
  * target instance ([[InstanceResolver]]), seeds a [[CockpitSnapshot]] from one `status` JSON-RPC round-trip to a
  * **running daemon**, then runs [[ForgeCockpit]].
  *
  * **Live data source (Task 4.4.2).** Rather than re-polling `status` on every render tick, the fleet view is kept
  * fresh by [[CockpitLiveFeed]]: a background fiber holds the daemon's B3 `subscribe` stream and, on each pushed event,
  * re-fetches `status` into a shared `Ref` (the ratified subscribe-triggered-refresh bridge — see [[CockpitLiveFeed]]
  * for why we re-fetch rather than fold the stream client-side). The render tick reads that ref locally, so it does no
  * network I/O. The daemon is the sole writer of the instance state (§6.3.1), so the cockpit only ever *reads*; the
  * feed fiber runs under a `Resource.background` whose scope closes when the operator quits, releasing the subscribe
  * socket — quitting the cockpit leaves the daemon and every worker running. A connect failure means no daemon is
  * running (exit 1), exactly as the workstream client commands report it.
  *
  * Per-worker drill-down + control actions are Tasks 4.4.3/4.4.4.
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
    CockpitLiveFeed.fetch(instance).attempt.flatMap {
      case Right(Some(initial)) =>
        for
          // `latest` is the shared fleet snapshot the live feed keeps fresh and the render tick reads.
          latest <- Ref.of[IO, Option[CockpitSnapshot]](Some(initial))
          // The per-tick reload bridges a read-only `Ref` read into the `Future` the termflow `Cmd.FCmd` expects, via
          // the global CE runtime (mirrors `TuiCommand`). It does no network I/O — `CockpitLiveFeed` does. A `None`
          // (set only by the seed) keeps the current frame.
          reload = () => latest.get.unsafeToFuture()
          // The live feed fiber runs for the lifetime of the TUI; quitting `ForgeCockpit.run` closes the background
          // scope, cancelling the fiber and releasing the subscribe socket (clean detach — the daemon keeps running).
          // The termflow runtime loop owns the calling thread until the user quits; run it on the blocking pool.
          exit <- CockpitLiveFeed
            .follow(instance, latest)
            .background
            .use(_ => IO.blocking(ForgeCockpit.run(initial, reload)).as(ExitCode.Success))
        yield exit
      case Right(None) =>
        Console[IO].errorln(s"forge cockpit: daemon for instance '$name' returned an error status").as(ExitCode(1))
      case Left(_) => notRunning(name, instance)
    }

  private def notRunning(name: String, instance: Instance): IO[ExitCode] =
    Console[IO]
      .errorln(
        s"forge cockpit: no daemon appears to be running for instance '$name' " +
          s"(no daemon port file at ${instance.portFile}). Start one with `forge daemon start`."
      )
      .as(ExitCode(1))
