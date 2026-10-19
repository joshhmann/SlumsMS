# 23: Scheduled live backups

**Area:** Ops  
**Depends on:** -  
**Status:** open

## What

Nightly DB dump of the live `mapleroot` schema (port 3309), kept 14 days and copied off-box; restore drill documented.

## Done when

A cron or systemd timer exists and a restore has been tested.
