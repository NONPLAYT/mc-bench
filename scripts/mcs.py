#!/usr/bin/env python3
"""Throwaway Minecraft server control for testing server cores (Paper and forks). Part of mc-bench."""
import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import socket
import statistics
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AGENT_SRC = ROOT / "agent"
SYSMON = ROOT / "scripts" / "lib" / "sysmon.py"
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "mc-bench"
STATE = ".mcs.json"
OUT = "agent-out"

JVM_FLAGS = [
    "--add-modules=jdk.incubator.vector",
    "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
    "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
    "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15",
    "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32",
    "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1", "-XX:G1NewSizePercent=30",
    "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
    "-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true",
]


def die(msg, code=1):
    print(f"mcs: {msg}", file=sys.stderr)
    sys.exit(code)


def load_state(d: Path) -> dict:
    p = d / STATE
    if not p.exists():
        die(f"{d} is not an mcs server dir (run `mcs.py new` first)")
    return json.loads(p.read_text())


def save_state(d: Path, state: dict):
    (d / STATE).write_text(json.dumps(state, indent=2) + "\n")


def alive(state: dict) -> bool:
    pid = state.get("pid")
    if not pid:
        return False
    try:
        cmdline = Path(f"/proc/{pid}/cmdline").read_bytes().decode(errors="replace")
        cwd = os.readlink(f"/proc/{pid}/cwd")
    except (FileNotFoundError, PermissionError):
        return False
    # guards against pid reuse; never match servers by process name (NixOS calls java .java-wrapped)
    return "server.jar" in cmdline and cwd == str(Path(state["jar"]).parent)


