# 45: Back up bot learning data and rotate old backups

**Area:** Ops
**Depends on:** 23 (live backups)
**Status:** open

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
