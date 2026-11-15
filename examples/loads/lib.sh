# shellcheck shell=bash
# Sourced by the load scripts. Usage of every load: <load>.sh <server-dir> [measure-seconds]
# The server dir is one made with `scripts/mcs.py new`; each run starts from a fresh world, or from a copy of
# WORLD=<dir holding world/, world_nether/, ...> when the load should not include world generation.

BENCH="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
S="${1:?usage: $(basename "$0") <server-dir> [measure-seconds]}"
WINDOW="${2:-120}"
XMX="${XMX:-6G}"

mcs() { python3 -I "$BENCH/scripts/mcs.py" "$@"; }
c() { mcs cmd "$S" "$@" >/dev/null; }

boot() {
  rm -rf "$S/world" "$S/world_nether" "$S/world_the_end" "$S/agent-out"
  if [[ -n "${WORLD:-}" ]]; then
    cp -a "$WORLD"/world* "$S"/
  fi
  mcs start "$S" --xmx "$XMX" >/dev/null && mcs wait "$S" >/dev/null
}

# a quiet world: nothing spawns or changes unless the load does it
quiet() {
  c gamerule spawn_mobs false
  c gamerule advance_time false
  c gamerule advance_weather false
  c time set midnight
}

# measure <label>: one window of WINDOW seconds, printed as one line
measure() {
  c ta mark "$1_start"
  sleep "$WINDOW"
  c ta mark "$1_end"
  c ta dump
  echo "$1 $(basename "$S"): $(mcs ticks "$S" --from-mark "$1_start" --to-mark "$1_end" | tr '\n' ' ')"
}

finish() {
  mcs errors "$S" | grep -v '^\s*at ' | head -n 10
  mcs stop "$S" >/dev/null
}
