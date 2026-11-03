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
| `RowRunSimulationTest` (16 tests) | `src/test/java/soloMapling/mechanics/RowRunSimulationTest.java` | a whole row end to end through the real orchestrator: Orbis PQ's eight stages, Zakum's arm-then-body chain, Horntail's three rooms, Ludi PQ's nine, Henesys PQ's six beds, LudiMaze's sixteen maps, the six Cafe areas, Pirate PQ's six maps, and a stage whose rule is never met |
| `Content.entryNpc` + `KpqStaging.start(Content, ...)` | `MechanicsModel.java`, `StageData.java`, `content/kpq.yaml`, `content/opq.yaml`, `KpqStaging.java` | one staging for every party quest: the front door (Lakelis / Wonky), the recruit map, the event script and the level band come from the row instead of four Kerning constants, so there is no Orbis twin of the staging to drift |
| `ContentLobby` + `-Dslums.mechanics.<row>.lobby` | `soloMapling/mechanics/ContentLobby.java`, registered at `net/server/Server.java` | bots assemble a party for any row whose lobby is on: they spawn the row's `minPlayers` at its `entryNpc` on its `recruitMap` and hand it to the one staging. Orbis is a row in its list, not the reason it exists; off unless all three flags are given |
| `PirateQuestSolver` + `content/piratepq.yaml` (1 stage) | `soloMapling/mechanics/PirateQuestSolver.java`, `content/piratepq.yaml` | Pirate PQ: six maps on one stage, three medal sets in the guide's order, two rooms to empty, four by-id doors taking one key each, and a clear that is the rescued leader's talk (`PiratePQ.js:337-344` writes no property) |
| `PiratePqSolversTest` (9 tests) | `src/test/java/soloMapling/mechanics/PiratePqSolversTest.java` | the fake ship: the row's gates and maps, the deck's empty-map passage, the guide's medal order, nineteen medals refused and twenty accepted, keys on the first shut door, three doors not a way through, the boss fought before the talk |
| `TreasureChestSolver` + `content/treasurepq.yaml` (1 stage) | `soloMapling/mechanics/TreasureChestSolver.java`, `content/treasurepq.yaml` | Treasure PQ: one map, the chest `6741001` as the only source of a monster, and `eim.clearPQ()` as the only clear - the same two primitives as OPQ's boxes and Cafe PQ's clear, in forty lines |
| `TreasurePqSolversTest` (4 tests) | `src/test/java/soloMapling/mechanics/TreasurePqSolversTest.java` | the row's gates, the chest hit before anything is fought, the fail-before that a chest-less row waits forever, and no roles to split |
| `DelliHoldSolver` + `content/dellibattle.yaml` (1 stage) | `soloMapling/mechanics/DelliHoldSolver.java`, `content/dellibattle.yaml` | DelliBattle: the one row whose clear is the server's clock (`scheduledTimeout`), so the solver only works the room and names the clock while it waits - and the friendly is never a target |
| `DelliBattleSolversTest` (5 tests) | `src/test/java/soloMapling/mechanics/DelliBattleSolversTest.java` | the gates, the budget longer than the script's timer, the hundred-tick proof that nothing the party does can clear it, the friendly never targeted, and the instance flag as the only end |
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

## Pirate PQ: six maps, five doors, and a clear that is a conversation

One stage over six maps, off its own switch (`-Dslums.mechanics.piratepq`, default off), one row
(`content/piratepq.yaml`), `clear: { instance: true }`. `PiratePQ.js` is a chain rather than a count, so the row declares
the maps (`maps:`) and one solver drives it (`pirate-quest`).

| Map | What the script wants | Where it is written |
|---|---|---|
| `925100000` the deck | an empty map, then the passage | `scripts/portal/davy_next0.js` |
| `925100100` the medals | 20 Rookie `4001120`, then 20 Rising `4001121`, then 20 Veteran `4001122`, in that order, to the guide `2094002` | `scripts/npc/2094002.js:47-78` sets `stage2` 1/2/3; `davy_next1.js` opens at `stage2 === "3"` |
| `925100200` the guards | an empty map | `davy_next2.js` |
| `925100300` the captains | an empty map | `davy_next3.js` |
| `925100400` the four doors | one Old Metal Key `4001117` on each of `2519000`..`2519003` (`sMob1`..`sMob4`) **and** an empty map | `davy_next4.js`; the WZ: type 100, quantity 1, one key per door |
| `925100500` the Lord Pirate | the boss down, then the rescued leader `2094001` talked to | `monsterKilled` spawns `2094001` at (777,140) and `2094001.js:45-58` calls `clearPQ()` |

- **Two things the row had to express that no other row needed.** The doors are item reactors known by **id** (their WZ
  names are `sMob1`..`sMob4`, not "door"), so stage 5 is the second user of `PlaceItemOn(reactorId, ...)` - the step HPQ
  built - and the four doors get one key each, not four keys on one. And the guide's three medals are a **sequence**, so
  the solver asks `stage2` which medal is wanted rather than the row naming one.
- **The clear is a talk, not a property.** `clearPQ` (`PiratePQ.js:337-344`) writes nothing; the sole writer is the
  rescued leader's talk. The row says `clear: { instance: true }`, and the solver talks only once the map is empty,
  because that NPC is spawned by the boss's death.
- **The keys are the map's own drops.** `4001117` is one of the instance's exclusives (`PiratePQ.js:73-76`) and
  `925100400` has no box reactor, so keys come from `9300114/9300115/9300116` through the SQL `mobdrops` table - a row
  there is invisible to every test in this branch. The run waits with a note naming the key, which is the only honest
  thing it can do.
- **Fail-before: three doors are not a way through.** `threeOpenDoorsAreNotAWayThrough` opens three of the four and gets
  no `EnterPortal`; `davy_next4.js` asks all four. Without that gate the party would be walked into a boss room the
  script never opened.
- **Fail-before: nineteen medals are not a hand-in.** `theLeaderHandsInTwentyOfTheMedalTheGuideWantsAndNineteenIsNotEnough`
  asserts the leader with 19 of the wanted medal never talks with an item, and with 20 talks with `4001120` x 20 - the
  guide's own `haveItem` gate.
- **End to end in simulation.** `aPiratePartyCrossesTheShipAndRescuesItsLeader` plays the row for up to 20,000 ticks and
  asserts `cleared`, that the instance flag is the only clear, that `stage2` reached `"3"`, that all four doors left
  state 0, that `PlaceItemOn` steps happened (keys on doors, not to an NPC), and that the rescued leader was talked to.
  It is the test that caught the row's first wrong assumption: the World stocked a map once per stage, so the medals
  room had nothing to kill - the ship is one stage over six maps and each map is stocked when the party first stands on
  it.

## Treasure PQ: one map, one chest, one monster - and the whole event is those three

The shortest row on the branch, off its own switch (`-Dslums.mechanics.treasurepq`, default off), one row
(`content/treasurepq.yaml`), `clear: { instance: true }`, four tests and one simulation case. It exists because it costs
almost nothing and it exercises two primitives a second time.

