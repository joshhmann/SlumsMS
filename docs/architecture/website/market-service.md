# Market service

## Purpose

The Slums Market is two halves. The static pages (prices with history, FM listings, recent sales, the NX rate, economy
numbers) are rendered by the site build from JSON the game server writes (`tools/site/sections/market.py:1-13`); the live
parts — watchlist, price alerts, the Buy/Sell board, stamps, cards, the exchange — come from a small HTTP service,
`tools/site/market_app.py`. The halves never call each other: the service reads the game's outbox and writes its inbox
(`tools/site/market_service.py:1-58`), the build reads a different folder.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `slums-market.service` | boot; runs `market_app.py` as user `slums-market` | `tools/site/slums-market.service:14` |
| `slums-site-market.timer` → `.service` | every 2 min; one-shot `build.py --section market` | `tools/site/slums-site-market.timer:10`, `tools/site/slums-site-market.service:14` |
| `PublicMarketExport.startIfEnabled()`, `SiteBoard.startIfEnabled()` | server boot, only with `-Dslums.market.export=true` / `-Dslums.board=true` | `src/main/java/net/server/Server.java:1040`, `:1047` |
| nginx `location /api/market/`, `/market/` | proxy to `127.0.0.1:8095`; sign-in gate for the pages | `tools/site/slums-login.nginx.conf:18-25`, `:45-50` |
| `build.py --private --section market` | operator, or the timer | `tools/site/build.py:238`, `:178-212` |

Everything the service answers, and how old its answer can be:

| Route / page | Inputs | Freshness |
|---|---|---|
| `GET /api/market/health` | none, no session (the only open path) | always 200; reads no file (`tools/site/market_app.py:238`) |
| `GET /api/market/me` | `slums_session` cookie | re-reads changed outbox files per call (`tools/site/market_service.py:294`, `:216-238`) |
| `GET /api/market/board` | cookie | `board.json` as of its `generated_at`; expired rows dropped (`:374-387`) |
| `GET /api/market/cards` | cookie | the service's own rows, never stale (`:473-490`) |
| `GET /api/market/stamps` | cookie | `stamps.json`, rewritten on the game's 15-minute cycle (`:356-372`, `SiteBoard.java:400`) |
| `POST /api/market/watch` | `{itemId}`, ≤ 20 | immediate (`:395-404`) |
| `POST /api/market/alerts` | `{itemId, dir, price}`, ≤ 10 | stored now; fires on the game's 15-minute cycle (`:405-417`, `SiteBoard.java:363`) |
| `POST /api/market/alerts/rearm`, `/delete`, `/seen` | `{id}`, or `{}` for `/seen` | immediate; the badge clears on `/seen` (`:418-433`) |
| `POST /api/market/posts` | `{kind, itemId, qty, price, character}`, ≤ 5 open, 3-day TTL | in the site's list now; on `board.json` after the game's 30 s poll (`:434-453`, `SiteBoard.java:339`) |
| `POST /api/market/posts/close` | `{id}` (GM needs a < 12 h sign-in) | closed now, dropped by the game at its next poll (`:459-472`) |
| `POST /api/market/cards` | `{card}`, 22 secrets + 3 rewards | immediate, never sent to the game (`:491-496`) |
| `GET/POST /api/market/exchange/*`, `/exchange/nx/*` | session, body, `?ticker=`/`?id=`/`?hours=` | `desk.sync()` per call; the desk file is refused past 120 s (`tools/site/exchange_desk.py:532`) |
| `market/*.html` (index, prices, listings, sales, nx, economy, board, mine, cards, stamps, invites, exchange) | the export folder, via `SLUMS_MARKET_DATA` | whole section rebuilt every 2 min (`slums-site-market.timer:10`); listings 120 s, sales/prices 900 s, day files 00:05 UTC (`PublicMarketExport.java:119-124`) |

## How it works

In words: the game writes JSON into a data folder (`PublicMarketExport`, `SiteBoard`) → `build.py --section market`
renders it into static HTML; separately `market_app.py` ⇄ SQLite ⇄ inbox/outbox files exchanges board, watchlist, alert
and stamp data with the running game. The two pipelines meet only in the browser.

