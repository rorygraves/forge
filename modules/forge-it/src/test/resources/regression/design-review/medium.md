# Design: `ProcessLock` force-release recovery (`forge unlock --force`)

## Motivation

Forge serialises all state-changing commands behind a single per-repo process
lock (§15). If an orchestrator process is killed (`SIGKILL`, power loss, OOM)
it cannot run its `Resource` finalizer, so the lock file is left on disk with a
PID that no longer maps to a live process. Every subsequent `forge run`
then fails to acquire the lock and the repo is wedged. `forge unlock --force`
is the recovery path.

## Lock file shape

`.forge/lock.json` holds `{ pid, host, command, acquiredAt }`. Acquisition is
an atomic create-new (`O_CREAT | O_EXCL`); the holder writes its identity, and
the finalizer deletes the file on clean exit.

## Force-release contract

`forge unlock --force` must work **even when the rest of the repo is broken** —
that is the whole point of a recovery command. Specifically it:

1. Does **not** load `.forge/config.json` (a prior crash may have left it
   malformed).
2. Does **not** install reviewer assets or construct a connector.
3. Reads the existing lock file, prints the holder identity it is about to
   evict, deletes the file, and exits 0.
4. If no lock file exists, exits 0 with a "nothing to release" notice — the
   command is idempotent.

This means the boot sequence must parse argv and detect `unlock --force`
**before** any config/asset/connector setup, short-circuiting straight to the
release path.

## Staleness detection (non-goal for v1)

We deliberately do **not** auto-detect a stale lock (e.g. by checking whether
the recorded PID is alive). PID reuse across hosts and containers makes
liveness checks unreliable, and a false "stale" verdict that silently steals an
active lock is far worse than requiring an explicit `--force`. Auto-detection
is deferred.

## Acceptance criteria

- `forge unlock --force` releases a lock left by a killed process and the next
  `forge run` acquires cleanly.
- `forge unlock --force` with no lock file present exits 0.
- `forge unlock --force` succeeds when `.forge/config.json` is missing or
  malformed (proves the short-circuit).
- A normal (non-force) state-changing command still fails fast against a held
  lock, printing the holder identity and exiting 2.
