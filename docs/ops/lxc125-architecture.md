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
together with the jar that knows them. Bot data files (dialogue packs, name pools, item configs, movement packets,
`BotTransit.yaml`...) are packed into the jar since ticket 22. `soloMapling.DataFiles` looks for each one in
`-Dsolomapling.dataDir=<dir>` (`<dir>/soloMapling/...`), then `src/main/java/soloMapling/...` under the server folder,
then the jar, and logs one `SoloMapling data: N/N resolved (...)` line at startup. Staging the non-Java files of
`src/main` is now optional. A staged tree still wins over the jar, so a stale `src/main/java/soloMapling` in the server
folder (live has the 1.2.1 one) shadows the jar's copy: stage the current tree or move the old one aside.
`java -cp target/Cosmic.jar soloMapling.DataFiles` in the server folder prints the same line and exits 1 on a miss.
Jars before ticket 22 (1.3.0 and older) still need the staged tree (see `docs/release/1.3.0.md` step 9).

## GM accounts (ticket 43)

**Status:** the two scripts are installed on the host and work against live today. The game-side parts (audit
table, name rules, trade/drop block, migration 009) are on branch `gm-safety` and reach live with the next release.
Live has no GM until the owner makes a new account and runs `slumsms-gm <account> 6`.

Make or remove a GM with `/usr/local/bin/slumsms-gm <account> <level>` (source `tools/gm/slumsms-gm`): it sets
`characters.gm` on every character of the account (0 = player, 6 = full GM; the account must be logged out and needs a
character), refuses bot ids, the template account `SMBotBase` and promoting banned accounts, and logs each change to the
`gm_level_changes` table and `/var/log/slumsms-gm.log`. `slumsms-gm --list` shows who is a GM. Defaults to live
(`buns-clean-db-1`, schema `mapleroot`); `--container mapleroot-integration-db --schema <test schema>` for the test
DB. GM commands land in `gm_command_audit` (migration 009) and the server log; read them with
`slumsms-gm-audit [--json] [--changes]` (`tools/gm/slumsms-gm-audit`). Automatic registration refuses staff-looking
account names (admin, gm, 4dm1n, ...) and character creation refuses GM-looking names. GM characters cannot trade with
or drop to players unless the server runs with `-Dslumsms.gm.allowGiving=true`.

## Scheduled jobs (live)

| Timer | When | What |
|---|---|---|
| `slumsms-backup.timer` | daily ~11:00 UTC | `/usr/local/bin/slumsms-nightly-backup`: dumps `mapleroot` to `/opt/mapleroot/backups/nightly`, keeps 14 days, SHA256SUMS |
| `slumsms-nav-bake.timer` | 02:30 America/Los_Angeles | NavDeepBake from the live jar into `cache/bot-nav/deep/v68` (keep 3), idle priority |
| `slumsms-nav-stuck-report.timer` | 00:20 UTC | NavStuckReport → `reports/nav/` in the server folder |

Unit files for the nav jobs are in the repo under `deploy/nav/`. Ticket 45's backup kit (nightly v2 with bot learning data, rotation timer,
off-box copy, monthly restore drill, test DB datadir move) is in `deploy/backup/` and not installed yet; restore drill
receipts are in `/opt/mapleroot-dev/restore-drill/receipts/`.

## Monitoring and Discord (tickets 42, 46)

**Status:** branch `monitoring`; installed for the test server only; live waits on the owner and a webhook URL.

`slumsms-monitor` (repo `deploy/monitor/`, copy on the host in `/opt/slumsms-monitor`, installed to
`/usr/local/bin`). One instance per watched server ("target"): `slumsms-monitor@<target>.timer` checks every minute
(unit, login port, journal error rate, heap/RSS vs `-Xmx`, DB container, disk, bots vs director target, LLM/Decide
health, backup and nav-bake timers) and alerts a Discord webhook with recoveries; `slumsms-status@<target>.timer`
edits a status message every 5 minutes; `slumsms-news-<target>.path` posts new launcher news when the manifest
changes. Config `/etc/slumsms-monitor/<target>.conf`, webhook URLs `/etc/slumsms-monitor/<target>.env` (0600, not in
git; without them messages go to `/var/log/slumsms-monitor/<target>.log`), state `/var/lib/slumsms-monitor/<target>/`.
Installed for `persistence-test` (no webhook yet). **Live is not installed**: the owner runs
`/opt/slumsms-monitor/install.sh live --status --news`. During a live deploy, `touch /etc/slumsms-monitor/live.maintenance`.
See `deploy/monitor/README.md`.

## Monitoring and Discord (tickets 42, 46)

