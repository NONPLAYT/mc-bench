#!/usr/bin/env bash
# Alternates one load between two prepared server dirs (different jars, or one jar with two configs):
#   ab.sh <load> <dir-a> <dir-b> [pairs=3] [measure-seconds=120]
# Runs A B A B ... with a cooldown in between; compare the spread of the per-run numbers, not single values.
set -u
LOAD="$(dirname "$0")/${1:?usage: ab.sh <load> <dir-a> <dir-b> [pairs] [seconds]}.sh"
A="${2:?dir-a}"; B="${3:?dir-b}"; PAIRS="${4:-3}"; SECONDS_="${5:-120}"
BENCH="$(cd "$(dirname "$0")/../.." && pwd)"
python3 -I "$BENCH/scripts/mcs.py" idle || echo "machine is busy: these numbers include that load" >&2
for (( i = 1; i <= PAIRS; i++ )); do
  for d in "$A" "$B"; do
    bash "$LOAD" "$d" "$SECONDS_"
    sync
    sleep "${COOLDOWN:-90}"
  done
done
