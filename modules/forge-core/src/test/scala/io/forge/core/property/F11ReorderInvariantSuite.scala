package io.forge.core.property

import io.forge.core.gen.Generators
import io.forge.core.manifest.{Manifest, ManifestPatch, PieceStatus}

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** PR-F F11 — §17 slice-2 invariant 12: §5.5 reorder invariant holds across arbitrary patch sequences.
  *
  * §5.5 says merged pieces must form a contiguous prefix of the piece list, in their original merge order. The
  * `Manifest.validate` check (and every `ManifestPatch.applyTo` invocation) enforces this; F11 wraps the existing
  * unit-level rule in a ScalaCheck property over generated patch sequences applied to arbitrary legal manifests.
  *
  * The property is two-sided:
  *   - any patch that `applyTo` *accepts* must produce a manifest whose merged prefix is identical to the input's
  *     merged prefix in both ids and order;
  *   - any patch that *would* shuffle the merged prefix is *rejected*.
  */
class F11ReorderInvariantSuite extends ScalaCheckSuite:

  /** Manifest with 1–6 pieces and any merged-prefix length (including 0). */
  private val genManifest: Gen[Manifest] = Generators.genManifest

  property("F11 — accepted patches preserve the merged prefix exactly") {
    forAll(genManifest) { (m: Manifest) =>
      forAll(Generators.genManifestPatch(m)) { (patch: ManifestPatch) =>
        patch.applyTo(m) match
          case Left(_) => proved
          case Right(updated) =>
            val initialMergedPrefix = m.pieces.takeWhile(_.status == PieceStatus.Merged).map(_.id)
            val updatedMergedPrefix = updated.pieces.takeWhile(_.status == PieceStatus.Merged).map(_.id)
            (initialMergedPrefix == updatedMergedPrefix) :|
              s"merged-prefix order changed: initial=$initialMergedPrefix, updated=$updatedMergedPrefix"
      }
    }
  }

  property("F11 — every accepted-patch result re-validates clean") {
    forAll(genManifest) { (m: Manifest) =>
      forAll(Generators.genManifestPatch(m)) { (patch: ManifestPatch) =>
        patch.applyTo(m) match
          case Left(_) => proved
          case Right(updated) =>
            updated.validate match
              case Right(_) => proved
              case Left(errs) => falsified :| s"applyTo produced a manifest that fails validate: $errs"
      }
    }
  }
