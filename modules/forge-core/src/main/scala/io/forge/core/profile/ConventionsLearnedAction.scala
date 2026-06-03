package io.forge.core.profile

import io.forge.core.{FeatureId, PrNumber}
import io.forge.core.log.ActionDraft

/** §19 `profile.conventions_learned` action (Phase 3 / A5, Task 3.2). Recorded out-of-band after a feature reaches
  * `FeatureDone` when the `ConventionLearner` (§11.7) was consulted and settled, so the run records *what the learner
  * proposed* — observable in the TUI / `forge stats`. Like `profile.local_gate` (decision D3) it is a no-op `Replay`
  * projection: the audit shows the learner ran, but the FSM never reads it (the profile delta reaches a later run only
  * as a committed `.forge/profile.json` input — the replayability invariant).
  *
  * Payload: `{ addedCommands: [argv...], hasClaudeMdProposal: bool, claudeMdPrNumber: int|null, summary }` —
  * `addedCommands` is the deduped set of commands actually merged into the profile
  * ([[ConventionDeltas.freshCommands]]); `hasClaudeMdProposal` flags that the learner proposed a CLAUDE.md edit;
  * `claudeMdPrNumber` is the PR Forge opened for that edit (§11.7), or `null` when there was no proposal or the open
  * failed and the proposal was persisted locally as a fallback (D9).
  *
  * **§19 schema gap (decision D7, design-3.0 §4):** the 1.7 §19 table enumerates only `profile.snapshot` /
  * `profile.failure_classified`; `profile.local_gate` (D3) and this kind are additive `profile.*` audit kinds to fold
  * into the table in the next contract revision.
  */
object ConventionsLearnedAction:
  val Kind: String = "profile.conventions_learned"

  def draft(
      feature: FeatureId,
      addedCommands: Vector[RepoCommand],
      hasClaudeMdProposal: Boolean,
      claudeMdPrNumber: Option[PrNumber],
      summary: String
  ): ActionDraft =
    ActionDraft(
      feature = feature,
      piece = None,
      actor = None,
      role = None,
      kind = Kind,
      payload = ujson.Obj(
        "addedCommands" -> ujson.Arr.from(addedCommands.map(c => ujson.Str(c.argv.mkString(" ")))),
        "hasClaudeMdProposal" -> ujson.Bool(hasClaudeMdProposal),
        "claudeMdPrNumber" -> claudeMdPrNumber.map(p => ujson.Num(p.value.toDouble)).getOrElse(ujson.Null),
        "summary" -> ujson.Str(summary)
      )
    )
