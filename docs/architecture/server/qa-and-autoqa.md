# QA suite and auto-QA

## Purpose

Two test-only tools live in the server process. `!qa` (GM level 3) stages a release-notes scenario inside the running
world, writes a per-run log, and undoes its staging when the run stops. Auto-QA drives `!qa` from headless "pretend
players" through a catalogue of 197 checks, grades what the game said and what the synthetic clients received, and
writes one JSON report plus periodic world-health sweeps. Neither exists on live: both are behind `-D` switches that
live's unit does not pass, and every hook they use is inert when nothing is listening.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `!qa` (no args) | opens the NPC window listing every scenario, grouped | src/main/java/soloMapling/menus/QaMenu.java:78 (NPC id 9010000 at :29), called from src/main/java/client/command/commands/gm3/QaCommand.java:28 |
| `!qa list` / `run` / `do` / `where` / `stop` | the five command cases | src/main/java/client/command/commands/gm3/QaCommand.java:38-54 |
| command registration | `if (soloMapling.qa.QaSuite.isEnabled())` → `qa` (rank 3), `botcombat`, `godlydrop` | src/main/java/client/command/CommandsExecutor.java:376-380 |
| `!autoqa run|status|stop|report|health|list` | registered only when `AutoQa.isEnabled()` | src/main/java/client/command/CommandsExecutor.java:385-387; cases src/main/java/client/command/commands/gm3/AutoQaCommand.java:31-95 |
| `!botwatch`, `!socialtrace`, `!decideshadow` | same gates, adjacent | src/main/java/client/command/CommandsExecutor.java:389-397 |
| health sweeps (no player input) | `AutoQa.boot()` from environment start | src/main/java/soloMapling/Environment/EnvironmentManager.java:151 → src/main/java/soloMapling/autoqa/AutoQa.java:90-105 |
| nightly `!autoqa run all` | `scheduleAtFixedRate(1 day)` at `AUTOQA_NIGHTLY_UTC_HOUR` | src/main/java/soloMapling/autoqa/AutoQaService.java:375-392 |
| operator queue (`tools/ops/autoqa-run`, cron/CI) | `run-queue.txt` polled every 30 s | src/main/java/soloMapling/autoqa/AutoQaService.java:403-421; writer tools/ops/autoqa-run:47-57 |
| bot chat listener (`LlmJudge`) | registered when a run opens the world | src/main/java/soloMapling/autoqa/GameAutoQaWorld.java:51-60 |
| QA event feed HTTP `/qa/events` | `-Dslums.qa.feed=true` only | src/main/java/soloMapling/inspect/QaEventFeed.java:60-61, 166 |

Gate: `QaSuite.isEnabled()` = `Boolean.getBoolean("slums.qa") || AttackCapture.isEnabled()`
(src/main/java/soloMapling/qa/QaSuite.java:33, 77-78; `AttackCapture.PROPERTY = "slums.attackcapture"` at
src/main/java/soloMapling/server/AttackCapture.java:48).

## How it works

### A. `!qa` — staging, log, undo

1. `QaCommand.execute` takes `list|run|do|where|stop`; no sub-command opens `QaMenu.open(gm)`
   (src/main/java/client/command/commands/gm3/QaCommand.java:28-54), so a menu click replays a typed command.
2. `QaSuite.start(Character gm, String id, String[] args)` (src/main/java/soloMapling/qa/QaSuite.java:139) resolves the
   scenario from `QaScenarios.all()` (src/main/java/soloMapling/qa/QaScenarios.java:76 = `core()` at :125 plus 44
   `all.addAll(<Family>Scenarios.all())` families), refuses an unknown id (:142), a second run for the same GM (:146)
   and a claim collision (:149-155), and takes `Math.max(1, scenario.minutes(args))` (:159).
3. It opens the run's file through `QaRun.open(List<String> header)` — header lines "scenario / gm / where / args /
   topics … stops after N min" (src/main/java/soloMapling/qa/QaSuite.java:164-172 → src/main/java/soloMapling/qa/QaRun.java:148).
   The path is `logs/qa/<scenarioId>-<yyyyMMdd'T'HHmmss'Z'>[-<gmId>].log` (src/main/java/soloMapling/qa/QaRun.java:29, 75-99;
   the `-gmId` suffix only when two lanes stage the same id at once, src/main/java/soloMapling/qa/QaSuite.java:161-163).
4. `QaLog.attach(run)` puts the run on the log's listener list (src/main/java/soloMapling/qa/QaSuite.java:176 →
   src/main/java/soloMapling/qa/QaLog.java:249). From here every product hook whose topic the run subscribes to and whose
   map/owner matches reaches its file: `QaLog.map/owner/toPlayer/any/chars` (:170-221) → `dispatch` (:224) →
   `QaRun.accepts(topic, mapId, ownerId)` (src/main/java/soloMapling/qa/QaRun.java:124-131) → `QaRun.event` (:167).
5. `scenario.stage(QaContext)` runs the staging (src/main/java/soloMapling/qa/QaScenario.java:64); every line it returns
   is written as `STAGE told GM: …` (src/main/java/soloMapling/qa/QaSuite.java:191-193). A `QaRefusal` stops the run with
   `refused: …` (:180-184), any other `RuntimeException` with `Staging <id> failed (…); what it had set up is undone`
   (:185-189).
6. A timeout is scheduled at `minutes * 60_000` and `tick(ctx)` is repeated when `tickSeconds() > 0`
   (src/main/java/soloMapling/qa/QaSuite.java:194-206; defaults `minutes` 15, `tickSeconds` 0 at
   src/main/java/soloMapling/qa/QaScenario.java:50-57).
