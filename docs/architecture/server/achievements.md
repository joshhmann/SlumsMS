# Achievements

## Purpose

Achievements are the 1.6.1 (ticket 93) cosmetic goal system: 32 one-time goals, each awarding a zero-stat copy of a v83 medal item, for job advances, levels, named boss kills, named PQ/boss clears, fishing catches, weddings, quests, trades, Free Market purchases, guild membership, Daily Check-In days, map tours and the first rebirth. Progress is stored per character — bots included — in `character_achievements`, and the medal is handed over exactly once. Players and bots share the same event path; the only difference is the delivery side (`Hands`).

## Entry points

| Surface | Where | Notes |
|---|---|---|
| `Achievements.on*` static hooks | src/main/java/soloMapling/achievements/Achievements.java:103-199 | The only way game code reports an event; every hook is a no-op when the flag is off |
| Login seed + owed-medal settle | src/main/java/soloMapling/achievements/Achievements.java:204 | Called from src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:423 |
| `@achievements` / `@achievements all` | src/main/java/client/command/commands/gm0/AchievementsCommand.java:27 | Registered src/main/java/client/command/CommandsExecutor.java:238 (gm0) |
| `!achievement list\|preview\|grant\|reset <char> <id>` | src/main/java/client/command/commands/gm3/AchievementCommand.java:28 | Registered src/main/java/client/command/CommandsExecutor.java:454 (gm3) |
| Auto-QA probes `achievements`, `achievementClear` | src/main/java/soloMapling/autoqa/Probes160.java:106-107 | Called for checks t-ac-1 / t-ac-2 |
| `!qa run ac-1` scenario | src/main/java/soloMapling/qa/AchievementScenarios.java:27 | Documented docs/ops/qa-suite.md:574 |
| Public site feed | src/main/java/soloMapling/site/SiteFeed.java:295 | `SiteFeed.achievement`, `Kind.MILESTONE` |
| QA driver | src/main/java/soloMapling/achievements/AchievementsQa.java | Used by both the probe and the `!qa` scenario |

There is no achievement window and no opcode: `grep -ni 'achiev|medal'` over src/main/java/net/opcodes/SendOpcode.java and src/main/java/net/opcodes/RecvOpcode.java returns nothing. Everything the player sees is a chat line via `GameHands.tell` → `Character.yellowMessage` (src/main/java/client/Character.java:8954 → `PacketCreator.sendYellowTip`, src/main/java/tools/PacketCreator.java:5435).

## How it works

1. A game event calls a static hook. Each hook wraps an `Event` and calls `Achievements.fire` (src/main/java/soloMapling/achievements/Achievements.java:86), which returns immediately when `chr == null` or the flag is off (:88-90), then does `s.submit(Subject.of(chr), e)` (:93). Hook list:

   | Hook | Event built | Call sites |
   |---|---|---|
   | `onJob(chr,newJobId)` :103 | `JOB, jobTier(newJobId)` | src/main/java/client/Character.java:1389; src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotProgression.java:163,176,190 |
   | `onLevel(chr)` :110 | `LEVEL chr.getLevel()` **and** `LEVEL_CAP level>=getMaxLevel()?1:0` | src/main/java/client/Character.java:4046 (player level-up), :4117 (offscreen/bot exp) |
   | `onMobKill(chr,mobId)` :120 | `MOB_KILL mobId` | src/main/java/server/maps/MapleMap.java:1799 (credited `eventKiller` only) |
   | `onClear(scriptName,members)` :127 | `CLEAR 1 key=scriptName` for **each** member | src/main/java/scripting/event/EventInstanceManager.java:1426, inside `setEventCleared(ExpeditionType)` :1424 |
   | `onFish` :136, `onGuildJoin` :140, `onWedding` :144, `onRebirth` :165, `onQuestDone` :169, `onTrade` :173, `onMarketBuy` :177 | `Event.of(t)` = value 1 | GameFishing.java:113; Guild.java:516 and MatchCheckerGuildCreation.java:129; RingActionHandler.java:410-411 and OwnedAltWedding.java:184-185; Character.java:12992 and BotRebirth.java:432; Quest.java:405; Trade.java:520-521 and BotBotTrade.java:476-477; HiredMerchant.java:362,957, PlayerShop.java:304,692, BotBotTrade.java:475 |
   | `onBotWedding(botId)` :149 | `WEDDING 1`, subject built from the id, `chr` may be null | BotCouples.java:421-422 |
   | `onCheckin(chr,day)` :182 | `CHECKIN day` | src/main/java/server/checkin/DailyCheckin.java:228 |
   | `onMap(chr,mapId)` :188 | `VISIT mapId` | src/main/java/client/Character.java:2185 (after `visitMap`) |

