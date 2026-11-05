# Bot Population

## Purpose

Keep a configured number of ArtificialPlayer characters in the world at all times, without any of them being a real session: a roster of named regulars loaded from the database, a director that logs them in and out along a daily curve, and a shared headless `Client` that gives each bot a place to hang `getClient()` without owning a socket.

- Target size comes from a daily curve scaled by `BOT_ROSTER_PEAK_ONLINE` (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationCurve.java:81`, `src/main/java/config/ServerConfig.java:139`), hard-capped by `BOT_POPULATION_PEAK` (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:388`).
- Identity is derived, never stored as a separate key: `charId = BOT_ID_BASE + rosterSlot`, `BOT_ID_BASE = 1_000_000_000` (`src/main/java/soloMapling/server/SoloMaplingConstants.java:23`, `src/main/java/soloMapling/ArtificialPlayer/BotStore.java:71`, `src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:47`).
- The population is the thing every other bot subsystem ticks off: the director's 30 s tick also drives gossip, guilds, couples, drama, bonds and the roster lifecycle (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:533`).

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `-Dsolomapling.enabled` + `-Dsolomapling.template-id` | Gate for the whole SoloMapling layer; needs both to be true/positive | `src/main/java/soloMapling/server/MapleRootSolo.java:16-23` |
| `-Dsolomapling.autopopulate` | The only thing that starts spawning: schedules `EnvironmentManager::environmentLoadStartup` after 1000 ms | `src/main/java/soloMapling/server/MapleRootSolo.java:51-53` |
| Headless client init | `BotClientHandler.initHeadlessBotClient()` at boot, before anything spawns | `src/main/java/soloMapling/server/MapleRootSolo.java:44` |
| `EnvironmentManager.environmentLoadStartup()` | Boot plan waves 1-7, then director handoff / waves 8-9 seed | `src/main/java/soloMapling/Environment/EnvironmentManager.java:110`, `:190-204` |
| `!env` command | GM re-runs the same startup | `src/main/java/client/command/commands/gm4/EnvironmentCommand.java:379` |
| `BotPopulationDirector.start()` | Director thread: one boot-fill tick, then a fixed-rate tick | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:414-430` |
| `!roster` | `status`, `saveall`, `director`, `curve`, `login`, `logout`, `pause`, `resume`, `buddies` | `src/main/java/client/command/commands/gm4/RosterCommand.java:35`, `:47-87` |
| `BotMapCapCommand` | `!botmapcap` read-out of caps and turn-aways | `src/main/java/client/command/commands/gm4/BotMapCapCommand.java:52` |
| Autosave sweep | Every autosave pass saves bots and writes their session rows | `src/main/java/net/server/task/CharacterAutosaverTask.java:55-82` |
| Shutdown | `Server.shutdownBots` quiesces schedulers, saves everyone, records sessions | `src/main/java/net/server/Server.java:2085-2125` |
| Auto-QA | t-bg-1, t-lk-1, t-tw-1, t-gb-6, t-ms-1 all read live population state | `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:202`, `:902`, `:1236`, `:1462`, `:1571` |

## How it works

1. **Boot gate.** `MapleRootSolo.start()` refuses to run without an explicit template id and logs "SoloMapling disabled; enable explicitly with a dedicated bot template ID before testing" (`src/main/java/soloMapling/server/MapleRootSolo.java:37-40`). The shipped service unit ships both flags false (`deploy/slumsms.service.example:11`).
2. **Headless client.** `BotClientHandler.initHeadlessBotClient()` creates exactly one `BotClient` for channel 1 and lazily one per other channel (`src/main/java/soloMapling/ArtificialPlayer/BotClientHandler.java:26-31`, `:39-44`). A `BotClient` calls `super(null, -1, "bot", null, world, channel)` — no socket, no session, no packet processor, and only world+channel are load-bearing (`src/main/java/client/BotClient.java:12-17`).
3. **Wave spawn (boot plan).** `environmentLoadStartup()` runs waves 1-7 (`src/main/java/soloMapling/Environment/EnvironmentManager.java:118-188`), each wave handing out slots through `BotStore.planSlot(wave, task, ordinal)` = `FIRST_PLANNED_SLOT(8292) + wave*65536 + task*2048 + ordinal` (`src/main/java/soloMapling/ArtificialPlayer/BotStore.java:112`, `:319-332`).
4. **Director handoff.** `directorOn = BotPopulationDirector.enabled()` and `rosterReady = active().size() >= max(1, BOT_ROSTER_SEED_MIN)` (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:403-411`). A ready roster means the director takes over immediately; otherwise waves 8/9 seed it first (`src/main/java/soloMapling/Environment/EnvironmentManager.java:190-204`). Wave 8 is the training cohorts (20 cohorts, 2820 bots at `BOT_POPULATION_SCALE=1.0`) and wave 9 the town presence (`src/main/java/soloMapling/Environment/EnvironmentManager.java:257-279`, `:298-331`).
5. **Roster load from the DB.** First touch of `BotRoster.all()` runs `SELECT * FROM botroster` over its own non-pooled snapshot connection, sets `bootHighestSlot` before any new member can take a slot, and logs "Bot roster holds {} members" (`src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:133`, `:151-152`, `:422-456`). A read failure throws `IllegalStateException("Could not read the bot roster", e)` rather than silently running empty (`:445`). Waves 8/9 were enrolled into `botroster` by migration `INSERT IGNORE ... WHERE BotId >= 8292 AND FLOOR((BotId - 8292) / 65536) IN (8, 9)` (`database/migrations/003-solomapling-bots.sql:84-90`).
6. **Who is online.** Three separate answers, deliberately: `CharacterStorage.botLoggedIn(charId)` / `getBotById` from the in-memory registry (`src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:30`, `:34`); `BotChannels.onlineBots()` by walking every channel's player storage and counting `BotHelpers.isBot` (`src/main/java/soloMapling/ArtificialPlayer/BotChannels.java:311-342`); and, inside the director, `online = live != null && !scheduledLogouts.contains(botId)` so a bot whose logout is already scheduled is not planned as present (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:588`).
7. **Director tick — target.** `tick(maxLogins)` bails while paused or quiesced (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:485-487`), snaps `target = curve().target(directorPeak(), local)` where `directorPeak = max(0, min(rosterPeak, populationPeak - environmentOnline))` (`:478-482`, `:496`). The curve is a piecewise-linear day in the server's own timezone with a weekend lift (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationCurve.java:57-89`).
8. **Director tick — plan.** `plan(...)` is pure and does four things in order: end sessions that ran their length, longest overstay first (`:107-112`); trim above `target + ceil(target*0.02)`, out-of-window first then longest sessions, deferring a guild's last online member (`:115-146`); build `eligible` = ACTIVE and not inside the 20-minute relog cooldown — the cooldown only applies to sessions the director itself ended, so a restart's mass logout comes straight back (`:147-155`); then log in one member of every bot guild with nobody online (`:158-178`) and finally weighted logins below target (`:180-193`) with key `pow(rng, 1/weight)`, `weight = inWindow ? 1.0 : 0.08`, times `SocialPilot.loginBoost` (`:198-202`).
9. **Director tick — logouts.** The plan's logouts are spread uniformly across the tick and handed to `BotLogout.request(charId, now)` on the scheduled executor (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:508-519`); the spread exists because a burst of 200 logouts contends for the pool at ~284 ms each (`:507-511`). A committed bot that finished its session is re-requested every tick instead (`:520-525`). `BotLogout.process(now)` then lets a bot go as soon as its commitment ends, and force-leaves at the 15-minute cap (`src/main/java/soloMapling/ArtificialPlayer/BotLogout.java:32`, `:111-128`).
10. **Director tick — logins.** Logins go through `LoginPace.offer` (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:546-556`), which carries `BOT_DIRECTOR_BOOT_FILL_PER_SECOND` a second with at most `BOT_DIRECTOR_BOOT_FILL_IN_FLIGHT` in flight and never blocks the caller (`:244-318`). A bot whose departure has not been written yet is skipped so it cannot read its own stale row (`:546-551`, `BotStore.departurePending`). The login budget per tick is `max(BOT_DIRECTOR_MAX_LOGINS_PER_TICK, perSecond * tickSeconds)` (`:351-355`); the boot-fill tick passes `Integer.MAX_VALUE` (`:420`).
11. **Login execution.** `logInClaimed` refuses a null member, an already-logged-in bot and a quiescing server (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:818-821`), enforces the population cap (`:824-827`), reserves a channel from the weighted split (`:829`, `src/main/java/soloMapling/ArtificialPlayer/BotChannels.java:361-363`), picks a home map and falls back under the per-map cap with `BotMapCap.noteTurnedAway` (`:832-848`), calls `BotGeneration.createBot(botId, spawn, home, 0,0,0,0)` (`:854`), releases the channel in `finally` (`:855-857`), stamps the bot's personality activity (`:862-864`) and starts the bot type — TRAINING_BOT when `LifeRuntime` is on and the type cannot reach every bot (`:868-871`).
12. **Character creation.** `BotGeneration.createBotOnChannel` derives `botId = BOT_ID_BASE + slot` (`src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java:183`), restores or creates the store row (`:195-202`), claims a name (`:206`), replays the stored placement for a roster member and calls `BotRoster.recordLogin` (`:212-221`), registers the bot in `CharacterStorage` (`:224`), notifies buddies/guilds/drama/family (`:225-228`), joins the map (`:243`) and enrols brand-new wave-8/9 members (`:245-259`).
13. **Move between channels.** `BotChannels.moveToChannel` warps a bot onto the same map and spot of another channel under a hop lock plus the bot's own warp lock, swapping its client to that channel's headless one (`src/main/java/soloMapling/ArtificialPlayer/BotChannels.java:431-482`). It refuses event instances and dead bots (`:432-437`), clears grind state, movement and spot claims first (`:450-458`), and swaps `setClient(BotClientHandler.getBotClient(ch))` before `changeMap` (`:471-476`).
14. **Idle / away.** Bots are never disconnected for inactivity: `TimeoutTask` skips them outright (`src/main/java/net/server/task/TimeoutTask.java:22`), and `BotClient.getLastPacket()` always returns now (`src/main/java/client/BotClient.java:141-143`). "Away" is modelled instead — rage-quit away suppresses the bot from the director's active set (`src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/BotDrama.java:99-102` used at `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:589-591`), AFK campers are parked in town chairs for 12-45 minutes under their own switch (`src/main/java/soloMapling/ArtificialPlayer/BotTownSystem/AfkCampers.java:40-66`), and `SocialPilot` can hold a bot online past its session length (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:592-599`).
15. **Population growth over time.** `BotRosterLifecycle.maybeStep` runs at most hourly from the director tick: sets quitters DORMANT, wakes returners, and creates new beginners and veterans up to `BOT_ROSTER_PEAK_ONLINE * BOT_ROSTER_SIZE_MULTIPLE` (`src/main/java/soloMapling/ArtificialPlayer/BotRosterLifecycle.java:95-129`, `:36-44`).
16. **Shutdown save.** `Server.shutdownBots` wraps everything in `BotDbGate.unmetered` (the shutdown-only exception to the gate), calls `BotShutdown.begin()` to quiesce every bot scheduler, stops `BotTickService`, then `BotStore.settleAndSaveAll(everyone, remainingBudget, ShutdownSaves.BOT_WRITERS)` and finally `BotRoster.recordSessions(everyone, now, true)` (`src/main/java/net/server/Server.java:2086-2117`). `settleAndSaveAll` gathers stored bots plus everything still in `deferredDepartures`, sorts debit-owing bots first, and writes with 4 daemon `bot-shutdown-save-N` threads against a deadline (`src/main/java/soloMapling/ArtificialPlayer/BotStore.java:1287-1340`, `src/main/java/soloMapling/server/ShutdownSaves.java:32`, `:38`). Unwritten debit bots go to the debit journal (`Server.java:2108-2114`).

