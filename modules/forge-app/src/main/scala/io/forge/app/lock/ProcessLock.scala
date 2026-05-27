package io.forge.app.lock

import cats.effect.{IO, Resource}

/** §13 single-writer enforcement. Pairs an OS-level `FileChannel.tryLock` on `.forge/state/.lock` with a sibling
  * `.lock.json` metadata file at [[io.forge.core.paths.ForgePaths.lockMetadataFile]]. The OS lock is the
  * compile-with-no-races invariant; the metadata file is for the human ("which `forge` is holding it?").
  *
  * Slice-3 scope: the lock primitive + same-JVM unit coverage. Cross-JVM `Held(_)` / `forceRelease` / crash-stale
  * scenarios live in `forge-it`'s `ProcessLockMultiJvmSuite` (PR-G G3) because `FileChannel.tryLock` semantics are
  * OS-enforced and same-JVM tests cannot reproduce live contention.
  *
  * The Slice-4 orchestrator wires `acquire` into every `forge` entry-point and translates the result variants into
  * §13's prompts / exit codes; `forceRelease` is the `forge unlock --force` body.
  */
trait ProcessLock:

  /** Try to acquire the lock for the lifetime of the returned Resource.
    *
    *   - On `Resource.use` entry: opens the lock file, calls `FileChannel.tryLock`, reconciles with sibling
    *     `.lock.json`. The returned [[LockAcquireResult]] tells the caller which §13 branch fired.
    *   - On `Resource.release`: closes the channel (drops the OS lock) and deletes the sibling `.lock.json` if the
    *     acquire path created or rewrote it (a clean shutdown removes its own metadata). A hard JVM crash leaves the
    *     metadata in place so the next start-up sees `Stale(_)` and can recover.
    *
    * @param metadata
    *   the holder description to write to `.lock.json` on `Acquired`. Includes PID, hostname, command string, and
    *   optional feature id (§13 schema).
    * @param acceptStale
    *   when `true`, mirrors the §13 `--yes` / `FORGE_AUTO_UNLOCK_STALE=1` semantics: a `Stale(_)` outcome is silently
    *   upgraded to `Acquired` (metadata overwritten). When `false`, `Stale(_)` is surfaced for the CLI / TUI to prompt.
    */
  def acquire(metadata: LockMetadata, acceptStale: Boolean): Resource[IO, LockAcquireResult]

  /** `forge unlock --force` body — release the lock without holding a Resource around it. Returns
    * [[ForceReleaseResult.LiveHolderRefused]] when another live process still owns the OS lock (§13: live holder never
    * gets bulldozed); [[ForceReleaseResult.Released]] when only stale metadata was present;
    * [[ForceReleaseResult.NoLockPresent]] when neither exists.
    */
  def forceRelease: IO[ForceReleaseResult]
