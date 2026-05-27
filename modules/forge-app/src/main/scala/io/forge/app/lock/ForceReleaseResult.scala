package io.forge.app.lock

/** Outcome of [[ProcessLock.forceRelease]] — the `forge unlock --force` path (§13).
  *
  *   - [[Released]] — the on-disk `.lock.json` was removed (or absent) and no live OS lock blocked the call.
  *   - [[LiveHolderRefused]] — a different live process still holds the `FileChannel` lock; `unlock --force` refuses
  *     per §13 and prints the holder metadata if any was on disk.
  *   - [[NoLockPresent]] — neither a live lock nor metadata exist; the operator already had nothing to release.
  */
enum ForceReleaseResult:
  case Released
  case LiveHolderRefused(metadata: Option[LockMetadata])
  case NoLockPresent
