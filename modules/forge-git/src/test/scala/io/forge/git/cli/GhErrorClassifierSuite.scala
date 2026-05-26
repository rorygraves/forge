package io.forge.git.cli

import io.forge.core.BranchName

/** PR-A A2 — `RealGhClient.classify` against fixture stderr blobs that mirror real `gh` output. The classifier is the
  * one chokepoint that maps `gh`'s wording (which can drift between versions) to the typed `GhError` ADT, so the suite
  * leans hard on representative phrasings rather than synthetic strings.
  */
class GhErrorClassifierSuite extends munit.FunSuite:

  test("exit 0 returns stdout body"):
    val r = RealGhClient.classify(0, "ok body", "")
    assertEquals(r, Right("ok body"))

  test("exit 0 returns empty stdout untouched"):
    val r = RealGhClient.classify(0, "", "")
    assertEquals(r, Right(""))

  test("api rate-limit phrasing → RateLimited"):
    val stderr = "HTTP 403: API rate limit exceeded for user ID 123 (HTTP 403)"
    RealGhClient.classify(1, "", stderr) match
      case Left(GhError.RateLimited(retryAfter, raw)) =>
        assertEquals(retryAfter, None)
        assert(raw.contains("rate limit exceeded"))
      case other => fail(s"expected RateLimited, got $other")

  test("secondary rate-limit phrasing → RateLimited"):
    val stderr = "You have exceeded a secondary rate limit. Please wait a few minutes before you try again."
    RealGhClient.classify(1, "", stderr) match
      case Left(GhError.RateLimited(_, raw)) => assert(raw.contains("secondary rate limit"))
      case other => fail(s"expected RateLimited, got $other")

  test("Retry-After parsed when present"):
    val stderr = "HTTP 429: rate limit exceeded\nRetry-After: 42"
    RealGhClient.classify(1, "", stderr) match
      case Left(GhError.RateLimited(Some(d), _)) => assertEquals(d.toSeconds, 42L)
      case other => fail(s"expected RateLimited with retry-after 42, got $other")

  test("X-RateLimit-Remaining: 0 also classified as RateLimited"):
    val stderr = "HTTP 403: Forbidden\nX-RateLimit-Remaining: 0"
    RealGhClient.classify(1, "", stderr) match
      case Left(_: GhError.RateLimited) => ()
      case other => fail(s"expected RateLimited, got $other")

  test("404 phrasing → NotFound"):
    val stderr = "gh: HTTP 404: Not Found (https://api.github.com/repos/owner/repo/pulls/9999)"
    RealGhClient.classify(1, "", stderr) match
      case Left(_: GhError.NotFound) => ()
      case other => fail(s"expected NotFound, got $other")

  test("401 phrasing → Unauthorized"):
    val stderr = "gh: HTTP 401: Bad credentials"
    RealGhClient.classify(1, "", stderr) match
      case Left(_: GhError.Unauthorized) => ()
      case other => fail(s"expected Unauthorized, got $other")

  test("403 (non-rate-limit) phrasing → Unauthorized"):
    val stderr = "gh: HTTP 403: Forbidden — Resource not accessible by integration"
    RealGhClient.classify(1, "", stderr) match
      case Left(_: GhError.Unauthorized) => ()
      case other => fail(s"expected Unauthorized, got $other")

  test("rate-limit wins over Unauthorized when both present"):
    val stderr = "HTTP 403: API rate limit exceeded — Forbidden"
    RealGhClient.classify(1, "", stderr) match
      case Left(_: GhError.RateLimited) => ()
      case other => fail(s"expected RateLimited (priority), got $other")

  test("rate-limit wins over NotFound when both present"):
    val stderr = "HTTP 404 then API rate limit exceeded later"
    RealGhClient.classify(1, "", stderr) match
      case Left(_: GhError.RateLimited) => ()
      case other => fail(s"expected RateLimited (priority), got $other")

  test("unknown non-zero exit → Transient"):
    val stderr = "some unexpected error from gh"
    RealGhClient.classify(7, "", stderr) match
      case Left(GhError.Transient(7, msg)) => assert(msg.contains("unexpected"))
      case other => fail(s"expected Transient(7, ...), got $other")

  test("PrUrlPattern parses a typical PR-create URL"):
    val url = "https://github.com/owner/repo/pull/4291\n"
    val m = RealGhClient.PrUrlPattern.findFirstMatchIn(url.trim)
    assertEquals(m.map(_.group(1)), Some("4291"))

  test("PrUrlPattern refuses a non-PR URL"):
    val m = RealGhClient.PrUrlPattern.findFirstMatchIn("https://github.com/owner/repo/issues/4291")
    assertEquals(m, None)

  test("branchProtectionApiPath: simple branch is untouched"):
    val path = RealGhClient.branchProtectionApiPath(BranchName("main"))
    assertEquals(path, "repos/{owner}/{repo}/branches/main/protection/required_status_checks")

  test("branchProtectionApiPath: '/' in branch name is encoded as %2F (release/1.0)"):
    val path = RealGhClient.branchProtectionApiPath(BranchName("release/1.0"))
    assertEquals(path, "repos/{owner}/{repo}/branches/release%2F1.0/protection/required_status_checks")

  test("branchProtectionApiPath: multiple '/' segments encoded"):
    val path = RealGhClient.branchProtectionApiPath(BranchName("team/foo/release"))
    assertEquals(path, "repos/{owner}/{repo}/branches/team%2Ffoo%2Frelease/protection/required_status_checks")
