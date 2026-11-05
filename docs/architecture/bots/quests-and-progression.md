# Quests and progression

## Purpose

Two things bots do that players also do, kept on the legal side of the server's own rules:

- **Quests.** A grinding bot may take a *kill quest* that its current grind map already satisfies, hunt it, and turn it
  in at the NPC. It is not an errand system: the picked quest becomes a `LifeGoals.Kind.QUEST` goal, so the life
  brain's `TAKE_QUEST → HUNT_QUEST → TURN_IN_QUEST` steps do the work (src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuests.java:29-56).
  Where a real player is watching the map, the real `Quest.canStart/start/canComplete/complete` run; where nobody is,
  the bot records a modelled completion with no rewards (BotQuests.java:574-602).
- **Progression.** Bots get an AP/SP build, spend it on level-up, and change job at the level the build says
  (src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotProgression.java:22-48). Advancement sets the job
  directly on the `Character`; it never calls `Character.changeJob` (BotProgression.java:44-47), and the 1st/2nd job
  transaction can be walked to the instructor instead of granted silently (src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/JobErrand.java:273-303).

The Temple of Time mainline (quests 3500-3520) is a second quest *source* on the same path, not a second system
(src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/TempleOfTime.java:19-23).

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `LifeRuntime.tick(now)` → `questTick(chr, phase==GRIND, now)` | every bot decision point (30 s self-throttle), only in `GRIND/IN_TOWN/LIFE_DWELL` | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:427, :747-754 |
| `BotQuests.consider(chr, log, grinding, homeMap, now)` | the only picker; requires `grinding` and no active quest | src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuests.java:267-293 |
| `TempleOfTime.consider(chr, log, now)` | called first, inside `consider` | BotQuests.java:281-285; src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/TempleOfTime.java:91-138 |
| `TrainingBot.questVisitTick(boolean planStop)` → `BotQuests.atNpc` | town stop / after-shopping walk-up at the quest NPC | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:502-547; call sites :1321, :1583 |
| `TrainingBot` DECIDE → `BotQuests.huntMap(log)` | the bot is sent to the hunt map | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1968-1979 |
| `BotQuests.offscreenKills(log, mapId, kills)` | offscreen grind stretch credits modelled kills | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2145-2158 |
| `Monster.hit` → `Character.raiseQuestMobCount(id)` | a watched kill credits the real quest | src/main/java/server/life/Monster.java:877; src/main/java/client/Character.java:8958-8989 |
| `Character.levelUp` / offscreen level grant | two progression hooks from the server core | src/main/java/client/Character.java:4049-4052 (offscreen), :4120-4121 (watched) |
| `BotStore` load/save | `restore` / `buildNew` around the stat columns | src/main/java/soloMapling/ArtificialPlayer/BotStore.java:510-516, :602-607 |
| `!botwhy` plan lines | `BotQuestChat.describe(memory)` prints "Quests: …" | src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestChat.java:183-196; src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:774-775 |
| Bot chat (`ChatTriage.Intent.QUESTS` / `RECOMMEND_QUEST`) | `BotQuestChat.doing` / `recommend` | src/main/java/soloMapling/ArtificialPlayer/BotBrainSystem/ChatTriage.java:34; src/main/java/soloMapling/ArtificialPlayer/BotChatActions.java:259-274 |
| auto-QA `t-bq-1`, `t-tm-1`, `t-jb-1`, `t-jb-2`, `t-gb-2`, `t-gb-6` | QA lanes | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1432-1437, :1444-1445, :1462-1463, :1480-1481, :1485-1486 |
| `-D` switches | see Switches | BotQuests.java:63, :95-101 |

## How it works

### Quest lifecycle (picked → accepted → hunted → turned in)

