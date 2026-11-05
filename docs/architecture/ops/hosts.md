# Hosts, units and trust boundaries

## Purpose

The stack runs on four machines and one reverse proxy: the agent workstation (187), the live box (164), the TEST box
(`mapletest`, 192.168.0.111) and the owner's Windows desktop (Silvia), plus a GPU box (192.168.0.17) that answers the
bots' chat and classifier calls. This page maps which host runs which unit on which port, where each host's data lives,
and which hops cross a trust boundary — so that a tool, a script or an agent can tell "TEST, free to change" from
"live, owner-gated" before it touches anything (`AGENTS.md:16`, `AGENTS.md:25`).

## Entry points

| What an operator runs | Where it runs | What it reaches |
|---|---|---|
| `tools/ops/deploy-test [--dry-run] [--feature]` | 187 | TEST box only (`tools/ops/deploy-test:21-25,42`) |
| `tools/ops/autoqa-run <ids|all>` | 187 | TEST box, writes the queue in `Server/data/autoqa/` (`tools/ops/autoqa-run:20,29`) |
| `tools/ops/test-status`, `tools/ops/qa-run-collect`, `tools/qa-runner/qarunner.py` | 187 | TEST box (`tools/ops/test-status:11`, `tools/ops/qa-run-collect:27`, `tools/qa-runner/qarunner.py:50`) |
| `tools/release/stage <ver>`, `rehearse <ver>`, `release <ver>` | 187 | `$RELEASE_HOST`, default **live** (`tools/release/lib.sh:4`) |
| `sudo /opt/slumsms-monitor/install.sh live --status --news` | 164, owner-run | installs monitor units + `/etc/slumsms-monitor/live.conf` (`deploy/monitor/install.sh:25,53-61`) |
| `tools/ops/install-live-dashboard --go` | 187 → 164 | nginx site for the live dashboard on :8096 (`tools/ops/install-live-dashboard:23,36-39`) |
| `slumsms-monitor check <target> [--dry-run]` | any host with the monitor | one target's checks (`deploy/monitor/slumsms-monitor@.service:7`) |
| `bash /opt/mapleroot-deployments/v<ver>/deploy.sh` | 164, owner-run | live unit `slumsms` (`deploy/v1.4.1/deploy.sh:40,48-50`) |

**Listening ports.** Every port below is named by a repo file; none of them was probed for this page.

| Port | Service | Host | Named at |
|---|---|---|---|
| 8485 | live game login | 164 | `src/main/java/net/server/Server.java:1018` (default), `deploy/monitor/targets/live.conf:4` |
| 7585-7592 | live channels, world slot 0 | 164 | `src/main/java/net/server/channel/Channel.java:64`, `src/test/java/net/server/channel/ChannelPortTest.java:11-12` |
| 8489 | TEST game login | mapletest | `tools/ops/client-mcp-win/src/testhost.py:27`, `AGENTS.md:66` |
| 7985-7989 (or -7992) | TEST channels | mapletest | `AGENTS.md:66` vs `docs/ops/lxc125-architecture.md:194` |
| 3309 | live DB container `buns-clean-db-1` | 164 | `docs/ops/lxc125-architecture.md:191`, `AGENTS.md:19` |
| 3307 (loopback) | TEST DB `mapleroot-integration-db` | mapletest | `docs/ops/lxc125-architecture.md:190`, `deploy/backup/slumsms-restore-drill:35` |
| 8090 | patch feed nginx | 164 | `docs/ops/lxc125-architecture.md:100` |
| 8091 | staff dashboard (template the TEST site was made from) | TEST | `deploy/dashboard/slumsms-dashboard.nginx.conf:17` |
| 8096 | live staff dashboard, LAN-only | 164 | `deploy/dashboard/slums-dashboard-live.nginx.conf:11,15-16` |
| 8098 | bot inspect API, loopback bind only | whichever game host | `docs/release/1.6.4.conf:271`, `src/main/java/soloMapling/inspect/BotInspectApi.java:61,110-111` |
| 8186 (live) / 8196 (TEST) | site auth API, loopback | 164 / mapletest | `docs/release/1.6.4.conf:229`, `src/main/java/soloMapling/site/auth/SiteAuthApi.java:155`, `docs/architecture/launcher/site-and-account.md:155` |
| 8092 | public site nginx | 164 | `docs/HANDOFF.md:130-134` |
| 8192 | TEST site nginx | mapletest | `AGENTS.md:53` |
| 8094 / 8095 / 8093+8097 | site login / market / report services, loopback | 164 | `docs/architecture/website/deploy.md:20-22` |
| 8194 / 8195 / 8193+8197 | the same three, TEST | mapletest | `docs/architecture/website/deploy.md:27` |
| 8479, 8285-8292 | transient dashboard-test server | 164 | `docs/ops/lxc125-cleanup-review.md:37`, `docs/ops/lxc125-architecture.md:195` |
| 8095 | `qwen9b` chat model | 192.168.0.17 | `tools/chatbank/README.md:25`, `tools/chatbank/units/qwen9b-8095.service:22` |
| 8900 / 8910 / 8913 | Decide classifier / tone / r7 model | 192.168.0.17 | `tools/chatbank/units/decide-8910.service:18`, `docs/release/1.6.4.conf:202` |
| 8599 | mock webhook, tests only | 187 | `deploy/monitor/README.md:182` |

