# 15: Per-map yield caps and daily meso cap

**Area:** Economy  
**Depends on:** 07  
**Status:** done 2026-10-19 on branch `economy` (from `combat`), not yet merged

## What

Extend OffscreenRewardBudget to observed play; ledger every bot meso and item; GM read-out.

## Done when

Bots cannot out-farm a map's real yield; daily caps are enforced.

## Result

- **One gate for everything a bot takes from the world (`BotYieldCaps`):** a mob drop it loots on screen
  (`BotHoldings.creditDrop` / `canCreditDrop`, so every loot path) and its offscreen share
  (`TrainingBot.accrueAbstractLoot`, after `OffscreenRewardBudget.draw`). A refused drop stays on the floor for a
  player (and a bot does not walk to it); a refused offscreen share is forfeited, never banked. A take whose credit
  then fails (full inventory, meso overflow) is given back.
- **Per-map yield cap:** one meter per map instance (map + channel), shared by every bot on it, observed or not.
  Bots together may take at most share x the instance's expected yield over a 10-minute window, in mesos and in
  item units. The yield is `OffscreenRewardBudget`'s own model (spawn table, respawn times, live occupancy, real
  drop tables at the world's drop/meso rates, times an event spawn multiplier), exposed as
  `OffscreenRewardBudget.expectedPerWindow`. The meter is a leaky bucket: it drains at the cap's rate, so a quiet
  map frees at most one window. The offscreen budget's per-map-id bucket still applies on top. Maps with no spawn
  table (towns, scripted boss maps) have no yield to measure and are not capped by map; the daily cap holds there.
- **Daily meso cap per bot:** max(100,000, level x 10,000) x the world's meso rate, per server-local day (lv 30:
  300k, lv 120: 1.2m at 1x). Observed meso drops are all or nothing; an offscreen share is cut to what is left.
  Items are not part of it (they are bounded by the map cap and the bot's inventory).
- **Players are never metered:** a non-bot passes untouched, and so does any player drop a bot picks up (trades
  falling to the floor, casino bets, items handed over). Mesos and items bots get from players (trades, their
  shops' sales) are transfers, not counted. Bots start with 0 mesos, so with every intake capped, what they can
  pass on through trades, merchants and shops is bounded by these caps. Ticket 12 (NPC selling) must call
  `BotYieldCaps.takeMesos` for sale proceeds, as that mints mesos.
- **Tuning (JVM system properties, no config.yaml keys):** `-Dsolomapling.botYieldCaps=false` (both caps off),
  `-Dsolomapling.botMapYieldShare` (default 1.0), `-Dsolomapling.botYieldWindowMs` (default 600000, min 60000),
  `-Dsolomapling.botDailyMesoPerLevel` (default 10000), `-Dsolomapling.botDailyMesoFloor` (default 100000).
- **Persistence, migration `006-bot-yield-caps.sql`** (+ rollback; ships in `database/migrations`, recorded in
  `mapleroot_integration_migrations`): new table `botyield (BotId PK, Day DATE, Meso BIGINT UNSIGNED)`, one row per
  bot. Today's rows are read once (on the first meso take); changed counts are written by the hourly autosave
  (`CharacterAutosaverTask`) and the shutdown sweep (`Server`). Nothing is written before the read succeeds, so a
  stored count is only added to. A crash loses at most the last hour of counts. Without 006 the counts live in memory
  and restart at zero each boot (logged once). Only adds a table; applying it twice changes nothing.
- **Ledger and GM read-out:** the meters are the ledger of what bots took from the world (per map: mesos and items
  taken since boot, room left, refusals; per bot: mesos today, refusals). `!botyield` (GM 4) shows the caps, the
  10 maps bots took most from and the 10 bots that took most mesos today; `!botyield top <n>`; `!botyield <name|id>`
  for one online bot against its cap. `BotHoldings` keeps its existing per-bot value ledger unchanged.
- **Tests:** 2554 run, 0 failures, 1 skipped (as before). 16 new (`BotYieldCapsTest` 15, `OffscreenRewardBudgetTest` +1): bots on one instance share its yield and
  another channel's instance has its own; the cap refills at its rate and never banks more than a window; item
  units; the share; the daily cap by level, its floor and the day reset; offscreen shares cut by both meters; a
  failed credit gives the cap back; players and player drops never metered; switched off; a refused mob drop stays on
  the floor; the store reads once, adds and writes; without 006 nothing is written; the read-out; the migration text.
- **Open issues:**
  - The map cap sits at 1.0 of the model's expected yield. If observed bots on a busy map are seen leaving their own
    drops, the model is under-estimating that map (e.g. global drops it does not count); raise
    `botMapYieldShare` rather than drop the cap.
  - Default daily cap numbers are a first guess; check `!botyield` after a day of population before tuning.
  - Branched from `combat`, which predates the 1/3 offscreen EXP pace commit on `bots-integration`; no overlap
    (this branch does not touch `OffscreenCombat`).
