# Drama, guilds and couples

## Purpose

Three related social layers that give bots a life story with and without the player:

| Layer | Question it answers | Root package |
|---|---|---|
| Drama | Do bots hold grudges, flirt, tease, rage-quit? | `src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/` |
| Guilds | Do bots belong to named crews, recruit, fight turf wars? | `src/main/java/soloMapling/ArtificialPlayer/BotGuildSystem/` |
| Couples | Do bots pair with each other and marry in Amoria? | `src/main/java/soloMapling/ArtificialPlayer/BotCoupleSystem/` |

Everything funnels through one door, `BotDrama`, so map/chat/death hooks fire drama only after the package switch and the "is this a bot?" check (`src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/BotDrama.java:19-139`). Romance with a *player* is opt-in per player (`@romance on`, off by default), while bot↔bot couples and guilds need no player consent at all.

## Entry points

| Kind | Hook | File:line |
|---|---|---|
| Tick | population director calls couples, guilds, drama every pass | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:540-542` |
| Tick | drama fans out to 6 subsystems | `src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/BotDrama.java:105-116` |
| Own 2 s tick | player romance wedding beats (not the director) | `src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/RomanceWedding.java:189-195` |
| Own tick | bot-couple wedding beats | `src/main/java/soloMapling/ArtificialPlayer/BotCoupleSystem/BotWeddings.java:201` |
| Chat | general map chat → drama | `src/main/java/net/server/channel/handlers/GeneralChatHandler.java:64` |
| Chat | guild chat → guild chatter | `src/main/java/net/server/channel/handlers/MultiChatHandler.java:71` |
| Map | player entered map → romance/rivalry map change | `src/main/java/server/maps/MapleMap.java:2858` |
| Combat | player death → roasts/revenge | `src/main/java/client/Character.java:9014` |
| Combat | item boom → rivalry + rage quit | `src/main/java/net/server/channel/handlers/ScrollHandler.java:188` |
| Level | guild member level up | `src/main/java/client/Character.java:7887` |
| Guild | join/expel/rank/disband | `src/main/java/net/server/guild/Guild.java:105,428,548,610,720` |
| Family | invite / summon / summon answer | `src/main/java/net/server/channel/handlers/FamilyAddHandler.java:77`, `FamilyUseHandler.java:150,235`, `FamilySummonResponseHandler.java:66` |
| Fame | player asks a bot for fame | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java:384` |
| Login | bot joined the world | `src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/BotDrama.java:118-123` |

Commands:

| Command | Realm | Surface |
|---|---|---|
| `@romance on\|off\|wedding\|breakup\|divorce` | player | `src/main/java/client/command/commands/gm0/RomanceCommand.java:16-36` |
| `@botdrama off\|on` | player | `src/main/java/client/command/commands/gm0/BotDramaCommand.java:15-38` |
| `@guildwar` / `@guildwar peace` | player | `src/main/java/client/command/commands/gm0/GuildWarCommand.java:16` |
| `@botromance …` (status/stage/ask/propose/wedding/object/runaway/crash/divorce/step) | GM | `src/main/java/client/command/commands/gm4/BotRomanceCommand.java:13-16` |
| `@botguild …` (list/form/stage/recruit/bossrun/quit/join/kick/step) | GM | `src/main/java/client/command/commands/gm4/BotGuildCommand.java` |
| `@couple …` (list/spawn/form/wedding/break/step) | GM | `src/main/java/client/command/commands/gm4/CoupleCommand.java:21-26` |

YAML: all dialogue lives in `src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/DramaDialogue.yaml` (root key `Drama:` at `:9`, `Wedding*` nodes `:202-262`, `Roast*` `:267-297`, `War*/Poach*/Ally*` `:430-486`).

## How it works

**Dispatch.** Each `BotDrama` hook returns immediately unless `DramaKit.enabled()` and the subject is not a bot; the remaining work is memory-only or handed to the executor. `BotDrama.maybeStep` (`BotDrama.java:105`) fans out in order: `BotRomance.maybeStep`, `BotRivalry.maybeStep`, `GuildWars.maybeStep`, `BotBotRivalry.maybeStep`, `GuildFeuds.maybeStep`, `RageQuit.maybeStep`.

**Numbered flow — one drama episode (a romance wedding with an objection):**

