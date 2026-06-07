package io.forge.daemon

import io.forge.core.WorkstreamId
import io.forge.instance.InstanceState

/** Phase-4 §8 / B2 (Task 4.3.5) — the **aggregate** budget caps a daemon authorizes a worker's reservation against.
  *
  * The per-session budget checks (§12, behind the worker boundary) stay frozen; this is the layer *above* them — the
  * fleet-wide ceiling the daemon enforces when a worker asks to spend before an agent spawn. A reservation is granted
  * iff the would-be total (`committed + outstanding + estimate`) stays within both the per-workstream and the
  * per-instance cap; otherwise it is refused and the worker holds (§8 — never killed mid-turn).
  *
  * Caps are a **constructed policy with defaults** today (mirroring the Task-4.3.3 `CredentialPolicy` posture):
  * persisting them in `instances/<name>/config.json` (so the per-instance / per-workstream caps are configurable
  * without a code change) is a clean carry-forward §-bump to `InstanceConfig`, deferred to 4.5 with the rest of the
  * aggregate-budget hardening.
  */
final case class BudgetPolicy(perWorkstreamCapUsd: BigDecimal, perInstanceCapUsd: BigDecimal):

  /** Would granting `estimate` for `workerId` (in `workstreamId`) keep both caps satisfied? Counts the snapshot's
    * committed spend plus all *other* workers' outstanding reservations — the worker's own current reservation is
    * excluded so a re-reserving worker (a fresh grant replaces its prior one) is not double-charged against itself.
    *
    * A worker with no owning workstream (`workstreamId = None`, a standalone 4.1-style registration) is bound only by
    * the per-instance cap. The decision is pure over the [[InstanceState]] snapshot, so it runs inside the daemon's
    * single-writer gate ([[DaemonState.modify]]) where the snapshot cannot change underneath it.
    */
  def authorize(
      state: InstanceState,
      workerId: String,
      workstreamId: Option[WorkstreamId],
      estimate: BigDecimal
  ): Boolean =
    val instanceOk =
      state.committedUsd + state.outstandingUsd(excludingWorker = Some(workerId)) + estimate <= perInstanceCapUsd
    val workstreamOk = workstreamId match
      case Some(ws) =>
        state.committedUsdForWorkstream(ws) +
          state.outstandingUsdForWorkstream(ws, excludingWorker = Some(workerId)) + estimate <= perWorkstreamCapUsd
      case None => true
    instanceOk && workstreamOk

object BudgetPolicy:

  /** A generous default workstream cap. Coarse on purpose — the live tuning + per-instance persistence is 4.5. */
  val DefaultPerWorkstreamCapUsd: BigDecimal = BigDecimal(200)

  /** A generous default per-instance cap. */
  val DefaultPerInstanceCapUsd: BigDecimal = BigDecimal(1000)

  /** The real daemon's default caps (used by `forge daemon start`). */
  val default: BudgetPolicy = BudgetPolicy(DefaultPerWorkstreamCapUsd, DefaultPerInstanceCapUsd)

  /** Effectively no aggregate ceiling — every realistic reservation is granted (the caps are far beyond any plausible
    * fleet spend). The safe default for a control-only / in-process daemon that wires no budget policy: budget
    * reservation must never *accidentally* block a worker, and the `cost.update` fan-in still aggregates committed
    * totals for the spend view regardless of the cap. (Contrast [[CredentialBroker.noop]], which *refuses*: a missing
    * credential is a loud failure, an unconfigured budget cap is not.)
    */
  val unlimited: BudgetPolicy =
    val huge = BigDecimal(Double.MaxValue)
    BudgetPolicy(perWorkstreamCapUsd = huge, perInstanceCapUsd = huge)
