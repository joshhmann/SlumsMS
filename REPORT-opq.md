# Party quests, ticket 164 - report (branch `164-opq`, 1.6.4): Orbis PQ, Ludi PQ and Henesys PQ

Worktree `/root/work/164-opq`, reset onto `0488c420e` (the `ex-2` auto-QA base) so the boss lane's engine is underneath.
Nothing pushed, nothing merged, live server untouched. The whole row is behind its **own** switch: a run starts only with
`-Dslums.mechanics=true` **and** `-Dslums.mechanics.opq=true`, so the Kerning PQ lanes behave exactly as they did.

The row covers **all nine script stages**: the run starts in the entrance and ends with `clearPQ` when the Root of Life is
put at the statue's base. Nothing in the two stages that were reported broken is behind that switch, or behind any switch:
the lobby and the sealed room already worked for a player who walks in, and this ticket changed neither - it added the two
tests that keep them working and the section below that says why they were reported broken.

## What ships

| Piece | Where | Why it was needed |
| --- | --- | --- |
| `Content.flag` + `Mechanics.enabledFor(Content)` | `soloMapling/mechanics/MechanicsModel.java`, `Mechanics.java`, `MechanicsRuns.java:354` | the row's own switch, so a half-covered party quest cannot be turned on by the flag that turns KPQ on |
| `ObjectiveKind.PLACE_ITEM`, `Objective.reactor` | `MechanicsModel.java`, `StageData.java` | "put the item on the ground inside this reactor": the altar, the music player, the statue base and the levers all need it, and `MapleMap.activateItemReactors` matches item **and** quantity |
| `SolverStep.PlaceItem` + `StepExecutor.placeItem` | `SolverStep.java`, `StepExecutor.java` | walks to the reactor's own position and drops `count` as **one stack** inside its box |
| `SolverStep.PlaceItemOn` + `StageView.reactorState(int)` | `SolverStep.java`, `StageView.java`, `LiveStageView.java`, `StepExecutor.java` | an item reactor named by **id**, with the exact stack, the server's 5000 ms activation honoured and a bounded retry: Henesys PQ's six beds, and the same contract PiratePQ, Ellin PQ and Magatia PQ need |
| `content/hpq.yaml` (2 stages) + `hpq-beds` | `soloMapling/mechanics/content/hpq.yaml`, `HpqBedsSolver.java` | Henesys PQ: six beds planted one drop at a time (`stage == "6"`), then ten cakes to Growlie (`1stageclear` present) |
| `SolverStep.HitReactorNamed` | `SolverStep.java`, `StepExecutor.java` | OPQ's levers are reactors named `"1"`..`"5"` (`getReactorByName("" + i)`), not ids |
| `Loot(0)` = the whole floor | `LiveStageView.floorDrops` | the storage room clears on `countItems() == 0`, and `countItems()` counts mesos too |
| `StageView.reactorState(String)` | `StageView.java`, `LiveStageView.java` | the levers' on/off lives in reactor state, the target in `stage6_c` |
| `content/opq.yaml` (8 stages) | `soloMapling/mechanics/content/opq.yaml`, listed in `index.txt` | entrance, walkway, storage, lobby, sealed room, a lounge room, the levers, and the statue with the garden - script stages 0-8, in our engine's 1..8 |
| `StageView.reactorEventState(String)` | `StageView.java`, `LiveStageView.java` | the lobby's pass is `4001056 + ` the slot the event script armed the `music` reactor with, not a table in the row |
| `StageView.area(int)` | `StageView.java`, `LiveStageView.java` | the sealed room's three platforms are the map's own `area` nodes; the NPC counts players with `getNumPlayersInArea(i)` |
| `StageSolver.extraMaps(StageView)` | `StageSolver.java`, `Orchestrator.java` (two sites) | a stage that drives a second map says so, so a bot in another room is not "behind" and is not walked out through a portal |
| `OpqBoxes` | `soloMapling/mechanics/OpqBoxes.java` | a clear opens a box; the stage that follows fetches the piece before it leaves - `forceHitReactor` only opens it, the drop needs a hit |
| 7 solvers: `opq-clouds`, `opq-sweep`, `opq-subroom`, `opq-levers`, `opq-lobby`, `opq-sealed`, `opq-statue` | `soloMapling/mechanics/Opq*Solver.java`, registered in `Orchestrator.allSolvers()` | each is one script's own clear rule, nothing else |
| `OpqSolversTest` (32 tests) | `src/test/java/soloMapling/mechanics/OpqSolversTest.java` | the fake room + the shipped row's shape, one test per stage rule and a fail-before per stage |
| `OrbisPqLobbyStageTest` (4 tests) | `src/test/java/scripting/reactor/OrbisPqLobbyStageTest.java` | the lobby: today's pass on the music player writes `statusStg3 = 0`, another day's matches nothing |
| `OrbisSealedRoomAreaTest` (2 tests) | `src/test/java/server/maps/OrbisSealedRoomAreaTest.java` | the sealed room: three platforms count their own players, and without them the stage has no answer at all |
| `RowRunSimulationTest` (7 tests) | `src/test/java/soloMapling/mechanics/RowRunSimulationTest.java` | a whole row end to end through the real orchestrator: Orbis PQ's eight stages, Zakum's arm-then-body chain, Horntail's three rooms, and a stage whose rule is never met |
| `Content.entryNpc` + `KpqStaging.start(Content, ...)` | `MechanicsModel.java`, `StageData.java`, `content/kpq.yaml`, `content/opq.yaml`, `KpqStaging.java` | one staging for every party quest: the front door (Lakelis / Wonky), the recruit map, the event script and the level band come from the row instead of four Kerning constants, so there is no Orbis twin of the staging to drift |
| `ContentLobby` + `-Dslums.mechanics.<row>.lobby` | `soloMapling/mechanics/ContentLobby.java`, registered at `net/server/Server.java` | bots assemble a party for any row whose lobby is on: they spawn the row's `minPlayers` at its `entryNpc` on its `recruitMap` and hand it to the one staging. Orbis is a row in its list, not the reason it exists; off unless all three flags are given |
| `ContentLobbyTest` (8 tests) | `src/test/java/soloMapling/mechanics/ContentLobbyTest.java` | row-parameterised over `ContentLobby.rows()`: the three-switch gate per row, the row each lobby reads, the ten-minute back-off per row and channel, and why Kerning's lobby structurally cannot staff these towers |

The walkway and the lounge reuse the existing `kill-collect-hand-in` solver: both stages are "farm N, pool them on the
leader, he hands them to Eak" (`haveItem(4001050, 30)` / `haveItem(4001052, 40)`), which is exactly what that solver does
for KPQ's passes.

## Stage coverage: Orbis PQ, 9 of 9 script stages

