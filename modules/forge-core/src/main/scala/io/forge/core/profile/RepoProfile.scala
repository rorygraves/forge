package io.forge.core.profile

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import upickle.default.{read, readwriter, write, ReadWriter}

/** Phase-3 (repo adaptation) domain model — the **`RepoProfile`** that lets Forge stop running blind on a repo.
  *
  * SPIKE STATUS (design-3.0 / forge-design-1.7 pending). This file ships the *shape* the Phase-3 contract builds
  * against — exercised against the real dogfood-#2 strings in `RepoProfileSpikeSuite` — before any LLM sensor is wired.
  * Per design-rationale **A5** ("deterministic spine + agentic senses"): the profile is a **versioned input** to the
  * core (like config), produced by an agentic sensor (`RepoProfiler`) but consumed *deterministically* by the FSM. It
  * is committed at `.forge/profile.json` and snapshotted/hashed into the action log per run (§19 `profile.snapshot`,
  * see [[ProfileSnapshot]]) so a replay binds to the profile-as-of-that-run and `Fsm.transition` stays
  * pure-given-inputs.
  *
  * Co-location note: the eventual home is likely RepoProfile + routing in `forge-core` (the spine consumes them) and
  * the LLM `RepoProfiler` / `FailureClassifier` *roles* in `forge-agents` (implementing [[FailureClassifier]] via the
  * native-schema + ReviewDecoders pattern, routed through the §3.2 Role indirection). The spike co-locates everything
  * in this package so the runnable proof needs no cross-module wiring; 1.7 fixes the final placement.
  */

/** Which lifecycle gate a repo command serves. Maps onto the §8 CI gate and the new local format/build gate. */
enum CommandKind:
  case Format
  case Lint
  case Build
  case Test
  case Typecheck

object CommandKind:
  def fromString(s: String): Either[String, CommandKind] = s match
    case "format" => Right(Format)
    case "lint" => Right(Lint)
    case "build" => Right(Build)
    case "test" => Right(Test)
    case "typecheck" => Right(Typecheck)
    case other => Left(s"unknown command kind '$other'")

  extension (k: CommandKind)
    def asString: String = k match
      case Format => "format"
      case Lint => "lint"
      case Build => "build"
      case Test => "test"
      case Typecheck => "typecheck"

  given ReadWriter[CommandKind] =
    readwriter[String].bimap(_.asString, s => fromString(s).fold(m => throw IllegalArgumentException(m), identity))

/** A5 "commands tiered by determinism". `Deterministic` = re-running the tool is a pure function of the working tree (a
  * formatter, a codegen step): its outcome carries no judgment, so the spine can run it itself. `Heuristic` =
  * interpreting the outcome needs judgment (a failing test could be a bug, a flake, or an env issue). The tier is what
  * lets [[FailureRouting]] decide, deterministically, whether a failure can collapse to a local tool run instead of a
  * paid driver fix-up round (the dogfood-#2 $1.78 / 12-min case).
  */
enum Determinism:
  case Deterministic
  case Heuristic

object Determinism:
  def fromString(s: String): Either[String, Determinism] = s match
    case "deterministic" => Right(Deterministic)
    case "heuristic" => Right(Heuristic)
    case other => Left(s"unknown determinism tier '$other'")

  extension (d: Determinism)
    def asString: String = d match
      case Deterministic => "deterministic"
      case Heuristic => "heuristic"

  given ReadWriter[Determinism] =
    readwriter[String].bimap(_.asString, s => fromString(s).fold(m => throw IllegalArgumentException(m), identity))

/** How merged PRs land on the integration branch. */
enum MergeStrategy:
  case Squash
  case Merge
  case Rebase

object MergeStrategy:
  def fromString(s: String): Either[String, MergeStrategy] = s match
    case "squash" => Right(Squash)
    case "merge" => Right(Merge)
    case "rebase" => Right(Rebase)
    case other => Left(s"unknown merge strategy '$other'")

  extension (m: MergeStrategy)
    def asString: String = m match
      case Squash => "squash"
      case Merge => "merge"
      case Rebase => "rebase"

  given ReadWriter[MergeStrategy] =
    readwriter[String].bimap(_.asString, s => fromString(s).fold(m => throw IllegalArgumentException(m), identity))

/** Branch model the repo follows — shapes where Forge opens its PRs. */
enum BranchModel:
  case TrunkBased
  case GitFlow

object BranchModel:
  def fromString(s: String): Either[String, BranchModel] = s match
    case "trunk_based" => Right(TrunkBased)
    case "git_flow" => Right(GitFlow)
    case other => Left(s"unknown branch model '$other'")

  extension (b: BranchModel)
    def asString: String = b match
      case TrunkBased => "trunk_based"
      case GitFlow => "git_flow"

  given ReadWriter[BranchModel] =
    readwriter[String].bimap(_.asString, s => fromString(s).fold(m => throw IllegalArgumentException(m), identity))

/** One gate command the repo exposes, tiered by determinism and tagged required/optional.
  *
  * @param autofix
  *   `true` when running this command *mutates the working tree to remediate the failure* (a formatter rewrites files
  *   in place). This — together with `determinism == Deterministic` — is the property that makes a failure routable to
  *   [[FixupRoute.RunLocalCommand]]: the spine can run it and commit the result without a driver turn. A `build`
  *   command may be deterministic in outcome but is not autofix (it reports, it does not repair).
  */
final case class RepoCommand(
    kind: CommandKind,
    argv: Vector[String],
    determinism: Determinism,
    required: Boolean,
    autofix: Boolean
) derives ReadWriter

/** Commit identity Forge should author as in this repo (§ git identity). */
final case class CommitIdentity(name: String, email: String) derives ReadWriter

/** Workflow shape — review/CI/merge-strategy/branch-model. The merge strategy lives here (a workflow concern); the
  * build/gate commands and commit identity live on [[RepoProfile]].
  */
final case class WorkflowProfile(
    reviewRequired: Boolean,
    ciRequiredChecks: Vector[String],
    branchModel: BranchModel,
    mergeStrategy: MergeStrategy
) derives ReadWriter

/** Structured perception of a repo: build tool, gate commands tiered by determinism + required/optional, commit
  * identity, and workflow shape. Committed at `.forge/profile.json`.
  */
final case class RepoProfile(
    schemaVersion: Int,
    buildTool: String,
    commands: Vector[RepoCommand],
    commitIdentity: CommitIdentity,
    workflow: WorkflowProfile
) derives ReadWriter:

  /** The repo command serving `kind`, if the profile declares one. */
  def command(kind: CommandKind): Option[RepoCommand] = commands.find(_.kind == kind)

  /** A stable content hash of this profile (A5 "versioned input … hashed into the action log per run"). Computed over
    * the canonical compact JSON; upickle serialises case-class fields in declaration order, so re-serialisation is
    * byte-stable and the hash is reproducible across runs. The committed `.forge/profile.json` is the content; this
    * hash, recorded in the §19 `profile.snapshot` action, binds a run to the profile that was in force.
    */
  def contentHash: String = RepoProfile.hash(this)

object RepoProfile:
  val CurrentSchemaVersion: Int = 1

  def fromJson(s: String): RepoProfile = read[RepoProfile](s)
  def toJson(p: RepoProfile, indent: Int = 2): String = write(p, indent = indent)

  /** SHA-256 over the compact JSON, truncated to 16 hex chars — enough to disambiguate profile versions in the log
    * without bloating it.
    */
  def hash(p: RepoProfile): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(write(p).getBytes(StandardCharsets.UTF_8))
    digest.take(8).map(b => f"$b%02x").mkString
