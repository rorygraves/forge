package io.forge.daemon

import cats.effect.{IO, Resource}

/** Phase-4 §7 / O7 (containerised execution) — the abstract **OCI runtime seam** the daemon uses to run a worker inside
  * an isolated container (Slice 4.3, Task 4.3.1).
  *
  * This is the container analogue of Slice 4.2's [[WorkerSpawner]]: where that seam starts a worker as a host OS
  * process and hands back a [[WorkerHandle]] (pid + await-exit + kill), this seam starts a worker inside a container
  * and hands back a [[ContainerHandle]] (container id + await-exit + kill). The handle surface is deliberately
  * identical, so the forge-app `ContainerSpawner` (Task 4.3.4) adapts an `OciRuntime` onto the supervisor's spawn path
  * **without changing the supervisor** — exactly the substitutability the 4.2.1 `WorkerSpawner` comment promised ("a
  * `ContainerSpawner` slots in behind this same seam").
  *
  * The seam is **abstract** (O7): [[DockerRuntime]] is the Docker-first implementation (the host is macOS), but a
  * Podman/colima backend slots in behind the same trait. The daemon never spells `docker` outside [[DockerRuntime]].
  *
  * It is deliberately *only* the runtime mechanism — given a fully-resolved [[ContainerSpec]] it starts the container,
  * surfaces its id, lets the caller await its exit code, and kills it. It does **not** know what a worker *is*: the
  * forge-specific spec (the image from the repo's `Forgefile` (4.3.2), the clone + control-socket mounts, the brokered
  * credentials (4.3.3)) is built by the supervisor in `forge-app` (4.3.4), the same split [[WorkerSpawner]] uses.
  */
trait OciRuntime:

  /** Run `spec` as a container, scoped to a `Resource`: the container is started on acquire and **force-removed on
    * release** (`docker rm -f` — defence-in-depth so a runtime that loses interest, or a cancelled fiber, does not leak
    * a container; idempotent on an already-gone container). The acquired [[ContainerHandle]] exposes the live
    * container.
    *
    * A worker meant to outlive the spawning scope (the normal case — the daemon supervises it across its own restarts,
    * §6.4) is run in a longer-lived scope, exactly as a host worker is launched via `Resource.allocated` and orphaned:
    * the container is owned by the OCI daemon (the Docker daemon), not the forge daemon, so it survives the forge
    * daemon's crash and a restarted daemon reattaches by container id. For the 4.3.1 spike the `Resource` bracket is
    * the unit of test cleanup.
    */
  def run(spec: ContainerSpec): Resource[IO, ContainerHandle]

/** A fully-resolved container launch spec (Task 4.3.1) — the container analogue of [[WorkerSpec]]. Constructed by the
  * supervisor (`forge-app`, 4.3.4); this module never spells the worker image, the clone mount, or the credential env.
  *
  *   - `image` — the OCI image reference (resolved from the repo's normative `Forgefile`, O1 / 4.3.2).
  *   - `command` — the argv run inside the container (head = executable). Empty ⇒ the image's default ENTRYPOINT/CMD.
  *   - `workdir` — the in-container working directory (`docker -w`); `None` ⇒ the image default.
  *   - `env` — environment entries set inside the container (`docker -e`). The credential broker (4.3.3) injects
  *     short-lived secrets here / over the control channel; a **host home mount is never used** (O6).
  *   - `mounts` — host→container bind mounts (the isolated clone, the daemon control socket — 4.3.4). Order-preserving.
  *   - `network` — the container network (`docker --network`); `None` ⇒ the Docker default bridge. `Some("none")`
  *     isolates it fully; a named network or `host` is set by the supervisor when the worker must reach the daemon
  *     socket by TCP rather than a mounted Unix socket.
  *   - `name` — an optional stable container name (`docker --name`) so the supervisor can reattach by name across a
  *     restart in addition to the id.
  *   - `removeOnExit` — when true, pass `--rm` so the OCI daemon removes the container as soon as it exits. Defaults
  *     **false**: a supervised worker is *not* `--rm` (a restarted daemon must still be able to `docker wait` / inspect
  *     the exited container to learn its exit code; `--rm` also races `docker wait`), and the `Resource` finalizer's
  *     `docker rm -f` is the cleanup. The spike's lifecycle test uses the default.
  */
final case class ContainerSpec(
    image: String,
    command: Seq[String] = Seq.empty,
    workdir: Option[String] = None,
    env: Map[String, String] = Map.empty,
    mounts: Seq[Mount] = Seq.empty,
    network: Option[String] = None,
    name: Option[String] = None,
    removeOnExit: Boolean = false
)

/** A host→container bind mount (Task 4.3.1). `source` is an absolute host path; `target` the in-container path;
  * `readOnly` appends the `:ro` mode. Translates to `docker -v <source>:<target>[:ro]`.
  */
final case class Mount(source: os.Path, target: String, readOnly: Boolean = false)

/** A handle to a running container (Task 4.3.1) — the daemon-side view, the container analogue of [[WorkerHandle]].
  * `containerId` is the OCI container id (recorded in the instance log as `worker.spawned` so a restarted daemon can
  * reattach — 4.3.4); [[awaitExit]] semantically-blocks until the container stops and yields its exit code; [[kill]]
  * requests termination (idempotent — killing an already-stopped container is a no-op).
  */
