# Site section modules

## Purpose

`sections/` is where the static site's pages are actually produced: one Python module per section, each exposing
`build(out, ...)` and writing its own folder under the output root (`tools/site/sections/__init__.py:1`). Because these
modules are the only code that reads the export files, they are also the privacy boundary: text a player could see is
escaped here, and anything resembling an account identity stops the build. `build.py` owns ordering, staging and the
check.

## Entry points

| Entry | What starts it | Where |
|---|---|---|
| `build.py` | operator; `--private` adds `database/market/admin`, `--world` adds `world` | `tools/site/build.py:232-263`, `:63-65` |
| `build.py --section <name>` | one-section rebuild, two-rename swap | `tools/site/build.py:252-255` → `:178-212` |
| market/world timers | every 2 min, `--private --world --section market\|world` | `slums-site-market.timer:10` + `.service:14`; `slums-site-world.timer:9` + `.service:12` |
| `build_section(name, out, rels, site_root=None)` | dispatch; unknown name → `SystemExit(f"unknown section {name!r}")` | `tools/site/build.py:110-131` |
| `PUBLIC`/`PRIVATE`/`LIVE` | section order: `notes,guide,pages` + `world` + `database,market,admin` | `tools/site/build.py:63-65`, `:144-145` |

Seven modules have a `build` entry (`notes`, `guide`, `pages` — five writers —, `database`, `market`, `world`,
`admin`); `dbdata.py` and `buddy.py` are helpers, `__init__.py` is a docstring.

## How it works

Each section is a function of (data dirs, `common.BUILT`) → files, called after `set_context()`
(`tools/site/build.py:99-107`) fills `common.BUILT` from `common.OPTIONAL` (`tools/site/common.py:29-30`).

**A. Static content — `notes`, `guide`, `pages`** (committed content only, no server export)

1. `pages.build_home(out, rels)` (`sections/pages.py:22`) reads `docs/site/home.html`, replaces the five `{{latest_*}}`
   tokens with `esc(v)` (`:28`), splices `notice_board()` (`:11`, `Update` when the version ends `.0`, `:15`) and
   `{{life_button}}` only with `"world" in common.BUILT` (`:31`); a leftover `{{...}}` is `SystemExit` (`:33-34`).
   Writes `index.html` (`:35`) and `404.html` (`:41`).
2. `notes.releases()` (`sections/notes.py:173`) parses `docs/site/releases/*.md`, refuses an empty folder, sorts
   newest-first; `parse_release` (`:37`) requires `version/title/date/summary` and a filename equal to the version, and
   an unknown block tag is a `SystemExit` (`:78-84`).
3. `notes.render_release()` (`:100`) renders each block, refusing duplicate heading ids (`:110`); every text fragment
   goes through `common.inline()` (`:123-157`), `{icon:item:NNN}` resolves through `icons()` (`:33`, `game_icon` `:23`
   exits on a missing PNG). `build()` (`:182`) writes `patch-notes/<version>/index.html` (`:196`) and the News index
   (`:206`, cards escaped `:200-202`).
4. `guide.build(out, HS_DATA)` (`sections/guide.py:96`) reads `docs/wiki/*.html`, requires `index.html` (`:100-101`),
   strips owner notes (`clean_wiki` `:88`), rewrites branding (`public_wording` `:75`), cross-links database pages only
   when `"database" in common.BUILT` (`:107-109`), applies `docs/site/guide-overrides/<page>.html` (`:120-122`), points
   skipped pages at `../patch-notes/` (`:123-124`), writes `guide/<name>` (`:136`); an unmatched fix is a `SystemExit`
   (`:137-139`).

**B. Live-data — `world`, `market`, `database` (+ `dbdata`)**

5. `dbdata.load(data_dir=None)` (`sections/dbdata.py:184`) reads `items/mobs/maps/npcs/quests` JSON from `SLUMS_HS_DATA`
   (default `data/site/hiddenstreet`, `:21`), keeps only reachable entities (`reachable()` `:127`, rules `:1-13`), drops
   Hangul/brand names (`:30-36`) and excluded maps (`:47-64`), trimming every cross-link that died with them.
   `database.build()` (`sections/database.py:674`) renders `street/` (index `:557` + the `data-hot` panel `:554-556`,
   items `:348/:363`, mobs `:412/:421`, maps `:451/:463`, npcs `:478/:487`, quests `:525/:533`, changes `:593`, whacky
   `:641`), copies icons (`:653`) and writes `street/search-index.json` (`:650`).
6. `market.build(out, db, data_dir=None, site_root=None)` (`sections/market.py:1092`) loads the four export files plus
   `prices/YYYY-MM-DD.json` (`:69`), validates (`:121`), wipes `market/` (`:1098`), renders prices (`:508`), lists
   (`:570`), home (`:631`), board/mine/cards (`:651`), exchange (`:1037`), invites (`:1007`), stamps (`:1002`), then
   writes `summary.json` (`:1066`) and `untradeable.json` (`:1057`).
