#!/usr/bin/env bash
#
# install-forge.sh — build and install the standalone `forge` launcher (roadmap §3.4).
#
# Builds the self-contained fat jar (`sbt forge-app/assembly`), copies it under
# ${FORGE_HOME:-$HOME/.forge}/lib/forge.jar, and installs the `bin/forge` launcher
# into a directory on your PATH (default ~/.local/bin). Idempotent — re-run after
# pulling new source to refresh the jar.
#
# Usage:
#   scripts/install-forge.sh [--bin-dir DIR] [--skip-build]
#
# Env:
#   FORGE_HOME   where the jar is installed (default ~/.forge).
#   PREFIX_BIN   default bin dir for the launcher (default ~/.local/bin); --bin-dir overrides.
#
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
forge_home="${FORGE_HOME:-$HOME/.forge}"
bin_dir="${PREFIX_BIN:-$HOME/.local/bin}"
skip_build=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bin-dir) bin_dir="$2"; shift 2 ;;
    --bin-dir=*) bin_dir="${1#*=}"; shift ;;
    --skip-build) skip_build=1; shift ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "install-forge.sh: unknown argument '$1'" >&2; exit 64 ;;
  esac
done

built_jar="$repo_root/modules/forge-app/target/scala-3.7.1/forge.jar"

if [[ "$skip_build" -eq 0 ]]; then
  echo "==> Building forge.jar (sbt forge-app/assembly)…"
  (cd "$repo_root" && sbt -batch forge-app/assembly)
fi

if [[ ! -f "$built_jar" ]]; then
  echo "install-forge.sh: expected jar not found at $built_jar" >&2
  echo "  (run without --skip-build, or check the Scala version in the path)" >&2
  exit 70
fi

echo "==> Installing jar -> $forge_home/lib/forge.jar"
mkdir -p "$forge_home/lib"
cp "$built_jar" "$forge_home/lib/forge.jar"

echo "==> Installing launcher -> $bin_dir/forge"
mkdir -p "$bin_dir"
cp "$repo_root/bin/forge" "$bin_dir/forge"
chmod +x "$bin_dir/forge"

echo
echo "Installed. Verify with:"
echo "    forge status        # run from inside any repo"
echo
if ! printf '%s' ":$PATH:" | grep -q ":$bin_dir:"; then
  echo "NOTE: $bin_dir is not on your PATH. Add it, e.g.:"
  echo "    export PATH=\"$bin_dir:\$PATH\""
fi