**A. Numbers: game file → build → page** (no service involved).

1. The 15-minute job `Runner.quarter()` writes `listings.json` (`PublicMarketExport.java:352`), `sales.json`
   (`:378-380`; `MarketSales.build` `MarketSales.java:30`, newest 500 `:20`), `nx-rate.json` (`:526`; `NxRate.file`
   `NxRate.java:148`, `MIN_TRADES = 5` `:29`), `economy-public.json` (`:430`; `PublicEconomy.file`
   `PublicEconomy.java:85`) and `prices/index.json` (`:512`). A separate 120-second loop rewrites only `listings.json`
   (`:119-121`, `LISTINGS_SECONDS` `:86`); at 00:05 UTC `nightly()` writes `prices/YYYY-MM-DD.json` plus a 12-day
   back-fill (`:82`, `:77`, `writeDay` `:457-463`, `PriceRollup.day` `PriceRollup.java:86`). Every write goes through
   `step(...)` (`:441-446`), which catches `Exception | LinkageError`, records the failure and logs at most every
   15 minutes (`:316`) — it never throws into the game.
2. The timer's `build.py --section market` stages into `out/.stage-market`, renames the live folder to `.old-market`,
   renames the stage in, checks the section, and rolls back on `SystemExit` (`tools/site/build.py:194-212`).
3. `market.build(out, db, data_dir, site_root)` (`tools/site/sections/market.py:1092`) calls `load()` (`:69`),
   `validate()` (`:121`), wipes `market/` (`:1098`), then renders: `build_prices` (`:508`), `build_lists` (`:570`),
   `build_home` (`:631`), `build_board` (`:651`), `build_exchange` (`:1037`), `build_invites`/`build_stamps` (`:1009`,
   `:1002`), `write_summary` (`:1066`), `write_untradeable` (`:1057`).
4. `load()` keeps the four head files plus the newest `HISTORY_DAYS = 30` day files (`:34`, `:77-79`), sets
   `m["sample"]` from any `"sample": true` (`:80`), scrubs in-game text (`:81`, `check.scrub` `tools/site/check.py:90-106`)
   and drops bot-sourced sales (`:85-88`), so only a trade with a real player on a side counts as a sale. `validate()`
   then refuses a schema mismatch, a wrong field type, a printed time that is not a time (`:145-154`) or any private
   field name anywhere in the file (`:105-108`, `:155-158`).
5. Aggregation on the page side: `real_count()` (`:460`) and `item_stats()` (`:471`) use only days with
   `MIN_SAMPLES = 3` (`:30`), take the newest such day as the median and the oldest as the baseline, and print each row's
   own day and span (`:491-499`); `sales_as_of()` prints the export's `generated_at` (`:501-505`).

**B. Board, watchlist, alerts, stamps: page → service → file → game.**

1. `market.js:343-348` loads `market/summary.json` + `market/untradeable.json`, then `load()` calls `api("me")`
   (`:332-341`); a non-200 unhides the "market service, which isn't answering" banner (`sections/market.py:653-655`).
2. `create_app`'s `before_request signed_in` checks the `slums_session` cookie on every path except
   `OPEN = {"/api/market/health"}` (`tools/site/market_app.py:210`, `:217-229`), else 401 "Sign in first.".
3. A GET runs `read()`: `with store.lock: store.sync(); reply(200, dict(fn(), ok=True))` (`market_app.py:78-81`).
   `Store.sync()` (`tools/site/market_service.py:216-238`) expires posts, compares outbox mtimes against `seen_files`,
   re-reads only what changed, skips a half-written file (`continue` on `OSError`/`JSONDecodeError`) and re-exports the
   inbox when anything changed (`:238`). `Store.board()` returns `{"posts": [], "generated_at": None}` for a missing or
   unparseable `outbox/board.json` (`:374-380`) and drops bad rows and expired posts (`:384-387`).
