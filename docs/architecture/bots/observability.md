# Bots observability

## Purpose

Everything an operator, GM or QA lane can use to see *what a bot is doing and why* without attaching a debugger, and
the two machine-readable channels the same information is written to: the QA hook bus (`QaLog` → `QaRun` files and the
TEST HTTP event feed) and the periodic JSON snapshots under `data/autoqa/`. Nothing here changes bot behaviour: every
surface is read-only by construction (`BotPerfStats` class comment "Counters only - nothing here changes bot behavior",
src/main/java/soloMapling/server/BotPerfStats.java:10-11; `SocialMetrics` class comment "Nothing here writes, logs or
changes a mood", src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:19-27).

## Entry points

| Surface | What you see | How to turn it on | file:line |
|---|---|---|---|
| `!botwhy <name>` | yellow chat, then `[BotLifePreview] bot=<id> …` in the log: last actual decision + age, phase now, current request state, plan line per goal, Bartle, mood/emotions, promises, bonds, learning, gear pick | nothing on a brain-enabled server (the brain is on by default); only a brain-off server needs `-Dslums.qa=true -Dslums.botlife.preview=true` | src/main/java/client/command/commands/gm3/BotWhyCommand.java:21-24, 88-107; registered src/main/java/client/command/CommandsExecutor.java:383 |
| `!botstatus` | `Bots on map N (<lod tier>): count[, first 40]` then one chat line per bot: id, name, level, job, kind, phase, `x/y`, nav decision | `-Dslums.debugtools=true`, or QA/auto-QA on | src/main/java/client/command/commands/gm3/BotStatusCommand.java:29, 44-68; registered src/main/java/client/command/CommandsExecutor.java:469 |
| `!env perf` | `bots: N active   dynamic movement: N   grinders: N`, `observed maps: full/halo/active`, then `BotPerfStats.report()` rate lines | registered whenever `-Dsolomapling.enabled` (bots on); no extra flag | src/main/java/client/command/commands/gm4/EnvironmentCommand.java:512-513, 625-637; registered src/main/java/client/command/CommandsExecutor.java:428 |
| `!env lod pin\|unpin\|list <mapId>` | one confirmation line: "pinned map N (k/cap)" / the pin list | `-Dslums.debugtools=true`, or QA/auto-QA on; refusal text "…is a test-server tool" | src/main/java/client/command/commands/gm4/EnvironmentCommand.java:95-101, 153-174 |
| `!env` (help) | the whole `!env` sub-command list incl. `perf`, `lod` | same as `!env` | src/main/java/client/command/commands/gm4/EnvironmentCommand.java:645 |
| `!qa menu\|list\|run\|do\|where\|stop` | scenario menu / `run <id> started`, `where` = live progress, `stop` = summary | `-Dslums.qa=true` (or `-Dslums.attackcapture=true`) | src/main/java/client/command/commands/gm3/QaCommand.java:17-31; registered src/main/java/client/command/CommandsExecutor.java:378 |
| `!botwatch start [bots] [minutes]\|status\|stop` | `Bot watch done: <summary>` + `Details: data/autoqa/botwatch-latest.json` | `-Dslums.qa=true` or `-Dslums.autoqa=true` | src/main/java/client/command/commands/gm3/BotWatchCommand.java:31-49; registered src/main/java/client/command/CommandsExecutor.java:390 |
| `!socialtrace start [hours]\|status\|stop` | trace start/stop confirmations and a status block (entries, dropped, threads) | `-Dslums.autoqa=true` or `-Dslums.socialtrace=<hours>` | src/main/java/soloMapling/autoqa/SocialTrace.java:101-115, 155, 189; registered src/main/java/client/command/CommandsExecutor.java:393 |
| `!decideshadow status\|disagree` | the shadow model's agreement scorecard | `-Dslums.autoqa=true` or `-Dslums.decide.shadow=<models>` | src/main/java/soloMapling/ArtificialPlayer/BotConvo/DecideShadow.java:74, 235; registered src/main/java/client/command/CommandsExecutor.java:396 |
| `!killcalib` | kill-time distribution dump (report only) | `-Dslums.killcalib=true` — **live runs it** | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/KillCalibration.java:40, 107-112; registered src/main/java/client/command/CommandsExecutor.java:472 |
| `!navprobe`, `!pos` nav region | nav probe answer; a `nav`/region line appended to `!pos` | `-Dslums.debugtools=true` (nav probe is unregistered otherwise; `!pos` just omits the line) | src/main/java/client/command/commands/gm3/PosCommand.java:43; src/main/java/client/command/CommandsExecutor.java:468; src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavTrace.java:133, 255 |
| `GET /bots/{cid}`, `/maps/{id}`, `/positions` | JSON bot document with `plan`, `why`, `mind`, `bonds`, `group`, `learning`, `gear`, `nav`, `lod` | `-Dslums.inspect=true -Dslums.inspect.port=8098 -Dslums.inspect.secretFile=<file>` (secret ≥ 16 chars, loopback only, header `X-Slums-Inspect`) — **live runs it** | src/main/java/soloMapling/inspect/BotInspectApi.java:60-66, 104-125; views src/main/java/soloMapling/inspect/InspectViews.java:48-82; boot src/main/java/net/server/Server.java:1044 |
| `GET /qa/events` (long-poll) | JSON `{seq, at, topic, kind, scenario, mapId, chars[], message}` windows | `-Dslums.qa.feed=true` on the inspect listener, never on live | src/main/java/soloMapling/inspect/QaEventFeed.java:60, 109-116, 122-136, 284-306; attached src/main/java/soloMapling/inspect/BotInspectApi.java:194 |
| messenger console: `mmc connect` then `botlog` / `botunlog` / `chalkboard` / `setallchalk` / `removeallchalk` / `resetbotlog` / `cmd <chat cmd>` | each bot state change mirrored into the messenger; chalkboard text above the bot | GM level ≥ 4 in the Maple Messenger, bots on (`MapleRootSolo` hook) | src/main/java/net/server/channel/handlers/MessengerHandler.java:125-128; src/main/java/soloMapling/server/MapleMessengerConsole.java:31, 87-150, 280-286 |
| `BotLog.txt` (+ `logs/botlog/BotLog-<date>-<i>.txt.gz`) | `[yyyy-MM-dd HH:mm:ss]: <Bot> State: …` one line per bot state change | always — `static final boolean log = true`; roll/prune via `-Dbotlog.maxSize` / `.maxAge` / `.maxTotal` | src/main/java/soloMapling/BotLogger.java:20-23; src/main/resources/log4j2.xml:10, 11-13, 48-66, 143-145 |
| `data/autoqa/bot-life.json` | nested 60 s snapshot: `metrics{}`, `onlineBots`, `activities`, `chosen`, `henesysLevels`, `sayDo`, `nameGuard`, `learning`, `towns`, `botTalk` | always unless `-Dslums.botlife.metrics=false` | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:883-921 |
| `data/autoqa/botwatch-latest.json` + `botwatch/botwatch-<stamp>.json` | per-bot verdict rows (OK/IDLE/MISMATCH/TELEPORTY/LOGGED_OUT) + summary + `check{}` | `!botwatch start` (or auto-QA `t-bw-1`) | src/main/java/soloMapling/autoqa/BotWatch.java:552-568 |
| `data/autoqa/social-trace/<date>.jsonl` + `social-trace-latest.json` | one JSON row per stage (trigger/compose/deliver/sense/decide/act/remember/spread) + rolling summary | `!socialtrace start`, `-Dslums.socialtrace=<hours>`, or auto-QA `t-st-1` | src/main/java/soloMapling/autoqa/SocialTrace.java:101-115, 819-826, 858 |
| `logs/qa/<id>-<UTC>.log` | one line per hook the run accepted, `[hh:mm:ss] TOPIC message` | `!qa run <id>` (or auto-QA) with `-Dslums.qa=true` | src/main/java/soloMapling/qa/QaSuite.java:94; src/main/java/soloMapling/qa/QaRun.java:69-79, 167 |
| `data/convo/routing/<day>.jsonl` | one routing-decision row per chat message considered | `-Dsolomapling.convo.routeLog=true`, or auto-QA on | src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/RouteLog.java:35, 48-57 |
| `[BotLife] DECISION sample 1/N` in the server log | sampled decision lines with the chosen activity mix | always at DEBUG; INFO sampling at `1/-Dslums.botlife.logEvery`, default 500 | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:38, 573-587 |

## How it works

### 1. The GM-facing surface: `!botwhy`

`BotWhyCommand.execute` resolves the name through `PlayerStorage.getCharacterByName` then
`CharacterStorage.getBotById` and refuses when `bot.getChr() != target`, i.e. the bot is not live in this world
(src/main/java/client/command/commands/gm3/BotWhyCommand.java:98-102). It then prints `inspectionLines` as yellow chat
**and** re-logs every line as `log.info("[BotLifePreview] bot={} {}", id, line)`
(src/main/java/client/command/commands/gm3/BotWhyCommand.java:103-106).

| Phase | Action | file:line |
|---|---|---|
| 1 | `decisionLines(bot, now)` — with the brain running, the **last stored** decision (`runtime.lastDecision()`) is re-described with *current* request state, prefixed "Last actual decision N s ago; phase now X. Request state below is current." | src/main/java/client/command/commands/gm3/BotWhyCommand.java:47-70 |
| 2 | no stored decision → `LifeObservation.describe(LifeObservation.capture(bot, now))`, a throwaway evaluation | src/main/java/client/command/commands/gm3/BotWhyCommand.java:64, 69 |
| 3 | `runtime.describePlan()` — one line per goal with pressure, the next GOAP step, its activity and destination map, "Done steps the game can't see", plus `BotQuestChat.describe` | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:761-779 |
| 4 | `SocialSelf.of(id).describe()` — Bartle blend | src/main/java/client/command/commands/gm3/BotWhyCommand.java:30 |
| 5 | `SocialMetrics.whyLines` — mode + mind (mood word, top emotions, strongest need) or "Mind: not loaded (no social state in memory for this bot yet)" | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:203-232 |
| 6 | `SocialMetrics.intentLines` — live promises | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:234-236 |
| 7 | `BotBonds.describe(id)` — top bonds + crew | src/main/java/soloMapling/ArtificialPlayer/BotAffinitySystem/BotBonds.java:963 |
| 8 | `LifeLearning.describe(id, level, now)` — quadrant, crossings, today's budget, fads, trends | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:481 |
| 9 | `BotGearWhy.describe(id, now)` — **returns null unless `DebugTools.isEnabled()`** | src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGearWhy.java:52; gate src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGearWhy.java:28-30 |

`LifeObservation.describe(report, actualDecision)` prints either `Bot life DECISION vN:` or `Bot life SHADOW vN:`, then
personality, goals, measured needs, unknown, `Outcome … selected …`, activity-request counters, the top three scored
activities with their contribution breakdown, and the "Unavailable:" activity list
(src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeObservation.java:147-176). With `actualDecision=true` the
outcome line ends "; recorded decision. This command starts no work." rather than "; preview only."
(src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeObservation.java:159-161).

The same lines are exposed over HTTP as the `why` key of `/bots/{cid}`, and `null` when the command is disabled
(src/main/java/soloMapling/inspect/InspectViews.java:80-81); `plan`, `goals`, `mind`, `bonds`, `group`, `learning`,
`bartle`, `gear` are separate keys of the same object (src/main/java/soloMapling/inspect/InspectViews.java:66-79), with `runtime` itself null unless
`LifeRuntime.enabled()` (src/main/java/soloMapling/inspect/InspectViews.java:72).

### 2. The QA hook bus: `QaLog`

`QaLog` is a static fan-out with a single volatile bitmask fast path
(src/main/java/soloMapling/qa/QaLog.java:79, 160-162).

| Element | Behaviour | file:line |
|---|---|---|
| `Topic` | 45 constants, each `final long bit = 1L << ordinal()`; "a long: 34 topics no longer fit an int" | src/main/java/soloMapling/qa/QaLog.java:27-76 |
| `mask` | OR of every attached run's topics and every sink's bits; `recompute()` after attach/detach/topics | src/main/java/soloMapling/qa/QaLog.java:262-275 |
| `map(t, mapId, msg)` | line about a map; `ownerId = 0` → sinks receive `NO_CHARS` | src/main/java/soloMapling/qa/QaLog.java:170-172, 225-233 |
| `owner(t, charId, msg)` | reaches only the run that character (a GM) started | src/main/java/soloMapling/qa/QaLog.java:175-177 |
| `toPlayer` | `owner`, but drops `playerId <= 0` "it would leak into every run on the map" | src/main/java/soloMapling/qa/QaLog.java:183-190 |
| `chars(t, mapId, int[], msg)` | the **only** variant that forwards real char ids to sinks | src/main/java/soloMapling/qa/QaLog.java:213-223 |
| `any(t, msg)` | every run listening to the topic, no map | src/main/java/soloMapling/qa/QaLog.java:191-193 |
| `every(key, ms)` | per-key rate limit for tick-frequency hooks | src/main/java/soloMapling/qa/QaLog.java:199-208 |
| `Sink` / `SinkHandle` | ticket 170 seam: one line per hook with `(topic, mapId, charIds, message)`; `topics()` and `close()` recompute the mask | src/main/java/soloMapling/qa/QaLog.java:102-135 |
| `listen(Listener)` | per-scenario seam with `(gmId, scenarioId, kind, message)` | src/main/java/soloMapling/qa/QaLog.java:144-148 |

Filtering downstream is `QaRun.accepts`: topic in the run's set, and `ownerId == 0 || ownerId == gmId`, and
`mapId == 0 || maps.isEmpty() || maps.contains(mapId)`
(src/main/java/soloMapling/qa/QaRun.java:124-132). The run's map set comes from `scenario.watchedMaps(gm, a)`
(src/main/java/soloMapling/qa/QaSuite.java:162-163), whose default is empty = every map
(src/main/java/soloMapling/qa/QaScenario.java:36-38); conversation scenarios do not override it, so `cv-*` runs see
every map.

Sink exceptions are swallowed so "a sink never breaks the hook that logged" (src/main/java/soloMapling/qa/QaLog.java:239-246).

### 3. The TEST feed: `QaEventFeed`

`QaEventFeed` rides the inspect listener (`QaEventFeed.attachIfEnabled(server, api.secret)`,
src/main/java/soloMapling/inspect/QaEventFeed.java:154-167, called at src/main/java/soloMapling/inspect/BotInspectApi.java:194)
and registers itself as a `QaLog.sink` plus a `QaLog.listen` listener for scenario lines (src/main/java/soloMapling/inspect/QaEventFeed.java:185-200).
It is refused on live by `liveReason` — live folder prefix, login port 8485, DB port 3309 (src/main/java/soloMapling/inspect/QaEventFeed.java:122-136) —
and is enabled only by the literal `true` (`"true".equalsIgnoreCase(props.apply(SWITCH))`, src/main/java/soloMapling/inspect/QaEventFeed.java:109-116).

Retention is a 20,000-event ring (src/main/java/soloMapling/inspect/QaEventFeed.java:64). `record()` keeps a line when it names characters, or when it is
map-scoped **and** some *watched* character stands on that map (those characters are then attached to the event); a
map line nobody watched is dropped, and a line with neither characters nor a map is kept as server-wide
(src/main/java/soloMapling/inspect/QaEventFeed.java:228-252, drop at :238).

**Card b276 — a bot's map-scoped `PLAN`/`ROUTE` lines never reach a GM's QA feed.** Three emitters write those lines
with `QaLog.map(...)`: the planner's `PLAN <bot> -> <player> <move>` (src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1179-1184,
gated by `QaLog.on(Topic.CONVO)` at :1179), the dispatcher's `ROUTE "<line>" -> <bot> [step]`
(src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java:304-308) and the candidate dump
`ROUTE CANDIDATES <json>` (src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/RouteLog.java:77-79). `map()`
reaches runs through the run's own map filter (src/main/java/soloMapling/qa/QaRun.java:124-132) — so a `cv-*` run, which watches every map, gets them
in `logs/qa/` — but a **sink** is handed them with `NO_CHARS` (src/main/java/soloMapling/qa/QaLog.java:225-233), and `QaEventFeed.record` drops a
character-less map line unless a *watched* character stands on that map (src/main/java/soloMapling/inspect/QaEventFeed.java:231-252). A GM polling the feed
with `chars=<own cid>` is therefore never shown a bot's plan, which is exactly b276 (/root/work/STATUS-1.6.4.md:78).
The mitigation today is `!botwhy` (per-bot, on demand) or a `cv-*` scenario log.