### Population sizes at a glance

| Quantity | Value | Source |
|---|---|---|
| `BOT_ID_BASE` | 1,000,000,000 | `src/main/java/soloMapling/server/SoloMaplingConstants.java:23` |
| Console bot id | 999,999,999 (below the base, so not a bot by id range) | `src/main/java/soloMapling/server/SoloMaplingConstants.java:24` |
| Merchant base id | 1,100,000,000 | `src/main/java/soloMapling/server/SoloMaplingConstants.java:25` |
| First planned slot | 8,292 | `src/main/java/soloMapling/ArtificialPlayer/BotStore.java:112` |
| First roster slot | 4,268,132 | `src/main/java/soloMapling/ArtificialPlayer/BotStore.java:120` |
| On-demand hand-spawn slots | 8,192 | `src/main/java/soloMapling/ArtificialPlayer/BotStore.java:110` |
| Boot-plan training cohorts (waves 8) | 2,820 bots at scale 1.0 | `src/main/java/soloMapling/Environment/EnvironmentManager.java:257-279` |
| Roster target, code default | 1,200 online | `src/main/java/config/ServerConfig.java:139` |
| Population cap, code default | 1,600 | `src/main/java/config/ServerConfig.java:154` |
| Roster target, live 1.6.4 | 3,000 | `docs/release/1.6.4.conf:201` |
| Population cap, live 1.6.4 | 3,500 | `docs/release/1.6.4.conf:201` |
| Boot fill rate, live 1.6.4 | 2/s (~25 min to 3,000) | `docs/release/1.6.4.conf:15-16`, `:194-196` |

