# Website deploy

## Purpose

The site is a folder of static files plus four small Python services behind nginx; deploying it means replacing that
folder and, when they change, three unit files and three nginx snippets. There is no orchestrator: a copy script builds
the tree the timers run from (`tools/site/deploy-build-164.sh`), a human does the swap and the reloads from the written
recipe (`docs/site/DEPLOY.md`), and the two rebuild timers keep the market and World sections fresh every two minutes.
The one automated installer is TEST-only (`tools/site/test164/install.sh`); it refuses to touch the shared nginx.

## Entry points

| Entry | Kind | What it does |
|---|---|---|
| `tools/site/deploy-build-164.sh [--dry-run]` | manual, run on 187 | rsyncs code/data/icons to `/opt/slums-site-build` on the live host (`deploy-build-164.sh:8-9`); builds nothing (`:21-29`) |
| `tools/site/test164/install.sh [--remove]` | manual, run on 187 | installs the whole TEST copy, renders `@TEST_HOST@`/`@NX_DESK@`, builds, enables and restarts (all steps) |
| `tools/site/test164/qa.sh` → `qa.py` | manual | HTTP checks against the TEST copy (`qa.sh:2-6`) |
| `slums-site-market.timer` → `slums-site-market.service` | timer, every 2 min | `build.py --section market --out /srv/slums-site` (`slums-site-market.service:14-17`) |
| `slums-site-world.timer` → `slums-site-world.service` | timer, every 2 min | `build.py --section world --out /srv/slums-site` (`slums-site-world.service:12-15`) |
| `slums-login`, `slums-market`, `slums-report` | long-running units | the three HTTP services nginx proxies to |
| `slums-site-test-web.service` | long-running | a **second** nginx process on the TEST host with its own config (`test164/slums-site-test-web.service:7-9`) |
| nginx snippets | config | `tools/site/slums-login.nginx.conf`, `slums-report.nginx.conf`, `slums-pass.nginx.conf` pasted into live's block; `test164/nginx.conf` is instead rendered whole |
| `docs/site/DEPLOY.md` §3b, §3c | written recipe | the release-night order, per-step checks and rollback |
| `deploy/site/*`, `deploy/dashboard/*` (server repo) | units+config | avatar export and staff dashboard, deployed by their own paths |

## How it works

The live path, in the order the recipe enforces (`DEPLOY.md:152-252`):

1. **Stop and back up.** Both rebuild timers are stopped, `/srv/slums-site`, `/opt/slums-site-build`, the three units,
   the nginx site file and the report map are copied aside (`DEPLOY.md:162-175`). The report map is copied to `/root`
   because a `.pre` copy left inside `conf.d` is loaded as a second config and `nginx -t` stops on the duplicate `map`
   (`DEPLOY.md:173-174`).
2. **Ship the tree.** On 187, `deploy-build-164.sh` preflights five paths and exits if any is missing — `build.py`,
   `docs/site/home.html`, `data/site/extra-icon-items.txt`, the Hidden Street `items.json`, `cache/site-icons/manifest.json`
   (`deploy-build-164.sh:18-20`) — then `ssh mkdir -p` `DEST/{tools,docs,hiddenstreet,data,cache}` (`:21`) and seven
   `rsync -a --delete` copies (`:22-27`). It writes the git short hash to `$DEST/DEPLOYED_COMMIT` (`:28`).
3. **Build beside the live folder.** On the live host, `build.py --private --world --no-tar --out /var/tmp/slums-site-161`
   (`DEPLOY.md:183-187`). `build.build` renders into `{out}.new-{pid}`, runs the checks, and only then renames `out` out
   of the way and the new folder in (`build.py:148-172`).
4. **Check.** `build.finish` runs `check.check_all` and raises `SystemExit("site check failed with N problem(s): the
   published site was left as it was, nothing was packed")` (`build.py:134-139`). `check_all` concatenates
   forbidden-string, link, noindex, top-level and private-section checks (`check.py:285-290`).
5. **Swap atomically.** `cp -a` to `/srv/slums-site.new-$S`, then `mv live old && mv new live` — "never an rsync or
   `rm -rf` into the live folder" (`DEPLOY.md:190-196`).
6. **Units.** Diff the unit against the installed one, `install -m 0644`, `systemctl daemon-reload`, restart
   login/report/market, then curl the three `/health` URLs for `{"ok": true}` (`DEPLOY.md:198-217`).
