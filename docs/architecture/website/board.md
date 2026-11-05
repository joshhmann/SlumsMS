# Board

## Purpose

The Buy/Sell board: a player signs in on the site, posts "I want to buy" or "I want to sell" one item at a fixed price
as one of their own characters, and the post appears both on the website and on a read-only NPC page at the Free
Market entrance. Nothing is a forum: there are no replies, threads or comments anywhere in the code (see Open
questions). The site owns posting and the player-facing account view; the game server owns validation, moderation,
bot offers and the NPC text, and the two sides never talk directly — they exchange JSON files in a shared
inbox/outbox folder.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `GET /api/market/board` | the board page, after sign-in | tools/site/market_app.py:86 |
| `POST /api/market/posts` | the "New post" form | tools/site/market_app.py:96 |
| `POST /api/market/posts/close` | "Take down" on My market, or a GM | tools/site/market_app.py:97 |
| `GET /market/board.html`, `/market/mine.html` | static build, sign-in-guarded | tools/site/sections/market.py:681, :699 |
| nginx `location /api/market/` | proxies to 127.0.0.1:8095, 60 r/m per visitor, 2 KB body | tools/site/slums-login.nginx.conf:5, :18-26 |
| `slums-market.service` | runs `market_app.py` as user `slums-market` | tools/site/slums-market.service:14-27 |
| `SiteBoard.startIfEnabled()` | boot, only with `-Dslums.board=true` | src/main/java/net/server/Server.java:1047 |
| `site-board` thread | 30 s mtime poll, 15 min cycle | src/main/java/soloMapling/Economy/board/SiteBoard.java:114-130 |
| NPC 1092017 script | `cm.sendSimple`/`sendOk` with `SiteBoard.npcPage` text | scripts/npc/1092017.js:11-19 |
| `!board`, `!board remove <id> [reason]` | GM level 2 command | src/main/java/client/command/CommandsExecutor.java:403 |

## How it works

**Reading the board (a page view).**

1. nginx applies `limit_req zone=slumsapi burst=20 nodelay` and proxies `/api/market/` to 8095
   (tools/site/slums-login.nginx.conf:19-22).
2. `before_request signed_in` refuses anything but `/api/market/health` without a valid session cookie, answering
   401 `"Sign in first."` (tools/site/market_app.py:217-227; the open set is tools/site/market_app.py:210).
3. `GET /api/market/board` runs `read(store.board)` under `store.lock` after `store.sync()`
   (tools/site/market_app.py:78-81, :86).
4. `Store.board` opens `outbox/board.json` (tools/site/market_service.py:376-377), and on a missing or broken file
   returns an empty board rather than an error (:378-380). It drops rows whose `kind` is not `WTB`/`WTS` or whose
   `itemId` is not an int (:384), drops rows already past `expiresAt` (:386), truncates the character to 13 chars
   (:388) and joins the item name from `search-index.json` (:389). No account id is ever emitted (:390-391).
5. `boardPage()` fetches it (docs/site/assets/market.js:242) and `drawBoard()` builds every cell with `el()` →
   `textContent` (docs/site/assets/market.js:28-33, :284-295).

**Writing a post.**

1. The form arrives filled from the static shell: kind radios, item picker, qty, price, `Post as <select>`
   (tools/site/sections/market.py:660-669). The select is filled from `me.characters` at runtime
   (docs/site/assets/market.js:236-238).
2. `wireBoard()`'s submit handler refuses a total over 2,147,483,647 client-side (docs/site/assets/market.js:319)
   and POSTs `{kind, itemId, qty, price, character}` (docs/site/assets/market.js:321-323). `api()` adds
   `X-Slums: 1` (docs/site/assets/market.js:21).
3. `write_body()` requires that header (:56-57), requires a matching `Origin` **when one is sent** (:58-61), caps the
   body at `MAX_BODY = 2048` twice (:62-66) and requires a JSON object (:67-73).
4. `store.change` takes the lock, runs `sync()`, then `rate()`, then the write, then `export()`
   (tools/site/market_service.py:497-504).
5. `add_post` (tools/site/market_service.py:434-453) checks kind (:436-437), that the item exists and is tradeable
   (:438), qty 1..32767 (:439), price 1..2,147,483,647 (:440), that qty × price fits one trade (:441-443), then
   `if who not in characters(s): raise Refused("Post as one of your own characters.")` (:444-446), then the 5-open
   cap as 409 (:447-448). The id is `"p" + secrets.token_hex(6)` (:450) and the row is inserted with
   `expires = now + POST_TTL` (:451-452).
