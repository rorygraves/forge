package io.forge.git.worktree

import io.forge.core.{BranchName, Sha}
import io.forge.git.cli.fake.FakeGitClient
import io.forge.git.cli.{GitError, StatusEntry}
import munit.CatsEffectSuite

/** D3-2 (roadmap §3.5) — table tests for the worktree-safety classifier. Half exercise the pure [[classify]] decision
  * tree directly; half drive the IO [[classifyWorktree]] gather-and-classify through `FakeGitClient` (the deliverable's
  * "unit tests with `FakeGitClient` across the cases"). The conservative invariant under test: every shape that is not
  * "expected branch + expected HEAD + at-most-driver-uncommitted edits" resolves to `UnexpectedDivergence`.
  */
class WorktreeSafetyClassifierSuite extends CatsEffectSuite:

  private val branch = BranchName("forge/feat/p1")
  private val head = Sha("abc1234")
  private val otherSha = Sha("def5678")

  private def modified(path: String): StatusEntry = StatusEntry(' ', 'M', path, None, ignored = false)
  private def untracked(path: String): StatusEntry = StatusEntry('?', '?', path, None, ignored = false)
  private def added(path: String): StatusEntry = StatusEntry('A', ' ', path, None, ignored = false)
  private def deleted(path: String): StatusEntry = StatusEntry(' ', 'D', path, None, ignored = false)

  // --- pure classify ---

  test("clean worktree on expected branch at expected HEAD → Clean (safe)"):
    val r = WorktreeSafetyClassifier.classify(branch, head, Right(branch), Right(head), Vector.empty)
    assertEquals(r, WorktreeSafety.Clean)
    assert(r.safeToResume)

  test("uncommitted edits only, expected branch + HEAD → DriverUncommittedOnly (safe)"):
    val entries = Vector(modified("src/Main.scala"), untracked("src/New.scala"), added("a"), deleted("gone"))
    val r = WorktreeSafetyClassifier.classify(branch, head, Right(branch), Right(head), entries)
    assertEquals(r, WorktreeSafety.DriverUncommittedOnly)
    assert(r.safeToResume)

  test("different branch → UnexpectedDivergence (unsafe)"):
    val r = WorktreeSafetyClassifier.classify(branch, head, Right(BranchName("main")), Right(head), Vector.empty)
    assertEquals(r, WorktreeSafety.UnexpectedDivergence)
    assert(!r.safeToResume)

  test("detached HEAD (currentBranch Left) → UnexpectedDivergence"):
    val detached = Left(GitError.ParseFailure("currentBranch", new RuntimeException("empty"), ""))
    val r = WorktreeSafetyClassifier.classify(branch, head, detached, Right(head), Vector(modified("x")))
    assertEquals(r, WorktreeSafety.UnexpectedDivergence)

  test("commits beyond expected HEAD → UnexpectedDivergence (operator/other committed work)"):
    val r = WorktreeSafetyClassifier.classify(branch, head, Right(branch), Right(otherSha), Vector.empty)
    assertEquals(r, WorktreeSafety.UnexpectedDivergence)

  test("currentSha Left → UnexpectedDivergence (conservative)"):
    val shaErr = Left(GitError.ParseFailure("currentSha", new RuntimeException("nope"), "garbage"))
    val r = WorktreeSafetyClassifier.classify(branch, head, Right(branch), shaErr, Vector.empty)
    assertEquals(r, WorktreeSafety.UnexpectedDivergence)

  test("merge conflict (UU) forces UnexpectedDivergence even on expected branch + HEAD"):
    val conflicted = Vector(StatusEntry('U', 'U', "src/Conflict.scala", None, ignored = false))
    val r = WorktreeSafetyClassifier.classify(branch, head, Right(branch), Right(head), conflicted)
    assertEquals(r, WorktreeSafety.UnexpectedDivergence)

  test("both-added (AA) and both-deleted (DD) are unmerged → UnexpectedDivergence"):
    val aa = Vector(StatusEntry('A', 'A', "added.scala", None, ignored = false))
    val dd = Vector(StatusEntry('D', 'D', "gone.scala", None, ignored = false))
    assertEquals(
      WorktreeSafetyClassifier.classify(branch, head, Right(branch), Right(head), aa),
      WorktreeSafety.UnexpectedDivergence
    )
    assertEquals(
      WorktreeSafetyClassifier.classify(branch, head, Right(branch), Right(head), dd),
      WorktreeSafety.UnexpectedDivergence
    )

  test("a conflicted row poisons an otherwise driver-uncommitted set"):
    val entries = Vector(modified("ok.scala"), StatusEntry('U', 'D', "conflict.scala", None, ignored = false))
    val r = WorktreeSafetyClassifier.classify(branch, head, Right(branch), Right(head), entries)
    assertEquals(r, WorktreeSafety.UnexpectedDivergence)

  // --- IO classifyWorktree over FakeGitClient ---

  test("classifyWorktree — clean tree → Right(Clean)"):
    val git = FakeGitClient.builder
      .currentBranch(branch)
      .currentSha(head)
      .status(Vector.empty)
      .build
    WorktreeSafetyClassifier.classifyWorktree(git, branch, head).map { r =>
      assertEquals(r, Right(WorktreeSafety.Clean))
    }

  test("classifyWorktree — driver edits → Right(DriverUncommittedOnly)"):
    val git = FakeGitClient.builder
      .currentBranch(branch)
      .currentSha(head)
      .status(Vector(modified("src/Main.scala")))
      .build
    WorktreeSafetyClassifier.classifyWorktree(git, branch, head).map { r =>
      assertEquals(r, Right(WorktreeSafety.DriverUncommittedOnly))
    }

  test("classifyWorktree — wrong branch → Right(UnexpectedDivergence)"):
    val git = FakeGitClient.builder
      .currentBranch(BranchName("main"))
      .currentSha(head)
      .status(Vector.empty)
      .build
    WorktreeSafetyClassifier.classifyWorktree(git, branch, head).map { r =>
      assertEquals(r, Right(WorktreeSafety.UnexpectedDivergence))
    }

  test("classifyWorktree — detached HEAD (currentBranch errors) → Right(UnexpectedDivergence)"):
    val git = FakeGitClient.builder
      .currentBranch(Left(GitError.ParseFailure("currentBranch", new RuntimeException("empty"), "")))
      .currentSha(head)
      .status(Vector(modified("x")))
      .build
    WorktreeSafetyClassifier.classifyWorktree(git, branch, head).map { r =>
      assertEquals(r, Right(WorktreeSafety.UnexpectedDivergence))
    }

  test("classifyWorktree — status read failure propagates as Left"):
    val err = GitError.Transient(128, "fatal: not a git repository")
    val git = FakeGitClient.builder
      .currentBranch(branch)
      .currentSha(head)
      .status(Left(err))
      .build
    WorktreeSafetyClassifier.classifyWorktree(git, branch, head).map { r =>
      assertEquals(r, Left(err))
    }
