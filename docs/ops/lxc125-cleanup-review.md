# LXC 125 storage review and cleanup proposal

Date: 2026-10-20. Host: LXC 125 (192.168.0.164). Status: **review only; nothing has been removed.**

## How this was gathered

Only read commands were used: `df`, `du -x` (run under `nice -n19 ionice -c3`), `find -printf`, `ls`, `stat`, `docker system df/ps/images/volume ls/inspect`, `journalctl --disk-usage`, `systemctl show/list-units/list-timers`, and read-only `information_schema` / `SHOW BINARY LOGS` queries in both MySQL containers.

One mistake: a shell redirect wrote a 3 MB scratch file listing (`/root/.cache/_x`) by accident. It was deleted straight away. Nothing else on the host was written, moved, stopped or deleted.

Sizes are from `du` on the ZFS subvolume, so they show allocated space after compression. Freed space should come close to these numbers. The exception is Proxmox-side ZFS snapshots of `subvol-125-disk-0`, which can hold space after a delete. Those can't be seen from inside the container, so check `zfs list -t snapshot` on the PVE host.

## Disk at a glance

`/` = 430G, 284G used, 147G free.

| Top-level | Size | Notes |
|---|---|---|
| /opt | 106G | buns 50G, mapleroot-control 22G, buns-clean 8.4G, mapleroot-dev 8.1G, cosmicms 5.5G, maplestory-dev 5.6G, astravia 2.5G, slums-tools 1.5G, mapleroot 1.4G |
| /root | 100G | bunsms-migration-from-187 38G, maplestory_dump 36G, loose files ~24.4G (`MapleRoot Full Repack.zip` 22.4G, `MapleGlobal-v83-setup.exe` 1.8G, `cosmicclean_backup.tar.gz` 168M), .omp 3.1G, caches ~1G |
| /srv | 64G | slums-patch 36G, slums-staging 29G, slums-dashboard-test 66M |
| /var | 13G | /var/lib/containerd 12G (docker images + build cache), docker volumes 253M, journal 146M, /var/log 152M total |
| /usr | 1.7G | system |
| /tmp | 633M | old scratch builds (Oct 14-19) |
| /home, /mnt, /media, /boot | ~0 | empty |

## What is running and what it depends on

| Unit / container | State | Depends on |
|---|---|---|
| `slumsms.service` (live, :8485) | running, enabled | `/opt/mapleroot/MapleRoot Full Repack/Server` (839M: wz 471M, nav cache 170M, jar 57M), DB `buns-clean-db-1` schema `mapleroot` |
| `buns-clean-db-1` (mysql:8.4, :3309) | up 2d | compose `/opt/buns-clean/docker-compose.clean.yml`, volume `buns-clean_db-data` (81M) |
| `slumsms-backup.timer` | daily 11:00 UTC | `/usr/local/bin/slumsms-nightly-backup` writes to `/opt/mapleroot/backups/nightly` (14-day rotation) |
| `slumsms-nav-bake.timer`, `slumsms-nav-stuck-report.timer` | waiting (not run yet) | live Server dir, `--keep=3` |
| nginx :8090 | running | `/srv/slums-patch` (manifest 1.3.0 = base `v83-2026.10.19` + client `1.2.1`; launcher-test channel symlinks `layers`) |
| `slums-persistence-test` (transient, :8489) | running | `/opt/mapleroot-dev/persistence-test/Server`, schema `mapleroot_persist` on :3307 |
| `slums-dashboard-test` (transient, :8479) | running | `/opt/mapleroot-dev/release-test/dashboard-test`, `/srv/slums-dashboard-test/data`, schema **`slums_release_test`** |
| `mapleroot-integration-db` (mysql:8.4, :3307) | up 7h | **bind mount `/opt/mapleroot-dev/solomapling-test/mysql`** (datadir) + `solomapling-test/secrets` |
| `slums-navops-test-bake.timer`, `slums-navops-test-stuck-report.timer` | active | `/opt/mapleroot-dev/nav-v2-test/navops` |
| `buns-web.service` | enabled but dead | `/opt/buns/web-client` |
| `cosmicms.service`, `buns-rc-assets.service`, `buns-web-dev.service` | disabled/dead | /opt/cosmicms, /opt/buns/rc-assets |
| omp python runner (`/tmp/omp-python-runner-0`) | running ~2 days, 850M RSS | /root/.omp (another agent tool) |

## Item-by-item verdicts

### KEEP

