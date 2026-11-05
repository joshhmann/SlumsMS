# Site build, checks and preview

## Purpose

`tools/site/build.py` renders the whole website — home, news, guides, Slums Street, the market, World and the admin
shell — into a plain folder of static files, then `tools/site/check.py` re-reads that folder the way a stranger would
before anything is published: when a rule breaks the build fails and the previously published folder is left exactly
as it was (`tools/site/build.py:134-141`, `:169-172`). It is standard-library Python with no runtime to speak of — the
site itself has no database, no PHP and no Node (`docs/site/DEPLOY.md:1-3`). The same code path rebuilds one section
inside the live folder, so the checks also gate the two unattended timers (`tools/site/slums-site-market.timer:7-10`).

## Entry points

| Entry | What starts it | Where |
|---|---|---|
| `python3 tools/site/build.py` | full public build to `dist/site` + `dist/slums-site.tar.gz` | tools/site/build.py:232, :251-257 |
| `--private` | adds `street/`, `market/`, `admin/` | tools/site/build.py:64, :251 |
| `--world` | adds `world/` from the server's site export | tools/site/build.py:65, :244 |
| `--section <name> --out <dir>` | one-section rebuild inside an existing build | tools/site/build.py:252-255 → :178 |
| `slums-site-market.timer` / `.service` | every 2 min: `build.py --private --world --section market` | tools/site/slums-site-market.timer:10, tools/site/slums-site-market.service:14 |
| `slums-site-world.timer` / `.service` | same cadence: `--section world` | tools/site/slums-site-world.timer:9, tools/site/slums-site-world.service:12 |
| `python3 tools/site/check.py [dir]` | stand-alone check of an existing folder | tools/site/check.py:295-301 |
| `python3 tools/site/preview.py [--dir --host --port]` | local HTTP preview with a pretend sign-in | tools/site/preview.py:122-133 |
| `python3 tools/site/icons.py --kinds …` | build-time icon cache; **never** run by build.py | tools/site/icons.py:253-283 |
| `python3 -m unittest discover -s tools/site -p 'test_*.py'` | the suite | tools/site/test_build_safety.py:1-4 |

## How it works

1. **Arguments.** `main()` (`tools/site/build.py:232`) parses `--out --tar --no-tar --private --section --hs-data
   --market-data --dashboard-web --world --site-data --nx {off,mock,on}` (`:234-245`), sets the module globals and
   `market.NX_DESK` (`:247-250`), and picks the defaults `dist/site-private` / `dist/site` (`:251`).
2. **Staging folder.** `build()` (`:148`) deletes any old tarball first (`:154-155`), then builds into
   `<out>.new-<pid>` (`:156-158`) — never into the published folder (audit W2, `:149-151`).
3. **Assets.** `copy_assets()` (`:88-96`) copies `docs/site/assets` wholesale, writes the one generated file
   `assets/build.json` carrying `{"site": <version>}`, copies `docs/site/secret/` only for a private build, and always
   copies `docs/site/robots.txt`.
4. **Version stamp.** `site_version()` (`:72-85`) reads `DEPLOYED_COMMIT` at the repo root (the build box has no git),
   else `git rev-parse --short HEAD`, else `None`.
5. **Releases.** `notes.releases()` (`tools/site/sections/notes.py:173-179`) parses every `docs/site/releases/*.md`,
   refuses an empty directory, and sorts newest-first; pages are written by `notes.build()` (`:182-207`).
6. **Context.** `set_context()` (`tools/site/build.py:99-107`) fills `common.BUILT` with the optional sections that
   this build has, sets the megaphone rail to the three newest releases, and prepends market rail lines when the market
   is being built.
7. **Sections.** `build_section()` (`:110-131`) dispatches to one module per section — `notes.build`, `guide.build`,
   `database.build`, `market.build`, `admin.build`, `world.build`, and `pages.build_home` plus the four small pages —
   and raises `SystemExit(f"unknown section {name!r}")` for anything else (`:131`).
