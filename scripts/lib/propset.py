#!/usr/bin/env python3
import sys
from pathlib import Path

def main(argv):
    path = Path(argv[1])
    props = {}
    order = []
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                order.append(("raw", line))
                continue
            k, _, v = line.partition("=")
            props[k.strip()] = v
            order.append(("kv", k.strip()))
    for assignment in argv[2:]:
        k, _, v = assignment.partition("=")
        if k not in props:
            order.append(("kv", k))
        props[k] = v
    out = []
    for kind, payload in order:
        out.append(payload if kind == "raw" else f"{payload}={props[payload]}")
    path.write_text("\n".join(out) + "\n", encoding="utf-8")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