| Item | Size | Last modified | Why |
|---|---|---|---|
| `/opt/mapleroot/MapleRoot Full Repack/Server` (+ `Database`) | 840M | 2026-10-20 | live server |
| `/opt/mapleroot/backups` | 507M | nightly 10-19, pre-v1.2.1 10-19 | DB backups: nightly (rotated) plus pre-deploy snapshots. Small, so keep them all for now |
| `/opt/mapleroot-deployments/v1.3.0`, `v1.2.x`, `baseline-20261018-r1` | 661M | 10-18 .. 10-20 | rollback material for recent deploys (baseline holds `PreviousServer` 550M; this can become a candidate after 1.3.0 has proven itself) |
| `/srv/slums-patch/layers/base/v83-2026.10.19` | 22G | 10-19 | published base layer (friends download it) |
| `/srv/slums-patch/layers/client/1.2.1` | 7.0G | 10-19 | published client layer |
| `/srv/slums-patch/launcher-test`, `manifest.json` | 219M | 10-20 | live and staging channel manifests, launcher |
| `/srv/slums-staging/client` + news/manifest backups | 7.0G | 10-19/10-20 | client staging source for the next client release |
| `/srv/slums-dashboard-test` | 66M | live | dashboard-test output |
| `/opt/mapleroot-dev/persistence-test` (except items below) | ~0.9G | 10-20 | owner's active test server |
| `/opt/mapleroot-dev/release-test` | 718M | 10-20 | `dashboard-test` runs from here; `Server` shares schema `slums_release_test` |
| `/opt/mapleroot-dev/solomapling-test/mysql` + `secrets` | 453M | 10-20 | **the datadir of the test DB container.** Don't touch it as a "test copy" |
| `/opt/mapleroot-dev/nav-v2-test/navops` | 172M | 10-20 | target of the navops test timers (until those are retired) |
| `/opt/buns-clean/docker-compose.clean.yml` + volume `buns-clean_db-data` | 81M | live | the live DB container belongs to this compose project |
| `/opt/slums-tools`, `/opt/mapleroot-launcher` | 1.5G / 600M | 10-19 | launcher build toolchain and source (`dist/` 365M could be rebuilt) |
| journal | 146M | — | small; no action needed |

### CANDIDATE (SlumsMS-owned, looks safe)

| Item | Size | Last modified / used | Why it looks safe |
|---|---|---|---|
| `/srv/slums-staging/base` | **22G** | 10-19 09:19 | Exact duplicate of the published `layers/base/v83-2026.10.19/files` (path and size listing is identical). Before removing it, confirm the layer build script doesn't need it in place for the next base bump (it could be re-copied from the layer). |
| `/srv/slums-patch/layers/client/1.2.0` | **7.0G** | last fetched 10-19 10:16 | Superseded. Neither manifest (live nor launcher-test) nor the 1.2.2 manifest backup references it. 1.2.1 is the rollback point. |
| `/root/MapleRoot Full Repack.zip` | **22.4G** | 10-18 | Byte-for-byte same size as `/root/maplestory_dump/MapleRoot Full Repack.zip`. One of the two is a duplicate. |
| Superseded test copies in `/opt/mapleroot-dev`: `core-candidate-20261018-r1` 422M, `core-review-20261018` 704M, `weather-review-20261018` 601M, `repo-baseline-20261018` 854M, `baselines` 108M, `rehearsal-1.3.0` 59K, tarballs/zip (`*-payload.tar.gz`, `Core-20261018-r1.zip`, `cashshop-test.tar.gz`) 591M | ~3.3G | 10-19 | These were review or rehearsal stages for releases that are now live (1.2.x / 1.3.0). No running unit uses them. |
| `/opt/mapleroot-dev/solomapling-test/installation` 694M, `checkpoints` 105M, `*.tar.gz` bundles | ~0.85G | 10-19 | Old SoloMapling port rehearsal. **Keep `mysql/` and `secrets/`** (test DB datadir). |
| Jar backups in persistence-test: `Server/target/Cosmic.jar.{pre-soakfix,pre-capfix,pre-attackfix,bak-20261019-combo,pre-attackcapture,pre-1.3.0,pre-firstbootfix}` (~395M), `persistence-test/Cosmic.jar` (dup of live), `backup-20261019-1810` 54M, `backup-srcmain-*.tar` | ~0.5G | 10-19/10-20 | Old iteration jars. Git history can rebuild them. Keep `pre-firstbootfix` (the most recent) if wanted. |
| `real-stats-test`: `Cosmic.jar.prev-*` 113M, `travel-run` 185M, `shops-run` 202M | ~0.5G | 10-19/10-20 | One-off experiment runs whose results are already merged. Owner should confirm the real-stats test itself is done (its schema `mapleroot_realstats` was last written 10-20 00:04). |
| Stale nav cache versions: `persistence-test/Server/cache/bot-nav/v61` 98M, `nav-v2-test/v61` 52M (+`cache`, `cache-v68`, `out-*/err-*`) | ~0.15G | 10-19 | Nav format v68 is current everywhere. |
| Test schemas on :3307: `mapleroot_core_review_20261018`, `mapleroot_weather_review_20261018`, `slumsms_baseline_20261018`, `mapleroot_route_rehearsal`, and `mapleroot_persist_cold` (18M, last 10-19 18:23, apparently a cold copy of persist) | ~32M | 10-19 | Their test dirs are candidates (see above). Note: **`slums_dashboard_test` doesn't exist**. dashboard-test uses `slums_release_test`, so there's nothing to drop there. |
| **Binlogs in `mapleroot-integration-db`** (`binlog.000001-3`) | **1.26G** | growing | Rotated by MySQL itself (`PURGE BINARY LOGS`), not by `rm`. See the risks section. |
| `/tmp` scratch (dashbuild*, mr-*, slice*, augurms-wz, node-v24 tarball, electron downloads, old .sql/.txt) | ~0.6G | Oct 14-19 | Build scratch. Leave `/tmp/omp-*` alone while the omp runner is up. |
| Docker: dangling anonymous volume `50de576c…` | 16M | 10-18 | No container uses it |

