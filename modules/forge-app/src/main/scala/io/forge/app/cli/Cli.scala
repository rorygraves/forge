package io.forge.app.cli

import io.forge.core.{FeatureId, InstanceName, PieceId, WorkstreamId}
import io.forge.git.branch.ForgeCommand
import io.forge.git.branch.ForgeCommand.ReadOnlyKind

/** Task 1.4.9 I2/I3 — the two-phase argv parser feeding [[io.forge.app.Main]].
  *
  * **Phase 1** ([[CliParser.phase1]]) extracts the global `--repo-root <path>` flag and the command *name*, and from
  * the name derives the [[CommandClass]] (which resources `Main` must set up) — *without* touching per-feature args.
  * This is the boot-sequence's "know the command class before any resource setup" requirement (I2 step 1): a malformed
  * `--repo-root`, an unknown command, or a missing command surfaces here, before config load / lock acquisition.
  *
  * **Phase 2** ([[CliParser.phase2]]) finishes the parse into a concrete [[ForgeCommand]] once `Main` knows the command
  * class. `Main` runs it *after* config load but *before* lock acquisition so (a) a usage error never grabs the process
  * lock and (b) the parsed feature id is available for the lock holder metadata.
  */

/** Resource class a command belongs to, per v1.2 §15. Drives which boot steps `Main` runs. */
enum CommandClass:
  /** `new | spec | run | resume | reconcile | refresh-cache | abandon` — acquire the process lock (§15). */
  case StateChanging

  /** `status | tail | rebuild-state` — no lock (§15 "Read-only / —"); never block an active `forge run`. */
  case ReadOnly

  /** `unlock --force` — the §13 recovery path; loads no config, constructs no connector, acquires no lock. */
  case UnlockForce

  /** `init-instance | add-repo | list-repos` — Phase-4 §4 instance-registry commands (Task 4.0.3). Instance-scoped, not
    * per-checkout: they load **no** [[io.forge.app.config.ForgeConfig]], construct no connector, and never take the
    * per-checkout process lock — the mutating ones (`init-instance` / `add-repo`) serialize on an instance-level lock
    * under `~/.forge/instances/<name>/` instead. Parsed via [[CliParser.parseInstance]] into an [[InstanceCommand]].
    */
  case Instance

  /** `daemon start | daemon stop | daemon status` — Phase-4 §6 daemon supervisor commands (Task 4.1.3). Instance-scoped
    * like [[Instance]] (no [[io.forge.app.config.ForgeConfig]], no connector, no per-checkout lock). `start` runs the
    * foreground supervisor holding the **instance** lock; `stop` / `status` are JSON-RPC client calls to a running
    * daemon. Parsed via [[CliParser.parseDaemon]] into a [[DaemonCommand]].
    */
  case Daemon

  /** `worker` — Phase-4 §5/§6 the **daemon-spawned worker process** (Slice 4.2, Task 4.2.1). *Hidden*: not part of the
    * operator-facing §15 command set — the daemon supervisor spawns it as a child (4.2.5), passing the instance, worker
    * id, repo, and feature on argv. Instance-scoped like [[Daemon]] (no [[io.forge.app.config.ForgeConfig]] at the
    * routing layer, no per-checkout lock here — the worker takes its *own* re-rooted per-worker lock when it runs the
    * real feature loop, 4.2.3). For the 4.2.1 spike its body phones home to the instance socket (register/status/event)
    * and exits; the real `Orchestrator` body lands in 4.2.3. Parsed via [[CliParser.parseWorker]] into a
    * [[WorkerCommand]].
    */
  case Worker

  /** `workstream new | workstream list | worker list` — Phase-4 §5 operator-facing workstream/worker **client**
    * commands (Slice 4.2, Task 4.2.4). Instance-scoped like [[Daemon]] (no [[io.forge.app.config.ForgeConfig]], no
    * connector, no per-checkout lock); each is a JSON-RPC client round-trip to a running daemon (`create-workstream` /
    * `status`), since the daemon is the sole writer of the instance state (§6.3.1). `worker list` rides here (rather
    * than under the hidden flag-driven [[Worker]] process command) because it is a sibling *read* of the same daemon
    * instance state. Parsed via [[CliParser.parseWorkstream]] into a [[WorkstreamCommand]].
    */
  case Workstream

