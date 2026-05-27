package io.forge.git.branch

import cats.effect.{IO, Ref}
import io.forge.core.{BranchName, FeatureId}
import io.forge.git.branch.protection.{CacheKey, InMemoryBranchProtectionCache, OverlaySource, RequiredChecksOverlay}
import io.forge.git.cli.GhError
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}
import munit.CatsEffectSuite

import java.time.Instant
import scala.concurrent.duration.*

/** PR-C C7 — cache hit/miss + TTL + epoch-bump invalidation, plus the C6 `requiredChecksOverlay` integration with the
  * fakes (rate-limit surfacing + Unauthorized fallback).
  */
class BranchProtectionCacheSuite extends CatsEffectSuite:

  private val feature = FeatureId("stripe-webhook")
  private val base = BranchName("main")
  private val now = Instant.parse("2026-05-27T09:00:00Z")

  /** Cache pinned at `now` via [[FakeClockSuite.fromRef]]. The put/get/invalidate tests construct overlays with
    * `fetchedAt = now`; pinning the cache's clock to the same instant keeps the entries within TTL regardless of how
    * long after `now` the suite happens to run on CI.
    */
  private def cacheAtNow: IO[InMemoryBranchProtectionCache] =
    for
      tick <- Ref.of[IO, Instant](now)
      cache <- InMemoryBranchProtectionCache(clock = FakeClockSuite.fromRef(tick))
    yield cache

  test("get on miss returns None"):
    for
      cache <- InMemoryBranchProtectionCache()
      r <- cache.get(CacheKey(feature, base, 0L))
    yield assertEquals(r, None)

  test("put then get returns the stored overlay"):
    val overlay = RequiredChecksOverlay(Set("ci/build"), now)
    val key = CacheKey(feature, base, 1L)
    for
      cache <- cacheAtNow
      _ <- cache.put(key, overlay)
      r <- cache.get(key)
    yield assertEquals(r, Some(overlay))

  test("different epoch → miss (caller bumps epoch on forge resume)"):
    val overlay = RequiredChecksOverlay(Set("ci/build"), now)
    for
      cache <- cacheAtNow
      _ <- cache.put(CacheKey(feature, base, 0L), overlay)
      r <- cache.get(CacheKey(feature, base, 1L))
    yield assertEquals(r, None)

  test("invalidateEpoch drops entries below the given epoch"):
    val overlay = RequiredChecksOverlay(Set("ci/build"), now)
    for
      cache <- cacheAtNow
      _ <- cache.put(CacheKey(feature, base, 0L), overlay)
      _ <- cache.put(CacheKey(feature, base, 1L), overlay)
      _ <- cache.invalidateEpoch(feature, base, belowEpoch = 1L)
      old <- cache.get(CacheKey(feature, base, 0L))
      current <- cache.get(CacheKey(feature, base, 1L))
    yield
      assertEquals(old, None)
      assertEquals(current, Some(overlay))

  test("TTL expiry — entry past `ttl` returns None and is evicted"):
    val ttl = 10.minutes
    val key = CacheKey(feature, base, 0L)
    val overlay = RequiredChecksOverlay(Set("ci/build"), now)
    // Manual fake clock so the test doesn't have to depend on cats-effect-testkit (added in Slice 3 PR-F per
    // design-2.3 §1.6 F5). Backed by a Ref[IO, Instant] so `advance` is straightforward.
    for
      tick <- Ref.of[IO, Instant](now)
      clock = FakeClockSuite.fromRef(tick)
      cache <- InMemoryBranchProtectionCache(ttl = ttl, clock = clock)
      _ <- cache.put(key, overlay.copy(fetchedAt = now))
      fresh <- cache.get(key)
      _ <- tick.set(now.plusSeconds(11 * 60))
      stale <- cache.get(key)
    yield
      assertEquals(fresh, Some(overlay.copy(fetchedAt = now)))
      assertEquals(stale, None)

  test("requiredChecksOverlay — cache miss → fetch → cache hit on second call (source = Protected)"):
    val payload = ujson.Obj(
      "contexts" -> ujson.Arr(ujson.Str("ci/build")),
      "checks" -> ujson.Arr(ujson.Obj("context" -> ujson.Str("ci/test")))
    )
    val fetchCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val gh = FakeGhClient.builder.apiBranchProtection { _ =>
      fetchCount.incrementAndGet()
      cats.effect.IO.pure(Right(Some(payload)))
    }.build
    for
      cache <- InMemoryBranchProtectionCache()
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      first <- bm.requiredChecksOverlay(feature, base, epoch = 1L)
      second <- bm.requiredChecksOverlay(feature, base, epoch = 1L)
    yield
      first match
        case Right(o) =>
          assertEquals(o.required, Set("ci/build", "ci/test"))
          assertEquals(o.source, OverlaySource.Protected)
        case other => fail(s"expected Right(overlay), got $other")
      assertEquals(first, second)
      assertEquals(fetchCount.get(), 1)

  test("requiredChecksOverlay — Unauthorized → empty overlay tagged Unauthorized (C6 pragmatic fallback)"):
    // The fallback is the same shape as the 404/Unprotected path (empty required set), but [[OverlaySource]] keeps
    // them distinct so Slice 4 can emit `harness.protection_unauthorized` only when the fallback actually fires.
    val gh = FakeGhClient.builder
      .apiBranchProtection(Left(GhError.Unauthorized("admin:repo required")))
      .build
    for
      cache <- InMemoryBranchProtectionCache()
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      r <- bm.requiredChecksOverlay(feature, base, epoch = 0L)
    yield r match
      case Right(overlay) =>
        assertEquals(overlay.required, Set.empty[String])
        assertEquals(overlay.source, OverlaySource.Unauthorized)
      case other => fail(s"expected Right(empty overlay), got $other")

  test("requiredChecksOverlay — RateLimited surfaces as BranchError.RateLimited"):
    val gh = FakeGhClient.builder
      .apiBranchProtection(Left(GhError.RateLimited(Some(42.seconds), "rate limit exceeded")))
      .build
    for
      cache <- InMemoryBranchProtectionCache()
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      r <- bm.requiredChecksOverlay(feature, base, epoch = 0L)
    yield r match
      case Left(BranchError.RateLimited(Some(d))) => assertEquals(d, 42.seconds)
      case other => fail(s"expected RateLimited, got $other")

  test("requiredChecksOverlay — None payload (404 unprotected) → empty required set tagged Unprotected"):
    val gh = FakeGhClient.builder.apiBranchProtection(Right(None)).build
    for
      cache <- InMemoryBranchProtectionCache()
      bm = BranchManagerFixture.manager(FakeGitClient.builder.build, gh, cache)
      r <- bm.requiredChecksOverlay(feature, base, epoch = 0L)
    yield r match
      case Right(overlay) =>
        assertEquals(overlay.required, Set.empty[String])
        assertEquals(overlay.source, OverlaySource.Unprotected)
      case other => fail(s"expected Right(empty overlay), got $other")

  test("RealGhClient.mapApiBranchProtection — NotFound flattens to Right(None) (Unprotected)"):
    val result = io.forge.git.cli.RealGhClient.mapApiBranchProtection(Left(GhError.NotFound("HTTP 404: Not Found")))
    IO.pure(assertEquals(result, Right(None)))

  test("RealGhClient.mapApiBranchProtection — Unauthorized stays Left (regression: not flattened to None)"):
    // Pins the slice-3 review fix. An earlier version of this mapping flattened 401/403 to `Right(None)`, which made
    // `RealBranchManager`'s Unauthorized branch unreachable in production and erased the Slice-4 distinction between
    // "no branch protection" and "caller lacks admin:repo".
    val result = io.forge.git.cli.RealGhClient.mapApiBranchProtection(Left(GhError.Unauthorized("admin:repo required")))
    IO.pure(result match
      case Left(_: GhError.Unauthorized) => ()
      case other => fail(s"expected Left(Unauthorized), got $other")
    )

  test("RealGhClient.mapApiBranchProtection — RateLimited stays Left (passthrough)"):
    val rl = GhError.RateLimited(Some(30.seconds), "rate limit exceeded")
    val result = io.forge.git.cli.RealGhClient.mapApiBranchProtection(Left(rl))
    IO.pure(assertEquals(result, Left(rl)))

  test("RealGhClient.mapApiBranchProtection — Right(json stdout) wraps as Right(Some(json))"):
    val result = io.forge.git.cli.RealGhClient.mapApiBranchProtection(Right("""{"contexts": ["ci/build"]}"""))
    IO.pure(result match
      case Right(Some(json)) => assertEquals(json("contexts")(0).str, "ci/build")
      case other => fail(s"expected Right(Some(json)), got $other")
    )

  test("RealGhClient.mapApiBranchProtection — Right(malformed stdout) surfaces as Left(ParseFailure)"):
    val result = io.forge.git.cli.RealGhClient.mapApiBranchProtection(Right("not json {"))
    IO.pure(result match
      case Left(GhError.ParseFailure("branch-protection", _, _)) => ()
      case other => fail(s"expected Left(ParseFailure), got $other")
    )
