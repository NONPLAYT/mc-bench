# Writing your own tests

mc-bench gives you the pieces; the tests are yours. Everything here was written while working on DivineMC and is
kept as a starting point: copy a file, change the load or the invariant, keep the method.

| Piece | What it does |
|---|---|
| `scripts/mcs.py` | throwaway server dirs: `new`, `start`, `wait`, `cmd` (RCON), `set` (config keys, fails on unknown ones), `errors`, `ticks`, `kill`, `stop`, `plugin` |
| TestAgent (`agent/`) | installed by `mcs.py new`: tick recorder and marks, fake players, `ta mobs`, spawn and chunk counters, tick scripts with traces |
| `scripts/spark_report.py` | reads spark profiles saved with `--save-to-file`, offline |

The command reference is in [`skill/mc-server-testing`](../skill/mc-server-testing/SKILL.md).

## Three kinds of test

**A load** puts the server under a defined amount of work and measures tick times. It is a shell script: start,
build the load with commands and `ta`, warm up, measure one window between two `ta mark`s, read
`mcs.py ticks --from-mark --to-mark`. See [`loads/`](loads). A load says nothing until it is run alternately
against a baseline (`loads/ab.sh`) and the two ranges do not overlap.

**A trace** runs a fixed command script tick by tick and writes the state of a box of the world after every tick
(`ta script run`). Two runs that should behave the same must produce identical files, so an optimization can be
checked against the path it replaces without knowing what to look for. See [`traces/`](traces).

**A probe** is a small plugin that provokes one specific situation and counts an invariant: items that should not
have moved, updates lost between threads, calls that threw. It is built against the exact server under test, so it
can reach server internals: `mcs.py plugin <server-dir> <probe-dir>` compiles `plugin.yml` + `src/**/*.java` with
the server's Mojang-mapped classes, its libraries and the jars already in `plugins/` (depend on TestAgent to use
its fake players). See [`probes/`](probes).

Every check needs a negative control: run it where the bug or the old behaviour exists (the old jar, the option
off) and confirm it fails there. A check that passes both ways proves nothing. Races show only under load and over
time: scale the load until the threads involved actually overlap.

## Loads

`<load>.sh <server-dir> [measure-seconds]`, server dir made with `mcs.py new`. `WORLD=<dir>` starts from a copy of a
pre-generated world instead of a fresh one; `XMX` sets the heap.

| Load | What it stresses |
|---|---|
| `spawn.sh` | natural spawning around 60 spread players plus 1500 zombies |
| `crush.sh` | 30 still players with 130 cows packed around each: collisions, entity ticking, tracking |
| `redstone.sh` | 64 observer clocks driving 120 dust each: neighbour updates |
| `hoppers.sh` | 32768 hoppers over barrels, while moving items and after they drained |
| `worlds.sh` | 20 players in each of three worlds |
| `clusters.sh` | 64 players in 8 far-apart clusters with zombies |
| `travel.sh` | 20 players flying apart: chunk generation or loading, sending, region IO, at a pinned tick rate |
| `ab.sh` | `ab.sh <load> <dir-a> <dir-b> [pairs] [seconds]`: alternates a load between two server dirs |

## Traces

`traces/trace.sh <server-dir> <script> <ticks> <label>` in a server made with `mcs.py new --flat`. The scripts were
written to check lithium's sleeping block entities against vanilla, and work as a regression set for anything
touching containers, hoppers, comparators and sculk:

| Script | Covers |
|---|---|
| `hoppers-furnaces-brewing.txt` | hopper chain with comparators, furnaces fed from top and side, brewing stand, item pickup, plugin API changes |
| `minecarts-over-hoppers.txt` | chest and hopper minecarts parked on and rolling over hoppers |
| `chunk-unload-reload.txt` | hoppers and furnaces across a chunk unload and reload |
| `inventory-replacement.txt` | inventories replaced under a hopper: data merge, snapshot update, setblock with NBT, clone, chest boat |
| `lithium-regressions.txt` | fuel set through the API, pre-typed chest halves, double chest half replaced |
| `mob-equipment.txt` | equipment changed, chunk reloaded, mobs killed and drops collected by hoppers |
| `sculk-catalyst.txt`, `sculk-sensors.txt` | sculk sensors, shrieker and catalyst bloom |
| `no-update-placement.txt` | a container placed without neighbour updates, and one placed by a command block |

Script format: `@<tick>` starts the commands for that tick; other lines are console commands or `api <action> x y z`
for changes a plugin makes (`add`, `amount`, `snapshot`, `nophys [material]`, `fuel`, `take`,
`vibration <game event>`, `bloom <charge>`). Two minecarts colliding are nondeterministic even between two runs of
one build: keep such things out of a trace, and check that items actually moved before trusting equal files.

## Probes

Build and install with `scripts/mcs.py plugin <server-dir> examples/probes/<name>`.

| Probe | Invariant | Commands |
|---|---|---|
| `cross-world` | `pp arm` builds a caged hopper pair and a tagged armor stand in every world; each `InventoryMoveItemEvent` (on that world's thread) then acts on the next world: reads or sets a block, loads a chunk, teleports the stand or a player, or bumps a plain and an atomic counter. `pp stats` counts results and exceptions per mode; plain < atomic means lost updates | `pp arm`, `pp mode <readblock\|setblock\|loadchunk\|teleport\|tpplayer\|counter\|none> [every]`, `pp stats`, `pp reset`, `pp crash <world>` |
| `worker-sync-load` | entity-move listeners on region worker threads request unloaded chunks; counts chunk loads per thread kind and overlapping `ChunkLoadEvent` handlers | `slp rate <n>`, `slp stats`, `slp reset`; `slp unlisten` drops `PreCreatureSpawnEvent` listeners so the no-listener spawn path runs, `slp poison` makes one chunk's block phase throw |
| `player-data` | read-after-write of player data and advancements through the API: save, read the raw file, `loadData`, compare; `quit` checks offline-player data after leaving; `phys` registers and unregisters a `BlockPhysicsEvent` listener at runtime | `pdw dat <n>`, `pdw adv`, `pdw quit`, `pdw phys <on\|off\|count>` |

They were written for DivineMC's parallel world ticking, regionized chunk ticking and player data writer and touch
some of its classes by reflection, but the Bukkit-level parts run on any Paper fork.