7. **nginx.** Paste part 3 of `slums-login.nginx.conf` (clean URLs, `/admin/`, `/admin/data/`, `/api/report/staff/`,
   `@signagain`, `absolute_redirect off` at server level) and part 1 of `slums-report.nginx.conf` (the `map`), then
   `nginx -t && systemctl reload nginx` (`DEPLOY.md:218-227`).
8. **Timers on.** One `systemctl start` of each oneshot, then the timers, then `is-failed` and a grep on the rebuilt
   page (`DEPLOY.md:229-233`), then the through-nginx checks on `http://127.0.0.1:8092` (`DEPLOY.md:234-244`).

Steady state instead uses `build.rebuild_section` (`build.py:178-212`): the section is built into `.stage-<folder>`,
the live folder is renamed to `.old-<folder>` and the stage renamed in (`:194-203`); the section-only check runs
after the swap and, on `SystemExit`, deletes the new folder and renames `.old` back "so a visitor never sees a
half-written section" (`:205-211`). Both oneshots serialise through one flock (`slums-site-market.service:11`,
`slums-site-world.service:11`), because one's link check would otherwise run while the other swaps a folder.

TEST is the same build with a different host, port and paths, driven by `install.sh`:

1. `TEST_HOST=${SLUMS_TEST_HOST:-192.168.0.111}` and `NX_DESK=${SLUMS_TEST_NX_DESK:-off}`, both validated
   (`install.sh:11-18`).
2. Preflight `data/site/extra-icon-items.txt` and `$DATA/items.json` (`:37-39`), then record the shared nginx's pid and
   an md5 of `/etc/nginx` (`:40`).
3. Create folders and **throwaway** keys with `umask 077` — session key, ip-salt, download-pass key, inspect secret,
   report salt — none printed (`:42-66`).
4. Seven rsyncs mirroring the live copy, with `testdata/nx-desk` as the only testdata (`:68-76`).
5. `DEPLOYED_COMMIT`, `sed` the two placeholders into the nginx config and every unit, `daemon-reload`, `nginx -t -q -c`,
   full build under `flock -w 300` and `nice -n 19` (`:77-88`).
6. `enable` four services and two timers, restart them, start the oneshots, `is-active` (`:89-93`).
7. Re-hash and fail if the shared nginx pid or config changed (`:94-95`). `--remove` reverses the install and keeps the
   key, salts and market db (`:26-33`).

## Data

| Path | Written by | Contents |
|---|---|---|
| `/opt/slums-site-build` | `deploy-build-164.sh:21-28` | the tree the timers and `market_app.py` run from; `DEPLOYED_COMMIT` is the only build stamp |
| `/srv/slums-site` | build.py swap (`build.py:169-172`), recipe step 5 | the served static site |
| `/srv/slums-site/assets/build.json` | `build.copy_assets` (`build.py:91-92`) | `{"site": <version>}`, stamped on every report (`slums-report.service:16`) |
| `/srv/slums-site/world/online.json` | World rebuild | server version for reports (`slums-report.service:15`) |
| `/var/lib/slums-market/market.db` | `market_app.py` | watchlists, alerts, posts, cards (`slums-market.service:21`) |
| `/var/lib/slums-market/{inbox,outbox}` | game server writes, service reads | exchange/board request files (`slums-market.service:23-24`) |
| `/var/lib/slums-feedback/reports.jsonl` | `report_service.py` | append-only reports (`slums-report.service:10`) |
| `/var/lib/slums-login/revoked.txt` | operator | banned account ids, read by login, market and the game (`slums-login.service:11`) |
| `/etc/slums-login/key`, `/etc/slums-login/ip-salt`, `/etc/slums-auth/secret`, `/etc/slums-report/salt` | owner, once | secrets, named here by path only |
| `/opt/slums-site-test`, `/srv/slums-site-test`, `/etc/slums-site-test/nginx.conf` | `install.sh` | the TEST mirror |

Nothing in the deploy path writes a ledger or a lock file beyond `/run/lock/slums-site-build.lock`
(`slums-site-market.service:14`); the only rollback state is the operator's `.bak-$S`/`.old-$S` folders.

## Switches