## Data

| Store | Shape | Where |
|---|---|---|
| `botstore` | One row per bot character: `BotId INT UNSIGNED AUTO_INCREMENT` PK starting at 100, names deliberately not unique | `database/migrations/003-solomapling-bots.sql:26-53` |
| `botroster` | `BotId` PK + 13 columns: Status, Personality, Persona, PlayWindowStart, PlayWindowLen, SessionMinutes, HomeMapId, Activity, ActivityState, LastLoginAt, LastLogoutAt, EnrolledAt | `database/migrations/003-solomapling-bots.sql:66-82` |
| `botmemory` | Long-term memory rows feeding `LifeMemoryStore` on session write | `database/migrations/003-solomapling-bots.sql:96` |
| `Member` | `record Member(botId, status, personality, persona, playWindowStart, playWindowLen, sessionMinutes, homeMapId, activity, activityState, lastLoginAt, lastLogoutAt, enrolledAt)` | `src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:43-45` |
| Activity resume slot | `pendingResume` map armed at login, consumed by the bot's first tick | `src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:211`, `:278-310` |
| Name pool | `BotNames/era-style-generated.txt` (12,002 lines) plus `SELECT name FROM characters` so a bot never takes a player's name | `src/main/java/soloMapling/ArtificialPlayer/BotNames.java:52-58`, `:177-200`, `:303-315` |
| Headless clients | `ConcurrentHashMap<Integer, Client> byChannel` | `src/main/java/soloMapling/ArtificialPlayer/BotClientHandler.java:17-18` |
| Live bot registry | `activeBotMap` — refuses owned alts with `IllegalArgumentException("Owned alts do not belong in the population registry")` | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:13-24` |
| Per-map cap state | `FULL` set of `channel<<32` OR-ed with `mapId` plus `TURNED_AWAY` counter | `src/main/java/soloMapling/ArtificialPlayer/BotMapCap.java:53-55` |
| Session record | Roster UPSERT of all 13 columns in one batch after `LifeMemoryStore.prepare/write/attach` | `src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:331-385` |
| Name identity | `BotId` is the identity; duplicate names are legal and `BotNames.claimStored` renames on adoption | `database/migrations/003-solomapling-bots.sql:26-29`, `src/main/java/soloMapling/ArtificialPlayer/BotNames.java:104-121` |

## Switches

| Switch | Default | Live 1.6.4 | Effect |
|---|---|---|---|
| `-Dsolomapling.enabled` | false | true (via unit/conf) | Master gate; also requires a positive template id | `src/main/java/soloMapling/server/MapleRootSolo.java:16-23` |
| `-Dsolomapling.template-id` | 0 | 6 on TEST | Bot character template | `src/main/java/soloMapling/server/MapleRootSolo.java:19-20` |
| `-Dsolomapling.autopopulate` | false | true (deploy unit ships false) | Starts `environmentLoadStartup` | `src/main/java/soloMapling/server/MapleRootSolo.java:52-53`, `deploy/slumsms.service.example:11` |
| `BOT_DIRECTOR_ENABLED` | true | not overridden | Director on/off | `src/main/java/config/ServerConfig.java:101` |
| `BOT_ROSTER_PEAK_ONLINE` | 1200 | **3000** | Roster target at curve peak | `src/main/java/config/ServerConfig.java:139`, `docs/release/1.6.4.conf:201` |
| `BOT_POPULATION_PEAK` | 1600 | **3500** | Hard ceiling including environment bots; 0 = no cap | `src/main/java/config/ServerConfig.java:154`, `docs/release/1.6.4.conf:201` |
| `BOT_ROSTER_CURVE` | `0:0.45,3:0.25,6:0.15,9:0.25,12:0.45,15:0.6,18:0.85,20:1.0,22:0.8` | not overridden | Daily shape; `flat` = constant | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationCurve.java:17`, `src/main/java/config/ServerConfig.java:140` |
| `BOT_ROSTER_WEEKEND_LIFT` | 1.1 | not overridden | Sat/Sun multiplier | `src/main/java/config/ServerConfig.java:141` |
| `BOT_ROSTER_TIMEZONE` | "" (server default) | not overridden | Curve timezone | `src/main/java/config/ServerConfig.java:142` |
| `BOT_DIRECTOR_TICK_SECONDS` | 30 | not overridden | Tick period, floor 5 s | `src/main/java/config/ServerConfig.java:143`, `BotPopulationDirector.java:421` |
| `BOT_DIRECTOR_MAX_LOGINS_PER_TICK` | 25 | not overridden | Per-tick login budget | `src/main/java/config/ServerConfig.java:144` |
| `BOT_DIRECTOR_BOOT_FILL_IN_FLIGHT` | 8 | not overridden | Concurrent logins | `src/main/java/config/ServerConfig.java:147` |
| `BOT_DIRECTOR_BOOT_FILL_PER_SECOND` | 8 | **2** | Login rate; 0 = unlimited | `src/main/java/config/ServerConfig.java:149`, `docs/release/1.6.4.conf:201` |
| `BOT_DIRECTOR_MAX_LOGOUTS_PER_TICK` | 25 | not overridden | Logout budget | `src/main/java/config/ServerConfig.java:150` |
| `BOT_ROSTER_SEED_MIN` | 100 | not overridden | Below this the director refuses and waves 8/9 seed | `src/main/java/config/ServerConfig.java:151` |
| `BOT_POPULATION_SCALE` | 1.0 | not overridden | Scales every boot-plan cohort, never a non-empty one to 0 | `src/main/java/config/ServerConfig.java:215`, `EnvironmentManager.java:290-296` |
| `BOT_ENVIRONMENT_SCALE` / `BOT_FM_ENVIRONMENT_SCALE` | 0.5 / 1.0 | not overridden | Environment (non-roster) bot counts | `src/main/java/config/ServerConfig.java:157`, `:160` |
| `BOT_CHANNEL_WEIGHTS` | `100,55,35,20,15` (ch1 ~45%) | not overridden (CHANNELS=5, CHANNEL_LOAD=125) | Channel split for logins | `src/main/java/config/ServerConfig.java:192`, `docs/release/1.6.4.conf:277-278` |
| `BOT_MAP_CAP` / `BOT_MAP_CAP_OVERRIDES` | 50 / "" | not overridden | Per-map instance cap; 0 = uncapped | `src/main/java/config/ServerConfig.java:197`, `:199`, `src/main/java/soloMapling/ArtificialPlayer/BotMapCap.java:158-165` |
| `BOT_TOWN_MAP_MAX` / `BOT_TOWN_PULL` | 200 / 0 | not overridden | Town maps get the larger cap only while the pull is on | `src/main/java/config/ServerConfig.java:171`, `:183` |
| `BOT_ROSTER_SIZE_MULTIPLE` / `NEW_PER_DAY` / `VETERANS_PER_DAY` / `QUIT_PER_DAY` / `RETURN_PER_DAY` | 3.0 / 40 / 300 / 0.002 / 0.01 | not overridden | Hourly roster lifecycle rates | `src/main/java/config/ServerConfig.java:217-222` |
| `-Dsolomapling.afkCampers` | on | not overridden | AFK campers in town | `src/main/java/soloMapling/ArtificialPlayer/BotTownSystem/AfkCampers.java:61` |
| `-Dslums.shutdown.botBudgetMs` / `BOT_WRITERS` | 60000 / 4 | not overridden | Shutdown save budget and writer threads | `src/main/java/soloMapling/server/ShutdownSaves.java:32`, `:38` |
| `SPAWN_BOTS_ON_STARTUP` | declared, unused | — | Dead flag: spawning is driven by `-Dsolomapling.autopopulate` | `src/main/java/config/ServerConfig.java:98`, `docs/release/1.3.0.md:156` |
| TEST-only start script | — | TEST: unknown | `start-test.sh` not available in this tree | — |

