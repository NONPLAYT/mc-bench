#!/usr/bin/env bash
# 64 players in 8 clusters far apart, 20 zombies each: what regionized chunk ticking spreads over threads.
# Clusters closer than about twice the view distance merge into one region.
source "$(dirname "$0")/lib.sh"
boot && {
  quiet
  c ta fake spawn 64 layout=cluster clusters=8 separation=768 radius=24 speed=0.2
  sleep 20
  c ta mobs zombie 20
  sleep 70
  measure clusters
}
finish
