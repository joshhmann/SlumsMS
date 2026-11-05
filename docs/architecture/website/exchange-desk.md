# Exchange desk

## Purpose

The NX desk is the one part of the website that moves items or mesos: a signed-in player trades NX against mesos at
SlumsBank's own quote, and the trade happens in the game, not on the web tier. The two halves never share a database or
a socket — the site writes a JSON request file, the game server's exchange thread answers it in another file, and the
page learns the outcome by polling. Everything else on the exchange page (book, tickers, hourly candles, the desk quote)
is a read of files the game writes for the public.

Scope of this page: the request/response channel (`tools/site/exchange_desk.py` ↔ `ExchangeService.Runner` /
`ExchangeSite`), the authentication of a desk request, the desk's UI surface, and the `-Dslums.exchange.desk` switch.
The order book for item tickers and the fixed-price board are separate (see `board.md` / `market-service.md`).

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `GET /api/market/exchange/nx/quote`, `/nx/history`, `/nx/me` | the NX tab's Alpine stores, every 30 s | tools/site/market_app.py:199-202 |
| `POST /api/market/exchange/nx/preview`, `/nx/confirm` | "Preview buy/sell", then "Confirm" | tools/site/market_app.py:203-205 |
| `GET /api/market/exchange/answer/<rid>` | the browser polls it after every write (1.5 s) | tools/site/market_app.py:150 |
| `POST /api/market/exchange/preview|confirm|cancel|refresh` | the item-ticker book (non-desk paths) | tools/site/market_app.py:151-158 |
| `POST /api/login/exchange-approve` | the password box under the confirm button | tools/site/login_service.py:579-580, :732-761 |
| `GET /api/market/exchange/book`, `/item`, `/me`, `/history` | the exchange page (`exchange.js`) | tools/site/market_app.py:143-148 |
| `market/exchange.html` | static build; the panel is spliced in at `NX_MARK` when the desk mode is mock/on | tools/site/sections/market.py:1037-1054 |
| `slums-market.service` | runs `market_app.py` (Flask, 127.0.0.1:8095) with `SLUMS_MARKET_EXCHANGE=on`, `SLUMS_MARKET_NX_DESK=on` | tools/site/slums-market.service:14-27 |
| `slums-site-market.timer` | rebuilds the market pages every 2 min with the same NX mode | tools/site/slums-site-market.timer:10 |
| `ExchangeService.startIfEnabled()` | boot, only with `-Dslums.exchange=true` | src/main/java/soloMapling/Economy/exchange/ExchangeService.java:52-85 |
| `nx-exchange` thread | two schedules: `quickSafely` every 2 s (stat), `tickSafely` every `pollSec` (15 s) | ExchangeService.java:70-77, :122 |
| `-Dslums.exchange.desk=true` | turns the desk on (NX trades at the house quote instead of resting orders) | src/main/java/soloMapling/Economy/exchange/DeskConfig.java:23, :49-51 |

There is **no** background thread in the site process: `desk.sync()` runs only inside an HTTP call
(tools/site/market_app.py:133, :139, :181).

## How it works

**A. The two folders.** `/var/lib/slums-market/inbox` is owned by the site user and read by the game; `/var/lib/slums-market/outbox`
is `root:slums-market` mode 2750, written by the game and read by the site (docs/site/LAUNCH.md:84-86,
src/main/java/soloMapling/Economy/board/SiteBoard.java:59-63, src/main/java/soloMapling/Economy/board/BoardFiles.java:14-18).
Both writers use write-temp-then-rename: `write_atomic` fsyncs and `os.replace`s (tools/site/market_service.py:111-117);
`BoardFiles.write` writes a temp file inside the outbox, chmods `rw-r-----` and `ATOMIC_MOVE`s it (BoardFiles.java:33-56).

**B. One trade, end to end.**

1. The player picks Buy/Sell and an amount on the NX tab and submits the form (`@submit.prevent="preview()"`,
   tools/site/sections/market.py:896-898). `preview` POSTs `{side, nx, character}` — **no price** — to `nx/preview`
   (docs/site/assets/exchange.js:750-765).
