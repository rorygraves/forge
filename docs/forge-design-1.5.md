# Forge — design doc v1.5 (SUPERSEDED)

> **Superseded — do not implement from this file.** v1.5 was the live
> implementation contract through Slice 2.0 + the §3.5 driver-respawn-avoidance
> (D3) work: on top of v1.4's run-observability §19 additions it folded in the
> §7.1 `resumeHeadlessDriver` connector method (extended to §7.10(a)), the §11.4
> restart-recovery resume-instead-of-respawn behaviour gated by a worktree-safety
> classifier, the §19 `<actor>.resume` kind for piece-driver restart resumes, and
> a `resumed` field on `session.complete` (gap #10, [`design-3.5.md`](design-3.5.md)).
>
> It is now superseded by the current live contract,
> **[forge-design-1.6.md](forge-design-1.6.md)** — a fully standalone spec
> (identical §0–§24 section structure) that additionally reconciles the §3.3
> dependency note and the build's Scala floor against what Slice 2.1 (TUI,
> [`design-2.1-tui.md`](design-2.1-tui.md)) actually shipped: the TUI library is
> `org.llm4s::termflow` **0.4.0** (a multi-module split), not the placeholder
> `0.0.1`/`0.1.0-SNAPSHOT`; consuming it forced the repo-wide **Scala 3.5.2 →
> 3.7.1** bump now recorded in the new §3.3.1 (design-rationale **BT1**); and the
> §3.1 component-diagram framing now notes that the in-process `Sub`/`Cmd` link is
> the deferred live-tap target while the shipped v1 `forge tui` is a read-only
> viewer. This revision touches only §3.1/§3.3 prose — **no contract surface
> (§7 trait, §11 FSM, §19 schema) changed** between v1.5 and v1.6. Per the §23
> standalone-revision rule, implementers read only v1.6.
>
> **Section numbering is preserved:** the many "v1.5 §N" references in code
> comments and older docs (and any older "v1.1 §N" … "v1.4 §N") map 1:1 onto the
> same §N in v1.6 (same titles, same ordering §0–§24) — no renumbering occurred
> between the revisions. Links that still point here resolve correctly via this
> redirect.
>
> The full v1.5 text was removed on **2026-06-01** when the Slice-2.1 (TUI)
> close-out (Task 2.1.8) landed the §3.3/§3.1 reconciliation; it remains
> recoverable from git history as an evolution record.
