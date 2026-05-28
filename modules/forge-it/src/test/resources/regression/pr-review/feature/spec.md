# Piece: reviewer-asset schema tightening + destination-kind guard

Review round on the reviewer-asset shipping PR. Two findings:

1. `refine.json` did not constrain its `patch` field to the real
   `ManifestPatch(reason, ops)` wire shape, and a stray `patch` was silently
   permitted on `no_change` / `reopen_design` outcomes. The schema must model
   each `ManifestPatchOp` variant via the uPickle `$type` discriminator and
   forbid `patch` when `outcome ∈ {no_change, reopen_design}`.
2. `AssetInstaller` treated any existing destination as a clean `Skipped`,
   even a directory or FIFO at the leaf path. Reading such a leaf as a file
   later fails with a generic IO error far from the cause. The installer must
   surface a typed `InvalidExistingDestination` when the existing destination
   is not a regular file.

## Acceptance criteria

- `refine.json` models `ManifestPatch` with `oneOf` branches per
  `ManifestPatchOp` variant and an `allOf` branch forbidding `patch` on the
  two no-patch outcomes.
- A schema-validation suite uses a real JSON Schema validator against payloads
  built from real `ManifestPatch` / `ManifestPatchOp` values so the schema and
  the wire shape cannot drift silently.
- `AssetInstaller` returns `InvalidExistingDestination(dest, kind)` for a
  non-file destination instead of falsely reporting `Skipped`.
