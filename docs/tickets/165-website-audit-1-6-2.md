# 165: Website audit — 1.6.1/1.6.2 promises vs what shipped (20 findings)

**Status:** open · **Owner:** Website lane · **Source:** owner, 2026-10-30 (paraphrased): read-only audit of the
website in the same style as tickets 163 (bots) and 164 (playbook) — bugs plus promised-vs-actual for 1.6.1/1.6.2 —
ending in a ticket.

**Anchors:** site worktree `/root/work/SlumsMS-site-161` @ `d5f2506f4` (branch `site-161-framework`, forked
`06a724dfc`, 1185 commits behind `release-1.6.2`); server `docs/release/1.6.2.conf`,
`docs/release/1.6.2-deploy-steps.md`; box `192.168.0.164` read-only. Re-grep before editing: the branch moves.

Full evidence: `/root/work/agent-runs/site/site-audit-162/REPORT.md`.

## 0. Bottom line

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
cd /root/work/SlumsMS-site-161
sed -n '146,152p;196,199p' tools/site/build.py                     # W2 rmtree before build, tar on success only
sed -n '262,268p' tools/site/check.py                              # W6 check_private skipped
sed -n '177,191p' tools/site/sections/world.py                     # W5 guarded vs unguarded parse
sed -n '65,66p' tools/site/sections/market.py                      # W4 subscript before validate
sed -n '206,212p' tools/site/market_app.py                         # W1 default off
ls docs/site/releases                                              # W3 stops at 1.6.0.md
ssh root@192.168.0.164 'grep ExecStart /etc/systemd/system/slums-market.service;
  grep -c SLUMS_MARKET_EXCHANGE /etc/systemd/system/slums-market.service'   # W1: old app, no flag
```