| Script stage | Map | Status | What clears it |
| --- | --- | --- | --- |
| 0 entrance | 920010000 | in the row (stage 1) | twenty cloud pieces dropped on the altar as one stack, then Eak's talk writes `statusStg0 = 1` |
| 1 walkway | 920010200 | in the row (stage 2) | thirty `4001050` pooled on the leader, handed to Eak (`statusStg1 = 1`) |
| 2 storage | 920010300 | in the row (stage 3) | no monster and no item left on the floor, then Eak (`statusStg2 = 1`) |
| 3 lobby | 920010400 | in the row (stage 4) | the day's pass dropped on the reactor named `music` writes `statusStg3 = 0`, then Eak writes `2` |
| 4 sealed room | 920010500 | in the row (stage 5) | exactly `stage4_0/1/2` players on the three platforms, then Eak (`statusStg4 = 1`) |
| 5 lounge | 920010600 + 601-604 | in the row (stage 6) | forty `4001052` handed to Eak in the lounge (`statusStg5 = 1`) |
| 6 on the way up | 920010700 | in the row (stage 7) | two of the five levers on and all five matching `stage6_c`, then Eak (`statusStg6 = 1`) |
| 7 garden | 920010800 | in the row (stage 8) | the Nependeath pot spawns Papa Pixie, he drops the Root of Life `4001055` |
| 8 statue | 920010100 | in the row (stage 8) | the six pieces on the six scars, then the Root of Life on the base reactor `minerva`: `scripts/reactor/2006001.js` sets `statusStg8 = 1` and calls `clearPQ` |

Stages 7 and 8 are **one** row stage (`opq-statue`, map 920010100) because only the second half has a property to watch: the
scars are counted with `getReactorByName("scar" + i).getState() >= 1` (`scripts/npc/2013001.js:11-19`) and there is no
property for "the statue is built", while `statusStg8` is what ends the quest. So the solver drops the pieces, asks Eak to
warp the team to the garden, fights Papa Pixie there, and carries the Root of Life back - `extraMaps` is what tells the
orchestrator that a bot in the garden is not a stray.

Which piece goes on which scar is a permutation, not the piece's own number, and it is read from our WZ: `scar1` takes
`4001046`, `scar2` `4001049`, `scar3` `4001047`, `scar4` `4001044`, `scar5` `4001048`, `scar6` `4001045`
(`Reactor.wz/2008000-2008005.img.xml`, one item trigger each). cosmic_agents' `STATUE_SCAR_BY_ITEM` is the same pairing.

## Stage coverage: Henesys PQ, 2 of 2 script stages

Henesys PQ's event script has two things that clear and one that ends the run; all three are in the row or pinned.

| Script stage | Map | Status | What clears it |
| --- | --- | --- | --- |
| 1 the six beds | 910010000 | in the row (stage 1) | one seed per bed, dropped on the bed's own id; the sixth activation sets `stage = "6"` (`scripts/reactor/9108000.js:4-9`) |
| 2 the rice cakes | 910010000 | in the row (stage 2) | ten `4001101` handed to Growlie 1012114: `1stageclear = true`, then `clearPQ` (`scripts/npc/1012114.js:12,66-76`) |
| the run-ender | - | pinned, never emitted | any hit on the Moon Bunny `9300061` ends the run 5 s later (`HenesysPQ.js:248-258`) |
| timer expiry after stage 1 | 910010200 -> 910010400 | **not modelled** | `scheduledTimeout` warps there only when the timer runs out with `1stageclear` set: a lost run, not a clear path |

## Ludi PQ: nine stages, one door, five boxes out of nine

The row is `content/lpq.yaml`, nine stages, all in the engine's own vocabulary, with its own switch
(`-Dslums.mechanics.lpq`, default OFF, on top of `-Dslums.mechanics`). Gates are our script's, not upstream's:
recruit `221024500`, front door 2040034 (the Red Sign, `scripts/npc/2040034.js`), five to six members, level 35-255,
45 minutes (`scripts/event/LudiPQ.js:26-29,35`).

| Script stage | Map | Clear signal | What drives it |
| --- | --- | --- | --- |
| 1 | 922010100 | `1stageclear == "true"` | `lpq-farm`: 25 passes from the map's monsters, hand-in at 2040036 |
| 2 | 922010200 | `2stageclear` | `lpq-farm`: **no monsters exist here** - eleven boxes (`2202003`) and a trap (`2200002`), 15 passes, hand-in at 2040037 |
| 3 | 922010300 | `3stageclear` | `lpq-farm`: eight monsters **and** eight boxes (`2201001`), 32 passes, 2040038 |
| 4 | 922010400 | `4stageclear` | `lpq-farm`: six passes **inside five rooms** (`922010401-405`, one room per member), 2040039 |
| 5 | 922010500 | `5stageclear` | `lpq-farm`: twenty-four passes inside six rooms (`922010501-506`), 2040040 |
| 6 | 922010600 | `6stageclear` | `lpq-farm` returns `ScriptedPortal("next00")`: the door's own script writes the property, nothing else does |
| 7 | 922010700 | `7stageclear` | `lpq-farm`: three passes from the three bosses and three boxes (`2201002`), 2040042 |
| 8 | 922010800 | `8stageclear` | `lpq-combo`: five members on the boxes the run's `stage8combo` names, 2040043 checks |
| 9 | 922010900 | `9stageclear` | `lpq-farm`: the key (`4001023`, not a pass), hand-in at 2040044, then `clearPQ` sends the party to 922011000 |

Three things are worth saying plainly:

- **The generic solver was not enough twice, and the reason is data.** `kill-collect-hand-in` farms monsters and pools
  passes; stage 2 has no monsters at all and stage 4/5 keep their passes in sub-rooms, so `lpq-farm` adds exactly those
  two behaviours and delegates the rest to it. Everything else really is the generic path.
- **A door can be the clear.** Stage 6's NPC only prints a hint; `scripts/portal/lpq5.js:33-41` writes `6stageclear`
  on the way through. `GCPortals.enter` (what `EnterPortal` uses for bots) checks `PqGates` and then does a plain
  `changeMap`, so a bot that "used the door" would never set it. The new `ScriptedPortal` step runs the portal's script
  the way `KpqStaging` already does for a staged human leader, and logs the outcome. That is the one new primitive, and
  it is not Ludi-specific: `PqGates` keeps every scripted door shut for bots, so Magatia, Ellin PQ and CWKPQ doors
  with side effects get the same use out of it (map-empty and reactor-state clears were *not* needed - no LPQ stage
  asks for one).
