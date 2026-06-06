package io.forge.core.forgefile

import cats.effect.IO
import io.forge.core.paths.ForgePaths

/** Store seam for the committed `Forgefile` (mirrors [[io.forge.core.profile.ProfileStore]]). Repo-level (one per
  * repo), so the API takes no `FeatureId`. **Load-only**: the `Forgefile` is human-authored normative policy (O1), so
  * Forge reads it but never writes it.
  */
trait ForgefileStore:
  /** The committed Forgefile, or `None` if the repo pins none (the supervisor then falls back to a default image). */
  def load(): IO[Option[Forgefile]]

object ForgefileStore:
  /** A no-op store reporting "no Forgefile" — the default where no pinning seam is wired (an unpinned worker runs the
    * supervisor's default image, exactly as a worker did pre-4.3).
    */
  val none: ForgefileStore = new ForgefileStore:
    def load(): IO[Option[Forgefile]] = IO.pure(None)

/** File-backed [[ForgefileStore]] over the repo-root `Forgefile`.
  *
  * Read policy matches [[io.forge.core.profile.FileProfileStore]]: a `Forgefile` is a **committed source of truth**, so
  * an absent file ⇒ `None`, but a present-but-malformed file is a real error that **propagates** (the IO fails) rather
  * than being silently masked as "no Forgefile" — a typo'd pin must not degrade quietly to the default image.
  */
final class FileForgefileStore(paths: ForgePaths) extends ForgefileStore:
  override def load(): IO[Option[Forgefile]] =
    IO.blocking {
      val file = paths.forgefile
      if !os.exists(file) then None
      else
        Forgefile.parse(os.read(file)) match
          case Right(f) => Some(f)
          case Left(err) => throw new IllegalArgumentException(s"malformed Forgefile at $file: $err")
    }