2. Flask's `before_request` hook requires the signed session cookie and puts it on `g.session`
   (market_app.py:217-227); `write_body()` requires the `X-Slums: 1` header, a matching `Origin` when one is sent, a body
   under `MAX_BODY` 2048, and a JSON object (market_app.py:53-72). Then `store.rate(account)` allows 30 writes a minute
   (tools/site/market_service.py:208-213) and `desk.sync()` ingests any new answers first (market_app.py:179-190).
3. `Desk.nx_preview` re-checks the sign-in age, the character, the side, refuses any `price` key, and ranges the amount
   (tools/site/exchange_desk.py:572-584). `Desk.ask` writes a row into the site's own SQLite `xreq` table with
   `id = "w" + secrets.token_hex(12)`, `account`, `type`, `createdAt` and `siteAuthAt` (epoch ms of the session's `iat`),
   then `export()` rewrites `inbox/exchange-requests.json` with every still-unanswered request
   (exchange_desk.py:365-386, :346-352).
4. Within 2 s the game thread's `quickSafely` sees the new mtime/size and runs a full `tick`
   (ExchangeService.java:156-164, :166-173). `readRequests` parses the file through `ExchangeSite.requests`
   (ExchangeService.java:205-235; src/main/java/soloMapling/Economy/exchange/ExchangeSite.java:143-212), skips an id it
   has already answered (`if (!answered.add(r.id())) continue;`, ExchangeService.java:222-224), and calls
   `ExchangeSite.answer` → `answer0` (ExchangeSite.java:249-262, :264-310).
5. `answer0` refuses a request older than `MAX_AGE_MS` (10 min) with `STALE` and does not act on it, then applies the
   reauth gate, resolves the character, and dispatches: a `preview` becomes `Exchange.preview(place, site=true)`, which
   with the desk on goes to `deskPreview` — the quote is `desk.quote(side)`, a one-use 12-byte hex token is minted into
   the in-memory `pending` map with a 60 s expiry, and the answer carries `token, expiresAt, price, mid, mesos, desk_nx,
   passwordRequired` (ExchangeSite.java:286-310; src/main/java/soloMapling/Economy/exchange/Exchange.java:990-1062,
   :1081-1100).
6. The answer goes into the `responses` deque, which keeps the newest 500 (ExchangeService.java:226-229, :132-133), and
   because something was answered the whole file `outbox/exchange-responses.json` is rewritten
   (ExchangeService.java:187-190). The book, tickers, history and `nx-desk.json` files are rewritten on every tick
   regardless (:191-202).
7. The browser's `answer(id)` is polling `/api/market/exchange/answer/<rid>` every 1.5 s (3000 ms after an error), gives
   up at 120 s with `NO_ANSWER`, and calls `onSlow` after 15 s (docs/site/assets/exchange.js:128-144). Each poll is an
   HTTP request, so it also drives `desk.sync()`: the site stats the responses file, and only when `(mtime_ns, size)`
   differs does it read it (exchange_desk.py:304-318).
8. `_answer` accepts a row only if that request is still unanswered **and** `r["account"]` equals the account it queued
   (exchange_desk.py:326-341) — the account check is why a file the game wrote cannot answer somebody else's request.
   The row is marked answered; a new `mine` replaces the cached `xmine` row, and a `confirm`/`cancel` sets `stale=1` so the
   next `/nx/me` asks again (:336-340).
9. The answer reaches the waiting poll as `{"pending": false, "answer": {...}}` (exchange_desk.py:467-483); `hold()`
   stores the token and starts a 1 s countdown that stops 5 s early (exchange.js:732-741, :156-159). Confirm POSTs only
   `{token}` (exchange.js:785-806).
10. On the game side that is a second request file entry, answered by `confirm` → `deskFill`: the token is removed first
    (`pending.remove(token)`, one use), the quote is compared with the one held (mismatch → `QUOTE_MOVED` plus a fresh
    token), the desk's own NX/meso balance is checked (`DESK_EMPTY`), the live-wallet path is chosen or the offline
    wallet path, and the ledger batch commits (Exchange.java:943-963, :1107-1152, :1371-1423). The response's `data.fill`
    is what the page prints: "Traded: N NX at P mesos each for M mesos." (exchange.js:790-795).
11. A SELL, or a BUY past the account's password-free 10,000,000 mesos in 12 h, comes back `PASSWORD_REQUIRED` with the
    token and a new expiry (Exchange.java:1631-1655). The page then POSTs the password to `/api/login/exchange-approve`,
    which forwards it to the game's `/exchange/approve`, drops it, and logs only the account and the code
    (tools/site/login_service.py:732-761; src/main/java/soloMapling/site/auth/SiteAuthApi.java:262-288;
    Exchange.java:1606-1624).

**C. UI surface.** One page, `market/exchange.html`, built as a static shell and filled by Alpine
(tools/site/sections/market.py:1037-1054). With the desk on, the NX tab is a quote panel — buy-at/sell-at, rate,
"moved today", the spread, the fee line, a "Prices are updating" banner when the file is stale, "The NX desk is closed
right now", "SlumsBank is buying NX but has none to sell yet" — plus the trade form, a preview box (price, amount, you
pay/get, rate, NX in the desk), the password box, Confirm / "Confirm with password" / "Never mind", and a 7-day hourly
chart (sections/market.py:871-930). The nav link stays hidden until the exchange answers `open` (`data-xc-nav hidden`,
sections/market.py:431-436). Polling is two clocks: the NX desk store runs a 30 s `setInterval` that skips hidden tabs
(exchange.js:601-611), while the book store is `live(API + "book", 10)` — a 10 s refresh that backoff-retries to 5 min on
error and pauses on a hidden tab (exchange.js:185, docs/site/assets/live.js:38-98); a lite answer is patched into the last
whole list, which is refetched every 55 s (exchange.js:192-206).