- **Where this departs from the plan and from upstream.** Seven of the nine stages run through `lpq-farm`, and the
  farming, pooling and hand-in inside it *is* `KillCollectHandInSolver` unchanged; the two extra behaviours (boxes, rooms)
  were cheaper as a small delegating solver than as a rewrite of the one KPQ's stage 5 shares - the generic solver has no
  reactor path at all, and stage 2 has no monster to fight. `StageData` also wants one `mob:` per `KILL_FOR_ITEM`, so
  stages with several monster ids (1: `9300006` and `9300005`, 7: the three bosses) name their primary one and list the
  rest in the stage's `mobs:`, which is what the solver reads anyway. Upstream's `AgentLpqDefinition` matches our nine
  counts and NPCs; its `MAX_LEVEL 50` and six-member party were not copied (table above).
- **Stage 8 is a different kind of answer.** `combination` matches a sequence the row declares; this one is written
  into the instance by the NPC when the stage opens, so the row cannot hold it. `lpq-combo` reads
  `stage8combo` + the map's nine `area` nodes each tick (`StageView.area(int)`), which is the same seam Orbis' sealed
  room uses.

## Henesys PQ: six beds, one drop per bed, and the server is five seconds late

The row is `content/hpq.yaml`, two stages, both on the hill `910010000`, with its own switches
(`-Dslums.mechanics.hpq` and `-Dslums.mechanics.hpq.lobby`, default OFF, on top of `-Dslums.mechanics`). Gates are our
script's: recruit `100000200`, front door 1012112 (Tory, `scripts/npc/1012112.js`), three to six members, level 10-255,
ten minutes, exit `910010300`, clear map `910010100` (`scripts/event/HenesysPQ.js:26-40,63-76`).

| Script stage | Map | Clear signal | What drives it |
| --- | --- | --- | --- |
| 1 | 910010000 | `stage` (the property) `== "6"` | `hpq-beds`: kill the hill's leaves, then drop one seed on each of the six beds, by id |
| 2 | 910010000 | `1stageclear` **present** (no value test) | `kill-collect-hand-in`: ten cakes from the ground, hand-in at 1012114 (Growlie) |

- **The one new primitive, and what it unblocks.** `SolverStep.PlaceItemOn(reactorId, itemId, count, why)` plus
  `StageView.reactorState(int)` and an executor that (a) never feeds a bed whose state has left zero, (b) waits
  `ITEM_REACTOR_SETTLE_MS = 6500 ms` between drops, and (c) gives up after `PLACE_ON_TRIES = 3` per bed per member.
  `PlaceItem` could not do this job: it takes a reactor *name* (the beds have none in `scripts/`, only the WZ names
  `moonflower1..6`) and it re-drops on the 1.5 s `DROP_GAP_MS`, inside the server's own delay. The same step is what
  PiratePQ (`sMob1-4` take `4001117`), Ellin PQ (`4001162` on the spine, `4001164` on the fountain) and Magatia PQ
  need, so it was built once, for the id-and-exact-quantity contract `MapleMap.activateItemReactors` enforces
  (`MapleMap.java:2665-2680`: item **and** whole quantity, drop inside `react.getArea()`, fires 5000 ms later).
- **The server's five seconds is a delay, not a refusal.** `ITEM_REACTOR_SETTLE_MS` is the 5000 ms activation plus a
  tick of margin; before it, a solver that re-checked would read "the bed did not take it" and drop again. Two drops
  inside the window both activate, and since each bed's script does `stage = parseInt(stage) + 1` and the stage clears
  at exactly `"6"` (`scripts/reactor/9108000.js:4-9`), that skips the clear for the rest of the run. The simulation
  measured this the honest way: before the fix, three members holding the same seed produced `stage = 18`, and the row
  could never advance. The fix is the per-bed claim in `stageMemory()` (`CLAIM_MS = 12 s`, re-takeable when it lapses),
  so the "never twice" rule is enforced twice over: by the claim before the drop and by `reactor.getState()` in the
  executor after it.
- **The box, and what the WZ does *not* say.** An item reactor's hit box is its own `lt`/`rb` vectors
  (`Reactor.wz/9108000.img.xml:18-19`: `lt(-19,-42)`, `rb(20,16)` around the bed), **not** a map `area` node -
  `Reactor.wz` has no `area` anywhere, and only 28 of 11,352 maps carry map-level areas at all, which is why `PlaceItemOn`
  drops on the reactor's own position instead of looking one up. The WZ also binds nothing: it says what each bed
  *accepts* (one type-100 event, quantity 1), never which dropped seed goes where, so the solver picks the bed from the
  seed it holds and the row states both sides per bed.
