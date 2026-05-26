package io.forge.core.log

import io.forge.core.*
import io.forge.core.Json.given

import java.time.Instant
import upickle.default.ReadWriter
import upickle.implicits.key

/** §6 / §19 — one entry in a feature's action log.
  *
  * The wire shape is v1.2 §19's NDJSON object: `{seq, ts, feature, piece, actor, role, kind, payload}`. The in-memory
  * field is named `at: Instant` (matching the §6 `Action` case-class definition); the `@key("ts")` annotation renames
  * it on the wire so the on-disk NDJSON stays `"ts"` per §19. The asymmetry is intentional: the §19 wire form
  * pre-existed the §6 model.
  *
  * `payload` is `ujson.Value` (raw JSON tree) because the §19 `kind` enum is open at the wire level — different `kind`
  * values carry different payload shapes, and `forge-core` cannot encode every projection. Per-kind interpretation
  * lives in `Feature.foldEvents` (PR-D D4).
  *
  * `Action` is the *stamped* shape — it carries `seq` (monotonic per feature, assigned by `ActionLog.append`) and `at`
  * (assigned via `IO.realTime` at write time). The pure FSM transition emits the unstamped sibling [[ActionDraft]]
  * instead; `ActionLog.append` (PR-D D1) calls `ActionDraft.stamp(seq, at)` on the way to disk.
  */
final case class Action(
    seq: Long,
    @key("ts") at: Instant,
    feature: FeatureId,
    piece: Option[PieceId],
    actor: Option[String],
    role: Option[String],
    kind: String,
    payload: ujson.Value
) derives ReadWriter

/** §6 / PR-B B3 — unstamped sibling of [[Action]]. Emitted by pure producers (the FSM's `Fsm.transition` in PR-C, the
  * reconcile rule in PR-E E4); stamped by `ActionLog.append` into a full [[Action]] before the NDJSON line is written.
  * The split keeps `Fsm.transition` free of `IO`, a clock, and the log-sequence allocator.
  *
  * `ActionDraft` is **never** serialised on its own — there is no wire form. The on-disk shape is always [[Action]].
  */
final case class ActionDraft(
    feature: FeatureId,
    piece: Option[PieceId],
    actor: Option[String],
    role: Option[String],
    kind: String,
    payload: ujson.Value
):
  /** Stamp this draft with the orchestrator-allocated `seq` and write-time `at`. The only path from `ActionDraft` to
    * `Action`; see `ActionLog.append` (PR-D D1).
    */
  def stamp(seq: Long, at: Instant): Action =
    Action(seq, at, feature, piece, actor, role, kind, payload)

object Action:
  /** Inverse of [[ActionDraft.stamp]] — kept as a named helper for callers that prefer the constructor style. */
  def from(draft: ActionDraft, seq: Long, at: Instant): Action =
    draft.stamp(seq, at)