7. The GM is told `QA <id> running: <title>` plus the staging lines plus `Logging to <path>. Stops in N min, or !qa stop.`
   (src/main/java/soloMapling/qa/QaSuite.java:207-212) — the auto-QA step that typed `!qa run` parses exactly that line
   (src/main/java/soloMapling/autoqa/Steps.java:28, 89-124).
8. `QaRun.write` fans every line to `QaLog.toListeners(gmId, scenarioId, kind, message)` *and* the file, flushing each
   line (`// auto-QA reads the run as it goes`, src/main/java/soloMapling/qa/QaRun.java:178-198).
9. `!qa stop` / timeout → `QaSuite.stop(gmId, "…")` (:215) → `QaRun.stop(reason, now)` (src/main/java/soloMapling/qa/QaRun.java:202)
   runs the undo stack last-first (`undone: <label>` / `cleanup failed: …`), writes `SUMMARY <topic n, …> events` and closes.
   `!qa do <step>` and `!qa where` are `QaScenario.action` (:67-69) and the live run's state.

### B. auto-QA — command, cron or queue → staging → assertions → report

1. Requests arrive three ways, all funnelling into `AutoQaService.start(Request, by, nightly, progress)`
   (src/main/java/soloMapling/autoqa/AutoQaService.java:134): the command (`AutoQaCommand` :31-47), the nightly timer
   (`start("all", "the schedule", true, null)`, :375-392), and `AutoQaRunQueue.takeNext` from `run-queue.txt`
   (:403-421; queue files src/main/java/soloMapling/autoqa/AutoQaRunQueue.java:20-21, 41).
   `Request(selector, fast, failed, release)` is parsed at :93-133; a second run is refused
   ("Auto-QA is already running (<trigger>): …", :136).
2. `select(catalogue, selector)` maps `all` → whole catalogue, `smoke` → `SMOKE = bg-1, vg-1, ch-1` (:73), else a comma
   list matched by full id, short id or family prefix (:45-71).
3. `runNow` sets the QA clock (`QaTiming.beginRun(mode.timeScale())`, :228; `fast` = 0.4, src/main/java/soloMapling/qa/QaTiming.java:25,71),
   compresses old day files (:230), opens the world (:234 → `GameAutoQaWorld.open()` :51) and calls
   `AutoQaRunner.run(checks, nightly, onResult)` (src/main/java/soloMapling/autoqa/AutoQaService.java:236-258 →
   src/main/java/soloMapling/autoqa/AutoQaRunner.java:155).
4. The runner settles first: it waits until the server has been up `-Dslums.autoqa.settleMs` (default 180 000 ms) so a
   boot-fill population does not answer a check's `!qa run` late (src/main/java/soloMapling/autoqa/AutoQaRunner.java:41-48, 295-313).
   If `world.unavailableReason()` says `!qa` or auto-QA is off (:77-85 in GameAutoQaWorld), every AUTO/HALF check is SKIPped
   and only MANUAL ones are recorded.
5. Lanes: `parallel = AutoQaScheduler.parallel()` = `-Dslums.autoqa.parallel` clamped 1..10, default 1
   (src/main/java/soloMapling/autoqa/AutoQaScheduler.java:40-42; `MAX_LANES = 10` at
   src/main/java/soloMapling/autoqa/SyntheticPlayers.java:61). At 1 the runner loops serially; above 1 `runLanes` starts,
   on any free lane, the first pending check whose declared `holds` are disjoint from every running check's, and a
   `WORLD` check only when nothing else runs (src/main/java/soloMapling/autoqa/AutoQaRunner.java:211-292;
   `keys`/`disjoint`/`canStart`/`pick` at src/main/java/soloMapling/autoqa/AutoQaScheduler.java:53-115).
6. `runOne(Check, nightly, lane)` (src/main/java/soloMapling/autoqa/AutoQaRunner.java:315) logs the check's synthetic
   players in (`CheckRun.actor(slot)`, src/main/java/soloMapling/autoqa/CheckRun.java:55) — slot 1 is a GM level 3 that
   types `!qa`, slot 2 a plain player (src/main/java/soloMapling/autoqa/SyntheticPlayers.java:44-70) — then runs the
   check's `Step`s: each is a real command or chat line sent as that character (`Steps.say` :54-70, `Steps.run` :89-124
   waits 20 s for "QA <id> running" and learns the first staged bot as `{bot}`), with `SPAM_GAP_MS = 400` between lines
   (src/main/java/soloMapling/autoqa/Steps.java:17) and a `PRECONDITION` yellow line turned into a SKIP, not a FAIL (:23-25,118).
7. Login is a headless **real** client: `SyntheticPlayers.login(slot)` builds `client.SyntheticClient`, primes the login
   session and dispatches `RecvOpcode.PLAYER_LOGGEDIN` through the real handler
   (src/main/java/soloMapling/autoqa/SyntheticPlayers.java:178-203). `SyntheticClient` is a `Client` with no socket whose
   doc says it is deliberately *not* a `BotClient`, so every bot system treats it as a person
   (src/main/java/client/SyntheticClient.java:17-28); it keeps the last 4000 decoded lines of the 11 send opcodes a
   tester reads (:28-42) as `AutoQaLine`s (src/main/java/soloMapling/autoqa/AutoQaLine.java:10-24).
8. Assertions run after the steps: `Check.Expect.eval(run)` for each expectation, patterns matched against
   `"KIND message"` (`Expects.has/atLeast/…`, src/main/java/soloMapling/autoqa/Expects.java:9-24, 42-70). One failed
   expectation → FAIL with the descriptions; no judged expectation at all → SKIP; otherwise PASS, and a HALF check's
   reason keeps `logic passes; still look: <why>` (src/main/java/soloMapling/autoqa/AutoQaRunner.java:355-400).
