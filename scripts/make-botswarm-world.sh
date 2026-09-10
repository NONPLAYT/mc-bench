#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/bench.conf"
source "$ROOT/scripts/lib/server.sh"
source "$ROOT/scripts/lib/profiles.sh"

"$ROOT/scripts/make-summons.sh"

dir="$(server_dir paper botswarmgen)"
out="$ROOT/work/botswarmgen"
rm -rf "$dir" "$out"; mkdir -p "$dir"
cp -a "$(server_dir paper prime)/." "$dir/"
rm -rf "$dir/world" "$dir/world_nether" "$dir/world_the_end" "$dir/logs"
provision_server paper botswarmgen
apply_profile paper stock "$dir" "$SEED_BOTSWARM"

echo "generating botswarm world (default terrain, radius $BOTSWARM_WORLD_RADIUS)"
start_server "$dir" "6144M" "$out"
wait_for_ready "$out" "$BOOT_TIMEOUT"
send_cmd "chunky world world"
send_cmd "chunky center 0 0"
send_cmd "chunky shape square"
send_cmd "chunky radius $BOTSWARM_WORLD_RADIUS"
sleep 2
send_cmd "chunky start"
wait_for_log "Task finished for" 3600
sleep 3
stop_server 300

mkdir -p "$ROOT/work/worlds/botswarm"
rm -rf "$ROOT/work/worlds/botswarm/world"
cp -a "$dir/world" "$ROOT/work/worlds/botswarm/world"
du -sh "$ROOT/work/worlds/botswarm/world"
