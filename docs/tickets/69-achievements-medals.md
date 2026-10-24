# Ticket 69: Achievements and medals

Target: 1.6.0. Status: Planned: draft goals and rewards while building reusable event progress.

Read AGENTS.md, docs/HANDOFF.md, docs/ROADMAP.md and docs/release/RUNBOOK.md first.
Sprint tracking: docs/release/1.6.0-sprint.md. This ticket expands an already approved roadmap item; it does not
authorize Live, shared TEST replacement, rewards or decisions reserved to the owner.

## Plan

Around 30 owner-reviewed goals for job advances, fishing, boss clears, weddings and login streaks. Players and bots share verified events. Persist bounded progress and award claims exactly once; do not count a request or start as completion. GM preview/stage/grant with explicit audit. Default off; no invented item stats.

## Expected files

Achievement catalogue/service/event hooks; durable claim storage; player menu and QA-only GM tools; BotLifeSystem goals; QA catalogue.

## Tests and completion

Event provenance, duplicates, thresholds, invalid/stale events, bot parity, restarts and exactly-once claims. Any migration needs idempotent apply/rollback tests on throwaway MySQL.

Run the appropriate full suite, stage automated checks on the isolated 1.6.0 TEST copy when applicable, and
record what was actually checked. Keep flags safe/off by default. Real-client results remain pending until
observed. A plan or disabled framework is not a completed player feature. Commit and push; no release merge
without the owner request.

## Owner choices

The roughly 30 goals and exact medal IDs/stats. Cosmetic versus existing stats requested; final list needs owner review.
