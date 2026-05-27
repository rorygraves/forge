package io.forge.git.branch

import cats.effect.IO
import io.forge.core.{BranchName, FeatureId, PieceId, PrNumber, Sha}
import io.forge.core.manifest.Manifest
import io.forge.git.branch.protection.RequiredChecksOverlay

/** Output of [[BranchManager.syncBase]] — the resolved base SHA after fast-forwarding. */
final case class BaseSnapshot(base: BranchName, sha: Sha)

/** Output of [[BranchManager.baseFreshness]] — whether the PR's base is current vs `expectedBaseSha`.
  *
  *   - [[BaseFreshness.UpToDate]] — PR head's base matches `expectedBaseSha`. Caller proceeds to CI/merge.
  *   - [[BaseFreshness.Updated]] — caller was configured with `baseFreshness.autoUpdate: true` (v1.2 §9) and
  *     `BranchManager` already issued `gh pr update-branch`. The carried `newBaseSha` is the post-update value of the
  *     PR's `baseRefOid` (re-read after `gh pr update-branch`), which the orchestrator persists into
  *     `manifest.pieces[i].baseSha` before re-entering `PieceAwaitingCi`. Without this SHA the next readiness pass
  *     would compare against the stale `expectedBaseSha` and re-trigger the auto-update on every poll.
  *   - [[BaseFreshness.Behind]] — PR is behind base and auto-update was off (or the PR isn't auto-updateable). Caller
  *     routes into `NeedsHumanIntervention("piece <p> PR is behind base", ResumeAfterHumanPush(...))`.
  */
sealed trait BaseFreshness extends Product with Serializable

object BaseFreshness:
  case object UpToDate extends BaseFreshness
  final case class Updated(newBaseSha: Sha) extends BaseFreshness
  final case class Behind(expected: Sha, observed: Sha) extends BaseFreshness

/** PR-C C3 — the §9 branch / PR orchestration surface. Pure when given a fake [[io.forge.git.cli.GitClient]] /
  * [[io.forge.git.cli.GhClient]]; every method returns an `IO[Either[BranchError, A]]` so the Slice-4 orchestrator can
  * fold failure into FSM transitions without `try`/`catch`.
  *
  * Method-level rationale lives on each declaration. Cross-cutting:
  *
  *   - **Branch names are derived, not stored** (BM7) — see [[BranchNaming]].
  *   - **`syncBase` preserves the current branch** (BM1 + the v0.7 reviewer fix). Branch-creation callers explicitly
  *     check out from the base SHA returned by `syncBase`.
  *   - **`createPieceBranch` returns `(BranchName, Sha)`; the orchestrator persists `manifest.pieces[i].baseSha`
  *     atomically with `status = InProgress` before transitioning** (carry-forward **S2-5**). `BranchManager` does
  *     **not** mutate the manifest.
  *   - **`baseFreshness(autoUpdate)`** — the auto-update path is the BranchManager's; the refuse-path is the FSM's.
  *     BranchManager surfaces a typed [[BaseFreshness]] outcome and lets the orchestrator vocabulary
  *     `NeedsHumanIntervention`.
  *   - **`tagSnapshot` is local-only by default** (§11.3 step 4). Pushing / pruning snapshot tags is gated on the
  *     `pushSnapshotTags: true` orchestrator path; [[pruneSnapshotTags]] enforces the §11.3 step 4 retention rule.
  */
