package io.forge.instance

import io.forge.core.WorkstreamId
import io.forge.core.Json.given

import upickle.default.ReadWriter

/** Phase-4 §5 — the **workstream** coordination object (Slice 4.2, Task 4.2.4).
  *
  * A workstream is *the unit a developer thinks about*: a goal that decomposes into one or more **workers** (one
  * repo-feature each, §5 cardinality). It is **not** an execution unit and **not** a checkout — those are the worker's.
  * Its lifecycle is deliberately small and *scalar* ([[WorkstreamStatus]]) and never reaches into `Fsm.transition`
  * (which stays behind the worker boundary, contract §9).
  *
  *   - `id` — the daemon-allocated [[WorkstreamId]] (`ws-1`, `ws-2`, …), the key in the instance log.
  *   - `goal` — the human description of what the workstream is for.
  *   - `status` — the scalar lifecycle position (`Planning → Active → Done/Abandoned`).
  *   - `workers` — the **ordering** over the worker ids that fulfil it (§5: cross-repo sequencing reads this; 4.2
  *     implements single-repo workers, so the list is normally length ≤ 1 and the ordering is forward-looking design).
  *     A worker joins this list when the daemon spawns it into the workstream (`worker.spawned`).
  *
  * **"Needs a human" is not a lifecycle state.** A workstream stays `Active` while *any* of its workers is progressing;
  * which workers need a human (and why) is a derived [[InstanceState.attention]] projection computed over the workers,
  * never a `status` value (§5).
  */
final case class Workstream(
    id: WorkstreamId,
    goal: String,
    status: WorkstreamStatus,
    workers: Vector[String]
) derives ReadWriter:

  /** Append `workerId` to the ordering if it is not already present (idempotent against a replayed `worker.spawned`).
    */
  def withWorker(workerId: String): Workstream =
    if workers.contains(workerId) then this else copy(workers = workers :+ workerId)

object Workstream:
  /** A freshly created workstream: `Planning`, no workers yet. */
  def planning(id: WorkstreamId, goal: String): Workstream =
    Workstream(id, goal, WorkstreamStatus.Planning, Vector.empty)

/** Phase-4 §5 — a workstream's scalar lifecycle. `Planning` (created, not yet driving workers) → `Active` (≥1 worker
  * spawned/progressing) → `Done` (goal met) / `Abandoned` (given up). Fully owned by Forge (unlike a worker's FSM-state
  * name, which is forwarded verbatim from behind the worker boundary), so it is modelled as a closed enum rather than
  * an open string — but serialised by *name* on the wire so [[InstanceEvent]] carries it as a plain string and an
  * unknown future status decodes to `None` (skip) rather than crashing an older fold.
  */
enum WorkstreamStatus derives ReadWriter:
  case Planning
  case Active
  case Done
  case Abandoned

object WorkstreamStatus:
  /** The wire name of a status (the enum case's simple name). */
  def name(s: WorkstreamStatus): String = s match
    case Planning => "Planning"
    case Active => "Active"
    case Done => "Done"
    case Abandoned => "Abandoned"

  /** Parse a wire name back to a status, or `None` for an unrecognised string (a future variant / a hand-edited log).
    */
  def fromString(s: String): Option[WorkstreamStatus] = s match
    case "Planning" => Some(Planning)
    case "Active" => Some(Active)
    case "Done" => Some(Done)
    case "Abandoned" => Some(Abandoned)
    case _ => None

/** Phase-4 §5 — why a worker needs a human, the categories the cockpit (4.4) renders as per-worker flags (G5). Derived
  * from the worker's exported FSM-state name (the open `status` string), so this mapping is the one place that knows
  * which states stop for a human. The contract names three: NHI, a driver question, a merge/approval gate.
  */
enum AttentionReason:
  /** `NeedsHumanIntervention` — automated recovery exhausted; the operator runs `forge resume --<hint>`. */
  case NeedsHumanIntervention

  /** `DesignNeedsHumanInput` — the design driver surfaced blocking questions awaiting human answers. */
  case DriverQuestion

  /** `DesignAwaitingMerge` / `PieceAwaitingMerge` / `PieceAwaitingReview` / `PlanningUpdate` — a merge or approval gate
    * awaiting a human (merge the PR, approve a planning update).
    */
  case MergeGate

object AttentionReason:
  /** The attention category an exported worker `status` (FSM-state name) implies, or `None` if the worker is making
    * autonomous progress and needs no human. The names match the [[io.forge.core.fsm.FsmState]] case names the worker
    * exports (`WorkerFeedExporter.fsmStateName`).
    */
  def forStatus(status: String): Option[AttentionReason] = status match
    case "NeedsHumanIntervention" => Some(NeedsHumanIntervention)
    case "DesignNeedsHumanInput" => Some(DriverQuestion)
    case "DesignAwaitingMerge" | "PieceAwaitingMerge" | "PieceAwaitingReview" | "PlanningUpdate" => Some(MergeGate)
    case _ => None

  /** A short stable token for the wire / CLI rendering. */
  def token(r: AttentionReason): String = r match
    case NeedsHumanIntervention => "needs-human-intervention"
    case DriverQuestion => "driver-question"
    case MergeGate => "merge-gate"

/** One entry in a workstream's [[InstanceState.attention]] projection: a worker that needs a human, and why. */
final case class WorkerAttention(workerId: String, reason: AttentionReason)
