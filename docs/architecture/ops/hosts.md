# Hosts, units and trust boundaries

## Purpose

The stack runs on five machines plus the owner's reverse proxy: the agent workstation (187), the live box (164), the
TEST box (`mapletest`, 192.168.0.111), the owner's Windows desktop (Silvia) and a GPU box (192.168.0.17) answering the
bots' chat and classifier calls. This page maps host → unit → port → data dir and the trust boundary each hop crosses, so
a tool can tell "TEST, free to change" from "live, owner-gated" before touching anything (`AGENTS.md:16,25`).

## Entry points

| What an operator runs | Where it runs | What it reaches |
|---|---|---|
| `tools/ops/deploy-test [--dry-run] [--feature]`, `autoqa-run <ids\|all>` | 187 | TEST box only; queue written to `Server/data/autoqa/` (`tools/ops/deploy-test:21-25,42`, `tools/ops/autoqa-run:20,29`) |
| `tools/ops/test-status`, `tools/ops/qa-run-collect`, `tools/qa-runner/qarunner.py` | 187 | TEST box (`tools/ops/test-status:11`, `tools/ops/qa-run-collect:27`, `tools/qa-runner/qarunner.py:50`) |
| `tools/release/stage <ver>`, `rehearse <ver>`, `release <ver>` | 187 | `$RELEASE_HOST`, default **live** (`tools/release/lib.sh:4`) |
| `sudo /opt/slumsms-monitor/install.sh live --status --news`, `slumsms-monitor check <target> [--dry-run]` | 164, owner-run | monitor units + `/etc/slumsms-monitor/live.conf` (`deploy/monitor/install.sh:25,53-61`, `deploy/monitor/slumsms-monitor@.service:7`) |
| `tools/ops/install-live-dashboard --go` | 187 → 164 | nginx site for the live dashboard on :8096 (`tools/ops/install-live-dashboard:23,36-39`) |
| `bash /opt/mapleroot-deployments/v<ver>/deploy.sh` | 164, owner-run | live unit `slumsms` (`deploy/v1.4.1/deploy.sh:40,48-50`) |

**Listening ports.** Every port below is named by a repo file; none of them was probed for this page.

| Port | Service | Host | Named at |
|---|---|---|---|
| 8485 / 7585-7592 | live game login / channels, world slot 0 | 164 | `src/main/java/net/server/Server.java:1018` (default), `src/main/java/net/server/channel/Channel.java:64`, `deploy/monitor/targets/live.conf:4`, `src/test/java/net/server/channel/ChannelPortTest.java:11-12` |
| 8489 / 7985-7989 (or -7992) | TEST game login / channels | mapletest | `tools/ops/client-mcp-win/src/testhost.py:27`, `AGENTS.md:66` vs `docs/ops/lxc125-architecture.md:194` |
| 3309 / 3307 (loopback) | live DB `buns-clean-db-1` / TEST DB `mapleroot-integration-db` | 164 / mapletest | `docs/ops/lxc125-architecture.md:190-191`, `AGENTS.md:19`, `deploy/backup/slumsms-restore-drill:35` |
| 8090 / 8092 | patch feed / public site, both nginx | 164 | `docs/ops/lxc125-architecture.md:100`, `docs/HANDOFF.md:130-134` |
| 8091 / 8192 | staff dashboard / TEST site, own nginx process | TEST | `deploy/dashboard/slumsms-dashboard.nginx.conf:17`, `docs/architecture/website/deploy.md:28`, `AGENTS.md:53` |
| 8096 | live staff dashboard, LAN-only | 164 | `deploy/dashboard/slums-dashboard-live.nginx.conf:11,15-16` |
| 8098 | bot inspect API, loopback bind only | whichever game host | `docs/release/1.6.4.conf:271`, `src/main/java/soloMapling/inspect/BotInspectApi.java:61,110-111` |
| 8186 (live) / 8196 (TEST) | site auth API, loopback | 164 / mapletest | `docs/release/1.6.4.conf:229`, `src/main/java/soloMapling/site/auth/SiteAuthApi.java:155`, `docs/architecture/launcher/site-and-account.md:155` |
| 8094 / 8095 / 8093+8097 (live), 8194 / 8195 / 8193+8197 (TEST) | site login / market / report services, loopback | 164 / mapletest | `docs/architecture/website/deploy.md:20-22,27` |
| 8479, 8285-8292 | transient dashboard-test server | 164 | `docs/ops/lxc125-cleanup-review.md:37`, `docs/ops/lxc125-architecture.md:195` |
| 8095 / 8900 / 8910 / 8913 | `qwen9b` chat model / Decide classifier, tone, r7 | 192.168.0.17 | `tools/chatbank/README.md:25`, `tools/chatbank/units/qwen9b-8095.service:22`, `tools/chatbank/units/decide-8910.service:18`, `docs/release/1.6.4.conf:202` |
| 8599 | mock webhook, tests only | 187 | `deploy/monitor/README.md:182` |