| # | Phase | What happens | File:line |
|---|---|---|---|
| 1 | **Consider** | `LifeRuntime.questTick` runs on a GRIND decision point: `syncReal` copies any real progress into the log, then `consider`. | LifeRuntime.java:747-754; BotQuests.java:399-409 |
| 2 | **Drop stale** | an active quest older than 2 h untaken / 24 h unfinished is dropped and counted (`dropped`). | BotQuests.java:270-273; :75 |
| 3 | **Gate** | requires `grinding == true` and `log.active() == null`; otherwise nothing happens this tick. | BotQuests.java:275 |
| 4 | **Throttle + cap** | one consider per bot per 10 min (`nextConsider`); daily cap `doneToday() >= perDay()`. | BotQuests.java:276-279; BotQuestLog.java:143 |
| 5 | **Temple first** | `TempleOfTime.consider` may return the next mainline quest (or null to fall through, or a crowd/channel defer). | BotQuests.java:281-285; TempleOfTime.java:91-138 |
| 6 | **Rank** | `ranked(chr, log, mapId, homeMap, TAKE_MIN_SCORE=1.5, 1)`: filter by map/level/hops/`canStart`, score, sort desc. | BotQuests.java:302-338; BotQuestScorer.java:27 |
| 7 | **Pick** | `log.pick(new Active(questId, startNpc, endNpc, startMap, endMap, huntMap=mapId, need=mobs, now))`; the active quest becomes a `QUEST` goal in the life plan. | BotQuests.java:289-290; src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:511-524 |
| 8 | **Walk to giver** | the plan's next step is `TAKE_QUEST` (Activity `HANG_OUT`), destination = `startMap`; `TrainingBot.questVisitTick` walks within `NPC_REACH_X/Y` (1200×800) with a 25 s walk deadline. | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePlanner.java:83; BotQuests.java:360-369, :605-610; TrainingBot.java:519-530 |
| 9 | **Accept** | `atNpc(TAKE_QUEST)`: watched → `restore` quest state, `gate.canStart`, `gate.start`, status must be `STARTED`, `log.taken(REAL)`; unwatched → `log.taken(MODELLED)` and `Visit.TAKEN_OFFSCREEN`. | BotQuests.java:453-475 |
| 10 | **Hunt** | the plan's step is `HUNT_QUEST` (Activity `GRIND`); DECIDE calls `BotQuests.huntMap` and sends the bot to the hunt map if `BotMapGates.mayEnter` and `BotMapCap.admit`. | LifePlanner.java:84; TrainingBot.java:1968-1979; BotQuests.java:377-381 |
| 11 | **Credit kills** | watched: the server credits `raiseQuestMobCount` per kill; `syncReal` reads it back per mob. Unwatched: `offscreenKills` adds `kills * share` split by the map's spawn list. | src/main/java/server/life/Monster.java:877; BotQuests.java:399-409, :386-396 |
| 12 | **Hunted** | `Active.hunted()` = every required mob has reached `need`; the fact flips to `QUEST_HUNTED`. | BotQuestLog.java:69; BotQuests.java:352-357 |
| 13 | **Walk to taker** | step `TURN_IN_QUEST` (Activity `HANG_OUT`), destination = `endMap`; same walk-up path. | LifePlanner.java:85; BotQuests.java:360-369 |
| 14 | **Turn in** | `atNpc(TURN_IN_QUEST)`: `syncReal`; not hunted → `NOT_READY`; unwatched or MODELLED → `recordOffscreen` (writes a COMPLETED quest status, `log.complete(false)`); watched → `canComplete`, then `reserve()` the rewards through `BotYieldCaps`, then `gate.complete`, status must be `COMPLETED`, `log.complete(true, now)`. | BotQuests.java:476-511, :574-589 |
| 15 | **Reward refusal** | `reserve` failing 3× (`MAX_DEFERRALS`) records the quest without rewards (`recordRefused`, writes nothing to the quest map). | BotQuests.java:495-500, :591-602, :77 |
| 16 | **Bookkeeping** | `complete` clears the Temple lane entry for that quest, advances `templeDone` when it was the next mainline step, caps the done deque at `keepDone`, bumps `doneToday`. | BotQuestLog.java:206-222, :116-120 |

### Quest scoring (`BotQuestScorer`)

Score = (quest value) / (grind EXP forgone on the trip). At 1.0 the quest is exactly as good as grinding.

