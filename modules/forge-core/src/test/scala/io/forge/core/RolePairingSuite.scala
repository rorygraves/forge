package io.forge.core

class RolePairingSuite extends munit.FunSuite:

  test("ClaudeDriver resolves to a same-CLI Claude pairing (D1)"):
    val p = RolePairing.of(Mode.ClaudeDriver)
    assertEquals(p.driver, Cli.Claude)
    assertEquals(p.reviewer, Cli.Claude)

  test("CodexDriver resolves to a same-CLI Codex pairing (D1)"):
    val p = RolePairing.of(Mode.CodexDriver)
    assertEquals(p.driver, Cli.Codex)
    assertEquals(p.reviewer, Cli.Codex)

  test("the same-CLI story: driver and reviewer share one CLI for every mode"):
    // design-3.5 D1 reconciliation — the production `forge run` path drives and reviews on one CLI
    // (Claude/haiku reviewing Claude; Codex reviewing Codex). The pairing reflects that, replacing the
    // retired cross-CLI `Role.pairFor` shape.
    Mode.values.foreach { m =>
      val p = RolePairing.of(m)
      assertEquals(p.driver, p.reviewer, s"pairing for $m must be same-CLI")
    }

  test("every mode resolves (resolver is total over Mode)"):
    Mode.values.foreach(m => RolePairing.of(m))
