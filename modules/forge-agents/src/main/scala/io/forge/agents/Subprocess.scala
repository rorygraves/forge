package io.forge.agents

import cats.effect.IO
import cats.effect.kernel.Resource
import fs2.Stream

import java.io.{IOException, InputStream}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Cats-effect/fs2 wrapper around `java.lang.ProcessBuilder`. Independent of any specific CLI — the per-connector
  * adapters layer flags + an event parser on top via [[StreamingDriver]].
  *
  * Design choices worth preserving (Slice 0 §2 + §7.9):
  *
  *   - **stderr is kept separate** from stdout. Codex leaks `"Reading additional input from stdin..."` on stderr even
  *     when the prompt is on argv; mixing the two would corrupt the JSON event stream. Both streams are exposed.
  *   - **Kill semantics are SIGTERM → grace → SIGKILL**, mirroring §7.9 and matched against Slice 0's measured ~100ms
  *     (Codex) / ~400ms (Claude) clean-exit times.
  *   - **Resource finalizer is a safety net**, not the primary cleanup. The Resource always force-kills any process
  *     still alive at exit (idempotent on already-dead processes), but the streaming session calls [[kill]] or
  *     [[closeStdin]] explicitly when it knows the right moment.
  *
  * Tested with shell builtins (`echo`, `cat`, `sleep`, `sh -c "trap '' TERM; …"`) — no real CLIs required.
  */
final class Subprocess private (
    private val process: java.lang.Process,
    val stdout: Stream[IO, String],
    val stderr: Stream[IO, String]
):

  /** Wait for the process to exit and return the exit code. Does not signal the process — pair with [[kill]] or
    * [[closeStdin]] to actually cause an exit.
    */
  def waitFor: IO[Int] =
    IO.fromCompletableFuture(IO.delay(process.onExit())).map(_.exitValue)

  /** Write `s + "\n"` (UTF-8) to stdin and flush. Fails the IO if stdin has already been closed. */
  def sendLine(s: String): IO[Unit] =
    IO.blocking {
      val w = process.getOutputStream
      w.write((s + "\n").getBytes(StandardCharsets.UTF_8))
      w.flush()
    }

  /** Close stdin, signalling EOF. Many CLIs (cat, codex headless) exit on EOF, so this is the graceful-shutdown path
    * when the protocol expects EOF. Idempotent — a second call is silently swallowed.
    */
  def closeStdin: IO[Unit] =
    IO.blocking(process.getOutputStream.close()).recover { case _: IOException => () }

  /** SIGTERM, race against `grace` for the process to exit on its own, then SIGKILL if it doesn't. Returns only once
    * the process is fully exited, so callers know reaping is done.
    *
    * Idempotent on an already-exited process: `destroy()` is a no-op, `waitFor` returns immediately.
    */
  def kill(grace: FiniteDuration = 5.seconds): IO[Unit] =
    IO.blocking { val _ = process.destroy() } >>
      IO.race(waitFor.void, IO.sleep(grace)).flatMap {
        case Left(_) => IO.unit
        case Right(_) =>
          IO.blocking { val _ = process.destroyForcibly() } >> waitFor.void
      }

  /** Whether the process is still alive at the moment of the check. */
  def isAlive: IO[Boolean] = IO.delay(process.isAlive)

  /** The OS pid. Useful for diagnostic logging. */
  def pid: IO[Long] = IO.delay(process.pid)

object Subprocess:

  /** Spawn a subprocess. The Resource finalizer hard-kills the process if it's still alive at exit (idempotent
    * otherwise) — a defense-in-depth against leaked subprocesses if a caller forgets to call `kill`.
    *
    * @param cmd
    *   argv list. `cmd.head` is the binary; the rest are arguments.
    * @param cwd
    *   working directory for the child. `None` inherits the JVM's cwd.
    * @param env
    *   environment overlay applied on top of the JVM's environment when `inheritEnv = true`, or used as the entire
    *   environment when `inheritEnv = false`.
    * @param inheritEnv
    *   when `true` (default), the child sees the JVM's environment plus `env` overrides; when `false`, only `env`.
    */
  def spawn(
      cmd: List[String],
      cwd: Option[os.Path] = None,
      env: Map[String, String] = Map.empty,
      inheritEnv: Boolean = true
  ): Resource[IO, Subprocess] =
    Resource
      .make(IO.blocking {
        val pb = new ProcessBuilder(cmd.asJava)
        cwd.foreach { p =>
          val _ = pb.directory(p.toIO)
        }
        if !inheritEnv then pb.environment().clear()
        env.foreach { case (k, v) => val _ = pb.environment().put(k, v) }
        val _ = pb.redirectErrorStream(false)
        pb.start()
      })(p => IO.blocking(if p.isAlive then { val _ = p.destroyForcibly() }))
      .map { p =>
        val out = readLines(p.getInputStream)
        val err = readLines(p.getErrorStream)
        new Subprocess(p, out, err)
      }

  private def readLines(is: => InputStream): Stream[IO, String] =
    fs2.io
      .readInputStream(IO.delay(is), chunkSize = 8192, closeAfterUse = false)
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
