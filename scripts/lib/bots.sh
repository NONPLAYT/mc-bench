# shellcheck shell=bash

BOT_JAR="$ROOT/jars/SoulFireCLI.jar"

write_account_file() {
  local file="$1" count="$2"
  python3 -c "
import sys
n = int(sys.argv[2])
open(sys.argv[1], 'w').write('\n'.join(f'Bench{i:04d}' for i in range(n)) + '\n')
" "$file" "$count"
}

count_joined() {
  local n
  n=$( { grep -ac "joined the game" "$SERVER_LOG" 2>/dev/null || true; } | head -1 )
  echo "${n:-0}"
}

start_bots() {
  local outdir="$1" count="$2"
  write_account_file "$outdir/accounts.txt" "$count"
  java -Xms2G -Xmx"$BOT_HEAP" -jar "$BOT_JAR" \
    --bot-address "$BOT_TARGET" \
    --account-file "$outdir/accounts.txt" --account-type OFFLINE \
    --bot-join-delay-min "$BOT_JOIN_DELAY_MIN" --bot-join-delay-max "$BOT_JOIN_DELAY_MAX" \
    --bot-concurrent-connects "$BOT_CONCURRENT_CONNECTS" \
    --anti-afk-enabled \
    --anti-afk-distance-min "$BOT_WALK_MIN" --anti-afk-distance-max "$BOT_WALK_MAX" \
    --anti-afk-delay-min "$BOT_WALK_DELAY_MIN" --anti-afk-delay-max "$BOT_WALK_DELAY_MAX" \
    -s > "$outdir/bots.log" 2>&1 &
  BOT_PID=$!
  python3 "$ROOT/scripts/lib/sysmon.py" "$BOT_PID" "$outdir/bots-sysmon.csv" 1.0 &
  BOT_SYSMON_PID=$!
}

wait_for_bots() {
  local outdir="$1" count="$2" timeout="$3" waited=0
  local joined
  while joined=$(count_joined); (( joined < count )); do
    if ! kill -0 "$BOT_PID" 2>/dev/null; then
      echo "  !! bot client died; tail:" >&2
      tail -15 "$outdir/bots.log" >&2
      return 1
    fi
    sleep 2
    waited=$((waited + 2))
    if (( waited > timeout )); then
      echo "  !! only $(count_joined) of $count bots joined in ${timeout}s" >&2
      return 1
    fi
  done
  return 0
}

spread_bots_on_grid() {
  local count="$1" spacing="$2" y="$3"
  local side idx x z
  side=$(python3 -c "import math,sys; print(int(math.ceil(math.sqrt(int(sys.argv[1])))))" "$count")
  idx=0
  while (( idx < count )); do
    x=$(( (idx % side - side / 2) * spacing ))
    z=$(( (idx / side - side / 2) * spacing ))
    send_cmd "$(printf 'tp Bench%04d %d %d %d' "$idx" "$x" "$y" "$z")"
    idx=$((idx + 1))
  done
}

spread_bots_in_clusters() {
  local count="$1" clusters="$2" separation="$3" y="$4"
  local idx cluster cx cz ox oz
  idx=0
  while (( idx < count )); do
    cluster=$(( idx % clusters ))
    cx=$(( (cluster % 4) * separation - separation ))
    cz=$(( (cluster / 4) * separation - separation ))
    ox=$(( (idx / clusters % 5) * 8 - 16 ))
    oz=$(( (idx / clusters / 5) * 8 - 16 ))
    send_cmd "$(printf 'tp Bench%04d %d %d %d' "$idx" "$((cx + ox))" "$y" "$((cz + oz))")"
    idx=$((idx + 1))
  done
}

stop_bots() {
  kill "$BOT_PID" 2>/dev/null || true
  kill "$BOT_SYSMON_PID" 2>/dev/null || true
  wait "$BOT_PID" 2>/dev/null || true
}