def port_in_use(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def set_properties(path: Path, assignments):
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    keys = {}
    for i, line in enumerate(lines):
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            keys[line.split("=", 1)[0].strip()] = i
    for a in assignments:
        k, _, v = a.partition("=")
        if k in keys:
            lines[keys[k]] = f"{k}={v}"
        else:
            keys[k] = len(lines)
            lines.append(f"{k}={v}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def find_yaml_key(lines, segments):
    lo, hi, idx = 0, len(lines), None
    for seg in segments:
        idx, expect = None, None
        for i in range(lo, hi):
            stripped = lines[i].strip()
            if not stripped or stripped.startswith("#") or stripped.startswith("-"):
                continue
            ind = indent_of(lines[i])
            if expect is None:
                expect = ind
            if ind < expect:
                break
            if ind == expect and stripped.split(":", 1)[0].strip().strip("'\"") == seg:
                idx = i
                break
        if idx is None:
            return None
        base, j = indent_of(lines[idx]), idx + 1
        while j < len(lines) and not (lines[j].strip() and not lines[j].strip().startswith("#") and indent_of(lines[j]) <= base):
            j += 1
        lo, hi = idx + 1, j
    return idx


def set_yaml(path: Path, assignments):
    lines = path.read_text(encoding="utf-8").splitlines()
    missing = []
    for a in assignments:
        key, _, value = a.partition("=")
        idx = find_yaml_key(lines, key.strip().split("."))
        if idx is None:
            missing.append(key)
            continue
        name = lines[idx].strip().split(":", 1)[0].strip()
        lines[idx] = f"{' ' * indent_of(lines[idx])}{name}: {value}"
    if missing:
        # a renamed setting must fail loudly, never silently test the default
        die(f"{path}: key(s) not found: {', '.join(missing)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def rcon(state: dict, command: str, timeout: float = 30.0) -> str:
    def packet(req_id, kind, body):
        data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
        return struct.pack("<i", len(data)) + data

    def read(sock):
        header = b""
        while len(header) < 4:
            chunk = sock.recv(4 - len(header))
            if not chunk:
                raise ConnectionError("rcon closed")
            header += chunk
        (length,) = struct.unpack("<i", header)
        data = b""
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                raise ConnectionError("rcon closed")
            data += chunk
        req_id, kind = struct.unpack("<ii", data[:8])
        return req_id, kind, data[8:-2].decode("utf-8", errors="replace")

    with socket.create_connection(("127.0.0.1", state["rcon_port"]), timeout=timeout) as sock:
        sock.sendall(packet(1, 3, state["rcon_password"]))
        req_id, _, _ = read(sock)
        if req_id == -1:
            die("rcon authentication failed")
        sock.sendall(packet(2, 2, command))
        # an empty command after the real one marks the end of a fragmented response
        sock.sendall(packet(3, 2, ""))
        out = []
        while True:
            req_id, _, body = read(sock)
            if req_id == 3:
                break
            out.append(body)
        return "".join(out)


def patch_server(d: Path):
    """Runs paperclip's patch step once, so versions/ and libraries/ exist to compile plugins against."""
    if sorted((d / "versions").glob("*/*.jar")):
        return
    jar = d / "server.jar"
    if not jar.exists():
        die(f"{d}: no server.jar")
    res = subprocess.run(["java", "-Dpaperclip.patchonly=true", "-jar", "server.jar"], cwd=d, capture_output=True, text=True)
    if res.returncode != 0:
        die("paperclip patch failed:\n" + res.stdout[-3000:] + res.stderr[-3000:])


def build_plugin(d: Path, src: Path, name: str) -> Path:
    """Compiles a plugin source dir (plugin.yml + src/**/*.java) against the exact server in d, cached by content hash.

    The classpath is the server's Mojang-mapped classes and libraries plus the jars in d/plugins, so a probe plugin
    can use NMS and depend on TestAgent.
    """
    patch_server(d)
    versions = sorted((d / "versions").glob("*/*.jar"))
    libraries = sorted((d / "libraries").rglob("*.jar"))
    plugins = sorted(p for p in (d / "plugins").glob("*.jar") if p.name != f"{name}.jar") if (d / "plugins").exists() else []
    sources = sorted((src / "src").rglob("*.java"))
    if not sources or not (src / "plugin.yml").exists():
        die(f"{src}: expected plugin.yml and src/**/*.java")
    h = hashlib.sha256()
    for f in [versions[0], *plugins, *sources, src / "plugin.yml"]:
        h.update(f.read_bytes())
    jar = CACHE / f"{name}-{h.hexdigest()[:16]}.jar"
    if jar.exists():
        return jar
    build = CACHE / f"build-{name}"
    shutil.rmtree(build, ignore_errors=True)
    (build / "classes").mkdir(parents=True)
    cp = ":".join(str(p) for p in [*versions, *libraries, *plugins])
    res = subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "--release", "25", "-cp", cp, "-d", str(build / "classes"), *map(str, sources)],
                         capture_output=True, text=True)
    if res.returncode != 0:
        die(f"{name} build failed:\n" + res.stdout + res.stderr)
    shutil.copy(src / "plugin.yml", build / "classes" / "plugin.yml")
    # Paper runs Mojang-mapped; without this the plugin's NMS references get remapped from Spigot names and break
    (build / "MANIFEST.MF").write_text("Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n")
    subprocess.run(["jar", "--create", "--file", str(jar), "--manifest", str(build / "MANIFEST.MF"), "-C", str(build / "classes"), "."], check=True)
    return jar


def install_plugin(d: Path, src: Path, name: str) -> Path:
    jar = build_plugin(d, src, name)
    (d / "plugins").mkdir(exist_ok=True)
    shutil.copy(jar, d / "plugins" / f"{name}.jar")
    return jar


def cmd_agent(a):
    d = Path(a.dir).resolve()
    install_plugin(d, AGENT_SRC, "TestAgent")
    print(f"TestAgent installed in {d / 'plugins'}")


def cmd_plugin(a):
    d = Path(a.dir).resolve()
    src = Path(a.src).resolve()
    name = a.name or src.name
    install_plugin(d, src, name)
    print(f"{name} built against {d} and installed")


def cmd_new(a):
    d = Path(a.dir).resolve()
    if d.exists() and any(d.iterdir()) and not a.force:
        die(f"{d} exists and is not empty (use --force to reuse it)")
    d.mkdir(parents=True, exist_ok=True)
    jar = Path(a.jar).resolve()
    if not jar.is_file():
        die(f"no such jar: {jar}")
    # a copy, so rebuilding the project jar never changes a server mid-test
    shutil.copy(jar, d / "server.jar")
    (d / "eula.txt").write_text("eula=true\n")
    rcon_port = a.port + 10
    props = [
        "online-mode=false", f"server-port={a.port}", "enable-rcon=true", f"rcon.port={rcon_port}",
        f"rcon.password={secrets.token_hex(12)}", "broadcast-rcon-to-ops=false", "spawn-protection=0",
        "max-players=1000", f"view-distance={a.view_distance}", f"simulation-distance={a.simulation_distance}",
        "motd=mcs test server", "enable-status=true",
    ]
    if a.seed is not None:
        props.append(f"level-seed={a.seed}")
    if a.flat:
        props.append("level-type=minecraft\\:flat")
    props.extend(a.prop or [])
    set_properties(d / "server.properties", props)
    password = next(p.split("=", 1)[1] for p in props if p.startswith("rcon.password="))
    print("patching server jar (paperclip patch-only)...")
    patch_server(d)
    state = {"jar": str(d / "server.jar"), "port": a.port, "rcon_port": rcon_port, "rcon_password": password, "pid": None}
    if not a.no_agent:
        install_plugin(d, AGENT_SRC, "TestAgent")
    save_state(d, state)
    print(f"ready: {d} (port {a.port}, rcon {rcon_port}){'' if a.no_agent else ', TestAgent installed'}")


def cmd_start(a):
    d = Path(a.dir).resolve()
    state = load_state(d)
    if alive(state):
        die(f"already running (pid {state['pid']})")
    for port in (state["port"], state["rcon_port"]):
        # a server killed a moment ago can still hold its ports briefly
        deadline = time.time() + 15
        while port_in_use(port) and time.time() < deadline:
            time.sleep(0.5)
        if port_in_use(port):
            die(f"port {port} is in use by another process")
    out = d / OUT
    out.mkdir(exist_ok=True)
    (out / "ready").unlink(missing_ok=True)
    fifo = d / "console.in"
    if not fifo.exists():
        os.mkfifo(fifo)
    # O_RDWR keeps a writer open on the FIFO, so the server's console never sees EOF
    stdin_fd = os.open(fifo, os.O_RDWR)
    log = open(d / "console.log", "w")
    java = ["java", f"-Xms{a.xmx}", f"-Xmx{a.xmx}", *JVM_FLAGS, f"-Dtestagent.out={out}", "-Dterminal.jline=false", "-Dterminal.ansi=false",
            *[f"-D{p}" for p in (a.sysprop or [])], *(a.jvm or []), "-jar", "server.jar", "--nogui"]
    if a.cpus:
        java = ["taskset", "-c", a.cpus, *java]
    proc = subprocess.Popen(java, cwd=d, stdin=stdin_fd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    os.close(stdin_fd)
    state.update(pid=proc.pid, started=time.time(), xmx=a.xmx, sysmon_pid=None)
    if not a.no_sysmon:
        sm = subprocess.Popen([sys.executable, str(SYSMON), str(proc.pid), str(out / "sysmon.csv"), "1.0"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        state["sysmon_pid"] = sm.pid
    save_state(d, state)
    print(f"started pid {proc.pid}, log {d / 'console.log'}")


def cmd_wait(a):
    d = Path(a.dir).resolve()
    state = load_state(d)
    deadline = time.time() + a.timeout
    log = d / "console.log"
    while time.time() < deadline:
        if (d / OUT / "ready").exists() or ("Done (" in log.read_text(errors="replace") if log.exists() else False):
            print(f"ready after {time.time() - state.get('started', time.time()):.0f}s")
            return
        if not alive(state):
            tail = log.read_text(errors="replace").splitlines()[-60:] if log.exists() else []
            die("server exited during startup; last lines:\n" + "\n".join(tail))
        time.sleep(1)
    die(f"not ready within {a.timeout}s")


def cmd_cmd(a):
    d = Path(a.dir).resolve()
    state = load_state(d)
    command = " ".join(a.command)
    if not command:
        die("no command given")
    try:
        print(rcon(state, command, a.timeout))
    except (ConnectionError, OSError) as e:
        if not a.console_fallback:
            die(f"rcon failed ({e}); pass --console to write to the console FIFO instead (no reply)")
        with open(d / "console.in", "w") as f:
            f.write(command + "\n")
        print("(sent via console, no reply)")


def stop_sysmon(state):
    pid = state.get("sysmon_pid")
    if pid:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass


def wait_exit(state, timeout) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not alive(state):
            return True
        time.sleep(0.5)
    return False


def cmd_stop(a):
    d = Path(a.dir).resolve()
    state = load_state(d)
    if not alive(state):
        print("not running")
        stop_sysmon(state)
        return
    try:
        rcon(state, "stop", 10)
    except (ConnectionError, OSError):
        with open(d / "console.in", "w") as f:
            f.write("stop\n")
    if not wait_exit(state, a.timeout):
        print(f"server ignored stop for {a.timeout}s, killing", file=sys.stderr)
        os.kill(state["pid"], signal.SIGKILL)
        wait_exit(state, 30)
    stop_sysmon(state)
    print("stopped")


def cmd_kill(a):
    d = Path(a.dir).resolve()
    state = load_state(d)
    if not alive(state):
        die("not running")
    os.kill(state["pid"], signal.SIGKILL)
    wait_exit(state, 30)
    stop_sysmon(state)
    print(f"killed pid {state['pid']} with SIGKILL")


def cmd_status(a):
    d = Path(a.dir).resolve()
    state = load_state(d)
    if alive(state):
        print(f"running pid {state['pid']} for {time.time() - state['started']:.0f}s, port {state['port']}")
    else:
        print("not running")


# startup noise of every offline test server
BENIGN = [re.compile(p) for p in (
    r"OFFLINE/INSECURE MODE", r"make no attempt to authenticate", r"hackers to connect", r'set "online-mode" to "true"',
    r"Did not find udev library", r"Using incubator modules",
    r"No key layers in MapLike",  # flat world preset written by `new --flat`
)]


def cmd_log(a):
    d = Path(a.dir).resolve()
    text = (d / "console.log").read_text(errors="replace").splitlines()
    if a.since:
        for i in range(len(text) - 1, -1, -1):
            if a.since in text[i]:
                text = text[i:]
                break
    pattern = re.compile(a.grep) if a.grep else None
    for line in text:
        if pattern is None or pattern.search(line):
            print(line)


def cmd_errors(a):
    d = Path(a.dir).resolve()
    lines = (d / "console.log").read_text(errors="replace").splitlines()
    ignore = [re.compile(p) for p in (a.ignore or [])] + ([] if a.all else BENIGN)
    groups, order = {}, []
    i = 0
    while i < len(lines):
        line = lines[i]
        m = re.search(r"\[(?:[\d:]+ )?(WARN|ERROR|FATAL)\]:?\s*(.*)", line)
        if m and not any(p.search(line) for p in ignore):
            trace = []
            j = i + 1
            while j < len(lines) and (lines[j].startswith("\t") or lines[j].lstrip().startswith(("at ", "Caused by", "..."))):
                trace.append(lines[j])
                j += 1
            key = re.sub(r"\d+", "N", m.group(2))[:160]
            if key not in groups:
                groups[key] = {"level": m.group(1), "count": 0, "first": line, "trace": trace[:12]}
                order.append(key)
            groups[key]["count"] += 1
            i = j
            continue
        i += 1
    if not order:
        print("no WARN/ERROR lines")
        return
    for key in order:
        g = groups[key]
        print(f"[{g['level']}] x{g['count']}: {g['first']}")
        for t in g["trace"]:
            print(f"    {t}")
    if any(groups[k]["level"] != "WARN" for k in order):
        sys.exit(3)


def cmd_set(a):
    d = Path(a.dir).resolve()
    path = d / a.file
    if not path.exists():
        die(f"{path} does not exist yet; start the server once so it writes its config")
    if path.suffix == ".properties":
        set_properties(path, a.assignments)
    else:
        set_yaml(path, a.assignments)
    print(f"updated {path}")


def percentile(sorted_vals, q):
    return sorted_vals[min(len(sorted_vals) - 1, int(q * (len(sorted_vals) - 1)))]


def cmd_ticks(a):
    d = Path(a.dir).resolve()
    out = d / OUT
    ticks_csv = out / "ticks.csv"
    if not ticks_csv.exists():
        die("no ticks.csv: run `mcs.py cmd <dir> ta dump` or stop the server first")
    rows = [line.split(",") for line in ticks_csv.read_text().splitlines()[1:] if line]
    ticks = [(int(t), int(ns) / 1e6) for t, ns in rows]
    marks = {}
    if (out / "marks.csv").exists():
        for line in (out / "marks.csv").read_text().splitlines()[1:]:
            parts = line.split(",", 2)
            if len(parts) == 3:
                marks[parts[2]] = (int(parts[0]), int(parts[1]))
    lo = marks[a.from_mark][0] if a.from_mark else None
    hi = marks[a.to_mark][0] if a.to_mark else None
    if a.from_mark and a.from_mark not in marks or a.to_mark and a.to_mark not in marks:
        die(f"unknown mark; have: {', '.join(marks)}")
    window = [ms for t, ms in ticks if (lo is None or t >= lo) and (hi is None or t < hi)]
    if not window:
        die("no ticks in window")
    s = sorted(window)
    over = sum(1 for v in window if v > 50.0)
    print(f"ticks {len(window)}  mean {statistics.fmean(window):.2f} ms  p50 {percentile(s, .5):.2f}  p95 {percentile(s, .95):.2f}  "
          f"p99 {percentile(s, .99):.2f}  max {s[-1]:.2f}  >50ms {over} ({over / len(window) * 100:.1f}%)")
    sysmon = out / "sysmon.csv"
    if sysmon.exists():
        lo_ms = marks[a.from_mark][1] if a.from_mark else 0
        hi_ms = marks[a.to_mark][1] if a.to_mark else 1 << 62
        rows = [line.split(",") for line in sysmon.read_text().splitlines()[1:] if line]
        rows = [r for r in rows if lo_ms <= int(r[0]) < hi_ms]
        if rows:
            col = lambda i: [float(r[i]) for r in rows]
            print(f"server cpu {statistics.fmean(col(2)):.0f}% of one core  machine cpu {statistics.fmean(col(4)):.0f}% of all {os.cpu_count()}  "
                  f"rss max {max(col(3)) / 2**30:.2f} GiB  cpu MHz mean {statistics.fmean(col(5)):.0f} (min {min(col(5)):.0f})  "
                  f"pkg temp max {max(col(6)):.0f} C")


def cmd_idle(a):
    load1, load5, _ = os.getloadavg()
    freqs = [int(p.read_text()) / 1000 for p in Path("/sys/devices/system/cpu").glob("cpu[0-9]*/cpufreq/scaling_cur_freq")]
    print(f"loadavg {load1:.2f} {load5:.2f}  cpu MHz mean {statistics.fmean(freqs):.0f}  cpus {os.cpu_count()}")
    if load1 > 1.0:
        print("warning: the machine is not idle; benchmark numbers will include that load", file=sys.stderr)
        sys.exit(4)


def main():
    p = argparse.ArgumentParser(prog="mcs.py", description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("new", help="create a throwaway server dir from a server jar")
    s.add_argument("dir")
    s.add_argument("--jar", required=True)
    s.add_argument("--port", type=int, default=25565)
    s.add_argument("--seed")
    s.add_argument("--flat", action="store_true")
    s.add_argument("--view-distance", type=int, default=10)
    s.add_argument("--simulation-distance", type=int, default=10)
    s.add_argument("--prop", action="append", help="extra server.properties key=value")
    s.add_argument("--no-agent", action="store_true")
    s.add_argument("--force", action="store_true")
    s.set_defaults(fn=cmd_new)

    s = sub.add_parser("start", help="start the server in the background")
    s.add_argument("dir")
    s.add_argument("--xmx", default="6G")
    s.add_argument("--jvm", action="append", help="extra JVM argument (repeatable)")
    s.add_argument("--sysprop", action="append", help="system property key=value, e.g. divinemc.rct.checkOwnership=true")
    s.add_argument("--cpus", help="taskset CPU list for the server JVM")
    s.add_argument("--no-sysmon", action="store_true")
    s.set_defaults(fn=cmd_start)

    s = sub.add_parser("wait", help="wait until the server finished starting")
    s.add_argument("dir")
    s.add_argument("--timeout", type=int, default=600)
    s.set_defaults(fn=cmd_wait)

    s = sub.add_parser("cmd", help="run a console command over RCON and print the reply")
    s.add_argument("--timeout", type=float, default=60.0)
    s.add_argument("--console", dest="console_fallback", action="store_true", help="fall back to the console FIFO")
    s.add_argument("dir")
    # everything after the dir is the command, including its own --flags (spark's --thread)
    s.add_argument("command", nargs=argparse.REMAINDER)
    s.set_defaults(fn=cmd_cmd)

    for name, fn, h in (("stop", cmd_stop, "graceful /stop, SIGKILL after the timeout"), ("kill", cmd_kill, "SIGKILL, for crash tests")):
        s = sub.add_parser(name, help=h)
        s.add_argument("dir")
        s.add_argument("--timeout", type=int, default=180)
        s.set_defaults(fn=fn)

    s = sub.add_parser("agent", help="(re)build TestAgent against this server and install it")
    s.add_argument("dir")
    s.set_defaults(fn=cmd_agent)

    s = sub.add_parser("plugin", help="build a probe plugin source dir against this server and install it")
    s.add_argument("dir")
    s.add_argument("src", help="dir with plugin.yml and src/**/*.java")
    s.add_argument("--name", help="jar name, default the source dir name")
    s.set_defaults(fn=cmd_plugin)

    s = sub.add_parser("status")
    s.add_argument("dir")
    s.set_defaults(fn=cmd_status)

    s = sub.add_parser("log", help="print console.log, optionally filtered")
    s.add_argument("dir")
    s.add_argument("--grep")
    s.add_argument("--since", help="start at the last line containing this text")
    s.set_defaults(fn=cmd_log)

    s = sub.add_parser("errors", help="group WARN/ERROR lines with their stack traces; exit 3 on ERROR")
    s.add_argument("dir")
    s.add_argument("--ignore", action="append", help="regex of lines to skip")
    s.add_argument("--all", action="store_true", help="also show the usual offline-server startup warnings")
    s.set_defaults(fn=cmd_errors)

    s = sub.add_parser("set", help="set keys in a config file (dotted YAML keys or .properties)")
    s.add_argument("dir")
    s.add_argument("file", help="path relative to the server dir, e.g. config/divinemc.yml")
    s.add_argument("assignments", nargs="+")
    s.set_defaults(fn=cmd_set)

    s = sub.add_parser("ticks", help="tick-time summary from the agent's dump, plus CPU/MHz/temperature")
    s.add_argument("dir")
    s.add_argument("--from-mark")
    s.add_argument("--to-mark")
    s.set_defaults(fn=cmd_ticks)

    s = sub.add_parser("idle", help="check the machine is idle before a benchmark")
    s.set_defaults(fn=cmd_idle)

    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