7. `world.build(...)` (`sections/world.py:754`) loads the three export files (`:42`), drops AutoQA rows (`:92`), scrubs
   (`:58`), validates (`:121`), wipes `world/` (`:763`), renders live (`:330`), feed (`:389`), rankings (`:586`),
   economy (`:654`), then writes `hot.json` (`:713`) and `traders.json` (`:746`).

**C. Interactive shells — sign-in pages, market board, `admin`, `buddy`**

8. `pages.build_login/join/forgot/report` (`sections/pages.py:44,50,56,62`) wrap committed HTML with
   `buddy.place(read(page), buddy.figure("../", slot))`; nothing talks to a server until `docs/site/assets/login.js`,
   `reports.js` or `invites.js` call `/api/login/...` (`tools/site/login_service.py`), `/api/report`
   (`report_service.py`) or `/api/market/...` (`market_service.py:538-581`).
9. `market.build_board` (`sections/market.py:651`) writes Alpine shells — `board.html` `:681`, `mine.html` `:699`,
   `cards.html` `:709` — filled from `api/market/<path>` (`docs/site/assets/market.js:24`); likewise `invites.html`
   `:1009`, `stamps.html` `:1004`, `exchange.html` `:1053`.
10. `admin.build(out, web=None)` (`sections/admin.py:213`) copies the staff dashboard into `admin/`: `dashboard.css`
    `:222`, renamed scripts `:223-225`, `app.html` `:231` (`SystemExit` `:227-228` if the title or CSS link is gone),
    iframe `index.html` `:234`, `bots.html` `:235`, `invites.html` `:237`, `reports.html` `:239`; every template uses
    `x-text`, never `x-html` (`:45,:70,:113`).
11. `finish()` runs `check.check_all` (`tools/site/build.py:134-141`); failure leaves the published folder untouched, and
    `rebuild_section` stages `.stage-<folder>`, swaps with two renames and reverts on a failed check (`:192-211`).

## Data

| Module (first line) | Output | Data source |
|---|---|---|
| `tools/site/sections/__init__.py:1` | docs only, no build | — |
| `tools/site/sections/pages.py:22` | `index.html`, `404.html`, `login/`, `join/`, `forgot/`, `report/` | `docs/site/{home,login,join,forgot,report}.html`, `rels` |
| `tools/site/sections/notes.py:182` | `patch-notes/index.html`, `patch-notes/<version>/index.html` | `docs/site/releases/*.md`, `docs/site/assets/icons/` |
| `tools/site/sections/guide.py:96` | `guide/<page>.html` | `docs/wiki/*.html`, `docs/site/guide-fixes.json`, `guide-overrides/`, `dbdata` |
| `tools/site/sections/database.py:674` | `street/**`, `street/search-index.json`, `street/icons/**` | `dbdata.load()` + `cache/site-icons/` |
| `tools/site/sections/dbdata.py:184` | no pages; in-memory maps | `SLUMS_HS_DATA/*.json` from `tools/hiddenstreet/build_data.py` |
| `tools/site/sections/market.py:1092` | `market/**`, `market/search-index.json`, `market/summary.json`, `market/untradeable.json` | `SLUMS_MARKET_DATA`: `listings/sales/nx-rate/economy-public.json`, `prices/*.json` |
| `tools/site/sections/world.py:754` | `world/index.html`, `world/online.json`, `world/feed/*.html`, `world/{hot,traders}.json`, board pages | `SLUMS_SITE_DATA`: `feed.json`, `world.json`, `leaderboards.json`, `avatars/` |
| `tools/site/sections/admin.py:213` | `admin/{index,app,bots,invites,reports}.html`, `dashboard.css`, scripts | `tools/dashboard/web/` |
| `tools/site/sections/buddy.py:135` | fragments spliced into other sections | in-module `SLOTS`/`OPEN_LINES` tables |

Contracts published for scripts: `world/online.json` = `{online, generated_at[, version]}` (`sections/world.py:383-385`);
`world/traders.json` = `{name: [completed, fellThrough]}` `:751`; `world/hot.json` = top maps/mobs/items, names and counts
only `:742`; `market/summary.json` = `{id: {n,m,c,lo,hi,s,d}}` (`market.py:1069-1073`); `market/untradeable.json` = ids
carrying `tradeBlock`/`quest`/`accountSharable` `:1060-1062`; `street/search-index.json` rows
`["i"|"m"|"p"|"n"|"q", id, name]` (`database.py:650`); `market/search-index.json` rows `["$", id, name, median]`
(`market.py:558`).

Server-side producers, so a number can be followed end to end (repo `/root/work/SlumsMS-164-archreview`):

