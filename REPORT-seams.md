# Engine seams for the last party quests - report (branch `164-seams`)

Worktree `/root/work/164-seams`, branch `164-seams`, branched from **`164-opq` at `c19adc3a06b59d2cbf931efceb6a2cd6c150ddc7`**
(`164 report: Pre-Horntail PQ is not completable, and here is every wall`, 2026-11-03 20:08 UTC). Another session keeps
committing rows to `164-opq`; this branch is a snapshot by design. Nothing pushed (`remote.origin.pushurl = DISABLED`),
nothing merged, nothing deployed, nothing run against TEST or live.

Four seams, in order, one commit each: (1) a reactor step by map object id, (2) a stage clock with a declared outcome,
(3) a branch-valued stage, (4) the world-queue start path and `content/guildquest.yaml`.

## 1. A reactor step by map object id

**The gap.** Guild Quest's Gatekeeper puzzle (map `990000300`) stands twenty statues in rows. They are reactors of three WZ
ids (`9208000` x7, `9208001` x6, `9208002` x7 on our copy of the map, WZ names `"1"`..`"20"`, plus the gate `statuegate`
`9208003`), and the script scores them by **map object id**: each statue's `act()` appends `objectId % 1000` zero-padded to
three digits to `stage1combo` while the display runs and to `stage1guess` while the party answers
(`scripts/reactor/9208000.js:34-37,53-58`, identical in `9208001.js`/`9208002.js`), and the Gatekeeper compares the strings
(`scripts/npc/9040009.js:79`). `StepExecutor` could address a reactor by WZ id (`HitReactor`, which resolves through
`MapleMap.getReactorById` - the first match of a hash walk) or by WZ name (`HitReactorNamed`); a step that says "the statue
whose object id is N" did not exist, and an id shared by seven statues hits whichever one the map lists first - a wrong
answer to the puzzle, not a slow one.

**What it does.**

| Piece | Where |
|---|---|
| `SolverStep.HitReactorByOid(int objectId)` - an intent: the bot walks within reach of that reactor and hits it with its own client, paced like every reactor hit (one per 3 s), a missing object id is said once per 10 s | `SolverStep.java`, `StepExecutor.hitReactorByOid` / `reactorByOid` |
| `StageView.reactors()` - every reactor on the stage's map as `ReactorPlacement(objectId, reactorId, name, state, x, y)`; `StageView.reactorStateByOid(int)` - one reactor's state by object id, `-1` when the map holds none | `StageView.java` (defaults: none / `-1`), `LiveStageView.java` |

The object id is the map instance's own (it differs from one run to the next), so a solver reads it from `reactors()` and a
row never carries one. Nothing moves or mutates anything server-side: the step is the bot's own walk and its own hit.

**Fail-before.** `ReactorByOidTest` (7). With the step and the view API in place but the executor ignoring the step - the
state of the old code for this step - four of seven fail for the right reason: `hitReactor` is *wanted but not invoked* on
the statue the step names (`theStepHitsTheStatueWhoseObjectIdItNamesAndNoOther`, `aHitIsPacedLikeEveryOtherReactorHit`),
`GCMovement.move` is *wanted but not invoked* toward a statue 400 px away (`aBotAwayFromTheStatueWalksToItAndHitsNothingYet`),
and a missing object id produces no line (`anObjectIdTheMapDoesNotHoldHitsNothingAndSaysSoOnce`: expected 1, was 0). The
other three pin the resolver and the view and are green by construction: `theWzIdCannotTellStatuesApartWhichIsWhyTheStepExists`
shows `reactorFor("9208000")` answering the first statue for any request while `reactorByOid` names exactly one.

**Still unproven.** (a) A real statue's `act()` through a real `Reactor` on a real map: the tests mock `Reactor`, so the
hit's script side effect (`stage1guess` growing by three digits) is argued from the scripts, not run - it is Item 4's
solver test that has to run the shipped `9208000.js` for that. (b) During the *display* phase a bot's hit would also
append to `stage1combo` (`9208000.js:41-44`: `!isRecentHitFromAttack()` is true for `hitReactor(client)`, which passes
`wHit=false`), so a solver must never hit a statue before `stage1status` is `active`; that is a solver rule, recorded here
because the step cannot know it.

## 2. A stage clock, and the outcome the row declares for it

**What already existed, and what did not.** `Stage.timeLimitMinutes` was already a row fact: the orchestrator honoured it for
`kind: BOSS` rows only (`Orchestrator.budgetFor`: `if (kind != BOSS) return run.budgetMs()`), counting from the stage's own
start, which is how Horntail's body room gets its hour (`scripts/reactor/2401000.js:33`). A party quest row that wrote it
had it **read and ignored**: PiratePQ's own header says the script restarts the timer to six minutes in every room
(`scripts/event/PiratePQ.js:241-268`, boss room eight) while the row's `timeLimitMinutes: 40` "is the whole run's", and
CWKPQ restarts it to 10/10/10/20/60 minutes (`scripts/event/CWKPQ.js:262-294`). In both, expiry is `end(eim)`
(`PiratePQ.js:223-225`, `CWKPQ.js:246-248`). And no row could say what the expiry *means* - end, or go on.

**What it does now.**

| Piece | Behaviour |
|---|---|
| `timeLimitMinutes:` on a stage | held by **every** kind of row (before: bosses only). The clock counts from the run's arrival in that stage, as the boss clock already did |
| the run's own clock | still applies to a non-boss row whose stage declares one: a room's clock is a tighter limit inside the run's, never a looser one (a boss row keeps replacing it, exactly as before) |
| `onTimeLimit: end` (default) | the run ends with `time budget of N min ran out on stage S` - the text the boss lane already produced - and the history line `abort: ...` |
| `onTimeLimit: advance` | the run goes on to stage S+1 **without a clear**, and says so in its history: `stage S: its own N min ran out; the row declares onTimeLimit: advance, so the run goes on to stage S+1 without a clear`. The next stage's clock starts on arrival. The one script that times a room this way is `GuildQuest.js:203-222`: its first `scheduledTimeout` closes the join window and starts the real 90-minute clock instead of ending the event |
| refused at load | `advance` without the stage's own `timeLimitMinutes` (no clock to run out), `advance` together with `loopTo`, `advance` on the last stage (nowhere to go), any other value |

