package io.forge.app.config

/** Task 1.4.9 I1 — failure channel for [[ForgeConfigLoader]].
  *
  * A *missing* `.forge/config.json` is **not** an error — §18 defaults apply, so the loader returns
  * `Right(ForgeConfig.Default)`. Errors are reserved for files that exist but can't be turned into a [[ForgeConfig]].
  */
sealed trait ConfigError extends Product with Serializable:
  def path: os.Path

object ConfigError:
  /** JSON parse failure, a non-object root, or a type mismatch when decoding into [[ForgeConfig]]. `path` points at the
    * offending file — `config.json` for a base-config or final-decode failure, the specific
    * `.forge/overrides/<key>.json` for an override parse failure.
    */
  final case class Malformed(path: os.Path, cause: Throwable) extends ConfigError

  /** Filesystem error reading a config or override file (permissions, etc.). */
  final case class IoFailure(path: os.Path, cause: Throwable) extends ConfigError
