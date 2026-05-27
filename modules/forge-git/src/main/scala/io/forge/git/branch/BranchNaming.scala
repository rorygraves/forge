package io.forge.git.branch

import io.forge.core.{BranchName, FeatureId, PieceId}

/** PR-C C4 — pure name-derivation helpers per design-rationale BM7.
  *
  * Branch names and snapshot tag names are derived from `branchPrefix + featureId + (pieceId | "design" |
  * "_snapshots/...")` — never stored. Manifest already exposes these via
  * [[io.forge.core.manifest.Manifest.designBranch]] / `pieceBranch` / `designSnapshotTag`, but callers below the
  * manifest layer (`BranchManager.tagSnapshot`'s round-aware variant, `BranchManager.pruneSnapshotTags`'s name parser)
  * need direct access without threading a `Manifest` through.
  */
object BranchNaming:

  /** §11.1 step 1 / §5.1: `<prefix>/<feature>/design`. */
  def designBranch(prefix: String, feature: FeatureId): BranchName =
    BranchName(s"$prefix/${feature.value}/design")

  /** §11.4 step 1 / §5.1: `<prefix>/<feature>/<piece>`. */
  def pieceBranch(prefix: String, feature: FeatureId, piece: PieceId): BranchName =
    BranchName(s"$prefix/${feature.value}/${piece.value}")

  /** §11.3 step 4 snapshot tag.
    *
    * `kind` is "design" for `<prefix>/_snapshots/<feature>/design-r<n>`; the parameter exists so the same helper can
    * derive piece-flavoured snapshot tags later without a second constructor.
    */
  def snapshotTag(prefix: String, feature: FeatureId, kind: String, round: Int): String =
    s"$prefix/_snapshots/${feature.value}/$kind-r$round"

  /** Snapshot-tag name prefix (no trailing slash) for [[BranchManager.pruneSnapshotTags]] enumeration. */
  def snapshotTagPrefix(prefix: String, feature: FeatureId): String =
    s"$prefix/_snapshots/${feature.value}/"

  /** Parse the `<kind>-r<round>` suffix on a snapshot tag produced by [[snapshotTag]]. Returns `None` when the suffix
    * doesn't match the canonical shape (so `pruneSnapshotTags` can ignore foreign tags rather than throw).
    */
  def parseSnapshotRound(tag: String, prefix: String, feature: FeatureId): Option[(String, Int)] =
    val head = snapshotTagPrefix(prefix, feature)
    if !tag.startsWith(head) then None
    else
      val suffix = tag.drop(head.length)
      val dashR = suffix.lastIndexOf("-r")
      if dashR <= 0 then None
      else
        val kind = suffix.substring(0, dashR)
        val tail = suffix.substring(dashR + 2)
        tail.toIntOption.map(n => (kind, n))
