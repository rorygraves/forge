package io.forge.app.command

import cats.effect.IO
import cats.effect.kernel.Ref
import io.forge.daemon.{DaemonClient, JsonRpc}
import io.forge.instance.Instance
import io.forge.tui.CockpitSnapshot

import scala.concurrent.duration.*

/** Slice 4.4 Task 4.4.2 — the cockpit's **live data source**: a persistent B3 `subscribe` connection that keeps a
  * shared [[CockpitSnapshot]] `Ref` event-fresh, so [[CockpitCommands]]' render tick reads a local ref rather than
  * re-polling the daemon on every tick.
  *
  * **Why subscribe drives a `status` re-fetch rather than a client-side event fold (the ratified bridge).** The
  * cockpit's §5 `attention` projection and §8 spend totals are derived canonically inside `forge-instance`
  * (`AttentionReason.forStatus`, the reservation table) — `forge-tui` cannot see them — and the daemon's subscribe
  * *seed* replays per-worker events only (no pre-attach workstreams / outstanding budget). Folding the stream
  * client-side would therefore duplicate load-bearing projection logic and lose pre-attach state. Instead each pushed
  * event is treated as a *change signal*: we debounce a burst and re-fetch the daemon `status` (a pure `Ref` read
  * server-side), keeping the projection canonical and lossless while still being event-driven (no fixed poll interval).
  *
  * The cockpit is a **client** (§6.3.1): [[follow]] only ever reads — it never takes the instance lock and never
  * records an event. It is designed to run under a `Resource.background` for the lifetime of the TUI; cancelling it
  * releases the `subscribe` socket, and server-side the daemon's bounded fan-out subscription drops (the daemon's
  * `publish1` is fire-and-forget and never back-pressures). Quitting the cockpit thus leaves the daemon and every
  * worker running.
  */
object CockpitLiveFeed:

  /** JSON-RPC ids are cosmetic for these one-way calls (the daemon matches on `method`); kept distinct for log clarity.
    */
  private val StatusId = 1L
  private val SubscribeId = 2L

  /** One `status` round-trip → a parsed fleet snapshot. A delivered RPC *error* response decodes to `None` (the daemon
    * answered unhappily); a transport failure (no daemon) raises and is handled by the caller.
    */
  def fetch(instance: Instance): IO[Option[CockpitSnapshot]] =
    DaemonClient.call(instance.portFile, JsonRpc.Request(StatusId, "status")).map {
      case JsonRpc.Response.Success(_, result) => Some(CockpitSnapshot.fromStatusJson(instance.name.value, result))
      case JsonRpc.Response.Failure(_, _) => None
    }

  /** Follow the daemon's live feed forever, keeping `latest` event-fresh, until cancelled (the [[CockpitCommands]]
    * `Resource.background` scope closes when the operator quits).
    *
    * Per (re)connect: do one immediate [[fetch]] catch-up (so an empty fleet — which emits no seed events — and a
    * post-reconnect gap both refresh), then open the persistent `subscribe` stream and, on each debounced event burst,
    * re-fetch `status` into `latest`. A `fetch` that yields `None` (RPC error) or raises (transport failure
    * mid-session) leaves `latest` untouched — the cockpit keeps the last good frame. When the stream completes (daemon
    * stopped) or errors, wait `reconnectDelay` and re-subscribe — the reconnect-on-drop policy.
    */
  def follow(
      instance: Instance,
      latest: Ref[IO, Option[CockpitSnapshot]],
      settle: FiniteDuration = 150.millis,
      reconnectDelay: FiniteDuration = 1.second
  ): IO[Unit] =
    val refresh: IO[Unit] =
      fetch(instance).attempt.flatMap {
        case Right(Some(snapshot)) => latest.set(Some(snapshot))
        case _ => IO.unit // RPC error or transport failure: keep the last good frame
      }

    val session: IO[Unit] =
      refresh *>
        DaemonClient
          .subscribe(instance.portFile, JsonRpc.Request(SubscribeId, "subscribe"))
          .debounce(settle)
          .evalMap(_ => refresh)
          .compile
          .drain

    // A dropped/closed connection (stream completion) or any transport error retries after a backoff; cancellation
    // (background scope close) interrupts the sleep or the stream, ending the loop cleanly.
    (session.handleErrorWith(_ => IO.unit) *> IO.sleep(reconnectDelay)).foreverM
