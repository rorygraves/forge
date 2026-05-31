package io.forge.app.orchestrator

import cats.effect.IO
import io.forge.core.{FeatureId, PrNumber}
import io.forge.core.paths.ForgePaths
import io.forge.git.watcher.PollBaseline

import java.util.UUID
import upickle.default.{read, write}

/** Task 1.4.10-d2c (**S4-1**) — cross-restart persistence for the [[io.forge.git.watcher.PRWatcher]] poll cursor.
  *
  * `PollBaseline` is the §S3-7 high-watermark cursor (`comments[]` / `reviews[]` timestamps + seen-id tie-breakers) the
  * watcher advances on every poll. Within one process the orchestrator threads it through a `Ref`, but a `forge run`
  * that crashes and restarts mid-gate would otherwise reset the cursor to `PollBaseline.empty` and re-surface every
  * human comment / review already acted on. This store persists the cursor to a sibling state file so the next run
  * resumes where the last poll left off.
  *
  * **Why a sibling file, not the manifest.** `Manifest` / `Piece` carry no baseline fields, and the manifest is the
  * committed §4 source of truth — `PollBaseline` is local-only state that mutates on *every* gh round-trip, so folding
  * it into the manifest would generate commit noise on each poll (design-1.4 J2 PRWatcher note; alternative considered
  * + rejected). The file is keyed by `PrNumber` so the design PR and every piece PR share one
  * `.forge/state/<feature>.poll-baselines.json`.
  *
  * **Durability posture.** Atomic (temp + `ATOMIC_MOVE`) so a reader never sees a half-written map, but unlike
  * [[io.forge.core.state.FileStateCache]] this is *not* the §4 canonical record: a missing or malformed file degrades
  * to "no baseline yet" (`PollBaseline.empty`) rather than an error — the worst case is one redundant re-surfacing of
  * an already-seen comment, which the FSM handles idempotently. The cursor is rebuildable in effect by simply polling
  * again, so it is never authoritative.
  */
final class PollBaselineStore(paths: ForgePaths):

  /** Load the persisted cursor for `pr`, or [[PollBaseline.empty]] if the file is absent / malformed / has no entry. */
  def load(feature: FeatureId, pr: PrNumber): IO[PollBaseline] =
    IO.blocking(readAll(feature).getOrElse(pr, PollBaseline.empty))

  /** Atomically merge `baseline` for `pr` into the feature's baseline map, preserving every other PR's cursor. */
  def save(feature: FeatureId, pr: PrNumber, baseline: PollBaseline): IO[Unit] =
    IO.blocking {
      val updated = readAll(feature).updated(pr, baseline)
      writeAll(feature, updated)
    }

  // Keyed by the raw PR number on disk — a JSON object with string keys is the natural, human-diffable shape, and it
  // sidesteps uPickle's array-of-pairs encoding for non-string map keys. `PrNumber` is an opaque `Int`, so the
  // conversion is a total round-trip at the boundary.
  private def readAll(feature: FeatureId): Map[PrNumber, PollBaseline] =
    val file = paths.pollBaselineFile(feature)
    if !os.exists(file) then Map.empty
    else
      try read[Map[Int, PollBaseline]](os.read(file)).map((pr, b) => PrNumber(pr) -> b)
      catch case _: Throwable => Map.empty

  private def writeAll(feature: FeatureId, map: Map[PrNumber, PollBaseline]): Unit =
    val target = paths.pollBaselineFile(feature)
    val parent = target / os.up
    if !os.exists(parent) then os.makeDir.all(parent)
    // Sibling temp + atomic rename so a concurrent/crashing reader never observes a partial JSON map.
    val temp = parent / s".${target.last}.tmp.${UUID.randomUUID()}"
    try
      os.write.over(temp, write(map.map((pr, b) => pr.value -> b), indent = 2))
      os.move(temp, target, replaceExisting = true, atomicMove = true)
    catch
      case t: Throwable =>
        try if os.exists(temp) then { val _ = os.remove(temp) }
        catch case _: Throwable => ()
        throw t
