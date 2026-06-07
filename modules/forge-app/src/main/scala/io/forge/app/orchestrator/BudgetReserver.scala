package io.forge.app.orchestrator

import cats.effect.IO

/** Phase-4 §8 / B2 (Task 4.3.5) — the orchestrator-side seam for **aggregate budget authorization before an agent
  * spawn**. The frozen per-session budget checks (§12) stay where they are; this wraps the §11 driver-session launches
  * so a worker obtains a fleet-wide reservation from the daemon *before* paying for a driver turn.
  *
  * [[RealSideEffects]] calls [[reserveBeforeSpawn]] at the top of each driver launch/resume. The default ([[noop]]) is
  * the non-daemon / `forge run` path — there is no aggregate budget, so every spawn proceeds (the frozen behaviour). A
  * daemon-spawned worker injects a real reserver (`io.forge.app.command.ReportingBudgetReserver`) that phones the
  * daemon over the control channel and **holds** on a refuse (it never raises on a refuse — only a transport error
  * escapes, to the worker's degrade path).
  */
trait BudgetReserver:

  /** Obtain budget headroom for the next agent session. Returns once a reservation is granted; on a refuse it holds
    * (surfacing a budget-hold) and retries rather than failing — the §8 never-kill-mid-turn rule lifted to the
    * aggregate. A transport failure (an unreachable daemon) is raised so the worker's degrade path reports it.
    */
  def reserveBeforeSpawn: IO[Unit]

object BudgetReserver:

  /** The non-daemon / pre-B2 default — no aggregate budget, every spawn proceeds immediately. Used by `forge run` (no
    * daemon) and host-process unit fixtures; behaviour is byte-identical to the pre-4.3.5 launch path.
    */
  val noop: BudgetReserver = new BudgetReserver:
    def reserveBeforeSpawn: IO[Unit] = IO.unit

/** The outcome of a `reserve-budget` request (Task 4.3.5). A *refuse* is a normal protocol outcome the worker holds on,
  * not an error — only a transport failure raises.
  */
enum ReservationOutcome:

  /** The daemon granted the reservation; `reservationId` is the handle the matching `budget.finalize` clears it by (the
    * worker does not act on the id — the daemon finalizes implicitly on the exported `cost.update`).
    */
  case Granted(reservationId: String)

  /** The daemon refused (granting would oversubscribe a cap); `reason` is the human-readable explanation surfaced in
    * the budget-hold.
    */
  case Refused(reason: String)
