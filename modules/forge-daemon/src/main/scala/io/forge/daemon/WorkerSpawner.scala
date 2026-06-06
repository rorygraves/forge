package io.forge.daemon

import cats.effect.{IO, Resource}

import scala.jdk.CollectionConverters.*

/** Phase-4 §6 / §7 (process topology B4) — the generic **OS-process spawn mechanism** the daemon uses to launch a real
  * worker child (Slice 4.2, Task 4.2.1).
  *
  * This is deliberately *only* the spawn mechanism — given a fully-resolved [[WorkerSpec]] it starts the process,
  * surfaces its pid, lets the caller await its exit, and kills it. It does **not** know what a worker *is*: the
  * forge-specific [[WorkerSpec]] (resolving the `forge worker` / `java -jar` launcher, the target instance/clone, the
  * worker id) is built by the supervisor in `forge-app` (Task 4.2.5), exactly as [[DaemonSocketServer]] owns the socket
  * transport while the lifecycle composes in `forge-app`.
  *
  * **4.2 runs the worker as a host process** (an isolated clone, ambient host credentials). The container topology
  * (4.3) slots a `ContainerSpawner` in behind this same seam without changing the supervisor — the [[WorkerHandle]]
  * (pid + await-exit + kill) is exactly the surface a container runtime also exposes.
  *
  * Mirrors the `forge-agents` `Subprocess` idiom (`java.lang.ProcessBuilder` + `Process.onExit()` for a non-blocking,
  * cancellable wait; force-kill on `Resource` release as defence-in-depth).
  */
trait WorkerSpawner:

  /** Spawn `spec` as a child process, scoped to a `Resource`: the process is started on acquire and **force-killed on
    * release** (a supervisor that loses interest, or a cancelled fiber, must not leak a child; idempotent on an
    * already-exited process). The acquired [[WorkerHandle]] exposes the live process. A worker meant to outlive the
    * spawning scope (the normal case — the daemon supervises it across its own restarts) is spawned in a longer-lived
    * scope; for the 4.2.1 spike the `Resource` bracket is the unit of test cleanup.
    */
  def spawn(spec: WorkerSpec): Resource[IO, WorkerHandle]

/** A fully-resolved child-process launch spec (Task 4.2.1). `command` is the argv (head = executable); `cwd` the
  * working directory; `env` extra environment entries layered over the inherited environment. Constructed by the
  * supervisor (`forge-app`, 4.2.5) — this module never spells the `forge` launcher.
  */
final case class WorkerSpec(
    command: Seq[String],
    cwd: os.Path,
    env: Map[String, String] = Map.empty
)

/** A handle to a spawned worker process (Task 4.2.1) — the daemon-side view of a live child. `pid` is the OS process id
  * (recorded in the instance log as `worker.spawned` so a restarted daemon can probe liveness — 4.2.5); [[awaitExit]]
  * semantically-blocks until the process terminates and yields its exit code; [[kill]] requests termination (idempotent
  * — killing an already-dead process is a no-op).
  */
trait WorkerHandle:
  /** The OS process id of the spawned child. */
  def pid: Long

  /** Semantically block until the child exits, yielding its exit code. Backed by `Process.onExit()`, so it is async +
    * cancellable — a cancelled await does not hold a thread and does not affect the child (a separate process keeps
    * running; killing it is [[kill]]'s job, run by the `Resource` finalizer). This is exactly the §6.4 contract: the
    * worker survives the daemon losing interest.
    */
  def awaitExit: IO[Int]

  /** Request the child terminate (forcibly), then wait until it is fully reaped. Idempotent: a no-op if already exited.
    */
  def kill: IO[Unit]

/** The real host-process spawner (Task 4.2.1) — backs the daemon's worker fleet with `java.lang.ProcessBuilder`. */
object RealWorkerSpawner extends WorkerSpawner:

  def spawn(spec: WorkerSpec): Resource[IO, WorkerHandle] =
    Resource
      .make(start(spec))(p =>
        // Force-kill if still alive, then wait until it is fully reaped, so release guarantees the child is gone (the
        // supervisor — and the WorkerSpawnerSuite finalizer test — relies on no live child outliving the scope).
        IO.blocking(if p.isAlive then { val _ = p.destroyForcibly() }) >>
          IO.fromCompletableFuture(IO.delay(p.onExit())).void
      )
      .map(handle)

  /** Start the child (blocking — process creation touches the OS). The child inherits the JVM's environment (so a
    * spawned `forge worker` sees the host's `PATH` / `gh` / `claude` credentials, as a 4.2 host-process worker must),
    * `spec.env` layered on top; stdin/stdout/stderr inherit the daemon's for the spike (per-worker log redirection is a
    * 4.2.5 observability concern).
    */
  private def start(spec: WorkerSpec): IO[java.lang.Process] =
    IO.blocking {
      val pb = new ProcessBuilder(spec.command.asJava)
      val _ = pb.directory(spec.cwd.toIO)
      spec.env.foreach { case (k, v) => val _ = pb.environment().put(k, v) }
      val _ = pb.inheritIO()
      pb.start()
    }

  private def handle(process: java.lang.Process): WorkerHandle = new WorkerHandle:
    def pid: Long = process.pid()

    def awaitExit: IO[Int] =
      IO.fromCompletableFuture(IO.delay(process.onExit())).map(_.exitValue)

    def kill: IO[Unit] =
      IO.blocking { val _ = process.destroyForcibly() } >> awaitExit.void