Consumers long-poll `/qa/events` with `since/chars/topics/match/wait/limit`; loopback only, 429 above 4 pollers
(src/main/java/soloMapling/inspect/QaEventFeed.java:284-306, 385-430). The auto-QA runner is
`tools/qa-runner/qarunner.py:48` (`FEED_URL = "http://127.0.0.1:8198/qa/events"`) with its own secret file
`/etc/slums-inspect-test/secret` (`tools/qa-runner/qarunner.py:49`).

### 4. Throughput: `BotPerfStats` and `!env perf`

Hot paths bump six `LongAdder`s — macro ticks, movement ticks, idle broadcasts, nudges, wheel dispatches, wheel lag
(src/main/java/soloMapling/server/BotPerfStats.java:19-26) — and `report()` diffs them against the previous readout:
"the first call after boot averages over the whole uptime - call twice a few seconds apart"
(src/main/java/soloMapling/server/BotPerfStats.java:56-61, 62-101). `!env perf` appends the counts the stats class deliberately does not import:
`bots: N active   dynamic movement: N   grinders: N`, `observed maps: full/halo/active`, then
`BotPerformanceMonitor.report()` (src/main/java/client/command/commands/gm4/EnvironmentCommand.java:625-637).

Counter sites: `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:86` (macro), `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:144` (movement),
`src/main/java/soloMapling/ArtificialPlayer/BotMovementSystem/MovementCommands.java:352` (idle broadcasts), `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:405` (nudges), `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:239` (combat sweep),
`src/main/java/soloMapling/server/BotTickService.java:250` (wheel dispatch).

