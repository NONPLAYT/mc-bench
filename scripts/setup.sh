#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/bench.conf"
mkdir -p "$ROOT/jars" "$ROOT/work"

MANIFEST="$ROOT/jars/builds.txt"
: > "$MANIFEST"

resolve_and_fetch() {
  local name="$1" url="$2" build="$3"
  local out="$ROOT/jars/$name.jar"
  echo "$name build $build"
  if [[ ! -f "$out" ]] || ! grep -q "^$name $build " "$MANIFEST.prev" 2>/dev/null; then
    curl -sSL --fail -o "$out" "$url"
  fi
  printf '%s %s %s %s\n' "$name" "$build" "$(sha256sum "$out" | cut -d' ' -f1)" "$url" >> "$MANIFEST"
}

[[ -f "$MANIFEST" ]] && cp "$MANIFEST" "$MANIFEST.prev" 2>/dev/null || true

read -r PAPER_BUILD PAPER_URL <<< "$(curl -sS "https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds" \
  | python3 -c 'import json,sys; b=json.load(sys.stdin)[0]; print(b["id"], b["downloads"]["server:default"]["url"])')"
resolve_and_fetch paper "$PAPER_URL" "$PAPER_BUILD"

PURPUR_BUILD="$(curl -sS "https://api.purpurmc.org/v2/purpur/$MC_VERSION" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["builds"]["latest"])')"
resolve_and_fetch purpur "https://api.purpurmc.org/v2/purpur/$MC_VERSION/$PURPUR_BUILD/download" "$PURPUR_BUILD"

LEAF_BUILD="$(curl -sS "https://api.leafmc.one/v2/projects/leaf/versions/$MC_VERSION" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["builds"][-1])')"
resolve_and_fetch leaf \
  "https://api.leafmc.one/v2/projects/leaf/versions/$MC_VERSION/builds/$LEAF_BUILD/downloads/leaf-$MC_VERSION-$LEAF_BUILD.jar" \
  "$LEAF_BUILD"

read -r DMC_BUILD DMC_URL <<< "$(curl -sS "https://api.bxteam.org/v1/builds/divinemc/$MC_VERSION/latest" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["build"], d["downloads"]["application"]["url"])')"
resolve_and_fetch divinemc "$DMC_URL" "$DMC_BUILD"

curl -sSL --fail -o "$ROOT/jars/Chunky.jar" \
  "https://cdn.modrinth.com/data/fALzjamp/versions/MdY6JATr/Chunky-Bukkit-1.5.3.jar"

rm -f "$MANIFEST.prev"
echo
echo "builds under test (publish this):"
cat "$MANIFEST"

if [[ ! -f "$ROOT/jars/SoulFireCLI.jar" ]]; then
  echo
  echo "missing jars/SoulFireCLI.jar - download it from"
  echo "  https://github.com/soulfiremc-com/SoulFire/releases/latest"
  echo "and save it as jars/SoulFireCLI.jar (the desktop app cannot be scripted)"
  exit 1
fi

if [[ ! -d "$ROOT/work/apibuild/libraries" ]]; then
  mkdir -p "$ROOT/work/apibuild"
  ( cd "$ROOT/work/apibuild" && java -Dpaperclip.patchonly=true -jar "$ROOT/jars/paper.jar" )
fi

"$ROOT/scripts/build-agent.sh"
"$ROOT/scripts/prime.sh"