## Data

| File | Direction | Writer | Reader | Format | When | Expiry / idempotency |
|---|---|---|---|---|---|---|
| `inbox/exchange-requests.json` | site → game | tools/site/exchange_desk.py:346-352 via tools/site/market_service.py:111-117 | ExchangeService.java:205-235 → ExchangeSite.java:143-212 | `{"schema":1,"requests":[{id,account,type,createdAt,siteAuthAt,…}]}` | rewritten after every queued request and every change to the open set | server answers `STALE` past 10 min (ExchangeSite.java:68, :268-270); one answer per id via `answered` (ExchangeService.java:132, :222-224) |
| `outbox/exchange-responses.json` | game → site | ExchangeService.java:187-190 → BoardFiles.java:29-56 | tools/site/exchange_desk.py:304-318 | `{"schema":1,"generated_at","responses":[{id,account,ok,code,message,data}]}` | only on a tick that answered something; newest last, last 500 (ExchangeSite.java:69) | matched by `id` **and** `account` (exchange_desk.py:326-341); site marks `NO_ANSWER` after 600 s (:319-321) |
| `outbox/nx-desk.json` | game → site | ExchangeService.java:196-202 (deleted when the desk is off) | tools/site/exchange_desk.py:529-541 | `{schema,generated_at,open,closed_reason,mid,buy,sell,spread_bps,fee_bps,drift_bps_last_hour,day_cap_pct,moved_today_pct,floor,ceil,desk_nx,selling,min_qty,history[]}` | every tick | stale past 120 s → 503 `PRICES_STALE` (exchange_desk.py:125, :529-534) |
| `outbox/exchange-book.json` | game → site | ExchangeService.java:191 | exchange_desk.py:401-412 | bids/asks/trades, `rate.mesos_per_1000nx` | every tick | read leniently (`rows_of`, market_service.py:120-127) |
| `outbox/exchange-tickers.json`, `exchange-history.json` | game → site | ExchangeService.java:192-193 | exchange_desk.py:389-400, :444-452 | per-ticker summary/limits/events; hourly candles | every tick | none; the page shows the file's own `generated_at` |
| `market.db` → `xreq(id TEXT PK, account, type, body, created, answered, answer)` + index `xreq_open`, `xmine(account PK, at, data, stale)` | site only | exchange_desk.py:266-272, :378-379, :343-344 | same | SQLite | on every ask/answer | rows deleted after 86 400 s (:322); `xmine` refreshed by each `mine` answer, invalidated (`stale=1`) by confirm/cancel (:336-340) |
| `Server/data/…` desk state row (migration `058-nx-desk`) | game only | Exchange.java:540-570 recovery / `Batch.withDesk` | Exchange.java at boot | one row, id 1 | on each hourly desk step | a missing/unreadable row does **not** trade: the desk stays null and the exchange halts with that reason (Exchange.java:566-567) |

