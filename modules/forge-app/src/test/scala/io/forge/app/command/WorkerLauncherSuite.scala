package io.forge.app.command

import munit.FunSuite

/** Task 4.2.6 — regression for the relative-classpath bug dogfood #7 surfaced: a worker child runs under `cwd =
  * workerRoot`, so a *relative* `java.class.path` entry (what `java -jar <relative-path>/forge.jar` yields) must be
  * absolutized against the daemon's cwd or the child fails with `ClassNotFoundException: io.forge.app.Main`.
  */
class WorkerLauncherSuite extends FunSuite:

  private val sep = java.io.File.pathSeparator
  private val base = os.Path("/Users/rory.graves/workspace/home/forge")

  test("absoluteClasspath resolves a relative fat-jar entry against the daemon cwd") {
    val out = WorkerLauncher.absoluteClasspath("modules/forge-app/target/scala-3.7.1/forge.jar", base)
    assertEquals(out, (base / "modules" / "forge-app" / "target" / "scala-3.7.1" / "forge.jar").toString)
    assert(os.Path(out).toString.startsWith("/"), s"expected an absolute path, got $out")
  }

  test("absoluteClasspath leaves an already-absolute entry unchanged") {
    val abs = "/opt/deps/lib.jar"
    assertEquals(WorkerLauncher.absoluteClasspath(abs, base), abs)
  }

  test("absoluteClasspath handles a multi-entry classpath (sbt run), preserving order and dropping empties") {
    val raw = s"target/classes${sep}/abs/dep.jar${sep}${sep}libs/other.jar"
    val out = WorkerLauncher.absoluteClasspath(raw, base)
    assertEquals(
      out,
      Seq(
        (base / "target" / "classes").toString,
        "/abs/dep.jar",
        (base / "libs" / "other.jar").toString
      ).mkString(sep)
    )
  }