1. Player has `@romance on` and an `ENGAGED` row; both are in Amoria (`680000000`) and the bot is live: `RomanceWedding.start` gates on `BotRomance.enabled`, no running ceremony, `ENGAGED`, map, `player.getPartnerId()==botCharId`, ring room, `DramaKit.liveBot`, `freeForScene`, `bringTo` (`src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/RomanceWedding.java:129-168`).
2. Forced beats are rolled once at start: objection (`wedding.objectChance`, 0.35), runaway (`runawayBeat` → `wedding.runawayChance`, 0.08), crash (`wedding.crashChance`, 0.3); all three require `!BotAffinity.optedOut(player)` and `BotAffinity.hostilityEnabled()` (`RomanceWedding.java:120-127,170-175`).
3. `gatherGuests` pulls at most three `FRIEND` bots, skipping the ex (`RomanceWedding.java:202-237`).
4. A dedicated 2 s scheduler started in `start` drives `RomanceWedding.step` (`RomanceWedding.java:189-195`); if the player or bot left the map the ceremony finishes as "the couple left" (`RomanceWedding.java:242-253`).
5. Scripted seconds after start: officiant 6 s, speak-now 14 s; with an objection the whole tail shifts +14 s (`RomanceWedding.java:254-262,264-275`).
6. Objection at 18 s: `object` picks the ex first, else a `FRIEND` crush, else the last guest, and brings it in (`RomanceWedding.java:426-473`); the bot replies 24 s, the officiant overrules 28 s (`RomanceWedding.java:269-275`).
7. Runaway at 24 s + shift: bot says `WeddingRunaway`, QA records `RUNAWAY`, player is told "still engaged", ceremony ends (`RomanceWedding.java:277-284`).
8. Bot vows 24 s + shift, officiant asks at 30 s + shift with `Say "i do" in chat.`, and `vowsAskedAt` is stamped (`RomanceWedding.java:285-292`).
9. The player's map chat hits the `I_DO` regex through `onPlayerChat` (`RomanceWedding.java:69,327-340`); silence for 40 s is taken as yes via `WeddingTakenAsYes` (`RomanceWedding.java:293-296`).
10. `makeItReal` checks bag room, picks the ring, `createRings`, delivers or deletes, removes tokens, bonds the pair, records `qa(MARRIED)` and gossips (`RomanceWedding.java:349-378`); failure ends the ceremony with the reason (`RomanceWedding.java:297-303`).
11. After marriage: kiss at +3 s, a guest cheer every 8 s, crash at +15 s (`crash` prefers the ex, then a `RIVAL`, then `WeddingCrash` and a 7 s leave), finish at +50 s (`RomanceWedding.java:308-322,475-520,545-585`).

**Rivalry.** `allowed` = enabled && `BotAffinity.hostilityEnabled` && not opted out (`BotDramaSystem/BotRivalry.java:145`). `onSight` rolls a KS war (`rivals.ksWarRival` 0.05, `rivals.ksWarNemesis` 0.15, `rivals.ksWarsPerHour` 3) or a callout at nemesis / 4+ hostiles with a 0.1 roll (`BotRivalry.java:214-300`); revenge on map change is 0.5 gated to one a day and six an hour, then a follow (`:511-560`), then a demand with an apology window (`rivals.apologyWaitMinutes` 1.5, `:576-620`); an apology in chat calms it, mocking turns the grudge into `NEMESIS` (`:662-700`).

**Beef / rage quit.** Bot-vs-bot call-outs (`BotDramaSystem/BotBotRivalry.java:210`), guild feuds from member spats (`BotDramaSystem/GuildFeuds.java:40-48,86`), and rare rage quits: only an expensive, high-level item lost to another bot qualifies (`BotDramaSystem/RageQuit.java:50-61,93-110`), the bot logs out 2-6 h, the population director keeps it away (`BotDrama.java:100-103` → `BotPopulationDirector.java:591`), and `DramaNews` publishes it (`BotDramaSystem/DramaNews.java:49-59`).

**Guilds.** `GuildPlan.SPECS` is a fixed table of ten named crews with vibe, size band, level band and rival (`BotGuildSystem/GuildPlan.java:30-55`). `BotGuilds.step` ensures load, rescales old GP once, then per guild runs party-up, recruit, chatter, a 10-minute quit/kick sweep, refill and rivalry jabs (`BotGuildSystem/BotGuilds.java:1002-1038`, quit/kick sweep `:1030-1033`, jabs `:1310`). Applications accept at 0.8 with a level floor of `max(minLevel, 50)` for HARDCORE/PVM (`GuildRules.java:95-124`); non player-led bots quit after `ignoreDays` 21 ignored days (`BotGuildSystem/GuildRules.java:147`) and get kicked under `kickMinKnown` 3 (`:164`). Boss runs roll every 30 min at 0.25, gather 4 min, run 25 min, and loot named items only (`GuildBossRuns.java:187-190,244,264-265`). Guild wars last `wars.warDays` 7 with a `wars.cooldownDays` 3 cooldown (`BotDramaSystem/GuildWarRules.java:22,26`), at most `wars.maxBotWars` 2 bot wars with a `wars.playerCheckMinutes` 30 player-war check (`BotDramaSystem/GuildWars.java:604,626`), scored minute by minute by the tick at `GuildWars.java:340`.