| Export | Producer | Cadence |
|---|---|---|
| `feed.json` | `site/SiteFeed.java:487` (`isPublic` `:136`), written by `site/PublicSiteExport.java:106` | 120 s (`:62`) |
| `world.json` | `site/SiteWorld.java:72-83`, written by `PublicSiteExport.java:105` | 60 s (`:63`) |
| `leaderboards.json` | `site/SiteLeaderboards.java:104`, written by `PublicSiteExport.java:107` | 15 min (`:64`) |
| kills behind `hot.json` | `site/SiteMobKills.java:143`, `:175` | TOP 25 / 168 h (`:23-24`) |
| `listings/sales/nx-rate/economy-public.json`, `prices/` | `Economy/market/PublicMarketExport.java:85`, `:119-121` | listings 120 s (`:80`) |
| `data/site/hiddenstreet/*.json` | `tools/hiddenstreet/build_data.py` (site repo) | manual |
| `reports/dashboard/live.json`, `avatars/index.json` | `dashboard/DashboardExporter.java:199`; `site/avatar/AvatarExport.java:156` | 60 s; on change |

## Switches

| Switch | Effect | Default | Where |
|---|---|---|---|
| `SLUMS_HS_DATA` / `--hs-data` | Slums Street dataset | `data/site/hiddenstreet` | `sections/dbdata.py:21` |
| `SLUMS_MARKET_DATA` / `--market-data` | market export folder | `cache/market-sample` | `sections/market.py:29` |
| `SLUMS_SITE_DATA` / `--site-data` | World export folder | `cache/site-sample` | `sections/world.py:31` |
| `SLUMS_MARKET_NX_DESK` / `--nx off\|mock\|on` | desk quote panel vs the closed rules | `off` | `sections/market.py:1032-1034` → `tools/site/exchange_desk.py:138-143`; `on` in `slums-site-market.service:13` |
| `--dashboard-web` | staff dashboard source | `tools/dashboard/web` | `sections/admin.py:26`, `tools/site/build.py:250` |
| `MIN_SAMPLES = 3` | days of history before a price/change shows | 3 | `sections/market.py:30` |
| `sample` flag in an export | sample banner on every page | export-driven | `sections/market.py:449-450`, `sections/world.py:277` |

`docs/site/secret/` is copied only in a private build (`tools/site/build.py:93-94`); no section reads it.

## Invariants

| Rule | Enforced at |
|---|---|
| Player-written text is escaped before HTML — `layout()` splices `body` verbatim (`tools/site/common.py:110`), so sections must escape | `common.inline()` escapes first `tools/site/common.py:150`; `world.text()` `sections/world.py:239`, feed names `:256`, times `:261`; `market.seller()` `:220`, chart tips `:315`, rail SOLD name `:1087`; `database.Site.link()` `:122-123`, descriptions `:330-331`; `notes` bodies `:123-157`, News cards `:200-202`; `pages` `:16-18`, `:28`; `guide` `:129`, `:134`; `buddy` `:145-148` |
| Client scripts never `innerHTML` export text | `docs/site/assets/market.js:3`, setter `:31`; `dbsearch.js:29-30`; `theme.js:632-655`; `test_admin.py:55,63` assert no `x-html` |
| Account-identifying fields stop the build instead of being filtered | `sections/world.py:37`, `:129-133`; `sections/market.py:106`, `:157-158`; `site/SiteFileShape.java:17`, `:112` |
| A missing or half-written export is refused, never half-rendered | `market.py:45-54`, `:74-75`; `world.py:45-48`, `:50-55`; `dbdata.py:192-197` |
| Online is the channel sum; a board rank comes from the export, not row order; most-hunted splits only when counted + estimated == kills | `world.total_online()` `:320`; `table()` `:489-501`; `split()` `:535`, `split_covers()` `:541` |
| World data never says who is a bot | `world.PRIVATE_FIELDS` `:37`, `drop_qa` `:92`; `test_site.py:1083` |
| Nav and cross-links exist only for sections actually built, and identical inputs give identical bytes (so a rebuild may replace the folder) | `common.py:74-76`; `guide.py:107-109`; `pages.py:31`; `check.check_all` `build.py:134`; `test_site.py:270-280` |

## Origin

Custom to this stack. Baseline `03d13cb21` (2026-10-18), branch `site-164-street`; first commit per module from
`git log --follow --format=%h -- <path> | tail -1`: `__init__.py`/`guide.py`/`notes.py`/`pages.py` `eff0c729f`
(2026-10-25, "one module per section, public build by default, private build kept apart"), `dbdata.py` `a129780cb`
(10-25), `database.py` `a3edfb098`, `world.py` `c5e619de7`, `market.py` `ade606c4c` (all 10-26), `buddy.py` `e73157d6e`
(10-27), `admin.py` `ea44482d1` (10-28). Latest: `buddy.py` `b8522f404` (11-04);
`database.py`/`guide.py`/`world.py` `fddf475fc` and `market.py` `4e3cdcc96` (11-05, the `/street/` move and exchange
work).

