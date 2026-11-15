# Behaviour scenarios

Each check needs a negative control: run it once where the bug or the old behaviour should show, and confirm the
check fails there. A check that passes both ways proves nothing.

`c(){ $MCS cmd "$S" "$@"; }` keeps the recipes short (`S` is the server dir).

## Clean boot

```bash
$MCS start $S && $MCS wait $S && sleep 20 && $MCS errors $S; $MCS stop $S
```

`errors` hides the usual offline-mode warnings (`--all` shows them). Any ERROR or a new WARN is a finding.

## Region storage survives a crash

Verified on DivineMC Buffered (`B_LINEAR`, 2026-11-12): with `max-sync-delay-ms: 10000` blocks older than ~10 s
survived `kill -9` while the region kept being written; with `600000` everything since boot was lost.

```bash
$MCS set $S divinemc.yml region-settings.type=B_LINEAR      # or LINEAR / MCA; then a fresh world:
rm -rf $S/world && $MCS start $S && $MCS wait $S
c forceload add 96 96 160 160 && sleep 3
c setblock 100 200 100 minecraft:diamond_block && c save-all
# keep writing the same region every second, so a "quiet for N seconds" flush never fires
for i in $(seq 1 15); do c setblock $((100+i)) 200 110 minecraft:gold_block; c save-all; sleep 1; done
$MCS kill $S && $MCS start $S && $MCS wait $S && sleep 3
c execute if block 100 200 100 minecraft:diamond_block      # "Test passed" = survived
c execute if block 115 200 110 minecraft:gold_block         # the last seconds may be lost by design
$MCS errors $S; $MCS stop $S
```

`save-all` only hands chunks to region storage; whether they reach the disk is the storage's job, which is what
this tests. For Linear, the window is `linear.io-flush-delay-ms`.

## Config validation

Set a bad value (`0`, a typo in an enum), start, and check the server falls back with a warning instead of
crashing later: `$MCS errors $S` shows the warning, the feature still works. Region and secure-seed settings in
DivineMC are read only at boot, so test them across a restart, not with `/divinemc reload`.

## World format guard

With an MCA world on disk, `set region-settings.type=B_LINEAR` and start: DivineMC must refuse to boot with
`IllegalStateException: Region folder ... holds .mca files` (`$MCS wait` fails and prints it).

## Secure seed stability

Enable `misc.secure-seed.enable`, start, `c seed`, stop, start, `c seed`: both `Feature seed` lines must match.
Toggle off, restart, toggle on, restart: still the same seed (it is kept in level.dat).

## Threading assertions (DivineMC)

`$MCS start $S --sysprop divinemc.rct.checkOwnership=true` with regionized chunk ticking on, then a clustered load
(`reference/load.md`), and `$MCS errors $S` after a few minutes. Races only show under real load and time.

## DAB / activation behaviour

Compare goal-selector work with a profile instead of guessing: same seed and mobs, `ta fake spawn 1 layout=center`
far from a mob farm, `spark profiler start --thread '*'`, then `spark_report.py report --busy --filter GoalSelector`.
Run once with the option off and once on.
