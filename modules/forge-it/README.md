# forge-it — integration tests against real CLIs

This module hosts the integration tests that exercise Forge against real
external binaries (`claude`, `codex`, `gh`, `git`) and against a
sacrificial GitHub repo. None of these tests are gated by `sbt test`
alone — the module's suites read environment variables at startup and
skip themselves when the prerequisite isn't present.

## Default-on suites

`sbt "project forge-it" test` runs only the suites that are cheap and
local-binary-only. Each detects its binary on `PATH` and skips when
absent (so a workstation without `claude` / `codex` installed still
sees a green run).

- `ClaudeHeadlessSmokeSuite`, `CodexHeadlessSmokeSuite` — single-prompt
  smoke against the real CLI; gated on the CLI being on `PATH` and
  `FORGE_IT_SKIP_{CLAUDE,CODEX}!=1`.
- `ClaudeStreamingSpecSuite`, `CodexStreamingSpecSuite` — streaming
  spec-phase coverage; same gating.

## Opt-in suites (environment-gated)

| Env var                    | Suite                            | What it costs |
|----------------------------|----------------------------------|---------------|
| `FORGE_IT_RUN_RELIABILITY=1` | `CodexHaltWithQuestionReliabilitySuite` | 20× real CLI runs ≈ 2–5 min wall-clock |
| `FORGE_IT_GH_REPO=<owner>/<repo>` | `BranchManagerIntegrationSuite` | one branch + PR + merge cycle against the named repo |
| `FORGE_IT_RUN_PROCLOCK=1`  | `ProcessLockMultiJvmSuite`       | spawns sibling JVMs per scenario, ~10–30s total |

The env vars are independent — set the ones you want. Default `sbt
"project forge-it" test` runs none of them.

## Sacrificial-repo setup (`FORGE_IT_GH_REPO`)

`BranchManagerIntegrationSuite` (PR-G G2 in `docs/design-2.3.md`) drives
the end-to-end branch / push / PR / poll / merge lifecycle against a
**throw-away** GitHub repository. **Do not point it at a real project**
— each run leaves a merged PR and (intentionally) a lingering branch
behind. The suite is designed to be idempotent: each run picks a fresh
random branch suffix, so parallel runs against the same repo don't
collide.

### One-time operator setup

1. **Create or fork an empty repo** in your own GitHub namespace. The
   maintainer's reference repo is `rorygraves/test-repo`. Fork it (or
   create a fresh empty one) into a namespace you control. Keep
   `Issues`, `Pull requests`, and `Actions` enabled — disabling them
   makes `gh pr create` fail in unhelpful ways.
2. **Ensure the `main` branch exists with at least one commit.** A
   brand-new empty GitHub repo has no commits and no branches; the IT
   bootstrap step handles that case on the first run, but you can also
   seed manually with `gh repo edit --default-branch main && git push
   -u origin main` against an initial commit.
3. **Authenticate `gh` and `git`** against your namespace:
   ```sh
   gh auth login           # interactive — pick HTTPS protocol
   gh auth setup-git       # configures git to use gh as credential helper
   ```
   The IT calls `gh repo clone` directly, so it inherits whatever auth
   posture `gh` already has.
4. **Export the env var** in the shell that runs sbt:
   ```sh
   export FORGE_IT_GH_REPO=your-user/your-test-repo
   sbt "project forge-it" "testOnly *BranchManagerIntegrationSuite"
   ```

### What the test does

For each run, with a random short suffix `<uid>`:

1. Clones `$FORGE_IT_GH_REPO` into a fresh `os.temp.dir`.
2. Bootstraps `main` with a placeholder commit + push if the remote has
   no `main` yet (first-run convenience for fresh empty repos).
3. `BranchManager.syncBase("main")` → asserts a `BaseSnapshot`.
4. `BranchManager.createPieceBranch(feature = "s3<uid>", piece = "p1",
   branchPrefix = "forge-it/slice3")` → branch
   `forge-it/slice3/s3<uid>/p1` cut from main.
5. Writes a no-op marker file (`forge-it-<uid>.txt`), commits, and
   `BranchManager.pushCurrentBranch()`.
6. `BranchManager.createPr("forge-it slice-3 IT", body, base = "main")`.
7. `PRWatcher.pollOnce(prNumber, PollBaseline.empty)` → asserts
   `state = Open`.
8. `RealGhClient.prMerge(prNumber, mode = "merge")`.
9. `PRWatcher.pollOnce(prNumber, nextBaseline)` (retried up to ~30s with
   1s back-off because GitHub's API takes a moment to reflect the
   merge) → asserts `state = Merged` with non-null `mergedAt` and
   `mergeCommit`.

### What gets left behind

The sacrificial repo accumulates branches + closed PRs over time. The
README intentionally avoids a `--delete-branch` on merge so the trail
is visible for post-hoc inspection. Prune branches and PRs out-of-band
when the test repo gets noisy:

```sh
# Delete all forge-it branches on the remote:
gh repo clone <FORGE_IT_GH_REPO> /tmp/cleanup && cd /tmp/cleanup
git fetch --prune origin
git branch -r | grep '^  origin/forge-it/slice3/' | sed 's|^  origin/||' | \
  xargs -n1 -I{} git push origin --delete {}
```

## Multi-JVM ProcessLock setup (`FORGE_IT_RUN_PROCLOCK`)

`ProcessLockMultiJvmSuite` (PR-G G3) spawns sibling JVMs via
`io.forge.agents.Subprocess` to exercise live `FileChannel.tryLock`
contention. The sibling JVM runs `io.forge.it.LockHolderMain` (in this
module's test classpath); the parent test process talks to it over the
child's stdin (close-to-exit) and stdout (acknowledgement line).

The only prerequisite is a JVM on `PATH` matching the one running sbt
— the spawn uses `System.getProperty("java.home") + "/bin/java"` and
inherits `java.class.path` from the test process. No network, no `gh`,
no GitHub.

Run with:

```sh
FORGE_IT_RUN_PROCLOCK=1 sbt "project forge-it" "testOnly *ProcessLockMultiJvmSuite"
```
