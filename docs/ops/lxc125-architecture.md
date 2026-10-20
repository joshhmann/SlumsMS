# SlumsMS host architecture (LXC 125, 192.168.0.164)

Last updated: 2026-10-20, after the 1.3.1 (Bera) live deploy and the storage cleanup. A copy lives on the host at
`/root/ARCHITECTURE.md`; the source of truth is this file in the SlumsMS repo (`docs/ops/lxc125-architecture.md`).

## The machines

| Machine | Address | Role |
|---|---|---|
| **LXC 125 "maplestory"** | 192.168.0.164 | Everything server-side: live game server, its DB, test servers, patch server, dashboard. Proxmox container on ZFS (`bigboi/subvol-125-disk-0`, 430 GB; grow with `pct resize 125 rootfs +NG` on the PVE host). 6 cores, 24 GB RAM. |
| Proxmox host | (PVE) | Runs LXC 125. ZFS snapshots of the subvol live here. |
| Owner's desktop "Silvia" | 192.168.0.186 | Windows. Plays/tests with the real client. Dev area `Z:\MSv83\SlumsMS-dev` (test client, launcher test, release zips); the rest of the desktop is read-only for agents. |
| GPU box | 192.168.0.17 | Bot chat models: Qwen 9B (`qwen9b`) at `:8095/v1`, Decide (GLiNER2.5-Decide) at `:8900/v1/systemone`. `:8080` belongs to another project. Managed there with `/root/launch-maple-chat.sh` and the `decide-shadow` service. |
| Agent workstation | (where Claude Code runs) | Git clones and worktrees under `/root/work` (SlumsMS, launcher, …), builds jars (`./mvnw -o`) and launcher zips, then copies them to 164 over SSH. |
| Caddy (owner's reverse proxy) | public | `slumsms.asslorde.com` → live game (login 8485), `slumspatch.asslorde.com` → nginx :8090 on 164. |

## Live game

| What | Where |
|---|---|
| Service | `slumsms.service` (`/etc/systemd/system/slumsms.service`), Java 21, `-Xmx4G`, `TimeoutStopSec=5min` |
| Server folder | `/opt/mapleroot/MapleRoot Full Repack/Server` (jar `target/Cosmic.jar`, `config.yaml`, `scripts/`, `wz/`, bot data under `src/main/java/soloMapling/…`, nav cache `cache/bot-nav/v68`) |
| Ports | login 8485, channels 7585-7592 (8 channels) |
| Version | SlumsMS 1.3.1, world 1 "Bera" only (config `worlds: - id: 1`; migration 008 moved characters from world 0). Server branch `world-bera`. Bots on: `-Dsolomapling.enabled=true -Dsolomapling.template-id=6 -Dsolomapling.autopopulate=true` |
| Database | MySQL 8.4 container `buns-clean-db-1`, host port **3309**, schema `mapleroot`, data in Docker volume `buns-clean_db-data`. Compose file: **`/opt/slumsms-db/docker-compose.yml`** (project name kept as `buns-clean` so compose owns the existing container; root password in `/opt/slumsms-db/.env`). Binlogs kept 7 days. |
| Bot chat | `config.yaml` `BOT_LLM_BASE_URL` → 192.168.0.17:8095 (`qwen9b`, thinking off via `BOT_LLM_EXTRA_BODY`), `BOT_DECIDE_URL` → 192.168.0.17:8900. If they're down, bots fall back to scripted lines. |
| Bot template | account `SMBotBase` (4, banned), character `SMTemplate` (6) |
| Deploy records | `/opt/mapleroot-deployments/<version>/` (DEPLOYMENT.md, deploy script, log, pre-deploy backups) |

**config.yaml is strict:** an unknown key stops the boot. New tunables ship as `-D` JVM flags, or as YAML keys only
together with the jar that knows them. The jar reads bot data files from `src/main/java/soloMapling/...` relative to
the server folder, so every deploy stages the non-Java files of `src/main` (see `docs/release/1.3.0.md` step 9).

## Scheduled jobs (live)

| Timer | When | What |
|---|---|---|
| `slumsms-backup.timer` | daily ~11:00 UTC | `/usr/local/bin/slumsms-nightly-backup`: dumps `mapleroot` to `/opt/mapleroot/backups/nightly`, keeps 14 days, SHA256SUMS |
| `slumsms-nav-bake.timer` | 02:30 America/Los_Angeles | NavDeepBake from the live jar into `cache/bot-nav/deep/v68` (keep 3), idle priority |
| `slumsms-nav-stuck-report.timer` | 00:20 UTC | NavStuckReport → `reports/nav/` in the server folder |

Unit files for the nav jobs are in the repo under `deploy/nav/`.

## Patch server (friends' downloads)

| What | Where |
|---|---|
| nginx site | `/etc/nginx/sites-enabled/slums-patch`, port **8090**, basic auth `/etc/nginx/slums-patch.htpasswd` (user `slums`, key in `/root/slums-patch-access-key.txt`) |
| Files | `/srv/slums-patch`: `manifest.json` (release 1.3.0, no-cache) → `layers/base/v83-2026.10.19` (~22 GB game data) + `layers/client/1.2.1` (client) |
| Launcher channel | `launcher.json` next to `manifest.json` (none published yet at the root; launcher 1.3.0 was handed out as a zip). Private staging channel: `/srv/slums-patch/launcher-test/` (its `layers` symlinks to the real layers) |
| Staging | `/srv/slums-staging` (client staging source, news JSON, manifest backups) |
| Publisher | `tools/publish.mjs` in the launcher repo (`--layer`, `--release --use`, `--launcher`), run on the workstation; upload layer folders first, manifest last. Rule: hash-diff any new client layer against the reference client before publishing. |
| Reference client | `/opt/mapleroot-control/MapleRoot Full Repack/Game Files` and `/root/maplestory_dump/MapleRoot Full Repack.zip`. Keep at least one. |

## Admin dashboard (test)

nginx site `slums-dashboard-test`, port **8091**, basic auth "SlumsMS admin", static app in
`/srv/slums-dashboard-test/web`, data snapshots in `/srv/slums-dashboard-test/data` written by the
`slums-dashboard-test` server (ports 8479/8285+, schema `slums_release_test`). Password in
`/root/slums-dashboard-test-password.txt`.

## Test servers and the test DB

| What | Where |
|---|---|
| Test DB | MySQL 8.4 container `mapleroot-integration-db`, **127.0.0.1:3307**, datadir bind-mounted from **`/opt/mapleroot-dev/solomapling-test/mysql`** (do not delete that folder: it holds every test schema), secrets in `solomapling-test/secrets`. 3 GB memory limit. Binlogs kept 1 day. |
| Owner's test server | `slums-persistence-test` (transient unit, started by `/opt/mapleroot-dev/persistence-test/start-test.sh`), login **8489**, channels 7985+, schema `mapleroot_persist`, attack capture on. Desktop test client `Z:\MSv83\SlumsMS-dev\test-client` points here. |
| Dashboard test server | `slums-dashboard-test`, see above |
| Scratch servers | Agents run short-lived copies (e.g. `slums-levelpath-scratch`, rehearsals) on spare ports with `CPUQuota=200% Nice=19 MemoryMax=6G` and scratch schemas, then delete them. Rehearsal receipts: `/opt/mapleroot-dev/rehearsal-1.3.0/receipt/`. |
| Other test folders | `real-stats-test` (schema `mapleroot_realstats`), `release-test`, `nav-v2-test` (its navops timers are disabled; config points at `mapleroot_persist`, so don't start it alongside persistence-test) |

## Other things on the box

- Host MariaDB 10.11 on 127.0.0.1:3306 (system service, not used by SlumsMS).
- `/opt/cosmicms` (earlier Cosmic server, service disabled), `/opt/maplestory-dev` (Kinoko research), `/opt/astravia-reference`, Ghidra (`/opt/ghidra_12.1_PUBLIC`, `/opt/ghidra-projects`), `/opt/maplesolo-merge`, `/opt/slums-tools` and `/opt/mapleroot-launcher` (launcher build toolchain), `/opt/valhalla`.
- `/root/.omp` and the omp runner (another agent tool), `/root/workspace/maplestory-wasm` (web client, also on GitHub).
- `/root/backups/2026-10-20-pre-cleanup/`: live and test DB dumps, BunsMS source/bundles, wasm builds rescued from /tmp, old BunsMS unit files.
- BunsMS was removed on 2026-10-20 (repo: github.com/joshhmann/bunsms).

## Repositories

| Repo | What | Tag |
|---|---|---|
| github.com/joshhmann/SlumsMS | Server (Cosmic/MapleRoot fork + SoloMapling bots), docs, tickets (`docs/tickets/`, start with `HANDOFF.md`), release docs | v1.3.0 |
| github.com/joshhmann/SlumsMS-Client | `slums.dll` + `SlumsMS.exe` (built on the desktop) | v1.3.0 (same code as 1.2.0) |
| github.com/joshhmann/slumsms-launcher | Electron launcher (self-update), `tools/publish.mjs` | v1.3.0 |
| github.com/joshhmann/maplestory-wasm | Browser client experiment | — |

One version number for all three SlumsMS repos from 1.3.0 on.

## How a release goes

1. Work lands on branches; full suite `./mvnw -o test` on the workstation.
2. Deploy to the owner's test server (persistence-test) and get an in-game check.
3. Rehearse risky upgrades on a copy of live (scratch schema + copied server folder, spare ports).
4. Live deploy with a script under `/opt/mapleroot-deployments/<version>/`: stop, back up (DB with locks, runtime, unit), migrations (ledger `mapleroot_integration_migrations`), stage files, start, smoke test, receipt. Agents hand the owner the command to run; live changes need the owner's go.
5. Publish client/launcher on the patch server (manifest last), tag all repos.

## Ports at a glance

| Port | What |
|---|---|
| 22 | SSH |
| 3306 (localhost) | host MariaDB (unused by SlumsMS) |
| 3307 (localhost) | test DB container |
| 3309 | live DB container |
| 8090 / 8091 | nginx: patch server / admin dashboard |
| 8485, 7585-7592 | live login / channels |
| 8489, 7985-7992 | owner's test server |
| 8479, 8285-8292 | dashboard test server |
| 8499+, 8519, 8585+, 8605+ | scratch servers (temporary) |
