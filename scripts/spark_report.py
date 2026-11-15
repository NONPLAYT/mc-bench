#!/usr/bin/env python3
"""Offline reader for spark `.sparkprofile` files (`/spark profiler stop --save-to-file`).

The file is a bare protobuf `spark.SamplerData` (lucko/spark spark_sampler.proto). Each ThreadNode holds all
its StackTraceNodes as one flat list; `children_refs` are indices into that list. `times[i]` (milliseconds)
belongs to the i-th entry of the sorted `time_windows`, whose stats are in `time_window_statistics`.
"""
import argparse
import re
import struct
import sys
from collections import defaultdict


def varint(buf, pos):
    result = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def fields(buf):
    """Yield (field_number, wire_type, value) for one message."""
    pos, end = 0, len(buf)
    while pos < end:
        key, pos = varint(buf, pos)
        num, wt = key >> 3, key & 7
        if wt == 0:
            val, pos = varint(buf, pos)
        elif wt == 1:
            val = buf[pos:pos + 8]
            pos += 8
        elif wt == 2:
            length, pos = varint(buf, pos)
            val = buf[pos:pos + length]
            pos += length
        elif wt == 5:
            val = buf[pos:pos + 4]
            pos += 4
        else:
            raise ValueError(f"unsupported wire type {wt}")
        yield num, wt, val


def doubles(wt, val):
    if wt == 1:
        return [struct.unpack("<d", val)[0]]
    return list(struct.unpack(f"<{len(val) // 8}d", val))


def ints(wt, val):
    if wt == 0:
        return [val if val < 1 << 63 else val - (1 << 64)]
    out, pos = [], 0
    while pos < len(val):
        v, pos = varint(val, pos)
        out.append(v)
    return out


def as_double(wt, val):
    return struct.unpack("<d", val)[0] if wt == 1 else float(val)


LAMBDA_ADDRESS = re.compile(r"\$\$Lambda[./]0x[0-9a-f]+(/0x[0-9a-f]+)?")


class Node:
    __slots__ = ("cls", "method", "line", "desc", "times", "refs")

    def __init__(self):
        self.cls, self.method, self.line, self.desc, self.times, self.refs = "", "", 0, "", [], []

    @property
    def key(self):
        # hidden lambda classes carry a per-JVM address that would make every run look different
        cls = LAMBDA_ADDRESS.sub("$$Lambda", self.cls)
        return f"{cls}.{self.method}" if cls else self.method


def parse_node(buf):
    n = Node()
    for num, wt, val in fields(buf):
        if num == 3:
            n.cls = val.decode()
        elif num == 4:
            n.method = val.decode()
        elif num == 6:
            n.line = val
        elif num == 7:
            n.desc = val.decode()
        elif num == 8:
            n.times.extend(doubles(wt, val))
        elif num == 9:
            n.refs.extend(ints(wt, val))
    return n


class Thread:
    def __init__(self):
        self.name, self.nodes, self.times, self.refs = "", [], [], []


def parse_thread(buf):
    t = Thread()
    for num, wt, val in fields(buf):
        if num == 1:
            t.name = val.decode()
        elif num == 3:
            t.nodes.append(parse_node(val))
        elif num == 4:
            t.times.extend(doubles(wt, val))
        elif num == 5:
            t.refs.extend(ints(wt, val))
    return t


WINDOW_FIELDS = {1: "ticks", 2: "cpu_process", 3: "cpu_system", 4: "tps", 5: "mspt_median", 6: "mspt_max", 7: "players",
                 8: "entities", 9: "tile_entities", 10: "chunks", 11: "start_time", 12: "end_time", 13: "duration"}


def parse_profile(path):
    data = open(path, "rb").read()
    if data[:2] == b"\x1f\x8b":
        import gzip
        data = gzip.decompress(data)
    meta, threads, windows, stats = {}, [], [], {}
    for num, wt, val in fields(data):
        if num == 1:
            for mnum, mwt, mval in fields(val):
                if mnum in (2, 3, 11, 12, 15, 16):
                    meta[{2: "start", 3: "interval", 11: "end", 12: "ticks", 15: "mode", 16: "engine"}[mnum]] = mval
                elif mnum == 7:
                    for pnum, _, pval in fields(mval):
                        if pnum in (2, 3, 4, 8):
                            meta[{2: "platform", 3: "version", 4: "minecraft", 8: "brand"}[pnum]] = pval.decode()
                elif mnum == 6:
                    meta["comment"] = mval.decode()
        elif num == 2:
            threads.append(parse_thread(val))
        elif num == 6:
            windows.extend(ints(wt, val))
        elif num == 7:
            key, entry = None, {}
            for enum_, ewt, evalue in fields(val):
                if enum_ == 1:
                    key = evalue
                elif enum_ == 2:
                    for snum, swt, sval in fields(evalue):
                        if snum in WINDOW_FIELDS:
                            entry[WINDOW_FIELDS[snum]] = as_double(swt, sval) if snum in (2, 3, 4, 5, 6) else sval
            stats[key] = entry
    return meta, threads, sorted(windows), stats


