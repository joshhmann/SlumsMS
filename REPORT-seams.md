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
