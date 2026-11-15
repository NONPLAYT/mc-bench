#!/usr/bin/env bash
# Entity crush: 30 still players with 130 cows packed around each, no cramming damage. Collisions, entity ticking
# and tracking; cows only (mixed hostile crowds are bimodal from run to run).
source "$(dirname "$0")/lib.sh"
boot && {
  quiet
  c gamerule max_entity_cramming 0
  c ta fake spawn 30 layout=grid spacing=160 speed=0
  sleep 20
  c ta mobs cow 130 spread=4
  sleep 60
  measure crush
}
finish
