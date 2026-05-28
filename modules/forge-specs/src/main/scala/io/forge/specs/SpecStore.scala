package io.forge.specs

import cats.effect.IO
import io.forge.core.{FeatureId, PieceId}
import io.forge.core.manifest.Manifest

/** Task 1.4.3 C2 — the §4 source-of-truth boundary for a feature's committed spec assets:
  *
  *   - `manifest.json` — machine source of truth (§4 / §5.1). Loads validate against [[Manifest.validate]]; callers
  *     receive a known-good [[Manifest]] or a typed [[SpecStoreError]].
  *   - `design.md` — feature design body (§11.1).
  *   - `decomposition.md` — rendered view of the manifest, **M1** / §5.3. The methods exist explicitly because `forge
  *     reconcile` (Slice 1.4b Task 1.4.13 / **M6**) reads back this file via editable-region markers, so both load and
  *     save need typed paths.
  *   - `pieces/<p>.md` — per-piece spec body (§5.1).
  *
  * `forge-core`'s [[io.forge.core.manifest.ManifestStore]] is the **read-only** path used by the rebuild pipeline
  * (`RebuildState.run`). `SpecStore` is the read+write counterpart for the orchestrator side (Slice 1.4b Task 1.4.10)
  * and `DocSync` (Task 1.4.4). The two error vocabularies differ because their callers handle errors differently —
  * `RebuildError.ManifestLoadFailed` lifts into the rebuild pipeline; [[SpecStoreError]] lifts into orchestrator
  * command output.
  *
  * Save semantics are atomic per §11.5 step 1: temp file in the same directory, `SYNC` the temp's contents,
  * `Files.move` with `ATOMIC_MOVE`, then fsync the parent directory. A reader observing the target path always sees
  * either the previous committed view or the new one — never a half-written file. This is the **S2-5** writer-side
  * invariant the orchestrator depends on; the orchestrator-loop test that asserts it lands at Slice 1.4b Task 1.4.11.
  */
trait SpecStore:

  def loadManifest(feature: FeatureId): IO[Either[SpecStoreError, Manifest]]
  def saveManifest(feature: FeatureId, manifest: Manifest): IO[Either[SpecStoreError, Unit]]

  def loadDesign(feature: FeatureId): IO[Either[SpecStoreError, String]]
  def saveDesign(feature: FeatureId, body: String): IO[Either[SpecStoreError, Unit]]

  def loadDecomposition(feature: FeatureId): IO[Either[SpecStoreError, String]]
  def saveDecomposition(feature: FeatureId, body: String): IO[Either[SpecStoreError, Unit]]

  def loadPieceSpec(feature: FeatureId, piece: PieceId): IO[Either[SpecStoreError, String]]
  def savePieceSpec(feature: FeatureId, piece: PieceId, body: String): IO[Either[SpecStoreError, Unit]]