| Input | Weight / role | File:line |
|---|---|---|
| `rewardExp` × `questExpFactor` (`USE_QUEST_RATE ? questExpRate/expRate : 1.0`) | **+**×1 added to value | BotQuests.java:331, :192-194; BotQuestScorer.java:56 |
| `bonus` | **+**×1 (bots always pass `0`) | BotQuests.java:331; BotQuestScorer.java:56 |
| required kills of a mob **not** on the grind map × `mobExp` | **+**×1 added to value — **dead in production**: the pick filter already requires every mob on the map, so `otherKills` is always 0 | BotQuestScorer.java:58-63; BotQuests.java:321 |
| required kills of a mob **on** the grind map | counts as neither value nor cost | BotQuestScorer.java:18-19 |
| travel seconds `2.0 × (hops→start + hops→end) × 20 s` | **+** added to cost | BotQuests.java:330, :69 |
| kills elsewhere × `killSecondsPerMob` (`60/kpm`) | **+** added to cost | BotQuests.java:332; BotQuestScorer.java:65 |
| baseline `kpm × expPerKill` (grind EXP/min, floored at 1.0) | **×** on the cost denominator | BotQuests.java:331; BotQuestScorer.java:31, :55, :66 |
| `value <= 0` | score **0** (never taken) | BotQuestScorer.java:64 |
| cost `<= 0` (no travel, no off-map kills) | score **`Double.MAX_VALUE`** ("pure win") | BotQuestScorer.java:67 |
| `TAKE_MIN_SCORE = 1.5` | take threshold in production | BotQuestScorer.java:27; BotQuests.java:286 |
| `RECOMMEND_MIN_SCORE = 1.0` | **no production caller** (tests only) | BotQuestScorer.java:29; src/test/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestScorerTest.java:39 |

Hard filters applied *before* scoring (they are not weights, they remove candidates):
completed/skipped id (BotQuests.java:319), Quest.wz level window (BotQuests.java:320), every required mob spawns on this
map (BotQuests.java:321), NPC resolvable on grind map / return town / home town (BotQuests.java:322-323, :341-347),
both trips ≤ `MAX_HOPS=4` (BotQuests.java:326, :71), real `Quest.canStart` (BotQuests.java:327).

### Temple of Time mainline

| Step | Rule | File:line |
|---|---|---|
| Eligibility | chain not finished (`templeDone < 21`) and `level >= ambition(botId)` where ambition = 90 + hash%36 | TempleOfTime.java:97, :78-82 |
| Per-quest level | `step.lvmin()` gate; already-skipped/completed step → null | TempleOfTime.java:101-104 |
| Money gate | 3514 (Sorcerer) needs `SORCERER_FEE + FEE_BUFFER` = 1.1 M | TempleOfTime.java:107, :62; TempleRoute.java:34-35 |
| Lane | `TempleLanes.choose` picks a lane map + channel, load = hunters + 2× real players, cap 3 | TempleOfTime.java:117-118; TempleLanes.java:53, :32-34 |
| Crowd | no lane anywhere → defer 60-180 s jitter; lane on another channel → `wanted` + defer 60 s, executed by `LifeRuntime`'s `CHANGE_CHANNEL` | TempleOfTime.java:120-131; LifeRuntime.java:471-473 |
| Scripted steps | 3507 (Memory Keeper) and 3514 (Sorcerer) run the real quest script through `BotQuestScript` with a bot-backed `QuestActionManager` | TempleOfTime.java:242-271, :291-385; BotQuestScript.java:44-66, :69-204 |
| Sub-quests | 3523-3539: the job instructor's sub-quest replaces the keeper step, then the log is restored to the keeper | TempleOfTime.java:254-256, :313-331; TempleRoute.java:104-130 |
| Gates | `mayEnter` (corridor maps past a lane exit) and `passes` (portal use) | TempleOfTime.java:210-216, :219-233; src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotMapGates.java:76-77 |
| Not driven | 3521 (Force Field) — item turn-in has no place on this path | TempleOfTime.java:41-42 |

