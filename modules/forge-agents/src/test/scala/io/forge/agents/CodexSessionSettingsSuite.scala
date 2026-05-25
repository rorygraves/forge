package io.forge.agents

class CodexSessionSettingsSuite extends munit.FunSuite:

  private val tmp = os.temp.dir()

  private val base = CodexSessionSettings(
    sandbox = "workspace-write",
    outputSchema = None,
    addDirs = Vector(tmp / "extra"),
    approvalMode = "never",
    workingDirectory = Some(tmp / "repo")
  )

  test("driver(...) factory has no outputSchema, no addDirs, no workingDirectory"):
    val s = CodexSessionSettings.driver(sandbox = "workspace-write", approvalMode = "never")
    assertEquals(s.outputSchema, None)
    assertEquals(s.addDirs, Vector.empty)
    assertEquals(s.workingDirectory, None)
    assertEquals(s.sandbox, "workspace-write")
    assertEquals(s.approvalMode, "never")

  test("isCompatibleForResume is true for an equal copy"):
    assert(base.isCompatibleForResume(base.copy()))

  test("isCompatibleForResume is false when sandbox differs"):
    assert(!base.isCompatibleForResume(base.copy(sandbox = "read-only")))

  test("isCompatibleForResume is false when outputSchema differs"):
    assert(!base.isCompatibleForResume(base.copy(outputSchema = Some(tmp / "schema.json"))))

  test("isCompatibleForResume is false when addDirs gains an entry"):
    assert(!base.isCompatibleForResume(base.copy(addDirs = base.addDirs :+ (tmp / "extra2"))))

  test("isCompatibleForResume treats addDirs ordering as significant"):
    val reordered = base.copy(addDirs = Vector(tmp / "extra2", tmp / "extra"))
    val original = base.copy(addDirs = Vector(tmp / "extra", tmp / "extra2"))
    assert(!original.isCompatibleForResume(reordered))

  test("isCompatibleForResume is false when approvalMode differs"):
    assert(!base.isCompatibleForResume(base.copy(approvalMode = "on-failure")))

  test("isCompatibleForResume is false when workingDirectory differs"):
    assert(!base.isCompatibleForResume(base.copy(workingDirectory = Some(tmp / "elsewhere"))))

  test("isCompatibleForResume is symmetric"):
    val other = base.copy(sandbox = "read-only")
    assertEquals(base.isCompatibleForResume(other), other.isCompatibleForResume(base))