### 5. BotLog and the messenger console: the per-bot text channel

`BotDebugHandler` is the per-bot state-change logger every bot type calls
(`getDebugger().debugLoggingFull(...)`, e.g. src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:611).
`debugLoggingFull(msg)` always writes `BotLog`, adds the messenger line only when that bot's id is on the console's
logging set, and paints the chalkboard only when chalk debug is on for that bot
(src/main/java/soloMapling/ArtificialPlayer/BotDebugHandler.java:61-74). `BotSM.updateState` calls
`handleDebugPrints` once per macro tick (src/main/java/soloMapling/ArtificialPlayer/BotSM.java:287, handler at
src/main/java/soloMapling/ArtificialPlayer/BotDebugHandler.java:104-107). `debugBubble` routes through `DebugUtilities.debugprint`, whose `printDebug` local is
hard-coded `false` (src/main/java/soloMapling/DebugUtilities.java:14-17), so bubbles are inert regardless of flags.

The console side is `MapleMessengerConsole`, entered only from a GM4's messenger chat through the `MapleRootSolo` hook
(src/main/java/net/server/channel/handlers/MessengerHandler.java:125-128). The GM first types `mmc connect` to join the
console (`connectUser`, src/main/java/soloMapling/server/MapleMessengerConsole.java:91-105), then `botlog <id|name>`
(`registerBotLoggingCommand("botlog", true)`, :111) and `botunlog` (:112) start/stop that bot's messenger mirror
(`startLoggingBot`/`stopLoggingBot`, :57-75), `chalkboard <id|name>` flips `BotDebugHandler.setBotChalkboard` (:114-122,
toggle at :77-84), `setallchalk`/`removeallchalk` sweep every bot (:124-130), `resetbotlog` (:132) and `cmd <chatCommand>`
(:136) reuse the chat-command path. Every handler runs behind `executeIfConnected` (:280-286), which refuses with "You
must be connected to the console to use this command.". `isLoggingBot(id)` is the read the per-bot logger gates on
(:53-55; src/main/java/soloMapling/ArtificialPlayer/BotDebugHandler.java:66).

