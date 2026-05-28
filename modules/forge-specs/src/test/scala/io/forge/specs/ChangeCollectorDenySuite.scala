package io.forge.specs

/** Task 1.4.5 E6 — every §18 default deny pattern triggers exactly when expected, plus rules 2–4 (outside repo, under
  * `.git/`, gitignored-and-not-under-`.forge/specs/`). The deny-pattern table is keyed by the pattern so a failure
  * points straight at the offending entry; each row also pins a root-level *and* a nested path to prove the `**`-prefix
  * glob workaround (design-rationale CC4) matches at depth 0 and beyond.
  */
class ChangeCollectorDenySuite extends munit.FunSuite, ChangeCollectorSupport:

  /** (deny pattern, representative paths that must match it). */
  private val denyCases: Vector[(String, Vector[String])] = Vector(
    "**/.env" -> Vector(".env", "config/.env"),
    "**/.env.*" -> Vector(".env.local", "deploy/.env.production"),
    "**/*.pem" -> Vector("server.pem", "certs/server.pem"),
    "**/*.key" -> Vector("private.key", "secrets/private.key"),
    "**/id_rsa*" -> Vector("id_rsa", "keys/id_rsa.pub"),
    "**/credentials.json" -> Vector("credentials.json", "gcp/credentials.json"),
    "**/.aws/**" -> Vector(".aws/credentials", "home/.aws/config"),
    "**/.ssh/**" -> Vector(".ssh/config", "home/.ssh/known_hosts"),
    "**/target/**" -> Vector("target/app.jar", "modules/core/target/x.class"),
    "**/build/**" -> Vector("build/out.js", "sub/build/out.js"),
    "**/dist/**" -> Vector("dist/bundle.js", "web/dist/bundle.js"),
    "**/node_modules/**" -> Vector("node_modules/left-pad/index.js", "web/node_modules/x/y.js"),
    "**/.bloop/**" -> Vector(".bloop/forge.json", "sub/.bloop/forge.json"),
    "**/.metals/**" -> Vector(".metals/metals.h2.db", "sub/.metals/x"),
    "**/.idea/**" -> Vector(".idea/workspace.xml", "sub/.idea/x.xml"),
    "**/.vscode/**" -> Vector(".vscode/settings.json", "sub/.vscode/settings.json")
  )

  denyCases.foreach { case (pattern, paths) =>
    paths.foreach { p =>
      test(s"§18 deny pattern '$pattern' denies '$p'") {
        classify(Vector(at(p, FileChangeKind.Added)), StagingConfig.Default) match
          case Classification.Deny(denied) =>
            assertEquals(denied.size, 1)
            assertEquals(denied.head._2, pattern, s"reported reason should be the matched pattern")
          case other => fail(s"expected Deny for '$p', got $other")
      }
    }
  }

  test("rule 2 — a path outside the repo root is Denied as 'outside repository root'") {
    classify(Vector(outside("/somewhere/else/notes.txt")), StagingConfig.Default) match
      case Classification.Deny(denied) =>
        assertEquals(denied.map(_._2), Vector("outside repository root"))
      case other => fail(s"expected Deny, got $other")
  }

  test("rule 3 — a path under .git/ is Denied as 'under .git/'") {
    classify(Vector(at(".git/config", FileChangeKind.Modified)), StagingConfig.Default) match
      case Classification.Deny(denied) =>
        assertEquals(denied.map(_._2), Vector("under .git/"))
      case other => fail(s"expected Deny, got $other")
  }

  test("rule 4 — a gitignored path NOT under .forge/specs/ is Denied as 'ignored by .gitignore'") {
    val change = at("scratch/notes.txt", FileChangeKind.Added, ignored = true)
    classify(Vector(change), StagingConfig.Default) match
      case Classification.Deny(denied) =>
        assertEquals(denied.map(_._2), Vector("ignored by .gitignore"))
      case other => fail(s"expected Deny, got $other")
  }

  test("Deny lists every offending change, not just the first") {
    val changes = Vector(
      at("server.pem", FileChangeKind.Added),
      at(".git/config", FileChangeKind.Modified),
      outside("/elsewhere/x.txt")
    )
    classify(changes, StagingConfig.Default) match
      case Classification.Deny(denied) =>
        assertEquals(denied.size, 3)
        assertEquals(denied.map(_._2).toSet, Set("**/*.pem", "under .git/", "outside repository root"))
      case other => fail(s"expected Deny, got $other")
  }
