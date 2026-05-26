package io.forge.git.watcher

import io.forge.core.Sha
import io.forge.core.pr.PrSnapshot

/** PR-B B2 — wire-decoded shape emitted by [[PrSnapshotDecoder]].
  *
  * v1.2 §6 `PrSnapshot` is deliberately provider-neutral and does **not** carry the head SHA — the FSM only needs it to
  * call `BranchManager.baseFreshness` from Slice 4. PR-B keeps `PrSnapshot` unchanged and surfaces the head SHA as a
  * parallel field on this wrapper so the consumer has both halves in one canonical place without the type leaking
  * upstream into `forge-core`.
  */
final case class DecodedSnapshot(snapshot: PrSnapshot, headSha: Sha)
