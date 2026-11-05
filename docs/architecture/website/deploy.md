# Website deploy

## Purpose

The site is a folder of static files plus three small Python services behind nginx; deploying it means replacing that
folder and, when they change, three unit files and three nginx snippets. There is no orchestrator: a copy script ships
the tree the timers run from (`tools/site/deploy-build-164.sh`), a human does the swap and the reloads from a written
recipe (`docs/site/DEPLOY.md`), and two rebuild timers refresh the market and World sections every two minutes. The one
automated installer is TEST-only (`tools/site/test164/install.sh`) and refuses to touch the shared nginx.

## Entry points

"Live host" is whatever machine serves `/srv/slums-site`; the repo names it only as the address in
`/root/work/SlumsMS-site-164-street/tools/site/deploy-build-164.sh:8`, so this doc does not repeat it. "TEST host" is `SLUMS_TEST_HOST`, default `192.168.0.111`
(`install.sh:11`). Every service binds loopback (`/root/work/SlumsMS-site-164-street/tools/site/login_service.py:838`, `/root/work/SlumsMS-site-164-street/tools/site/report_service.py:502,509,526`,
`/root/work/SlumsMS-site-164-street/tools/site/market_app.py:286`); nginx is the only routable listener.

| Unit / entry | Host | Port | What it does |
|---|---|---|---|
| `slums-login.service` | live | 127.0.0.1:8094 | sign-in, session check, download passes (`slums-login.service:9,12`) |
| `slums-market.service` | live | 127.0.0.1:8095 | Flask market app, exchange, board (`slums-market.service:14,27`) |
| `slums-report.service` | live | 127.0.0.1:8093 public, :8097 staff | reports and the staff reader (`slums-report.service:9,17`) |
| live nginx `server{}` | live | :8092 | serves `/srv/slums-site` (`LAUNCH.md:26-27`) |
| `slums-site-{market,world}.timer`→`.service` | live | none | section rebuild every 2 min (`slums-site-market.timer:10`) |
| `deploy-build-164.sh` | 187 → live | none | copies sources to `/opt/slums-site-build` (`:21-28`) |
| `tools/site/slums-{login,report,pass}.nginx.conf` | live | under :8092 | numbered parts pasted into the live block |
| `slums-{login,market,report}-test.service` | **TEST** | 127.0.0.1:8194 / 8195 / 8193+8197 | mirror of the three services |
| `slums-site-test-web.service` | **TEST** | `SLUMS_TEST_HOST`:8192 | second nginx process, own config (`:7-9`) |
| `slums-site-test-{market,world}.timer` | **TEST** | none | same rebuilds into `/srv/slums-site-test` |
| `test164/install.sh`, `qa.sh` | 187 → TEST | none | the only full installer, and the HTTP checks |
| `deploy/site/slumsms-site-avatars{,-test}.{service,timer}` | game host | none | PNG avatar export into the site data folder |
| `deploy/dashboard/slums-dashboard-live.nginx.conf` / `slumsms-dashboard.nginx.conf` | live :8096 LAN-only / **TEST** :8091 | as listed | staff dashboard; live adds `allow 192.168.0.0/24` + basic auth (`:15-19`) |

TLS is not in this repo: `theslumsms.asslorde.com` ends at the owner's Caddy, which reverse-proxies to the live host's
8092 (`LAUNCH.md:28-30`). No site-repo nginx file has `ssl_certificate` or `listen 443` (grep, 2026-11-05).

## How it works

The live path, in the order the recipe enforces (`docs/site/DEPLOY.md:152-252`):

1. **Stop and back up.** Both timers stop; `/srv/slums-site`, `/opt/slums-site-build`, the three units, the nginx site
   file and the report map are copied aside (`DEPLOY.md:162-172`). The map goes to `/root` because a `.pre` copy inside
   `conf.d` loads as a second config and `nginx -t` stops on the duplicate `map` (`:173-174`).
2. **Ship the tree.** `deploy-build-164.sh` preflights five paths and exits if any is missing — `build.py`,
   `docs/site/home.html`, `extra-icon-items.txt`, the Hidden Street `items.json`, the icon manifest (`:18-20`) — then
   `ssh mkdir -p`, seven `rsync -a --delete` copies (`:21-27`) and `DEPLOYED_COMMIT` (`:28`).