8. **Fallback ordering.** `sections_for()` (`:144-145`) is always `notes, guide, pages`, then `world` only with
   `--world`, then `database, market, admin` only with `--private`.
9. **Clean URLs.** `common.clean_links()` (`tools/site/common.py:180-191`) rewrites every relative `.html` link so
   pages say `market/exchange`, not `market/exchange.html` (`common.py:167-170`); nginx serves `x.html` for `x`
   (`tools/site/slums-login.nginx.conf:87`).
10. **Check.** `finish()` (`:134-141`) runs `check.check_all()`; each problem is printed as `FAIL <problem>` on stderr
    and the build raises `SystemExit("site check failed with N problem(s): the published site was left as it was,
    nothing was packed")`.
11. **Swap.** Only on success: `out` → `<out>.old-<pid>`, `<out>.new-<pid>` → `out`, stale folder removed (`:169-172`).
12. **Pack.** `make_tar()` (`:215-229`) writes `slums-site/…` with `uid=gid=0`, `uname=gname="root"`, modes `0755`
    dirs / `0644` files, and sorted directory and file order.
13. **One section.** `rebuild_section()` (`:178-212`) needs an existing `index.html` (`:180-181`), builds into
    `out/.stage-<folder>` (`:194-197`), swaps the folder in with two renames (`:199-203`), then runs the same check
    limited to that folder (`:205-206`); a failing check removes the new folder and restores `.old-<folder>`
    (`:207-211`). `pages` has no folder, so it is built in place (`:187-191`).
14. **Stand-alone check.** `check_all()` (`tools/site/check.py:285-292`) is `check_forbidden + check_links +
    check_noindex`, plus `check_top` for a private build, or `check_private` for a public one; a one-section rebuild
    passes `only` and gets neither of the last two.
15. **Preview.** `Handler.do_GET` (`tools/site/preview.py:60-71`) answers `/api/login/me`, refuses every other `/api/`
    with 503, gates `/street/ /market/ /secret/` behind the `slums_session=preview` cookie, and otherwise serves files
    through `SimpleHTTPRequestHandler` (`:131`).

## Data

| Artefact | Written by | Where it lands |
|---|---|---|
| Home, 404, sign-in, join, forgot, report | `pages.build_home/…` (`sections/pages.py:22,44,50,56,62`) | `index.html`, `404.html`, `login/`, `join/`, `forgot/`, `report/` |
| News and patch notes | `notes.build` (`sections/notes.py:182-207`) | `patch-notes/index.html`, `patch-notes/<version>/index.html` |
| Guides (restyled wiki) | `guide.build` (`sections/guide.py:96-136`) | `guide/<page>.html` |
| Slums Street | `database.build` (`sections/database.py:674`) | `street/` + `street/search-index.json` (`:650`) |
| Market | `market.build` (`sections/market.py:1092`) | `market/` + `market/search-index.json` (`:558`), `summary.json`, `untradeable.json` |
| World | `world.build` (`sections/world.py:754`) | `world/` + `online.json`, `hot.json` (`:742`), `traders.json` (`:751`) |
| Admin shell | `admin.build` (`sections/admin.py:213-239`) | `admin/index.html`, `app.html`, `bots.html`, `invites.html`, `reports.html` |
| Icons and thumbs | `copy_icons` (`sections/database.py:653-671`) from `cache/site-icons/` | `street/icons/<kind>/<id>.png`, `street/icons/<kind>/t/<id>.webp` |
| Site stamp | `copy_assets` (`tools/site/build.py:91-92`) | `assets/build.json` = `{"site": "4e3cdcc96"}` locally |
| Tarball | `make_tar` (`:215-229`) | `slums-site/index.html`, 219 entries for a public build |

