# shellcheck shell=bash

Y() { python3 "$ROOT/scripts/lib/yamlset.py" "$@"; }
P() { python3 "$ROOT/scripts/lib/propset.py" "$@"; }

CHUNK_WORKER_THREADS=6
CHUNK_IO_THREADS=4

apply_common() {
  local dir="$1" seed="$2"
  P "$dir/server.properties" \
    "level-seed=$seed" \
    "online-mode=false" \
    "view-distance=$VIEW_DISTANCE" \
    "simulation-distance=$SIMULATION_DISTANCE" \
    "max-players=$MAX_PLAYERS" \
    "spawn-protection=0" \
    "difficulty=normal" \
    "gamemode=survival" \
    "enable-status=false" \
    "network-compression-threshold=256" \
    "sync-chunk-writes=false"

  Y "$dir/config/paper-global.yml" \
    "chunk-system.worker-threads=$CHUNK_WORKER_THREADS" \
    "chunk-system.io-threads=$CHUNK_IO_THREADS"

  Y "$dir/bukkit.yml" "ticks-per.autosave=0"
}

apply_parity_divinemc() {
  local dir="$1"
  Y "$dir/divinemc.yml" \
    "misc.lag-compensation.enabled=false" \
    "misc.secure-seed.enable=false" \
    "async.pathfinding.enable=false" \
    "async.parallel-entity-tracker.enable=false" \
    "async.mob-spawning.enable=false" \
    "async.mob-spawning.async-natural-spawn=false" \
    "async.portal-search-prefetch.enable=false" \
    "async.parallel-world-ticking.enable=false" \
    "async.regionized-chunk-ticking.enable=false" \
    "async.chunk-sending.enable=false" \
    "async.parallel-sensors.enable=false" \
    "performance.virtual-threads.enabled=false" \
    "performance.dab.enabled=false" \
    "performance.optimizations.clump-orbs=false" \
    "region-settings.type=MCA" \
    "fixes.misc.disable-leaf-decay=false" \
    "misc.old-features.copper-bulb-1gt=false" \
    "misc.old-features.crafter-1gt=false"
}

apply_parity_leaf() {
  local dir="$1"
  Y "$dir/config/leaf-global.yml" \
    "async.async-chunk-send.enabled=false" \
    "async.async-mob-spawning.enabled=false" \
    "async.async-pathfinding.enabled=false" \
    "async.async-playerdata-save.enabled=false" \
    "async.async-entity-tracker.enabled=false" \
    "async.parallel-world-ticking.enabled=false" \
    "performance.dab.enabled=false" \
    "performance.faster-random-generator.enabled=false" \
    "performance.faster-random-generator.enable-for-worldgen=false" \
    "performance.use-virtual-thread.bukkit-async-scheduler=false" \
    "performance.use-virtual-thread.folia-async-scheduler=false" \
    "performance.use-virtual-thread.async-chat-executor=false" \
    "performance.use-virtual-thread.download-pool=false" \
    "misc.lag-compensation.enabled=false" \
    "misc.secure-seed.enabled=false" \
    "misc.region-format.format-name=MCA" \
    "gameplay-mechanisms.allow-tripwire-dupe=false"
}

apply_dfconly_divinemc() {
  local dir="$1"
  Y "$dir/divinemc.yml" \
    "performance.chunks.experimental.enable-density-function-compiler=true"
}

apply_dfconly_leaf() {
  local dir="$1"
  Y "$dir/config/leaf-global.yml" \
    "performance.density-function-compiler.enabled=true"
}

apply_rct_divinemc() {
  local dir="$1"
  Y "$dir/divinemc.yml" \
    "async.regionized-chunk-ticking.enable=true" \
    "async.regionized-chunk-ticking.executor-thread-count=$RCT_THREADS" \
    "async.parallel-world-ticking.enable=false"
}

apply_max_divinemc() {
  local dir="$1"
  Y "$dir/divinemc.yml" \
    "async.parallel-world-ticking.enable=true" \
    "async.regionized-chunk-ticking.enable=true" \
    "async.chunk-sending.enable=true" \
    "async.chunk-sending.max-threads=4" \
    "async.parallel-sensors.enable=true" \
    "async.pathfinding.enable=true" \
    "async.pathfinding.max-threads=2" \
    "async.parallel-entity-tracker.enable=true" \
    "performance.dab.enabled=true" \
    "performance.chunks.experimental.enable-density-function-compiler=true" \
    "performance.chunks.end-biome-cache-enabled=true" \
    "performance.optimizations.use-compact-bit-storage=true"
}

apply_max_leaf() {
  local dir="$1"
  Y "$dir/config/leaf-global.yml" \
    "async.async-chunk-send.enabled=true" \
    "async.async-mob-spawning.enabled=true" \
    "async.async-pathfinding.enabled=true" \
    "async.async-entity-tracker.enabled=true" \
    "async.parallel-world-ticking.enabled=true" \
    "async.parallel-world-ticking.threads=4" \
    "performance.dab.enabled=true" \
    "performance.density-function-compiler.enabled=true" \
    "performance.cache-biome.enabled=true" \
    "performance.cache-biome.mob-spawning=true" \
    "performance.optimize-mob-spawning=true" \
    "performance.optimize-entity-activation=true" \
    "performance.optimize-random-tick=true" \
    "performance.optimize-mob-despawn=true"
  Y "$dir/config/paper-world-defaults.yml" "entities.spawning.per-player-mob-spawns=true"
}

apply_purpur_dab() {
  local dir="$1" value="$2"
  [[ -f "$dir/purpur.yml" ]] || return 0
  Y "$dir/purpur.yml" "world-settings.default.gameplay-mechanics.entities-can-use-portals=true" 2>/dev/null || true
}

apply_profile() {
  local build="$1" profile="$2" dir="$3" seed="$4"
  apply_common "$dir" "$seed"

  case "$profile" in
    stock) : ;;
    parity)
      case "$build" in
        divinemc) apply_parity_divinemc "$dir" ;;
        leaf)     apply_parity_leaf "$dir" ;;
      esac
      ;;
    rct)
      case "$build" in
        divinemc) apply_rct_divinemc "$dir" ;;
      esac
      ;;
    dfconly)
      case "$build" in
        divinemc) apply_dfconly_divinemc "$dir" ;;
        leaf)     apply_dfconly_leaf "$dir" ;;
      esac
      ;;
    max)
      case "$build" in
        divinemc) apply_max_divinemc "$dir" ;;
        leaf)     apply_max_leaf "$dir" ;;
      esac
      ;;
    *) echo "unknown profile: $profile" >&2; return 1 ;;
  esac
}