| Piece | Where it is written |
|---|---|
| gates: 4-6 players, level **140**-255, 45 minutes, entryMap `674030000`, exitMap and recruitMap `674030100`, clearMap `674030300` | `scripts/event/TreasurePQ.js:25-37` |
| the only summon on the map | `scripts/reactor/6741001.js`: `act()` spawns `9400589` |
| the only clear | `scripts/event/TreasurePQ.js:244-252` watches `9400589` alone and calls `clearPQ` (`:232-237`), which sets the instance's flag plus a ten-second warp to the bonus map |
| the front door | `scripts/npc/9220018.js:50-80` (MV's Lair) - the row's `entryNpc` |

- **`statusStg1` is a property nothing reads.** `setup` (`:115-125`) writes it as `"0"`; no other line in the file and no
  NPC mentions it. A row that cleared on it would clear on setup, so the row clears on the instance's flag instead.
- **Fail-before: without the chest there is nothing to kill.** `aRowWithoutTheChestNeverClearsBecauseTheMapHasNoOtherMonster`
  removes the reactor from the view and the solver waits forever naming the chest - which is the row this event would be
  with only a "kill what is out" rule, since `respawnStages` (`:131-134`) re-spawns a map that has nothing to spawn.
- **Fail-before: the chest is hit before anything is fought.** `theChestIsHitBeforeAnythingExistsToFightAndItIsTheOnlyThingHit`
  asserts the first step is `HitReactor(6741001)`, and that once a monster is out the chest is left alone (a second hit
  would summon a second monster for nothing).
- **End to end in simulation.** `aTreasurePartyOpensTheChestAndKillsWhatItHolds` plays the row and asserts the instance
  clear, no invented property, the chest hit, and a `Fight` step - the monster the chest held was fought, not skipped.

## DelliBattle: one room, one clock, and a clear no party can reach

The one row on this branch whose clear is a **timer**, off its own switch (`-Dslums.mechanics.dellibattle`, default off),
one row (`content/dellibattle.yaml`), `clear: { instance: true }`.

| Piece | Where it is written |
|---|---|
| gates: **1-2 players**, level 120-255, six minutes, `entryMap` `925010300`, `exitMap` and `recruitMap` `925010200`, `minMapId == maxMapId` | `scripts/event/DelliBattle.js:26-36` |
| the room re-spawns every 15 seconds | `:109-112` |
| **the clear** | `:119-124` `scheduledTimeout` kills the room, shows the clear effect and calls `clearPQ` (`:208-211`), which sets the flag |
| the only way to fail | `:194-199` `friendlyKilled` - Delli `9300162` dying ends the run |
| nothing else counts | `:213` `monsterKilled` is empty |

- **The party cannot advance this event, and the row says so instead of pretending.** `DelliHoldSolver` is two rules:
  fight what is out, otherwise wait naming the clock. Every one of a hundred ticks in
  `theRoomIsWorkedAndTheEventIsNeverAdvancedByKilling` is a `Wait`, which is the honest shape of a survival event.
- **`timeLimitMinutes: 8`, not 6.** The row's number is the whole-run budget the orchestrator enforces; the script's own
  six-minute timeout is what writes the flag. A row that copied the script's `6` could end the run on the budget one tick
  before the flag existed. `theRowIsTheScriptsGatesAndABudgetLongerThanTheScriptsClock` pins the inequality.
- **The friendly is safe by construction, not by a list.** `BotAttackDriver.botMayTarget` already refuses WZ-friendly
  mobs (the same check that keeps HPQ's Moon Bunny alive), so `Fight(0)` cannot target `9300162`;
  `theClerAndTheFriendlyAreNeverATarget` asserts the step asks for no mob id and no item.
- **End to end in simulation.** `aDelliPartyHoldsTheRoomUntilTheScriptsClockClearsIt` runs the row for the full six
  minutes of simulated time, asserts the instance flag is the clear, that no property was invented, and that the party
  never sat still for longer than the 15-second respawn.
- **No lobby row, and why.** `ContentLobby.rows()` does not list it: this event's own gate is one player
  (`DelliBattle.js:27`) and the staging hands `KpqStaging` a party of at least two, so a lobby would staff a party the
  script refuses to look at. A human party of two plays it with the row flag; no bot party is assembled.

## Magatia PQ: the read that corrects my own verdict

I said this was volume, not blockers. The read says otherwise, and the blocker is one line of stage 1.

**The stage-1 blocker: twenty-six NPCs with one id.** `Map.wz/Map9/926110000.img.xml` holds **26 placements of NPC
`2112013`** (`2112007` in the Z variant) and one reactor `d00`. Each placement rolls its own reward per instance
(`scripts/npc/2112013.js:29-33`: `book = "stg1_b" + (cm.getNpcObjectId() % 26)`), consumes it when read (`:35-36`), and
the one that rolls `3` sets `statusStg1 = 1` and hits `d00` (`:56-61`). A bot therefore has to read **each placement in
turn** - and our engine cannot address one: `SolverStep.TalkNpc(int npcId, int itemId, int count)` resolves through
`MapleMap.getNPCById` (`src/main/java/server/maps/MapleMap.java:2090-2101`), which returns the first NPC with that id, so
every bot would re-read the same book for ever and the stage would never clear.

The minimal seam, in this branch's own shape, is two additions and nothing else:

- `StageView.npcPlacements(int npcId)` -> the placements' `(objectId, x, y)`, from `MapleMap.getMapObjects()` filtered on
  `MapObjectType.NPC` (the same list `getNPCById` walks);
- `SolverStep.TalkNpcAt(int objectId, int npcId)` -> a `TalkNpc` that resolves the NPC by object id.

That is the fifth time this lane has needed "address the one the data means": reactors by id (`PlaceItemOn`), reactors by
name (`HitReactorNamed`), map sets (`maps:`), portals (`portalsOn`) - and now NPC placements. Until it exists, the row
cannot be built honestly, so no row was built.

**Which script writes each property, and which reads it** (a `grep` of `scripts/`, not a guess; Magatia's portals are
`jnr*` in the A variant and `rnj*` in Z):

| Property | Written by | Read by |
|---|---|---|
| `statusStg1` | `scripts/npc/2112007.js:59` / `2112013.js:61` (the book that rolls a pass) | `scripts/portal/jnr1_pt00.js`, `rnj1_pt00.js` (the way forward) |
| `statusStg2` | `scripts/event/MagatiaPQ_A.js:425` (`monsterKilled`, room empty) | `scripts/portal/jnr1_out.js`, `rnj1_out.js` |
| `statusStg3` | `scripts/reactor/2618000.js` | `scripts/portal/jnr2_out.js`, `rnj2_out.js` |
| `statusStg4` | `scripts/npc/2112005.js:83` / `2112006.js:83` (the leader's two files) | `2112005.js:63` (opens `jnr3_out3` once set) |
| `statusStg5` | `scripts/portal/jnr4_r1..r4.js` / `rnj4_r1..r4.js` | the same four portals |
| `statusStg6` | `scripts/portal/jnr5_rp.js` | itself, plus the combo `generateStg6Combo` writes (`MagatiaPQ_A.js:188-214`) |
| `statusStg7` | `scripts/npc/2112000.js:110,122` / `2112010.js:108,118` (Yulete, twice) | the Yulete NPC's own menu |

So the row is **portal-driven**, like Orbis: each stage's clear is a property a portal or an NPC writes and the *next
portal* reads, which is `ScriptedPortal`/`EnterPortal` plus one `TalkNpc` or `HitReactor` per stage. The one stage whose
chain I could not trace end to end from the portal scripts alone is stage 3 (`jnr2_out` and reactor `2618000`) - say
"unclear" rather than guess: it needs `scripts/reactor/2618000.js` and `scripts/portal/jnr2_out.js` read together, which
is the first hour of the build, not a design question.

- **The four-player requirement is exact**, `minPlayers = 4, maxPlayers = 4` (`MagatiaPQ_A.js:27`), and the row must
  refuse a party of three and a party of five. Our staging spawns `minPlayers`, so a Magatia lobby is the first row whose
  party size is a single number - the same `KpqStaging` path, one `if`-free consequence of the row.
- **The variant pair is `_A.js` and `_Z.js`, not `_A`/`_B`** - Zenumist's own ids (`926100*`), the same seven stages.
  One row parameterised twice, or two rows; the scripts are 462 lines each and differ in map ids and NPC ids only.

## CWKPQ: the job is a member fact, and it is one component wide

`scripts/event/CWKPQ.js:98-135` plants a randomised set of job reactors, fakes included, and the party has to find the one
that answers its own member's job. The question was whether carrying the job on `RunMember` is small.

It is small. `RunMember` is `record RunMember(int characterId, boolean leader, boolean bot, MechanicsPersona persona)`
(`src/main/java/soloMapling/mechanics/RunMember.java:7`) built at exactly one site, `MechanicsRuns.java:162`, from a real
`Character` - and `Character.getJob()` already exists, because `EllinPQ.js:93` filters on it (`Math.floor(job/1000) == 0`).
So `int job` is one component with a convenience constructor, filled where the member is built, read by a solver, and
gated by nothing new. It does not need a jobs *model*: it is what the character already is.

What it does not solve by itself, and why no CWKPQ stage was attempted in this commit: the reactor-name-to-job mapping is
in the script's own table (`4skill0a`..`4fake1e`, `:98-135`) and the sign-up is a reactor **touch**, not a hit.

**Click versus touch, and a correction to a defect this report was told about.** The asymmetry is real: `Reactor.hitReactor`
runs `onHit`/`act` (`src/main/java/server/maps/Reactor.java:242-246`), while `touch`/`untouch` are dispatched only from a
client packet (`src/main/java/net/server/channel/handlers/TouchReactorHandler.java:34-46` -> `ReactorScriptManager.touch`,
`src/main/java/scripting/reactor/ReactorScriptManager.java:109-131`). `scripts/reactor/2408003.js:30-34` writes `summoned`
inside `touch()`, so a bot click cannot set it.

But **the shipped Horntail row does not wait on `summoned`**, so it does not hang in room 1:
`content/horntail.yaml` stages 1 and 2 say "no summonProperty" in as many words, for exactly this reason, and the fight
does not need a summon - `scripts/event/HorntailBattle.js:87-93` spawns 8810000 in room 1 at (960,120) and 8810001 in room
2 at (-420,120) **at setup**. Touching 2408003 on top of that would spawn a *second* head, because its `touch()` spawns
unconditionally, so the row is right to leave the reactor alone. This is also recorded as correction 1 in "Corrections to
what this branch had already shipped", where the row used to name `summonProperty: summoned` and was fixed.

The hole is therefore real but **has no consumer on this branch yet**: `SolverStep.TouchReactor` should land with its first
real user (CWKPQ's Master Guardians), not before it, or it is a step nothing calls.

## REVERSAL: EllinPQ is not unbuildable - the maps have content and the scripts have a clear

This report first carried "ElnathPQ: unstartable (quest 6192 has no start path)" as a verdict. **That verdict was wrong**,
and it is withdrawn here rather than left to survive into a status file. `930000500` and `930000600` are EllinPQ's stage 5
and stage 6 - not empty maps with no script - and the event is fully scripted.

- `scripts/event/EllinPQ.js` (255 lines): `minPlayers = 4, maxPlayers = 6` (`:27`), `minLevel = 44, maxLevel = 255`
  (`:28`), `entryMap = 930000000` (`:29`), `exitMap = 930000800` (`:30`), `recruitMap = 300030100` (`:31`),
  `minMapId/maxMapId = 930000000/930000800` (`:34-35`), `eventTime = 30` minutes (`:37`).
- The job gate is one line, `:93` - `Math.floor(ch.getJob().getId() / 1000) == 0`, "only adventurers". That is a **party
  formation filter**, not an engine gap: the row's `minLevel` band plus a job check where the party is recruited, or a
  mixed party left to fail at the script's own `getEligibleParty` - which is what happens to any human party containing a
  non-adventurer today. No job model is invented for it.
- Front door `2133000` (`scripts/npc/2133000.js:51`), 930000500's reactors shuffled at setup (`:118-120`) - the memory
  puzzle, which is placements of reactors we already address by id, not of NPCs. Its clears are: map-empty for stage 1 (`monsterKilled` `:246-250`), `statusStg4` for the
foundry, and the Poison Golem `9300182`'s death for the whole event (`:236-241` -> `clearPQ`). `930000500` is the room
whose reactors are shuffled at setup (`:118-120`) - the memory puzzle, which is a per-run placement like Magatia's books but
of reactors, and reactors we *can* address by id.

So the honest status is: EllinPQ is a buildable row. Its clears are the script's own - map-empty for stage 1
(`monsterKilled`, `:246-250`) and the Poison Golem `9300182`'s death for the event (`:236-241` -> `clearPQ`) - and the one
gate is adventurer-only jobs, which belongs in party formation. The verdict above is void, and nothing about EllinPQ needs
a ticket saying it is impossible for humans either.

## The fifth addressing seam: an NPC that stands in twenty-six places

Magatia PQ's stage 1 is not a fetch and not a kill, and it is the first stage on the branch that cannot be driven by any
existing step. `scripts/npc/2112013.js` (Alcadno's shelf; `2112007.js` is the Zenumist twin) is one NPC standing **26
times** on `926110000`, and the script tells them apart by the click's object id:

```
var book = "stg1_b" + (cm.getNpcObjectId() % 26);   // :29-32, and the book that rolls the pass sets statusStg1 = 1 (:56-61)
```

`SolverStep.TalkNpc` resolves through `MapleMap.getNPCById` (`MapleMap.java:2090-2101`), which answers with the **first**
standing, so a bot reads one book for ever; and `BotNpcTalk`'s stand-in `cm` was built with the three-argument
`NPCConversationManager` constructor, which leaves `npcOid` at 0, so every bot talk would have read `stg1_b0` even with
the right placement walked to. Both are fixed here, as the **fifth** seam of this shape (after reactors by id, reactors by
name, declared map sets and portals):

| Piece | Where | Why |
| --- | --- | --- |
| `StageView.NpcPlacement(objectId, x, y)` + `StageView.npcPlacements(int)` | `StageView.java` | every standing of an NPC on the stage map, in map order; `LiveStageView` builds it from `getMapObjectsInRange(NPC)` |
| `SolverStep.TalkNpcAt(objectId, npcId)` + `StepExecutor.talkAt` | `SolverStep.java`, `StepExecutor.java` | walks to **that** standing and hands the script its object id |
| `BotNpcTalk.talkAt(bot, npcId, objectId)` | `BotNpcTalk.java` | `BotCm` now uses the five-argument `NPCConversationManager` ctor (`NPCConversationManager.java:112`), the one that carries `npcOid` |
| `MagatiaBooksSolver` (`magatia-books`) | `MagatiaBooksSolver.java` | reads the shelf one unread standing at a time, claims each book in `stageMemory()` so two members never read the same one, and past 26 books says so (`Abandon`) instead of looping |

**This is a shipped-behaviour change, not only a seam.** Until this commit every bot NPC talk ran the script with the
three-argument `NPCConversationManager` constructor, so `cm.getNpcObjectId()` answered **0** for every bot, everywhere.
Any script keyed on the object id - Magatia's 26 books, and whatever else is keyed that way and has not been met yet - was
reading the placeholder. The one-line reason: `npcOid` is only set by the five-argument constructor
(`NPCConversationManager.java:112-118`), and `BotCm` was calling the three-argument one.

- **Fail-before.** `noNumberOfTriesToTheFirstBookEverClearsThisShelf` reads the first standing 200 times and the shelf
  never clears - which is the shipped behaviour of `TalkNpc` - then reads the same shelf one standing at a time and
  clears on the seventh. `theShelfIsReadOneBookAtATimeAndOnlyTheSeventhClearsIt` pins the order (1000..1006, seven reads,
  no repeats); `twoMembersNeverReadTheSameBook` pins the claim; `aShelfWithNoBooksLeftSaysSoInsteadOfLooping` pins the
  bound.
- **What is still missing for the row.** Stage 1 is driveable now; the row itself is not written, because Magatia is seven
  stages and five of them are portal-driven with properties our scripts write and read across `jnr*` portals
  (`statusStg2` -> `jnr1_out`, `statusStg3` -> `jnr2_out` from `reactor/2618000.js`, `statusStg4` from `2112005.js:83`,
  `statusStg5` -> `jnr4_r1..r4`, `statusStg6` -> `jnr5_rp`, `statusStg7` -> `2112000.js:110`). Stage 3 (`jnr2_out` +
  `2618000`) is **unclear** from a read alone and will be marked as such rather than guessed. A one-stage row would end
  the run as "cleared" after stage 1 and tell a party the PQ was finished, so nothing is loaded until the set is whole.

## A clear with no signal at all: `{ mapEmpty: true }`

Until now a row could express exactly two clears: an event property (`{ eventProperty, equals }`) or the instance's own
flag (`{ instance: true }`). Ellin PQ's first stage has neither - `scripts/portal/party6_stage.js:9-18` refuses the
passage while `cm.getMap().getMonsters()` is non-empty, and the event writes no property anywhere - so a row for it could
only have been written by guessing.

`MechanicsModel.Clear` gained a fourth component (`mapEmpty`), a third YAML kind (`clear: { mapEmpty: true }`), and one
line in `StageView.cleared()` (`liveMobs() == 0`). It is the tenth clear/address primitive of this shape.

- **Fail-before.** `withoutTheNewKindThereIsNoClearToGiveIt` writes the same row with no `clear:` block and shows the
  reader refuse it, with an error that now names `mapEmpty` among the three kinds - before this change the vocabulary had
  no way to say it at all. `aRowWhoseStageClearsWhenTheMapIsEmptyAdvancesPastIt` reads `{ mapEmpty: true }` through the
  real YAML reader and clears a three-monster room only when the monsters are gone;
  `aMapEmptyStageIsNotClearedByAnInstanceFlagOrAProperty` shows the instance flag does not stand in for it.
- **What it is not.** It is not "the stage is over when the party stops fighting": it asks the map, every tick, and a
  re-spawn puts the stage back in play - which is exactly Ellin's first room, where the passage stays shut until the room
  is empty.
- **No row uses it yet.** Ellin PQ is the intended consumer and its row is not written (see the lane report), so this is
  engine vocabulary with a fail-before and no product code path behind it. That is stated here rather than left to be
  discovered.

## Magatia PQ: six of the seven script stages driven, and the seventh is a walk-out

`content/magatiapq.yaml` is the Alcadno variant (`eventScript: MagatiaPQ_A`), off `-Dslums.mechanics.magatiapq`, exactly
four players, level 71-255, 45 minutes, front door Juliet `2112003` on `261000021`. Its six stages are the script's own
first six, in order, each driven by a solver and each clearing on a signal the script actually writes; the seventh is a
warp-out. The Zenumist twin (`MagatiaPQ_Z`, `92610xxx`, `rnj*` names, twins `9300139`/`9300140`) is the same shape with
different ids and is not covered by this row.

| # | Map | Rule | Written by | Solver |
|---|---|---|---|---|
| 1 | `926110001` | 26 standings of the bookshelf; the book that rolls 3 sets the pass | `2112013.js:29-32,56-61` | `magatia-books` |
| 2 | `926110001` | the map has no monsters left | `MagatiaPQ_A.js:418-426` (`statusStg2`) | `fight-room` |
| 3 | `926110200` | the beaker counts three hits, and only while its state is 6 | `2618000.js:27-41` (`statusStg3`) | `reactor-count` |
| 4 | `926110200` + `926110201/202` | the leader hands over 4001134 **and** 4001135 | `2112005.js:72-87` (`statusStg4`) | `magatia-files` |
| 5 | `926110300` + rooms `926110301-304` | the four rooms, and in each a ten-row climb whose column per row is the event's own `stage6_comb<room>` | `jnr4_r1..r4.js:3-15` (entry bits), `jnr5_rp.js:25-60` (`statusStg6 = 1` on row 9) | `magatia-climb` |
| 6 | `926110401` | Yulete summons the Frankenroid when the leader talks, then it is one fight; either form's death calls `clearPQ` | `2112010.js:99-127` (talk → `9300151` for a party that did not escort, `9300152` for one that did, `statusStg7` = 1/2), `MagatiaPQ_A.js:435-450` (death → `clearPQ`) | `summoned-boss` |
| 7 | `926110500` | **nothing to do**: `2112011.js:57` only warps to `926110600`, and the run has already ended `"cleared"` at stage 6 | `2112011.js:57` | none - deliberately not a stage |

- **The stage that was held is now driven, because reading the exit beat reading the write.** `statusStg5` is a
  four-bit "which rooms have we entered" mask that no script finishes, and an earlier version of this row cleared it on
  `== "done"` - a value nothing can ever write. The authority is the portal out: `scripts/portal/jnr5_rp.js:25-60` takes
  `pt<row><col>` from the portal's own name, reads or rolls the room's ten-digit `stage6_comb<room>`, and on row 9 sets
  `statusStg6 = 1`. So the row's clear is `statusStg6 == "1"` and `magatia-climb` reads the combination the server
  publishes, works out the row from the client's own portal heights (`ROW_Y`, ten values from
  `Map.wz/Map9/926110301.img.xml`), and takes that portal with `ScriptedPortal` - the only step that runs a portal's side
  effects for a bot.
- **The boss room is a talk, then a fight, then the instance's flag - and `statusStg7` is not a clear.** Reading
  `2112010.js:99-127` and `MagatiaPQ_A.js:435-450` together settles it: talking to Yulete **summons** the Frankenroid
  (`9300151` if the party skipped the escort, `9300152` if it did it) and writes `statusStg7` as 1 or 2, while the clear
  is `clearPQ()` on either form's death - so the row's clear is `{ instance: true }` and `summoned-boss` needs no phase
  table. Talking again takes the `state == -1` branch, which only speaks, so a bot cannot stack bosses. Both forms are
  ordinary mobs on the room's ground; `boss-phases`' role/add targeting buys nothing here, and inventing a two-phase
  table for a mob with no parts would be exactly the kind of field that is written before it is read.
- **Stage 7 is a walk-out, so the row stops at six.** `scripts/npc/2112011.js:57` warps to `926110600` and nothing else
  happens there; the orchestrator has already ended the run `"cleared"` on stage 6's instance flag.
- **Three stage rules became three generic solvers**, because each is "the script decides, the bot keeps the condition
  true": `fight-room` (empty the room), `reactor-count` (hit until the row's counted clear is reached, reading the counter
  from the event rather than assuming), `magatia-files` (both documents to the leader, then to the NPC with **no** item in
  the step, because passing one id would make the leader hand a file over twice).
- **The Zenumist twin is not covered.** `MagatiaPQ_Z` is byte-identical except for the `92610xxx` maps, the `rnj*`
  reactor/portal names and the boss ids (`9300139`/`9300140` instead of `9300151`/`9300152`), so it needs its own row; a
  row that claimed both would be wrong about every id it names.
- **What is not tested here.** There is no simulation case for this row - seventeen maps, two side rooms and a
  four-player fixed party make it the largest world `RowRunSimulationTest` would need, and the row's value is in its
  stages' rules, which `MagatiaPqSolversTest` pins one by one. That gap is stated rather than papered over, and it is the
  first thing to add when stage 5 is decided.

## A clear that reads a reactor's state: `{ reactorState: { id, state } }`

The third clear kind with no property behind it. Ellin PQ's second stage is over when the spine reactor `3009000` reaches
state 4 and writes nothing; `Clear.ofReactorState(int,int)` + `StageView.cleared()`'s `reactorState(id) >= state` branch
express it, `StageData` parses `reactorState: { id: N, state: M }` and names it in the no-clear error. `>=` rather than
`==` because a reactor only advances, so a later state is still done - and an absent reactor answers `-1`, which can never
look like a clear. `MapEmptyClearTest.aStageCanClearOnAReactorsStateInstead` is the fail-before (absent reactor, state 3,
state 4, state 6). **No row uses it yet**; Ellin PQ's stage 2 is the intended consumer.

## Ellin PQ: the marble rule moved, and the stage that needed a fifth clear kind

Four things landed for Ellin, and the row is honest about the two stages it cannot drive.

| Piece | What it is | Where |
|---|---|---|
| `server/life/MonsterCatch` | the purifier-marble rule, extracted from the packet handler so a bot and a player catch a Poison Flower under the same conditions (`POISON_FLOWER` below `(maxHp/10)*4`), instead of a second copy in the executor | `UseCatchItemHandler`'s `EPQ_PURIFICATION_MARBLE` case now calls it |
| `SolverStep.UseItemOnMob(itemId, mobId)` | "try one now", nearest monster of that kind, one attempt per second per bot, logging `USE-ITEM`/`USE-ITEM-MISS` with the monster's HP | `StepExecutor.useItemOnMob` |
| `{ reactorState: { id, state } }` | the clear for a stage whose finish line is a reactor, not a property or a flag | see the section above |
| `ReactorStateSolver` (`reactor-state`) | presses the stage's reactor until the state the row's clear names | Ellin stage 2 |

The row (`content/ellinpq.yaml`, `eventScript: EllinPQ`, front door `2133000`, recruit `300030100`, level 44-255, 4-6
players, 30 minutes, `-Dslums.mechanics.ellinpq`) drives three of the seven script stages - empty the forest
(`{ mapEmpty: true }`, `party6_stage.js:9-18`), open the spine (`{ reactorState: { id: 3009000, state: 4 } }`,
`party6_stage.js:19-28`), and Ellin's first talk (`statusStg4 = 1`, `2133001.js:37-45`) - and **holds on the marble
stage**, whose name says why: `2133001.js:93-94` takes the leader's twenty `4001169` and warps the team to `930000500`,
so the stage's clear is a **warp**, and none of the four clear kinds (property, instance flag, empty map, reactor state)
can see it. Stages 5-6 are behind it. The marble rule and the step are built and the row does not use them yet: the
missing piece is a clear that can read "the party arrived on the next stage's map", and inventing one to make the row
look complete is exactly what this report exists to avoid.

The adventurer-only filter (`EllinPQ.js:93`) is recorded as a **party-formation fact**: `getEligibleParty` already
refuses a mixed party at the door, and no job model was added.

## What a difficulty variant would need from this engine

Recorded as a finding for the difficulty/chaos plan, not as work: five places where a second variant of a row cannot be
expressed today, each of which the plan has to decide rather than discover later.

| # | Where it bites | Why a variant cannot be expressed | cite |
|---|---|---|---|
| 1 | `MechanicsModel.Content` is one row = one gate set | `minLevel`, `maxLevel`, `minPlayers`, `maxPlayers`, `timeLimitMinutes` are single ints on `Content`, so "the hard version is level 110+ and 6 players" means a **second row** with its own `eventScript` name and its own switch. That works for ROW data but not for a variant of the *same* script, because `MechanicsRuns.contentFor` keys rows by event script name - two rows cannot share one script | `src/main/java/soloMapling/mechanics/MechanicsModel.java:344-347`, `MechanicsRuns.java` (`contentFor`) |
| 2 | `Stage` is single-valued | `mapId`, `timeLimitMinutes`, `mobs`, `killTarget`, `hitReactor` are one value each per stage; a chaos Zakum that summons a different body id or holds a different room has nowhere to put the second value. `tiers` exists on `Content` (`Map<Tier, TierRules>`) and is the closest thing, but it holds rules (damage floor, etc.), not stage identity | `src/main/java/soloMapling/mechanics/MechanicsModel.java:264-266` |
| 3 | The clear signal is one `Clear` | a variant whose clear differs - a chaos boss that writes `defeatedChaosBoss`, a hard PQ whose property holds a different value - can only be another row, because `Clear` is a single record on the stage and `StageView.cleared()` reads exactly one of its five kinds | `src/main/java/soloMapling/mechanics/MechanicsModel.java:190-193`, `StageView.java:249-270` |
| 4 | Boss ids live in Java in one place | `BossPhasesSolver.BODY_FAMILY = [8800000, 8800001, 8800002]` is a hardcoded Zakum body chain; a chaos Zakum's chain is a row fact and the solver cannot see it. This is the one place I would move to the row first (a `bodyChain:` on the stage) | `src/main/java/soloMapling/mechanics/BossPhasesSolver.java` (`BODY_FAMILY`) |
| 5 | No reward split | rows can name items a party *fetches* (`items:`) and a clear, but nothing describes what a clear pays or how a variant scales it (EXP, drops, a chest). A chaos variant's reward table has no field to live in | `src/main/java/soloMapling/mechanics/MechanicsModel.java` (`ItemUse`, `Content` - no reward component) |

**One more finding from the same source, for the boss lane rather than this one.** The Pre-Zakum farm stage is the same
mechanism and **not** `KILL_FOR_ITEM`: `reactordrops` gives `4001015` at `chance 1` from `2112005` and `2112012`, and
`4001018` from `2112014`, all present on the `2800100*` maps. A stage that farms there is `HitReactor` + `Loot`, and the
row must be written that way if it is written at all. Unverified and left as a question: whether one Pre-Zakum reactor
yields thirty hits' worth of `4001015` (it depends on that reactor's state count in `Reactor.wz`, which is not in this
tree) - if it does not, the stage needs several reactors or a raised hit cap, and which of those it is cannot be
decided from here.

Where I could, I moved the number into the row instead of the solver while writing this: `EllinMarblesSolver` now reads
the hand-in count from the row's `HAND_IN`, the item it hands over from that objective, the marble to spend from the other
`COLLECT`, and the monster to spend it on from the stage's `mobs:` (the constants are fallbacks only) - so a variant that
wanted forty marbles on a different flower is a `ellinpq.yaml` change, not a code change.

## A clear with no signal at all 2: `{ partyOnMap: <mapId> }`

The fifth kind, and the one Ellin PQ's marble stage needed. A stage that ends by **warping the team** writes nothing:
`scripts/npc/2133001.js:93-94` takes the leader's twenty marbles and `warpEventTeam(930000500)`, and no event property
changes hands. The row now says `clear: { partyOnMap: 930000500 }`; `StageView.cleared()` answers it when **every
registered member** is on that map.

"Every", not "any", is the whole design: a solver's `WarpTo` moves one member at a time, so "any" would let a stage clear
halfway through its own hand-over. `PartyOnMapClearTest` (3) pins it - one member through the warp does not clear, a member
still on the stage map holds it, every member there clears it, and an **empty** member list does not clear anything
(nobody registered is not everybody arrived).

## Ellin PQ: all six stages driven

| Stage | Map | Rule | Clear |
|---|---|---|---|
| 1 | `930000100` | kill what is out | `{ mapEmpty: true }` (`party6_stage.js:9-18`) |
| 2 | `930000200` | press the spine `3009000` to state 4 | `{ reactorState: { id: 3009000, state: 4 } }` |
| 3 | `930000300` | the room empties, the leader talks | `statusStg4 == "1"` (`2133001.js:37-45`) |
| 4 | `930000400` | `ellin-marbles`: talk once for ten `2270004`, throw them at `9300174`, loot `4001169`, hand twenty over | `{ partyOnMap: 930000500 }` |
| 5 | `930000500` | `ellin-stone`: the leader hits reactor `3002001`, loots the one drop, shows it | `{ partyOnMap: 930000600 }` (`2133004.js:28-33`) |
| 6 | `930000600` | `fight-room`; the Poison Golem `9300182` is the PQ's end | `{ instance: true }` (`EllinPQ.js:239-241`) |

- **Stage 4 is real, not a hold.** `EllinMarblesSolver` (`ellin-marbles`) is the marble loop: the leader's first talk is
  what hands out the ten marbles (`2133001.js:47-60`), `UseItemOnMob(2270004, 9300174)` spends them (the rule lives once,
  in `server/life/MonsterCatch.usePurifierMarble`), `Loot(4001169)` picks up what the flowers leave, and at twenty the
  leader talks with the count - which warps the team and clears the stage by arrival.
- **Stage 5 was held and is now driven - the hold was the wrong call and the data is why.** I had concluded from
  `scripts/` alone that `930000500` had no server-side rule. It has one, and it is not in `scripts/`: the map holds NPC
  `2133004`, one `3002001` reactor and eight `3002002` decorations, and the **stock `reactordrops` table has exactly one
  row** for those ids - `reactorid 3002001 -> itemid 4001163, chance 1, questid -1`, with nothing at all for `3002002`.
  `chance` is a denominator (`ReactorActionManager.java:241`, `Math.random() < dropRate / (double) chance`, and
  `Character.dropRate` is 1), so chance 1 is a guaranteed drop, and `scripts/reactor/3002001.js`'s four lines are
  `showClearEffect` + `rm.dropItems()`. `EllinStoneSolver` (`ellin-stone`) is therefore: leader hits `3002001` -> loots
  `4001163` -> shows it to `2133004`, which warps the team to `930000600` (`2133004.js:28-33`) - the `partyOnMap` clear
  this report already had. The eight `3002002`s never need touching, so the executor resolving one reactor per step is not
  a limit here. **The lesson is worth more than the stage: a PQ's drop rule can live entirely in the database**, and a
  `scripts/`-only read cannot see it. Only the leader loots, deliberately: it is one drop on the floor and the NPC refuses
  anyone who is not the event leader.
- **Stage 6 is the last stage, and it ends the PQ, not the room.** `930000600` holds the Poison Golem `9300182`;
  `EllinPQ.js:236-241` calls `clearPQ()` on its death, so the clear is `{ instance: true }` - no `partyOnMap` second kind
  is needed, and the row ends there (stage 7, `930000700`, is the post-clear room and is not a stage).
- **Zenumist stays uncovered**, and it is the same class of answer: the script that would advance it is missing a counter.

## CWKPQ: the stage map, read to the end, and the one thing that stops a row tonight

Gates, from `scripts/event/CWKPQ.js:26-39`: `isPq`, **6-30 players**, level **90-255**, `entryMap 610030100`,
`recruitMap`/`exitMap`/`clearMap 610030020`, `eventTime 2` minutes for the first stage, `maxLobbies 1`. Every stage flag is
seeded `"0"` at setup (`:150-172`).

| Stage | Flag | What raises it | Step kind |
|---|---|---|---|
| 1 (way in) | `glpq1` | `scripts/portal/glpqPortalDummy.js:8` after reactor `mob0`'s state is >= 1 | portal script + a reactor read |
| 2 | `glpq2` | reactors `6109003`/`6109004` `act()` to five (`6109003.js:8-15`) | **hit** (`HitReactor(6109003)`) |
| 3 | `glpq3` + `glpq3_p` | the same reactors' second counter, **plus** `glpqPortal2.js:7-19` counting party members through the door, five each | hit **and** a per-member portal pass |
| 4 | `glpq4` | sigils `6109016`..`6109020` `act()` to five (`6109016.js:1-11` and twins) | **hit** |
| 5 | `glpq5` | reactors `6109005`..`6109009` `act()` to five | **hit** |
| 6 | `glpq6` + flames | `6109021`+ answer to **`touch()`**, each with its own `glpq_fN` | **touch** |
| boss | the script's own clear | `boss-phases` on `610030800` | hit |

**Why no row tonight, stated plainly:** it is a six-stage build across three distinct mechanics, and every stage's exit is
a *portal script* that reads the flag (`glpqPortal0..5.js`), not a warp our orchestrator can take. The randomness the
owner was worried about turned out **not** to be the blocker - the sigils and the five-count reactors are addressed by
id, so `HitReactor(6109016)` reaches them whatever the shuffle did, and only the flames need a name. The blocker is
scope: three solvers (sigil hit, party-passage counter, flame touch), six stages, and a portal-progression gate like
Magatia's, against a branch that is being merged. Shipping stage 1 alone would end the run `"cleared"` at its last
playable stage, which is the lie this report keeps refusing to tell.

**What is deterministic, for the record:** the five sigil ids and the five `6109005..6109009` ids are fixed in the
scripts, the counters are event properties, and `6109016.js:1-11` needs no job check at all - it increments `glpq4`
whatever member hits it, so `RunMember.jobId` is *not* needed for stage 4 even though it is now carried. The job is only
needed if a future stage wants to place members by class.

## CWKPQ: why the row is still not written, in two facts

Both step kinds are needed on the same map, and that is the whole shape of the remaining work:

| Reactors | What fires them | What they count |
|---|---|---|
| `6109016`..`6109020` (the five sigils) | **`act()`** - a hit, so `HitReactor(<id>)` already reaches them | `glpq4` + 1 each, and five shows the `4pt` clear effect (`6109016.js:1-11` and its four twins) |
| `6109021`..`6109027`+ (the flames) | **`touch()`** - only from a client packet, which is why `TouchReactor` had to exist | their own `glpq_fN` flags, read by the boss room's rules |

So CWKPQ's stages mix hit-addressed and touch-addressed reactors, on maps whose reactors are further shuffled and
re-named per run by `CWKPQ.js:98-135`. Writing it stage by stage is a real build (the flame stage alone reads
`6109021`..`6109027` plus whatever consumes `glpq_f0..f7`), which is why the engine pieces shipped ahead of the row rather
than a half-row shipped with them. `HitReactor(id)` covers the sigils today; `TouchReactor(id)`/`TouchReactorNamed(name)`
cover the flames.

## CWKPQ, first half: the job is carried and the touch has a step

Two of the three pieces are in; the sign-up row is not.

| Piece | What it is | Where |
|---|---|---|
| `RunMember.jobId` + `jobClass()` + `hasJob()` | the member's job id, carried from the real `Character` at its one construction site (`MechanicsRuns.java:162`, `c.getJob().getId()`), never inferred from a persona. `jobClass()` is the first digit of the id - `jobId / 100`, not `/ 1000`, because Maple job ids are three digits (`110` is a Fighter) | `RunMemberJobTest` 2 |
| `SolverStep.TouchReactor(int)` | the `touch` callback, which is dispatched **only** from `TouchReactorHandler.java:34-46` and which `HitReactor` cannot reach (`hitReactor` runs `onHit`/`act`) | `StepExecutor.touchReactor` calls `ReactorScriptManager.touch(client, reactor)`, one touch a second per bot |

A **name-addressed touch** also landed (below) - `4skill<jobClass><slot>` carries its own answer, so no accessor is
needed to list reactors: `StageView.reactorState(name)` answers `-1` for a name the map does not hold, which is enough to
probe `4skill<j>a`..`4skill<j>e`.

What the sign-up row still needs, and it is one chain of reads, not a design:

- `scripts/portal/glpqPortalDummy.js:1-20` is what actually sets `glpq1 = 1`, and it does so from **`mob0`'s reactor
  state** (`if (react.getState() < 1) { forceHitReactor(1); ... glpq1 = 1; warp(610030100) }`), not from the job reactors
  directly. So the chain is: eight `4skill*`/`4fake*` touches on `610030400` → whatever arms `mob0` → the dummy portal.
  `glpqPortal0.js:1-11` then gates the way to `610030200` on `glpq1`. The next hand must read what arms `mob0` before
  writing a stage, because a solver that touches job reactors and then expects `glpq1` would stall on a portal it never
  walks into.

The sign-up's own reactor set is now pinned too: the eight deployed job reactors are the `6109016`-family, whose `act()`
increments `glpq4` and shows the `4pt` clear effect at five (`scripts/reactor/6109016.js:1-11`), so the map's exit is a
five-sigil count rather than a single sign-up. `scripts/portal/glpqPortalDummy.js` never touches `mob0` from any script
(`mob0` appears in exactly one file in `scripts/`), which is why the row is not written on a guess.

What the sign-up row still needs, beyond that chain:

1. A **name-addressed touch** (`TouchReactorNamed(String)`, the twin of `HitReactorNamed`) or a
   `StageView.reactorNames(int)` accessor, because CWKPQ chooses its reactors by **name**: `CWKPQ.js:98-135` deploys eight
   groups of `4skill<jobClass><slot>` / `4fake*` onto `610030400`, one per group, retrying until all six job values are
   present, then `shuffleReactors` moves them. The name carries the answer (`4skill2d` is the magician's), which is why
   nothing has to be guessed - but the engine cannot currently list or address a reactor by name for a touch.
2. The row itself: stage 1 on `610030400`, solver `cwkpq-signup`, one member per job class from 1 to 4 (and 5 if the party
   has a pirate), fakes in the never-target set, clear on the event's `glpq1` (`CWKPQ.js:150-160` seeds every `glpqN` to
   `"0"`; the sign-up sets `glpq1 = 1`).

## CWKPQ ships: four rooms, and the flames that say why they are not one

`content/cwkpq.yaml` is a row now (`flag: slums.mechanics.cwkpq`, 6-30 players, level 90-255, 20 minutes, front door
`9201113` on the recruit map `610030020` - `scripts/npc/9201113.js:44` is the only script that starts the event).

| # | map | room | solver | clear |
|---|---|---|---|---|
| 1 | `610030400` | the five job sigils | `cwkpq-sigils` | `glpq4 == "5"` (`6109016.js:5-12`) |
| 2 | `610030300` | the door that counts five | `cwkpq-door` | `glpq3_p == "5"`, the exit's own condition (`glpqPortal3.js:4`) |
| 3 | `610030500` | the Relic of Mastery | `cwkpq-sigils` (same class, the row names its five reactors) | `glpq5 == "5"` (`6109005.js:6-12`, `glpqPortal5.js:4`) |
| 4 | `610030800` | the host, the Guardians and the four Twisted Masters | `cwkpq-boss` | `{ instance: true }`, reached only through `glpq6 == 3` (`9201115.js:80-91`) |

Two solvers, not four, because the two counted rooms are the same shape: a list of reactors, a counter the scripts raise
on `act()`, and a clear the row names. `CwkpqSigilSolver` reads its reactors from the stage's own `HIT_REACTOR`
objectives, so stage 3 is a yaml entry rather than a second class, and its overshoot guard reads the counter the
<b>row's</b> clear names - which is the real bug in this PQ family: `6109016.js:5` and `6109005.js:6` are both
unguarded, so a sixth hit leaves the counter at 6 and a `== 5` clear can never match again.

`CwkpqDoorSolver` is the one place the party has to take turns: `glpqPortal2.js:3-19` counts each passer into
`glpq3_p` <b>and warps him back onto the same map</b>, so "one at a time" is not a schedule the solver imposes - it
counts passes, and a member could pass twice. The two reactors of that room (`6109003`/`6109004`) raise `glpq3`, the
other half of the exit's condition, so the solver works them first and only then sends anyone through the door; at five
it waits, because `glpqPortal2.js:15` refuses a sixth pass.

`CwkpqBossSolver` is `Converse(9201115, [0, 0])` between fights, three times over: `glpq6` 0 -> 1 spawns ten 9400594
and twenty 9400582, 1 -> 2 spawns the four Twisted Masters (9400590 Margana, 9400591 Red Nirg, 9400592 Rellik,
9400593 Hsalf), and 2 -> 3 is the host calling `clearPQ()`. The masters are **not** a tier of their own: the spawner
decides when they exist, so the solver fights whatever is out and then asks again.

**The flames are the one room this row does not model, and the reason is a line, not an opinion.** The eight flame
reactors `6109021`..`6109027` answer `touch()` and each counts its own `glpq_fN` (`6109021.js:9-13`), but a sweep of
every reader of `glpq_f` in `scripts/` finds **only the event's own seeds** (`CWKPQ.js:165-172`) - nothing checks them,
no portal gates on them, and the map's `610030600` exit is `glpqPortal6`, which gates on `glpq6` alone
(`glpqPortal6.js:4`). A stage with no writable clear cannot be a row stage, so the row does not claim one; if the keep
ever starts reading those flags, the room is one `TouchReactorNamed` stage away.

## CWKPQ: the map data the row needs, read

`Map.wz` is not in this tree, so the names came from the read-only dump at
`/root/work/SlumsMS-152-maps/wz/Map.wz/Map/Map6/` (identical file in `/root/slums-nav-v70/wz`):

| map | portal names | script | reactors and their names |
|---|---|---|---|
| `610030300` | `next00` at (215,-1107), `next01` at (-441,1535), both `pt 8` | `glpqPortal2`, `glpqPortal3` | five, unnamed |
| `610030500` | `next00` at (1187,93); `in01`..`in05` | `glpqPortal5`, `glpqPortal00`..`04` | `5weapon0`..`5weapon4` |
| `610030600` | `next00` at (1809,274); seven `sp`; six `h##_#` rope portals | `glpqPortal6` | `mob1` |

Two consequences. Every CWKPQ exit is named **`next00`**, so no stage of this row needs a WZ-derived portal name in the
row: `nextPortal: next00` is true on all three of these maps. And the Relic room's five reactors are named
`5weapon0`..`5weapon4`, so `cwkpq-sigils` could address that room by name too - it addresses by id because the row's
`HIT_REACTOR` objectives carry the ids and the shuffle cannot change them.

## The reload flake: one hypothesis tested and falsified

`BotConversationFlowTest` fails only inside a whole-suite run (`/tmp/opq-cwkpq3.log` one failure,
`/tmp/opq-cwkpq4.log` two) and passes 22/22 alone. The obvious suspect is its `private static int nextPlayer = 2001`,
whose comment says "sessions are static: every test talks to a fresh pair" - so the first thing tried was resetting it
to 2001 in the class's own `@BeforeEach`. That made it **worse**: 13 of 22 failed, because the ids are static keys
into static session maps and reusing them collides with the sessions an earlier test left behind. The hypothesis is
therefore falsified and the reset is reverted, not shipped. What is established: the dependence is on state another
test leaves, not on this class's own counter; the class is green alone and green in the exclusion run.

## CWKPQ verdict: no missing primitive, so the keep is a build and not a wall

The earlier line in this report - "every exit a portal script reading the flag rather than a warp the orchestrator can
take" - was wrong, and it was wrong three times over. Checked in the order the owner asked, with the quotes:

1. **Is there a step that sends a bot through a portal, and does it run the portal's script?** Yes, two, and the
   distinction is the whole answer. `SolverStep.EnterPortal(String portalName)` (`SolverStep.java:201`) is dispatched at
   `StepExecutor.java:157` to `enterPortal` (`:1140-1165`), which resolves `map.getPortal(name)`, walks to it within
   20 px x / 60 px y, rate-limits 1.5 s and calls `GCMovement.enterPortal(bot, portal)` - logging
   `"portal X on N is still shut"` when it refuses. That refusal is `PqGates.open` inside `GCPortals.enter`: a
   **scripted** door with side effects stays shut for a bot's plain crossing. The step that runs the script is
   `SolverStep.ScriptedPortal(String)` (`SolverStep.java:190-199`), dispatched at `StepExecutor.java:158` to
   `scriptedPortal`, which goes through `scripting.portal.PortalScriptManager.runPortalScript` - the entry point
   `KpqStaging.java:242` already uses. CWKPQ's exits are all scripts, so `ScriptedPortal` is the step, and it exists.
2. **Does `clear.eventProperty` accept a string?** It compares strings: `StageView.java:272-273` is
   `value.map(v -> v.trim().equals(clear.equals()))`. A YAML `equals: 5` is read as the string `"5"`, which is exactly
   what `scripts/reactor/6109016.js:5` writes with `setIntProperty`.
3. **What does the door out of `610030400` need?** `scripts/portal/glpqPortal4.js:4` gates on `glpq4` being at least
   five. No member count, no door counter, and `mob0`/`glpq1` is the door off `610030100`, not this one. Five hits on five
   sigils is the whole requirement.

So the keep's first stage is a row stage today, and `CwkpqSigilSolversTest` drives it: a mini row parses with
`clear: { eventProperty: glpq4, equals: 5 }`, five members take the five sigils (`CwkpqSigilSolver.SIGILS`,
`6109016`..`6109020`, assigned round robin over the party's own sorted ids), the fourth hit does not clear it, the fifth
does, and a lit room never hits again - `6109016.js:5` is **unguarded**, so a sixth hit leaves `glpq4` at 6 where
`== 5` could never match, which is why the solver waits once the counter is lit. `CwkpqSigilSolver` (`cwkpq-sigils`) is
registered in `Orchestrator.allSolvers()`.

What that does **not** buy is a `content/cwkpq.yaml`, and the reason is arithmetic rather than a gap: a row's last
playable stage ends the run `"cleared"`, so a one-stage row would announce a finished CWKPQ after the sign-up. The
remaining work is three solvers, not one primitive - a door-throughput stage (`glpq3`/`glpq3_p` count five members
through `glpqPortal2`), the five-reactor `glpq5` room, and the flames `6109021`+ which answer `touch()` and need
`TouchReactorNamed` - plus the boss on `610030800` as ordinary `boss-phases` work. That is a build session, and this
report's own rule (no stage claimed that is not driven) is why the row does not ship tonight with one stage in it.

## Ellin PQ and CWKPQ: what each still needs, costed rather than guessed

Neither is written. Both are fully read, and the pieces are named so the next hand does not have to re-derive them.

**Ellin PQ** (`scripts/event/EllinPQ.js`, 4-6 players `:27`, level 44-255 `:28`, 30 minutes `:37`, front door `2133000`,
adventurer-only at `:93` as a party-formation fact, door step `930000000`, stages `930000100`..`930000700`, clear at
`930000800`). Two primitives it needs, both general:

| Primitive | Why | Shape |
|---|---|---|
| a clear that reads a **reactor's state** | stage 2's pond reactor `3002000` and spine `3009000` at state 4, with no event property involved | `clear: { reactorState: { id: 3009000, state: 4 } }` - `StageView.reactorState(int)` already exists and is in use by Henesys PQ and `magatia-climb` |
| a step that **uses an item on a mob** | `UseItem(2270004)` on `9300174`, twenty times, to make `4001169` | `SolverStep.UseItemOnMob(int itemId, int mobId)`, executed as the client's own use-item packet against the mob (`InventoryHandler`-side path), with the drop arriving through the normal `Fight`/`Loot` flow |

Then the row: stages 1-4 driven (`mapEmpty` for the first room - `clear: { mapEmpty: true }`, and Ellin is now its only
intended consumer -,
`reactorState` for the pond, `fight-room`/`Loot` for the rest), stage 5 (`930000500`, shuffled reactors) held with the
reason named, stage 6 the Poison Golem `9300182` clearing on the instance's flag (`:230-241`).

**CWKPQ** (`scripts/event/CWKPQ.js`, four sign-up reactors and their job map `:98-135`). Two pieces, as agreed: the
**touch step** (`SolverStep.TouchReactor(int)`, because `touch`/`untouch` are dispatched only from
`TouchReactorHandler.java:34-46`) and **`RunMember.job`** (one component, one construction site,
`MechanicsRuns.java:162`, carried from the real `Character`, never inferred from persona). They must land together: the
touch step's first real consumer is the sign-up, and the job is what decides which reactor a member belongs at.

## The one flaky test, and what it actually was

`BotConversationFlowTest.aRefusedPartyAskIsPlannedFromTheGatesVerdict` failed in loaded full-suite runs and passed
alone. It was never a race in the product and never a static leak: the test read `asked.get(0)` - the **first** prompt the
shared fake brain received in that test - and asserted that it was the party plan. The reply path may queue more than one
brain call for one wave of chat (a greeting for the player, then the party ask), so under load the greeting landed first
and the test inspected a HELLO plan while the verdict it wanted sat at index 1.

The fix is in the selector, not in timing: `partyAsks()` returns the calls whose system prompt carries
`"Answer their party ask"`, the test waits for exactly one of those, and `assertEquals(1, plans.size())` now pins the
invariant that matters - **one** plan per party ask, other calls being chat rather than plans. No sleep, no retry, no
ordering hack, no filter flag. Proved by two clean full runs: `8609 tests, 0 failures, 0 errors, 137 skipped`, `EXIT=0`,
zero `<<< FAILURE` markers, `/tmp/opq-flake-A.log` and `/tmp/opq-flake-B.log`.

Two earlier hypotheses were falsified on the way and are recorded so nobody repeats them: resetting the class's
`private static int nextPlayer` in `@BeforeEach` fails 13 of 22 (those ids key static session maps, so reuse collides with
sessions an earlier test left behind), and a cross-class static leak is impossible as configured - `pom.xml:252-255` sets
`forkCount 1` with `reuseForks false`, so each test class gets its own JVM.

## BossRush's map inventory, as the client actually has it (and where the row stops)

The report's layout line (`970030100 + band + 500*s + 100*k`, `[D]`) does not describe this client. Read out of
`Map.wz/Map9`, the honour of a full inventory instead:

- **28 blocks of twelve waves**: `97003<bb><ww>` for `bb` = `00`..`27` and `ww` = `00`..`11`. Block `00` is special (its
  `00`..`09` and `20` are the hub, the entry and six rest spots `970030001`-`970030006`); every other block is twelve
  waves, `01` = `970030100` which is the event's own `entryMap` (`scripts/event/BossRushPQ.js:29`).
- **The waves do not advance the way the report reads them.** `scripts/portal/raid_stage.js:26-32` is
  `if (mapId % 500 >= 100) next = mapId + 100; else next = 970030001 + floor((mapId-970030100)/500)` - the `+100` walks
  the *block* digit, not the wave digit, so a five-block window (`970030100` -> `...200` -> `...300` -> `...400` ->
  `...500`) ends at the window's rest spot, and the bands are the windows (`detectTeamLobby` picks band 0..7 from the
  party's level, `scripts/npc/9000037.js:40-70`, and the starter calls `em.startInstance(i)` for `i < 8`, `:163-170`).
- **Every map the row could name exists**: all 28 blocks × 12 waves are in WZ, plus `970030000`-`970030020` (hub, entry,
  rest spots) and the four `9700327xx` blocks the finals live in.

**The band -> window arithmetic, read out of both files.** `scripts/npc/9000037.js:162-166` is the whole of it:

    var lobby = detectTeamLobby(eli), i;
    for (i = lobby; i < 8; i++) {
        if (em.startInstance(i, cm.getParty(), cm.getPlayer().getMap(), 1)) break;
    }

so the band is *literally the instance index*, retried upward until a free lobby answers. `BossRushPQ.js:136` stores it
(`eim.setProperty("lobby", lobbyid)`) and `:146-148` warps to `entryMap + eim.getIntProperty("lobby")`, i.e.
**`970030100 + band`**, which is why the client has twelve waves at every block: `970030100`..`970030111`. A band's five
waves are therefore `970030100 + band + 100*k` for k = 0..4, and `raid_stage.js:26-32` sends the fifth
(`(970030100 + band + 400) % 500`, which is 400+b and so ≥ 100 only for b ≤ 99 - always) one step further to
`970030001 + floor((…-970030100)/500)` = **`970030001`**, the same rest spot for every band.

**The rows are generated and committed** - eight of them, `mechanics/content/bossrush0.yaml`..`bossrush7.yaml`, five waves
plus the rest spot each, the rest spot carrying `loopTo: 1` so the set repeats and the five-minute clock ends the run.
`BossRushRowTest` is the generator's assertion rather than a transcription of what someone typed: the stage count, every
emitted map id, `mapEmpty` on every wave, `loopTo: 1` on the last stage, and that no two bands share a wave map (the rest
spot is deliberately shared, and the test says so). Test-side rather than build-side because the repo has no code
generator and a checked-in YAML only a generator can reproduce rots the first time it is hand-edited.

**The integrity pass is clean**: `tools/ops/map-integrity --wz … --map <41 ids>` reports 41 maps scanned, zero portals to a
missing map, zero portals whose `tn` the target lacks, zero mobs/NPCs/reactors without WZ entries, zero bad return maps,
zero maps with no string name, `EXIT=0`.

## A stage set that repeats: `loopTo:`

BossRush PQ is the one event whose stages are not a sequence (`/root/work/PQ-UNFINISHED-REPORT.md:438-462`): its door
(`scripts/portal/raid_stage.js:28`) sends the party to the next wave while the map is empty, and the run ends when the
five-minute clock does - there is no `clearPQ`, no property, no NPC. A row of N stages could not say that, because every
stage was terminal-on-clear and the orchestrator ended a run at its last playable stage.

So the primitive is a stage field rather than a solver: `loopTo: <stage index>` on the last stage of the set makes the
orchestrator send the run **back** to that stage instead of forward, in the same branch that used to end the run. The
clock is what ends such a run, and it ends it the way any over-time run ends - with the budget's own reason in the
history. `LoopStageTest` (3) pins it: a row without the field keeps advancing (every other row on this branch), the
field parses and refuses a forward or negative index, and a played row goes `1, 2, 1, 2, 1` and **never** reports
`cleared`, ending on its own one-minute clock instead.

**The row is not written yet.** Its layout is `970030100 + band + 500*s + 100*k` per the report, and generating 8 bands of
waves from that needs every emitted map checked against the client (`-Dwz-path` integrity), which is the next turn's whole
job rather than a line at the end of this one. The primitive is the part that had to exist first.

## GuildQuest: both blockers are settled, and the pattern was never server-side

Part 5 item 6 asks two questions of the scripts. Both are answered, and neither answer is "unknown".

**The same-guild gate is satisfiable.** `scripts/event/GuildQuest.js:97-113` takes `guildId` from the *leader*
(`:104-112`), then keeps members whose `getMapId() == recruitMap`, level in band and `getGuildId() == guildId`
(`:116-124`). Playerbots hold real guilds (`BotGuilds.java:63`, `onBotLogin:325-349`, `attach:350-352`, `join:399-436`,
`refill:1082`), so the gate is a party-assembly fact, not a wall.

**The pattern is published, not painted.** The statues do the publishing: `scripts/npc/9040009.js:106-116` empty
`stage1combo` and fires one `delayedHitReactor(client, 5000 + 3500*i)` per statue; each statue's `act()`
(`scripts/reactor/9208000.js:34-51`) appends its **own object id modulo 1000**, zero-padded to three digits, and when the
string reaches `3 * (stage + 3)` characters it flips `stage1status` to `active` and clears `stage1guess`. During
`active` the same `act()` appends every statue a player hits (`:53-62`), and the Gatekeeper compares
`stage1combo === stage1guess` (`9040009.js:79`). So the answer to "is the pattern readable" is yes: it is an event
property, in full, after the display finishes - no screenshot, no guessing.

**What it still needs** (so nobody starts this blind):

| Needed | Why | Size |
|---|---|---|
| a **by-object-id** reactor step | the combo names statues by `objectId % 1000`, and `StepExecutor.hitReactorAt` resolves by `getReactorById` only; `HitReactorNamed` resolves by WZ name | ~1 step + 1 executor branch + `StageView` accessor, the same shape as `TalkNpcAt` was for Magatia's 26 books |
| a **queue-started** run | this is the real blocker: `scripts/npc/9040000.js:73-91` registers the guild with `em.isQueueFull()` / `getQueueSize()`, and joining goes through `findLobby(cm.getPlayer().getGuildId())` - an instance this engine's `MechanicsRuns` cannot enter, because it starts instances with `EventManager.startInstance` and never joins a queue | the largest piece in Part 5 after ZakumPQ; it is a start-path seam, not a row |
| five stage rows | Sharenian Ruins' stages, all keyed on `Nstageclear`-style properties | follows the two above |

Not built, and the reason is structural rather than a missing fact: the second row of that table is the whole item.

## Boss rooms that take minutes to produce a target: `graceMs:`

`BossPhasesSolver` used to hold one global rule: a summoned room with nothing the party may hit for 30 s was a room that
could never be cleared, so the run ended (its own comment at `BossPhasesSolver.java:37-40`). P15 in
`/root/work/PQ-UNFINISHED-REPORT.md` names the four bosses where that is wrong: Hard Will's, Hard Lucid's, Hard
Darknell's and Hard Verus' first target arrives 60, 120, 120 and 240 seconds after the summon, behind scripted waiting -
a 30-second grace ends fights that had not started yet.

So the grace is the stage's own: `graceMs:` on a stage, 0 meaning "the solver's 30 s", read by
`BossPhasesSolver.graceMs(StageView)` and quoted in the abandon line's seconds. `LateSpawnGraceTest` pins the four
behaviours: a row without the field keeps 30 s, a row with `graceMs: 240000` gets four minutes, the solver reads the row
and not the constant, and a summoned room inside its grace **waits** while past it the run ends with the row's own grace
named.

**No shipped row names `graceMs:` yet, and that is honest rather than accidental:** the four rows it exists for are blocked
on the other half of the same finding - their spawns are missing from the scripts, which is not ours to fix - so the
primitive ships with its test and its first consumer arrives with those rows. The alternative, naming it on a row whose
first target is never late, would make the field look exercised when it is not.

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
- **Pirate PQ's keys and medals come out of the SQL drop tables.** `4001117` and the three medals are the instance's
  exclusives, so they drop from `9300114`/`9300115`/`9300116`/the deck's monsters through `mobdrops` - a row there is
  invisible to every test in this branch. A missing row costs the run a `Wait` note naming the item, not a wrong clear.
- **Pirate PQ's row says 40 minutes; the script says four, restarted per stage.** `PiratePQ.js:39` is `eventTime = 4`,
  and `changedMapInside` (`:241-268`) restarts it at six minutes on each of the four middle maps and eight on the boss.
  The row's number is the whole-run budget `Orchestrator.budgetFor` uses, so it has to be the sum, not the script's
  first value; a stage that stalls still ends by the script's own timer, not ours.
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

`mvn -o test` on this worktree, whole suite, **no `-Dtest` filter**: **8612 tests, 0 failures, 0 errors, 137 skipped,
BUILD SUCCESS, `EXIT=0`, zero `<<< FAILURE` markers** (`/tmp/opq-loop.log`). That is the number this report treats as the
suite's proof. Two runs before it were also clean at 8609 each, which is what proved the `BotConversationFlowTest`
selector fix rather than asserting it (`/tmp/opq-flake-A.log`, `/tmp/opq-flake-B.log`, both `EXIT=0`).

The ladder, so the next number is comparable: 8612 (the `loopTo:` primitive) -> 8609 (the flake fix, twice) -> 8605 (CWKPQ row + door + boss) -> 8601 (`/tmp/opq-nocf.log`, which excluded
one class) -> 8597 (`/tmp/opq-ellin4.log`, Ellin six of six) -> 8592 (`/tmp/opq-cwkpq2.log`) -> 8590 -> 8586 -> 8585 ->
8582 -> 8577 -> 8573 -> 8567 -> 8562 -> 8552 -> 8541 -> 8532 -> 8529 -> 8515.

**The `BotConversationFlowTest` flake is still open, with three hypotheses tested.** It failed in two earlier full runs
(`/tmp/opq-cwkpq3.log` 8593/1, `/tmp/opq-cwkpq4.log` two before a SIGTERM) and passes 22/22 alone.

1. *Its own static id counter leaked.* Resetting `private static int nextPlayer = 2001` in the class's `@BeforeEach`
   made it worse - 13 of 22 failed - because those ids key static session maps and reusing them collides with sessions an
   earlier test in the same class left behind. Falsified, reverted.
2. *Another test class leaves static state behind.* Impossible as stated: `pom.xml:252-255` is `forkCount 1` with
   `reuseForks false`, so every test class gets its own JVM. Confirmed empirically: `soloMapling.ArtificialPlayer.*Test`
   on its own is **694 tests, 0 failures, 8 skipped** (`/tmp/opq-bisect1.log`).
3. What is left is timing inside the class: it drives replies through `CompletableFuture` with
   `solomapling.chat.typingScale=0.01` and asserts on what a session said, so a full-suite run (many forked JVMs, a loaded
   machine) can read the session before the reply lands. The fix is to await the futures the test already controls rather
   than to sleep or retry - not done, and the reason the number above is reported with the caveat rather than as "the
   flake is fixed".

`tools/ops/map-integrity --wz /root/work/SlumsMS-152-maps/wz --map 610030300,610030400,610030500,610030600,610030800`
reports the keep's five maps clean except one **pre-existing** client-data defect that this row does not touch:
`610030800 out00` points at `610030010`'s portal `in00`, which does not exist.

## The Zenumist twin is not buildable: its stage-3 counter is never written

The twin looked like a copy with different ids, and the ids do differ as expected (`MagatiaPQ_Z.js:25-31,37`: entry
`926100000`, exit `926100700`, recruit `261000011`, 4 players, level 71-255, 45 minutes; front door
`2112004`; books `2112007` keyed on `cm.getNpcObjectId() % 26`; files to `2112006`; Yulete `2112000` summoning
`9300139`/`9300140` and writing `statusStg7` 1/2). One id is not different, it is **missing**:

- `scripts/portal/rnj2_out.js:2` refuses the way on until `getIntProperty("statusStg3") == 3`.
- `scripts/reactor/2619000.js:27-31` - the Zenumist crystal that stands where the Alcadno beaker stands - has
  `function hit() { rm.dropItems(); }` and an `act()` whose whole body is a comment. It never touches the counter.
- A sweep of every `statusStg3` writer in `scripts/` finds exactly one that raises it: `2618000.js:32`, the Alcadno
  beaker. The other hits are the `-1`/`0` seeds and two unrelated events (Orbis, Amoria, Ludi).

So stage 3 of the Zenumist PQ cannot be passed **by a player either** - it is a defect in the Z scripts, not a gap in this
engine, and the row is not written. The costed copy (`magatiapqz`, `92610xxx` band, the same six solvers) is one small
change away the day `2619000.js` counts the way `2618000.js` does; writing it now would ship a row that stalls at stage 3
with a "held" note that blames our engine for the script's missing line.