## Invariants

| Invariant | Why it holds | File:line |
|---|---|---|
| A bot is a `Character` with a shared headless `BotClient`; `getClient().getPlayer()` is null and packets sent to a bot go nowhere | `BotClient.sendPacket` is a no-op and the client is constructed with no player | `src/main/java/client/BotClient.java:25-28`, `:12-17` |
| Scripts that need a player must use `BotClient.forScripts(bot)`, never `getClient()` directly | A per-action client subclass returns the bot from `getPlayer()` while sharing the shared client's script engine | `src/main/java/client/BotClient.java:48-100` |
| `Character.isLoggedin()` stays false for bots; `BotClient.isLoggedIn()` returns true so autosave/disconnect loops skip them | Both halves are needed or bots get saved twice and disconnected | `src/main/java/client/BotClient.java:43-46` |
| Bots are never disconnected for inactivity | `TimeoutTask` skips bots and `getLastPacket()` always returns now | `src/main/java/net/server/task/TimeoutTask.java:22`, `src/main/java/client/BotClient.java:141-143` |
| `charId = BOT_ID_BASE + slot` and everything below `BOT_ID_BASE` is not a population bot | Single derivation point; console bot is 999,999,999 | `src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:47`, `src/main/java/soloMapling/server/SoloMaplingConstants.java:23-24` |
| Planned slots never collide with roster slots | Roster slots start at `FIRST_ROSTER_SLOT = 8292 + 65*65536 = 4,268,132` and `isRosterSlot` guards | `src/main/java/soloMapling/ArtificialPlayer/BotStore.java:114-120`, `:335-337` |
| At most `BOT_POPULATION_PEAK` bots, counting environment bots and logins on the way | `populationCapHasRoom` = `onlineBots() + loggingIn.size() < cap`, checked before every login and every enrolment | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:388-392`, `:824-827`, `BotRosterLifecycle.java:162-166` |
| Director logins are idempotent | `CharacterStorage.botLoggedIn` is checked before any reservation | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:818-821` |
| A bot never logs out mid-commitment | `mayLogOut` vetoes trades, follower duty, death, guild boss gathering, weddings, drama scenes, active social responders and any party with a real player | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:643-673` |
| A restock errand is a commitment too | `mayEndSession` = `mayLogOut && !TrainingBot.onErrand()` | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:634-636` |
| The relog cooldown applies only to director-ended sessions | `endedOnPurpose` distinguishes a rage quit from a server restart | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:147-155` |
| Never plan `online + on the way` past the target | `loginsOnTheWay` is subtracted in `plan` | `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:373-376`, `:99-104` |
| Bot DB work never queues on the pool while the pool is held for players | `BotDbGate` caps bots at 6 of 10 connections and refuses instead of waiting on virtual threads | `src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:92`, `:255`, `:281` |
| No bot DB work runs on a virtual thread | Java 21 pins carriers in `synchronized`; work is redirected to `runBotDb`/`runBotLogout` platform pools | `src/main/java/soloMapling/server/ExecutorServiceManager.java:18-22`, `src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java:132-142` |
| Bot activity stops before shutdown saves begin | One-way quiesce gate: every scheduler registers via `onQuiesce` and runs immediately if already quiesced | `src/main/java/soloMapling/server/BotShutdown.java:31-54`, `:60-71` |
| Only `Character`, never packets, is the API | Bots have no socket to hold any packet state | `src/main/java/client/BotClient.java:25-28` |
| Bot names are legal and never a real player's | Pool filtered against `[A-Za-z0-9]{3,12}` and `SELECT name FROM characters` | `src/main/java/soloMapling/ArtificialPlayer/BotNames.java:55`, `:177-216` |
| Owned alts are not roster members | `CharacterStorage.addActiveBot` throws and `BotHelpers.isRosterBot` = `isBot && !isOwnedAlt` | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:19-24`, `src/main/java/soloMapling/ArtificialPlayer/BotHelpers.java:47-49` |
| Lifecycle cleanup only accepts headless bots | `IllegalArgumentException("Only headless bots may use bot lifecycle cleanup")` | `src/main/java/soloMapling/ArtificialPlayer/BotLifecycle.java:17-19` |

