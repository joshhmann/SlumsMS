# Config

## Purpose

Turn one `config.yaml` file at the process working directory into `YamlConfig.config`, a plain holder of a
`ServerConfig` (359 public fields) and a `List<WorldConfig>` (13 fields each). Everything the server lets an operator change
without a build goes here: database credentials, world and channel shape, timer periods, feature `USE_*` flags, bot
population numbers, and the 1.6.x gameplay knobs. Everything else that looks like a switch lives in a `-D` property or an
environment variable and is read where it is used, not here.

## Entry points

| Entry | Where | What it does |
|---|---|---|
| Static load | src/main/java/config/YamlConfig.java:15 | `public static final YamlConfig config = loadConfig()` — the whole file loads at class-init time, so the first touch of any `YamlConfig.config.*` triggers the read and can throw `ExceptionInInitializerError` |
| Loader | src/main/java/config/YamlConfig.java:20-31 | `loadConfig()` |
| File name | src/main/java/config/YamlConfig.java:14 | `CONFIG_FILE_NAME = "config.yaml"`, resolved relative to the JVM's working directory (`Path.of`, line 22) |
| Second, lenient read | src/main/java/constants/string/CharsetConstants.java:63-75 | `loadCharsetFromConfig()` reads the same file into `StrippedYamlConfig` (87-92) with `setIgnoreUnknownProperties(true)` (65) just to get `server.CHARSET` |
| Charset decision | src/main/java/constants/string/CharsetConstants.java:32, 77-85 | `CHARSET = loadCharset()` — that charset is then used for the strict read (YamlConfig.java:22) |
| World id validation | src/main/java/config/WorldConfigs.java:55-81 | `validate(entries, nameCount)`, called from src/main/java/net/server/Server.java:957 |
| World lookup | src/main/java/config/WorldConfigs.java:23-49 | `idOf`, `ids`, `forWorld` |
| Template | config.example.yaml:1-537 | the complete, bootable template; README.md:10 tells operators to copy it |
| Live values | docs/release/1.6.4.conf:201 (numeric/boolean keys), :271 (`-D` flags) | what the release actually sets |

## How it works

1. **Charset first (bootstrapping problem).** `YamlConfig.loadConfig()` needs a `Charset` to open the file, and the charset
   comes from the file. `CharsetConstants.CHARSET` (line 32) breaks the cycle: `loadCharsetFromConfig()` opens `config.yaml`
   as `US_ASCII` (65), turns on `reader.getConfig().readConfig.setIgnoreUnknownProperties(true)` (65) and reads into a
   two-field `StrippedYamlConfig` (67, class at 87-92). Any charset in the file beyond US-ASCII is therefore invisible to
   this pass, which is fine because `CHARSET` is one of the four names in the `Language` enum (35-45); an unknown value logs
   `"Charset {} was not found, defaulting to US-ASCII"` and falls back (47-53).
2. **Strict read.** `loadConfig()` (YamlConfig.java:20-31) opens the file with `CharsetConstants.CHARSET` (22), calls
   `reader.read(YamlConfig.class)` (23) and closes it (24).
3. **Unknown key aborts boot.** `YamlConfig` never calls `setIgnoreUnknownProperties`. yamlbeans 1.17's `ReadConfig`
   constructor sets only `autoMerge`/`classTags`/`anchors`, so `ignoreUnknownProperties` stays at its `false` default; an
   unknown key throws `YamlReader$YamlReaderException` with `"Unable to find property '<name>' on class:<type>"`, and
   `YamlException extends java.io.IOException`, so `catch (IOException e)` at YamlConfig.java:28 catches it and rethrows
   `RuntimeException("Could not successfully parse config file config.yaml: ...")` (29). From the still-initializing static
   field at YamlConfig.java:15 the JVM wraps that as `ExceptionInInitializerError`. AGENTS.md:110 states the rule
   ("**Config is strict:** an unknown key in `config.yaml` aborts boot"). Consequence: a key may only be added to a server
   that already runs a build declaring the field.
4. **Missing key does NOT abort.** There is no per-key defaulting either: an absent key leaves the field at its JVM default,
   `0`, `false` or `null` for every declaration without an inline initialiser. config.example.yaml:8-13 states this and names
   the measured consequence (`ITEM_EXPIRE_CHECK` at 0 makes `TimerManager.register` throw inside `MapleMap.addPlayer`, which
   aborts the rest of that login).
5. **File missing.** `FileNotFoundException` → `RuntimeException("Could not read config file config.yaml: ...")`
   (YamlConfig.java:26-27). Nothing is caught at the call site; `main` does not wrap `init()`
   (src/main/java/net/server/Server.java:1156-1159), so the process dies with a stack trace.
