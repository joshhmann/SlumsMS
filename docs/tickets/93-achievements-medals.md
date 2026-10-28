# Ticket 93: Achievements and medals

Target: 1.6.0, built in 1.6.1 (branch 161-medals). Status: implemented and on on TEST (2026-10-28); real-client look pending; live switch in docs/release/1.6.1.conf.

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

Owner chose cosmetic medals with no new combat stats. Exact 30 goal/medal pairings are proposed in
[the catalogue](../achievements/1.6.0-proposal.md); final review and atomic player/bot claim persistence remain.
No medal item or gameplay value was changed.

## Built (1.6.1, 2026-10-28)

- Goals: `src/main/java/soloMapling/achievements/achievements.yaml`, 32 goals (the proposal's 30 with these changes:
  the Be My Friend and daily-dungeon goals dropped, no event exists yet; added 50 quests, 10 trades, 5 Free Market
  purchases and the first rebirth; the 30-day streak became the 28-day check-in cycle, the longest streak the
  Daily Check-In has). Every medal id was checked in the server's `Character.wz/Accessory` and in the client's
  `Data` (tools/clientcheck scan of 2026-10-21, same ids).
- Medals are zero-stat copies made from the real item (`GameHands.zeroStats`); the ordinary item is untouched.
- Storage: `character_achievements` (migration 042 + rollback, tested apply-twice / rollback-twice on MySQL 8.0 by
  `JdbcAchievementStoreIT`). State 0 progress, 1 earned (medal owed), 2 granted. The claim is a conditional write,
  so two claims cannot both win; the item is added and the character saved before the receipt; a crash in between
  is repaired from the bag (a medal already held only gets its receipt).
- Hooks, command, GM tool, QA: see docs/roadmap/1.6.1.md and docs/ops/qa-suite.md (ac-1, t-ac-1).
- Not done: backfill of past boss/PQ/fishing/streak events (none were recorded); the client-side look of the medal
  tab; a daily-dungeon goal (the dungeon does not exist).
