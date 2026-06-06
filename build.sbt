// Forge — Scala meta-orchestrator (see docs/forge-design-1.2.md).
// Module layout per §3.2; build order per §17.

// Bumped 3.5.2 → 3.7.1 for Slice 2.1 (TUI): termflow is published only for Scala 3.7.x (TASTy 28.7),
// which a 3.5.2 compiler (TASTy ≤ 28.5) cannot read. See docs/design-2.1-tui.md Task 1.
ThisBuild / scalaVersion := "3.7.1"
ThisBuild / organization := "io.forge"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Wunused:imports",
  "-Wvalue-discard",
  "-Xfatal-warnings"
)

// --- versions ---
val V = new {
  val catsEffect = "3.5.4"
  val fs2 = "3.11.0"
  val osLib = "0.11.3"
  val upickle = "4.0.2"
  val jsonSchemaValidator = "1.5.3"
  val munit = "1.0.4"
  val munitCatsEffect = "2.0.0"
  // Slice 2.1 (TUI): termflow Elm-architecture framework. 0.4.0 is the API the forge-tui app is
  // written against (grounded against its published sources). Maven Central currently carries 0.3.0;
  // 0.4.0 resolves from the local ivy repo (`publishLocal`, on sbt's default resolver chain) until it
  // ships to Central. Tracked as a Slice 2.1 Task-1 watch item — see docs/design-2.1-tui.md.
  val termflow = "0.4.0"
}

// --- libraries ---
val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
// Slice 3 PR-F: TestControl for deterministic clock advancement in SessionMonitorSuite.
val catsEffectTestkit = "org.typelevel" %% "cats-effect-testkit" % V.catsEffect % Test
val fs2Core = "co.fs2" %% "fs2-core" % V.fs2
val fs2Io = "co.fs2" %% "fs2-io" % V.fs2
val osLib = "com.lihaoyi" %% "os-lib" % V.osLib
val upickle = "com.lihaoyi" %% "upickle" % V.upickle
val jsonSchemaValidator = "com.networknt" % "json-schema-validator" % V.jsonSchemaValidator
val munit = "org.scalameta" %% "munit" % V.munit % Test
// munit-scalacheck releases its own version line (no 1.0.4 cut). 1.0.0 is the latest tagged
// compat with munit 1.0.x; pinning explicitly so the dep doesn't drift with munit bumps.
val munitScalacheck = "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test
val munitCatsEffect = "org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect % Test
// Slice 2.1 (TUI). The `termflow` aggregator transitively pulls termflow-{terminal,screen,app,widgets};
// `termflow-testkit` (TuiTestDriver / GoldenSupport / KeySim) is Test-only.
val termflow = "org.llm4s" %% "termflow" % V.termflow
val termflowTestkit = "org.llm4s" %% "termflow-testkit" % V.termflow % Test

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(munit, munitScalacheck, munitCatsEffect),
  Test / testFrameworks += new TestFramework("munit.Framework")
)