## Origin

| Path | Oldest commits (`git log --follow ... | tail -2`) | Verdict |
|---|---|---|
| `src/main/java/soloMapling/ArtificialPlayer/BotRoster.java` | `4d125f57e` 2026-10-19 "Keep bot spawns from starving the DB pool and fix two soak NPEs"; `1512c08a6` 2026-10-19 "Merge the SoloMapling persistence fork onto the live baseline" | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java` | `88afaed00` 2026-10-20 "Add scripted world events with bot reactions (ticket 21)"; `1512c08a6` | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotStore.java` | `4efa1767a` 2026-10-19 "Store bot stats, AP/SP and skills with the bot row"; `1512c08a6` | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java` | `1fa6229b5` 2026-10-19 "Keep the bot DB gate from deadlocking a warm boot on Java 21"; `4d125f57e` | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotPopulationCurve.java` | `1512c08a6` only | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotLogout.java` | `389d72d3b` 2026-10-20; `1512c08a6` | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotMapCap.java` | `956c55486` 2026-10-27; `3fde80134` 2026-10-23 "Cap how many bots the bot systems put on one map instance" | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotChannels.java` | `aa3439586` 2026-10-21 "Five channels: bots on 1-4 (40/28/20/12), channel 5 bot-free"; `0feb6a099` 2026-10-21 | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotNames.java` | `389d72d3b` 2026-10-20 "Load SoloMapling data from the jar when no source tree is staged"; `1512c08a6` | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java` | `1512c08a6`; `03d13cb21` | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotClientHandler.java` | `59330e4fe` 2026-10-20 "Let a world name its id in config.yaml and run Bera alone"; `03d13cb21` | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotShutdown.java` | `1512c08a6` only | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotLifecycle.java` | `03d13cb21` only | custom |
| `src/main/java/soloMapling/server/SoloMaplingConstants.java` | `59330e4fe`; `03d13cb21` | custom |
| `src/main/java/client/BotClient.java` | `37109c3c0` 2026-10-19 "Let the bot client ignore boss HP bars"; `03d13cb21` 2026-10-18 "Establish deployable MapleRoot server baseline" | custom, layered on the upstream `Client` |

