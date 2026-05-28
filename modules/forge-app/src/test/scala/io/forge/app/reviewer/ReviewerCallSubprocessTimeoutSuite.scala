package io.forge.app.reviewer

import cats.effect.IO
import io.forge.agents.{ClaudeConnector, DesignReviewInput, ReviewerAssets}
import io.forge.core.FeatureId
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** Task 1.4.7 review round 1 (Finding 1) — prove the reviewer wall-clock cap bounds **real** wall-clock against a
  * **real subprocess**, not just an `IO.never` fake under `TestControl`.
  *
  * [[ReviewerCallWallClockSuite]] cancels an `IO.never`, which is trivially cancelable — so the cap fires instantly
  * there. A real `claude` subprocess is different: the reviewer collector drains stdout via uninterruptible
  * `IO.blocking` reads that only return on process EOF. If the cap's `IO.race` cancellation cannot kill the process, it
  * can deadlock (cancellation waits for the blocked read; the read waits for EOF; EOF waits for the kill — which is
  * sequenced after cancellation). That is the suspected cause of the live smoke taking ~90 min despite a 3-min cap
  * (design-1.4 §S4-3 watch-item).
  *
  * This test spawns a fake `claude` that emits nothing and sleeps far longer than the cap, then asserts the cap returns
  * [[ReviewerOutcome.Timeout]] within a small real-wall-clock bound. With a working cap the call returns in ~the cap
  * duration; if the cap is defeated, the call runs until the fake exits (or `munitTimeout`), and the assertion fails
  * with the observed elapsed time.
  */
class ReviewerCallSubprocessTimeoutSuite extends CatsEffectSuite:

  private val designInput =
    DesignReviewInput(featureId = FeatureId("feat-1"), round = 1, designMarkdown = "# design")

  private def fakeScript(prefix: String, body: String): os.Path =
    val script = os.temp(contents = s"#!/bin/sh\n$body\n", prefix = prefix, suffix = ".sh", deleteOnExit = true)
    os.perms.set(script, "rwx------")
    script

  /** Produces no output, just blocks — the drain fiber parks in a single uninterruptible read. */
  private def fakeSilentHang(sleepSeconds: Int): os.Path =
    fakeScript("fake-claude-silent-", s"sleep $sleepSeconds")

  /** Streams output forever — the drain fiber loops over many reads. Closer to a real `claude` that is actively
    * emitting while the cap should still fire.
    */
  private def fakeChattyHang(): os.Path =
    fakeScript("fake-claude-chatty-", "while true; do echo '{\"partial\":true}'; sleep 0.05; done")

  private def assets(): ReviewerAssets =
    val schema = os.temp(contents = """{"type":"object"}""", prefix = "schema-", suffix = ".json", deleteOnExit = true)
    val sys = os.temp(contents = "Review the design", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schema, sys),
      prReview = ReviewerAssets.PerMethod(schema, sys),
      refine = ReviewerAssets.PerMethod(schema, sys)
    )

  private def assertCapped(binary: os.Path): IO[Unit] =
    val call = new RealReviewerCall(ClaudeConnector(binary = binary.toString, reviewerAssets = Some(assets())))
    call.designReview(designInput, ReviewerLimits(wallClockTimeout = 2.seconds)).timed.map { (elapsed, outcome) =>
      assertEquals(
        outcome,
        ReviewerOutcome.Timeout,
        clue = s"expected the 2s cap to fire Timeout; got $outcome after $elapsed"
      )
      assert(
        elapsed < 15.seconds,
        clue = s"wall-clock cap must bound real time (subprocess must be killed); call took $elapsed"
      )
    }

  test("cap bounds a silent hanging subprocess (drain parked on one uninterruptible read)"):
    // Fake sleeps 30s with no output; cap is 2s. A working cap returns Timeout in ~2s; a defeated cap runs ~30s then
    // surfaces an adapter error (empty stdout) — either way the assertions fail loudly with the observed timing.
    assertCapped(fakeSilentHang(30))

  test("cap bounds a chatty hanging subprocess (drain looping over many reads — closer to live claude)"):
    assertCapped(fakeChattyHang())