2. `AchievementService.submit` (src/main/java/soloMapling/achievements/AchievementService.java:55) runs the cheap filter `concerns(charId,e)` (:73) — some goal for that trigger matches the event and is not already `EARNED` — and hands the work to the single-thread executor named `achievements` created in `Achievements.build()` (src/main/java/soloMapling/achievements/Achievements.java:73). Game threads never touch the database.

3. `AchievementService.handle(Subject,Event,quiet)` (:91, `synchronized`) loads the character's rows from the cache or `JdbcAchievementStore.load` (:283 `rows`), then for every goal of that trigger computes `Goal.next(progress,e)` (src/main/java/soloMapling/achievements/Goal.java:19) and skips when the value is unchanged.

4. When `Goal.done(after)` (Goal.java:60, `after >= needed()`) is true, the claim is the conditional UPDATE `markEarned` (src/main/java/soloMapling/achievements/AchievementService.java:107 → JdbcAchievementStore.java:66-85): it only wins from `state = 0`, so two processes cannot both claim the same goal. Non-done goals that keep progress (every non-`MAX` trigger, Goal.java:65) are written with `saveProgress` (AchievementService.java:112 → JdbcAchievementStore.java:52-56), which never overwrites a row already past `progress`.

5. Each freshly earned goal gives the player the line `Achievement: <title> - <text>` (AchievementService.java:118) and `deliver(who,g,!quiet)` (:120) announces it on the site feed when `publicly` and the character is live (:136-138). Bots skip the feed (`who.chr()` is usually null for offline bots).

6. `settleLocked` (:172) walks the whole catalogue and calls `grantOne` (:186) for every row `state == EARNED`: add the medal if the bag does not already hold it (`GameHands.has` → `haveItemWithId`, GameHands.java:13), `hands.save` (character save, non-bots), then `markGranted` (:199 → JdbcAchievementStore.java:93-97). A full Equip tab returns `Grant.NO_ROOM` and tells the player "Make room in your Equip inventory…" (:191-195); the row stays `EARNED` and the medal is owed until the next login, `@achievements` or the next event.

7. Login seeding: `Achievements.onLogin` (Achievements.java:204) skips bots, then on the achievements thread runs `forget(id)` and quiet `handle` calls for the current job tier, level and level cap, plus `loginFacts` (Achievements.java:239): `GUILD 1` when `getGuildId()>0`, `WEDDING 1` when `isMarried()`, and `REBIRTH getReborns()` only when `USE_REBIRTH_SYSTEM` is on. Those are `Event.fact` (src/main/java/soloMapling/achievements/Event.java:22) and `Goal.next` treats a count-mode fact as a floor (`Math.max`), so logging in again never double-counts (Goal.java:34). The login ends with `settle(who)` (:153) to hand over owed medals.

8. `@achievements` calls `svc.settle` then prints from `AchievementsCommand.lines` (AchievementsCommand.java:45): `"Achievements: N of 32 earned."`, an `Earned:` line wrapped at 100 chars (:80), and progress lines for unfinished counting goals; `all` lists every goal with `Goal.progressText` (Goal.java:70).

## Data

`src/main/java/soloMapling/achievements/achievements.yaml` — 209 lines, `goals:` at :21, **32 goals** (`grep -c '^  - id:'` = 32), 32 distinct medals, all in 1142xxx.