9. Cleanup is unconditional (`finally`, src/main/java/soloMapling/autoqa/AutoQaRunner.java:404-409): `cleanup(run)` sends
   `!unhide` per hidden slot and `!qa stop` per slot that staged a scenario (:420-431). `world.logoutAll()` follows at the
   end of the run (:184-190).
10. Each result is handed to the report the moment it is known — `onResult` writes a partial report and logs
    `Auto-QA PASS t-af-1 …` (src/main/java/soloMapling/autoqa/AutoQaService.java:240-252) — tagged with the QA clock it
    ran on (`fast lane: QA staging ran at 0.4x`, src/main/java/soloMapling/autoqa/AutoQaRunner.java:132-150).
11. At the end: `ChatQuality.runNow()` (:257), then for a release run `AutoQaRelease.judge(catalogue, results,
    allowList(), ClientScriptVerdicts.load(...))` (:263-266), then the final write and `world.close()`/`QaTiming.endRun()`
    (:267-287). `AutoQaReport.write` writes `report-<UTC day>.json` **and** `latest.json`
    (src/main/java/soloMapling/autoqa/AutoQaReport.java:368).
12. Health sweeps run beside all of this, on their own scheduled thread: `HealthSweeper.start()` every
    `AUTOQA_SWEEP_MINUTES` (:46-58), `sweepNow()` → `HealthSweep.run(...)` over the nine (ten with `decide`) invariant
    checks, written to `health-latest.json` + `health/<day>.jsonl` (:114-123; checks
    src/main/java/soloMapling/autoqa/HealthSweep.java:122-135, `SENT_CHECK` :162).

### The check catalogue

`AutoQaCatalog` is one flat `build()` (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:99) memoised into a
`volatile List<Check>` (:76-88, `find` :90). It holds **197 checks** — 167 `Check.auto`, 19 `Check.half`, 11
`Check.manual` — under **94 two-letter prefixes** (first `Check.auto("t-af-1"` :103, last `Check.half("t-ng-10"` :1596).
The class doc says they are "every check on the 1.5.0 release notes page … in page order" (:44-52) and 1.6.x checks were
appended after it. Each check is `(id, title, kind, why, players, nightly, minutes, holds, steps, expects)`
(src/main/java/soloMapling/autoqa/Check.java:23-24) with builder defaults players=1, nightly=true, minutes=10,
holds=`{world}` (:116-119). Measured composition: 93 `.laneOnly()` + 61 keyed `.holds("map:…"/"pq:…"/"ch:…")` +
30 WORLD-only + 2 WORLD-default + 11 MANUAL (WORLD) = 197; 8 `.notNightly()` (t-gw-3, t-mr-1, t-rm-2, t-rm-4, t-cv-6,
t-ev-3, t-nx-1, t-nx-2), 3 `.players(2)` (t-mr-1, t-nx-1, t-nx-2), 186 with an explicit `.minutes(n)`, 12 graded by the
LLM judge (`judged(...)`), 11 manual (t-ga-4, t-rg-1, t-rg-2, t-mv-1, t-jq-1, t-cl-1..3, t-cc-1, t-mr-2, t-la-1 — the
same ids as the release allow-list, src/main/resources/soloMapling/autoqa-release-skips.tsv).