/** Phase-1 parse result: enough to route resource setup, with per-command args deferred to [[CliParser.phase2]]. */
final case class Invocation(
    repoRoot: Option[String],
    name: String,
    commandClass: CommandClass,
    /** Whether this command may invoke a driver/reviewer connector (I2 steps 5–6 set). Connector + asset wiring lands
      * in Task 1.4.10; phase 1 already classifies it so that wiring drops in without re-deriving the set.
      */
    needsConnector: Boolean,
    rest: Vector[String]
)

/** Parsed Phase-4 instance-registry command (Task 4.0.3) — the [[CommandClass.Instance]] analogue of [[ForgeCommand]],
  * kept in the CLI layer rather than `forge-git` because instance commands never touch branch/PR preflight.
  *
  * `add-repo` / `list-repos` carry an **optional** target instance: `Some(name)` from an explicit `--instance <name>`
  * flag, or `None` to let the handler resolve the default (the sole instance when exactly one exists). `init-instance`
  * always names its instance positionally. `repoPath` stays a raw string here — the handler resolves it against
  * `os.pwd` into an absolute `os.Path` (a parse-layer `os.Path` would bind to the wrong cwd).
  */
sealed trait InstanceCommand extends Product with Serializable
object InstanceCommand:
  final case class InitInstance(name: InstanceName) extends InstanceCommand
  final case class AddRepo(instance: Option[InstanceName], repoPath: String) extends InstanceCommand
  final case class ListRepos(instance: Option[InstanceName]) extends InstanceCommand

/** Parsed Phase-4 daemon supervisor command (Task 4.1.3) — the [[CommandClass.Daemon]] analogue of [[InstanceCommand]].
  * Each subcommand carries an **optional** target instance: `Some(name)` from an explicit `--instance <name>`, or
  * `None` to let the handler resolve the default (the sole instance when exactly one exists), exactly as
  * [[InstanceCommand]]'s `add-repo` / `list-repos` do.
  */
sealed trait DaemonCommand extends Product with Serializable:
  def instance: Option[InstanceName]
object DaemonCommand:
  /** `forge daemon start [--container]` — run the foreground supervisor (holds the instance lock for its lifetime).
    * `container` (Slice 4.3, Task 4.3.4) selects the **containerised** worker runtime for new spawns (each `forge
    * worker` runs inside an isolated OCI container, O7); the default `false` keeps the frozen 4.2 host-process runtime.
    * The flag governs only *new* spawns — reconcile reattaches both topologies it finds in the log either way.
    */
  final case class Start(instance: Option[InstanceName], container: Boolean = false) extends DaemonCommand

  /** `forge daemon stop` — ask a running daemon to shut down (a `shutdown` JSON-RPC call). */
  final case class Stop(instance: Option[InstanceName]) extends DaemonCommand

  /** `forge daemon status` — render a running daemon's status snapshot (a `status` JSON-RPC call). */
  final case class Status(instance: Option[InstanceName]) extends DaemonCommand

/** Parsed Phase-4 hidden worker command (Task 4.2.1 / 4.3.4) — the [[CommandClass.Worker]] payload. The target
  * `instance`, the `workerId` it was assigned, the `repo` it drives, and the `feature` are **required** (the daemon
  * always names them explicitly when spawning, never a sole-instance fallback), arriving as `--instance` /
  * `--worker-id` / `--repo` / `--feature` flags on the spawned argv.
  *
  * The optional fields are the Slice-4.3 **container-mode** overrides: a containerised worker (started inside an OCI
  * container by the [[io.forge.app.command.ContainerRuntime]]) cannot derive its paths from the host `~/.forge` (which
  * does not exist in the container) nor read the host port file, so the daemon passes them explicitly — `--worker-root
  * <path>` (the in-container worker root; `checkout = <root>/checkout`) and `--socket <host:port>` (the daemon's TCP
  * address, `host.docker.internal:<port>`) — and the `--container` flag tells the worker to **broker its credentials**
  * over that connection (O6) instead of relying on ambient host credentials. Absent for a 4.2 host-process worker (it
  * derives paths from the instance and reads the daemon's loopback port from the instance port file).
  */
final case class WorkerCommand(
    instance: InstanceName,
    workerId: String,
    repo: String,
    feature: FeatureId,
    workerRoot: Option[String] = None,
    socket: Option[String] = None,
    container: Boolean = false
)

