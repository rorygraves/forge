package io.forge.core.state

import cats.effect.IO
import io.forge.core.FeatureId
import io.forge.core.fsm.Feature
import io.forge.core.log.ActionLog
import io.forge.core.manifest.ManifestStore

/** PR-E E3 — outcome of [[StateCache.verifyAgainstLog]].
  *
  * `Consistent(feature)` — the on-disk cache matched the rebuilt `Feature` byte-for-byte (well, value-for-value:
  * uPickle codec round-trip). The cache stays in place.
  *
  * `Rewritten(feature)` — the cache was absent or differed from the rebuild's projection. The rebuilt `Feature` has
  * been written to disk and a `harness.cache_invalidated` action has been appended to the log so the divergence is
  * forensically visible.
  */
sealed trait VerifyResult:
  def feature: Feature
object VerifyResult:
  final case class Consistent(feature: Feature) extends VerifyResult
  final case class Rewritten(feature: Feature) extends VerifyResult

/** §4 / §11.0 — local rebuildable state cache for a feature's `Feature` projection.
  *
  * `manifest.json` (§4) is the committed source of truth; the action log (§19) is the canonical runtime record. The
  * `Feature` projected by `Feature.foldEvents(initial, log)` is what the cache stores — a serialised aggregate of the
  * §6.1 projections (state, session ids, cost totals, branch-protection epoch, design-feedback round).
  *
  * Writes are atomic at the filesystem layer (temp file + fsync + `os.move`) per the §11.5 "Write via temp file +
  * os.move" rule; a reader observing the file always sees either the previous committed view or the new one.
  *
  * `verifyAgainstLog` is the §11.0 step 4 entry point: it rebuilds the `Feature` from the manifest + log and either
  * confirms the cache or replaces it.
  */
trait StateCache:

  /** Read the on-disk cache. Returns `None` when the file is absent (a fresh feature, or post-`forge rebuild-state`
    * deletion). Returns `Some(feature)` on a successful uPickle decode of the cache contents.
    *
    * Does **not** validate against the manifest or the action log — that's [[verifyAgainstLog]]'s job. A `load` after a
    * crash may surface a stale cache; callers that need authoritative state run [[verifyAgainstLog]] (or call
    * [[RebuildState.run]] directly).
    */
  def load(featureId: FeatureId): IO[Option[Feature]]

  /** Write the `feature` atomically. Implementation contract: write to a temp file in the same directory as the target
    * (so the rename is atomic on POSIX), `fsync` the temp, then `os.move` into place. The target's parent directory is
    * created on demand.
    */
  def save(featureId: FeatureId, feature: Feature): IO[Unit]

  /** §11.0 step 4 — rebuild the `Feature` from `manifest.json` + the action log and compare against the cached value.
    *
    * On a clean match: the cache stays in place and the result is [[VerifyResult.Consistent]].
    *
    * On a missing cache or a value mismatch: runs the full [[RebuildState.run]] pipeline (manifest seed → `foldEvents`
    * → `reconcile` → `save`), appends a `harness.cache_invalidated` action to the log so the divergence is forensically
    * visible, and returns [[VerifyResult.Rewritten]] carrying the freshly-saved `Feature`.
    *
    * Any [[RebuildError]] from the rebuild path propagates through this method's `Either` channel rather than being
    * swallowed; the §11.0 step-4 caller (Slice 4 CLI) lifts it into `NeedsHumanIntervention("state cache
    * unrecoverable")`.
    */
  def verifyAgainstLog(
      featureId: FeatureId,
      manifestStore: ManifestStore,
      log: ActionLog
  ): IO[Either[RebuildError, VerifyResult]]
