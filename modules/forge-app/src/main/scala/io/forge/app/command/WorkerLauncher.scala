package io.forge.app.command

import io.forge.core.FeatureId
import io.forge.daemon.WorkerSpec
import io.forge.instance.Instance

/** Task 4.2.5 — resolve the [[WorkerSpec]] that launches a `forge worker` child process (the forge-specific half of the
  * B4 boundary the 4.2.1 `WorkerSpawner` comment deferred here).
  *
  * The supervisor ([[RealSupervisor]]) builds a spec via this seam, then hands it to the generic
  * [[io.forge.daemon.WorkerSpawner]] to spawn. Splitting it out keeps the launcher *resolution* (which `java`, which
  * classpath, which main class) injectable so a supervisor unit test can substitute a trivial child (a `sh` sleeper)
  * for the real forge JVM.
  */
trait WorkerLauncher:

  /** The launch spec for worker `workerId` driving `feature` on registered `repo`, under `instance`. The child's
    * stdout+stderr are captured to a per-worker log file under the worker root (so the worker survives the daemon
    * cleanly — see [[WorkerSpec.logFile]]); cwd is the worker root.
    */
  def workerSpec(instance: Instance, workerId: String, repo: String, feature: FeatureId): WorkerSpec

object WorkerLauncher:

  /** The fully-qualified main class the child JVM runs (`forge worker …`). */
  val MainClass: String = "io.forge.app.Main"

  /** Resolve every entry of a `File.pathSeparator`-joined `classpath` to an absolute path against `base` (the daemon's
    * cwd), preserving order and dropping empties. A no-op for already-absolute entries; the load-bearing case is a
    * relative entry from `java -jar <relative-path>/forge.jar`, which the worker child — running under a different cwd
    * (`workerRoot`) — could not otherwise resolve. See [[Real]].
    */
  private[command] def absoluteClasspath(classpath: String, base: os.Path): String =
    classpath
      .split(java.io.File.pathSeparatorChar)
      .iterator
      .filter(_.nonEmpty)
      .map(entry => os.Path(entry, base).toString)
      .mkString(java.io.File.pathSeparator)

  /** Re-invokes *this* JVM as `java -cp <classpath> io.forge.app.Main worker --instance … --worker-id … --repo …
    * --feature …`. Resolving the live `java.home` + `java.class.path` works in both the sbt run (classpath = all
    * module/dep jars) and the assembled fat-jar (classpath = the single `forge.jar`) without spelling either — the same
    * way the host's `forge` launcher resolves. The child inherits the daemon's environment (host `PATH` / `gh` /
    * `claude` credentials, as a 4.2 host-process worker must), so no extra `env` is layered.
    *
    * `java.class.path` entries are resolved to **absolute** paths against the daemon's working directory before they go
    * to the child: the child runs with `cwd = workerRoot` (under the instance dir), so a *relative* classpath entry —
    * which `java -jar <relative-path>/forge.jar` yields verbatim — would be unresolvable from the child's cwd
    * (`ClassNotFoundException: io.forge.app.Main`). Absolutizing here is cwd-independent and a no-op for an
    * already-absolute entry (the sbt run's). Surfaced live by Task 4.2.6's dogfood #7.
    */
  val Real: WorkerLauncher = new WorkerLauncher:
    def workerSpec(instance: Instance, workerId: String, repo: String, feature: FeatureId): WorkerSpec =
      val javaBin = os.Path(sys.props("java.home")) / "bin" / "java"
      val classpath = absoluteClasspath(sys.props("java.class.path"), os.pwd)
      val workerRoot = instance.workerRoot(workerId)
      WorkerSpec(
        command = Seq(
          javaBin.toString,
          "-cp",
          classpath,
          MainClass,
          "worker",
          "--instance",
          instance.name.value,
          "--worker-id",
          workerId,
          "--repo",
          repo,
          "--feature",
          feature.value
        ),
        cwd = workerRoot,
        logFile = Some(workerRoot / "worker.log")
      )
