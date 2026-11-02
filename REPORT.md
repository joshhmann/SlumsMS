# PQ polish, ticket 130 follow-up - report (branch `164-pqpolish`, 1.6.4)

Worktree `/root/work/164-pqpolish`, branch `164-pqpolish` off `origin/release-1.6.4`, one commit,
`5d62de529` ("Say what a stalled KPQ bot is actually waiting for"). Nothing pushed, nothing merged, no deploy, no
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

## Tests added (all green)

| Test class | Count | What it pins |
| --- | --- | --- |
| `OccupyStallNoteTest` | 7 | a climbing stander says nothing; a stander that stops says so every 30 s; a second walk to the same rope gets its own clock; a few pixels is not progress; a new position restarts the clock; the line names both times and both points; the old clock still fires where the watch does not |
| `StallTargetLabelTest` | 6 | the sentinel reads `all`; the whole-map fight is not a count; a counted fight keeps its count; a fight for no item prints no count; the line keeps one position; stage 5's shipped collector step is the whole-map one |

`mvn -q -o test -Dtest='soloMapling.mechanics.OccupyStallNoteTest,soloMapling.mechanics.StallTargetLabelTest'`
->
`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` and `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

Full suite on this worktree: `mvn -o test` -> `Tests run: 8372, Failures: 0, Errors: 0, Skipped: 137`, BUILD SUCCESS
(`surefire-reports/`, 1,097 test classes, all written 2026-11-02).

## Auto-QA

`tools/ops/autoqa-proof c0e0a2cd7..5d62de529` maps `StepExecutor.java` to 7 of 186 checks; the two test files need no
check of their own:

```
tools/ops/autoqa-run t-in-3,t-mx-1,t-mx-2,t-mx-3,t-mx-4,t-mx-5,t-mx-6
```

`t-mx-1..6` SKIP unless the server runs with `-Dslums.mechanics=true`.

## What is still unproven

- **No line of this has been seen on TEST.** Both fixes are proven by the unit suite against the old strings; whether
  the new `OCCUPY` note fires on the runs that used to be false alarms is exactly what the next mx-2 run's log will
  answer. Nothing here was deployed.
- **The 30 px / 10 s thresholds are reasoned, not measured.** A climber's real speed on a 4 px wide, 218 px tall rope
  gives 30 px in a few ticks; a bot that leaves and returns within 10 s would inherit the previous walk's clock. If
  the next run still shows a false alarm, the threshold is one constant (`OCCUPY_PROGRESS_PX`, `OCCUPY_GAP_MS`).
- **`STALL` still cannot say why a bot is not picking up.** `stallWhy` reports the bag and the floor around it; a bot
  standing next to a drop it may not take looks the same as a bot with nothing to take. That is a separate audit.
- **The collector reading `on 0/all` is honest but blunt.** It says "holding 0, fighting indefinitely"; it does not
  say whether the park still has monsters, which is the number that would actually explain the wait.

## Risks

- The log format is consumed by whoever reads a TEST run, and two shapes changed. Anything that greps for
  `has not reached position` or `/2147483647` (a dashboard, a saved grep, an operator's habit) reads differently from
  the next run on. No code in this repo parses either string (checked: no reader of `OCCUPY`/`STALL` outside
  `StepExecutor` and its tests).
- `OccupyWatch` is per-beat state. A bot that changes role mid-run (leader to follower) keeps its watch; the walk it
  was on is stale by the `OCCUPY_GAP_MS` rule, so the worst case is one extra note after the change, not a wrong one.
- The `all` label hides the sentinel from anyone who *relied* on `2147483647` to spot a misconfigured step. That is
  the intent, but it removes a de facto assertion: if a future step accidentally returns `MAX_VALUE` where it meant a
  count, the log now says `all` instead of looking broken.
- `fightTargetLabel` is `static` and shared by both lines, so a change to it moves `STALL` and the hand-in line
  together. That is deliberate (one meaning, one place) and is what `aCountedFightStillPrintsItsCount` protects.

## Reproducing the checks

```
cd /root/work/164-pqpolish
mvn -q -o test -Dtest='soloMapling.mechanics.OccupyStallNoteTest,soloMapling.mechanics.StallTargetLabelTest'
mvn -o test                        # full suite: 8372 / 0 / 0 / 137
tools/ops/autoqa-proof c0e0a2cd7..5d62de529
tools/ops/autoqa-run t-in-3,t-mx-1,t-mx-2,t-mx-3,t-mx-4,t-mx-5,t-mx-6   # -Dslums.mechanics=true on the server
```

Then read the next mx-2 log for `OCCUPY ... has not got closer to` lines and check how many are followed within 30 s
by a `WAIT` on the same position: the number that was 873 of 1,513 is the before/after.
