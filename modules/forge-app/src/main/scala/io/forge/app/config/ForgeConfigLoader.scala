package io.forge.app.config

import cats.effect.IO
import io.forge.core.paths.ForgePaths

import scala.util.control.NonFatal

/** Task 1.4.9 I1 — loads [[ForgeConfig]] from `.forge/config.json` (§18) with per-key overrides.
  *
  * Resolution order (last wins):
  *   1. §18 defaults — supplied by [[ForgeConfig]]'s default args; a missing or partial `config.json` falls back to
  *      them automatically. 2. `.forge/config.json` — the per-repo base config. 3. `.forge/overrides/<key>.json` — one
  *      file per **top-level** `config.json` key (`claude`, `staging`, `mode`, …). The file's content **replaces** that
  *      whole key's value in the merged object before decoding. Replacement (not deep merge) is the v1 semantics:
  *      "per-key" override means the override file owns that key. Because each nested config type (e.g.
  *      [[ClaudeConfig]]) also carries default args, a *partial* override object still defaults its own missing
  *      sub-keys on the final decode.
  *
  * The merge happens at the `ujson` layer (read base + overrides into one object, then decode once) so the lenient
  * default-arg behaviour applies uniformly to whatever the merge produced. A *missing* `config.json` is not an error
  * (`Right(ForgeConfig.Default)`); only a file that exists-but-won't-decode yields a [[ConfigError]].
  */
object ForgeConfigLoader:

  def load(paths: ForgePaths): IO[Either[ConfigError, ForgeConfig]] =
    IO.blocking(loadSync(paths))

  /** Pure-ish (blocking-IO) core, exposed package-private so the unit suite can exercise it without an `IO` runtime. */
  private[config] def loadSync(paths: ForgePaths): Either[ConfigError, ForgeConfig] =
    for
      base <- readObject(paths.configFile)
      merged <- applyOverrides(base, paths)
      config <- decode(merged, paths.configFile)
    yield config

  /** Read `config.json` into a `ujson.Obj`. Missing ⇒ empty object (defaults apply); non-object root ⇒ `Malformed`. */
  private def readObject(file: os.Path): Either[ConfigError, ujson.Obj] =
    if !os.exists(file) then Right(ujson.Obj())
    else
      readText(file).flatMap { text =>
        try
          ujson.read(text) match
            case obj: ujson.Obj => Right(obj)
            case other =>
              Left(
                ConfigError.Malformed(
                  file,
                  new IllegalArgumentException(s"expected a JSON object, got ${other.getClass.getSimpleName}")
                )
              )
        catch case NonFatal(t) => Left(ConfigError.Malformed(file, t))
      }

  /** Merge each `.forge/overrides/<key>.json` onto the base object, one top-level key per file (filename stem = key).
    */
  private def applyOverrides(base: ujson.Obj, paths: ForgePaths): Either[ConfigError, ujson.Obj] =
    if !os.exists(paths.overridesDir) then Right(base)
    else
      val listed =
        try Right(os.list(paths.overridesDir).filter(p => os.isFile(p) && p.last.endsWith(".json")).sortBy(_.last))
        catch case NonFatal(t) => Left(ConfigError.IoFailure(paths.overridesDir, t))
      listed.flatMap { files =>
        files.foldLeft[Either[ConfigError, ujson.Obj]](Right(base)) { (acc, file) =>
          acc.flatMap { obj =>
            readValue(file).map { value =>
              obj(file.last.stripSuffix(".json")) = value
              obj
            }
          }
        }
      }

  /** Decode the merged object into [[ForgeConfig]]; failures (bad enum value, type mismatch) surface as `Malformed`. */
  private def decode(merged: ujson.Obj, configFile: os.Path): Either[ConfigError, ForgeConfig] =
    try Right(upickle.default.read[ForgeConfig](merged))
    catch case NonFatal(t) => Left(ConfigError.Malformed(configFile, t))

  private def readValue(file: os.Path): Either[ConfigError, ujson.Value] =
    readText(file).flatMap { text =>
      try Right(ujson.read(text))
      catch case NonFatal(t) => Left(ConfigError.Malformed(file, t))
    }

  private def readText(file: os.Path): Either[ConfigError, String] =
    try Right(os.read(file))
    catch case NonFatal(t) => Left(ConfigError.IoFailure(file, t))
