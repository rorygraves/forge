package io.forge.app.command

import cats.effect.{IO, Resource}
import io.forge.core.FeatureId
import io.forge.core.forgefile.FileForgefileStore
import io.forge.core.paths.ForgePaths
import io.forge.daemon.{ContainerSpec, DaemonAddress, Mount, OciRuntime, WorkerSpawner}
import io.forge.instance.Instance

/** Task 4.3.4 — the seam abstracting **how** the daemon launches a worker: as a host OS process (Slice 4.2) or inside
  * an isolated OCI container (Slice 4.3). It sits between the [[RealSupervisor]] (which owns provisioning, the durable
  * `worker.spawned`/`worker.exited` records, idempotency, and the §6.4 reconcile) and the two low-level mechanisms
  * ([[io.forge.daemon.WorkerSpawner]] for a host process, [[io.forge.daemon.OciRuntime]] for a container).
  *
  * The supervisor depends only on this seam, so its hard parts (the single-writer allocation gate, the reservation
  * table, the exit-watch) are identical for both topologies; only the spawn mechanism + the recorded **liveness key**
  * differ. A host worker's key is its child `pid`; a container worker's key is its OCI container id — exactly the
  * `pid`-or-`containerId` split `worker.spawned` / [[io.forge.instance.WorkerRecord]] now carry.
  */
trait WorkerRuntime:

  /** Launch worker `workerId` for `feature` on registered `repo` under `instance`, scoped to a `Resource` (the
    * supervisor orphans it via `.allocated`, as in 4.2 — a worker must outlive the daemon, §6.4). The clone is already
    * provisioned by the supervisor before this is called (host-side, even for a container — the container mounts it);
    * this only starts the execution unit and yields a topology-agnostic [[LaunchedWorker]].
    */
  def launch(instance: Instance, workerId: String, repo: String, feature: FeatureId): Resource[IO, LaunchedWorker]

/** The liveness key a spawned worker is reattached by across a daemon restart (§6.4) — the daemon-side identity the
  * supervisor records in `worker.spawned` and re-probes on reconcile.
  */
enum LivenessKey:
  /** A host-process worker (Slice 4.2): the OS child pid, re-probed via [[ProcessHandle]]. */
  case HostPid(pid: Long)

  /** A containerised worker (Slice 4.3): the OCI container id, re-probed via [[io.forge.daemon.OciRuntime.running]]. */
  case ContainerId(id: String)

/** A handle to a freshly-launched worker (Task 4.3.4) — the topology-agnostic surface the supervisor watches, unifying
  * [[io.forge.daemon.WorkerHandle]] (pid + await + kill) and [[io.forge.daemon.ContainerHandle]] (id + await + kill)
  * behind a single [[key]] + await-exit + kill.
  */
trait LaunchedWorker:
  def key: LivenessKey
  def awaitExit: IO[Int]
  def kill: IO[Unit]

/** The host-process runtime (Slice 4.2 path) — wraps the existing [[io.forge.daemon.WorkerSpawner]] +
  * [[io.forge.app.command.WorkerLauncher]]; the launched worker's key is its child pid.
  */
final class HostProcessRuntime(spawner: WorkerSpawner, launcher: WorkerLauncher) extends WorkerRuntime:
  def launch(instance: Instance, workerId: String, repo: String, feature: FeatureId): Resource[IO, LaunchedWorker] =
    spawner.spawn(launcher.workerSpec(instance, workerId, repo, feature)).map { handle =>
      new LaunchedWorker:
        def key: LivenessKey = LivenessKey.HostPid(handle.pid)
        def awaitExit: IO[Int] = handle.awaitExit
        def kill: IO[Unit] = handle.kill
    }

/** The containerised runtime (Slice 4.3 path, O7) — resolves the worker container's image from the repo's normative
  * `Forgefile` (O1, falling back to [[defaultImage]] when none is committed), builds the [[ContainerSpec]] (the
  * isolated clone mounted in, **no host home mount** — O6), and runs `forge worker` inside it via the abstract
  * [[OciRuntime]] seam. The launched worker's key is its container id.
  *
  * The worker reaches the daemon over **TCP** at `host.docker.internal:<port>` (resolved at launch from the daemon's
  * port file, which it has written by the time any spawn runs) rather than a bind-mounted Unix socket — a far more
  * reliable channel across the Docker Desktop VM boundary on macOS. Credentials are **not** in the spec (a `docker
  * inspect`-safe posture, O6): the in-container worker brokers them over that TCP connection (`broker-credentials`,
  * Task 4.3.3) and applies them to its own process env.
  */
