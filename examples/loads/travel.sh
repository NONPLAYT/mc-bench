#!/usr/bin/env bash
# 20 players flying apart at 1 block/tick: chunk generation (fresh world) or loading (WORLD=<pre-travelled copy>),
# chunk sending and region IO. Moving players load more chunks when ticks are slow, which slows ticks further, so the
# tick rate is pinned below what either side of a comparison can sustain (TICK_RATE, default 10).
source "$(dirname "$0")/lib.sh"
boot && {
  c tick rate "${TICK_RATE:-10}"
  c ta fake spawn 20 layout=grid spacing=2000 radius=4000 speed=1.0
  c ta chunks reset
  sleep 30
  measure travel
  echo "  $(mcs cmd "$S" ta chunks | tr '\n' ' ')"
}
finish
