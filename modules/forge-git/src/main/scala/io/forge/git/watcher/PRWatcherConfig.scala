package io.forge.git.watcher

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** PR-D D4 — runtime knobs for [[PRWatcher]]. Defaults mirror v1.2 §18 (`pollIntervalMs: 30000`, `rateLimitBackoffMs:
  * 60000`); Slice 4's config loader will surface these in `.forge/config.json`.
  *
  * Slice 3 keeps the config self-contained (no on-disk binding) so the watcher can be exercised by unit tests without a
  * full config-loader dependency.
  *
  * @param pollInterval
  *   space between successive polls on the happy path. v1.2 §18 `pollIntervalMs` default.
  * @param rateLimitBackoff
  *   sleep duration after a [[PollResult.RateLimited]] without a `retryAfter` (when `gh` didn't emit a `Retry-After`
  *   header). v1.2 §18 `rateLimitBackoffMs` default.
  * @param transientBackoff
  *   sleep duration after a [[PollResult.TransientError]] (a `gh` server / network blip, e.g. an HTTP 503) before
  *   re-polling. Mirrors `rateLimitBackoff`; `OrchestratorBuilder` wires both to the same §18 `rateLimitBackoffMs` knob
  *   (both are "GitHub is momentarily unavailable — pause and retry" intervals). Dogfood finding #5.
  * @param consecutiveRateLimitsBeforeFailing
  *   after this many consecutive rate-limited polls, the watcher emits [[PollResult.Failed]] carrying the underlying
  *   `GhError.RateLimited` so the orchestrator can decide whether to keep watching. The internal counter resets on any
  *   non-rate-limit result (Snapshot or other Failed). Filed as carry-forward **S3-4** — Slice 4 may tighten this into
  *   a hard stream failure.
  * @param consecutiveTransientFailuresBeforeFailing
  *   the transient-error twin of `consecutiveRateLimitsBeforeFailing`: after this many consecutive
  *   [[PollResult.TransientError]] polls (no intervening Snapshot), the watcher promotes the transient to
  *   [[PollResult.Failed]] so a genuinely-down GitHub eventually escalates to `NeedsHumanIntervention` instead of
  *   polling forever. The counter resets on any non-transient result. Default mirrors the rate-limit cliff (3). Dogfood
  *   finding #5 (a *single* transient 503 must be absorbed, not escalated).
  * @param botLogin
  *   the GitHub login Forge posts as. Passed verbatim to [[PrSnapshotDecoder.decode]] for the bot-author filter so the
  *   FSM doesn't see its own comments as "new human signal".
  * @param requestedFields
  *   `gh pr view --json <fields>` field set. Defaults to [[PRWatcher.DefaultFields]] (v1.2 §9 pinning).
  */
final case class PRWatcherConfig(
    pollInterval: FiniteDuration = 30.seconds,
    rateLimitBackoff: FiniteDuration = 60.seconds,
    transientBackoff: FiniteDuration = 60.seconds,
    consecutiveRateLimitsBeforeFailing: Int = 3,
    consecutiveTransientFailuresBeforeFailing: Int = 3,
    botLogin: String = "forge-bot",
    requestedFields: Vector[String] = PRWatcher.DefaultFields
)
