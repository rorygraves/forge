package io.forge.core.log

import cats.effect.IO
import io.forge.core.FeatureId

/** §4 / §19 — append-only per-feature action log.
  *
  * The local NDJSON file at `.forge/log/<feature>.jsonl` is the canonical record (§4 invariant); state-cache contents
  * are derivable by replaying the log. The wire shape is one `Action` per line, exactly as spelled out in §19 (`seq`,
  * `ts`, `feature`, `piece`, `actor`, `role`, `kind`, `payload`).
  *
  * Pure callers — the FSM in particular — emit unstamped [[ActionDraft]]s. `ActionLog.append` / `appendAll` allocate
  * `seq` (monotonic per feature, in-process under the §13 single-writer process lock) and `at` (`IO.realTime →
  * Instant`) at the moment of writing, so the FSM stays free of a clock and a sequence allocator.
  *
  * Crash recovery: `replay` is tolerant of a partially-flushed trailing line (the file impl truncates it back to the
  * last `\n` boundary on disk and writes a `harness.error log_truncated` recovery entry — see [[FileActionLog]] for the
  * full contract). Higher-level cross-cutting reconciliation (`fsm.transition` present but `audit.piece_merged`
  * missing, etc.) is the orchestrator's `RebuildState.reconcile` job in PR-E E4; this trait owns only the per-line
  * file-level recovery.
  *
  * Concurrency: a single Forge process per repo holds the §13 OS-level lock, so the in-process `seq` allocator is the
  * authoritative source for `seq` ordering. The trait does NOT add a cross-process strategy.
  */
trait ActionLog:

  /** Stamp the draft with the next `seq` and the current wall-clock `at`, then append it as one NDJSON line. Returns
    * the stamped [[Action]] so callers can correlate the on-disk record with the in-memory event that produced it.
    *
    * Single-line write: one OS write call per append. Crash atomicity is best-effort per POSIX (a partial flush is
    * possible but `replay`'s repair contract restores a valid NDJSON prefix).
    */
  def append(featureId: FeatureId, draft: ActionDraft): IO[Action]

  /** Atomically stamp and write a batch of drafts. Allocates the next N `seq` values, captures `at` once for the whole
    * batch (matching the "conceptually atomic transition" semantics of `Fsm.transition`'s return value), renders every
    * draft to its NDJSON line, concatenates into a single byte buffer, and issues a single `Files.write(... APPEND |
    * SYNC)` call.
    *
    * The single-write strategy reduces the recoverable crash window — one OS-level write means fewer points at which a
    * partial flush can interleave with a crash than N sequential `append` calls — but is **not** a crash-atomicity
    * guarantee at the filesystem level (POSIX makes no such guarantee for regular files). The durable correctness
    * guarantee comes from `replay`'s on-disk truncate-and-recover (D5) plus `RebuildState.reconcile`'s
    * missing-companion repair (E4); the single-write strategy just reduces how often that fallback fires.
    *
    * Returns the stamped Vector[Action] in the same order as the input drafts.
    */
  def appendAll(featureId: FeatureId, drafts: Vector[ActionDraft]): IO[Vector[Action]]

  /** Read the entire on-disk log and return the **durable post-repair** view.
    *
    * If the file ends in a partially-flushed line, `replay` physically truncates the file at the last `\n` on disk
    * (before the recovery entry is written, so the on-disk prefix is always valid NDJSON), then appends a
    * `harness.error { kind: "log_truncated", droppedBytes }` action with the next available `seq`. The returned Vector
    * contains the survivors followed by the recovery entry, matching exactly what subsequent consumers will read.
    *
    * D4's `Feature.foldEvents` treats the recovery entry as a no-op projection (forensic marker, not a domain event).
    *
    * The file impl is a one-shot writer at this point — once `replay` returns, the file is in a clean appendable state.
    */
  def replay(featureId: FeatureId): IO[Vector[Action]]

  /** The next `seq` value the log would allocate for `featureId`. Warm-up reads the existing file via `replay`; once
    * primed, the value is held in-memory and incremented under the §13 single-writer invariant.
    *
    * Exposed mainly for tests and rebuild paths; `append` / `appendAll` allocate `seq` internally and don't require the
    * caller to manage it.
    */
  def nextSeq(featureId: FeatureId): IO[Long]
