package io.forge.core.paths

import io.forge.core.{FeatureId, PieceId}

/** §4 + §18 path helper. Every `.forge/...` location flows through this single object — no other call site in the
  * codebase hardcodes a `.forge/...` literal. PR-A A4 graduates this from a smell test to a build-enforced rule.
  *
  * **Phase-4 B1 (committed-vs-local split — `forge-design-2.0.md` §3.1 B1 / §4.3).** This is the worker-boundary path
  * seam. Two roots, not one:
  *   - `repoRoot` anchors the **committed** family — the versioned inputs + review history that live in git and merge
  *     back through PRs: `.forge/specs/` (design / `manifest.json` / decomposition / `pieces/` / `audit/`),
  *     `config.json`, `profile.json`, `overrides/`, and the per-repo `prices.json`. Under Phase-4 isolation "repoRoot"
  *     is the *worker's clone* root, never the registered source checkout.
  *   - `localRoot` anchors the **local-runtime** family — gitignored, canonical-but-rebuildable state the worker owns:
  *     the action log (`.forge/log/<feature>.jsonl`, the §4 local-canonical source of truth — its *location* re-roots,
  *     its bytes/semantics do not), the state cache + poll baselines (`.forge/state/`), and the lock + its metadata.
  *
  * `localRootOpt` **defaults to `None`, which resolves [[localRoot]] to `repoRoot`**, so single-repo v1 usage is
  * byte-identical (log/state/lock stay in the repo checkout). A Phase-4 instance constructs `ForgePaths(repoRoot =
  * <worker clone>, localRootOpt = Some(<instance>/workers/<id>))` to re-root the runtime out of the checkout while the
  * committed family stays in git. The rest of the codebase keeps using `paths.xxx(...)` unchanged — this is a
  * constructor change, not a callsite sweep (enforced clean by `ForgePathsSuite`'s `os.walk` smell sweep).
  * (`localRootOpt` is an `Option` rather than a `= repoRoot` default because a Scala constructor default cannot
  * reference an earlier parameter; [[localRoot]] does the resolution in the body.)
  *
  * The per-user `~/.forge/prices.json` (§7.10(b)) is resolved via `os.home` so tests can stub it through a custom
  * `home` parameter.
  */
final class ForgePaths(val repoRoot: os.Path, val home: os.Path = os.home, localRootOpt: Option[os.Path] = None):

  /** The root the **local-runtime** family re-roots under — the supplied `localRootOpt`, or `repoRoot` when absent (v1
    * single-repo layout). See [[localForgeDir]].
    */
  val localRoot: os.Path = localRootOpt.getOrElse(repoRoot)

  /** Root of the per-repo Forge directory — anchors the **committed** family (specs / config / profile / overrides). */
  val repoForgeDir: os.Path = repoRoot / ".forge"

  /** Root of the local-runtime Forge directory — anchors the **gitignored** family (log / state / lock). Equals
    * [[repoForgeDir]] when `localRoot` defaults to `repoRoot` (v1 single-repo layout); re-roots under the worker's
    * instance directory under Phase-4 isolation (B1).
    */
  val localForgeDir: os.Path = localRoot / ".forge"

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

  /** `.forge/profile.json` — committed per-repo adaptation profile (Phase 3 / design-rationale A5). A single repo-level
    * [[io.forge.core.profile.RepoProfile]] (build tool, gate commands tiered by determinism, commit identity, workflow
    * shape), snapshotted + hashed into the action log per run (§19 `profile.snapshot`) so `Fsm.transition` stays
    * replayable against the profile-as-of-that-run. Committed (not gitignored) — it is a versioned input, mutated only
    * *between* runs by the agentic sensors, never mid-transition.
    */
  val profileFile: os.Path = repoForgeDir / "profile.json"

  /** `.forge/overrides/` — per-key config override directory (§18 "per-repo overrides under `.forge/overrides/`"). */
  val overridesDir: os.Path = repoForgeDir / "overrides"

  /** `.forge/overrides/<key>.json` — per-key override of a top-level `config.json` key (e.g. `claude`, `staging`). */
  def overrideFile(key: String): os.Path = overridesDir / s"$key.json"

  // --- per-feature runtime state (gitignored; re-rooted under localForgeDir per B1) ---

  /** `.forge/log/<feature>.jsonl` — local canonical runtime log (§4 invariant). Re-roots under [[localForgeDir]] (B1):
    * the worker owns this file; its location moves under Phase-4 isolation, its format/replay semantics do not.
    */
  def featureLog(id: FeatureId): os.Path = localForgeDir / "log" / s"${id.value}.jsonl"

  /** `.forge/state/<feature>.json` — local rebuildable state cache. Re-roots under [[localForgeDir]] (B1). */
  def stateFile(id: FeatureId): os.Path = localForgeDir / "state" / s"${id.value}.json"

  /** `.forge/state/<feature>.poll-baselines.json` — sibling state file holding the per-PR `PollBaseline`s the
    * orchestrator's `PRWatcher` poll loop carries (§S3-7 round-2 / carry-forward S4-1). `Manifest` / `Piece` carry no
    * baseline fields (the manifest is the §4 committed source of truth; the poll baseline is local-only state that
    * mutates on every gh round-trip), so the baseline map lives here keyed across the design PR + every piece PR. Same
    * gitignored `.forge/state/` family as `stateFile`. Re-roots under [[localForgeDir]] (B1).
    */
  def pollBaselineFile(id: FeatureId): os.Path = localForgeDir / "state" / s"${id.value}.poll-baselines.json"

  /** `.forge/state/.lock` — OS-level lock file (§13). Re-roots under [[localForgeDir]] (B1). */
  val lockFile: os.Path = localForgeDir / "state" / ".lock"

  /** `.forge/state/.lock.json` — lock holder metadata (§13). Re-roots under [[localForgeDir]] (B1). */
  val lockMetadataFile: os.Path = localForgeDir / "state" / ".lock.json"

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