| Key | Meaning | Validation (src/main/java/soloMapling/achievements/GoalCatalog.java) |
|---|---|---|
| `id` | stable goal id, also the DB key | `[a-z0-9][a-z0-9-]{0,31}`, unique (:77-79) |
| `title` | medal-line name | required text (:114-116) |
| `text` | "how to" text next to the title | required text |
| `medal` | item id handed over | 1142000..1142999 (:24), unique, and `knownItem` — must resolve to a named item (:82-84) |
| `trigger` | which `Trigger` constant | `Trigger.parse`, unknown → `IllegalArgumentException` (Trigger.java:39-44) |
| `target` | count needed | 1..100000 (:87-89); ignored/1 for `VISIT` (`needed()` = `(1<<maps.size())-1`, Goal.java:56) |
| `mobs` | mob ids that count | present iff `MOB_KILL` (:99-100) |
| `scripts` | event script names that count | present iff `CLEAR`, lowercased at load (:93-98) |
| `maps` | map ids for the tour | present iff `VISIT`, max 20 (:100) |

At most a `goals:` list; any other top-level key rejects the file (:60-63); an empty list rejects it (:105-107). Codes: unknown key (:73), bad/duplicate id (:78), bad medal (:83), bad target (:88), shape mismatch (:100). A rejected file is logged by `Achievements.build` (`"Achievements are off: … is invalid"`, Achievements.java:81) and the server runs with the feature off until restart.

Triggers (`Trigger.java:8-25`) and their `Mode` (:27):

| Trigger | Mode | Goal counts (`Goal.next`, Goal.java:19-41) |
|---|---|---|
| `job` | MAX | `max(progress, jobTier)`; `jobTier` = 0 for `jobId%1000==0`/2001, 1 for `%100==0`, else `min(4, 2+jobId%10)` (Achievements.java:267-276) |
| `level`, `level_cap` | MAX | `max(progress, value)` — never goes down |
| `mob_kill` | ADD | +1 when the mob id is in `mobs` |
| `clear` | ADD | +1 when the lowercased script name before the first `_` is in `scripts` (`Goal.scriptName`, Goal.java:81) |
| `fish`, `guild`, `wedding`, `rebirth`, `quest`, `trade`, `market` | ADD | `progress + max(0,value)`; a login fact becomes a floor |
| `checkin` | MAX | `max(progress, day)` — the claimed day number |
| `visit` | MASK | bit `maps.indexOf(mapId)` OR'd into progress; done at all-bits-set |

Storage: `database/migrations/043-achievements.sql:11-20` creates `character_achievements(charid INT, achievement VARCHAR(40), progress INT, state TINYINT, earned_at BIGINT, granted_at BIGINT, PRIMARY KEY(charid,achievement), KEY character_achievements_recent(state,earned_at)) ENGINE=InnoDB` — no foreign key, because bots have no `characters` row (comment :1-6); the ledger id is `043-achievements` (:22). Rollback `database/migrations/043-achievements.rollback.sql` drops the table and the ledger row; medals already in bags stay.

`state` values (`AchievementStore.java:11-15`): 0 = progress, 1 = EARNED (medal owed), 2 = GRANTED (medal in the bag). `AchievementStore.Row(progress, state, earnedAt, grantedAt)` (:17).

Medals are copies: `GameHands.medal` (src/main/java/soloMapling/achievements/GameHands.java:55) takes `ItemInformationProvider.getEquipByIdAsEquip` and `zeroStats` (:63) zeroes str/dex/int/luk/hp/mp/watk/matk/wdef/mdef/acc/avoid/hands/speed/jump/vicious/upgradeSlots. The medal sits in the `MEDAL` equip slot (src/main/java/client/inventory/BodyPart.java:61, src/main/java/constants/inventory/EquipSlot.java:28).

## Switches