6. `export()` rewrites `inbox/posts.json` with every `status='open'` row plus the account id
   (tools/site/market_service.py:274-292, :290).
7. Within 30 s the server's poll notices the new mtime (SiteBoard.java:339-352), `readPosts` parses it
   (`BoardInbox.posts`, src/main/java/soloMapling/Economy/board/BoardInbox.java:25-59) and `BoardRules.validate`
   checks it (src/main/java/soloMapling/Economy/board/BoardRules.java:57-113).
8. `writeBoard()` rewrites all five outbox files (SiteBoard.java:620-631); `BoardOutbox.board` sorts newest first and
   blanks the name of any GM/QA poster (src/main/java/soloMapling/Economy/board/BoardOutbox.java:39-63).
9. The next page view (step 4 above) shows the post. The JS tells the player it will appear "once the game checks
   it, within a minute or two" (docs/site/assets/market.js:325) — true only while the server runs.

**Moderation.** A player's own take-down is `close_post` with the row's account equal to the session's
(tools/site/market_service.py:464-465, `status='closed'`). A GM take-down needs a session signed in within
`GM_FRESH = 12 h` (`gm_fresh`, tools/site/market_service.py:455-457, audit W13); a stale GM cookie gets 401 (:466-467)
and a fresh one sets `status='removed', reason='taken down by a GM'` (:468-469); anyone else gets 403 (:470-471).
In game, `!board remove` reaches `SiteBoard.gmRemove` (SiteBoard.java:202-209) → `Engine.remove` (SiteBoard.java:574-600)
which records the id in the state file, drops it from both lists and rewrites `removed.json`; the site's `_removed`
then flips the row to `status='removed'` on its next sync (tools/site/market_service.py:266-272). Rejections travel
the same way through `rejected.json` (`_rejected`, tools/site/market_service.py:258-264) and both reasons show up on
My market under the post (docs/site/assets/market.js:201-202).

**Offers.** Bots answer posts from the server side only: `offerOnNew` fires on the first sighting
(SiteBoard.java:448-455), `moreOffers` allows a second offer an hour later at 25% (SiteBoard.java:457-467, constants
SiteBoard.java:66-67), `deliver` whispers any online character of the account or leaves a note, re-checking the post is
still open and the bot still has the item or the mesos (SiteBoard.java:495-533). The site never sees an offer until
`offers.json` is read for the bell (tools/site/market_service.py:316-335).

**The NPC.** `npcPage(int)` returns a closed message when the engine is null (SiteBoard.java:160-166); otherwise
`npcPage(List, page)` shows 8 posts newest-first as `#eBuy/Sell Board#n` with `#bWTB#k`/`#rWTS#k`, `#t<item>#`,
`PriceBook.shorthand` and `#d<character>#k`, with `#L0`/`#L1` Next/Previous (SiteBoard.java:168-201).

## Data

| Where | Shape | Written by |
|---|---|---|
| `/var/lib/slums-market/market.db` | `posts(id, account, character, kind, item, qty, price, created, expires, status, reason)`; also `watch`, `alerts`, `cards`, `seen_files`, `notes_seen` | tools/site/market_service.py:79-89 (schema), :451-452 (insert) |
| `inbox/posts.json` | `{schema:1, generated_at, posts:[{id, account, character, kind, itemId, qty, price, createdAt, expiresAt}]}`, open rows only, oldest first | tools/site/market_service.py:274-292 |
| `inbox/alerts.json` | `{schema:1, generated_at, accounts:{"<acct>":{watch:[], alerts:[{id,itemId,dir,price,armed,armedAt}]}}}` | tools/site/market_service.py:283-291 |
| `outbox/board.json` | `{schema:1, generated_at, posts:[{id, character, kind, itemId, qty, price, createdAt, expiresAt}]}`, open posts, newest first, no account | src/main/java/soloMapling/Economy/board/BoardOutbox.java:39-63 |
| `outbox/offers.json` | `{postId, bot, price, t}` per offer, 7 days kept | BoardOutbox.java:67-83; tools/site/market_service.py:316-335 |
| `outbox/rejected.json`, `removed.json` | `{schema, generated_at, rejected|removed:[{id, reason, by, t}]}` | BoardOutbox.java:85, :99 |
| `outbox/alerts-fired.json` | fired alert ids per account | BoardOutbox.java:116 |
| `Server/data/site-board-state.json` | which bots answered which post, GM removals, fired alerts (`slums.board.state` moves it) | SiteBoard.java:102-104, :639-651 |
| `market/board.html`, `mine.html` | static shells, empty `<tbody id="mk-board">`, server-rendered nothing | tools/site/sections/market.py:651-681 |
| `database/migrations/034-site-board-npc.sql` | one `plife` row (world 0 and 1) placing NPC 1092017 on 910000000 | migration file |

