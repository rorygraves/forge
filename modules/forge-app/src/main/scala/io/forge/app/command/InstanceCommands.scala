package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.cli.InstanceCommand
import io.forge.app.lock.{FileProcessLock, LockAcquireResult, LockMetadata}
import io.forge.core.InstanceName
import io.forge.instance.{FileInstanceStore, Instance, InstanceError, InstanceStore}

import java.net.InetAddress
import java.time.Instant
import scala.util.control.NonFatal

/** Task 4.0.3 — handlers for the Phase-4 instance-registry commands (`init-instance` / `add-repo` / `list-repos`).
  *
  * These are instance-scoped, not per-checkout: they own `~/.forge/instances/<name>/` (via [[FileInstanceStore]]),
  * never the repo's `.forge`, so `Main` routes them here without loading [[io.forge.app.config.ForgeConfig]],
  * installing reviewer assets, or taking the per-checkout process lock. The two mutating commands serialize on an
  * **instance-level** lock ([[Instance.lockFile]]); `list-repos` is read-only and takes none. Full instance-lock
  * ownership (held by the long-running daemon, with per-worker locks below it) is 4.1.
  */
object InstanceCommands:

  def run(home: os.Path, command: InstanceCommand): IO[ExitCode] =
    val store = new FileInstanceStore(home)
    command match
      case InstanceCommand.InitInstance(name) => initInstance(home, store, name)
      case InstanceCommand.AddRepo(instance, repoRaw) => addRepo(home, store, instance, repoRaw)
      case InstanceCommand.ListRepos(instance) => listRepos(home, store, instance)

  // --- init-instance ---------------------------------------------------------

  private def initInstance(home: os.Path, store: InstanceStore, name: InstanceName): IO[ExitCode] =
    // Acquiring the lock lays down the (empty) instance dir as a side effect; `create` then guards on `config.json`
    // (not the dir) so a second concurrent `init-instance` of the same name, once it wins the lock, sees DuplicateInstance.
    withInstanceLock(Instance.at(name, home), "init-instance") {
      store.create(name).flatMap {
        case Right(created) =>
          Console[IO]
            .println(s"forge init-instance ${name.value}: created instance at ${created.dir}")
            .as(ExitCode.Success)
        case Left(err) =>
          Console[IO].errorln(s"forge init-instance ${name.value}: ${render(err)}").as(ExitCode(1))
      }
    }

  // --- add-repo --------------------------------------------------------------

  private def addRepo(
      home: os.Path,
      store: InstanceStore,
      instance: Option[InstanceName],
      repoRaw: String
  ): IO[ExitCode] =
    resolveInstance(home, store, instance, "add-repo").flatMap {
      case Left(code) => IO.pure(code)
      case Right(resolved) =>
        // `repoRaw` is resolved against the caller's cwd at handler time (not at parse time), so a relative `add-repo .`
        // binds to where the operator ran `forge`, and registry membership compares canonically (os.Path is absolute).
        IO.blocking(os.Path(repoRaw, os.pwd)).flatMap { repoPath =>
          withInstanceLock(resolved, "add-repo") {
            store.addRepo(resolved, repoPath).flatMap {
              case Right(entry) =>
                Console[IO]
                  .println(s"forge add-repo: registered ${entry.path} with instance ${resolved.name.value}")
                  .as(ExitCode.Success)
              case Left(err) =>
                Console[IO].errorln(s"forge add-repo: ${render(err)}").as(ExitCode(1))
            }
          }
        }
    }

  // --- list-repos (read-only — no lock) --------------------------------------

  private def listRepos(home: os.Path, store: InstanceStore, instance: Option[InstanceName]): IO[ExitCode] =
    resolveInstance(home, store, instance, "list-repos").flatMap {
      case Left(code) => IO.pure(code)
      case Right(resolved) =>
        store.listRepos(resolved).flatMap {
          case Right(repos) if repos.isEmpty =>
            Console[IO]
              .println(s"forge list-repos: no repos registered with instance ${resolved.name.value}")
              .as(ExitCode.Success)
          case Right(repos) =>
            Console[IO].println(repos.map(_.path).mkString("\n")).as(ExitCode.Success)
          case Left(err) =>
            Console[IO].errorln(s"forge list-repos: ${render(err)}").as(ExitCode(1))
        }
    }

  // --- instance resolution ---------------------------------------------------

  /** Resolve the instance an `add-repo` / `list-repos` targets: the `--instance <name>` value when given, else the sole
    * instance when exactly one exists. Zero or many instances with no flag is a usage-class failure that tells the
    * operator how to disambiguate. Returns `Left(exitCode)` having already printed the diagnostic.
    */
  private def resolveInstance(
      home: os.Path,
      store: InstanceStore,
      instance: Option[InstanceName],
      label: String
  ): IO[Either[ExitCode, Instance]] =
    instance match
      case Some(name) =>
        store.load(name).flatMap {
          case Right(resolved) => IO.pure(Right(resolved))
          case Left(err) => Console[IO].errorln(s"forge $label: ${render(err)}").as(Left(ExitCode(1)))
        }
      case None =>
        store.list.flatMap {
          case Vector(only) =>
            store.load(only).flatMap {
              case Right(resolved) => IO.pure(Right(resolved))
              case Left(err) => Console[IO].errorln(s"forge $label: ${render(err)}").as(Left(ExitCode(1)))
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

  // --- instance lock ---------------------------------------------------------

  /** Run `body` while holding the instance-level OS lock at [[Instance.lockFile]]. Uses `acceptStale = true`: in 4.0 no
    * process legitimately holds this lock across its own exit (the daemon that will is 4.1), so a stale lock left by a
    * crashed registry command is always safe to reclaim. A *live* cross-process holder still surfaces as `Held` → exit
    * 2.
    */
  private def withInstanceLock(instance: Instance, label: String)(body: => IO[ExitCode]): IO[ExitCode] =
    lockMetadata(label).flatMap { metadata =>
      new FileProcessLock(instance.lockFile, instance.lockMetadataFile).acquire(metadata, acceptStale = true).use {
        case LockAcquireResult.Acquired => body
        case LockAcquireResult.Held(holder) =>
          Console[IO]
            .errorln(
              s"forge $label: another forge process holds the '${instance.name.value}' instance lock." +
                holderSuffix(holder)
            )
            .as(ExitCode(2))
        case LockAcquireResult.Stale(holder) =>
          // Unreachable with acceptStale = true (Stale is upgraded to Acquired); kept as a defensive branch.
          Console[IO]
            .errorln(
              s"forge $label: a stale '${instance.name.value}' instance lock is present." + holderSuffix(Some(holder))
            )
            .as(ExitCode(2))
      }
    }

  private def lockMetadata(label: String): IO[LockMetadata] =
    IO.blocking {
      val hostname =
        try InetAddress.getLocalHost.getHostName
        catch case NonFatal(_) => "unknown"
      LockMetadata(
        pid = ProcessHandle.current().pid(),
        hostname = hostname,
        startedAt = Instant.now(),
        command = s"forge $label",
        feature = None
      )
    }

  private def holderSuffix(meta: Option[LockMetadata]): String = meta match
    case Some(m) => s" Holder: pid=${m.pid} host=${m.hostname} command='${m.command}' started=${m.startedAt}."
    case None => ""

  private def render(err: InstanceError): String = err match
    case InstanceError.NoSuchInstance(name) =>
      s"no such instance '${name.value}' (create it with `forge init-instance ${name.value}`)"
    case InstanceError.DuplicateInstance(name) => s"instance '${name.value}' already exists"
    case InstanceError.RepoNotFound(path) => s"path does not exist or is not a directory: $path"
    case InstanceError.NotAGitRepo(path) => s"not a git working tree (no .git entry): $path"
    case InstanceError.DuplicateRepo(path) => s"repo already registered with this instance: $path"
    case InstanceError.Malformed(file, cause) => s"malformed registry at $file: ${cause.getMessage}"
    case InstanceError.IoFailure(file, cause) => s"I/O error at $file: ${cause.getMessage}"
