# 01: Merge nutnnut nav v60-67 into GCMoveSystem (graph v68)

**Area:** Nav v2  
**Depends on:** -  
**Status:** closed 2026-10-25 (done in v1.3.0, branch nav-v2 @ 0bef943f8).

## What

Port the fork-walk fix, phantom-edge skip, rope Y windows, teleport and flash-jump edges with launch windows (gated by job), and portal route buckets onto our v61 (our own 60-61 stay). Branch `nav-v2`. IN PROGRESS: a background agent started 2026-10-19.

## Done when

GRAPH_VERSION 68; mvn test green; real-WZ v61 vs v68 comparison on the 164 test install with no map losing reachability; PROVENANCE updated.

## Result

- `GRAPH_VERSION` 68 = nutnnut's v60-62 and v64-67 merged onto our v60-61. v63 (baked portal-route buckets) is
  skipped because it needs their route cache.
- New behaviour: fork-walk chain, the phantom-jump skip, rope climb-height windows, and teleport and flash-jump
  edges. Skill edges are gated by job (stock mages and Blaze Wizards teleport; Hermit/Night Lord and Night Walker
  flash-jump) and the MOVEMENTSKILLS field limit blocks them.
- Tests: 2341 run, 0 failures (21 new; the fork and phantom tests fail without their fixes).
- Real-WZ comparison on 11 maps: no walk-reachable foothold lost; CLIMB edges drop 50-65%.
- **Risk to handle before live:** graph builds are 2-4.4x slower (Ellinia 2.2s to 9.9s) and every cache rebuilds on
  the version bump. Pre-bake the v68 caches on the test box with `NavGraphCompare` / a bake pass and ship
  `cache/bot-nav` with the deploy, or accept a warm-up period.
- Flash-jump execution for nav edges is unit-tested only; watch a thief in game. There is no MP gate yet (ticket 08).
