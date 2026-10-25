# Ticket 68: Fishing for players and bots

Target: 1.6.0. Status: Implemented on `160-life-in-town`; isolated TEST verified; real-client and bot travel review pending.

Read AGENTS.md, docs/HANDOFF.md, docs/ROADMAP.md and docs/release/RUNBOOK.md first.
Sprint tracking: docs/release/1.6.0-sprint.md. This ticket expands an already approved roadmap item; it does not
authorize Live, shared TEST replacement, rewards or decisions reserved to the owner.

## Plan

Use chair 3011000 on explicitly approved water maps. Reuse the world scheduler and existing inventory grants, but do not enable legacy meso/EXP rewards or its rare-item pool. One active session per character; stand, movement, map/channel changes, death and logout cancel. Bots use the same session service through their life actor, with actual seating before truthful fishing chat. Default off until the owner reviews exact rewards.

## Expected files

Fishing service/policy and tests; Character chair lifecycle; World fishing tick; BotLifeSystem and TrainingBot adapters; QA scenarios/catalogue.

## Tests and completion

Deterministic catch intervals and weighted outcomes; no duplicate payout; cancellation and stale callbacks; full bag; dead/logged-out/map-changed actors; bot/client special cases; flag-off behavior. Stage player and bot sessions with !qa/auto-QA.

Run the appropriate full suite, stage automated checks on the isolated 1.6.0 TEST copy when applicable, and
record what was actually checked. Keep flags safe/off by default. Real-client results remain pending until
observed. A plan or disabled framework is not a completed player feature. Commit and push; no release merge
without the owner request.

## Owner choices

Exact fishing reward IDs, quantities, chances and permitted water spots. No new meso farm. Owner preference requested; prepare a concrete table before enabling rewards.

## Sprint implementation and approved reward table

Code `a572d4361` adds a shared player/bot session service, one-at-a-time bot-life FISH activity, chair and
world-timer integration, cancellation, inventory grants, and `fh-1`/`fh-2` QA scenarios. Default-off properties:
`slums.fishing.enabled` and `slums.fishing.rewards`; both must be true to start rewarded sessions.
No new scheduler or model calls. Existing legacy rewards are bypassed while the new mode is on.

The owner approved this exact table in chat: one item per 90 seconds seated, 35% Whitebait (4031627),
30% Carp (4031630), 25% Snail Shell (4000000), 5% Red Potion (2000000), 5% Blue Potion (2000003).
No direct mesos, EXP, gear, scrolls or rare rewards. IDs and names were checked against TEST WZ data.
Only the isolated 8491 start script has both flags enabled, with a pre-change backup.

The first version reuses existing fishing maps 120010000, 251000100 and 541010110, with the existing water-height
restriction. A Henesys fishing zone has not been added. Bots need an owned Fishing Chair and a reachable spot;
this version does not buy or give chairs to roster bots. Actual bot travel/seating and client effects remain
unverified. Unit tests cover concurrency, stale actor cleanup, cancellation, full bags, flag-off behavior and
all weighted boundaries. Full offline suite: 4,622 tests, zero failures/errors, 38 skips (local WZ absent).

First isolated run: seven selected checks passed, but `t-fh-2` refused before starting a session. Its log is
`fh-2-20261024T234232Z.log`; no item was granted. The fixture used a random spawn that can choose the upper
st000 portal (y=-83), outside the fishing-height rule. Commit `3f8250967` uses named waterside spawn sp
(y=89). A regression failed before and passed after; full suite passes with 4,623 tests, zero failures/errors, 38 skips. Repeated TEST result follows below.

Repeated check on `3f8250967`: `t-fh-1` and `t-fh-2` PASS. Actual fixture seated at 23:58:18.738 UTC,
received one Snail Shell (4000000) at 23:59:50.445 UTC (~91.7 s), then stood at 23:59:50.753 UTC and
reported `STOPPED active=false`. Cleanup removed the fixture chair and extra catch. Log:
`fh-2-20261024T235818Z.log`, Oct 24, 2026 (4:58–4:59 PM PT). No real-client visuals or actual bot fishing
trip were tested. The earlier refusal is resolved by the named waterside spawn, without changing eligibility.
