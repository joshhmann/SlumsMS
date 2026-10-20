# 35: Admin dashboard: nav learning, bots, economy

**Area:** Ops
**Depends on:** 04 / 04b / 04c (nav data), 15 (economy caps)
**Status:** in progress

## What

A password-protected browser dashboard for the owner, served from 164 the same way the patch server is: nginx plus
basic auth, behind the owner's Caddy if they add a hostname.

- **Map viewer:**
  - Any map's footholds, ropes and ladders and portals, drawn from the WZ data.
  - The bot nav graph on top: walk, jump, drop, rope, teleport and flash-jump edges.
  - Edges learned from players (`NavObservedEdges`) and from the night bake (`NavDeepBake`) are highlighted.
  - Each edge is shaded by learned cost (`NavEdgeLearning` failures and successes).
  - Stuck spots (`NavStuckLog`) are shown as a heatmap.
  - Pan and zoom, a profile picker (speed/jump and job gates), and a search by map id or name.
- **Learning over time:** new edges per night and per source, failures, the maps improving fastest and the worst
  maps.
- **Bots:**
  - Online count over time against the population curve.
  - Breakdowns by level, class, map and activity (grind, travel, town, break).
- **Economy:**
  - Bot meso and item intake per map and per day against the ticket 15 caps.
  - The spread of bot wealth.
  - Later: shop and trade flows.
- **Server health:** DB pool use (`BotDbGate`), tick times (`BotPerformanceMonitor`) and memory.

## How

- **The server only writes small read-only snapshots** (JSON) on a timer, e.g. every minute plus a daily rollup.
  - The dashboard never connects to the game or the game DB for writing.
  - It can't slow down or break the server.
- **Map geometry** is exported once per map (or on demand) from the WZ files and the graph cache.
- **A static web app** (no build step needed on 164) reads the snapshots.
- **Access:** behind basic auth, separate from the patch-server password; never public without auth.
- **Config:** no new config.yaml keys unless they ship with the jar that knows them.

## Done when

- The owner opens the dashboard and can browse any map's nav graph with learned and stuck overlays.
- The bot population, activity and economy charts update within a few minutes.
- Turning it off removes nothing from the game.
