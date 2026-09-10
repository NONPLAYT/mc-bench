#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/bench.conf"
source "$ROOT/scripts/lib/server.sh"

PROFILE="prime"
for build in "${BUILDS[@]}"; do
  dir="$(server_dir "$build" "$PROFILE")"
  out="$ROOT/work/prime/$build"
  echo "== priming $build"
  rm -rf "$dir" "$out"
  provision_server "$build" "$PROFILE"
  start_server "$dir" "2048M" "$out"
  if wait_for_ready "$out" "$BOOT_TIMEOUT"; then
    sleep 20
    send_cmd "bench mark prime"
    boot_ms=$(( $(cat "$out/ready") - $(grep -o '"enableEpochMs": [0-9]*' "$out/agent-meta.json" 2>/dev/null | grep -o '[0-9]*' || echo 0) ))
    echo "   ready"
  else
    echo "   FAILED TO START" >&2
  fi
  stop_server 120
  echo "   ticks recorded: $(( $(wc -l < "$out/ticks.csv" 2>/dev/null || echo 1) - 1 ))"
  echo "   configs generated:"
  ( cd "$dir" && find . -maxdepth 2 \( -name '*.yml' -o -name '*.properties' -o -name '*.toml' \) \
      -not -path './plugins/*' | sort | sed 's/^/     /' )
done
