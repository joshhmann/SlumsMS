# Site, dashboard, inspect and reports

## Purpose

Three read-only surfaces hang off the game server: a public site export (feed/leaderboards/world JSON written to disk and scraped by the website), an admin dashboard export (live.json plus a 30-day series, served behind nginx basic auth), and two loopback HTTP APIs (the site's auth/sign-up API and a bot-inspect API). Beside them sits the bug-report pipeline: `@bug`/`@idea` in game and the client log upload, both appended to the same JSONL file the site's report service reads. Nothing here mutates game state; every writer is a scheduled or on-demand reader of the live world plus a few small SQL queries.

## Entry points

Boot wiring, all inside `net.server.Server.init()` after "Cosmic is now online" (`src/main/java/net/server/Server.java:1036-1049`):

| Line | Call | Gate |
|---|---|---|
| `src/main/java/net/server/Server.java:926` | `server.reports.WarnLogRing.install();` | always |
| `src/main/java/net/server/Server.java:1038` | `DashboardExporter.startIfEnabled()` | `-Dsolomapling.dashboard=true` |
| `src/main/java/net/server/Server.java:1041` | `PublicSiteExport.startIfEnabled()` | `-Dslums.site.export=true` |
| `src/main/java/net/server/Server.java:1042` | `TradeRatings.start()` | always, no switch |
| `src/main/java/net/server/Server.java:1043` | `SiteAuthApi.startIfEnabled()` | `-Dslums.auth.port` set |
| `src/main/java/net/server/Server.java:1044` | `BotInspectApi.startIfEnabled()` | `-Dslums.inspect.port` + `DebugTools.featureOn("slums.inspect")` |

Inbound paths that feed these systems: `@bug`/`@reportbug` and `@idea` (`src/main/java/client/command/commands/gm0/ReportBugCommand.java:54,73`, registered at `src/main/java/client/command/CommandsExecutor.java:273-274`), the client log upload opcode `BUG_LOG_UPLOAD(0x373B)` (`src/main/java/net/PacketProcessor.java:194`, `src/main/java/net/opcodes/RecvOpcode.java:30`, handled in `src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:23`), the GM commands `@invite` (`src/main/java/client/command/CommandsExecutor.java:242`) and `@resetpw` (`src/main/java/client/command/CommandsExecutor.java:499`), and the `!qa sf-1` scenario (`src/main/java/soloMapling/qa/QaScenarios.java:106`).

Off-process entry points: `deploy/site/slumsms-site-avatars.service` (runs `soloMapling.site.avatar.AvatarExport` against the site folder every 15 min, `deploy/site/slumsms-site-avatars.timer:6`), `deploy/dashboard/slumsms-dashboard-maps.service` (runs `DashboardMapExport` hourly at `:40`, `deploy/dashboard/slumsms-dashboard-maps.timer:7`), and `deploy/dashboard/slumsms-dashboard-extras@.service`.

## How it works

### 1. Boot starts five independent writers

`Server.init()` (`src/main/java/net/server/Server.java:1038-1044`) starts each subsystem separately; each `startIfEnabled()` returns immediately when its switch is off, so a default build writes only `traderatings` rows and the warn ring.

### 2. `PublicSiteExport` — the public site files

1. `PublicSiteExport.startIfEnabled()` (`src/main/java/soloMapling/site/PublicSiteExport.java:89`) resolves the output directory — `-Dslums.site.dir`, else `Server/data/site` when a `./Server` directory exists, else `data/site` (`src/main/java/soloMapling/site/PublicSiteExport.java:80-87`).
2. It flips recording on in the two in-memory collectors: `SiteFeed.setRecording(true)` and `SiteMobKills.setRecording(true)` (`src/main/java/soloMapling/site/PublicSiteExport.java:96-97`). Before that point the hooks fire but store nothing.
3. Unless `-Dslums.site.mobKills.noDb=true`, it restores 7 days of hourly kill buckets from MySQL and schedules a flush every 300 s (`src/main/java/soloMapling/site/PublicSiteExport.java:98-102`, `SiteMobKills.restore` at `src/main/java/soloMapling/site/SiteMobKills.java:183`, `SiteMobKills.flush` at `src/main/java/soloMapling/site/SiteMobKills.java:217`).
4. `seedSafely()` reads the last `feed.json` back into the rings so a restart does not empty the feed (`src/main/java/soloMapling/site/PublicSiteExport.java:103,219`, `SiteFeed.seed` at `src/main/java/soloMapling/site/SiteFeed.java:504`).
5. Three `scheduleWithFixedDelay` tasks run on one daemon thread named `site-export` (`src/main/java/soloMapling/site/PublicSiteExport.java:94,105-107`): `worldSafely` every 60 s after 30 s, `feedSafely` every 120 s after 45 s, `boardsSafely` every 900 s after 120 s.
6. Each task goes through `safely(what, Supplier)` (`src/main/java/soloMapling/site/PublicSiteExport.java:243`), which counts failures and logs a warning at most every 15 minutes (`WARN_EVERY_MS`, `src/main/java/soloMapling/site/PublicSiteExport.java:65`); a failure in one file never stops the schedule.
7. `writeFeed()` (`src/main/java/soloMapling/site/PublicSiteExport.java:297`) builds `SiteFeed.file(SiteFeed.snapshot(now), now)` and hands it to `AtomicFiles.writeJson`. `snapshot()` (`src/main/java/soloMapling/site/SiteFeed.java:458`) drops items past their keep window (7 days; megaphones 24 h), sorts newest first, caps at `LIMIT=200` and megaphones at `MEGAPHONE_MAX=80` (`src/main/java/soloMapling/site/SiteFeed.java:98,102-104`).
8. `writeWorld()` (`src/main/java/soloMapling/site/PublicSiteExport.java:302`) calls `SiteWorld.file(...)` (`src/main/java/soloMapling/site/SiteWorld.java:83`) with online counts, the event headline, running events, and two kill tables from `SiteMobKills.top` (`src/main/java/soloMapling/site/SiteMobKills.java:143`).
9. `writeBoards()` (`src/main/java/soloMapling/site/PublicSiteExport.java:320`) calls `SiteLeaderboards.file(...)` (`src/main/java/soloMapling/site/SiteLeaderboards.java:104`) with the player/guild/boss/trade rows read by `ServerSources.boards()` (`src/main/java/soloMapling/site/PublicSiteExport.java:431`).
10. `forceExport()` (`src/main/java/soloMapling/site/PublicSiteExport.java:142`) runs `all()` — feed, world, leaderboards in that order (`src/main/java/soloMapling/site/PublicSiteExport.java:269-273`) — on the exporter thread if running, else on a one-shot daemon thread; it works even when the switch is off, which is what `!qa sf-1` relies on (`src/main/java/soloMapling/qa/SiteScenarios.java:33`).

### 3. `TradeRatings` — the only always-on writer

`TradeRatings.start()` (`src/main/java/soloMapling/site/TradeRatings.java:113`) schedules `flushSafely` every 60 s on a daemon thread named `trade-ratings`. `completed`/`fellThrough` (`src/main/java/soloMapling/site/TradeRatings.java:60,72`) accumulate into a `ConcurrentHashMap<Integer, long[2]>` capped at `MAX_PENDING=50_000` (`src/main/java/soloMapling/site/TradeRatings.java:39,98`); `flush` writes one batched upsert per row (`src/main/java/soloMapling/site/TradeRatings.java:35`). `shutdown()` drains once more with a 5 s await (`src/main/java/soloMapling/site/TradeRatings.java:144`). The public-facing view (`privateFile`, `src/main/java/soloMapling/site/TradeRatings.java:203`) is not written to the site folder: `PublicSiteExport.tradeRatingsFile` (`src/main/java/soloMapling/site/PublicSiteExport.java:171`) hands it to `GameBoardWorld.tradeRatings` (`src/main/java/soloMapling/Economy/board/GameBoardWorld.java:139`), which `SiteBoard` writes into its outbox (`src/main/java/soloMapling/Economy/board/SiteBoard.java:391-396`).

### 4. `DashboardExporter` — live.json and the series

1. `DashboardExporter.startIfEnabled()` (`src/main/java/soloMapling/dashboard/DashboardExporter.java:59`) reads `-Dsolomapling.dashboard.dir` (default `reports/dashboard`, `src/main/java/soloMapling/dashboard/DashboardExporter.java:64`) and `-Dsolomapling.dashboard.periodSeconds` (default 60, floored at 15, `src/main/java/soloMapling/dashboard/DashboardExporter.java:65`).
2. `scheduleWithFixedDelay(runner::runSafely, 10, period, SECONDS)` on daemon thread `dashboard-export` at minimum priority (`src/main/java/soloMapling/dashboard/DashboardExporter.java:69-77`).
3. `runOnce(now)` (`src/main/java/soloMapling/dashboard/DashboardExporter.java:165`) reads bots once (`ServerSources.bots`, `src/main/java/soloMapling/dashboard/ServerSources.java:42`), feeds them to the economy model (`DerivedEconomy.observe`, `src/main/java/soloMapling/dashboard/DerivedEconomy.java:37`), and builds the snapshot (`LiveSnapshot.build`, `src/main/java/soloMapling/dashboard/LiveSnapshot.java:54`).
4. It then attaches `chat` (`src/main/java/soloMapling/dashboard/DashboardExporter.java:174`), the `ch` series point (`:176`), `accounts` re-read at most every 5 min (`:177-183`, `ACCOUNTS_EVERY_MS` at `src/main/java/soloMapling/dashboard/DashboardExporter.java:44`), `social` in a try/catch so one failing read leaves the rest (`:185-190`), `wealthIndex` (`:191`), `switches` (`:193`, `EffectiveSwitches.snapshot` at `src/main/java/soloMapling/dashboard/EffectiveSwitches.java:30`), optional `scrollPrices` (`:194-197`) and `exportMs` (`:198`).
5. `live.json` is written atomically (`src/main/java/soloMapling/dashboard/DashboardExporter.java:199`, `AtomicFiles.writeJson` at `src/main/java/soloMapling/dashboard/AtomicFiles.java:21`), `calibration.json` when kill calibration is on (`:200-203`), and the series point is added (`:205`); every fifth run or a day roll saves `series/<day>.json` and `days.json` (`:206-209`, `SeriesStore.save`/`writeIndex` at `src/main/java/soloMapling/dashboard/SeriesStore.java:67,93`).

### 5. `SiteAuthApi` — request/response

1. `startIfEnabled()` (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:105`) returns unless `-Dslums.auth.port` is set (`:106-109`), then requires a secret file (`-Dslums.auth.secretFile`) whose trimmed content is at least `MIN_SECRET=16` (`:38,111`); a short or missing secret logs why and leaves the API off (`:112-115`).
2. It wires `SiteAccounts.forGame()` (`:116`), `PasswordResets.forGame(accounts, secret)` (`:117`) and a `SiteInvites` over `AuthStore.Jdbc` (`:118`), then binds `InetAddress.getLoopbackAddress()` with a 2-thread daemon pool (`:119,153-164`).
3. `serve()` reads at most `MAX_BODY+1 = 4097` bytes (`:37,189`), calls `handle(method, path, authHeader, body)` (`:190`) and always answers `application/json` + `Cache-Control: no-store` (`:196-197`). A runtime failure becomes `500 UNAVAILABLE` (`:191-193`).
4. `handle()` checks `MessageDigest.isEqual` against the secret header `X-Slums-Auth` (`:36,291`) and answers `401 UNAUTHORIZED` before any routing (`:204-206`).
5. `route()` (`:215`) rejects unknown paths with `404 NOT_FOUND`, non-POST with `405`, a null or oversized body with `413`, unparsable JSON with `400` (`:216-229`). Known paths: `/signup`, `/verify`, `/reset/request|status|complete` (`RESET_PATHS`, `:290`), `/invite/create|list|cancel` (`INVITE_PATHS`, `:292`), `/exchange/approve` (`:251`).
6. `/signup` and `/verify` call `SiteAccounts.signup`/`verify` (`src/main/java/soloMapling/site/auth/SiteAccounts.java:167,221`); success also notifies the exchange (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:244-246`). A rate-limited result answers HTTP 429, every other result 200 (`:247`).
7. `/exchange/approve` (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:262`) requires `account_id > 0` and a token matching `[0-9a-f]{8,64}` (`:265`), re-checks the password, verifies it belongs to that account, then calls the exchange; the doc at `:253-261` lists the answers and states the password and token are never logged.
8. `/reset/complete` additionally sets `revoke_sessions: true` in its answer (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:346`); `ResetStore.Jdbc` marks the request `used` and updates `accounts.password` (`src/main/java/soloMapling/site/auth/ResetStore.java:73,75`).
9. Response bodies are built by `body(SiteAccounts.Result)` (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:360-376`): success `{ok:true, account_id, account, gm, gm_level, characters}`, failure `{ok:false, error, rule?}`; transport-level errors use `error(status, code)` (`:378-383`).

### 6. `BotInspectApi` and `QaEventFeed`

`BotInspectApi.startIfEnabled()` (`src/main/java/soloMapling/inspect/BotInspectApi.java:104`) returns early unless `-Dslums.inspect.port` is set and `DebugTools.featureOn("slums.inspect")` holds (`:106-108`), then refuses to start on a non-loopback `-Dslums.inspect.bind` (`:110-114`) or a missing/short secret (`:115-120`). `bindAddress` accepts only `localhost` or a loopback literal (`:169-186`) and `start()` throws unless the address is loopback, binding backlog 16 with a `2 + MAX_POLLERS` pool when the QA feed attached and 2 threads otherwise (`:189-204`). `serve()` closes the request body unread and answers 403 for a non-loopback remote (`:227-231`); `handle()` checks `X-Slums-Inspect` with `MessageDigest.isEqual` (`:246-249,291`), allows GET only (`:250-252`), 404s anything but `/positions`, `/bots/<cid>` and `/maps/<id>` (`:254-256`) and caches each path for `CACHE_MS=2000` (`:66,257-267`). Views come from `InspectViews.bot/map/positions` (`src/main/java/soloMapling/inspect/InspectViews.java:48,157,233`), fed by `GameSources` (`src/main/java/soloMapling/inspect/BotInspectApi.java:304-337`): `/bots/<cid>` returns null for a non-bot (`:310-311`), and `/maps/<id>` reads `getMapFactory().getMaps()` without loading (`:321`) and returns null when no instance exists and nobody is on the map (`:328-330`), so the API never loads a map.

`QaEventFeed.attachIfEnabled(server, secret)` (`src/main/java/soloMapling/inspect/QaEventFeed.java:154`) rides the same listener and adds `/qa/events` (`:61,166`). It refuses to attach on what it decides is live: user.dir under `/opt/mapleroot/MapleRoot Full Repack`, login port 8485, or a DB URL containing `:3309` (`:75-77,122-140`). Pollers long-poll up to 20 s over a 20,000-entry ring (`:64-65`), capped at `MAX_POLLERS=4` (`:72`, 429 above that at `:326`).

### 7. `AvatarExport` — the site's avatar images

`AvatarExport.main` (`src/main/java/soloMapling/site/avatar/AvatarExport.java:101`) is a batch job, not a server thread. `run()` (`:112`) reads the exported `leaderboards.json`, resolves the looks of the names it lists (`names()` at `:162`), renders one PNG per distinct look key (`AvatarRenderer.SIZE=96`, `src/main/java/soloMapling/site/avatar/AvatarRenderer.java:23`), writes `avatars/index.json` (`src/main/java/soloMapling/site/avatar/AvatarExport.java:155-156`) and prunes PNGs no longer referenced and older than `--keep-days` (default 7) (`:157`, `prune` at `:212`).

### 8. `DashboardMapExport` and the extras script

`DashboardMapExport` (`src/main/java/soloMapling/dashboard/DashboardMapExport.java:88`) is a second batch job writing under `<out>/nav/`: `names.json` (`:134`), `graphs/<key>.json` (`:177`), `maps/<id>.json` (`:204`), `state.json` (`:224`), `index.json` (`:330`), `learning/<day>.json` (`:359`) and `learning.json` (`:425`). It is incremental (a map file is skipped when present, `:306`) and needs `-Dwz-path` and `-Dbot.nav.cacheDir` (both set in `deploy/dashboard/slumsms-dashboard-maps.service:13`). The Python `tools/dashboard/extras/slums-dashboard-extras` adds `nav/world.json` (`tools/dashboard/extras/slums-dashboard-extras:263`), which no Java writes.

### 9. Reports: `@bug`, `@idea`, the client log upload

1. `ReportBugCommand.report` (`src/main/java/client/command/commands/gm0/ReportBugCommand.java:87`) validates length against `ReportStore.MIN_LENGTH=5` / `BUG_MAX_LENGTH=500` / `MAX_LENGTH=2000` (`src/main/java/server/reports/ReportStore.java:60-63`).
2. For a bug it builds the context lazily — the supplier is only invoked after the rate-limit checks pass (`src/main/java/client/command/commands/gm0/ReportBugCommand.java:106-107`) — capped at `BugContext.MAX_BYTES=64 KiB` (`src/main/java/server/reports/BugContext.java:41,379`).
3. `ReportStore.submit` (`src/main/java/server/reports/ReportStore.java:205`) rate-limits per account (3 per 10 min, 20 a day) and globally (60 an hour, 300 a day) before building the context (`:66-70,218-224`), then hands the line to the single writer thread; the writer takes an exclusive POSIX lock, re-reads the tail for the last id, writes `context/<ref>.json`, appends one JSON line and `force(true)`s it (`:228-256`). A force failure only warns, because telling the player "failed" would make them file it twice (`:256`).
4. `BugLogUploadHandler.handle` (`src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:27`) validates the nonce (`BugClientLogs.parse`, `src/main/java/server/reports/BugClientLogs.java:100`), scrubs the text, and merges it into the report context (`BugClientLogs.toContext`, `:147`). Uploads need the client cap bit `CAPS_LOG_UPLOAD = 1<<1` (`src/main/java/server/reports/BugClientLogs.java:40,68`).
5. `WarnLogRing.install()` (`src/main/java/server/reports/WarnLogRing.java:56`) attaches a log4j appender to the root logger holding the last `CAPACITY=500` WARN/ERROR lines (`:27`), which `linesFor` filters at report time (`:121`).

## Data

### Output files

| File | Writer | Cadence | Consumer |
|---|---|---|---|
| `<site>/feed.json` | `PublicSiteExport.writeFeed` (`src/main/java/soloMapling/site/PublicSiteExport.java:297`) | 120 s + forced by `!qa sf-1` | website; re-seeded at boot by `SiteFeed.seed` (`src/main/java/soloMapling/site/SiteFeed.java:504`) |
| `<site>/leaderboards.json` | `PublicSiteExport.writeBoards` (`:320`) | 900 s | website, and `AvatarExport` (`src/main/java/soloMapling/site/avatar/AvatarExport.java:166`) |
| `<site>/world.json` | `PublicSiteExport.writeWorld` (`:302`) | 60 s | website |
| `<site>/avatars/<key>.png`, `avatars/index.json` | `AvatarExport.run` (`src/main/java/soloMapling/site/avatar/AvatarExport.java:112`) | 15 min timer (`deploy/site/slumsms-site-avatars.timer:6`) | website |
| `trade-ratings.json` (board outbox) | `SiteBoard` via `PublicSiteExport.tradeRatingsFile` (`src/main/java/soloMapling/site/PublicSiteExport.java:171`, `src/main/java/soloMapling/Economy/board/SiteBoard.java:391-396`) | 15 min board cycle (`src/main/java/soloMapling/Economy/board/SiteBoard.java:63,127`) | site board service |
| `<dashboard>/live.json` | `DashboardExporter.runOnce` (`src/main/java/soloMapling/dashboard/DashboardExporter.java:199`) | `periodSeconds`, default 60 | dashboard web app (`tools/dashboard/web/app.js:822`) |
| `<dashboard>/calibration.json` | same (`:200-203`) | same, only when `KillCalibration.enabled()` | dashboard |
| `<dashboard>/series/<day>.json`, `<dashboard>/daily/<day>.json`, `days.json` | `SeriesStore` (`src/main/java/soloMapling/dashboard/SeriesStore.java:67,73,93`) | every 5th run + day roll | dashboard (`tools/dashboard/web/app.js:555`) |
| `<dashboard>/nav/*` | `DashboardMapExport` (`src/main/java/soloMapling/dashboard/DashboardMapExport.java:134-425`), `slums-dashboard-extras` (`:263`) | hourly at `:40` (`deploy/dashboard/slumsms-dashboard-maps.timer:7`) | dashboard map view |
| `<reports>/reports.jsonl` + `context/<ref>.json` | `ReportStore` (`src/main/java/server/reports/ReportStore.java:205`) | on submit | site `report_service.py` (shared file, exclusive lock) |
| MySQL `site_mob_kills` | `SiteMobKills.flush` (`src/main/java/soloMapling/site/SiteMobKills.java:217`) | 300 s | the same server after a restart |
| MySQL `traderatings` | `TradeRatings.flush` (`src/main/java/soloMapling/site/TradeRatings.java:155`) | 60 s + shutdown | `ServerSources` SQL and the site |

### Database objects

`traderatings` (migration 035, `database/migrations/035-trade-ratings.sql`), `invite_codes` (036, `database/migrations/036-invite-codes.sql`), `password_resets` (037, `database/migrations/037-password-resets.sql`, decoy rows use `account_id 0` and an HMAC of the typed name — `database/migrations/037-password-resets.sql:10-12`), `site_mob_kills` (047, `database/migrations/047-mob-kills.sql`) with its `estimated` column (056, `database/migrations/056-mob-kills-split.sql`).

### Privacy-shaped output

- `AccountsExport` selects only names, timestamps, the first 4 characters of an invite code and counts; the class doc at `src/main/java/soloMapling/dashboard/AccountsExport.java:25-35` states "Never a password, hash, IP, email or full code", and the `REAL` predicate at `:47-48` excludes `SMBotBase`, `autoqa%` and bot character ids. `invite` is truncated at `CODE_PREFIX=4` plus an ellipsis (`:40,124`).
- `BugPrivacy.SENSITIVE` (`src/main/java/server/reports/BugPrivacy.java:16-24`) blocks any server log line mentioning passwords, tokens, salt, hashes, e-mail, IP, MAC, session ids, cookies, `api key`, `account name`, `login X`, IPv4/IPv6 or 32+ hex; `serverLineAllowed` (`:27-29`) is the gate.
- `BugPrivacy.scrubClientText` (`:46`) drops whole client lines matching `DROP_CLIENT_LINE` to `"[line removed]"` (`:31`), rewrites Windows user paths to `$1<user>`, `name=value` pairs to `<redacted>`, and IPs/MACs/e-mails to `<ip>`/`<mac>`/`<email>`; a player's own account name (3+ chars) becomes `<account>`.
- `SiteFeed.isPublic` (`src/main/java/soloMapling/site/SiteFeed.java:147-148`) keeps GMs, synthetic clients and names starting `autoqa` out of every feed item; `SiteLeaderboards.file` applies the same test to players and guilds (`src/main/java/soloMapling/site/SiteLeaderboards.java:71-73,114-116`).
- `SiteFileShape.FORBIDDEN_KEYS` (`src/main/java/soloMapling/site/SiteFileShape.java:17`) fails the shape check if any site file contains `characterid`, `botid`, `is_bot`, `email`, `ip`, `hwid`, `mac`, `password` or any key starting `account` (`:112`).
- `ReportStore` writes its file `rw-r-----` and directory `rwxr-x---` (`src/main/java/server/reports/ReportStore.java:80-81,303-319`); `EffectiveSwitches` hides names containing `secret`, `key`, `pass`, `token` and drops values that look like paths (`src/main/java/soloMapling/dashboard/EffectiveSwitches.java:23,53,87`).

## Switches

| Switch | Type | Default | Effect | Live |
|---|---|---|---|---|
| `slums.site.export` | `-D` | off | starts `PublicSiteExport` (`src/main/java/soloMapling/site/PublicSiteExport.java:58,76`) | on (`docs/release/1.6.4.conf:230`) |
| `slums.site.dir` | `-D` | `Server/data/site` or `data/site` (`src/main/java/soloMapling/site/PublicSiteExport.java:80`) | site output folder | not set in `1.6.4.conf` |
| `slums.site.mobKills.noDb` | `-D` | off | skips restore/flush of `site_mob_kills` (`src/main/java/soloMapling/site/PublicSiteExport.java:99`) | not set |
| `solomapling.dashboard` | `-D` | off | starts `DashboardExporter` (`src/main/java/soloMapling/dashboard/DashboardExporter.java:36,54`) | not in `1.6.4.conf` (was `docs/release/1.6.0.conf:78`) |
| `solomapling.dashboard.dir` | `-D` | `reports/dashboard` (`src/main/java/soloMapling/dashboard/DashboardExporter.java:64`) | dashboard output folder | `1.6.0.conf:78` only |
| `solomapling.dashboard.periodSeconds` | `-D` | 60, min 15 (`src/main/java/soloMapling/dashboard/DashboardExporter.java:65`) | snapshot cadence | `1.6.0.conf:78` only |
| `slums.auth.port` | `-D` | unset = off (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:106`) | site auth listener | 8186 (`docs/release/1.6.4.conf:229`) |
| `slums.auth.secretFile` | `-D` | empty → off (needs ≥16 chars, `src/main/java/soloMapling/site/auth/SiteAuthApi.java:38,111`) | shared secret | `/etc/slums-auth/secret` (`1.6.4.conf:229`) |
| `slums.auth.revoked` / `slums.weblogin.dir` | `-D` | `revoked.txt` beside the latter (`src/main/java/soloMapling/site/auth/SiteAccounts.java:116`) | sign-in revocation list | `/var/lib/slums-login/revoked.txt` (`1.6.4.conf:229`) |
| `slums.inspect` + `slums.inspect.port` | `-D` | off (`src/main/java/soloMapling/inspect/BotInspectApi.java:60-61,104`) | bot inspect API | `1.6.2.conf:253-254` suggests `true` / 8098 |
| `slums.inspect.secretFile` | `-D` | empty → off (`src/main/java/soloMapling/inspect/BotInspectApi.java:62,210-216`) | inspect secret | `1.6.2.conf:255` |
| `slums.inspect.bind` | `-D` | loopback (`src/main/java/soloMapling/inspect/BotInspectApi.java:63,169`) | bind address | not set |
| `slums.qa.feed` | `-D` | off; refused on live (`src/main/java/soloMapling/inspect/QaEventFeed.java:60,110,154-166`) | QA event feed on the inspect listener | off, TEST: unknown |
| `solomapling.reportsFile` | `-D` | `data/reports/reports.jsonl` with a warning (`src/main/java/server/reports/ReportStore.java:115-119`) | report JSONL path | `/var/lib/slums-feedback/reports.jsonl` (`1.6.4.conf:271`) |
| `AUTO_REGISTER_MIN_PASSWORD_LENGTH` | `config.yaml` (`config/ServerConfig.java:42`) | 6 | minimum password for a site sign-up (`src/main/java/soloMapling/site/auth/SiteAccounts.java:156`) | value unknown (config not read) |
| `AUTO_REGISTER_IP_COOLDOWN_MINUTES` | `config.yaml` (`config/ServerConfig.java:44`) | 1 | sign-up cooldown per IP hash (`src/main/java/soloMapling/site/auth/SiteAccounts.java:167`) | value unknown |
| `BCRYPT_MIGRATION` | `config.yaml` (`config/ServerConfig.java:71`) | false | upgrades a legacy hash on successful sign-in (`src/main/java/soloMapling/site/auth/SiteAccounts.java:259`) | value unknown |

TEST's `start-test.sh` is on another host and was not read, so every TEST column above is unknown rather than guessed.

## Invariants

- Site and dashboard writers never take the game lock, never send a packet and never load a map: `PublicSiteExport.ServerSources` queries MySQL and world collections only (`src/main/java/soloMapling/site/PublicSiteExport.java:330`), `DashboardMapExport` skips a map whose file already exists (`src/main/java/soloMapling/dashboard/DashboardMapExport.java:306`), and `BotInspectApi.GameSources.map` reads only already-loaded `MapFactory` entries (`src/main/java/soloMapling/inspect/BotInspectApi.java:321`).
- A writer failure must never affect the game: every scheduled task logs and continues (`src/main/java/soloMapling/site/PublicSiteExport.java:243`, `src/main/java/soloMapling/dashboard/DashboardExporter.java:147`), and both `startIfEnabled` methods swallow a startup `RuntimeException` with "the game is unaffected" (`src/main/java/soloMapling/site/PublicSiteExport.java:113`, `src/main/java/soloMapling/dashboard/DashboardExporter.java:80`).
- Output is published atomically: a temp file in the same directory, `rw-r--r--`, `ATOMIC_MOVE` with `REPLACE_EXISTING`, non-atomic fallback (`src/main/java/soloMapling/dashboard/AtomicFiles.java:33-38`).
- Nothing private reaches a site file: GM/synthetic/`autoqa` characters are filtered at the collector (`src/main/java/soloMapling/site/SiteFeed.java:147`), and `SiteFileShape.shape` is the machine check that no forbidden key slipped in (`src/main/java/soloMapling/site/SiteFileShape.java:25,37,110-112`).
- The two HTTP APIs bind loopback only and compare the secret in constant time (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:153,291`; `src/main/java/soloMapling/inspect/BotInspectApi.java:189-197,291`), and both answer `Cache-Control: no-store`.
- The report file is append-only and shared with the site, so every append takes an exclusive lock over one channel and re-reads the tail for the next id (`src/main/java/server/reports/ReportStore.java:232-235`); closing any descriptor drops a POSIX lock, hence the single channel (`:232-233`).
- Public feeds never carry killer/player ids, only names (`src/main/java/soloMapling/site/SiteFileShape.java:17`); images are keyed by look hash, not by id (`src/main/java/soloMapling/site/avatar/AvatarExport.java:127`, `AvatarLook.cacheKey` at `src/main/java/soloMapling/site/avatar/AvatarLook.java:67`).

## Origin

| Area | Origin | Evidence |
|---|---|---|
| `src/main/java/soloMapling/site/**` | custom | path absent from `/root/workspace/cosmicms/src/main/java` (only 11 upstream roots, no `soloMapling`); oldest commit `e63bd29d3 2026-10-26 "WIP: site export (feed, leaderboards, world)"` |
| `src/main/java/soloMapling/dashboard/**` | custom | same absence; oldest commit `a3d8a6ebf 2026-10-19 "Add the admin dashboard: snapshot exporter, map export and web app"` |
| `src/main/java/soloMapling/inspect/**` | custom | oldest commit `160e7d92d 2026-10-30 "Add a loopback-only read-only bot inspect endpoint (ticket 157)"` |
| `src/main/java/server/reports/**` | custom | oldest commit `927c215a3 2026-10-25 "Log @bug and @idea reports to a durable JSONL file"` |
| `tools/cosmicdiff/classifications.json` | no entries | grep for these paths returns nothing, so no method-level DELIBERATE/BROKEN/RESTORED claim exists for them |

Baseline for "ours vs upstream" is `03d13cb21 "Establish deployable MapleRoot server baseline"`; every commit above postdates it.

## Tests and QA

| Layer | Coverage |
|---|---|
| Unit tests | `src/test/java/soloMapling/site/`: `PublicSiteExportTest`, `SiteFeedTest`, `SiteFeedAchievementTest`, `SiteLeaderboardsTest`, `SiteMobKillsTest`, `SiteWorldTest`, `TradeRatingsTest`; `site/auth/`: `SiteAccountsTest`, `SiteAuthApiTest`, `SiteAuthExchangeTest`, `SiteAuthQaTest`, `SiteInvitesTest`, `InviteCodesTest`, `PasswordResetsTest`, `AuthStoreWorldTest`, `RevokedFileTest`; `site/avatar/AvatarRendererTest` |
| Unit tests | `src/test/java/soloMapling/dashboard/`: `AccountsExportTest`, `DashboardExporterTest`, `DerivedEconomyTest`, `EffectiveSwitchesTest`, `SeriesStoreHistoryTest`, `ServerSourcesTest` |
| Unit tests | `src/test/java/soloMapling/inspect/`: `BotInspectApiTest`, `InspectViewsTest`, `QaEventFeedTest` |
| Unit tests | `src/test/java/server/reports/`: `ReportStoreTest`, `ReportCommandTest`, `BugsCommandTest`, `BugContextTest`, `BugClientLogsTest`, `BugPrivacyTest`, `PlayerTrailTest`, `WarnLogRingTest` |
| auto-QA | `t-sf-1` forced site export (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1134`), `t-bd-1` site board (`:1142`), `t-au-1` invite→signup→signin→reset (`:1248`), `t-iv-1`/`t-iv-2` invites (`:1373,1377`), `t-br-1` `@bug` (`:1401`), `t-nv-3` inspect API (`:1545`) |
| `!qa` | `sf-1` only (`src/main/java/soloMapling/qa/SiteScenarios.java:31`), registered at `src/main/java/soloMapling/qa/QaScenarios.java:106`; it parses each of `PublicSiteExport.FILES` and runs `SiteFileShape.shape` on it (`src/main/java/soloMapling/qa/SiteScenarios.java:67-80`) |

Not covered: no test drives `DashboardMapExport` or the Python `slums-dashboard-extras`; no test asserts the avatar job's pruning; `AvatarExport` has only the renderer unit test; `WarnLogRing` has no test for the loggers that bypass it (chat, trades, BotLog); no test checks that `PublicSiteExport`'s 300 s mob-kill flush survives a real MySQL failure beyond the two catches in `restore` (`src/main/java/soloMapling/site/SiteMobKills.java:183-215`); `QaEventFeed.liveReason` has unit coverage but its real live guard is not exercised on live.

## Known issues

- **s17** (`/root/work/STATUS-1.6.4.md:132`): "Most hunted: split real kills from estimated off-screen kills — the site now labels every count '(est.)' because the server sends one mixed number." The code now emits `counted` and `estimated` per row (`src/main/java/soloMapling/site/SiteWorld.java:176-177`), so the server side of this card looks landed; the site label is the open half. `[INFERENCE]` from the code, not from a passing check.
- **b276** (`/root/work/STATUS-1.6.4.md:78`): a bot's map-scoped PLAN/ROUTE lines never reach the GM's QA feed; triaged S4, out of 1.6.4 scope. Sits in `QaEventFeed`/`QaLog`, not in the export writers.
- **Ticketing state**: `docs/tickets/107-password-reset-requests.md` records the server half built with the site pages outstanding; `docs/tickets/131-bug-reports.md` records the client log upload behind `CAPS_LOG_UPLOAD` with no client setting it — visible in code at `src/main/java/server/reports/BugClientLogs.java:68`.
- **NEW-FINDING:** `src/main/java/soloMapling/dashboard/DashboardExporter.java:202-203` writes `calibration.json` into the dashboard data folder only when `KillCalibration.enabled()`, while `deploy/dashboard/slums-dashboard-extras@.service` and the web app never reference that file (`tools/dashboard/web/app.js` has no `calibration.json` string) — a file written with no reader.
- **NEW-FINDING:** `src/main/java/soloMapling/dashboard/DashboardMapExport.java:306` skips a map whose `maps/<id>.json` already exists, and nothing in the file re-exports it when the nav graph version changes unless `--force` is passed; the map signature at `:165` covers graphs only, so stale `maps/*.json` survive a WZ update.
- **NEW-FINDING:** `src/main/java/soloMapling/site/PublicSiteExport.java:98-102` restores and flushes `site_mob_kills` on the same single-thread executor that writes the three JSON files; a slow or blocked MySQL round trip inside `SiteMobKills.restore`/`flush` delays `world.json`/`feed.json`, and the 60 s world write is the site's most time-sensitive file.

## State

| Area | State | Evidence |
|---|---|---|
| Site export (`PublicSiteExport`, `SiteFeed`, `SiteWorld`, `SiteLeaderboards`) | working | unit tests + auto-QA `t-sf-1` (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1134`) + live switch on (`docs/release/1.6.4.conf:230`) |
| `SiteMobKills` split | working | migration 056 applied in the 1.6.2 line, `counted`/`estimated` emitted (`src/main/java/soloMapling/site/SiteWorld.java:176-177`), `SiteMobKillsTest` present |
| `TradeRatings` | working | `TradeRatingsTest`, always-on boot call `src/main/java/net/server/Server.java:1042`, migration 035 |
| `AvatarExport` | partial | only `AvatarRendererTest`; the job itself has no test and only runs from a systemd timer |
| Dashboard exporter | working | `DashboardExporterTest`, `SeriesStoreHistoryTest`, live cycle logic in `runOnce` (`src/main/java/soloMapling/dashboard/DashboardExporter.java:165-210`) |
| Dashboard map export | unknown | no test; `MAP_FORMAT="f3"` (`src/main/java/soloMapling/dashboard/DashboardMapExport.java:100`) and the incremental skip at `:306` are unverified here |
| Site auth API | working | `SiteAuthApiTest`, `SiteAuthExchangeTest`, `SiteAuthQaTest`, auto-QA `t-au-1`; live port 8186 |
| Password resets | partial | server half tested (`PasswordResetsTest`), site pages per `docs/tickets/107-password-reset-requests.md` |
| Bot inspect API | working | `BotInspectApiTest`, auto-QA `t-nv-3`; off by default, live flag only suggested (`docs/release/1.6.2.conf:253-255`) |
| QA event feed | partial | `QaEventFeedTest`, refused on live by design; b276 open |
| `@bug`/`@idea` reports | working | `ReportStoreTest`, `ReportCommandTest`, `BugContextTest`, `BugPrivacyTest`, auto-QA `t-br-1`, live `-Dsolomapling.reportsFile` (`1.6.4.conf:271`) |
| Client log upload | partial | code and `BugClientLogsTest` exist, but no client sets `CAPS_LOG_UPLOAD` (`src/main/java/server/reports/BugClientLogs.java:68`, `docs/tickets/131-bug-reports.md`) |

## Open questions

- Which TEST flags are actually set? `start-test.sh` lives on mapletest and was not read, so the TEST side of every switch, the TEST site/dashboard folders, and whether the dashboard even runs on TEST are unknown.
- Is the dashboard still deployed to live? `docs/release/1.6.4.conf` no longer carries `-Dsolomapling.dashboard`, while `deploy/dashboard/slums-dashboard-live.nginx.conf` and `extras-live.conf` still point at `/srv/slums-dashboard/data`. The conf comment at `1.6.4.conf:229-231` lists only the site/market/board flags as "kept from 1.6.2", so whether the unit still passes the dashboard flag is not determinable from the repo.
- Does anything read `calibration.json` or `nav/stuck-latest.md`/`nav/bake-latest.md`? `tools/dashboard/web/app.js:490` references the last two as strings; no consumer was found for `calibration.json`.
- Who is expected to call `PublicSiteExport.forceExport()` besides `!qa sf-1`? The only in-repo caller is `src/main/java/soloMapling/qa/SiteScenarios.java:33`.
- The site's own `tools/site/` tree (including `report_service.py`, which shares the reports JSONL and its locking protocol) is not in this repository, so the cross-process lock contract can only be read from the Java side (`src/main/java/server/reports/ReportStore.java:299-379`) and `docs/tickets/131-bug-reports.md:102`.
