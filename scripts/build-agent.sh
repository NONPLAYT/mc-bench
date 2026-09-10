#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

API_JAR="$(find "$ROOT/work/apibuild/libraries/io/papermc/paper/paper-api" -name '*.jar' 2>/dev/null | head -1)"
if [[ -z "$API_JAR" ]]; then
  echo "paper-api jar not found; run scripts/setup.sh first" >&2
  exit 1
fi
CP="$(find "$ROOT/work/apibuild/libraries" -name '*.jar' | tr '\n' ':')"

OUT="$ROOT/work/agent-build"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

javac -nowarn -encoding UTF-8 --release 21 \
  -cp "$CP" \
  -d "$OUT/classes" \
  "$ROOT"/agent/src/org/bxteam/bench/*.java

cp "$ROOT/agent/plugin.yml" "$OUT/classes/plugin.yml"
jar --create --file "$ROOT/jars/BenchAgent.jar" -C "$OUT/classes" .

echo "built $ROOT/jars/BenchAgent.jar against $(basename "$API_JAR")"
