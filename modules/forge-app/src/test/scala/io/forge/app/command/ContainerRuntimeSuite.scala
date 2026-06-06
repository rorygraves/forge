package io.forge.app.command

import cats.effect.{IO, Resource}
import io.forge.core.{FeatureId, InstanceName}
import io.forge.daemon.{DockerRuntime, Mount}
import io.forge.instance.Instance
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.3.4 — always-on (no Docker) coverage of [[ContainerRuntime]]: the pure [[ContainerRuntime.buildSpec]]
  * container-spec builder (the mounts, the **absence of any host-home mount** — the O6 crux of the G4 claim, the `forge
  * worker` argv with the explicit in-container paths) and [[ContainerRuntime.resolveImage]] (the clone's
  * `Forgefile.image` or the default fallback). The real containerised-spawn-and-reconcile tie-together is the opt-in
  * [[SupervisorContainerReconcileSuite]] + the Task 4.3.6 dogfood.
  */
class ContainerRuntimeSuite extends CatsEffectSuite:

  private val instance = Instance.at(InstanceName("demo"), os.Path("/tmp") / "forge-home-stub")
  private val feature = FeatureId("add-auth")

  // --- buildSpec (pure) ---

  test("buildSpec mounts only the worker root + the control socket — never the host home (O6)") {
    val spec = ContainerRuntime.buildSpec(instance, "w-1", "/repos/szork", feature, "img:1")
    assertEquals(
      spec.mounts.toSet,
      Set(
        Mount(instance.workerRoot("w-1"), ContainerRuntime.ContainerWorkerRoot),
        Mount(instance.socketFile, ContainerRuntime.ContainerSocket)
      )
    )
    val home = os.home.toString
    assert(
      spec.mounts.forall(m => !m.source.toString.startsWith(home)),
      s"no mount may touch the host home: ${spec.mounts}"
    )
    assert(spec.env.isEmpty, "secrets must never enter the container spec (they are brokered over the socket, O6)")
  }

  test("buildSpec carries the resolved image, the in-container checkout workdir, a stable name, and is not --rm") {
    val spec = ContainerRuntime.buildSpec(instance, "w-1", "/repo", feature, "myimage:2")
    assertEquals(spec.image, "myimage:2")
    assertEquals(spec.workdir, Some(s"${ContainerRuntime.ContainerWorkerRoot}/checkout"))
    assertEquals(spec.name, Some("forge-demo-w-1"))
    assertEquals(spec.removeOnExit, false, "a supervised worker must stay reattachable/inspectable after it exits")
  }

  test("buildSpec runs `forge worker` with the explicit in-container paths + --container") {
    val spec = ContainerRuntime.buildSpec(instance, "w-7", "/repo", feature, "img")
    assertEquals(spec.command.take(2), Seq(ContainerRuntime.WorkerEntrypoint, "worker"))
    assert(spec.command.containsSlice(Seq("--worker-id", "w-7")))
    assert(spec.command.containsSlice(Seq("--feature", feature.value)))
    assert(spec.command.containsSlice(Seq("--worker-root", ContainerRuntime.ContainerWorkerRoot)))
    assert(spec.command.containsSlice(Seq("--socket", ContainerRuntime.ContainerSocket)))
    assert(spec.command.contains("--container"), "the worker must be told to broker credentials over the socket")
  }

  // --- resolveImage (reads the clone's Forgefile) ---

  /** A throwaway instance home with a worker checkout dir, cleaned up after. */
  private val withClone: Resource[IO, (Instance, os.Path)] =
    Resource.make {
      IO.blocking {
        val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
        val inst = Instance.at(InstanceName("demo"), home)
        os.makeDir.all(inst.workerCheckout("w-1"))
        (inst, home)
      }
    } { case (_, home) => IO.blocking(os.remove.all(home)) }

  test("resolveImage falls back to the default image when the clone has no Forgefile") {
    withClone.use { case (inst, _) =>
      new ContainerRuntime(DockerRuntime, "fallback:img").resolveImage(inst, "w-1").map { img =>
        assertEquals(img, "fallback:img")
      }
    }
  }

  test("resolveImage returns the clone's committed Forgefile image (O1 source of truth)") {
    withClone.use { case (inst, _) =>
      IO.blocking(os.write(inst.workerCheckout("w-1") / "Forgefile", "image ghcr.io/acme/forge-worker:9\n")) *>
        new ContainerRuntime(DockerRuntime, "fallback:img").resolveImage(inst, "w-1").map { img =>
          assertEquals(img, "ghcr.io/acme/forge-worker:9")
        }
    }
  }