def window_mask(windows, stats, skip_first):
    keep = [True] * len(windows)
    for i in range(min(skip_first, len(windows))):
        keep[i] = False
    return keep


def total(times, mask):
    return sum(v for v, k in zip(times, mask) if k)


# frames a thread sits in while it has nothing to do; --busy drops their subtrees
IDLE = re.compile(r"MinecraftServer\.(waitUntilNextTick|recordTaskExecutionTimeWhileWaiting|managedBlock)|LockSupport\.park|Unsafe\.park|Object\.wait|Thread\.sleep|"
                  r"epoll|ForkJoinPool\.awaitWork|ThreadPoolExecutor\.getTask|LinkedBlockingQueue\.take|SynchronousQueue|__futex|pthread_cond_(timed)?wait")


def effective_totals(thread, mask, idle):
    raw = [total(n.times, mask) for n in thread.nodes]
    if idle is None:
        return raw
    eff = [0.0] * len(thread.nodes)
    # nodes are stored post-order, so every child index precedes its parent
    for i, node in enumerate(thread.nodes):
        if idle.search(node.key):
            continue
        own = raw[i] - sum(raw[c] for c in node.refs)
        eff[i] = own + sum(eff[c] for c in node.refs)
    return eff


def thread_total(thread, mask, idle):
    if idle is None:
        return total(thread.times, mask)
    eff = effective_totals(thread, mask, idle)
    return sum(eff[r] for r in thread.refs)


def analyse(thread, mask, idle=None):
    """Per-method self time and path-deduplicated total time (recursion is not double counted)."""
    node_total = effective_totals(thread, mask, idle)
    skip = [idle is not None and idle.search(n.key) is not None for n in thread.nodes]
    self_by, total_by = defaultdict(float), defaultdict(float)
    on_path = defaultdict(int)
    stack = [(r, False) for r in reversed(thread.refs)]
    while stack:
        idx, leaving = stack.pop()
        node = thread.nodes[idx]
        if leaving:
            on_path[node.key] -= 1
            continue
        if skip[idx]:
            continue
        t = node_total[idx]
        self_by[node.key] += t - sum(node_total[c] for c in node.refs)
        if on_path[node.key] == 0:
            total_by[node.key] += t
        on_path[node.key] += 1
        stack.append((idx, True))
        stack.extend((c, False) for c in reversed(node.refs))
    return node_total, self_by, total_by


def select_threads(threads, pattern):
    rx = re.compile(pattern)
    chosen = [t for t in threads if rx.search(t.name)]
    if not chosen:
        sys.exit(f"spark_report: no thread matches {pattern!r}; threads: {', '.join(t.name for t in threads)}")
    return chosen


def merged(chosen, mask, idle=None):
    self_by, total_by, grand = defaultdict(float), defaultdict(float), 0.0
    for t in chosen:
        _, s, tot = analyse(t, mask, idle)
        grand += thread_total(t, mask, idle)
        for k, v in s.items():
            self_by[k] += v
        for k, v in tot.items():
            total_by[k] += v
    return self_by, total_by, grand


def print_tree(thread, mask, min_pct, max_depth, idle=None):
    node_total, _, _ = analyse(thread, mask, idle)
    grand = thread_total(thread, mask, idle) or 1.0
    print(f"\ntree of {thread.name!r} (nodes >= {min_pct}% of the thread, depth <= {max_depth}):")
    stack = [(r, 0) for r in reversed(thread.refs)]
    while stack:
        idx, depth = stack.pop()
        pct = node_total[idx] / grand * 100
        node = thread.nodes[idx]
        if pct < min_pct or (idle is not None and idle.search(node.key)):
            continue
        line = f":{node.line}" if node.line else ""
        print(f"{'  ' * depth}{pct:6.2f}%  {node.key}{line}")
        if depth < max_depth:
            stack.extend((c, depth + 1) for c in sorted(node.refs, key=lambda c: node_total[c]))