Baseline: the repo's oldest commit is `63e5f2369` 2024-06-16 "Fix Energy charge crashing certain other players" (upstream Cosmic lineage) beneath `03d13cb21` (MapleRoot baseline). Every file above is SlumsMS-authored on top of that baseline — `modified` for `client/BotClient.java` (a subclass of the upstream `Client`), `custom` for everything under `soloMapling/`.

## Tests and QA

| Kind | Coverage | File |
|---|---|---|
| Unit — planner | Curve interpolation/wrap/weekend lift, online count tracking the curve across days, same regulars at the same hour, commitments never logged out + relog cooldown, restart logouts come straight back, finished session replaced, one login at a time | `src/test/java/soloMapling/ArtificialPlayer/BotPopulationDirectorTest.java:26`, `:99`, `:125`, `:157`, `:183`, `:199`, `:216` |
| Unit — cap | Boot fill never over cap, planning from online alone overruns, room under target, cap counts on-the-way and 0 = no cap | `src/test/java/soloMapling/ArtificialPlayer/BotPopulationOvershootTest.java:117`, `:140`, `:153`, `:165` |
| Unit — budget | Shipped defaults assert 1600 peak / 1200 roster / 0.5 scale with `directorPeak` 1200, scaled waves, daily curve (300 peak → 45 at 06:00, 135 at 00:00, 149 Sat 00:00), channel split `{533,293,187,107,80}` | `src/test/java/soloMapling/ArtificialPlayer/BotPopulationBudgetTest.java:50`, `:57`, `:77`, `:99`, `:112` |
| Unit — pace | In-flight cap, offer never blocks, queued once, starts spread | `src/test/java/soloMapling/ArtificialPlayer/BootFillPaceTest.java:21`, `:43`, `:56`, `:64` |
| Unit — roster | Migration enrols waves 8/9, roster slots above the boot plan, member by slot, next slot, empty roster, enrol once writes every column | `src/test/java/soloMapling/ArtificialPlayer/BotRosterTest.java:91`, `:101`, `:115`, `:131`, `:139`, `:145` |
| Unit — roster lifecycle | Small roster filled with veterans, nobody quits while online, returning player keeps everything, fractional rates | `src/test/java/soloMapling/ArtificialPlayer/BotRosterLifecycleTest.java:59`, `:75`, `:86`, `:98` |
| Unit — resume | Switched mode resumed, brain starts training, mode switch off forces training, without brain uses recorded type | `src/test/java/soloMapling/ArtificialPlayer/BotRosterResumeChoiceTest.java:18`, `:24`, `:29`, `:34` |
| Unit — logout | Party counts only with a real player, a talking player holds, goodbye lines | `src/test/java/soloMapling/ArtificialPlayer/BotLogoutTest.java:33`, `:52`, `:84` |
| Unit — DB gate | Player login always has room, mass spawn stays within the share, virtual-thread storm is refused, permit returned once, failing pool, nested hold, no-room failure | `src/test/java/soloMapling/ArtificialPlayer/BotDbGateTest.java:36`, `:43`, `:75`, `:119`, `:132`, `:140`, `:152` |
| Unit — channels | Shipped weights, any channel count, 500 logins, split as bots come and go, parallel reservations, env counts, director peak | `src/test/java/soloMapling/ArtificialPlayer/BotChannelsTest.java:17`, `:27`, `:43`, `:58`, `:92`, `:109`, `:125` |
| Unit — map cap | Town max only while the pull is on, default 50, overrides parse, override else default, room below cap, reads config | `src/test/java/soloMapling/ArtificialPlayer/BotMapCapTest.java:47`, `:74`, `:81`, `:91`, `:100`, `:108` |
| Unit — names | Pool legal and unique, names never a player's, exhausted pool, oldest keeps a shared name, stored name kept, player cannot take a bot name | `src/test/java/soloMapling/ArtificialPlayer/BotNamesTest.java:30`, `:37`, `:50`, `:61`, `:77`, `:85` |
| Unit — store | Every column read, statements write each column, key from plan not arrival, hand-spawned slots | `src/test/java/soloMapling/ArtificialPlayer/BotStoreTest.java:151`, `:200`, `:238`, `:259` |
| Unit — misc present | `BotPopulationProof`, `BotRosterSocialLodTest`, `BotLogoutWarpRaceTest`, `BotDbGateLoadTest`, `BotChannelVirtualThreadTest`, `BotStoreRealStatsTest`, `BotStoreStashTest`, `BotStoreReportTest`, `BotGenerationLevelRangeTest`, `BotGenerationLoginThreadTest`, `BotGenerationShutChannelTest`, `BotDeathPersistenceTest`, `BotPopulationMixTest`, `SocialPilotDirectorTest` | `src/test/java/soloMapling/ArtificialPlayer/` |
| Auto-QA | t-bg-1 ten bot guilds out in the world; t-lk-1 bots camp AFK, walk when watched, type like 2009 players; t-tw-1 towns fill toward targets, ch1 busiest, no map over max; t-gb-6 new roster slot rolls an AP variant; t-ms-1 training/social/town all populated and capped | `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:202`, `:902`, `:1236`, `:1462`, `:1571` |
| Auto-QA probes | `townSample`/`townVerdict` with `TOWN_WINDOW_S` (director ticks every 30 s, pulls are 3/min per map); `apVariants` roster-slot probe; social probes SKIP when the population is small | `src/main/java/soloMapling/autoqa/Probes160.java:736-775`, `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1607`, `Probes162.java:1145-1229`, `Probes164Social.java:38`, `:78` |
| `!qa` | `bl-4` records a life profile through an online roster bot (round trip); guild scenarios assert empty-guild verdicts against `BotPopulationDirector.runningForMs()` | `src/main/java/soloMapling/qa/BotLifeScenarios.java:134-158`, `src/main/java/soloMapling/qa/GameAutoQaWorld.java:416-468`, `:461` |
| Not covered | No test asserts a full boot plan end-to-end through `EnvironmentManager` waves 1-9; no test asserts `BotShutdown` + `settleAndSaveAll` under a real deadline; `BotNames` pool load is tested only through seams (`useSourcesForTest`); `BotClient` no-ops have no dedicated test; no test covers `BotClientHandler` per-channel client creation | — |

