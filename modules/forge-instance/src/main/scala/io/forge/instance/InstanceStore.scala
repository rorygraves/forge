package io.forge.instance

import cats.effect.IO
import io.forge.core.InstanceName
import io.forge.core.paths.ForgePaths

import scala.util.control.NonFatal

/** Reader/writer for the per-user Forge **instance** registry (Phase-4 §4 / Task 4.0.2).
  *
  * Owns the `~/.forge/instances/<name>/{config.json, repos.json, workers/}` layout: create an instance, resolve one by
  * name, register repos against it, and list them. Surface is deliberately tight — exactly what the Task 4.0.3 CLI
  * (`init-instance` / `add-repo` / `list-repos`) needs. The only impl is [[FileInstanceStore]]; the trait exists so the
  * CLI layer can be tested against a fake without a tmp `home`, mirroring `SpecStore`/`ManifestStore`.
  *
  * Recoverable failures land on the `Either` left as [[InstanceError]]; `IO` carries only unexpected exceptions.
  */
trait InstanceStore:

  /** Create `instances/<name>/` with an empty registry. [[InstanceError.DuplicateInstance]] if it already exists. */
  def create(name: InstanceName): IO[Either[InstanceError, Instance]]

  /** Resolve an existing instance by name. [[InstanceError.NoSuchInstance]] if its directory/config is absent. */
  def load(name: InstanceName): IO[Either[InstanceError, Instance]]

  /** Every instance name currently present under the user's instance root (registration order is filesystem order). */
  def list: IO[Vector[InstanceName]]

  /** Register `repoPath` (a git working tree) against `instance`. Validates existence + git-ness + non-duplication,
    * then appends to `repos.json`. Returns the newly registered entry.
    */
  def addRepo(instance: Instance, repoPath: os.Path): IO[Either[InstanceError, RegisteredRepo]]

  /** The repos registered against `instance`, in registration order. */
  def listRepos(instance: Instance): IO[Either[InstanceError, Vector[RegisteredRepo]]]

/** File-backed [[InstanceStore]] rooted at `home` (default `os.home`; tests pass a tmp dir).
  *
  * Slice 4.0 deliberately uses **plain `os.write.over`** for `config.json` / `repos.json`, not the atomic
  * temp-file-then-rename dance of `FileSpecStore`: there is no daemon yet that could crash mid-write, and the contract
  * frames the registry as a temporary plain-file simplification superseded by 4.1's append-only instance action log
  * (docs/design-4.0.md carry-forward). When the daemon arrives, durability moves to that log, not to this file.
  */
