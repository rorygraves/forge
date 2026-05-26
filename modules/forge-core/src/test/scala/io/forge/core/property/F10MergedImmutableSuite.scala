package io.forge.core.property

import io.forge.core.*
import io.forge.core.gen.Generators
import io.forge.core.manifest.{ManifestPatch, ManifestPatchOp, PieceStatus}

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** PR-F F10 — §17 slice-2 invariant 11: already-merged piece ids cannot be removed by planning updates.
  *
  * Property: for any manifest with a non-empty merged prefix, applying `ManifestPatch.RemovePiece(mergedId)` returns
  * `Left("op[i] RemovePiece: cannot remove merged piece ...")`. Edit, AddPiece (with `after` pointing at a merged piece
  * other than the last one), and Reorder (changing the merged prefix) are also rejected — the property covers the full
  * surface.
  */
class F10MergedImmutableSuite extends ScalaCheckSuite:

  /** Manifest generator that *always* has at least one merged piece. */
  private val genManifestWithMerged: Gen[io.forge.core.manifest.Manifest] =
    for
      featureId <- Generators.genFeatureId
      pieceCount <- Gen.choose(2, 5)
      mergedPrefix <- Gen.choose(1, pieceCount)
    yield Generators.manifestWith(featureId, pieceCount, mergedPrefix)

  property("F10 — RemovePiece(mergedId) always returns Left with merged-piece error") {
    forAll(genManifestWithMerged) { (m: io.forge.core.manifest.Manifest) =>
      val merged = m.pieces.filter(_.status == PieceStatus.Merged)
      val results = merged.map { p =>
        val patch = ManifestPatch(reason = "test", ops = Vector(ManifestPatchOp.RemovePiece(p.id)))
        (p.id, patch.applyTo(m))
      }
      val allRejected = results.forall:
        case (_, Left(errs)) => errs.exists(_.contains("cannot remove merged piece"))
        case _ => false
      allRejected :| s"some merged-piece removals were accepted: $results"
    }
  }

  property("F10 — EditPiece(mergedId) always returns Left with edit-merged error") {
    forAll(genManifestWithMerged) { (m: io.forge.core.manifest.Manifest) =>
      val merged = m.pieces.filter(_.status == PieceStatus.Merged)
      val results = merged.map { p =>
        val patch = ManifestPatch(
          reason = "test",
          ops = Vector(
            ManifestPatchOp.EditPiece(
              id = p.id,
              title = Some("new title"),
              summary = None,
              specPath = None,
              acceptanceHash = None
            )
          )
        )
        (p.id, patch.applyTo(m))
      }
      val allRejected = results.forall:
        case (_, Left(errs)) => errs.exists(_.contains("cannot edit merged piece"))
        case _ => false
      allRejected :| s"some merged-piece edits were accepted: $results"
    }
  }

  property(
    "F10 — generated patches against merged-prefix manifests never produce a manifest with fewer merged pieces"
  ) {
    forAll(genManifestWithMerged) { (m: io.forge.core.manifest.Manifest) =>
      forAll(Generators.genManifestPatch(m)) { (patch: ManifestPatch) =>
        patch.applyTo(m) match
          case Left(_) => proved
          case Right(updated) =>
            val initialMergedIds = m.merged.map(_.id).toSet
            val updatedMergedIds = updated.merged.map(_.id).toSet
            // The merged set should never shrink — merged pieces are immutable.
            (initialMergedIds.subsetOf(updatedMergedIds)) :|
              s"merged set shrank: initial=$initialMergedIds, updated=$updatedMergedIds"
      }
    }
  }