| Switch | Kind | Default | Live / TEST |
|---|---|---|---|
| `-Dslums.achievements` | JVM property, `Boolean.getBoolean` (Achievements.java:18, :28) | false (off in code) | Live ON: `-Dslums.achievements=true` in `UNIT_EDITS` (docs/release/1.6.4.conf:271, carried from docs/release/1.6.1.conf:250). TEST `start-test.sh` is on another host and not reachable — unknown, but ticket 93 records TEST running with the flag and 043 applied (docs/tickets/93-achievements-medals.md:3). |
| `-Dslums.achievements.it.url` | JVM property | unset → `JdbcAchievementStoreIT` skips (`Assumptions.assumeTrue`, src/test/java/soloMapling/achievements/JdbcAchievementStoreIT.java:30) | Test-only |
| `USE_REBIRTH_SYSTEM` | config.yaml key (src/main/java/config/ServerConfig.java:353) | no default assigned (false until yaml says otherwise) | True on live (docs/release/1.6.1.conf:61); gates only the REBIRTH login fact (Achievements.java:250) |
| `DAILY_CHECKIN_ENABLED` | config.yaml key (ServerConfig.java:46) | `false` | On on live (docs/release/1.6.1.conf:7); gates the only `CHECKIN` event source (DailyCheckin.java:60) |

There is no `ServerConfig` field for achievements: the whole feature is behind the one `-D` flag, and `AchievementService` is built lazily on first use (Achievements.java:44-59). With the flag off, `service()` returns null and every hook returns before doing anything (`withTheSwitchOffEveryHookDoesNothingAndNeverThrows`, src/test/java/soloMapling/achievements/MedalsAndHooksTest.java:58).

## Invariants

- Exactly once: a goal flips to EARNED only through the conditional `UPDATE … WHERE state = 0` (JdbcAchievementStore.java:66-69); a lost race falls through to `INSERT IGNORE` (:76-84) which reports 0 rows, and `handle` then does not count the goal as earned (AchievementService.java:108-111).
- Order: EARNED (row) → item in bag → character save → GRANTED (row). A crash anywhere in the middle is repaired by `settle`, because a row at EARNED with the medal already held only gets `markGranted` (AchievementService.java:188-198). Covered by `aCrashBetweenTheItemAndTheReceiptNeverGivesTwo`.
- Progress never moves backwards on a finished goal: `saveProgress` only rewrites `state = 0` rows (JdbcAchievementStore.java:53-54), and `MAX`-mode goals are monotone by `Math.max` (Goal.java:36).
- A login fact is a floor, never an increment (Event.java:22, Goal.java:34), so multiple logins cannot accumulate a count.
- The medal item is cosmetic: `zeroStats` clears every stat and upgrade slot (GameHands.java:63).
- Nothing a hook does can throw into the game thread: `fire` catches `RuntimeException|LinkageError` (Achievements.java:97-99), `submit` catches everything around `executor.execute` (AchievementService.java:62-67), and `handle` catches `SQLException` by dropping the character's cache entry (AchievementService.java:129-131).
- A bot's medal is best effort; its row is marked GRANTED either way (AchievementService.java:139-148), and `hands.save` skips bots (GameHands.java:31-33).
- The yaml is all-or-nothing: any error means no achievements at all, not a partial catalogue (GoalCatalog.java:60-107, Achievements.java:79-82).
- Clear goals key on the event script name, not the instance name: `EventInstanceManager.scriptName()` returns `em.getName()` and only falls back to the instance name (src/main/java/scripting/event/EventInstanceManager.java:882-885); passing the instance name ("Zakum1") never matched, which is why t-ac-2 exercises the real `clearPQ` path.

## Origin

**Custom.** There is no `soloMapling` package in the Cosmic base tree (`/root/workspace/cosmicms/src/main/java/soloMapling` does not exist), so no file in this subsystem exists upstream and none can be `diff`-ed against it. `tools/cosmicdiff/classifications.json` contains 0 entries matching `achievements`.

History (`git log --follow` on src/main/java/soloMapling/achievements/AchievementService.java and achievements.yaml):

| Commit | Date | What |
|---|---|---|
| 6b9f1a07f | 2026-10-28 | First drop: 32 data-driven goals, zero-stat medals, `character_achievements` (ticket 93) — still the only commit touching achievements.yaml |
| adc11453c | 2026-10-28 | Renumber the migration to 043 (042 is Slums-chan) |
| d41799e55 | 2026-10-28 | Resolve achievements.yaml through `DataFiles` |
| 9f10cc83e | 2026-10-28 | Clears key on the event script name; login facts for guild/marriage/rebirth; script-completed quests count |
| 1482288c1 | 2026-10-28 | Add t-ac-2 (real PQ clear) |
| 64c70434e | 2026-10-29 | QA fixes (ac-1 counters) |
| d32bf5bf5 | 2026-11-01 | RealClearQaTest stub matches the null client |