4. A POST goes `write()` → `store.change()` = lock → `sync()` → `rate()` → mutation → `export()`
   (`market_app.py:83-84`, `tools/site/market_service.py:497-505`), so `inbox/posts.json` and `inbox/alerts.json` are
   rewritten atomically after every change (`:274-292`, `write_atomic` `:111`).
5. The game's `poll()` sees the changed mtime within 30 s (`SiteBoard.java:339-356`, `POLL_SECONDS = 30` `:62`) and
   rewrites `board`/`offers`/`rejected`/`removed`/`alerts-fired.json` (`writeBoard` `:619-629`); its 15-minute `cycle()`
   (`:363-387`, `CYCLE_MINUTES` `:63`) adds `trade-ratings.json` (`:389-398`) and `stamps.json` (`:400`) from
   `SlumsStamps.privateFileFromDb` (`src/main/java/server/checkin/SlumsStamps.java:200`).
6. Writer bodies require `X-Slums: 1`, an `Origin` whose netloc equals `Host`, and ≤ 2048 bytes
   (`tools/site/market_app.py:53-71`, `tools/site/market_service.py:555-570`).

**C. When the export is stale or missing.**

| Situation | What happens | Where |
|---|---|---|
| A required head file, or every `prices/*.json`, is missing | `SystemExit("{dir}: market data missing (…); run tools/site/market_sample.py or point --market-data at the export")`, no page is written, the staged folder never goes live, the old pages stay up | `tools/site/sections/market.py:71-75`, `tools/site/build.py:205-211` |
| A file is truncated, is not an object, or a field has the wrong type / a bad time / a private name | `SystemExit("market data refused: …")` or the joined problem list; the live section is restored from `.old-market` | `sections/market.py:45-55`, `:121-158`, `:1094-1097`; `build.py:207-211` |
| The export stops (game down, `-Dslums.market.export` off) but the files stay | the build succeeds and the pages keep the last `generated_at` and print it as "as of …"; nothing compares it to now, so a stale page reads as current — it does not 500 and it does not say "stale" | `sections/market.py:501-505`, `:579` |
| `board.json` missing or broken | `{"posts": [], "generated_at": None}`, page shows "No posts yet." — indistinguishable from an empty board | `tools/site/market_service.py:374-380`, `tools/site/sections/market.py:680` |
| `offers.json`, `trade-ratings.json`, `stamps.json`, `search-index.json` missing | `[]`, `None`, `{"stamps": null}`, bare item ids; My market falls back to `world/traders.json`, stamps say "The game hasn't sent the stamp counts yet." — no error | `market_service.py:316-335`, `:337-354`, `:356-372`, `:159-170`; `sections/market.py:994` |
| `nx-desk.json` older than 120 s | 503 `PRICES_STALE` / "The NX prices are updating. Try again in a minute." | `tools/site/exchange_desk.py:530-533` |
| The service itself is down | pages still render; `market.js` unhides "The board, watchlist and alerts need the market service, which isn't answering." | `docs/site/assets/market.js:332-341`, `sections/market.py:653-655` |

## Data