trait BranchManager:

  /** §15 command-aware preflight. `manifest` is `None` only for [[ForgeCommand.New]] (no feature yet) and the read-only
    * / lock-only commands; every other command must pass the current manifest so the BranchManager can derive the
    * expected piece branch (BM6) and validate `forge resume` invariants.
    */
  def preflight(command: ForgeCommand, manifest: Option[Manifest]): IO[PreflightReport]

  /** §9 — `git fetch origin <base>` + fast-forward `refs/heads/<base>`. **Current branch is preserved** (BM1). Returns
    * the resolved base SHA on success; [[BranchError.BaseDiverged]] on local divergence.
    */
  def syncBase(base: BranchName): IO[Either[BranchError, BaseSnapshot]]

  /** §11.1 step 1 — derive `<branchPrefix>/<feature>/design` and `git checkout -B` from the resolved base. The base SHA
    * is consulted internally via [[syncBase]] (caller supplies the snapshot to avoid a second `git fetch`).
    */
  def createDesignBranch(
      feature: FeatureId,
      branchPrefix: String,
      base: BaseSnapshot
  ): IO[Either[BranchError, BranchName]]

  /** §11.4 step 1 — derive `<branchPrefix>/<feature>/<piece>`, `git checkout -B` from `base.sha`, and return the
    * `(branch, baseSha)` pair. The orchestrator (Slice 4 — carry-forward **S2-5**) is responsible for atomically
    * persisting `manifest.pieces[i].baseSha = baseSha` AND `status = InProgress` before transitioning out of branch
    * creation.
    */
  def createPieceBranch(
      feature: FeatureId,
      piece: PieceId,
      branchPrefix: String,
      base: BaseSnapshot
  ): IO[Either[BranchError, (BranchName, Sha)]]

  /** §9 — read the PR's current `baseRefOid` via `gh pr view`. If it matches `expectedBaseSha`, returns
    * [[BaseFreshness.UpToDate]]. Otherwise:
    *
    *   - `autoUpdate = true` → call `gh pr update-branch`, then re-read `baseRefOid` and return
    *     [[BaseFreshness.Updated]] carrying the new base SHA so the orchestrator can persist
    *     `manifest.pieces[i].baseSha` (otherwise the next readiness pass sees the same stale `expectedBaseSha` and
    *     loops on the auto-update).
    *   - `autoUpdate = false` → return [[BaseFreshness.Behind]] and let the orchestrator surface
    *     `NeedsHumanIntervention("piece <p> PR is behind base", ResumeAfterHumanPush(...))`.
    */
  def baseFreshness(
      pr: PrNumber,
      expectedBaseSha: Sha,
      autoUpdate: Boolean
  ): IO[Either[BranchError, BaseFreshness]]

  /** §11.3 step 5 / §11.4 step 6 — `git push origin <currentBranch> [--force-with-lease]`. A `ForceLeaseRejected`
    * underlying error becomes [[BranchError.ForceLeaseRejected]].
    */
  def pushCurrentBranch(forceWithLease: Boolean = false): IO[Either[BranchError, Unit]]

  /** §11.2 step 13 / §11.4 step 6 — `gh pr create --title <t> --body <b> --base <base> --head <currentBranch>`. */
  def createPr(title: String, body: String, base: BranchName): IO[Either[BranchError, PrNumber]]

  /** §9 / BM2 — `gh pr update-branch <pr>`. */
  def updatePrBranch(pr: PrNumber): IO[Either[BranchError, Unit]]

  /** §11.3 step 4 — `git tag <name> <sha>`. Local-only; pushing is the [[pushTag]] caller's choice. */
  def tagSnapshot(name: String, sha: Sha): IO[Either[BranchError, Unit]]

  /** §11.3 step 4 — `git push origin <name>`. Used by the `pushSnapshotTags: true` orchestrator path. */
  def pushTag(name: String): IO[Either[BranchError, Unit]]

  /** §11.3 step 4 — `git push origin :refs/tags/<name>`. Pairs with [[pushTag]] for the prune-on-rotate path. */
  def deleteRemoteTag(name: String): IO[Either[BranchError, Unit]]

  /** §11.3 step 4 retention — list local snapshot tags under `<branchPrefix>/_snapshots/<feature>/`, keep the
    * `retention` newest by round suffix, delete the rest locally. When `alsoRemote = true` (matching
    * `config.github.pushSnapshotTags: true`), the same pruned set is also removed from `origin`. Returns the vector of
    * pruned tag names.
    */
  def pruneSnapshotTags(
      feature: FeatureId,
      branchPrefix: String,
      retention: Int,
      alsoRemote: Boolean = false
  ): IO[Either[BranchError, Vector[String]]]

  /** §8.1 + §9 — read the branch-protection required-checks overlay, cache-first. C6 contract: rate-limit surfaces as
    * [[BranchError.RateLimited]]; an `Unauthorized` underlying error degrades to an empty overlay (Slice 4 logs
    * `harness.protection_unauthorized` as a pragmatic fallback).
    */
  def requiredChecksOverlay(
      feature: FeatureId,
      base: BranchName,
      epoch: Long
  ): IO[Either[BranchError, RequiredChecksOverlay]]