Any player-visible field is written as JSON and rendered with `textContent`; the build's `html.escape`
(tools/site/common.py:11) never touches a board row because board rows are not built. `market/` is a private section,
so `check.py` only guards that it stays out of public pages (tools/site/check.py:233-245).

## Switches

| Switch | Default | Home |
|---|---|---|
| `-Dslums.board` | `false` (live: `true`, docs/release/1.6.4.conf:230) | SiteBoard.java:54, :78-80 |
| `-Dslums.board.inbox` / `.outbox` | `/var/lib/slums-market/{inbox,outbox}` | SiteBoard.java:55-61, :91-100 |
| `-Dslums.board.state` | `Server/data/site-board-state.json` | SiteBoard.java:57, :102-104 |
| `-Dslums.board.offerDelayMinMs` / `.offerDelayMaxMs` | 20 s / 120 s | SiteBoard.java:236-237 |
| `-Dslums.board.accountPerHour` / `.serverPer10Min` | 10 / 8 (offer limits) | SiteBoard.java:107-111 |
| `SLUMS_MARKET_PORT` / `--port` | 8095, loopback only | tools/site/market_service.py:615, :621 |
| `SLUMS_MARKET_DB`, `_INBOX`, `_OUTBOX`, `_ITEMS`, `_UNTRADEABLE`, `_KEY_FILE` | `/var/lib/slums-market/…`, `/srv/slums-site/…`, `/etc/slums-login/key` | tools/site/market_service.py:604-615 |
| nginx `limit_req` for `/api/market/` | 60 r/m per visitor, burst 20, body 2 KB | tools/site/slums-login.nginx.conf:5, :19-21 |
| Unit `Environment=` | `SLUMS_MARKET_EXCHANGE=on`, `SLUMS_MARKET_NX_DESK=on` (neither affects the board) | tools/site/slums-market.service:15-18 |

Numbers with no switch: `MAX_OPEN_POSTS = 5`, `POST_TTL = 3 days`, `GM_FRESH = 12 h`, `WRITES_PER_MIN = 30`,
`MAX_BODY = 2048` (tools/site/market_service.py:61-67) and the server's `MAX_OPEN = 5`, `LIFE_MS = 3 days`,
`MAX_QTY = 32767`, `MAX_TOTAL = Integer.MAX_VALUE`, `FUTURE_SLACK_MS = 5 min`
(src/main/java/soloMapling/Economy/board/BoardRules.java:21-28).

## Invariants

- **A post is only ever made as a character of the posting account.** The site checks the session's own list
  (tools/site/market_service.py:444-446 over `characters()` :507-510); the server re-checks the name against the
  `characters` table and its `accountid` (BoardRules.java:72-80) and rejects bot character ids ≥ `BOT_ID_BASE`
  (:77-78). Either check alone would be enough, which is why a stale or lying inbox row cannot impersonate.
- **The server's outbox is the only truth for what the board shows.** The site never renders its own `posts` table on
  the board page — it reads `outbox/board.json` only (tools/site/market_service.py:374-392).
- **No account id ever reaches the browser.** The site emits none (tools/site/market_service.py:390-391, asserted in
  tools/site/test_market_service.py:214-224) and the server writes none (BoardOutbox.java:49-56); a test scans every
  outbox file for the account name (src/test/java/soloMapling/Economy/board/SiteBoardTest.java:127-148).
- **GM and QA posters are not named on the site.** `BoardOutbox.board` blanks the character when
  `SiteFeed.isPublic` says no (BoardOutbox.java:46-48, src/main/java/soloMapling/site/SiteFeed.java:147-154); the JS
  shows "someone" for the empty name (docs/site/assets/market.js:293).
