package io.forge.app.command

import cats.effect.{IO, Ref}
import io.forge.app.orchestrator.ReservationOutcome
import io.forge.core.FeatureId
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** Task 4.3.5 (B2) — the worker-side reserver: [[ReportingBudgetReserver]] proceeds on a grant and *holds* (reporting a
  * budget-hold status, retrying) on a refuse, never raising; plus the [[WorkerReporter.parseReservation]] wire decode.
  */
class BudgetReserverSuite extends CatsEffectSuite:

  /** A reporter whose `reserveBudget` pops a scripted queue of outcomes and records each `status` call. */
  private final class ScriptedReporter(outcomes: Ref[IO, List[ReservationOutcome]], statuses: Ref[IO, Vector[String]])
      extends WorkerReporter:
    def register(repo: String, feature: FeatureId): IO[Unit] = IO.unit
    def status(status: String): IO[Unit] = statuses.update(_ :+ status)
    def event(event: ujson.Value): IO[Unit] = IO.unit
    def brokerCredentials(repo: String): IO[Map[String, String]] = IO.pure(Map.empty)
    def reserveBudget(estimateUsd: BigDecimal): IO[ReservationOutcome] =
      outcomes.modify {
        case head :: tail => (tail, head)
        case Nil => (Nil, ReservationOutcome.Granted("res-final"))
      }

  test("a grant lets the spawn proceed with no budget-hold status"):
    for
      outcomes <- Ref.of[IO, List[ReservationOutcome]](List(ReservationOutcome.Granted("res-1")))
      statuses <- Ref.of[IO, Vector[String]](Vector.empty)
      reserver = new ReportingBudgetReserver(new ScriptedReporter(outcomes, statuses), BigDecimal(8), 1.milli)
      _ <- reserver.reserveBeforeSpawn
      reported <- statuses.get
    yield assertEquals(reported, Vector.empty)

  test("a refuse holds (reports BudgetHold, retries) until a later grant, never raising"):
    for
      outcomes <- Ref.of[IO, List[ReservationOutcome]](
        List(
          ReservationOutcome.Refused("cap reached"),
          ReservationOutcome.Refused("cap reached"),
          ReservationOutcome.Granted("res-3")
        )
      )
      statuses <- Ref.of[IO, Vector[String]](Vector.empty)
      reserver = new ReportingBudgetReserver(new ScriptedReporter(outcomes, statuses), BigDecimal(8), 1.milli)
      _ <- reserver.reserveBeforeSpawn
      reported <- statuses.get
    yield assertEquals(reported, Vector.fill(2)(ReportingBudgetReserver.BudgetHoldStatus))

  test("parseReservation decodes granted, refused (with and without a reason), and rejects a contract violation"):
    assertEquals(
      WorkerReporter.parseReservation(ujson.Obj("granted" -> true, "reservationId" -> "res-1")).toOption,
      Some(ReservationOutcome.Granted("res-1"))
    )
    assertEquals(
      WorkerReporter.parseReservation(ujson.Obj("granted" -> false, "reason" -> "cap reached")).toOption,
      Some(ReservationOutcome.Refused("cap reached"))
    )
    // a refuse with no reason still parses (a refuse must never fail to decode)
    WorkerReporter.parseReservation(ujson.Obj("granted" -> false)).toOption match
      case Some(ReservationOutcome.Refused(_)) => ()
      case other => fail(s"expected a Refused, got $other")
    // a granted body missing the reservationId is a contract violation
    assert(WorkerReporter.parseReservation(ujson.Obj("granted" -> true)).isLeft)
    // a body without the granted flag is a contract violation
    assert(WorkerReporter.parseReservation(ujson.Obj("reservationId" -> "x")).isLeft)
