# Forge — design doc v1.4 (SUPERSEDED)

> **Superseded — do not implement from this file.** v1.4 was the Phase-1 →
> Phase-2 implementation contract through Slice 2.0: it folded the Slice-2.0
> run-observability additions into the §19 action-log schema — a new
> `session.complete` audit kind, an optional `wait` field on `fsm.transition`
> (decision **D4**), and a new `audit.resume_from_nhi` kind (decision **D3**),
> plus the operational `FORGE_DRIVER_RAW_DUMP_DIR` debug sink.
>
> It is now superseded by the current live contract,
> **[forge-design-1.6.md](forge-design-1.6.md)** — a fully standalone spec
> (identical §0–§24 section structure). The intervening v1.5 revision (now itself
> a [redirect stub](forge-design-1.5.md)) folded in the Phase-2 roadmap §3.5
> driver-respawn-avoidance work (the "D3" chunks,
> [`design-3.5.md`](design-3.5.md)): a new §7.1 `resumeHeadlessDriver` connector
> method (extended to §7.10(a)), the §11.4 restart-recovery
> resume-instead-of-respawn behaviour gated by a worktree-safety classifier, the
> §19 `<actor>.resume` kind for piece-driver restart resumes, and a `resumed`
> field on `session.complete` (gap #10). v1.6 then reconciled the §3.3 termflow
> dependency note + the new §3.3.1 Scala-3.7.1 floor against what Slice 2.1 (TUI)
> shipped. Per the §23 standalone-revision rule, implementers read only v1.6.
>
> **Section numbering is preserved:** the many "v1.4 §N" references in code
> comments and older docs (and any older "v1.1 §N" / "v1.2 §N" / "v1.3 §N") map
> 1:1 onto the same §N in v1.6 (same titles, same ordering §0–§24) — no
> renumbering occurred between the revisions. Links that still point here resolve
> correctly via this redirect.
>
> The full v1.4 text was removed on **2026-06-01** when the §3.5
> driver-respawn-avoidance work (D3) closed; it remains recoverable from git
> history as an evolution record.