trait ContainerHandle:
  /** The OCI container id of the running container (the stable reattach key across a daemon restart, vs a host pid). */
  def containerId: String

  /** Semantically block until the container stops, yielding its exit code (backed by `docker wait`). Runs on the
    * blocking pool. Unlike the host [[WorkerHandle.awaitExit]] (`Process.onExit()`, fully cancellable), a cancelled
    * await may leave a `docker wait` subprocess running until the container stops — harmless (it touches nothing), and
    * a cancellation-clean reattach via `docker events`/polling is a 4.3.4 refinement, not a spike concern.
    */
  def awaitExit: IO[Int]

  /** Request the container stop (`docker kill`), then it is reaped by the OCI daemon. Idempotent: a no-op (errors
    * swallowed) if the container is already stopped or gone.
    */
  def kill: IO[Unit]

/** The Docker-first OCI runtime (Task 4.3.1) — backs the daemon's worker containers by shelling the `docker` CLI via
  * the one-shot `os.proc(...).call(...)` idiom (the same shell-out shape `forge-git`'s `RealGitClient` / `RealGhClient`
  * use). Detached (`docker run -d`) so the container is owned by the Docker daemon and survives the forge daemon (the
  * §6.4 premise); the [[ContainerHandle]] reattaches via `docker wait` / `docker kill` by id.
  */
object DockerRuntime extends OciRuntime:

  /** The `docker` executable name (resolved on `PATH`). */
  val Docker: String = "docker"

  def run(spec: ContainerSpec): Resource[IO, ContainerHandle] =
    Resource
      .make(start(spec))(id =>
        // Force-remove on release (kill + rm), idempotent — a supervisor that loses interest, or a cancelled fiber,
        // must not leak a container. Errors swallowed: the container may already be gone (it exited + was reaped, or a
        // prior rm won the race).
        runQuiet(rmArgs(id))
      )
      .map(handle)

  /** Start the container (blocking — process + container creation touch the OS / the Docker daemon) and return its id.
    * `docker run -d` prints the full container id on stdout; we trim it. A non-zero `docker run` (bad image, daemon
    * down) raises with the captured stderr so the caller's `Resource` acquire fails cleanly.
    */
  private def start(spec: ContainerSpec): IO[String] =
    IO.blocking {
      val res = os.proc(runArgs(spec)).call(check = false, stderr = os.Pipe)
      if res.exitCode != 0 then
        throw new RuntimeException(s"docker run failed (exit ${res.exitCode}): ${res.err.text().trim}")
      val id = res.out.text().trim
      if id.isEmpty then throw new RuntimeException("docker run returned no container id")
      id
    }

  private def handle(id: String): ContainerHandle = new ContainerHandle:
    def containerId: String = id

    def awaitExit: IO[Int] =
      IO.blocking {
        // `docker wait` blocks until the container stops and prints its exit code (one integer per line). Take the last
        // non-empty line and parse it; a non-numeric / empty result (the container was removed out from under us) raises
        // so the watcher surfaces it rather than silently reporting a bogus 0.
        val res = os.proc(waitArgs(id)).call(check = false, stderr = os.Pipe)
        val code = res.out.lines().reverseIterator.map(_.trim).find(_.nonEmpty).flatMap(_.toIntOption)
        code.getOrElse(
          throw new RuntimeException(
            s"docker wait '$id' did not yield an exit code (exit ${res.exitCode}): ${res.err.text().trim}"
          )
        )
      }

    def kill: IO[Unit] = runQuiet(killArgs(id))

  /** Run a `docker` argv for effect, swallowing failure (the idempotent kill/rm finalizers): a `docker kill`/`rm` of an
    * already-stopped/gone container exits non-zero, which is the no-op we want.
    */
  private def runQuiet(argv: Vector[String]): IO[Unit] =
    IO.blocking { val _ = os.proc(argv).call(check = false, stderr = os.Pipe, stdout = os.Pipe) }.void

  // --- pure argv builders (unit-tested without Docker) ---

  /** The `docker run -d …` argv for `spec`. Flag order: detached, then `--rm`/`--name`/`--network` (run options), then
    * `-w`/`-e`/`-v` (container config, env keys **sorted** for a deterministic argv), then the image, then the in-
    * container command. Env keys are sorted so the argv is stable regardless of `Map` iteration order (and so the unit
    * test is not order-flaky).
    */
  private[daemon] def runArgs(spec: ContainerSpec): Vector[String] =
    val base = Vector(Docker, "run", "-d")
    val rm = if spec.removeOnExit then Vector("--rm") else Vector.empty
    val name = spec.name.toVector.flatMap(n => Vector("--name", n))
    val network = spec.network.toVector.flatMap(n => Vector("--network", n))
    val workdir = spec.workdir.toVector.flatMap(w => Vector("-w", w))
    val env = spec.env.toVector.sortBy(_._1).flatMap { case (k, v) => Vector("-e", s"$k=$v") }
    val mounts = spec.mounts.toVector.flatMap(m => Vector("-v", mountArg(m)))
    val command = spec.command.toVector
    base ++ rm ++ name ++ network ++ workdir ++ env ++ mounts ++ Vector(spec.image) ++ command

  /** The `-v` value for a mount: `<host-source>:<container-target>[:ro]`. */
  private[daemon] def mountArg(m: Mount): String =
    val ro = if m.readOnly then ":ro" else ""
    s"${m.source}:${m.target}$ro"

  private[daemon] def waitArgs(id: String): Vector[String] = Vector(Docker, "wait", id)
  private[daemon] def killArgs(id: String): Vector[String] = Vector(Docker, "kill", id)

  /** Force-remove (kill if running, then remove) — the `Resource` finalizer cleanup. */
  private[daemon] def rmArgs(id: String): Vector[String] = Vector(Docker, "rm", "-f", id)
