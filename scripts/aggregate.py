#!/usr/bin/env python3
import argparse
import json
import re
import statistics
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def read_csv(path):
    if not path.exists():
        return []
    lines = path.read_text(encoding="utf-8").splitlines()
    if len(lines) < 2:
        return []
    header = lines[0].split(",")
    return [dict(zip(header, line.split(","))) for line in lines[1:] if line.strip()]

def marks(run_dir):
    return {m["label"]: (int(m["tick"]), int(m["epoch_ms"])) for m in read_csv(run_dir / "marks.csv")}

def percentile(sorted_values, q):
    if not sorted_values:
        return None
    if len(sorted_values) == 1:
        return sorted_values[0]
    pos = (len(sorted_values) - 1) * q
    lo = int(pos)
    hi = min(lo + 1, len(sorted_values) - 1)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (pos - lo)

def mspt_stats(run_dir, start_tick, end_tick):
    rows = read_csv(run_dir / "ticks.csv")
    values = [
        int(r["duration_ns"]) / 1e6
        for r in rows
        if start_tick <= int(r["tick"]) <= end_tick
    ]
    if not values:
        return None
    values.sort()
    return {
        "ticks": len(values),
        "p50": round(percentile(values, 0.50), 3),
        "p95": round(percentile(values, 0.95), 3),
        "p99": round(percentile(values, 0.99), 3),
        "max": round(values[-1], 3),
        "mean": round(statistics.fmean(values), 3),
    }

def cpu_between(run_dir, start_ms, end_ms, name="sysmon.csv"):
    rows = read_csv(run_dir / name)
    if not rows:
        return None
    inside = [r for r in rows if start_ms <= int(r["epoch_ms"]) <= end_ms]
    if len(inside) < 2:
        return None
    cpu = float(inside[-1]["proc_cpu_seconds_total"]) - float(inside[0]["proc_cpu_seconds_total"])
    return {
        "cpu_seconds": round(cpu, 1),
        "peak_rss_mb": round(max(int(r["rss_bytes"]) for r in inside) / 1024 / 1024),
        "mean_proc_cpu_pct": round(statistics.fmean(float(r["proc_cpu_pct"]) for r in inside), 1),
    }

def summarise(values, digits=1):
    if not values:
        return None
    values = sorted(values)
    median = statistics.median(values)
    return {
        "median": round(median, digits),
        "min": round(values[0], digits),
        "max": round(values[-1], digits),
        "n": len(values),
        "spread_pct": round((values[-1] - values[0]) / median * 100, 1) if median else None,
    }

def collect_chunkgen(cell_dir):
    runs = []
    for run_dir in sorted(cell_dir.glob("run*")):
        if not (run_dir / "COMPLETE").exists():
            continue
        m = marks(run_dir)
        if "gen_start" not in m or "gen_end" not in m:
            continue
        (t0, e0), (t1, e1) = m["gen_start"], m["gen_end"]
        entry = {
            "run": run_dir.name,
            "wall_seconds": round((e1 - e0) / 1000, 1),
            "mspt": mspt_stats(run_dir, t0, t1),
        }
        entry.update(cpu_between(run_dir, e0, e1) or {})
        chunks = None
        chunky_seconds = None
        console = run_dir / "console.log"
        if console.exists():
            for line in console.read_text(encoding="utf-8", errors="replace").splitlines():
                if "Task finished for" in line and "Processed:" in line:
                    chunks = int(line.split("Processed:")[1].split("chunks")[0].strip().replace(",", ""))
                    if "Total time:" in line:
                        try:
                            secs = 0
                            for part in line.split("Total time:")[1].strip().split(":"):
                                secs = secs * 60 + int(part)
                            chunky_seconds = secs
                        except ValueError:
                            pass
        entry["chunks"] = chunks
        if chunky_seconds:
            entry["chunky_seconds"] = chunky_seconds
        if chunks and entry["wall_seconds"]:
            entry["chunks_per_second"] = round(chunks / entry["wall_seconds"], 1)
        runs.append(entry)
    return runs

