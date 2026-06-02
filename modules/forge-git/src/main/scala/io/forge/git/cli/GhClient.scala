package io.forge.git.cli

import cats.effect.IO
import io.forge.core.{BranchName, PrNumber}

/** §9 `gh` surface: the minimum slice-3 needs to build `BranchManager.createPr` / `updatePrBranch` and `PRWatcher`'s
  * polling loop. Provider-neutral — every method returns `IO[Either[GhError, A]]`; the trait makes no claim about which
  * implementation hits the network. `RealGhClient` shells out via `os.proc.call`; `FakeGhClient` (test fixtures, PR-A
  * A4) returns canned `Either`s.
  *
  * v1 talks to CLIs only (v1.2 §3.3 / §22); there is no raw-HTTP path.
  */
trait GhClient:

  /** `gh pr view <pr> --json <fields>` — the workhorse for `PRWatcher`. Returns the parsed JSON envelope so the caller
    * (`PrSnapshotDecoder`, PR-B) can pull fields without re-running `gh`.
    */
  def prView(pr: PrNumber, fields: Vector[String]): IO[Either[GhError, ujson.Value]]

  /** `gh pr create --title <t> --body <b> --base <b> --head <h>`. `gh` writes the PR URL to stdout; the implementation
    * parses the trailing `/pull/<n>` digits with a pinned regex. Per `design-rationale.md` BM8 (and S3-6's correction
    * of the BM8 wording), `gh pr create` has no `--json` flag, so URL parsing is the only contract.
    */
  def prCreate(
      title: String,
      body: String,
      base: BranchName,
      head: BranchName
  ): IO[Either[GhError, PrNumber]]

  /** `gh pr update-branch <pr>` — used by §9 base-freshness `autoUpdate: true` path (BM2). */
  def prUpdateBranch(pr: PrNumber): IO[Either[GhError, Unit]]

  /** `gh pr diff <pr>` — placeholder for Slice 4's reviewer-asset PR (R1 owns the diff). No Slice-3 caller; lives on
    * the trait so `FakeGhClient` can stub it for completeness.
    */
  def prDiff(pr: PrNumber): IO[Either[GhError, String]]

  /** `gh api repos/{owner}/{repo}/branches/<base>/protection/required_status_checks`. `None` when the repo is
    * unprotected (404) or the caller lacks `admin:repo`; `Some(json)` on hit. The 401/403 vs 404 distinction is
    * available via the `GhError` envelope when the caller cares.
    */
  def apiBranchProtection(base: BranchName): IO[Either[GhError, Option[ujson.Value]]]

  /** `gh pr checks <pr>` — the human-readable check table (one row per check: name, state, elapsed, link). The fix-up
    * flow (§11.6) writes it into `pieces/<p>.failures.md` so the fix-up driver knows which CI checks failed instead of
    * running blind. Returns the raw text. NOTE: `gh pr checks` exits non-zero when checks are failing/pending — exactly
    * when Forge calls it — so the implementation captures stdout regardless of exit code (not a `GhError`).
    */
  def prChecks(pr: PrNumber): IO[Either[GhError, String]]

  /** `gh pr list --head <head> --state open --json number` — the open PR whose head branch is `head`, or `None` when
    * none exists. Used to make the §11.4 commit→push→createPr flow idempotent: a re-run after a post-settle crash (the
    * driver settled, the orchestrator already opened the piece PR, then crashed before the `PrOpened` transition
    * persisted) detects the already-open PR and returns its number instead of failing `prCreate` ("a pull request
    * already exists for branch …"). `gh pr list` exits 0 with `[]` when there is no match, so the empty list is a clean
    * `Right(None)`, not an error.
    */
  def prForBranch(head: BranchName): IO[Either[GhError, Option[PrNumber]]]

  /** Runs gh with the run-view log-failed flags: the raw log of just the failed steps of an Actions run. Phase 3 §8.2
    * pipes this into the FailureClassifier so it sees the real scalafmt error line rather than the gh pr checks
    * summary; see dogfood number four. It is also written into the piece failures.md for a DriverFixup. The runId is
    * the actions run id segment off the failing check's detailsUrl; see io.forge.core.pr.CheckResult.runId. The log
    * lines are tab-prefixed and carry ANSI colour codes that GhClient.stripAnsi cleans.
    */
  def runViewLogFailed(runId: String): IO[Either[GhError, String]]

object GhClient:
  // ANSI CSI escape sequences: ESC, then a bracket, then params, then a final byte. Covers both the SGR colour codes
  // and the cursor or erase codes that gh log lines carry.
  private val AnsiPattern: scala.util.matching.Regex = "\\u001b\\[[0-9;?]*[ -/]*[@-~]".r

  /** Byte-order mark gh sometimes prefixes to a log line; dropped alongside the ANSI escapes. */
  private val Bom: String = "﻿"

  /** Strip ANSI SGR and cursor escape sequences from a captured log so the classifier markers and the failures.md
    * driver context read as plain text.
    */
  def stripAnsi(s: String): String = AnsiPattern.replaceAllIn(s, "").replace(Bom, "")
