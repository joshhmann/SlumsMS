# Website privacy rules

`tools/…` and `docs/site/…` are in the site repo (`SlumsMS-site-164-street`, branch `site-164-street`); `src/main/java/…`
is this repo (branch `164-archreview`). "Public" = reachable by anyone who can load the site, itself friends-only
(`robots.txt`, noindex — see `build-and-checks.md`).

## Purpose

Every page is built from JSON files that server-side exporters write, so privacy is decided at three places: what the
exporters put in those files, what the section modules refuse to accept, and what the post-build check re-reads. The rule
is narrow: character and guild names are public, account identity is not (`tools/site/sections/market.py:105`,
`tools/site/check.py:34-43`). E-mail, IP, password, PIC, birthday, HWID and chat never reach a public file, and nothing
says *who is a bot* — bots and players are presented as one server (`tools/site/sections/market.py:1-13`).

## Entry points

| Entry | Starts it | Where |
|---|---|---|
| `build.py --private --world` | full build; each section's `build()` runs its own `validate()` first | `tools/site/build.py:63-69`, `:148-176`, `:228-262` |
| `build.py --section market\|world` | the two 2-minute timers under one `flock` | `tools/site/slums-site-market.service:14` |
| exporters | `-Dslums.site.export=true` (feed/leaderboards/world), `-Dslums.market.export=true` (market files), `-Dslums.board=true` (board outbox) | `src/main/java/soloMapling/site/PublicSiteExport.java:35-50`, `.../Economy/market/PublicMarketExport.java:45-60`, `.../Economy/board/SiteBoard.java:62` |
| `@bug` in game / the report form | append one scrubbed report line to the reports file | `src/main/java/server/reports/ReportStore.java:600-632`, `tools/site/report_service.py:409-441` |
| nginx locations | decide *who* may see `street/ market/ secret/ admin/` and `/admin/data/` | `tools/site/slums-login.nginx.conf:38-64`, `:91-130` |
| `check.py <dir>` / `unittest discover` | a stand-alone re-check / the privacy tests | `tools/site/check.py:295-302`, `docs/site/DEPLOY.md:22` |

## How it works

1. **Exporters drop identity.** `PublicSiteExport` writes feed/leaderboards/world with "Names only: no character ids, no
   account data" (`src/main/java/soloMapling/site/PublicSiteExport.java:35-50`); `SiteLeaderboards.players()` drops staff,
   banned, template and Auto-QA rows, keeping `id` only to join (`.../site/SiteLeaderboards.java:59-75`, `:109`); `SiteFeed`
   refuses any drama item naming a non-bot (`.../site/SiteFeed.java:20-35`), its `validName` test dropping GM and QA names
   (`:148`).
2. **Market/economy exporters narrow further.** `MarketSales` keeps no parties and cuts times to the UTC hour
   (`.../Economy/market/MarketSales.java:10-16`, `:20-23`); `MarketListings` writes `seller_name`, deliberately no
   `seller_kind` (`MarketListings.java:80-81`); `PublicEconomy` blanks human buckets under 5 and drops `topHolders`
   (`.../Economy/market/PublicEconomy.java:9-18`, `:137-145`).
3. **Section load scrubs game text.** `market.load()` calls `check.scrub(m)` on the parsed export, then drops `bot_seed`
   rows (`tools/site/sections/market.py:81`, `:85-88`); `world.load()` does the same after `drop_qa()`
   (`tools/site/sections/world.py:42-60`); `database`/`guide` scrub wording via their own maps
   (`tools/site/sections/database.py:185-192`, `guide.py:60-80`).
4. **Section validate refuses private *fields*.** `market.validate()` walks the key tree of `listings`, `sales`, `nx-rate`
   and `economy-public` and fails on any key whose lowercased name is in `PRIVATE_FIELDS`
   (`tools/site/sections/market.py:105-108`, `:111-118`, `:153-158`); `world.validate()` does the same for
   `feed`/`world`/`leaderboards` plus the bot-label names (`tools/site/sections/world.py:37`, `:121-146`). `build()` turns
   the list into `SystemExit("… refused: …")` (`market.py:1092-1097`, `world.py:754-762`).
