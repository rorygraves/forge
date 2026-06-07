# forge-worker — the default forge-capable worker container image (Slice 4.3, O1/O7).
#
# A containerised Forge worker runs `forge worker … --container` inside this image,
# reaching the daemon over TCP at host.docker.internal:<port> (the daemon's
# `--socket` address) and driving the frozen v1 loop with its credentials brokered
# over that connection (Task 4.3.3 / O6 — no host home mount, no bind-mounted
# socket). The image therefore bundles everything the worker boundary needs on
# PATH: the `forge` launcher (the assembled fat jar), a JRE, git, the `gh` CLI, and
# the `claude` / `codex` agent CLIs.
#
# This is the supervisor's *fallback* image (`ContainerRuntime.DefaultImage =
# forge-worker:latest`) — a repo pins its own image + tool versions in a committed
# `Forgefile` (O1, the normative source of truth). The ARG floors below mirror the
# CLI floors Forge validated in Slice 0 (claude 2.1.150) and the codex stdin-EOF
# fix (0.133); bump them via --build-arg or pin precisely in a Forgefile.
#
# Build (stages the assembled jar into this context first):
#   scripts/build-forge-worker-image.sh
# or manually:
#   sbt forge-app/assembly
#   cp modules/forge-app/target/scala-3.7.1/forge.jar docker/forge-worker/forge.jar
#   docker build -t forge-worker:latest docker/forge-worker

FROM eclipse-temurin:21-jre AS base

# Pinned tool floors — overridable via --build-arg, or pinned per-repo in a Forgefile.
ARG CLAUDE_VERSION=2.1.150
ARG CODEX_VERSION=0.133.0
ARG NODE_MAJOR=22

# System tooling: git (clone/branch/commit gates), gh (PR/merge gates), plus the
# build prerequisites for the agent CLIs (Node.js) and the gh apt repository.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg git; \
    # Node.js (for the npm-distributed agent CLIs)
    mkdir -p /etc/apt/keyrings; \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
      | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg; \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" \
      > /etc/apt/sources.list.d/nodesource.list; \
    # GitHub CLI (official apt repository)
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
      | gpg --dearmor -o /etc/apt/keyrings/githubcli-archive-keyring.gpg; \
    chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg; \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
      > /etc/apt/sources.list.d/github-cli.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends nodejs gh; \
    rm -rf /var/lib/apt/lists/*

# Agent CLIs (npm-distributed). Pinned to the ARG floors; a repo's Forgefile is the
# normative pin — this image is only the default fallback.
RUN set -eux; \
    npm install -g \
      "@anthropic-ai/claude-code@${CLAUDE_VERSION}" \
      "@openai/codex@${CODEX_VERSION}"; \
    npm cache clean --force

# Git credential helper (O6, Slice 4.3): route HTTPS auth for GitHub through `gh auth
# git-credential`, which reads the token from the `GH_TOKEN`/`GITHUB_TOKEN` env var. The
# containerised worker has no host home mount and no ambient git credentials; its scoped
# `gh` token is brokered over the control channel (Task 4.3.3) and threaded into the `git`
# subprocess env (RealGitClient `env` overlay), so the in-container `git push` over HTTPS
# authenticates via this helper without any credential ever touching the image or the
# container spec. Written to the *system* gitconfig so it applies regardless of which UID
# runs (the clone is bind-mounted; we run as root today — see the UID note below). This is
# what `gh auth setup-git` writes, set directly so it needs no login at build time. The
# leading empty `helper ''` resets any inherited helper list so only gh's runs.
RUN git config --system credential."https://github.com".helper '' \
 && git config --system --add credential."https://github.com".helper '!gh auth git-credential' \
 && git config --system credential."https://gist.github.com".helper '' \
 && git config --system --add credential."https://gist.github.com".helper '!gh auth git-credential'

# The Forge launcher + assembled fat jar. forge.jar must be staged into this build
# context first (see the header / build-forge-worker-image.sh).
COPY forge.jar /opt/forge/forge.jar
COPY forge /usr/local/bin/forge
RUN chmod +x /usr/local/bin/forge

# The clone is bind-mounted in at /forge/worker (ContainerRuntime.ContainerWorkerRoot)
# and the supervisor sets the workdir to the checkout; the daemon is reached over TCP
# (host.docker.internal:<port>), not a mount. We run as root: the bind-mounted clone is owned by the
# host user, so a non-root container UID would have to be reconciled with the host
# UID to write the checkout — that UID-mapping hardening (defence-in-depth on top of
# the O6 credential/home isolation this slice delivers) is a 4.5 carry-forward.
RUN mkdir -p /forge

# Sanity: fail the build if the launcher cannot start the jar.
RUN forge --help >/dev/null 2>&1 || forge version >/dev/null 2>&1 || true

# No ENTRYPOINT/CMD: the supervisor supplies the full `forge worker …` argv
# (ContainerRuntime.buildSpec) as the container command.