8095 is used twice: by `qwen9b` on 192.168.0.17 and by the live market service on 164 (`docs/architecture/website/deploy.md:21`).
Different hosts, so nothing collides — but "8095" alone does not identify a service.

## How it works

**Topology.** One row per machine; "reads it from the repo" names the file that points there.

| Host | Role | Units | Ports | Data dir | Read by (file:line) |
|---|---|---|---|---|---|
| 187 `openclaw` | agent workstation: checkouts, builds, all ops/release tooling | none (no server) | none | `/root/work`, `/root/work/qa-runs` | `AGENTS.md:51`, `tools/qa-runner/qarunner.py:44` |
| 164 LXC 125 `maplestory` | live game, live DB, patch feed, deploy staging, live dashboard, public site | `slumsms.service`, `slumsms-monitor@live.timer`, `slumsms-status@live.timer`, `slumsms-news@live.timer`, `slumsms-announce@live.timer`, `slumsms-backup.service/.timer`, `slumsms-backup-rotate.service/.timer`, `slumsms-restore-drill.service/.timer`, `slumsms-nav-bake.service/.timer`, `slumsms-nav-stuck-report.service/.timer`, `slumsms-dashboard-maps.service/.timer`, `slumsms-site-avatars.service/.timer`, `slumsms-dashboard-extras@live.timer` (not installed yet) | 8485, 7585+, 3309, 8090, 8092, 8096, 8098, 8186 | `/opt/mapleroot/MapleRoot Full Repack/Server`, `/opt/mapleroot/backups/nightly`, `/opt/mapleroot-deployments`, `/srv/slums-patch`, `/srv/slums-site`, `/srv/slums-dashboard/data` | `AGENTS.md:52,64-67`, `deploy/monitor/targets/live.conf:3-8`, `tools/release/lib.sh:4`, `deploy/backup/install.sh:57-60` |
| mapletest 192.168.0.111 | TEST game, TEST DB, TEST site, TEST dashboard, TEST mirror units | `slums-persistence-test` (transient), `slumsms-monitor@persistence-test.timer`, `slumsms-dashboard-maps-test.service/.timer`, `slumsms-site-avatars-test.service/.timer`, `slumsms-dashboard-extras@dashboard-test.timer` | 8489, 7985+, 3307, 8192, 8091, 8194-8197 | `/opt/mapleroot-dev/persistence-test/Server`, `/opt/mapleroot-dev/solomapling-test/mysql`, `/srv/slums-dashboard-test/data` | `AGENTS.md:53,58-60`, `tools/ops/deploy-test:21-25`, `deploy/monitor/targets/persistence-test.conf:3-8` |
| Silvia 192.168.0.186 | owner's Windows desktop: builds the client DLL + launcher exe | none | none | `Z:\MSv83\SlumsMS-dev` (read-write area only) | `AGENTS.md:54`, `docs/ops/lxc125-architecture.md:12` |
| 192.168.0.17 | GPU box: chat model, classifier, tone | `qwen9b-8095`, `decide-8900`, `decide-8910`, `decide-8913` | 8095, 8900, 8910, 8913 | not in this repo | `docs/release/1.6.4.conf:202`, `tools/chatbank/README.md:25` |
| Caddy (owner's) | public TLS front door | none in repo | 443 → 8485 / 8090 / 8092 | not in this repo | `docs/ops/lxc125-architecture.md:15`, `docs/HANDOFF.md:130-134` |

**Trust boundaries.**

1. **Live is the owner's** — `AGENTS.md:16-24` forbids any write to the live unit, DB, folder or patch feed without an
   explicit per-action "go"; reading (logs, files, SELECT) is allowed with player-privacy rules (`:21-24`).
2. **TEST is agent-reachable.** 187 → mapletest is the only routine SSH hop for agents, through one allowlist
   (`tools/ops/client-mcp-win/src/testhost.py:23`) with its own key (`:26`).
3. **The live DB is 164-only, port 3309** (`AGENTS.md:19`); the dump/restore path never writes to it
   (`deploy/backup/slumsms-restore-drill:6,35,225-226`) and the drill's ledger read is SELECT-only (`:154`).
4. **Keys live on the hosts, not in the repo**: patch key on 164, Tauri signing key on the build desktop, cloud LLM keys
   per host, monitor webhooks on 164 (`AGENTS.md:28-33`). Two nginx htpasswords are reused across boxes —
   the live dashboard authenticates against TEST's file (`deploy/dashboard/slums-dashboard-live.nginx.conf:19`).
5. **Everything routable is behind nginx or the game port.** Site/auth/inspect services bind loopback
   (`SiteAuthApi.java:155`, `BotInspectApi.java:110-111`); the live dashboard adds a LAN allow-list plus basic auth
   (`deploy/dashboard/slums-dashboard-live.nginx.conf:15-18`).
6. **The release path defaults to live.** `tools/release/lib.sh:4` points at 164, so a mistyped `stage`/`rehearse` writes
   to the live box's staging tree — the boundary there is procedural (owner runs it), not enforced by a host check.
7. **Agents may reach 192.168.0.17** for chat/classifier calls (`docs/release/1.6.4.conf:202`) but that box is not
   managed from this repo — only the unit files under `tools/chatbank/units/` describe it.

**Flow.** How a change travels from 187 to a running server.

1. **Workstation 187 builds.** Git checkouts live under `/root/work`, Maven and node build the jar and launcher
   (`AGENTS.md:51`); nothing on 187 is a server.
2. **TEST deploy crosses one hop.** `tools/ops/lib.sh` resolves the TEST box through `testhost.py --shell`
   (`tools/ops/lib.sh:12`), builds `TEST_SSH_OPTS` with `BatchMode=yes -o ConnectTimeout=15` (`:17`) and adds
   `IdentitiesOnly=yes -i ~/.ssh/id_slums_lxc` when the resolved host is mapletest (`:19`); `test_ssh()` then runs
   `ssh ... root@$TEST_HOST` (`:23`).
3. **The allowlist is one file.** `testhost.py` holds `ENV="SLUMS_TEST_HOST"`, `TEST_HOSTS=("192.168.0.164",
   "192.168.0.111")`, `DEFAULT_HOST="192.168.0.111"`, `PORT="8489"`, `LIVE_PORT="8485"`
   (`tools/ops/client-mcp-win/src/testhost.py:21-28`), and its docstring says every reader goes through it and
   "Nothing else may read SLUMS_TEST_HOST" (`:7-12`).
4. **Release tooling crosses to live.** `stage` copies the built tree to `$RELEASE_HOST:$DEPLOY_ROOT/v$VERSION` after
   checking the jar hash against `BUILD.txt` (`tools/release/stage:8,27,39,43,82`); `rehearse` execs
   `rehearse-host.sh` on the same host (`tools/release/rehearse:13`).
5. **The live host runs one game unit plus timers.** `slumsms.service` (Java 21, `-Xmx4G`) serves login 8485 and channels
   7585+ from `/opt/mapleroot/MapleRoot Full Repack/Server` (`docs/ops/lxc125-architecture.md:21-22`); ports come from
   `-Dmapleroot.login-port` (default 8485, `src/main/java/net/server/Server.java:1018`) and
   `-Dmapleroot.channel-base-port` (default 7585, `src/main/java/net/server/channel/Channel.java:64`).
6. **Batch jobs and servers share that folder read-only.** `slumsms-dashboard-maps.service` and the nav units run with
   `WorkingDirectory` set to the live Server dir (`deploy/dashboard/slumsms-dashboard-maps.service:12`,
   `deploy/nav/slumsms-nav-bake.service:14`), the test twins point at the TEST Server dir
   (`deploy/dashboard/slumsms-dashboard-maps-test.service:8`).
7. **nginx fronts the web surfaces.** Patch feed :8090, TEST dashboard :8091, live dashboard :8096, public site :8092,
   TEST site :8192 — each bound in a repo file (see the ports table above). The live dashboard site allows `192.168.0.0/24`
   and denies the rest (`deploy/dashboard/slums-dashboard-live.nginx.conf:15-16`).
8. **Loopback services stay loopback.** The site auth API binds `InetAddress.getLoopbackAddress()`
   (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:155`) and logs "listening on 127.0.0.1"
   (`:121`); the bot inspect API refuses any non-loopback bind (`src/main/java/soloMapling/inspect/BotInspectApi.java:45,110-111`).
9. **QA refuses live by construction.** `QaEventFeed` hard-codes `LIVE_LOGIN_PORT=8485` and `LIVE_DB_PORT=":3309"` and
   refuses to attach when either matches (`src/main/java/soloMapling/inspect/QaEventFeed.java:76-77`), so a jar pointed
   at the live box cannot emit QA events.
10. **The monitor watches per target.** One `slumsms-monitor@<target>.timer` per watched server, every minute
    (`deploy/monitor/slumsms-monitor@.timer:6`), config from `/etc/slumsms-monitor/<target>.conf`
    (`deploy/monitor/install.sh:39,61`), alert webhooks from `<target>.env` at 0600 (`deploy/monitor/targets/example.env:1`).

## Data

| Host | Path | Written by | When |
|---|---|---|---|
| 164 | `/opt/mapleroot/MapleRoot Full Repack/Server` | deploy (`deploy/v1.4.1/deploy.sh:40`) | each release |
| 164 | `/opt/mapleroot/backups/nightly` | `slumsms-nightly-backup` (`deploy/backup/slumsms-nightly-backup:27`) | daily 11:00 UTC (`deploy/backup/slumsms-backup.timer:5`) |
| 164 | `/var/lib/slumsms-monitor/<target>/` | monitor state (`deploy/monitor/slumsms-monitor:45,164`) | every run |
| 164 | `/var/log/slumsms-monitor/<target>.log` | monitor fallback log (`deploy/monitor/slumsms-monitor:46,167`) | when no webhook |
| 164 | `/var/lib/slumsms-backup/` | rotation plan/state (`deploy/backup/slumsms-backup.conf.example:27`) | each rotation |
| 164 | `/srv/slums-dashboard/data`, `/srv/slums-dashboard-test/data` | `DashboardMapExport --out=` (`deploy/dashboard/slumsms-dashboard-maps.service:13`) | hourly at :40 (`deploy/dashboard/slumsms-dashboard-maps.timer:7`) |
| 164 | `/srv/slums-patch/{manifest.json,layers,tauri}` | `publish-client` / `publish-launcher` (`tools/release/publish-client:108`) | owner's go only |
| 164 | `/opt/mapleroot-deployments/v<ver>/` | `stage` (`tools/release/stage:27,82`) | each stage |
| 164 | `/var/lib/slums-feedback/reports.jsonl` | game server (`docs/release/1.6.4.conf:271`) | runtime |
| mapletest | `/opt/mapleroot-dev/persistence-test/Server` | `deploy-test` (`tools/ops/deploy-test:22`) | each TEST deploy |
| mapletest | `/opt/mapleroot-dev/persistence-test/Server/DEPLOYED_COMMIT` | deploy (`tools/qa-runner/qarunner.py:50`) | each TEST deploy |
| 187 | `/root/work/qa-runs` | `qarunner.py` (`tools/qa-runner/qarunner.py:44`) | each run |
| 187 | `/var/log/slums-dashboard-install.log` | `install-live-dashboard` on 164 (`tools/ops/install-live-dashboard:18`) | each install |

Secret material, named by path only: `/root/slums-patch-access-key.txt`, `/root/.config/slumsms-launcher/tauri-signing.key`,
`/root/.config/slumsms/cloud-llm.env`, `/etc/slums-persistence-test/cloud-llm.env`, `/etc/slumsms-monitor/live.env`,
`/etc/slums-auth/secret`, `/etc/slums-inspect/secret`, `/opt/slumsms-db/.env`, `/etc/nginx/slums-patch.htpasswd`
(`AGENTS.md:28-33`, `docs/ops/lxc125-architecture.md:25,100`).

## Switches

| Switch | Home | Default | Effect |
|---|---|---|---|
| `SLUMS_TEST_HOST` | `tools/ops/client-mcp-win/src/testhost.py:21` | `192.168.0.111` (`:24`) | which TEST box the ops/QA tools reach |
| `SLUMS_HOST` | `install-live-dashboard:23` | `root@192.168.0.164` | dashboard install target |
| `RELEASE_HOST` | `tools/release/lib.sh:4` | `root@192.168.0.164` | stage/rehearse/release target — **live by default** |
| `DEPLOY_ROOT` | `tools/release/lib.sh:5` | `/opt/mapleroot-deployments` | deploy tree on that host |
| `LIVE_PATCH_DIR` | `tools/release/publish-client:108` | `/srv/slums-patch` | which feed counts as live |
| `TEST_DB_PORT` | `deploy/backup/slumsms-restore-drill:35` | `3307` | drill target DSN |
| `SLUMSMS_MONITOR_STATE_DIR` / `_LOG_DIR` | `deploy/monitor/slumsms-monitor:45-46` | `/var/lib/slumsms-monitor`, `/var/log/slumsms-monitor` | monitor paths |
| `AUTOQA_LOCAL_DIR` | `tools/ops/autoqa-run` (local mode) | unset | run the queue locally in tests |
| `-Dmapleroot.login-port` / `.channel-base-port` | `src/main/java/net/server/Server.java:1018`, `src/main/java/net/server/channel/Channel.java:64` | 8485 / 7585 | the ports the guard checks |

## Invariants

- Only two hosts may be named as TEST: the allowlist is `TEST_HOSTS` (`tools/ops/client-mcp-win/src/testhost.py:23`), and
  every reader goes through it (`:7-12`).
- 8489 is TEST's login port, 8485 is live's; `port()` refuses the live port (`tools/ops/client-mcp-win/src/testhost.py:27-28`, asserted at
  `tools/ops/client-mcp-win/tests/test_qatools.py:285-295`).
- A TEST tool may not be pointed at a live path: `case "$SRV" in *"/opt/mapleroot/"*|*"Full Repack"*)` exits 1
  (`tools/ops/deploy-test:42`).
- A rehearsal may not use a live or test login port: `case $LOGIN_PORT in 8485|8489|8479|8509)` dies
  (`deploy/v1.4.1/rehearse.sh:44`).
- The restore drill never restores into live: schema is `mapleroot_drill_<date>` on `127.0.0.1:3307`
  (`deploy/backup/slumsms-restore-drill:6,35`) and it refuses a config still naming `:3309` (`:225-226`).
- QA event emission is disabled on the live box by port and DB-port detection (`src/main/java/soloMapling/inspect/QaEventFeed.java:76-77`).
- The live dashboard is LAN-only plus basic auth (`deploy/dashboard/slums-dashboard-live.nginx.conf:15-19`), and its
  installer re-checks with `nginx -t` before reloading (`tools/ops/install-live-dashboard:16-17`).
- `feature-health` refuses a live host without `--live-readonly` (`tools/ops/feature-health:32-33,146-147`).

## Origin

| File | First commit |
|---|---|
| `deploy/slumsms.service.example` | `03d13cb21` (baseline) |
| `AGENTS.md` | `383b0df55` |
| `deploy/dashboard/*.nginx.conf`, `slumsms-dashboard-maps.service` | `a3d8a6ebf` |
| `deploy/dashboard/extras-*.conf`, `slums-dashboard-extras@.service` | `196a52e20` |
| `deploy/dashboard/slumsms-dashboard-maps-test.service` | `7813bcee9` |
| `deploy/site/slumsms-site-avatars*.service` | `d614b3a18` |
| `deploy/nav/slumsms-nav-bake.service` | `c5461bb44` |
| `deploy/monitor/targets/*.conf`, `install.sh`, `slumsms-monitor` | `eb9e092b1` |
| `deploy/backup/*` | `e533edec8` |
| `tools/ops/lib.sh` | `243fd5591` |
| `tools/ops/client-mcp-win/src/testhost.py` | `ad8a48f58` |
| `tools/ops/install-live-dashboard` | `b746d8899` |
| `tools/release/lib.sh` | `9d437b372` |
| `docs/ops/lxc125-architecture.md` | `56ed37251` |
| `docs/ops/lxc125-cleanup-review.md` | `467f718cc` |

## Tests and QA

- `tools/ops/test_testhost.sh` — shell-level: replaces `ssh` with a printer and asserts the default argv is
  `root@192.168.0.111` with `id_slums_lxc` and BatchMode (`:19-27`), and that only TEST's two boxes are accepted (`:29-30`).
  No remote calls.
- `tools/ops/client-mcp-win/tests/test_qatools.py:225-295` — `SLUMS_TEST_HOST` moves the guard (`:270-274`), live's port
  is refused (`:292-295`), a refused setting is never silently defaulted (`:276-283`).
- `tools/ops/test_feature_health.py` — `live`/`192.168.0.164` refused without the flag (`:65-70`).
- `deploy/monitor/test_*.py` — monitor check logic against fixtures; `test_announce.py` runs `mock-webhook.py` locally.
- `deploy/backup/test-rotate-protect.sh` — rotation/protect rules.
- Not covered: no test asserts a real host's unit/port state, nginx site correctness beyond `nginx -t` at install time,
  or that `RELEASE_HOST` points at the intended box. `tools/release/test_live_endpoint.py` checks the client's
  live host/port text, not the SSH target.

## Known issues

- **NEW-FINDING: a TEST dashboard port is claimed on two boxes.** `AGENTS.md:53` puts the TEST dashboard on `mapletest`
  `:8091`; `docs/ops/lxc125-architecture.md:109` and `deploy/dashboard/extras-dashboard-test.conf:1` put it on 164 at
  `:8091` with data `/srv/slums-dashboard-test/data`. Both cannot be true; the repo cannot say which box serves it.
- **NEW-FINDING: `tools/release/lib.sh:4` defaults every release hop to live.** `stage`, `rehearse` and `release` all
  use `$RELEASE_HOST=root@192.168.0.164`, and nothing in `tools/release/stage` refuses the path — the only guard is that
  the owner runs the command. Contrast `tools/ops/*`, which refuse live by allowlist and path.
- **NEW-FINDING: two docs disagree on the live DB compose file.** `docs/ops/lxc125-architecture.md:25` says
  `/opt/slumsms-db/docker-compose.yml`; `docs/ops/lxc125-cleanup-review.md:32,62` says
  `/opt/buns-clean/docker-compose.clean.yml` and that the container belongs to that project. Operationally it decides
  which folder is safe to delete.
- **NEW-FINDING: `testhost.py` contradicts itself.** The docstring still says `DEFAULT_HOST` is 164 and "flips to
  192.168.0.111 then" (`tools/ops/client-mcp-win/src/testhost.py:3-5`, echoed at `tools/qa-runner/qarunner.py:46`), while
  `:24` is already `192.168.0.111`.
- **NEW-FINDING: `AGENTS.md:52` and `docs/ops/lxc125-architecture.md:10` disagree on 164's size** — 12 cores / 48 GB
  versus 6 cores / 24 GB / 430 GB. Card **b299** (`/root/work/STATUS-1.6.4.md:64`) records the owner growing the disk to
  530 GB, so the lxc125 page is stale in at least the size column.
- **NEW-FINDING: TEST channel range disagrees.** `AGENTS.md:66` says 7985-7989 (5 channels); the ports table at
  `docs/ops/lxc125-architecture.md:193` says 7985-7992 (8). Live runs `CHANNELS=5` (`docs/release/1.6.4.conf:277`).
- **NEW-FINDING: live monitoring status is contradictory.** `docs/ops/lxc125-architecture.md:70,72` says live monitoring
  is not installed (and the whole "Monitoring and Discord" section appears twice, `:68` and `:83`), while
  `docs/release/1.6.3-deploy-steps.md:112-115` instructs the operator to install it on 164.
- **NEW-FINDING: the monitor's live target still reports `VERSION=1.4.0`** (`deploy/monitor/targets/live.conf:10`) while
  live is 1.6.4; the status message and any version comparison read that value.
- **NEW-FINDING: `deploy/slumsms.service.example:10` points `WorkingDirectory` at `/opt/slumsms/Server`**, a path no
  doc lists; live's folder is `/opt/mapleroot/MapleRoot Full Repack/Server` (`AGENTS.md:65`).
- **NEW-FINDING: the TEST site/console ports in session notes have no repo source.** `docs/sessions/website.md:2`
  says the TEST site installs with `tools/site/test164/install.sh` and `:21` names a key console on 164:8791, but
  `git ls-files | grep -c tools/site/` is 0 in this repo, so neither the installer nor the port can be verified here.
- **b268** (monitor log-silence check; `/root/work/STATUS-1.6.4.md:61`) — the login-port check alone missed a hung live
  server, which is exactly the failure mode a port-only host map hides.

## State

**Working, with stale documentation.** The mechanism is real and tested offline: the TEST allowlist is one module with
tests (`tools/ops/client-mcp-win/tests/test_qatools.py:285-295`), the live path guard exists (`tools/ops/deploy-test:42`),
the loopback binds are in code (`src/main/java/soloMapling/site/auth/SiteAuthApi.java:155`, `src/main/java/soloMapling/inspect/BotInspectApi.java:110-111`). What is not verifiable from this
repo is the deployed state of any host — which units are enabled, which ports actually answer, and whether the TEST
dashboard is on mapletest or 164.

## Open questions

- Which box serves the TEST dashboard at :8091 — mapletest (`AGENTS.md:53`) or 164
  (`docs/ops/lxc125-architecture.md:109`)? The repo records both.
- Is live monitoring installed? `docs/ops/lxc125-architecture.md:70` says no; `docs/release/1.6.3-deploy-steps.md:112`
  says install it; `deploy/dashboard/extras-live.conf:1` still says "not installed yet".
- Which compose file owns `buns-clean-db-1` now (`/opt/slumsms-db/...` vs `/opt/buns-clean/...`)? Deleting the wrong
  folder would take the live DB's definition with it.
- Does live's `slumsms.service` still carry `-Dsolomapling.dashboard.dir=/srv/slums-dashboard/data`? Only
  `docs/release/1.6.0.conf:78` adds it; `docs/release/1.6.4.conf` has zero `solomapling.dashboard` occurrences, and
  `tools/ops/install-live-dashboard:20-21` says the page stays empty until a jar runs with it on.
- `maplebackup` (ticket 109, `docs/tickets/109-test-backup-infra.md:38-45`) is documented as the off-box target but is
  absent from `AGENTS.md:48-55`; is it live, and does `OFFBOX_DEST` point at it? `deploy/backup/slumsms-backup.conf.example:17`
  ships it empty.