5. **Templates escape everything they print.** `esc` is `html.escape` (`tools/site/common.py:11`, `:150`); cells, seller
   cells and feed text all go through it (`tools/site/sections/world.py:466-486`, `:237-242`, `market.py:216-220`). In the
   browser, board text is only set with `textContent` (`docs/site/assets/market.js:3`, `:31`).
6. **The finished folder is re-read as a stranger.** `build.finish()` runs `check.check_all()`
   (`tools/site/build.py:134-142` → `tools/site/check.py:285-292`): forbidden literals and regexes, link rules, noindex,
   then private-section or top-level-whitelist rules; a failure leaves the published folder untouched (`build.py:148-176`).
7. **Reports are scrubbed at both ends.** `BugPrivacy` drops server log lines that mention a secret and masks paths,
   e-mails, IPs and the account name in client text (`src/main/java/server/reports/BugPrivacy.java:21-31`, `:34-43`,
   `:57-70`; called from `BugClientLogs.java:149`, `WarnLogRing.java:125`); the site reader re-scrubs every row and drops
   IP/e-mail/user-agent keys at any depth (`tools/site/report_service.py:113-114`, `:122-127`, `:138-160`); the raw IP is
   never stored, only `sha256(salt + ip)` (`:301-302`, `:309-345`).

## Data

| Data item | Public? | Enforced at | If the exporter changes |
|---|---|---|---|
| character / guild names (incl. `seller_name`, guild `leader`, traders) | yes | `tools/site/sections/market.py:216-220` (esc), `.../world.py:466-486` (esc), `src/main/java/soloMapling/site/SiteLeaderboards.java:122-183` | shown as text; a name carrying a banned word is masked by `check.scrub` (`tools/site/check.py:90-108`) |
| account name / `account_id` / `accountId` | no | `tools/site/sections/market.py:105-108`, `:153-158`; `.../world.py:37`, `:130-133`; `tools/site/report_service.py:113-114` | new key named exactly as a private field → build refused; a *renamed* key (e.g. `seller_email`) passes (NEW-FINDING) |
| IP address | no | `tools/site/check.py:34-43` (IPv4 regex, catches it in output), `.../report_service.py:301-302` (only a salted hash is stored), `.../login_service.py:404-406` | an IPv4 printed in any built file fails the check |
| e-mail address | no | `tools/site/check.py:34-43` (email regex); `.../report_service.py:113-114` `HIDDEN` drops `e-mail`/`email` keys | a built file with an e-mail fails; a runtime API response is not scanned (see Known issues) |
| password / PIC / birthday / HWID / MAC / session / chat | no | `tools/site/sections/market.py:106-108` (`PRIVATE_FIELDS`), `src/main/java/server/reports/BugPrivacy.java:21-31`, `src/main/java/soloMapling/dashboard/AccountsExport.java:24-34` (queries never select them) | any export key with one of those names → build refused |
| bot/player labels (`seller_kind`, `is_bot`, `player_kind`, …) | no | `tools/site/sections/world.py:37`, `:130-133`; `src/main/java/soloMapling/Economy/market/MarketListings.java:80-81` | build refused (World); `market.validate` only refuses the exact name (NEW-FINDING) |
| one person's mesos / holdings | no | `src/main/java/soloMapling/Economy/market/PublicEconomy.java:137-145` (`FLOOR = 5`, `topHolders` removed) | a bucket under 5 people arrives as nulls plus `privacy.suppressed`; nothing re-checks at build time |
| sale time to the minute | no | `src/main/java/soloMapling/Economy/market/MarketSales.java:20-23` (truncated to the hour) | an unrounded `hour` still passes `validate` if it is a valid time (`tools/site/sections/market.py:142-146`) |
| server commit / host name / ports / local paths | no | `tools/site/check.py:22-31` (30 literals), `:34-43` (`.164`, "port 164", "164 box"), `tools/site/sections/world.py:378-385` (version only, regex-shaped) | fails the build |
| item / map / mob ids and names in ranks, hot list, traders | yes | `tools/site/sections/world.py:713-751`; `src/main/java/soloMapling/site/SiteLeaderboards.java:164-169` (`TRADER_MIN = 5`) | shown as data; no identity attached |
| `ip_hash` in the reports file | no (staff model), never on a public page | `tools/site/report_service.py:113-114` drops it from rows; the jsonl keeps it (`:309-345`) and nginx gates the reader (`tools/site/slums-login.nginx.conf:118-130`) | a staff row would need the field added back to `STAFF_FIELDS` |
| reset-request `contact` (player-typed e-mail or text) | no — GM-level-3 only | `src/main/java/soloMapling/dashboard/AccountsExport.java:142-159` writes it to `/admin/data/`; `tools/site/slums-login.nginx.conf:108-117` gates that path | appears in the dashboard snapshot the moment the exporter adds it; nginx is the only gate |

