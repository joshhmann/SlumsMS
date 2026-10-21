# 45: Back up bot learning data and rotate old backups

**Area:** Ops
**Depends on:** 23 (live backups)
**Status:** built on branch `backups` (`deploy/backup/`), restore drill run and PASSED on 2026-10-20; live install
(new nightly script, rotation timer) waits for the owner (`install.sh`); off-box copy is built but disabled until the
owner picks a target; the test DB datadir move waits for a window

## What

- Add `cache/bot-nav/learned`, `stuck` and `heat` (small, grow with play) to the nightly backup next to the DB dump.
- Rotate pre-deploy material: keep the last 3 deploys in `/opt/mapleroot/backups/pre-*` and
  `/opt/mapleroot-deployments/*`, and prune test-server `backup-*` and `Cosmic.jar.pre-*` files older than 14 days.
- Off-box copy: push the nightly dump (encrypted) to the desktop or another host, so a lost LXC isn't a lost world.
- Move the test DB datadir out of `/opt/mapleroot-dev/solomapling-test/mysql` to `/opt/mapleroot-dev/integration-db/`
  in a planned window, so cleaning "old test copies" can never wipe it.
- A restore drill: restore last night's dump into a scratch schema and boot a copy of live from it, monthly.

## Done when

A restore drill from last night's off-box copy boots with players, bots and learned nav intact, and backup folders
stop growing without bound.

## Progress (2026-10-20)

Everything is in `deploy/backup/` (see its README.md for details and the owner's steps):

- `slumsms-nightly-backup` v2: DB dump as before plus `bot-nav-<ts>.tar.gz` (learned, stuck, heat; ~190 KB on the
  persistence-test data, ~70 KB on live), both in SHA256SUMS, 14 days / newest 3 kept, stale SHA256SUMS lines
  dropped. Tested against persistence-test's folder and `mapleroot_persist` on the test DB, including retention and a
  failing off-box push (exit 3, local backup kept).
- `slumsms-rotate-backups` + `slumsms-backup-rotate.timer`: dry run by default; `--apply` deletes only what an
  earlier run listed at least 20 h before. The owner keeps `PreviousServer` (2026-10-20): `PROTECT_PATHS` in the conf
  (default `PreviousServer`) is never listed or deleted, nor any folder holding one; `test-rotate-protect.sh` proves
  it in dry run and `--apply`. Dry run on 164 (2026-10-20, after the change) would delete the three
  `backups/pre-solomapling-20261018T*` folders and the three git bundles of `mapleroot-deployments/baseline-20261018-r1`
  (6 items, 188 MB) and reports `baseline-20261018-r1/PreviousServer` (541 MB) as protected; no test-server leftovers
  are older than 14 days.
  Staged/dry-run release folders (e.g. `1.3.2-dryrun`) do not count as deploys and are never touched.
- Off-box: gpg AES256 symmetric with `/root/.config/slumsms-backup/offbox.key` (164 only), rsync or scp to
  `OFFBOX_DEST`; disabled until the owner sets a target (README lists what is needed).
- `move-test-db-datadir.sh`: preflight / `--go` / `--rollback` for moving the test DB datadir to
  `/opt/mapleroot-dev/integration-db/mysql` (rename on the same dataset, container recreated, old one kept). Not run.
- `slumsms-restore-drill` (+ monthly timer, optional): run on 2026-10-20 from the 11:03 UTC nightly (pre-Bera dump,
  migration 008 applied by the drill to match live's ledger), learned nav archived read-only from live because the
  v1 nightly has none yet. PASS: login handshake v83, world 1 Bera, 4 character rows and player items/skills/storages
  unchanged across the run, bot store 3341 / roster 2513 = restored rows, director boot fill 227, all 12 learned books
  (140 edges) loaded by the live jar's NavEdgeLearning onto v68 edges, 0 ERROR/Exception lines, stop in 20 s.
  Receipts: `/opt/mapleroot-dev/restore-drill/receipts/20261020T140038Z` and `20261020T141000Z` on 164.

Still open for "done": the owner installs the kit, picks an off-box target, and a drill runs from an off-box copy
(`slumsms-restore-drill --dump <…sql.gz.gpg> --nav <…tar.gz.gpg>`).