### Job advance

| # | Phase | What happens | File:line |
|---|---|---|---|
| 1 | Goal | `LifeGoals.seed` adds a `JOB_ADVANCE` goal at `nextAdvance(jobId)` (10 / 30 / 70 / 120), pressure `min(1, level/target)` | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeGoals.java:81-100, :190-200 |
| 2 | Watched level-up | `Character.levelUp` → `BotProgression.afterLevelUp(bot, levelBefore)` | src/main/java/client/Character.java:4120-4121 |
| 3 | Offscreen levels | `Character` grant path → `afterOffscreenLevels(bot, levelBefore)` | src/main/java/client/Character.java:4049-4052 |
| 4 | Settle | `progress(bot, ungrantedTo)` → `capture` → `BotBuild.catchUp` + `BotBuild.settle(state, choice, catalog, gate)` | BotProgression.java:198-237; BotBuild.java:648-663, :614-642 |
| 5 | Gate | `AdvanceGate` = `JobErrand.gateFor(bot)`: `null` when `-Dsolomapling.jobErrand` is off (so `settle` advances instantly) | BotProgression.java:216; JobErrand.java:240-245 |
| 6 | Errand | `JobErrand.mayAdvance`: watched, non-owned-alt bot with a reachable instructor → `begin()` (travel + walk + 6 min timeout), returns false this pass | JobErrand.java:248-303 |
| 7 | Arrival | `arrive`/`advance` → `BotProgression.afterErrand(bot)` re-runs `progress`, which now passes the gate via `ALLOW` | JobErrand.java:305-311, :339-355; BotProgression.java:172-178 |
| 8 | Fallback | no route / fare / failed trip / missing NPC / timeout → instant advance (`advance(e,false)`) | JobErrand.java:313-325, :96-144 |
| 9 | Write | `apply`: `bot.setJob(job)`, `BotWeapons.ensureFittingWeapon`, rebuilt skill map, `SkillPoints.booksFor`, `applyBotBuild`, `replaceBotSkills`, `refreshStatsAfterProgression` | BotProgression.java:368-413 |
| 10 | Fourth job | tier-4 advance adds +3 SP, grants `grantFourthJobPackage` and master caps from `FourthJobMasteries` when `-Dsolomapling.botSkillBooks` is on | BotBuild.java:531-549, :557-577; BotSkillPlan.java:101-128 |
| 11 | Unpaid package | the 4th-job package costs 10 M mesos, charged on the spot and re-tried later if short | BotProgression.java:243-254; BotSkillPlan.java:128 |
| 12 | Rebirth | separate, level-200 path: `BotRebirth.stage/tripMap/gather/craft/perform` | src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotRebirth.java:207-215, :410-444; TrainingBot.java:1406-1435 |

### What is NOT implemented

- **3rd/4th job errands.** `instructorFor` returns null for any target job below 100 or ≥600 and for tier ≠ 1,2 —
  "3rd job is a quest and a test, 4th job is Leafre: not walked" (JobErrand.java:200-216). Cygnus and Aran advance
  instantly too.
- **Hunting or buying mastery books.** Books only arrive as loot or from players (docs/tickets/159-job-advance-errands.md:24).
- **Item/talk quests.** `BotQuestIndex.qualifies` rejects scripted, timed, non-mob and non-standard-action quests
  (src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestIndex.java:124-132, :85-86, :94).
- **Temple 3521** (item turn-in) and any non-mainline Temple quest (TempleOfTime.java:41-42).
- **`RECOMMEND_MIN_SCORE`** is dead: the chat recommend path filters nothing with it (BotQuestScorer.java:29;
  BotQuestChat.java:134-152).
- **No quest rewards offscreen**: `recordOffscreen`/`recordRefused` give no EXP, mesos or items (BotQuests.java:574-602).

## Data