| Prefix | Area (from the check titles) | n | Catalog lines |
|---|---|---|---|
| `t-af-n` | Bot friendship / rivalry acts (friend acts, grudge, @botdrama off) | 3 | 103-123 |
| `t-rv-n` | Rival roasts, KS war, callout megaphone, apology follow, grounded trash talk | 5 | 134 |
| `t-bb-n` | Add a bot as buddy, buddy chat, bot-to-bot crew | 3 | 175 |
| `t-bg-n` | Ten world bot guilds exist | 1 | 202 |
| `t-gu-n` | Bot-guild join / recruit / jab / boss run / kick / leave | 7 | 205-282 |
| `t-gw-n` | Guild war, declare on player guild, poaching, alliance+betrayal | 4 | 289-310 |
| `t-pb-n` | Party invites follow feelings | 1 | 316 |
| `t-cu-n` | Married bot couple, same-gender bot best friends | 2 | 331-339 |
| `t-we-n` | Amoria wedding (same gender) | 1 | 345 |
| `t-mr-n` | Engagement / proposal (a woman may propose) | 2 | 352 |
| `t-rm-n` | Flirt-date, propose and marry a bot, breakups+gossip, wedding drama | 4 | 376-415 |
| `t-cv-n` | Chat conversations: plain-word actions, haggling, tone, voices, ratings, line bank, party chat, both model endpoints | 12 | 437-545 |
| `t-cq-n` | Daily chat quality (relevance, stiffness, say-do) | 1 | 553 |
| `t-pl-n` | Planned replies: town truth, filled slots, one bot chat an hour | 1 | 559 |
| `t-ga-n` | Gachapon machines (play, six machines, remote ticket, bot rolls) | 4 | 565-582 |
| `t-wc-n` | Rich bot spends down (wealth ceiling) | 1 | 586 |
| `t-bs-n` | Buy from a bot shop; SaleRules never lists boss-only drops | 2 | 594-600 |
| `t-td-n` | Sell/buy with a bot by asking; haggling cannot be tricked | 3 | 603-623 |
| `t-gf-n` | Friend bot gives a gift / pots on request | 2 | 629-638 |
| `t-pr-n` | @price shows real prices | 1 | 645 |
| `t-sk-n` | NPC shop (Hanako) and new NPC prices | 2 | 651-654 |
| `t-rg-n` | Friendship ring, couple ring (same gender too) | 2 | 657-660 |
| `t-sc-n` | Bot scrolling styles, starter Relaxer/Fishing Chair | 3 | 667 |
| `t-cb-n` | Combat: skills+buffs, facing/spacing, potions+restock+deaths, neutral bots keep distance | 4 | 677-698 |
| `t-sp-n` | Bots spread out | 1 | 710 |
| `t-sl-n` | Town salon cosmetic names and filtering | 1 | 716 |
| `t-ps-n` | Town bots keep clear of NPCs | 1 | 720 |
| `t-pt-n` | Bots walk with looting pets | 1 | 726 |
| `t-bk-n` | Attack pacing: swing timing, held Hurricane/Rapid, no melee that closes a viewer's range | 2 | 732-753 |
| `t-at-n` | Bots attack at a player's pace | 1 | 740 |
| `t-id-n` | Idle bots step out of mob packs | 1 | 762 |
| `t-mv-n` | Attacking stops you (GM slide measurement) | 1 | 766 |
| `t-jq-n` | No bounces in jump quests, double-tap jump elsewhere | 1 | 768 |
| `t-cl-n` | Client look: rope boxes, Cash Shop wheel, pet equips | 3 | 772-774 |
| `t-cc-n` | Character creation never "disabled" | 1 | 775 |
| `t-vg-n` | Old client is told to update (version gate) | 1 | 777 |
| `t-ch-n` | Five channels, channel 5 quiet | 1 | 780 |
| `t-la-n` | Tauri launcher checklist | 1 | 783 |
| `t-vb-n` | v83 basics: salon, delete with PIC, KPQ clear, mount chain, shop+EXP card, job advance, storage/Duey/trade, Maker | 8 | 787-833 |
| `t-fh-n` | Fishing timer+cancel, chair and item grant | 2 | 842-848 |
| `t-mb-n` | Bot answers a megaphone buy request | 1 | 855 |
| `t-bl-n` | Bot life: shadow policy, one restock trip, supply need, learned profile, goal->plan steps | 5 | 865-942 |
| `t-lk-n` | Player likeness: AFK campers, walks while watched, 2009 typing | 1 | 902 |
| `t-sg-n` | Social gate: @quiet bots, rival beef only after a KS/roast | 2 | 913-923 |
| `t-bc-n` | Crowded-map bot says cc and shows on a quieter channel | 1 | 951 |
| `t-me-n` | Memory: KPQ clear remembered, scam trade becomes a grudge | 2 | 961-967 |
| `t-gs-n` | A KS becomes true gossip from a crew mate | 1 | 977 |
| `t-in-n` | Initiative: greet on login, ask about your goal a day later, PQ invite by level | 3 | 991-1010 |
| `t-lr-n` | Learning drift: Socializer KS'd 30x drifts toward Killer | 1 | 1021 |
| `t-dr-n` | Drama boom: boom, rage quit, come back | 1 | 1043 |
| `t-cr-n` | Crush: signs, jealousy, becomes a couple | 1 | 1052 |
| `t-ev-n` | World events: Giant Cake, invasion, full invasion | 3 | 1064-1108 |
| `t-mk-n` | Forced market export; bot Maker batch | 2 | 1123 |
| `t-sf-n` | Forced site export (feed, leaderboards, world files) | 1 | 1134 |
| `t-bd-n` | Site board: post gets a bot offer and an alert | 1 | 1142 |
| `t-bw-n` | Bots do what they decided (bot watch) | 1 | 1151 |
| `t-st-n` | Bot social life leaves a trace | 1 | 1158 |
| `t-ex-n` | Zakum / Chaos Zakum expeditions with bots | 3 | 1165-1187 |
| `t-nx-n` | NX code buy+redeem, ignoring buyer still gets the code | 2 | 1200-1213 |
| `t-oa-n` | Owned-alt companion: party, cap 5, saved on dismiss | 1 | 1231 |
| `t-tw-n` | Town population targets, ch1 busiest, no map over cap | 1 | 1236 |
| `t-hp-n` | MaxHP/MaxMP past 30,000 reach the client, capped at 99,999 | 1 | 1242 |
| `t-au-n` | Site invite, sign-up, sign-in, GM-approved password reset | 1 | 1248 |
| `t-ds-n` | Decide shadow rows, text=false keeps only the length | 1 | 1252 |
| `t-np-n` | Custom NPC caps (Coco, Dalair, Maple TV) | 1 | 1257 |
| `t-ck-n` | Daily check-in pays once per 24 h | 1 | 1262 |
| `t-ss-n` | Slums Stamps: claim, earn, buy | 1 | 1272 |
| `t-xc-n` | NX exchange, LEAF ticker, market volatility, guild-war market news, scroll trader lists | 5 | 1277-1302 |
| `t-fy-n` | Family: bot junior + entitlements, perks, Reunion/Summon, Add Junior | 4 | 1307-1324 |
| `t-ti-n` | Maple Island opt-in question and landing | 1 | 1328 |
| `t-rb-n` | Bot rebirth, key item, player reborn at Wyatt | 3 | 1333-1345 |
| `t-ac-n` | Achievements: kill/fishing goals, PQ instance clears | 2 | 1350-1355 |
| `t-pg-n` | Lazy Penguins one per town, named | 1 | 1359 |
| `t-nc-n` | Newer-area Monster Book cards drop at 2% | 1 | 1364 |
| `t-ld-n` | 120+ ladder: gates, mob HP/EXP rows, drop tables | 1 | 1369 |
| `t-iv-n` | @invite rules and site invites sharing the weekly cap | 2 | 1373-1377 |
| `t-sn-n` | Slums-chan NPC greets | 1 | 1382 |
| `t-bt-n` | Bot talk, friend crews pick a town, say-do carried out | 3 | 1387-1397 |
| `t-br-n` | @bug: ref reply, report line, context | 1 | 1401 |
| `t-nv-n` | Nav probe: portals/NPCs reachable, no one-way traps, debug tools answer | 3 | 1406 |
| `t-mx-n` | Kerning PQ mechanics with bots (1-6) | 6 | 1413-1519 |
| `t-bq-n` | Watched bot takes, hunts and turns in a mob quest | 1 | 1432 |
| `t-tm-n` | Bot is offered the next Temple mainline quest | 1 | 1436 |
| `t-gb-n` | Gear/build: Pareto gear, dexless caps, gear-first grinding, junk sort, EV scrolling, AP variants | 6 | 1440-1462 |
| `t-su-n` | Cleric support heal, fame requests, bot Magic Guard | 3 | 1466-1474 |
| `t-jb-n` | Job errand at the instructor, skill books | 2 | 1480-1485 |
| `t-nn-n` | Navigation: swim wall jump, blocked-position watchdog | 2 | 1489-1493 |
| `t-gr-n` | Chat grounding: snapshot is truth (static and live) | 2 | 1528-1534 |
| `t-mp-n` | Map routing: no door into a map that fails to load | 1 | 1552 |
| `t-ms-n` | Bot modes: training/social/town populated | 1 | 1571 |
| `t-sm-n` | Bot minds: moods in range, no NaN | 1 | 1577 |
| `t-sa-n` | Social acts: three kinds land, budget and turn cap bite | 1 | 1583 |
| `t-pi-n` | Henesys pilot counters report work | 1 | 1589 |
| `t-ng-n` | 1.6.4 gear half checks: nimble weapon core, equipment bridge | 2 | 1593-1596 |