**Couples.** `BotCouples.step` runs crushes first, then formation, then sour/breakup, then couple behaviour, sweep, and a wedding window (`BotCoupleSystem/BotCouples.java:449-474`): `now-lastWeddingAt >= weddingGapHours 24 && !BotWeddings.running() && WorldEvents.curveFraction() >= 0.5 && rng < 0.1`. Formation is driven by `BotCrushes.step` (`BotCoupleSystem/BotCrushes.java:177`), a 10-minute roll with a 3-day crush TTL (`:54-58`). Breakups are capped at `BREAKUPS_PER_DAY` 1 world-wide (`BotCouples.java:664,720-740`) and jealousy fights roll 0.15 within 150 px (`BotCouples.java:666,687,696`). Bot weddings run as a `WorldEvents` ceremony (`BotCoupleSystem/BotWeddings.java:227-280`) and bless every watcher once, capped by `blessExpCap` 20000 (`BotCoupleSystem/BotWeddings.java:352-366,368-370`).

## Data

| Table | Columns of note | Migration |
|---|---|---|
| `botcouple` | `BotA`/`BotB` UNIQUE, `Stage` default DATING, `FormedAt`, `MarriedAt`, `LastNoteAt` | `database/migrations/012-bot-couples.sql:16-30` |
| `botguild` | `GuildId` PK, `Vibe`, `RivalId`, `TargetSize`, `CreatedAt` | `database/migrations/014-bot-guilds.sql:21` |
| `botguildmember` | `BotId`, `GuildId`, `GuildRank` default 5, `AttentionAt` | `database/migrations/014-bot-guilds.sql:31` |
| `botromance` | PK(`CharacterId`,`BotId`), `Stage`, `TogetherAt`, `RingItemId`, `BotRingId` | `database/migrations/015-bot-romance.sql:17` |
| `botromanceopt` | `CharacterId` opt-in set | `database/migrations/015-bot-romance.sql:31` |
| `botguildwar` | `Kind`, `GuildA`/`GuildB`/`BPlayer`, `State`, `ScoreA`/`ScoreB` | `database/migrations/016-bot-guild-wars.sql:15` |

Every store degrades to memory when the table is missing (`BotDramaSystem/RomanceStore.java:20-24,91`). Fame and episodes are computed, not stored: fame is a seeded per-id value capped at 30 000 (`src/main/java/soloMapling/ArtificialPlayer/BotFame.java:14-30`), episodes are 24 h-throttled records (`src/main/java/soloMapling/ArtificialPlayer/BotEpisodes.java:39,69-116`).

## Switches

| Switch | Where | Default | Live (`docs/release/1.6.4.conf:271` UNIT_EDITS, `:268` owner answer) |
|---|---|---|---|
| `-Dsolomapling.drama` | `BotDramaSystem/DramaKit.java:85` | true | not set → on |
| `solomapling.drama.romance` | `BotDramaSystem/BotRomance.java:126` | true | not set → on |
| `solomapling.drama.rivals` | `BotDramaSystem/BotRivalry.java:132-133` | true | not set → on |
| `solomapling.drama.beef` | `BotDramaSystem/BeefKit.java:35-36` | true | not set → on |
| `solomapling.drama.wars` | `BotDramaSystem/GuildWars.java:79-80` | true | not set → on |
| `solomapling.drama.<key>` numeric knobs (`wars.warDays` 7, `wars.cooldownDays` 3, `wars.maxBotWars` 2, `rivals.ksWarMinutes` 3, `wedding.objectChance` 0.35, `couples.*`) | `BotDramaSystem/DramaKit.java:64-82` | per key | not set → defaults |
| `solomapling.guilds.enabled` / `.seed` | `BotGuildSystem/GuildRules.java:50,55` | true / true | not set → on |
| `solomapling.couples.enabled` | `BotCoupleSystem/BotCouples.java:161-162` | true | not set → on |
| `solomapling.couples.crushes` | `BotCoupleSystem/BotCrushes.java:80-81` | true | not set → on |
| `solomapling.couples.blessExpCap` | `BotCoupleSystem/BotWeddings.java:350` | 20000 | not set |
| `slums.family.botsOff` | `src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:86-87` | false (inverse) | not set |
| `slums.bots.famerequest` | `src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:33` | false | `=true` |
| `slums.ownedalts.marry` | `src/main/java/soloMapling/companions/OwnedAltWedding.java:26` | false | `=true` |
| `@romance on\|off` | `client/command/commands/gm0/RomanceCommand.java:16,23`; rule `AGENTS.md:224` (§7) | **off** | per player |
| `@botdrama off\|on` | `client/command/commands/gm0/BotDramaCommand.java:15` | on | per player |

