package io.forge.app.command

import cats.effect.{IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.{FeatureId, InstanceName}
import io.forge.daemon.{BrokerError, BrokeredCredentials, CredentialBroker, Daemon, DaemonState}
import io.forge.instance.{FileInstanceStore, Instance}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.3.3 — the real credential broker ([[RealCredentialBroker]], O6) and the worker-side request
  * ([[WorkerReporter.brokerCredentials]]).
  *
  * Two layers:
  *   - [[RealCredentialBroker]] resolution against a fixed [[SecretSource]] (no real process env): the per-repo PAT
  *     sourcing, the agent-key best-effort + `missing` reporting, the per-repo override, and the
  *     **refuse-don't-fall-back** posture when the required `gh` token is unconfigured.
  *   - the end-to-end round-trip ([[WorkerReporter.daemon]] → an in-process daemon wired with the real broker), proving
  *     the worker receives exactly the brokered env over the control channel — the `served` idiom from
  *     [[WorkerFeedExporterSuite]].
  */
class CredentialBrokerSuite extends CatsEffectSuite:

  // --- RealCredentialBroker resolution (no process env) -----------------------

  private def broker(policy: CredentialPolicy, secrets: Map[String, String]): CredentialBroker =
    new RealCredentialBroker(policy, SecretSource.fixed(secrets))

  test("brokers the scoped gh token + both agent keys when all are configured") {
    broker(
      CredentialPolicy.Default,
      Map("FORGE_GH_TOKEN" -> "ghp_scoped", "ANTHROPIC_API_KEY" -> "sk-ant", "OPENAI_API_KEY" -> "sk-oai")
    ).brokerFor("w-1", "/repo").map {
      case Right(BrokeredCredentials(env, missing)) =>
        assertEquals(env("GH_TOKEN"), "ghp_scoped")
        assertEquals(env("ANTHROPIC_API_KEY"), "sk-ant")
        assertEquals(env("OPENAI_API_KEY"), "sk-oai")
        assertEquals(missing, Vector.empty)
      case other => fail(s"expected brokered creds, got $other")
    }
  }

  test("an absent agent key is reported in `missing`, not refused; the gh token still brokers") {
    broker(CredentialPolicy.Default, Map("FORGE_GH_TOKEN" -> "ghp_scoped", "ANTHROPIC_API_KEY" -> "sk-ant"))
      .brokerFor("w-1", "/repo")
      .map {
        case Right(BrokeredCredentials(env, missing)) =>
          assert(env.contains("GH_TOKEN"))
          assert(env.contains("ANTHROPIC_API_KEY"))
          assert(!env.contains("OPENAI_API_KEY"))
          assertEquals(missing, Vector("OPENAI_API_KEY"))
        case other => fail(s"expected brokered creds, got $other")
      }
  }

  test("refuses (does not fall back to a host-wide token) when the required gh token is unconfigured") {
    broker(CredentialPolicy.Default, Map("ANTHROPIC_API_KEY" -> "sk-ant")).brokerFor("w-1", "/repo").map {
      case Left(BrokerError.MissingRequiredSecret(envVar)) => assertEquals(envVar, "FORGE_GH_TOKEN")
      case other => fail(s"expected a MissingRequiredSecret refusal, got $other")
    }
  }

  test("a set-but-empty secret reads as absent (so an empty gh token still refuses)") {
    broker(CredentialPolicy.Default, Map("FORGE_GH_TOKEN" -> "")).brokerFor("w-1", "/repo").map {
      case Left(BrokerError.MissingRequiredSecret(envVar)) => assertEquals(envVar, "FORGE_GH_TOKEN")
      case other => fail(s"expected a MissingRequiredSecret refusal, got $other")
    }
  }

  test("a per-repo override sources the gh token from the repo-specific env var") {
    val policy = CredentialPolicy(ghTokenEnvByRepo = Map("/repo/llm4s" -> "GITHUB_LLM4S_PAT"))
    val secrets = Map("FORGE_GH_TOKEN" -> "ghp_default", "GITHUB_LLM4S_PAT" -> "ghp_llm4s")
    broker(policy, secrets).brokerFor("w-1", "/repo/llm4s").map {
      case Right(creds) => assertEquals(creds.env("GH_TOKEN"), "ghp_llm4s")
      case other => fail(s"expected brokered creds, got $other")
    }
  }

  test("a repo with no override falls back to the default gh-token env var") {
    val policy = CredentialPolicy(ghTokenEnvByRepo = Map("/repo/llm4s" -> "GITHUB_LLM4S_PAT"))
    val secrets = Map("FORGE_GH_TOKEN" -> "ghp_default", "GITHUB_LLM4S_PAT" -> "ghp_llm4s")
    broker(policy, secrets).brokerFor("w-1", "/repo/other").map {
      case Right(creds) => assertEquals(creds.env("GH_TOKEN"), "ghp_default")
      case other => fail(s"expected brokered creds, got $other")
    }
  }

  // --- WorkerReporter.parseCredentials (wire decode) --------------------------

  test("parseCredentials decodes the {env} object into the env map") {
    val body = ujson.Obj("env" -> ujson.Obj("GH_TOKEN" -> "t", "ANTHROPIC_API_KEY" -> "k"), "missing" -> ujson.Arr())
    assertEquals(WorkerReporter.parseCredentials(body), Right(Map("GH_TOKEN" -> "t", "ANTHROPIC_API_KEY" -> "k")))
  }

  test("parseCredentials fails a body missing 'env' rather than returning an empty map") {
    assert(WorkerReporter.parseCredentials(ujson.Obj("missing" -> ujson.Arr())).isLeft)
  }

  test("parseCredentials fails a non-string env value") {
    assert(WorkerReporter.parseCredentials(ujson.Obj("env" -> ujson.Obj("GH_TOKEN" -> 7))).isLeft)
  }

  // --- end-to-end round-trip over the control channel -------------------------

  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"cb-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  private def served[A](inst: Instance, broker: CredentialBroker)(body: => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.socketFile, state, shutdown, io.forge.daemon.Supervisor.noop, broker)
        .background
        .use(_ => body.guarantee(shutdown.complete(()).void))
    yield result

  /** A reporter whose `register` (via `callWithRetry`) has already raced the daemon binding its socket — the real
    * worker order (register, then broker), so a later plain `call` for `broker-credentials` finds the socket bound.
    */
  private def reporterFor(inst: Instance): IO[WorkerReporter] =
    WorkerReporter
      .daemon(inst.socketFile, "w-1")
      .flatTap(_.register("/repo", FeatureId("feat")))

  test("a worker receives the brokered env over the control channel") {
    instance.use { inst =>
      val b = broker(CredentialPolicy.Default, Map("FORGE_GH_TOKEN" -> "ghp_scoped", "OPENAI_API_KEY" -> "sk-oai"))
      served(inst, b) {
        for
          reporter <- reporterFor(inst)
          env <- reporter.brokerCredentials("/repo")
        yield
          assertEquals(env("GH_TOKEN"), "ghp_scoped")
          assertEquals(env("OPENAI_API_KEY"), "sk-oai")
          assert(!env.contains("ANTHROPIC_API_KEY"))
      }
    }
  }

  test("a broker refusal is raised to the worker's degrade path") {
    instance.use { inst =>
      val b = broker(CredentialPolicy.Default, Map.empty) // no gh token ⇒ refuse
      served(inst, b) {
        reporterFor(inst)
          .flatMap(_.brokerCredentials("/repo"))
          .attempt
          .map {
            case Left(t) => assert(t.getMessage.contains("FORGE_GH_TOKEN"), t.getMessage)
            case Right(env) => fail(s"expected a raised refusal, got $env")
          }
      }
    }
  }