A row that names neither field behaves byte-for-byte as before: the stage clock only exists where a stage declares one, and no
shipped party-quest row declares one (`grep -n "^    timeLimitMinutes"` finds Horntail's three boss rooms only). **No shipped row was
changed**: PiratePQ and CWKPQ could now declare their room clocks, but that changes what those rows do and belongs to the
session committing rows to `164-opq`.

**Fail-before.** `StageClockTest` (10). With the field read and the orchestrator unchanged - the old code's behaviour for this
fact - three of ten fail for the right reason: `aPartyQuestStageThatDeclaresAClockIsHeldToItNotToTheRowsOne` (a six-minute
room still running at 6:01: *expected true but was false*), `aRoomThatDeclaresEndEndsTheRunWithTheReasonInTheHistory` (the
same, through the history) and `aRoomThatDeclaresAdvanceSendsTheRunOnWhenItsClockRunsOutAndSaysSo` (*expected stage 2 but was
1*: the declared outcome is read and nothing acts on it - the silent stall). The other seven are regression guards and parse
checks: a stage with no clock keeps the row's whole-run clock, a longer room clock never outlives the row's, a clear on the same
tick still wins, a boss row keeps exactly what it did (and `BossStageBudgetTest`, `RevivePauseTest`, `LoopStageTest`,
`RowRunSimulationTest` stay green), the outcome parses in any case and defaults to `end`, and the four refusals above.

**Still unproven.** (a) No live run: the orchestrator is driven with a fake `StageView`, as `BossStageBudgetTest` is. (b) `advance`
has no shipped consumer yet - the one script that wants it, Guild Quest's strategy phase, is covered by the portal's own
`entryTimestamp` gate in the row built on Item 4, so the primitive ships with its tests and waits for a row that needs it,
the way `graceMs:` did. (c) The row-level clock is unchanged, so a row that declares room clocks *and* is slower than the
script's real timers (which restart on entry) is still only as honest as its numbers.

## 3. A stage whose successor depends on what the room says

**The two scripts that need it** (both Pre-Horntail PQ, which stays unbuilt for the reasons in `REPORT-opq.md` - the engine
shape is what this item is):

- the lightbulb room reads a reactor: `scripts/portal/hontale_C.js:30-41` sends the leader's team to `240050300` when the
  reactor named `light` (`2408001`, on `240050200`) is at state 1 and to `240050310` when it is at state 3, and refuses
  ("Hit the Lightbulb to determine your fate!") at any other state - two rooms, one of which a given run never sees;
- the fifth door goes back: `scripts/portal/hontale_Bopen.js:96-124` takes the party from the fifth mine `240050105` to the hub
  `240050100` again, so a room is entered twice.

**What it does.** A stage may carry `branches:`, a short list; each entry is a `when:` and a `goto:`.

```yaml
branches:
  - when: { reactor: light, state: 1 }      # a reactor, by WZ name (or by WZ id when all digits), at exactly this state
    goto: 8
  - when: { eventProperty: path, equals: b }  # an event property: present, or equal to a value
    goto: 9
  - goto: end                                # no when: always holds, so it goes last; `end` = the row is finished here
```

When the stage clears (its own `clear:`, unchanged), the first entry whose `when:` holds wins: the run goes to that stage - at or
before the current one is a re-entry - or, for `goto: end`, the row is finished there, **cleared**, through the same end-of-row
path the last stage takes (including `afterClear`). None holding means index + 1, exactly as before. A branch to a stage this run
does not play (past `lastStage`) finishes the row the same way instead of stranding it. The history says which branch fired
(`stage 7: reactor light at state 3: on to stage 9`). It reads state a row can already read (`StageView.eventProperty`,
`reactorState`); it interprets no script. `loopTo:` is unchanged and runs after the branches find nothing.

Refused at load: a `goto` that is not a stage of the row, or the stage itself, or neither a number nor `end`; a missing `goto`;
a `when` with neither an `eventProperty` nor a `reactor`, with both, or a reactor without a state (or a negative one); an entry
after an unconditional one (unreachable).

A re-entered room whose clear property is already set clears on its next tick: the row author owns making a loop terminate,
and the run's clock is what stops one that does not, exactly as it stops `loopTo`.

**Fail-before.** `BranchStageTest` (11). With `branches:` parsed and the orchestrator unchanged - the old code, which skips
unknown keys - five fail for the right reason: a run whose room says `path = b` still walks `[1, 2, 3]` instead of `[1, 3, 4]`;
a `goto: end` row still plays all four rooms (`[1, 2, 3, 4]`); a reactor at state 3 still goes to room 2; the first-match order
and the beyond-`lastStage` end both read `index + 1`. The rest are guards and parse checks: a row without branches is
unchanged, the state-1 light cave still works, a missing reactor matches nothing, a re-entry goes back and then on, and nine
bad `branches:` are refused at load, each named.

**Still unproven.** No shipped row uses it: the one quest that needs it (Pre-Horntail) is not completable, so the primitive is
tested with a synthetic row and the `hontale_C.js` shape. The reactor read is `reactorState(String)` / `reactorState(int)`, which
`LiveStageView` already implements against the stage's map; `holds()` itself is a default method on `StageView` and is
exercised through fakes, not through a real map.