Numeric drama knobs are read from the same property name plus a suffix (`DramaKit.prop`, `BotDramaSystem/DramaKit.java:66`), so they cannot appear in `config.yaml`, which is strict.

## Invariants

- A bot's `getClient().getPlayer()` is null, so every hook takes a `Character`; bots are identified by id ≥ 1 000 000 000 and packets aimed at a bot go nowhere.
- Hooks must stay O(1) on the calling thread: each `BotDrama` entry point gates on `DramaKit.enabled()` and membership before rolling (`BotDrama.java:19-139`).
- `@romance off` (the default) means no bot flirts, asks out or proposes; ex-taunts additionally require `BotAffinity`'s hostility not to be opted out (`BotRomance.java:506-560`).
- `@botdrama off` must stop trash talk, KSing, defames and refusals out of spite, stored per character in `botdrama` (migration 011) (`src/main/java/client/command/commands/gm0/BotDramaCommand.java:9-12`).
- Couple breakup drama is at most one a day world-wide unless staged (`BotCouples.java:664,720-728`).
- A wedding cannot start while `BotWeddings.running()` or another ceremony holds the scene (`RomanceWedding.java:129-168`, `BotWeddings.java:110-123`).
- No bot wedding or owned-alt wedding grants a stat or EXP bonus beyond the fixed guest blessing (`BotWeddings.java:352-370`; `OwnedAltWedding.java:18-22`).

## Origin

Base, modified, custom are per-file: every file below is **custom**, added under `soloMapling/` with no upstream v83 ancestor (`git log --follow` on each path returns only soloMapling commits).

| Path | Oldest commit | Count |
|---|---|---|
| `BotDramaSystem/` | `b27adfdb5` 2026-10-22 "Add opt-in romance between players and bots (1.5.0 bot drama)" | 47 |
| `BotGuildSystem/` | `086880a8d` 2026-10-21 "Bot guilds and partying with players (1.5.0 bots lane, ticket 50 B/C)" | 29 |
| `BotCoupleSystem/` | `e20144653` 2026-10-21 "Bot couples and Amoria weddings (ticket 58)" | 23 |
| `BotFamily.java` | `68d88c19b` 2026-10-27 | 3 |
| `BotFame.java` | `d3c22681a` 2026-10-21 "(1.4.2)" | 1 |
| `BotFameRequest.java` | `5ce80fbb6` 2026-10-30 | 2 |
| `BotEpisodes.java` | `84fffe0fb` 2026-10-25 | 6 |
| `companions/OwnedAltWedding.java` | `21ec85c67` 2026-11-04 "Ticket 189 stage 2" | 1 |

## Tests and QA