## Tests and QA

`python3 -m unittest discover -s tools/site -p 'test_*.py'` — 287 test methods in 15 files; the real build runs once in
`test_site.py:17` `SiteBuildTest.setUpClass`, so most section assertions ride on a full build.

| Test | Covers |
|---|---|
| `tools/site/test_site.py:241` `SectionsTest` | private sections hidden from nav, public build refuses private pages, `rebuild_one_section` `:270`, staging gone and rollback `:282` |
| `tools/site/test_site.py:375` `MarketTest` | prices/listings/sales/nx pages, `summary.json` `:871`, exchange hidden until open `:912`, nameless seller `:465`, `traders.json` `:1051` |
| `tools/site/test_site.py:727` `WorldTest` | nav, `online.json` version `:1122`, hunted split `:1182`, dojo `:1211`, escape+bold `:1105`, private-data refusal `:1089`, no bot labels `:1083` |
| `tools/site/test_site.py:571` / `:674` | reachability rules, `street/` URLs, cleaned descriptions |
| `test_guide_163.py`, `test_guide_spell_traces.py`, `test_help_details.py` | guide wording, spell traces, fixes/overrides, folded help blocks |
| `tools/site/test_admin.py` | colours renamed `:39`, framed dashboard `:47`, reports/bots never render HTML `:55`, `:63` |
| `tools/site/test_build_safety.py`, `test_clean_urls.py`, `test_vendor.py` | failed check keeps the site `:31`, bad exports refused `:94/:108/:113`, clean links, pinned Alpine sha256 |

**Not covered:** no test builds `admin/` against the real installed dashboard copy (`test_admin.py` uses a CSS
fixture); `world/hot.json` and `market/untradeable.json` contents are never asserted; `buddy.figure`'s crc32 line
choice is untested; no browser test exercises `market.js`/`theme.js`/`exchange.js`; nothing checks that the timer units
pass the flags they claim.

## Known issues

- **w06** (Website, later): rename pages, database → Slums Street — done (`sections/database.py:21` `DB="street"`, nginx
  301 `tools/site/slums-login.nginx.conf:35-36`); only code identifiers still say "database".
- **w10** (Website, later): more Slums-chan cards in My cards — the card set is fixed in `sections/market.py:983`.
- **L16** (Website, open): list and sell the new exchange items; `sections/market.py:1037` only toggles the desk panel.
- **s17** (Server, later): most-hunted counted vs estimated — the site labels counts "(est.)" (`sections/world.py:528`)
  while the server sends one mixed number; `split()` `:535` now consumes a split export.
- **Checked, not a defect:** `world.build()` wipes `world/` before rendering (`sections/world.py:763`), but every
  caller passes a staging directory — a full build renders into `<out>.new-<pid>` (`tools/site/build.py:152-168`) and
  the 2-minute timer renders into `<out>/.stage-world` (`tools/site/build.py:192-211`) — so the wipe never touches the
  served folder. The one unstaged path is `pages`, which writes single files in place (`tools/site/build.py:186-191`).
- **NEW-FINDING:** `sections/guide.py:54` still says "update wiki_body() in tools/site/build.py" — the function lives in
  `sections/guide.py`, so the message sends the reader to the wrong file.
- **NEW-FINDING:** `sections/admin.py:223-225` copies exactly the names in `SCRIPTS` (`:27`); a dashboard that adds a
  fourth script ships an `app.html` requesting a file the build never wrote.

## State

**working, with the caveats above.** Evidence: a public build is 219 files and a `--private --world` build is 31,911,
both ending "check passed" at HEAD `4e3cdcc96` (`docs/architecture/website/build-and-checks.md:91-92,190-191`); all
seven builders are reachable from `tools/site/build.py:110-131`; escape and refusal paths are pinned by `test_site.py`
and `test_build_safety.py`; both timers run `rebuild_section` every 2 minutes in production.

## Open questions

- `docs/architecture/website/README.md` calls the exchange desk one of "four small Python services" while its diagram
  treats it as a file contract; the code is a file pair plus `desk_mode()` (`tools/site/exchange_desk.py:138-143`).
- Which unit is deployed after the `/street/` move? `tools/site/slums-market.service:25` points `SLUMS_MARKET_ITEMS` at
  `/srv/slums-site/street/search-index.json`, but `test_market_service.py:33` records live still naming
  `database/search-index.json`. Should `database.build()` own the icon copy (`sections/database.py:653`) at all?
- Is the `(est.)` labelling (`sections/world.py:528`) still wanted now that the server can split
  (`SiteMobKills.java:175`, `SiteWorld.java:82`)?
