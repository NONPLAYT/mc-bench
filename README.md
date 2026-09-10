# mc-bench

Benchmark harness comparing **Paper**, **Purpur**, **Leaf** and **DivineMC** on Minecraft 26.2.

## Setup

Needs JDK 25, Python 3, curl and about 60 GB of disk.

```sh
# SoulFire is the only jar not fetched automatically: grab the CLI jar from
# https://github.com/soulfiremc-com/SoulFire/releases/latest and save it as:
#   jars/SoulFireCLI.jar          (the desktop app is a GUI and cannot be scripted)

scripts/setup.sh                  # resolves the latest official build of each fork,
                                  # builds the agent, boots each build once
scripts/make-botswarm-world.sh    # shared world for the bot scenarios (~10 min)
```

`setup.sh` writes `jars/builds.txt` with the build number, sha256 and URL of every
jar it fetched. Publish that file with the results: it is what makes a number
reproducible.

## Tuning for the host

Defaults target a 16-core machine. Host-specific values go in `bench.local.conf`
(copy `bench.local.conf.example`), which `bench.conf` sources last and git ignores.
Put your tuned numbers there once instead of prefixing every command; publish the
file with the results, since it says what the host actually ran.

A full `botswarm` run takes about three hours, so it is worth spending five
minutes first checking the load lands in the 20-35 ms band:

```sh
BOTSWARM_MEASURE_SECONDS=120 scripts/run.sh botswarm --builds divinemc --profiles stock --runs 1
scripts/aggregate.py botswarm
```

| Setting | Meaning | 6 cores | 16 cores |
|---|---|---|---|
| `BOTSWARM_BOTS` | main load knob | 60 | 150 |
| `CHUNK_WORKER_THREADS` / `CHUNK_IO_THREADS` | pinned identically on every build | 6 / 4 | 12 / 6 |
| `RCT_THREADS` | regionised ticking workers; sweep it | 4 | 4, 8, 12 |
| `BOT_HEAP` | bot client heap | 6144M | 12288M |
| `SERVER_CPUS` / `BOT_CPUS` | taskset lists per JVM; see `scripts/cpu-layout.sh` | — | one CCD each |

A value pinned in `bench.local.conf` also beats a command-line prefix, since that
file is sourced last. Comment the line out before sweeping that parameter.

The machine must be otherwise idle, `swapoff -a`, CPU governor `performance`.

On a dual-CCD chip such as the 9950X3D only one CCD carries the extra cache, and
letting the scheduler move the server between them adds variance. `SERVER_CPUS`
and `BOT_CPUS` pin each JVM separately; `scripts/cpu-layout.sh` reads the L3
topology and prints both lines ready to paste into `bench.local.conf`:

```sh
scripts/cpu-layout.sh
#   SERVER_CPUS=0-7,16-23      <- cache-heavy CCD
#   BOT_CPUS=8-15,24-31
```

Do not put `taskset` in front of `run.sh` instead: affinity is inherited, so that
pins the server and the bot client to the *same* CCD, which is the opposite of
what you want.

Better still, run the bots from a second machine and point `BOT_TARGET` at this
host. Bots cost ~8x the CPU the server does, so sharing one box means measuring
contention rather than the server.

## Running

```sh
scripts/run.sh <scenario> [--runs N] [--profiles a,b] [--builds a,b] [--radius R] [--verify]
scripts/aggregate.py <scenario>       # -> results/<scenario>.json + a console table
```

`--verify` applies every config profile without running anything, and fails if a
setting no longer exists in some fork.

## Scenarios

| Scenario | What it loads | Measures |
|---|---|---|
| `chunkgen` | Chunky pregenerates radius 2048, fresh world each run | wall time, chunks/s, CPU-seconds |
| `botswarm` | 150 SoulFire bots wandering a grid, plus 1500 mobs | MSPT p50/p99, server + bot CPU |
| `rctclusters` | 64 bots and 4000 mobs in 8 clusters 768 blocks apart | MSPT p50/p99, CPU; scales with `RCT_THREADS` |

`rctclusters` targets regionised chunk ticking, which only DivineMC has. Clusters
must stay far apart or they merge into one region and the feature does nothing.

## Profiles

| Profile | Meaning |
|---|---|
| `stock` | each fork exactly as shipped |
| `parity` | same game behaviour and threading model everywhere: nothing async, no gameplay changes |
| `max` | each fork's performance features turned up |
| `dfconly` | stock plus the density function compiler, to isolate it |
| `rct` | regionised chunk ticking only, at `RCT_THREADS` |

Leaf ships nearly everything off by default while DivineMC ships several features
on, so `stock` vs `stock` is not a like-for-like comparison. That is what `parity`
is for.

## Reading the numbers

- Runs are interleaved and the matrix order rotates, so thermal drift cannot look
  like a difference between forks. `sysmon` records CPU MHz and package
  temperature: check they match across cells before trusting a result.
- Always report CPU-seconds next to wall time. A fork that lowers MSPT by using
  more cores has not made the work cheaper.
- **Bots cost far more CPU than the server** (~8x in our measurements), so on one
  machine the bot client is the bottleneck, not the server. Mobs are the cheap way
  to load the server. Set `BOT_TARGET` to run bots from another host.
- Aim for p50 around 20-35 ms. A saturated server measures how badly it degrades,
  not how fast it is. **Bots, not mobs, are what load the server** in `botswarm`:
  cutting mobs 4000 -> 1500 moved p50 only 82 -> 64 ms, while cutting bots 150 -> 60
  moved it to 21 ms. Tune `BOTSWARM_BOTS` first.

Measured anchor points on a Ryzen 5 5600 (6c/12t), server and bots on the same box:

| Load | p50 | p99 | server CPU | bot CPU |
|---|---|---|---|---|
| 150 bots, 1500 mobs | 64 ms | 103 ms | 202% | 447% |
| 60 bots, 1500 mobs | 21 ms | 30 ms | 113% | 195% |
| `rctclusters`, 64 bots, 4000 mobs | 12 ms | 20 ms | 121% | 404% |

The shipped default of 150 bots targets a 16-core machine; on 6 cores use 60.
- Worldgen is nondeterministic: two runs of the same build differ in ~10% of
  chunks, and Paper does the same. Terrain diffs cannot validate a change.

## Publishing

`results/*.json` holds every individual run, not just the summary, and
`work/raw/` holds per-tick CSVs and the full config set each run actually used.
Publish both, show the spread, and put the CPU column next to the time column.
