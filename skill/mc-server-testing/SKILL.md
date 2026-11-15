---
name: mc-server-testing
description: Run throwaway Minecraft servers (DivineMC, Paper, Purpur, Leaf or any Paper fork) locally to verify a change works, reproduce a bug, crash-test world storage, load the server with in-process fake players, measure tick times, check behaviour tick by tick against a baseline and profile with spark offline. Use when asked to test, smoke-test, stress-test, benchmark, profile or "check on a real server" a server core, a config option or a patch, to find lag or hot spots, or to compare two builds.
---

# Minecraft server testing

This skill lives in `skill/mc-server-testing/` of the mc-bench checkout (`~/Projects/mc-bench` on the author's
machine); `$BENCH` below is that checkout. `scripts/mcs.py` drives a throwaway server dir; the TestAgent plugin
(`agent/`) records tick times, spawns fake players and runs tick scripts; `scripts/spark_report.py` reads spark
profiles offline. Ready-made loads, traces and probe plugins are in `examples/` (see `examples/README.md`).
The fork-vs-fork benchmark matrix (`scripts/run.sh`) is described in the repo README.

```bash
BENCH=~/Projects/mc-bench
MCS="python3 -I $BENCH/scripts/mcs.py"
SPARK="python3 -I $BENCH/scripts/spark_report.py"
```

## Rules

- Only throwaway dirs (the session scratchpad, or a dir the user named). Never point `mcs.py` at the user's real server or world.
- Never match servers by process name (`pgrep java` misses NixOS's `.java-wrapped`, `pkill -f` can kill this session). `mcs.py` tracks its own pid and checks cwd before signalling.
- Never clone a server dir by copying it: the copied `.mcs.json` still points at the old dir's jar, so `mcs` loses the pid ("server exited during startup") while the server keeps the port. Make a new one with `new`.
- Default port 25565 may be the user's dev server: pass `--port` (e.g. 25700; RCON is port + 10).
- spark profiles stay local: stop with `--save-to-file`. Never `spark profiler start --timeout`: an auto-stopped profile is uploaded.
- Long runs (benchmarks, anything over a few minutes) are detached so they survive this session: `setsid nohup bash run.sh > out 2>&1 < /dev/null & disown`, then read `out`. After a crash, check for orphaned servers (`$MCS status <dir>`) before starting another. Never edit a script while it runs: bash reads it by offset.
- Quote selectors in `cmd`: zsh expands `@e[type=!player]` as a glob, the command chain dies and the server is left running. Run chains as `start && ... ; stop` so a failure still stops it.
- Keep the machine quiet during a measurement window: a Gradle build alongside a run invalidates it. Check the `machine cpu` and `cpu MHz` columns of `ticks` against the other arm.
- A claim about performance needs a measurement that survives repetition (see `reference/benchmarking.md`); a claim about behaviour needs a check whose negative control fails.

## Lifecycle

```bash
$MCS new  <dir> --jar <server.jar> --port 25700 [--seed N] [--flat] [--view-distance 10] [--prop key=value]
$MCS start <dir> [--xmx 6G] [--sysprop divinemc.rct.checkOwnership=true] [--jvm -XX:...] [--cpus 0-5]
$MCS wait <dir>                      # until "Done", or prints the tail and fails if the server died
$MCS cmd  <dir> <console command>    # RCON, prints the reply (spark replies go to the log instead)
$MCS cmd --console <dir> <command>   # console FIFO, for commands longer than RCON accepts (no reply)
$MCS log  <dir> [--grep REGEX] [--since TEXT]
$MCS errors <dir>                    # WARN/ERROR grouped with stack traces; exit 3 if any ERROR
$MCS set  <dir> <file> dotted.key=value ...   # YAML (fails on unknown keys) or .properties
$MCS stop <dir>                      # graceful; SIGKILL after --timeout
$MCS kill <dir>                      # SIGKILL now, for crash/durability tests
$MCS status <dir>
$MCS ticks <dir> [--from-mark A] [--to-mark B]  # p50/p95/p99/max + server CPU, MHz, temperature
$MCS idle                            # exit 4 if the machine is busy (before benchmarks)
$MCS agent <dir>                     # rebuild TestAgent after changing agent/ and install it
$MCS plugin <dir> <src-dir>          # build a probe plugin (plugin.yml + src/**/*.java) against this server
```

`new` copies the jar (rebuilding the project never changes a running test), runs paperclip patch-only, builds the
TestAgent against that exact server (cached by hash in `~/.cache/mc-bench`) and installs it. Config files exist
only after the first start: `start`, `wait`, `stop`, then `set`, then delete `world/` if the change affects
generation or storage. A server dir made by an older jar lacks newer config keys (`set` fails): boot it once with
the new jar first.

DivineMC jar: `./gradlew createPaperclipJar` in the project → `divinemc-server/build/libs/divinemc-paperclip-*.jar`.
Other forks: `$BENCH/jars/` (fetched by `scripts/setup.sh`).

## TestAgent commands (`ta`)

```
ta fake spawn <n> [layout=grid|cluster|center] [spacing=160] [clusters=8] [separation=768] [origin=x,z]
                  [radius=32] [speed=0.2] [y=140] [seed=1] [world=<name>] [targetable=true]
ta fake status | ta fake clear
ta mobs <entity_type> <per_player> [spread=16]     # at ground level around each fake, loaded chunks only
ta mark <label> | ta reset | ta stats [seconds] | ta dump
ta spawns reset | ta spawns                        # NATURAL pre-spawn/spawned counts per type since reset, also agent-out/spawns.txt
ta chunks reset | ta chunks                        # chunks generated / loaded from disk / unloaded per second since reset, also agent-out/chunks.txt
ta script run <file> <ticks> [box=x0,y0,z0,x1,y1,z1] [out=<file>] | ta script status
```

Fake players are real `ServerPlayer`s on a connection with no client: they load and generate chunks, get
tracked, keep natural spawning going, are targeted by mobs and have chunk packets built for them, but packets are
dropped before Netty encoding and they never send movement packets. What that does and doesn't exercise:
`reference/load.md`. Each fake flies at `y` and wanders within `radius` of its home at `speed` blocks/tick (0 stays
put, 0.2 walk, 1+ forces new chunks). Homes are laid out around `origin` (default the world spawn): a grid with
`spacing`, or `clusters` centres on a circle of radius `separation`. `ta mark` labels a tick so `mcs.py ticks` can
cut warm-up out.

`ta script` runs console commands at fixed ticks and writes the state of the box after every tick to
`agent-out/trace-<name>.txt`: two runs that should behave the same must give identical files. Format and the
prepared scenarios: `examples/README.md` ("Traces").

Probe plugins can `depend: [TestAgent]` and call `fakePlayers()`, `spawnFakes(count, options)` and `mark(label)`
on the plugin instance.

## Profiling

```bash
$MCS cmd <dir> spark profiler start --thread '*'      # add --alloc for allocations
# ... let the scenario run ...
$MCS cmd <dir> spark profiler stop --save-to-file     # -> <dir>/plugins/spark/profile-*.sparkprofile
$SPARK report <file> --busy [--thread REGEX] [--filter org\\.bxteam] [--tree 3] [--threads]
$SPARK diff <before> <after> --busy [--total]
```

Format, flags and how to read the numbers: `reference/spark.md`.

## Recipes

Behaviour checks with their negative controls (storage crash tests, config edge cases, thread-ownership
assertions, log-clean boots): `reference/scenarios.md`. Benchmarks and A/B comparisons: `reference/benchmarking.md`.
Loads, traces and probes to start from: `$BENCH/examples/`.
