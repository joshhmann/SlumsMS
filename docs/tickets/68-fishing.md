# Ticket 68: Fishing for players and bots

Target: 1.6.0. Status: Next: inspect the legacy fishing path; build the new opt-in timer and cancellation flow before rewards.

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
