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

// --- RepoProfiler (§7.11 sensor) ----

/** One file the `RepoProfiler` sensor reads to perceive the repo, packaged for the prompt body. `path` is repo-relative
  * (e.g. `build.sbt`, `.github/workflows/ci.yml`) so the model can reason about *where* a convention is declared.
  */
final case class RepoFile(path: String, content: String)

/** §7.11 `RepoProfiler` input — the repo facts the sensor reads to produce a [[io.forge.core.profile.RepoProfile]]: the
  * agent docs (`AGENTS.md` / `CLAUDE.md` / `README.md`), the build files, and the CI workflow files. Repo-level (no
  * feature), mirroring [[io.forge.core.profile.ProfileStore]]. The connector renders these into a stable prompt body
  * ([[ReviewerPrompts.repoProfileBody]]) and decodes the structured reply via [[ReviewDecoders.repoProfile]].
  *
  * `readmeDoc` is the repo's `README.md` (P2): repos often document the canonical package manager, test command, or
  * merge requirements only there, so it is a first-class profiler input alongside the agent guides.
  */
final case class RepoProfilerInput(
    repoName: String,
    agentsDoc: Option[String],
    claudeDoc: Option[String],
    readmeDoc: Option[String],
    buildFiles: Vector[RepoFile],
    workflowFiles: Vector[RepoFile]
)

// --- FailureClassifier (§7.11 sensor, Task 3.1.4) ----

/** §7.11 `FailureClassifier` input — the gate failure the LLM sensor perceives into a
  * [[io.forge.core.profile.Classification]]. Consulted **only** when the deterministic
  * [[io.forge.core.profile.RuleBasedFailureClassifier]] returns a low-confidence `Unknown` (the §7.11 cost lever): the
  * rules baseline handles every dogfood case for free; the LLM tail pays a reviewer-call only for the genuinely
  * ambiguous failure. `profile` is carried so the model can name a `suggested` [[io.forge.core.profile.CommandKind]]
  * the repo actually exposes (a `DeterministicFix` is only routable to a local run when the profile declares the
  * matching autofix command). The connector renders these into a stable prompt body
  * ([[ReviewerPrompts.classifyFailureBody]]) and decodes the structured reply via
  * [[ReviewDecoders.failureClassification]].
  */
final case class FailureClassifierInput(
    featureId: FeatureId,
    gate: String,
    failureLog: String,
    profile: io.forge.core.profile.RepoProfile
)

// --- ConventionLearner (§7.11 sensor, Task 3.2) ----

/** One classified gate failure the feature hit during its run, distilled from a §19 `profile.failure_classified` action
  * — the failure→remedy signal the `ConventionLearner` mines. `route` is the [[io.forge.core.profile.FixupRoute]] label
  * the spine chose (a `DriverFixup` is a round the repo paid for; a `RunLocalCommand` shows the profile already had the
  * remedy), so the learner can tell which failures a new convention would have *avoided*.
  */
final case class ObservedFailure(gate: String, kind: String, suggested: Option[String], route: String, evidence: String)

/** One reviewer `RequestChanges` blocker the feature hit during its run, distilled from a §19 `review.request_changes`
  * action — the *recurring-reviewer-comment* signal the `ConventionLearner` mines (decision D8) alongside
  * [[ObservedFailure]] (the failure→remedy half). A blocker that recurs across pieces/rounds is exactly the kind of
  * thing a CLAUDE.md note could pre-empt. `gate ∈ {"design","code"}`; `round` is the design-review round for a design
  * gate, `None` for a code review.
  */
final case class ObservedReviewerComment(gate: String, round: Option[Int], blocker: String)

/** §7.11 `ConventionLearner` input — what the post-run sensor reads to propose
  * [[io.forge.core.profile.ConventionDeltas]] after a feature reaches `FeatureDone` (§11.7). It mines two signals
  * against the current `profile` and `claudeDoc` to propose (a) gate commands the profile was missing and (b) a
  * CLAUDE.md addition, **proposing only** — never mutating either autonomously:
  *
  *   - `failures` — the run's classified gate failures (the failure→remedy half), and
  *   - `reviewerComments` — the run's reviewer `RequestChanges` blockers (the recurring-reviewer-comment half, decision
  *     D8).
  *
  * The orchestrator gathers both from the feature's action log and only consults the sensor when **either** is
  * non-empty (the §7.11 cost lever — no failure *and* no reviewer-comment signal, no sensor call). The connector
  * renders these into a stable prompt body ([[ReviewerPrompts.learnConventionsBody]]) and decodes the structured reply
  * via [[ReviewDecoders.conventionDeltas]].
  */
final case class ConventionLearnerInput(
    featureId: FeatureId,
    profile: io.forge.core.profile.RepoProfile,
    claudeDoc: Option[String],
    failures: Vector[ObservedFailure],
    reviewerComments: Vector[ObservedReviewerComment]
)
