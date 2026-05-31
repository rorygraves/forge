# MVP-gate run evidence — `image-creds-dedup`

P3 evidence for **Task 1.4.16** (Slice 1.4 Phase-1 MVP gate). The first real
end-to-end Forge run: feature **`image-creds-dedup`** ("de-duplicate the
image-credentials feature-flag logic") driven through `forge new` → `forge spec`
→ `forge run` to **`FeatureDone`** in **`llm4s/szork`** (Scala/Vue), Claude
driver, real GitHub Actions CI + branch protection, 2026-05-30 → 2026-05-31.

## Files

- **`action-log.jsonl`** — the NDJSON action log (`fsm.transition` +
  `audit.piece_merged` entries), the run's source-of-truth audit trail. 25
  entries, `Drafting` (seq 0) → `FeatureDone` (seq 24).
- **`design.md`** — the design produced by the design-review phase (merged as
  szork PR #9).
- **`manifest.json`** — the single-piece manifest the spec phase produced.

## The validated lifecycle

```
seq  0  Drafting → InteractiveSpec
seq  1  InteractiveSpec → DesignReviewing        (design-review one-shot, approve)
seq  2  DesignReviewing → DesignAwaitingMerge     (design PR #9 opened, CI green)
seq  3  DesignAwaitingMerge → DesignReady         (PR #9 merged by operator)
seq  4  DesignReady → PieceImplementing           (implement driver spawned)
seq 5–12  PieceImplementing ⇄ NeedsHumanIntervention  (×4 — the gaps, fixed in-flight)
seq 13  PieceImplementing → PieceAwaitingCi        (piece PR #10 opened)
seq 14  PieceAwaitingCi → PieceCiFailed            (§8 gate: CI fail)
seq 15  PieceCiFailed → PieceFixingUp
seq 16  PieceFixingUp → PieceAwaitingCi
seq 17  PieceAwaitingCi → PieceCiFailed            (§8 gate: CI fail again)
seq 18  PieceCiFailed → PieceFixingUp
seq 19  PieceFixingUp → PieceAwaitingCi
seq 20  PieceAwaitingCi → PieceAwaitingReview      (§8 gate: green)
seq 21  PieceAwaitingReview → PieceAwaitingMerge   (reviewer approve)
seq 22  PieceAwaitingMerge → Refining              (PR #10 merged by operator)
seq 23  audit.piece_merged
seq 24  Refining → FeatureDone
```

The 13 integration gaps surfaced and fixed during the run are catalogued in
[`../../mvp-friction.md`](../../mvp-friction.md).
