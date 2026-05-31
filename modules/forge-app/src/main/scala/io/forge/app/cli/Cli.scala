package io.forge.app.cli

import io.forge.core.{FeatureId, PieceId}
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

/** Argv parse failures. All map to `EX_USAGE` (exit 64) at the `Main` boundary. */
sealed trait CliError extends Product with Serializable:
  def message: String

object CliError:
  case object NoCommand extends CliError:
    def message: String =
      "no command given; expected one of: " +
        "new, spec, run, status, resume, reconcile, refresh-cache, abandon, rebuild-state, unlock, tail"

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

object CliParser:

  // --- phase 1 ---------------------------------------------------------------

  def phase1(args: List[String]): Either[CliError, Invocation] =
    extractRepoRoot(args).flatMap { (repoRoot, remaining) =>
      remaining match
        case Nil => Left(CliError.NoCommand)
        case name :: rest =>
          commandClassOf(name).map { (cls, needsConnector) =>
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

  private def commandClassOf(name: String): Either[CliError, (CommandClass, Boolean)] =
    name match
      case "new" | "spec" | "run" | "resume" | "reconcile" => Right((CommandClass.StateChanging, true))
      case "refresh-cache" | "abandon" => Right((CommandClass.StateChanging, false))
      case "status" | "tail" | "rebuild-state" => Right((CommandClass.ReadOnly, false))
      case "unlock" => Right((CommandClass.UnlockForce, false))
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
      case "resume" => parseResume(rest)
      case "status" => Right(ForgeCommand.ReadOnly(ReadOnlyKind.Status))
      case "tail" => Right(ForgeCommand.ReadOnly(ReadOnlyKind.Tail))
      case "rebuild-state" => Right(ForgeCommand.ReadOnly(ReadOnlyKind.RebuildState))
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
