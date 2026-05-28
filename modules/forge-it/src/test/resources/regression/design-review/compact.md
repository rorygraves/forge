# Design: `forge tail <feature>` — live action-log tail

## Goal

Give an operator a live view of a running feature's action log without
attaching to the orchestrator process. `forge tail <feature>` follows
`.forge/log/<feature>.jsonl`, printing each appended `Action` as a
human-readable line as it lands.

## Surface

- New `ReadOnlyKind.Tail` variant on the `ForgeCommand` ADT.
- `forge tail <feature>` is a **read-only** command per §15: it does **not**
  acquire the process lock, so it can run alongside an active `forge run`.
- Output: one line per log entry — `<seq> <iso-timestamp> <action-kind>
  <summary>`. Malformed lines are reported inline and skipped, not fatal.

## Behaviour

1. Resolve the log path via `ForgePaths.featureLog(featureId)`.
2. If the file is absent, exit 0 with a "no log yet" notice (the feature may
   not have produced an action).
3. Otherwise open the file, render existing lines, then poll for appends.
4. `Ctrl-C` exits cleanly with code 0.

## Acceptance criteria

- Tailing a feature with an existing log renders every committed action in
  sequence order.
- A line appended while tailing appears without restarting the command.
- Running `forge tail` against an active `forge run` does not block the run
  (no lock contention).