6. **Top-level shape.** `YamlConfig` declares exactly two fields (17-18): `List<WorldConfig> worlds` and
   `ServerConfig server`. Both are wiped out by re-assignment during `read(...)`, so a `server:` block in the file replaces
   the object; a file without `server:` leaves `YamlConfig.config.server == null` and the first `config.server.X` dereference
   in `init()` NPEs (Server.java:931 is the earliest one).
7. **World entries.** yamlbeans binds each `worlds:` list item to `WorldConfig` (src/main/java/config/WorldConfig.java:3-18):
   `id = -1`, `flag = 0`, `server_message = "Welcome!"`, `event_message = ""`, `why_am_i_recommended = ""`, `channels = 1`,
   `exp_rate = 1`, `meso_rate = 1`, `drop_rate = 1`, `boss_drop_rate = 1`, `quest_rate = 1`, `travel_rate = 1`,
   `fishing_rate = 1`. `id = -1` means "my position in the list" (doc comment at WorldConfig.java:4).
8. **Validation.** `Server.init()` calls `WorldConfigs.validate(YamlConfig.config.worlds, GameConstants.WORLD_NAMES.length)`
   at Server.java:957, before any bind. `validate` (WorldConfigs.java:55-81) throws `IllegalStateException` for an absent or
   empty list (57-59), a null entry (63-64), `id < -1` (65-68), `id >= nameCount` (70-72), and a duplicate id (75-78), each
   message naming the offending entry index and the legal range. `nameCount` is 21 (`GameConstants.WORLD_NAMES`,
   src/main/java/constants/game/GameConstants.java:23, Scania..Medere).
9. **Id, not position, is the key.** `idOf(index, entry)` returns `entry.id >= 0 ? entry.id : index` (WorldConfigs.java:23-25);
   `Server` stores worlds in a slot-ordered list *and* a by-id map (Server.java:118-122, `registerWorld` 501-517), so a config
   with one entry `id: 1` runs Bera with no world 0 (ticket 41, docs/tickets/41-world-name-bera.md).
10. **Consumption.** Order matters only in that `init()` reads `SHUTDOWNHOOK` before the pool (931), `TIMEZONE` before the
    pool (938), `WORLDS`/`worlds` at world start (978), and the timer periods in `initializeTimelyTasks` (1081-1102) and
    `World`'s constructor (src/main/java/net/server/world/World.java:199-220). Every other field is read lazily at the site
    that needs it, e.g. `USE_AUTOSAVE` at src/main/java/net/server/task/CharacterAutosaverTask.java:43.

## Data

