package io.forge.specs

import cats.effect.unsafe.implicits.global

/** Shared fixtures for the five Task 1.4.5 E6 `ChangeCollector*Suite`s. `classify` is pure (no filesystem / git
  * access), so the tests need no temp dir — `repoRoot` is a synthetic absolute path and [[FileChange]]s are built
  * relative to it.
  */
trait ChangeCollectorSupport:

  protected val repoRoot: os.Path = os.Path("/repo")
  protected val collector: DefaultChangeCollector = new DefaultChangeCollector

  protected def classify(changes: Vector[FileChange], config: StagingConfig): Classification =
    collector.classify(repoRoot, changes, config).unsafeRunSync()

  /** A change at `rel` (a `/`-separated repo-relative path). */
  protected def at(
      rel: String,
      kind: FileChangeKind = FileChangeKind.Added,
      ignored: Boolean = false
  ): FileChange =
    FileChange(repoRoot / os.RelPath(rel), kind, ignored)

  /** A change at an absolute path *outside* `repoRoot`. */
  protected def outside(absolute: String, kind: FileChangeKind = FileChangeKind.Modified): FileChange =
    FileChange(os.Path(absolute), kind)