3. **Build beside the live folder.** `build.py --private --world --no-tar --out /var/tmp/slums-site-161`
   (`DEPLOY.md:183-187`) renders into `{out}.new-{pid}` and renames it in only after the checks (`docs/wiki/build.py:148-172`).
4. **Check.** `build.finish` runs `check.check_all` and raises `SystemExit("site check failed with N problem(s): the
   published site was left as it was, nothing was packed")` (`docs/wiki/build.py:134-139`); `check_all` concatenates the
   forbidden-string, link, noindex, top-level and private-section checks (`/root/work/SlumsMS-site-164-street/tools/site/check.py:285-290`).
5. **Swap atomically.** `cp -a` to `/srv/slums-site.new-$S`, then `mv live old && mv new live` — "never an rsync or
   `rm -rf` into the live folder" (`DEPLOY.md:190-196`).
6. **Units.** `diff` against the installed unit, `install -m 0644`, `daemon-reload`, restart login/report/market, curl
   the three `/health` URLs for `{"ok": true}` (`DEPLOY.md:198-217`).
7. **nginx.** Paste part 3 of `slums-login.nginx.conf` (clean URLs, `/admin/`, `/admin/data/`, `/api/report/staff/`,
   `@signagain`, server-level `absolute_redirect off`) with part 1 of `slums-report.nginx.conf` (the `map`), then
   `nginx -t && systemctl reload nginx` (`DEPLOY.md:218-227`).
8. **Timers on, then verify.** Start each oneshot, then the timers, `is-failed`, a grep on the rebuilt page
   (`DEPLOY.md:229-233`), then signed-out and signed-in checks through `http://127.0.0.1:8092` (`:234-244`).

Steady state uses `build.rebuild_section` instead (`docs/wiki/build.py:178-212`): the section builds into `.stage-<folder>`, the
live folder is renamed `.old-<folder>`, the stage renamed in (`:194-203`); the section-only check runs *after* the swap
and, on `SystemExit`, deletes the new folder and renames `.old` back, "so a visitor never sees a half-written section"
(`:205-211`). Both oneshots serialise through one flock (`slums-site-market.service:14`, `slums-site-world.service:12`),
because one's link check would otherwise run while the other swaps a folder.

TEST is the same build with another host, port and paths (`install.sh:11-95`): validate `SLUMS_TEST_HOST` and
`SLUMS_TEST_NX_DESK` (`:11-18`), preflight the icon list (`:37-39`), hash the shared nginx's pid and `/etc/nginx`
(`:40`), make folders and throwaway keys under `umask 077` (`:42-66`), rsync seven times with `testdata/nx-desk` as the
only testdata (`:68-76`), then one remote script: `DEPLOYED_COMMIT`, `sed` `@TEST_HOST@`/`@NX_DESK@` into the config and
every unit, `daemon-reload`, `nginx -t -q -c`, a full build under `flock -w 300`/`nice -n 19`, `enable` and restart four
services and two timers, start the oneshots, `is-active` (`:77-93`), then re-hash and fail if the pid or config changed
(`:94-95`). `--remove` reverses it, keeping the key, salts and market db (`:26-33`).

What rolls back: nothing automatic. The live rollback restores the step-1 backups in a fixed order — timers stopped, failed
folders moved aside, `/opt/slums-site-build`, `/srv/slums-site`, the two Python files, the three units,
`conf.d/slums-report.conf` and the site file back, `daemon-reload`, restart, timers on (`DEPLOY.md:254-272`); market-only
is one unit restore (`:274-277`); 1.6.3 added a desk-only variant (`:347-351`). TEST's rollback is `install.sh --remove`.

## Data

| Path | Written by | Contents |
|---|---|---|
| `/opt/slums-site-build` | `/root/work/SlumsMS-site-164-street/tools/site/deploy-build-164.sh:21-28` | the tree the timers and `market_app.py` run from; `DEPLOYED_COMMIT` is the only stamp |
| `/srv/slums-site` | `docs/wiki/build.py:169-172`, recipe step 5 | the served static site |
| `.../assets/build.json` | `build.copy_assets` (`docs/wiki/build.py:91-92`) | `{"site": <version>}`, read per report (`slums-report.service:16`) |
| `.../world/online.json` | World rebuild | server version for reports (`slums-report.service:15`) |
| `/var/lib/slums-market/{market.db,inbox,outbox}` | `market_app.py`; game writes inbox/outbox | watchlists, alerts, posts, cards (`slums-market.service:21-24`) |
| `/var/lib/slums-feedback/reports.jsonl` | `report_service.py` | append-only reports (`slums-report.service:10`) |
| `/var/lib/slums-login/revoked.txt` | operator | banned ids read by login, market and the game (`slums-login.service:11`) |
| `/etc/slums-login/{key,ip-salt}`, `/etc/slums-auth/secret`, `/etc/slums-report/salt` | owner, once | secrets — named by path only |
| `/opt/slums-site-test`, `/srv/slums-site-test`, `/etc/slums-site-test/nginx.conf` | `install.sh` | the TEST mirror |

