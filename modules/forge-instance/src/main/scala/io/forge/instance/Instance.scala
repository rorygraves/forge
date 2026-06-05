package io.forge.instance

import io.forge.core.{FeatureId, InstanceName}

/** A resolved Forge **instance** handle (Phase-4 §4 / `forge-design-2.0.md` §4) — a `name` plus the on-disk `dir` it
  * occupies under `~/.forge/instances/`. This is a runtime view, not the persisted shape: the durable bytes live in
  * [[InstanceConfig]] (`config.json`) and [[RepoRegistry]] (`repos.json`); `Instance` just carries the directory and
  * derives the leaf paths inside it.
  *
  * The per-instance leaves are derived here (not in `ForgePaths`) because they are instance-*internal* layout, whereas
  * the `~/.forge/instances/<name>/` anchor — which spells `.forge` — lives on `ForgePaths.instanceDir` so the
  * build-enforced no-`.forge`-literal smell sweep keeps covering it. Construct an `Instance` via `Instance.at(name,
  * home)` (or `InstanceStore`), never by spelling the anchor here.
  */
final case class Instance(name: InstanceName, dir: os.Path):

  /** `instances/<name>/config.json` — the persisted [[InstanceConfig]]. */
  def configFile: os.Path = dir / "config.json"

  /** `instances/<name>/repos.json` — the persisted [[RepoRegistry]]. */
  def reposFile: os.Path = dir / "repos.json"

  /** `instances/<name>/workers/` — root of the per-feature local-runtime re-root targets. In Slice 4.0 a "worker" is
    * only a *directory* (the B1 `localRoot` for `ForgePaths`); the daemon-spawned worker process is 4.1+.
    */
  def workersDir: os.Path = dir / "workers"

  /** `instances/<name>/workers/<feature>/` — the B1 local-runtime re-root for a feature. Passed as
    * `ForgePaths(repoRoot, localRootOpt = Some(workerDir(feature)))` in Task 4.0.4, so the feature's log/state/lock
    * land under the instance dir while its committed specs stay in the repo checkout.
    */
  def workerDir(feature: FeatureId): os.Path = workersDir / feature.value

  /** `instances/<name>/.lock` — the instance-level OS lock file (Task 4.0.3). The `init-instance` / `add-repo` registry
    * commands serialize on this instead of the per-checkout `.forge/state/.lock`, since they mutate instance-scoped
    * state (the registry), not a repo checkout. Same leaf names as the per-checkout lock (`.lock` + `.lock.json`); full
    * instance-lock ownership (held by the long-running daemon) is 4.1's job.
    */
  def lockFile: os.Path = dir / ".lock"

  /** `instances/<name>/.lock.json` — sibling holder-metadata for [[lockFile]] (the §13 "who holds it?" diagnostic). */
  def lockMetadataFile: os.Path = dir / ".lock.json"

object Instance:
  /** Resolve the on-disk directory for `name` under `home`'s instance root (default `os.home`) — does **not** touch
    * disk. Use [[InstanceStore]] to create/load with existence + decode checks.
    */
  def at(name: InstanceName, home: os.Path = os.home): Instance =
    Instance(name, io.forge.core.paths.ForgePaths.instanceDir(name, home))
