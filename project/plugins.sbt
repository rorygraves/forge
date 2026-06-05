// sbt-scalafmt wires `scalafmtAll` / `scalafmtCheckAll` — required by
// README and AGENTS.md.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

// sbt-assembly packages `forge-app` into a single self-contained `forge.jar`
// (all modules + the bundled `assets/` and `prices.example.json` classpath
// resources) for the standalone launcher — roadmap §3.4 OSS-readiness.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
