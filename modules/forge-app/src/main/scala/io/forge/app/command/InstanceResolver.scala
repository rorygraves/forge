package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.core.InstanceName
import io.forge.instance.{Instance, InstanceError, InstanceStore}

/** Shared "which instance does this command target?" resolution for the Phase-4 instance-scoped command families
  * ([[InstanceCommands]], Task 4.0.3; [[DaemonCommands]], Task 4.1.3).
  *
  * The rule (contract §4 / §6): an explicit `--instance <name>` is loaded directly; with no flag, the **sole** instance
  * is used when exactly one exists, and zero-or-many is a usage-class failure that tells the operator how to
  * disambiguate. Diagnostics are printed here; the caller gets back `Left(exitCode)` already-reported, or the resolved
  * [[Instance]].
  */
object InstanceResolver:

  def resolve(
      store: InstanceStore,
      instance: Option[InstanceName],
      label: String
  ): IO[Either[ExitCode, Instance]] =
    instance match
      case Some(name) =>
        store.load(name).flatMap {
          case Right(resolved) => IO.pure(Right(resolved))
          case Left(err) => Console[IO].errorln(s"forge $label: ${renderError(err)}").as(Left(ExitCode(1)))
        }
      case None =>
        store.list.flatMap {
          case Vector(only) =>
            store.load(only).flatMap {
              case Right(resolved) => IO.pure(Right(resolved))
              case Left(err) => Console[IO].errorln(s"forge $label: ${renderError(err)}").as(Left(ExitCode(1)))
            }
          case Vector() =>
            Console[IO]
              .errorln(s"forge $label: no instances exist. Create one with `forge init-instance <name>`.")
              .as(Left(ExitCode(1)))
          case many =>
            Console[IO]
              .errorln(
                s"forge $label: multiple instances exist (${many.map(_.value).mkString(", ")}); " +
                  "pick one with `--instance <name>`."
              )
              .as(Left(ExitCode(1)))
        }

  /** Human-readable rendering of an [[InstanceError]] for a CLI diagnostic line. */
  def renderError(err: InstanceError): String = err match
    case InstanceError.NoSuchInstance(name) =>
      s"no such instance '${name.value}' (create it with `forge init-instance ${name.value}`)"
    case InstanceError.DuplicateInstance(name) => s"instance '${name.value}' already exists"
    case InstanceError.RepoNotFound(path) => s"path does not exist or is not a directory: $path"
    case InstanceError.NotAGitRepo(path) => s"not a git working tree (no .git entry): $path"
    case InstanceError.DuplicateRepo(path) => s"repo already registered with this instance: $path"
    case InstanceError.Malformed(file, cause) => s"malformed registry at $file: ${cause.getMessage}"
    case InstanceError.IoFailure(file, cause) => s"I/O error at $file: ${cause.getMessage}"
