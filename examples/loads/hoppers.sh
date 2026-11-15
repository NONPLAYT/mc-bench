#!/usr/bin/env bash
# 32768 hoppers over barrels in eight layers: first while they move items, then once everything drained (idle cost).
source "$(dirname "$0")/lib.sh"
ITEMS='{Items:[{Slot:0b,id:"minecraft:stone",count:64},{Slot:1b,id:"minecraft:dirt",count:64},{Slot:2b,id:"minecraft:sand",count:64},{Slot:3b,id:"minecraft:gravel",count:64},{Slot:4b,id:"minecraft:cobblestone",count:64}]}'
boot && {
  quiet
  c forceload add 0 0 63 63
  sleep 5
  for y in 200 203 206 209 212 215 218 221; do
    c fill 0 $y 0 63 $y 63 minecraft:barrel
    c fill 0 $((y + 1)) 0 63 $((y + 1)) 63 "minecraft:hopper[facing=down]$ITEMS"
  done
  sleep 10
  measure hoppers_active
  sleep 60
  measure hoppers_drained
}
finish
