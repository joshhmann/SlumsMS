# 04b: Nightly deep bake

**Area:** Nav v2  
**Depends on:** 01  
**Status:** done 2026-10-19 on branch `nav-ops`, not yet merged; timers installed only on the 164 test install

## What

While the server is quiet, a dense jump/drop/mobility search over every map, written as versioned overlays the graph loads next time.

## Done when

Scheduled job, a report of edges added per map, no impact on players.

## Result

### What the bake does

`NavDeepBake` is a separate JVM that ships in `Cosmic.jar`. For every graph key it:
- rebuilds the graph at `SampleDensity.DEEP`: platform launch points every 2 px instead of one walk step, endpoint
  spacing 2 px instead of 10, rope anchors every 10 px instead of 30;
- keeps the jumps, drops, rope moves, teleports and flash jumps the live graph lacks;
- writes them as an overlay.

Which keys it covers:
- every key in `cache/bot-nav/v68`, which are the profiles bots used;
- with `--all-maps`, the base profile of every map in Map.wz (11,352 maps).

Rules for what is kept:
- An edge is new unless the live graph has the same move launching within 16 px of it. A move is the same regions,
  type and direction.
- Each move keeps at most 3 launch spots, counting the live graph's.
- The density does not change region ids. Each file carries the graph's region-layout signature, and a file whose
  signature does not match is ignored.

Missing live graphs get built and saved too, so the server does not build them at boot.

### How the server reads it

`NavDeepOverlay` merges the overlay edges into `NavObservedEdges.outgoing`, which is the planner's one edge source,
and into walk reachability:
- The merged lists are built once per graph, so the planner allocates nothing extra.
- A running server picks up a new generation within a minute, without a restart.
- The cached graph files are never modified.

### Versions, swap and rollback

- Files live in `cache/bot-nav/deep/v68/<yyyyMMdd-HHmmss>/<key>.json`. `current` is a symlink, swapped with one atomic
  rename.
- The last 3 generations are kept. To roll back, run
  `java -Dbot.nav.cacheDir=cache/bot-nav -cp target/Cosmic.jar soloMapling.ArtificialPlayer.GCMoveSystem.NavDeepBake --rollback`.
- Graph cache files (`.bin`) are now written to a temp file and renamed, both by the server and by the bake.

### Bounded and incremental

- Keys already in `current` are carried over by hard link.
- New keys go in this order: maps with stuck events in the last 7 days (04c), then maps bots have graphs for, then the
  rest.
- No new map starts after `--budget-minutes` (150). After 30 more minutes the run publishes without the maps still
  building.
- A run with nothing new publishes nothing.
- Keys that fail are marked and skipped until `--full`. `NavDeepOverlay.FORMAT` is bumped to re-bake everything.

### No impact on players

The unit runs the bake with:
- `CPUSchedulingPolicy=idle` (SCHED_IDLE: it only gets CPU nothing else wants), `Nice=19` and `IOSchedulingClass=idle`;
- `CPUQuota=200%`, `MemoryMax=2G` and `OOMScoreAdjust=800`;
- a start at 02:30 Pacific, not `Persistent`, so it is never caught up in daytime.

It shares nothing with the server process.

### Report

`reports/nav/bake-<day>.md` and `bake-latest.md` list the totals by type, the edges added and new region connections
per map, and a per-key table. The same report is in each generation directory.

### Lock contention fixed

`BotPhysicsEngine.collisionIndex` took one global `synchronizedMap` lock on every physics probe. A per-thread
last-lookup cache now answers without the lock; it returns the same index, so behaviour is unchanged.

| Measurement | Before | After |
| --- | --- | --- |
| `NavGraphBake`, 48 maps, 12 threads (dev box) | 45 s wall, 159 s CPU | 13.7 s wall, 76 s CPU |
| `findBelowIndexed` microbenchmark, 8 / 16 threads | about 10M probes/s total | 65M / 128M probes/s |

- The 48 graphs from the bake are byte-identical before and after.
- In the microbenchmark, the "before" figure was capped by the lock at any thread count. The bot-tick pattern is
  about 200 probes on one map, then another map. This helps live bot movement at scale as well.

### Tests

- `NavDeepBakeTest`: the diff rules, the per-move cap, overlay load/merge/signature, generation swap, rollback and
  prune, and a real-WZ Henesys deep bake. The real-WZ test is skipped without `wz/`.
- Full suite: 2,520 run, 0 failures, 2 skipped.

### Test install on 164

On `/opt/mapleroot-dev/nav-v2-test/navops`, a 20-minute-budget run of the unit:
- baked 1,146 of 15,359 keys (the 4,354 cached keys, plus base keys for all maps), with 0 failures;
- added 14,283 edges, 4,479 of which join regions the live graph did not join directly;
- used 42 CPU-minutes over 22.6 minutes, at 2 cores and SCHED_IDLE, with a 1.3 GB peak.

At that rate the whole set takes about 4 nights at 150 minutes, after which only new keys are baked. A second run
carried the 1,146 over and baked 61 more. Rollback and the forward swap were checked.

The test units are `slums-navops-test-bake.{service,timer}` (budget 20 min).

## Install on live (only with the owner's go, with the release that carries this jar)

The bake must run the server's own jar, so both use the same `GRAPH_VERSION`. The server picks overlays up by itself.

1. Deploy a jar built from `nav-ops` (or anything merged from it) as `target/Cosmic.jar` in
   `/opt/mapleroot/MapleRoot Full Repack/Server`.
2. Copy the units into place:
   `cp deploy/nav/slumsms-nav-bake.{service,timer} /etc/systemd/system/`
3. Reload systemd and check the units:
   `systemctl daemon-reload && systemd-analyze verify /etc/systemd/system/slumsms-nav-bake.*`
4. Optionally, make one bounded run by hand and read `reports/nav/bake-latest.md`:
   `systemctl start slumsms-nav-bake.service`
5. Enable the timer: `systemctl enable --now slumsms-nav-bake.timer`
6. Check it: `systemctl list-timers slumsms-nav-bake.timer`. The next run should be 02:30 Pacific.

**Disk:** about 300 MB of `.bin` files for all maps' base graphs, plus a few MB per generation.

**Turn it off:** `systemctl disable --now slumsms-nav-bake.timer`

**Drop the overlays entirely:** `rm "cache/bot-nav/deep/v68/current"`. Bots then plan on the live graphs only, within
a minute.

## Open issues

- The added edges come from the same builder and checks as live ones, but no bot has walked them in game yet. Watch the
  stuck report (04c) on the maps with the most new connections after enabling it. Nav learning penalises any that fail.
- `NavEdgeLearning.byGraph` is also a global `synchronizedMap`, hit on every planner edge relaxation. Same pattern,
  not changed here (ticket 04's area); worth the same fix before bots go live at scale.
- `NavObservedEdges` (player-learned jumps) still writes `cache/bot-nav/observed` regardless of `bot.nav.cacheDir`.
