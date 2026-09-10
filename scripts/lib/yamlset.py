#!/usr/bin/env python3
import sys
from pathlib import Path

def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))

def find_key_line(lines, path):
    depth = 0
    lo, hi = 0, len(lines)
    idx = None
    for seg in path:
        idx = None
        expect = None
        i = lo
        while i < hi:
            line = lines[i]
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or stripped.startswith("-"):
                i += 1
                continue
            ind = indent_of(line)
            if expect is None:
                expect = ind
            if ind < expect:
                break
            if ind == expect and stripped.split(":", 1)[0].strip().strip("'\"") == seg:
                idx = i
                break
            i += 1
        if idx is None:
            return None
        lo = idx + 1
        child_hi = lo
        base = indent_of(lines[idx])
        while child_hi < len(lines):
            line = lines[child_hi]
            if line.strip() and not line.strip().startswith("#") and indent_of(line) <= base:
                break
            child_hi += 1
        hi = child_hi
        depth += 1
    return idx

def main(argv):
    if len(argv) < 3:
        print("usage: yamlset.py <file> <dotted.key>=<value> [...]", file=sys.stderr)
        return 2
    path = Path(argv[1])
    if not path.exists():
        print(f"yamlset: no such file: {path}", file=sys.stderr)
        return 1
    lines = path.read_text(encoding="utf-8").splitlines()

    failures = []
    for assignment in argv[2:]:
        key, _, value = assignment.partition("=")
        segments = key.strip().split(".")
        idx = find_key_line(lines, segments)
        if idx is None:
            failures.append(key.strip())
            continue
        ind = " " * indent_of(lines[idx])
        name = lines[idx].strip().split(":", 1)[0].strip()
        lines[idx] = f"{ind}{name}: {value}"

    if failures:
        print(f"yamlset: {path}: key path(s) not found: {', '.join(failures)}", file=sys.stderr)
        return 1

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