final class FileInstanceStore(home: os.Path = os.home) extends InstanceStore:

  override def create(name: InstanceName): IO[Either[InstanceError, Instance]] =
    IO.blocking {
      val instance = Instance.at(name, home)
      // Guard on `config.json` (the same marker `load` uses), not the directory: the Task 4.0.3 `init-instance` flow
      // acquires the instance lock *first*, and acquiring `instance.lockFile` creates the (empty) instance dir as a
      // side effect. A bare dir without a `config.json` is therefore "not yet an instance", so it must not read as a
      // duplicate; the present-config check is the true "already exists" test and keeps `create` idempotent-safe.
      if os.exists(instance.configFile) then Left(InstanceError.DuplicateInstance(name))
      else
        try
          os.makeDir.all(instance.dir)
          os.makeDir.all(instance.workersDir)
          os.write.over(instance.configFile, InstanceConfig.toJson(InstanceConfig(name)))
          os.write.over(instance.reposFile, RepoRegistry.toJson(RepoRegistry.empty))
          Right(instance)
        catch case NonFatal(t) => Left(InstanceError.IoFailure(instance.dir, t))
    }

  override def load(name: InstanceName): IO[Either[InstanceError, Instance]] =
    IO.blocking {
      val instance = Instance.at(name, home)
      if !os.exists(instance.configFile) then Left(InstanceError.NoSuchInstance(name))
      // Decode (not just stat) `config.json`, symmetric with `readRegistry`: a present-but-undecodable or wrong-schema
      // config surfaces `Malformed` rather than reading as a valid instance. This is also the load path's only use of
      // the `InstanceConfig` codec — without it the codec is dead and a corrupt config is silently accepted.
      else decodeConfig(instance.configFile).map(_ => instance)
    }

  override def list: IO[Vector[InstanceName]] =
    IO.blocking {
      val root = ForgePaths.instancesRoot(home)
      if !os.exists(root) then Vector.empty
      else
        os.list(root)
          .filter(os.isDir)
          // Only count directories that actually look like instances (have a config.json) and whose name is valid.
          .filter(p => os.exists(p / "config.json"))
          .flatMap(p => InstanceName.fromString(p.last).toOption)
          .toVector
    }

  override def addRepo(instance: Instance, repoPath: os.Path): IO[Either[InstanceError, RegisteredRepo]] =
    IO.blocking {
      // `os.Path` is always absolute + normalised (the CLI builds it via `os.Path(arg, os.pwd)`), so registry
      // membership compares as a normalised absolute path regardless of how it was spelled on the command line.
      // (Symlinks are not resolved — two symlinked spellings of one real dir could both register; acceptable for 4.0.)
      val path = repoPath
      if !os.exists(path) || !os.isDir(path) then Left(InstanceError.RepoNotFound(path))
      else if !os.exists(path / ".git") then Left(InstanceError.NotAGitRepo(path))
      else
        readRegistry(instance) match
          case Left(err) => Left(err)
          case Right(registry) =>
            if registry.contains(path) then Left(InstanceError.DuplicateRepo(path))
            else
              val entry = RegisteredRepo(path.toString)
              try
                os.write.over(instance.reposFile, RepoRegistry.toJson(registry.add(entry)))
                Right(entry)
              catch case NonFatal(t) => Left(InstanceError.IoFailure(instance.reposFile, t))
    }

  override def listRepos(instance: Instance): IO[Either[InstanceError, Vector[RegisteredRepo]]] =
    IO.blocking(readRegistry(instance).map(_.repos))

  /** Read + decode `repos.json`. A missing file decodes to [[RepoRegistry.empty]] (a freshly created instance always
    * has one, but tolerating absence keeps `add-repo` robust against a hand-deleted registry); a present-but-malformed
    * file (undecodable or a schema version this build does not read) surfaces [[InstanceError.Malformed]] rather than
    * silently resetting the registry.
    */
  private def readRegistry(instance: Instance): Either[InstanceError, RepoRegistry] =
    val file = instance.reposFile
    if !os.exists(file) then Right(RepoRegistry.empty)
    else
      try
        val reg = RepoRegistry.fromJson(os.read(file))
        if reg.schemaVersion != RepoRegistry.CurrentSchemaVersion then
          Left(InstanceError.Malformed(file, unsupportedSchema(reg.schemaVersion, RepoRegistry.CurrentSchemaVersion)))
        else Right(reg)
      catch case NonFatal(t) => Left(InstanceError.Malformed(file, t))

  /** Read + decode `config.json`, validating its schema version. Mirrors [[readRegistry]] so the two persisted files
    * are handled symmetrically: a present-but-undecodable or wrong-schema config surfaces [[InstanceError.Malformed]].
    */
  private def decodeConfig(file: os.Path): Either[InstanceError, InstanceConfig] =
    try
      val cfg = InstanceConfig.fromJson(os.read(file))
      if cfg.schemaVersion != InstanceConfig.CurrentSchemaVersion then
        Left(InstanceError.Malformed(file, unsupportedSchema(cfg.schemaVersion, InstanceConfig.CurrentSchemaVersion)))
      else Right(cfg)
    catch case NonFatal(t) => Left(InstanceError.Malformed(file, t))

  /** The `cause` carried on [[InstanceError.Malformed]] when a persisted file decodes but declares a `schemaVersion`
    * this build does not read (the §4.0 carry-forward to 4.1's durable log will revisit migration; today an unknown
    * version is a hard, forensically-labelled stop rather than a silent mis-decode).
    */
  private def unsupportedSchema(found: Int, expected: Int): Throwable =
    new IllegalStateException(s"unsupported schemaVersion $found (this Forge build reads version $expected)")
