# Board

## Purpose

The Buy/Sell board: a signed-in player posts "I want to buy" or "I want to sell" one item at a fixed price as one of
their own characters, and the post shows both on the website and on a read-only NPC page at the Free Market entrance.
It is not a forum — no replies, threads or comments exist anywhere in the code (see Open questions). The site owns
posting and the account view; the game server owns validation, moderation, bot offers and the NPC text. The two never
talk directly: they exchange JSON files through a shared inbox/outbox folder.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `GET /api/market/board` | the board page, after sign-in | tools/site/market_app.py:86 |
| `POST /api/market/posts`, `/posts/close` | the "New post" form; "Take down" on My market, or a GM | tools/site/market_app.py:96-97 |
| `GET /market/board.html`, `/market/mine.html` | static build, sign-in-guarded | tools/site/sections/market.py:681, :699 |
| nginx `location /api/market/`, `/market/` | proxy to 127.0.0.1:8095; `auth_request` on pages | tools/site/slums-login.nginx.conf:18-26, :45-51 |
| `slums-market.service` | runs `market_app.py` as user `slums-market` | tools/site/slums-market.service:14-27 |
| `SiteBoard.startIfEnabled()` | boot, only with `-Dslums.board=true` | src/main/java/net/server/Server.java:1047 |
| `site-board` thread | 30 s inbox poll, 15 min cycle, first at 60 s / 120 s | src/main/java/soloMapling/Economy/board/SiteBoard.java:114-134 |
| NPC 1092017 | `cm.sendSimple`/`sendOk` with `SiteBoard.npcPage` text | scripts/npc/1092017.js:11-19 |
| `!board`, `!board remove <id> [reason]` | GM level 2 command | src/main/java/client/command/CommandsExecutor.java:403 |

## How it works

**Reading (one page view).**

1. nginx applies `limit_req zone=slumsapi burst=20 nodelay` and proxies to 8095 (tools/site/slums-login.nginx.conf:19-22).
   `before_request signed_in` refuses everything but `/api/market/health` without a cookie (tools/site/market_app.py:217-227,
   open set :210, 401 `"Sign in first."` :223-225), then `GET /api/market/board` runs `read(store.board)` under
   `store.lock` after `store.sync()` (:78-81, :86).
2. `Store.board` opens `outbox/board.json` (tools/site/market_service.py:376-377) and on a missing or broken file returns
   an empty board rather than an error (:378-380). It drops rows whose `kind` is not `WTB`/`WTS` or whose `itemId` is not
   an int (:384), rows past `expiresAt` (:386), truncates the character to 13 chars (:388), joins the item name from
   `search-index.json` (:389), and emits no account id (:390-391).
3. `boardPage()` fetches it (docs/site/assets/market.js:242); `drawBoard()` builds every cell with `el()` → `textContent`
   (:28-33, :284-295), printing "someone" for a blanked name (:293) and a row count for freshness (:297).

**Writing (two processes).**

1. The shell supplies the form (tools/site/sections/market.py:660-669); `me.characters` fills `Post as`
   (docs/site/assets/market.js:236-238). `wireBoard()` refuses a total over 2,147,483,647 client-side (:319) and POSTs
   `{kind, itemId, qty, price, character}` (:321-323) with `X-Slums: 1` (:21).
2. `write_body()` requires that header (tools/site/market_app.py:56-57), a matching `Origin` **when one is sent**
   (:58-61), a body under `MAX_BODY = 2048` checked twice (:62-66) and a JSON object (:67-73).
3. `store.change` takes the lock, then `sync()`, `rate()`, the write and `export()` (tools/site/market_service.py:497-504),
   calling `add_post` (:434-453): kind (:436-437), a real tradeable item (:438), qty 1..32767 (:439), price
   1..2,147,483,647 (:440), qty × price inside one trade (:441-443), the identity gate (:444-446), the 5-open cap as 409
   (:447-448); id `"p" + secrets.token_hex(6)` (:450), `expires = now + POST_TTL` (:451-452).
4. `export()` rewrites `inbox/posts.json` with every `status='open'` row, account id included (tools/site/market_service.py:274-292, :290).
5. Within 30 s the poll sees the new mtime (SiteBoard.java:339-357); `BoardInbox.posts` parses it
   (src/main/java/soloMapling/Economy/board/BoardInbox.java:25-59) and `BoardRules.validate` judges it
   (src/main/java/soloMapling/Economy/board/BoardRules.java:57-112).