The oldest commit is 6b9f1a07f; 03d13cb21 (the base-v83 repack) predates everything in this package.

## Tests and QA

Unit/integration tests, src/test/java/soloMapling/achievements/ (11 files):

| Class | What it proves |
|---|---|
| `AchievementServiceTest` | count reaches target once; levels+jobs complete several goals at once; unrelated/invalid events change nothing; named kill/clear count; progress survives a restart; full bag leaves the medal owed; offline character gets it at login; a crash between item and receipt never gives two; a second process cannot claim twice; bots earn the same way and are recorded with a full bag; seeded facts are quiet and GM grant/reset work; `submit` queues and skips finished goals; the QA check resets every goal its catches count |
| `AchievementsMigrationTest` | migration 043 is idempotent, has a rollback, and creates no foreign key |
| `EventClearHookTest` | a named instance clear earns its scripts goal (`aNamedInstanceClearEarnsItsScriptsGoal`) |
| `GoalCatalogTest` | 30..34 goals with distinct medals; the medal set equals `VERIFIED_MEDALS` (32 ids, :20-23); every trigger the ticket names has a goal; the reader is strict; the yaml is in `DataFiles.KNOWN`; `DataFiles.optional` finds it |
| `GoalTest` | adds vs non-adds; tiers/levels keep the highest; only the named mob/script counts; a visit goal needs every map; job tiers from job ids |
| `JdbcAchievementStoreIT` | real MySQL: claims happen once, progress never moves an earned row, the service round-trips and survives a restart — skipped unless `-Dslums.achievements.it.url` names a scratch schema (:25-30) |
| `LoginFactsAndQuestHookTest` | guild/marriage/rebirth from before count at login; a fact is a floor; a script-completed quest counts once and a repeat of a done quest does not |
| `MedalsAndHooksTest` | a medal copy carries no stats; the earned line is the yellow tip (never `dropMessage`); flag off makes every hook a no-op and never throws; the player's list shows earned goals and progress |
| `RealClearQaTest` | a named Kerning instance cleared through `clearPQ` earns `pq-kerning` |

Surefire runs one class per fresh JVM, `forkCount=${test.forks}` (4 by default), `reuseForks=false` (pom.xml:257-259).

