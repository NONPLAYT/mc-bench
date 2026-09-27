#!/usr/bin/env bash
# Runs the whole published set unattended and can be restarted after a crash:
# run.sh skips runs that already completed, and each stage below keeps its own
# raw directory so the RCT thread sweep points never mix.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_TAG="${1:?usage: session.sh <host-tag>   e.g. session.sh 9950x}"
STATE="$ROOT/work/session"
RAW="$ROOT/work/raw"

mkdir -p "$STATE"
[[ -f "$STATE/date" ]] || date +%F > "$STATE/date"
OUT="$ROOT/results/$HOST_TAG/$(cat "$STATE/date")"

[[ -f "$ROOT/jars/SoulFireCLI.jar" ]] || { echo "missing jars/SoulFireCLI.jar" >&2; exit 1; }
[[ -f "$ROOT/bench.local.conf" ]] || { echo "missing bench.local.conf" >&2; exit 1; }
if [[ "$(RCT_THREADS=3 bash -c "source '$ROOT/bench.conf'; echo \$RCT_THREADS")" != 3 ]]; then
  echo "bench.local.conf pins RCT_THREADS; write it as RCT_THREADS=\${RCT_THREADS:-8} so the sweep can override it" >&2
  exit 1
fi
if [[ -z "$(bash -c "source '$ROOT/bench.conf'; echo \${SERVER_CPUS:-}")" ]]; then
  layout="$("$ROOT/scripts/cpu-layout.sh")"
  echo "$layout"
  if grep -q 'SERVER_CPUS=' <<< "$layout"; then
    command -v taskset >/dev/null || { echo "taskset is missing (util-linux)" >&2; exit 1; }
    {
      echo
      echo "# Server on the cache-heavy CCD, bots on the other one (scripts/cpu-layout.sh)."
      echo "# UNPINNED_SERVER=1 gives the server the whole chip, session.sh sets it for chunkgen."
      grep -oE 'SERVER_CPUS=[0-9,-]+' <<< "$layout" | sed 's/^/[[ -n "${UNPINNED_SERVER:-}" ]] || /'
      grep -oE 'BOT_CPUS=[0-9,-]+' <<< "$layout"
    } >> "$ROOT/bench.local.conf"
  else
    echo "warning: single L3 domain, the server and the bots are not pinned" >&2
  fi
fi
if grep -qv performance /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null; then
  echo "warning: CPU governor is not 'performance' on every core" >&2
fi
if [[ -n "$(swapon --noheadings 2>/dev/null)" ]]; then
  echo "warning: swap is on, run swapoff -a" >&2
fi

if [[ ! -f "$STATE/setup.done" ]]; then
  for stale in "$RAW" "$ROOT/work/servers"; do
    if [[ -d "$stale" ]]; then
      mv "$stale" "$stale.before-$(date +%Y%m%d-%H%M%S)"
    fi
  done
  "$ROOT/scripts/setup.sh"
  "$ROOT/scripts/make-summons.sh"
  touch "$STATE/setup.done"
fi
if [[ ! -d "$ROOT/work/worlds/botswarm/world" ]]; then
  "$ROOT/scripts/make-botswarm-world.sh"
fi

activate() {
  local scenario="$1" tag="$2" live="$RAW/$1"
  if [[ -d "$live" ]]; then
    local owner
    owner="$(cat "$live/.stage" 2>/dev/null || echo unknown)"
    [[ "$owner" == "$tag" ]] && return 0
    mv "$live" "$RAW/$scenario-$owner"
  fi
  if [[ -d "$RAW/$scenario-$tag" ]]; then
    mv "$RAW/$scenario-$tag" "$live"
  else
    mkdir -p "$live"
  fi
  echo "$tag" > "$live/.stage"
}

park() {
  mv "$RAW/$1" "$RAW/$1-$2"
}

stage() {
  local scenario="$1" tag="$2" threads="$3"
  shift 3
  echo
  echo "===== $scenario $tag (RCT_THREADS=$threads)  $(date '+%F %T')"
  activate "$scenario" "$tag"
  if [[ "$scenario" == chunkgen ]]; then
    UNPINNED_SERVER=1 RCT_THREADS="$threads" "$ROOT/scripts/run.sh" "$scenario" "$@"
  else
    RCT_THREADS="$threads" "$ROOT/scripts/run.sh" "$scenario" "$@"
  fi
  park "$scenario" "$tag"
}

publish() {
  local scenario="$1" tag="$2" name="$3" baseline_from="${4:-}"
  activate "$scenario" "$tag"
  if [[ -n "$baseline_from" ]]; then
    for cell in paper__stock purpur__stock; do
      rm -rf "$RAW/$scenario/$cell"
      cp -a "$RAW/$scenario-$baseline_from/$cell" "$RAW/$scenario/$cell"
    done
  fi
  "$ROOT/scripts/aggregate.py" "$scenario"
  cp "$ROOT/results/$scenario.json" "$OUT/$name.json"
  if [[ -n "$baseline_from" ]]; then
    rm -rf "$RAW/$scenario/paper__stock" "$RAW/$scenario/purpur__stock"
  fi
  park "$scenario" "$tag"
}

stage chunkgen    main 8
stage botswarm    main 8  --profiles stock,parity,max,rct
stage rctclusters t4   4  --builds divinemc
stage rctclusters t8   8
stage rctclusters t12  12 --builds divinemc
stage rctblocks   main 8

mkdir -p "$OUT"
publish chunkgen    main chunkgen
publish botswarm    main botswarm
publish rctclusters t4   rctclusters-t4  t8
publish rctclusters t8   rctclusters-t8
publish rctclusters t12  rctclusters-t12 t8
publish rctblocks   main rctblocks

cp "$ROOT/bench.local.conf" "$ROOT/jars/builds.txt" "$OUT/"
"$ROOT/scripts/report.py" "$OUT"
tar czf "$OUT/raw.tar.gz" -C "$ROOT/work" \
  raw/chunkgen-main raw/botswarm-main raw/rctclusters-t4 raw/rctclusters-t8 raw/rctclusters-t12 raw/rctblocks-main
echo
echo "session complete: $OUT"