| Path | Direction | Contract |
|---|---|---|
| `Server/data/market/listings.json` | server → build | `{schema, generated_at, shops, count, listings[{item_id,name,qty,bundles,price,bundle_price,channel,map_id,room,shop_title,seller_name,seller_kind,shop_type}]}` (`PublicMarketExport.java:352`, `MarketListings.java:53`) |
| `…/sales.json` | server → build | head `{schema, generated_at, count, sales}`, rows `{item_id,name,price,qty,source,player_side,hour}` (`MarketSales.java:30`) |
| `…/nx-rate.json` | server → build | `{schema, generated_at, min_trades, days[], today}`; row `{date, mesos_per_1000nx, n_trades, n_cash_resales, n_code_trades, bot_share}` (`NxRate.java:148`) |
| `…/economy-public.json` | server → build | `as_of`, `mesos_total`, `players_share`, `trades_24h`, `player_trades_24h`, `shops_open`, `mesos_removed_7d`, `bots_online_peak`, `detail` (`PublicEconomy.java:85`) |
| `…/prices/YYYY-MM-DD.json` | server → build | per-item `open/close/high/low/median/norm_median/count/units/player_share` (`PriceRollup.java:39`, `:86`); `prices/index.json` lists the dates (`PublicMarketExport.java:512`) |
| `/var/lib/slums-market/inbox/{posts,alerts}.json` | site → game | `posts[{id,account,character,kind,itemId,qty,price,createdAt,expiresAt}]`, `alerts{accounts:{id:{watch[],alerts[]}}}` (`tools/site/market_service.py:274-292`) |
| `/var/lib/slums-market/outbox/*.json` | game → site | `board`, `offers`, `rejected`, `removed`, `alerts-fired` (`SiteBoard.java:619-629`), `trade-ratings`, `stamps` (`:389-400`) |
| `/var/lib/slums-market/market.db` | service only | SQLite WAL: `watch`, `alerts`, `posts`, `seen_files`, `notes_seen`, `cards` (`tools/site/market_service.py:79-88`) |
| `market/summary.json`, `market/untradeable.json`, `market/search-index.json` | build → browser | `{id:{n,m,c,lo,hi,s,d}}` (`sections/market.py:1066-1073`), sorted untradeable ids (`:1057-1062`), `["$", id, name, median]` (`:557-559`) |

Persisted when: the service DB on every write (`:497-505`); the inbox after every change (`:238`); the outbox by the
game's poll/cycle (`SiteBoard.java:355`, `:383`); the export files on their 120 s / 900 s / daily clocks.

## Switches

| Switch | Default | Effect | Live |
|---|---|---|---|
| `-Dslums.market.export` | off (`PublicMarketExport.java:70`) | writes the five export files | on (`docs/release/1.6.4.conf:230`) |
| `-Dslums.market.dir` | `Server/data/market` (`PublicMarketExport.java:98-105`) | where | default |
| `-Dslums.market.listingsSeconds` | 120 (`PublicMarketExport.java:80`) | listings-only loop; `0` disables it | not in `docs/release/1.6.4.conf` → 120 |
| `-Dslums.board` | off (`SiteBoard.java:51`) | the file channel | on (`docs/release/1.6.4.conf:230`) |
| `SLUMS_MARKET_EXCHANGE` | `off` (`tools/site/market_app.py:265-273`) | exchange URLs open vs `CLOSED` | `on` (`slums-market.service:15`) |
| `SLUMS_MARKET_NX_DESK` | `off` (`tools/site/exchange_desk.py:138-144`) | NX desk panel; must match the rebuild (`slums-market.service:16-18`, `slums-site-market.service:13`) | `on` |
| `SLUMS_MARKET_PORT` / `SLUMS_MARKET_{DB,INBOX,OUTBOX,ITEMS,UNTRADEABLE}` | 8095 / `/var/lib/slums-market/*`, `/srv/slums-site/street/search-index.json` (`tools/site/market_app.py:265-273`) | paths | as default (`slums-market.service:19-27`) |
| `SLUMS_MARKET_DATA` / `--market-data` | `cache/market-sample` (`tools/site/sections/market.py:29`) | export folder the build reads | the game's `Server/data/market` (`slums-site-market.service:14-17`) |
| nginx `slumsapi` zone | 60 r/m, burst 20 (`tools/site/slums-login.nginx.conf:5`, `:19-20`) | per-visitor API limit | unknown — the installed conf on 164 was not read |

## Invariants

- One writer per folder: the site only writes `inbox/`, the game only writes `outbox/` (`tools/site/market_service.py:1-20`,
  `SiteBoard.java:24-62`), and every file is replaced atomically (`tools/site/market_service.py:111`, `BoardFiles.java`),
  so a reader never sees half a file.
- No account names on any outbox file; `board.json` hides a name for GM/QA characters and unnamed players
  (`BoardOutbox.java:39-48`), and the service prints "someone" for an empty character (`docs/site/assets/market.js:293`).
- Every write is rate-limited to 30 per account per minute (`tools/site/market_service.py:66`, `:208-214`).
- A post expires after 3 days (`:62`), may only be made as the account's own character (`:434-453`), and a GM take-down
  needs a sign-in from the last 12 hours (`:63`, `:455`, `:459-471`).
