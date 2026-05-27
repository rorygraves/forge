package io.forge.app.lock

import io.forge.core.FeatureId
import io.forge.core.Json.given

import java.time.Instant
import upickle.default.ReadWriter

/** Metadata written alongside the OS-level lock file at [[io.forge.core.paths.ForgePaths.lockMetadataFile]] so that a
  * second `forge` invocation can identify the holder before deciding to wait, refuse, or unlock (§13). The shape pairs
  * with `.lock` via [[FileProcessLock]] — `.lock` carries the OS-enforced `FileChannel.tryLock`; `.lock.json` carries
  * the human-readable holder info.
  *
  *   - `pid` / `hostname` identify the holder for the §13 step 4 "print holder info" path.
  *   - `startedAt` lets the operator gauge whether the lock is plausibly live ("started 12s ago" vs "started 4h ago").
  *   - `command` is the user-typed CLI command (e.g. `"forge run stripe-webhook"`) for context in the holder message.
  *   - `feature` is `None` for commands that don't bind to a feature (`forge unlock --force`, `forge status` etc).
  */
final case class LockMetadata(
    pid: Long,
    hostname: String,
    startedAt: Instant,
    command: String,
    feature: Option[FeatureId]
) derives ReadWriter

object LockMetadata:

  /** Sentinel placeholder returned by [[FileProcessLock.acquire]] when the `.lock.json` sibling exists but cannot be
    * decoded (corrupted file, partially-written JSON, version skew). The §13 stale-lock prompt still surfaces — the
    * operator just sees "unknown" rather than per-field detail.
    */
  val Unknown: LockMetadata = LockMetadata(
    pid = -1L,
    hostname = "unknown",
    startedAt = Instant.EPOCH,
    command = "unknown",
    feature = None
  )
