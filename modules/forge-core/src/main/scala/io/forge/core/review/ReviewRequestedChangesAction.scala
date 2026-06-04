package io.forge.core.review

import io.forge.core.{FeatureId, PieceId}
import io.forge.core.log.ActionDraft

/** §19 `review.request_changes` action (Phase 3 / A5, decision D8). Recorded when Forge's own reviewer one-shot returns
  * a `RequestChanges` verdict — design-review (§11.2) or code-review (§11.5) — capturing the **blocker prose** so the
  * post-run `ConventionLearner` (§11.7) can mine *recurring reviewer comments* as a convention signal, alongside the
  * `profile.failure_classified` failure→remedy signal it already mines (the other half of the §7.11/§11.7 framing).
  * Before this kind existed the action log captured classified gate failures but not reviewer comment text, so the
  * learner's cost lever gated on failures alone — see design-3.0 §4 **D8**.
  *
  * Like the `profile.*` audit kinds it is a **no-op `Replay` projection** (the default branch of `Replay.step`): the
  * FSM consumes the *verdict* (via `FsmEvent.DesignReviewReceived` / `CodeReviewVerdict`), never this audit row, and
  * the blocker text reaches a later run only through a human-approved CLAUDE.md / profile change. So the replayability
  * invariant holds (`ProfileReplayInvarianceSuite` R1) — the audit shows the reviewer asked for changes; nothing about
  * the §6.1 projections changes.
  *
  * Payload: `{ gate, round, blockers }` — `gate ∈ {"design","code"}`; `round` is the design-review round (1-based) for
  * `gate == "design"`, `null` for a code review; `blockers` the reviewer's blocking-comment summaries. The top-level
  * `piece` tag is set for a code review and `None` for a (piece-less) design review.
  */
object ReviewRequestedChangesAction:
  val Kind: String = "review.request_changes"

  def draft(
      feature: FeatureId,
      gate: String,
      round: Option[Int],
      piece: Option[PieceId],
      blockers: Vector[String]
  ): ActionDraft =
    ActionDraft(
      feature = feature,
      piece = piece,
      actor = None,
      role = None,
      kind = Kind,
      payload = ujson.Obj(
        "gate" -> ujson.Str(gate),
        "round" -> round.map(r => ujson.Num(r.toDouble)).getOrElse(ujson.Null),
        "blockers" -> ujson.Arr.from(blockers.map(b => ujson.Str(b)))
      )
    )