`BotLogger.log` is unconditional — `static final boolean log = true`
(src/main/java/soloMapling/BotLogger.java:20) — so **every** bot state-change line reaches `BotLog.txt`, and only the
messenger mirror is opt-in. The file is a Log4j `RollingFile` with a 100 MB size trigger, a daily roll and a
700 MB / 7-day prune of `logs/botlog/` (src/main/resources/log4j2.xml:11-13, 48-66).

### 6. Periodic snapshots

`LifeRuntime.reportIfDue` runs on a 60 s CAS cadence on the shared executor, samples the Henesys level bands on map
100000000, and writes `data/autoqa/bot-life.json` through a tmp file + `ATOMIC_MOVE`
(src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:883-921; bands :897-905, file :911, move
:912-916, failure log "Bot life report unavailable: {}" :918). Keys: `metrics{decisions,accepted,cpuNanos,replans}`
(:864-867), `at`, `onlineBots`, `activities`, `selected`, `chosen`, `henesysLevels`, `sayDo`, `nameGuard`, `learning`,
`towns`, `botTalk` (:906-910). There is **no Java reader** — the only consumers are
`tools/ops/test-status:43` and docs.

`!botwatch` follows a sample of bots and judges `OK/IDLE/MISMATCH/TELEPORTY/LOGGED_OUT`, writing
`data/autoqa/botwatch-latest.json` plus a dated copy (src/main/java/soloMapling/autoqa/BotWatch.java:552-568;
defaults 40 bots / 30 min, caps 200 / 240, src/main/java/soloMapling/autoqa/BotWatch.java:54).

`SocialTrace` follows every bot↔bot thread and drama event through trigger → compose → deliver → sense → decide → act →
remember → spread, with pretend observers standing in town, appending JSONL by UTC day and rewriting a summary
(src/main/java/soloMapling/autoqa/SocialTrace.java:101-115, 819-826, 858). It boots 3 minutes after start when the
property is set (src/main/java/soloMapling/Environment/EnvironmentManager.java:152).

## Data