Nothing else persists: the only lock is `/run/lock/slums-site-build.lock` (`slums-site-market.service:14`), and the only
rollback state is the operator's `.bak-$S`/`.old-$S` folders.

## Switches

| Switch | Home | Default | Effect |
|---|---|---|---|
| `SLUMS_HS_DATA` | env, `/root/work/SlumsMS-site-164-street/tools/site/deploy-build-164.sh:10` | `data/site/hiddenstreet` | which dataset is copied |
| `--dry-run` | arg, `/root/work/SlumsMS-site-164-street/tools/site/deploy-build-164.sh:12` | off | `rsync --dry-run --itemize-changes` |
| `SLUMS_TEST_HOST` | env, `install.sh:11`, `/root/work/SlumsMS-site-164-street/tools/site/test164/qa.sh:5` | `192.168.0.111` | the one TEST address; IPv4 only (`:16-18`) |
| `SLUMS_TEST_NX_DESK` | env, `install.sh:14` | `off` | `off|mock|on`, rendered into both TEST units |
| `SLUMS_MARKET_NX_DESK` | `slums-market.service:18`, `slums-site-market.service:13` | `on` live; unset = off in code | must match in service and rebuild (`/root/work/SlumsMS-site-164-street/tools/site/test_deploy.py:348-361`) |
| `SLUMS_MARKET_EXCHANGE` | `slums-market.service:15` | `on` | exchange open |
| `SLUMS_LOGIN_{PORT,AUTH_URL,AUTH_SECRET_FILE,IP_SALT_FILE}` | `slums-login.service:12-16` | 8094, `127.0.0.1:8186` | service wiring |
| `SLUMS_LOGIN_INSPECT_{URL,SECRET_FILE}` | `slums-login.service:20-21` | commented out | bot inspect, off until the owner's go (`:17-19`) |
| `SLUMS_REPORT_{PORT,STAFF_PORT,WORLD_FILE,SITE_BUILD}` | `slums-report.service:10-17` | 8093 / 8097 | report wiring |
| `absolute_redirect off` | nginx, `slums-login.nginx.conf:83` | off | relative redirects behind the front proxy |
| `SLUMS_HOST` | `tools/ops/install-live-dashboard:23` | `root@<live>` | dashboard installer target |

The live address is hard-coded in `/root/work/SlumsMS-site-164-street/tools/site/deploy-build-164.sh:8` and `install-live-dashboard:23,37`; the TEST one is not
(`/root/work/SlumsMS-site-164-street/tools/site/test_deploy.py:223-234`). Whether any value differs on the running host is **unknown** — the repo units are copies, and
the recipe says to `diff` a live unit before installing (`DEPLOY.md:198-199`).

## Invariants

- **A failed check never publishes.** `build.build` works in `{out}.new-{pid}` and removes it on any exception
  (`docs/wiki/build.py:156-168`); `finish` raises before any rename (`:134-139`); a stale tarball is deleted first (`:154-155`).
  Tested at `/root/work/SlumsMS-site-164-street/tools/site/test_build_safety.py:31-40`.
- **A section is never half-written.** Stage, two renames, check, swap back on failure (`docs/wiki/build.py:194-211`); nginx denies
  dot paths so `.stage-`/`.old-` is unreachable (`slums-login.nginx.conf:65-67`).
- **The live swap is two renames, not a copy** (`DEPLOY.md:190-196`, audit W10).
- **The published folder is checked, not the sources** (`/root/work/SlumsMS-site-164-street/tools/site/check.py:285-290`): forbidden strings (`:22-31`),
  `.164`/any IPv4/any e-mail (`:34-41`), private sections in a public build (`:233`).
