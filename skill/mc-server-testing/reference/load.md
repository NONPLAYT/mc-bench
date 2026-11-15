# Load generation

## Why in-process fake players

A bot client (mc-bench used SoulFire until November 2026) costs several times the CPU and memory of the server it
connects to, so on one machine it measures contention with its own client, not the server. Fake players are
`ServerPlayer`s on a client-less `Connection`, ticked by the TestAgent: the server does all its per-player work
and nothing else runs.

| Exercised | Not exercised |
| --- | --- |
| player join (`placeNewPlayer`, join events), keepalive, chunk batch acks, teleport acks | login/configuration phase, encryption |
| chunk loading and generation following players, ticket management | Netty encoding, compression, socket writes (packets are dropped after they are built) |
| chunk packet building incl. async chunk sending serialization, light data | client movement packets, `handleMovePlayer`, movement checks, anticheat |
| entity tracking (scan, pairing, `sendChanges`), natural spawning around players | client-driven physics: fakes fly at a fixed height |
| mob AI targeting players (unless `targetable=false`), activation range | chat, commands, inventory clicks |

The network path (Netty, compression, packet decode) is not covered; it needs real clients from another machine.

## Presets

Ready-made scripts for these and more are in `examples/loads/`.

| Scenario | Commands | Stresses |
| --- | --- | --- |
| spread players | `ta fake spawn 60 layout=grid spacing=160 radius=48 speed=0.3` then `ta mobs zombie 25` | tracking, spawning, chunk ticking over a large loaded area |
| clustered (RCT) | `ta fake spawn 64 layout=cluster clusters=8 separation=768 radius=24` then `ta mobs zombie 60` | regionized chunk ticking; clusters must stay far apart (mc-bench uses 768) or they merge into one region |
| one crowd | `ta fake spawn 40 layout=center radius=16` then `ta mobs cow 50` | one region: entity tick, collisions, tracker pairs |
| travel / worldgen | `ta fake spawn 16 layout=center radius=4000 speed=1.5` | chunk generation and IO, region storage writes |

Calibrate on the machine before trusting a run: target a p50 in the 20-35 ms band (loaded but not collapsing).
Measured 2026-11-12 on a Ryzen 5 5600, DivineMC defaults, fresh world: 10 fakes p50 7 ms; 60 fakes (grid 160) + 1500
zombies p50 84 ms, half of the main thread in `NaturalSpawner.spawnForChunk`. A flat world without mobs measures
almost nothing (flat chunks compress to nothing, nothing ticks).

## Gotchas

- Mobs only spawn in loaded chunks; run `ta mobs` after the fakes have been out for a few seconds.
- `ta fake clear` disconnects through the normal quit path; plugins see join/quit events for fakes.
- Fakes are survival players with infinite Resistance V and Saturation: mobs target and attack them, nothing kills
  them. `targetable=false` makes them invulnerable instead, which also hides them from mob targeting
  (`canBeSeenAsEnemy`).
- Before 2026-11-16 a fake with `speed=0` never left 0,0,0 (its join teleport ack reset it): loads with still fakes
  measured before then put every player and every `ta mobs` crowd in one spot.
- `/forceload` refuses more than 256 chunks per call; without loaded chunks entities do not tick at all.
- 26.x game rules are snake_case (`spawn_mobs`, `random_tick_speed`, `advance_weather`).
