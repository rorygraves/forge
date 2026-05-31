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
