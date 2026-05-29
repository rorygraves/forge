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

  /** `.forge/specs/` — root of the committed per-feature spec tree. */
  val specsRoot: os.Path = repoForgeDir / "specs"

  /** `.forge/specs/<feature>/` — committed feature spec directory. */
  def featureSpecDir(id: FeatureId): os.Path = specsRoot / id.value

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

  // --- per-repo configuration (§18) ---

  /** `.forge/config.json` — per-repo configuration (§18). Missing file ⇒ §18 defaults apply. */
  val configFile: os.Path = repoForgeDir / "config.json"

  /** `.forge/overrides/` — per-key config override directory (§18 "per-repo overrides under `.forge/overrides/`"). */
  val overridesDir: os.Path = repoForgeDir / "overrides"

  /** `.forge/overrides/<key>.json` — per-key override of a top-level `config.json` key (e.g. `claude`, `staging`). */
  def overrideFile(key: String): os.Path = overridesDir / s"$key.json"

  // --- per-feature runtime state (gitignored) ---

  /** `.forge/log/<feature>.jsonl` — local canonical runtime log (§4 invariant). */
  def featureLog(id: FeatureId): os.Path = repoForgeDir / "log" / s"${id.value}.jsonl"

  /** `.forge/state/<feature>.json` — local rebuildable state cache. */
  def stateFile(id: FeatureId): os.Path = repoForgeDir / "state" / s"${id.value}.json"

  /** `.forge/state/<feature>.poll-baselines.json` — sibling state file holding the per-PR `PollBaseline`s the
    * orchestrator's `PRWatcher` poll loop carries (§S3-7 round-2 / carry-forward S4-1). `Manifest` / `Piece` carry no
    * baseline fields (the manifest is the §4 committed source of truth; the poll baseline is local-only state that
    * mutates on every gh round-trip), so the baseline map lives here keyed across the design PR + every piece PR. Same
    * gitignored `.forge/state/` family as `stateFile`.
    */
  def pollBaselineFile(id: FeatureId): os.Path = repoForgeDir / "state" / s"${id.value}.poll-baselines.json"

  /** `.forge/state/.lock` — OS-level lock file (§13). */
  val lockFile: os.Path = repoForgeDir / "state" / ".lock"

  /** `.forge/state/.lock.json` — lock holder metadata (§13). */
  val lockMetadataFile: os.Path = repoForgeDir / "state" / ".lock.json"

  // --- per-user reviewer assets (§10.2 / §14.3 / §17 slice 4 PR-A) ---

  /** `~/.forge/schemas/` — per-user reviewer JSON Schemas (one per §7.1 reviewer method). */
  val userSchemasDir: os.Path = userForgeDir / "schemas"

  /** `~/.forge/prompts/` — per-user reviewer + driver system-prompt files (per CLI × method). */
  val userPromptsDir: os.Path = userForgeDir / "prompts"

  /** `~/.forge/templates/` — per-user PR-body / decomposition / answer templates (§11.4 / §7.7 / §14.3). */
  val userTemplatesDir: os.Path = userForgeDir / "templates"

  // --- prices (§7.10(b)) ---

  /** `~/.forge/prices.json` — per-user model price table. */
  val pricesUser: os.Path = userForgeDir / "prices.json"

  /** `.forge/prices.json` — per-repo override of the user price table. */
  val pricesRepo: os.Path = repoForgeDir / "prices.json"