### OWNER DECIDES (not clearly SlumsMS, or a judgement call)

| Item | Size | Last modified | What it is |
|---|---|---|---|
| `/root/bunsms-migration-from-187/workspace.tar.gz.partial` (+ `unpacked` 2.9G) | **38G** | 10-18 01:40 | Incomplete (`.partial`) BunsMS workspace transfer from .187. It looks abandoned. Biggest single win if BunsMS is retired. |
| `/opt/buns` (rc-assets 22G, client-assets 13G, runtime-releases 12G, nx 3.9G, backups 66M) | **50G** | 10-18 | BunsMS project: the rc6 runtime, QA WZ variants and client assets. Containers `buns-server-1`, `buns-db-1`, `buns-dashboard-1`, `buns-rc6-private-restore-8b2110f` have been exited for 2 days. `buns-web.service` is still *enabled* but dead. |
| `/opt/buns-clean` minus the compose file and DB volume (baseline 3.4G, client 3.4G, refs 1.3G, server 361M) | ~8.3G | 10-18 | BunsMS "clean" project. Its server, web and dashboard containers have been exited for 24h. **Its DB container is the live SlumsMS DB**, so keep the compose file and volume. Moving the DB to its own compose project would decouple them. |
| `/opt/buns-dev`, `/opt/buns-iso-*`, `/opt/valhalla` | ~35M | 10-15..18 | small BunsMS / other leftovers |
| Docker volume `buns_db-data` | 157M | — | old BunsMS DB |
| `cosmic` schema in `buns-clean-db-1` | 11M | 10-18 08:05 | Leftover Cosmic/BunsMS schema in the live container; SlumsMS uses `mapleroot` |
| `/root/maplestory_dump` (Repack zip 22.4G, `Community Repack .IMG.zip` 6.4G, `TengaMS2025.7z` 5.0G, `server-wz.tar.gz` 3.8G, Etc.wz, WzModTools, docs) | 36G | 10-14..18 | Source archives. Keep exactly one copy of the original Repack zip (see the duplicate above). The others are reference material. |
| `/opt/mapleroot-control/MapleRoot Full Repack/Game Files` | **22G** | 10-18 | Unpacked original client. Its listing differs from the published base, so it's a separate reference copy. It may be the "standalone client" used for the publish hash-diff rule. **Keep at least one standalone client reference** (this folder or the zip). Deleting both breaks release verification. |
| Docker images not used by any container: `maplestory-wasm-wasm-builder` 3.35G, `emscripten/emsdk` 3.2G, `mysql:8.4.0` 806M, `maven` 405M, `node:20` 293M, `kinoko-*` 576M, `amazoncorretto:21` 237M, `python`, `hello-world` | ~6.8G | — | Web-client (wasm), Kinoko and build images. They are not SlumsMS runtime. |
| Docker build cache (114 entries, 0 active) | ~5.1G | — | `docker builder prune` would free it; images can be rebuilt. |
| Exited containers (`buns-*`, `buns-clean-server/web/dashboard`) | <1M | 1-2 days | Removing them would free the `buns-*` images (~1.8G). |
| `/opt/cosmicms` (wz 5.3G) | 5.5G | 10-18 | Earlier Cosmic server; `cosmicms.service` is disabled |
| `/opt/maplestory-dev` (kinoko 1.8G, data 3.8G) | 5.6G | 10-18 | Kinoko research |
| `/opt/astravia-reference`, `/opt/ghidra_12.1_PUBLIC` + `ghidra-projects`, `/opt/maplesolo-merge`, `/opt/_archive-20261018-consolidation` | 2.5G / 0.64G / 0.36G / 62M | 05-26..10-18 | Reference and RE tools, merge workspace, archive |
| `/root/.omp` (run/daemons headless browser profile 2.7G) | 3.1G | 10-20 | Another agent tool. Its runner process is live. |
| `/root/MapleGlobal-v83-setup.exe` 1.8G, `cosmicclean_backup.tar.gz` 168M, `/root/workspace` (bunsms, maplestory-wasm) 69M, `/root/v83-setup-dl` 151M | ~2.2G | 2021/May | Original installer and old workspaces |
| Caches: `~/.cache` 279M (electron 270M), `~/.local` 258M, `~/.nuget` 230M, `~/.m2` 198M, `~/.npm` 65M | ~1G | — | Safe to clear but they refill on the next build. Low value. |

