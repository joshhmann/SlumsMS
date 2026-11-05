# Site section modules

## Purpose

`sections/` is where the static site's pages are actually produced: one Python module per site section, each exposing
`build(out, ...)` and writing its own folder under the output root (`tools/site/sections/__init__.py:1`). The modules
are the only place that reads export files, so they double as the privacy boundary — anything a player could see as
text is escaped here, and anything that looks like an account identity stops the build. `build.py` owns ordering,
staging and the check; the sections own rendering.

## Entry points

| Entry | What starts it | Where |
|---|---|---|
| `build.py` full build | operator, `--private` adds `database/market/admin`, `--world` adds `world` | `tools/site/build.py:232`, `:63-65` |
| `build.py --section <name> --out <dir>` | one-section rebuild in place | `tools/site/build.py:252-255` → `:178` |
| `slums-site-market.timer` → `.service` | every 2 min, `--private --world --section market` | `tools/site/slums-site-market.timer:10`, `slums-site-market.service:14` |
| `slums-site-world.timer` → `.service` | every 2 min, `--section world` | `tools/site/slums-site-world.timer:9`, `slums-site-world.service:12` |
| section dispatch | `build_section(name, out, rels, site_root=None)` | `tools/site/build.py:110-131`, unknown name → `SystemExit(f"unknown section {name!r}")` `:131` |
| section list | `PUBLIC=["notes","guide","pages"]`, `PRIVATE=["database","market","admin"]`, `LIVE=["world"]` | `tools/site/build.py:63-65`; order at `:144` |

## How it works

Every section is a pure function of (data dirs, `common.BUILT`) → files, called after `set_context()`
(`tools/site/build.py:96-108`) has filled `common.BUILT` from `common.OPTIONAL = {"world","database","market"}`
(`tools/site/common.py:29-30`) and the megaphone rail.

**A. Static content — `notes`, `guide`, `pages`** (no server export, only committed content)

1. `pages.build_home(out, rels)` (`sections/pages.py:22`) reads `docs/site/home.html` and replaces the five
   `{{latest_*}}` tokens with `esc(v)` (`:28`), splices `notice_board()` (`:11`, tag `Update` when the version ends
   in `.0`, `:15`) and `{{life_button}}` only when `"world" in common.BUILT` (`:31`); any unknown `{{...}}` left is a
   `SystemExit(f"home.html: unknown placeholders {left}")` (`:33-34`). Writes `index.html` (`:35`) and `404.html`
   with `base="/"` (`:41`).
2. `notes.releases()` (`sections/notes.py:173`) parses `docs/site/releases/*.md`, refuses an empty folder, sorts
   newest-first; `parse_release` (`:37`) requires front matter `version/title/date/summary` and a filename equal to
   the version (`:47-50`), and turns unknown block tags into `SystemExit("unknown tag ...")` (`:78-84`).
3. `notes.render_release()` (`:100`) renders each body block, refusing duplicate heading ids (`:110`); every text
   fragment goes through `common.inline()` (`:123,:130,:140,:142,:144,:148,:157`) and `{icon:item:NNN}` tokens are
   resolved by `icons()` (`:33`, `game_icon` `:23` exits if the PNG is missing). `build()` writes
   `patch-notes/<version>/index.html` (`:196`) and the index (`:206`, list cards escaped `:200-202`).
4. `guide.build(out, HS_DATA)` (`sections/guide.py:96`) reads `docs/wiki/*.html`, requires `docs/wiki/index.html`
   (`:100-101`), strips owner/source notes and empty sections (`clean_wiki` `:88`), rewrites branding
   (`public_wording` `:75`, tables `:60`/`:71`), cross-links database pages only when `"database" in common.BUILT`
   (`:106-108`), applies `docs/site/guide-overrides/<page>.html` (`:119-121`), points skipped pages at
   `../patch-notes/` (`:123`), writes `guide/<name>` (`:136`); a fix that matched nothing is a `SystemExit`
   (`:138-139`).

**B. Live-data — `world`, `market`, `database` (+ `dbdata`)**

