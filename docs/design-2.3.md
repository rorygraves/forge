# design-2.3 — Slice 3 implementation plan

> **Maps to:** [`roadmap.md`](roadmap.md) §2.3 (Phase 1 / Slice 3 —
> BranchManager, PRWatcher, ProcessLock, SessionMonitor) and
> [`forge-design-1.2.md`](forge-design-1.2.md) §17 slice 3 deliverables.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation
> plans"): every uncomplete roadmap section gets a `design-<section>.md`
> companion. The roadmap stays terse — direction and exit criteria — and
> this file owns the per-task breakdown. Items get ticked off here as they
> land; the roadmap section gets ticked off only after a code review on
> the section as a whole.
>
> **Status:** 🟢 open — 2026-05-26. Slice 2 closed earlier the same day
> (see [`design-2.2.md`](design-2.2.md) for the audit trail). Slice 3
> opens with PR-A as the entry point. v1.3 carry-forwards inherited from
> Slices 1–2 (**C14**, **C15**, **S2-1** through **S2-10**) live in
> [`design-rationale.md`](design-rationale.md) and
> [`roadmap.md`](roadmap.md) §7.2; §4 below walks them again at section
> closure.

## 0. Exit criterion for Slice 3

Roadmap §2.3: `forge-git` ships `BranchManager` + `PRWatcher`;
`forge-app` ships `ProcessLock` + `SessionMonitor`. Together they let
the Slice-4 orchestrator produce every `FsmEvent` (Slice 2 PR-B B4 /
**S2-2**) that the FSM expects from the outside world. Concretely,
this slice is done when:

1. `forge-git` exposes:
   - `GhClient` / `GitClient` traits + concrete `os-lib`-backed impls.
     One-shot `gh` / `git` subprocess invocation with typed `GhError` /
     `GitError` ADTs (rate-limit, not-found, auth, transient,
     parse-failure). v1 talks to CLIs only (§3.3, §22) — no raw HTTP.
   - `PrSnapshotDecoder.decode(json, baseline, botLogin):
     Either[DecodeError, DecodedSnapshot]` — provider-neutral
     decode of `gh pr view --json …` output into a
     `DecodedSnapshot(snapshot: PrSnapshot, headSha: Sha)`. The
     `PrSnapshot` half lands in `forge-core` per Slice-2 **S2-4**
     (see
     `modules/forge-core/src/main/scala/io/forge/core/pr/PrSnapshot.scala`);
     `headSha` lives outside the snapshot because v1.2 §6
     `PrSnapshot` doesn't carry it and `BranchManager.baseFreshness`
     needs it parallel-fielded.
     The decoder owns the **`mergeStateStatus` trap** per
     design-rationale CI6 (merge is detected from `state == "MERGED"`
     + non-null `mergedAt`, never from `mergeStateStatus`) and the
     "new since baseline" filter per RL2.
   - `BranchManager` per v1.2 §9 — `preflight`, `syncBase`,
     `createDesignBranch`, `createPieceBranch`, `baseFreshness`,
     `pushCurrentBranch` (with `forceWithLease`), `createPr`,
     `updatePrBranch`, `tagSnapshot`, `pushTag`, `deleteRemoteTag`.
     Branch-name derivation per BM7 (`<branchPrefix>/<feature>/(design|<piece>)`).
     Force-push-with-lease rejection surfaces a typed
     `ForceLeaseRejected` outcome for the orchestrator (Slice 4) to
     route into `NeedsHumanIntervention(..., ReopenDesign(prNumber))`
     per §11.3 step 5.
   - `BranchProtectionCache` — in-memory, keyed by
     `(featureId, baseBranch, cacheEpoch)` per §8.1 / CI5; TTL default
     1h (`config.github.branchProtectionTtlSec`). No on-disk
     persistence in Slice 3 (process-local).
   - `PRWatcher` per v1.2 §9 — `watch(pr: PrNumber):
     Stream[IO, PollResult]`. Polls `gh pr view --json
     state,statusCheckRollup,reviews,reviewDecision,mergeable,mergeStateStatus,comments,commits,mergedAt,mergeCommit`
     every `pollIntervalMs` (default 30s). Rate-limit back-off honours
     `Retry-After` per RL1; surfaces transient back-off as
     `PollResult.RateLimited(retryAfter)` rather than failing the
     stream; surfaces persistent failures (auth, 404) as
     `PollResult.Failed(GhError)`.
2. `forge-app` exposes:
   - `ProcessLock` — `FileChannel.tryLock` on
     `paths.lockFile` + sibling `paths.lockMetadataFile` write per §13.
     `acquire(metadata): Resource[IO, LockAcquireResult]` returns
     `Acquired | Stale(staleMetadata) | Held(otherMetadata)` per BM4 /
     BM5. The Resource finalizer drops the metadata file (clean exit)
     but leaves stale-detection to the next start-up.
   - `SessionMonitor` — watches the connector's
     `Stream[IO, AgentEvent]`, tracks per-session elapsed time and
     accumulated cost, and invokes `session.kill()` on per-turn cost
     breach (§12 check 3) or settle timeout. Produces typed
     `MonitorOutcome` events (`Settled`, `SettleTimeout`,
     `TurnBudgetBreached`, `BudgetBreached`) that the Slice-4
     orchestrator maps 1:1 onto `FsmEvent` variants. Slice 3 covers
     the four driver phases the FSM already handles
     (`SessionPhase.{Spec, DesignRevision, Implement, Fixup}`); the
     reviewer/refine phase coverage is **S2-8**'s Slice-4 decision and
     stays out of scope here.
3. Unit-level coverage matches roadmap §2.3's
   "fake-`gh` unit coverage" bar — the bulk of decoder edge cases
   exercises in `forge-git`'s test scope against fixture JSON files,
   not against a real `gh` binary. Concretely:
   - `PrSnapshotDecoder` exercises every `PrState` (`OPEN | CLOSED |
     MERGED`), every `CheckState` / `CheckConclusion` variant from the
     `PrSnapshot.scala` enums, every `ReviewDecision`, mergeable /
     `mergeStateStatus` combinations including the CI6 trap, comment
     baseline filtering per RL2, and a documented "unknown enum value"
     failure mode.
   - `BranchManager` exercises every §9 rule (local-diverged refuse,
     stale-PR-base autoUpdate vs refuse, force-lease rejected,
     snapshot-tag retention pruning, BM6 piece-branch-mismatch on
     `--commit-human-fix`) against a `FakeGhClient` /
     `FakeGitClient`.
   - `PRWatcher` exercises rate-limit back-off (RL1), baseline filter
     (RL2), terminal merge detection (CI6), and transient-vs-permanent
     `GhError` routing against a fake.
   - `ProcessLock` exercises live-lock contention via a sibling JVM
     spawned by the test (cannot be faked — file-channel semantics are
     OS-level), plus the stale-metadata recovery paths.
   - `SessionMonitor` exercises elapsed-time / cost-cap behaviour
     against a fake `Stream[IO, AgentEvent]` and a controllable
     `Clock` (`cats.effect.testkit.TestControl`).
4. One **sacrificial-repo integration path** per roadmap §2.3 runs in
   `forge-it` against a real `gh` + `git` against a thrown-away repo:
   branch creation → push → PR creation → watcher polling → merge
   transitions observed. Opt-in via `FORGE_IT_GH_REPO=<owner>/<repo>`
   (default-off, mirroring Slice 1's `FORGE_IT_RUN_RELIABILITY` pattern
   per the "default-on test runtime <60s" feedback memory).
5. A code review on the section confirms (1)–(4) and that the §4
   carry-forward list — inherited (**C14**, **C15**, **S2-1** through
   **S2-10**) plus any new Slice-3 deviations — is durably handed off
   (PR-H H5); the `[~]` bullets in `roadmap.md` §2.3 flip to `[x]`.

## 1. Sub-PR breakdown

Eight numbered sub-PRs. The dependency graph is *mostly* linear
(`A → B → {C, D}` may parallelise once B lands; `E` and `F` are
independent of `forge-git` and can land at any time after `A`'s module
skeleton; `G` and `H` close the section). Slice 2's experience is that
linear is operationally simpler — §2 below names the safe
parallelisation points but doesn't promise to use them.

### 1.1 PR A — `forge-git` module skeleton + `GhClient` / `GitClient` foundations — ⏳ pending

`forge-git` currently has zero sources (`build.sbt` declares the
module; `modules/forge-git/src/` is empty). PR-A lays the foundation
every subsequent Slice-3 sub-PR depends on: typed CLI invocation
clients + the `GhError` / `GitError` channel.

The big subprocess-utility question — *do we lift Slice 1's
`forge-agents.Subprocess` into `forge-core`, depend on `forge-agents`
from `forge-git`, or invoke `gh` / `git` directly via `os-lib`?* —
gets settled in PR-A.

