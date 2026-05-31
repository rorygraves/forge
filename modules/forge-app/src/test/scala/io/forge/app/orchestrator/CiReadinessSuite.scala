package io.forge.app.orchestrator

import io.forge.app.config.CiConfig
import io.forge.core.PrNumber
import io.forge.core.CiPolicy
import io.forge.core.pr.{CheckConclusion, CheckResult, CheckRollup, CheckState, PrSnapshot, PrState}
import io.forge.git.branch.protection.{OverlaySource, RequiredChecksOverlay}

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** §8 CI-readiness policy unit suite — `evaluate` is pure (the discovery window reaches it as the `elapsed` argument),
  * so the timeout rules are exercised by passing explicit durations rather than driving a clock. The orchestrator-side
  * monotonic-clock integration is covered by the e2e suites under `OrchestratorE2E*`.
  */
class CiReadinessSuite extends munit.FunSuite:

  private val pr = PrNumber(5001)
  private val overlayUnprotected = RequiredChecksOverlay(Set.empty, Instant.EPOCH, OverlaySource.Unprotected)
  private def overlayProtected(names: String*): RequiredChecksOverlay =
    RequiredChecksOverlay(names.toSet, Instant.EPOCH, OverlaySource.Protected)

  private def check(name: String, conclusion: Option[CheckConclusion]): CheckResult =
    CheckResult(name, if conclusion.isDefined then CheckState.Completed else CheckState.InProgress, conclusion)
  private def green(name: String): CheckResult = check(name, Some(CheckConclusion.Success))
  private def failing(name: String): CheckResult = check(name, Some(CheckConclusion.Failure))
  private def pending(name: String): CheckResult = check(name, None)

  /** A snapshot carrying `observed` checks only (mirrors the real decoder, which never populates `required`). */
  private def snap(observed: CheckResult*): PrSnapshot =
    PrSnapshot(
      pr,
      PrState.Open,
      None,
      None,
      CheckRollup(Vector.empty, observed.toVector),
      None,
      Vector.empty,
      Some(true)
    )

  // ci config with overridable knobs; defaults match §18.
  private def cfg(
      requiredChecksOverlay: Vector[String] = Vector.empty,
      minimumExpectedChecks: Int = 1,
      checkDiscoveryTimeoutSec: Int = 180,
      stableGreenPolls: Int = 2
  ): CiConfig =
    CiConfig(
      policy = "branch_protection_then_observed",
      requiredChecksOverlay = requiredChecksOverlay,
      minimumExpectedChecks = minimumExpectedChecks,
      checkDiscoveryTimeoutSec = checkDiscoveryTimeoutSec,
      stableGreenPolls = stableGreenPolls
    )

  private val zero: FiniteDuration = 0.seconds
  private val pastTimeout: FiniteDuration = 200.seconds // > default 180s window

  private def eval(
      overlay: RequiredChecksOverlay,
      s: PrSnapshot,
      state: CiDiscoveryState = CiDiscoveryState.initial,
      elapsed: FiniteDuration = zero,
      policy: CiPolicy = CiPolicy.BranchProtectionThenObserved,
      ci: CiConfig = cfg()
  ): CiDecision =
    CiReadiness.evaluate(policy, ci, overlay, s, state, elapsed)

  // --- CiPolicy.None ---

  test("None → Forward(degenerate all-success), regardless of observed state"):
    val d = eval(overlayUnprotected, snap(failing("ci")), policy = CiPolicy.None)
    d match
      case CiDecision.Forward(rollup) =>
        assert(rollup.required.nonEmpty && rollup.required.forall(_.conclusion.contains(CheckConclusion.Success)))
      case other => fail(s"expected Forward(all-success), got $other")

  // --- overlay union + green gate ---

  test("union overlay: required set = branch-protection ∪ config overlay; all green @ stableGreenPolls=1 → Forward"):
    val d = eval(
      overlayProtected("build"),
      snap(green("build"), green("lint"), pending("docs")),
      ci = cfg(requiredChecksOverlay = Vector("lint"), stableGreenPolls = 1)
    )
    d match
      case CiDecision.Forward(rollup) =>
        assertEquals(rollup.required.map(_.name).toSet, Set("build", "lint"))
      case other => fail(s"expected Forward, got $other")

  test("failing required check → Forward immediately (no debounce; FSM routes to PieceCiFailed)"):
    val d = eval(overlayProtected("build"), snap(failing("build")), ci = cfg(stableGreenPolls = 2))
    d match
      case CiDecision.Forward(rollup) => assert(rollup.required.exists(_.name == "build"))
      case other => fail(s"expected Forward(failed), got $other")

  // --- §8 discovery rules ---

  test("§8 rule 1: no checks discovered after timeout → Blocked('no CI checks discovered')"):
    assertEquals(eval(overlayUnprotected, snap(), elapsed = pastTimeout), CiDecision.Blocked("no CI checks discovered"))

  test("§8 rule 1: no checks but still inside discovery window → KeepPolling"):
    assertEquals(eval(overlayUnprotected, snap(), elapsed = zero), CiDecision.KeepPolling(CiDiscoveryState(0)))

  test("§8 rule 3: fewer than minimumExpectedChecks after timeout → Blocked(only N…)"):
    val d = eval(overlayUnprotected, snap(green("a")), elapsed = pastTimeout, ci = cfg(minimumExpectedChecks = 2))
    assertEquals(d, CiDecision.Blocked("only 1 CI checks observed, expected at least 2"))

  test("§8 rule 2: required check from union never appeared after timeout → Blocked(with source)"):
    val d = eval(overlayProtected("build"), snap(green("lint")), elapsed = pastTimeout)
    assertEquals(d, CiDecision.Blocked("required check 'build' never appeared (source: Protected)"))

  test("§8 rule 2: required check missing but still inside window → KeepPolling (not blocked)"):
    assertEquals(
      eval(overlayProtected("build"), snap(green("lint")), elapsed = zero),
      CiDecision.KeepPolling(CiDiscoveryState(0))
    )

  // --- stableGreenPolls debounce ---

  test("stableGreenPolls=2: first all-green poll → KeepPolling(consecutiveGreen=1)"):
    assertEquals(
      eval(overlayUnprotected, snap(green("a")), ci = cfg(stableGreenPolls = 2)),
      CiDecision.KeepPolling(CiDiscoveryState(1))
    )

  test("stableGreenPolls=2: second consecutive all-green poll → Forward"):
    val d = eval(overlayUnprotected, snap(green("a")), state = CiDiscoveryState(1), ci = cfg(stableGreenPolls = 2))
    d match
      case CiDecision.Forward(rollup) => assertEquals(rollup.required.map(_.name), Vector("a"))
      case other => fail(s"expected Forward, got $other")

  test("stableGreenPolls: a non-green poll resets the consecutive-green count to 0"):
    assertEquals(
      eval(
        overlayUnprotected,
        snap(green("a"), pending("b")),
        state = CiDiscoveryState(1),
        ci = cfg(stableGreenPolls = 2)
      ),
      CiDecision.KeepPolling(CiDiscoveryState(0))
    )

  test("observed fallback: required set = all observed checks (not just a subset)"):
    val d = eval(overlayUnprotected, snap(green("a"), green("b")), ci = cfg(stableGreenPolls = 1))
    d match
      case CiDecision.Forward(rollup) => assertEquals(rollup.required.map(_.name).toSet, Set("a", "b"))
      case other => fail(s"expected Forward, got $other")
