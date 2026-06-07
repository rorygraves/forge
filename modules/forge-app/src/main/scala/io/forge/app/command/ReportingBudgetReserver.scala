package io.forge.app.command

import cats.effect.IO
import cats.effect.std.Console
import io.forge.app.orchestrator.{BudgetReserver, ReservationOutcome}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*

/** Task 4.3.5 — the daemon-backed [[BudgetReserver]] a `forge worker` injects into its orchestrator: it phones the
  * daemon's `reserve-budget` RPC over the control channel before each driver spawn, **holds** on a refuse, and proceeds
  * on a grant.
  *
  *   - **grant** → return immediately; the driver spawn proceeds.
  *   - **refuse** → surface a budget-hold (a [[BudgetHoldStatus]] `worker-status`, so the instance view shows the
  *     worker is waiting on budget rather than its FSM state) and retry after [[holdBackoff]]. The worker is never
  *     killed mid-turn (§8 — Slice-2.2's rule lifted to the aggregate); it waits until another worker's `cost.update`
  *     frees headroom. The next granted spawn restores the real status (the feed exporter mirrors the FSM state on its
  *     next tick).
  *   - a **transport error** (an unreachable daemon) propagates from `reserveBudget`, escaping to the worker loop's
  *     degrade path — exactly as a failed `register`/`status` does.
  *
  * `estimateUsd` is the **coarse per-session reservation amount** (O11): the per-piece session cap. The daemon's
  * `budget.finalize` corrects it to the real spend from the exported `cost.update`, so an over-estimate only costs
  * temporary headroom, never accuracy. A learned/profiled per-phase estimate is a 4.5 refinement.
  */
final class ReportingBudgetReserver(
    reporter: WorkerReporter,
    estimateUsd: BigDecimal,
    holdBackoff: FiniteDuration = ReportingBudgetReserver.DefaultHoldBackoff
) extends BudgetReserver:

  def reserveBeforeSpawn: IO[Unit] =
    reporter.reserveBudget(estimateUsd).flatMap {
      case ReservationOutcome.Granted(_) => IO.unit
      case ReservationOutcome.Refused(reason) =>
        Console[IO].errorln(s"forge worker: budget hold — $reason; waiting for headroom…") *>
          reporter.status(ReportingBudgetReserver.BudgetHoldStatus) *>
          IO.sleep(holdBackoff) *>
          reserveBeforeSpawn
    }

object ReportingBudgetReserver:

  /** The `worker-status` a held worker reports while waiting on budget — distinct from any FSM-state name so the
    * instance view (and, later, the 4.4 cockpit) can show "waiting on budget".
    */
  val BudgetHoldStatus: String = "BudgetHold"

  /** How long a held worker waits before re-attempting its reservation. Coarse polling — the TTL/notify-on-free
    * hardening is 4.5.
    */
  val DefaultHoldBackoff: FiniteDuration = 10.seconds