| Switch | Home | Default | Effect |
|---|---|---|---|
| `SLUMS_HS_DATA` | env, `deploy-build-164.sh:10` | `data/site/hiddenstreet` | which dataset is copied |
| `--dry-run` | arg, `deploy-build-164.sh:12` | off | `rsync --dry-run --itemize-changes` |
| `SLUMS_TEST_HOST` | env, `install.sh:11` / `qa.sh:5` | `192.168.0.111` | the one TEST address; IPv4 only (`:16-18`) |
| `SLUMS_TEST_NX_DESK` | env, `install.sh:14` | `off` | `off|mock|on`, rendered into both TEST units |
| `SLUMS_MARKET_NX_DESK` | unit, `slums-market.service:18`, `slums-site-market.service:13` | `on` live, unset = off in code | must match in service and rebuild (test at `test_deploy.py:348-361`) |
| `SLUMS_MARKET_EXCHANGE` | unit, `slums-market.service:15` | `on` | Exchange open |
| `SLUMS_LOGIN_PORT/_AUTH_URL/_AUTH_SECRET_FILE/_IP_SALT_FILE` | `slums-login.service:12-16` | 8094, `127.0.0.1:8186` | service wiring |
| `SLUMS_LOGIN_INSPECT_URL/_SECRET_FILE` | `slums-login.service:20-21` | commented out | bot inspect, off until the owner's go (`:17-19`) |
| `SLUMS_REPORT_PORT/_STAFF_PORT/_WORLD_FILE/_SITE_BUILD` | `slums-report.service:10-17` | 8093/8097 | report wiring |
| `Absolute_redirect` | nginx, `slums-login.nginx.conf:83` | off | relative redirects behind the front proxy |
| `SLUMS_HOST` | `tools/ops/install-live-dashboard:23` | `root@192.168.0.164` | dashboard installer target |

The live host's own address is hard-coded in `deploy-build-164.sh:8` and `install-live-dashboard:23,37`; the TEST host is
not (that is enforced by `test_deploy.py:223-234`). Whether any of these differ on the running host today is
**unknown** — the units in the repo are copies, and the recipe says a live unit must be diffed before install
(`DEPLOY.md:198-199`).

## Invariants

- **A failed check never publishes.** `build.build` builds into `{out}.new-{pid}` and removes it on any exception
  (`build.py:156-168`); `finish` raises before any rename (`build.py:134-139`); the tarball is deleted first so a
  failure cannot leave one behind (`build.py:154-155`). Tested at `test_build_safety.py:31-40`.
- **A section is never half-written.** `rebuild_section` stages, swaps with two renames and swaps back on a failed
  check (`build.py:194-211`). nginx refuses dot paths so a `.stage-`/`.old-` folder is unreachable
  (`slums-login.nginx.conf:65-67`).
- **The live swap is two renames, not a copy** (`DEPLOY.md:190-196`, audit W10).
- **The published folder is checked, not the sources.** `check_all` re-reads the output (`check.py:285-290`): forbidden
  strings (`:22-31`), `.164`/any IPv4/any e-mail (`:34-41`), private sections in a public build (`:233`).
- **No secret is in the repo.** `.gitignore:7-9,17` excludes `config.yaml`, `.env`, `.env.*`, `/secrets/`; `git ls-files`
  shows no key, htpasswd or env file — `docs/site/SECRETS.md` and `docs/site/secret/*.webp` are the card answer key and
  art. Enforcement is by convention only: nothing scans for a leaked secret.
- **TEST never touches the shared nginx.** `install.sh:40` hashes `/etc/nginx` and `:94-95` fails the run if the pid or
  hash changed. The TEST nginx is a separate process with its own pid and temp dirs (`test164/nginx.conf:7-26`).
- **TLS ends outside the repo.** No `ssl_certificate` and no `listen 443` exists in any site-repo nginx file (grep,
  2026-11-05); the owner's Caddy terminates HTTPS and proxies to `164:8092` (`LAUNCH.md:26-30`), and every redirect is
  relative because of `absolute_redirect off` (`slums-login.nginx.conf:79-83`). Convention, not enforcement: nothing in
  the repo fails if a direct `:8092` exposure appears.
- **Services listen on loopback only.** `login_service.py:838`, `report_service.py:502,509,526`, `market_app.py:286` bind
  `127.0.0.1`; nginx is the only listener on a routable port.

## Origin

Custom, on the repack baseline. Site repo branch `site-164-street`, 714 commits, root commit `03d13cb21` 2026-10-18
"Establish deployable MapleRoot server baseline". First commits per file (`git log --follow … | tail -1`):

