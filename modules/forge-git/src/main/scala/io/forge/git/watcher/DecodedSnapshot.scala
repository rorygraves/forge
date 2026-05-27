package io.forge.git.watcher

import io.forge.core.Sha
import io.forge.core.pr.PrSnapshot

/** PR-B B2 — wire-decoded shape emitted by [[PrSnapshotDecoder]].
  *
  *   - `snapshot` is the v1.2 §6 provider-neutral [[PrSnapshot]] — what the FSM consumes.
  *   - `headSha` is the head commit oid (from `commits[-1].oid`), kept as a parallel field because v1.2 §6 doesn't
  *     carry it on `PrSnapshot`. `BranchManager.baseFreshness` (Slice 4) consumes it.
  *   - `nextBaseline` is the [[PollBaseline]] the orchestrator should persist after consuming this snapshot — the
  *     watermark + same-second-id tie-breakers needed for the next poll to correctly filter "new since baseline"
  *     entries (review round 2 — design-rationale S3-7).
  */
final case class DecodedSnapshot(snapshot: PrSnapshot, headSha: Sha, nextBaseline: PollBaseline)