5. `dbdata.load(data_dir=None)` (`sections/dbdata.py:184`) reads `items/mobs/maps/npcs/quests` JSON from
   `SLUMS_HS_DATA` (default `data/site/hiddenstreet`, `:21`), keeps only *reachable* entities (`reachable()` `:127`,
   rules in the docstring `:1-13`), drops Hangul/brand names (`:30-36`), excluded maps (`:47-64`) and trims every
   cross-link that died with them. `database.build()` (`sections/database.py:674`) then renders Slums Street into
   `street/` (index `:557`, items `:348/:363`, mobs `:412/:421`, maps `:451/:463`, npcs `:478/:487`, quests
   `:525/:533`, changes `:593`, whacky `:641`), copies icons (`copy_icons` `:653`) and writes
   `street/search-index.json` (`:650`).
6. `market.build(out, db, data_dir=None, site_root=None)` (`sections/market.py:1092`) loads the four export files plus
   `prices/YYYY-MM-DD.json` (`load` `:69`), validates them (`validate` `:121`), wipes `market/`, then renders prices
   (`build_prices` `:508`), lists (`build_lists` `:570`), home (`build_home` `:631`), board/mine/cards (`build_board`
   `:651`) and finally writes `summary.json` (`:1066`) and `untradeable.json` (`:1057`).
7. `world.build(out, data_dir=None, market_dir=None, db=None, site_root=None)` (`sections/world.py:754`) loads
   `feed.json`, `world.json`, `leaderboards.json` (`load` `:42`), scrubs them (`check.scrub`, `:60`), drops AutoQA
   rows (`drop_qa` `:92`), validates (`validate` `:121`, private field → `f"private field '{path}' must not be
   published"` `:131-133`), then renders live (`build_live` `:330`), feed (`build_feed` `:389`), rankings
   (`build_rankings` `:586`), economy (`build_economy` `:654`), and emits `hot.json` (`:713`) and `traders.json`
   (`:746`).

**C. Interactive / server-backed shells — `pages` sign-in pages, `market` board, `admin`, `buddy`**

8. `pages.build_login/join/forgot/report` (`sections/pages.py:44,50,56,62`) wrap committed HTML
   (`docs/site/login.html`, …) with `buddy.place(read(page), buddy.figure("../", slot))` — the page is inert until
   `docs/site/assets/login.js` / `reports.js` / `invites.js` call `/api/login/...` (`login_service.py`), `/api/report`
   (`report_service.py`) or `/api/market/...` (`market_service.py:538-581`).
9. `market.build_board` (`sections/market.py:651`) writes Alpine shells (`market/board.html` `:681`, `mine.html` `:699`,
   `cards.html` `:709`) whose content arrives from `api/market/<path>` (`docs/site/assets/market.js:24`); likewise
   `invites.html` (`:1009`, `invites.js:17`) and `stamps.html` (`:1004`, `stamps.js:16`).
10. `admin.build(out, web=None)` (`sections/admin.py:213`) copies the staff dashboard (`tools/dashboard/web`) into
    `admin/`: `dashboard.css` (`:222`), renamed scripts (`:225`), `app.html` (`:231`, exits if the CSS link or title
    vanished), an iframe `index.html` (`:234`) plus `bots.html` (`:235`), `invites.html` (`:237`), `reports.html`
    (`:239`). Every template renders server data with `x-text`, never `x-html` (`:45,:70,:113`).
11. `finish()` runs `check.check_all` (`tools/site/build.py:134-141`); a failure leaves the published folder untouched.
    `rebuild_section` stages `.stage-<folder>`, swaps with two renames, and reverts on a failed check
    (`tools/site/build.py:178-212`).

## Data