- [ ] **A1.** **Decide subprocess-utility ownership and document the
  decision as carry-forward S3-1.** Recommendation: `forge-git` uses
  `os-lib`'s `os.proc(...).call(cwd, env, check = false,
  stderr = os.Pipe)` directly for one-shot `gh` / `git`
  invocations. Rationale:
    - `gh pr view --json …` returns a one-shot JSON payload; no
      streaming. `os.proc.call` blocks until exit and captures stdout
      + stderr + exit code in one go.
    - `git fetch` / `git push` / `git tag` are likewise one-shot.
    - Lifting `Subprocess` to `forge-core` would add `fs2-core` +
      `fs2-io` deps to the lowest-level module (currently
      `cats-effect` + `upickle` + `os-lib`) — out of proportion for
      the use case.
    - Depending on `forge-agents` from `forge-git` reverses the
      module-layout intent (`forge-agents` owns CLI-of-Claude / CLI-of-Codex
      adapters; nothing in `forge-git` cares about agent traits).
  S3-1 records the decision in `design-rationale.md` so a future
  reader doesn't re-derive it from grep failures.
- [ ] **A2.** `io.forge.git.cli.GhClient` trait + concrete
  `RealGhClient(repoRoot: os.Path, env: Map[String, String] =
  Map.empty)`. Trait methods cover the Slice-3 surface; each method
  returns `IO[Either[GhError, A]]` for a typed `A`:
    - `prView(pr: PrNumber, fields: Vector[String]): IO[Either[GhError,
      ujson.Value]]` — the workhorse poll call.
    - `prCreate(title: String, body: String, base: BranchName,
      head: BranchName): IO[Either[GhError, PrNumber]]` — invokes
      `gh pr create --title <t> --body <b> --base <b> --head <h>`,
      which writes the PR URL to stdout (e.g.
      `https://github.com/<owner>/<repo>/pull/4291\n`). Parse the
      trailing segment with the pinned regex
      `^https?://[^/]+/[^/]+/[^/]+/pull/(\d+)\s*$`; on no-match,
      surface `ParseFailure("pr-create-url", raw)`. **Spec note
      (filed as S3-6):** `design-rationale.md` BM8 cites
      `gh pr create --json url -q .url`, but `gh pr create` has no
      `--json` flag (`gh pr view` is the JSON-shaped command). The
      stdout-URL form is the supported behaviour and the only
      contract `gh pr create` makes; PR-G files S3-6 against the
      design rationale.
    - `prUpdateBranch(pr: PrNumber): IO[Either[GhError, Unit]]` — for
      §9 base-freshness `autoUpdate: true` path.
    - `prDiff(pr: PrNumber): IO[Either[GhError, String]]` — placeholder
      for Slice 4's reviewer-asset PR (no Slice-3 caller, but lands
      here so the trait is complete enough for fake injection in
      `BranchManager` / `PRWatcher` tests; cheap to add).
    - `apiBranchProtection(base: BranchName): IO[Either[GhError,
      Option[ujson.Value]]]` — `gh api
      repos/{owner}/{repo}/branches/<base>/protection/required_status_checks`.
      `None` when the repo is unprotected (404) or the caller lacks
      `admin:repo`; `Some(json)` on hit.
  `GhError` is a sealed trait in `io.forge.git.cli`:
    - `RateLimited(retryAfter: Option[FiniteDuration], raw: String)` —
      403/429 with `X-RateLimit-Remaining: 0` or `Retry-After` header.
      `gh` surfaces these on stderr; A2's decoder maps them.
    - `NotFound(path: String)` — 404. Used by
      `apiBranchProtection` to distinguish "unprotected" from
      "unreachable".
    - `Unauthorized(message: String)` — 401/403 without rate-limit
      framing.
    - `Transient(exitCode: Int, stderr: String)` — non-zero exit with
      no rate-limit / auth / 404 framing. Caller retries.
    - `ParseFailure(stage: String, cause: Throwable, raw: String)` —
      `gh` returned 0 + a stdout payload that `ujson.read` (or
      `PrSnapshotDecoder`) rejected.
  Argv encoding: every method builds a `Vector[String]` argv and
  passes it through a single private `invoke(argv,
  stdinIfAny): IO[Either[GhError, String]]` helper that owns the
  rate-limit / 404 / transient classification. The classifier reads
  the exit code first, then scans stderr for the rate-limit framing
  `gh` emits (`HTTP 403: API rate limit exceeded` or
  `secondary rate limit`). One unit suite (`GhErrorClassifierSuite`)
  hands the classifier fixture stderr blobs from real `gh` output and
  asserts the correct variant; PR-G updates fixtures if `gh`'s wording
  changes against the integration `gh` version pin.
  Spec: v1.2 §3.3 (CLIs only), §9 (`gh pr view` / `gh api`
  invocations), design-rationale RL1.