| File | First commit |
|---|---|
| `tools/site/deploy-build-164.sh` | `61359e94a` 2026-10-26 "164 build-folder deploy script; relative sign-in redirect behind Caddy" |
| `tools/site/slums-login.service`, `slums-login.nginx.conf` | `93bbca9bb` 2026-10-26 "Website sign-in with a game account…" |
| `tools/site/slums-market.service` | `99a001c04` 2026-10-26 "Slums Market: watchlist, price alerts and a Buy/Sell board" |
| `tools/site/slums-report.service`, `slums-report.nginx.conf` | `5122b392d` 2026-10-25 "Add a public report-a-bug / request-a-feature form" |
| `tools/site/slums-site-market.{service,timer}` | `fc3daae81` 2026-10-26 "systemd units for the 15-minute market rebuild on 164 (not installed)" |
| `tools/site/slums-site-world.{service,timer}` | `c5e619de7` 2026-10-26 "Site: World pages … Database renamed Slums Street" |
| `tools/site/slums-pass.nginx.conf` | `fd7f7ac7f` 2026-10-27 "Download pass for the launcher (ticket 106)" |
| `tools/site/test164/install.sh`, `test164/nginx.conf` | `671c779de` 2026-10-26 "TEST copy of the site on 164 (not installed yet)" |
| `docs/site/DEPLOY.md` | `74099f82c` 2026-10-24 "Add a public website: home, 1.5.0 patch notes and the player guide" |

Server-repo counterparts: `deploy/site/slumsms-site-avatars.{service,timer}` and the `-test` variant from `d614b3a18`
2026-10-27; `deploy/dashboard/slums-dashboard-live.nginx.conf` and `slumsms-dashboard.nginx.conf` from `a3d8a6ebf`
2026-10-19; `slums-dashboard-extras@.*` from `196a52e20` 2026-10-20; `tools/ops/install-live-dashboard` from
`b746d8899` 2026-10-26. All custom — the upstream repack ships no site.

## Tests and QA

`tools/site/test_deploy.py` (361 lines) is the deploy suite; it never contacts a host — it builds a fake repo and stubs
`git`, `ssh` and `rsync` (`:32-77`):

- `LiveBuildCopyTest` (`:79-96`) — `data/site` including `extra-icon-items.txt` reaches `/opt/slums-site-build`, and a
  missing file stops the copy before any rsync.
- `TestSiteInstallTest` (`:99-128`) — the same for the TEST install; `test_every_script_sent_to_the_test_host_parses`
  (`:111-121`) pipes each remote script through `bash -n`, the check added after a broken unit loop on release night.
- `NginxRedirectTest` (`:145-161`) — `absolute_redirect off` must be a server-level line in `test164/nginx.conf` and in
  the live recipe's part 3a.
- `DeployDocTest` (`:178-207`) — the recipe itself is asserted: the report map backed up outside `conf.d`, step 2 naming
  the icon list, step 5 installing the map and the redirect line, step 8 handling a unit with no inspect lines, the
  rollback restoring the map, and the release-night note.
- `TestHostTest` (`:210-277`) — one default host across `install.sh`/`qa.sh`/`qa.py`, `@TEST_HOST@` rendered where nginx
  needs it, an IPv4 requirement.
- `TestNxDeskModeTest` (`:280-316`) — `@NX_DESK@` in both units, filled by the install, `mock` fixtures copied, a wrong
  mode refused.
- `GuideTest` (`:325-341`) — the newest release row exists in `docs/wiki/changelog.html` and §4 names the refresh.
- `LiveNxDeskUnitsTest` (`:348-361`) — both live units carry `SLUMS_MARKET_NX_DESK=on`.

Also relevant: `test_build_safety.py:17-45` (failed full build keeps the published site, no stale tarball, no half-built
folder), `test_site.py:276-301` (rebuild in place, no `.stage`/`.old` left, rollback on a failed check), and
`test_download_pass.py:122-153` (the `/api/auth/pass` answers 204/401/503 through a real server).