## Known issues

| Card | Issue |
|---|---|
| b225 (`/root/work/STATUS-1.6.4.md:57`) | KPQ passes a bot's hand to the leader but the leader never sees it |
| b232 (`:58`) | A follower bot inside an instance does not leave with its leader |
| b240 (`:59`) | Regular bots say they are grinding while standing in town |
| b241 (`:60`) | KPQ bonus stage messages wrong for bots |
| b276 (`:78`) | QA feed never receives bot map-scoped PLAN/ROUTE lines |
| b284 (`:80`) | Auto-QA false failures across 10 lanes (QA-tool defect, not population) |
| b285 (`:81`) | Alt upgrade offer refused |
| b287 (`:83`) | t-xc-5 scroll trader never lists Chaos/White Scroll |
| e08 (`:118`) | Ramp-shaped boot fill (a burst of regulars, then a slowing trickle) is not implemented; 1.6.4 ships the flat `BOT_DIRECTOR_BOOT_FILL_PER_SECOND` |

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotChannels.java:320` calls `getPlayerStorage().getAllCharacters()` per channel, and `src/main/java/net/server/PlayerStorage.java:91-98` returns `new ArrayList<>(storage.values())` — every login check (`BotChannels.onlineBots`, `:336-342`) and every autosave/`environmentOnline` call copies all five channels' full player lists, i.e. ~3,000 element copies per login during a fill.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:484-487` guards the tick only on `paused`/`isQuiesced`, and `start()` schedules it with `scheduleAtFixedRate` on a 10-thread pool (`src/main/java/soloMapling/server/ExecutorServiceManager.java:16`) — a tick that runs longer than `BOT_DIRECTOR_TICK_SECONDS` overlaps the next one, so two ticks can plan logouts against the same roster. The pace and `LOGIN_SLOTS` limit the damage on the login side, but nothing serialises the logouts.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:225` sizes `LOGIN_SLOTS` at 8 while `src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:92` grants the bots 6 of 10 pooled connections, so up to 8 logins can be resident and refused by the gate on a single pooled borrow; `BOT_DIRECTOR_BOOT_FILL_IN_FLIGHT` defaults to 8 (`src/main/java/config/ServerConfig.java:147`) for the same reason. Refusals do not corrupt state (`:824-827` re-checks the cap, `:855-857` releases the channel in `finally`) but they burn a channel reservation.

Fixed during review: `docs/architecture/bots/README.md:11` cited the shared headless client as `src/main/java/soloMapling/ArtificialPlayer/BotClient.java`; that path does not exist — the file is `src/main/java/client/BotClient.java`. The README now carries the real path.

NEW-FINDING: `src/main/java/config/ServerConfig.java:98` declares `SPAWN_BOTS_ON_STARTUP` and nothing reads it; `src/main/java/soloMapling/server/MapleRootSolo.java:52-53` keys off `-Dsolomapling.autopopulate` instead. A stale config key that looks live is a trap for operators (`docs/release/1.3.0.md:156` already documents it as unused).

## State

**Working.** Evidence: the director's planner is unit-tested on its pure function (`src/test/java/soloMapling/ArtificialPlayer/BotPopulationDirectorTest.java:26`), the cap is tested explicitly including "counts on-the-way and 0 = no cap" (`src/test/java/soloMapling/ArtificialPlayer/BotPopulationOvershootTest.java:165`), the boot-fill pace is tested for non-blocking offers and spread starts (`src/test/java/soloMapling/ArtificialPlayer/BootFillPaceTest.java:43`, `:64`), the DB gate is tested for refusals and single permit return (`src/test/java/soloMapling/ArtificialPlayer/BotDbGateTest.java:75`, `:119`), and live 1.6.4 raises the roster peak only on the strength of the b273 cap proof (`docs/release/1.6.4.conf:15-16`). The population cap was enforced in code before 1.6.4 — 1.6.2 only lowered the director's target, which is why TEST reached 4,562 bots on a 3,500 cap (`docs/release/1.6.4.conf:194-196`).

Open gap in a shipped subsystem: the ramp-shaped boot fill that the release owner put on the 1.6.4 board has no code yet (card e08, `/root/work/STATUS-1.6.4.md:118`); until then boot is a flat 2/s fill and a restart takes ~25 minutes to reach 3,000 (`docs/release/1.6.4.conf:15-16`).

## Open questions

- Is `BOT_DIRECTOR_TICK_SECONDS=30` still the right period at a 3,000-bot target with logouts spread across the whole tick (`src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:508-519`)? No test bounds tick wall-clock time.
- `LOGIN_SLOTS` 8 vs gate permits 6 (`BotPopulationDirector.java:225`, `BotDbGate.java:92`): deliberate head-room for warm queueing, or an unretuned leftover? The 8 is also the `BOOT_FILL_IN_FLIGHT` default, so the two numbers are not independent.
- Do overlapping ticks actually happen in production? `scheduleAtFixedRate` on a 10-thread pool allows it, but no log records the overlap — `lastTickAt` is written (`:500`) and never compared against the start of the next tick.
- Should `BotRosterLifecycle.create` keep bypassing the login pace (`src/main/java/soloMapling/ArtificialPlayer/BotRosterLifecycle.java:162-166` comments that an enrolment comes online at once)? It counts against the cap but not against `BOT_DIRECTOR_BOOT_FILL_PER_SECOND`, so its rate is bounded only by `NEW_PER_DAY`/`VETERANS_PER_DAY`.
- `environmentOnline` is recomputed by walking every channel on each cap check — is a cached counter acceptable given that the population only changes at login, logout and channel moves, all of which already hold `BotChannels.RESERVATION_LOCK`?
