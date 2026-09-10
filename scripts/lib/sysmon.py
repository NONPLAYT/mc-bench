#!/usr/bin/env python3
import os
import sys
import time
from pathlib import Path

CLK_TCK = os.sysconf("SC_CLK_TCK")

def read_proc_cpu(pid):
    try:
        parts = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    except (FileNotFoundError, ProcessLookupError, IndexError):
        return None
    utime, stime = int(parts[11]), int(parts[12])
    return (utime + stime) / CLK_TCK

def read_proc_rss(pid):
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) * 1024
    except (FileNotFoundError, ProcessLookupError):
        return None
    return 0

def read_cpu_mhz():
    total, n = 0, 0
    for path in Path("/sys/devices/system/cpu").glob("cpu[0-9]*/cpufreq/scaling_cur_freq"):
        try:
            total += int(path.read_text()) / 1000.0
            n += 1
        except (OSError, ValueError):
            pass
    return total / n if n else 0.0

PKG_TEMP = next(
    (h / "temp1_input" for h in Path("/sys/class/hwmon").glob("hwmon*")
     if (h / "name").exists() and (h / "name").read_text().strip() in ("k10temp", "coretemp", "zenpower")),
    None,
)

def read_pkg_temp():
    if PKG_TEMP is None:
        return 0.0
    try:
        return int(PKG_TEMP.read_text()) / 1000.0
    except (OSError, ValueError):
        return 0.0

def read_system_cpu():
    fields = Path("/proc/stat").read_text().split("\n", 1)[0].split()[1:]
    values = [int(x) for x in fields]
    idle = values[3] + values[4]
    return sum(values) / CLK_TCK, idle / CLK_TCK

def main():
    pid = int(sys.argv[1])
    out = Path(sys.argv[2])
    interval = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0

    prev_cpu = read_proc_cpu(pid)
    prev_sys, prev_idle = read_system_cpu()
    prev_t = time.time()

    with out.open("w", encoding="utf-8") as fh:
        fh.write("epoch_ms,proc_cpu_seconds_total,proc_cpu_pct,rss_bytes,system_cpu_pct,cpu_mhz,pkg_temp_c\n")
        fh.flush()
        while True:
            time.sleep(interval)
            cpu = read_proc_cpu(pid)
            if cpu is None:
                break
            rss = read_proc_rss(pid)
            now = time.time()
            sys_total, sys_idle = read_system_cpu()

            wall = now - prev_t
            proc_pct = (cpu - prev_cpu) / wall * 100.0 if wall > 0 else 0.0
            d_total = sys_total - prev_sys
            d_idle = sys_idle - prev_idle
            sys_pct = (1.0 - d_idle / d_total) * 100.0 if d_total > 0 else 0.0

            fh.write(f"{int(now * 1000)},{cpu:.3f},{proc_pct:.2f},{rss},{sys_pct:.2f},{read_cpu_mhz():.0f},{read_pkg_temp():.1f}\n")
            fh.flush()

            prev_cpu, prev_t, prev_sys, prev_idle = cpu, now, sys_total, sys_idle

if __name__ == "__main__":
    main()
