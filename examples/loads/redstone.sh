#!/usr/bin/env bash
# 64 observer clocks, each driving 120 redstone dust, in force-loaded chunks with nobody around: neighbour updates.
source "$(dirname "$0")/lib.sh"
boot && {
  quiet
  c forceload add 0 0 127 127
  sleep 10
  Y=100
  c fill 0 $((Y - 1)) 0 127 $((Y - 1)) 127 minecraft:stone
  for z in $(seq 0 2 126); do
    c fill 2 $Y $z 121 $Y $z minecraft:redstone_wire
    c setblock 0 $Y $z 'minecraft:observer[facing=east]'
    c setblock 1 $Y $z 'minecraft:observer[facing=west]'
  done
  c fill 0 $Y 0 0 $Y 126 'minecraft:observer[facing=east,powered=true]' replace minecraft:observer
  sleep 30
  measure redstone
}
finish
