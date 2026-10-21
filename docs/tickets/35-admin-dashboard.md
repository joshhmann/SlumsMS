# 35: Admin dashboard: nav learning, bots, economy

**Area:** Ops
**Depends on:** 04 / 04b / 04c (nav data), 15 (economy caps)
**Status:** in progress: code shipped in v1.3.0 (`dashboard`) and v1.4.0 (`dashboard-next`); it runs on the test
dashboard (164:8091) only, not on live. See "State" below.

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

## State (2026-10-20)

Built (branches `dashboard`, `dashboard-next`):

- **Server exporter** `soloMapling.dashboard.DashboardExporter` (`-Dsolomapling.dashboard=true`, `.dir`,
  `.periodSeconds`): `live.json` every minute, `series/<day>.json`, `daily/<day>.json`, `days.json`. Each series point
  also carries `ch`: cumulative bot chat replies per tier (llm, scripted, emote, ignored, flagged; tickets 36/37).
- **Map export** `soloMapling.dashboard.DashboardMapExport` (`deploy/dashboard/slumsms-dashboard-maps.{service,timer}`):
  map geometry, nav graphs with night-bake, player-learned and learned-cost marks, stuck heat, learning history.
- **Web app** `tools/dashboard/web` (static, no build): Map, Learning, Bots, Economy, Health (with the chat tier
  table and a replies-per-hour-by-tier chart), and **GM audit**. Under the header, a **status strip** with each
  monitored server's checks (ticket 42), red when a check fails, amber when the monitor has gone quiet.
- **Ops files job** `tools/dashboard/extras/slums-dashboard-extras` + `deploy/dashboard/slums-dashboard-extras@.{service,timer}`
  (every minute, root, idle priority). Writes `data/ops/status.json` (copies of
  `/var/lib/slumsms-monitor/<target>/status.json`, so nginx never reads /var/lib) and every 5 minutes
  `data/ops/gm.json` (`slumsms-gm-audit --json` for `gm_command_audit` and `gm_level_changes`; SELECTs through
  `docker exec` with the container's own password: no credentials in files, the browser never touches the DB).
  Config `/etc/slums-dashboard/<instance>.conf` (examples `deploy/dashboard/extras-*.conf`).

Test install on 164 (nginx `slums-dashboard-test`, http://192.168.0.164:8091/): web app, exporter
(`slums-dashboard-test` server), map export data, and `slums-dashboard-extras@dashboard-test.timer` with
`MONITOR_TARGETS=persistence-test` (the dashboard test server has no monitor target of its own) and the GM audit on
`mapleroot-integration-db` / `slums_release_test`. Gaps on the test install:

- `slums_release_test` does not have migration 009 yet, so the GM page shows "apply migration 009" (applying it was
  blocked by a permission check for the agent; the owner can run it: it only adds two tables).
- The `slums-dashboard-test` jar predates the chat stats, so the chat panels say so until it runs a newer jar
  (which also needs migrations 003-009 on its schema).
- Bots are off on that server (`-Dsolomapling.enabled=false`), so bot and economy charts are flat.

### Install (live, when the owner says go)

1. nginx: `deploy/dashboard/slumsms-dashboard.nginx.conf` as a site (port, `root` = a copy of `tools/dashboard/web`,
   `/data/` alias = the snapshot folder, e.g. `/srv/slums-dashboard/data`), a new htpasswd (not the patch server's).
2. Live server: add `-Dsolomapling.dashboard=true -Dsolomapling.dashboard.dir=/srv/slums-dashboard/data` to its java
   line (takes effect at the next restart; the jar must contain the exporter).
3. Map export: `deploy/dashboard/slumsms-dashboard-maps.{service,timer}`.
4. Ops files: `install -m755 tools/dashboard/extras/slums-dashboard-extras /usr/local/bin/`, the two
   `slums-dashboard-extras@` units into `/etc/systemd/system/`, `deploy/dashboard/extras-live.conf` as
   `/etc/slums-dashboard/live.conf`, then `systemctl enable --now slums-dashboard-extras@live.timer`. Needs
   `slumsms-monitor@live` installed (ticket 42) for the strip, and migration 009 plus the ticket 43 jar on live for
   GM commands to appear.

### Still open

- Live install (above). Economy against real ticket 15 caps once the ledger is merged (the panel shows the derived
  estimate until then). Shop and trade flows. A monitor target for the dashboard test server itself, if wanted.