6. `writeBoard()` rewrites all five outbox files (SiteBoard.java:620-631); `BoardOutbox.board` sorts newest first and
   blanks hidden posters (src/main/java/soloMapling/Economy/board/BoardOutbox.java:39-63). The UI's promise that a post
   appears "once the game checks it, within a minute or two" (docs/site/assets/market.js:325) holds only while the game
   server runs.

**Identity and what is public.** A poster is the session's account id (`s["a"]`, tools/site/market_service.py:530-534)
plus one of its characters: `who` is the form's `character` or the session's `c` (:444) and `characters(s)` is the
session's `cs` list (:507-510), so `if who not in characters(s): raise Refused("Post as one of your own characters.")`
(:445-446) is the gate. The server re-derives the owner from `characters` (BoardRules.java:73-78 via
`GameBoardWorld.owner`, src/main/java/soloMapling/Economy/board/GameBoardWorld.java:107-135) and blanks GM, QA and
synthetic posters (BoardOutbox.java:46-48, src/main/java/soloMapling/site/SiteFeed.java:147-154). Nothing is public: the
API 401s without a session and nginx gates `/market/` with `auth_request /api/auth/check`
(tools/site/slums-login.nginx.conf:45-51); the account id never reaches the browser (tools/site/market_service.py:390-391,
BoardOutbox.java:44-55).

**Escaping and rate limiting.** Board rows carry no HTML: every cell is a DOM node set with `textContent`
(docs/site/assets/market.js:28-33) and the builder emits an empty tbody (tools/site/sections/market.py:678), so
`html.escape` (tools/site/common.py:11) never applies to the board. The one unescaped sink is the NPC line
`" - #d" + post.character() + "#k"` (SiteBoard.java:187), safe only because names are `[a-zA-Z0-9]{3,12}`
(src/main/java/client/Character.java:1133) and `BoardRules` demands a real character. Writes are capped at
`WRITES_PER_MIN = 30` per account in-process (tools/site/market_service.py:208-214) plus 60 r/m per visitor in nginx;
nothing limits reads in the app (Known issues).

**Moderation.** A player's own take-down is `close_post` when the row's account equals the session's
(tools/site/market_service.py:464-465). A GM take-down needs a sign-in within `GM_FRESH = 12 h` (`gm_fresh`, :455-457,
audit W13): stale → 401 (:466-467), fresh → `status='removed', reason='taken down by a GM'` (:468-469), else → 403
(:470-471). In game `!board remove` reaches `SiteBoard.gmRemove` (SiteBoard.java:202-209) → `Engine.remove` (:574-600),
which records the id in the state file and rewrites `removed.json`; the site's `_removed` flips the row on the next sync
(:266-272). Rejections travel through `rejected.json` (:258-264); both reasons show under the post (docs/site/assets/market.js:201-202).

**Offers and the NPC.** Bots answer server-side only: `offerOnNew` on first sighting (SiteBoard.java:448-455),
`moreOffers` allows one more an hour later at 25% (:457-467, constants :66-67), `deliver` whispers any online character
or leaves a note, re-checking the post is open and the bot still has the item or mesos (:495-533); the site sees offers
only in `offers.json`, for the bell (tools/site/market_service.py:316-335). `npcPage(int)` answers "closed" when the
engine is null (SiteBoard.java:160-166); else `npcPage(List, page)` shows 8 posts newest-first as `#eBuy/Sell Board#n`
with `#bWTB#k`/`#rWTS#k`, `#t<item>#`, `PriceBook.shorthand` and `#d<character>#k`, plus `#L0`/`#L1` paging (:168-201).

## Data

| Where | Shape | Written by |
|---|---|---|
| `/var/lib/slums-market/market.db` | `posts(id, account, character, kind, item, qty, price, created, expires, status, reason)` plus `watch`, `alerts`, `cards`, `seen_files`, `notes_seen` | tools/site/market_service.py:79-89, :451-452 |
| `inbox/posts.json` | `{schema:1, generated_at, posts:[{id, account, character, kind, itemId, qty, price, createdAt, expiresAt}]}` — open rows, oldest first, account for the game side only | tools/site/market_service.py:274-292 |
| `outbox/board.json` | `{schema:1, generated_at, posts:[{id, character, kind, itemId, qty, price, createdAt, expiresAt}]}` — open posts, newest first, **no account field** | BoardOutbox.java:39-63 |
| `outbox/offers.json` | `{postId, bot, price, t}` per offer, 7 days kept | BoardOutbox.java:67-83; tools/site/market_service.py:316-335 |
| `outbox/rejected.json`, `removed.json`, `alerts-fired.json` | `{schema, generated_at, rejected\|removed:[{id, reason, by, t}]}`, and fired alert ids per account | BoardOutbox.java:85, :99, :116 |
| `Server/data/site-board-state.json` | which bots answered which post, GM removals, fired alerts (`slums.board.state` moves it) | SiteBoard.java:102-104, :639-649 |
| `market/board.html`, `mine.html` | static shells with an empty `<tbody id="mk-board">`; the builder renders no post | tools/site/sections/market.py:651-681 |
| `database/migrations/034-site-board-npc.sql` | one `plife` row per world (0 and 1) putting NPC 1092017 on 910000000 | migration `034-site-board-npc` |