| Store | Shape | File:line |
|---|---|---|
| `botlifeprofile` row, key `"quests"` | one active quest (`npc`, `maps`, `need`, `kills`, `taken`, `mode`, `at`), capped done ids, day counters, `temple` progress | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:218, :752; BotQuestLog.java:247-315 |
| `Character` quest map (memory only) | filled by `restore` from the log; only exists once `enableBotQuestState()` is called | BotQuests.java:418-435; src/main/java/client/Character.java:6952-6967 |
| `Quest.wz` Check/Act/QuestInfo | the index every pick is filtered through; built lazily once | BotQuestIndex.java:102-114, :149-223 |
| `botstore` stat columns + `botskills` | AP/SP/pools/skills/build level (`BotRealStats`) | src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotRealStats.java:12-30; BotStore.java:146-152, :1519-1521 |
| `Map.wz` life | NPC presence and mob spawn lists, cached per map | BotQuests.java:230-260 |
| `BotQuestLog` skip set | bounded (`KEEP_SKIP=16`) ids the bot gave up on | BotQuestLog.java:116, :240-243 |

Sizes: ~120 bytes of JSON for the active quest, ~6 bytes per completed id, cap 32 ids by default
(BotQuestLog.java:90, :118-120, and class doc :13-16). Roster bots are never written through `saveCharToDB`, so this
log is their only quest store (BotQuestLog.java:13-16).

## Switches

| Switch | Default | Live (docs/release/1.6.4.conf UNIT_EDITS :271) | File:line |
|---|---|---|---|
| `-Dslums.botquests.enabled` | `false` | **on** | BotQuests.java:63, :95-97 |
| `-Dslums.botquests.perDay` | 6 | default | BotQuests.java:65, :99-101 |
| `-Dslums.botquests.keepDone` | 32 | default | BotQuestLog.java:90, :118-120 |
| `-Dslums.temple.enabled` | `false` | **on** (needs botquests + life) | TempleOfTime.java:48, :73-75 |
| `-Dslums.temple.laneCap` | 3 | default | TempleLanes.java:28, :32-34 |
| `-Dsolomapling.jobErrand` | `false` | **on** | JobErrand.java:48, :187-189 |
| `-Dsolomapling.jobErrand.timeoutMs` | 360 000 | default | JobErrand.java:49, :51 |
| `-Dsolomapling.botSkillBooks` | `false` | **on** | BotSkillBooks.java:43, :75-76 |
| `-Dslums.gear.dexless` | off | **on** | BotProgression.java:223-231 |
| `-Dslums.bots.apvariants` / `.fromSlot` | off / — | **on** / `auto` | BotProgression.java:435-455 |
| `-Dsolomapling.walkWhenWatched` | on unless `false` | default | src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/WatchGate.java:27, :40-42 |
| `BOT_REBIRTH_ENABLED`, `BOT_REBIRTH_PER_DAY` (yaml) | `false`, 2 | **true, 2** | src/main/java/config/ServerConfig.java:477-478; docs/release/1.6.4.conf:205; BotRebirth.java:116-120 |
| `-Dsolomapling.economy.maker.botBatch` | `false` (economy.yaml `maker.botBatch`) | **on** | src/main/java/soloMapling/Economy/economy.yaml:159; BotMaker.java:84 |
| TEST start-test.sh | — | TEST: unknown (script unreachable in this review) | — |

Every quest/progression switch is a `-D` JVM property or a yaml key; none is read from a per-bot row, so turning one
off changes behaviour for bots already in the world.

## Invariants

1. **Bots carry no quest state unless switched on.** `Character.tracksQuestState()` is `!isArtificial(id) || botQuestState`
   (src/main/java/client/Character.java:6952-6954); the quest map is enabled per bot by `restore`
   (BotQuests.java:418-435).
2. **Never `forceStart`/`forceComplete`.** Every watched take/turn-in goes through `Quest.getInstance(id).start/complete`
   with the real `canStart/canComplete` verdict first (BotQuests.java:121-148, :447-513; BotQuestScript.java:20-21).
3. **A modelled quest earns nothing.** `Visit.RECORDED`/`recordRefused` write at most a COMPLETED status and never pay
   out (BotQuests.java:574-602, :624-655).
