package io.forge.core.profile

import upickle.default.ReadWriter

/** §11.7 `ConventionLearner` output (Phase 3 / A5, Task 3.2) — what the post-run learner *proposes* after a feature
  * reaches `FeatureDone`. The learner is a **perceive-and-propose** sensor: it never gates the transition and never
  * mutates a doc autonomously. Two proposal channels, mirroring the §11.7 contract:
  *
  *   - `addCommands` — repo gate commands the profile was missing that would have avoided an observed failure (e.g. the
  *     `Format` autofix the dogfood-#2/#3 runs kept paying fix-up rounds for). These are merged into the committed
  *     `.forge/profile.json` via [[ProfileStore.save]] ([[applyTo]] / [[freshCommands]] dedup against what the profile
  *     already declares), landing as a normal, human-reviewable git diff. **Additive only** — the learner never
  *     proposes removing or rewriting an existing command (that needs human judgment; an additive delta is the safe
  *     floor).
  *   - `claudeMdProposal` — a proposed addition to the repo's own CLAUDE.md ("the implement driver must run `sbt
  *     scalafmtAll` before settling"). **Never applied** by Forge: persisted for a human to review and open as a PR.
  *     The §11.7 "no autonomous doc mutation — it proposes, the human merges" guarantee (the auto-PR-open step is a
  *     deferred follow-up, see design-3.0 §4).
  *
  * The whole value is advisory: a learner timeout/failure is logged and dropped (same posture as the §14.2 refinery).
  */
final case class ConventionDeltas(
    addCommands: Vector[RepoCommand],
    claudeMdProposal: Option[ClaudeMdProposal],
    summary: String
) derives ReadWriter:

  /** The commands in `addCommands` the profile does not already declare (deduped by `(kind, argv)`). The set actually
    * applied by [[applyTo]] and recorded in the §19 `profile.conventions_learned` audit action.
    */
  def freshCommands(profile: RepoProfile): Vector[RepoCommand] =
    val existing = profile.commands.map(c => (c.kind, c.argv)).toSet
    addCommands.filterNot(c => existing.contains((c.kind, c.argv)))

  /** Merge the fresh commands into `profile` (additive; existing commands untouched). When there is nothing fresh the
    * returned profile is `==` the input, so the orchestrator can skip the `ProfileStore.save` write entirely.
    */
  def applyTo(profile: RepoProfile): RepoProfile =
    val fresh = freshCommands(profile)
    if fresh.isEmpty then profile else profile.copy(commands = profile.commands ++ fresh)

/** A proposed addition to the repo's CLAUDE.md the learner mined from this run. Not applied — persisted for human
  * review (§11.7 "no autonomous doc mutation"). `rationale` is *why* (the failure→remedy pattern it observed);
  * `suggestedAddition` is the convention text to add.
  */
final case class ClaudeMdProposal(rationale: String, suggestedAddition: String) derives ReadWriter
