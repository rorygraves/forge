package io.forge.app.command

import cats.effect.{IO, Ref, Resource}
import io.forge.app.config.ForgeConfig
import io.forge.core.FeatureId
import io.forge.core.paths.ForgePaths
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.3.4 — the worker-side credential-brokering wiring in [[WorkerLoop]]: a **containerised** worker
  * (`brokerCredentials = true`) requests its host-isolated credentials over the control channel (O6) after registering;
  * a host-process worker (`false`) does not (it inherits the daemon's environment). Exercised over an empty clone (no
  * manifest), so the loop returns early via the `noManifest` path *after* the register + broker steps — enough to
  * assert the brokering decision without a real orchestrator/connector. The end-to-end credential application inside a
  * real container is the Task 4.3.6 dogfood.
  */
class WorkerLoopBrokerSuite extends CatsEffectSuite:

  private val feature = FeatureId("feat")

  /** A fake reporter recording which RPCs the loop made; `brokerCredentials` returns a canned scoped-token env. */
  private final class RecordingReporter(calls: Ref[IO, Vector[String]]) extends WorkerReporter:
    def register(repo: String, feature: FeatureId): IO[Unit] = calls.update(_ :+ s"register:$repo")
    def status(status: String): IO[Unit] = calls.update(_ :+ s"status:$status")
    def event(event: ujson.Value): IO[Unit] = calls.update(_ :+ "event")
    def brokerCredentials(repo: String): IO[Map[String, String]] =
      calls.update(_ :+ s"broker:$repo").as(Map("GH_TOKEN" -> "scoped"))

  /** An empty clone dir (no manifest) the loop re-roots its paths onto, cleaned up after. */
  private val emptyClone: Resource[IO, ForgePaths] =
    Resource.make(IO.blocking {
      val dir = os.Path("/tmp") / s"fw-${UUID.randomUUID().toString.take(8)}"
      os.makeDir.all(dir)
      new ForgePaths(dir, dir, localRootOpt = Some(dir))
    })(p => IO.blocking(os.remove.all(p.repoRoot)))

  private def runWith(brokerCredentials: Boolean): IO[Vector[String]] =
    emptyClone.use { paths =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap { calls =>
        WorkerLoop
          .run(paths, ForgeConfig(), new RecordingReporter(calls), "/repos/szork", feature, brokerCredentials)
          .attempt *> calls.get
      }
    }

  test("container mode brokers credentials over the control channel after registering") {
    runWith(brokerCredentials = true).map { calls =>
      assertEquals(calls.headOption, Some("register:/repos/szork"))
      assert(calls.contains("broker:/repos/szork"), s"expected a broker-credentials call, got $calls")
    }
  }

  test("host mode does not broker credentials (it inherits the daemon's environment)") {
    runWith(brokerCredentials = false).map { calls =>
      assertEquals(calls.headOption, Some("register:/repos/szork"))
      assert(!calls.exists(_.startsWith("broker:")), s"host worker must not broker, got $calls")
    }
  }
