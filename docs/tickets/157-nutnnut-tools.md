# 157: Adopt nutnnut's web admin views and debug tools (safely) for QA and learning

**Status:** open, 1.6.2. **Owner:** Server Dev (server hooks), Website (dashboard pages). **Source:** owner,
2026-10-30: "adopt what we can from webadmin and any debugging tools to further enhance qa testing, nav learning,
behavior learning etc". Upstream: https://github.com/nutnnut/Cosmic README_SERVER_BOTS.md (AGPL-3.0, same as ours;
credit `nutnnut/Cosmic <sha>` in each port commit and in PROVENANCE.md). Full matrix: `157-nutnnut-tools/adoption-matrix.md`.
Upstream has nothing new since our 10-24/25 reviews (ticket 87); newest dev commit 77e0c5bc9 (2026-08-28).

## Rules (why their admin was refused before, NN-020)

Their admin listens on all interfaces with no login, and some GET links change state. We port the ideas, not the
server:
- no new port open to the network; static views through our existing exporters behind TEST basic auth or the site's
  GM 3+ staff login; live views from one localhost-only endpoint (secret header, off by default, read-only, cached);
- controls are POST-only with a CSRF token, TEST-only, and each runs the matching GM command as the staff member so
  `gm_command_audit` records it; no bulk destructive actions (no disconnect-all, no wipe, no economy reset); no
  controls on live.

## Build order

1. **Path trace on stuck** (S-M): keep the last ~120 nav ticks for watched bots and attach them to every stuck
   report, so nav learning sees the cause, not just the stop. Capped per hour.
2. **Kill calibration** (M): real vs modelled kill rate by job and level band, report-only, to check the offscreen
   economy (EXP, mesos, drops, yield caps, Most hunted).
3. **Dashboard additions, read-only** (S-M): world graph with a dead-end ("trap map") check that also becomes an
   auto-QA check; a route preview on the Map tab over our existing export; which switches are on.
4. **Live bot inspect** (M): `!botwhy` / LifeInspection as JSON over the localhost endpoint (goals, plan step,
   Bartle blend, memory, learning, LOD, tick cost) plus a world map of bot positions; feeds behaviour learning and
   ticket 156's state-of-mind work.
5. **`!navprobe` and `!env lod pin`** (S, TEST-only, audited): "can bot X reach (x,y)" with the edge path, a nightly
   probe of every portal and NPC spot, and forcing chosen maps to full simulation for auto-QA.
6. Small debug read-outs (S each): perf CSV, grind-map candidates, sell/scroll decisions, gear-pick reasons in
   `!botwhy`, nav region in `!pos`, one status line per bot, a live decision stream in the Messenger console, an
   offline pathfinder shortcut check.

Later candidates from the matrix (separate decisions): bot questing slice (M-L), job-advance errands (M), 4th-job
mastery books (M), companion commands and sharing for owned alts (NN-012, M), Maker plan / batch crafting (needs
ticket 112).

## Progress

- 2026-10-30, branch `162-inspect` (from release-1.6.2): item 2 (kill calibration: `KillCalibration`,
  `calibration.json`, `!killcalib`, switch `-Dslums.killcalib`, on TEST; 1.6.2.conf sets it on for live too) and item 4 (live bot inspect:
  `BotInspectApi` on 127.0.0.1, `X-Slums-Inspect` secret header, GET `/bots/{cid}`, `/maps/{id}`, `/positions`,
  switch `-Dslums.inspect` plus `-Dslums.inspect.port` and `-Dslums.inspect.secretFile`), plus `nav/pits.json` for the
  Website lane's dead-end check. Contract: docs/design/inspect-api.md. Not yet run on TEST; the site proxy and
  dashboard tables are the Website lane's.
- 2026-10-31, branch `162-navpits` (bug b109, the dead-end check's 12 maps with unhandled pits). Graph v70 builds
  the missing edges on five of them (wall-bound notches 105070200/105070400 r11 and 105090000 r21 get a jump out,
  2 px near-vertical footholds on 610020006 walk off their low end, 410000350's bottom floor keeps its under-floor
  touch portal up00). `NavPits` gains a second rule, traps: a region the exit area can reach but that leads back to no
  portal to another map (same rule and `region_at` placement as `check_map`; skipped on swim maps and maps without such
  a portal). The planner, walk reachability and idle safety never route into one, `nav/pits.json` lists them, and a
  bot standing in one is put on the nearest platform with a way out after 3 s (20 s while a player is on the map;
  stuck-log kind `PIT`). Traps on TEST's graphs: 104000000, 220000100, 220060201, 220060301, 222020100 (plus
  200000301 r30, a sealed 5 px slot, and the below-view pits that were already handled). The map export marks swim
  maps `"swim": true` and `slums-dashboard-extras` skips the ground-graph check there (230030001, 230040400); the
  updated script must be copied to `/usr/local/bin/slums-dashboard-extras` on 164 to take effect. Tests:
  NavDeadEndMapsTest (WZ), NavPitsTest, tools/dashboard/extras/test_world.py.

## Not adopted

Their server as written, disconnect-all, wipe, economy reset, their market beliefs (NN-013), cross-account
ownership transfer, their population system (ours is ahead, NN-021). The "wisteria" client edit and
Seikoxi/BotlingMaple have no clear licence: don't use without checking.

## Auto-QA (ticket 164 b224, 2026-10-30)

Branch `162-qaharden`: **t-nv-2** is item 3's promised "no trap maps" check (`NavTrapQa` over GCWorldGraph from Lith
Harbor; FAILs on a one-way-in trap not in the committed baseline `nav-trap-maps.txt`: 485 of 1,471 reachable maps on
10-30, mostly Arcane River and Grandis with one-way rides, plus Rien - leads for the nav lane, not fixed here).
**t-nv-3** checks that the tools answer on the server: the inspect API refuses no secret / a wrong one (403) and its
view of a fresh bot is the bot, kill calibration reports, a watched bot keeps a nav trace ring.