final class ContainerRuntime(oci: OciRuntime, defaultImage: String, home: os.Path = os.home) extends WorkerRuntime:

  def launch(instance: Instance, workerId: String, repo: String, feature: FeatureId): Resource[IO, LaunchedWorker] =
    for
      image <- Resource.eval(resolveImage(instance, workerId))
      port <- Resource.eval(DaemonAddress.readPort(instance.portFile))
      daemon = DaemonAddress.dockerHost(port)
      handle <- oci.run(ContainerRuntime.buildSpec(instance, workerId, repo, feature, image, daemon))
    yield new LaunchedWorker:
      def key: LivenessKey = LivenessKey.ContainerId(handle.containerId)
      def awaitExit: IO[Int] = handle.awaitExit
      def kill: IO[Unit] = handle.kill

  /** The image the worker's container runs: the clone's committed `Forgefile.image` (O1, the normative source of
    * truth), or [[defaultImage]] when no `Forgefile` is committed. The clone is at `instance.workerCheckout(workerId)`;
    * a malformed `Forgefile` fails loudly via [[FileForgefileStore]] (a typo'd pin must not degrade to the default
    * image).
    */
  private[command] def resolveImage(instance: Instance, workerId: String): IO[String] =
    val cloneePaths = new ForgePaths(instance.workerCheckout(workerId), home)
    new FileForgefileStore(cloneePaths).load().map(_.map(_.image).getOrElse(defaultImage))

object ContainerRuntime:

  /** The in-container path the worker root (the isolated clone + the local-runtime family) is bind-mounted to. The
    * worker derives its checkout as `<this>/checkout`; it is passed `--worker-root <this>` so it never re-derives paths
    * from the host `~/.forge` (which does not exist in the container).
    */
  val ContainerWorkerRoot: String = "/forge/worker"

  /** The worker entrypoint inside the container — the `forge` launcher the container image provides (the Forgefile
    * image is a forge-capable base bundling `forge` + the pinned `gh`/`claude`/`codex` tooling, O1).
    */
  val WorkerEntrypoint: String = "forge"

  /** The default worker image when no `Forgefile` is committed (the supervisor's fallback, design-4.3 §4 carry-forward
    * — devcontainer/`Dockerfile` discovery + build is deferred).
    */
  val DefaultImage: String = "forge-worker:latest"

  /** Build the [[ContainerSpec]] for a containerised worker (Task 4.3.4) — pure, so the always-on test can assert the
    * single clone mount, the **absence of any host-home mount or daemon-socket mount** (O6 / the TCP migration), the
    * `--add-host` that lets the worker reach the daemon, and the `forge worker` argv without Docker. **One** bind
    * mount: the worker root (RW; `checkout = <root>/checkout`, the local-runtime family alongside). The daemon is
    * reached over TCP at `daemon` (`host.docker.internal:<port>`), passed as the `--socket` value, with
    * `host.docker.internal:host-gateway` added so the name resolves. No `env` (secrets never enter the spec, O6 — the
    * worker brokers them over the TCP connection). `removeOnExit = false` so a restarted daemon can still inspect/await
    * the exited container.
    */
  def buildSpec(
      instance: Instance,
      workerId: String,
      repo: String,
      feature: FeatureId,
      image: String,
      daemon: DaemonAddress
  ): ContainerSpec =
    ContainerSpec(
      image = image,
      command = Seq(
        WorkerEntrypoint,
        "worker",
        "--instance",
        instance.name.value,
        "--worker-id",
        workerId,
        "--repo",
        repo,
        "--feature",
        feature.value,
        "--worker-root",
        ContainerWorkerRoot,
        "--socket",
        daemon.render,
        "--container"
      ),
      workdir = Some(s"$ContainerWorkerRoot/checkout"),
      mounts = Seq(
        Mount(instance.workerRoot(workerId), ContainerWorkerRoot)
      ),
      extraHosts = Map(DaemonAddress.DockerHost -> "host-gateway"),
      name = Some(s"forge-${instance.name.value}-$workerId"),
      removeOnExit = false
    )
