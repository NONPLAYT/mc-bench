#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/bench.conf"

python3 - "$ROOT/work/scenarios/rct-summons.txt" <<PY
import math, sys, pathlib
out = pathlib.Path(sys.argv[1]); out.parent.mkdir(parents=True, exist_ok=True)
CLUSTERS, SEP, PER, Y = $RCT_CLUSTERS, $RCT_SEPARATION, $RCT_MOBS_PER_CLUSTER, $SUMMON_FROM_Y
MIX = [("zombie", 30), ("skeleton", 20), ("cow", 20), ("villager", 10), ("pig", 10), ("creeper", 10)]
lines = []
for c in range(CLUSTERS):
    # same centres as the agent's "layout=cluster origin=0,0"
    cx, cz = round(math.cos(2 * math.pi * c / CLUSTERS) * SEP), round(math.sin(2 * math.pi * c / CLUSTERS) * SEP)
    side = int(PER ** 0.5) + 1
    step = 64 / side
    i = 0
    for gx in range(side):
        for gz in range(side):
            if i >= PER:
                break
            acc, pick, r = 0, MIX[0][0], (i * 7) % 100
            for name, weight in MIX:
                acc += weight
                if r < acc:
                    pick = name
                    break
            x = round(cx - 32 + gx * step, 1)
            z = round(cz - 32 + gz * step, 1)
            lines.append(f"summon {pick} {x} {Y} {z} {{PersistenceRequired:1b}}")
            i += 1
out.write_text("\n".join(lines) + "\n")
print(f"wrote {len(lines)} summons across {CLUSTERS} clusters")
PY

python3 - "$ROOT/work/scenarios/rct-redstone.txt" <<PY
import math, sys, pathlib
out = pathlib.Path(sys.argv[1]); out.parent.mkdir(parents=True, exist_ok=True)
CLUSTERS, SEP, ROWS, LENGTH, Y = $RCT_CLUSTERS, $RCT_SEPARATION, $RCTBLOCKS_ROWS, $RCTBLOCKS_ROW_LENGTH, $RCTBLOCKS_Y
lines = []
for c in range(CLUSTERS):
    # same centres as the agent's "layout=cluster origin=0,0"
    cx, cz = round(math.cos(2 * math.pi * c / CLUSTERS) * SEP), round(math.sin(2 * math.pi * c / CLUSTERS) * SEP)
    z0, z1 = cz - LENGTH // 2, cz - LENGTH // 2 + LENGTH - 1
    for r in range(ROWS):
        x = cx - ROWS * 3 + r * 6
        lines.append(f"fill {x - 1} {Y} {z0} {x - 1} {Y} {z1} minecraft:stone")
        lines.append(f"fill {x} {Y} {z0} {x} {Y} {z1} minecraft:sticky_piston[facing=west]")
        lines.append(f"fill {x + 1} {Y} {z0} {x + 1} {Y} {z1} minecraft:observer[facing=east]")
        lines.append(f"fill {x + 2} {Y} {z0} {x + 2} {Y} {z1} minecraft:observer[facing=west]")
out.write_text("\n".join(lines) + "\n")
print(f"wrote {CLUSTERS * ROWS * LENGTH} observer clocks across {CLUSTERS} clusters")
PY

python3 - "$ROOT/work/scenarios/players-summons.txt" <<SUMMONS
import math, sys, pathlib
out = pathlib.Path(sys.argv[1]); out.parent.mkdir(parents=True, exist_ok=True)
TOTAL, SPACING, PLAYERS, Y = $PLAYERS_MOBS, $PLAYERS_GRID_SPACING, $PLAYERS_COUNT, $SUMMON_FROM_Y
MIX = [("zombie", 30), ("skeleton", 20), ("cow", 20), ("villager", 10), ("pig", 10), ("creeper", 10)]
# one cell per fake player, same grid as the agent's "layout=grid origin=0,0"
side = math.ceil(math.sqrt(PLAYERS))
per_cell = TOTAL // PLAYERS + 1
lines = []
i = 0
for p in range(PLAYERS):
    bx, bz = (p % side - side // 2) * SPACING, (p // side - side // 2) * SPACING
    for k in range(per_cell):
        if i >= TOTAL:
            break
        acc, pick, r = 0, MIX[0][0], (i * 7) % 100
        for name, weight in MIX:
            acc += weight
            if r < acc:
                pick = name
                break
        lines.append(f"summon {pick} {bx + (k % 8) * 6 - 24} {Y} {bz + (k // 8) * 6 - 24} {{PersistenceRequired:1b}}")
        i += 1
out.write_text("\n".join(lines) + "\n")
print(f"wrote {len(lines)} player-grid summons")
SUMMONS