| Module (first line) | Output | Data source |
|---|---|---|
| `tools/site/sections/__init__.py:1` | docs only, no build | — |
| `tools/site/sections/pages.py:22` | `index.html`, `404.html`, `login/`, `join/`, `forgot/`, `report/` | `docs/site/{home,login,join,forgot,report}.html` + `rels` from `notes` |
| `tools/site/sections/notes.py:182` | `patch-notes/index.html`, `patch-notes/<version>/index.html` | `docs/site/releases/*.md`, `docs/site/assets/icons/` |
| `tools/site/sections/guide.py:96` | `guide/<page>.html` | `docs/wiki/*.html`, `docs/site/guide-fixes.json`, `guide-overrides/`, `dbdata` |
| `tools/site/sections/database.py:674` | `street/**`, `street/search-index.json`, `street/icons/**` | `dbdata.load()` + `cache/site-icons/` |
| `tools/site/sections/dbdata.py:184` | no pages; in-memory maps | `SLUMS_HS_DATA/*.json` written by `tools/hiddenstreet/build_data.py` |
| `tools/site/sections/market.py:1092` | `market/**`, `market/search-index.json`, `market/summary.json`, `market/untradeable.json` | `SLUMS_MARKET_DATA`: `listings/sales/nx-rate/economy-public.json`, `prices/*.json` |
| `tools/site/sections/world.py:754` | `world/index.html`, `world/online.json`, `world/feed/*.html`, `world/{hot,traders}.json`, board pages | `SLUMS_SITE_DATA`: `feed.json`, `world.json`, `leaderboards.json`, `avatars/` |
| `tools/site/sections/admin.py:213` | `admin/{index,app,bots,invites,reports}.html`, `dashboard.css`, scripts | `tools/dashboard/web/` |
| `tools/site/sections/buddy.py:135` | fragments spliced into other sections | in-module `SLOTS`/`OPEN_LINES` tables |

File contracts the sections publish (read back by scripts, not by Python): `world/online.json` = `{online,
generated_at[, version]}` (`sections/world.py:385`, version regex `[0-9A-Za-z.+-]{1,32}` `:381`);
`world/traders.json` = `{name: [completed, fellThrough]}` (`:751`); `world/hot.json` = top maps/mobs/items with
names, ids and counts only (`:742`); `market/summary.json` = `n,m,c,lo,hi,s,d` (`market.py:1073`);
`market/untradeable.json` flags `tradeBlock`/`quest`/`accountSharable` (`:1062`); `street/search-index.json` rows are
`["i"|"m"|"p"|"n"|"q", id, name]` (`database.py:650`); `market/search-index.json` rows are `["$", id, name, median]`
(`market.py:558`).

Server-side producers, for following a number end to end (repo `/root/work/SlumsMS-164-archreview`):

| Export file | Producer | Cadence |
|---|---|---|
| `feed.json` | `src/main/java/soloMapling/site/SiteFeed.java` (writers `:203`, `:228`; `isPublic` `:136`) written by `site/PublicSiteExport.java:105-107` | 120 s (`PublicSiteExport.java:62`) |
| `world.json` | `src/main/java/soloMapling/site/SiteWorld.java:72-83` | 60 s (`PublicSiteExport.java:63`) |
| `leaderboards.json` | `src/main/java/soloMapling/site/SiteLeaderboards.java:104` | 15 min (`PublicSiteExport.java:64`) |
| `hot.json` inputs (mob kills) | `src/main/java/soloMapling/site/SiteMobKills.java:143`, `:175` | TOP 25 / 168 h (`:23-24`) |
| `listings/sales/nx-rate/economy-public.json` | `src/main/java/soloMapling/Economy/market/PublicMarketExport.java:85`, `:120` | 120 s listings (`:80`) |
| `data/site/hiddenstreet/*.json` | `tools/hiddenstreet/build_data.py` (site repo) | manual |
| dashboard data | `src/main/java/soloMapling/dashboard/DashboardExporter.java:199` | 60 s |
| avatars | `src/main/java/soloMapling/site/avatar/AvatarExport.java` | on change |

## Switches

| Switch | Effect | Default | Where |
|---|---|---|---|
| `SLUMS_HS_DATA` / `--hs-data` | Slums Street dataset | `data/site/hiddenstreet` | `sections/dbdata.py:21` |
| `SLUMS_MARKET_DATA` / `--market-data` | market export folder | `cache/market-sample` | `sections/market.py:29` |
| `SLUMS_SITE_DATA` / `--site-data` | World export folder | `cache/site-sample` | `sections/world.py:31` |
| `SLUMS_MARKET_NX_DESK` / `--nx off\|mock\|on` | exchange desk panel or the "coming" rules | `off` | `sections/market.py:1032` → `tools/site/exchange_desk.py:139-143`; set `on` in `slums-site-market.service:13` |
| `--dashboard-web` | staff dashboard source | `tools/dashboard/web` | `sections/admin.py:26` |
| `common.BUILT` | which cross-links/nav items exist | from `common.OPTIONAL` | `tools/site/common.py:29-30`, `tools/site/build.py:96-99` |
| `market.sample` / `world["sample"]` | sample banner on every page | export-driven | `sections/market.py:447`, `sections/world.py:277` |
| `DEPLOYED_COMMIT` | not read here; stamps `assets/build.json` | git short HEAD | `tools/site/build.py:72-85` |

