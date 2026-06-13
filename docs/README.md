# Forge docs — index

Start here. The live design surface is intentionally small; older slice
plans and spec revisions stay in-tree as audit trails, but the project
does not try to maintain a full narrative history in this index.

## Live / forward-looking

| Doc | What it is |
|---|---|
| [`roadmap.md`](roadmap.md) | Current product plan, phase status, and priority queue. |
| [`forge-design-1.16.md`](forge-design-1.16.md) | Live v1 implementation contract: the single-feature lifecycle, command set, connectors, reviewer/sensor cost accounting, FSM, action log, and repo-adaptation behaviour. |
| [`forge-design-2.0.md`](forge-design-2.0.md) | Phase-4 architecture contract: workspace/instance scope, workstreams, workers, daemon, container isolation, aggregate budget authorization, and cockpit observability. |
| [`design-4.4.md`](design-4.4.md) | Active Slice 4.4 implementation plan: daemon-backed cockpit TUI, control actions, inspection, live multi-workstream container proof, and current quality gates. |
| [`design-rationale.md`](design-rationale.md) | Durable explanations for non-obvious tradeoffs and reconciliations. |

## Current direction

- **Validation mode:** Forge must support true cross-model validation
  (one CLI drives, another independently reviews). Same-CLI validation
  remains a supported pairing for local/cost-sensitive runs, but not the
  product ceiling.
- **Quality gate:** root `sbt test` must be aggregate-green and stable;
  `sbt scalafmtCheckAll` remains required.
- **Active phase:** Phase 4 is in progress. Slices 4.0–4.3 have landed
  the instance/daemon/worker/container/budget engine. Slice 4.4 is the
  cockpit and live fleet proof.

## Reference / support

| Doc | What it is |
|---|---|
| [`agent-best-practices.md`](agent-best-practices.md) | Working discipline for human + agent contributors. |
| [`slice-0/slice-0-report.md`](slice-0/slice-0-report.md) | Captured CLI capability validation. |
| [`slice-1/slice-1-findings.md`](slice-1/slice-1-findings.md) | Connector-runtime findings from early implementation. |
| [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md) | Dogfood run #1, the MVP gate friction log. |
| [`dogfood/`](dogfood/) | Live-run notes and Phase-4 proof runbooks. |

## Historical audit trails

Closed `design-*.md` files record how a slice landed: task breakdowns,
status logs, review rounds, and carry-forwards. Treat them as evidence,
not as the live contract. The live contracts are the roadmap plus
`forge-design-1.16.md` and `forge-design-2.0.md`.

Closed plan families:

- `design-1.4.md`, `design-2.0.md`, `design-2.1*.md`,
  `design-2.2*.md`, `design-2.3.md` — Phase 1/2 implementation trails.
- `design-3*.md`, `design-phase3-exit.md` — Phase 3 repo-adaptation
  trails.
- `design-4.0.md`, `design-4.1.md`, `design-4.2.md`,
  `design-4.3.md` — closed Phase-4 engine slices.

Superseded `forge-design-1.N.md` revisions remain to preserve old links
and section references. Only `forge-design-1.16.md` is the current v1
implementation contract.