- **A post's lifetime is min(the site's expiry, created + 3 days).** BoardRules.java:52-55, applied at :98.
- **A bad inbox file never wins.** Any parse or lookup failure logs `"INBOX posts.json not read, keeping the last
  board"` and keeps the previous board (SiteBoard.java:413-430); a `RuntimeException` out of a lookup is rethrown as
  `IllegalStateException` on purpose (BoardRules.java:86-89).
- **Outbox writes are atomic and 0640.** A temp file is created inside the target folder, permissioned, then moved
  (src/main/java/soloMapling/Economy/board/BoardFiles.java:33-57; asserted SiteBoardTest.java:296-312).
- **The whole board is behind a session.** Both the JSON API and the pages require sign-in
  (tools/site/market_app.py:217-227; tools/site/slums-login.nginx.conf:45-46).

## Origin

Custom in both repos, same day, site first.

| Path | First commit | Branch |
|---|---|---|
| tools/site/market_service.py | `99a001c04` 2026-10-26 "Slums Market: watchlist, price alerts and a Buy/Sell board for signed-in players" | `site-164-street` |
| docs/site/assets/market.js | `99a001c04` | `site-164-street` |
| tools/site/sections/market.py | `ade606c4c` 2026-10-26 "Market v1 (private): Basil Market pages on sample data, ready for the export" | `site-164-street` |
| tools/site/market_app.py | `770d5e8f4` 2026-10-27 "Start the 1.6.1 site framework: Flask market service and Alpine live panels" | `site-164-street` |
| src/main/java/soloMapling/Economy/board/* | `48f495ffc` 2026-10-26 "Add the server side of the website's Buy/Sell board and price alerts" | `164-archreview` |
| scripts/npc/1092017.js | `48f495ffc` | `164-archreview` |

`market_app.py` is the deployed unit today and reimplements only the HTTP layer of `market_service.py`, kept
behaviourally identical so a rollback is a process swap on the same port (tools/site/market_app.py:19).

## Tests and QA

| Test | Covers |
|---|---|
| tools/site/test_market_service.py:156-172 | posting as a foreign character is refused, bad kind refused, 409 at 5 open, expiry frees room |
| tools/site/test_market_service.py:174-184 | qty × price over the trade cap, and an untradeable item |
| tools/site/test_market_service.py:186-199 | take-down by owner, by a stranger, and by a GM |
| tools/site/test_market_service.py:201-212 | a GM take-down needs a sign-in from the last 12 h |
| tools/site/test_market_service.py:214-224 | expired and unknown-kind rows dropped, no `account` key |
| tools/site/test_market_service.py:226-230 | the 30 writes/minute limiter answers 429 |
| tools/site/test_market_service.py:303-313 | no cookie → 401; missing `X-Slums` → 403; a foreign `Origin` → 403 |
| tools/site/test_market_app.py:46-63 | the Flask app's URL map equals the old service's list, 404 cases, a 413 at `MAX_BODY`, and the two services answering identically |
| src/test/java/soloMapling/Economy/board/BoardRulesTest.java:29-97 | every rejection reason, the 3-day cap, five oldest kept, duplicate ids, a DB failure not being a rejection |
| src/test/java/soloMapling/Economy/board/SiteBoardTest.java:111-148 | GM/QA names blanked; a good post in `board.json`, a bad one in `rejected.json`, no account name in any file |
| SiteBoardTest.java:313-377 | three-day expiry, an unreadable inbox keeping the last board, bot posts, NPC paging newest-first |
| src/main/java/soloMapling/qa/BoardScenarios.java:51 | the in-game QA scenario `bd-1` (own `Engine` on scratch folders) |

Run: `python3 -m unittest discover -s tools/site -p 'test_*.py'` (the site header states it, and it passed here:
21 market tests OK). **Not covered:** no test that a *missing* `Origin` is accepted (only a wrong one); no test that a
signed-out `GET /api/market/board` specifically is refused; no test of the nginx 60 r/m limit (the rate test is
in-app only); no test of HTML/markup injection in the NPC text or the board page; no test at all for replies or
threads, because they do not exist.

## Known issues

- No open card mentions the board. `/root/work/STATUS-1.6.4.md` has only `e14` (:124, the in-game Exchange window,
  "later"), which is a different surface, and `e01` (:111, ticket 175's economy rework) which proposes board changes
  rather than reporting a defect. `docs/tickets/175-economy-rework-flea-lessons.md:77-82` lists unbuilt ideas
  (level gate, listing fee, slots by reputation, category bans, barters) — all future, none implemented.
- NEW-FINDING: the site has no rate limit of its own. `Store.rate` lives in `market_service.py:208-214` and is keyed
  on a per-process dict (`self.writes`, tools/site/market_service.py:181) that is lost on restart and never shared;
  the deployed `market_app.py` does not call it at all for its own layer — the only write throttle in production is
  nginx's `60r/m` per visitor (tools/site/slums-login.nginx.conf:5, :19-20), which a client that rotates its
  `X-Forwarded-For` (or has many addresses) can beat. Reads are unlimited apart from that same limit, so
  `GET /api/market/board` can be polled as fast as nginx allows.
- NEW-FINDING: with the game server off, the board page silently freezes. `Store.board` reads only
  `outbox/board.json` (tools/site/market_service.py:374-392) and never falls back to its own `posts` table, so new
  posts show under "Your posts" but never reach the board (and `board.json`'s `generated_at` goes stale) while
  `-Dslums.board` is off. Nothing in the response or the UI says the game side is stale.
- NEW-FINDING: `X-Slums: 1` is the only cross-site gate for writes; a missing `Origin` is accepted
  (tools/site/market_service.py:558-560, tools/site/market_app.py:58-61). Any non-browser client can post — which is
  probably the intent — but the "Origin must match Host" comment reads as though it always applies.
- NEW-FINDING: no `Content-Security-Policy` and no `X-Frame-Options` on `/market/`
  (tools/site/slums-login.nginx.conf:45-51 sets only `Cache-Control`, `nosniff` and `Referrer-Policy`). Impact today
  is low because every player-written string goes through `textContent` (docs/site/assets/market.js:3, :28-33), but
  the page is framable and the defence is one careless `innerHTML` away from failing.
- NEW-FINDING: the board NPC interpolates the character name into client markup with no escaping —
  `" - #d" + post.character() + "#k"` (src/main/java/soloMapling/Economy/board/SiteBoard.java:187). Today this is
  bounded by the name rules (`[a-zA-Z0-9]{3,12}`, src/main/java/client/Character.java:1133; the same pattern for bot
  names, src/main/java/soloMapling/ArtificialPlayer/BotNames.java:55) and by `BoardRules` requiring the name to be a
  real character (BoardRules.java:72-80), so `#`, `\` and `@` cannot reach the string. It is an unescaped sink that
  depends entirely on rules enforced elsewhere.

## State

**Working.** Live runs `-Dslums.board=true` (docs/release/1.6.4.conf:230); the site half has 21 passing tests
including the identity, moderation, cap and expiry cases, and the server half has six test classes over the same
rules plus an in-game QA scenario. Evidence for the file channel being live: `docs/release/1.6.4.conf:230` names the
switch in `SERVER_SET` and `slums-market.service:14-27` ships the service with the matching inbox/outbox paths.

**Partial** in two named respects: the deployed `market_app.py` carries no in-app write limiter (Known issues), and
the board's own view depends on the game server being up (Known issues). Neither is a live incident; both are code
facts.

## Open questions

- `docs/architecture/website/README.md:50` says "**Board.** Posts and replies under moderation and identity rules".
  There are no replies: no route, no table, no column and no UI for them (grep for `repl` in
  tools/site/market_service.py, tools/site/market_app.py, docs/site/assets/market.js and the whole
  `soloMapling/Economy/board` package finds only `os.replace`, `REPLACE_EXISTING` and `str.replace`). The README line
  should say "posts", not "posts and replies".
- `docs/architecture/website/README.md:30` also gives `board.md` and `exchange-desk.md` to the same mermaid node as
  the exchange desk ("Desk"); the board is not a desk — it is post rows plus an NPC page and shares only the folder.
- Is posting meant to be gated (level, reputation, fee)? Ticket 175 lists all three as ideas
  (docs/tickets/175-economy-rework-flea-lessons.md:77-82) and nothing in the code implements any of them, but the doc
  should not imply they exist.
- Does the unauthenticated visitor have any board view at all? Both the API and `/market/` need a session
  (tools/site/market_app.py:217-227; tools/site/slums-login.nginx.conf:45-46), so the answer today is no — yet the
  board is described as a place "buyers and sellers find you" (SiteBoard.java:176-177), which reads as public.
- `docs/architecture/server/config.md:138` and `docs/architecture/server/economy.md:130` agree the default is off; no
  doc records whether TEST runs it. TEST's `start-test.sh` was not read for this review, so: unknown.
