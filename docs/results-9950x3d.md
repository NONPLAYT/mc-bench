# Paper vs Purpur vs Leaf vs DivineMC on a Ryzen 9 9950X3D

Minecraft 26.2, measured 2026-09-12. Five runs per cell with a 90 s cooldown
between runs, and the matrix order rotated between repeats. The tables give the
median of the five runs; "spread" is (max-min)/median over those five. Paper
stock is the baseline: `+N%` means N% better than Paper, `-N%` worse.

Raw runs: [`results/9950x3d/2026-09-12/`](../results/9950x3d/2026-09-12), one
JSON per scenario plus a CSV with the same numbers. The tables below are
generated from them:

```sh
scripts/report.py results/9950x3d/2026-09-12
```

## Host

| | |
|---|---|
| CPU | Ryzen 9 9950X3D, 16c/32t, no CPU pinning |
| Minecraft | 26.2 |
| Server heap | 8192M, G1 |
| Simulation / view distance | 10 / 10 |
| Bot client | SoulFire on the same host, 12288M heap |
| Host config | [`bench.local.conf`](../results/9950x3d/2026-09-12/bench.local.conf) |

`bench.local.conf` pins `BOTSWARM_BOTS=150`, `CHUNK_WORKER_THREADS=12`,
`CHUNK_IO_THREADS=4`, `BOT_HEAP=12288M` and `RCT_THREADS=8`; the rctclusters
sweep overrides `RCT_THREADS` with 4, 8 and 12.

## Profiles

| Profile | Meaning |
|---|---|
| `stock` | the fork exactly as shipped |
| `parity` | same game behaviour and threading model everywhere: nothing async, no gameplay changes |
| `max` | the fork's performance features turned up |
| `rct` | regionised chunk ticking only, at `RCT_THREADS` |

## Columns

| Column | What it is |
|---|---|
| p50 / p99, ms | tick time; the budget for one tick at 20 TPS is 50 ms |
| server CPU, cores | mean CPU of the server process over the measurement: 100% = one core |
| bot CPU | mean CPU of the SoulFire client; this is the load, not a result of the build |
| CPU vs Paper | CPU-seconds of the server process relative to Paper |
| headroom to 50 ms | what is left of the tick budget: `(50 - p50) / 50`; in parentheses, how many times p50 fits into the budget |

## botswarm

150 SoulFire bots walking a 160-block grid in a world of radius 1600, plus 1500
mobs summoned from y=200. 120 s warmup, 300 s measurement.

| Build / profile | p50, ms | spread | p99, ms | max, ms | server CPU | cores | bot CPU | RSS, MB | p50 vs Paper | p99 vs Paper | CPU vs Paper | headroom to 50 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| leaf max | 11.99 | 6.8% | 19.21 | 28.9 | 75% | 0.75 | 438% | 9292 | +53.7% | +47.7% | -22.7% | 76.0% (×4.2) |
| leaf stock | 21.13 | 4.1% | 31.11 | 42.1 | 88% | 0.88 | 444% | 9318 | +18.5% | +15.3% | -9.1% | 57.7% (×2.4) |
| leaf parity | 21.92 | 11.1% | 32.27 | 45.3 | 88% | 0.88 | 435% | 9281 | +15.5% | +12.2% | -7.4% | 56.2% (×2.3) |
| divinemc stock | 24.38 | 3.5% | 33.18 | 52.6 | 110% | 1.10 | 447% | 9305 | +6.0% | +9.7% | +14.2% | 51.2% (×2.1) |
| divinemc max | 25.46 | 2.0% | 34.30 | 47.3 | 114% | 1.14 | 440% | 9251 | +1.8% | +6.7% | +19.3% | 49.1% (×2.0) |
| paper stock | 25.93 | 6.7% | 36.74 | 47.0 | 97% | 0.97 | 440% | 9284 | +0.0% | +0.0% | +0.0% | 48.1% (×1.9) |
| purpur stock | 28.19 | 4.1% | 39.35 | 55.9 | 104% | 1.04 | 444% | 9273 | -8.7% | -7.1% | +7.8% | 43.6% (×1.8) |
| divinemc parity | 28.76 | 5.6% | 39.60 | 55.8 | 105% | 1.05 | 436% | 9300 | -10.9% | -7.8% | +9.1% | 42.5% (×1.7) |

Leaf max wins twice over: p50 is half of Paper's and it burns 23% fewer
CPU-seconds, the only cell here where the work became cheaper rather than spread
across more cores. Leaf keeps +15.5% on parity, so its advantage survives an
identical threading model. DivineMC stock gives +6% on p50 for +14% CPU, and on
parity it falls 10.9% below Paper — its stock gain comes from moving work onto
other threads, not from a faster tick. Purpur is 8.7% behind Paper on p50 at
+7.8% CPU.

## rctclusters

64 bots and 4000 mobs (500 per cluster) in 8 clusters 768 blocks apart. 120 s
warmup, 300 s measurement. The scenario targets regionised chunk ticking, which
only DivineMC has, so the matrix is DivineMC `rct` against stock Paper and
Purpur, run at `RCT_THREADS` 4, 8 and 12.

### RCT_THREADS=4