Prices cross the boundary at 1,000 NX per unit: the game stores and publishes "mesos per 1,000 NX", the site divides by
`PER_NX = 1000` for every price it shows and multiplies a typed price back before sending
(tools/site/exchange_desk.py:66-72, :78-117, :503; ExchangeSite.java:573-576).

## Switches

| Switch | Where | Default | Effect |
|---|---|---|---|
| `-Dslums.exchange=true` | src/main/java/soloMapling/Economy/exchange/ExchangeConfig.java:38 | off | starts the exchange thread at all; without it nothing here runs |
| `-Dslums.exchange.desk=true` | DeskConfig.java:23, :49-51 | off | NX trades at the house quote; off, the NX order book of the base exchange runs unchanged |
| `-Dslums.exchange.desk.spreadBps` / `.driftBpsPerPct` / `.maxStepBps` / `.dayCapPct` / `.feeBps` / `.minQty` | DeskConfig.java:8-15, :35-37 | 200 / 7 / 50 / 20 / 0 / 100 | the desk's spread, drift and caps |
| `-Dslums.exchange.pollSec` | ExchangeConfig.java:29, :88 | 15 | the full poll; the 2 s stat runs beside it (ExchangeService.java:76-77, :122) |
| `-Dslums.exchange.houseAccount` | ExchangeConfig | 0 | the desk is closed with "no house account" at 0 (Exchange.java:179-190); live conf sets 15 |
| `-Dslums.exchange.siteAuthMaxHours` | NxGuard.java:57-62, :41 | 12 | how old the site sign-in may be for a preview/confirm/cancel/mine; 0 turns the check off |
| `-Dslums.exchange.siteAuthGame` / `.stepUp` / `.stepUpFreeMesos` | src/main/java/soloMapling/Economy/exchange/SiteReauth.java:25-47 | on / on / 10,000,000 | the b005 game-side sign-in record and the password step-up |
| `-Dslums.board.inbox`, `-Dslums.board.outbox` | src/main/java/soloMapling/Economy/board/SiteBoard.java:43-44, :91-100 | `/var/lib/slums-market/{inbox,outbox}` | the folder pair both halves use (shared with the board) |
| `SLUMS_MARKET_EXCHANGE=on` | tools/site/market_app.py:272 | `off` | off, every exchange URL answers 503 `CLOSED` and nothing reaches the game |
| `SLUMS_MARKET_NX_DESK=off|mock|on` | tools/site/exchange_desk.py:138-144; market_app.py:273 | `off` | off: the desk URLs 404 and the NX tab is the order book; mock: fixtures, nothing is written to the inbox; on: the game's file |
| `SLUMS_MARKET_INBOX` / `SLUMS_MARKET_OUTBOX` / `SLUMS_MARKET_DB` | tools/site/market_app.py:266-268 | `/var/lib/slums-market/…` | where the site reads and writes |
| `SLUMS_MARKET_NX_DESK` for the rebuild | tools/site/slums-site-market.service:13 | — | must equal the service's value or the built page flips back to the book |

