// Forge — Scala meta-orchestrator (see forge-design-1.0.md).
// Module layout per §3.2; build order per §17.

ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "io.forge"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Wunused:imports",
  "-Wvalue-discard",
  "-Xfatal-warnings"
)

// --- versions ---
val V = new {
  val catsEffect          = "3.5.4"
  val fs2                 = "3.11.0"
  val osLib               = "0.11.3"
  val upickle             = "4.0.2"
  val jsonSchemaValidator = "1.5.3"
  val munit               = "1.0.4"
  val munitCatsEffect     = "2.0.0"
}

// --- libraries ---
val catsEffect          = "org.typelevel"   %% "cats-effect"            % V.catsEffect
val fs2Core             = "co.fs2"          %% "fs2-core"               % V.fs2
val fs2Io               = "co.fs2"          %% "fs2-io"                 % V.fs2
val osLib               = "com.lihaoyi"     %% "os-lib"                 % V.osLib
val upickle             = "com.lihaoyi"     %% "upickle"                % V.upickle
val jsonSchemaValidator = "com.networknt"    % "json-schema-validator"  % V.jsonSchemaValidator
val munit               = "org.scalameta"   %% "munit"                  % V.munit             % Test
val munitCatsEffect     = "org.typelevel"   %% "munit-cats-effect"      % V.munitCatsEffect   % Test

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(munit, munitCatsEffect),
  Test / testFrameworks += new TestFramework("munit.Framework")
)

lazy val `forge-core` = (project in file("modules/forge-core"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(upickle, osLib)
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
    libraryDependencies ++= Seq(osLib, upickle)
  )

// TUI deferred to Slice 5; termflow 0.1.0-SNAPSHOT will be a publishLocal dep when available.
lazy val `forge-tui` = (project in file("modules/forge-tui"))
  .dependsOn(`forge-core`, `forge-agents`)
  .settings(commonSettings)

lazy val `forge-app` = (project in file("modules/forge-app"))
  .dependsOn(`forge-core`, `forge-agents`, `forge-git`, `forge-specs`, `forge-tui`)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(catsEffect, osLib)
  )

// Integration tests against real claude/codex CLIs. Built last (Slice 1+).
lazy val `forge-it` = (project in file("modules/forge-it"))
  .dependsOn(`forge-app`)
  .settings(commonSettings)
  .settings(
    Test / parallelExecution := false
  )

lazy val root = (project in file("."))
  .aggregate(
    `forge-core`, `forge-agents`, `forge-git`, `forge-specs`,
    `forge-tui`, `forge-app`, `forge-it`
  )
  .settings(
    name := "forge",
    publish / skip := true
  )