| Field group (by `ServerConfig` prefix / section comment) | Lines | Examples with defaults |
|---|---|---|
| Database (`DB_*`, `INIT_CONNECTION_POOL_TIMEOUT`) — comment at 8 | 9-18 | `DB_URL_FORMAT`, `DB_HOST`, `DB_USER`, `DB_PASS` (no initialisers → `null`); `INIT_CONNECTION_POOL_TIMEOUT` = 0; `DB_CONNECTION_TIMEOUT_MS = 30000` (18) |
| Login (`WORLDS`, `WLDLIST_SIZE`, `CHANNEL_*`, timer periods) — 20 | 21-31 | all 0: `WORLDS` (21), `WLDLIST_SIZE` (22), `CHANNEL_SIZE` (23), `CHANNEL_LOAD` (24), `CHANNEL_LOCKS` (25), `RESPAWN_INTERVAL` (27), `PURGING_INTERVAL` (28), `RANKING_INTERVAL` (29), `COUPON_INTERVAL` (30), `UPDATE_INTERVAL` (31) |
| Auth / client gates — 40, 45, 47, 50, 56, 58, 63, 65 | 33-75 | `AUTO_REGISTER_MIN_PASSWORD_LENGTH = 6` (42), `AUTO_REGISTER_MAX_ACCOUNTS_PER_IP = 0` (43), `AUTO_REGISTER_IP_COOLDOWN_MINUTES = 1` (44), `DAILY_CHECKIN_ENABLED = false` (46), `TUTORIAL_ISLAND_PROMPT = true` (49), `DAMAGE_RANK_ENABLED = false` (52), `DAMAGE_RANK_MIN_BUILD = 10690` (53), `DAMAGE_RANK_MIN_MOB_HP = 5000000` (54), `DAILY_CHECKIN_MIN_LEVEL = 10` (55), `COLOR_PRISM_ENABLED = false` (57), `MIN_CLIENT_BUILD = 0` (61), `CLIENT_UPDATE_MESSAGE = "Your game is out of date: open the SlumsMS launcher to update."` (62), `MIN_CLIENT_BUILD_GM_BYPASS = false` (64), `CUSTOM_NPC_IDS = "9990000,2985-2999"` (69), `CUSTOM_NPC_MIN_BUILD = 10610` (70) |
| Multiclient / per-IP gates — 77, 81, 84 | 78-87 | `MAX_ALLOWED_ACCOUNT_HWID`, `MAX_ACCOUNT_LOGIN_ATTEMPT`, `LOGIN_ATTEMPT_DURATION` (no initialisers), `LOGIN_MAX_SESSIONS_PER_IP = 8` (83), `LOGIN_MAX_CONNECTS_PER_IP = 30` (85), `LOGIN_CONNECT_WINDOW_SECONDS = 60` (86) |
| IP — 88 | 89-92 | `HOST`, `LANHOST`, `LOCALHOST`, `GMSERVER` (all `null`) |
| Other — 94 | 95-98 | `SHUTDOWNHOOK` (95, dead default `false`), `SPAWN_BOTS_ON_STARTUP` (98, **nothing reads it**) |
| Bots: director — 100 | 101-151 | `BOT_DIRECTOR_ENABLED = true` (101), `BOT_ROSTER_PEAK_ONLINE = 1200` (139), `BOT_ROSTER_CURVE` (140), `BOT_ROSTER_WEEKEND_LIFT = 1.1` (141), `BOT_ROSTER_TIMEZONE = ""` (142), `BOT_DIRECTOR_TICK_SECONDS = 30` (143), `BOT_DIRECTOR_MAX_LOGINS_PER_TICK = 25` (144), `BOT_DIRECTOR_BOOT_FILL_IN_FLIGHT = 8` (147), `BOT_DIRECTOR_BOOT_FILL_PER_SECOND = 8` (149), `BOT_DIRECTOR_MAX_LOGOUTS_PER_TICK = 25` (150), `BOT_ROSTER_SEED_MIN = 100` (151) |
| Bots: owned alts / companions — 102-119 | 103-120 | `OWNED_ALTS_ENABLED = false` (103), `OWNED_ALTS_SUMMON_ENABLED = true` (107), `COMPANION_ORDERS/TRADES/UPGRADES/SUPPLIES_ENABLED = false` (111-119), `OWNED_ALTS_INVITE_SUMMON_ENABLED = false`; boot line composed at 126-134 |
| Bots: population / towns — 135-203 | 137-203 | `MAP_ENTRY_TRICKLE = true` (137), `BOT_POPULATION_PEAK = 1600` (154), `BOT_ENVIRONMENT_SCALE = 0.5` (157), `BOT_FM_ENVIRONMENT_SCALE = 1.0` (160), `BOT_TOWN_DRESSING_SCALE = -1` (165), `BOT_LIFE_TOWN_WEIGHTS` (168), `BOT_TOWN_PULL = 0` (171), `BOT_TOWN_BUSY_TARGET = ""` (174), `BOT_TOWN_CHANNEL_WEIGHTS` (177), `BOT_TOWN_PLAYER_BOOST = 1.5` (179), `BOT_TOWN_MAP_MAX = 200` (183), `BOT_TOWN_PULL_PER_MIN = 3` (185), `BOT_TOWN_SHARE_MAX = 0.45` (187), `BOT_CHANNEL_WEIGHTS = "100,55,35,20,15"` (192), `BOT_MAP_CAP = 50` (197), `BOT_MAP_CAP_OVERRIDES = ""` (199), `WORLD_EVENT_MAP_MAX = 200` (203) |
| Auto-QA — 204 | 206-213 | `AUTOQA_ENABLED = false` (206), `AUTOQA_SWEEP_MINUTES = 5` (207), `AUTOQA_SWEEP_MAX_BOTS = 3000` (208), `AUTOQA_STUCK_MINUTES = 3` (209), `AUTOQA_CLUMP_RADIUS = 30` (210), `AUTOQA_CLUMP_MIN_BOTS = 5` (211), `AUTOQA_NIGHTLY_UTC_HOUR = 10` (212), `AUTOQA_JUDGE_MAX_CALLS = 40` (213) |
| Bots: scale / roster lifecycle — 214, 216 | 215-222 | `BOT_POPULATION_SCALE = 1.0` (215), `BOT_ROSTER_SIZE_MULTIPLE = 3.0` (217), `BOT_ROSTER_NEW_PER_DAY = 40` (218), `BOT_ROSTER_VETERAN_FILL_BELOW = 0.5` (219), `BOT_ROSTER_VETERANS_PER_DAY = 300` (220), `BOT_ROSTER_QUIT_PER_DAY = 0.002` (221), `BOT_ROSTER_RETURN_PER_DAY = 0.01` (222) |
| Bots: local LLM — 224 | 226-236 | `BOT_LLM_*` (URL/model/timeouts), plus `Map<String,String>` `BOT_LLM_EXTRA_BODY` (219) and `BOT_LLM_EXTRA_HEADERS` (220) |
| Bots: cloud LLM — 237 | 240-262 | `BOT_LLM_CLOUD_TIMEOUT... = 6000`, `BOT_LLM_CLOUD_DAILY_BUDGET_USD = 1.0`, price fields (0.30 / -1 / 1.20), `MODE = "overflow"`, `BOT_LLM_LOCAL_SLOW_MS = 2500` |
| Bots: combat feel — 263 | 264-278 | `BOT_COMBAT_HUMAN = true`, `AOE_MIN_MOBS = 3`, `SLOPPINESS = 1.0`, `DEATHS = true`, `KS_MANNERS = true`, `SPREAD = true`, `MENU_WINDOW = true` |
| Bots: decide endpoint — 280 | 281-290 | `BOT_DECIDE_URL = ""`, `BOT_DECIDE_TONE_URL = ""`, `BOT_DECIDE_MODEL = "decide"`, `BOT_DECIDE_TONE_MODEL = "decide"`, `BOT_DECIDE_TIMEOUT_MS = 600`, `BOT_DECIDE_ASK = 0.55` |
| Server Flags (`USE_*`) — 291 | 292-356 | 55+ booleans with no initialiser, i.e. `false`, except `USE_DUEY = true` (316); `GODLY_DROP_*` 312-315 |
| Events/PQs — 358 | 359-363 | `USE_OLD_GMS_STYLED_PQ_NPCS`, `USE_ENABLE_SOLO_EXPEDITIONS`, `USE_ENABLE_DAILY_EXPEDITIONS`, `USE_ENABLE_RECALL_EVENT` |
| Announcement / Cash Shop / Maker / Custom / Commands — 364, 369, 375, 381, 385 | 365-397 | `USE_ANNOUNCE_*` (365-367), `USE_JOINT_CASHSHOP_INVENTORY` (370), `USE_CLEAR_OUTDATED_COUPONS` (371), `ALLOW_CASHSHOP_NAME_CHANGE` (372); EXP rates 389-396 (`EQUIP_EXP_RATE`, `PQ_BONUS_EXP_RATE`, `EXP_SPLIT_LEVEL_INTERVAL`, `LEECH`, `MVP_EXP_MOD`, `COMMON_EXP_MOD`, `PARTY_BONUS_EXP_RATE`) |
| Miscellaneous — 398 | 399-419 | `TIMEZONE` (399), `CHARSET` (400), `MAX_MONITORED_BUFFSTATS` (403), `MAX_AP` (404), `MAX_EVENT_LEVELS` (405), `BLOCK_NPC_RACE_CONDT` (406), `TOT_MOB_QUEST_REQUIREMENT` (407), `MOB_REACTOR_REFRESH_TIME` (408), `PARTY_SEARCH_REENTRY_LIMIT = 10` (414, ticket 164), `NAME_CHANGE_COOLDOWN` (415), `WORLD_TRANSFER_COOLDOWN = NAME_CHANGE_COOLDOWN` (416), `INSTANT_NAME_CHANGE` (417), `REBIRTH_NPC_ID` (418) |
| Dangling items/locks — 420 | 421-431 | `ITEM_EXPIRE_TIME`, `KITE_EXPIRE_TIME`, `ITEM_MONITOR_TIME`, `LOCK_MONITOR_TIME`, `ITEM_EXPIRE_CHECK`, `ITEM_LIMIT_ON_MAP`, `MAP_VISITED_SIZE`, `MAP_DAMAGE_OVERTIME_INTERVAL`, `MAP_DAMAGE_OVERTIME_COUNT` — all `0` unless the file sets them |
| Map monitor — 426; Channel mob disease — 433 | 434-438 | `MOB_STATUS_MONITOR_PROC`, `MOB_STATUS_MONITOR_LIFE`, `MOB_STATUS_MONITOR_*` |
| Scroll / skills / character / quest / guild / family / equipment / chair / PlayerNPC / pet / event / dojo / wedding / buyback / event end / GM security / custom NPCs | 441-598 | `SCROLL_CHANCE_ROLLS`, `CHSCROLL_STAT_RATE/RANGE` (442-449), `MINIMUM_GM_LEVEL_TO_TRADE/USE_STORAGE/USE_DUEY/DROP = 4` (583-586), `NPCS_SCRIPTABLE` `Map` (589), `EXPEDITION_HWID_LIMIT` (592), `USE_THREAD_TRACKER` (598), `STARFORCE_*`, `WEDDING_RESERVATION_*` |