## Data

| Path (repo-relative) | Written by | Shape |
|---|---|---|
| `Server/logs/qa/<scenarioId>-<yyyyMMddTHHmmssZ>[-<gmId>].log` | `QaRun.open/write/stop` (src/main/java/soloMapling/qa/QaRun.java:75-99, 148-249) | `#` header, `STAGE`, `TOPIC` event lines `<time> <KIND> <message>`, `TICK`, `CLEANUP undone: …`, `SUMMARY <topic n, …> events` |
| `data/autoqa/report-<yyyy-MM-dd>.json`, `data/autoqa/latest.json` | `AutoQaReport.write` (src/main/java/soloMapling/autoqa/AutoQaReport.java:368) | build, day, updated, `summary{PASS,FAIL,SKIP,NOT_RUN,total}`, `health{status,summary,time}`, `runs[{trigger,started,finished,checks,PASS,FAIL,SKIP,parallel,…}]`, `checks{"t-af-1":{status,title,kind,reason,evidence≤40,started,finished,seconds,build}}` (:16-32) |
| `data/autoqa/health-latest.json`, `data/autoqa/health/<day>.jsonl` | `HealthSweeper.write` (:114-123) | status, summary, botsSeen, botsSampled, mapsSeen, per-check map |
| `data/autoqa/run-queue.txt`, `run-queue.work`, `run-queue.seq`, `run-request.txt` | `tools/ops/autoqa-run` (:47-57) then `AutoQaRunQueue` (src/main/java/soloMapling/autoqa/AutoQaRunQueue.java:20-67) | one `<n>TAB<UTC stamp>TAB<request>` line per request |
| `data/autoqa/social-trace/<day>.jsonl`, `social-trace-latest.json` | `SocialTrace` (src/main/java/soloMapling/autoqa/SocialTrace.java:35-41) | one bot↔bot episode per line, pipeline stage verdicts |
| `data/autoqa/client-verdicts/` | read by `ClientScriptVerdicts.load` (:43), applied per release report (:150) | scripted-client verdicts per check id (ticket 166) |
| `data/autoqa/<other>` | `ChatQuality` (chat-quality-*.json), `BotWatch` (:552), `DecideShadow`, `SayDo` (:705) | day files, gzipped after `-Dslums.history.gzipDays` (default 30) and deleted after `-Dslums.history.autoqaKeepDays` (src/main/java/soloMapling/autoqa/AutoQa.java:43-60) |
| `data/convo/routing/<day>.jsonl` | `RouteLog.log` (src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/RouteLog.java:60-85) | routing decisions; also mirrored to the `CONVO` topic as `ROUTE CANDIDATES {json}` |

`QaLog` carries 45 `Topic` constants, each a bit in one `long` mask (src/main/java/soloMapling/qa/QaLog.java:28-73);
hooks are guarded by `QaLog.on(topic)` (:160-162) so they cost one bit test when no run listens. 392 `QaLog.*` call
sites exist outside `soloMapling/qa` (i.e. in product code), across 119 files.

## Switches

