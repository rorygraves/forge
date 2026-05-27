package io.forge.git.watcher

import cats.effect.{IO, Ref}
import fs2.Stream
import io.forge.core.PrNumber
import io.forge.git.cli.{GhClient, GhError}

import scala.concurrent.duration.FiniteDuration

/** Concrete [[PRWatcher]] over a [[GhClient]] + [[PrSnapshotDecoder]].
  *
  * The polling loop is a `fs2.Stream` that:
  *
  *   1. reads the current `baseline` from the `Ref`;
  *   1. calls [[pollOnce]] (one `gh pr view --json …` round-trip + decode);
  *   1. on [[PollResult.Snapshot]], writes `decoded.nextBaseline` back to the `Ref`;
  *   1. sleeps for an interval determined by the result (see [[sleepFor]]);
  *   1. emits the [[PollResult]] downstream.
  *
  * **Rate-limit recovery (RL1, D3).** Successive [[PollResult.RateLimited]] results increment an internal counter held
  * in a fresh `Ref` per `watch` call. When the counter reaches [[PRWatcherConfig.consecutiveRateLimitsBeforeFailing]],
  * the next rate-limit is *also* emitted as a [[PollResult.Failed]] carrying the underlying `GhError.RateLimited`, so
  * the orchestrator can decide whether to keep watching or escalate. The counter resets on any other result.
  *
  * **Carry-forward S3-4.** The choice to keep emitting `RateLimited` events past the threshold (rather than terminating
  * the stream) is the non-failing-event posture filed against design-rationale. Slice 4 may tighten it; PR-D ships the
  * soft variant so a single rate-limit blip doesn't fail the FSM.
  */
final class RealPRWatcher(
    gh: GhClient,
    config: PRWatcherConfig
) extends PRWatcher:

  override def pollOnce(pr: PrNumber, baseline: PollBaseline): IO[PollResult] =
    gh.prView(pr, config.requestedFields).map {
      case Left(err: GhError.RateLimited) => PollResult.RateLimited(err.retryAfter)
      case Left(err) => PollResult.Failed(err)
      case Right(json) =>
        PrSnapshotDecoder.decode(json, baseline, config.botLogin) match
          case Right(decoded) => PollResult.Snapshot(decoded)
          case Left(decodeErr) =>
            PollResult.Failed(GhError.ParseFailure("pr-view-decode", DecodeException(decodeErr), decodeErr.toString))
    }

  override def watch(pr: PrNumber, baseline: Ref[IO, PollBaseline]): Stream[IO, PollResult] =
    Stream.eval(Ref.of[IO, Int](0)).flatMap { consecutiveRateLimits =>
      Stream.repeatEval(stepOnce(pr, baseline, consecutiveRateLimits)).evalTap { result =>
        IO.sleep(sleepFor(result))
      }
    }

  /** One poll + baseline / counter bookkeeping. The returned [[PollResult]] is what downstream observers see. */
  private def stepOnce(
      pr: PrNumber,
      baseline: Ref[IO, PollBaseline],
      consecutiveRateLimits: Ref[IO, Int]
  ): IO[PollResult] =
    for
      current <- baseline.get
      raw <- pollOnce(pr, current)
      out <- raw match
        case PollResult.Snapshot(decoded) =>
          baseline.set(decoded.nextBaseline) *> consecutiveRateLimits.set(0).as(raw)
        case PollResult.RateLimited(retryAfter) =>
          consecutiveRateLimits.updateAndGet(_ + 1).map { count =>
            if count >= config.consecutiveRateLimitsBeforeFailing then
              PollResult.Failed(GhError.RateLimited(retryAfter, s"$count consecutive rate-limited polls"))
            else raw
          }
        case _: PollResult.Failed =>
          consecutiveRateLimits.set(0).as(raw)
    yield out

  /** Choose the inter-poll sleep based on the most recent result:
    *
    *   - [[PollResult.RateLimited]] with `retryAfter = Some(d)` → sleep `d`.
    *   - [[PollResult.RateLimited]] without `retryAfter` → sleep [[PRWatcherConfig.rateLimitBackoff]].
    *   - [[PollResult.Failed]] from a promoted rate-limit threshold breach → also use the rate-limit back-off (so the
    *     orchestrator gets a breather before deciding what to do).
    *   - everything else → [[PRWatcherConfig.pollInterval]].
    */
  private def sleepFor(result: PollResult): FiniteDuration =
    result match
      case PollResult.RateLimited(Some(d)) => d
      case PollResult.RateLimited(None) => config.rateLimitBackoff
      case PollResult.Failed(_: GhError.RateLimited) => config.rateLimitBackoff
      case _ => config.pollInterval

/** Wraps a [[DecodeError]] inside a `Throwable` so [[GhError.ParseFailure]] can carry it through its `cause` field
  * without forcing the decoder to throw. The decoder itself is pure — the exception is constructed only at the watcher
  * boundary where we need the existing parse-failure plumbing.
  */
private final case class DecodeException(decodeError: DecodeError) extends RuntimeException(decodeError.toString)