4. **Rewards must fit the yield caps.** Mesos and items pass `BotYieldCaps`; a refusal defers the turn-in, it never
   overflows the bot (BotQuests.java:199-228, :495-500).
5. **One active quest, one pick per 10 min, `perDay` completions per UTC day, `keepDone` ids kept**
   (BotQuestLog.java:157-162, :143, BotQuests.java:276-279).
6. **Only roster bots quest.** `isRosterBot` is a seam; the default is `BotHelpers::isRosterBot` (BotQuests.java:674-676).
7. **Job changes bypass `Character.changeJob`.** `apply` calls `setJob` directly, so no job-advance quest, no
   `JobAdvance` packet and no `changeJob` side effects run for a bot (BotProgression.java:368-374, :44-47).
8. **Skill map is replaced whole.** `replaceBotSkills` never merges the clone template's skills in
   (BotProgression.java:368-413).
9. **A Temple lane entry is cleared the moment its quest ends**, by id not by bot (BotQuestLog.java:206-231;
   TempleLanes.java:121-131).
10. **Errand time is bounded**: 6 minutes then the instant advance, and `BotCommitments` treats a running errand as a
    reason not to start something else (JobErrand.java:49-51, :313-325; src/main/java/soloMapling/ArtificialPlayer/BotCommitments.java:77-80).

## Origin

| Area | Verdict | Evidence |
|---|---|---|
| `BotQuestSystem/**` | **custom** (ported *idea*, own code) | oldest commits `babcd1641 2026-10-30` "Let grinding bots take and turn in kill quests at the NPC (ticket 158, switch off)", `f911a1915`, `357adfde2` (git log --follow, BotQuests.java); repo base `03d13cb21 2026-10-18` "Establish deployable MapleRoot server baseline" |
| `BotQuestScorer` | **custom port** of nutnnut/Cosmic `77e0c5bc9` `BotQuestManager` (AGPL-3.0), talk-quest half dropped, grind-map mobs excluded from both sides | BotQuestScorer.java:6-9, :18-19 |
| `TempleOfTime` / `TempleRoute` / `TempleLanes` | **custom**, inspired by upstream `BotTempleProgressionManager.grindLane`; upstream force-start/force-completes, this does not | TempleOfTime.java:19-23, TempleRoute.java:11-13, TempleLanes.java:14-17 |
| `BotProgressionSystem/**` | **custom** | oldest commits `990789b48 2026-10-19` "Apply bot builds to live bots on both gain paths", `f411b9811 2026-10-19` "Add BotBuild: AP/SP builds and job advancement for stock classes", `7096210ff` (git log --follow) |
| `JobErrand` | **custom** | `0cf1a8cfa 2026-10-30` "Bots walk to their job instructor to advance and use 4th-job mastery books (ticket 159)", `cd29b674a 2026-10-31` |
| `BotSkillBooks` | **custom** (concept credited to nutnnut/Cosmic `dev` `BotSkillBookManager`) | BotSkillBooks.java:19-35 |
| `BotMakerSystem` | **not progression** — crafting/economy: `BotMaker.townBatch` makes crystals and disassembles junk, and `learnByLevel` only raises the Maker skill with level | src/main/java/soloMapling/ArtificialPlayer/BotMakerSystem/BotMaker.java:217-244, :399-417; git `ea0103901`, `eb154325c`, `b6a03a3d5` (2026-10-30) |

All of `src/main/java/soloMapling/` is custom relative to the MapleRoot base commit; the AGPL credits above are for
*ideas*, and the port notes say so explicitly (BotQuestScorer.java:6-9).

## Tests and QA