DEATH = re.compile(r"\[(\d\d:\d\d:\d\d) INFO\]: Bench\d{4} (was|died|fell|drowned|blew|burned|went|hit|walked|tried|froze|starved|suffocated|experienced|discovered|withered|didn't)\b")
STAMP = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]$")
REGION = re.compile(r"^Region with (\d+) chunks and (\d+) entities")
PLAYER = re.compile(r"^- \S+ avg region tick time:")

def day_seconds(hms):
    h, m, s = (int(x) for x in hms.split(":"))
    return h * 3600 + m * 60 + s

def local_window(e0, e1):
    lo = day_seconds(time.strftime("%H:%M:%S", time.localtime(e0 / 1000)))
    hi = day_seconds(time.strftime("%H:%M:%S", time.localtime(e1 / 1000)))
    if hi >= lo:
        return lambda t: lo <= t <= hi
    return lambda t: t >= lo or t <= hi

def bot_deaths(run_dir, inside):
    console = run_dir / "console.log"
    if not console.exists():
        return None
    return sum(1 for line in console.read_text(encoding="utf-8", errors="replace").splitlines()
               if (m := DEATH.search(line)) and inside(day_seconds(m.group(1))))

def region_stats(run_dir, inside):
    logs = sorted((run_dir / "tracking").glob("*/region-tick-*.log"))
    if not logs:
        return None
    snaps, current, keep = [], None, False
    for log in logs:
        for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
            if m := STAMP.match(line):
                keep = inside(day_seconds(f"{m.group(1)}:{m.group(2)}:{m.group(3)}"))
                current = [] if keep else None
                if keep:
                    snaps.append(current)
            elif current is not None and (m := REGION.match(line)):
                current.append([int(m.group(1)), int(m.group(2)), 0])
            elif current is not None and current and PLAYER.match(line):
                current[-1][2] += 1
    snaps = [s for s in snaps if s]
    if not snaps:
        return None
    return {
        "snapshots": len(snaps),
        "regions": statistics.median(len(s) for s in snaps),
        "largest_chunk_share": round(statistics.median(max(r[0] for r in s) / sum(r[0] for r in s) for s in snaps), 2),
        "largest_entity_share": round(statistics.median(max(r[1] for r in s) / max(1, sum(r[1] for r in s)) for s in snaps), 2),
        "most_players_in_region": max(max(r[2] for r in s) for s in snaps),
    }