| Artifact | Shape | Writer | Reader |
|---|---|---|---|
| `BotLog.txt` + `logs/botlog/BotLog-<date>-<i>.txt.gz` | text, `[yyyy-MM-dd HH:mm:ss]: msg` | `BotLogger.log` → Log4j `soloMapling.BotLog` (src/main/resources/log4j2.xml:10, 48-66, 143-145) | operator (grep) |
| `logs/qa/<id>-<UTC>.log` | text, one line per accepted hook | `QaRun.event` (src/main/java/soloMapling/qa/QaRun.java:167; path resolved at :69-79) | GM, docs/ops/qa-suite.md |
| ring of 20,000 `Event` + `/qa/events` JSON | `{seq, at, topic, kind, scenario, mapId, chars[], message}` | `QaEventFeed.record` (src/main/java/soloMapling/inspect/QaEventFeed.java:231-252) | `tools/qa-runner/qarunner.py` |
| `data/autoqa/bot-life.json` | nested counters, rewritten each 60 s | `LifeRuntime.reportIfDue` (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:883-921) | `tools/ops/test-status:43` |
| `data/autoqa/botwatch-latest.json`, `botwatch/botwatch-<stamp>.json` | verdict per bot + summary | `BotWatch.write` (src/main/java/soloMapling/autoqa/BotWatch.java:552-568) | operator / auto-QA card |
| `data/autoqa/social-trace/<date>.jsonl`, `social-trace-latest.json` | append-only events + rolling summary | `SocialTrace.appendLine` / `:858` | operator, `SocialTrace.status()` |
| `data/convo/routing/<day>.jsonl` | one routing decision per row | `RouteLog` writer thread `convo-route-log` (src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/RouteLog.java:37-41, 67-84) | operator |
| `/bots/{cid}` JSON | `ok/at/cid/name/level/job/kind/ownedAlt/doing/lifePhase/map/channel/x/y/lod/tick/nav/grinding/gear/bartle/goals/plan/memory/mode/mind/bonds/group/learning/why` | `InspectViews.bot` (src/main/java/soloMapling/inspect/InspectViews.java:48-82) | inspect clients, auto-QA t-nv-3 |

## Switches

| Switch | Default | Effect | file:line | Live (docs/release/1.6.4.conf:271)? |
|---|---|---|---|---|
| `-Dslums.qa=true` | off | `QaSuite.isEnabled()`, registers `!qa`, `!botcombat`, `!godlydrop`, `!botwatch`, enables `DebugTools` | src/main/java/soloMapling/qa/QaSuite.java:77-79; src/main/java/client/command/CommandsExecutor.java:377-391 | **not set** → QA suite off on live |
| `-Dslums.attackcapture=true` | off | also turns `QaSuite.isEnabled()` on (same method) | src/main/java/soloMapling/qa/QaSuite.java:78 | not set |
| `-Dslums.autoqa=true` (`AUTOQA_ENABLED`) | `false` | auto-QA scheduler, `!autoqa`, `!botwatch`, `!socialtrace`, `!decideshadow`, `DebugTools`, `AutoQa.dataDir()` base | src/main/java/soloMapling/autoqa/AutoQa.java:26, 32-40; src/main/java/config/ServerConfig.java:206 | not set |
| `-Dslums.autoqa.dir` | `data/autoqa` | output root for every snapshot | src/main/java/soloMapling/autoqa/AutoQa.java:67-69 | not set |
| `-Dslums.botlife.preview=true` | off | **only** widens `!botwhy` to QA-without-brain; it starts no work | src/main/java/client/command/commands/gm3/BotWhyCommand.java:23, 90 | **not set** |
| `-Dslums.botlife.metrics` | `true` | `false` stops `bot-life.json` entirely | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:884 | not set → snapshot on |
| `-Dslums.botlife.logEvery` | `500` | 1-in-N INFO decision lines (`[BotLife] DECISION sample 1/N`) | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:38, 581-585 | not set |
| `-Dslums.debugtools` | = `QaSuite \|\| AutoQa` | forces the TEST-only tools on/off: `!navprobe`, `!botstatus`, `!env lod`, `!pos` nav region, `BotGearWhy`, per-feature defaults | src/main/java/soloMapling/server/DebugTools.java:20, 26-33, 48-54 | not set → off on live |
| `-Dslums.qa.feed=true` | off | `/qa/events` on the inspect listener; refused on live | src/main/java/soloMapling/inspect/QaEventFeed.java:60, 109-116, 122-136 | not set |
| `-Dslums.inspect=true` `.port` `.secretFile` `.bind` | off | read-only bot inspect HTTP API | src/main/java/soloMapling/inspect/BotInspectApi.java:60-66, 104-125 | **ON**: `-Dslums.inspect=true -Dslums.inspect.port=8098 -Dslums.inspect.secretFile=/etc/slums-inspect/secret` |
| `-Dslums.killcalib=true` | off | `!killcalib`, kill-time calibration dump | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/KillCalibration.java:40, 107-112 | **ON** |
| `-Dslums.navTrace=true` (+ `.perHour`, default 20) | off | per-bot nav path capture, read by `!botstatus`'s `nav` column | src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavTrace.java:39, 43-44, 133, 255 | **ON** |
| `-Dslums.socialtrace=<hours>` | unset | starts `SocialTrace` 3 min after boot, registers `!socialtrace` | src/main/java/soloMapling/autoqa/SocialTrace.java:50, 101-115; src/main/java/client/command/CommandsExecutor.java:392-394 | not set |
| `-Dslums.decide.shadow=<models>` | unset | chat-model shadow run; `!decideshadow` reads its scorecard | src/main/java/soloMapling/ArtificialPlayer/BotConvo/DecideShadow.java:74, 235; src/main/java/client/command/CommandsExecutor.java:395-397 | not set |
| `-Dsolomapling.convo.routeLog=true` | off (= auto-QA) | ROUTE CANDIDATES JSONL on disk | src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/RouteLog.java:35, 48-57 | not set |
| `-Dbotlog.maxSize` / `.maxAge` / `.maxTotal` | `100 MB` / `7d` / `700 MB` | roll and prune `logs/botlog/` | src/main/resources/log4j2.xml:11-13, 56-66 | not set |
| `-Dsolomapling.dashboard=true` (+`.dir`, `.periodSeconds`) | off | dashboard exporter that consumes `SocialMetrics` counters | src/main/java/soloMapling/dashboard/DashboardExporter.java:23-25, 59-72; boot src/main/java/net/server/Server.java:1038 | not set |

