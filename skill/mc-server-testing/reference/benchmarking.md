# Benchmarks and A/B comparisons

Most "it got faster" results are noise. These rules come from the fork benchmarks in this repo (`scripts/run.sh`).

## Before

- `mcs.py idle` must pass; close browsers/IDEs indexing in the background.
- Same jar copy, same seed, same `--xmx`, same config profile for every arm. A fork's defaults are not
  comparable to another's: compare like-for-like settings (mc-bench calls this the `parity` profile).
- Pre-generate the area the scenario uses (run the scenario once, stop, keep `world/`), unless worldgen is what
  is measured. Copy that world into every arm: `cp -a` the dir before `start`.

## During

- Warm up, then `ta mark measure_start`, measure a fixed window (≥ 300 s for MSPT), `ta mark measure_end`, `ta dump`.
- `mcs.py ticks <dir> --from-mark measure_start --to-mark measure_end` gives p50/p95/p99/max plus mean CPU MHz and
  package temperature for the same window.
- Alternate arms (A B A B …), at least 3 runs each, with a cooldown (`sleep 90`) between runs:
  `examples/loads/ab.sh <load> <dir-a> <dir-b>` does this for any load script.
- Players that move load more chunks when ticks are slow, which slows ticks further: a gap between two builds
  amplifies itself. Pin the tick rate below what both can sustain (`tick rate 10`) for moving-player loads.

## Trusting a result

- Mean CPU MHz must be equal across runs (±2%). A bimodal result that tracks MHz is boost/thermal state, not code.
- Report the spread (min-max of the per-run p50), not one number. If the arms overlap, there is no result.
- A change in a hot spot found by spark needs both: the profile share moved and `ticks` moved.
- Ticks over 50 ms and `max` are dominated by GC and outliers; compare p50/p95 first.
- Write down what was measured and what was not (e.g. "fake players: no Netty encoding").

## Host notes

- One L3 domain (e.g. a Ryzen 5 5600): nothing to pin; `--cpus` only isolates the server from other work. On a
  multi-CCD chip pin it to the cache-heavy CCD (`scripts/cpu-layout.sh`).
- Keep `--xmx` well below RAM; two servers at once double memory bandwidth pressure, so run arms one at a time.
