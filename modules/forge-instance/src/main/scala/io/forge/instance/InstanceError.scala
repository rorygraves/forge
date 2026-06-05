package io.forge.instance

import io.forge.core.InstanceName

/** Error vocabulary for [[InstanceStore]] (Phase-4 §4 / Task 4.0.2).
  *
  * Recoverable, caller-facing failures live on the `Either` left; only genuine I/O exceptions ride `IO`'s error
  * channel. Mirrors the `forge-core`/`forge-specs` store convention (`RebuildError`, `SpecStoreError`): each store
  * keeps its own typed vocabulary rather than leaking raw exceptions to the CLI.
  */
sealed trait InstanceError
object InstanceError:

  /** No instance directory (or its `config.json`) exists for `name` under the user's instance root. */
  final case class NoSuchInstance(name: InstanceName) extends InstanceError

  /** `init-instance` asked to create `name` but its directory already exists. */
  final case class DuplicateInstance(name: InstanceName) extends InstanceError

  /** `add-repo` was given a path that does not exist or is not a directory. */
  final case class RepoNotFound(path: os.Path) extends InstanceError

  /** `add-repo` was given a directory that is not a git working tree (no `.git` entry). */
  final case class NotAGitRepo(path: os.Path) extends InstanceError

  /** `add-repo` was given a path already present in the instance's registry. */
  final case class DuplicateRepo(path: os.Path) extends InstanceError

  /** A persisted file (`config.json` / `repos.json`) is present but undecodable (truncated, hand-edit, schema drift).
    * `cause` carries the decode-failure description for forensic context.
    */
  final case class Malformed(file: os.Path, cause: Throwable) extends InstanceError

  /** A read or write failed with a genuine I/O error that is nonetheless worth surfacing on the typed channel (e.g. a
    * permission error creating the instance dir) rather than aborting the whole CLI command.
    */
  final case class IoFailure(file: os.Path, cause: Throwable) extends InstanceError