TEST start script: `start-test.sh` is not present in this checkout — TEST: unknown.

## Invariants

1. **Read-only.** No surface here may start work. `!botwhy` states it in its own output ("This command starts no work",
   src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeObservation.java:159-160); `BotPerfStats` is counters only (src/main/java/soloMapling/server/BotPerfStats.java:10-11); `SocialMetrics` never
   writes or decays state (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:19-26). `!env lod` is the one pin command and is a separate `DebugTools`
   argument (src/main/java/client/command/commands/gm4/EnvironmentCommand.java:96-101).
2. **`on(topic)` before building anything.** Every hook tests `QaLog.on(...)` first, so nothing is allocated when no
   run or sink wants the topic (src/main/java/soloMapling/qa/QaLog.java:158-162; e.g. `BotConversation.planLog`,
   src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1177).
3. **A sink never breaks the hook that logged** — sink exceptions are swallowed (src/main/java/soloMapling/qa/QaLog.java:239-246).
4. **Never on live.** `QaEventFeed` refuses the live folder, login port 8485 and DB port 3309 (src/main/java/soloMapling/inspect/QaEventFeed.java:122-136);
   `DebugTools` is off unless QA/auto-QA (src/main/java/soloMapling/server/DebugTools.java:26-33); `BotInspectApi` binds loopback only and requires a
   ≥16-char secret (src/main/java/soloMapling/inspect/BotInspectApi.java:110-119, 190-192).
5. **A bot's `getClient().getPlayer()` is null** — every inspect path resolves the `Character` via
   `CharacterStorage.getBotById` and checks `bot.getChr()` identity (src/main/java/client/command/commands/gm3/BotWhyCommand.java:98-101; InspectViews.bot takes
   `(Character, BotSM, now)`, src/main/java/soloMapling/inspect/InspectViews.java:48).
6. **Line shape is stable for parsers.** `QaRun` filters by topic + owner + map only (src/main/java/soloMapling/qa/QaRun.java:124-132), and
   `QaEventFeed` only by characters/map (src/main/java/soloMapling/inspect/QaEventFeed.java:231-252); consumers therefore match on literal prefixes such
   as `PLAN `, `ROUTE `, `BW1 PASS`, `ST1 THREAD`, so those prefixes are a contract (src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1181,
   src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java:304, src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1151-1158).
7. **Rate limits before volume.** `QaLog.every(key, intervalMs)` exists so tick-frequency hooks stay bounded
   (src/main/java/soloMapling/qa/QaLog.java:195-208); `NavTrace.perHour` caps path capture (src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavTrace.java:44).

## Origin

| File | Oldest commit | Verdict |
|---|---|---|
| `src/main/java/soloMapling/server/BotPerfStats.java` | `03d13cb21` 2026-10-18 "Establish deployable MapleRoot server baseline" | base (present in the baseline import, unmodified since) |
| `src/main/java/soloMapling/qa/QaLog.java` | `ade430132` 2026-10-21 "Add the !qa test-scenario suite with per-scenario debug logs" | custom |
| `src/main/java/client/command/commands/gm3/BotWhyCommand.java` | `c0ce7dbe6` 2026-10-24 "Inspect bot life needs through an opt-in GM preview" | custom |
| `src/main/java/soloMapling/autoqa/SocialTrace.java` | `08602ac61` 2026-10-26 "Trace bot-to-bot social life stage by stage, with observers in town" | custom |
| `src/main/java/soloMapling/inspect/QaEventFeed.java` | `c2ab2dc97` 2026-10-30 "Add the TEST-only QA event feed" | custom |
| `src/main/java/soloMapling/server/DebugTools.java` | `23cea6bc6` 2026-10-30 "Add TEST-only nav and bot debug tools" | custom |
| `src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java` | `e545ee953` 2026-11-03 | custom |

The whole `soloMapling/` tree is fork-local: its first commit is the 2026-10-18 baseline
(`03d13cb21`), so anything bearing a later date is custom, and the observability layer is entirely 1.6.0-1.6.4 work.
`BotWhyCommand.isEnabled` evolved `QA && preview` (`c0ce7dbe6`) → `LifeRuntime.enabled() || QA && preview`
(`4d78245d3`, 2026-10-24); the `runtime != null` ternary inside `decisionLines` arrived with `5ba601dc4` (2026-10-25).

## Tests and QA

