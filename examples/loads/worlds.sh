#!/usr/bin/env bash
# The same load in three worlds (20 walking players each): what parallel world ticking spreads over threads.
source "$(dirname "$0")/lib.sh"
boot && {
  for w in world world_nether world_the_end; do
    c ta fake spawn 20 layout=grid spacing=160 speed=0.2 world=$w
  done
  sleep 90
  measure worlds
}
finish
