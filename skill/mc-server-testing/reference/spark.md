# spark, offline

Paper and its forks bundle spark; no plugin to install. Paper also runs a background profiler; `profiler start`
replaces it and `stop` restarts it.

## Recording

| Command | Meaning |
| --- | --- |
| `spark profiler start --thread '*'` | all threads (default is the main thread only) |
| `--thread <name>` / `--thread <pattern> --regex` | named threads |
| `--interval <ms>` | sampling interval, default 4 |
| `--alloc [--interval <bytes>] [--alloc-live-only]` | allocation profile instead of CPU |
| `--only-ticks-over <ms>` | keep only samples from ticks longer than this (lag spikes) |
| `--ignore-sleeping` | drop sleeping threads at record time |
| `--not-combined` | keep pooled threads separate instead of `Name (xN)` |
| `--force-java-sampler` | Java sampler instead of async-profiler |
| `spark profiler stop --save-to-file` | write `plugins/spark/profile-<date>.sparkprofile`, no upload |
| `spark profiler cancel` | drop the running profile |

Never use `start --timeout`: on expiry spark uploads to spark.lucko.me. `spark health` and `spark tps` print
to the log and are fine.

## File format (checked against spark-paper 1.10.191)

`--save-to-file` writes `SamplerData.toByteArray()`: bare protobuf, no header, no compression. Schema:
`lucko/spark` `spark-common/src/main/proto/spark/spark_sampler.proto` + `spark.proto`.

- `SamplerData`: 1 metadata, 2 threads, 6 time_windows (sorted window ids), 7 time_window_statistics (map id → stats).
- `ThreadNode`: 1 name (pooled threads are merged as `Name (xN)`), 3 children = **every** StackTraceNode of the
  thread as one flat post-order list, 4 times, 5 children_refs = indices of the root nodes in that list.
- `StackTraceNode`: 3 class, 4 method, 6 line, 7 desc, 8 times, 9 children_refs (indices into the same list).
- `times[i]` is milliseconds for window `time_windows[i]`; a node's time includes its children.
  Thread total ≈ profile duration × threads in the group.
- `WindowStatistics` per one-minute window: ticks, tps, mspt_median, mspt_max, players, entities, chunks, cpu.
- `metadata.interval` is in microseconds.

## spark_report.py

- `report --busy`: drops idle subtrees (park, wait, sleep, epoll, `waitUntilNextTick`), so shares are of time the
  thread actually worked. Without it the main thread of a healthy server is ~85% `Unsafe.park`.
- Self time = node minus children. Total time is deduplicated along each stack, so recursion is not double counted.
- `--thread` is a regex over thread names; the default is the main thread, `^Server thread$`. Pools such as
  `Parallel Entity Tracker (x3)` match as one entry.
- `--skip-windows N` drops warm-up windows; `--tree PCT` prints the call tree down to nodes ≥ PCT% of the thread.
- `diff` compares shares, not absolute time: a method can lose share because something else grew. Compare
  profiles taken under the same load, and confirm with `mcs.py ticks` before calling anything faster.

## Reading a profile

1. Start with `report --busy --tree 3` on the main thread: where the tick goes (level tick → chunk tick, entity
   tick, natural spawn; connection tick; scheduler).
2. `--threads` shows whether work moved off-thread (async pathfinding, tracker, chunk workers) and how much.
3. A hot leaf method (`--busy`, top self time) is a lead, not a cause: look at its callers in the tree.
4. Lag spikes: record with `--only-ticks-over 50` during the bad period.
