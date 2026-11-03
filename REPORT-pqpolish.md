# PQ polish, ticket 130 follow-up - report (branch `164-pqpolish`, 1.6.4)

Worktree `/root/work/164-pqpolish`, branch `164-pqpolish` off `origin/release-1.6.4`, two commits:
`5d62de529` ("Say what a stalled KPQ bot is actually waiting for", the fix and its tests) and `4c20f4b58`
("Say what a stalled KPQ run was waiting for, in a report", this file). Nothing pushed, nothing merged, no deploy, no
server touched: everything below is read from the TEST mechanics log and from the unit suite.

The lane fixes two diagnostics that lied about a KPQ run on TEST. Neither changes what a bot does - both change what
the log says about it, so the next TEST run answers questions the last one could not.

## Result

The behaviour change: `STALL` and `OCCUPY` now print what the bot is actually waiting for.

| Line | Before | After |
| --- | --- | --- |
| `STALL` on the stage 5 collectors | `STALL xCashShopX on 0/2147483647 x 4001008: +0 in 90 s (under 2 a minute): ...` | `STALL xCashShopX on 0/all x 4001008: +0 in 90 s (under 2 a minute): ...` |
| `OCCUPY` on a rope | `OCCUPY xXToyAquaXx has not reached position rope1 in 30 s: at -797,130, wants -753,-23 (a rope)` | `OCCUPY xXToyAquaXx has not got closer to rope1 for 30 s (walking 30 s): at -797,130, wants -753,-23 (a rope)` |

A counted fight still prints its count (`on 10/10 x 4001007:`, `on 3/35 x 4001007:`), so the leader's hand-in step
reads exactly as it did. Only the whole-map sentinel changed spelling.

`OCCUPY` also stops firing on a bot that is doing what it was told. Its note is now a **progress** clock (restarted
whenever the bot gets 30 px nearer its aim point) over a **contiguous walk** (a gap of more than 10 s between two
ticks - the bot stood on the position and the solver answered with a `Wait`, or was sent elsewhere - starts a new
walk). The line then names both times a reader needs: how long it has been stuck, and how long it has been trying.

## What the measurements were

Read off the TEST log for 2026-11-01 00:31 -> 11-02 03:16, 34,816 `[Mechanics]` lines.

**`STALL`**: 180 of 186 lines read `on 0/2147483647 x 4001008`. The target was the stage 5 collectors'
`Fight(0, item, Integer.MAX_VALUE)` (`KillCollectHandInSolver`, the non-leader branch: fight while monsters live on
the map), so a watching player read "collect 2,147,483,647 passes" where the step means "keep fighting until the park
is empty". The leader's own step, `HAND_IN 10` (`content/kpq.yaml:311-316`), was the one counted fight, and it prints
unchanged.

**`OCCUPY`**: 1,513 lines (rope1 456, rope3 356, rope4 327, rope2 320, platforms 54) and **873 of them (57.7%) were
followed within 30 s by `WAIT <same bot> ... on <same position>`** - reported as failing, then found fine a median
**5 s** later (p75 7 s, p90 9 s). Counting all later `WAIT`s it is 1,157 (76.5%). **478** of the alarms came under
30 s after the bot had already said it stood on that very position, because the clock reset only on a *new* position
id: once a bot had occupied rope1 and left, the next walk back to rope1 re-used the first walk's clock. The rope
rectangles are also 4 px wide and 150-222 px tall (`content/kpq.yaml:144-147`), so "not inside the rectangle yet" is
the normal state of a climbing bot, not a stall.

(Numbers as pinned in the two test headers and in `StepExecutor`'s comments. An earlier handback quoted 875; the log
count is 873, which is the figure the code documents and the tests assert.)

## The fix

`src/main/java/soloMapling/mechanics/StepExecutor.java`, +153 / -32.

- `static String fightTargetLabel(SolverStep.Fight f)` - `untilItemCount() == Integer.MAX_VALUE ? "all" : the count`.
  `stallLine(name, held, f, why)` and the `HAND_IN` line both go through it.
- `static String stallLine(String name, int held, SolverStep.Fight f, String why)` - the one place a `STALL` line is
  built (it was assembled inline at the call site).
- `static final int OCCUPY_PROGRESS_PX = 30;` and `static final long OCCUPY_GAP_MS = 10_000L;` - the two constants the
  new clock needs, each justified at its declaration (a climber crosses 30+ px between 30 s notes; a 10 s gap means
  the bot was not walking here in between).
- `static final class StepExecutor.OccupyWatch` - replaces the per-beat `String occupyAt; long occupySinceMs; long
  occupyToldMs;` with `positionId`, `bestPx`, `sinceMs`, `toldMs`, `startedMs`, `sinceStepMs`, and
  `due(SolverStep.Occupy o, Point at, long now)`. Call site:
  `if (beat.occupy.due(o, at, now)) { log.accept(occupyStallLine(bot, o, at, beat.occupy.walkMs(now), now - beat.occupy.sinceMs)); }`
  (`StepExecutor.java:709`).
- `static String occupyStallLine(String name, SolverStep.Occupy o, Point at, long walkMs, long stalledMs)` - the line,
  one place, testable without a `Character` (`StepExecutor.java:728`).

No switch. Both changes are unconditional: a diagnostic that reports the wrong thing has no useful "off" setting, and
neither touches a bot's decision. The existing gate, `-Dslums.mechanics` (`Mechanics.FLAG`), still decides whether
the KPQ rows run at all, and is unchanged by this branch.

## Fail-before evidence

Each pin states the old line first, then the new one, so the change cannot be silent:

- `OccupyStallNoteTest.theOldClockFiredOnASecondWalkToTheSameRopeAndTheWatchDoesNot` reimplements the pre-fix state in
  the test (`String[] oldAt`, `long[] oldSince`, `long[] oldTold`, `LongPredicate oldFires` calling the surviving
  `StepExecutor.occupyDueForNote`) and asserts
  `"the old clock fires on the very first tick of the second walk"`, then that `OccupyWatch` does not.
- `OccupyStallNoteTest.theLineNamesHowLongItHasBeenStuckAndHowLongItHasWalked` keeps
  `"OCCUPY xXToyAquaXx has not reached position rope1 in 30 s: ..."` with
  `assertTrue(before.contains("in 30 s"), "the old shape, kept here so the change is not silent")` and asserts the new
  string character for character.
- `StallTargetLabelTest.todayTheCollectorsTargetIsTwoBillionAndNowItSaysAll` keeps the old expression as
  `oldStallLine(...)`, asserts `on 0/2147483647 x 4001008`, then asserts `on 0/all x 4001008`.
