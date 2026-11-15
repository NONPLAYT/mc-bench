#!/usr/bin/env bash
# Prints the CCD layout and, on a multi-CCD chip, a ready-to-paste SERVER_CPUS
# line. CCD membership comes from the L3 id (each CCD has its own L3); the
# cache-heavy CCD is the one with the larger L3, which is where the server
# belongs: letting the scheduler move it between CCDs adds variance. Run this on
# the machine that will run the benchmark.
set -euo pipefail

declare -A cpus_of size_of
for c in /sys/devices/system/cpu/cpu[0-9]*; do
  n="${c##*/cpu}"
  [[ -r "$c/cache/index3/id" ]] || continue
  id="$(cat "$c/cache/index3/id")"
  cpus_of[$id]+="$n "
  size_of[$id]="$(cat "$c/cache/index3/size")"
done

if (( ${#cpus_of[@]} == 0 )); then
  echo "cannot read L3 topology from sysfs; fall back to: lscpu -e" >&2
  exit 1
fi

tolist() { python3 -c '
import sys
ns = sorted(int(x) for x in sys.argv[1].split())
out, start = [], ns[0]
for a, b in zip(ns, ns[1:] + [None]):
    if b != a + 1:
        out.append(str(start) if start == a else f"{start}-{a}")
        start = b
print(",".join(out))' "$1"; }

echo "L3 domains (CCDs):"
biggest="" biggest_kb=0
for id in $(printf '%s\n' "${!cpus_of[@]}" | sort -n); do
  list="$(tolist "${cpus_of[$id]}")"
  printf "  L3 #%-3s %-9s cpus %s\n" "$id" "${size_of[$id]}" "$list"
  kb="${size_of[$id]%[KMG]}"
  case "${size_of[$id]}" in *M) kb=$(( kb * 1024 ));; *G) kb=$(( kb * 1024 * 1024 ));; esac
  if (( kb > biggest_kb )); then biggest_kb=$kb biggest=$id; fi
done

if (( ${#cpus_of[@]} == 1 )); then
  echo
  echo "Single L3 domain: nothing to pin, leave SERVER_CPUS empty."
  exit 0
fi

echo
echo "Server on the cache-heavy CCD (L3 #$biggest, ${size_of[$biggest]}):"
echo "  SERVER_CPUS=$(tolist "${cpus_of[$biggest]}")"
echo
echo "Add that line to bench.local.conf."