Secrets: `docs/site/secret/` is copied only in a private build (`tools/site/build.py:93`); contents are named by path
only and are never read by a section module.

## Invariants

| Rule | Enforced at |
|---|---|
| Player-written text is escaped before it reaches HTML — `layout()` splices `body` verbatim, so sections must escape | `common.inline()` escapes first, `tools/site/common.py:150`; `world.text()` `sections/world.py:239`; `feed_rows` names `:256`, times `:261`; `market.seller()` `:220`, chart tips `:315`, rail item names `:1088`; `database.Site.link()` `:122-123`, descriptions after `clean_desc(scrub(...))` `:330-331`; `notes` body `:123-157` + index `:200-202`; `pages` `:16-18`, `:28`; `guide` `:129`, `:134`; `buddy` `:145-148` |
| Client scripts never `innerHTML` export text | `docs/site/assets/market.js:3` (rule in the header), `:31`; `dbsearch.js:29-30`; `theme.js:632-655` uses `textContent`; `test_admin.py:55,63` assert no `x-html` |
| Account-identifying fields stop the build instead of being filtered | `sections/world.py:37` (`PRIVATE_FIELDS`), `:131-133`; `sections/market.py:106`, `:158`; server side `site/SiteFileShape.java:17`, `:112` |
| A missing/broken export is refused, never half-rendered | `market.py:45-46` (`"market data refused: ... can't be read as JSON"`), `world.py:50-55` (`"World data refused: ..."`), `dbdata.py:192` |
| Online count is the sum of the channel list, and a board rank comes from the export, not the row order | `world.total_online()` `sections/world.py:320`; `table()` `:489-501` (audit W17/W18) |
| Most-hunted splits only when counted + estimated == kills | `world.split()` `:535`, `split_covers()` `:541`, `HUNTED_SPLIT` `:531` |
| World data never says who is a bot | `world.PRIVATE_FIELDS` `:37`, `drop_qa` `:92`, `feed_rows` `:244`; test `test_site.py:1083` |
| Section order and folder set are fixed by the build, not the module | `build.py:63-65`, `:144`, `rebuild_section` folder map `:185-186` |
| Output is deterministic for identical inputs (section rebuilds must match the full build) | `check.check_all` at `build.py:134`; `test_site.py:270-280` |

## Origin

Custom to this stack. Baseline commit `03d13cb21` (2026-10-18). First commit per module on branch `site-164-street`,
from `git log --follow --format=%h -- <path> | tail -1`:

| Module | First commit | Date |
|---|---|---|
| `__init__.py`, `guide.py`, `notes.py`, `pages.py` | `eff0c729f` "Site build: one module per section, public build by default, private build kept apart" | 2026-10-25 |
| `dbdata.py` | `a129780cb` | 2026-10-25 |
| `database.py` | `a3edfb098` | 2026-10-26 |
| `world.py` | `c5e619de7` | 2026-10-26 |
| `market.py` | `ade606c4c` | 2026-10-26 |
| `buddy.py` | `e73157d6e` | 2026-10-27 |
| `admin.py` | `ea44482d1` | 2026-10-28 |

Latest touches: `buddy.py` `b8522f404` (2026-11-04); `database.py`/`guide.py`/`world.py` `fddf475fc` and `market.py`
`4e3cdcc96` (2026-11-05, the `/street/` move and exchange work).

## Tests and QA

`python3 -m unittest discover -s tools/site -p 'test_*.py'` — 287 test methods over 15 files; the real build runs once
in `test_site.py:17` `SiteBuildTest.setUpClass` (`:19-23`), so most section assertions ride on a full build.

