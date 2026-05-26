package io.forge.core.paths

import io.forge.core.{FeatureId, PieceId}

/** §4 + §18 path helper. Every `.forge/...` location flows through this single object — no other call site in the
  * codebase hardcodes a `.forge/...` literal. PR-A A4 graduates this from a smell test to a build-enforced rule.
  *
  * Phase-4 multi-instance support (roadmap §2.6, §7.1) swaps the constructor to re-root state / log / lock at
  * `~/.forge/instances/<name>/` while leaving the `.forge/specs/` family anchored at `repoRoot`; the rest of the
  * codebase keeps using `paths.xxx(...)` unchanged.
  *
  * The per-user `~/.forge/prices.json` (§7.10(b)) is resolved via `os.home` so tests can stub it through a custom
  * `home` parameter.
  */
final class ForgePaths(val repoRoot: os.Path, val home: os.Path = os.home):

  /** Root of the per-repo Forge directory. */
  val repoForgeDir: os.Path = repoRoot / ".forge"

  /** Root of the per-user Forge directory (`~/.forge`). */
  val userForgeDir: os.Path = home / ".forge"

  // --- per-feature spec assets (committed) ---

  /** `.forge/specs/<feature>/` — committed feature spec directory. */
  def featureSpecDir(id: FeatureId): os.Path = repoForgeDir / "specs" / id.value

  /** `.forge/specs/<feature>/design.md`. */
  def design(id: FeatureId): os.Path = featureSpecDir(id) / "design.md"

  /** `.forge/specs/<feature>/manifest.json` — machine source of truth (§4 invariant). */
  def manifest(id: FeatureId): os.Path = featureSpecDir(id) / "manifest.json"

  /** `.forge/specs/<feature>/decomposition.md` — rendered view of the manifest. */
  def decomposition(id: FeatureId): os.Path = featureSpecDir(id) / "decomposition.md"

  /** `.forge/specs/<feature>/pieces/<p>.md`. */
  def pieceSpec(featureId: FeatureId, pieceId: PieceId): os.Path =
    featureSpecDir(featureId) / "pieces" / s"${pieceId.value}.md"

  /** `.forge/specs/<feature>/audit/`. */
  def auditDir(id: FeatureId): os.Path = featureSpecDir(id) / "audit"

  /** `.forge/specs/<feature>/audit/<name>` — leaf name supplied by the caller (e.g. `spec-answers.md`). */
  def audit(id: FeatureId, name: String): os.Path = auditDir(id) / name

  // --- per-feature runtime state (gitignored) ---

  /** `.forge/log/<feature>.jsonl` — local canonical runtime log (§4 invariant). */
  def featureLog(id: FeatureId): os.Path = repoForgeDir / "log" / s"${id.value}.jsonl"

  /** `.forge/state/<feature>.json` — local rebuildable state cache. */
  def stateFile(id: FeatureId): os.Path = repoForgeDir / "state" / s"${id.value}.json"

  /** `.forge/state/.lock` — OS-level lock file (§13). */
  val lockFile: os.Path = repoForgeDir / "state" / ".lock"

  /** `.forge/state/.lock.json` — lock holder metadata (§13). */
  val lockMetadataFile: os.Path = repoForgeDir / "state" / ".lock.json"

  // --- prices (§7.10(b)) ---

  /** `~/.forge/prices.json` — per-user model price table. */
  val pricesUser: os.Path = userForgeDir / "prices.json"

  /** `.forge/prices.json` — per-repo override of the user price table. */
  val pricesRepo: os.Path = repoForgeDir / "prices.json"
