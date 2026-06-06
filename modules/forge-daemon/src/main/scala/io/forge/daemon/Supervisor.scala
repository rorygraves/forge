package io.forge.daemon

import cats.effect.IO
import io.forge.core.{FeatureId, WorkstreamId}

/** Phase-4 §6.2 — the seam the daemon's RPC handler uses to drive **worker-process supervision** (Slice 4.2, Task
  * 4.2.5).
  *
  * forge-daemon owns the durable state + the socket transport + the generic [[WorkerSpawner]] mechanism, but it does
  * **not** know how to turn a workstream request into a running worker: provisioning an isolated clone ([[io.forge]]
  * `WorkerProvisioner`), resolving the `forge worker` launcher, and re-rooting the worker's [[io.forge.instance]] paths
  * all live in `forge-app`, where the supervisor lifecycle composes (the same split [[DaemonSocketServer]] /
  * [[DaemonState]] already use). This trait is the narrow interface across that boundary: the RPC handler calls it,
  * `forge-app`'s `RealSupervisor` implements it. Defined here (not in `forge-app`) so the handler can depend on it;
  * kept to forge-core types only ([[WorkstreamId]] / [[FeatureId]]) so no forge-app type leaks down.
  *
  * The *durable* effects of a spawn (record `worker.spawned`, flip the workstream `Planning → Active`, record
  * `worker.exited` on exit) go through [[DaemonState]] inside the implementation, so a restarted daemon rebuilds the
  * fleet from the instance log alone (§6.4) — the supervisor seam itself carries no state the log doesn't.
  */
trait Supervisor:

  /** Spawn a worker into `workstreamId` driving `feature` on the registered source `repo`: provision its isolated clone
    * (O10), launch the `forge worker` child process (B4), record `worker.spawned`, activate the workstream, and watch
    * for the child's exit (recording `worker.exited`). Returns the daemon-allocated worker id on success, or a
    * human-readable reason the handler maps to a JSON-RPC error (an unknown/closed workstream, a clone/spawn failure).
    * Idempotent against a workstream that already has a live worker for `feature` — a retried request does not
    * double-spawn (§6.4).
    */
  def spawnWorker(workstreamId: WorkstreamId, repo: String, feature: FeatureId): IO[Either[String, String]]

object Supervisor:

  /** A supervisor that refuses to spawn — the 4.1 control-only daemon (and the default for `serveUntilShutdown` callers
    * that wire no supervisor, e.g. the in-process test daemons). Every `spawnWorker` answers with the reason so the
    * handler returns a clean error rather than a `MethodNotFound`.
    */
  val noop: Supervisor = new Supervisor:
    def spawnWorker(workstreamId: WorkstreamId, repo: String, feature: FeatureId): IO[Either[String, String]] =
      IO.pure(Left("this daemon has no worker supervisor (worker spawning is unavailable)"))