Auto-QA (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`):

| Check | Line | Probe | Wants |
|---|---|---|---|
| `t-ac-1` | :1350 | `achievements` | a Mano kill and ten catches through the real event path hand over one zero-stat medal each, exactly once; a Snail kill and repeats give nothing |
| `t-ac-2` | :1355 | `achievementClear` | a real `KerningPQ` instance cleared through `clearPQ` earns `pq-kerning` within 15 s |

Both hold `achievements` (a scheduling key, `Check.Builder.holds`, src/main/java/soloMapling/autoqa/Check.java:158; `AutoQaScheduler.keys` src/main/java/soloMapling/autoqa/AutoQaScheduler.java:55), not a capability gate. Probes: src/main/java/soloMapling/autoqa/Probes160.java:68 (names), :106-107 (dispatch), :2005 `achievements`, :2021 `achievementClear`.

`!qa` scenario `ac-1` (src/main/java/soloMapling/qa/AchievementScenarios.java:27): clears the GM's `kill-mano` and `fish-10` goals and medals, `!qa do mano` / `!qa do fish` (ten kept catches), `!qa do list`; on stop, resets and takes the medals back (docs/ops/qa-suite.md:574-580). Needs `-Dslums.achievements=true`.

Not covered: no client-side test of the medal tab or the medal's appearance (there is no achievement UI), no test of the `SiteFeed` line, no multi-process/multi-JVM test against a real server pair (the "second process" case is simulated with two store instances), no test of `onBotWedding` with an offline bot subject beyond the service-level path, no load test of the 6,000-entry cache, and `t-ac-2` was recorded as not yet run on TEST (docs/roadmap/1.6.1.md:126).

## Known issues

| Id | One line |
|---|---|
| `sweep#B3` (docs/tickets/181-bot-behaviour-audit-1-6-4.md:229) | Cache overflow calls `cache.clear()`, dropping every character's rows at once — src/main/java/soloMapling/achievements/AchievementService.java:286-288, guarded only by a size check, so a burst of 6,000 distinct ids throws away every cached row (the reload is correct but each character pays a `load` again). |
| ticket 93 (docs/tickets/93-achievements-medals.md:45-46) | Not done: backfill of past boss/PQ/fishing/streak events; the client-side look of the medal tab; a daily-dungeon goal (the dungeon does not exist). |
| ticket 93 (docs/tickets/93-achievements-medals.md:3) | Status recorded as "real-client look pending"; live switch shipped in docs/release/1.6.1.conf. |
| NEW-FINDING: src/main/java/soloMapling/achievements/AchievementService.java:144-146 | The bot branch of `deliver` updates `cache.getOrDefault(id, new HashMap<>())…computeIfPresent(…)`. When the id is not cached (the normal case after `forget`, or after the `cache.clear()` above) the write lands on a throwaway map and is lost, so the cached row stays EARNED while the DB says GRANTED; the next `settleLocked` then calls `grantOne`, sees `markGranted` return false and reports `ALREADY` — harmless to the player, but the cache and the DB disagree until the next reload. |
| NEW-FINDING: src/main/java/soloMapling/achievements/Achievements.java:259-264 | `Achievements.onLogout` has no caller anywhere in src/main/java (`grep -rn 'Achievements.onLogout'` finds only the declaration), so the per-character cache entry is only dropped by the login path (`forget` at Achievements.java:216) or the 6,000-entry clear. Dead code today; documented intent ("forget on logout") is not wired. |
| NEW-FINDING: src/main/java/soloMapling/achievements/AchievementService.java:134-137 | `deliver` announces before the medal is actually granted, so the site feed can show an achievement whose medal is still owed (`NO_ROOM`). Arguably intended (the achievement is earned, the medal is pending), but the feed text says the medal was received. |

No `ac-*` card exists in /root/work/STATUS-1.6.4.md, /root/work/OWNER-DECISIONS.md or /root/work/PQ-UNFINISHED-REPORT.md (grep for `achiev` returns nothing in those three files).

## State

**working.** Evidence: 32-goal catalogue loads and validates strictly (GoalCatalog.java:60-107, `theShippedFileHasAboutThirtyGoalsWithDistinctVerifiedMedals`); the full event → progress → claim → medal → receipt flow is exercised over an in-memory store (`AchievementServiceTest`, 14 cases) and over real MySQL (`JdbcAchievementStoreIT`, opt-in); every hook has a call site in game code (table above); `t-ac-1` PASS on TEST 2026-10-28 (docs/roadmap/1.6.1.md:126) with the flag on; live runs the flag (docs/release/1.6.4.conf:271) and migration 043 is in live's `LEDGER_BEFORE` (docs/release/1.6.4.conf:182, entry 39 of the list). Partial spots: the client-side medal look was never verified on a real client (ticket 93:3), and no backfill exists for events recorded before 1.6.1 (ticket 93:45).

## Open questions

- Whether TEST's `start-test.sh` still passes `-Dslums.achievements=true` — the file is on another host and not reachable here; the repository only shows the live `UNIT_EDITS`.
- Why `onLogout` exists without a caller: intended for a `PlayerLoggedoutHandler` hook that was never added, or left over after the login `forget` made it unnecessary.
- Whether the `sweep#B3` cache clear is reachable in practice: the audit ticket asks the same question (docs/tickets/181-bot-behaviour-audit-1-6-4.md, "whether 6,000 character ids … are reached"), no measurement is recorded.
- Whether the achievements feed line should fire before or after the medal is in the bag (`deliver` order, AchievementService.java:134-137) — no ticket decides it.
- Whether a future client will get an achievement window; ticket 93 records the medal tab as pending, and no opcode exists in this tree.