Measured at HEAD `4e3cdcc96`: a public build is 219 files (16 guide pages, 10 releases, 21 `assets/` entries); a
`--private --world` build is 31,911 files / 230 MB (`street/` 31,481, `assets/` 185, `market/` 135, `secret/` 50,
`world/` 17, `admin/` 9), with `street/icons/items` 4,971 — the number `docs/site/DEPLOY.md:124-127` says to verify
before publishing. Inputs that must exist first: `docs/site/releases/*.md` (else `notes.py:176-177` stops),
`docs/wiki/index.html` for the guide (`sections/guide.py`), `data/site/hiddenstreet/*.json` via `dbdata.load()`
(`sections/dbdata.py:184-192`), `cache/site-icons/manifest.json` (`sections/database.py:101-105`), and the market and
World exports, which are refused rather than half-read (`sections/market.py:45-46`, `sections/world.py:50-55`).

## Switches

| Switch | Effect | Default | Where |
|---|---|---|---|
| `--out` | output folder | `dist/site`, `dist/site-private` with `--private` | tools/site/build.py:251 |
| `--tar` / `--no-tar` | write the `.tar.gz` or not | on; `dist/slums-site[-private].tar.gz` | tools/site/build.py:256-257 |
| `--private` | Slums Street, market, admin | off | tools/site/build.py:64 |
| `--world` | World section | off | tools/site/build.py:65 |
| `--section` | rebuild one section in place | off | tools/site/build.py:252-255 |
| `--hs-data` / `SLUMS_HS_DATA` | Slums Street data folder | `data/site/hiddenstreet` | tools/site/sections/dbdata.py:21 |
| `--market-data` / `SLUMS_MARKET_DATA` | market export folder | `cache/market-sample` | tools/site/sections/market.py:29 |
| `--site-data` / `SLUMS_SITE_DATA` | World export folder | `cache/site-sample` | tools/site/sections/world.py:31 |
| `--dashboard-web` | staff dashboard source | `tools/dashboard/web` | tools/site/sections/admin.py:213 |
| `--nx` / `SLUMS_MARKET_NX_DESK` | exchange desk mode off/mock/on | `off` | tools/site/build.py:245, tools/site/slums-site-market.service:13 |
| `DEPLOYED_COMMIT` | version stamped into `assets/build.json` | git short HEAD | tools/site/build.py:72-85 |
| `preview.py --dir/--host/--port` | preview root and bind | `dist/preview`, `127.0.0.1`, `8780` | tools/site/preview.py:124-126 |
| `icons.py --kinds/--no-fetch/--chrome/--thumbs/--layer/--host` | icon cache refresh | `items,mobs,npcs`; layer `v83-2026.10.19` | tools/site/icons.py:256-265, :31 |

## Invariants

| Rule | Why the code relies on it | Enforced at |
|---|---|---|
| Player-written text is escaped before it reaches HTML | `layout()` splices `body` in unescaped, so every section must escape | `tools/site/common.py:109-111`; `esc(s, quote=True)` first in `inline()` (`:149-151`); `world.text()` (`sections/world.py:237-241`); `market.seller()` (`sections/market.py:220`) |
| Escaping is tested, not assumed | a megaphone is the one string an outsider writes into a page | `tools/site/test_site.py:1105-1110`, `:469` |
| Game-written text can never fail a rebuild | one player name must not leave every page stale | `check.scrub()` (`tools/site/check.py:90-108`), called by `world.load` (`sections/world.py:58`) |
| No absolute local path in output | pages must not leak `/root/…` or `/srv/…` | `FORBIDDEN` `/opt/ /srv/ /root/ /etc/` (`check.py:22-31`) + root-relative link refusal (`check.py:210`); measured zero in a 31,911-file private build |
| No external request of any kind | the site "loads nothing from elsewhere" | `check.py:205-208` |
| Deterministic output for the same inputs | section rebuilds must reproduce identical bytes | tested `tools/site/test_site.py:270-278`; two builds at HEAD hash-identical (219/219 files) |
| The published folder is never written until the check passes | a visitor must never see a half-built site | `tools/site/build.py:156-172`, `:192-211`; `tools/site/test_build_safety.py:31-45` |
| Private sections never reach a public build | `/street/`, `/market/` are sign-in guarded | `check_private` (`check.py:236-248`); ordering at `check.py:285-292`; `tools/site/test_site.py:258-262` |
| Search engines stay out | friends-only site | `robots.txt` + `noindex` in the first 4096 bytes (`check.py:270-282`); `tools/site/test_site.py:76-81` |
| Every clean URL resolves | links say `money`, files are `money.html` | `check_links` resolves `target + ".html"` (`check.py:212-217`) and nginx mirrors it (`slums-login.nginx.conf:87`) |

