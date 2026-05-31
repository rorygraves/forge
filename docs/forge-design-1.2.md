# Forge — design doc v1.2 (SUPERSEDED)

> **Superseded — do not implement from this file.** v1.2 was the Phase-1
> implementation contract: it folded three Slice-1 connector-trait corrections
> into v1.1 — (a) `runStreamingSpec` / `resumeStreamingSpec` carry an initial
> user message; (b) `StreamingSession.answerQuestion(toolUseId, answer)` for the
> §7.2 `tool_result` reply path; (c) `AgentEvent.AskUserQuestion` carries the
> originating `toolUseId`.
>
> It was first superseded by [v1.3](forge-design-1.3.md) (the Slice-1.4
> `resumeStreamingSpec` system-prompt correction, design-rationale **C14**) and
> is now superseded by the current live contract,
> **[forge-design-1.4.md](forge-design-1.4.md)** — a fully standalone spec
> (identical §0–§24 section structure) that additionally folds in the Slice-2.0
> §19 run-observability additions. Per the §23 standalone-revision rule,
> implementers read only v1.4.
>
> **Section numbering is preserved:** the many "v1.2 §N" references in code
> comments and older docs map 1:1 onto the same §N in v1.4 (same titles, same
> ordering §0–§24) — no renumbering occurred between the revisions.
>
> The full v1.2 text was removed on **2026-05-31** in the Phase-1 docs
> consolidation; it remains recoverable from git history as an evolution record.