| Switch | Default | Effect | Live (docs/release/1.6.4.conf) |
|---|---|---|---|
| `-Dslums.qa` | unset | registers `!qa`/`!botcombat`/`!godlydrop` and turns every `QaLog` hook live (src/main/java/soloMapling/qa/QaSuite.java:33,77; src/main/java/client/command/CommandsExecutor.java:376-380) | **absent** — not in `SERVER_SET` (:201-213) nor in the `-D` list (:217-240); live has no `!qa` |
| `-Dslums.attackcapture` | unset | also enables `!qa` (`QaSuite.isEnabled` ORs it in, :77-78); TEST is recorded as passing it (docs/ops/qa-suite.md:9, src/test/java/soloMapling/qa/QaRegistryTest.java:13-24) | absent |
| `-Dslums.autoqa` | unset | registers `!autoqa`, starts health sweeps and the nightly run (src/main/java/soloMapling/autoqa/AutoQa.java:26,33,90) | **absent** — grep for `slums.qa`/`slums.autoqa` over docs/release/1.6.4.conf returns nothing |
| `AUTOQA_ENABLED` | `false` | config twin of `-Dslums.autoqa` (src/main/java/config/ServerConfig.java:206) | not in `SERVER_SET` (:201-213) → false |
| `AUTOQA_SWEEP_MINUTES` | `5` | health sweep period, floored at 1 (src/main/java/soloMapling/autoqa/HealthSweeper.java:42) | not set |
| `AUTOQA_SWEEP_MAX_BOTS` | `3000` | bots sampled per sweep (ServerConfig.java:208) | not set |
| `AUTOQA_STUCK_MINUTES` | `3` | same spot while moving = stuck (:209) | not set |
| `AUTOQA_CLUMP_RADIUS` | `30` px | clump radius (:210) | not set |
| `AUTOQA_CLUMP_MIN_BOTS` | `5` | floored at 2 by the sweeper (:211) | not set |
| `AUTOQA_NIGHTLY_UTC_HOUR` | `10` | nightly `run all`; `-1` = none (src/main/java/soloMapling/autoqa/AutoQaService.java:375-380) | not set |
| `AUTOQA_JUDGE_MAX_CALLS` | `40` | LLM judge calls per run (src/main/java/soloMapling/autoqa/LlmJudge.java:22-28,101) | not set |
| `-Dslums.qa.timeScale` | unset (1.0) | scales staged windows, clamped 0.1..1.0 (src/main/java/soloMapling/qa/QaTiming.java:22-43); `--fast` sets 0.4 for one run (:25,71-75) | n/a |
| `-Dslums.autoqa.dir` | `data/autoqa` | report/queue/health root (src/main/java/soloMapling/autoqa/AutoQa.java:67-69) | n/a |
| `-Dslums.autoqa.parallel` | `1` | lanes, clamped 1..10 (src/main/java/soloMapling/autoqa/AutoQaScheduler.java:22,40-42); docs/ops/qa-suite.md:964 records that a release conf passes `=10` | unset |
| `-Dslums.autoqa.longestFirst` | `false` | pack lanes longest-budget-first (AutoQaScheduler.java:31-37) | unset |
| `-Dslums.autoqa.settleMs` | `180000` | settle wait after a server start (src/main/java/soloMapling/autoqa/AutoQaRunner.java:47) | unset |
| `-Dslums.autoqa.kpqStallMin` | `8` (`0` = off) | a progress-guarded wait FAILs after this much silence (src/main/java/soloMapling/autoqa/Steps.java:158-211) | unset |
| `-Dslums.history.autoqaKeepDays` | unset (keep) | deletes auto-QA day files after N days (src/main/java/soloMapling/autoqa/AutoQa.java:43-47) | n/a |
| `-Dslums.qa.feed` | unset | starts the `/qa/events` HTTP feed (src/main/java/soloMapling/inspect/QaEventFeed.java:60-61,109) | absent |

Live verdict: **live runs neither suite.** `SERVER_SET` in docs/release/1.6.4.conf:201-213 carries only `config.yaml`
keys (bot caps, family/tutorial/rebirth/damage-rank/companion switches, `STARFORCE_BOOST_PERCENTAGE2=0.2`), and the `-D`
flags live's unit carries are listed in the comment block at :217-240 (`-Dslums.alts.lanes`, `-Dslums.exchange.desk`,
`-Dslums.exchange.traders.nx…`, `-Dslums.auth.port/secretFile/revoked`, `-Dslums.market.export`, `-Dslums.site.export`,
`-Dslums.board`, `-Dbot.nav.*`) — neither `-Dslums.qa` nor `-Dslums.autoqa` appears anywhere in the file. AGENTS.md:122-124
states the same rule. TEST: `start-test.sh` lives on another host and is not readable here, so **what TEST actually passes
is unknown**; the repo records it as passing `-Dslums.attackcapture=true` (docs/ops/qa-suite.md:9,
src/test/java/soloMapling/qa/QaRegistryTest.java:13-24) and setting the lane count (docs/ops/qa-suite.md:964).

## Invariants

| # | Invariant | Evidence |
|---|---|---|
| 1 | `!qa` exists only with `-Dslums.qa` or attack capture; on live it is never registered and no hook fires | src/main/java/soloMapling/qa/QaSuite.java:77-78; src/main/java/client/command/CommandsExecutor.java:376-380; src/test/java/client/command/QaCommandGatingTest.java |
| 2 | A check touches nothing outside its declared `holds`; `world` means it runs alone | src/main/java/soloMapling/autoqa/Check.java:27,135-160; src/main/java/soloMapling/autoqa/AutoQaScheduler.java:70-84 |
| 3 | Non-MANUAL checks must have expectations and a minutes budget; every MANUAL check states why a person is needed | src/test/java/soloMapling/autoqa/AutoQaCatalogTest.java:37-52 |
| 4 | Cleanup always runs, even on abort/exception, and staging undos run last-first | src/main/java/soloMapling/autoqa/AutoQaRunner.java:404-431; src/main/java/soloMapling/qa/QaRun.java:202-249 |
| 5 | One `!autoqa` run at a time; one `!qa` run per GM; conflicting claims refused | src/main/java/soloMapling/autoqa/AutoQaService.java:134-136; src/main/java/soloMapling/qa/QaSuite.java:145-155 |
| 6 | A wait that runs out FAILs; "may legitimately be empty" must be said with `quietFor`/`optionalWait` | src/main/java/soloMapling/autoqa/Steps.java:273-330; docs/ops/qa-suite.md:898-942 (b222) |
| 7 | A release run is fresh, whole-catalogue, normal-clock, and fails on any FAIL or any SKIP not on the allow-list | src/main/java/soloMapling/autoqa/AutoQaRelease.java:120-181; src/main/java/soloMapling/autoqa/AutoQaService.java:263-266 |
| 8 | A synthetic player is a person to every bot system, never a bot | src/main/java/client/SyntheticClient.java:17-24 |
| 9 | MANUAL checks never execute headless: they are recorded as SKIP with their reason | src/main/java/soloMapling/autoqa/AutoQaRunner.java:315-320; src/main/java/soloMapling/autoqa/AutoQaReport.java:38-63 |
| 10 | Queue requests are appended by the operator only; the server renames the file into one it alone writes | src/main/java/soloMapling/autoqa/AutoQaRunQueue.java:12-21 |