lazy val `forge-core` = (project in file("modules/forge-core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(catsEffect, upickle, osLib)
  )

// Phase-4 Slice 4.0 (Task 4.0.2): the per-user Forge **instance** model + registry store
// (`~/.forge/instances/<name>/{config.json,repos.json,workers/}`). Depends on forge-core for `ForgePaths`
// (instance topology) + `InstanceName`/`FeatureId` ids + the `io.forge.core.Json` codecs. No daemon / container /
// worker process yet — "worker" here is only the B1 local-runtime re-root directory (see docs/design-4.0.md).
lazy val `forge-instance` = (project in file("modules/forge-instance"))
  .dependsOn(`forge-core`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(catsEffect, upickle, osLib)
  )

// Phase-4 Slice 4.1: the long-running **daemon** mechanics. Owns the durable instance state store (append-only instance
// action log + rebuildable cache — contract §6.4 / O8) via forge-instance, plus the daemon's runtime snapshot
// (`DaemonState`, Task 4.1.3) and the JSON-RPC-2.0-over-Unix-socket status/control API (O2) the CLI/TUI clients speak.
// The supervisor *lifecycle* (instance-lock acquisition + the `forge daemon` CLI) composes in forge-app, where the §13
// `FileProcessLock` lives. Depends on forge-instance (Instance model + socket path + durability core) and transitively
// forge-core. fs2-io supplies the `JdkUnixSockets` transport (JDK-21 native). No worker process / container yet — see
// docs/design-4.1.md.
lazy val `forge-daemon` = (project in file("modules/forge-daemon"))
  .dependsOn(`forge-instance`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(catsEffect, fs2Core, fs2Io, osLib, upickle)
  )

lazy val `forge-agents` = (project in file("modules/forge-agents"))
  .dependsOn(`forge-core`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(catsEffect, fs2Core, fs2Io, osLib, upickle, jsonSchemaValidator)
  )

lazy val `forge-git` = (project in file("modules/forge-git"))
  .dependsOn(`forge-core`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(catsEffect, fs2Core, osLib, upickle)
  )

lazy val `forge-specs` = (project in file("modules/forge-specs"))
  .dependsOn(`forge-core`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(osLib, upickle),
    // Slice 4 Task 1.4.4: expose the shipped `assets/templates/...` on the test classpath so DocSyncSuite renders the
    // real `decomposition.md.hbs` (no drift between the rendered fixture and what AssetInstaller actually ships).
    Test / unmanagedResourceDirectories += (LocalRootProject / baseDirectory).value / "assets"
  )

// Slice 2.1 (TUI). termflow Elm-architecture UI over the forge-core read-model (FileStateCache /
// FileActionLog / Manifest). Reads the canonical action log + state cache (log-tail-first per
// docs/design-2.1-tui.md); a live AgentEvent tap is a later Task. Depends on forge-core (read-model)
// and forge-agents (event ADT for the later live-tap), NOT forge-app (forge-app dependsOn forge-tui).
lazy val `forge-tui` = (project in file("modules/forge-tui"))
  .dependsOn(`forge-core`, `forge-agents`)
  .settings(commonSettings)
  .settings(
    // catsEffect: the Task 2.1.2 snapshot builder's read-only `load` seam returns `IO` (reads the state cache + decodes
    // the action log in place). Declared explicitly even though forge-core re-exports it, matching the repo convention.
    libraryDependencies ++= Seq(termflow, termflowTestkit, osLib, upickle, catsEffect)
  )

lazy val `forge-app` = (project in file("modules/forge-app"))
  // Phase-4 Slice 4.0 (Task 4.0.3): forge-instance supplies the instance registry store + Instance handle for the
  // `init-instance` / `add-repo` / `list-repos` CLI commands (and, in 4.0.4, the worker re-root paths).
  // Slice 4.1 (Task 4.1.3): forge-daemon supplies the daemon mechanics (`DaemonState` / `Daemon` / `DaemonClient`) the
  // `forge daemon start|stop|status` CLI composes around the §13 `FileProcessLock` that lives here.
  .dependsOn(`forge-core`, `forge-agents`, `forge-git`, `forge-specs`, `forge-tui`, `forge-instance`, `forge-daemon`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(catsEffect, fs2Core, osLib, catsEffectTestkit),
    // Slice 4 PR-A: ship the in-tree reviewer assets (`assets/reviewer/{schemas,prompts}/...`,
    // `assets/templates/...`) on the forge-app classpath. AssetInstaller reads them from there
    // and copies into the user's `~/.forge/` on first run.
    Compile / unmanagedResourceDirectories += (LocalRootProject / baseDirectory).value / "assets",
    // Run `forge` in a forked JVM with the parent terminal's stdin connected, so the interactive
    // `forge spec` line-mode REPL reads cleanly from the console (`sbt "forge-app/run ..."`).
    Compile / run / fork := true,
    Compile / run / connectInput := true,
    Compile / run / outputStrategy := Some(StdoutOutput),
    // Roadmap §3.4 OSS-readiness: `sbt forge-app/assembly` builds the self-contained `forge.jar`
    // that `scripts/install-forge.sh` drops under `~/.forge/lib/` for the `bin/forge` launcher.
    // The fat jar bundles every module plus the `assets/` and `prices.example.json` classpath
    // resources, so AssetInstaller's `getResourceAsStream` reads work outside the repo checkout.
    assembly / mainClass := Some("io.forge.app.Main"),
    assembly / assemblyJarName := "forge.jar",
    assembly / assemblyMergeStrategy := {
      // sbt-assembly's default already discards MANIFEST.MF / module-info; these cover the
      // duplicate metadata that the cats-effect / fs2 / jackson (json-schema-validator) graph ships.
      case PathList("META-INF", "versions", _*) => MergeStrategy.discard
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case path if path.endsWith("module-info.class") => MergeStrategy.discard
      case other =>
        val default = (assembly / assemblyMergeStrategy).value
        default(other)
    }
  )

// Integration tests against real claude/codex/gh CLIs. Built last (Slice 1+). Intentionally NOT in root's
// `.aggregate(...)` list below: the IT suites need real `claude`, `codex`, `gh` on PATH plus network access,
// which CI and local dev environments often lack. Wire them up explicitly via `sbt "project forge-it" <task>`
// (the project does have an `.dependsOn(`forge-app`)`, so `sbt "project forge-it" compile` still rebuilds
// the upstream graph). `sbt forge-it/compile` is enough to catch a refactor that breaks the forge-it API
// surface — drop it into CI alongside `sbt compile` if you want belt-and-braces.
lazy val `forge-it` = (project in file("modules/forge-it"))
  .dependsOn(`forge-app`)
  .settings(commonSettings)
  .settings(
    Test / parallelExecution := false
  )

lazy val root = (project in file("."))
  .aggregate(
    `forge-core`,
    `forge-instance`,
    `forge-daemon`,
    `forge-agents`,
    `forge-git`,
    `forge-specs`,
    `forge-tui`,
    `forge-app`
    // forge-it intentionally excluded — see the comment on `forge-it` above for the rationale.
  )
  .settings(
    name := "forge",
    publish / skip := true
  )