Not covered: nothing runs nginx or systemd, so no test asserts that a live unit's port, path or user matches the nginx
snippet that proxies to it (only the NX-desk mode is paired); nothing tests part 2's auth gating of `/street/`,
`/market/` or `/secret/`; nothing proves the `--delete` rsyncs cannot remove live files (`DEPLOY.md:121-123` relies on
the operator's icon count); the rollback has never been rehearsed (`DEPLOY.md:256`); and no check compares a
hand-installed live unit with the repo copy.

## Known issues

- **L19 (Ops, open)** — "Put the merged tree on TEST and run the lane checks": nothing from 1.6.4 has been deployed to
  TEST, so every statement here about TEST reflects the branch, not a running install.
- **L16 (Website, open)** — the site does not yet list or sell the new exchange items; a rebuild of `market` carries
  whatever the export has, so no deploy change is implied.
- **b299 (Ops, S2, MITIGATED)** — the live host's root disk was 95% full; the owner grew it on Nov 3. A full disk stops
  the live database and therefore both rebuilds and all three services; the deploy path has no disk preflight.
- **w06 (Website, later, qw01)** — `database` → Slums Street rename; the deploy side is done (`/database/` 301s to
  `/street/`, `slums-login.nginx.conf:35-37`, `test164/nginx.conf:128-130`), the content side is not.
- **w10 (Website, later)** — more Slums-chan cards; new pictures under `docs/site/secret/` need a full rebuild because
  `copy_assets` copies them at build time (`build.py:93-94`), not a timer run.
- **NEW-FINDING:** `docs/architecture/website/README.md:41` says `deploy-build-164.sh` "ships the checked output and
  reloads the units and nginx". It does neither: it copies sources into the build folder and stops
  (`deploy-build-164.sh:21-29`), and `DEPLOY.md:177` states "it builds nothing".
- **NEW-FINDING:** `docs/site/LAUNCH.md:168` says "`cache/site-icons` is not needed (the market reuses the database's
  icons already published)", while `deploy-build-164.sh:14-15,27` copies the cache precisely because a build without it
  empties the live icon slots, and `DEPLOY.md:121-123` makes an operator check it. LAUNCH.md is stale.
- **NEW-FINDING:** the market rebuild is described as "15-minute" in `slums-site-market.service`'s first commit
  (`fc3daae81`) and in `test164/README.md:17`, but both timers run every 2 minutes
  (`slums-site-market.timer:10`, `slums-site-test-market.timer:8`). Comments and the timer disagree in the repo.
- **NEW-FINDING:** two tickets share the number 162 — `docs/tickets/162-nx-forex.md` and
  `docs/tickets/162-gear-optimizer-full.md` — while `slums-market.service:16` and `DEPLOY.md:205` cite "1.6.3, ticket
  162" for the NX desk. The cross-reference is ambiguous.
- **NEW-FINDING:** `deploy/dashboard/slums-dashboard-live.nginx.conf:19` authenticates the live dashboard with
  `/etc/nginx/slums-dashboard-test.htpasswd` — TEST's file, by path (`install-live-dashboard:38,53`). It is deliberate,
  but rotating the TEST password silently changes the live dashboard's.

## State

**Partial.** Copying, building, the atomic swap, the section rebuilds, the unit set and the nginx snippets all exist and
are exercised by tests and by two release nights (`DEPLOY.md:152-290`); the TEST installer is complete and rehearses the
whole flow except hosting. What is missing is any *automation* of the live half: the live deploy is 8 hand-run steps
whose only guard is a written checklist, one of whose steps (`DEPLOY.md:190-196`) is done by hand rather than by
`build.py`, and whose rollback has never been run (`DEPLOY.md:256`). Evidence either way for the running host is out of
scope here — the units in the repo are copies.

## Open questions

- Do the installed live units match the repo copies? `DEPLOY.md:198-199` asks the operator to `diff` before installing,
  which implies they diverged at least once (the 1.6.2 night: `DEPLOY.md:288-289`). Nothing records the answer.
- Which host actually serves the live site today — the same one that runs the game DB, or the LXC `DEPLOY.md:24-34`
  describes? The recipe talks about "the LXC" (`DEPLOY.md:113`) and about `/opt/slums-site-build` in the same breath;
  the repo does not resolve it, so this doc says "the live host" throughout.
- Is `slums-site-market.service` still "not installed" as its commit message says (`fc3daae81`)? `DEPLOY.md:229-230`
  starts it and `is-failed`-checks it, so the message looks stale, but no file here proves an install.
- Why does `deploy-build-164.sh` not exclude `testdata/` from the icon copy too — only from `tools/site/`
  (`deploy-build-164.sh:22`)? `testdata/nx-desk` is what `SLUMS_TEST_NX_DESK=mock` serves, so a live tree carrying it
  would expose fixtures nothing reads. Harmless today, unclear by intent.
- Should `check.py`'s forbidden list run over the *deployed* folder as a post-deploy gate? Today the only check is inside
  the build, which is skipped entirely by the timers' `--section` path when they swap a single folder
  (`build.py:206` passes `only=folder`).
