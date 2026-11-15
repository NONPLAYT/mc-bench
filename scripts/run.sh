#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/bench.conf"
source "$ROOT/scripts/lib/server.sh"
source "$ROOT/scripts/lib/profiles.sh"

SCENARIO="${1:-}"; shift || true
case "$SCENARIO" in
  rctclusters|rctblocks) PROFILES=(rct) ;;
  *)           PROFILES=(stock parity max) ;;
esac
SELECTED_BUILDS=("${BUILDS[@]}")
RADIUS="$CHUNKGEN_RADIUS"
DRY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs)     RUNS="$2"; shift 2 ;;
    --profiles) IFS=, read -r -a PROFILES <<< "$2"; shift 2 ;;
    --builds)   IFS=, read -r -a SELECTED_BUILDS <<< "$2"; shift 2 ;;
    --radius)   RADIUS="$2"; shift 2 ;;
    --verify)   DRY=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$SCENARIO" ]] || { echo "usage: run.sh <chunkgen|players|rctclusters|rctblocks> [--runs N] [--profiles a,b] [--builds a,b] [--radius R] [--verify]" >&2; exit 2; }

MATRIX=()
for b in "${SELECTED_BUILDS[@]}"; do
  for p in "${PROFILES[@]}"; do
    if [[ "$p" == "max" && ( "$b" == "paper" || "$b" == "purpur" ) ]]; then continue; fi
    if [[ "$p" == "parity" && ( "$b" == "paper" || "$b" == "purpur" ) ]]; then continue; fi
    if [[ "$p" == "rct" && "$b" != divinemc* ]]; then continue; fi
    MATRIX+=("$b:$p")
  done
done
for b in "${SELECTED_BUILDS[@]}"; do
  [[ "$b" == "paper" || "$b" == "purpur" ]] && MATRIX+=("$b:stock")
done
readarray -t MATRIX < <(printf '%s\n' "${MATRIX[@]}" | awk '!seen[$0]++')

echo "matrix (${#MATRIX[@]} cells): ${MATRIX[*]}"
echo "repetitions: $RUNS   scenario: $SCENARIO"

prepare_cell() {
  local build="$1" profile="$2" seed="$3" dir
  dir="$(server_dir "$build" "$profile")"
  if [[ ! -f "$dir/config/paper-global.yml" ]]; then
    rm -rf "$dir"
    mkdir -p "$dir"
    cp -a "$(server_dir "$build" prime)/." "$dir/"
    rm -rf "$dir/world" "$dir/world_nether" "$dir/world_the_end" "$dir/logs" "$dir/console.log"
  fi
  provision_server "$build" "$profile"
  apply_profile "$build" "$profile" "$dir" "$seed"
}

SEED_VAR="SEED_$(echo "$SCENARIO" | tr '[:lower:]' '[:upper:]')"
SEED="${!SEED_VAR}"

for cell in "${MATRIX[@]}"; do
  IFS=: read -r build profile <<< "$cell"
  echo "-- preparing $build/$profile"
  prepare_cell "$build" "$profile" "$SEED"
done

if [[ "$DRY" == 1 ]]; then
  echo "verify only: all profiles applied cleanly, no run performed."
  exit 0
fi

run_chunkgen() {
  local build="$1" profile="$2" out="$3" dir
  dir="$(server_dir "$build" "$profile")"
  rm -rf "$dir/world" "$dir/world_nether" "$dir/world_the_end"

  start_server "$dir" "$CHUNKGEN_XMX" "$out"
  wait_for_ready "$out" "$BOOT_TIMEOUT" || return 1

  send_cmd "chunky world world"
  send_cmd "chunky center 0 0"
  send_cmd "chunky shape circle"
  send_cmd "chunky radius $RADIUS"
  sleep 2
  send_cmd "ta mark gen_start"
  send_cmd "chunky start"

  wait_for_log "Task finished for" "$CHUNKGEN_TIMEOUT" 0.2 || return 1
  send_cmd "ta mark gen_end"
  sleep 2
  return 0
}

prepare_world() {
  local random_tick_speed="$1"
  send_cmd "gamerule spawn_mobs false"
  send_cmd "gamerule advance_weather false"
  send_cmd "gamerule fall_damage false"
  send_cmd "gamerule random_tick_speed $random_tick_speed"
  send_cmd "time set midnight"
  send_cmd "weather clear"
  sleep 10
}

measure() {
  local warmup="$1" seconds="$2"
  echo "     warmup ${warmup}s"
  sleep "$warmup"
  send_cmd "ta reset"
  send_cmd "ta mark measure_start"
  echo "     measuring ${seconds}s"
  sleep "$seconds"
  send_cmd "ta mark measure_end"
  sleep 2
  send_cmd "kill @e[type=!minecraft:player]"
  sleep 3
}

summon_from() {
  while read -r cmd; do send_cmd "$cmd"; done < "$1"
}

