package io.forge.agents

class CodexPromptSuite extends munit.FunSuite:

  private def tmpFile(contents: String): os.Path =
    os.temp(contents, suffix = ".md")

  test("withSystemBlock concatenates system file + user prompt with a blank line"):
    val path = tmpFile("## System\nYou are a careful driver.")
    val out = CodexPrompt.withSystemBlock(path, "Implement piece p1.")
    assertEquals(out, "## System\nYou are a careful driver.\n\nImplement piece p1.")

  test("withSystemBlock trims a single trailing newline in the system file"):
    val path = tmpFile("## System\nbody\n")
    val out = CodexPrompt.withSystemBlock(path, "user")
    assertEquals(out, "## System\nbody\n\nuser")

  test("withSystemBlock trims trailing whitespace in the user prompt too"):
    val path = tmpFile("sys")
    val out = CodexPrompt.withSystemBlock(path, "user\n")
    assertEquals(out, "sys\n\nuser")

  test("withSystemBlock returns only user prompt when system file is empty"):
    val path = tmpFile("")
    assertEquals(CodexPrompt.withSystemBlock(path, "just user"), "just user")

  test("withSystemBlock returns only system block when user prompt is empty"):
    val path = tmpFile("## System\nonly sys")
    assertEquals(CodexPrompt.withSystemBlock(path, ""), "## System\nonly sys")

  test("withSystemBlock raises a recognisable error when the system file is missing"):
    val missing = os.temp.dir() / "does-not-exist.md"
    intercept[Throwable](CodexPrompt.withSystemBlock(missing, "user"))
