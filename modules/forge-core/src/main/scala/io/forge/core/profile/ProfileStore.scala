package io.forge.core.profile

import cats.effect.IO
import io.forge.core.paths.ForgePaths

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.util.UUID

/** Store seam for the committed `.forge/profile.json` (mirrors the `File*Store` family, e.g. `FileStateCache`).
  * Repo-level (one profile per repo), not per-feature, so the API takes no `FeatureId`.
  */
trait ProfileStore:
  /** The committed profile, or `None` if the repo has not been profiled yet. */
  def load(): IO[Option[RepoProfile]]

  /** Atomically persist the profile to `.forge/profile.json`. */
  def save(profile: RepoProfile): IO[Unit]

/** File-backed [[ProfileStore]] over `.forge/profile.json`, atomic + durable exactly as `FileStateCache` (sibling temp
  * + `SYNC`, `ATOMIC_MOVE`, parent-dir fsync).
  *
  * Read policy differs from the state cache on purpose: the state cache is *rebuildable* from log + manifest, so it
  * swallows a decode failure as `None`. `profile.json` is a **committed source of truth** — a malformed profile is a
  * real error, not "no profile yet" — so a decode failure here propagates rather than being silently masked. Absent
  * file ⇒ `None`; present-but-unparseable ⇒ the IO fails.
  */
final class FileProfileStore(paths: ForgePaths) extends ProfileStore:

  override def load(): IO[Option[RepoProfile]] =
    IO.blocking {
      val file = paths.profileFile
      if !os.exists(file) then None
      else Some(RepoProfile.fromJson(os.read(file)))
    }

  override def save(profile: RepoProfile): IO[Unit] =
    IO.blocking {
      val target = paths.profileFile
      val parent = target / os.up
      if !os.exists(parent) then os.makeDir.all(parent)
      val temp = parent / s".${target.last}.tmp.${UUID.randomUUID()}"
      val bytes = RepoProfile.toJson(profile).getBytes(StandardCharsets.UTF_8)
      try
        val _ = Files.write(
          temp.toNIO,
          bytes,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
          StandardOpenOption.SYNC
        )
        val _ = Files.move(
          temp.toNIO,
          target.toNIO,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
        ()
      catch
        case t: Throwable =>
          try
            if os.exists(temp) then
              val _ = os.remove(temp)
              ()
          catch case _: Throwable => ()
          throw t
    }