## Switches

| Switch | Default | What it decides |
|---|---|---|
| `-Dslums.site.export`/`-Dslums.site.dir` | off / `Server/data/site` | whether feed, leaderboards and world are written (`src/main/java/soloMapling/site/PublicSiteExport.java:58-61`) |
| `-Dslums.market.export`/`-Dslums.market.dir` | off / `Server/data/market` | the market export (`.../Economy/market/PublicMarketExport.java:70-71`) |
| `-Dslums.board` | off | the board outbox the public board API reads (`.../Economy/board/SiteBoard.java:62`) |
| `SLUMS_MARKET_DATA` / `SLUMS_SITE_DATA` | `cache/market-sample` / `cache/site-sample` | which export folder a build reads (`tools/site/sections/market.py:29`, `.../world.py:31`) |
| `--private` / `--world` / `--section` | public-only build | whether the private sections and World exist at all (`tools/site/build.py:63-69`) |
| `--staff-port` (`SLUMS_REPORT_STAFF_PORT`) | `0` = off | the staff reader listener; the public listener never serves staff routes (`tools/site/report_service.py:517-531`, `:409-418`) |
| `--salt-file`/`--ip-salt`/`--proxy-hops` | `/etc/slums-report/salt`, `/etc/slums-login/ip-salt`, `1` | how the IP is hashed and which X-Forwarded-For entry is believed (`report_service.py:517-520`, `login_service.py:804`, `:497-501`) |
| `-Dsolomapling.dashboard.dir` | `reports/dashboard` | where the dashboard snapshots (accounts, resets, reports) are written |

## Invariants

Each rule below is a prohibition plus the code that enforces it; rule 11 is the prohibitions that exist only as prose.

1. **A private field name anywhere in a market or world export stops the build** (`market.py:153-158`,
   `world.py:130-133`) — exact names only, `prices/*.json` unwalked (Known issues).
2. **Every value a page prints is escaped.** `tools/site/common.py:11`, `:150`; cells
   `tools/site/sections/world.py:466-486`; sellers `tools/site/sections/market.py:216-220`.
3. **Game-written text can never fail a rebuild and can never carry a banned word out.** `check.scrub`
   (`tools/site/check.py:90-108`) masks forbidden words, turns e-mail/IPv4 into `[hidden]`, rewrites the `164` pattern;
   applied by `market.load` (`tools/site/sections/market.py:81`) and `world.load` (`tools/site/sections/world.py:58`).
4. **The site as built contains no infrastructure string.** 30 literals plus 5 patterns, on every build
   (`tools/site/check.py:22-31`, `:34-43`, `:132-161`).
5. **A public build cannot contain a sign-in section, nor link to one.** `tools/site/check.py:233-250`.
6. **A private build may publish only a whitelisted top level or a nginx-guarded section.** `tools/site/check.py:253-264`.
7. **Nothing is indexed.** `docs/site/robots.txt`, meta tag (`tools/site/common.py:86`), `tools/site/check.py:267-280`.
8. **A failed check never publishes and never leaves a tarball.** `tools/site/build.py:134-142`, `:148-176`.
9. **The raw IP is never stored or logged; only `sha256(salt + ip)`.** `tools/site/report_service.py:301-302`;
   `login_service.py:408-413` sends only `ipHash`.
10. **Human aggregates under 5 people are blanked; `topHolders` never ships.**
    `.../Economy/market/PublicEconomy.java:20`, `:137-145`.
11. **Prose-only, no enforcing code:** "sales are delayed at least 1 h", "no per-item bot/player split" and "add a privacy
    lint to check.py for these" (`docs/design/track-d-plan.md:216-226`) — `MarketSales.java:20` only truncates to the
    hour, `PriceRollup.java:69-70` still writes `player_share`, `check.py` has no `MIN_SAMPLES`/`FLOOR`/`TRADER_MIN`. The
    comment "Real personal information never goes on the site" (`market.py:105`) is prose backed by rule 1's code.