`market/` is a private section, so `check.py` only guards that it stays out of public pages (tools/site/check.py:233-245);
no build-time escaping applies, because no board row is ever built.

## Switches

| Switch | Default | Home |
|---|---|---|
| `-Dslums.board` | `false` (live: `true`, docs/release/1.6.4.conf:230) | SiteBoard.java:54, :78-80 |
| `-Dslums.board.inbox` / `.outbox` / `.state` | `/var/lib/slums-market/{inbox,outbox}`, `Server/data/site-board-state.json` | SiteBoard.java:55-61, :91-104 |
| `-Dslums.board.offerDelayMinMs` / `.offerDelayMaxMs` | 20 s / 120 s | SiteBoard.java:236-237 |
| `-Dslums.board.accountPerHour` / `.serverPer10Min` | 10 / 8 (offer limits) | SiteBoard.java:107-111 |
| `SLUMS_MARKET_PORT` / `--port` | 8095, loopback only | tools/site/market_service.py:615, :621 |
| `SLUMS_MARKET_DB`, `_INBOX`, `_OUTBOX`, `_ITEMS`, `_UNTRADEABLE`, `_KEY_FILE` | `/var/lib/slums-market/…`, `/srv/slums-site/…`, `/etc/slums-login/key` | tools/site/market_service.py:604-615 |
| nginx `limit_req` for `/api/market/` | 60 r/m per visitor, burst 20, body 2 KB | tools/site/slums-login.nginx.conf:5, :19-21 |
| Unit `Environment=` | `SLUMS_MARKET_EXCHANGE=on`, `SLUMS_MARKET_NX_DESK=on` (neither affects the board) | tools/site/slums-market.service:15-18 |

Numbers with no switch: `MAX_OPEN_POSTS = 5`, `POST_TTL = 3 days`, `GM_FRESH = 12 h`, `WRITES_PER_MIN = 30`,
`MAX_BODY = 2048` (tools/site/market_service.py:61-67); `MAX_OPEN = 5`, `LIFE_MS = 3 days`, `MAX_QTY = 32767`,
`MAX_TOTAL = Integer.MAX_VALUE`, `FUTURE_SLACK_MS = 5 min`, `BOT_ID_BASE = 1e9` (BoardRules.java:21-29).

## Invariants

- **A post is only ever made as a character of the posting account** — the site checks the session's own list
  (tools/site/market_service.py:444-446 over `characters()` :507-510), the server re-checks the name against
  `characters.accountid` (BoardRules.java:72-80) and rejects bot ids ≥ `BOT_ID_BASE` (:77-78), so a stale or lying
  inbox row cannot impersonate.
- **The server's outbox is the only truth for what the board shows** — the board page renders `outbox/board.json`, never
  the site's own `posts` table (tools/site/market_service.py:374-392).
- **No account id reaches the browser**: the site emits none (tools/site/market_service.py:390-391, asserted
  tools/site/test_market_service.py:214-224), the server writes none (BoardOutbox.java:49-56), and a test scans every
  outbox file for the account name (src/test/java/soloMapling/Economy/board/SiteBoardTest.java:127-148). GM and QA
  posters keep their name off **the website** (BoardOutbox.java:46-48); the NPC does not — see Known issues.
- **A post's lifetime is min(the site's expiry, created + 3 days)** — BoardRules.java:52-55, applied at :98.
- **A bad inbox file never wins**: any parse or lookup failure logs `"INBOX posts.json not read, keeping the last board"`
  and keeps the previous board (SiteBoard.java:413-430); a `RuntimeException` out of a lookup is rethrown as
  `IllegalStateException` on purpose (BoardRules.java:84-86).
