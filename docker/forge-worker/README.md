# forge-worker — default worker container image (Slice 4.3, O1/O7)

The image a containerised Forge worker runs in. A worker spawned by `forge daemon
start --container` runs `forge worker … --container` inside this image, reaching
the daemon over a bind-mounted control socket and driving the frozen v1 loop with
its credentials **brokered over that socket** (Task 4.3.3 / O6 — no host home
mount, a per-worker scoped `gh` token, short-lived agent auth).

This is the supervisor's **fallback** image
(`ContainerRuntime.DefaultImage = forge-worker:latest`). A repo pins its own image
and tool versions in a committed `Forgefile` (O1, the normative source of truth);
the default image is used only when no `Forgefile` is committed.

## Build

```bash
scripts/build-forge-worker-image.sh           # sbt forge-app/assembly + docker build
scripts/build-forge-worker-image.sh --skip-build   # reuse an existing forge.jar
scripts/build-forge-worker-image.sh --tag ghcr.io/acme/forge-worker:9   # custom tag
```

The script assembles `forge.jar`, stages it into this directory (the Docker build
context — `forge.jar` here is git-ignored), and builds the image.

## What's inside

| Component | Why |
|-----------|-----|
| `eclipse-temurin:21-jre` | the JRE the fat jar needs (Scala 3.7.1 / JVM 21) |
| `git` | clone / branch / commit gates |
| `gh` | PR / merge gates (uses the **brokered**, scoped token, not a host login) |
| `claude`, `codex` | the agent driver/reviewer CLIs (auth brokered over the socket) |
| `/opt/forge/forge.jar` + `/usr/local/bin/forge` | the worker entrypoint |

Tool floors are `--build-arg`s (`CLAUDE_VERSION`, `CODEX_VERSION`, `NODE_MAJOR`)
defaulting to the Slice-0-validated floors (claude `2.1.150`, codex `0.133.0`).
Pin precisely per-repo in a `Forgefile` rather than rebuilding this default.

## How the supervisor uses it

`ContainerRuntime.buildSpec` (forge-app) builds the `docker run` spec: it
bind-mounts the isolated clone at `/forge/worker` and the daemon socket at
`/forge/daemon.sock`, sets the workdir to `/forge/worker/checkout`, and runs
`forge worker --worker-root /forge/worker --socket /forge/daemon.sock --container …`.
**No host home is mounted and no secrets are in the spec** (`docker inspect`-safe);
the worker brokers its credentials over the mounted socket.
