package io.forge.agents

import io.forge.core.*

/** §7.1 reviewer-method types. These are the schemas a reviewer connector binds against
  * (`~/.forge/schemas/{design-review,code-review,refine}.json`). The Scala model is a mirror of those JSON schemas.
  *
  * NOTE(slice-1): kept minimal until the actual schemas exist.
  */

enum ReviewVerdict:
  case Approve
  case RequestChanges

/** Inline blocker from a code review (§10.2). `path = None` means the blocker belongs in the summary comment rather
  * than as an inline thread.
  */
final case class ReviewBlocker(
    summary: String,
    path: Option[String],
    line: Option[Int],
    anchorText: Option[String]
)

// --- Design review ----

final case class DesignReviewInput(
    featureId: FeatureId,
    round: Int,
    designMarkdown: String
)

final case class DesignReview(
    verdict: ReviewVerdict,
    blockers: Vector[ReviewBlocker],
    questions: Vector[Question],
    summary: String
)

// --- PR review ----

final case class PrReviewInput(
    featureId: FeatureId,
    pieceId: PieceId,
    prNumber: PrNumber,
    pieceSpec: String,
    diff: String,
    changedFiles: Vector[String]
)

final case class PrReview(
    verdict: ReviewVerdict,
    blockers: Vector[ReviewBlocker],
    summary: String
)

// --- Refinery ----

enum RefineOutcome:
  case NoChange
  case UpdatePlan
  case ReopenDesign

/** §14.3 refine input: just-merged piece plus the design it came from. */
final case class RefineInput(
    featureId: FeatureId,
    mergedPieceId: PieceId,
    designMarkdown: String,
    manifestJson: String
)

/** §14.3 refine result. `outcome = UpdatePlan` carries a patch the orchestrator later validates against the live
  * manifest.
  */
final case class RefineResult(
    outcome: RefineOutcome,
    reason: String,
    patchJson: Option[String]
)
