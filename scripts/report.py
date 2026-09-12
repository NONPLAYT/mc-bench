#!/usr/bin/env python3
"""Turns a directory of aggregate.py outputs into publishable tables.

Reads results/<host>/<scenario>[-<variant>].json and writes a markdown report
plus one CSV per scenario, both relative to Paper stock. Every derived number
here comes from the medians already in those files; nothing is re-measured.
"""
import argparse
import csv
import json
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUDGET_MS = 50.0  # one tick at 20 TPS

def cell_label(cell):
    return f"{cell['build']} {cell['profile']}"

def median_of(runs, *path, default=None):
    values = []
    for r in runs:
        v = r
        for k in path:
            v = v.get(k) if isinstance(v, dict) else None
            if v is None:
                break
        if v is not None:
            values.append(v)
    return statistics.median(values) if values else default

def mspt_rows(doc):
    base = doc["cells"].get("paper__stock")
    rows = []
    for name, c in doc["cells"].items():
        runs = c["runs"]
        cpu_s = median_of(runs, "cpu_seconds")
        ticks = median_of(runs, "mspt", "ticks")
        row = {
            "cell": name,
            "label": cell_label(c),
            "p50": c["mspt_p50"]["median"],
            "p50_spread": c["mspt_p50"]["spread_pct"],
            "p99": c["mspt_p99"]["median"],
            "p99_spread": c["mspt_p99"]["spread_pct"],
            "max": median_of(runs, "mspt", "max"),
            "srv_cpu_pct": statistics.fmean(r["mean_proc_cpu_pct"] for r in runs),
            "srv_cpu_seconds": cpu_s,
            "cpu_ms_per_tick": cpu_s * 1000 / ticks,
            "bot_cpu_pct": (c.get("bot_cpu_pct") or {}).get("median"),
            "peak_rss_mb": c["peak_rss_mb"]["median"],
            "runs": len(runs),
        }
        row["cores_used"] = row["srv_cpu_pct"] / 100
        row["tick_headroom_pct"] = (BUDGET_MS - row["p50"]) / BUDGET_MS * 100
        row["p99_headroom_pct"] = (BUDGET_MS - row["p99"]) / BUDGET_MS * 100
        row["load_headroom_x"] = BUDGET_MS / row["p50"]
        rows.append(row)
    if base:
        b50 = base["mspt_p50"]["median"]
        b99 = base["mspt_p99"]["median"]
        bcpu = median_of(base["runs"], "cpu_seconds")
        for row in rows:
            row["vs_paper_p50_pct"] = (b50 - row["p50"]) / b50 * 100
            row["vs_paper_p99_pct"] = (b99 - row["p99"]) / b99 * 100
            row["speedup"] = b50 / row["p50"]
            row["cpu_vs_paper_pct"] = (row["srv_cpu_seconds"] - bcpu) / bcpu * 100
    return sorted(rows, key=lambda r: r["p50"])

def chunkgen_rows(doc):
    base = doc["cells"].get("paper__stock")
    rows = []
    for name, c in doc["cells"].items():
        runs = c["runs"]
        chunks = median_of(runs, "chunks")
        row = {
            "cell": name,
            "label": cell_label(c),
            "wall_seconds": c["wall_seconds"]["median"],
            "wall_spread": c["wall_seconds"]["spread_pct"],
            "chunks_per_second": c["chunks_per_second"]["median"],
            "cps_spread": c["chunks_per_second"]["spread_pct"],
            "cpu_seconds": c["cpu_seconds"]["median"],
            "cpu_spread": c["cpu_seconds"]["spread_pct"],
            "srv_cpu_pct": statistics.fmean(r["mean_proc_cpu_pct"] for r in runs),
            "peak_rss_mb": c["peak_rss_mb"]["median"],
            "p50": c["mspt_p50"]["median"],
            "p99": c["mspt_p99"]["median"],
            "chunks": chunks,
            "runs": len(runs),
        }
        row["cores_used"] = row["srv_cpu_pct"] / 100
        row["cpu_seconds_per_1k_chunks"] = row["cpu_seconds"] / chunks * 1000
        rows.append(row)
    if base:
        bw = base["wall_seconds"]["median"]
        bcpu = base["cpu_seconds"]["median"]
        for row in rows:
            row["vs_paper_pct"] = (bw - row["wall_seconds"]) / bw * 100
            row["speedup"] = bw / row["wall_seconds"]
            row["cpu_vs_paper_pct"] = (row["cpu_seconds"] - bcpu) / bcpu * 100
    return sorted(rows, key=lambda r: r["wall_seconds"])

