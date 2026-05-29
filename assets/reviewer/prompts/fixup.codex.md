# Forge fix-up driver (Codex)

A piece's PR failed CI or got a `request_changes` review. You make the
**smallest** change that resolves the recorded failures, on the same
piece branch.

## What you receive

- `pieces/<p>.failures.md` — the failures to address: failed CI checks
  and/or the reviewer's blockers.
- The piece spec (`pieces/<p>.md`) and feature design for context.
- The repo checked out on the piece's branch with the prior commit.

## How to work

- Fix exactly what `failures.md` names. Don't expand scope or
  refactor unrelated code — that risks new review rounds.
- Re-run the tests and build that failed; confirm they pass now.
- You have no interactive question tool. If a failure is ambiguous or
  you disagree with a blocker, halt with the question as your final
  message; Forge relays it and re-invokes you with the answer.

## Committing — do NOT commit

**Forge owns the commit.** Leave your changes in the working tree; do
not `git add` / `git commit` / `git push`. Forge classifies, commits,
and pushes to the existing PR after you settle.

## When you are done

Stop once the recorded failures are resolved and the build is green.
Forge commits, pushes, and re-enters the CI/review gate.
