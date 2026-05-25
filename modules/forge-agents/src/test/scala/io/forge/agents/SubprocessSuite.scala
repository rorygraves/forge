package io.forge.agents

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration.*

class SubprocessSuite extends munit.FunSuite:

  // These tests use POSIX shell builtins. Skip on platforms without /bin/sh.
  private val hasPosixShell = os.exists(os.Path("/bin/sh"))

  override def munitTimeout: scala.concurrent.duration.Duration = 30.seconds

  test("echo emits a single line on stdout and exits 0"):
    assume(hasPosixShell)
    val program = Subprocess
      .spawn(List("/bin/sh", "-c", "echo hello"))
      .use: sp =>
        for
          lines <- sp.stdout.compile.toVector
          code <- sp.waitFor
        yield (lines, code)
    val (lines, code) = program.unsafeRunSync()
    assertEquals(lines.headOption, Some("hello"))
    assertEquals(code, 0)

  test("cat echoes stdin lines back on stdout"):
    assume(hasPosixShell)
    val program = Subprocess
      .spawn(List("/bin/sh", "-c", "cat"))
      .use: sp =>
        for
          _ <- sp.sendLine("alpha")
          _ <- sp.sendLine("beta")
          _ <- sp.closeStdin
          lines <- sp.stdout.compile.toVector
          code <- sp.waitFor
        yield (lines.toList, code)
    val (lines, code) = program.unsafeRunSync()
    // Some shells leave a trailing empty line after the final newline; accept either.
    assertEquals(lines.filter(_.nonEmpty).take(2), List("alpha", "beta"))
    assertEquals(code, 0)

  test("stdout and stderr are delivered on separate streams"):
    assume(hasPosixShell)
    val program = Subprocess
      .spawn(List("/bin/sh", "-c", "echo out; echo err 1>&2"))
      .use: sp =>
        for
          // Read in parallel — neither stream should block the other.
          outFib <- sp.stdout.compile.toVector.start
          errFib <- sp.stderr.compile.toVector.start
          out <- outFib.joinWithNever
          err <- errFib.joinWithNever
        yield (out.filter(_.nonEmpty), err.filter(_.nonEmpty))
    val (out, err) = program.unsafeRunSync()
    assertEquals(out.toList, List("out"))
    assertEquals(err.toList, List("err"))

  test("kill sends SIGTERM and returns quickly when the process is well-behaved"):
    assume(hasPosixShell)
    val program = Subprocess
      .spawn(List("/bin/sh", "-c", "sleep 60"))
      .use: sp =>
        for
          start <- IO.monotonic
          _ <- sp.kill(2.seconds)
          end <- IO.monotonic
          alive <- sp.isAlive
        yield (end - start, alive)
    val (elapsed, alive) = program.unsafeRunSync()
    assertEquals(alive, false)
    // SIGTERM on /bin/sh sleep exits well under 2s; on most systems sub-100ms.
    assert(elapsed < 2.seconds, clue = elapsed)

  test("kill escalates to SIGKILL when the process traps SIGTERM"):
    assume(hasPosixShell)
    // Need a shell that ignores SIGTERM *and* doesn't get optimized into `exec`ing its final command — many /bin/sh
    // implementations exec the last command in a `-c` script, which bypasses the trap. A while-loop with no final
    // exec target keeps the shell as the live process so the trap is the one taking the SIGTERM. The `echo started`
    // is a synchronization barrier: without it, this test races spawn vs. trap installation — Java's
    // ProcessBuilder.start returns immediately, and SIGTERM landing in the ~400μs before `trap '' TERM` executes
    // will kill the shell at default behaviour and the SIGKILL path is never exercised.
    val program = Subprocess
      .spawn(List("/bin/sh", "-c", "trap '' TERM; echo started; while true; do sleep 0.1; done"))
      .use: sp =>
        for
          _ <- sp.stdout.head.compile.lastOrError // wait for "started"
          start <- IO.monotonic
          _ <- sp.kill(800.millis)
          end <- IO.monotonic
          alive <- sp.isAlive
          code <- sp.waitFor
        yield (end - start, alive, code)
    val (elapsed, alive, code) = program.unsafeRunSync()
    assertEquals(alive, false)
    assertEquals(code, 137) // 128 + 9 = SIGKILL exit code on POSIX
    // Should take ~grace duration, not 60s.
    assert(elapsed >= 700.millis, clue = elapsed) // small slack
    assert(elapsed < 3.seconds, clue = elapsed)

  test("Resource finalizer hard-kills a still-running process"):
    assume(hasPosixShell)
    // Capture the pid inside .use, then assert outside .use that the process is gone.
    val program = Subprocess
      .spawn(List("/bin/sh", "-c", "sleep 60"))
      .use(sp => sp.pid)
    val pid = program.unsafeRunSync()
    // Give the OS a beat to reap.
    IO.sleep(200.millis).unsafeRunSync()
    // `kill -0 <pid>` exits 0 if the process exists, non-zero otherwise.
    val checkAlive = Subprocess
      .spawn(List("/bin/sh", "-c", s"kill -0 $pid 2>/dev/null; echo $$?"))
      .use(sp => sp.stdout.compile.toVector)
      .unsafeRunSync()
    assertEquals(checkAlive.filter(_.nonEmpty).headOption, Some("1"))

  test("sendLine after closeStdin fails"):
    assume(hasPosixShell)
    val program = Subprocess
      .spawn(List("/bin/sh", "-c", "cat"))
      .use: sp =>
        for
          _ <- sp.sendLine("a")
          _ <- sp.closeStdin
          result <- sp.sendLine("b").attempt
        yield result
    val result = program.unsafeRunSync()
    assert(result.isLeft, clue = result)

  test("closeStdin is idempotent"):
    assume(hasPosixShell)
    val program = Subprocess
      .spawn(List("/bin/sh", "-c", "cat"))
      .use: sp =>
        for
          _ <- sp.closeStdin
          _ <- sp.closeStdin
          code <- sp.waitFor
        yield code
    assertEquals(program.unsafeRunSync(), 0)

  test("spawn of a non-existent binary fails the Resource acquisition"):
    val program = Subprocess
      .spawn(List("/this/definitely/does/not/exist"))
      .use(_ => IO.unit)
      .attempt
    assert(program.unsafeRunSync().isLeft)
