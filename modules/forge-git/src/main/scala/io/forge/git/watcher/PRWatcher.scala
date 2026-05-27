package io.forge.git.watcher

import cats.effect.{IO, Ref}
import fs2.Stream
import io.forge.core.PrNumber

/** PR-D D1 — `gh pr view --json …` polling surface per v1.2 §9.
  *
  * The trait offers two entry points:
  *
  *   - [[pollOnce]] — single poll, returns a typed [[PollResult]]. The orchestrator (Slice 4) uses this on the `forge
  *     resume` warm-up path where it wants exactly one snapshot before re-entering the FSM loop.
  *   - [[watch]] — continuous polling. The `baseline` is a `Ref` because the high-watermark advances across polls; on
  *     [[PollResult.Snapshot]] the watcher writes `decoded.nextBaseline` back into the `Ref` so the next poll picks up
  *     the new cursor. Rate-limited / failed polls leave the baseline untouched.
  *
  * The watcher knows about gh's rate-limit framing only enough to decide *when* to poll again (RL1 back-off). The
  * action log entry `harness.rate_limited` is the Slice-4 orchestrator's job — `PRWatcher` surfaces
  * [[PollResult.RateLimited]] events the log writer can react to.
  */
trait PRWatcher:

  /** Continuous polling. Element type is [[PollResult]]; the stream never naturally terminates — the orchestrator
    * either consumes a `Snapshot` whose `state == Merged` and unsubscribes via `.take` / `.takeWhile`, or wraps the
    * stream in `.interruptWhen` for cancellation.
    *
    * @param pr
    *   PR number to poll.
    * @param baseline
    *   mutable cursor. The watcher writes `Snapshot.decoded.nextBaseline` here on each clean poll; the orchestrator
    *   reads the same `Ref` to persist baseline checkpoints to manifest / log.
    */
  def watch(pr: PrNumber, baseline: Ref[IO, PollBaseline]): Stream[IO, PollResult]

  /** Single-poll variant. Returns the typed outcome without mutating any shared state — the caller decides whether to
    * persist `decoded.nextBaseline` on a `Snapshot` result.
    */
  def pollOnce(pr: PrNumber, baseline: PollBaseline): IO[PollResult]

object PRWatcher:

  /** v1.2 §9 / Slice 0 §4.1 pin — the field set every poll requests from `gh pr view --json …`. Pinned here so the
    * decoder and watcher don't drift on what `commits`, `mergeable`, `mergeStateStatus`, etc. mean in practice.
    */
  val DefaultFields: Vector[String] = Vector(
    "state",
    "statusCheckRollup",
    "reviews",
    "reviewDecision",
    "mergeable",
    "mergeStateStatus",
    "comments",
    "commits",
    "mergedAt",
    "mergeCommit",
    "number"
  )