`-Dslums.exchange.desk=true` **is** in `UNIT_EDITS` (docs/release/1.6.4.conf:271, beside `.exchange=true`,
`.startHalted=true`, `.houseAccount=15`, `.traders.nx=true`, `.traders.nx.worldHour=5000`), and its proof line is
recorded at docs/release/1.6.4.conf:224 ("s44 merged, t-xc-* green and one site desk trade on TEST"). What the repo does
not record is the go/no-go *outcome* of that proof: docs/release/1.6.4.conf:26-28 lists the flag as "still owner-decided"
and docs/architecture/switches.md:63-68 says the same, so a reader cannot tell from the branch whether it is meant to
stay on.

## Invariants

1. **One answer per request id per process.** `answered.add(r.id())` guards the loop (ExchangeService.java:222-224), and
   the test `theRunnerAnswersEachRequestOnceAndWritesTheOutbox` rewrites the same inbox and asserts one order
   (src/test/java/soloMapling/Economy/exchange/ExchangeSiteTest.java:126-157).
2. **The site will not answer itself.** `_answer` drops a row unless it matches an open `xreq` row of the same account
   (exchange_desk.py:326-341).
3. **The site's write is the file, not the DB.** Only `export()` touches the inbox and only `sync()` reads the outbox; both
   run under `store.lock` inside a request (market_app.py:131-141, :179-190).
4. **Old requests are answered, never acted on.** `STALE` short-circuits before the switch (ExchangeSite.java:268-270).
5. **A desk token is one use and expires in 60 s** (Exchange.java:1109, :949-953; `theHeldQuoteIsOneUseAndExpires`,
   ExchangeDeskTest.java:357).
6. **The quote a fill uses is the quote that was previewed**; a moved quote answers `QUOTE_MOVED` with a fresh token and
   moves nothing (Exchange.java:1118-1119, :1155-1168; `quoteMovedAtConfirmCarriesTheFreshQuote`, ExchangeDeskTest.java:485).
7. **Nothing is minted**: a fill needs the desk's own NX or mesos (`DESK_EMPTY`, Exchange.java:1068-1079, :1130).
8. **A halt clears every held token**, so a preview made before a halt can never place after a resume
   (Exchange.java:3021-3027).
9. **Answer files are replaced whole and atomically** (BoardFiles.java:33-56), so a reader never sees half a file; the
   site's `data is not None` guard retries a broken read instead of advancing its stamp (exchange_desk.py:313-316).

## Origin

