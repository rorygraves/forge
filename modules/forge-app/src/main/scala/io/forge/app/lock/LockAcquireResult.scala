package io.forge.app.lock

/** Outcome of [[ProcessLock.acquire]]. Modelled as a sealed ADT (not an `Either`) because three outcomes need to flow
  * to distinct §13 handlers:
  *
  *   - [[LockAcquireResult.Acquired]] — caller owns the lock; the Resource finalizer cleans up on scope exit.
  *   - [[LockAcquireResult.Stale]] — OS lock was free but a sibling `.lock.json` survived from a prior crashed run.
  *     Forge's CLI (Slice 4) prompts the operator; the TUI shows a y/N dialog (BM5). `acceptStale = true` upgrades this
  *     silently to `Acquired`.
  *   - [[LockAcquireResult.Held]] — OS lock is currently held by another live process. The caller prints holder info
  *     and exits non-zero per §13 step 4.
  *
  * The `Held` payload is `Option[LockMetadata]` because the on-disk `.lock.json` may be absent (live lock without
  * metadata, race condition during another process's startup) or unparseable (treated as missing for the holder message
  * — the operator still sees "another process holds the lock").
  */
sealed trait LockAcquireResult

object LockAcquireResult:
  case object Acquired extends LockAcquireResult
  final case class Stale(staleMetadata: LockMetadata) extends LockAcquireResult
  final case class Held(otherMetadata: Option[LockMetadata]) extends LockAcquireResult