| Layer | Coverage |
|---|---|
| JUnit — drama | 18 classes under `src/test/java/soloMapling/ArtificialPlayer/BotDramaSystem/` (`BeefTest`, `BotBotEscalationTest`, `BotRivalryTest`, `BotRomanceTest`, `ContentGuardTest`, `DramaKitBringToTest`, `GuildWarCheckScopeTest`, `GuildWarsTest`, `RevengeArcTest`, `RomanceDivorce*Test`, `RomanceFactsTest`, `RomanceFollowFreeTest`, `RomanceRulesTest`, `RomanceStoreRetryTest`, `RomanceWeddingRingTest`, `RomanceWeddingRunawayTest`, `TurfContestPickTest`) |
| JUnit — guilds | 10 classes under `.../BotGuildSystem/` (`BotGuildSafetyTest`, `BotGuildsTest`, `ChatHoldHopTest`, `GuildBossRunCrewPartyTest`, `GuildBossRunPreemptTest`, `GuildBossRunQaCrewTest`, `GuildJabRepeatTest`, `GuildRulesTest`, `GuildShowUpLoginTest`, `KickWhisperTest`) |
| JUnit — couples | 7 classes under `.../BotCoupleSystem/` (`BotCoupleSafetyTest`, `BotCouplesTest`, `BotCrushesTest`, `CoupleStagingPairTest`, `CoupleTogetherTest`, `WeddingChatterTest`, `WeddingWindowTest`) |
| JUnit — side pieces | `BotFameTest`, `BotFameRequestTest`, `BotFamilyTest`, `BotFamilyTestSeams`; no dedicated `BotEpisodes` test |
| Auto-QA | `t-af-2/3` (`autoqa/AutoQaCatalog.java:112,123`), `t-rv-1..5` (`:134,143,153,159`), `t-bg-1` (`:202`), `t-gu-1..6,8` (`:205-282`), `t-gw-1..4` (`:289-310`), `t-cu-1/2` (`:331,339`), `t-rm-1..4` (`:376-415`), `t-su-2` fame requests (`:1470`) |
| `!qa` | `qa/DramaScenarios.java:33` = `Rm1Dating, Rm2Wedding, Rm3Breakup, Rm4Objection, Rv1Roast, Rv2KsWar, Rv3Callout, Rv4Revenge, Gw1BotWar, Gw2PlayerWar, Gw3Poach, Gw4Alliance`, registered at `qa/QaScenarios.java:87`, plus the `@botdrama`-off scenario at `:1359` |

Not covered: no test drives a couple-ring purchase (auto-QA `t-rg-2` is manual, `AutoQaCatalog.java:660`), and nothing exercises the drama tick under population load.

## Known issues

| Card | Line in `/root/work/STATUS-1.6.4.md` | Issue |
|---|---|---|
| b225 | 57 | KPQ pass hand-overs invisible (fix `205acddac`) — drama hooks stay silent across instance handover |
| b232 | 58 | Follower bot in an instance does not leave with its leader (`625948ac9`) |
| b240 | 59 | Bots say they are grinding while standing in town (`23fe8f08f`, `3aba87f15`) |
| b241 | 60 | KPQ bonus-stage messages (`6c257d926`) |

Only b225-b241's four cards exist; b233-b239 are unused. Adjacent: b276 (`:78`, QA feed drops map-scoped PLAN/ROUTE lines), b284 (`:80`, ten-lane false failures), b285 (`:81`, alt upgrade trade refused unless owner online), b298 (`:48`, crafted packets skip the leader check).

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/RomanceWedding.java:293-296` — an unanswered proposal is auto-accepted after 40 s (`WeddingTakenAsYes`), so a player who walks away from the altar marries the bot without ever saying "i do"; the runaway beat is the only escape and it is rolled at 8%.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotCoupleSystem/BotCouples.java:449-474` — the wedding window is only reachable from the 60 s couples step and requires `curveFraction() >= 0.5`, so bot weddings silently stop happening whenever the world event curve is below half, with no switch to override it.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/BotDrama.java:134-137` — `replyAllowed` returns true whenever the drama package is disabled, so `-Dsolomapling.drama=false` also removes the `@romance off` content filter from model replies.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:110-127` — the comment records that the old `slums.family.acceptChance` 80% roll is gone and a familiar player is now always accepted, so the documented accept-chance knob in `AGENTS.md`-adjacent docs no longer has any effect if still set.

## State

**Working, with caveats.** Drama, guilds and couples all tick from the same director line (`BotPopulationDirector.java:540-542`), their JUnit suites are the largest bot test group (35 classes), and auto-QA plus 12 `!qa` scenarios cover romance, rivalry, guild war, couples and fame. Caveats: the b240 town-vs-grinding chatter bug and the b232 instance-follower bug sit in the same population path, and the auto-accept-at-40-s wedding rule is a behaviour gap rather than a crash.

## Open questions

- Are the `solomapling.drama.*` numeric knobs documented anywhere operators can find them, or only in `DramaKit.prop` call sites?
- `docs/release/1.6.4.conf:253-256` still says alt weddings and marriage EXP are "OFF (ticket 189 stage 2/3)" while `:268` records the owner turning them ON while `:271` passes `-Dslums.ownedalts.marry=true` and `-Dslums.marriage.expbonus=true`; the stale comments should be corrected, but it is undecided which is authoritative.
- Should `BotEpisodes` get a dedicated test, given firm `PARTY`, `TRADE` and `SCAM` episodes feed bot chatter?