| Build / profile | p50, ms | spread | p99, ms | max, ms | server CPU | cores | bot CPU | RSS, MB | p50 vs Paper | p99 vs Paper | CPU vs Paper | headroom to 50 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| divinemc rct | 4.04 | 4.5% | 6.17 | 9.9 | 56% | 0.56 | 230% | 9306 | +39.3% | +35.2% | +20.8% | 91.9% (×12.4) |
| paper stock | 6.66 | 7.8% | 9.51 | 14.1 | 46% | 0.46 | 231% | 9247 | +0.0% | +0.0% | +0.0% | 86.7% (×7.5) |
| purpur stock | 6.86 | 8.1% | 10.03 | 20.4 | 49% | 0.49 | 233% | 9283 | -3.0% | -5.5% | +3.6% | 86.3% (×7.3) |

Four workers already put DivineMC 39% ahead of Paper on p50, for +21% CPU.

### RCT_THREADS=8

| Build / profile | p50, ms | spread | p99, ms | max, ms | server CPU | cores | bot CPU | RSS, MB | p50 vs Paper | p99 vs Paper | CPU vs Paper | headroom to 50 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| divinemc rct | 3.74 | 2.5% | 5.27 | 10.5 | 57% | 0.57 | 231% | 9272 | +44.1% | +44.2% | +23.5% | 92.5% (×13.4) |
| paper stock | 6.68 | 4.2% | 9.44 | 12.4 | 46% | 0.46 | 231% | 9263 | +0.0% | +0.0% | +0.0% | 86.6% (×7.5) |
| purpur stock | 6.84 | 2.4% | 9.64 | 13.6 | 48% | 0.48 | 228% | 9246 | -2.4% | -2.1% | +4.8% | 86.3% (×7.3) |

One worker per cluster is the best cell of the sweep: +44% on both p50 and p99.

### RCT_THREADS=12

| Build / profile | p50, ms | spread | p99, ms | max, ms | server CPU | cores | bot CPU | RSS, MB | p50 vs Paper | p99 vs Paper | CPU vs Paper | headroom to 50 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| divinemc rct | 3.75 | 4.2% | 5.35 | 8.0 | 56% | 0.56 | 231% | 9276 | +44.0% | +42.6% | +17.1% | 92.5% (×13.3) |
| paper stock | 6.71 | 3.6% | 9.31 | 12.7 | 47% | 0.47 | 227% | 9246 | +0.0% | +0.0% | +0.0% | 86.6% (×7.5) |
| purpur stock | 6.81 | 4.5% | 9.59 | 13.7 | 47% | 0.47 | 236% | 9246 | -1.6% | -3.0% | -1.3% | 86.4% (×7.3) |

Twelve workers land on the same numbers as eight; the four extra threads go
unused.

### Scaling with thread count

DivineMC `rct` across the three sweeps:

| RCT_THREADS | p50, ms | p99, ms | server CPU-s | vs Paper |
|---:|---:|---:|---:|---:|
| 4 | 4.04 | 6.17 | 166 | +39.3% |
| 8 | 3.74 | 5.27 | 168 | +44.1% |
| 12 | 3.76 | 5.35 | 167 | +44.0% |

Threads pay off exactly once: from 4 to 8, p50 drops 7.5% and p99 15% while CPU
stays flat (+1.3%). From 8 to 12 the 0.5% difference sits inside the spread.
With eight clusters, eight workers is the ceiling.

## chunkgen

Chunky pregenerates radius 2048 — 66 049 chunks — on a fresh world every run,
server heap 8192M.

| Build / profile | time, s | spread | chunks/s | CPU-s | CPU-s per 1000 chunks | cores | RSS, MB | vs Paper | CPU vs Paper |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| divinemc max | 102.6 | 1.2% | 643.8 | 966 | 14.63 | 9.5 | 9305 | +9.4% | -14.6% |
| leaf max | 104.2 | 1.5% | 633.9 | 964 | 14.60 | 9.4 | 9271 | +8.0% | -14.7% |
| paper stock | 113.2 | 1.2% | 583.5 | 1131 | 17.12 | 10.1 | 9206 | +0.0% | +0.0% |
| divinemc stock | 114.2 | 1.8% | 578.4 | 1136 | 17.20 | 10.0 | 9243 | -0.9% | +0.4% |
| divinemc parity | 114.6 | 12.8% | 576.3 | 1147 | 17.37 | 10.2 | 9196 | -1.2% | +1.4% |
| leaf parity | 116.7 | 1.1% | 566.0 | 1178 | 17.84 | 10.1 | 9193 | -3.1% | +4.2% |
| leaf stock | 116.9 | 1.2% | 565.0 | 1176 | 17.80 | 10.1 | 9207 | -3.3% | +3.9% |
| purpur stock | 121.8 | 7.3% | 542.3 | 1283 | 19.42 | 10.5 | 9178 | -7.6% | +13.4% |

The whole win in generation comes from the `max` profile, and it is the same for
both forks: +8-9% on time at -15% CPU, the density function compiler making the
work smaller rather than wider. Stock and parity Leaf and DivineMC are
indistinguishable from Paper, within 3% either way. Purpur is 7.6% slower at
+13% CPU. This is also the only scenario that loads the machine: 9.5-10.5 cores
out of 16.
