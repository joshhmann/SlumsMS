# 04c: Daily stuck-hotspot report

**Area:** Nav v2  
**Depends on:** 01  
**Status:** shipped in v1.3.0 (branch `nav-ops`, via `nav-learning`); `slumsms-nav-stuck-report.timer` runs on live

## What

Where bots got stuck: map, position, failed edge, count. It feeds the nav lab; humans and agents fix the cases physics cannot solve.

## Done when

A daily file or GM command with the top hotspots.

## Result

- **Recording** (`NavStuckLog`, in the server): the movement code already noticed stuck bots; each of these now also
  counts one event per UTC day, map, kind, 50px spot and the edge the bot was on:
  - `UNSTUCK`: the unstuck hop fired;
  - `GAVE_UP`: an edge given up because its launch window was never reached;
  - `ROPE_HUNG`: ClimbRecovery kicked the bot off a hung rope;
  - `AIR_FROZEN` and `FELL_OFF_MAP`: put back by a watchdog;
  - `NO_REGION` and `NO_PATH`: the bot stood off the graph, or had no route. These two are counted at most once per
    bot per 30 s.
- **Cost and storage:** one map update per event. Written to `cache/bot-nav/stuck/<day>.json` every 5 minutes and at
  shutdown, atomically. A restart on the same day keeps counting; a day keeps at most 20,000 spots.
- **Report** (`NavStuckReport`, separate JVM, reads files only). It writes `reports/nav/stuck-<day>.md` and
  `reports/nav/stuck-latest.md`, with:
  - the top spots (count, map, spot, kind, edge, graph key, last time);
  - the maps with the most events;
  - route failures, listed separately;
  - the edges nav learning currently penalises (from `cache/bot-nav/learned`).

  A one-line summary goes to stdout, which is the journal under systemd.
- **GM:** `!gcmove stuck` shows today's 8 worst spots. `!gcmove learned` also prints today's count and the deep-bake
  generation.
- The nightly bake (04b) bakes the maps with the most stuck events in the last 7 days first.
- Tests: `NavStuckLogTest`, which covers spot counting, restart and day turnover, and the report.

## Install on live (only with the owner's go, with the release that carries this jar)

1. Deploy a jar built from `nav-ops` (or anything merged from it) as `target/Cosmic.jar`.
2. Copy the units into place:
   `cp deploy/nav/slumsms-nav-stuck-report.{service,timer} /etc/systemd/system/`
3. Reload systemd and enable the timer:
   `systemctl daemon-reload && systemctl enable --now slumsms-nav-stuck-report.timer`
4. Check the timer is listed: `systemctl list-timers slumsms-nav-stuck-report.timer`.
5. Try it once by hand:
   `systemctl start slumsms-nav-stuck-report.service; journalctl -u slumsms-nav-stuck-report -n 3`
6. Read the report: `reports/nav/stuck-latest.md` under the server directory.

The timer runs at 00:20 UTC and reports the UTC day that just ended. The report is empty until bots run with this jar
(live has `solomapling.enabled=false`).

**Remove it:**
`systemctl disable --now slumsms-nav-stuck-report.timer && rm /etc/systemd/system/slumsms-nav-stuck-report.*`

## Test install (164, `/opt/mapleroot-dev/nav-v2-test/navops`)

The units are installed as `slums-navops-test-stuck-report.{service,timer}`. They ran on a hand-made
`stuck/2026-10-18.json`, because no bot server runs on that install. Real counts appear once a bot server runs this
jar.