## Origin

Both repos are custom (no base-project ancestry for any of this code).

| File | First commit | Message |
|---|---|---|
| `tools/site/check.py`, `build.py`, `test_site.py` | `74099f82c` 2026-10-24 | Add a public website: home, 1.5.0 patch notes and the player guide |
| `tools/site/sections/market.py` | `ade606c4c` 2026-10-26 | Market v1 (private): Basil Market pages on sample data, ready for the export |
| `tools/site/sections/world.py` | `c5e619de7` 2026-10-26 | World pages (live world, feed, rankings, economy); Database renamed Slums Street |
| `tools/site/report_service.py` / `login_service.py` | `5122b392d` / `93bbca9bb` | the report form and receiver; sign-in with a game account |
| `tools/site/sections/admin.py` | `ea44482d1` 2026-10-28 | Staff dashboard on the website at /admin/, for GM level 3 and up |
| `tools/site/test_build_safety.py` | `f2b2abe9d` 2026-10-30 | Site build: never wipe the site before a good build, refuse broken exports (W2, W4, W5) |
| `src/main/java/soloMapling/site/*` | `e63bd29d3` 2026-10-26 | WIP: site export (feed, leaderboards, world) |
| `src/main/java/soloMapling/Economy/market/*` | `b61bac793` 2026-10-26 | Export the website Market's files: FM listings, sales, daily prices, NX rate, economy |
| `src/main/java/soloMapling/dashboard/AccountsExport.java` | `71663fe85` 2026-10-26 | Add an Accounts panel to the staff dashboard |
| `src/main/java/server/reports/BugPrivacy.java` | `3d66a79be` 2026-10-28 | @bug files one site report with versions, context, trail and log lines |

The owner's "character and guild names are fine, emails and IP addresses are not" (2026-10-26) is quoted in
`tools/site/check.py:34`; the "one server" bot-label rule of that day in `tools/site/sections/market.py:9-12`; `seller_kind`
left the listings export then because the build refused it (`docs/design/track-d-plan.md:367`).

## Tests and QA

With only these two repos, a reviewer verifies "no private data on the site" in five steps:

1. Read `tools/site/check.py:22-84` — the whole vocabulary (30 literals, 5 patterns, ALLOW, WORD_OK, GAME_TEXT) is
   constants at the top, one screen.
2. `python3 tools/site/check.py dist/site-private` — clean prints `site check: 0 problem(s) in <dir>`
   (`check.py:295-302`); otherwise every hit prints as `file:line`.
3. `grep -rn "check.scrub" tools/site/` — two call sites (`sections/market.py:81`, `sections/world.py:58`) plus the avatar
   index (`:77`); game-derived text must pass through one of them.
4. `grep -rn "PRIVATE_FIELDS" tools/site/sections/` — two sets (`market.py:106`, `world.py:37`) and two walks
   (`market.py:153-158`, `world.py:130-133`); compare against every export file the build reads.
5. `python3 -m unittest discover -s tools/site -p 'test_*.py'` — the tests below, `test_build_safety.py` included.

| Test | Covers | Gap |
|---|---|---|
| `tools/site/test_site.py:197-226` | 22 literals (host, ports, `ssh root@164`, `Password`, `autoqa`, brand names) fail; `Lv. 164`/item `1640001`/quest text pass; a dotted LAN address ending `.164` and "my password is" fail; `ALLOW` is one exact literal | only the listed literals |
| `tools/site/test_site.py:92-94` | a full build has zero forbidden strings and zero broken links | sample data, not live exports |
| `tools/site/test_site.py:76-81`, `:417-428` | robots.txt `Disallow: /` + noindex; `account_id` → "private field"; bad price; wrong schema | only the four walked files |
| `tools/site/test_site.py:1083-1103`, `:1276-1285` | `is_bot`/`account_id`/unknown kind/wrong schema refused; no `>bot<` on World pages; `ScrubTest` masks words and `[hidden]`s e-mail/IP while leaving `qty: 164` | exact key names; page text not export |
| `tools/site/test_report.py:215-225`, `:318-380` | raw IP absent, 64-hex `ip_hash`, mode `0o640`; staff rows carry no `ip_hash`/e-mail/`accountId`/UA; public port 404s staff routes | — |
| `tools/site/test_build_safety.py:31-47`, `:94-130` | a failed check keeps the published site, removes tarball + staging; truncated sales, a non-time `hour`, a broken world file are "refused" | — |