def cmd_report(a):
    meta, threads, windows, stats = parse_profile(a.file)
    mask = window_mask(windows, stats, a.skip_windows)
    duration = (meta.get("end", 0) - meta.get("start", 0)) / 1000
    mode = {0: "execution", 1: "allocation", 2: "lock"}.get(meta.get("mode", 0))
    engine = {0: "java", 1: "async"}.get(meta.get("engine", 0))
    print(f"{meta.get('brand') or meta.get('platform')} {meta.get('version', '')} (MC {meta.get('minecraft', '?')}), "
          f"{duration:.0f} s, {mode}/{engine}, interval {meta.get('interval')}, {len(windows)} windows")
    kept = [stats[w] for w, k in zip(windows, mask) if k and w in stats]
    if kept:
        def col(name):
            return [w.get(name, 0) for w in kept]
        print(f"windows: tps min {min(col('tps')):.2f}, mspt median of medians {sorted(col('mspt_median'))[len(kept) // 2]:.2f} ms, "
              f"mspt max {max(col('mspt_max')):.2f} ms, players {max(col('players'))}, entities {max(col('entities'))}, chunks {max(col('chunks'))}")

    if a.threads:
        print("\nthreads by sampled time:")
        all_total = sum(total(t.times, mask) for t in threads) or 1.0
        for t in sorted(threads, key=lambda t: -total(t.times, mask))[:a.top]:
            print(f"  {total(t.times, mask) / all_total * 100:6.2f}%  {t.name}")

    idle = IDLE if a.busy else None
    chosen = select_threads(threads, a.thread)
    self_by, total_by, grand = merged(chosen, mask, idle)
    busy_note = "busy " if a.busy else ""
    print(f"\n{busy_note}time of the selected threads: {grand / 1000:.2f} s over {duration:.0f} s of profile"
          + (f" ({grand / max(duration * 1000, 1) * 100:.1f}% of one core)" if duration else ""))
    grand = grand or 1.0
    rx = re.compile(a.filter) if a.filter else None
    label = ", ".join(t.name for t in chosen[:4]) + (" ..." if len(chosen) > 4 else "")
    print(f"\nthreads: {label}  (shares are of these threads' sampled time)")
    for title, table in (("top self time", self_by), ("top total time (incl. callees)", total_by)):
        print(f"\n{title}:")
        items = [(k, v) for k, v in table.items() if rx is None or rx.search(k)]
        for k, v in sorted(items, key=lambda kv: -kv[1])[:a.top]:
            print(f"  {v / grand * 100:6.2f}%  {v:9.0f} ms  {k}")
    if a.tree:
        for t in chosen[:1]:
            print_tree(t, mask, a.tree, a.depth, idle)


def cmd_diff(a):
    rows = []
    shares = []
    for path in (a.before, a.after):
        meta, threads, windows, stats = parse_profile(path)
        mask = window_mask(windows, stats, a.skip_windows)
        self_by, total_by, grand = merged(select_threads(threads, a.thread), mask, IDLE if a.busy else None)
        table = total_by if a.total else self_by
        shares.append({k: v / (grand or 1.0) * 100 for k, v in table.items()})
    rx = re.compile(a.filter) if a.filter else None
    for k in set(shares[0]) | set(shares[1]):
        if rx and not rx.search(k):
            continue
        b, c = shares[0].get(k, 0.0), shares[1].get(k, 0.0)
        rows.append((c - b, b, c, k))
    print(f"{'total' if a.total else 'self'} time share, before -> after (largest changes; shares, not absolute time):")
    for delta, b, c, k in sorted(rows, key=lambda r: -abs(r[0]))[:a.top]:
        print(f"  {delta:+7.2f}  {b:6.2f}% -> {c:6.2f}%  {k}")


def main():
    p = argparse.ArgumentParser(prog="spark_report.py", description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--thread", default=r"^Server thread$", help="regex of thread names to analyse (default: main thread)")
    common.add_argument("--filter", help="regex: only list methods matching it, e.g. org\\.bxteam")
    common.add_argument("--top", type=int, default=25)
    common.add_argument("--skip-windows", type=int, default=0, help="drop the first N one-minute windows (warm-up)")
    common.add_argument("--busy", action="store_true", help="drop idle/wait subtrees; shares become shares of busy time")

    s = sub.add_parser("report", parents=[common])
    s.add_argument("file")
    s.add_argument("--threads", action="store_true", help="list threads by sampled time")
    s.add_argument("--tree", type=float, metavar="PCT", help="print the call tree down to nodes >= PCT%% of the thread")
    s.add_argument("--depth", type=int, default=25)
    s.set_defaults(fn=cmd_report)

    s = sub.add_parser("diff", parents=[common])
    s.add_argument("before")
    s.add_argument("after")
    s.add_argument("--total", action="store_true", help="compare total (inclusive) instead of self time")
    s.set_defaults(fn=cmd_diff)

    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
