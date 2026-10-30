# 165: Website audit — 1.6.1/1.6.2 promises vs what shipped (20 findings)

**Status:** open · **Owner:** Website lane · **Source:** owner, 2026-10-30 (paraphrased): read-only audit of the
website in the same style as tickets 163 (bots) and 164 (playbook) — bugs plus promised-vs-actual for 1.6.1/1.6.2 —
ending in a ticket.

**Anchors:** site worktree `/root/work/SlumsMS-site-161` @ `d5f2506f4` when audited, `a250d5131` when re-checked
(branch `site-161-framework`, forked `06a724dfc`, 1185 commits behind `release-1.6.2`); server
`docs/release/1.6.2.conf`,
`docs/release/1.6.2-deploy-steps.md`; box `192.168.0.164` read-only. Re-grep before editing: the branch moves.

Full evidence: `/root/work/agent-runs/site/site-audit-162/REPORT.md`.

## 0. Status (2026-10-30, after the fix round)

18 of the 20 findings are fixed on `site-161-framework` plus the `site-165-w19` branch: three (W1, W6-W14, W20) by
`96bee8e1e` "Website audit MEDIUM fixes…", one (W19) by `c9eb56b54`, and W15-W18 by `936df84e9` (two new tests, `Ran
196 tests … OK`). Everything below was re-grepped at `a250d5131` and `936df84e9`.

