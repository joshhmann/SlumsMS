# 23: Scheduled live backups

**Area:** Ops  
**Depends on:** -  
**Status:** done 2026-10-19 (ops on live; superseded by ticket 45's scripts in v1.4.0)

## What

Nightly DB dump of the live `mapleroot` schema (port 3309), kept 14 days and copied off-box; restore drill documented.

## Done when

A cron or systemd timer exists and a restore has been tested.

## Done

- `slumsms-backup.timer` on 164 runs nightly at 11:00 UTC (4am Pacific), with `Persistent=true`.
- It calls `/usr/local/bin/slumsms-nightly-backup`, which dumps `mapleroot` with `--lock-all-tables` to
  `/opt/mapleroot/backups/nightly/`, keeps 14 days and appends hashes to `SHA256SUMS`.
- Restore drill: the 2026-10-19 backup was loaded into a scratch schema on the test DB (94 tables, 3 accounts,
  5 characters) and then dropped.
- Off-box copy (for example to the desktop's Data_Vault) is still to do.