- **No secret is in the repo.** `.gitignore:7-9,17` excludes `config.yaml`, `.env`, `.env.*`, `/secrets/`, and
  `git ls-files` shows no key, htpasswd or env file; `docs/site/SECRETS.md` and `docs/site/secret/*.webp` are the card
  answer key and art. Convention only: nothing scans for a leaked secret.
- **TEST never touches the shared nginx** — `install.sh:40` hashes `/etc/nginx` and `:94-95` fails the run if the pid or
  hash changed; the TEST nginx is a separate process with its own pid and temp dirs (`test164/nginx.conf:7-26`).
- **TLS ends outside the repo**, and redirects are relative because of `absolute_redirect off`
  (`slums-login.nginx.conf:79-83`). Convention, not enforcement: nothing fails if 8092 is exposed directly.
- **Services listen on loopback only** (`/root/work/SlumsMS-site-164-street/tools/site/login_service.py:838`, `/root/work/SlumsMS-site-164-street/tools/site/report_service.py:502,509,526`, `/root/work/SlumsMS-site-164-street/tools/site/market_app.py:286`).

## Origin

All custom — the base v83 repack ships no site. Site repo branch `site-164-street`, 714 commits, root `03d13cb21`
2026-10-18 "Establish deployable MapleRoot server baseline"; every deploy file arrives in one 2026-10-24…27 burst
(`git log --follow … | tail -1`). `docs/site/DEPLOY.md` `74099f82c` was first (10-24, "Add a public website"),
`slums-report.*` `5122b392d` (10-25), then `93bbca9bb` (login), `99a001c04` (market), `61359e94a` (`deploy-build-164.sh`),
`fc3daae81` (market units, message "not installed"), `c5e619de7` (world units) and `671c779de` (TEST copy) on 10-26,
`fd7f7ac7f` (download pass) on 10-27. Server-repo counterparts: avatars `d614b3a18` 2026-10-27, dashboards `a3d8a6ebf`
2026-10-19, `slums-dashboard-extras@.*` `196a52e20` 2026-10-20, `install-live-dashboard` `b746d8899` 2026-10-26.

## Tests and QA

`tools/site/test_deploy.py` (361 lines) is the deploy suite; it never contacts a host — it builds a fake repo and stubs
`git`, `ssh` and `rsync` (`:32-77`):

- `LiveBuildCopyTest` (`:79-96`) — `data/site` including `extra-icon-items.txt` reaches `/opt/slums-site-build`; a
  missing file stops the copy before any rsync.
- `TestSiteInstallTest` (`:99-128`) — same for TEST; `test_every_script_sent_to_the_test_host_parses` (`:111-121`) pipes
  each remote script through `bash -n`, added after a broken unit loop on release night.
- `NginxRedirectTest` (`:145-161`) — `absolute_redirect off` must be server-level in `test164/nginx.conf` and in the
  recipe's part 3a. `DeployDocTest` (`:178-207`) — the map is backed up outside `conf.d`, step 2 names the icon list,
  step 5 installs map and redirect line, step 8 handles a unit with no inspect lines, the rollback restores the map.
- `TestHostTest` (`:210-277`) — one default host across `install.sh`/`qa.sh`/`qa.py`, `@TEST_HOST@` rendered where nginx
  needs it, IPv4 required. `TestNxDeskModeTest` (`:280-316`) — `@NX_DESK@` in both units, filled by the install, `mock`
  fixtures copied, a wrong mode refused; `LiveNxDeskUnitsTest` (`:348-361`) — both live units carry `on`.
  `GuideTest` (`:325-341`) — the newest release row exists in `docs/wiki/changelog.html`.