| W | state | evidence at `a250d5131` (+ `c9eb56b54`) |
|---|---|---|
| W1 | fixed | `tools/site/slums-market.service:14-15` `market_app.py` + `Environment=SLUMS_MARKET_EXCHANGE=on`; `docs/site/DEPLOY.md:176`; `test164/slums-market-test.service:18` already had it |
| W2 | fixed | `tools/site/build.py:154` `.new-<pid>` staging, `:168` `os.rename(out, old)`, failure removes staging and the stale tarball |
| W3 | **open** | `docs/site/releases/` still ends at `1.6.0.md` |
| W4 | fixed | `sections/market.py:44-46` "can't be read as JSON" refusal; `sections/world.py:50` same for the site export |
| W5 | fixed | `sections/market.py:58` `when()` returns `""`; `validate()` checks every printed time |
| W6 | fixed | `tools/site/check.py:283` `(check_top(root) if private else [] if only else check_private(root))` - a private build now gets a top-level whitelist instead of a skipped check |
| W7 | fixed | `sections/world.py:522-523` the split is dropped unless `counted + estimated == kills` on every row |
| W8 | fixed | `sections/world.py:598-601` the hunted board prints `world.json`'s own as-of ("As of …"), the standings keep `leaderboards.json`'s |
| W9 | fixed | `tools/site/slums-login.nginx.conf:62` `location ~ /\. { deny all; }`, same in `test164/nginx.conf:149-150` |
| W10 | fixed | `docs/site/DEPLOY.md:163-168` two renames instead of rsync/`rm -rf`; `check.py` no longer writes into the live dir |
| W11 | **partial** | service half fixed (`login_service.py:497-500` `proxy_hops`, `test164/slums-login-test.service:15` `SLUMS_LOGIN_PROXY_HOPS=0`), `slums-report.nginx.conf:2-6` still keys the zone on the raw `$http_x_forwarded_for` - needs the owner's front-proxy answer |
| W12 | fixed as a step | `DEPLOY.md:207-213` + `1.6.2-deploy-steps.md:166-170` (uncomment the two `SLUMS_LOGIN_INSPECT_*` lines, owner's go); the live unit stays commented on purpose |
| W13 | fixed | `market_service.py:63` `GM_FRESH = 12 * 3600`, `:448-450` `gm_fresh()` - a take-down needs a sign-in inside 12 h; test at `test_market_service.py:181-182` |
| W14 | fixed | `nosniff` + `Referrer-Policy` repeated in the three gated blocks (`slums-login.nginx.conf:57-58` and neighbours) |
| W15 | fixed | `936df84e9`: `item_stats` carries `span` (the newest and oldest shown day's real distance) and the card prints it; a 4-day history whose shown days are Oct 22-25 reads "3-day change", not "4-day" |
| W16 | fixed | `936df84e9`: `sales_as_of()` prints `sales.json`'s `generated_at` on the sales page and in every item page's Recent sales panel, and `validate()` now rejects a `generated_at` that isn't a time |
| W17 | fixed | `936df84e9`: `total_online()` returns the channel sum whenever there is a channel list (the export's own total only stands in when there is none) |
| W18 | fixed | `936df84e9`: `table()` reads the rank it prints (`r["rank"]`, position only as a fallback) and highlights `rank <= 3`, so 1, 1, 3 highlights three rows and a fourth-place row stays plain |
| W19 | fixed | `c9eb56b54`: `AvatarTest` makes its own market export and passes `market_dir`; a clean `git archive` run is `Ran 194 tests … OK` (was 1 error) |
| W20 | fixed | `FRAMEWORK.md:26-28` no longer says "unwired"; `DEPLOY.md` has no "exchange stays off" / "shows server 1.6.1" left; `LOGIN.md:100-101` no daily NX cap |

P1 is delivered too: `a250d5131` adds `/market/stamps` with `GET /api/market/stamps`
(`market_service.py:349-365`, own account row only, never public) - the server's `stamps.json` finally has a reader.
P3 (b005 by-hand checklist on the integrated TEST build) still has no artefact; P4 is W3; P6 (FAQ link to My invites)
is unchanged.

What is left before the 1.6.2 site deploy: W3 (two notes files), W11's proxy decision, W15-W18 (labels and
cross-checks, none of them a nightly risk), and the live box itself - it still runs the old `market_service.py`
(checked read-only on 164), so the unit swap from W1 is a deploy step, not a code one.

## 0b. Bottom line (as audited at `d5f2506f4`)

The site code is in good shape (auth, escaping, SQL, the report lock, the feed contract all hold). What will bite
the 1.6.2 night is **deployment**: the game's exchange goes ON in 1.6.2 but the live market unit runs the old app
with the exchange flag absent, so the site half answers "closed" (W1); a full build wipes the published docroot
before generating and a failed check leaves the previous tarball behind (W2); there are no 1.6.1/1.6.2 patch notes
(W3); and one malformed export crashes the rebuild with a traceback instead of refusing the file (W4, W5). Two
capabilities shipped without their site half: the stamps/account page (P1) and the live bot-inspect proxy (W12).

## 1. HIGH — fix before the 1.6.2 deploy

**W1 · The site's exchange cannot open while the game's does.** `docs/release/1.6.2.conf:11-13` turns the exchange
on. Live evidence: `slums-market.service` on 164 runs `market_service.py` (no exchange routes) with no
`SLUMS_MARKET_EXCHANGE`; the repo unit `tools/site/slums-market.service:11-12` runs `market_app.py` with the flag
commented ("until the owner opens it"), and `market_app.py:210` defaults `off` → `{"open": false}`. The deploy steps
never mention either. `sections/market.py:651-652` tells players it "opens in a later update". Fix: switch
`ExecStart` to `market_app.py`, add `Environment=SLUMS_MARKET_EXCHANGE=on`, add the deploy step, and run the b005
by-hand checklist against the integrated 1.6.2 TEST build (the test164 unit already sets the flag).

**W2 · A full build deletes the published site before generating it.** `tools/site/build.py:148-149` rmtree's `out`
first; `finish()` (`:119-135`) raises `SystemExit("… nothing was packed")` before `make_tar`, so the previous
`dist/slums-site[-private].tar.gz` survives (`:197`). One bad export on a full build = empty docroot plus an old
tarball that looks current. Fix: build into a temp dir and swap on success; delete the stale tarball on failure.

**W3 · 1.6.1 and 1.6.2 have no patch notes.** `docs/site/releases/` ends at `1.6.0.md` while
`docs/site/DEPLOY.md:222-227` requires one per release; `sections/notes.py` sources from that directory, so the home
page's latest-patch card still advertises 1.6.0. Fix: write `1.6.1.md` and `1.6.2.md` before deploying.

**W4 · A malformed export crashes the rebuild with a traceback.** `sections/market.py:65-66` subscripts
`m["sales"]["sales"]` (and `x["player_side"]` / `x["source"]`) before `validate()`, so a truncated `sales.json`
raises `KeyError` instead of `SystemExit("market data refused: …")`; `sections/world.py:741-743` dies the same way.
Fix: guard with `isinstance(m.get("sales"), dict)` / `.get(..., [])`, let `validate()` refuse.

**W5 · Timestamps parsed outside validation.** `sections/world.py:177-181` `pt_day()` calls `fromisoformat` unguarded
while `utc_date()` (`:184-190`) catches `ValueError`; `sections/market.py:138-141` `when()` likewise. One garbled
`t`/`hour` aborts the build. Fix: parse inside `validate()`, or return `""` the way `utc_date()` returns `None`.

## 2. MEDIUM

**W6 · The private-content check is skipped where it is used.** `tools/site/check.py:262-268` —
`([] if private or only else check_private(root))`: both the 2-minute timers (`--private --section world|market`) and
`build.py:236` (`--private --out dist/site`) skip it, so the invariant only holds for the default invocation nobody
deploys.

**W7 · The hunted split is not integrity-checked.** `sections/world.py:517-519` `split()` only asserts ints;
`:560-562` publishes `counted`/`estimated` straight from `world.json`, so `counted + estimated != kills` ships.

**W8 · No freshness on the hunted board; a borrowed as-of on the rankings index.** `sections/world.py:594-599`
hardcodes "Last 7 days" / "Last 24 hours" with no window start or `as of`; `:623-625` stamps one `as of` from
`leaderboards.json` (15-min cadence) over cards fed by `world.json` (60 s). TEST `/world/rankings/hunted` shows
1,433,124 kills under "Kills (est.)" with no as-of anywhere.

**W9 · Staging dirs inside the published docroot, no dot-deny in the shipped template.** `build.py:175-185` creates
`.stage-<folder>` / `.old-<folder>` inside `out`; the box denies dot paths (`sites-enabled/slums-site:75`) but
`tools/site/slums-login.nginx.conf` and `DEPLOY.md` do not — a deployment rebuilt from the docs exposes half-written
pages. Nothing was observed leaking.

**W10 · The publish step is non-atomic and excludes the wrong things.** `DEPLOY.md:120` rsync `--delete --exclude
market/ --exclude world/` deletes `.stage-*`/`.old-*` mid-swap; `:126-127` `rm -rf` + extract; `check.py:168-173`
writes the page set into the live directory before checking it.

**W11 · Per-address limits rest on an unverifiable X-Forwarded-For.** `slums-report.nginx.conf:2-6` (reused for
`slumsapi` at `slums-login.nginx.conf:5`) keys the zone on the raw `$http_x_forwarded_for`, not `real_ip`;
`login_service.py:509-511` trusts the left-most entry while `report_service.py:354-357` documents that left entries
are client-supplied and counts from the right. One of the two is wrong for the deployed front (no Caddy config on
164). Decide one model and key the zones on the resolved address.

**W12 · The staff Bots page cannot work on live at 1.6.2.** `1.6.2-deploy-steps.md:132-133` says "Bot inspect ON for
live … install the site's live proxy settings (port 8098)", but the live login unit keeps `SLUMS_LOGIN_INSPECT_URL`
/ `_SECRET_FILE` commented. Uncomment at deploy; verify with a real bot (ticket 157).

**W13 · GM take-down is a bare flag (documented, decide).** `login_service.py:150-152` sets `g=1` for any GM>0 with
no freshness; `market_service.py:435` removes any post on `g`, while `/admin` needs `gl>=3` + 12 h
(`:164-167`). `docs/site/LOGIN.md:70` documents the current behaviour, so this is an owner decision, not a defect.

**W14 · Gated prefixes lose the site-wide security headers.** `slums-login.nginx.conf:36,41,52` add `Cache-Control`
inside `/database/`, `/market/`, `/secret/`, and nginx drops the server-level `X-Content-Type-Options` /
`Referrer-Policy` there (the file notes it at `:47`). Repeat the headers in those blocks.

## 3. LOW / drift

- **W15** price index: `sections/market.py:428-437` takes median/low/high from the newest day with ≥`MIN_SAMPLES`
  sales (up to 30 days old) and compares against the oldest such day; the item page prints the date (`:456-459`),
  the index heads the column `span(m)` ("last 30 days") and the change is "N-day" in name only.
- **W16** "Recent sales" (`:519-527`) has no as-of; "newest first" is the server's order
  (`Economy/market/MarketSales.java:15`) and the site neither enforces nor stamps it.
- **W17** `sections/world.py:321` "Online now" uses `world.online` and never cross-checks the channel rows printed
  beside it.
- **W18** `sections/world.py:482-483,491-500` rank cell uses the export's `rank` while podium/order use list
  position; bosses are locally re-sorted by `clears`.
- **W19** a fresh clone is red: `Ran 184 tests … FAILED (errors=1)`,
  `tools/site/test_site.py:980 test_ranked_names_get_their_picture_and_others_a_blank` calls `world.load()` at
  `:1002`, which needs the untracked `cache/market-sample` (`sections/market.py:28`).
- **W20** drift: `docs/site/FRAMEWORK.md` (units/login_service/live.js "unwired"); `DEPLOY.md:167-169,192-194`
  ("exchange stays off"); `DEPLOY.md:196` ("shows server 1.6.1"); `docs/sessions/website.md:17-20`;
  `LOGIN.md` NX-cap claim; `1.6.1-notes.md:331`.

## 4. Promised, not delivered

| # | promise | reality |
|---|---|---|
| P1 | stamps / account page on the site (`docs/roadmap/1.6.1.md:79`, `docs/tickets/111-slums-stamps.md:31-32`; server writes `stamps.json` "for the site's account page", `server/checkin/SlumsStamps.java:39-40`, `Economy/board/SiteBoard.java:400-403`) | no site code reads `stamps` at all |
| P2 | NX exchange on the site (`1.6.2.conf:11-13`) | W1 |
| P3 | b005 by-hand checklist on the integrated TEST build | no artefact on disk |
| P4 | patch notes per release (`DEPLOY.md:222-227`) | W3 |
| P5 | bot inspect page on the site for live | W12 |
| P6 | "My invites" reachable from the FAQ (`1.6.1-notes.md:125` vs `docs/wiki/faq.html:37-38`) | page exists under `/market/`; FAQ silent |

## 5. Not defects (checked, so nobody re-litigates)

Auth/sessions (`login_service.py:134-136` HMAC, `:175,:196` compare_digest, `:497` HttpOnly+SameSite=Lax, `:236-259`
logout, `:213-225` reset kills sessions, `:298-301` download pass); SQL parameterised; HTML escaped; loopback binds;
report lock on both sides (`report_service.py:71-78,326-343` and `server/reports/ReportStore.java:234,550`);
feed kind + `t`-string contract (`sections/world.py:33`, `SiteFeed.java:68`, `SiteFileShape.java:51`); exchange
server-side auth (`ExchangeSite.java:35,95,148,198-219`, `NxGuard.java:15`); wiki drift across the 1185-commit gap
is 3 files / 5 lines; dot-path leakage not observed on 8192.

## 6. Re-verify (read-only)

```bash
cd /root/work/SlumsMS-site-161                                   # branch site-161-framework (a250d5131)
git log --oneline -3                                             # W1/W6-W14/W20 and the stamps page
ls docs/site/releases                                            # W3 open: still stops at 1.6.0.md
sed -n '500,512p' tools/site/sections/market.py                  # W15 open: "N-day change" vs the sample days
sed -n '555,565p' tools/site/sections/market.py                  # W16 open: no as-of on Recent sales
sed -n '318,330p' tools/site/sections/world.py                   # W17 open: online vs the channel sum
sed -n '480,490p' tools/site/sections/world.py                   # W18 open: export rank vs list position
git archive HEAD | tar -x -C /tmp/x && cd /tmp/x/tools/site \
  && python3 -m unittest discover -s . -p 'test_*.py'            # W19 fixed: 194 tests, OK, no cache needed
ssh root@192.168.0.164 'grep ExecStart /etc/systemd/system/slums-market.service;
  grep -c SLUMS_MARKET_EXCHANGE /etc/systemd/system/slums-market.service'   # W1: old app, no flag
```