## Origin

Custom, written for this stack; the repo's baseline commit is `03d13cb21` (2026-10-18, "Establish deployable
MapleRoot server baseline"), and every tool below arrived after it on branch `site-164-street`:

| File | First commit |
|---|---|
| `tools/site/build.py`, `tools/site/check.py` | `74099f82c` 2026-10-24 "Add a public website: home, 1.5.0 patch notes and the player guide" |
| `tools/site/common.py` | `eff0c729f` 2026-10-25 "Site build: one module per section, public build by default, private kept apart" |
| `tools/site/icons.py` | `a129780cb` 2026-10-25 "Database: reachable-entity loader and icon extraction from the RC client files" |
| `tools/site/preview.py` | `5d8701022` 2026-10-26 "Site preview server: the whole site on the LAN with a pretend sign-in" |
| `tools/site/site_sample.py` | `c5e619de7` 2026-10-26 "Site: World pages …, Database renamed Slums Street" |
| `tools/site/test_build_safety.py` | `f2b2abe9d` 2026-10-30 "Site build: never wipe the site before a good build, refuse broken exports (audit W2, W4, W5)" |

The 164-host pattern was narrowed on `6eb0ba895` 2026-10-25 ("Site check: catch the 164 host only as an address or
machine name"); the audit fixes landed on `96bee8e1e` and, for the exchange and `/street/` move, `fddf475fc`
2026-11-05. `--section` and the timers are the 1.6.x addition; the whole framework decision is
`docs/site/FRAMEWORK.md:1-5`.

## Tests and QA

293 tests pass at HEAD `4e3cdcc96` (`python3 -m unittest discover -s tools/site -p 'test_*.py'`, 55.6 s).

| Test file | Covers |
|---|---|
| `tools/site/test_site.py` | one real build in `setUpClass` (`:17-27`): pages exist, no leftover `{{` (`:96-101`), robots + noindex (`:76-81`), megaphone rail (`:83-90`), `check_forbidden`/`check_links` both empty (`:92-94`), tarball contents (`:177`), nav hides private sections (`:250-268`), one-section rebuild is byte-identical (`:270-280`), failed section swap rolls back and leaves no dot dirs (`:282-301`), icon tokens (`:332-341`), `ScrubTest` (`:1276-1285`) |
| `tools/site/test_build_safety.py` | failed check keeps the published site, removes the stale tarball and leaves no `.new-` folder (`:31-45`); a good build replaces the site (`:42-46`); an unknown top-level folder fails a private build (`:51-65`); truncated JSON, `[]`, a row without `player_side`, and bad times are refused with a message, never a traceback (`:94-131`) |
| `tools/site/test_clean_urls.py` | `clean_links` output exactly (`:26`) and that `check_links` follows clean URLs (`:37`) |
| `tools/site/test_vendor.py` | the vendored Alpine file's sha256 is pinned and nothing unpinned sits in `vendor/` |
| `tools/site/test_deploy.py` | the deploy shell scripts run against stubbed `git`/`ssh`/`rsync`; no absolute redirects in nginx |
| `tools/site/test_guide_163.py`, `test_guide_spell_traces.py`, `test_help_details.py`, `test_login.py`, `test_market_app.py`, `test_market_service.py`, `test_exchange_desk.py`, `test_admin.py`, `test_report.py`, `test_download_pass.py` | one section or service each |

**Not covered:** `tools/site/preview.py` has no test at all (no `test_*.py` imports it); `icons.py` is only exercised
indirectly through note icon tokens (`test_site.py:332-341`); nothing asserts the tarball is reproducible; the
`pages` rebuild branch (`build.py:187-191`) has no rollback test; `srcset` references are unchecked by `check_links`.

## Known issues

- **W2 — fixed.** A full build staged beside `--out` and swapped only after the check (`tools/site/build.py:156-172`);
  test `tools/site/test_build_safety.py:31-45`.
- **W3 — open, and already stale.** The card says `docs/site/releases/` "still ends at `1.6.0.md`"
  (`docs/tickets/165-website-audit-1-6-2.md:24`); the folder now holds 1.5.0–1.5.6, 1.6.0, 1.6.2 and 1.6.3 and the
  home page advertises 1.6.3. What is genuinely missing is 1.6.1 (never released separately) and 1.6.4.
- **W11 — partial.** Per-address report limits still key on the raw `X-Forwarded-For`
  (`docs/tickets/165-website-audit-1-6-2.md:32`); waits on the owner's front-proxy answer. Not build-related.
- **NEW-FINDING:** `rebuild_section("pages", …)` writes into the live folder before checking and does not roll back
  (`tools/site/build.py:187-191`). Measured: after injecting `<a href="nowhere">`, the check failed and the published
  `index.html` kept the broken link.
- **NEW-FINDING:** the preview cannot serve clean URLs. `/guide/money.html` returns 200 but `/guide/money` returns 404,
  because `SimpleHTTPRequestHandler` (`tools/site/preview.py:131`) does not emulate nginx's
  `try_files $uri $uri.html $uri/ =404` (`tools/site/slums-login.nginx.conf:87`); all 12 clean links on `guide/` are
  dead in preview.
- **NEW-FINDING:** `check_links` reads only `href`/`src` (`tools/site/check.py:162-196`), so `srcset`, `<style>` `url()`
  in HTML, and a web manifest can point at a missing file and still pass; `tools/site/common.py:100` is the first
  place that would need it.
- **NEW-FINDING:** doc drift — `build.py:179` and the comment at `:192-193` still say "every 15 minutes", while both
  timers run every 2 minutes (`tools/site/slums-site-market.timer:10`).
- **NEW-FINDING:** `check_noindex` opens `robots.txt` without closing it (`tools/site/check.py:275`), a
  `ResourceWarning` in the suite.

## State

**working.** Evidence: at HEAD `4e3cdcc96` a public build and a `--private --world` build both end in
"check passed" (31,911 files, 105.7 s); two builds of the same input are byte-identical across 219 files; 293 unit
tests pass; the failed-build and failed-section paths are covered by `tools/site/test_build_safety.py:31-65` and
`tools/site/test_site.py:282-301`. The one live-relevant soft spot is that the preview is a convenience harness, not a
faithful copy of nginx (see Known issues), so "it looked fine in preview" is not evidence a clean URL works.

## Open questions

- Is `DEPLOYED_COMMIT` always present on the build box? `site_version()` falls back to git and then to `null`
  (`tools/site/build.py:72-85`), and nothing in `check.py` refuses a `null` stamp — the report service would then
  record releases as "None".
- Should the preview serve clean URLs (a tiny `try_files` equivalent in `preview.py:71`) or should `DEPLOY.md:18`'s
  `python3 -m http.server` recipe be retired in favour of it? Today the docs name both.
- `tools/site/slums-market.service:25` points `SLUMS_MARKET_ITEMS` at `/srv/slums-site/street/search-index.json` while
  `tools/site/test_market_service.py:33` notes live's unit still names `database/search-index.json`; which unit is
  deployed after the `fddf475fc` move is not visible from this repo.
- `docs/site/DEPLOY.md:124-127` depends on an operator counting `street/icons/items` by hand before every publish. Was
  a check (e.g. "refuse a build whose icon count is 0") ever considered, or is the count deliberately left to the
  human?