Counts on this branch: `grep -cE '^\s+(public|private|protected)\s' src/main/java/config/ServerConfig.java` = 360 lines
(359 fields + the `Map` field whose declaration the regex splits differently); config.example.yaml carries **359** `server:`
keys, one per field (`ConfigTemplateCompletenessTest.everyServerFieldAppearsInTheTemplate`,
src/test/java/config/ConfigTemplateCompletenessTest.java:68-75).

The four doc-level facts worth carrying:

| Fact | Evidence |
|---|---|
| A missing key silently keeps `0`/`false`/`null`; only an **unknown** key aborts | YamlConfig.java:20-31 (no defaulting), config.example.yaml:8-13, AGENTS.md:110 |
| 13 fields are named where a `0` was measured to break a read site | src/test/java/config/ConfigTemplateCompletenessTest.java:39-54 |
| World ids are validated (duplicates, range) but `channels = 0` is not — it produces a world with no channels and no message | WorldConfigs.java:55-81 validates ids only; `for (int j = 1; j <= wc.channels; j++)` at Server.java:475 |
| `SPAWN_BOTS_ON_STARTUP` is declared and documented but never read | only occurrence in src/ is src/main/java/config/ServerConfig.java:98; still listed at config.example.yaml:108 and described as unused in docs/release/1.3.0.md:156 |

