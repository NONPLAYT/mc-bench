# shellcheck shell=bash

server_dir() { echo "$ROOT/work/servers/$1__$2"; }

provision_server() {
  local build="$1" profile="$2" dir
  dir="$(server_dir "$build" "$profile")"
  mkdir -p "$dir/plugins"
  local jarvar="JAR_${build}"
  ln -sfn "$ROOT/jars/${!jarvar}" "$dir/server.jar"
  echo "eula=true" > "$dir/eula.txt"
  cp "$ROOT/jars/BenchAgent.jar" "$dir/plugins/"
  cp "$ROOT/jars/Chunky.jar" "$dir/plugins/"
}

start_server() {
  local dir="$1" xmx="$2" outdir="$3"
  if (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ":25565 "; then
    echo "  !! port 25565 is already in use; a previous server is still running" >&2
    return 1
  fi
  SERVER_LOG="$dir/console.log"
  : > "$SERVER_LOG"
  rm -f "$outdir/ready"
  mkdir -p "$outdir"

  rm -f "$dir/stdin.fifo"
  mkfifo "$dir/stdin.fifo"
  exec {SERVER_FIFO_FD}<>"$dir/stdin.fifo"

  local -a pin=()
  if [[ -n "${SERVER_CPUS:-}" ]]; then
    command -v taskset >/dev/null || { echo "  !! SERVER_CPUS is set but taskset is missing" >&2; return 1; }
    pin=(taskset -c "$SERVER_CPUS")
  fi

  ( cd "$dir" && exec ${pin[@]+"${pin[@]}"} java \
      "-Xms$xmx" "-Xmx$xmx" \
      "${JVM_FLAGS_COMMON[@]}" \
      "-Dbench.out=$outdir" \
      "-Dbench.capacity=$((20 * 60 * 150))" \
      -jar server.jar --nogui \
      < "$dir/stdin.fifo" > "$SERVER_LOG" 2>&1 ) &
  SERVER_PID=$!

  python3 "$ROOT/scripts/lib/sysmon.py" "$SERVER_PID" "$outdir/sysmon.csv" 1.0 &
  SYSMON_PID=$!
}

send_cmd() {
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    return 1
  fi
  printf '%s\n' "$1" >&"$SERVER_FIFO_FD"
}

wait_for_ready() {
  local outdir="$1" timeout="$2" waited=0
  while [[ ! -f "$outdir/ready" ]]; do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "  !! server died during startup; tail of console:" >&2
      tail -25 "$SERVER_LOG" >&2
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
    if (( waited > timeout )); then
      echo "  !! server did not become ready within ${timeout}s" >&2
      tail -25 "$SERVER_LOG" >&2
      return 1
    fi
  done
  return 0
}

wait_for_log() {
  local pattern="$1" timeout="$2" interval="${3:-2}" deadline
  deadline=$(( $(date +%s) + timeout ))
  while ! grep -qF "$pattern" "$SERVER_LOG"; do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "  !! server died while waiting for: $pattern" >&2
      tail -25 "$SERVER_LOG" >&2
      return 1
    fi
    if (( $(date +%s) > deadline )); then
      echo "  !! timed out (${timeout}s) waiting for: $pattern" >&2
      return 1
    fi
    sleep "$interval"
  done
  return 0
}

stop_server() {
  local timeout="${1:-180}" waited=0
  send_cmd "bench dump" || true
  sleep 2
  send_cmd "stop" || true
  while kill -0 "$SERVER_PID" 2>/dev/null; do
    sleep 1
    waited=$((waited + 1))
    if (( waited > timeout )); then
      echo "  !! server ignored /stop, killing" >&2
      kill -9 "$SERVER_PID" 2>/dev/null || true
      break
    fi
  done
  wait "$SERVER_PID" 2>/dev/null || true
  kill "$SYSMON_PID" 2>/dev/null || true
  exec {SERVER_FIFO_FD}>&- || true
}
