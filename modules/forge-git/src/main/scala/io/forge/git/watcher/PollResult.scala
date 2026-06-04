package io.forge.git.watcher

import io.forge.git.cli.GhError

import scala.concurrent.duration.FiniteDuration

/** PR-D D1 — output of [[PRWatcher.pollOnce]] / element type of [[PRWatcher.watch]].
  *
  *   - [[PollResult.Snapshot]] — clean poll. Carries the full [[DecodedSnapshot]], including
  *     [[DecodedSnapshot.nextBaseline]] which the orchestrator should persist before the next call.
  *   - [[PollResult.RateLimited]] — RL1 back-off signal. Baseline is unchanged (we didn't decode anything); the
  *     streaming loop honours `retryAfter` (or the configured default) before re-polling.
  *   - [[PollResult.TransientError]] — a retry-worthy `gh` server / network blip (`GhError.Transient`, e.g. an HTTP
  *     503). Like [[PollResult.RateLimited]] this is a *non-failing* back-off signal: the baseline is unchanged, the
  *     loop sleeps [[io.forge.git.watcher.PRWatcherConfig.transientBackoff]] and re-polls, and the orchestrator absorbs
  *     it (keep polling) rather than escalating. Dogfood finding #5: a single transient 503 on the §9 PR-state poll
  *     must not hard-route to `NeedsHumanIntervention`. Promoted to [[PollResult.Failed]] only once
  *     [[io.forge.git.watcher.PRWatcherConfig.consecutiveTransientFailuresBeforeFailing]] consecutive transients occur
  *     (the same soft-cliff as the rate-limit path, design-rationale S3-4).
  *   - [[PollResult.Failed]] — a fatal failure that polling cannot recover (auth, 404, parse failure), or a *promoted*
  *     transient / rate-limit threshold breach. The stream surfaces and continues; the orchestrator escalates to a
  *     `HarnessError`. Repeated rate-limit failures surface here once
  *     [[io.forge.git.watcher.PRWatcherConfig.consecutiveRateLimitsBeforeFailing]] is reached (design-rationale RL1 +
  *     S3-4 — the soft "after N, emit Failed" semantics is filed as a carry-forward).
  */
sealed trait PollResult extends Product with Serializable

object PollResult:
  final case class Snapshot(decoded: DecodedSnapshot) extends PollResult
  final case class RateLimited(retryAfter: Option[FiniteDuration]) extends PollResult
  final case class TransientError(error: GhError) extends PollResult
  final case class Failed(error: GhError) extends PollResult