# fake players come first so their chunks are loaded before the mobs are summoned into them
run_players() {
  local build="$1" profile="$2" out="$3" dir
  dir="$(server_dir "$build" "$profile")"
  rm -rf "$dir/world" "$dir/world_nether" "$dir/world_the_end"
  cp -a "$ROOT/work/worlds/players/world" "$dir/world"

  start_server "$dir" "$PLAYERS_XMX" "$out"
  wait_for_ready "$out" "$BOOT_TIMEOUT" || return 1
  prepare_world 3

  send_cmd "ta mark join_start"
  send_cmd "ta fake spawn $PLAYERS_COUNT layout=grid spacing=$PLAYERS_GRID_SPACING origin=0,0 radius=$PLAYERS_WANDER_RADIUS speed=$PLAYERS_SPEED y=$PLAYERS_Y"
  send_cmd "ta mark join_end"
  sleep 20

  send_cmd "ta mark spawn_start"
  summon_from "$ROOT/work/scenarios/players-summons.txt"
  send_cmd "ta mark spawn_end"

  measure "$PLAYERS_WARMUP_SECONDS" "$PLAYERS_MEASURE_SECONDS"
  return 0
}

run_rct() {
  local build="$1" profile="$2" out="$3" random_tick_speed="$4" extra="$5" dir
  dir="$(server_dir "$build" "$profile")"
  rm -rf "$dir/world" "$dir/world_nether" "$dir/world_the_end"
  cp -a "$ROOT/work/worlds/players/world" "$dir/world"

  start_server "$dir" "$RCT_XMX" "$out"
  wait_for_ready "$out" "$BOOT_TIMEOUT" || return 1
  prepare_world "$random_tick_speed"

  send_cmd "ta mark join_start"
  send_cmd "ta fake spawn $RCT_PLAYERS layout=cluster clusters=$RCT_CLUSTERS separation=$RCT_SEPARATION origin=0,0 radius=$RCT_CLUSTER_RADIUS speed=$PLAYERS_SPEED y=$PLAYERS_Y"
  send_cmd "ta mark join_end"
  sleep 20

  send_cmd "ta mark spawn_start"
  summon_from "$ROOT/work/scenarios/rct-summons.txt"
  if [[ -n "$extra" ]]; then
    summon_from "$extra"
  fi
  send_cmd "ta mark spawn_end"

  measure "$RCT_WARMUP_SECONDS" "$RCT_MEASURE_SECONDS"
  return 0
}

run_rctclusters() {
  run_rct "$1" "$2" "$3" 3 ""
}

run_rctblocks() {
  run_rct "$1" "$2" "$3" "$RCTBLOCKS_RANDOM_TICK_SPEED" "$ROOT/work/scenarios/rct-redstone.txt"
}

total=$(( RUNS * ${#MATRIX[@]} ))
done_count=0
started_at=$(date +%s)

for (( run = 1; run <= RUNS; run++ )); do
  n=${#MATRIX[@]}
  for (( k = 0; k < n; k++ )); do
    idx=$(( (k + run - 1) % n ))
    IFS=: read -r build profile <<< "${MATRIX[$idx]}"
    out="$ROOT/work/raw/$SCENARIO/${build}__${profile}/run$run"

    if [[ -f "$out/COMPLETE" ]]; then
      echo "[$((++done_count))/$total] skip $build/$profile run$run (already complete)"
      continue
    fi
    rm -rf "$out"; mkdir -p "$out"
    rm -rf "$(server_dir "$build" "$profile")/tracking"

    echo "[$((++done_count))/$total] $build/$profile run$run  ($(date +%H:%M:%S))"
    ok=0
    if "run_$SCENARIO" "$build" "$profile" "$out"; then ok=1; fi
    stop_server 240

    dir="$(server_dir "$build" "$profile")"
    cp "$dir/console.log" "$out/" 2>/dev/null || true
    [[ -d "$dir/tracking" ]] && cp -a "$dir/tracking" "$out/"
    mkdir -p "$out/configs"
    ( cd "$dir" && tar cf - --exclude='./plugins' $(find . -maxdepth 2 \( -name '*.yml' -o -name '*.properties' \) -not -path './plugins/*') 2>/dev/null ) | tar xf - -C "$out/configs" 2>/dev/null || true

    if [[ "$ok" == 1 ]]; then
      touch "$out/COMPLETE"
    else
      echo "  !! run failed, marked incomplete" >&2
    fi

    sync
    sleep "$COOLDOWN_SECONDS"

    elapsed=$(( $(date +%s) - started_at ))
    if (( done_count > 0 )); then
      eta=$(( elapsed * (total - done_count) / done_count ))
      printf '     elapsed %dm, eta %dm\n' $(( elapsed / 60 )) $(( eta / 60 ))
    fi
  done
done

echo "done: $SCENARIO"
