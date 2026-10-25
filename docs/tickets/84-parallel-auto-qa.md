# 84: Run auto-QA checks in parallel (target 1.6.0)

Status: planned for 1.6.0 (owner, 2026-10-25: "parallel auto qa would be good").

## Why
A full auto-QA run (84 checks) takes 1-2 h because checks run one after another on one server and most of the time
is real waits: guild boss gathering/inside time, buddy/party accept windows, coupon/cooldown expiries, bots' human
"late" replies (up to ~55 s). `--fast` (0.4x staged waits) and `--failed` (rerun failures only) help, but a release
still needs one normal full run.

## Goal
A normal full run in ~20-30 min by running independent checks concurrently.

## Plan
1. Classify every check in AutoQaCatalog: which world state it touches (map, channel, party/guild, global events,
   roster/director, cash shop, megaphones, server clock) and whether it can share the server with others.
2. A pool of pretend players (AutoQaOne..N, SyntheticClient) instead of one, each check staged on its own player and,
   where it needs one, its own channel/map instance.
3. A scheduler: run N lanes (start with 4, one per channel 2-5), serialize checks that touch global state (world
   megaphones, director, server-wide events), keep per-check logs separate (logs/qa/<id>-<ts>.log already are).
4. Results/test-status unchanged in shape; record lane and timing per check; flag checks that fail only in parallel
   (shared-state leaks) as their own category.
5. Prove it: one normal sequential run and one parallel run on the same build must agree (same PASS/FAIL set).
