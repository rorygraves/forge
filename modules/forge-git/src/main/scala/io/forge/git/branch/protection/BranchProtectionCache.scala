package io.forge.git.branch.protection

import cats.effect.{Clock, IO, Ref}
import io.forge.core.{BranchName, FeatureId}

import java.time.Instant
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** PR-C C5 — branch-protection cache key per v1.2 §8.1. The triple `(feature, base, epoch)` makes invalidation
  * explicit:
  *
  *   - `feature` keeps two parallel `forge run` invocations (different features, same base) from sharing state.
  *   - `base` matches §8.1's "scoped by base branch" — `main` and `release-1.x` carry independent protection sets.
  *   - `epoch` is the design-rationale CI5 fan-out — incremented on every `forge resume` / `forge refresh-cache` so a
  *     prior orchestrator process's results don't leak into a new run.
  */
final case class CacheKey(feature: FeatureId, base: BranchName, epoch: Long)

/** Discriminator for how the overlay was obtained. Slice-4 audit-log writers map each variant onto a `gh.action` /
  * `harness.*` entry — in particular `Unauthorized` is the §C6 pragmatic fallback the audit log records as
  * `harness.protection_unauthorized`. Without the discriminator on [[RequiredChecksOverlay]], Slice 4 cannot
  * distinguish "no branch protection set up on `base`" (`Unprotected`, 404 from `gh api`) from "caller lacks
  * `admin:repo`" (`Unauthorized`) — both surface as an empty required set otherwise.
  */
enum OverlaySource:
  /** `gh api …/branch-protection/required_status_checks` returned a JSON payload — `required` is the genuine
    * branch-protection contract. The audit log records this case via normal `gh.action` entries.
    */
  case Protected

  /** `gh api …` returned 404 — no branch protection on this base. `required` is empty by definition. */
  case Unprotected

  /** `gh api …` returned 401/403 — caller lacks `admin:repo` on the repo. Slice 3's [[RealBranchManager]] treats this
    * as "no required checks" pragmatically (so `forge run` doesn't block on missing privileges); Slice 4 logs
    * `harness.protection_unauthorized` to make the fallback auditable.
    */
  case Unauthorized

/** Cached required-checks overlay value. `required` is the union of branch-protection-required check names; `fetchedAt`
  * is the read timestamp used for TTL eviction; `source` tells Slice 4 which `gh api` outcome produced this overlay so
  * `harness.protection_unauthorized` can be emitted only when the fallback actually fires (see [[OverlaySource]]).
  */
final case class RequiredChecksOverlay(
    required: Set[String],
    fetchedAt: Instant,
    source: OverlaySource = OverlaySource.Protected
)

/** PR-C C5 — pluggable storage seam. Slice 3 ships [[InMemoryBranchProtectionCache]] only; on-disk persistence is filed
  * as carry-forward **S3-2** (no caller in Slice 3 needs it).
  */
trait BranchProtectionCache:
  /** `Some(overlay)` on a hit that is still within `ttl`. `None` on miss OR on TTL-expired entries (callers don't have
    * to re-check the timestamp themselves).
    */
  def get(key: CacheKey): IO[Option[RequiredChecksOverlay]]

  /** Stores `overlay` against `key`. Subsequent `get(key)` within `ttl` returns it. */
  def put(key: CacheKey, overlay: RequiredChecksOverlay): IO[Unit]

  /** Drop every entry for `(feature, base)` whose `epoch < belowEpoch`. Called by Slice 4's resume / refresh-cache
    * handlers after bumping `feature.branchProtectionCacheEpoch`. Slice-3 callers don't invoke this directly — they
    * bump the epoch and let the next `get` miss naturally — but a Slice-4 long-running orchestrator may want eager
    * eviction to bound memory.
    */
  def invalidateEpoch(feature: FeatureId, base: BranchName, belowEpoch: Long): IO[Unit]

object BranchProtectionCache:
  /** Default TTL per `config.github.branchProtectionTtlSec` (v1.2 §18). */
  val DefaultTtl: FiniteDuration = 1.hour

/** Process-local in-memory cache. Holds entries keyed by [[CacheKey]] in a single `Ref`-wrapped map.
  *
  * No on-disk persistence in Slice 3 (carry-forward **S3-2**). Bumping the epoch on `feature` makes prior cached values
  * unreachable; eager eviction is also provided via [[invalidateEpoch]] so a long-lived orchestrator process doesn't
  * accumulate dead entries.
  */
final class InMemoryBranchProtectionCache private (
    state: Ref[IO, Map[CacheKey, RequiredChecksOverlay]],
    ttl: FiniteDuration,
    clock: Clock[IO]
) extends BranchProtectionCache:

  override def get(key: CacheKey): IO[Option[RequiredChecksOverlay]] =
    clock.realTimeInstant.flatMap { now =>
      state.modify { m =>
        m.get(key) match
          case None => (m, None)
          case Some(overlay) =>
            val age = java.time.Duration.between(overlay.fetchedAt, now)
            if age.toNanos > ttl.toNanos then (m - key, None)
            else (m, Some(overlay))
      }
    }

  override def put(key: CacheKey, overlay: RequiredChecksOverlay): IO[Unit] =
    state.update(_.updated(key, overlay))

  override def invalidateEpoch(feature: FeatureId, base: BranchName, belowEpoch: Long): IO[Unit] =
    state.update { m =>
      m.filterNot { case (k, _) =>
        k.feature == feature && k.base == base && k.epoch < belowEpoch
      }
    }

object InMemoryBranchProtectionCache:
  def apply(
      ttl: FiniteDuration = BranchProtectionCache.DefaultTtl,
      clock: Clock[IO] = Clock[IO]
  ): IO[InMemoryBranchProtectionCache] =
    Ref.of[IO, Map[CacheKey, RequiredChecksOverlay]](Map.empty).map { ref =>
      new InMemoryBranchProtectionCache(ref, ttl, clock)
    }