| Test / check | Covers | file:line |
|---|---|---|
| `BotWhyCommandTest.previewRequiresBothQaAndExplicitFlag` | the two-part gate | src/test/java/client/command/commands/gm3/BotWhyCommandTest.java:69 |
| `BotWhyCommandTest.disabledCommandDoesNotLookUpOrTouchBots` | refusal path is side-effect free | src/test/java/client/command/commands/gm3/BotWhyCommandTest.java:90 |
| `QaLogTest.offWhenNothingRuns` / `onlyTheTopicsOfARunningScenarioAreOn` | mask recompute | src/test/java/soloMapling/qa/QaLogTest.java:34, 43 |
| `QaLogTest.linesLandInTheRunsFileFilteredByMapAndOwner` / `aSaidLineReachesOnlyTheRunOfThePlayerItNames` | map+owner routing | src/test/java/soloMapling/qa/QaLogTest.java:53, 77 |
| `QaLogTest.everyRateLimitsAKey` / `nothingIsWrittenAfterStop` | per-key rate limit (one true then false per 60 s window); a stopped run writes nothing | src/test/java/soloMapling/qa/QaLogTest.java:94, 101 |
| `QaEventFeedTest` (12 tests) | off unless explicit, live guard, filter by topic/char/regex, map lines reach watched chars and are dropped otherwise, ring, long-poll, sink vs run | src/test/java/soloMapling/inspect/QaEventFeedTest.java:69, 80, 92, 101, 114, 142, 161, 177, 194, 203, 220, 239 |
| `DebugToolsTest.offOnLiveOnOnTheTestServer` | test-vs-live rule | src/test/java/soloMapling/server/DebugToolsTest.java:13 |
| `BotInspectApiTest` (`DebugTools.featureFor(live) == false`) | per-feature default on live | src/test/java/soloMapling/inspect/BotInspectApiTest.java:170 |
| `InspectViewsTest` | key shape incl. `plan`/`why` | src/test/java/soloMapling/inspect/InspectViewsTest.java:45, 56, 72, 93 |
| `BotWatchJudgeTest` | `OK/IDLE/MISMATCH/TELEPORTY/LOGGED_OUT` verdicts | src/test/java/soloMapling/autoqa/BotWatchJudgeTest.java:69-97 |
| `SocialMetricsTest` | `countAct`/`shortReason`/`moodWord` shapes | src/test/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetricsTest.java:66, 88 |
| `BotWatchCommandTest`, `BotWatchScenarioTest`, `BotWatchWriteTest`, `BotWatchCatalogTest`, `NavProbeScenariosTest` | command syntax/refusals, `bw-1` emits, `BotWatch.write` shapes the latest+dated files, catalog wiring, nav probes | src/test/java/client/command/commands/gm3/BotWatchCommandTest.java:11; src/test/java/soloMapling/qa/BotWatchScenarioTest.java:59; src/test/java/soloMapling/autoqa/BotWatchWriteTest.java; src/test/java/soloMapling/autoqa/BotWatchCatalogTest.java; src/test/java/soloMapling/qa/NavProbeScenariosTest.java:32 |
| `SocialTraceJudgeTest`, `SocialTraceTrainingTipTest` | trace line judging | src/test/java/soloMapling/autoqa/SocialTraceJudgeTest.java |
| `DocSwitchDriftTest` | pins `docs/release/1.6.2.conf` for `slums.inspect`, `slums.killcalib`, `slums.navTrace` and asserts no listed doc says "off on live" — but the constant is hard-coded to the **1.6.2** conf (src/test/java/soloMapling/DocSwitchDriftTest.java:21, 24), not the 1.6.4 conf live now ships | src/test/java/soloMapling/DocSwitchDriftTest.java:19-54 |

