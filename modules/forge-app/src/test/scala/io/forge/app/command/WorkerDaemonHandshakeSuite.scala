package io.forge.app.command

import cats.effect.{IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.{FeatureId, InstanceName}
import io.forge.daemon.{BudgetPolicy, CredentialBroker, Daemon, DaemonState, Supervisor}
import io.forge.instance.{FileInstanceStore, Instance}
import munit.CatsEffectSuite

import java.util.UUID
import scala.concurrent.duration.*

/** Task 4.3.6 — the **worker↔daemon handshake tie-together** for the Slice-4.3 exit criterion, exercised through the
  * actual worker-side seams in [[WorkerLoop]]'s exact order (register → broker credentials → reserve budget → finalize
  * on a `cost.update`) over **one** real served daemon wired with both a real [[RealCredentialBroker]] (O6) and a real
  * [[BudgetPolicy]] (B2).
  *
  * The individual RPCs are already proven in isolation — [[CredentialBrokerSuite]] (broker over the socket),
  * [[io.forge.daemon.DaemonReserveBudgetSuite]] (reserve cycle via the raw client). This suite proves they compose
  * through the [[WorkerReporter]] + [[ReportingBudgetReserver]] the containerised worker actually runs, in the order it
  * runs them — the logical core of the exit criterion that no single test covered. The remaining gap (the same flow
  * from a *real* `forge worker` process *inside the container* over a *bind-mounted* socket) is the Task 4.3.6 live
  * dogfood, because a host→container Unix-socket crossing is not portably automatable (Docker Desktop's VM boundary).
  */
class WorkerDaemonHandshakeSuite extends CatsEffectSuite:

  private val feature = FeatureId("feat")

  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"wh-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  /** Serve the daemon over the instance socket with the given broker + budget, exposing the [[DaemonState]] so a test
    * can snapshot the committed/outstanding totals and the worker's reported status.
    */
  private def served[A](inst: Instance, broker: CredentialBroker, budget: BudgetPolicy)(
      body: DaemonState => IO[A]
  ): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.portFile, state, shutdown, Supervisor.noop, broker, budget)
        .background
        .use(_ => body(state).guarantee(shutdown.complete(()).void))
    yield result

  private def realBroker(secrets: Map[String, String]): CredentialBroker =
    new RealCredentialBroker(CredentialPolicy.Default, SecretSource.fixed(secrets))

  private def costUpdate(usd: Double): ujson.Value =
    ujson.Obj("kind" -> "cost.update", "payload" -> ujson.Obj("usd" -> usd))

  // --- the happy-path tie-together: register → broker → reserve → finalize ----

  test("a worker registers, brokers host-isolated creds, reserves, then finalizes on its cost.update") {
    instance.use { inst =>
      val broker = realBroker(Map("FORGE_GH_TOKEN" -> "ghp_scoped", "ANTHROPIC_API_KEY" -> "sk-ant"))
      served(inst, broker, BudgetPolicy.default) { state =>
        for
          reporter <- WorkerReporter.daemon(inst.portFile, "w-1")
          // 1. register (WorkerLoop step 1)
          _ <- reporter.register("/repo", feature)
          // 2. broker credentials over the control channel (O6) — the scoped gh token, never a host login.
          env <- reporter.brokerCredentials("/repo")
          // 3. reserve budget before the (first) driver spawn via the daemon-backed reserver — a grant proceeds.
          _ <- new ReportingBudgetReserver(reporter, BigDecimal(8)).reserveBeforeSpawn
          afterReserve <- state.snapshot
          // 4. the exported cost.update fans into committed spend and finalizes the reservation.
          _ <- reporter.event(costUpdate(2.5))
          afterCost <- state.snapshot
        yield
          // brokered env is exactly the host-isolated secrets — the scoped token, nothing sourced from the host home.
          assertEquals(env.get("GH_TOKEN"), Some("ghp_scoped"))
          assertEquals(env.get("ANTHROPIC_API_KEY"), Some("sk-ant"))
          assert(env.keySet.subsetOf(Set("GH_TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY")), s"unexpected creds: $env")
          // after the grant: the 8.0 estimate is outstanding, nothing committed yet.
          assertEquals(afterReserve.outstandingUsd(), BigDecimal(8))
          assertEquals(afterReserve.committedUsd, BigDecimal(0))
          // after the cost.update: reservation finalized (outstanding cleared), actual spend committed.
          assertEquals(afterCost.outstandingUsd(), BigDecimal(0))
          assertEquals(afterCost.committedUsd, BigDecimal(2.5))
      }
    }
  }

  // --- the refuse path: a hold, never a mid-turn kill (§8) ---------------------

  test("a refused reservation makes the reserver hold (never proceeds) and reports a BudgetHold status") {
    instance.use { inst =>
      val broker = realBroker(Map("FORGE_GH_TOKEN" -> "ghp_scoped"))
      val tiny = BudgetPolicy(perWorkstreamCapUsd = BigDecimal(1), perInstanceCapUsd = BigDecimal(1))
      served(inst, broker, tiny) { state =>
        for
          reporter <- WorkerReporter.daemon(inst.portFile, "w-1")
          _ <- reporter.register("/repo", feature)
          reserver = new ReportingBudgetReserver(reporter, BigDecimal(8), holdBackoff = 50.millis)
          // The reserver must hold on a refuse: race it against a window — the window must win (it never proceeds).
          outcome <- IO.race(reserver.reserveBeforeSpawn, IO.sleep(400.millis))
          held <- state.snapshot
        yield
          assert(outcome.isRight, "a refused reservation must hold (the reserver must not proceed past the cap)")
          assertEquals(
            held.worker("w-1").map(_.status),
            Some(ReportingBudgetReserver.BudgetHoldStatus),
            "a held worker reports BudgetHold so the instance view shows it is waiting on budget"
          )
          // nothing was committed and no reservation leaked (a refuse records audit-only, no outstanding grant).
          assertEquals(held.committedUsd, BigDecimal(0))
          assertEquals(held.outstandingUsd(), BigDecimal(0))
      }
    }
  }
