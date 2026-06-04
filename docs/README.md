# Forge docs — index

Start here. This index sorts the `docs/` tree into **live / forward-looking**,
**reference**, and **closed / historical** so the active design surface is easy
to find. (The per-slice audit trails are kept in-tree for history rather than
deleted; git holds the full lineage either way.)

> **Direction (2026-06-02):** the roadmap is re-centered on **repo adaptation** —
> a deterministic spine + agentic *senses* (RepoProfiler, FailureClassifier,
> ConventionLearner) so Forge stops running blind on a repo. See
> [`roadmap.md`](roadmap.md) §4 (Phase 3) and [`design-rationale.md`](design-rationale.md) **A5**.

## Live / forward-looking — read these

| Doc | What it is |
|---|---|
| [`roadmap.md`](roadmap.md) | Multi-horizon product plan. Phase 3 = Repo Adaptation (the current pivot); Phase 4 = workspace/workstream platform; Phase 5 = cockpit. |
| [`forge-design-1.9.md`](forge-design-1.9.md) | **Live implementation contract** (the spec) — latest revision. Standalone-by-freeze over 1.8: restates only §6/§11.5/§18 (the `WorkflowProfile` begins parameterizing the §11 FSM — review-required, design-3.3), freezing the rest at 1.8. §-numbers preserved, so a "v1.2 §N" reference resolves to the same §N. |
| [`forge-design-1.8.md`](forge-design-1.8.md) | Phase-3 revision: the pre-PR Build gate (§6/§8.3/§11.4/§11.5/§19, design-3.0 D2). 1.9 freezes its unchanged sections; read together. |
| [`forge-design-1.7.md`](forge-design-1.7.md) | Phase-3 architectural revision (Repo Adaptation): `RepoProfile` / `WorkflowProfile`, sensor surface (§3/§6/§7/§8/§11/§18/§19). 1.8/1.9 freeze its unchanged sections. |
| [`forge-design-1.6.md`](forge-design-1.6.md) | The pre-Phase-3 base (Phases 1–2). 1.7/1.8/1.9 freeze its unchanged sections. |
| [`design-rationale.md`](design-rationale.md) | Why the spec looks the way it does. Decision/rejected/where-to-read per item. **A5** is the Phase-3 architecture direction. |
| [`design-3.0.md`](design-3.0.md) / [`design-3.1-build-gate.md`](design-3.1-build-gate.md) | Phase-3 implementation plans — Slice 3.0/3.1 (RepoProfile + senses) and the 3.1-D2 pre-PR Build gate. Task breakdowns, status logs, carry-forwards. (Slice 3.3 closed — see the historical table below.) |
| [`design-3.5-role-trait.md`](design-3.5-role-trait.md) | **Open** — Slice 3.5 (the last Phase-3 sub-slice): the role-trait refactor (roadmap §4.2). Generalises `Mode` into an `Agent` base + `Driver`/`Reviewer`/sensor role configurations. Not to be confused with the closed [`design-3.5.md`](design-3.5.md) (Phase-2 D3 driver-respawn). |

## Reference / support

| Doc | What it is |
|---|---|
| [`agent-best-practices.md`](agent-best-practices.md) | Working discipline for human + agent contributors (mirrored by the CLAUDE.md "Testing & review discipline" list). |
| [`slice-0/slice-0-report.md`](slice-0/slice-0-report.md) | CLI-capability validation (pinned flags, transcripts). |
| [`slice-1/slice-1-findings.md`](slice-1/slice-1-findings.md) | Connector runtime findings. |
| [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md) | Dogfood run #1 (`image-creds-dedup`, MVP gate). |
| [`dogfood/extract-media-network-config.md`](dogfood/extract-media-network-config.md) | Dogfood run #2 — the run that motivated the adaptation pivot (the $0.73-vs-$1.78 formatter waste). |

## Closed / historical — per-slice audit trails (reference only)

These are **closed**; they record *how* a slice was built (Task breakdowns, status
logs, review rounds, carry-forwards). They are not the live contract — that is
`forge-design-1.6.md`. Kept for history.

| Doc | Slice | Closed |
|---|---|---|
| [`design-2.1.md`](design-2.1.md) | Slice 1.1 — agent connectors | 2026-05-26 |
| [`design-2.2.md`](design-2.2.md) | Slice 1.2 — FSM / core / action log | 2026-05-26 |
| [`design-2.3.md`](design-2.3.md) | Slice 1.3 — forge-git / forge-app | 2026-05-27 |
| [`design-1.4.md`](design-1.4.md) | Slice 1.4 — reviewer assets + orchestrator + MVP gate (Phase 1 close) | 2026-05-31 |
| [`design-2.0.md`](design-2.0.md) | Slice 2.0 — run observability | 2026-05-31 |
| [`design-3.5.md`](design-3.5.md) | D3 — driver-respawn-avoidance on resume | 2026-06-01 |
| [`design-2.1-tui.md`](design-2.1-tui.md) | Slice 2.1 — read-only TUI | 2026-06-01 |
| [`design-3.3.md`](design-3.3.md) | Slice 3.3 — `WorkflowProfile` FSM parameterization (review-required + CI required-check sensing) | 2026-06-04 |

> Naming note: the `design-N.md` numbers are *slice* ids and do **not** track the
> `forge-design-1.N.md` spec revisions — e.g. `design-2.1.md` is Slice 1.1, while
> `design-2.1-tui.md` is the Phase-2 TUI slice (renamed to dodge the collision).

## Superseded spec revisions (redirect stubs)

`forge-design-1.1.md` … `forge-design-1.5.md` are one-line stubs pointing at the
live `forge-design-1.6.md`; full prior text is in git history. They exist only so
old "v1.x" links still resolve.