## Switches

### The three homes

| Home | Example | Read by | Failure mode |
|---|---|---|---|
| `config.yaml` key → field of `ServerConfig` / `WorldConfig` | `USE_FAMILY_SYSTEM: true` | `YamlConfig` static init (YamlConfig.java:15) | unknown key → abort at class init; missing key → silent `0`/`false`/`null` |
| `-D` system property | `-Dslums.mechanics=true` | `System.getProperty` / `Boolean.getBoolean` at the point of use | typo → silently `false`; nothing validates names |
| Environment variable / external file | `DB_HOST`, `SLUMSMS_REGISTRATION_WEBHOOK`, `SOLOMAPLING_LLM*_API_KEY`, secret files | `System.getenv` (7 call sites in src/main/java) | absent env → the documented fallback (e.g. `DB_HOST` absent = use `config.yaml`) |

### `-D` flags that matter, with defaults

| Flag | Default | Switches | Reader |
|---|---|---|---|
| `mapleroot.login-port` | `8485` (`Integer.getInteger`) | login bind port | src/main/java/net/server/Server.java:1018 |
| `mapleroot.channel-base-port` | `7585` | channel port base, `base + (ch-1) + slot*100` | src/main/java/net/server/channel/Channel.java:64, 67-69 |
| `wz-path` | `"wz"` | WZ data directory | src/main/java/provider/wz/WZFiles.java:40-45 |
| `cashshop.window.enabled` | `false` | load the cash-shop catalog at boot | Server.java:1036 |
| `weather.enabled` | `false` | per-world `WeatherTask` | World.java:200 |
| `solomapling.enabled` | `false` | bot system on at all (also needs `solomapling.template-id` > 0) | src/main/java/soloMapling/server/MapleRootSolo.java:16-25 |
| `solomapling.template-id` | `0` | the dedicated bot template; 0 makes `templateId()` throw if called | MapleRootSolo.java:19-25 |
| `solomapling.autopopulate` | `false` | run the seed waves / director at boot | MapleRootSolo.java:51-53 |
| `solomapling.dashboard` (+ `.dir`) | `false` / `reports/dashboard` | dashboard JSON exporter | src/main/java/soloMapling/dashboard/DashboardExporter.java:36-37, 55, 64 |
| `slums.market.export` (+ `.dir`) | `false` | website market files | src/main/java/soloMapling/Economy/market/PublicMarketExport.java:70-71, 95 |
| `slums.site.export` (+ `.dir`) | `false` | website feed/leaderboards | src/main/java/soloMapling/site/PublicSiteExport.java:58-59, 77 |
| `slums.site.mobKills.noDb` | `false` | skip the DB read for the mob-kill feed | PublicSiteExport.java:99, 120 |
| `slums.board` | `false` | site board + price alerts | src/main/java/soloMapling/Economy/board/SiteBoard.java:54, 79 |
| `slums.exchange` (+ `startHalted`, `houseAccount`, `traders`, `traders.pct`, tickers) | `false` | NX exchange and its traders | src/main/java/soloMapling/Economy/exchange/ExchangeService.java:53-60; `startHalted` at ExchangeService.java:93 |
| `slums.auth.port` (+ `slums.auth.secretFile`, `slums.auth.revoked`) | unset | sign-up/sign-in HTTP API on loopback | src/main/java/soloMapling/site/auth/SiteAuthApi.java:106-118 |
| `slums.inspect.port` (+ `slums.inspect.secretFile`, `slums.inspect.bind`) | unset | read-only bot inspect API | src/main/java/soloMapling/inspect/BotInspectApi.java:61-63, 105-115 |
| `slums.mechanics` (+ `.boss`, `.opq`, `.lpq`, `.hpq`, `.lobby`, `.chatter`) | off; `.chatter` is the one thing that defaults **on** | boss runs and party-quest automation | src/main/java/soloMapling/mechanics/Mechanics.java:20-44, 131-133, 196-201 |
| `slums.mechanics.<row>` and `slums.mechanics.<row>.lobby` | unset | one content row / its bot lobby | Mechanics.java:200, src/main/java/soloMapling/mechanics/ContentLobby.java:75, 81 |
| `slums.gear.optimizer` | `"climb"` | gear-scoring strategy (`pareto` on live) | src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGear.java:70 |
| `slums.gear.dexless`, `slums.junk.sort`, `slums.grind.gearFirst`, `slums.scroll.ev`, `slums.bonds`, `slums.crews`, `slums.bottalk`, `slums.achievements`, `slums.temple.enabled`, `slums.botquests.enabled`, `slums.chaos.zakum` | `false` | per-feature rollout switches | BotProgression.java:223, JunkSort.java:42, GearFirstAdvisor.java:42, BotScrolling.java:234, RelationView.java:31, src/main/java/soloMapling/autoqa/AutoQaCatalog.java:2220 |
| `slums.bots.apvariants` (+ `.fromSlot`), `.partysupport`, `.famerequest`, `.magicguard`, `.merchantpoolstock` | `false` | bot behaviour add-ons | grep of src/main/java (one reader each, named in `docs/architecture/switches.md`) |
| `slums.family.*` (e.g. `botsOff`, `bondingMin`) | unset | family bot behaviour | src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:92 |
| `slums.bottalk.<topic>` | unset | one chat topic | src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:162-166 |
| `slums.shutdown.playerBudgetMs` / `.botBudgetMs` | `60000` each | shutdown phase deadlines | src/main/java/soloMapling/server/ShutdownSaves.java:29, 32 |
| `slums.db.snapshotConnectTimeoutMs` | `10000` | snapshot connection timeout | src/main/java/tools/DatabaseConnection.java:46, 154, 167 |
| `slumsms.gm.allowGiving` | `false` | GM-to-player trades and drops | src/main/java/client/GmGiving.java:11, 17 |
| `polyglot.engine.WarnInterpreterOnly` | **set to `false` by `main`** | silences the Graal warning | Server.java:1157 (not operator-facing) |