## Origin

`custom`. Neither package exists upstream: `/root/workspace/cosmicms/src/main/java` has no `soloMapling/` directory at all
(only `client`, `config`, `constants`, `database`, `model`, `net`, `provider`, `scripting`, `server`, `service`), so
`src/main/java/soloMapling/qa` and `src/main/java/soloMapling/autoqa` cannot be base files. They were written on top of the
repack baseline: oldest commits `ade430132 2026-10-21 Add the !qa test-scenario suite with per-scenario debug logs` and
`e1f005b5e 2026-10-22 Auto-QA phase 1: periodic health sweeps`, against baseline `03d13cb21 2026-10-18 Establish
deployable MapleRoot server baseline`. `tools/cosmicdiff/classifications.json` has no entry matching `qa` (82 keys, none
for these paths). The product-side hooks (`QaLog.map/owner/toPlayer/any/chars` calls in `client/`,
`net/server/channel/handlers/`, `soloMapling/ArtificialPlayer/`) are edited base files, i.e. the subsystem is custom code
threaded through modified base classes.

## Tests and QA

Unit tests: `src/test/java/soloMapling/autoqa` (63 files, 502 `@Test`) and `src/test/java/soloMapling/qa` (59 files, 274
`@Test`), plus `src/test/java/client/SyntheticClientTest.java`, `src/test/java/client/SyntheticClientStatTest.java` and
`src/test/java/client/command/QaCommandGatingTest.java`.

| Test | Pins |
|---|---|
| src/test/java/soloMapling/qa/QaSuiteTest.java, QaRegistryTest.java | registration/gating, id shape, live-vs-TEST command lines (QaRegistryTest.java:13-24, `offOnLiveOnOnTheTestServer`), minutes bounds |
| src/test/java/soloMapling/qa/QaLogTest.java, QaRunCleanupTest.java, QaTenLaneLogTest.java | topic mask, map/owner scoping, LIFO cleanup, two lanes writing two files |
| src/test/java/soloMapling/qa/QaTimingTest.java | 0.4 fast scale, clamps, evidence label |
| src/test/java/soloMapling/qa/QaScenarioStagingTest.java | a handful of scenarios' staging and undo (fm-1, gd-1, …) |
| src/test/java/soloMapling/autoqa/AutoQaCatalogTest.java | the whole 1.5.0 page order id-for-id (:19,33), expectations/budget (:37-52), manual reasons, nightly hour (:200) |
| AutoQaCatalogPatternTest.java, SaidScopeCatalogTest.java, JudgedPatternGroupTest.java, JudgedToleranceTest.java | every expectation regex against real log-line shapes (src/test/java/soloMapling/autoqa/AutoQaCatalogPatternTest.java:11-15, the 2026-10-22 regex review) |
| AutoQaSchedulerTest.java | disjoint keys, WORLD-waits, `checksThatReadMapWideLinesOrSetAGlobalPropertyDeclareIt` (:251-287), longest-first packing |
| AutoQaTenLanesTest.java, AutoQaLanesTest.java, AutoQaSeedingTest.java | 10 lanes, 20 distinct players, slot table, lane isolation |
| AutoQaRunnerTest.java, AutoQaWaitTimeoutTest.java, AutoQaReportTest.java, AutoQaRerunTest.java, AutoQaReleaseTest.java, ClientScriptVerdictsTest.java | state machine, timeouts, report merge, carry-forward, release verdict, allow-list = the 11 client-only ids |
| AutoQaRunQueueTest.java, AutoQaRunRequestTest.java | `run-queue.txt` append/rename, `--fast/--failed/release` parsing |
| HealthSweepTest.java, SweepMathTest.java, FootholdCheckTest.java, OverlapCheckTest.java | the pure sweep invariants and their arithmetic |
| LlmJudgeTest.java, LlmJudgeBudgetTest.java | prompt/parse and the cloud-budget skip, not live model quality |
| SyntheticLoginAccountViewTest.java, SyntheticPlayerActivityTest.java, QaAltsTest.java | synthetic login view, "logged in as a person", companion-alt reuse |
| StepsAccess.java, RefusalNeverSaysYesTest.java, SayAndHearTest.java, GroundingAsSentTest.java | step helpers, refusal wording, chat capture |

In-game QA material: 133 distinct `!qa` scenario ids in 95 `QaScenario` implementations across 45 `*Scenarios` files
(6 alias ids: bk-2, cc-1, fm-2, gd-2, gh-2, sc-1; `QaScenarios.PLANNED` at src/main/java/soloMapling/qa/QaScenarios.java:49
lists three not yet built), and the 197 auto-QA check ids above.

Not covered: no test starts a real server — every test uses `FakeQaWorld`/`AutoQaRunnerTest`'s scripted world or a
`SyntheticClient` fake, so `GameQaWorld`/`GameAutoQaWorld` thread-safety and the probes' server-side facts are unverified
by unit tests. Scenario coverage is partial: 95 implementations vs 59 test files in `soloMapling/qa`, so most scenario
classes have no staging test. `QaLog`'s 392 product call sites are not asserted by any unit test; their proof is manual
`!qa` runs. The LLM judge is never called for real in tests. Nothing verifies that the release allow-list still matches
the catalogue's MANUAL ids at run time (only `AutoQaReleaseTest` against its own `CLIENT_ONLY` list). World-health
thresholds have no test against a live population.