/** Parsed Phase-4 operator workstream/worker client command ([[CommandClass.Workstream]], Task 4.2.4). Each carries an
  * **optional** target instance (`Some` from `--instance <name>`, else the sole-instance fallback resolved by the
  * handler), exactly like [[DaemonCommand]]. These are JSON-RPC client calls to a running daemon, not direct state
  * mutations (the daemon is the instance log's single writer, §6.3.1).
  */
sealed trait WorkstreamCommand extends Product with Serializable:
  def instance: Option[InstanceName]
object WorkstreamCommand:
  /** `forge workstream new --goal <text>` — create a `Planning` workstream (a `create-workstream` RPC). */
  final case class New(instance: Option[InstanceName], goal: String) extends WorkstreamCommand

  /** `forge workstream list` — list the instance's workstreams + their `attention` projection (a `status` RPC). */
  final case class List(instance: Option[InstanceName]) extends WorkstreamCommand

  /** `forge worker list` — list the instance's workers (a `status` RPC). Rides on this command family as a sibling read
    * of the same daemon instance state.
    */
  final case class WorkerList(instance: Option[InstanceName]) extends WorkstreamCommand

  /** `forge workstream spawn <ws-id> --repo <path> --feature <id>` — ask the daemon to spawn a worker into the
    * workstream (a `spawn-worker` RPC, Task 4.2.5). The daemon's supervisor provisions an isolated clone, launches the
    * `forge worker` child, records `worker.spawned`, and flips the workstream `Planning → Active`.
    */
  final case class SpawnWorker(
      instance: Option[InstanceName],
      workstreamId: WorkstreamId,
      repo: String,
      feature: FeatureId
  ) extends WorkstreamCommand

/** Argv parse failures. All map to `EX_USAGE` (exit 64) at the `Main` boundary. */
sealed trait CliError extends Product with Serializable:
  def message: String

object CliError:
  case object NoCommand extends CliError:
    def message: String =
      "no command given; expected one of: " +
        "new, spec, run, status, resume, reconcile, refresh-cache, abandon, profile, rebuild-state, unlock, tail, " +
        "stats, tui, init-instance, add-repo, list-repos, daemon, workstream, worker list"

  final case class UnknownCommand(name: String) extends CliError:
    def message: String = s"unknown command '$name'"

  final case class MissingFlagValue(flag: String) extends CliError:
    def message: String = s"flag '$flag' requires a value"

  final case class MissingFeatureId(command: String) extends CliError:
    def message: String = s"'$command' requires a <feature> argument"

  final case class InvalidFeatureId(raw: String, reason: String) extends CliError:
    def message: String = s"invalid feature id '$raw': $reason"

  final case class InvalidPieceId(raw: String, reason: String) extends CliError:
    def message: String = s"invalid piece id '$raw': $reason"

  final case class BadResumeHint(detail: String) extends CliError:
    def message: String = detail

  case object UnlockRequiresForce extends CliError:
    def message: String = "'unlock' requires --force (the only supported form is `forge unlock --force`)"

  final case class MissingInstanceName(command: String) extends CliError:
    def message: String = s"'$command' requires an <instance-name> argument"

  final case class InvalidInstanceName(raw: String, reason: String) extends CliError:
    def message: String = s"invalid instance name '$raw': $reason"

  final case class MissingRepoPath(command: String) extends CliError:
    def message: String = s"'$command' requires a <path> argument"

  case object MissingDaemonSubcommand extends CliError:
    def message: String = "'daemon' requires a subcommand: start, stop, or status"

  final case class UnknownDaemonSubcommand(name: String) extends CliError:
    def message: String = s"unknown daemon subcommand '$name'; expected one of: start, stop, status"

  final case class MissingWorkerFlag(flag: String) extends CliError:
    def message: String = s"'worker' requires the $flag flag"

  case object MissingWorkstreamSubcommand extends CliError:
    def message: String = "'workstream' requires a subcommand: new, list, or spawn"

  final case class UnknownWorkstreamSubcommand(name: String) extends CliError:
    def message: String = s"unknown workstream subcommand '$name'; expected one of: new, list, spawn"

  case object MissingWorkstreamId extends CliError:
    def message: String = "'workstream spawn' requires a <workstream-id> argument"

  final case class InvalidWorkstreamId(raw: String, reason: String) extends CliError:
    def message: String = s"invalid workstream id '$raw': $reason"

  case object UnknownWorkerSubcommand extends CliError:
    def message: String = "'worker' is daemon-internal; the only operator form is `forge worker list`"

