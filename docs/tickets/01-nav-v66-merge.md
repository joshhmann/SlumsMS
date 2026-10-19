# 01: Merge nutnnut nav v60-67 into GCMoveSystem (graph v68)

**Area:** Nav v2  
**Depends on:** -  
**Status:** in progress

## What

Port the fork-walk fix, phantom-edge skip, rope Y windows, teleport and flash-jump edges with launch windows (gated by job), and portal route buckets onto our v61 (our own 60-61 stay). Branch `nav-v2`. IN PROGRESS: a background agent started 2026-10-19.

## Done when

GRAPH_VERSION 68; mvn test green; real-WZ v61 vs v68 comparison on the 164 test install with no map losing reachability; PROVENANCE updated.