- **Outbox writes are atomic and 0640** — a temp file inside the target folder, permissioned, then moved
  (src/main/java/soloMapling/Economy/board/BoardFiles.java:33-57; asserted SiteBoardTest.java:296-312).
- **The whole board is behind a session**, API and pages alike (tools/site/market_app.py:217-227;
  tools/site/slums-login.nginx.conf:45-46).

## Origin

Custom in both repos, same day, site first.

| Path | First commit | Branch |
|---|---|---|
| tools/site/market_service.py, docs/site/assets/market.js | `99a001c04` 2026-10-26 "Slums Market: watchlist, price alerts and a Buy/Sell board for signed-in players" | `site-164-street` |
| tools/site/sections/market.py | `ade606c4c` 2026-10-26 "Market v1 (private): Basil Market pages on sample data, ready for the export" | `site-164-street` |
| tools/site/market_app.py | `770d5e8f4` 2026-10-27 "Start the 1.6.1 site framework: Flask market service and Alpine live panels" | `site-164-street` |
| src/main/java/soloMapling/Economy/board/*, scripts/npc/1092017.js | `48f495ffc` 2026-10-26 "Add the server side of the website's Buy/Sell board and price alerts" | `164-archreview` |

`market_app.py` is the deployed unit today and reimplements only the HTTP layer of `market_service.py`, kept
behaviourally identical so a rollback is a process swap on the same port (tools/site/market_app.py:19-27).

## Tests and QA

| Test | Covers |
|---|---|
| tools/site/test_market_service.py:156-184 | posting as a foreign character, a bad kind, 409 at 5 open, expiry freeing room, qty × price over the trade cap, an untradeable item |
| tools/site/test_market_service.py:186-212 | take-down by owner, by a stranger, by a GM, and the 12 h GM sign-in rule |
| tools/site/test_market_service.py:214-239 | expired and unknown-kind rows dropped, no `account` key survives, the 30 writes/min `Refused(429)`, a broken `alerts-fired.json` retried once fixed |
| tools/site/test_market_service.py:303-313 | no cookie → 401; no `X-Slums` → 403; foreign `Origin` → 403 |
| tools/site/test_market_app.py:46-76 | Flask URL map equals the stdlib service's docstring, 404s, 413 at `MAX_BODY`, both answering identically |
| BoardRulesTest.java:29-97 | every rejection reason, the 3-day cap, five oldest kept, duplicate ids, a DB failure not being a rejection |
| BoardInboxTest.java:13-61 | camel/snake field names, ISO and epoch times, a bare array, account-keyed alerts, broken rows skipped |
| BoardStateOffersTest.java:17-42 | 7-day/20-per-post and 500-total made-offer caps, state round-trip, newest first |
| BoardAlertsTest.java:24-78 | below/above firing on the cheapest listing, fire-once until rearm, the first-ten cap |
| SiteBoardTest.java:111-148, :296-377 | GM/QA names blanked, a good post in `board.json` and a bad one in `rejected.json`, no account name in any outbox file; temp-file writes, three-day expiry, an unreadable inbox keeping the last board, bot posts, NPC paging |
| SiteBoardNpcMigrationTest.java:35-47 | the NPC migration's ledger id and a spot clear of portals and other NPCs |
| src/main/java/soloMapling/qa/BoardScenarios.java:104-141 | in-game scenario `bd-1` on its own `Engine` and scratch folders, incl. no account name in `OUTBOX_FILES` |

Run: `python3 -m unittest discover -s tools/site -p 'test_*.py'` (tools/site/test_market_service.py:1-10).
**Not covered:** a *missing* `Origin` being accepted (only a wrong one is tested); a signed-out `GET /api/market/board`
specifically (the 401 tests use `/me` and `/watch`); the nginx 60 r/m limit (the rate test is in-app only); a restart
clearing the limiter; markup injection in the NPC `#d` text or on the board page; replies or threads, which do not exist.

## Known issues

- No open card mentions the board. `/root/work/STATUS-1.6.4.md` has `e14` (:124, the in-game Exchange window, "later" —
  a different surface) and `e01` (:111, ticket 175's economy rework), which proposes board changes rather than reporting
  a defect. `docs/tickets/175-economy-rework-flea-lessons.md:77-82` lists unbuilt ideas (level gate, listing fee, slots
  by reputation, category bans, barters) — none implemented.
- NEW-FINDING: the write limiter is memory-only and reads are not limited in-app. `Store.rate`
  (tools/site/market_service.py:208-214) keeps a 60 s window in the process dict `self.writes` (:181), so a restart
  clears every budget and a second worker keeps its own. Every board write does pass through it — both POST routes map
  to `store.change` (tools/site/market_app.py:96-97 → tools/site/market_service.py:497-504; stdlib :579-580) — so the
  gap is durability and scale, not a missing call. Reads get only nginx's 60 r/m (tools/site/slums-login.nginx.conf:5,
  :19-20), keyed on the right-most `X-Forwarded-For` (tools/site/slums-report.nginx.conf:7-11).
- NEW-FINDING: with the game server off the board silently freezes, and nothing renders `board.json`'s `generated_at`.
  `Store.board` reads only `outbox/board.json` (tools/site/market_service.py:374-392) and never falls back to its own
  `posts` table, so a new post appears under "Your posts" but never on the board while `-Dslums.board` is off; the
  `generated_at` is returned (:392) and ignored, `drawBoard` printing only a row count (docs/site/assets/market.js:297).
  A player cannot tell "nobody has posted" from "the game server has been down for two days".
- NEW-FINDING: name blanking applies to `board.json` but not to the NPC page. `writeBoard` passes
  `world::publicCharacter` into `BoardOutbox.board` (SiteBoard.java:622) so the site hides GM/QA/synthetic posters, while
  `npcPage` prints `post.character()` straight from `board()` (SiteBoard.java:187). A GM who posts, or a bot named
  `autoqa…`, is anonymous on the site and named at the FM entrance.
- NEW-FINDING: the NPC interpolates the character name into client markup unescaped — `" - #d" + post.character() +
  "#k"` (SiteBoard.java:187). Today it is bounded by the name rules (`[a-zA-Z0-9]{3,12}`,
  src/main/java/client/Character.java:1133; bots the same, src/main/java/soloMapling/ArtificialPlayer/BotNames.java:55)
  and by `BoardRules` requiring a real character (BoardRules.java:73-78), so `#`, `\` and `@` cannot reach the string —
  but its safety rests entirely on rules enforced elsewhere.
- NEW-FINDING: `/market/` sends no `Content-Security-Policy` and no `X-Frame-Options`
  (tools/site/slums-login.nginx.conf:45-51 sets only `Cache-Control`, `nosniff`, `Referrer-Policy`). Impact is low today
  because every player string goes through `textContent` (docs/site/assets/market.js:3, :28-33), but the page is framable.

## State

**Working.** Live runs `-Dslums.board=true` (docs/release/1.6.4.conf:230) and `slums-market.service:14-27` ships the
service with matching inbox/outbox paths, so the file channel is live by config. The site half has 21 passing tests
(`python3 -m unittest discover -s tools/site -p 'test_market_service.py'` → OK) covering identity, moderation, cap and
expiry; the server half has six test classes over the same rules plus an in-game QA scenario. **Partial** in two named
respects — the board's view stops updating when the game server is down, and the write limiter is per-process — both
recorded above as code facts, neither a live incident.

## Open questions

- `docs/architecture/website/README.md:50` says "**Board.** Posts and replies under moderation and identity rules".
  There are no replies: no route, no table, no column and no UI for them (grep for `repl` in
  tools/site/market_service.py, tools/site/market_app.py, docs/site/assets/market.js and the whole
  `soloMapling/Economy/board` package finds only `os.replace`, `REPLACE_EXISTING` and `str.replace`). It should say "posts".
- `docs/architecture/website/README.md:27` hangs `board.md` off the mermaid node
  `Desk[tools/site/exchange_desk.py<br/>board.md / exchange-desk.md]`; the board is not a desk — post rows plus an NPC
  page, sharing only the folder.
- Is posting meant to be gated (level, reputation, fee)? Ticket 175 lists all three as ideas
  (docs/tickets/175-economy-rework-flea-lessons.md:77-82) and nothing implements any of them.
- Does an unauthenticated visitor get any board view? Both the API and `/market/` need a session
  (tools/site/market_app.py:217-227; tools/site/slums-login.nginx.conf:45-46), so today the answer is no — yet the NPC
  text calls the board a place where "buyers and sellers find you" (SiteBoard.java:176-177), which reads public.
- `docs/architecture/server/config.md:138` and `docs/architecture/server/economy.md:130` agree the default is off; no doc
  records whether TEST runs it. TEST's `start-test.sh` was not read for this review, so: unknown.