object CliParser:

  // --- phase 1 ---------------------------------------------------------------

  def phase1(args: List[String]): Either[CliError, Invocation] =
    extractRepoRoot(args).flatMap { (repoRoot, remaining) =>
      remaining match
        case Nil => Left(CliError.NoCommand)
        case name :: rest =>
          commandClassOf(name, rest).map { (cls, needsConnector) =>
            Invocation(repoRoot, name, cls, needsConnector, rest.toVector)
          }
    }

  /** Pull the single global `--repo-root <path>` flag out of the arg list wherever it appears, returning the value and
    * the remaining tokens. A trailing `--repo-root` with no value is an error.
    */
  private def extractRepoRoot(args: List[String]): Either[CliError, (Option[String], List[String])] =
    args match
      case Nil => Right((None, Nil))
      case "--repo-root" :: value :: tail if !value.startsWith("--") => Right((Some(value), tail))
      case "--repo-root" :: _ => Left(CliError.MissingFlagValue("--repo-root"))
      case other :: tail => extractRepoRoot(tail).map((rr, rest) => (rr, other :: rest))

  private def commandClassOf(name: String, rest: List[String]): Either[CliError, (CommandClass, Boolean)] =
    name match
      case "new" | "spec" | "run" | "resume" | "reconcile" => Right((CommandClass.StateChanging, true))
      case "profile" => Right((CommandClass.StateChanging, true))
      case "refresh-cache" | "abandon" => Right((CommandClass.StateChanging, false))
      case "status" | "tail" | "rebuild-state" | "stats" | "tui" => Right((CommandClass.ReadOnly, false))
      case "unlock" => Right((CommandClass.UnlockForce, false))
      case "init-instance" | "add-repo" | "list-repos" => Right((CommandClass.Instance, false))
      case "daemon" => Right((CommandClass.Daemon, false))
      case "workstream" => Right((CommandClass.Workstream, false))
      // `worker list` is the operator client read (Task 4.2.4); the bare/flag form is the hidden daemon-spawned process
      // (Task 4.2.1). needsConnector is false for both — the real loop builds its own connector under the worker's
      // re-rooted paths (4.2.3), not via the Main asset-install path.
      case "worker" if rest.find(t => !t.startsWith("--")).contains("list") => Right((CommandClass.Workstream, false))
      case "worker" => Right((CommandClass.Worker, false))
      case other => Left(CliError.UnknownCommand(other))

  // --- phase 2 ---------------------------------------------------------------

  def phase2(name: String, rest: Vector[String]): Either[CliError, ForgeCommand] =
    name match
      case "new" => featureOnly(name, rest).map(ForgeCommand.New(_))
      case "spec" => featureOnly(name, rest).map(ForgeCommand.Spec(_))
      case "run" => featureOnly(name, rest).map(ForgeCommand.Run(_))
      case "reconcile" => featureOnly(name, rest).map(ForgeCommand.Reconcile(_))
      case "refresh-cache" => featureOnly(name, rest).map(ForgeCommand.RefreshCache(_))
      case "abandon" => featureOnly(name, rest).map(ForgeCommand.Abandon(_))
      case "profile" => Right(ForgeCommand.Profile)
      case "resume" => parseResume(rest)
      case "status" => Right(ForgeCommand.ReadOnly(ReadOnlyKind.Status))
      case "tail" => Right(ForgeCommand.ReadOnly(ReadOnlyKind.Tail))
      case "rebuild-state" => Right(ForgeCommand.ReadOnly(ReadOnlyKind.RebuildState))
      case "stats" => Right(ForgeCommand.ReadOnly(ReadOnlyKind.Stats))
      case "tui" => Right(ForgeCommand.ReadOnly(ReadOnlyKind.Tui))
      case "unlock" =>
        if rest.contains("--force") then Right(ForgeCommand.UnlockForce) else Left(CliError.UnlockRequiresForce)
      case other => Left(CliError.UnknownCommand(other))

  /** Read-only feature-arg parse for the §15 read-only handlers (`status` / `tail` / `rebuild-state`). Phase 2
    * collapses every read-only command to `ForgeCommand.ReadOnly(kind)` (the feature is not part of the `forge-git`
    * `ForgeCommand` ADT), so the handler parses its own feature from the `rest` tokens carried on
    * [[io.forge.app.command.ReadOnlyContext]]. A parse failure surfaces at handler time as `EX_USAGE` (exit 64), the
    * same code Main's [[usageError]] uses. `command` names the command for the "requires a <feature>" message.
    */
  def requireFeature(command: String, rest: Vector[String]): Either[CliError, FeatureId] =
    featureOnly(command, rest)

  /** Like [[requireFeature]] but the feature is optional — backs `forge status [<feature>]`. `None` means "no feature
    * argument given" (the overview form); a present-but-invalid id is still a [[CliError]].
    */
  def optionalFeature(rest: Vector[String]): Either[CliError, Option[FeatureId]] =
    rest.find(t => !t.startsWith("--")) match
      case None => Right(None)
      case Some(raw) => FeatureId.fromString(raw).left.map(CliError.InvalidFeatureId(raw, _)).map(Some(_))

  /** The feature id a [[ForgeCommand]] binds to (for lock metadata). Read-only / unlock bind to no feature. */
  def featureOf(command: ForgeCommand): Option[FeatureId] = command match
    case ForgeCommand.New(f) => Some(f)
    case ForgeCommand.Spec(f) => Some(f)
    case ForgeCommand.Run(f) => Some(f)
    case ForgeCommand.Reconcile(f) => Some(f)
    case ForgeCommand.RefreshCache(f) => Some(f)
    case ForgeCommand.Abandon(f) => Some(f)
    case ForgeCommand.Profile => None
    case ForgeCommand.ResumeAfterHumanPush(f, _) => Some(f)
    case ForgeCommand.ResumeCommitHumanFix(f, _) => Some(f)
    case ForgeCommand.ResumeRunFixup(f, _) => Some(f)
    case ForgeCommand.ReadOnly(_) => None
    case ForgeCommand.UnlockForce => None

  private val ResumeFlags: Map[String, (FeatureId, PieceId) => ForgeCommand] = Map(
    "--after-human-push" -> ((f, p) => ForgeCommand.ResumeAfterHumanPush(f, p)),
    "--commit-human-fix" -> ((f, p) => ForgeCommand.ResumeCommitHumanFix(f, p)),
    "--run-fixup" -> ((f, p) => ForgeCommand.ResumeRunFixup(f, p))
  )

  /** `forge resume <feature> --<hint> <piece>` — exactly one hint flag, carrying the piece id as its value. */
  private def parseResume(rest: Vector[String]): Either[CliError, ForgeCommand] =
    ResumeFlags.keys.filter(rest.contains).toVector match
      case Vector(flag) =>
        for
          feature <- featureOnly("resume", rest)
          pieceRaw <- flagValue(rest, flag)
          piece <- PieceId.fromString(pieceRaw).left.map(CliError.InvalidPieceId(pieceRaw, _))
        yield ResumeFlags(flag)(feature, piece)
      case Vector() =>
        Left(
          CliError.BadResumeHint(
            "'resume' requires exactly one of --after-human-push <piece>, --commit-human-fix <piece>, --run-fixup <piece>"
          )
        )
      case many =>
        Left(CliError.BadResumeHint(s"'resume' accepts only one hint flag; got ${many.mkString(", ")}"))

  /** First positional (non-`--`) token as a [[FeatureId]]. Used by the feature-only commands and as `resume`'s leading
    * argument (by convention the feature precedes the hint flag).
    */
  private def featureOnly(command: String, rest: Vector[String]): Either[CliError, FeatureId] =
    rest.find(t => !t.startsWith("--")) match
      case None => Left(CliError.MissingFeatureId(command))
      case Some(raw) => FeatureId.fromString(raw).left.map(CliError.InvalidFeatureId(raw, _))

  private def flagValue(rest: Vector[String], flag: String): Either[CliError, String] =
    val i = rest.indexOf(flag)
    if i >= 0 && i + 1 < rest.length && !rest(i + 1).startsWith("--") then Right(rest(i + 1))
    else Left(CliError.MissingFlagValue(flag))

  // --- instance commands (Task 4.0.3) ---------------------------------------

  /** Parse a [[CommandClass.Instance]] invocation into an [[InstanceCommand]]. The third parse entry point alongside
    * [[phase2]] (which only produces [[ForgeCommand]]s): instance commands are CLI-layer-only, so they get their own
    * result type rather than being shoehorned into the branch-preflight `ForgeCommand` ADT. Usage errors surface as
    * [[CliError]] → `EX_USAGE` (exit 64) at the `Main` boundary, exactly like [[phase2]].
    */
  def parseInstance(name: String, rest: Vector[String]): Either[CliError, InstanceCommand] =
    name match
      case "init-instance" =>
        // The instance name is positional; `--instance` is meaningless here (the command *is* the name).
        firstPositional(rest)
          .toRight(CliError.MissingInstanceName("init-instance"))
          .flatMap(instanceName)
          .map(InstanceCommand.InitInstance(_))

      case "add-repo" =>
        extractInstance(rest).flatMap { (instance, remaining) =>
          firstPositional(remaining)
            .toRight(CliError.MissingRepoPath("add-repo"))
            .map(repo => InstanceCommand.AddRepo(instance, repo))
        }

      case "list-repos" =>
        extractInstance(rest).map((instance, _) => InstanceCommand.ListRepos(instance))

      case other => Left(CliError.UnknownCommand(other))

  /** Parse a [[CommandClass.Daemon]] invocation (`daemon <subcommand> [--instance <name>]`) into a [[DaemonCommand]].
    * The fourth parse entry point alongside [[phase2]] / [[parseInstance]]: like instance commands, daemon commands are
    * CLI-layer-only and instance-scoped. `--instance` is pulled out wherever it appears (so it isn't mistaken for the
    * subcommand positional); the leading positional names the subcommand. Usage errors surface as [[CliError]] →
    * `EX_USAGE` (exit 64) at the `Main` boundary.
    */
  def parseDaemon(rest: Vector[String]): Either[CliError, DaemonCommand] =
    extractInstance(rest).flatMap { (instance, remaining) =>
      firstPositional(remaining) match
        case None => Left(CliError.MissingDaemonSubcommand)
        case Some("start") => Right(DaemonCommand.Start(instance, container = remaining.contains("--container")))
        case Some("stop") => Right(DaemonCommand.Stop(instance))
        case Some("status") => Right(DaemonCommand.Status(instance))
        case Some(other) => Left(CliError.UnknownDaemonSubcommand(other))
    }

  /** Parse a [[CommandClass.Worker]] invocation (`worker --instance <name> --worker-id <id> --repo <path> --feature
    * <id>`) into a [[WorkerCommand]] (Task 4.2.1). The fifth parse entry point — every field is a **required** flag
    * (the daemon always passes them when spawning the child), so a missing flag is a [[CliError.MissingWorkerFlag]] and
    * a present-but-valueless flag a [[CliError.MissingFlagValue]], both → `EX_USAGE` (exit 64) at the `Main` boundary.
    */
  def parseWorker(rest: Vector[String]): Either[CliError, WorkerCommand] =
    for
      instanceRaw <- requiredFlag(rest, "--instance")
      instance <- instanceName(instanceRaw)
      workerId <- requiredFlag(rest, "--worker-id")
      repo <- requiredFlag(rest, "--repo")
      featureRaw <- requiredFlag(rest, "--feature")
      feature <- FeatureId.fromString(featureRaw).left.map(CliError.InvalidFeatureId(featureRaw, _))
    yield WorkerCommand(
      instance,
      workerId,
      repo,
      feature,
      workerRoot = optionalFlag(rest, "--worker-root"),
      socket = optionalFlag(rest, "--socket"),
      container = rest.contains("--container")
    )

  /** Parse a [[CommandClass.Workstream]] invocation into a [[WorkstreamCommand]] (Task 4.2.4). The sixth parse entry
    * point. `name` is `"workstream"` (subcommands `new` / `list`) or `"worker"` (only `list` reaches here — the bare
    * flag form routed to [[CommandClass.Worker]] in phase 1). `--instance` is pulled out wherever it appears; the
    * leading positional names the subcommand. `workstream new` requires a `--goal <text>`. Usage errors surface as
    * [[CliError]] → `EX_USAGE` (exit 64) at the `Main` boundary.
    */
  def parseWorkstream(name: String, rest: Vector[String]): Either[CliError, WorkstreamCommand] =
    extractInstance(rest).flatMap { (instance, remaining) =>
      name match
        case "worker" =>
          firstPositional(remaining) match
            case Some("list") => Right(WorkstreamCommand.WorkerList(instance))
            case _ => Left(CliError.UnknownWorkerSubcommand)
        case _ =>
          firstPositional(remaining) match
            case None => Left(CliError.MissingWorkstreamSubcommand)
            case Some("list") => Right(WorkstreamCommand.List(instance))
            case Some("new") => flagValue(remaining, "--goal").map(WorkstreamCommand.New(instance, _))
            case Some("spawn") => parseSpawn(instance, remaining)
            case Some(other) => Left(CliError.UnknownWorkstreamSubcommand(other))
    }

  /** `workstream spawn <ws-id> --repo <path> --feature <id>` — the workstream id is the positional **immediately
    * after** the `spawn` subcommand (the same "positional precedes the flags" convention `resume` uses); `--repo` /
    * `--feature` are required flags. Reading the id positionally by its position after `spawn` (rather than a global
    * positional scan) keeps a flag *value* like `--repo /r` from being mistaken for the id. The repo path stays a raw
    * string (the [[io.forge.app.command.WorkstreamCommands]] handler resolves it against `os.pwd` before sending, so
    * the daemon receives an absolute source path).
    */
  private def parseSpawn(
      instance: Option[InstanceName],
      remaining: Vector[String]
  ): Either[CliError, WorkstreamCommand] =
    val wsRawOpt = remaining.dropWhile(_ != "spawn").drop(1).headOption.filterNot(_.startsWith("--"))
    for
      wsRaw <- wsRawOpt.toRight(CliError.MissingWorkstreamId)
      ws <- WorkstreamId.fromString(wsRaw).left.map(CliError.InvalidWorkstreamId(wsRaw, _))
      repo <- flagValue(remaining, "--repo")
      featureRaw <- flagValue(remaining, "--feature")
      feature <- FeatureId.fromString(featureRaw).left.map(CliError.InvalidFeatureId(featureRaw, _))
    yield WorkstreamCommand.SpawnWorker(instance, ws, repo, feature)

  /** A required `--flag <value>`: absent ⇒ [[CliError.MissingWorkerFlag]]; present-but-valueless (end of args or
    * another `--flag` next) ⇒ [[CliError.MissingFlagValue]].
    */
  private def requiredFlag(rest: Vector[String], flag: String): Either[CliError, String] =
    rest.indexOf(flag) match
      case -1 => Left(CliError.MissingWorkerFlag(flag))
      case i if i + 1 < rest.length && !rest(i + 1).startsWith("--") => Right(rest(i + 1))
      case _ => Left(CliError.MissingFlagValue(flag))

  /** First positional (non-`--`) token, if any. */
  private def firstPositional(rest: Vector[String]): Option[String] = rest.find(t => !t.startsWith("--"))

  /** The value of an **optional** `--flag <value>` (a non-`--` token immediately after it), or `None` when the flag is
    * absent or has no value. Unlike [[requiredFlag]]/[[flagValue]] a missing flag is not an error.
    */
  private def optionalFlag(rest: Vector[String], flag: String): Option[String] =
    rest.indexOf(flag) match
      case i if i >= 0 && i + 1 < rest.length && !rest(i + 1).startsWith("--") => Some(rest(i + 1))
      case _ => None

  private def instanceName(raw: String): Either[CliError, InstanceName] =
    InstanceName.fromString(raw).left.map(CliError.InvalidInstanceName(raw, _))

  /** Pull an optional `--instance <name>` flag out of `rest`, returning the parsed name (or `None`) and the remaining
    * tokens with the flag + its value removed — so a later positional scan (`add-repo`'s `<path>`, or a feature
    * command's `<feature>`) doesn't mistake the flag's value for the positional. A `--instance` with no value, or an
    * invalid name, is a usage error.
    *
    * Public because Task 4.0.4 reuses it on the **feature** command path: `Main` strips `--instance` from a
    * state-changing / read-only command's `rest` (so phase-2 / handler feature parsing sees a clean positional list)
    * and feeds the parsed name to [[io.forge.app.command.InstancePaths]] to re-root the local-runtime family (B1).
    */
  def extractInstance(rest: Vector[String]): Either[CliError, (Option[InstanceName], Vector[String])] =
    rest.indexOf("--instance") match
      case -1 => Right((None, rest))
      case i if i + 1 < rest.length && !rest(i + 1).startsWith("--") =>
        instanceName(rest(i + 1)).map(n => (Some(n), rest.patch(i, Nil, 2)))
      case _ => Left(CliError.MissingFlagValue("--instance"))
