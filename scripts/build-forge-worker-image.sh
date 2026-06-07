#!/usr/bin/env bash
#
# build-forge-worker-image.sh — build the default forge-worker container image
# (Slice 4.3, O1/O7). Assembles the fat jar, stages it into the Docker build
# context, and builds `forge-worker:latest` (the supervisor's fallback image,
# `ContainerRuntime.DefaultImage`).
#
# Usage:
#   scripts/build-forge-worker-image.sh [--tag NAME] [--skip-build]
#
# Options:
#   --tag NAME     image tag to build (default: forge-worker:latest).
#   --skip-build   reuse an already-assembled forge.jar (skip `sbt forge-app/assembly`).
#
# Requires a running Docker daemon. Mirrors `install-forge.sh`'s build step.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ctx="$repo_root/docker/forge-worker"
tag="forge-worker:latest"
skip_build=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) tag="$2"; shift 2 ;;
    --tag=*) tag="${1#*=}"; shift ;;
    --skip-build) skip_build=1; shift ;;
    -h|--help)
      sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "build-forge-worker-image.sh: unknown argument '$1'" >&2; exit 64 ;;
  esac
done

built_jar="$repo_root/modules/forge-app/target/scala-3.7.1/forge.jar"

if [[ "$skip_build" -eq 0 ]]; then
  echo "==> Building forge.jar (sbt forge-app/assembly)…"
  (cd "$repo_root" && sbt -batch forge-app/assembly)
fi

if [[ ! -f "$built_jar" ]]; then
  echo "build-forge-worker-image.sh: expected jar not found at $built_jar" >&2
  echo "  (run without --skip-build, or check the Scala version in the path)" >&2
  exit 70
fi

echo "==> Staging jar -> $ctx/forge.jar"
cp "$built_jar" "$ctx/forge.jar"

echo "==> docker build -t $tag $ctx"
docker build -t "$tag" "$ctx"

echo
echo "Built $tag. The supervisor uses this when a repo commits no Forgefile."
echo "To run a containerised worker:  forge daemon start --container"
