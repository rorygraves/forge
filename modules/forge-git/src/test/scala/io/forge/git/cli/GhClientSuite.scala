package io.forge.git.cli

/** Task 3.1.2 — [[GhClient.stripAnsi]] over the real `gh run view --log-failed` framing (see docs/slice-3/fixtures):
  * ANSI SGR colour codes and the byte-order mark `gh` prefixes are removed so the classifier markers and the
  * `failures.md` driver context read as plain text.
  */
class GhClientSuite extends munit.FunSuite:

  private val ESC = ''

  test("stripAnsi removes SGR colour codes, leaving the plain log text"):
    val raw = s"$ESC[0m[$ESC[31merror$ESC[0m] scalafmt: 1 files must be formatted$ESC[0m"
    assertEquals(GhClient.stripAnsi(raw), "[error] scalafmt: 1 files must be formatted")

  test("stripAnsi removes cursor/erase codes and the leading BOM"):
    assertEquals(GhClient.stripAnsi(s"﻿2026-06-01T22:35:00Z $ESC[0Jdone"), "2026-06-01T22:35:00Z done")

  test("stripAnsi is a no-op on plain text"):
    assertEquals(GhClient.stripAnsi("scalafmt: 1 files must be formatted"), "scalafmt: 1 files must be formatted")