| Test | Covers |
|---|---|
| `tools/site/test_site.py:241` `SectionsTest` | nav hides private sections, public build refuses private pages, `rebuild_one_section`, stage rollback |
| `tools/site/test_site.py:375` `MarketTest` | prices/listings/sales/nx pages, `summary.json` `:872-886`, exchange hidden until open `:912`, `traders.json` round-trip `:1075-1081` |
| `tools/site/test_site.py:727` `WorldTest` | pages and nav, `online.json` version `:1122-1146`, hunted split `:1182`, dojo board only with rows `:1211`, escape/bold `:1105`, private-data refusal `:1089`, no bot labels `:1083` |
| `tools/site/test_site.py:571` `DbDataTest`, `:674` `DatabaseTextTest` | reachability rules, `street/` URLs, cleaned descriptions |
| `tools/site/test_guide_163.py`, `test_guide_spell_traces.py` | guide wording, spell traces, fix/override handling |
| `tools/site/test_admin.py` | CSS colours renamed (`:39`), framed dashboard (`:47`), reports/bots never render HTML (`:55`, `:63`) |
| `tools/site/test_build_safety.py` | failed check keeps the site (`:31`), bad exports refused (`:94`, `:108`, `:113`) |
| `tools/site/test_clean_urls.py`, `test_help_details.py`, `test_vendor.py` | clean links, folded help blocks, pinned Alpine sha256 |

**Not covered:** no test builds `admin/` against the real installed dashboard copy (`test_admin.py` uses a CSS fixture);
`world/hot.json` content is never asserted; `buddy.figure`'s crc32 line choice is untested; no browser test exercises
`market.js`/`theme.js`/`exchange.js`; nothing tests that the timer units pass the flags they claim to pass.

## Known issues

- **w06** (Website, later): rename pages, database → Slums Street — implemented (`sections/database.py:21` `DB="street"`,
  nginx 301 at `tools/site/slums-login.nginx.conf:34-36`), only the code identifiers still say "database".
- **w10** (Website, later): more Slums-chan cards in My cards — `sections/market.py:983` keeps the card set fixed.
- **L16** (Website, open): the site should list and sell the new exchange items; `sections/market.py:1037` only toggles
  the desk panel.
- **s17** (Server, later): most-hunted counted vs estimated split — the site labels counts "(est.)" while the server
  sends one mixed number; `world.split()` (`sections/world.py:535`) is the hook that now consumes a split export.
- **NEW-FINDING:** `world.build()` wipes `world/` before rendering (`sections/world.py:757`), so a mid-run exception
  leaves the live World folder empty until the next 2-minute timer; the staging protection in `build.py:178-212` only
  covers the whole folder swap, not the contents.
- **NEW-FINDING:** `sections/guide.py:50` still tells operators to "update wiki_body() in tools/site/build.py" — the
  function moved to `sections/guide.py`, so that error message points at the wrong file.
- **NEW-FINDING:** `admin.build()` copies the dashboard's `app.js` and friends but `sections/admin.py:27` hard-codes
  three filenames; a dashboard that adds a fourth script ships a page that silently 404s its JS.

## State

**working, with the caveats above.** Evidence: a public build is 219 files and a `--private --world` build is 31,911
files, both ending in "check passed" at HEAD `4e3cdcc96` (`docs/architecture/website/build-and-checks.md`); all nine
section modules have a build entry called from `tools/site/build.py:110-131`; the escape and refusal paths are pinned
by `test_site.py` and `test_build_safety.py`; the two timers exercise `rebuild_section` every 2 minutes in production.

## Open questions

- `docs/architecture/website/README.md` says "four small Python services (login, market, reports, exchange desk)" while
  its own flow diagram treats the desk as a file contract (`tools/site/exchange_desk.py`). Is the desk a service or a
  file pair? The code says file pair plus `desk_mode()` (`tools/site/exchange_desk.py:139-143`).
- Which unit is deployed after the `/street/` move? `tools/site/slums-market.service:25` points
  `SLUMS_MARKET_ITEMS` at `/srv/slums-site/street/search-index.json`, but `test_market_service.py:33` records live still
  naming `database/search-index.json`.
- Should `database.build()` own the icon cache copy (`sections/database.py:653`) or should `icons.py` be a separate
  build step? Today the docstring of `tools/site/icons.py` says it is never run by `build.py`, so a missing manifest
  fails the section mid-build.
- Is the `(est.)` labelling in `sections/world.py:528` still wanted now that the server can split (`SiteMobKills.java:175`)?
