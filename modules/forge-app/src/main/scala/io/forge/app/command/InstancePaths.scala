package io.forge.app.command

import cats.effect.IO
import io.forge.core.{FeatureId, InstanceName}
import io.forge.core.paths.ForgePaths
import io.forge.instance.{FileInstanceStore, Instance, InstanceError}

/** Task 4.0.4 — resolve the B1 **local-runtime root** for a feature command (`forge-design-2.0.md` §4.3).
  *
  * Given a command's base [[ForgePaths]] (anchored at the repo checkout), the optional `--instance <name>` the operator
  * supplied, and the feature the command binds to, decide where the gitignored local-runtime family (the action log,
  * the state cache + poll baselines, the lock) should live:
  *
  *   - **No `--instance` flag ⇒ v1 (byte-identical).** Return the base paths unchanged so `log/state/lock` stay in the
  *     repo checkout exactly as in single-repo v1. The instance registry is **not even read** in this case, so the v1
  *     path has zero coupling to `~/.forge` state. Feature-less commands (`profile`, `status`'s overview) likewise have
  *     nothing per-feature to re-root and return the base paths.
  *   - **Explicit `--instance <name>` ⇒ re-root.** Return `ForgePaths(repoRoot, localRootOpt =
  *     Some(<instance>/workers/<feature>))` so the committed family (specs / config / profile) stays in the repo and
  *     merges via PR, while the local-runtime family re-roots under the instance's per-feature worker directory. Every
  *     `paths.xxx` consumer downstream is unchanged — this is a constructor swap, B1's whole promise.
  *
  * Re-root is driven **only** by an explicit `--instance` (decided for Slice 4.0): a no-flag feature command is never
  * silently bound to an instance, keeping the "byte-identical to v1 when no instance is in play" exit-criterion
  * guarantee literal — there is no registry read, let alone a sole-instance auto-adopt, on the v1 path. (This differs
  * from [[InstanceCommands]]' `add-repo`/`list-repos` sole-instance default, which is a registry-management convenience
  * where reading the registry is the whole point.) An explicit `--instance` that names a non-existent instance is the
  * one hard error ([[InstanceError.NoSuchInstance]]); the operator asked for a specific instance that isn't there.
  */
object InstancePaths:

  /** Outcome of resolving the local-runtime root. `instance` is `Some` exactly when [[paths]] was re-rooted under an
    * instance worker dir (so `Main` can note where the runtime landed); `None` means the v1 in-repo layout.
    */
  final case class Resolved(paths: ForgePaths, instance: Option[Instance])

  /** Resolve the local-runtime root for `feature` under `base`. See the class doc for the rule table. Touches disk only
    * to read the instance registry under `base.home`; never mutates it.
    */
  def resolve(
      base: ForgePaths,
      instance: Option[InstanceName],
      feature: Option[FeatureId]
  ): IO[Either[InstanceError, Resolved]] =
    (instance, feature) match
      // Explicit --instance + a feature ⇒ re-root. The instance must exist (NoSuchInstance otherwise — the operator
      // named a specific instance), and `load` confirms its `config.json` is on disk before we re-root.
      case (Some(name), Some(f)) =>
        new FileInstanceStore(base.home).load(name).map(_.map(inst => Resolved(reRoot(base, inst, f), Some(inst))))
      // No flag, or no feature to bind a worker dir to ⇒ v1: keep the base paths and never touch the registry.
      case _ => IO.pure(Right(Resolved(base, None)))

  /** Re-root the local-runtime family under `instance`'s per-feature worker dir, keeping `repoRoot` (committed family)
    * and `home` (per-user assets) anchored where they were.
    */
  private def reRoot(base: ForgePaths, instance: Instance, feature: FeatureId): ForgePaths =
    new ForgePaths(base.repoRoot, base.home, localRootOpt = Some(instance.workerDir(feature)))