Also relevant: `/root/work/SlumsMS-site-164-street/tools/site/test_build_safety.py:17-45` (failed full build keeps the published site, no stale tarball, no half-built
folder), `/root/work/SlumsMS-site-164-street/tools/site/test_site.py:276-301` (rebuild in place, no `.stage`/`.old` left, rollback on a failed check),
`/root/work/SlumsMS-site-164-street/tools/site/test_download_pass.py:122-153` (the pass endpoint's 204/401/503 through a real server).

Not covered: nothing runs nginx or systemd, so no test asserts that a live unit's port, path or user matches the snippet
proxying to it (only the NX-desk mode is paired); nothing tests part 2's gating of `/street/`, `/market/`, `/secret/`;
nothing proves the `--delete` rsyncs cannot remove live files (`DEPLOY.md:121-123` relies on an operator's icon count);
the rollback has never been rehearsed (`DEPLOY.md:256`); and no check compares an installed live unit with its repo copy.

## Known issues

- **L19 (Ops, open)** — "Put the merged tree on TEST and run the lane checks": nothing from 1.6.4 is on TEST, so every
  TEST statement here reflects the branch, not a running install.
- **L16 (Website, open)** — the site does not list or sell the new exchange items; the `market` rebuild carries whatever
  the export has, so no deploy change is implied.
- **b299 (Ops, S2, MITIGATED)** — the live host's root disk was 95% full; the owner grew it on Nov 3. A full disk stops
  the live database and therefore both rebuilds and all three services; the deploy path has no disk preflight.
- **w06 (Website, later, qw01)** — `database` → Slums Street: deploy side done (`/database/` 301s to `/street/`,
  `slums-login.nginx.conf:35-37`, `test164/nginx.conf:128-130`), content side not.
- **w10 (Website, later)** — more Slums-chan cards; new pictures under `docs/site/secret/` need a full rebuild, since
  `copy_assets` copies them at build time (`docs/wiki/build.py:93-94`), not a timer run.
- **NEW-FINDING:** `docs/architecture/website/README.md:41` says `deploy-build-164.sh` "ships the checked output and
  reloads the units and nginx". It does neither — it copies sources into the build folder and stops
  (`/root/work/SlumsMS-site-164-street/tools/site/deploy-build-164.sh:21-29`), and `DEPLOY.md:177` states "it builds nothing".
- **NEW-FINDING:** `docs/site/LAUNCH.md:168` says "`cache/site-icons` is not needed", while `/root/work/SlumsMS-site-164-street/tools/site/deploy-build-164.sh:14-15,27`
  copies it precisely because a build without it empties the live icon slots (`DEPLOY.md:121-123`). LAUNCH.md is stale.
- **NEW-FINDING:** the market rebuild is called "15-minute" in `fc3daae81`'s message and `test164/README.md:17`, but both
  timers run every 2 minutes (`slums-site-market.timer:10`, `slums-site-test-market.timer:8`).
- **NEW-FINDING:** two tickets share number 162 (`docs/tickets/162-nx-forex.md`, `162-gear-optimizer-full.md`) while
  `slums-market.service:16` and `DEPLOY.md:205` cite "1.6.3, ticket 162" for the NX desk — an ambiguous reference.
- **NEW-FINDING:** `deploy/dashboard/slums-dashboard-live.nginx.conf:19` authenticates the *live* dashboard with
  `/etc/nginx/slums-dashboard-test.htpasswd` — TEST's file, by path (`install-live-dashboard:38,53`). Deliberate, but
  rotating the TEST password silently changes the live dashboard's.

## State

**Partial.** Copying, building, the atomic swap, the section rebuilds, the unit set and the nginx snippets all exist and
are exercised by tests and by two release nights (`DEPLOY.md:152-290`); the TEST installer rehearses the whole flow except
hosting. What is missing is any *automation* of the live half: the live deploy is eight hand-run steps whose only guard is
a written checklist, one swap (`DEPLOY.md:190-196`) done by hand rather than by `build.py`, and a rollback never run
(`DEPLOY.md:256`). Evidence about the running host is out of scope — the repo's units are copies.

## Open questions

- Do the installed live units match the repo copies? `DEPLOY.md:198-199` tells the operator to `diff` first, implying they
  diverged at least once (the 1.6.2 night: `DEPLOY.md:288-289`). Nothing records the answer.
- Which machine is "the live host" — the one running the game database, or the LXC `DEPLOY.md:24-34` describes? The recipe
  says "the LXC" and uses `/opt/slums-site-build` in the same breath; unresolved here.
- Is `slums-site-market.service` still "not installed" as `fc3daae81` says? `DEPLOY.md:229-230` starts and `is-failed`
  checks it, so the message looks stale, but no file proves an install.
- Why does `/root/work/SlumsMS-site-164-street/tools/site/deploy-build-164.sh:22` exclude `testdata/` from the `tools/site` copy but nothing else? `testdata/nx-desk` is
  exactly what `SLUMS_TEST_NX_DESK=mock` serves, so a live tree carrying unread fixtures is harmless but unexplained.
- Should `check.py`'s forbidden list also run over the deployed folder as a post-deploy gate? Today the only check runs
  inside the build, and the timers pass `only=folder` (`docs/wiki/build.py:206`), so a section swap checks one section.
