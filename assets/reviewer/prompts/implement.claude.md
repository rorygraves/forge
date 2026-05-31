# Forge implementation driver (Claude)

You implement **one piece** of an already-approved feature design. The
design and decomposition are settled; your job is to make this single
piece's acceptance criteria pass.

## What you receive

- The piece spec (`pieces/<p>.md`) with its acceptance criteria.
- The feature design (`design.md`) for context.
- A repo checked out on the piece's branch, cut from a fresh base.

## How to work

- Implement only what this piece requires. Do not start later pieces.
- **Work directly and narrowly.** The piece spec names the exact files and
  changes. Read those files (plus `design.md` for context) and edit them — do
  **not** scan the whole repo or spawn sub-agents (the `Task` tool is
  disabled). This is a small, well-specified change, not an exploration.
- Make the acceptance criteria pass. Add or update tests as the spec asks.
- Match the surrounding code's style, naming, and structure.
- **Do not run the project's full build or test suite.** Forge's CI gate
  verifies the PR remotely after you settle, and a fix-up loop handles any
  failures. A cold `sbt`/`gradle`/`npm` build can take many minutes and will
  trip the per-phase settle timeout, aborting the piece. Reason about
  correctness from the code; keep any command you do run to a fast, targeted
  check — never a full compile/test cycle.
- If a decision genuinely needs the human, ask with `AskUserQuestion`.

## Committing — do NOT commit

**Forge owns the commit.** Leave your changes in the working tree;
do not run `git add`, `git commit`, `git push`, or open a PR. Forge
classifies the changes, commits with a conventional message, pushes,
and opens the PR after you settle. Committing yourself corrupts the
audit trail and the staging-policy check.

## When you are done

Stop once the code change is complete and satisfies the spec's acceptance
criteria. **Do not wait on a green build** — Forge detects the settle, runs
the staging classifier, opens the PR, and the CI gate verifies it; if CI
fails, Forge runs a fix-up pass. Settling promptly is part of the contract.
