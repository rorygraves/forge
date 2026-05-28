# Piece: PR-watcher Unauthorized passthrough + forge-it test isolation

Two related fixes surfaced in a forge-git review round:

1. The `gh` baseline decoder must pass an `Unauthorized` author-association
   through unchanged rather than collapsing it into a generic bucket — the
   orchestrator's §11 review-gate logic distinguishes authorised reviewers
   from drive-by comments, and silently rewriting the association breaks that.
2. The root `test` aggregation pulls in `forge-it`, whose suites require
   `claude` / `codex` / `gh` on PATH and so fail any environment that lacks
   them. `forge-it` must be excluded from the default aggregated `test` task
   and run explicitly via `sbt "project forge-it" test`.

## Acceptance criteria

- The decoder preserves every documented `authorAssociation` value, including
  `Unauthorized`, with a round-trip test asserting the passthrough.
- Root `test` no longer depends on `forge-it`; the project still compiles via
  the dependency graph so a refactor that breaks the `forge-it` API is caught
  by `sbt "project forge-it" compile`.
- Existing decoder + branch-manager suites stay green.
