# Orbis PQ, ticket 164 - report (branch `164-opq`, 1.6.4)

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

The walkway and the lounge reuse the existing `kill-collect-hand-in` solver: both stages are "farm N, pool them on the
leader, he hands them to Eak" (`haveItem(4001050, 30)` / `haveItem(4001052, 40)`), which is exactly what that solver does
for KPQ's passes.

## Stage coverage: 9 of 9 script stages

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

## Entry: what starts an Orbis run

The run is armed by the generic seam, not by anything Orbis-specific: every party-question instance start calls
`MechanicsRuns.onInstanceStarted(eim, false)` (`src/main/java/scripting/event/EventManager.java:647`, and again at `:723`),
and `contentFor` matches the row by the event script's name `OrbisPQ` (`MechanicsRuns.java:377-388`). So the eight stages
are played by the bots the moment an `OrbisPQ` instance exists - and the row's own flag is what decides whether it is.

What does **not** exist is the way in: `KpqStaging` (`src/main/java/soloMapling/mechanics/KpqStaging.java:28-35`) is
Kerning City only - it spawns the bots next to Lakelis at `RECRUIT_MAP 103000000`, forms the party and talks to Lakelis -
so with Orbis the party still has to be assembled by a human leader (or by the existing party-follow) before the instance
starts. That gap is entry, not stage logic, and it is the largest thing this ticket did not do.

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

## Not done on purpose

No new dependency store, no second config file, no table, no client asset. The row is one YAML line in `index.txt` and
the switch is one system property. `docs/PROVENANCE.md` has the credit block for cosmic_agents `3c7e09435`.

## Checks run

`mvn -o test` on this worktree, whole suite, nothing excluded: **8479 tests, 0 failures, 0 errors, 137 skipped, BUILD
SUCCESS** (`/tmp/opq-final.log`, exit 0). That is the number to compare against the rest of the release - 8455 before this
branch's tests, 8461 after its first commit (`OpqSolversTest` 14, `OrbisPqLobbyStageTest` 4,
`OrbisSealedRoomAreaTest` 2), and 8479 once the lobby, the sealed room and the finale were added (`OpqSolversTest` 32).

Earlier in the branch the same command found one real breakage, and it is fixed: `MechanicsDataFilesTest
.theContentRowsLoadFromTheJarWithNoSourceTree` pins the exact list of content rows and had to learn `opq`.
