package io.forge.core.state

import io.forge.core.FeatureId
import io.forge.core.log.ReplayError

/** PR-E E4 — error vocabulary for the rebuild pipeline (`RebuildState.run` and `StateCache.verifyAgainstLog`).
  *
  * Each layer keeps its own error vocabulary; lower-level failures are lifted explicitly here rather than re-exported.
  * Concretely:
  *
  *   - [[io.forge.core.log.ReplayError]] is local to the replay layer (`io.forge.core.log`). The rebuild layer lifts it
  *     via [[RebuildError.ReplayInconsistent]].
  *   - [[io.forge.core.manifest.ManifestStore]] failures surface as [[RebuildError.ManifestLoadFailed]] — the rebuild
  *     pipeline's step 1.
  *   - Structurally-incompatible recovery cases (manifest claims a piece merged but the fold-state isn't a §11.5 crash
  *     window) surface as [[RebuildError.InconsistentRecovery]] — operator intervention required.
  *
  * `IO` (cats-effect) carries only genuine I/O exceptions on the unhappy path; every recoverable rebuild failure is in
  * `Either`'s left.
  */
sealed trait RebuildError
object RebuildError:

  /** §4 — `manifest.json` is unreadable, malformed, or fails `Manifest.validate`. Pipeline step 1 of
    * [[RebuildState.run]] hands back this case before any log work begins.
    */
  final case class ManifestLoadFailed(featureId: FeatureId, cause: Throwable) extends RebuildError

  /** §11.0 — the on-disk state cache (`.forge/state/<feature>.json`) is present but undecodable (truncated, partial
    * flush, hand-edit). `detail` carries the decode-failure description for forensic context.
    *
    * This is **not** a fatal rebuild error: the §4 invariant makes the cache rebuildable from `manifest.json` + the
    * action log, so a corrupt cache never blocks recovery — [[StateCache.load]] collapses it to `None` and
    * [[StateCache.verifyAgainstLog]] rewrites it (appending `harness.cache_invalidated` with `reason =
    * cache_unreadable`). It is surfaced explicitly only by the *strict* read [[StateCache.loadStrict]], so a caller
    * that wants to *report* corruption (rather than silently heal it) can — `forge rebuild-state` (Task 1.4.15 O4)
    * probes the prior cache this way to tell the operator their cache was corrupt before rebuilding from the log.
    */
  final case class CacheCorrupt(featureId: FeatureId, detail: String) extends RebuildError

  /** The action log fold returned a [[ReplayError]] (e.g. non-monotonic `seq`, cross-actor session resume, audit /
    * manifest PR mismatch). The lower-level cause is preserved so callers can pattern-match into it.
    */
  final case class ReplayInconsistent(cause: ReplayError) extends RebuildError

  /** The reconcile post-pass detected a structural incompatibility between the action log's fold-state and the
    * manifest's recorded piece statuses. Examples:
    *   - manifest claims piece `p` is merged but the log's fold-state is `PieceAwaitingReview(p, _)` (case (c)
    *     variant);
    *   - log contains an `audit.piece_merged` entry whose paired `fsm.transition: PieceAwaitingMerge → Refining` is
    *     missing (case (d) — structurally impossible under §11.5 ordering);
    *   - two or more pieces fall into the §11.5 crash-window case at once (multi-piece divergence).
    *
    * In each case the safe play is to refuse to invent FSM transitions and surface the situation to the operator. The
    * Slice-4 CLI lifts this into `NeedsHumanIntervention("state cache unrecoverable")`.
    */
  final case class InconsistentRecovery(reason: String) extends RebuildError