Auto-QA checks (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`):

| Check | What it proves | file:line |
|---|---|---|
| `t-bw-1` "Bots do what they decided", 13 min | runs `bw-1`, waits for `BW1 (PASS\|FAIL)`, expects `BW1 START Watching` / `BW1 SUMMARY n/m bots OK` / `BW1 PASS` | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1151-1156 |
| `t-st-1` "Bot social life leaves a trace", 12 min | runs `st-1`, waits for `ST1 THREAD … TRIGGER ok COMPOSE ok DELIVER ok`, expects `ST1 START Social trace` and a thread line with `lines=[2-9]`; the `ST1` prefix comes from the scenario (`ctx.run().note("ST1", "THREAD " + s)`, src/main/java/soloMapling/qa/SocialTraceScenarios.java:49-56) and the body from `SocialTrace.deliveredThreads` (src/main/java/soloMapling/autoqa/SocialTrace.java:870-884) | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1158-1161 |
| `t-nv-3` "Debug tools answer: bot inspect API, kill calibration, nav path trace", 2 min, laneOnly | `probe("debugTools")` — asks the inspect API, kill calibration and `NavTrace` for an answer | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1545-1547 |

`!qa` scenarios feeding these: `bw-1`, `st-1`, the `cv-*` conversation set (which relies on `PLAN`/`ROUTE` lines) and
the `nv-*` nav probes.

**Not covered:** `BotPerfStats.report()` has no unit test — the only reference under `src/test/java` is
`src/test/java/soloMapling/ArtificialPlayer/BotScaleProof.java`; `BotLogger` and `MapleMessengerConsole` have no tests
at all; `LifeRuntime.reportIfDue` (the `bot-life.json` shape) is asserted only by document text, not by a test.

## Known issues

- **b276** (S4, QA-tool defect, staging, scope freeze, closer Server Dev) — "QA feed: a bot's map-scoped PLAN/ROUTE lines never reach the GM's QA feed (not tagged with the watched character)"; mechanism walked through in section 3 above. /root/work/STATUS-1.6.4.md:78
- **b284** (S3, QA-tool defect) — auto-QA at 10 lanes gives false failures because the scheduler's key declarations miss shared state; the same contention applies to any lane that reads the QA feed during another lane's scenario `[INFERENCE: b284 names the scheduler, not the feed]`. /root/work/STATUS-1.6.4.md:80
- **b240** (S3) — regular bots say they're grinding while standing in town: the chat claim differs from the state a GM would read off `!botwhy`/`!botstatus` `[INFERENCE: no surface cross-checks the said words against the phase]`. /root/work/STATUS-1.6.4.md:59
- **b241** (S4) — KPQ bonus stage keeps messaging after everyone leaves; the only observable trace of it is per-run QA output, not a persistent metric. /root/work/STATUS-1.6.4.md:60
- **b225** (S3, fixed in `5cc8061d1`) and **b232** (S3, merged `625948ac9`) are earlier bot bugs now closed in the freeze batch; neither added an observability surface. /root/work/STATUS-1.6.4.md:57-58
- **b287** (S4) — `t-xc-5` scroll trader never lists a Chaos/White Scroll; a bot-economy check, not player-facing, and the QA feed carries no trader-listing line that would explain the failure. /root/work/STATUS-1.6.4.md:83
- **NEW-FINDING:** `BotWhyCommand.isEnabled()` at src/main/java/client/command/commands/gm3/BotWhyCommand.java:23 is `LifeRuntime.enabled() || QaSuite.isEnabled() && Boolean.getBoolean(...)`, so **on any server with the brain on (which is 1.6.4's default) the `-Dslums.botlife.preview` half is dead**: `!botwhy` is registered unconditionally and can never show the "needs QA and -Dslums.botlife.preview" refusal (src/main/java/client/command/commands/gm3/BotWhyCommand.java:90). docs/architecture/server/commands.md:153 and docs/HANDOFF.md:29 still describe it as the gate, and `BotWhyCommandTest` only exercises the property with `LifeRuntime` off.
- **NEW-FINDING:** the b276 root cause is structural, not a tagging bug: `QaLog.map` forwards `NO_CHARS` to sinks (src/main/java/soloMapling/qa/QaLog.java:225-233) while `QaRun` fills the map filter in from the scenario (src/main/java/soloMapling/qa/QaRun.java:124-132), so a map-scoped line can only ever reach a *sink* that happens to have a watched character on that map (src/main/java/soloMapling/inspect/QaEventFeed.java:231-252). Any future sink has the same hole unless it re-implements the drop rule.
- **NEW-FINDING:** `SocialMetrics.whyLines` reports "Mind: not loaded (no social state in memory for this bot yet)" (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:203-232) whenever `LifeMemoryStore.peek` misses — on a live server with thousands of bots and partial residence this reads as "the bot has no mind", which is indistinguishable from a real absence of social state. There is no counter for "peek missed".
- **NEW-FINDING:** `BotPerfStats.report()` is `synchronized` and computes deltas against static `prev*` fields (src/main/java/soloMapling/server/BotPerfStats.java:49-61), so two GMs running `!env perf` interleaved split the same window between them and each sees roughly half the true rate. Nothing documents or guards that.
- **NEW-FINDING:** `!botstatus` truncates at 40 bots with only a `, first 40` suffix (src/main/java/client/command/commands/gm3/BotStatusCommand.java:29, 47-49) and emits via `dropMessage(5, ...)` (:66-67), i.e. one chat packet per bot — on a full map that is 41 packets, and the header's `, first N` wording is easy to miss when reading a scrollback.
- **NEW-FINDING:** `data/autoqa/bot-life.json` is written whenever `-Dslums.botlife.metrics` is not `false` (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:884), with no QA/auto-QA guard, so a **live** server writes it into `data/autoqa/` even though live runs neither the QA suite nor auto-QA. docs/design/player-likeness-audit.md:12 nevertheless states "bot-life.json does not exist on TEST", which is about `botlife.enabled`, not this flag — the two are easy to conflate.

## State

**working** (with one staging defect, b276, and one dead-gate finding).

Evidence: the core surfaces are wired and tested — `QaLog` mask/owner/map routing and its rate limiter have six unit
tests (src/test/java/soloMapling/qa/QaLogTest.java:34-101, file 109 lines); `QaEventFeedTest` has twelve, including the
secret/query refusals, the live guard, topic/char/regex filtering, the map-drop rule, the ring, long-poll behaviour and
sink-vs-run separation (src/test/java/soloMapling/inspect/QaEventFeedTest.java:69-239, file 249 lines); `DebugTools`
and `BotInspectApi` have live-vs-test tests (src/test/java/soloMapling/server/DebugToolsTest.java:13-21,
src/test/java/soloMapling/inspect/BotInspectApiTest.java:168-174); the inspect API ships **on** for live
(docs/release/1.6.4.conf:271) and is exercised by auto-QA `t-nv-3`
(src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1545-1547). `!botwhy` renders from a live code path with the brain
on by default (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:94-102) and is reachable on
live for that reason.

Partial edges: `bot-life.json` has no Java reader and no test (`tools/ops/test-status:43` is the only consumer);
`BotPerfStats` has no test; the b276 feed gap means the QA feed is incomplete for conversation scenarios only when
consumed by a per-character watcher (the `QaRun` file path is unaffected because `cv-*` watches every map).

## Open questions

- Is `-Dslums.botlife.preview` meant to retire now that `LifeRuntime.enabled()` defaults true? The refusal string at
  src/main/java/client/command/commands/gm3/BotWhyCommand.java:90 and three docs still describe it as the gate.
- Should `QaLog.map` carry a synthetic "map watcher" character id so sinks do not need their own map→watcher lookup
  (the b276 fix), or should `QaEventFeed` be the only place that keeps map-scoped lines? The current split lets any new
  sink reproduce the drop.
- Is `data/autoqa/bot-life.json` on live intentional? `docs/design/player-likeness-audit.md:12` says the file does not
  exist on TEST, and there is no `-Dslums.autoqa.dir` override in `docs/release/1.6.4.conf:271`.
- Auto-QA `t-st-1` matches the literal `ST1 THREAD … TRIGGER ok COMPOSE ok DELIVER ok` produced by
  `ctx.run().note("ST1", "THREAD " + s)` over `SocialTrace.deliveredThreads`
  (src/main/java/soloMapling/qa/SocialTraceScenarios.java:49-56; src/main/java/soloMapling/autoqa/SocialTrace.java:870-884).
  Is that shape covered by a test, or only by the catalog's regex? `SocialTraceJudgeTest` covers judging, not the line
  format.
- No TEST `start-test.sh` in this checkout, so TEST's actual switch set for `slums.qa`, `slums.qa.feed` and
  `slums.socialtrace` could not be read: TEST: unknown.