def pct(v, signed=False):
    if v is None:
        return "—"
    return f"{v:+.1f}%" if signed else f"{v:.1f}%"

def mspt_table(rows):
    out = [
        "| Build / profile | p50, ms | spread | p99, ms | max, ms | server CPU | cores | bot CPU | RSS, MB | p50 vs Paper | p99 vs Paper | CPU vs Paper | headroom to 50 ms |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for r in rows:
        bot = f"{r['bot_cpu_pct']:.0f}%" if r["bot_cpu_pct"] else "—"
        out.append(
            f"| {r['label']} | {r['p50']:.2f} | {pct(r['p50_spread'])} | {r['p99']:.2f} | {r['max']:.1f} | "
            f"{r['srv_cpu_pct']:.0f}% | {r['cores_used']:.2f} | "
            f"{bot} | {r['peak_rss_mb']:.0f} | "
            f"{pct(r.get('vs_paper_p50_pct'), True)} | {pct(r.get('vs_paper_p99_pct'), True)} | "
            f"{pct(r.get('cpu_vs_paper_pct'), True)} | {pct(r['tick_headroom_pct'])} (×{r['load_headroom_x']:.1f}) |"
        )
    return "\n".join(out)

def chunkgen_table(rows):
    out = [
        "| Build / profile | time, s | spread | chunks/s | CPU-s | CPU-s per 1000 chunks | cores | RSS, MB | vs Paper | CPU vs Paper |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for r in rows:
        out.append(
            f"| {r['label']} | {r['wall_seconds']:.1f} | {pct(r['wall_spread'])} | {r['chunks_per_second']:.1f} | "
            f"{r['cpu_seconds']:.0f} | {r['cpu_seconds_per_1k_chunks']:.2f} | {r['cores_used']:.1f} | "
            f"{r['peak_rss_mb']:.0f} | {pct(r.get('vs_paper_pct'), True)} | {pct(r.get('cpu_vs_paper_pct'), True)} |"
        )
    return "\n".join(out)

def write_csv(path, rows):
    if not rows:
        return
    fields = sorted({k for r in rows for k in r})
    ordered = ["cell", "label"] + [f for f in fields if f not in ("cell", "label")]
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=ordered)
        w.writeheader()
        for r in rows:
            w.writerow({k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()})

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results_dir", help="directory of aggregate.py outputs, e.g. results/9950x3d")
    ap.add_argument("--out", help="markdown file to write (default: <results_dir>/report.md)")
    args = ap.parse_args()

    src = Path(args.results_dir)
    if not src.is_absolute():
        src = ROOT / src
    files = sorted(src.glob("*.json"))
    if not files:
        raise SystemExit(f"no result files in {src}")

    docs = {f.stem: json.loads(f.read_text(encoding="utf-8")) for f in files}
    md = ["# Results", ""]
    for stem, doc in sorted(docs.items(), key=lambda kv: (kv[1]["scenario"], kv[0])):
        rows = chunkgen_rows(doc) if doc["scenario"] == "chunkgen" else mspt_rows(doc)
        table = chunkgen_table(rows) if doc["scenario"] == "chunkgen" else mspt_table(rows)
        md += [f"## {stem}", "", table, ""]
        write_csv(src / f"{stem}.csv", rows)

    out = Path(args.out) if args.out else src / "report.md"
    if not out.is_absolute():
        out = ROOT / out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"wrote {out} and {len(docs)} csv files in {src}")

if __name__ == "__main__":
    main()