## Risks and growth to watch

1. **Test DB binlogs grow about 1.8 GB/day.** `mapleroot-integration-db` has `log_bin=1` and `binlog_expire_logs_seconds=2592000` (30 days). `binlog.000003` reached 540M in about 7 hours after the 00:29 restart, and `binlog.000002` holds 716M. With the ~1,000-bot autopopulate test running, steady state is roughly **40-55 GB** of binlogs before expiry starts deleting them. Fix (owner's call, not done): `PURGE BINARY LOGS BEFORE NOW()` now, then start the test container with `--skip-log-bin` or `--binlog-expire-logs-seconds=86400`. The live DB (`buns-clean-db-1`) grows about 35 MB/day (67M binlog in 2 days), which is about 1 GB at the 30-day cap. That's fine, but worth the same 3-7 day expiry.
2. **The test DB datadir lives inside a "test copy" folder** (`/opt/mapleroot-dev/solomapling-test/mysql`). Anyone deleting `solomapling-test` as a stale rehearsal would wipe every test schema, including `mapleroot_persist`. Consider moving it to e.g. `/opt/mapleroot-dev/integration-db/` during a planned window.
3. **nav-v2-test points at `mapleroot_persist`**, the same schema as the active persistence-test server (`nav-v2-test/config.yaml`). Its timers only run the offline bake and report, but starting a nav-v2-test server would share the DB with persistence-test.
4. **Duplicate navops timers.** `slums-navops-test-*` duplicate the live `slumsms-nav-*` timers. Both bakes start around 09:30 UTC, each with 2 threads and up to 1.5G heap, alongside the live server and two test servers. If nav v2 is now in live (live already uses cache v68), the test timers are candidates to `systemctl disable --now` (not done).
5. **Nav cache growth.** The live `cache/bot-nav` is 170M (v68), and `--keep=3` bounds the bake output. `learned/`, `stuck/` and `heat/` are still tiny (live 63K, persistence-test 675K). This is fine for now, but recheck after a week of 1,000 bots. Old format dirs (`v61`) are not cleaned automatically when the format version bumps.
6. **Pre-deploy backups have no rotation.** `/opt/mapleroot/backups/pre-*`, `/opt/mapleroot-deployments/*` and the persistence-test `backup-*` dirs only grow, by about 50-180M per deploy. That's small for now; prune by hand to keep the last ~3 deploys.
7. **Jar backup sprawl.** Every hotfix leaves a `Cosmic.jar.pre-*` (~56M) in the test `target/`. There are 7 in persistence-test so far.
8. **Server logs.** persistence-test `logs` is 12M and live is 2.7M. There's no sign of runaway logging. The journal is 146M and under journald's own cap.
9. `buns-web.service` is still enabled but dead. It will try to start on boot.
10. nginx on :8090 gets `GET /.git/config` scans from somewhere. `/srv/slums-patch` has no `.git`, but keep it that way.

## Estimated reclaimable space

| Bucket | Estimate |
|---|---|
| CANDIDATE, SlumsMS-owned (staging base 22G + client 1.2.0 7G + duplicate Repack zip 22.4G + test copies/jars/runs/nav ~5.3G + binlogs 1.3G + /tmp 0.6G) | **~58G** |
| OWNER DECIDES: BunsMS (migration partial 38G + /opt/buns 50G + buns-clean non-DB 8.3G + images/volumes ~2G) | ~98G |
| OWNER DECIDES: other reference material (maplestory_dump extras ~14G, mapleroot-control 22G *only if the zip stays as the reference*, cosmicms 5.5G, maplestory-dev 5.6G, astravia/ghidra/merge 3.5G, docker images + build cache ~10G, .omp 3.1G, installer 1.8G, caches ~1G) | ~66G |
| **Total possible** | **~220G of 284G used** |

Recommended first pass (lowest risk, ~58G): drop `/srv/slums-staging/base`, `/srv/slums-patch/layers/client/1.2.0`, the duplicate `/root/MapleRoot Full Repack.zip`, the superseded `/opt/mapleroot-dev` review/baseline copies and jar backups, purge the test DB binlogs, and turn off binlogs on the test DB. If BunsMS is retired, the `bunsms-migration-from-187` partial (38G) and `/opt/buns` (50G) are the next big wins.
