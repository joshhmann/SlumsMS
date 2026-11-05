# Boot and shutdown

## Purpose

Start one JVM from a cold config file to a fully listening game server: load `config.yaml`, open the database pool, start
every world and channel, register the recurring tasks, bind the login port, then hand over to bots and the optional
export/API services. The mirror path saves real players, then bots under a budget, stops the worlds and the thread pools
and exits. Both directions are one process with no supervisor logic of its own — systemd (or the operator's shell) is the
supervisor, and every abort path is a `throw` into `main`.

## Entry points

| Entry | Where | What it does |
|---|---|---|
| JVM main | src/main/java/net/server/Server.java:1156 | `main(String[])` sets `polyglot.engine.WarnInterpreterOnly=false` (Server.java:1157) and calls `Server.getInstance().init()` (Server.java:1158) |
| Jar manifest | pom.xml:52 | `<mainClass>net.server.Server</mainClass>`, artifact `Cosmic` (pom.xml:7) |
| Boot method | src/main/java/net/server/Server.java:923 | `public void init()` — the whole cold start, 923-1050 |
| Shutdown hook | src/main/java/net/server/Server.java:931-933 | `Runtime.getRuntime().addShutdownHook(new Thread(shutdown(false)))`, only when `SHUTDOWNHOOK` |
| Shutdown body | src/main/java/net/server/Server.java:2075 | `public final Runnable shutdown(final boolean restart)` → `shutdownInternal(restart)` at Server.java:2127 |
| GM shutdown | src/main/java/client/command/commands/gm6/ShutdownCommand.java:78 | `TimerManager.getInstance().schedule(Server.getInstance().shutdown(false), time)` |
| Client connection gate | src/main/java/client/Client.java:178-183 | `channelActive` closes any socket opened while `!Server.getInstance().isOnline()` |

## How it works

Cold start, in the order `Server.init()` executes (all line numbers in src/main/java/net/server/Server.java unless noted):

1. **Banner.** `923` `init()` records `Instant beforeInit`; `925` logs `"SlumsMS v{} (MapleStory v{}) starting up."` with
   `ServerConstants.SLUMSMS_VERSION` (`"1.6.4"`, src/main/java/constants/net/ServerConstants.java:10) and
   `ServerConstants.VERSION` (`83`, ServerConstants.java:8). `926` `server.reports.WarnLogRing.install()` keeps the last
   WARN/ERROR lines for `@bug` (ticket 131). `927` `constants.net.BuildInfo.jarSha256()` hashes the jar in the background.
2. **Shutdown hook.** `931-933` registers the hook only if `YamlConfig.config.server.SHUTDOWNHOOK`. Nothing else in the
   boot depends on it.
3. **Time zone.** `938` `TimeZone.setDefault(TimeZone.getTimeZone(YamlConfig.config.server.TIMEZONE))`, deliberately before
   the pool because the MySQL driver fixes each connection's zone when it opens (comment at Server.java:935-937).
4. **Connection pool.** `940-942` `if (!DatabaseConnection.initializeConnectionPool()) throw new
   IllegalStateException("Failed to initiate a connection to the database")`. This is the first hard abort; there is no
   retry and no degraded mode.
5. **Packet-handler dependencies.** `944` `channelDependencies = registerChannelDependencies()` → method at `1053-1062`:
   `new NoteService(new NoteDao())`, `new FredrickProcessor(noteService)`, `new ChannelDependencies(...)`, then
   `PacketProcessor.registerGameHandlerDependencies(channelDependencies)` (`1060`). Without this every channel handler
   that needs notes or duey would be constructed without them.
6. **Slow WZ/DB loads in parallel.** `946` a fixed pool of 10 threads; `947-951` submit `SkillFactory.loadAllSkills()`,
   `CashItemFactory.loadAllCashItems()`, `Quest.loadAllQuests()`,
   `SkillbookInformationProvider.loadAllSkillbookInformation()`, `PlayerNPCFactory.loadFactoryMetadata()`; `952`
   `initExecutor.shutdown()`. Results are awaited later (step 12), so a failure here does not abort the boot yet.
7. **World config validation.** `957` `WorldConfigs.validate(YamlConfig.config.worlds, GameConstants.WORLD_NAMES.length)`
   (comment `956`: "Ticket 41: a bad world id stops the boot here, before anything is bound or written"). The rules are in
   src/main/java/config/WorldConfigs.java:55-81: empty/absent list (57), null entry (63), `id < -1` (66), `id >= 21` (71),
   duplicate id (76), each an `IllegalStateException` that escapes `init()` — see `config.md`.
8. **Startup-bound DB repairs, on one connection.** `959-972`: `setAllLoggedOut`, `setAllMerchantsInactive`,
   `cleanNxcodeCoupons`, `loadCouponRates`, `updateActiveCoupons`, `NewYearCardRecord.startPendingNewYearCardRequests`,
   `CashIdGenerator.loadExistentCashIdsFromDb`, `applyAllNameChanges`, `applyAllWorldTransfers`. `SQLException` →
   `log.error("Failed to run all startup-bound database tasks")` and `throw new IllegalStateException(sqle)` (971-972).
   The first two are what make a crash-restart safe: every account is stamped logged-out and every hired merchant
   inactive before any client can connect.
9. **Threads and timers.** `974` `ThreadManager.getInstance().start()`; `975` `initializeTimelyTasks(channelDependencies)`.
10. **Worlds.** `978` `int worldCount = Math.min(YamlConfig.config.worlds.size(), YamlConfig.config.server.WORLDS)`; `979-982`
    warn `"WORLDS is {} but config.yaml lists {} world(s); starting {}."` when the list is longer than `WORLDS`; `984-986`
    `for (i < worldCount) initWorld()`. A list *shorter* than `WORLDS` starts what exists and only warns.
11. **Ranking, PlayerNPC, families.** `987` `initWorldPlayerRanking()`; `989` `loadPlayerNpcMapStepFromDb()`; `993-1000` when
    `USE_FAMILY_SYSTEM`, one connection to `client.Family.loadAllFamilies(con)` and
    `log.info("Family buffs restored for {} characters", client.FamilyBuffs.load(System.currentTimeMillis()))`.
12. **Stale-comment catch-all.** `1003-1006` `catch (Exception e) { log.error("[SEVERE] Syntax error in 'world.ini'.", e);
    System.exit(0); }` wraps *only* steps 10-11. The message is a leftover from the ini-based repack: this repo has no
    `world.ini` (worlds come from `config.yaml`), and `System.exit(0)` exits with success status, so a failed world start
    looks like a clean stop to systemd. See Known issues.
13. **Await the loaders.** `1009-1016` `future.get()` for each; any failure → `log.error("Failed to run all startup-bound
    loading tasks", e)` and `throw new IllegalStateException(e)`.
14. **Bind login.** `1018` `final int loginPort = Integer.getInteger("mapleroot.login-port", 8485)`; `1019`
    `loginServer = initLoginServer(loginPort)` → `1063-1067` `new LoginServer(port).start()`; `1021` log `"Listening on
    port {}"`. The login bind is the last hard failure point before the server is usable.
15. **Online.** `1023` `online = true`; `1025` log `"Cosmic is now online after {} ms."` (the string still says Cosmic).
    From here `Client.channelActive` stops rejecting sockets (src/main/java/client/Client.java:180-183).
16. **Static tables and commands.** `1027` `OpcodeConstants.generateOpcodeNames()`; `1028` `CommandsExecutor.getInstance()`.
17. **Owned-alt boot line.** `1031` `log.info(YamlConfig.config.server.ownedAltSwitchesLine())` — one line naming all seven
    owned-alt/companion switches (src/main/java/config/ServerConfig.java:126-134; AL-4, ticket 183).
18. **Event scripts reloaded.** `1033-1035` `for (Channel ch : getAllChannels()) ch.reloadEventScriptManager()`. During the
    channel constructors `Server.isOnline()` was still false, so each channel loaded only `{"0_EXAMPLE"}`
    (src/main/java/net/server/channel/Channel.java:145-153); this loop is where the real event scripts finally load.
19. **Cash shop catalog.** `1036` `if (Boolean.getBoolean("cashshop.window.enabled")) server.cashshop.CashShopCatalog.load()`.
20. **Bots.** `1037` `soloMapling.server.MapleRootSolo.start()`. It no-ops with
    `"SoloMapling disabled; enable explicitly with a dedicated bot template ID before testing"` unless
    `-Dsolomapling.enabled=true` and `-Dsolomapling.template-id` > 0 (src/main/java/soloMapling/server/MapleRootSolo.java:16-25).
    When enabled it runs `DataFiles.checkAtStartup()`, `EquipMetadataCache.initialize()`, `DesirableEquipList.load()`,
    `BotClientHandler.initHeadlessBotClient()`, `BotShopping.install()`, `SocialGate.startAtBoot()`,
    `LifeLearning.onCrossing(...)` (MapleRootSolo.java:36-50), logs
    `"SoloMapling initialized; automatic population: {}"` (MapleRootSolo.java:51) and, with
    `-Dsolomapling.autopopulate=true`, schedules `EnvironmentManager.environmentLoadStartup()` 1 s later
    (MapleRootSolo.java:52-53). That method runs the nine seed waves and then hands the population to the director
    (src/main/java/soloMapling/Environment/EnvironmentManager.java:110-212).
21. **Optional services, in order.** `1038` `DashboardExporter.startIfEnabled()`, `1039`
    `EconomySnapshotService.startNightlyJob()` (unconditional), `1040` `PublicMarketExport.startIfEnabled()`, `1041`
    `PublicSiteExport.startIfEnabled()`, `1042` `TradeRatings.start()` (unconditional), `1043`
    `SiteAuthApi.startIfEnabled()`, `1044` `BotInspectApi.startIfEnabled()`, `1045` `ExchangeService.startIfEnabled()`,
    `1046` `MarketVolatility.startIfEnabled()`, `1047` `SiteBoard.startIfEnabled()`, `1048` `KpqLobby.startIfEnabled()`,
    `1049` `ContentLobby.startIfEnabled()`. Each gate is a `-D` property; see `## Switches`.

**World and channel start.** `initWorld()` (Server.java:452-493) takes the slot as `worlds.size()` under the world lock,
returns `-1` if that slot is past `WLDLIST_SIZE` or past the list (459), reads `WorldConfig wc = worlds.get(slot)` (466),
computes `int i = WorldConfigs.idOf(slot, wc)` (467 — the config's `id`, else its position), logs
`"Starting world {} ({})"`, constructs `new World(i, wc.flag, wc.event_message, wc.exp_rate, wc.drop_rate,
wc.boss_drop_rate, wc.meso_rate, wc.quest_rate, wc.travel_rate, wc.fishing_rate)` (470), then one
`new Channel(i, j, bootTime, slot)` per `wc.channels` (475-479). `registerWorld(...)` (501-517) rejects a world whose slot is
not `worlds.size()` or whose id is taken; on `false` the world is shut down and `initWorld` returns `-2` (488-492).
`World`'s constructor (src/main/java/net/server/world/World.java:169-221) registers the per-world timers: `PetFullnessTask`
1 min (199), `WeatherTask` only when `-Dweather.enabled` (200-203), `ServerMessageTask` 10 s (204), `MountTirednessTask`
1 min (205), `HiredMerchantTask` 10 min (206), `TimedMapObjectTask` 1 min (207), `CharacterAutosaverTask` 1 h (208),
`WeddingReservationTask` per `WEDDING_RESERVATION_INTERVAL` (209), `MapOwnershipTask` 20 s (210), `FishingTask` 10 s (211),
`PartySearchTask` 10 s (212), `TimeoutTask` 10 s (213), `CharacterHpDecreaseTask` per `MAP_DAMAGE_OVERTIME_INTERVAL` (214),
and `FamilyDailyResetTask` daily when `USE_FAMILY_SYSTEM` (216-220). `Channel`'s constructor
(src/main/java/net/server/channel/Channel.java:130-170) computes `port = channelPort(BASE_PORT, portSlot, channel)` (136)
with `BASE_PORT = Integer.getInteger("mapleroot.channel-base-port", 7585)` (64) and
`basePort + (channel - 1) + portSlot*100` (67-69), builds `ip = HOST + ":" + port` (137), starts the Netty
`ChannelServer` (144, 172-175), creates `new ServicesManager(ChannelServices.OVERALL)` (164) and logs
`"Channel {}: Listening on port {}"` (166). Any exception in the constructor is swallowed with
`log.warn("Error during channel initialization")` (167-169) — a channel that fails to bind leaves the world up with one
fewer channel and no abort.

**Recurring tasks registered at boot** (`initializeTimelyTasks`, Server.java:1081-1130):

| Task | Period | Line |
|---|---|---|
| `TimerManager.purge()` | `PURGING_INTERVAL` | 1084 |
| `disconnectIdlesOnLoginTask()` | every 5 min | 1085 (defined 2071-2073, body `disconnectIdlesOnLoginState` 2042) |
| `CharacterDiseaseTask` | `UPDATE_INTERVAL` | 1088 |
| `CouponTask` | `COUPON_INTERVAL`, first after the next hour | 1089 |
| `RankingCommandTask` | 5 min | 1090 |
| `RankingLoginTask` | `RANKING_INTERVAL`, first after the next hour | 1091 |
| `LoginCoordinatorTask` | 1 h | 1092 |
| `EventRecallCoordinatorTask` | 1 h | 1093 |
| `LoginStorageTask` | 2 min | 1094 |
| `DueyFredrickTask` | 1 h | 1095 |
| `InvitationTask` | 30 s | 1096 |
| `RespawnTask` | `RESPAWN_INTERVAL` | 1097 |
| `BossLogTask` | 1 day, first after the next day | 1101 (preceded by `ExpeditionBossLog.resetBossLogTable()`, 1100) |
| JQ-door broadcaster | 1 min poll | 1103-1129, a bare `new Thread` loop, not a `TimerManager` task |

**Shutdown.** `shutdown(false)` is both the hook (Server.java:931) and the `!shutdown` command body
(ShutdownCommand.java:78). `shutdownInternal` (Server.java:2127-2253) runs:

1. `2129` `if (getWorlds() == null) return;` — second entry is a no-op.
2. `2135-2137` `for (World w : getWorlds()) w.beginShutdown()`. That sets `shuttingDown` and cancels the hourly
   `CharacterAutosaverTask` (src/main/java/net/server/world/World.java:2192-2198), so the autosave and the final save can
   never write the same character.
3. `2142` `ShutdownSaves.run(new ShutdownSaves.Steps(){...})`. The player phase (`2144-2185`) snapshots
   `new ArrayList<>(connectedClients.values())` (2146) and runs `Client::forceDisconnect` over it through
   `ShutdownSaves.forEachWithin(clients, ..., PLAYER_SAVERS, budgetMs)` (2149); it then closes owned alts via
   `OwnedAltService.outForShutdown()` and `alts::closeForShutdown` under `max(5000, left)` (2160-2180). The bot phase
   (`saveBots`, ~2183) calls `shutdownBots(budget)` only when `soloMapling.server.MapleRootSolo.isEnabled()`.
4. `shutdownBots` (2084-2125) runs inside `BotDbGate.unmetered(...)`: `BotShutdown.begin()`, `BotTickService.stop()`, then
   per world `BotStore.settleAndSaveAll(everyone, leftMs, ShutdownSaves.BOT_WRITERS)`, then `BotRoster.recordSessions`,
   `BotYieldCaps::flush`, `BotBonds::flushAtShutdown`.
5. `2189` `for (World w : getWorlds()) w.shutdown()` → each `Channel.shutdown()`
   (src/main/java/net/server/channel/Channel.java:188-214: close merchants, disconnect away players, `players::disconnectAll`,
   dispose event scripts and maps, stop schedules, stop the Netty server), then the world cancels its own timers and runs
   `players.disconnectAll()` (World.java:2271).
6. `2211-2220` `for (Channel ch : allChannels) while (!ch.finishedShutdown()) Thread.sleep(1000)`.
7. `2224` `EconomySnapshotService.shutdown()`, `2225` `TradeRatings.shutdown()`, `2229`
   `DatabaseConnection.closeSnapshotConnections()` (ticket 174: the roster/store snapshot connections are not Hikari's).
8. `2231` `resetServerWorlds()`; `2233-2235` `ThreadManager.getInstance().stop()`,
   `TimerManager.getInstance().purge()`, `TimerManager.getInstance().stop()`.
9. `2237` log `"Worlds and channels are offline."`; `2240` `loginServer.stop()`.
10. `2243-2248` when `!restart`: `LogManager.shutdown()` then `new Thread(() -> System.exit(0)).start()` (a thread, because
    calling `System.exit` inside a shutdown-hook body deadlocks — comment at 2243). When `restart`: `instance = null` and
    `getInstance().init()` (2250-2252), an in-process reboot that reuses the same JVM.

Budgets: `ShutdownSaves.PLAYER_BUDGET_MS` and `BOT_BUDGET_MS` default to 60 s each, `PLAYER_SAVERS = BOT_WRITERS = 4`,
`BOT_GRACE_MS = 5_000` (src/main/java/soloMapling/server/ShutdownSaves.java:29-44).

## Data

| Data | Where |
|---|---|
| `config.yaml` | read once by the `YamlConfig.config` static initializer (src/main/java/config/YamlConfig.java:15) — see `config.md` |
| `worlds` / `worldsById` / `channels` | `Server` fields, src/main/java/net/server/Server.java:118-122; filled by `registerWorld` (501-517), cleared by `resetServerWorlds` (585) |
| `connectedClients` | `Map<String,Client>` keyed by account name, src/main/java/net/server/Server.java:214-226 |
| `online` / `serverCurrentTime` | Server.java:150-153, `isOnline()` 186-187, `updateCurrentTime()` 166-171 |
| `StrippedYamlConfig` | second, lenient read of the same file to pick the charset, src/main/java/constants/string/CharsetConstants.java:63-90 |
| Timers | `TimerManager` singleton, src/main/java/server/TimerManager.java:41-42, thread pool 4 threads named `TimerManager-Worker-N` (63-79) |
| Worker pool | `ThreadManager` singleton, src/main/java/server/ThreadManager.java:55 |
| Services per channel | `ServicesManager`, src/main/java/net/server/services/ServicesManager.java:29-47 |
| Log4j config | src/main/resources/log4j2.xml (not read for this review; named only) |

## Switches

Config keys come from src/main/java/config/ServerConfig.java; `-D` names are read at the point of use. Live values are from
docs/release/1.6.4.conf (`SERVER_SET` at :201, `UNIT_EDITS` at :271). TEST's `start-test.sh` lives on another host and was
not read, so every "TEST" cell is unknown.

| Switch | Type | Default | What it changes | Live |
|---|---|---|---|---|
| `SHUTDOWNHOOK` | config (`ServerConfig.java:95`) | `false` (no initialiser) | whether `init()` registers `shutdown(false)` as a JVM hook (Server.java:931) | `config.example.yaml:105` says `true`; the live key is not in `SERVER_SET`, so unknown |
| `TIMEZONE` | config (`ServerConfig.java:399`) | `null` → `TimeZone.getTimeZone(null)` throws `NullPointerException` inside `init()` | the JVM default zone, set before the pool (Server.java:938) | not in `SERVER_SET`; live config.yaml not readable here |
| `WORLDS`, `WLDLIST_SIZE` | config (`ServerConfig.java:21-22`) | `0` each | how many `worlds:` entries start (978), and the hard slot ceiling (459) | not in `SERVER_SET` — live keeps whatever its `config.yaml` says |
| `CHANNEL_SIZE`, `CHANNEL_LOAD`, `CHANNEL_LOCKS` | config (`ServerConfig.java:23-25`) | `0` each | per-channel player capacity / soft load / lock count; `CHANNEL_LOAD` is rejected by `SERVER_SET` (`tools/release/gen-deploy:63`) and set by `set-release-config --channel-load` instead | `docs/release/1.6.4.conf:277-278` `CHANNELS=5`, `CHANNEL_LOAD=125` |
| `UPDATE_INTERVAL`, `PURGING_INTERVAL`, `RESPAWN_INTERVAL`, `COUPON_INTERVAL`, `RANKING_INTERVAL` | config (`ServerConfig.java:27-31`) | `0` each | the timer periods in `## How it works` (1084-1097) and `serverCurrentTime` stepping (175). A 0 here passes straight into `TimerManager.register` and throws | not in `SERVER_SET`; `config.example.yaml:62-66` documents 10000/300000/3600000/3600000/777 |
| `USE_FAMILY_SYSTEM` | config (`ServerConfig.java:309`) | `false` | family load at boot (993-1000) and the daily reset task (World.java:216-220) | `SERVER_SET` (1.6.4.conf:206) sets `true` |
| `USE_AUTOSAVE` | config (`ServerConfig.java:327`) | `false` | `CharacterAutosaverTask` and `Character.save` (src/main/java/net/server/task/CharacterAutosaverTask.java:43, src/main/java/client/Character.java:9873) | unknown |
| `MIN_CLIENT_BUILD`, `MIN_CLIENT_BUILD_GM_BYPASS` | config (`ServerConfig.java:61,64`) | `0`, `false` | login refused for older clients (`ClientVersionGate`, src/main/java/net/server/handlers/login/ClientVersionGate.java:18-28) | `docs/release/1.6.4.conf:284` `MIN_CLIENT_BUILD=10740` |
| `-Dmapleroot.login-port` | property | `8485` | login bind port (Server.java:1018) | not in `UNIT_EDITS`; unit uses the default |
| `-Dmapleroot.channel-base-port` | property | `7585` | channel port base (Channel.java:64, 67-69) | not in `UNIT_EDITS` |
| `-Dcashshop.window.enabled` | property | `false` | loads the cash-shop catalog at boot (Server.java:1036) | `-Dcashshop.window.enabled=true` (deploy/slumsms.service.example:11) |
| `-Dweather.enabled` | property | `false` | registers `WeatherTask` per world (World.java:200) | `-Dweather.enabled=true` (deploy/slumsms.service.example:11) |
| `-Dsolomapling.enabled` + `-Dsolomapling.template-id` | properties | `false`, `0` | whether `MapleRootSolo.start()` does anything (MapleRootSolo.java:16-25) | `UNIT_EDITS` does not enable it; the design/list flags keep the unit at `false` |
| `-Dsolomapling.autopopulate` | property | `false` | schedules the seed waves 1 s after boot (MapleRootSolo.java:52-53) | live seeds through this path only if set; `deploy/slumsms.service.example:11` ships it `false` |
| `-Dslums.shutdown.playerBudgetMs` / `.botBudgetMs` | properties | `60000` / `60000` | the two shutdown phases' deadlines (ShutdownSaves.java:29,32) | not in `UNIT_EDITS` |
| `-Dslums.mechanics`, `-Dslums.mechanics.lobby` | properties | `false` | `KpqLobby.startIfEnabled()` (Server.java:1048; src/main/java/soloMapling/mechanics/KpqLobby.java:59,67-71) | both `true` (1.6.4.conf:271) |
| `-Dslums.mechanics.<row>.lobby` | property, name from data | unset | `ContentLobby` rows (src/main/java/soloMapling/mechanics/ContentLobby.java:75,81) | `.lobby` off for live per /root/work/OWNER-DECISIONS.md:118 |
| `-Dslums.market.export`, `-Dslums.site.export`, `-Dslums.board`, `-Dslums.exchange`, `-Dsolomapling.dashboard` | properties | `false` | the optional exporters/APIs at Server.java:1038-1047 (constants at PublicMarketExport.java:70, PublicSiteExport.java:58, SiteBoard.java:54, DashboardExporter.java:36) | `market.export`/`site.export`/`board` `true` (1.6.4.conf:271 region + 1.6.0 flags at :229); exchange `true` with `startHalted=true` |
| `-Dslums.auth.port` (+ `slums.auth.secretFile`) | properties | unset | starts `SiteAuthApi` (src/main/java/soloMapling/site/auth/SiteAuthApi.java:106-118) | already on live per 1.6.4.conf:229 comment (path only, not quoted here) |
| `-Dslums.inspect.port` | property | unset | starts `BotInspectApi` (src/main/java/soloMapling/inspect/BotInspectApi.java:61-63,105) | unknown |
| `-Dwz-path` | property | `"wz"` | location of the WZ tree (src/main/java/provider/wz/WZFiles.java:39-46) | not in `UNIT_EDITS`; the unit runs from the staged directory |

Unknown `-D` names are silently ignored — nothing validates the property namespace, unlike `SERVER_SET`, which `gen-deploy`
checks key by key against `ServerConfig.java` (`tools/release/gen-deploy:55-70`).

## Invariants

1. **Nothing listens before the pool and the config are good.** The order at Server.java:940 → 957 → 1019 means a bad DB
   or a bad `worlds:` entry aborts with no socket bound and no rows written.
2. **`TimeZone.setDefault` precedes every connection** (Server.java:938, comment 935-937); moving it after
   `initializeConnectionPool()` reintroduces the "already logged in" login bug it was added for.
3. **`WORLDS` caps, the list supplies.** `worldCount = min(list.size(), WORLDS)` (978) — a longer list is truncated with a
   warning; a shorter list is not an error.
4. **World ids are validated before any bind** (957), so `getWorld(id)` can be assumed to resolve for every started world.
5. **A world's slot is its start order**, and `registerWorld` refuses a slot/id mismatch (501-517); `channels` and
   `worldsById` are keyed by id, `worlds` is ordered by slot.
6. **Channel ports are a pure function of base port, world slot and channel number** (Channel.java:67-69); two worlds on
   the same slot would collide, which `registerWorld` prevents.
7. **One writer per character at a time.** `World.beginShutdown()` cancels the autosave before any final save
   (World.java:2192-2198; test src/test/java/net/server/task/AutosaveDuringShutdownTest.java:19-22).
8. **Bots are frozen before they are swept.** `BotShutdown.begin()` then `BotTickService.stop()` before
   `BotStore.settleAndSaveAll` (Server.java:2090-2097), so the sweep sees a population nothing is mutating.
9. **Shutdown is idempotent twice over:** `World.beginShutdown` is documented idempotent (World.java:2192-2198), and
   `shutdownInternal` returns early when `getWorlds() == null` (2129).
10. **`online` flips only after the login server binds** (1023 after 1019), and `Client.channelActive` enforces it
    (Client.java:180).

## Origin

| Path | Origin | Evidence |
|---|---|---|
| src/main/java/net/server/Server.java | modified | oldest commit `03d13cb21` (2026-10-18, repack baseline); `diff -q` differs from /root/workspace/cosmicms |
| src/main/java/config/YamlConfig.java | base | oldest `03d13cb21`; identical to `/root/workspace/cosmicms/src/main/java/config/YamlConfig.java` |
| src/main/java/config/ServerConfig.java | modified | oldest `03d13cb21`; differs from upstream |
| src/main/java/config/WorldConfig.java | modified | `03d13cb21`, then `59330e4fe` (2026-10-20 "Let a world name its id in config.yaml and run Bera alone"); differs from upstream |
| src/main/java/config/WorldConfigs.java | custom | only commit is `59330e4fe`; absent upstream |
| src/main/java/net/server/services/ServicesManager.java | base | `03d13cb21`; identical to upstream |
| src/main/java/server/TimerManager.java | modified | `03d13cb21`; differs from upstream (thread naming, `removeOnCancelPolicy`, `purge()`) |
| src/main/java/server/ThreadManager.java | modified | `03d13cb21`; differs from upstream (rejection handler spawning a bare thread) |
| src/main/java/client/Client.java | modified | oldest `03d13cb21`; differs from upstream |
| config.example.yaml | custom | `03d13cb21` in `--follow` output is the repack's own initial import; file is 537 lines and absent upstream; `docs/tickets/273-config-template-gap.md` documents it as the 1.6.4 rewrite |
| src/main/java/soloMapling/server/BotTickService.java, src/main/java/soloMapling/server/ShutdownSaves.java, src/main/java/soloMapling/server/BotShutdown.java | custom | `soloMapling/` is absent upstream; ticket 09 and commit `4d08a0cba` for the shutdown split |

`tools/cosmicdiff/classifications.json` has no entry for any path in this subsystem (grep for "boot"/"shutdown" returned
nothing), so no classification can be quoted as evidence.

## Tests and QA

| Test | Covers |
|---|---|
| src/test/java/net/server/ServerWorldRegistryTest.java:24-70 | `registerWorld` slot/id rules, Bera-alone as world 1, home world selection (ticket 41). Uses `Server.getInstance()` + `resetServerWorlds()` in `@BeforeEach`/`@AfterEach`, so it does exercise the real registry, not a double |
| src/test/java/config/WorldConfigsTest.java:20-66 | `WorldConfigs.validate` — positional ids, duplicate id, unknown id |
| src/test/java/net/server/task/AutosaveDuringShutdownTest.java | a world that is shutting down is not autosaved (1.5.2, ticket 80) |
| src/test/java/net/server/channel/ChannelBroadcastAfterShutdownTest.java | a channel whose `players` is null after shutdown does not NPE on a GM broadcast (1.5.2) |
| src/test/java/net/server/PlayerStorageDisconnectAllTest.java | `PlayerStorage.disconnectAll` survives one failing character (1.5.2) |
| src/test/java/config/OwnedAltSwitchLineTest.java | the AL-4 boot line names every owned-alt switch and the shipped conf sets the master one |
| src/test/java/client/LogoutWithoutWorldTest.java | logout when the world is already gone |

Auto-QA: `src/main/java/soloMapling/autoqa/AutoQaCatalog.java` has **no** boot- or shutdown-specific check id (the only
"shutdown"/"boot" hits are unrelated comments, e.g. AutoQaCatalog.java:847, :1235, :1461-1462). `!qa` scenarios under
src/main/java/soloMapling/qa/ contain no boot or shutdown scenario.

Not covered, explicitly: nothing starts the real `init()` in a test (no test binds a port or needs MySQL); no test asserts
the abort paths (bad DB, bad world id via `Server.init`, `System.exit(0)`); no test asserts the shutdown ordering
(players-then-bots, `beginShutdown` before the sweep) at the `shutdownInternal` level; ticket 62's own acceptance asked for
"an integration test [that] models separate login and channel connections", and none exists
(docs/tickets/62-shutdown-active-player-saves.md).

## Known issues

| Id | One line | Source |
|---|---|---|
| L06 | Owned alts: failed save no longer locks an alt out; "clearer gate and boot lines" — the boot line landed (`ownedAltSwitchesLine`, Server.java:1031), the gate half is listed as needing a check | /root/work/STATUS-1.6.4.md:97 |
| e08 | Ramp-shaped bot boot fill is wanted for 1.6.4 but needs code; until then the boot fill is flat (`BOT_DIRECTOR_BOOT_FILL_PER_SECOND`, ServerConfig.java:149) | /root/work/STATUS-1.6.4.md:118 |
| 62 | `shutdownInternal` builds its player worklist from `connectedClients` (Server.java:2146), and `registerClient` is called only from the login handler (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:226); channel clients are never registered, so the "save the real players first" phase iterates login sessions. Ticket marked closed 2026-10-25 in 4d08a0cba, but that commit's Server.java hunk swaps `for (Client client : connectedClients.values())` for `new ArrayList<>(connectedClients.values())` — same source | docs/tickets/62-shutdown-active-player-saves.md |
| NEW-FINDING: src/main/java/net/server/Server.java:1003-1006 | The world-start catch logs `"[SEVERE] Syntax error in 'world.ini'."` and calls `System.exit(0)`. There is no `world.ini` in this repo (worlds are `config.yaml`), and exit code 0 tells systemd the run succeeded, so a world that fails to start is indistinguishable from a clean stop | own reading |
| NEW-FINDING: src/main/java/net/server/Server.java:2192-2208 | A commented-out shutdown block is left in the method, including `while (ch.getConnectedClients() > 0)` and a `System.err.println("FUCK MY LIFE")`. `getConnectedClients()` exists nowhere at HEAD (the only occurrence is in that comment), so anyone reviving the block gets a compile error, and a profanity string sits in a shipped source file | own reading |
| NEW-FINDING: src/main/java/net/server/channel/Channel.java:167-169 | A channel constructor failure is swallowed with `log.warn`; the server reports "now online" with a missing channel and a port that is not listening, with no boot-failure signal | own reading |
| NEW-FINDING: src/main/java/net/server/Server.java:1025 | The online message still reads `"Cosmic is now online after {} ms."` while the boot banner says SlumsMS — the release-parity check in `docs/release/1.6.4.conf:295` greps for `"Starting world 0"` in `FORBID_LOG` and `"SoloMapling initialized"` in `EXPECT_LOG`, but nothing anchors on this line | own reading |
| NEW-FINDING: src/main/java/net/server/Server.java:1103-1129 | The JQ-door broadcaster is a raw `new Thread` with no reference kept, so shutdown cannot interrupt it; it survives into the restart path (`instance = null; getInstance().init()`, Server.java:2250-2252) and a second copy starts | own reading |

## State

**partial.** Verified by reading, not by running: the cold path exists end to end with ordered aborts (Server.java:940,
957, 1019) and the shutdown path has budgeted, ordered phases (2127-2252) with tests for the autosave/shutdown exclusion
and the null-storage channel (AutosaveDuringShutdownTest, ChannelBroadcastAfterShutdownTest). It is not "working" because
three things are not evidenced: (a) ticket 62's registry defect is unfixed at HEAD (the shutdown player phase sweeps login
clients), (b) no test drives `init()` or the abort paths, so the boot sequence is unverified as a whole, and (c) the
`System.exit(0)` swallow (1003-1006) and the swallowed channel-constructor exception (Channel.java:167-169) mean a partial
boot reports success. No deploy, restart or rehearsal was run for this review.

## Open questions

- Does live's `config.yaml` set `SHUTDOWNHOOK`, `TIMEZONE`, `WORLDS` and `WLDLIST_SIZE`? None of them is in `SERVER_SET`
  (docs/release/1.6.4.conf:201-214), `CONFIG_APPEND` is empty (:188), and the live file is not readable from this host —
  `config.yaml` is git-ignored (.gitignore:7) and absent from this worktree.
- What TEST's `start-test.sh` passes: the unit's flags, a different `-D` set, or a different `mapleroot.login-port`?
  The file lives on mapletest and was out of reach.
- Is the JQ-door `new Thread` loop (1103-1129) intentional or a leftover? It has no shutdown path and no `-D` gate.
- Did anyone run the restart path (`restart=true`, Server.java:2250-2252) since `soloMapling` bot code was added? A
  restart leaves the door thread, `BotTickService`'s static `ENTRIES` (cleared only by `stop()`) and any static caches to
  be re-entered by a second `init()`, and no test covers it.
