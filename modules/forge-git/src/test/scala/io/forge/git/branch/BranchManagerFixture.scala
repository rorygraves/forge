package io.forge.git.branch

import cats.effect.{Clock, IO}
import io.forge.core.{BranchName, FeatureId, Mode}
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.git.branch.protection.{BranchProtectionCache, InMemoryBranchProtectionCache}
import io.forge.git.cli.fake.{FakeGhClient, FakeGitClient}

/** PR-C C7 — shared scaffolding for the `BranchManager…Suite` tests. Constructs a [[RealBranchManager]] over fakes with
  * a fresh in-memory cache; per-suite `git` / `gh` stubs are supplied by the caller.
  */
object BranchManagerFixture:

  /** Build a fresh [[RealBranchManager]] for one test. The cache resource is created eagerly inside the returned IO so
    * each test has an independent cache instance.
    */
  def manager(
      git: FakeGitClient,
      gh: FakeGhClient,
      cache: BranchProtectionCache
  ): RealBranchManager =
    new RealBranchManager(git, gh, cache, Clock[IO])

  def freshCache: IO[BranchProtectionCache] = InMemoryBranchProtectionCache().map(c => c: BranchProtectionCache)

  /** Minimal manifest for preflight tests: one pending piece p1, no design PR. */
  def sampleManifest(
      feature: FeatureId,
      branchPrefix: String = "forge",
      base: BranchName = BranchName("main")
  ): Manifest =
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = feature,
      title = "sample feature",
      baseBranch = base,
      branchPrefix = branchPrefix,
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = Vector(
        Piece(
          id = io.forge.core.PieceId("p1"),
          order = 1,
          title = "piece one",
          summary = "do the thing",
          specPath = ".forge/specs/sample/pieces/p1.md",
          acceptanceHash = "sha256:abc",
          status = PieceStatus.Pending,
          baseSha = None,
          prNumber = None,
          mergeCommit = None,
          mergedAt = None,
          attempts = 0
        )
      )
    )
