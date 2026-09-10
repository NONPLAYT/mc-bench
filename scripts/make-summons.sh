#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/bench.conf"

python3 - "$ROOT/work/scenarios/rct-summons.txt" <<PY
import sys, pathlib
out = pathlib.Path(sys.argv[1]); out.parent.mkdir(parents=True, exist_ok=True)
CLUSTERS, SEP, PER, Y = $RCT_CLUSTERS, $RCT_SEPARATION, $RCT_MOBS_PER_CLUSTER, $SUMMON_FROM_Y
MIX = [("zombie", 30), ("skeleton", 20), ("cow", 20), ("villager", 10), ("pig", 10), ("creeper", 10)]
lines = []
for c in range(CLUSTERS):
    cx, cz = (c % 4) * SEP - SEP, (c // 4) * SEP - SEP
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

python3 - "$ROOT/work/scenarios/botswarm-summons.txt" <<SUMMONS
import sys, pathlib
out = pathlib.Path(sys.argv[1]); out.parent.mkdir(parents=True, exist_ok=True)
TOTAL, SPACING, BOTS, Y = $BOTSWARM_MOBS, $BOTSWARM_GRID_SPACING, $BOTSWARM_BOTS, $SUMMON_FROM_Y
MIX = [("zombie", 30), ("skeleton", 20), ("cow", 20), ("villager", 10), ("pig", 10), ("creeper", 10)]
side = int(BOTS ** 0.5) + 1
per_cell = TOTAL // (side * side) + 1
lines = []
i = 0
for gx in range(side):
    for gz in range(side):
        bx, bz = (gx - side // 2) * SPACING, (gz - side // 2) * SPACING
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
print(f"wrote {len(lines)} botswarm summons")
SUMMONS

