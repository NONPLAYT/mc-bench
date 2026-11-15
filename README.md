# mc-bench

Benchmark harness comparing **Paper**, **Purpur**, **Leaf** and **DivineMC** on Minecraft 26.3, and the toolkit
for testing a server core on a local machine: throwaway servers, in-process fake players, tick-by-tick traces and
probe plugins.

Load comes from fake players: `ServerPlayer`s that live inside the server on a connection with no client, driven
by the TestAgent plugin (`agent/`). They load and generate chunks, get tracked, keep natural spawning going, are
targeted by mobs and have chunk packets built for them, without a bot client eating the CPU and memory the server
is measured on. What they do not exercise is the network path: packets are dropped before Netty encoding.

## Setup

Needs JDK 25, Python 3, curl and about 60 GB of disk.

```sh
scripts/setup.sh                  # resolves the latest official build of each fork, boots each
                                  # build once (the agent is compiled against every server)
scripts/make-world.sh             # shared world for the players and rct scenarios (~10 min)
```

`setup.sh` writes `jars/builds.txt` with the build number, sha256 and URL of every
jar it fetched. Publish that file with the results: it is what makes a number
reproducible.

## Tuning for the host

Defaults target a 16-core machine. Host-specific values go in `bench.local.conf`
(copy `bench.local.conf.example`), which `bench.conf` sources last and git ignores.
Put your tuned numbers there once instead of prefixing every command; publish the
file with the results, since it says what the host actually ran.

A full `players` run takes a few hours, so it is worth spending five minutes
first checking the load lands in the 20-35 ms band:

```sh
PLAYERS_MEASURE_SECONDS=120 scripts/run.sh players --builds divinemc --profiles stock --runs 1
scripts/aggregate.py players
```

| Setting | Meaning |
|---|---|
| `PLAYERS_COUNT` | fake players in `players`; the main load knob |
| `PLAYERS_MOBS` | mobs summoned on the players' grid |
| `PLAYERS_SPEED` / `PLAYERS_WANDER_RADIUS` | how fast and how far each player wanders around its home |
| `CHUNK_WORKER_THREADS` / `CHUNK_IO_THREADS` | pinned identically on every build |
| `RCT_THREADS` | regionised ticking workers; sweep it |
| `SERVER_CPUS` | taskset list for the server JVM; see `scripts/cpu-layout.sh` |

The fake-player loads have not been calibrated on a 16-core machine yet; start from the defaults and adjust
`PLAYERS_COUNT` until p50 is in the band.

A value pinned in `bench.local.conf` also beats a command-line prefix, since that
file is sourced last. Comment the line out before sweeping that parameter.

The machine must be otherwise idle, `swapoff -a`, CPU governor `performance`.

On a dual-CCD chip such as the 9950X3D only one CCD carries the extra cache, and
letting the scheduler move the server between them adds variance.
`scripts/cpu-layout.sh` reads the L3 topology and prints a `SERVER_CPUS` line
for the cache-heavy CCD, ready to paste into `bench.local.conf`.

## Running

```sh
scripts/run.sh <scenario> [--runs N] [--profiles a,b] [--builds a,b] [--radius R] [--verify]
scripts/aggregate.py <scenario>       # -> results/<scenario>.json + a console table
scripts/report.py results/<host>/<date>   # -> markdown tables + one CSV per scenario
```

`--verify` applies every config profile without running anything, and fails if a
setting no longer exists in some fork.

`scripts/session.sh <host-tag>` runs the whole published set in one go (setup,
chunkgen, players with `rct`, the rctclusters sweep at 4/8/12 threads, rctblocks)
and writes `results/<host-tag>/<date>/` with the report. Paper and Purpur do not
read `RCT_THREADS`, so they run once at 8 threads and serve as the baseline of
every sweep point. On a multi-CCD chip it appends the `scripts/cpu-layout.sh`
pinning to `bench.local.conf` unless `SERVER_CPUS` is already set; chunkgen runs
unpinned on the whole chip. Rerun the same
command after a crash to resume.

## Scenarios

| Scenario | What it loads | Measures |
|---|---|---|
| `chunkgen` | Chunky pregenerates radius 2048, fresh world each run | wall time, chunks/s, CPU-seconds |
| `players` | 60 fake players wandering a 160-block grid, plus 1500 mobs | MSPT p50/p99, server CPU |
| `rctclusters` | 64 fake players and 4000 mobs in 8 clusters on a circle of radius 768 | MSPT p50/p99, CPU; scales with `RCT_THREADS` |
| `rctblocks` | `rctclusters` plus 256 observer-clock sticky pistons per cluster and `random_tick_speed` 30 | same, under heavy block updates |

`rctclusters` and `rctblocks` target regionised chunk ticking, which only DivineMC
has. Clusters must stay far apart or they merge into one region and the feature
does nothing. Fake players have infinite Resistance V and Saturation, so mobs
attack them but none of them die. The aggregator reports, from DivineMC's
`tracking/<world>/region-tick-*.log` (copied into each run directory), the median
number of regions; check it before trusting an RCT result.

## Profiles

| Profile | Meaning |
|---|---|
| `stock` | each fork exactly as shipped |
| `parity` | same game behaviour and threading model everywhere: nothing async, no gameplay changes |
| `max` | each fork's performance features turned up |
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
- Aim for p50 around 20-35 ms. A saturated server measures how badly it degrades,
  not how fast it is. Players, more than mobs, are what load the server: tune
  `PLAYERS_COUNT` first.
- Moving players load more chunks when ticks are slow, which slows ticks further,
  so a small gap between builds amplifies itself. Compare per-chunk work or pin
  the tick rate when a result looks too large.
- Results published before November 2026 (`docs/results-9950x3d.md`) used SoulFire
  bots, not fake players: they are not comparable with new runs.
- Worldgen is nondeterministic: two runs of the same build differ in ~10% of
  chunks, and Paper does the same. Terrain diffs cannot validate a change.

## Testing a server core

Besides the matrix above, the same pieces work on single throwaway servers for
checking a change: `scripts/mcs.py` creates and drives a server dir, the agent
spawns fake players, records ticks and runs tick-by-tick traces, and
`scripts/mcs.py plugin` builds a probe plugin against the exact server under
test. [`examples/`](examples/README.md) has the loads, traces and probes written
while working on DivineMC, as a base for your own tests;
[`skill/mc-server-testing`](skill/mc-server-testing/SKILL.md) is the command
reference (also usable as a Claude Code skill: link it into `~/.claude/skills/`).

## Publishing

`results/*.json` holds every individual run, not just the summary, and
`work/raw/` holds per-tick CSVs and the full config set each run actually used.
Publish both, show the spread, and put the CPU column next to the time column.

`scripts/report.py` does the last step: point it at a directory of aggregate.py
outputs (one per scenario, `<scenario>-<variant>.json` for a parameter sweep) and
it writes the comparison tables against Paper stock plus a CSV of the same numbers
for a web page. Published sets live under `results/<host>/<date>/` next to the
`bench.local.conf` and `jars/builds.txt` of that session; `results/9950x3d/2026-09-12/`
-> [`docs/results-9950x3d.md`](docs/results-9950x3d.md) is one.
