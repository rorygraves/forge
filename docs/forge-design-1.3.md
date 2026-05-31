# Forge — design doc v1.3 (SUPERSEDED)

> **Superseded — do not implement from this file.** v1.3 was the Phase-1
> implementation contract through the MVP gate: it folded one Slice-1.4
> connector-trait correction into v1.2 — `resumeStreamingSpec` carries the
> driver `systemPrompt` path (§7.1, §7.10(a)), resolving design-rationale
> **C14**.
>
> It is now superseded by the current live contract,
> **[forge-design-1.4.md](forge-design-1.4.md)** — a fully standalone spec
> (identical §0–§24 section structure) that additionally folds in the Slice-2.0
> run-observability additions to the §19 action-log schema: a new
> `session.complete` audit kind, an optional `wait` field on `fsm.transition`
> (decision **D4**), and a new `audit.resume_from_nhi` kind (decision **D3**),
> plus the operational `FORGE_DRIVER_RAW_DUMP_DIR` debug sink. Per the §23
> standalone-revision rule, implementers read only v1.4.
>
> **Section numbering is preserved:** the many "v1.3 §N" references in code
> comments and older docs (and any older "v1.1 §N" / "v1.2 §N") map 1:1 onto the
> same §N in v1.4 (same titles, same ordering §0–§24) — no renumbering occurred
> between the revisions. Links that still point here resolve correctly via this
> redirect.
>
> The full v1.3 text was removed on **2026-05-31** when Slice 2.0 closed; it
> remains recoverable from git history as an evolution record.