- [ ] **A3.** `io.forge.git.cli.GitClient` trait + concrete
  `RealGitClient(repoRoot: os.Path)`. Methods cover the Slice-3
  surface; each returns `IO[Either[GitError, A]]`:
    - `currentBranch: IO[Either[GitError, BranchName]]` —
      `git branch --show-current`.
    - `currentSha: IO[Either[GitError, Sha]]` — `git rev-parse HEAD`.
    - `fetch(remote: String = "origin"): IO[Either[GitError, Unit]]` —
      `git fetch <remote>`.
    - `fastForwardBase(base: BranchName): IO[Either[GitError,
      FastForwardResult]]` — **safe regardless of current branch.**
      Algorithm:
        1. `git fetch origin <base>` — no working-tree side effects.
        2. Read `localSha = git rev-parse refs/heads/<base>` (may be
           missing on a freshly-cloned worktree — treat as
           "bootstrap"). Read `remoteSha = git rev-parse
           refs/remotes/origin/<base>` (must exist; missing is a
           `GitError.Transient` with the `gh-remote-missing` stage).
        3. If `localSha == remoteSha` → `AlreadyUpToDate(remoteSha)`.
        4. Else if `git merge-base --is-ancestor <localSha>
           <remoteSha>` returns 0 (local strictly behind remote) →
           fast-forward the local ref. Two sub-paths so we never
           merge `<base>` into the wrong checked-out branch:
             - `currentBranch == <base>` → `git merge --ff-only
               refs/remotes/origin/<base>`.
             - `currentBranch != <base>` → `git update-ref
               refs/heads/<base> <remoteSha>` (no working-tree
               side-effects; doesn't touch the index).
           Return `Updated(remoteSha)`.
        5. Else (local descendant or unrelated) → `LocallyDiverged(localSha,
           remoteSha)` — the BM1 "base branch diverged locally"
           trigger.
      `FastForwardResult` is `Updated(Sha) | AlreadyUpToDate(Sha) |
      LocallyDiverged(localSha, remoteSha)`. The explicit ancestor
      check at step 4 is what makes the safety story work — naively
      issuing `git merge --ff-only origin/<base>` from a feature
      branch would silently merge `<base>` into the feature branch
      (the bug the v0.7 reviewer caught).
    - `checkout(branch: BranchName, createFrom: Option[BranchName]):
      IO[Either[GitError, Unit]]` — `git checkout -B <branch>
      <createFrom?>`.
    - `push(branch: BranchName, force: Boolean = false,
      forceWithLease: Boolean = false): IO[Either[GitError, Unit]]` —
      `git push origin <branch> [--force-with-lease]`.
    - `tag(name: String, sha: Sha): IO[Either[GitError, Unit]]` —
      `git tag <name> <sha>`.
    - `pushTag(name: String): IO[Either[GitError, Unit]]` —
      `git push origin <name>`.
    - `deleteRemoteTag(name: String): IO[Either[GitError, Unit]]` —
      `git push origin :refs/tags/<name>`.
    - `isWorktreeClean: IO[Either[GitError, Boolean]]` — `git status
      --porcelain` empty / non-empty.
    - `branchExistsLocal(name: BranchName): IO[Either[GitError,
      Boolean]]` and `branchExistsRemote(name: BranchName):
      IO[Either[GitError, Boolean]]`.
  `GitError` is a sealed trait:
    - `NoUpstream(branch: BranchName)` — push to a branch with no
      upstream and no `--set-upstream`.
    - `ForceLeaseRejected(branch: BranchName, stderr: String)` — the
      explicit §11.3 step 5 case.
    - `FastForwardImpossible(local: Sha, remote: Sha)` — emitted by
      `fastForwardBase` when local has diverged.
    - `Transient(exitCode: Int, stderr: String)`.
    - `ParseFailure(stage: String, cause: Throwable, raw: String)`.
  PR-G's IT exercises the real `git` semantics; unit tests use
  `FakeGitClient` stubs (in-memory dictionary keyed by command shape).
  Spec: v1.2 §9, §11.3 step 5, design-rationale BM1.
- [ ] **A4.** `FakeGhClient` / `FakeGitClient` test fixtures under
  `modules/forge-git/src/test/scala/io/forge/git/cli/fake/`. Each
  exposes a builder API (`FakeGhClient.builder.prView(pr,
  responses).build`) so per-suite stubs are obvious at the call site.
  No dependency on the real `gh` / `git` binaries; suites that need
  the real CLI live in `forge-it` (PR-G).
- [ ] **A5.** Wire `design-2.3.md` into the parent docs (mirrors PR-A
  A5 in Slice 2):
    - `AGENTS.md` §"Active design-`<section>`.md files" — replace
      *(none currently open)* with the design-2.3.md pointer.
    - `CLAUDE.md` TL;DR "Active implementation plan" — replace
      *(none currently open)* with the design-2.3.md pointer; bump
      "Current state" to mark Slice 3 active.
    - `roadmap.md` §2.3 — add a 🟢 "Slice 3 open — 2026-05-26" status
      block pointing at `design-2.3.md` with PR-A as the entry point
      (mirroring Slice 2's open-section flavoured pattern).
- [ ] **A6.** PR-A landing checklist:
    - `sbt clean compile` clean under `-Xfatal-warnings`.
    - `sbt test` green; no regression on `forge-core` (358) or
      `forge-agents` (181) baselines. `forge-git` gains its first
      tests (target: 15–25 tests on the `GhError` classifier +
      `FakeGhClient` / `FakeGitClient` smoke).
    - `sbt scalafmtCheckAll` clean.
    - `sbt "project forge-it" test` clean (no `forge-it` source
      touched in PR-A; smoke check only).
    - `ForgePathsSuite`'s `os.walk` sweep still green (`forge-git`
      under `modules/` is now in scope; A3's `RealGitClient` /
      `RealGhClient` only refer to paths via `repoRoot` and `paths`
      arguments — no `".forge` literals).
    - This file's PR-A header flipped to "✅ landed" and a §3 status-log
      entry added.

### 1.2 PR B — `PrSnapshotDecoder` + `PollBaseline` — ⏳ pending

PR-B is the *decode-only* PR. Pure functions from `ujson.Value` →
`Either[DecodeError, DecodedSnapshot]`. The bulk of roadmap §2.3's
"fake-gh unit coverage" goal lands here.

- [ ] **B1.** `PollBaseline` case class in
  `io.forge.git.watcher`:
  ```scala
  final case class PollBaseline(
    lastSeenCommentId: Option[Long],
    lastSeenReviewId: Option[Long],
    lastSeenCheckRunIds: Set[Long]
  )
  ```
  Captured at PR creation per RL2 / §11.4 step 6. The orchestrator
  (Slice 4) owns persistence; Slice-3 stores the baseline alongside
  `Feature` (or in the manifest piece record — Slice 4 settles
  exactly where). The decoder consumes a baseline and filters
  comments / reviews to those with numeric id `>
  baseline.lastSeenCommentId`. **All GitHub id comparisons are
  numeric (`Long`), not lexicographic on stringified ids** — GitHub
  `databaseId` values are 64-bit integers and routinely cross the
  decimal-digit-count boundary (e.g. `"100" < "99"` under string
  compare). The wire JSON exposes `databaseId` as a JSON number;
  the decoder reads it via `json("databaseId").num.toLong`. Stored
  on disk as `Long` via uPickle's default codec.
- [ ] **B2.** `PrSnapshotDecoder.decode(json: ujson.Value, baseline:
  PollBaseline, botLogin: String): Either[DecodeError,
  DecodedSnapshot]` — the canonical decoder entrypoint.
  `DecodedSnapshot(snapshot: PrSnapshot, headSha: Sha)` is a small
  PR-B-introduced wrapper that pairs the `forge-core` `PrSnapshot`
  with the head-sha read from `commits[-1].oid`. `DecodedSnapshot`
  lives in `io.forge.git.watcher` (not `forge-core`) because v1.2
  §6 `PrSnapshot` deliberately doesn't carry `headSha`; PR-B respects
  that and surfaces it as a sibling field. `DecodeError` is a
  sealed trait local to `io.forge.git.watcher` with cases:
    - `MissingField(path: String)` — required field absent.
    - `UnknownEnumValue(field: String, observed: String,
      knownValues: Vector[String])` — `state`, `mergeable`,
      `reviewDecision`, `CheckState`, `CheckConclusion`. The known
      values surface in the error so a future GitHub addition is
      diagnosable without grepping enum source.
    - `MalformedShape(path: String, expected: String, observed:
      String)` — JSON shape mismatch (e.g. array where object
      expected).
  Decoded output is a `forge-core` `PrSnapshot`. The decoder does
  **not** mutate baseline state — that's the watcher's job (PR-D).
- [ ] **B3.** Field-by-field decode rules (each rule paired with a
  test under B5):
    - `state` → `PrState.fromString` (already in
      `PrSnapshot.scala`).
    - `mergedAt` → `Option[Instant]` (null-tolerant).
    - `mergeCommit.oid` → `Option[Sha]` (null-tolerant; the field is
      a JSON object `{oid: "abc"}` when present, `null` when not).
    - `mergeable` → `Option[Boolean]` derived from the wire string:
      `MERGEABLE` → `Some(true)`, `CONFLICTING` → `Some(false)`,
      `UNKNOWN` → `None`. Anything else → `UnknownEnumValue`.
    - `mergeStateStatus` → **dropped on the floor.** CI6 says merge
      detection lives in `state == "MERGED"` + `mergedAt`; the field
      never returns `"MERGED"`. PR-B includes a source-level comment
      at the decoder ref naming CI6 so a future reader doesn't try to
      "fix" the omission.
    - `reviewDecision` → `Option[ReviewDecision]` via
      `ReviewDecision.fromString`. Null + missing both decode as
      `None`.
    - `statusCheckRollup` → `CheckRollup`. Each entry decodes to a
      `CheckResult(name, state: CheckState, conclusion:
      Option[CheckConclusion])`. The decoder doesn't yet know which
      checks are "required" — that overlay is applied by
      `BranchManager` (PR-C) using the `BranchProtectionCache` lookup;
      PR-B emits everything under `observed`, leaves `required` as
      `Vector.empty`. PR-C `BranchManager.snapshotWithRequiredOverlay`
      promotes the named subset.
    - `comments` → filter to entries with `databaseId` (the monotonic
      `Long` id) greater than `baseline.lastSeenCommentId`; convert
      to `PrComment`. Skip Forge's own comments (`author.login ==
      config.github.botLogin`, default `"forge-bot"` — Slice 4 wires
      the actual login; PR-B accepts the login as a decoder arg).
    - `latestReviews` / `reviews` → folded into the same
      `unseenComments` vector for FSM purposes (the FSM cares about
      "new human signal"; the distinction between issue-comment and
      review-comment is audit-trail-only). Each review's `body` lands
      as a `PrComment` with `path = None` / `line = None`.
    - `commits[-1].oid` → populates `DecodedSnapshot.headSha`
      (alongside the `PrSnapshot` payload), so
      `BranchManager.baseFreshness` reads it without mutating the
      `forge-core` `PrSnapshot` ADT. Empty `commits` array →
      `DecodeError.MissingField("commits[-1].oid")`.
- [ ] **B4.** **Comment baseline filter is a pure function**
  (`Comments.unseen(rawComments, baseline)`) tested independently
  from `decode`. Three properties:
    - Numeric (`Long`) id ordering — explicitly assert
      `unseen(c"99", baseline = Some(100L)).isEmpty` and
      `unseen(c"100", baseline = Some(99L)).nonEmpty` so a future
      "let's stringify for serialization convenience" regression
      can't reintroduce the lexicographic ordering bug.
    - Empty baseline → every comment is unseen.
    - Baseline equal to last comment → empty unseen set.
- [ ] **B5.** Decoder test suite under
  `modules/forge-git/src/test/scala/io/forge/git/watcher/`. Fixture
  JSON files under
  `modules/forge-git/src/test/resources/gh-pr-view/`:
    - `open-no-checks.json` — fresh PR, no required-checks, no
      observed checks.
    - `open-checks-running.json` — required set populated, observed
      set running.
    - `open-checks-mixed.json` — one required green, one required
      failing, one observed neutral.
    - `open-changes-requested.json` — `reviewDecision:
      CHANGES_REQUESTED` with a body.
    - `open-mergeable-conflicting.json` — `mergeable: CONFLICTING`,
      `mergeStateStatus: DIRTY` — verifies CI6 (the decoder ignores
      the latter, doesn't treat it as terminal).
    - `closed-not-merged.json` — `state: CLOSED`, `mergedAt: null`,
      `mergeCommit: null`. Triggers §11.3 / §11.5 "PR closed without
      merge" downstream.
    - `merged.json` — `state: MERGED`, non-null `mergedAt` +
      `mergeCommit.oid`. The happy `Merged` event source.
    - `merged-stale-mergestate.json` — same as `merged.json` but with
      `mergeStateStatus: CLEAN` to verify the CI6 trap explicitly:
      *and* a malformed `mergeStateStatus: MERGED` fixture (which
      should still decode to `state.Merged = true` — that's the
      whole point of CI6).
    - `rate-limit-error.json` — not a `pr view` payload but an error
      shape `gh` emits when rate-limited (used by A2's classifier,
      cross-tested here).
    - Negative cases: `malformed-missing-state.json`,
      `malformed-unknown-check-state.json`,
      `malformed-shape.json` — each asserts a specific `DecodeError`
      variant.
  Suite target: 20–25 tests. This is roadmap §2.3's "fake-gh unit
  coverage" core; every other Slice-3 component sits on top of the
  decoded `PrSnapshot`, so getting the decoder taut here pays out
  through PR-C/D/G.

### 1.3 PR C — `BranchManager` + `BranchProtectionCache` — ⏳ pending

The §9 / §15 / §11.3 step 5 logic. Pure when given fake `GhClient` /
`GitClient` (no `IO`-shaped side-effects of its own).

- [ ] **C1.** `io.forge.git.branch.ForgeCommand` — sealed trait with one
  case per §15 row:
    - `New(featureId)`, `Spec(featureId)`, `Run(featureId)`,
      `ResumeAfterHumanPush(featureId, pieceId)`,
      `ResumeCommitHumanFix(featureId, pieceId)`,
      `ResumeRunFixup(featureId, pieceId)`, `Reconcile(featureId)`,
      `RefreshCache(featureId)`, `ReadOnly(StatusOrReplay)`,
      `UnlockForce`, `Abandon(featureId)`.
  Slice 3 owns only the BranchManager-relevant subset; Slice 4's CLI
  builds the rest from this enum.
- [ ] **C2.** `PreflightReport` case class with the §15 checks expressed
  as `Vector[PreflightCheck]` where each `PreflightCheck` is `Passed`
  or `Failed(reason, escapableViaForce: Boolean)`. Per BM3 / §15,
  `--force` available everywhere; usage logged as
  `harness.preflight_bypassed` by the orchestrator (Slice 4 — PR-C
  surfaces the bypassable bit on each check).
- [ ] **C3.** `BranchManager` trait per v1.2 §9. Method-by-method
  implementation sketch (each gets a unit suite under C7):
    - `preflight(command: ForgeCommand): IO[PreflightReport]` —
      consults `GitClient.isWorktreeClean`,
      `GitClient.currentBranch`, the `Feature` (loaded by the
      orchestrator and passed in), and the §15 table. Branch-match
      for `ResumeCommitHumanFix` per BM6 — compares
      `git branch --show-current` against `derivedPieceBranch(feature,
      piece)`; mismatch is a `Failed` check with a clear message.
    - `syncBase(base: BranchName): IO[Either[BranchError,
      BaseSnapshot]]` — delegates to `git.fastForwardBase(base)`,
      whose contract is safe regardless of the current branch
      (PR-A A3). Branch-manager-side mapping:
      `Updated(sha) | AlreadyUpToDate(sha)` →
      `Right(BaseSnapshot(base, sha))`; `LocallyDiverged(local,
      remote)` → `Left(BranchError.BaseDiverged(local, remote))`
      matching BM1. **syncBase does NOT check out `<base>`** — the
      current branch is preserved across the call. Branch creation
      callers (`createDesignBranch`, `createPieceBranch`) handle
      the subsequent `git checkout -B <new> <base>` themselves.
    - `createDesignBranch(feature: FeatureId): IO[Either[BranchError,
      BranchName]]` — derives `<branchPrefix>/<feature>/design`,
      checks out from base. No `baseSha` capture — design branches
      aren't recorded in manifest pieces.
    - `createPieceBranch(feature: FeatureId, piece: PieceId):
      IO[Either[BranchError, (BranchName, Sha)]]` — derives
      `<branchPrefix>/<feature>/<piece>`, checks out from base,
      returns the base SHA captured at branch creation per §9 /
      §11.4 step 1. **The orchestrator (Slice 4) atomically persists
      `manifest.pieces[i].baseSha = baseSha` AND `status =
      InProgress` before transitioning** — that ordering is **S2-5**'s
      Slice-4 obligation, not Slice 3's. PR-C surfaces a clear
      method contract docstring naming **S2-5** so Slice 4 has the
      anchor when it writes the writer-side test.
    - `baseFreshness(pr: PrNumber, expectedBaseSha: Sha):
      IO[Either[BranchError, BaseFreshness]]` — `gh.prView(pr,
      Vector("baseRefName", "baseRefOid"))`, compare oid with
      `expectedBaseSha`. Returns
      `BaseFreshness.UpToDate | Behind(expected, observed)`.
      The `autoUpdate: true` branch (§9 / BM2) calls
      `gh.prUpdateBranch(pr)` and *returns* `Updated` so the
      orchestrator can re-enter `PieceAwaitingCi`; `autoUpdate:
      false` returns `Behind` and lets the orchestrator surface
      `NeedsHumanIntervention("piece <p> PR is behind base",
      ResumeAfterHumanPush(...))`. BranchManager does **not** itself
      construct `NeedsHumanIntervention` — that's the FSM's vocabulary.
    - `pushCurrentBranch(forceWithLease: Boolean = false):
      IO[Either[BranchError, Unit]]` — `git.push(currentBranch,
      forceWithLease)`. Maps `GitError.ForceLeaseRejected` to
      `BranchError.ForceLeaseRejected(branch)` so §11.3 step 5's
      caller has a typed signal.
    - `createPr(title: String, body: String, base: BranchName):
      IO[Either[BranchError, PrNumber]]` — `gh.prCreate` per BM8.
    - `updatePrBranch(pr: PrNumber): IO[Either[BranchError, Unit]]` —
      thin wrapper over `gh.prUpdateBranch`.
    - `tagSnapshot(name: String, sha: Sha): IO[Either[BranchError,
      Unit]]` — `git.tag`. Snapshot tag retention per §11.3 step 4:
      caller passes the explicit name (`<branchPrefix>/_snapshots/<feature>/design-r<n>`);
      PR-C exposes a sibling helper `pruneSnapshotTags(feature,
      retention: Int): IO[Either[BranchError, Vector[String]]]` that
      lists `<branchPrefix>/_snapshots/<feature>/*` tags, keeps the
      `retention` newest (by round-number suffix), and deletes the
      rest. **Default-local-only** per §11.3 step 4 — only the
      `pushSnapshotTags: true` orchestrator path calls `pushTag` /
      `deleteRemoteTag`.
    - `pushTag(name: String): IO[Either[BranchError, Unit]]` and
      `deleteRemoteTag(name: String): IO[Either[BranchError, Unit]]`
      — thin wrappers per §11.3 step 4 push-and-prune.
- [ ] **C4.** Branch-name derivation per BM7 in
  `io.forge.git.branch.BranchNaming`:
    - `designBranch(prefix: String, feature: FeatureId): BranchName`
      → `<prefix>/<feature>/design`.
    - `pieceBranch(prefix: String, feature: FeatureId, piece: PieceId):
      BranchName` → `<prefix>/<feature>/<piece>`.
    - `snapshotTag(prefix: String, feature: FeatureId, kind: String,
      round: Int): String` → `<prefix>/_snapshots/<feature>/<kind>-r<round>`.
  Pure helpers; no `IO`. Slice-4 audit-snapshot wiring uses the same
  helpers.
- [ ] **C5.** `BranchProtectionCache` trait in
  `io.forge.git.branch.protection`:
  ```scala
  final case class CacheKey(feature: FeatureId, base: BranchName,
                            epoch: Long)
  final case class RequiredChecksOverlay(required: Set[String],
                                          fetchedAt: Instant)

  trait BranchProtectionCache:
    def get(key: CacheKey): IO[Option[RequiredChecksOverlay]]
    def put(key: CacheKey, overlay: RequiredChecksOverlay): IO[Unit]
    def invalidateEpoch(feature: FeatureId, base: BranchName,
                        belowEpoch: Long): IO[Unit]
  ```
  `InMemoryBranchProtectionCache(ttl: FiniteDuration = 1.hour,
  clock: Clock[IO] = ...)` — keyed map with TTL eviction on `get`.
  No on-disk persistence in Slice 3; epoch lives on `Feature` already
  (Slice 2 PR-B), so the orchestrator threads `feature.branchProtectionCacheEpoch`
  into every call site. Eviction triggers per CI5 / §8.1:
  - `forge resume` (any variant): orchestrator increments
    `feature.branchProtectionCacheEpoch`; subsequent `get` calls miss
    on the old key.
  - `forge refresh-cache`: same as above.
  - TTL expiry: cache lookup checks `now - fetchedAt > ttl` and
    returns `None`.
  Slice-3 scope: the cache type + impl. Slice 4 wires the epoch bump
  into `forge resume` and `forge refresh-cache`.
- [ ] **C6.** `BranchManager.requiredChecksOverlay(feature: FeatureId,
  base: BranchName, epoch: Long): IO[Either[BranchError,
  RequiredChecksOverlay]]` — cache `get` → on miss, call
  `gh.apiBranchProtection(base)`, parse the returned JSON (None →
  empty required set), build a `RequiredChecksOverlay`, `put` into
  the cache, return. On `gh.apiBranchProtection` →
  `RateLimited(retryAfter)`: surface `BranchError.RateLimited(retryAfter)`
  so the orchestrator can decide whether to back off the workflow.
  On `Unauthorized` (caller lacks `admin:repo`): surface
  `RequiredChecksOverlay(Set.empty, now)` — treat unprotected from
  the BranchManager's POV; PR-C documents this as a deliberate
  pragmatic choice (Slice 4 audit log records the
  unauthorized-fallback as `harness.protection_unauthorized`).
- [ ] **C7.** Unit suites under
  `modules/forge-git/src/test/scala/io/forge/git/branch/`:
    - `BranchNamingSuite` — pure helpers (5–8 tests).
    - `BranchManagerPreflightSuite` — every §15 row + the BM6
      mismatch case (8–12 tests against a fake clean/dirty `git
      status` + fake current-branch).
    - `BranchManagerSyncBaseSuite` — happy (on-base ff path),
      happy (off-base `update-ref` path — fake `GitClient`
      asserts the `merge --ff-only` form is **not** invoked when
      current branch isn't base), already-up-to-date no-op,
      diverged-local (BM1) (4–5 tests).
    - `BranchManagerCreatePieceBranchSuite` — base-sha capture, with
      a deliberate comment naming **S2-5** as the Slice-4
      writer-side gating (2–3 tests).
    - `BranchManagerBaseFreshnessSuite` — up-to-date, behind +
      autoUpdate, behind without autoUpdate (3 tests).
    - `BranchManagerForcePushSuite` — happy force-with-lease, force-lease
      rejected → `BranchError.ForceLeaseRejected` (3 tests).
    - `BranchManagerCreatePrSuite` — happy, transient retry, parse
      failure on missing url (3 tests).
    - `BranchManagerSnapshotTagSuite` — tag, push, retention prune
      keep-last-3 (3–5 tests).
    - `BranchProtectionCacheSuite` — cache miss → fetch → cache hit;
      TTL expiry; epoch-bump invalidation; rate-limited fetch surfaces
      cleanly (5–8 tests).
  Suite target: 35–50 tests across PR-C.

### 1.4 PR D — `PRWatcher` (polling + rate-limit + baseline) — ⏳ pending

`fs2.Stream`-shaped poller against the `GhClient` from PR-A and the
`PrSnapshotDecoder` from PR-B.

- [ ] **D1.** `io.forge.git.watcher.PRWatcher` trait per v1.2 §9. Two
  factory methods:
    - `watch(pr: PrNumber, baseline: Ref[IO, PollBaseline]):
      Stream[IO, PollResult]` — continuous polling. The `Ref` exists
      because the baseline mutates as new comments are seen; the
      orchestrator (Slice 4) owns the `Ref` and persists baseline
      checkpoints to manifest/log on its own cadence.
    - `pollOnce(pr: PrNumber, baseline: PollBaseline):
      IO[(PollBaseline, PollResult)]` — single poll, returned new
      baseline + outcome. Useful for the orchestrator's
      `forge resume` startup path where it wants one snapshot before
      entering the FSM loop.
  `PollResult` is a sealed trait:
    - `Snapshot(decoded: DecodedSnapshot)` — clean poll. Reuses the
      PR-B `DecodedSnapshot(snapshot, headSha)` shape verbatim so
      there's one canonical wire-decoded shape from gh JSON onward.
    - `RateLimited(retryAfter: Option[FiniteDuration])` — RL1
      back-off. The stream sleeps `retryAfter` (or
      `config.github.rateLimitBackoffMs` default 60000) and continues.
    - `Failed(error: GhError)` — non-rate-limit `GhError`. The stream
      surfaces and continues; the orchestrator decides whether to
      keep watching or escalate to `HarnessError`.
- [ ] **D2.** `RealPRWatcher(gh: GhClient, decoder: PrSnapshotDecoder,
  config: PRWatcherConfig, clock: Clock[IO])`. The polling loop is
  `Stream.repeatEval(pollOnce(pr, baseline.get).flatMap { case (new,
  result) => baseline.set(new).as(result) }).metered(pollInterval)`
  with `metered` adapting to `result match { case RateLimited(d) =>
  d.getOrElse(default); case _ => pollInterval }`. The `metered`
  hook above is sketch; PR-D settles the exact `Stream` shape from
  the call-site test fixtures.
- [ ] **D3.** Rate-limit recovery semantics (RL1 / §18
  `rateLimitBackoffMs`):
    - `RateLimited(retryAfter: Some(d))` → sleep `d`, then continue.
    - `RateLimited(retryAfter: None)` → sleep
      `config.github.rateLimitBackoffMs` (default 60s).
    - Three consecutive `RateLimited` results → emit one
      `Failed(GhError.RateLimited(...))` so the orchestrator can
      surface `harness.rate_limited` and decide whether to keep
      polling. The threshold (3) is a config knob with a sane default;
      PR-D surfaces it in `PRWatcherConfig`.
  Per RL1, the orchestrator's log writer (Slice 4) emits
  `harness.rate_limited` actions when it sees `PollResult.RateLimited`
  — PR-D doesn't write to the log itself.
- [ ] **D4.** `PRWatcherConfig` (case class with defaults, no on-disk
  binding in Slice 3):
  ```scala
  final case class PRWatcherConfig(
    pollInterval: FiniteDuration = 30.seconds,
    rateLimitBackoff: FiniteDuration = 60.seconds,
    consecutiveRateLimitsBeforeFailing: Int = 3,
    botLogin: String = "forge-bot",
    requestedFields: Vector[String] = PRWatcher.DefaultFields
  )
  ```
  `DefaultFields` is the `gh pr view --json` field list named in
  v1.2 §9: `state, statusCheckRollup, reviews, reviewDecision,
  mergeable, mergeStateStatus, comments, commits, mergedAt,
  mergeCommit`.
- [ ] **D5.** Unit suites under
  `modules/forge-git/src/test/scala/io/forge/git/watcher/`:
    - `PRWatcherBasicSuite` — fake `GhClient` returns a fixed
      `prView` sequence; assert the resulting `Stream` emits one
      `Snapshot` per poll, baseline `Ref` mutates correctly between
      polls (5–8 tests).
    - `PRWatcherRateLimitSuite` — fake yields `RateLimited(Some(d))`
      → assert back-off `d`; yields three consecutive without
      `retryAfter` → assert `Failed` after the threshold; combines
      `TestControl` from `munit-cats-effect` for deterministic
      clocking (5–8 tests).
    - `PRWatcherBaselineSuite` — feed the same JSON twice and assert
      the second pass sees zero unseen comments (RL2 contract) (3–5
      tests).
    - `PRWatcherMergedDetectionSuite` — fixture sequence Open → Open
      → Merged; assert the third emits a `Snapshot` with `state =
      Merged` + non-null `mergedAt` and `mergeCommit` — i.e. the
      `forge-core.FsmEvent.Merged` ingredients without ever consulting
      `mergeStateStatus` (CI6) (3–5 tests).

### 1.5 PR E — `forge-app` skeleton + `ProcessLock` — ⏳ pending

`forge-app` currently has zero sources too. PR-E lays its skeleton
and ships the file-channel lock per §13.

- [ ] **E1.** Module skeleton under
  `modules/forge-app/src/main/scala/io/forge/app/`. No `main` entry
  point yet — that's Slice 4. PR-E just creates the package
  structure (`io.forge.app.lock`, `io.forge.app.monitor`).
- [ ] **E2.** `ProcessLock` trait in `io.forge.app.lock`:
  ```scala
  final case class LockMetadata(
    pid: Long,
    hostname: String,
    startedAt: Instant,
    command: String,
    feature: Option[FeatureId]
  ) derives ReadWriter

  sealed trait LockAcquireResult
  object LockAcquireResult:
    case object Acquired extends LockAcquireResult
    final case class Stale(staleMetadata: LockMetadata)
      extends LockAcquireResult
    final case class Held(otherMetadata: Option[LockMetadata])
      extends LockAcquireResult

  trait ProcessLock:
    def acquire(metadata: LockMetadata,
                acceptStale: Boolean): Resource[IO, LockAcquireResult]
    def forceRelease: IO[ForceReleaseResult]

  enum ForceReleaseResult:
    case Released
    case LiveHolderRefused(metadata: Option[LockMetadata])
    case NoLockPresent
  ```
  `acceptStale: Boolean` mirrors the §13 `--yes` / `FORGE_AUTO_UNLOCK_STALE`
  CLI behaviour: when `true`, `Stale` is silently upgraded to
  `Acquired` (after rewriting the metadata); when `false`, `Stale` is
  surfaced and the CLI prompts (TUI: BM5). The `Held` case includes
  `otherMetadata: Option[LockMetadata]` because `.lock.json` may be
  absent or unparseable while the OS lock is held.
- [ ] **E3.** `FileProcessLock(paths: ForgePaths)` implementation:
    - On `acquire`:
      1. Ensure `paths.lockFile.parent` exists (`os.makeDir.all`).
      2. Open `paths.lockFile` via `FileChannel.open(... CREATE,
         WRITE, READ)`.
      3. `tryLock()` on the channel:
         - `null` (lock held by another process) → read sibling
           `paths.lockMetadataFile` (best-effort decode), return
           `Held(otherMetadata)`.
         - non-null → check sibling `paths.lockMetadataFile`:
           - absent → write current metadata, return `Acquired`.
           - present + valid + the PID matches our own → idempotent
             re-acquire (Slice 4 may call inside a Resource scope;
             defensive against double-acquire), return `Acquired`.
           - present + valid + PID *doesn't* match → `acceptStale`
             chooses: `true` → overwrite metadata + return
             `Acquired`; `false` → return `Stale(staleMetadata)`.
           - present + unparseable → return `Stale(LockMetadata.unknown)`
             so the orchestrator prompts.
      4. `Resource.release`: close the channel (drops OS lock) +
         delete sibling `paths.lockMetadataFile` on clean release
         only (so a `JVM.halt`-style hard crash leaves the metadata
         in place for next-startup stale detection).
    - `forceRelease`: try `tryLock` on a *separate* channel. Live
      lock → `LiveHolderRefused`. No live lock → read+delete
      metadata, return `Released`. No metadata + no live lock →
      `NoLockPresent`.
  Spec: v1.2 §13, design-rationale BM4 / BM5.
- [ ] **E4.** `FileProcessLockSuite` under
  `modules/forge-app/src/test/scala/io/forge/app/lock/` — **same-JVM
  unit cases only**. Live cross-process contention (which exercises
  the OS-level `FileChannel` lock semantics that no fake / no
  same-JVM dance can reproduce) lives in `forge-it` per PR-G G3 —
  the
  [I/O contracts need integration tests](file:///Users/rory.graves/.claude/projects/-Users-rory-graves-workspace-home-forge/memory/feedback-io-integration-tests.md)
  memory points at exactly this split: same-JVM tests can confirm
  the metadata/stat machinery, but only a real second process can
  confirm `tryLock` blocking semantics. Per the
  [test runtime cost is design](file:///Users/rory.graves/.claude/projects/-Users-rory-graves-workspace-home-forge/memory/feedback-test-runtime-cost.md)
  memory, keeping cross-JVM in `forge-it` (which is already opt-in)
  preserves the default-on `<60s` budget for `forge-app/test`.
  Same-JVM coverage in E4:
    - Metadata `ReadWriter` round-trip (write + read +
      `assertEquals`) (1 test).
    - First-ever acquire on a missing lock file: file is created,
      metadata written, `Acquired` returned (1 test).
    - Idempotent re-acquire: same JVM holds the lock via Resource
      scope A; nested Resource scope B detects matching PID + returns
      `Acquired` without disturbing the OS lock (1 test).
    - Stale metadata + missing live lock: synthesise a metadata file
      from a "different PID" (e.g. PID `1` on macOS / Linux is
      always live but isn't us); call `acquire(_, acceptStale =
      false)` → `Stale(metadata)`; call `acquire(_, acceptStale =
      true)` → `Acquired` with metadata rewritten (2 tests).
    - Unparseable metadata: write garbage to `.lock.json`, acquire
      → `Stale(LockMetadata.unknown)` (1 test).
    - Parent-dir creation: `paths.lockFile.parent` absent → `acquire`
      creates it (1 test).
    - Clean release: Resource exits → `.lock.json` removed, OS lock
      released (1 test).
    - `forceRelease`: no live lock + no metadata → `NoLockPresent`
      (1 test). No live lock + stale metadata → `Released` and
      metadata removed (1 test).
  Suite target: 10–12 tests, all <100ms per test.

  Cross-JVM coverage (live `Held(_)` contention, `forceRelease`
  live-refusal, sibling-JVM crash-stale path) is exclusively in
  PR-G G3's `ProcessLockMultiJvmSuite` under `forge-it`, opt-in
  via env var so `sbt "project forge-it" test` smoke runs don't
  pay the per-test JVM spawn cost.

### 1.6 PR F — `SessionMonitor` — ⏳ pending

The cost-cap / settle-timeout enforcer per §12 / §7.9. Lives in
`forge-app` because it bridges `forge-agents` (`AgentEvent` stream,
`StreamingSession.kill()`) and `forge-core` (`FsmEvent` it emits).

- [ ] **F1.** `io.forge.app.monitor.MonitorOutcome` — output ADT
  that maps **structurally** (not always 1:1) to a Slice-2
  `FsmEvent` variant. The MonitorOutcome carries richer typed
  cost data than the corresponding FsmEvent; the Slice-4
  orchestrator flattens the rich payload into the
  `FsmEvent.{TurnBudgetBreached, BudgetBreached}.message: String`
  field at the conversion point (e.g.
  `s"turn cost \$$turnUsd exceeded cap \$$cap"`). Keeping the rich
  data here means `SessionMonitor`'s own assertions / logs
  / future Slice-4 audit-log entries don't need a re-parse.
  ```scala
  sealed trait MonitorOutcome
  object MonitorOutcome:
    /** → FsmEvent.Settled(phase, outcome) verbatim. */
    final case class Settled(phase: SessionPhase,
                              outcome: SettleOutcome)
      extends MonitorOutcome

    /** → FsmEvent.SettleTimeout(phase, reason) verbatim. */
    final case class SettleTimeout(phase: SessionPhase,
                                    reason: String)
      extends MonitorOutcome

    /** → FsmEvent.TurnBudgetBreached(phase, message). Slice-4
      * formats `turnUsd` + `capUsd` into the message string. */
    final case class TurnBudgetBreached(phase: SessionPhase,
                                         turnUsd: BigDecimal,
                                         capUsd: BigDecimal)
      extends MonitorOutcome

    /** → FsmEvent.BudgetBreached(scope, message). Slice-4
      * formats `totals` + `capUsd` into the message string. */
    final case class BudgetBreached(scope: BudgetScope,
                                     totals: CostTotals,
                                     capUsd: BigDecimal)
      extends MonitorOutcome
  ```
  Imports from `forge-core`: `SessionPhase`, `SettleOutcome`,
  `BudgetScope` (Slice-2 enums) and `CostTotals` (Slice-2 cost
  type, `BigDecimal`-backed). All four are pre-existing —
  SessionMonitor doesn't drag the FSM itself into `forge-app`.
  **Number type is `BigDecimal`**, not `Double`, matching
  `Cost.usd` / `CostTotals.*` in
  `modules/forge-core/src/main/scala/io/forge/core/cost/Cost.scala`.
- [ ] **F2.** `SessionMonitor` trait:
  ```scala
  final case class SessionLimits(
    settleTimeout: FiniteDuration,
    maxTurnCostUsd: BigDecimal,
    maxPieceCostUsd: Option[BigDecimal],
    maxFeatureCostUsd: Option[BigDecimal]
  )

  trait SessionMonitor:
    def monitor(
      phase: SessionPhase,
      session: StreamingSession,
      events: Stream[IO, AgentEvent],
      limits: SessionLimits,
      runningTotals: Ref[IO, CostTotals]
    ): IO[MonitorOutcome]
  ```
  All cost fields are `BigDecimal` to match Slice-2's `Cost.usd`
  + `CostTotals` shape (`modules/forge-core/.../cost/Cost.scala`).
  Slice-4 config parsing converts the JSON `maxTurnCostUsd: 2.00`
  via `BigDecimal(...)` — no precision-lossy `Double` round-trip
  in the budget path.
  Returns the first `MonitorOutcome` that fires. The orchestrator
  (Slice 4) keeps `runningTotals` across sessions, so per-piece /
  per-feature caps accumulate across multiple sessions. PR-F
  documents the contract: SessionMonitor **does not** mutate the
  cost totals after firing — the orchestrator's settle handler
  resets / advances them per `MonitorOutcome`.
- [ ] **F3.** Implementation sketch (`io.forge.app.monitor.RealSessionMonitor(clock:
  Clock[IO])`):
    - The settle clock starts at `monitor` entry. A `Deferred[IO,
      MonitorOutcome]` collects the first outcome.
    - A fiber races `IO.sleep(limits.settleTimeout)` against the
      events Stream completing cleanly. The sleep fires
      `SettleTimeout(phase, "settle timeout " + d + " expired")` →
      `session.kill()` → completes the Deferred.
    - The events stream is `.evalMap`-ed to consume each event:
      - `AgentEvent.CostUpdate(cost)` (single field — `cost: Cost`
        carries `provider`, `model`, `inputTokens`, `outputTokens`,
        `usd: BigDecimal` per `modules/forge-agents/src/main/scala/io/forge/agents/AgentEvent.scala`).
        `cost.usd` is the *delta* for that turn. Update
        `runningTotals.modify`:
        ```scala
        old.copy(
          feature = old.feature + cost.usd,
          piece   = old.piece   + cost.usd,
          turn    = old.turn    + cost.usd
        )
        ```
        Then check per-turn cap: if `new.turn >
        limits.maxTurnCostUsd` → `session.kill()` →
        `TurnBudgetBreached(phase, new.turn, limits.maxTurnCostUsd)`.
        Then check feature/piece caps: if `new.feature >
        limits.maxFeatureCostUsd.get` (and analogously for piece)
        → emit `BudgetBreached(scope, new, capUsd)` **without
        calling `session.kill()`** — §12 check 2 says "let current
        turn complete, no new spawns". The Slice-4 orchestrator
        refuses the next spawn.
        Per-turn-counter reset: the per-turn slot in `CostTotals.turn`
        is reset on `AgentEvent.UserMessage` (new turn boundary)
        and on `AgentEvent.Result` (end of turn). The orchestrator's
        `Ref[IO, CostTotals]` owner does the reset, not
        SessionMonitor — F3's evalMap reads `runningTotals` and
        treats `.turn` as authoritative. PR-F's contract docstring
        names this so Slice 4 has the boundary clear.
      - `AgentEvent.Result(success, _)` → emit
        `Settled(phase, if success then Clean else
        AdapterError("non-zero result"))` and complete.
    - On Deferred completion, return its value.
- [ ] **F4.** Phase scope per §17 slice 3:
  SessionMonitor covers the four driver phases — `Spec`,
  `DesignRevision`, `Implement`, `Fixup`. Reviewer (`reviewDesign` /
  `reviewPr`) and refinery (`refine`) are one-shot adapter calls
  that don't expose a `Stream[IO, AgentEvent]` to monitor; their
  wall-clock caps live in their respective adapter wrappers
  (Slice 4, reviewer-asset PR). PR-F's scaladoc cross-references
  **S2-8** to make the alignment explicit:
    > SessionMonitor emits `SettleTimeout(phase, _)` only for the
    > four `SessionPhase` variants that `Fsm.transition` currently
    > handles (Spec, DesignRevision, Implement, Fixup). Per
    > carry-forward **S2-8**, the FSM's silent no-op for the
    > reviewer/refine phases is intentional in Slice 2 and will be
    > resolved in Slice 4. Slice 3 chooses to *not* emit reviewer/refine
    > settle outcomes because there is no streaming-session driver
    > for them; the reviewer-asset PR in Slice 4 lands the matching
    > FSM handlers + the one-shot wall-clock cap wrapper together.
- [ ] **F5.** Unit suite under
  `modules/forge-app/src/test/scala/io/forge/app/monitor/`:
    - `SessionMonitorSettleSuite` — `Stream` completes cleanly →
      `Settled(Clean)`; non-zero `Result` → `Settled(AdapterError)`
      (3–4 tests).
    - `SessionMonitorTimeoutSuite` — Stream stalls; `TestControl`
      advances past `settleTimeout` → `SettleTimeout`,
      `session.kill()` was called (3 tests).
    - `SessionMonitorTurnCostSuite` — `CostUpdate(cost)` events
      bring `runningTotals.turn` over `maxTurnCostUsd` mid-turn →
      `TurnBudgetBreached(phase, turnUsd, capUsd)`,
      `session.kill()` was called (3 tests).
    - `SessionMonitorFeatureCostSuite` — `CostUpdate(cost)` brings
      `runningTotals.feature` over `maxFeatureCostUsd` →
      `BudgetBreached(BudgetScope.Feature, totals, capUsd)`,
      `session.kill()` was **not** called (§12 check 2) (3 tests).
    - `SessionMonitorPieceCostSuite` — same shape against
      `maxPieceCostUsd` → `BudgetBreached(BudgetScope.Piece(p),
      totals, capUsd)` (2 tests).
    - `SessionMonitorPhaseCoverageSuite` — table-driven assertion
      that the four driver phases each route through the same
      logic; reviewer/refine phases are not legal inputs and a
      precondition check refuses them with an `IllegalArgumentException`
      (so a Slice-4 caller can't pass the wrong phase silently)
      (4 tests).
  Suite target: 18–25 tests. SessionMonitor uses
  `cats.effect.testkit.TestControl` for deterministic clocking;
  PR-F adds `munit-cats-effect`'s `TestControl` adapter to
  `forge-app/test`.
- [ ] **F6.** **Coherence-pass anchor.** Per the AGENTS.md
  "design-review coherence pass" feedback memory, the SessionMonitor
  contract has three interface boundaries that have to stay aligned:
  the connector trait (`StreamingSession.kill()` semantics), the FSM
  (`FsmEvent.TurnBudgetBreached` / `SettleTimeout` payload shapes),
  and the action log (the §19 `harness.session_killed` action whose
  `reason: "settle_timeout" | "turn_budget"` field is one of the few
  closed-string enums in the §19 schema). PR-F's scaladoc on
  `MonitorOutcome` lists each downstream consumer; if a Slice-4
  reviewer flags a contract drift, the consumer list anchors the
  audit.

### 1.7 PR G — Sacrificial-repo integration test — ⏳ pending

One end-to-end integration test in `forge-it` per roadmap §2.3, opt-in
via env var.

- [ ] **G1.** `FORGE_IT_GH_REPO` env var — `<owner>/<repo>` pointing
  at a throw-away repo the maintainer has push/PR rights on. README
  / `forge-it/README` documents the setup (a `.github` template the
  maintainer can fork into their personal namespace). PR-G suite
  skips with `assume(...)` when unset.
- [ ] **G2.** `BranchManagerIntegrationSuite` in `forge-it`. Single
  test, per roadmap §2.3's "branch, push, PR, observe watcher →
  merge transitions":
    1. Lock a unique branch name (`forge-it/slice3/<uuid>/<piece>`)
       so parallel runs don't collide.
    2. `BranchManager.syncBase("main")` → assert
       `BaseSnapshot(main, currentMainSha)`.
    3. `BranchManager.createPieceBranch(featureId, "p1")` → assert
       branch exists on disk and `baseSha` matches.
    4. Write a no-op file (`echo "$uuid" >> README.md`), commit
       (`git commit -m "feat: slice-3 IT"`), `BranchManager.pushCurrentBranch()`.
    5. `BranchManager.createPr(title, body, base = "main")` → assert
       `Right(prNumber)`.
    6. Run `PRWatcher.pollOnce(prNumber, PollBaseline.empty)` →
       assert `Snapshot(DecodedSnapshot(snap, _))` with
       `snap.state == Open`.
    7. **Merge via the test using `gh.prMerge` (PR-A's `GhClient`
       grows a `prMerge` method here — minimum surface for the IT
       to drive its own merge).** Spec note: §11 keeps "piece PRs
       are merged by the human" — `prMerge` is **not** wired into
       BranchManager's Slice-3 trait; it's an IT-only helper on
       `RealGhClient`. PR-G's scaladoc + test name make the test-only
       use explicit.
    8. `PRWatcher.pollOnce` again → assert
       `Snapshot(DecodedSnapshot(snap, _))` with
       `snap.state == Merged`, non-null `mergedAt` and `mergeCommit`.
    9. Cleanup: `BranchManager.deleteRemoteTag` is not in scope here;
       the branch lingers (the sacrificial repo accepts that — the
       README warns the operator that branches accumulate and can be
       pruned out-of-band).
  Test target: <2min run time on a warm `gh` cache. Opt-in keeps it
  out of the default `sbt "project forge-it" test` cadence.
- [ ] **G3.** `ProcessLockMultiJvmSuite` in `forge-it` — **the sole
  home for live-cross-process `FileProcessLock` coverage** (PR-E E4
  is same-JVM-only). Opt-in via `FORGE_IT_RUN_PROCLOCK=1`; PR-G
  settles whether the env var stays separate or folds into
  `FORGE_IT_RUN_RELIABILITY` based on the typical operator's
  workflow (the suite's per-test JVM spawn is what justifies a
  separate gate from `FORGE_IT_RUN_RELIABILITY`'s ≥20-sample bar).
  Three scenarios, each spawning a sibling JVM via
  `Subprocess.spawn` (which lives in `forge-agents` and `forge-it`
  already depends on it) running a small `LockHolderMain`
  helper packaged under `forge-it/src/test/scala`:
    - **Live `Held(_)` contention.** Spawn helper holding the lock;
      assert host JVM's `acquire(_, acceptStale = false)` returns
      `Held(holderMetadata)`; close sibling stdin → wait exit →
      retry → `Acquired`.
    - **Crash-stale recovery.** Spawn helper holding the lock;
      `destroyForcibly` the helper (mimics a hard crash that
      leaves `.lock.json` behind); call `acquire(_, acceptStale =
      false)` → `Stale(metadata)`; call again with `acceptStale =
      true` → `Acquired`.
    - **`forceRelease` live-refusal.** Spawn helper holding lock;
      host JVM's `forceRelease` → `LiveHolderRefused(metadata)`;
      sibling stdin close → exit → `forceRelease` →
      `Released`.
  *Rationale for the forge-app/forge-it split:* the cross-JVM dance
  is exactly the kind of environment-shaped flake the
  [I/O contracts need integration tests](file:///Users/rory.graves/.claude/projects/-Users-rory-graves-workspace-home-forge/memory/feedback-io-integration-tests.md)
  memory points at — same-JVM tests can confirm the metadata /
  stat machinery (PR-E E4), but only a real second process can
  confirm `FileChannel.tryLock`'s blocking semantics. Keeping the
  multi-JVM scenarios out of `forge-app/test` also preserves the
  default-on `<60s` runtime budget per the
  [test runtime cost is design](file:///Users/rory.graves/.claude/projects/-Users-rory-graves-workspace-home-forge/memory/feedback-test-runtime-cost.md)
  memory.

### 1.8 PR H — Close-out: code review + carry-forward walk — ⏳ pending

- [ ] **H1.** Code review on PR-A through PR-G as a section. The
  section-level review checklist (mirroring PR-G in Slice 2):
    - Does every §9 / §13 / §15 / §11.3 step 5 rule have at least
      one named test under PR-A..G?
    - Do all decoder edge cases (CI6, RL1, RL2, branch-protection
      cache invalidation, mergeable states) have a fixture under
      `gh-pr-view/`?
    - Does `BranchManager.createPieceBranch`'s docstring name
      **S2-5** so Slice 4's writer-side test has the anchor?
    - Does `SessionMonitor`'s scaladoc name **S2-8** so the Slice-4
      reviewer-asset PR has the alignment in plain sight?
    - Does **C14**'s FSM-side comment (placed in Slice 2 PR-C C5)
      still resolve to a Slice-4 orchestrator anchor — i.e. has any
      Slice-3 code accidentally shipped the orchestrator-side
      re-issuance? (Expected answer: no — Slice 3 ships components
      below the orchestrator, not the orchestrator itself.)
    - Section-level coherence pass per
      [feedback-design-review-coherence-pass](file:///Users/rory.graves/.claude/projects/-Users-rory-graves-workspace-home-forge/memory/feedback-design-review-coherence-pass.md):
      treat any review finding as a signal to re-walk the whole
      contract.
- [ ] **H2.** `roadmap.md` §2.3 bullet list rewritten with per-item
  `[x]` markers covering `BranchManager`, `PRWatcher`, `ProcessLock`,
  `SessionMonitor`, fake-`gh` unit coverage (decoder edge cases per
  CI6 / RL1 / RL2 / branch-protection cache / mergeable states), and
  the sacrificial-repo integration path. Status block flipped from
  🟢 "Slice 3 open" to ✅ "Slice 3 closed". Top status line bumped
  to draft v0.8 reflecting all three Phase-1 component slices
  closed.
- [ ] **H3.** `AGENTS.md` "Current state" gets a Slice 3 paragraph
  (mirroring the Slice 1 / Slice 2 paragraphs in length and detail);
  Slice 4 marked "next". "Active design-`<section>`.md files" list
  returns to *(none currently open)* with audit-trail pointers to
  Slices 1, 2, and 3.
- [ ] **H4.** `CLAUDE.md` TL;DR "Active implementation plan" →
  *(none currently open)* with audit-trail pointers; "Current state"
  rewritten covering Slices 1, 2, and 3.
- [ ] **H5.** §4 carry-forward walked. Inherited from Slices 1–2
  (**C14**, **C15**, **S2-1** through **S2-10**) — each already filed
  in `design-rationale.md` and surfaced in `roadmap.md` §7.2; no
  Slice-3 code resolution. New Slice-3 carry-forwards (each filed in
  `design-rationale.md` under "Slice 3 spec deviations"):
    - **S3-1** (subprocess utility ownership) — already filed in
      PR-A A1 against the design-rationale tail.
    - **S3-2** (`BranchProtectionCache` is process-local in-memory, no
      on-disk persistence in Slice 3) — filed in PR-G if Slice 4
      surfaces a need.
    - **S3-3** (`GhClient` / `GitClient` trait abstractions — not in
      v1.2 spec but needed for unit testability) — filed in PR-A so
      reviewers see the "why".
    - **S3-4** (`PRWatcher.PollResult.RateLimited` is a non-failing
      stream event, not a `GhError` — diverges from the natural
      reading of RL1 that says "back-off" without a typed event) —
      filed in PR-D.
    - **S3-5** (SessionMonitor scope excludes reviewer/refine
      phases — alignment with **S2-8**) — filed in PR-F.
  `roadmap.md` §7.2 mirrors every S3-* entry with a Slice-4 pointer.
- [ ] **H6.** This file flipped from "active" to "audit trail" (status
  header at the top of §0 reflects ✅ closed). `design-2.4.md`
  opens when Slice 4 starts (and inherits §4 below as its starting
  carry-forward list).

## 2. Order of work

`A → B → C → D → E → F → G → H` — the canonical order.

The dependency-strict edges are `A → B → {C, D}` and `A → E` /
`A → F` (only the `forge-git` skeleton is a prereq for `E`/`F`,
because they live in `forge-app` and `forge-app` depends on
`forge-git` transitively via the build.sbt module graph). Strictly
speaking, after `A` lands:

- `B` blocks `C` and `D` (both consume `PrSnapshotDecoder` /
  `PollResult`).
- `E` and `F` are independent of `B`/`C`/`D` and can land at any time.
- `C` and `D` can land in either order once `B` is in.
- `G` needs `C`, `D`, `E`, `F`.
- `H` is always last.

In practice, the strictly-linear ordering matches Slice 2's experience:
review attention is the bottleneck, not compile time, so landing
sub-PRs serially keeps the per-PR review surface manageable. The
parallelisation notes above exist for "if a sub-PR stalls, here are
the safe-to-do-in-parallel alternatives."

## 3. Status log

Update this section as items land. The roadmap section ticks off only
after PR-H lands.

- 2026-05-26 — design-2.3.md created on the close of Slice 2
  (`design-2.2.md` closed earlier same day). No PR-A code yet.

## 4. Carry-forward to v1.3

Items the section closure (PR-H) **must not silently bury** when it
flips the §2.3 roadmap bullet. Each one needs a durable home — a
roadmap §7.2 bullet, a tracking issue, or an explicit
deferred-decision entry in `design-rationale.md` — before PR-H H2
ticks the section.

### Inherited from Slices 1–2

- **C14 — `CodexConnector.resumeStreamingSpec` cannot honour §7.10(a)
  system-prompt prepending** (design-rationale C14). Slice 2 PR-C C5
  placed the FSM-side awareness comments at the design-revision and
  design-PR-feedback resume sites. **Slice 3 doesn't touch this** —
  no `forge-agents` resume path runs through Slice-3 components.
  C14 still rolls forward unchanged; Slice 4B's orchestrator wiring
  closes it (per `roadmap.md` §7.2.2).
- **C15 — Native schema regression suite (PR-D) deferred to the
  reviewer-asset PR** (design-rationale C15). **Slice 3 doesn't
  touch this** — Slice-3 components are below the reviewer-asset
  layer. Rolls forward to Slice 4A unchanged.
- **S2-1 through S2-10** — every Slice-2 spec deviation
  (`design-rationale.md` "Slice 2 spec deviations"). Slice 3 has
  three direct interactions worth noting:
    - **S2-2 (FsmEvent ADT shape)** — Slice 3 is the first consumer
      of the de-facto ADT contract; `BranchManager` produces
      `BranchCreated` / `PrOpened`, `PRWatcher` produces
      `DesignPrSnapshotUpdated` / `PrSnapshotUpdated` / `Merged` /
      `CheckDiscoveryComplete`, `SessionMonitor` produces
      `Settled` / `SettleTimeout` / `TurnBudgetBreached` /
      `BudgetBreached`. If any Slice-3 component finds the ADT
      under-specified or mis-shaped (e.g., a needed field missing),
      PR-H notes it under S2-2 so Slice 4 / v1.3 can address.
      Current expectation: the ADT is sufficient — but the
      coherence pass at H1 is the place to verify.
    - **S2-5 (writer-side atomic-merge ordering test)** — Slice 3's
      `BranchManager.createPieceBranch` returns `(BranchName, Sha)`
      and **does not** persist the manifest mutation. The
      orchestrator (Slice 4) is responsible for the atomic
      `manifest.json` write before the FSM transition action. PR-C
      C3's `createPieceBranch` docstring names S2-5 so Slice 4 has
      the anchor.
    - **S2-8 (SettleTimeout reviewer/refine phase coverage)** —
      SessionMonitor (PR-F) intentionally only covers the four
      driver phases per F4; reviewer/refine wall-clock caps live in
      Slice 4's reviewer-asset wrappers. F4's docstring names S2-8.

### New in Slice 3 (provisional — settled at PR-H)

The list below is the *expected* Slice-3 deviations. PR-H reconciles
the actual set against this expectation (carry-forwards that didn't
materialize get pruned; new ones surfaced by code review get added).

- **S3-1 — Subprocess utility ownership: `forge-git` uses `os-lib`
  `os.proc.call`, not the `forge-agents.Subprocess` streaming class**
  (PR-A A1). v1.2 doesn't say either way; Slice 1 introduced
  `Subprocess` as a streaming wrapper for the long-lived CLIs.
  Slice 3's `gh` / `git` invocations are one-shot. Adding `fs2-core`
  + `fs2-io` to `forge-core` for "one tool" or making `forge-git`
  depend on `forge-agents` would both be more costly than a thin
  `os-lib`-based call layer. Filed against design-rationale; no
  v1.3 spec change needed — this is a module-layout call.
- **S3-2 — `BranchProtectionCache` is process-local in-memory** (PR-C
  C5). v1.2 §8.1 says the cache is "scoped by `(featureId,
  baseBranch, cacheEpoch)`" but doesn't say where it lives. Slice 3
  keeps it in memory; the epoch on `Feature` (Slice 2 PR-B) is the
  invalidation key. If Slice 4 surfaces a need to persist the cache
  across orchestrator restarts (e.g., to avoid a `gh api` round-trip
  on every `forge run` warm-up), S3-2 reopens as a Slice-4 watch
  item. **Default behaviour:** no persistence; epoch bumps on
  every `forge resume` re-fetch from `gh api`. Cost: one HTTP round
  per resume, ~150ms, well under the perceptible threshold.
- **S3-3 — `GhClient` / `GitClient` trait abstractions** (PR-A A2 /
  A3). v1.2 §9 lists `BranchManager` and `PRWatcher` methods but
  doesn't mandate an inner abstraction. Slice 3 introduces traits +
  `Real…` impls + `Fake…` test fixtures so the unit-test surface
  for §9 / §11.3 / §11.4 / §11.5 logic doesn't need a real `gh`
  binary. Filed against design-rationale; the traits aren't in the
  spec because the spec didn't need to settle them — they're a
  testability seam.
- **S3-4 — `PRWatcher.PollResult.RateLimited` is a non-failing
  stream event** (PR-D D1 / D3). RL1 says "back-off on 403/429" and
  `harness.rate_limited` is the action-log signal. The
  `PRWatcher` could surface rate-limit as a stream failure (and
  re-enter on next call) or as a non-failing event (and continue
  internally). Slice 3 chooses the non-failing event: the stream
  emits `RateLimited(retryAfter)` once per back-off so the
  orchestrator (Slice 4) can log it, but the stream keeps running.
  Slice 4 may want a tighter contract — three consecutive
  rate-limits could be a hard `Failed` rather than the soft
  "after three, emit Failed" PR-D ships. If Slice 4 trips that
  cliff, S3-4 is the anchor.
- **S3-5 — SessionMonitor scope excludes reviewer/refine phases**
  (PR-F F4). Same rationale as **S2-8** but on the SessionMonitor
  side: the four driver phases are the only ones with a
  `Stream[IO, AgentEvent]` to watch. Reviewer/refine are one-shot
  adapter calls; their wall-clock caps live in the reviewer-asset
  PR (Slice 4A). Filing S3-5 closes the loop alongside S2-8 so
  Slice 4 has both pieces in plain sight.
- **S3-6 — `gh pr create` has no `--json` flag; URL parse is the
  contract** (PR-A A2). `design-rationale.md` BM8 names
  `gh pr create --json url -q .url` as the PR-number-capture
  pattern. The flag doesn't exist on any released `gh` (`gh pr
  create` writes the PR URL to stdout; `gh pr view` is the JSON
  one). Slice 3 ships the stdout-URL parse with a pinned regex.
  v1.3 rationale BM8 should be corrected to name the URL-parse
  contract (and optionally name the fallback two-call form
  `gh pr create … && gh pr view <url> --json number -q .number`
  for use behind a feature flag, though Slice 3 doesn't ship the
  fallback).

## 5. Cross-references

- v1.2 spec for `BranchManager` and `PRWatcher`: §8 (CI policy), §8.1
  (branch-protection cache), §9 (the two traits), §15
  (command-aware preflight).
- v1.2 spec for `ProcessLock`: §13.
- v1.2 spec for `SessionMonitor`: §7.9 (settle bounds), §12 (budget
  enforcement).
- v1.2 spec for the lifecycle steps Slice-3 components surface events
  for: §11.2 step 13 (PR open), §11.3 step 5 (force-push-with-lease),
  §11.4 step 1 (`createPieceBranch`), §11.4 step 6 (`createPr`),
  §11.5 step 1 (`Merged`), §11.5 (CI + review polling).
- v1.2 spec for the action-log signals Slice-3 components produce:
  §19 (`gh.poll`, `gh.action`, `harness.rate_limited`,
  `harness.session_killed`).
- Decisions backing the Slice-3 contracts: design-rationale **BM1**
  (syncBase + ff), **BM2** (stale base behaviour), **BM3**
  (command-aware preflight), **BM4** (OS lock + metadata), **BM5**
  (stale-lock UX), **BM6** (commit-human-fix branch check), **BM7**
  (derived branch names), **BM8** (PR number capture), **CI5**
  (epoch-scoped cache), **CI6** (`mergeStateStatus` trap), **RL1**
  (rate-limit + caching), **RL2** (baseline IDs).
- Slice 0 wire-shape findings consumed by `PrSnapshotDecoder`:
  `slice-0/slice-0-report.md` §9 (`gh pr view --json` field set
  pinned), §10 (branch-protection required-status-checks endpoint),
  §11 (line-based comment API — Slice-3 doesn't post comments, but
  the reviewer-asset PR will rely on the same `gh pr review` shape).
- Phase context + seam discipline: `roadmap.md` §2.3 (this slice),
  §2.6 (seams to leave open — `.forge/state/.lock` scope is "this
  checkout", paths via `ForgePaths`).
- Predecessors: `design-2.1.md` (Slice 1 audit trail, closed
  2026-05-26), `design-2.2.md` (Slice 2 audit trail, closed
  2026-05-26).