Custom code, no upstream. Server: `ExchangeSite.java` and `ExchangeService.java` first commit **2a595c2a0**
(2026-10-27, "Scaffold the NX <-> meso exchange behind -Dslums.exchange (ticket 113)"), then a926ab7bd; the desk itself
arrives with `Desk.java` in **b7356ef09** (2026-11-01, "NX desk: NX trades at the house's one quote instead of an order
book"). Branch `164-archreview`. Site: `tools/site/exchange_desk.py` (and its test) first commit **23feff41c**
(2026-10-27, "NX exchange page: the book, the official rate chart, last trades, preview/confirm orders and cancel");
the NX quote panel is **e765618dc** (2026-11-01), the "My NX reads again" fix **f4e57c8c6**, latest **1433fa66f**
(2026-11-05). Branch `site-164-street`.

## Tests and QA

| Test | Path | Covers | Gap |
|---|---|---|---|
| 34 methods, `DeskTest`/`ExchangeHttpTest`/`NxDeskTest`/`NxMockTest` | tools/site/test_exchange_desk.py | preview→inbox→answer round trip (:79); `siteAuthAt` on every request (:93); 12 h sign-in (:99); per-NX prices (:116); rules checked before the game is asked (:146); another account's answer ignored, its request 404 (:160); one `mine` at a time and cancel invalidating it (:171); `NO_ANSWER` after 10 min (:191); five waiting → 429 (:198); book/depth/item/history (:205-:264); broken responses file retried (:273); end-to-end preview+confirm over HTTP (:299); header and session required (:311); `REAUTH` (:318); switched off (:326); NX quote shape (:363), 404 with the desk off (:388), stale/broken desk file (:461, :476), nx preview sends no price (:516), confirm → fill (:543), mock never touches the inbox (:620) | nothing exercises the real server's file; no test for two browsers, a site restart mid-poll, or a >500-response backlog |
| `ExchangeSiteTest` (8) | src/test/java/soloMapling/Economy/exchange/ExchangeSiteTest.java | parser and bad rows (:36); answers against the exchange (:61); one answer per id (:126); character by name (:180); quick check only on change (:212); the full poll sees an unchanged stamp (:248) | not the desk-specific confirm path |
| `ExchangeDeskTest` (34) | …/ExchangeDeskTest.java | spread and every fill at the quote (:172), conservation under random fills (:217), live-wallet fill (:245), wrong character (:265), loading window (:321), one-use token (:357), caps and hard window (:371, :398), `R` survives a restart (:414), closed desk (:432, :452), `DESK_EMPTY` (:467), `QUOTE_MOVED` (:485), NX order refused while the desk runs (:513), the runner writes/removes `nx-desk.json` (:633), unreadable state halts (:654), `nx-desk.json` is the API.md shape (:760), site preview without a price (:818), password step-up (:572) | no multi-process/file-timing test against a real outbox |
| `NxGuardTest` | …/NxGuardTest.java | the 2,500,000–10,000,000 hard window (:230), 50–200 % band (:73), site request needs a 12 h sign-in (:435) | — |
| `ExchangeDeskBotTest`, `ExchangeQaDeskTest` | …/ExchangeDeskBotTest.java, …/ExchangeQaDeskTest.java | bots trade the desk, per-bot/world hourly allowance (:197, :221), QA round trip conserves the ledger (:126) | — |
| Auto-QA | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1277, :1284, :1290, :1296, :1302; Probes160.java:3212+, :3332+ | `t-xc-1`..`t-xc-5`; with the desk on, `t-xc-1` buys 100 NX at the sell quote and sells it back | runs on the game side only; it never walks the website's file channel |

Not covered anywhere: the site's `sync()` racing a big responses file, the 20,000-id `answered` reset, a site process
restart with requests still open, two browser tabs of the same account, and the `PASSWORD_REQUIRED` path end to end
across the two services (the site test stubs it; the server test stops at `confirmSite`).

## Known issues

- **b281** (S4, server): with the desk on, `@nx tickers` / `@nx book` still describe NX as an order book — in-game text only.
- **b277** (S4, server): the desk fill audit line prints `acct=? char=?` instead of naming the trader; ledger correct. Note
  `ExchangeDeskBotTest.java:142` asserts the audit *does* name the trader for bot fills — the two statements disagree.
- **HOLES.md:187**: `ExchangeService.java:76` rewrites `exchange-tickers.json` (and, by :191-193, book/history/desk) every
  15 s even when nothing changed — the page's files are re-serialised continuously.
- **HOLES.md:184**: `Exchange.java:1493-1496` logs "desk step not stored" and keeps the new `R` in memory only, so the
  stored value diverges and a restart silently reloads the older one.
- **HOLES.md:183**: `Exchange.java:669-671` refuses NX orders *before* the idempotency lookup at :677, so a retried
  pre-cutover NX request id answers "the NX book is off" instead of its recorded order.
- **e16** (open, server): more regulars at the desk on both sides. **e09** (later): desk auto top-up from SlumsBank.
  **e12** (later): PELIXIR ticker label. **e14** (later): in-game exchange window.
- **NEW-FINDING (security):** the request file is unsigned. The game trusts the `account` field it reads
  (ExchangeSite.java:34-39, docs/tickets/113-nx-exchange.md "the site is trusted to send the logged-in account id") and
  only re-checks that the character belongs to that account (Exchange.java:2947-2976). Any process able to write
  `/var/lib/slums-market/inbox` can therefore act as any account; the only barrier is the folder mode
  (docs/site/LAUNCH.md:85, BoardFiles.java:14-18). There is no HMAC and no nonce shared between the two halves.
- **NEW-FINDING (liveness):** the site ingests answers only inside an HTTP request (market_app.py:133, :139, :181; no
  background thread in `market_app.py`). A page closed mid-trade leaves the request open until the next page load, and
  the `NO_ANSWER` sweep (exchange_desk.py:319-321) also only runs then — the answer sits unread in the file meanwhile.
- **NEW-FINDING (restart):** the `answered` set is in memory (ExchangeService.java:132) and tokens live in memory too
  (Exchange.java:938-942). After a game restart, a `confirm` still present in the inbox is answered `TOKEN_EXPIRED`,
  which the player reads as "that preview expired" even though they never saw an outcome. It cannot double-spend —
  `pending` is gone — but the message is wrong.
- **NEW-FINDING (restart):** the halt that a post-commit desk failure raises (Exchange.java:1238, :1243-1247) is not
  persisted; only `-Dslums.exchange.startHalted` survives a restart (ExchangeService.java:87-94), so a restart lifts a
  halt that a failed fill caused.
- **NEW-FINDING (replay):** once `answered` passes 20,000 it is cleared wholesale (ExchangeService.java:231-233). Only
  `place` is idempotent through the database (Exchange.java:677-682); a `preview`, `mine` or `cancel` id still listed in
  the inbox can be answered a second time after the clear.
- **NEW-FINDING (loss):** the responses file keeps 500 answers (ExchangeSite.java:69). A site outage long enough to
  cross 500 answers silently drops the oldest; those requests then age into `NO_ANSWER` rather than an outcome.

## State

**working** for the file contract as written, with the caveats above. Evidence: the full path exists on both sides with
matching shapes (ExchangeSite.java:12-64 documents exactly what exchange_desk.py:124-131, :304-352 read and write);
both ends write atomically; 34 site tests plus the server's `ExchangeSiteTest`/`ExchangeDeskTest` cover the round trip,
the one-use token, `QUOTE_MOVED`, `DESK_EMPTY`, the caps and the restart of `R`; the six auto-QA probes `t-xc-1..5` exist.
**Not exercised during this review**: no test, build or deploy was run (read-only pass), so "working" means "the code and
its tests say so", not "observed on TEST".

Is the desk *shipped* on? **written on, intent unrecorded.** `-Dslums.exchange.desk=true` sits in the live `UNIT_EDITS`
(docs/release/1.6.4.conf:271) and the conf asks for a TEST proof at :224, but :26-28 still calls it owner-decided and
docs/architecture/switches.md:63-68 records no outcome. The code is `working`; whether it is *meant* to be enabled on
live is a release-time decision this branch does not answer.

## Open questions

- `docs/architecture/server/economy.md:95-119` already carries a file-contract table for these same files. It is not
  wrong, but it is the game-side view; this page is the site-side one and they should not drift.
- How long may a player keep a held quote across a site deploy? `pending` lives in the game, so a *site* restart is
  harmless, but nothing bounds how many `preview` tokens one account can hold open at once (only the 5-waiting cap and
  30 writes/min apply) — is that intended?
- docs/release/1.6.4.conf:116-118 says a rollback drops the desk table and that it must never be rolled back while the
  desk's escrow buckets are non-zero, but nothing in code refuses that: `Exchange.recover` only reads the table when
  DeskConfig is on, so an operator who drops it with NX still in the escrow buckets gets no error from the code — is the
  "drain the desk first" step enforced anywhere, or is it prose only?
- With `SLUMS_MARKET_NX_DESK=mock`, `/nx/preview` still holds a token and `/nx/confirm` answers from fixtures
  (exchange_desk.py:221-252, :380-383). Nothing marks such an answer as fake in the response body, so a page built in
  mock mode looks identical to a live one — should the API say so?
- The `b277` / `ExchangeDeskBotTest.java:142` disagreement above: which audit line is the defective one?