## Known issues

| Card | Sev | One line |
|---|---|---|
| b276 | S4 (docs row /root/work/STATUS-1.6.4.md:78) | A bot's map-scoped `PLAN`/`ROUTE` lines never reach the GM's QA feed: they are emitted with `QaLog.map(...)` (src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1179-1183, src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java:304-305, RouteLog.java:77-78), so `dispatch` passes no character ids (src/main/java/soloMapling/qa/QaLog.java:224-236) and `QaEventFeed.record` drops a map line nobody watched is standing on (src/main/java/soloMapling/inspect/QaEventFeed.java:231-247). Triaged QA-tool (staging), not product. |
| b284 | S3 (STATUS:80) | Auto-QA at 10 lanes gives false failures: 8 of the 22:00 run's FAILs passed when rerun one at a time. Cause per triage: the scheduler's key declarations miss shared state — `keys(check)` is exactly `check.holds()` (src/main/java/soloMapling/autoqa/AutoQaScheduler.java:53-55). |
| b286 | S3 (STATUS:82) | Ticket 179: the QA **worker** loses batched ready events, so consecutive waits time out though the signal was recorded. That is `tools/qa-runner/qarunner.py` (`SilviaWorker.wait_signal`), not `AutoQaRunner`; the release-run rule is "a FAIL that is also b286, rerun serially before acting". |
| b287 | S4 (STATUS:83) | t-xc-5: a PANIC-style scroll trader holding a Chaos/White Scroll never lists it; serial rerun at 07:00:22 UTC also failed — a real product-side lead in the exchange lane, not a harness bug. |
| b280 | S3 (STATUS:63) | Product: pet auto-potion does not save a player on a newer map; chased with a TEST-only QA probe (the `PETPOT` topic, src/main/java/soloMapling/qa/QaLog.java:73-74, src/main/java/net/server/channel/handlers/PetAutoPotHandler.java:63). |
| NEW-FINDING: src/main/java/soloMapling/qa/QaLog.java:76 | — | The comment `// a long: 34 topics no longer fit an int` is stale: the enum holds 45 constants (`OWNED_ALT` at :28 … `PETPOT` at :73); the mask is still a single `long`, so this is documentation drift only. |
| NEW-FINDING: src/test/java/soloMapling/autoqa/AutoQaSchedulerTest.java:251-253 | — | The guard that catches undeclared shared state knows only five literal patterns (`MANNERS|TALLY|WEALTH |TRADE SHOP|clumpsOnMyMap`) and matches them against step *descriptions*; a new probe that reads map-wide lines under another wording passes the guard, which is exactly the failure class of b284. |
| NEW-FINDING: src/main/java/soloMapling/autoqa/AutoQaRunner.java:211-247 + AutoQaScheduler.java:86-115 | — | A `WORLD` check runs only when `busy` is empty (`canStart`, AutoQaScheduler.java:74-79) while `pick` always takes the first pending check that *can* start (:86-101), so with parallel>1 a world check placed early in the catalogue is pushed to the end of the run and everything behind it runs first — the declared minutes budget is still honoured per check, but the run's wall time pays for it. |
| NEW-FINDING: src/main/java/soloMapling/autoqa/AutoQaRunner.java:355-400 | — | A check whose expectations all SKIP is reported SKIP with reason `nothing could be judged here`, and a release run then fails it unless it is on the allow-list — an infrastructure skip (server not fully up, probe disabled) is indistinguishable in the report from a genuinely skipped feature. |

## State

`working`, with a partial edge. Evidence: the mechanism is complete end-to-end in code (registration → staging → assert →
report, all cited above) and guarded by 776 unit tests across the two packages; `docs/ops/qa-suite.md` records full runs
on TEST (`d4f103b0e`, 2026-10-30, 181 checks, 9,578 s; 2026-10-31, 187 checks, 8,331 s at 4 lanes) and `AutoQaReport`'s
merge-by-check design means a day's runs accumulate rather than overwrite. The multi-lane accuracy is `partial`: b284 is
open (10 lanes produce false FAILs; `docs/tickets/167-parallel-autoqa.md` records the four state kinds that were found and
fixed, and the remaining ones are the cards above). Live does not run any of it. Nothing in this document was verified by
running the server — no run, test or deploy was executed for this review.

## Open questions

* What does TEST's `start-test.sh` actually pass today (`-Dslums.qa`? `-Dslums.attackcapture`? `-Dslums.autoqa`? the lane
  count?)? The file is on the TEST host and not reachable from this worktree; only the repo's own record
  (docs/ops/qa-suite.md:9, 964; src/test/java/soloMapling/qa/QaRegistryTest.java:13-24, timestamped 2026-10-21) says
  attack capture and a lane count.
* Is live's `config.yaml` really free of `AUTOQA_*` keys? Not opened (secrets policy); the release conf `SERVER_SET` does
  not set any, and `AutoQa.isEnabled()` reads only the JVM property plus that field
  (src/main/java/soloMapling/autoqa/AutoQa.java:33-42).
* b284's remaining shared-state keys: which checks still under-declare is not written down anywhere in the repo — the fix
  needs a run-by-run diff of a 10-lane run against the serial reruns, and those report files live on TEST.
* Whether the 11-entry release allow-list (src/main/resources/soloMapling/autoqa-release-skips.tsv) is still the owner's
  intended client-only set for 1.6.4. `AutoQaReleaseTest` only asserts it equals its own copy of the list.
* Auto-QA's nightly cost on a full-population TEST server (~8,300–9,600 s per `all` run) versus testers' expectations: no
  budget or SLA is stated in the repo beyond the per-check `minutes` fields.