| Layer | Coverage | File |
|---|---|---|
| Unit — quests | scorer maths, index parsing, log persistence/completion, driver picks, gate faults, Temple route/lanes/scripted steps | src/test/java/soloMapling/ArtificialPlayer/BotQuestSystem/ (`BotQuestScorerTest`, `BotQuestIndexTest`, `BotQuestLogTest`, `BotQuestsTest`, `BotQuestGateFaultTest`, `TempleOfTimeTest`, `TempleRouteTest`, `TempleLaneLifetimeTest`, `TempleScriptsForBotsTest`, `BotQuestChatTest`) |
| Unit — progression | build/AP/SP maths, variants, dexless cap, books, errand routing, rebirth | src/test/java/soloMapling/ArtificialPlayer/BotProgressionSystem/ (`BotBuildTest`, `BotBuildVariantTest`, `BotBuildDexlessTest`, `BotProgressionTest`, `BotProgressionSpBooksTest`, `JobErrandTest`, `JobErrandRouteTest`, `BotSkillBooksTest`, `BotRebirthTest`, `Rebirth*`) |
| Unit — isolation | quest state stays off for artificial characters | src/test/java/soloMapling/ArtificialPlayer/BotQuestStateBoundaryTest.java |
| Unit — maker | fees, junk, path | src/test/java/soloMapling/ArtificialPlayer/BotMakerSystem/ |
| auto-QA | `t-bq-1` take/hunt/turn-in one mob quest (3 min); `t-tm-1` next Temple mainline step + lane (2 min); `t-jb-1` walk to instructor or fall back (6 min, `Check.WORLD`); `t-jb-2` read a wanted book, sell a spare (1 min); `t-gb-2` dexless AP cap; `t-gb-6` variant rolling | AutoQaCatalog.java:1432-1437, :1444-1445, :1462-1463, :1480-1481, :1485-1486; probes in src/main/java/soloMapling/autoqa/Probes162.java |
| `!qa` scenarios | none for quests or progression | src/main/java/soloMapling/qa/ (no quest/progression scenario) |

**Not covered:** a real client watching a bot take and turn in a quest (no test asserts what a player sees); the 24 h
life-profile row size; the economy effect of books being withheld from bot shops; Temple lane pressure with many real
players; `BotQuestChat` replies against a live model; the whole maker batch under live data volume.

## Known issues

| Card | One line |
|---|---|
| e05 (/root/work/STATUS-1.6.4.md:115) | "1.6.4 scoping: headless quest gateway for bots" — still `later`, so bots have no headless gateway for quests. |
| e15 (/root/work/STATUS-1.6.4.md:125) | Lion King's Castle quest line has never been audited end to end — outside the bot path today. |
| e17 (/root/work/STATUS-1.6.4.md:127) | Gear progression (normal → 200, the 120-149 bridge, the rebirth forge handoff) is still `check`. |
| b240 (/root/work/STATUS-1.6.4.md:59) | "Regular bots say they're grinding while standing in town" — the same phase/talk mismatch that makes `!botwhy` quest lines read oddly. |

No b225/b232/b241/b276/b284/b285/b287 card is a bot-quest or bot-progression defect; those are instance, QA-feed and
economy cards (see /root/work/STATUS-1.6.4.md:57-83).

NEW-FINDING: `RECOMMEND_MIN_SCORE` is declared and unit-tested but has no production caller, so the chat "recommend a
quest" path has no worth floor at all — `BotQuestChat.recommend` filters only by level and what the bot knows
(src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestScorer.java:29; BotQuestChat.java:134-152).

NEW-FINDING: `BotQuests.ranked` filters to quests whose every required mob spawns on the grind map
(BotQuests.java:321) and then hands that same full set to the scorer as `overlapMobs` (BotQuests.java:331), so
`!overlapMobs.contains(...)` at src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestScorer.java:59 can never
be true in production: `otherKills` is always 0 and `travelSeconds` is the whole cost. The scorer's off-map-EXP branch
and its `killSecondsPerMob` argument are therefore unreachable from the only caller, and a quest that needs one mob off
the map is excluded outright rather than priced (the upstream port priced it — BotQuestScorer.java:10-21).

NEW-FINDING: the Temple lane picker is consulted only inside `TempleOfTime.consider`
(TempleOfTime.java:117-118), which itself only runs when `BotQuests.consider` passes `grinding == true`
(BotQuests.java:275, :281). A bot that reaches its Temple level while its brain keeps it in town never gets a lane,
and `TempleLanes.hunters` will age its own entries out after 6 h (TempleLanes.java:30, :133).

