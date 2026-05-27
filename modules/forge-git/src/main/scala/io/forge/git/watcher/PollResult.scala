package io.forge.git.watcher

import io.forge.git.cli.GhError

import scala.concurrent.duration.FiniteDuration

/** PR-D D1 — output of [[PRWatcher.pollOnce]] / element type of [[PRWatcher.watch]].
  *
  *   - [[PollResult.Snapshot]] — clean poll. Carries the full [[DecodedSnapshot]], including
  *     [[DecodedSnapshot.nextBaseline]] which the orchestrator should persist before the next call.
  *   - [[PollResult.RateLimited]] — RL1 back-off signal. Baseline is unchanged (we didn't decode anything); the
  *     streaming loop honours `retryAfter` (or the configured default) before re-polling.
  *   - [[PollResult.Failed]] — non-rate-limit failure (transient subprocess error, auth, 404, parse failure). The
  *     stream surfaces and continues; the orchestrator (Slice 4) decides whether to keep watching or escalate to a
  *     `HarnessError`. Repeated rate-limit failures also surface here once
  *     [[io.forge.git.watcher.PRWatcherConfig.consecutiveRateLimitsBeforeFailing]] is reached (design-rationale RL1 +
  *     S3-4 — the soft "after N, emit Failed" semantics is filed as a carry-forward).
  */
sealed trait PollResult extends Product with Serializable

object PollResult:
  final case class Snapshot(decoded: DecodedSnapshot) extends PollResult
  final case class RateLimited(retryAfter: Option[FiniteDuration]) extends PollResult
  final case class Failed(error: GhError) extends PollResult
