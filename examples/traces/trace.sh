#!/usr/bin/env bash
# Runs one tick script in a fresh world and keeps its per-tick trace (make the server dir with `mcs.py new --flat`,
# the scripts build in the air at y 98-104):
#   trace.sh <server-dir> <script.txt> <ticks> <label>     ->  ./trace-<label>.txt
# Two runs that should behave the same (option off vs on, old jar vs new) must give identical files:
#   cmp trace-off.txt trace-on.txt    (the first differing line is the first tick that differs)
# Run the same arm twice first: a scenario that differs off vs off is nondeterministic and proves nothing.
# The box traced is x/z 0..47, y 90..110 of the overworld; the scripts build there and forceload 0 0 47 31.
set -u
S="${1:?usage: trace.sh <server-dir> <script> <ticks> <label>}"; SCRIPT="$2"; TICKS="$3"; LABEL="$4"
BENCH="$(cd "$(dirname "$0")/../.." && pwd)"
mcs() { python3 -I "$BENCH/scripts/mcs.py" "$@"; }
rm -rf "$S/world" "$S/world_nether" "$S/world_the_end" "$S/agent-out"
cp "$SCRIPT" "$S/script.txt"
mcs start "$S" --xmx "${XMX:-3G}" >/dev/null && mcs wait "$S" >/dev/null && {
  mcs cmd "$S" forceload add 0 0 47 31 >/dev/null
  sleep 3
  mcs cmd "$S" ta script run script.txt "$TICKS" box=0,90,0,47,110,47 >/dev/null
  sleep $(( TICKS / 20 + 8 ))
  cp "$S/agent-out/trace-script.txt" "trace-$LABEL.txt" && echo "trace-$LABEL.txt: $(wc -l < "trace-$LABEL.txt") ticks"
  mcs errors "$S" | grep -v '^\s*at ' | head -n 10
}
mcs stop "$S" >/dev/null
