package io.forge.daemon

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** Task 4.3.1 — the OCI runtime seam ([[DockerRuntime]]), the container side of the B4 boundary.
  *
  * Two layers, mirroring `WorkerSpawnerSuite`'s "unit the mechanism, prove it against real I/O" discipline:
  *
  *   - The **pure argv builders** (`runArgs`/`mountArg`/`waitArgs`/…) are unit-tested with **no Docker** — the flag
  *     translation is the part most likely to drift, and it must stay fast + always-on (the `<60s` default-on rule).
  *   - The **real-container lifecycle** (run → await exit code → kill → finalizer-removes) is proven against a **real**
  *     Docker daemon, **opt-in** via `FORGE_IT_RUN_DOCKER=1` (it needs a running Docker daemon and may pull `busybox`,
  *     so it is not part of the default-on suite — the `FORGE_IT_RUN_*` convention). `busybox` is the smallest
  *     universally-available image; `sh -c 'exit N'` / `sleep` are busybox builtins.
  *
  * The full tie-together — the daemon running a real `forge worker` container that reaches it over a mounted socket —
  * is the Task 4.3.6 live dogfood; here the runtime mechanism is unit/real-container-proven on its own.
  */
class OciRuntimeSuite extends CatsEffectSuite:

  private val dockerEnabled: Boolean = sys.env.get("FORGE_IT_RUN_DOCKER").contains("1")
  private val image = "busybox"

  // --- pure argv builders (always-on, no Docker) ---

  test("runArgs: image-only spec is `docker run -d <image>`") {
    assertEquals(DockerRuntime.runArgs(ContainerSpec(image = image)), Vector("docker", "run", "-d", "busybox"))
  }

  test("runArgs: full spec translates every field in flag order, env keys sorted") {
    val spec = ContainerSpec(
      image = image,
      command = Seq("sh", "-c", "echo hi"),
      workdir = Some("/work"),
      env = Map("ZED" -> "2", "ALPHA" -> "1"),
      mounts = Seq(Mount(os.root / "host" / "clone", "/clone"), Mount(os.root / "sock", "/sock", readOnly = true)),
      network = Some("none"),
      name = Some("forge-w-1"),
      removeOnExit = true
    )
    assertEquals(
      DockerRuntime.runArgs(spec),
      Vector(
        "docker",
        "run",
        "-d",
        "--rm",
        "--name",
        "forge-w-1",
        "--network",
        "none",
        "-w",
        "/work",
        // env sorted by key (ALPHA before ZED) so the argv is stable regardless of Map order
        "-e",
        "ALPHA=1",
        "-e",
        "ZED=2",
        "-v",
        "/host/clone:/clone",
        "-v",
        "/sock:/sock:ro",
        "busybox",
        "sh",
        "-c",
        "echo hi"
      )
    )
  }

  test("runArgs: removeOnExit defaults false (no --rm) — a supervised worker must be reattachable after it exits") {
    assert(!DockerRuntime.runArgs(ContainerSpec(image = image)).contains("--rm"))
  }

  test("mountArg: read-only appends :ro, read-write does not") {
    assertEquals(DockerRuntime.mountArg(Mount(os.root / "a", "/b")), "/a:/b")
    assertEquals(DockerRuntime.mountArg(Mount(os.root / "a", "/b", readOnly = true)), "/a:/b:ro")
  }

  test("wait/kill/rm/inspect argv shapes") {
    assertEquals(DockerRuntime.waitArgs("cid"), Vector("docker", "wait", "cid"))
    assertEquals(DockerRuntime.killArgs("cid"), Vector("docker", "kill", "cid"))
    assertEquals(DockerRuntime.rmArgs("cid"), Vector("docker", "rm", "-f", "cid"))
    assertEquals(DockerRuntime.inspectArgs("cid"), Vector("docker", "inspect", "-f", "{{.State.Running}}", "cid"))
  }

  // --- real-container lifecycle (opt-in: FORGE_IT_RUN_DOCKER=1, needs a Docker daemon) ---

  test("run a real container that exits with a code; awaitExit yields it; id is non-empty") {
    assume(dockerEnabled, "set FORGE_IT_RUN_DOCKER=1 (and have a running Docker daemon) to run the live container test")
    DockerRuntime.run(ContainerSpec(image = image, command = Seq("sh", "-c", "exit 7"))).use { handle =>
      handle.awaitExit.map { code =>
        assert(handle.containerId.nonEmpty, "expected a real container id")
        assertEquals(code, 7)
      }
    }
  }

  test("a long-running container can be killed; kill is idempotent and awaitExit then completes") {
    assume(dockerEnabled, "set FORGE_IT_RUN_DOCKER=1 (and have a running Docker daemon) to run the live container test")
    DockerRuntime.run(ContainerSpec(image = image, command = Seq("sleep", "120"))).use { handle =>
      for
        _ <- handle.kill
        code <- handle.awaitExit.timeout(15.seconds)
        // A killed container exits non-zero (SIGKILL → 137); we only assert it is not a clean 0.
        _ = assertNotEquals(code, 0)
        _ <- handle.kill // idempotent: killing an already-stopped container is a no-op
      yield ()
    }
  }

  test("running + attach reattach a live container by id (the §6.4 daemon-restart probe)") {
    assume(dockerEnabled, "set FORGE_IT_RUN_DOCKER=1 (and have a running Docker daemon) to run the live container test")
    // Start a sleeper detached, learn its id, then — *without* the original handle — probe liveness and reattach by id
    // (exactly what the supervisor's reconcile does after a daemon restart), kill it, and confirm it reads not-running.
    DockerRuntime.run(ContainerSpec(image = image, command = Seq("sleep", "120"))).use { spawned =>
      val id = spawned.containerId
      for
        liveBefore <- DockerRuntime.running(id)
        _ = assert(liveBefore, "a freshly-run container reads as running")
        reattached = DockerRuntime.attach(id)
        _ <- reattached.kill
        _ <- reattached.awaitExit.timeout(15.seconds)
        liveAfter <- DockerRuntime.running(id)
        _ = assert(!liveAfter, "a killed container reads as not-running")
        unknown <- DockerRuntime.running("does-not-exist")
        _ = assert(!unknown, "an unknown container reads as not-running rather than raising")
      yield ()
    }
  }

  test("the Resource finalizer force-removes a still-running container") {
    assume(dockerEnabled, "set FORGE_IT_RUN_DOCKER=1 (and have a running Docker daemon) to run the live container test")
    for
      id <- DockerRuntime
        .run(ContainerSpec(image = image, command = Seq("sleep", "120")))
        .use(h => IO.pure(h.containerId))
      // After release, the container must no longer exist (`docker inspect` exits non-zero for an unknown id).
      gone <- IO.blocking(os.proc("docker", "inspect", id).call(check = false, stderr = os.Pipe).exitCode != 0)
    yield assert(gone, s"expected container $id to be removed after Resource release")
  }
