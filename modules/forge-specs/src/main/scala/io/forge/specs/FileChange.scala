package io.forge.specs

/** Task 1.4.5 E1 — one working-tree change, the unit [[ChangeCollector.classify]] decides on.
  *
  * A change is always a *file* (git tracks files, never bare directories), so `path` is a file path and the
  * classification rules in §10.1 reason about a single path. `path` is absolute; [[ChangeCollector]] relativises it
  * against the repo root before matching patterns.
  *
  * **`gitIgnored` deviation from the E1 sketch.** §10.1 rule 4 ("ignored by `.gitignore` → `Deny` unless under
  * `.forge/specs/...`") needs to know whether git ignores the path — knowledge the pure, git-less `classify` cannot
  * compute itself (`forge-specs` does not depend on `forge-git`). So `FileChange` carries the bit: the orchestrator
  * (Slice 1.4b Task 1.4.10), which owns the git seam, populates it from `git status --porcelain --ignored` (the `!!`
  * status), and `classify` reads it. Defaults to `false` so the common case (a tracked edit) constructs tersely.
  */
final case class FileChange(
    path: os.Path,
    kind: FileChangeKind,
    gitIgnored: Boolean = false
)

/** The git-porcelain status of a [[FileChange]]. `Renamed` carries the source path for audit / PR-body rendering;
  * [[ChangeCollector]] classifies the destination (`FileChange.path`), since that is where the bytes land.
  */
enum FileChangeKind:
  case Added
  case Modified
  case Deleted
  case Renamed(from: os.Path)