Not covered: an e-mail through a *renamed* key; a private key inside `prices/*.json`; runtime JSON from
`market_service.py`/`exchange_desk.py` (the check reads built files only); the 1 h sales delay (no code).

## Known issues

- `docs/tickets/165-website-audit-1-6-2.md:32` — **W11 partial**: `slums-report.nginx.conf` keys its rate-limit zone on
  the raw `$http_x_forwarded_for` while `report_service.py:354` ignores left-most entries; one is wrong.
- `docs/architecture/HOLES.md:152` — `server/checkin/SlumsStamps.java:200-213` exports every account with stamps > 0, GM
  accounts included, into the board file the site reads.
- `docs/architecture/HOLES.md:335-339` — export-surface defects that leak nothing: one executor delays
  `world.json`/`feed.json` (`PublicSiteExport.java:98-102`), an unused `calibration.json`
  (`DashboardExporter.java:202-203`), stale maps (`DashboardMapExport.java:306`).
- No STATUS-1.6.4 card covers privacy; its website cards are L16, w06, w10.
- NEW-FINDING: the private-field lint is an exact-name match (`market.py:155-157`), so `seller_email` passes; it walks
  only `listings`, `sales`, `nx-rate`, `economy-public` (`:153`), so a private key inside `prices/YYYY-MM-DD.json` is never
  refused even though `load()` reads it (`:77-79`) — and that file carries `player_share`/`player_count`
  (`PriceRollup.java:69-70`), a per-item real-player share on a public file, against the spirit of `market.py:1-13`.
- NEW-FINDING: the board API returns outbox text unfiltered (`tools/site/market_service.py:374-386`; no `check.scrub` in
  that service), so a banned word or e-mail in a title reaches the browser — safe HTML via `textContent`
  (`docs/site/assets/market.js:3`), invisible to the build check.
- NEW-FINDING: `docs/design/track-d-plan.md:216-226` promises "sales delayed at least 1 h" and a plan-derived privacy
  lint; only hour-truncation exists (`MarketSales.java:20-23`), and no `MIN_SAMPLES`/`FLOOR`/`TRADER_MIN` lint.

## State

**Working, with named holes.** All three layers hold: exporters write names only (`PublicSiteExport.java:35-50`), validation
refuses private field names (`market.py:153-158`, `world.py:130-133`), templates escape (`common.py:150`), and the
post-build check refuses a folder with an e-mail, an IPv4, the server host or a private section (`check.py:132-161`,
`:233-250`) while a failure leaves the site untouched (`build.py:134-142`). No raw IP is stored
(`report_service.py:301-302`, `login_service.py:404-406`) and `ip_hash` never leaves the GM reader
(`report_service.py:113-114`, `slums-login.nginx.conf:118-130`). The holes sit in the negative space — exact-name
matching, `prices/` unwalked, runtime APIs outside the check, the plan's unbuilt delay/lint. Evidence:
`tools/site/test_site.py:92-94`, `test_build_safety.py:31-47`.

## Open questions

1. Is `player_share` in `prices/*.json` acceptable? No page prints it, but it is a per-item bot/player split on a public
   file (`market.py:1-13`); the fix would be `PriceRollup.java:69-70`.
2. Should `check.py` gain the plan's rules (median floor, human-bucket floor, sale delay)?
   `docs/design/track-d-plan.md:226` asks for it; nothing implements them.
3. Is nginx the intended single gate for the reset-request `contact` that `AccountsExport.java:142-159` writes to
   `/admin/data/`? No build-time check knows that key exists (`tools/site/sections/admin.py:1-22`).
4. Should `market_service` apply `check.scrub` to board text (NEW-FINDING above), or is display-side safety enough?
5. `docs/architecture/website/README.md:48` points at `report-service.md`, absent from this directory. Which page owns the
   report service?