### Config vs `-D` vs env, and the release pipeline

- **`config.yaml` is not in git** (.gitignore:7 `/config.yaml`) and is absent from this worktree. Live's file is produced on
  the host; `docs/release/1.6.4.conf:184-188` says `CONFIG_APPEND=""` and `CONFIG_HEAD=""` for 1.6.4 — nothing is appended or
  replaced, so live keeps its own `config.yaml` and only the keys in `SERVER_SET` are edited in place by
  `tools/release/set-release-config` (`--set KEY=VALUE`, header at tools/release/set-release-config:8-14).
- **`SERVER_SET` is type-checked against `ServerConfig.java`** before the deploy: `tools/release/gen-deploy:59-70`
  rejects an unknown key with `"SERVER_SET $k is not a numeric, boolean or text field of ServerConfig.java (the strict
  config would refuse it)"`, rejects a boolean given a number and vice versa, and rejects `CHANNEL_LOAD`,
  `MIN_CLIENT_BUILD`, `CHANNELS` (they have their own variables, :63). That check exists **because** the loader is strict.
- **Live's `SERVER_SET`** (docs/release/1.6.4.conf:201-214): `BOT_ROSTER_PEAK_ONLINE=3000`, `BOT_POPULATION_PEAK=3500`,
  `BOT_DIRECTOR_BOOT_FILL_PER_SECOND=2`, `BOT_DECIDE_URL`, `BOT_DECIDE_TONE_URL`, `BOT_DECIDE_MODEL=jev-latest`,
  `BOT_DECIDE_TONE_MODEL=jev-latest`, `USE_FAMILY_SYSTEM=true`, `TUTORIAL_ISLAND_PROMPT=true`, `BOT_REBIRTH_ENABLED=true`,
  `BOT_REBIRTH_PER_DAY=2`, `DAMAGE_RANK_ENABLED=true`, `DAMAGE_RANK_MIN_BUILD=10640`, `OWNED_ALTS_SUMMON_ENABLED=true`, the
  four `COMPANION_*_ENABLED=true`, `OWNED_ALTS_INVITE_SUMMON_ENABLED=true`, `STARFORCE_BOOST_PERCENTAGE2=0.2`.
  `DB_CONNECTION_TIMEOUT_MS` is deliberately **not** set (:149-152 explains why: the code default is 30 s and a 10 s pool
  timeout turned busy boot-fill waits into refusals).
- **`CHANNELS=5`, `CHANNEL_LOAD=125`, `MIN_CLIENT_BUILD=10740`** are separate variables (:277-284), not `SERVER_SET` keys.
- **`-D` flags on live** come from `UNIT_EDITS` (docs/release/1.6.4.conf:271), one sed that inserts them before `-jar`:
  achievements, bot talk, crews, `-Dsolomapling.reportsFile`, the exchange set (with `startHalted=true`, `houseAccount=15`,
  `traders`, `traders.pct=35`), bot quests, the temple, mechanics + `slums.mechanics.lobby`, `slums.gear.optimizer=pareto`,
  `slums.gear.dexless`, `slums.grind.gearFirst`, `slums.junk.sort`, `slums.scroll.ev`, the bot behaviour add-ons, job
  errands and bot skill books. The 1.6.0 flags `slums.market.export`, `slums.site.export`, `slums.board` and the auth port
  are already on live (:229 comment names the auth flags; paths only, not quoted here).
