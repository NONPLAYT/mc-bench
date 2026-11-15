#!/usr/bin/env bash
# Natural spawning around 60 spread players plus 1500 zombies; half the main thread sits in NaturalSpawner.
# A PreCreatureSpawnEvent listener (TestAgent counts spawns only after `ta spawns reset`) changes this path.
source "$(dirname "$0")/lib.sh"
boot && {
  c ta fake spawn 60 layout=grid spacing=160 radius=48 speed=0.3
  sleep 40
  c ta mobs zombie 25
  sleep 20
  measure spawn
}
finish
