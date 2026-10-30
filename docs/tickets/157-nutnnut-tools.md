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
  `calibration.json`, `!killcalib`, switch `-Dslums.killcalib`, on TEST, off on live) and item 4 (live bot inspect:
  `BotInspectApi` on 127.0.0.1, `X-Slums-Inspect` secret header, GET `/bots/{cid}`, `/maps/{id}`, `/positions`,
  switch `-Dslums.inspect` plus `-Dslums.inspect.port` and `-Dslums.inspect.secretFile`), plus `nav/pits.json` for the
  Website lane's dead-end check. Contract: docs/design/inspect-api.md. Not yet run on TEST; the site proxy and
  dashboard tables are the Website lane's.

## Not adopted

Their server as written, disconnect-all, wipe, economy reset, their market beliefs (NN-013), cross-account
ownership transfer, their population system (ours is ahead, NN-021). The "wisteria" client edit and
Seikoxi/BotlingMaple have no clear licence: don't use without checking.