- **TEST:** what `start-test.sh` passes is **unknown** — the file lives on mapletest and was not readable for this review.

## Invariants

1. **Unknown key = boot abort; missing key = silent default.** Both halves are load-bearing and asymmetric
   (YamlConfig.java:20-31, config.example.yaml:8-13).
2. **The config is a global mutable singleton** (`YamlConfig.config`, YamlConfig.java:15). Tests mutate fields in place and
   restore them (`AutosaveDuringShutdownTest` @BeforeEach/@AfterEach), which is the only supported pattern.
3. **Field types are the file's contract.** yamlbeans converts by field type, so `CHANNEL_SIZE: "20"` becomes the int 20 but
   `CHANNEL_SIZE: 20.5` fails the parse; `gen-deploy` mirrors those rules with its regexes
   (tools/release/gen-deploy:60-69).
4. **Every `ServerConfig` field must appear exactly once in `config.example.yaml`**, enforced by
   src/test/java/config/ConfigTemplateCompletenessTest.java:68-75; an extra key there is reported as "not a ServerConfig
   field (typo, or a removed field)".
5. **A 0 is not "off".** 13 fields are asserted non-zero in the template because a 0 was measured to break a read site
   (ConfigTemplateCompletenessTest.java:39-54, :106-110).
6. **World ids are unique and in `0..20`** (WorldConfigs.java:65-78), verified before any socket binds (Server.java:957).
7. **`WORLDS` truncates the world list, never extends it** (Server.java:978) — a config with one world and `WORLDS: 5`
   starts one world and logs a warning.
8. **Charset is read by a second, lenient parser** (CharsetConstants.java:63-75); adding a key it does not declare is fine,
   which is why that reader can survive a file the strict reader would reject.

## Origin