NEW-FINDING: a quest taken while nobody watched can never be upgraded. `atNpc` records the mode at take time
(BotQuests.java:456 `log.taken(BotQuestLog.Mode.MODELLED)`) and the turn-in sends anything not `REAL` down
`recordOffscreen` even when `watched` is true on that later tick (BotQuests.java:480) — so a bot that happens to be
observed when it turns in still gets no rewards and no real completion, which contradicts the class's "where a player
can see it, the real quest path runs" (BotQuests.java:39-42).

NEW-FINDING: the `BotQuestLog` class doc links `{@link BotQuests#LEVEL_WINDOW}` (src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestLog.java:27)
but no `LEVEL_WINDOW` member exists anywhere in `BotQuests` — grep finds only that javadoc line — so the stated reason a
dropped completed id can never be re-picked ("picks only look at quests whose start level is within LEVEL_WINDOW") does
not describe the code that runs; the level rule is the Quest.wz `lvmin/lvmax` check at BotQuests.java:320.

NEW-FINDING: the daily quest cap counts quests that paid nothing. `recordRefused` and `recordOffscreen` both call
`log.complete(false, ...)` (BotQuests.java:586, :599) and `complete` unconditionally does `rollDay`+`doneToday++`
(src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestLog.java:217-218), so `perDay()` is spent on modelled
and reward-less turn-ins; a bot whose rewards the yield caps refuse three times (BotQuests.java:497) burns its whole day
on quests that gave it nothing, and `doneToday` is also what gates the next pick (BotQuests.java:279).

## State

**working.** Evidence: 182 unit tests exist around the driver, the build maths and the Temple data — 68 in
src/test/java/soloMapling/ArtificialPlayer/BotQuestSystem/, 111 in
src/test/java/soloMapling/ArtificialPlayer/BotProgressionSystem/, 3 in
src/test/java/soloMapling/ArtificialPlayer/BotQuestStateBoundaryTest.java (counted `@Test` methods; not executed in this
review — no builds allowed);
six live auto-QA checks (`t-bq-1`, `t-tm-1`, `t-jb-1`, `t-jb-2`, `t-gb-2`, `t-gb-6`) cover exactly the four acceptance
flows; all switches are on in live's UNIT_EDITS (docs/release/1.6.4.conf:271); the Temple scripted steps are tested
against the real quest scripts (src/test/java/soloMapling/ArtificialPlayer/BotQuestSystem/TempleScriptsForBotsTest.java).

**partial** in three named places: no client-side proof of what a player sees; no 3rd/4th-job errand and no book
hunting/buying (JobErrand.java:200-216; docs/tickets/159-job-advance-errands.md:24); Temple 3521 and item quests
are out of scope by design (TempleOfTime.java:41-42; BotQuestIndex.java:124-132).

## Open questions

1. Does a real client render a bot's COMPLETED quest status (`recordOffscreen` writing into a bot's quest map) as
   anything a player could see — and is that intended when the bot pays out nothing? No test pins it
   (BotQuests.java:574-589).
2. `BotQuests.consider` requires `grinding`; is a bot that lives in town forever supposed to never quest, or should the
   `HANG_OUT`/`IN_TOWN` phase be able to pick a quest whose NPC is in front of it? (BotQuests.java:275)
3. Is the off-map quest really meant to be unpickable (filter at BotQuests.java:321), or was the scorer's off-map pricing
   (BotQuestScorer.java:58-63) supposed to see those quests? One of the two is dead today.
4. Was `RECOMMEND_MIN_SCORE` meant to gate chat recommendations, and did dropping it let bots recommend junk quests
   (BotQuestChat.java:134-152)?
5. `BotRebirth.enabled()` needs `BOT_REBIRTH_ENABLED && USE_REBIRTH_SYSTEM` (BotRebirth.java:116); live sets the first
   (docs/release/1.6.4.conf:205) and 1.6.1's conf says the second is "already true on live"
   (docs/release/1.6.1.conf:61), but 1.6.4's conf does not restate it — unverified in this review.