`slumsms-monitor` (repo `deploy/monitor/`, copy on the host in `/opt/slumsms-monitor`, installed to
`/usr/local/bin`). One instance per watched server ("target"): `slumsms-monitor@<target>.timer` checks every minute
(unit, login port, journal error rate, heap/RSS vs `-Xmx`, DB container, disk, bots vs director target, LLM/Decide
health, backup and nav-bake timers) and alerts a Discord webhook with recoveries; `slumsms-status@<target>.timer`
edits a status message every 5 minutes; `slumsms-news-<target>.path` posts new launcher news when the manifest
changes. Config `/etc/slumsms-monitor/<target>.conf`, webhook URLs `/etc/slumsms-monitor/<target>.env` (0600, not in
git; without them messages go to `/var/log/slumsms-monitor/<target>.log`), state `/var/lib/slumsms-monitor/<target>/`.
Installed for `persistence-test` (no webhook yet). **Live is not installed**: the owner runs
`/opt/slumsms-monitor/install.sh live --status --news`. During a live deploy, `touch /etc/slumsms-monitor/live.maintenance`.
See `deploy/monitor/README.md`.

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
`/root/slums-dashboard-test-password.txt`. `slums-dashboard-extras@dashboard-test.timer` (config
`/etc/slums-dashboard/dashboard-test.conf`) copies the persistence-test monitor status and the GM audit of
`slums_release_test` into `data/ops/` every minute.

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

Scripted in `tools/release/` (ticket 44, branch `release-pipeline` until merged; rehearsed with a dummy 1.3.2: ~29 s
downtime; the publish wrappers need `--sync-publisher` once because `/opt/slums-tools/launcher` is older than the
launcher repo; checklist template `tools/release/templates/CHECKLIST.md`):

1. Work lands on branches. `tools/release/build`: full suite + jar + sha (`dist/release-build/BUILD.txt`).
2. Deploy to the owner's test server (persistence-test) and get an in-game check.
3. `docs/release/<version>.conf` (migrations, config keys, unit edits, pre-checks, smoke lines), then
   `tools/release/stage <version>`: jar, scripts, non-Java `src/main` data (optional for jars with ticket 22, see
   above), migrations and the generated
   `deploy.sh`/`rollback.sh` to `/opt/mapleroot-deployments/v<version>/`.
4. `tools/release/rehearse <version>`: fresh copy of live (DB into scratch schema `rh_*` on 3307, Server/ copy, unit
   `slumsms-rh-*` on 8499/8585+ under CPUQuota=200% Nice=19 MemoryMax=6G), the staged deploy.sh and rollback.sh, player
   rows compared, everything cleaned up; receipt in `/opt/mapleroot-dev/rehearsal-<version>/receipt/`.
5. Owner: `bash /opt/mapleroot-deployments/v<version>/deploy.sh --dry-run`, then without `--dry-run`: pre-checks
   (abort before the stop), stop, backup (DB with locks, runtime, unit), ledger-checked migrations
   (`mapleroot_integration_migrations.migration_id`), stage, start, smoke test, `RECEIPT.md`. `rollback.sh` next to it.
   Agents hand the owner the command; live changes need the owner's go.
6. `tools/release/publish-client` / `publish-launcher` (hash-diff against the reference client and the current
   release built in; manifest last), tag all three repos.

## Live deploy history

| Version | When (UTC) | Downtime | Record |
|---|---|---|---|
| 1.3.0 | 2026-10-20 06:49 | ~1 min | `/opt/mapleroot-deployments/v1.3.0/DEPLOYMENT.md` |
| 1.3.1 (Bera: world 0 → 1, migration 008) | 2026-10-20 13:48 | 26.9 s | `/opt/mapleroot-deployments/v1.3.1-bera/` (deploy.log, backup, rollback.sh) |

## Waiting on the owner (2026-10-20)

| What | Command / decision |
|---|---|
| Make a GM | new account + character, logged out: `slumsms-gm <account> 6 --note "owner GM account"` |
| Monitoring on live | `/opt/slumsms-monitor/install.sh live --status --news`, then the webhook URL(s) in `/etc/slumsms-monitor/live.env` |
| Backups v2 | `bash /opt/mapleroot-dev/t45-backup-kit/install.sh` (rotation stays dry-run); pick an off-box target; copy `/root/.config/slumsms-backup/offbox.key` to a password manager; keep or drop `baseline-20261018-r1/PreviousServer` (541 MB) |
| Test DB datadir move | a window with test servers stopped: `move-test-db-datadir.sh --go` |
| Publisher sync | allow `publish-* --sync-publisher` to update `/opt/slums-tools/launcher` |

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