- `qty × price` must fit one trade window, `2,147,483,647` (`:65`, `:434-453`); the browser checks the same number
  (`docs/site/assets/market.js:319`).
- A build refuses data rather than guessing: unreadable JSON is `SystemExit("market data refused: …")`
  (`tools/site/sections/market.py:45-55`), a validation problem aborts with the list (`:1097`), and no private field name
  may appear in an export (`:105-108`). Untradeable items are refused twice (`:197-201`, `sections/market.py:1057-1062`).
- Pages never say who is a bot (`sections/market.py:10-12`).

## Origin

**custom.** Server side: `b61bac793` (2026-10-26, "Export the website Market's files: FM listings, sales, daily prices,
NX rate, economy", `PublicMarketExport.java`), `48f495ffc` (2026-10-26, "Add the server side of the website's Buy/Sell
board and price alerts", `SiteBoard.java`), `TradeRatings.java` in `bc9761da3`. Branch `site-164-street`:
`sections/market.py` + `market_sample.py` at `ade606c4c` (2026-10-26, "Market v1 (private): Basil Market pages on sample
data, ready for the export"), `market_service.py` + `slums-market.service` at `99a001c04` (2026-10-26), the rebuild units
at `fc3daae81` ("not installed"), the 2-minute timer at `f1bd72057` (2026-10-27), `market_app.py` at `770d5e8f4`
(2026-10-27, "Start the 1.6.1 site framework: Flask market service"), the nginx block at `93bbca9bb`. Plan: ticket 79
(`docs/tickets/79-basil-market.md`).

## Tests and QA

| Test | Covers |
|---|---|
| `tools/site/test_market_service.py:81-273` | watchlist cap, offers read once, own-character trades, alert fires once + rearm, alert rules, post limits/expiry, trade-window cap, GM/removed take-down, GM 12 h (`:201`), board row filtering (`:214`), 30/min (`:226`), broken outbox retried (`:232`), cards + rewards (`:241`) |
| `tools/site/test_market_service.py:303-355` | signed out, forged cookie, cross-site write refused, cards need the header, stamps show only the account's own row, a session with no character list |
| `tools/site/test_market_app.py:46-63` | the Flask app answers the same URLs and bodies as `market_service.py`, 404 for unknown paths/methods, big bodies; `:104-190` the exchange/NX routes |
| `tools/site/test_site.py:430`, `:477`, `:492`, `:525`, `:558`; `test_build_safety.py:94`, `:108` | the contract fixture loads clean, "as of" and real spans, newest-first sales, the pages render, PT+UTC formatting; a truncated/odd export is refused with a message and a bad time is refused, not crashed |
| `tools/site/test_exchange_desk.py:326`, `:461` | switched-off exchange answers `{"open": false}` and 503 `CLOSED`; a stale desk file says "prices are updating" |
| `src/test/java/soloMapling/Economy/market/PublicMarketExportTest.java:76`, `:127`, `:174`, `:196`, `:241`, `:271`, `:277` | a quarter writes every file and back-fills, gzip'd old days, NX history, the economy floor, a failing source fails only its files, off by default, a listings run writes only listings |
| `src/test/java/soloMapling/Economy/market/MarketWritersTest.java:37-296` | nameless items, GM/QA sellers, FM rooms 1-22, the rollup fields, median, NX rate + the under-5 null, NX cards, the economy floor |
| `src/test/java/soloMapling/Economy/board/` | `BoardAlertsTest` below/above/fires-once/armed, `SiteBoardTest` posts, offers, alerts, GM removal, stamps, ratings |

Not covered: the 60 r/m nginx limit and its burst, the `slums-site-market.timer` schedule, `market.js` DOM behaviour
beyond the PT/UTC string (`test_site.py:558`), and the sample generator's shapes other than through the pages. This
review ran `python3 -m unittest -q tools.site.test_market_service tools.site.test_build_safety` → 27 tests, OK.

## Known issues

- **No card of its own.** Nearest: `w06` (rename `database/` → Slums Street, later), `w10` (more cards in My cards), `L16`
  (the site lists and sells new exchange items), `e01` (economy rework) — `/root/work/STATUS-1.6.4.md:136`, `:137`, `:107`, `:111`.
- Audit card 165's market items (W4, W5, W9, W13, W14, W15, W16) are all recorded fixed
  (`docs/tickets/165-website-audit-1-6-2.md:25-37`).
- **NEW-FINDING:** `/api/market/health` answers `{"ok": true}` unconditionally (`tools/site/market_app.py:238`); it never
  looks at the outbox or the export folder, so a stopped game exporter is invisible to the health check quoted in
  `docs/site/DEPLOY.md:215-217`.
- **NEW-FINDING:** no static page compares its data's `generated_at` to now (`tools/site/sections/market.py:501-505`,
  `:579`), so after the export stops the pages keep their last numbers and read as current; the stamps page and the NX
  desk do detect staleness (`sections/market.py:994`, `tools/site/exchange_desk.py:530-533`) but the price pages do not.
- **NEW-FINDING:** a missing or broken `board.json` and an empty board are the same answer — `{"posts": [],
  "generated_at": None}` (`tools/site/market_service.py:374-380`) — and the page prints "No posts yet."
  (`docs/site/assets/market.js:298`, `tools/site/sections/market.py:680`), so a dead game-side board looks like nobody posting.
- **NEW-FINDING:** `tools/site/slums-market.service:25` points `SLUMS_MARKET_ITEMS` at `street/search-index.json` while
  `docs/site/DEPLOY.md:145-150` still describes units naming `database/search-index.json`; the `Items` fallback
  (`tools/site/market_service.py:137`) hides the difference, so which unit is installed on live is not visible here.
- The "every 15 minutes" wording in `tools/site/build.py:179`/`:193` is stale — the timer runs every 2 minutes
  (`slums-site-market.timer:10`); already recorded in `website/build-and-checks.md`.
- `HOLES.md:152`: `src/main/java/server/checkin/SlumsStamps.java:200-213` exports every account with `stamps > 0`,
  GM-owned accounts included; `tools/site/market_service.py:356-372` only ever returns the caller's own row, so the leak
  stays inside the private outbox file (`slums-market.service:23`).

## State

**working.** `PublicMarketExportTest` and `MarketWritersTest` pin every file shape, the back-fill, the off-by-default
switch and the never-throw path (`PublicMarketExportTest.java:271`, `:241`); both services start from boot behind their
flags (`Server.java:1040`, `:1047`). The 27 service/build-safety tests pass, the Flask app is proven URL-compatible with
the stdlib service (`test_market_app.py:46-63`), and the page build refuses bad data instead of shipping it
(`test_build_safety.py:94-111`). Live has the export and board switches on (`docs/release/1.6.4.conf:230`) and the Flask
unit installed per `docs/site/DEPLOY.md:200-217`. Runtime behaviour on the live host was **not observed** for this review.

## Open questions

- `docs/architecture/website/README.md:50` says "the service serves a cached view" of the prices. It does not: prices,
  listings, sales, the NX rate and the economy are rendered by the build into static HTML
  (`tools/site/sections/market.py:1092-1109`) and the service never opens the export folder. Should that line be
  reworded to "a build task renders the exports every 2 minutes"?
- The export keeps every day file forever (`PublicMarketExport.java:73`) but the site loads only the newest 30
  (`tools/site/sections/market.py:34`, `:77-79`): product decision, or just the largest chart that fits? And
  `listings.json` still carries `seller_kind`/`bot_shops` for the build to drop (`:10-12`) — should the export stop
  emitting them, or does the World section use them?
- The rate limiter is per account and in-process; nothing in the schema records a write count
  (`tools/site/market_service.py:79-88`, `:208-214`), so a restart appears to reset it. Acceptable for 30/min?
- The deployed unit bodies, timers and nginx on 192.168.0.164 are known here only from `docs/site/DEPLOY.md` and
  `docs/site/LAUNCH.md:17-22`; they were not read from the host, so the live configuration is **unknown**.