| Path | Origin | Evidence |
|---|---|---|
| src/main/java/config/YamlConfig.java | base | only commit `03d13cb21` (2026-10-18); byte-identical to `/root/workspace/cosmicms/src/main/java/config/YamlConfig.java` |
| src/main/java/config/ServerConfig.java | modified | oldest `03d13cb21`, later `e07a2f1f1` ("Limit automatic registration and stamp the release version"); `diff -q` differs from upstream. Fields with ticket numbers in their comments: 43 (582), 164 (414), 183 (122-125), b296 (`STARFORCE_BOOST_PERCENTAGE2`) |
| src/main/java/config/WorldConfig.java | modified | `03d13cb21` then `59330e4fe` (2026-10-20, "Let a world name its id in config.yaml and run Bera alone"); differs from upstream |
| src/main/java/config/WorldConfigs.java | custom | only commit `59330e4fe`; absent from /root/workspace/cosmicms |
| config.example.yaml | custom | 537 lines; absent upstream; rewritten in 1.6.4 by `fc17b56c4` + `3e451ef3a` per docs/tickets/273-config-template-gap.md (`--follow` shows `03d13cb21`, the repack's own initial import — the repo has no earlier history, so "custom" rests on absence upstream plus the ticket) |
| src/main/java/constants/string/CharsetConstants.java | modified | carries the `setIgnoreUnknownProperties(true)` bootstrap read (65) that YamlConfig does not have |
| docs/release/1.6.4.conf | custom | release conf, no upstream counterpart |

`tools/cosmicdiff/classifications.json` has no entry matching `config/` or `CharsetConstants` (grep returned nothing).

## Tests and QA

| Test | Covers |
|---|---|
| src/test/java/config/ConfigTemplateCompletenessTest.java | 4 tests: every `ServerConfig` field in the template (:68), every `WorldConfig` field (:77), the template passes `WorldConfigs.validate` (:90), no breaking zero in the template (:106) |
| src/test/java/config/WorldConfigsTest.java | `idOf` positional/explicit ids (:21), `UNSET` default (:29), live Bera-alone (:34), mixed ids (:43), duplicate id throws (:55), unknown id throws (:62) |
| src/test/java/net/server/ServerWorldRegistryTest.java | the runtime half of ticket 41: `registerWorld` rejects a duplicate id and a wrong slot, worlds register in slot order, Bera-alone resolves as home world (:31-70) |
| src/test/java/config/OwnedAltSwitchLineTest.java | `ownedAltSwitchesLine()` names all seven switches and reports the values actually booted with; the shipped conf states the master switch (:25-43) |
| src/test/java/config/FamilyConfigDefaultsTest.java, GmSecurityDefaultsTest.java, StarForceBoostDefaultTest.java, WeddingConfigDefaultsTest.java | per-family default assertions added when those defaults were wrong once (family, GM levels, star-force %, wedding reservation) |

Auto-QA: `src/main/java/soloMapling/autoqa/AutoQaCatalog.java` has no config-loading check; `src/main/java/soloMapling/qa/`
has no config scenario.

Not covered, explicitly: **no test asserts the strict unknown-key abort** (grep for "unknown" in src/test/java/config and
src/test/java/net/server hits only unrelated tests), no test asserts the missing-key silent-default behaviour other than
the template completeness proxy, no test loads a malformed `config.yaml` from disk (every config test builds a `YamlConfig`
by hand or reads the template), and `WorldConfig.channels = 0` is untested.

## Known issues

| Id | One line | Source |
|---|---|---|
| 181 / 273 | The shipped template was 9-10 lines and set 6 of 359 keys, so a fresh install booted with 13 boot-critical fields at 0 (`ITEM_EXPIRE_CHECK` broke the first `MapleMap.addPlayer`, `MAP_VISITED_SIZE` broke the first map change, `MOB_STATUS_AGGRO_INTERVAL` broke the aggro monitor). Fixed on `163-integration` (`fc17b56c4`, `3e451ef3a`) as `config.example.yaml` 537 lines + `ConfigTemplateCompletenessTest`; the ticket stays open only for the forward merge into `release-1.6.4` | docs/tickets/273-config-template-gap.md:3-14, :45-70; /root/work/STATUS-1.6.4.md does not carry the card (no line matches "config template") |
| b296 | `STARFORCE_BOOST_PERCENTAGE2` was absent from live's config and read 0; live now sets `0.2` via `SERVER_SET` | docs/release/1.6.4.conf:216-217 |
| 164 | `PARTY_SEARCH_REENTRY_LIMIT` had no initialiser, so an omitted key let a single empty search expire the leader for 77 ticks; now `= 10` at ServerConfig.java:414 | ServerConfig.java:409-414 |
| NEW-FINDING: src/main/java/config/ServerConfig.java:98 | `SPAWN_BOTS_ON_STARTUP` is declared, documented in config.example.yaml:108, described as unused in docs/release/1.3.0.md:156, and read by nothing in src/main/java. An operator who sets it gets silence | own reading (grep: one occurrence in src/) |
| NEW-FINDING: src/main/java/config/WorldConfigs.java:55-81 | `validate` checks ids and nothing else. `channels: 0` passes and produces a world that starts, logs "Finished loading world", and can never accept a player; `channels: -3` likewise. Only `id` is range-checked | own reading |
| NEW-FINDING: src/main/java/config/ServerConfig.java:416 | `WORLD_TRANSFER_COOLDOWN = NAME_CHANGE_COOLDOWN` is evaluated once at construction, so a file setting only `NAME_CHANGE_COOLDOWN` silently moves the world-transfer cooldown with it, while a file setting both is order-independent only because yamlbeans writes fields after construction. The intent ("default is same as name change") is a default, not an alias | own reading |
| NEW-FINDING: src/main/java/constants/string/CharsetConstants.java:65 | The charset bootstrap read sets `setIgnoreUnknownProperties(true)`, so it cannot serve as a strictness pre-check: a `config.yaml` with a typo'd key passes this read and only fails the later strict one, which means the failure surfaces from a static initializer with `ExceptionInInitializerError` instead of a clean message | own reading |
| NEW-FINDING: src/main/java/config/YamlConfig.java:22 | The file is opened relative to the process working directory with no search path and no `-D` override, so a unit that starts with the wrong `WorkingDirectory` fails with "Could not read config file config.yaml" and no hint of where it looked | own reading |

## State

**working** for the loader and **partial** for the template. Evidence: the loader path is exercised by three test classes
that assert real behaviour (ConfigTemplateCompletenessTest parses `config.example.yaml` with the same `YamlReader` the server
uses and compares values; WorldConfigsTest asserts every validate branch; ServerWorldRegistryTest asserts the runtime
registry), and the strictness mechanism is understood down to the yamlbeans default. It is not fully "working" because the
strict-abort path itself has no test, the template fix is still only on `163-integration` and not in `release-1.6.4`
(docs/tickets/273-config-template-gap.md:3-6), and live's own `config.yaml` is not readable from this host
(.gitignore:7). No build or test run was performed for this review.

## Open questions

- Which exact fields does live's `config.yaml` set beyond `SERVER_SET`? Not determinable here: the file is git-ignored and
  absent, `CONFIG_APPEND`/`CONFIG_HEAD` are empty (docs/release/1.6.4.conf:188-190), and the host is unreachable.
- What does TEST's `start-test.sh` pass, and does its `config.yaml` differ from live's? Unknown — file on mapletest.
- Does the merge of `163-integration` into `release-1.6.4` happen with `DB_CONNECTION_TIMEOUT_MS` present (359 fields) or
  absent (358)? The ticket predicts the completeness test catches whichever way it goes, but the test has to be run
  (docs/tickets/273-config-template-gap.md:110-130).
- Is `CHARSET` ever non-`US-ASCII` in production? Nothing in either release conf sets it, and the bootstrap read only
  recognises four names (CharsetConstants.java:35-45); a tool that writes the file in another encoding would be misread.