Port 8095 is claimed twice — `qwen9b` on 192.168.0.17 and the live market service on 164
(`docs/architecture/website/deploy.md:21`). No collision, but "8095" alone does not name a service.

## How it works

**Topology.** One row per machine; "reads it from the repo" names the file that points there.

| Host | Role | Units | Ports | Data dir | Read by (file:line) |
|---|---|---|---|---|---|
| 187 `openclaw` | agent workstation: checkouts, builds, all ops/release tooling | none (no server) | none | `/root/work`, `/root/work/qa-runs` | `AGENTS.md:51`, `tools/qa-runner/qarunner.py:44` |
| 164 LXC 125 `maplestory` | live game, live DB, patch feed, deploy staging, live dashboard, public site | `slumsms.service`; `.service/.timer` pairs `slumsms-{monitor,status}@live`, `slumsms-announce@live`, `slumsms-backup{,-rotate}`, `slumsms-restore-drill`, `slumsms-nav-bake`, `slumsms-nav-stuck-report`, `slumsms-dashboard-maps`, `slumsms-site-avatars`; `slumsms-news-live.path`; `slumsms-dashboard-extras@live.timer` (not installed) | 8485, 7585+, 3309, 8090, 8092, 8096, 8098, 8186 | `/opt/mapleroot/MapleRoot Full Repack/Server`, `/opt/mapleroot/backups/nightly`, `/opt/mapleroot-deployments`, `/srv/slums-patch`, `/srv/slums-site`, `/srv/slums-dashboard/data` | `AGENTS.md:52,64-67`, `deploy/monitor/targets/live.conf:3-8`, `deploy/monitor/install.sh:54-56,84-90`, `tools/release/lib.sh:4`, `deploy/backup/install.sh:57-60` |
| mapletest 192.168.0.111 | TEST game, TEST DB, TEST site, TEST dashboard, TEST mirror units | `slums-persistence-test` (transient), `slumsms-monitor@persistence-test.timer`, `slumsms-{dashboard-maps,site-avatars}-test.service/.timer`, `slumsms-dashboard-extras@dashboard-test.timer`, `slums-site-test-web.service`, `slums-{login,market,report}-test.service`, `slums-site-test-{market,world}.timer` | 8489, 7985+, 3307, 8192, 8091, 8194-8197 | `/opt/mapleroot-dev/persistence-test/Server`, `/opt/mapleroot-dev/solomapling-test/mysql`, `/srv/slums-dashboard-test/data`, `/srv/slums-site-test` | `AGENTS.md:53,58-60`, `tools/ops/deploy-test:21-25`, `deploy/monitor/targets/persistence-test.conf:3-8`, `docs/architecture/website/deploy.md:27-30` |
| Silvia 192.168.0.186 | owner's Windows desktop: builds the client DLL + launcher exe | none | none | `Z:\MSv83\SlumsMS-dev` (read-write area only) | `AGENTS.md:54`, `docs/ops/lxc125-architecture.md:12` |
| 192.168.0.17 | GPU box: chat model, classifier, tone | `qwen9b-8095`, `decide-8900`, `decide-8910`, `decide-8913` | 8095, 8900, 8910, 8913 | not in this repo | `docs/release/1.6.4.conf:202`, `tools/chatbank/README.md:25` |
| Caddy (owner's) | public TLS front door | none in repo | 443 → 8485 / 8090 / 8092 | not in this repo | `docs/ops/lxc125-architecture.md:15`, `docs/HANDOFF.md:130-134` |

**Trust boundaries.**

1. **Live is the owner's** — `AGENTS.md:16-24` forbids any write to the live unit, DB, folder or patch feed without a
   per-action "go"; reading (logs, files, SELECT) is allowed under the player-privacy rules (`:21-24`). The live DB is
   reachable only on 164:3309 (`AGENTS.md:19`), the dump path never writes there
   (`deploy/backup/slumsms-restore-drill:6,35,225-226`, ledger read SELECT-only at `:154`).
2. **TEST is agent-reachable; 192.168.0.17 is reachable but unmanaged.** 187 → mapletest is the only routine SSH hop for
   agents, through one allowlist (`tools/ops/client-mcp-win/src/testhost.py:23`) with its own key (`:26`). The GPU box
   answers chat/classifier calls (`docs/release/1.6.4.conf:202`), but only the unit files under `tools/chatbank/units/`
   describe it — nothing in this repo manages it.
3. **Keys live on the hosts, not in the repo**: patch key on 164, Tauri signing key on the build desktop, cloud LLM keys
   per host, monitor webhooks on 164 (`AGENTS.md:28-33`). Two nginx htpasswords are reused across boxes —
   the live dashboard authenticates against TEST's file (`deploy/dashboard/slums-dashboard-live.nginx.conf:19`).
4. **Everything routable is behind nginx or the game port.** Site/auth/inspect services bind loopback
   (`SiteAuthApi.java:155`, `BotInspectApi.java:110-111`); the live dashboard adds a LAN allow-list plus basic auth
   (`deploy/dashboard/slums-dashboard-live.nginx.conf:15-18`).
5. **The release path defaults to live.** `tools/release/lib.sh:4` points at 164, so a mistyped `stage`/`rehearse` writes
   to the live box's staging tree — the boundary there is procedural (owner runs it), not enforced by a host check.

**Flow.** How a change travels from 187 to a running server.

1. **187 builds.** Git checkouts under `/root/work`; Maven and node produce the jar and launcher (`AGENTS.md:51`).
2. **TEST deploy crosses one hop.** `tools/ops/lib.sh` resolves the box with `testhost.py --shell` (`:12`), builds
   `TEST_SSH_OPTS` (`BatchMode=yes`, `ConnectTimeout=15`, plus `-i ~/.ssh/id_slums_lxc` for mapletest) (`:17,19`), then
   `test_ssh()` runs `ssh ... root@$TEST_HOST` (`:23`); `deploy-test` refuses a live path (`tools/ops/deploy-test:42`).
3. **A release crosses to live.** `stage` copies to `$RELEASE_HOST:$DEPLOY_ROOT/v$VERSION` after a jar-hash check
   (`tools/release/stage:27,39,82`); `rehearse` execs `rehearse-host.sh` on the same host (`tools/release/rehearse:13`).
   Neither refuses the live path (trust boundary 5).
4. **Live runs one game unit beside batch jobs on one folder.** `slumsms.service` (Java 21, `-Xmx4G`) serves login 8485
   and channels 7585+ from `/opt/mapleroot/MapleRoot Full Repack/Server` (`docs/ops/lxc125-architecture.md:21-22`);
   dashboard-maps and nav units set `WorkingDirectory` there (`deploy/dashboard/slumsms-dashboard-maps.service:12`,
   `deploy/nav/slumsms-nav-bake.service:14`), the test twin at the TEST Server dir
   (`deploy/dashboard/slumsms-dashboard-maps-test.service:8`).
5. **nginx is the only routable web surface; QA cannot run on live.** Loopback binds stay loopback
   (`SiteAuthApi.java:155`, `BotInspectApi.java:45,110-111`); `QaEventFeed` refuses the live login and DB ports
   (`src/main/java/soloMapling/inspect/QaEventFeed.java:76-77`). The monitor ticks one
   `slumsms-monitor@<target>.timer` per watched server each minute (`deploy/monitor/slumsms-monitor@.timer:6`) from
   `/etc/slumsms-monitor/<target>.conf` (`deploy/monitor/install.sh:39,61`).

## Data

| Host | Path | Written by | When |
|---|---|---|---|
| 164 | `/opt/mapleroot/MapleRoot Full Repack/Server` | deploy (`deploy/v1.4.1/deploy.sh:40`) | each release |
| 164 | `/opt/mapleroot/backups/nightly` + `/var/lib/slumsms-backup/` | `slumsms-nightly-backup` (`deploy/backup/slumsms-nightly-backup:27`), rotation state (`deploy/backup/slumsms-backup.conf.example:27`) | daily 11:00 UTC (`deploy/backup/slumsms-backup.timer:5`); each rotation |
| 164 | `/var/lib/slumsms-monitor/<target>/`, `/var/log/slumsms-monitor/<target>.log` | monitor state and fallback log (`deploy/monitor/slumsms-monitor:45-46,164,167`) | every run; log when no webhook |
| 164 `/srv/slums-dashboard/data`; TEST `/srv/slums-dashboard-test/data` (box disputed, see findings) | `DashboardMapExport --out=` (`deploy/dashboard/slumsms-dashboard-maps.service:13`, `...-maps-test.service:9`), `slums-dashboard-extras` (`deploy/dashboard/extras-dashboard-test.conf:4`) | hourly at :40; extras every minute (`deploy/dashboard/slumsms-dashboard-extras@.timer:6`) |
| 164 | `/opt/mapleroot-deployments/v<ver>/`, later `/srv/slums-patch/{manifest.json,layers,tauri}` | `stage` (`tools/release/stage:27,82`); `publish-client` / `publish-launcher` (`tools/release/publish-client:108`) | each stage; publish only on the owner's go |
| 164 | `/var/lib/slums-feedback/reports.jsonl`, `/var/log/slums-dashboard-install.log` | game server (`docs/release/1.6.4.conf:271`); `install-live-dashboard` run from 187 (`tools/ops/install-live-dashboard:18`) | runtime; each install |
| mapletest | `/opt/mapleroot-dev/persistence-test/Server` (+ `DEPLOYED_COMMIT`) | `deploy-test` (`tools/ops/deploy-test:22`) | each TEST deploy |
| 187 | `/root/work/qa-runs` | `qarunner.py` (`tools/qa-runner/qarunner.py:44`) | each run |

Secret material, named by path only: `/root/slums-patch-access-key.txt`, `/root/.config/slumsms-launcher/tauri-signing.key`,
`/root/.config/slumsms/cloud-llm.env`, `/etc/slums-persistence-test/cloud-llm.env`, `/etc/slumsms-monitor/live.env`,
`/etc/slums-auth/secret`, `/etc/slums-inspect/secret`, `/opt/slumsms-db/.env`, `/etc/nginx/slums-patch.htpasswd`
(`AGENTS.md:28-33`, `docs/ops/lxc125-architecture.md:25,100`).

## Switches

| Switch | Home | Default | Effect |
|---|---|---|---|
| `SLUMS_TEST_HOST` | `tools/ops/client-mcp-win/src/testhost.py:21` | `192.168.0.111` (`:24`) | which TEST box the ops/QA tools reach |
| `SLUMS_HOST` / `RELEASE_HOST` | `install-live-dashboard:23` / `tools/release/lib.sh:4` | `root@192.168.0.164` | dashboard install target; stage/rehearse/release target — **live by default** |
| `DEPLOY_ROOT` | `tools/release/lib.sh:5` | `/opt/mapleroot-deployments` | deploy tree on that host |
| `LIVE_PATCH_DIR` | `tools/release/publish-client:108` | `/srv/slums-patch` | which feed counts as live |
| `TEST_DB_PORT` | `deploy/backup/slumsms-restore-drill:35` | `3307` | drill target DSN |
| `SLUMSMS_MONITOR_STATE_DIR` / `_LOG_DIR` | `deploy/monitor/slumsms-monitor:45-46` | `/var/lib/slumsms-monitor`, `/var/log/slumsms-monitor` | monitor paths |
| `AUTOQA_LOCAL_DIR` | `tools/ops/autoqa-run` (local mode) | unset | run the queue locally in tests |
| `-Dmapleroot.login-port` / `.channel-base-port` | `src/main/java/net/server/Server.java:1018`, `src/main/java/net/server/channel/Channel.java:64` | 8485 / 7585 | the ports the guard checks |

## Invariants

- Only two hosts may be named TEST, through one allowlist (`tools/ops/client-mcp-win/src/testhost.py:23`) that every
  reader goes through (`:7-12`); `port()` refuses the live port 8485 (`:27-28`, asserted at
  `tools/ops/client-mcp-win/tests/test_qatools.py:285-295`).
- A TEST tool may not be pointed at a live path: `case "$SRV" in *"/opt/mapleroot/"*|*"Full Repack"*)` exits 1
  (`tools/ops/deploy-test:42`). A rehearsal may not take a used login port: `case $LOGIN_PORT in
  8485|8489|8479|8509)` dies (`deploy/v1.4.1/rehearse.sh:44`).
- The restore drill never restores into live: schema `mapleroot_drill_<date>` on `127.0.0.1:3307`
  (`deploy/backup/slumsms-restore-drill:6,35`), refusing a config still naming `:3309` (`:225-226`).
- QA event emission is off on the live box by port and DB-port detection
  (`src/main/java/soloMapling/inspect/QaEventFeed.java:76-77`); `feature-health` refuses a live host without
  `--live-readonly` (`tools/ops/feature-health:32-33,146-147`).
- The live dashboard is LAN-only plus basic auth (`deploy/dashboard/slums-dashboard-live.nginx.conf:15-19`), installed
  only after `nginx -t` passes (`tools/ops/install-live-dashboard:16-17`).

## Origin

`deploy/slumsms.service.example` is the `03d13cb21` baseline; `AGENTS.md` first landed in `383b0df55`. Everything else is
later and arrived in one commit per concern: dashboard nginx + maps export `a3d8a6ebf` (extras `196a52e20`, maps-test
`7813bcee9`); site avatars `d614b3a18`; nav bake/stuck report `c5461bb44`, `3af98e73b`; monitor (`slumsms-monitor`,
`install.sh`, targets) `eb9e092b1`; backup + restore drill `e533edec8`; ops TEST tooling (`lib.sh` `243fd5591`,
`testhost.py` `ad8a48f58`, `install-live-dashboard` `b746d8899`); release tooling `9d437b372`; the two 164 operation
pages `56ed37251`, `467f718cc`.

## Tests and QA

- `tools/ops/test_testhost.sh` — replaces `ssh` with a printer: asserts the default argv is `root@192.168.0.111` with
  `id_slums_lxc` and BatchMode (`:19-27`) and that only TEST's two boxes are accepted (`:29-30`). No remote calls.
- `tools/ops/client-mcp-win/tests/test_qatools.py:225-295` — `SLUMS_TEST_HOST` moves the guard (`:270-274`), live's port
  is refused (`:292-295`), a refused setting is never silently defaulted (`:276-283`).
- `tools/ops/test_feature_health.py` — `live`/`192.168.0.164` refused without the flag (`:65-70`).
- `deploy/monitor/test_*.py` — monitor check logic against fixtures (`test_announce.py` runs `mock-webhook.py` locally);
  `deploy/backup/test-rotate-protect.sh` covers rotation/protect.
- Not covered: no test asserts a real host's unit/port state, nginx correctness beyond `nginx -t` at install time, or
  that `RELEASE_HOST` names the intended box — `tools/release/test_live_endpoint.py` checks the client's live host/port
  text, not the SSH target.

## Known issues

- **NEW-FINDING: a TEST dashboard port is claimed on two boxes.** `AGENTS.md:53` puts :8091 on `mapletest`;
  `docs/ops/lxc125-architecture.md:109` and `deploy/dashboard/extras-dashboard-test.conf:1` put it on 164. Both cannot hold.
- **NEW-FINDING: `tools/release/lib.sh:4` defaults every release hop to live** — `stage`, `rehearse`, `release` all use
  `$RELEASE_HOST=root@192.168.0.164` and `stage` never refuses that path; the only guard is that the owner runs it,
  unlike `tools/ops/*`, which refuse live by allowlist and path.
- **NEW-FINDING: two docs disagree on the live DB compose file.** `docs/ops/lxc125-architecture.md:25` says
  `/opt/slumsms-db/docker-compose.yml`; `docs/ops/lxc125-cleanup-review.md:32,62` says
  `/opt/buns-clean/docker-compose.clean.yml`, which owns the container — it decides which folder is safe to delete.
- **NEW-FINDING: 164's size and live's folder are stated against stale values.** 12 cores / 48 GB (`AGENTS.md:52`) vs
  6 cores / 24 GB / 430 GB (`docs/ops/lxc125-architecture.md:10`), though card **b299** (`/root/work/STATUS-1.6.4.md:64`)
  records 530 GB. `deploy/slumsms.service.example:10` sets `WorkingDirectory=/opt/slumsms/Server`, a path no doc lists;
  live's is `/opt/mapleroot/MapleRoot Full Repack/Server` (`AGENTS.md:65`).
- **NEW-FINDING: TEST's documented facts disagree, sometimes within one file.** TEST channels are 7985-7989 in
  `AGENTS.md:66` but 7985-7992 in `docs/ops/lxc125-architecture.md:194`, whose own `:121` says "channels 7985+"; live
  runs `CHANNELS=5` (`docs/release/1.6.4.conf:277`). The API allowlist docstring says `DEFAULT_HOST` is 164 and "flips to
  192.168.0.111 then" (`tools/ops/client-mcp-win/src/testhost.py:3-5`, echoed at `tools/qa-runner/qarunner.py:46`) while
  `:24` is already `192.168.0.111`.
- **NEW-FINDING: live monitoring status is contradictory, and its target is stale.**
  `docs/ops/lxc125-architecture.md:70,79` says it is not installed (that section appears twice, `:68` and `:83`), while
  `docs/release/1.6.3-deploy-steps.md:112-115` tells the operator to install it on 164. If installed, it reports
  `VERSION=1.4.0` (`deploy/monitor/targets/live.conf:10`) against a 1.6.4 live.
- **NEW-FINDING: the TEST site installer defaults to the live box.** `docs/architecture/website/deploy.md:14-15` claims
  `192.168.0.111`; the site repo has `TEST_HOST=${SLUMS_TEST_HOST:-192.168.0.164}` (`161-mapletest-site`, `d667a7381`)
  and plain `HOST=root@192.168.0.164` before it (`SlumsMS-site/tools/site/test164/install.sh:8`); its nginx config
  hardcodes `listen 192.168.0.164:8192` (`tools/site/test164/nginx.conf:24`), rewritten by `sed` only on the newer branch
  (`install.sh:66`). `docs/sessions/website.md:21` also names a key console on 164:8791 no tracked file mentions.
- **b268** (monitor log-silence check; `/root/work/STATUS-1.6.4.md:61`) — the login-port check alone missed a hung live
  server, exactly the failure a port-only host map hides.

## State

**Working, with stale documentation.** The guards are real and tested offline — the TEST allowlist
(`tools/ops/client-mcp-win/tests/test_qatools.py:285-295`), the live path refusal (`tools/ops/deploy-test:42`), the
loopback binds (`SiteAuthApi.java:155`, `BotInspectApi.java:110-111`). Unverifiable from this repo: any host's deployed
state — which units are enabled, which ports answer, whether the TEST dashboard sits on mapletest or 164.

## Open questions

- Which box serves the TEST dashboard at :8091 — mapletest (`AGENTS.md:53`) or 164
  (`docs/ops/lxc125-architecture.md:109`, `deploy/dashboard/extras-live.conf:1` says "not installed yet")? Is live
  monitoring installed at all (`docs/release/1.6.3-deploy-steps.md:112`)? The repo records both states.
- Which compose file owns `buns-clean-db-1` now (`/opt/slumsms-db/...` vs `/opt/buns-clean/...`)? Deleting the wrong
  folder would take the live DB's definition with it.
- Does live's `slumsms.service` carry `-Dsolomapling.dashboard.dir=/srv/slums-dashboard/data`? Only
  `docs/release/1.6.0.conf:78` adds it, `docs/release/1.6.4.conf` has none, and
  `tools/ops/install-live-dashboard:20-21` says the page stays empty without it.
- `maplebackup` (ticket 109, `docs/tickets/109-test-backup-infra.md:38-45`) is documented as the off-box target but is
  absent from `AGENTS.md:48-55`; is it live, and does `OFFBOX_DEST` point at it? `deploy/backup/slumsms-backup.conf.example:17`
  ships it empty.