def collect_mspt(cell_dir):
    runs = []
    for run_dir in sorted(cell_dir.glob("run*")):
        if not (run_dir / "COMPLETE").exists():
            continue
        m = marks(run_dir)
        if "measure_start" not in m or "measure_end" not in m:
            continue
        (t0, e0), (t1, e1) = m["measure_start"], m["measure_end"]
        entry = {"run": run_dir.name, "mspt": mspt_stats(run_dir, t0, t1)}
        entry.update(cpu_between(run_dir, e0, e1) or {})
        inside = local_window(e0, e1)
        deaths = bot_deaths(run_dir, inside)
        if deaths is not None:
            entry["bot_deaths"] = deaths
        regions = region_stats(run_dir, inside)
        if regions:
            entry["regions"] = regions
        bots = cpu_between(run_dir, e0, e1, "bots-sysmon.csv")
        if bots:
            entry["bot_cpu_seconds"] = bots["cpu_seconds"]
            entry["bot_cpu_pct"] = bots["mean_proc_cpu_pct"]
        runs.append(entry)
    return runs

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario", choices=["chunkgen", "botswarm", "rctclusters", "rctblocks"])
    args = ap.parse_args()

    raw = ROOT / "work" / "raw" / args.scenario
    collect = collect_chunkgen if args.scenario == "chunkgen" else collect_mspt

    cells = {}
    for cell_dir in sorted(raw.glob("*__*")):
        build, profile = cell_dir.name.split("__")
        runs = collect(cell_dir)
        if not runs:
            continue
        cell = {"build": build, "profile": profile, "runs": runs}
        if args.scenario == "chunkgen":
            cell["wall_seconds"] = summarise([r["wall_seconds"] for r in runs])
            cell["cpu_seconds"] = summarise([r["cpu_seconds"] for r in runs if "cpu_seconds" in r])
            cell["chunks_per_second"] = summarise([r["chunks_per_second"] for r in runs if r.get("chunks_per_second")])
        cell["mspt_p50"] = summarise([r["mspt"]["p50"] for r in runs if r["mspt"]], 3)
        cell["mspt_p99"] = summarise([r["mspt"]["p99"] for r in runs if r["mspt"]], 3)
        cell["peak_rss_mb"] = summarise([r["peak_rss_mb"] for r in runs if "peak_rss_mb" in r], 0)
        cell["bot_cpu_pct"] = summarise([r["bot_cpu_pct"] for r in runs if "bot_cpu_pct" in r], 0)
        if any("bot_deaths" in r for r in runs):
            cell["bot_deaths"] = summarise([r["bot_deaths"] for r in runs if "bot_deaths" in r], 0)
        if any("regions" in r for r in runs):
            cell["regions"] = summarise([r["regions"]["regions"] for r in runs if "regions" in r], 1)
        cells[cell_dir.name] = cell

    baseline = cells.get("paper__stock")
    key = "wall_seconds" if args.scenario == "chunkgen" else "mspt_p50"
    if baseline and baseline.get(key):
        base = baseline[key]["median"]
        for cell in cells.values():
            if cell.get(key):
                cell["vs_paper_pct"] = round((base - cell[key]["median"]) / base * 100, 1)

    out = ROOT / "results" / f"{args.scenario}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({"scenario": args.scenario, "cells": cells}, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out}")

    width = max(len(k) for k in cells) if cells else 10
    if args.scenario == "chunkgen":
        print(f"\n{'cell':<{width}}  {'wall s (median)':>16}  {'spread':>7}  {'cpu s':>8}  {'cps':>7}  {'vs paper':>9}")
        for name, c in sorted(cells.items(), key=lambda kv: kv[1]["wall_seconds"]["median"] if kv[1].get("wall_seconds") else 1e9):
            w, cpu, cps = c.get("wall_seconds"), c.get("cpu_seconds"), c.get("chunks_per_second")
            print(f"{name:<{width}}  {w['median']:>16}  {str(w['spread_pct']) + '%':>7}  "
                  f"{(cpu['median'] if cpu else 0):>8}  {(cps['median'] if cps else 0):>7}  "
                  f"{str(c.get('vs_paper_pct', '')) + '%':>9}")
    else:
        print(f"\n{'cell':<{width}}  {'p50 ms':>8}  {'p99 ms':>8}  {'srv cpu%':>9}  {'bot cpu%':>9}  {'deaths':>7}  {'regions':>8}  {'vs paper':>9}")
        for name, c in sorted(cells.items(), key=lambda kv: kv[1]["mspt_p50"]["median"] if kv[1].get("mspt_p50") else 1e9):
            bot = c.get("bot_cpu_pct")
            print(f"{name:<{width}}  {c['mspt_p50']['median']:>8}  {c['mspt_p99']['median']:>8}  "
                  f"{statistics.fmean([r['mean_proc_cpu_pct'] for r in c['runs'] if 'mean_proc_cpu_pct' in r] or [0]):>9.0f}  "
                  f"{(bot['median'] if bot else 0):>9.0f}  "
                  f"{(c['bot_deaths']['median'] if c.get('bot_deaths') else '-'):>7}  "
                  f"{(c['regions']['median'] if c.get('regions') else '-'):>8}  "
                  f"{str(c.get('vs_paper_pct', '')) + '%':>9}")

if __name__ == "__main__":
    main()
