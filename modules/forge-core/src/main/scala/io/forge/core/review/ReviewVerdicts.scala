package io.forge.core.review

import io.forge.core.Question
import io.forge.core.manifest.ManifestPatch

import upickle.default.ReadWriter

/** §6 / PR-B B0 — core-side reviewer-verdict summaries.
  *
  * The FSM consumes the *decision*, not the full reviewer payload (`forge-agents`' `DesignReview` / `PrReview` /
  * `RefineResult` carry the rich shapes). `forge-agents` will project its rich types into these summaries at the call
  * site in Slice 4; the FSM only ever sees the summaries. This keeps `forge-core` free of any `forge-agents`
  * dependency.
  *
  * §11 cross-references:
  *   - `DesignReviewVerdict`: §11.2 design-review revision rounds.
  *   - `PrReviewVerdict`: §11.5 code-review verdicts on a piece PR.
  *   - `RefineVerdict`: §11.7 refinery advance step (also §14.3).
  */

/** §11.2 design-review outcome. `BlockingQuestions` is the one variant that pushes the FSM into
  * `DesignNeedsHumanInput`; the other two map to `DesignReady` (Approve) and a new revision round (`RequestChanges`).
  */
enum DesignReviewVerdict derives ReadWriter:
  case Approve
  case RequestChanges(blockers: Vector[String])
  case BlockingQuestions(questions: Vector[Question])

/** §11.5 code-review outcome on a piece PR. */
enum PrReviewVerdict derives ReadWriter:
  case Approve
  case RequestChanges(blockers: Vector[String])

/** §11.7 / §14.3 refinery outcome.
  *
  *   - `NoChange`: advance to next piece (or `FeatureDone`).
  *   - `UpdatePlan(patch)`: enter `PlanningUpdate(reason, patch)` for human approval.
  *   - `ReopenDesign(reason)`: reopen design from scratch.
  */
enum RefineVerdict derives ReadWriter:
  case NoChange
  case UpdatePlan(patch: ManifestPatch)
  case ReopenDesign(reason: String)
