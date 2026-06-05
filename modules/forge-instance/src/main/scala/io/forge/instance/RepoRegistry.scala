package io.forge.instance

import io.forge.core.InstanceName
import io.forge.core.Json.given
import upickle.default.{read, write, ReadWriter}

/** Persisted `config.json` for an instance — the minimal Slice-4.0 shape (just identity + a schema version for forward
  * compatibility). Richer instance config (prompts, default repo, workstream ordering) is later-slice work.
  */
final case class InstanceConfig(schemaVersion: Int, name: InstanceName) derives ReadWriter

object InstanceConfig:
  /** Bump when the persisted shape changes incompatibly. */
  val CurrentSchemaVersion: Int = 1

  def apply(name: InstanceName): InstanceConfig = InstanceConfig(CurrentSchemaVersion, name)

  def fromJson(s: String): InstanceConfig = read[InstanceConfig](s)
  def toJson(c: InstanceConfig, indent: Int = 2): String = write(c, indent = indent)

/** One repo registered against an instance (§4). `path` is the **registered source checkout** — registry/input only,
  * never the shared mutation target (under isolation each worker operates on its own clone; `forge-design-2.0.md`
  * §4.3). Stored as an absolute path string so the registry is portable across processes.
  */
final case class RegisteredRepo(path: String) derives ReadWriter:
  /** The registered path as an `os.Path` (always absolute — the store persists `os.Path.toString`). */
  def asPath: os.Path = os.Path(path)

/** Persisted `repos.json` — the set of repos registered against an instance, in registration order. Slice 4.0 keeps a
  * plain mutable file (no daemon yet to crash); the durable append-only instance action log + rebuildable cache is
  * 4.1's job (contract §6.4 / O8 — see docs/design-4.0.md carry-forward).
  */
final case class RepoRegistry(schemaVersion: Int, repos: Vector[RegisteredRepo]) derives ReadWriter:

  /** True when `path` (compared as a normalised absolute path) is already registered. */
  def contains(path: os.Path): Boolean = repos.exists(_.asPath == path)

  /** Append `repo` (caller has already checked it is not a duplicate). */
  def add(repo: RegisteredRepo): RepoRegistry = copy(repos = repos :+ repo)

object RepoRegistry:
  /** Bump when the persisted shape changes incompatibly. */
  val CurrentSchemaVersion: Int = 1

  /** An empty registry at the current schema version. */
  val empty: RepoRegistry = RepoRegistry(CurrentSchemaVersion, Vector.empty)

  def fromJson(s: String): RepoRegistry = read[RepoRegistry](s)
  def toJson(r: RepoRegistry, indent: Int = 2): String = write(r, indent = indent)