- **Which facts came from the WZ, and which from the script.** The pairing (bed `9108000..9108005` takes
  `4001095..4001100`, one each) and the geometry (the beds, the `fullmoon` reactor at `(-183,-433)`, Growlie at
  `(429,83)`, the hill's six monster ids `9300062/3/4`, `9300081/2/3`) are read from the client's own
  `Reactor.wz/9108000..9108005.img.xml` `event/0` slots (`type 100, state 1, quantity 1`) and
  `Map.wz/Map/Map9/910010000.img.xml`, cross-checked against the read-only WZ tree beside the repo. BotStory's
  `capabilities/partyquest/hpq/AgentHpqDefinition.java:75-81` carries the same six pairs (`SeedBed(seed, reactor, name)`)
  and is credited for the pairing, but the row's numbers are the WZ's. Everything about *when* a stage clears, who clears it and what ends the run is our script's.
- **A presence test is safe here, and that was checked rather than assumed.** `1stageclear` is written by
  `scripts/npc/1012114.js:12` (`clearStage`) and read by presence at `:59`, and `setup()` initialises `level`, `stage`,
  `bunnyCake` and `bunnyDamaged` - **not** `1stageclear` (`HenesysPQ.js:111-114`), so "present" cannot be true before
  Growlie takes the cakes. `bunnyCake` is the opposite case: written and incremented (`HenesysPQ.js:254-262`) and read
  by nothing, so the row does not model it.
- **The bunny is never a target, at two levels.** `friendlyKilled` on `9300061` schedules `bunnyDefeated` and ends the
  run five seconds later (`HenesysPQ.js:248-258`). At the hit, `BotAttackDriver.botMayTarget`
  (`BotAttackDriver.java:836-840`) already refuses a `Mob.wz` `friendly` mob - the comment there names HPQ's bunny - so
  the `Fight(0)` steps this row emits cannot reach it; and at the row, no step ever names `9300061`, which
  `theMoonBunnyIsNeverHitOnTheHill` asserts over the whole simulated run as well as over every step. The bunny is not on
  the hill during most of stage 1 anyway: the sixth bed spawns it (`scripts/reactor/9108000.js:10-14`).
- **Where this departs.** Stage 2 hands its ten cakes in through the generic solver, although nothing is *killed* for
  them - the cakes are the bunny's own drops picked off the ground, and the map's monsters only interrupt it. It fits
  because the generic path is fight-then-loot-then-pool-then-hand-in and the row's `KILL_FOR_ITEM` names the cake; the
  fiction is stated here rather than hidden in a solver of its own. Both stages sit on the same map, which the engine
  allows (the stage, not the map, is the boundary). Not modelled on purpose: `910010200` (the map `respawnStages`
  re-spawns and `scheduledTimeout` warps to only when the timer runs out *with* `1stageclear` set) and the bonus
  `910010400` - both are timer-expiry paths, not clear paths.
- **What the fail-before lines were.** "All six beds planted but a seventh activation in the window would skip `6`",
  "nine cakes are not a clear", "the moon bunny is never a target", and for the executor "a bed whose drop the server
  never takes is dropped on three times, never forty". The first is the one the simulation caught; the others are
  pinned as tests.

## The clear that is not a property (nine rows, one accessor)

Some finales write nothing. Cafe PQ's, Treasure PQ's and LudiMaze PQ's last stage only call `eim.clearPQ()`, and the only
signal that exists is the instance's own flag. `StageView.cleared()` used to return false for any stage whose data named
no property, so such a row could never advance past its last stage, however many times the instance said it was cleared:
not a missing solver, a missing *question*. The fix is small and general:

- `MechanicsModel.Clear` gained a third component (`onInstance`) and a factory `Clear.instance()`; a row writes
  `clear: { instance: true }`, and `StageData` still refuses a clear that names neither (`no clear: { eventProperty: ... }
  (+ optional equals: value), or { instance: true }`). The format doc (`docs/tickets/130-bots-play-mechanics.md`, the
  `clear` row) says so.
- `StageView.eventCleared()` is the new read (`default false` so every existing fake still compiles);
  `LiveStageView.eventCleared()` is `eim.isEventCleared()`.
- `StageView.cleared()` answers from it when the data says `onInstance`, before it looks at any property.

The fail-before pair is in `InstanceClearTest`: `aRowWhoseFinalStageClearsOnTheInstanceAdvancesPastIt` (a one-stage row
whose only clear is the flag: today the run ends `cleared`, before the change it never ends) and
`aStageWithNoPropertyToReadWasNeverClearBefore`. Both were run against the old `cleared()` and both failed there - the
message then is the whole defect: "the row's only stage clears on the instance's flag ==> expected: true but was: false".
Cafe PQ is now the first row to use it (below); LudiMaze PQ, PiratePQ, Treasure PQ and DelliBattle are the ones behind
it.

## Cafe PQ: six areas, six rows, one flag, and an NPC that pays

Six separate events (`scripts/event/CafePQ_1.js` .. `_6.js`), identical skeleton, one stage each. Six rows
(`content/cafepq1.yaml` .. `cafepq6.yaml`), ids `cafepq1`..`cafepq6`, `eventScript: CafePQ_N`, one solver
(`cafepq-coupons`), each clearing on `clear: { instance: true }` - no property exists anywhere in those scripts.

| area | maps (the script's own `eventMaps`) | coupons | row |
|---|---|---|---|
| 1 | 190000000, 190000001, 190000002 | 400 | `cafepq1` |
| 2 | 191000000, 191000001 | 350 | `cafepq2` |
| 3 | 192000000, 192000001 | 350 | `cafepq3` |
| 4 | 195000000, 195010000, 195020000, 195030000 | 450 | `cafepq4` |
| 5 | 196000000, 196010000 | 500 | `cafepq5` |
| 6 | 197000000, 197010000 | 300 | `cafepq6` |

Gates (`CafePQ_1.js:27-31,38`, identical in all six): 3-6 players, level 21-255, 45 minutes, front door and recruit map
193000000 (the Computer, `scripts/npc/1052013.js`), exit the same. `CouponsNeeded` is `:39`; the drop per kill is
`:251-259` (5 above level 40, 1 below, 3 above 65 in area 3 only). The coupons are spawned by hand on the floor at each
kill (`:261-277`), exclusive to the instance, and every `eventMap` re-spawns every 15 s (`:131-138`).

- **Six flags would be six ways to be half-on, so there is one.** All six rows carry `flag: slums.mechanics.cafepq`
  (`Mechanics.CAFE_FLAG`), because the Computer asks *which* area from one menu on one map - a server that can play one
  can play them all. Per row, as before: the lobby switch is `slums.mechanics.cafepqN.lobby` and the run cap is
  `slums.mechanics.cafepqN.runs`, which are the knobs that actually differ. `ContentLobbyTest` had to learn this: its
  row switch is now `content.flag()` when the row has one, and it still proves three flags are needed before bots form a
  party, now for all nine staffed rows.
- **The Computer pays, it does not charge.** `1052013.js:59` is `cm.gainItem(4001007, couponsNeeded)` - a *positive*
  quantity, so the party keeps its coupons and is given the same number again. A row with a `HAND_IN` objective would
  have the bots dropping coupons for an NPC that is handing them out, so the row states the count in a `COLLECT`
  objective (the leader's own tally) and the talk carries no item at all: `TalkNpc(1052013, 0, 0)`, with the script's
  own `haveItem` check as the gate. `theLeaderTalksToTheComputerOnlyWithTheWholeCountInHand` pins `itemId == 0` and
  `count == 0`, and the simulation asserts no step ever reads `TalkNpc[1052013] with 400 x 4001007`.
- **What the solver does, in order.** Leader with fewer than `couponsNeeded` → fight the area's monsters keeping the
  coupon (`Fight(0, 4001007, need)`), loot any coupon on the floor, and otherwise wait for the 15 s respawn; a member
  holding the whole count drops them by the leader (`PassHandover.give`); the leader with the whole count talks to the
  Computer. A member in one of the area's *sibling* maps is not behind (`extraMaps` = the script's other `eventMaps`),
  and a member on a foreign map walks back.
- **Fail-before.** `aCafeAreaWithoutItsCouponsNeverAdvancesOnTheComputersTalk` shuts the area's monsters and floor down:
  the leader reaches the Computer without the count, the script's `haveItem` refuses, the instance never clears and the
  run does not end `cleared`. With the instance flag wired in, the six good runs all end `cleared`, one stage each.

## LudiMaze PQ: sixteen maps, one stage, and a graph the client already publishes

One stage spanning sixteen maps, off its own switch (`-Dslums.mechanics.ludimazepq`, default off), one row
(`content/ludimazepq.yaml`), `clear: { instance: true }`. Three engine conditions had to exist before the maze could be
driven at all, and each has a fail-before:

1. **A declared map set, not a single map.** `playerEntry` warps each member to `809050000 + rand(0..14)`
   (`LudiMazePQ.js:144-150`), so fifteen maps are legitimate ground for one stage. `MechanicsModel.Stage` gained
   `maps:` (a list) and `mapSet()`, `StageData` parses it, and the orchestrator's wrong-map branch and its
   `runsThisMap` both ask the whole set. Fail-before: `aMemberOnADeclaredMapIsDrivenByTheSolverAndNotTreatedAsAStray`,
   run against the old single-map branch, gets `Wait[why=not on stage 1's map yet]` for a member standing on a declared
   map - which is how fourteen members would have been dragged to the leader's map and the maze bypassed by accident.
2. **`isEventTeamTogether()`.** `9103000.js:57-61` refuses the leader while one member is missing, so
   `StageView.missingFrom(mapId)` / `teamTogether(mapId)` answer it over the members the instance still holds
   (`registered` + `alive`), and the solver's `Wait` names who is missing. No count of members the instance has let go.
3. **The instance flag for the clear.** `9103000.js:66-73` calls `clearPQ()` and writes nothing; the row says
   `clear: { instance: true }` (the accessor from `1596fec0d`).

**The correction that matters: the maze is not shuffled.** The plan said "per-instance shuffled maze search", but
`LudiMazePQ.js:120-127` calls `shuffleReactors()`, which moves *reactors*; the passages are `pt 2` portals named
`t000`..`t003` whose targets are fixed in the client data (`Map.wz/Map/Map8/8090500*.img.xml`). Pinned from the dump and
in `LudiMazeSolversTest`: map `8090500NN` leads to `NN+4`, `NN+7`, `NN+12` - except that `809050008`'s `t001` reaches the
exit `809050015`, and only that one passage in the whole maze does. What *is* random is the start map. So the search is
real but tiny: `ludimaze` reads the portals it can see into a graph kept in `stageMemory()` (per instance, discarded when
the stage changes), walks the shortest route over what the party has learned, and explores the first passage to a map
nobody has seen yet. Sixteen members' first ticks map the whole maze.

- **The pass and the summoner.** The maps hold no monsters of their own; `scripts/reactor/8098000.js` summons them
  (ids scaled by distance from `809050005`) and they drop `4001106`. The exit wants thirty in the leader's hand
  (`9103000.js:62`), so the solver hits the summoner, fights, and - when the leader is still short at the exit - walks
  back through the exit's own `t000` to farm rather than idling at the goal.
- **Bounded, and named.** If the graph cannot reach the exit the solver maps what it has and, past `GIVE_UP_MS` (12 of
  the script's 15 minutes), abandons the run with the map set and the time in the reason:
  `aPartyThatCannotReachTheExitSaysWhatItMappedAndAbandonsInsideTheBudget` builds a two-map maze with no way out.
- **End to end in simulation.** `aSplitLudiMazePartyFindsTheExitAndClearsOnTheInstanceFlag` starts three members on
  three different maps and asserts the run ends `cleared`, that `EnterPortal` steps happened, that the exit's NPC was
  talked to, and that no property was invented (`props.isEmpty()`).

## Expedition rule: pause for revive (this is the report it lives in)

The boss report is on `164-bossfight`, a different branch, so this one carries the rule. It is the answer to the lane
report's owner question 1 ("a human death unregisters the member, and the run then aborts"): **pause for revive**.

`ZakumBattle.js` and `HorntailBattle.js` unregister a dead character so it can revive and walk back in, and
`Orchestrator.memberLost` read that as "a member left" and aborted. Now, for a `kind: BOSS` row, it holds the run:

| Piece | Behaviour |
| --- | --- |
| Switch | `-Dslums.bossfight.revivepause`, **on by default** (`RevivePause.on()`; safe to default on because the expiry path is exactly today's abort) |
| Window | `-Dslums.bossfight.revivepause.ms`, default `300000` (five minutes: warp out, revive, walk back through the altar); one row may override it with `-Dslums.bossfight.revivepause.ms.<row id>`, e.g. `.zakumbattle=600000` |
| While held | no stage advances, no member is warped, every bot in the instance gets `Wait` naming who it waits for (`waiting for Zak (12) to come back from a revive (no longer in the stage, 240 s left)`) |
| Resume | the moment the character is registered with the instance again: it returns to its place in the member list, roles are re-claimed, and the run says `12 is back after 60 s: the run resumes on stage 1` |
| Expiry | the run ends through the ordinary abort, saying `the revive window of 300 s ran out waiting for Zak (12) (no longer in the stage) - not a solver failure` |
| Death cap | wins: `EventInstanceManager.endForDeathCount` ends the instance and `MechanicsRuns.tick` aborts on that before the orchestrator is asked, so a pause cannot outlive the cap |
| Party quests | untouched: a KPQ or OPQ run whose member leaves ends as it always did, because the pause is `kind: BOSS` only |

The stage's own clock (a boss room's budget) gives the pause back on resume: being held for a revive is not the party's
time against the room. Tests: `RevivePauseTest` (5) - a member lost holds the run and it resumes, the same loss without
the switch is today's abort, the window expiring ends the run with that line, the death cap's end wins over the pause,
and a party quest still ends when a member leaves.

## A whole row, end to end, in simulation

`src/test/java/soloMapling/mechanics/RowRunSimulationTest.java` drives the **real**
`Orchestrator`, the real solvers and a fake `StageView` in a 100 ms loop and plays
three rows from entry to clear: Orbis PQ (eight stages), Zakum (one) and Horntail (three). It asserts that the stages
advance one at a time in the row's declared order, that the run ends on its own clear, that no member sits on a map the
stage does not drive, that no member idles more than twelve ticks while the party has work, and - the negative - that a
stage whose rule can never be met is worked and never abandoned. The fake world writes a property **only** when the step
that earns it is applied (the twenty clouds through `PlaceItem` on the altar, the levers through `HitReactorNamed`, the
clear through `TalkNpc`), so a solver that returns a no-op passes nothing.

What it proves: the orchestration - stage advance, role claims, map hand-offs, the box a clear opens, the two-map last
stage - works for a whole row, and the solvers agree with each other across stages rather than only within one.
What it does not prove: that a real `Character` can execute the steps (`StepExecutor` and its tests), that the maps hold
the reactors and NPCs the row names, or that the SQL drop tables behind the passes, the pieces and the Root of Life exist.

Two real defects fell out of it and are fixed in the same commit:

1. **A row with no portals could never advance.** When a member was still on the previous stage's map, the orchestrator
   walked it through `back.nextPortal()` or, with no portal to walk, waited - and Orbis PQ's row gives no `nextPortal`
   because its rooms are entered by map id. The party would have stood on the entrance map until the 30-minute budget ran
   out. Now a row that has no portal for that transition warps the member into the stage's room; Kerning PQ is untouched
   because every one of its transitions has `nextPortal: next00`.
2. **The lounge stage could never hand in.** `opq-subroom` talks to Eak in `920010600` while the stage's map is the side
   room `920010601`, and `extraMaps` did not name the lounge, so the orchestrator dropped the leader's `TalkNpc` as a
   step from a member on the wrong map and waited instead. The lounge is now one of that stage's maps.

Ludi PQ has the same two cases: `aLudiPartyPlaysAllNineStagesToTheKey` walks all nine maps in order and asserts
stage 6 was taken by `ScriptedPortal[portalName=next00]` and stage 2's passes came out of `2202003`, and
`aLudiStageWhosePassCountIsNeverReachedIsWorkedAndNeverAbandoned` holds the pass drops at zero and requires that the
run neither advances, abandons, nor goes silent.

## The two stages that were reported broken, verified rather than assumed

Both were reported as broken for humans and both are fine; the two tests below are what settles it, and the numbers are from
our own WZ, not from upstream.

| Claim | What the code actually does | Evidence |
| --- | --- | --- |
| "nothing writes `statusStg3 = 0`" | the reactor the pass is dropped on is **named `music`** (id `2008006`), not one of the `lp` boxes; its script is exactly `rm.getEventInstance().setProperty("statusStg3", "0")` | `scripts/reactor/2008006.js`; map reactors of `920010400` in `Map.wz/Map/Map9/920010400.img.xml` (2008006 `music`, 2002011 `stone3`, 2002004-2010 `lp`) |
| "the day is never armed" | `setup()` arms it: `getReactorByName("music").setEventState(new Date().getDay())` | `scripts/event/OrbisPQ.js:162-163` |
| "one slot per reactor, so only Sunday's pass can match" | a map's reactors are built by `ReactorFactory.getReactor` (`ReactorFactory.java:127-200`), which keeps **every** `event` child, so state 0 has seven slots and `getReactItem(day)` is `4001056 + day`; the one-slot builder is `getReactorS`, used only by `CustomReactor` | `MapFactory.java:406` (map path) vs `CustomReactor.java:67`; `Reactor.wz/2008006.img.xml` state 0..7, 7 slots each |
| "the drop always activates it" | `MapleMap.activateItemReactors` (`MapleMap.java:2664-2679`) needs the item **and** the quantity to equal the armed slot and the drop to sit inside the reactor's own box, so another day's pass matches nothing and does nothing | `Reactor.getArea()` (`Reactor.java:391`), `ReactorStats.getReactItem` (`ReactorStats.java:114`) |
| "`areas` is always empty, so three players can never be counted" | `MapFactory` fills them: it reads the map's `area` nodes and calls `map.addMapleArea(...)`, which is `MapleMap.addMapleArea` (`MapleMap.java:3684`), the writer of the same list `getArea`/`getNumPlayersInArea` read | `MapFactory.java:273-279`; the three rects in `Map.wz/Map/Map9/920010500.img.xml` imgdirs 1-3 |

Two tests keep it that way, and both fail if the chain breaks (they are in the branch's first commits and are part of
the suite below):

- `src/test/java/scripting/reactor/OrbisPqLobbyStageTest.java` runs the **shipped** `scripts/reactor/2008006.js` through the
  same Graal engine the server uses (`AbstractScriptManager.java:45`) over a stand-in `rm`, and asserts it leaves
  `statusStg3 = "0"`; it then builds the reactor the way `ReactorFactory.getReactor` builds it and asserts day `d` accepts
  `4001056 + d` and nothing else, and that a pass dropped outside the reactor's box arms nothing.
- `src/test/java/server/maps/OrbisSealedRoomAreaTest.java` puts a real `MapleMap` in the position the reported bug left it -
  no areas at all - and shows `getNumPlayersInArea(0)` throwing `IndexOutOfBoundsException`, i.e. the stage could never be
  cleared; then adds the three numbered rectangles the map loader adds and shows 2/1/0 and 1/1/1 counted per platform.

What is still unverified, because it lives in the database and not in the tree: the `lp` boxes drop their passes from the SQL
`reactordrops` rows (`ReactorScriptManager.java:93`), so a missing row for reactor ids 2002004-2002010 would still leave a
party with nothing to place. That is data, not code, and it is the one thing left that could stop a human in the lobby.

## Script vs upstream, where they disagree (our scripts win)

1. **The altar wants one stack of twenty.** `scripts/reactor/2006000.js` spawns Eak from the reactor's item reaction and
   `MapleMap.activateItemReactors` (`MapleMap.java:2664-2679`) compares the item **and** the quantity, so 2×10 does not
   count. cosmic_agents never puts the clouds on the altar (it hits the reactors and then talks); our `opq-clouds` places
   `20` in one stack, which is what the server accepts.
2. **Storage clears on an empty floor.** `countItems() == 0` counts mesos (`MapleMap.java:1422-1424`); upstream ends the
   room as soon as its bot holds the reward piece. Ours loots with `Loot(0)` and only then talks.
3. **The storage room in our tree gives its piece from Eak** (`gainItem(4001045, 1)`), not from the reward box upstream
   hits. The row therefore carries no reactor for it; `opq-sweep` opens boxes if the map has any and always ends with
   the talk.
4. **The levers are `stage6_c`, five characters, exactly two on.** Their coordinator keeps its own model; ours reads the
   property the server writes and clicks only the differing levers. The five are shared over the bots in character-id
   order so no two bots toggle the same lever.
5. **The lounge is four side rooms upstream, one here.** Their `LOUNGE_SUBROOM_ENTRY_PORTALS` sends one bot per room; the
   four rooms share their drop, so our party enters one room (`920010601`) and the leader warps back to Eak in the lounge
   (`920010600`) with the forty pieces. This one is a deliberate difference, not a fix.

6. **The statue and the garden are one stage here.** Our script keeps them as two (7 = "the team is in the garden",
   8 = "the base is armed") but only the second has a property to watch, and the scar condition is reactor state. One
   stage with `statusStg8` as its clear is the only shape the engine can express without a new clear type.
7. **Papa Pixie is spawned by the pot, not by seeds.** `scripts/reactor/2001016.js` clears the garden and spawns
   `9300039` on any hit; placing the seeds (`4001074` on the pot reactors) is upstream's flavour step and is skipped.

## Ludi PQ against upstream

| Item | Ours (the truth) | cosmic_agents `AgentLpqDefinition` | Which wins |
| --- | --- | --- | --- |
| level band | 35-255 (`scripts/event/LudiPQ.js:28-29`) | `MIN_LEVEL 35` / `MAX_LEVEL 50` | ours: the same foreign-constant mistake the Orbis 51-70 cap nearly put in this branch |
| party | 5-6 (`LudiPQ.js:26-27`) | six | ours |
| stage counts | 25, 15, 32, 6, 24, -, 3, combo, 1 (`2040036.js-2040044.js`) | the same nine counts | agree - the only part copied from it is this table, cross-checked against our NPCs |
| stage 6 | a door whose script writes `6stageclear` | no door stage | ours |
| boxes / rooms | stage 2 is boxes only, stages 4-5 are rooms | modelled as "interact" | ours (our map data says where the passes are) |

## Entry: what starts an Orbis run

Two seams, both generic:

1. **The run.** Every party-question instance start calls `MechanicsRuns.onInstanceStarted(eim, false)`
   (`src/main/java/scripting/event/EventManager.java:647`, again at `:723`), and `contentFor` matches the row by the
   event script's name `OrbisPQ` (`MechanicsRuns.java:377-388`). The eight stages are played by the bots the moment an
   `OrbisPQ` instance exists.
2. **The party.** `KpqStaging` (`src/main/java/soloMapling/mechanics/KpqStaging.java`) now takes its front door, its
   recruit map, its event script and its level band **from the content row** instead of four Kerning constants:
   `start(Content, Channel, Character, boolean withGm, int bots, boolean perfect, long budgetMs, int firstStage,
   int lastStage, Consumer<String>, Consumer<Active>)`. The Kerning entry points delegate to it with the Kerning row, so
   there is one staging in the tree, not two. The row gained one key for this: `entryNpc` (`kpq.yaml:9020000` Lakelis,
   `opq.yaml:2013000` Wonky) - the NPC a bot leader accepts from, because a bot cannot press a button.

`ContentLobby` (`src/main/java/soloMapling/mechanics/ContentLobby.java`, registered at `net/server/Server.java` beside
`KpqLobby.startIfEnabled()`) is what makes a bot party exist at the tower, and at any other tower whose row is listed. It is **not** Kerning's lobby with a different
map, and the difference is the point:

| | Kerning's lobby (`KpqLobby`) | a staffed row's lobby (`ContentLobby`) |
| --- | --- | --- |
| Where the party comes from | picks bots already standing at Lakelis (`KpqLobby.pickParty` over `lobby.getAllPlayers()`), a population this fork keeps on `103000000` | **spawns** `content.minPlayers()` bots on the row's recruit map, at `entryNpc`, in the row's level band - the tower has no standing population to pick from |
| When it runs | every 60 s | every 60 s |
| What starts the quest | leader talks to Lakelis | same: `KpqStaging` talks to `content.entryNpc()` |
| Yield to a human party | yes (`yieldForStaging`) | not applicable: an Orbis instance on the channel means `busy()` and it does not start |

Three switches per row, all off unless given: `-Dslums.mechanics=true`, `-Dslums.mechanics.opq=true` (the row's own, so
the tower's stages stay off for a server that only wanted Kerning), and `-Dslums.mechanics.opq.lobby=true` (this lobby,
so nothing spawns five bots behind an operator's back). The names are one formula, `slums.mechanics.<row id>.lobby`, and
`slums.mechanics.<row id>.runs` caps how many runs of that row are in flight (1).
A channel that refused a start (`getEligibleParty` said no) is left alone for ten minutes rather than retried every
minute. `ContentLobbyTest` pins the gate, the row, the ten-minute back-off and the structural reason Kerning's lobby cannot
staff these towers - for **every** row in `ContentLobby.rows()`, so a row that joins the list without a front door or a
party size fails there (its picker returns an empty list for an empty lobby). The Kerning path itself is proved
untouched by the tests that already ran it: `KpqFullRunTest` plays Kerning end to end (29 tests) and `KpqStagingRoomTest`
holds the staging's refusals, both green with the row-driven `start(Content, ...)` underneath.

## Open risks

- The walkway hand-in assumes Eak stands on `920010200`: `scripts/npc/2013001.js` has a `920010200` case, and
  cosmic_agents' `WALKWAY_EAK` is a point on that map, so the NPC has to be map data - [INFERENCE] from those two, not
  verified against WZ here.
- The lobby's seven passes and the six statue pieces come out of the SQL `reactordrops` table, and the Root of Life out
  of `mobdrops` (`ReactorScriptManager.java:93` is where the reactor rows are read). If a row is missing the party stalls
  with a `Wait` note naming the item, not silently.
- The box that a clear opens gets `OpqBoxes.GIVE_UP_MS` (60 s) of hitting before the run gives up on it and moves on, so a
  box whose drop row is missing costs a minute, not the run.
- `MobHitboxIndex`-style geometry is untouched: the solvers only move to points the data gives them, so a stage whose
  anchor is wrong will stall visibly (`Wait` notes) rather than teleport.
- **The lobby has never been observed in game.** `ContentLobbyTest` pins its gate, its rows and its back-off, and
  `KpqStaging` is the same code Kerning has used since 1.6.1, but nobody has watched five bots spawn at Wonky on
  `200080101`, form a party and walk into the tower. The first TEST deploy with the three flags is the evidence that is
  missing, and it is the only thing standing between this row and "proved".
- The lobby spawns its party on a live channel, so an operator who turns `-Dslums.mechanics.opq.lobby=true` on will see
  five level-51 to 56 bots appear next to Wonky. That is what the switch is for; the row's own flag being separate means
  a server can play the stages (with a human's party) without ever spawning one.

## Not done on purpose

No new dependency store, no second config file, no table, no client asset. The row is one YAML line in `index.txt` and
the switch is one system property. `docs/PROVENANCE.md` has the credit block for cosmic_agents `3c7e09435`.

## Corrections to what this branch had already shipped (2026-11-02 review)

Four defects in rows this lane wrote were found by review, and the fixes are on the record rather than silently
rewritten. Three are content rows, one is a doc citation that would have led the next reader to "fix" working code.

| # | What was wrong | Why it mattered | What it says now |
| --- | --- | --- | --- |
| 1 | `horntail.yaml` stages 1-2 named `summonProperty: summoned` | **A run could never leave the waiting phase.** `BossPhasesSolver.java:62` asks `BossTargetRules.phase(...)`, which returns `WAITING_FOR_SUMMON` while the property is absent, and the solver's `Wait` at `:66-67` plus its abandon at `:82` both require the summon - so a bot Horntail run parked until the 120-minute timer. And nothing could ever set it: `scripts/reactor/2408003.js:30-34` writes it in `touch()`, the server runs `touch()` from a client touch packet (`TouchReactorHandler.java:43-44`), while a bot's click goes `StepExecutor` -> `Reactor.hitReactor` -> `onHit`/`act`, and that script defines only `touch`. Meanwhile the fight was already standing: `HorntailBattle.js:86-93` pre-spawns `8810000` at `(960,120)` in room 1 and `8810001` at `(-420,120)` in room 2 at setup. | Both stages drop `summonProperty`, next to a comment that says which line spawns the head. `BossPhasesSolverTest` pins that a head room with **no event properties at all** returns a `Fight` on tick 0, and `HorntailPhasesTest` pins that no Horntail stage names a summon property. Stage 3 keeps none, as before. |
| 2 | the add family `8810019-8820023` was described as "no script and no server code spawns it" | The sentence is true about scripts and false about the fight: they arrive through the parts' own mob skill 200 - `Skill.wz/MobSkill.img.xml` levels 53-60, `MobSkill.java:286` `case SUMMON` -> `summonMonsters` (`:332-400`) - so a live wave of wyverns and cornians appears mid-fight. They are not statues either (the statues are `8820019-8820023`, Pink Bean's, on `270050100`). | The row says what spawns them, keeps them out of every `mobs:` list (killing one advances nothing), and `LiveStageView.partRole` returns `null` for them so `bossParts` drops them before the rules see them - they can neither become a target nor starve the solver into the 30 s abandon. `BossPhasesSolverTest.aSummonedAddWaveNeverBecomesATargetAndNeverStarvesTheFight` pins both halves. |
| 3 | `zakum.yaml:9,:33` cited `MapleMap.java:4939-4947` for the arm shield and `makeMonsterReal` for the body chain | `:4939-4947` is Horntail's part-to-body fake damage in `spawnHorntailOnGroundBelow`. And the `8800000 -> 8800001 -> 8800002` chain is **not implemented in Java at all**: it is WZ data (`Mob.wz/8800000.img.xml` `revive` 0 = `8800001`, `8800001` -> `8800002`, `8800002` none) consumed by `Monster.killBy` (`Monster.java:899-973`, `:903` `getRevives()`, `:924-933` spawns the next phase). `makeMonsterReal` only un-fakes the body when the last arm dies (`MapleMap.java:1785-1810`, the un-fake at `:1803-1804`). | The shield citation is `MapleMap.java:1530-1541` `bossDamageBlocked`, enforced for a player at `:1631-1635` and for a bot at `BotAttackEffects.java:228-234`; the wake is `:1785-1810`; the chain is attributed to WZ `revive` + `Monster.killBy`. `docs/tickets/130-bots-play-mechanics.md` and `docs/PROVENANCE.md` carry the same correction, because a reader without `wz/` sees zero implementation and would "fix" a working fight. |
| 4 | `opq.yaml` declared 3/6 players, level 51-70, 30 minutes | The gates were cosmic_agents' (`AgentOpqDefinition`), not our script's: `OrbisPQ.js:27` is `minPlayers = 5, maxPlayers = 6`, `:28` is `51-255`, `:37` is `eventTime = 45`. Our 3 let a party of three look complete, while the gate that actually decides is the script's `getEligibleParty` (`:104-112`: leader plus 5-6 members **on the recruit map** inside the band), which `startInstance` refuses on. | The row is 5/6, 51-255, 45 minutes, with the reason in the row. `exitMap` stays `200080101` and now says so: it is where a run **we** end sends a member, not the script's own `920011200` exit (`OrbisPQ.js:30`). The row's `sources` says the gates are ours and that upstream's are not copied - structure yes, gates no. `RowRunSimulationTest` plays OPQ with a full five-member party for the same reason. |

## Checks run

`mvn -o test` on this worktree, whole suite, nothing excluded: **8552 tests, 0 failures, 0 errors, 137 skipped, BUILD
SUCCESS** (`/tmp/opq-maze.log`, exit 0 - LudiMaze PQ's own commit; `LudiMazeSolversTest` 10 and one simulation case, and
both engine conditions carry a fail-before run against the old code first). Before it: **8541 tests, 0 failures, 0
errors, 137 skipped, BUILD SUCCESS** (`/tmp/opq-cafe.log`, exit 0 - Cafe PQ's own commit; `CafePqSolversTest` 7 and two
simulation cases, one of them the fail-before that shuts an area's monsters and floor down). Before it: **8532 tests, 0 failures, 0 errors, 137
skipped, BUILD SUCCESS** (`/tmp/opq-instance2.log`, exit 0 - the instance-clear commit; `InstanceClearTest` 3 and the
fail-before pair run against the old `cleared()` first, where two of them failed). Before it: **8529 tests, 0 failures, 0 errors, 137
skipped, BUILD SUCCESS** (`/tmp/opq-hpq.log`, exit 0 - Henesys PQ's own commit; `HpqSolversTest` 11 and three simulation
cases, one of which is the double-activation trap). Before it, on the Ludi-only head: **8515 tests, 0 failures, 0 errors, 137 skipped,
BUILD SUCCESS** (`/tmp/opq-lpq.log`, exit 0; `LpqSolversTest` 12, two more simulation cases, and the shared row/data
tests that walk every content file). Before it, on the Orbis-only tree: **8499 tests, 0 failures, 0
errors, 137 skipped, BUILD SUCCESS** (`/tmp/opq-lobby-final.log`, exit 0; the lobby's tests then became `ContentLobbyTest`, 8 of them, in the same range). That is the number to compare against the rest of the release - 8455 before this
branch's tests, 8461 after its first commit (`OpqSolversTest` 14, `OrbisPqLobbyStageTest` 4,
`OrbisSealedRoomAreaTest` 2), 8479 once the lobby, the sealed room and the finale were added (`OpqSolversTest` 32), 8486
with the whole-row simulation (`RowRunSimulationTest` 7), 8491 with the revive pause (`RevivePauseTest` 5), 8493 once
the corrections above added their two phase tests (`BossPhasesSolverTest` 15, `HorntailPhasesTest` 9), and 8499 with the
lobby (`ContentLobbyTest` 8, after the Orbis-shaped `OpqLobby` was generalised to every row).

Two `mvn` runs in this lane died mid-flight and neither was a test failure: one because a `bash` call is auto-backgrounded
and killed at 60 s (a whole-suite run needs ~27 minutes and `async: true, timeout: 0`), and one because the tree was
edited while surefire was discovering tests (`TestEngine with ID 'junit-jupiter' failed to discover tests`). Freeze the
tree for the whole run.

One run of the suite (the instance-clear one, `/tmp/opq-instance.log`) reported one failure that is not this lane's and
not a regression: `BotConversationFlowTest.aRefusedPartyAskIsPlannedFromTheGatesVerdict` failed on
`assertTrue(system.contains("Say no to the party"))` - the conversation planner's prompt - and passes alone (22/22 each
time), then passed in the whole suite on the next run. It is an order-dependent test in the conversation lane; this
commit touches no conversation code. Recorded rather than re-run into silence.

Earlier in the branch the same command found one real breakage, and it is fixed: `MechanicsDataFilesTest
.theContentRowsLoadFromTheJarWithNoSourceTree` pins the exact list of content rows and had to learn `opq` (then `lpq`,
then `hpq`). The Henesys PQ run also found a real defect before it ever shipped, in simulation rather than in game: three
members holding the same seed dropped on the same bed inside the server's activation window and the instance's `stage`
read `18` instead of `6` - see the Henesys PQ section's claim rule.
