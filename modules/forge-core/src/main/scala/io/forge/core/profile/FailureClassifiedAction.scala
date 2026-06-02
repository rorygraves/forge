package io.forge.core.profile

import io.forge.core.{FeatureId, PieceId}
import io.forge.core.log.ActionDraft

/** §19 `profile.failure_classified` action (Phase 3 / A5). Emitted on every classified gate failure (§8.2) **before**
  * the route is acted on, recording the perception so a replay reuses the recorded `Classification` / `route` rather
  * than re-invoking the (non-deterministic) sensor — the mechanism that keeps `Fsm.transition` replayable while
  * classification is agentic.
  *
  * `forge stats` folds these into a "fix-ups avoided / dollars saved" row: each `route == "RunLocalCommand"` is a
  * fix-up round that did **not** happen (the dogfood-#2 collapse, measured).
  *
  * Payload: `{ gate, kind, confidence, suggested, route, source, evidence }` — `gate ∈ {"ci","local"}`, `source ∈
  * {"rules","llm"}`, `kind`/`route` the enum labels, `suggested` the remediating [[CommandKind]] for a
  * `DeterministicFix` (else `null`).
  */
object FailureClassifiedAction:
  val Kind: String = "profile.failure_classified"

  def draft(
      feature: FeatureId,
      piece: PieceId,
      gate: String,
      classification: Classification,
      route: FixupRoute,
      source: String
  ): ActionDraft =
    ActionDraft(
      feature = feature,
      piece = Some(piece),
      actor = None,
      role = None,
      kind = Kind,
      payload = ujson.Obj(
        "gate" -> ujson.Str(gate),
        "kind" -> ujson.Str(classification.kind.asString),
        "confidence" -> ujson.Num(classification.confidence),
        "suggested" -> classification.suggested.map(k => ujson.Str(k.asString)).getOrElse(ujson.Null),
        "route" -> ujson.Str(route.label),
        "source" -> ujson.Str(source),
        "evidence" -> ujson.Str(classification.evidence)
      )
    )
