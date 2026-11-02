# Orbis PQ, ticket 164 - report (branch `164-opq`, 1.6.4)

Worktree `/root/work/164-opq`, reset onto `0488c420e` (the `ex-2` auto-QA base) so the boss lane's engine is underneath.
Nothing pushed, nothing merged, live server untouched. The whole row is behind its **own** switch: a run starts only with
`-Dslums.mechanics=true` **and** `-Dslums.mechanics.opq=true`, so the Kerning PQ lanes behave exactly as they did.

## What ships

| Piece | Where | Why it was needed |
| --- | --- | --- |
| `Content.flag` + `Mechanics.enabledFor(Content)` | `soloMapling/mechanics/MechanicsModel.java`, `Mechanics.java`, `MechanicsRuns.java:354` | the row's own switch, so a half-covered party quest cannot be turned on by the flag that turns KPQ on |
| `ObjectiveKind.PLACE_ITEM`, `Objective.reactor` | `MechanicsModel.java`, `StageData.java` | "put the item on the ground inside this reactor": the altar, the music player, the statue base and the levers all need it, and `MapleMap.activateItemReactors` matches item **and** quantity |
| `SolverStep.PlaceItem` + `StepExecutor.placeItem` | `SolverStep.java`, `StepExecutor.java` | walks to the reactor's own position and drops `count` as **one stack** inside its box |
| `SolverStep.HitReactorNamed` | `SolverStep.java`, `StepExecutor.java` | OPQ's levers are reactors named `"1"`..`"5"` (`getReactorByName("" + i)`), not ids |
| `Loot(0)` = the whole floor | `LiveStageView.floorDrops` | the storage room clears on `countItems() == 0`, and `countItems()` counts mesos too |
| `StageView.reactorState(String)` | `StageView.java`, `LiveStageView.java` | the levers' on/off lives in reactor state, the target in `stage6_c` |
| `content/opq.yaml` (5 stages) | `soloMapling/mechanics/content/opq.yaml`, listed in `index.txt` | entrance, walkway, storage, a lounge room, the levers - script stages 0, 1, 2, 5, 6, in our engine's 1..5 |
| 4 solvers: `opq-clouds`, `opq-sweep`, `opq-subroom`, `opq-levers` | `soloMapling/mechanics/Opq*Solver.java`, registered in `Orchestrator.allSolvers()` | each is one script's own clear rule, nothing else |
| `OpqSolversTest` (14 tests) | `src/test/java/soloMapling/mechanics/OpqSolversTest.java` | the fake room + the shipped row's shape |

The walkway and the lounge reuse the existing `kill-collect-hand-in` solver: both stages are "farm N, pool them on the
leader, he hands them to Eak" (`haveItem(4001050, 30)` / `haveItem(4001052, 40)`), which is exactly what that solver does
for KPQ's passes.

## Where the run stops, and which stage it is

The row covers **five of the script's nine stages**, and it stops where the tower stops being buildable in this tree:

| Script stage | Map | Status | The exact blocker |
| --- | --- | --- | --- |
| 0 entrance | 920010000 | in the row | - |
| 1 walkway | 920010200 | in the row | - |
| 2 storage | 920010300 | in the row | - |
| 3 lobby | 920010400 | **out** | the stage needs the event property `statusStg3` to become `0` before Eak's clear check, and **no script in our tree ever sets it**: `scripts/event/OrbisPQ.js:121` writes `-1` and `scripts/npc/2013001.js:122` only reads it. Our lobby reactors `scripts/reactor/2002005.js` and `2002007.js` are bare `act() { rm.dropItems(); }`. So the lobby is unclearable for a human too, not just for bots - a port gap against upstream, whose `AgentOpqCoordinator.tickLobby` places the day's LP on the music player. |
| 4 sealed room | 920010500 | **out** | the clear needs `cm.getMap().getNumPlayersInArea(i)` over three platform areas; `MapleMap.getNumPlayersInArea` (`src/main/java/server/maps/MapleMap.java:4487`) reads `areas`, and the only writer, `MapleMap.addArea` (`MapleMap.java:3685`), has **no caller anywhere in `src/main/java`**. `areas` is always empty, so the "exactly 3 players on these platforms" check can never pass. |
| 5 lounge | 920010600 + 601-604 | in the row (as 601) | - |
| 6 on the way up | 920010700 | in the row | - |
| 7 garden | 920010800 | **out** | it needs the sixth and seventh statue pieces, which only stage 3 and stage 4 hand out, and its boss (Papa Pixie 9300039) is spawned by a rule our scripts do not state. Nothing about it can be driven without those two stages first. |
| 8 statue | 920010100 | **out** | `clearPQ()` and `statusStg8=1` happen only when the six scars are full (`scripts/npc/2013001.js:11-19`), so six pieces are needed and the same two blocked stages own two of them. |

So the last stage the row drives is **stage 5, the five levers**; clearing it opens the box with the sixth piece and the
run then ends with the party still inside the tower, holding five pieces. That is a designed stop, not a stall inside a
stage, and it is why the row is off by default. The two blockers above are the only things between this and the whole
tower: nothing else in stages 3-4-7-8 needs a new engine feature, only the pieces those two stages hand out.

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

## Entry: what starts an Orbis run

The run is armed by the generic seam, not by anything Orbis-specific: every party-question instance start calls
`MechanicsRuns.onInstanceStarted(eim, false)` (`src/main/java/scripting/event/EventManager.java:647`, and again at `:723`),
and `contentFor` matches the row by the event script's name `OrbisPQ` (`MechanicsRuns.java:377-388`). So the five stages
are played by the bots the moment an `OrbisPQ` instance exists - and the row's own flag is what decides whether it is.

What does **not** exist is the way in: `KpqStaging` (`src/main/java/soloMapling/mechanics/KpqStaging.java:28-35`) is
Kerning City only - it spawns the bots next to Lakelis at `RECRUIT_MAP 103000000`, forms the party and talks to Lakelis -
so with Orbis the party still has to be assembled by a human leader (or by the existing party-follow) before the instance
starts. That gap is entry, not stage logic, and it is the largest thing this ticket did not do.

## Open risks

- The walkway hand-in assumes Eak stands on `920010200`: `scripts/npc/2013001.js` has a `920010200` case, and
  cosmic_agents' `WALKWAY_EAK` is a point on that map, so the NPC has to be map data - [INFERENCE] from those two, not
  verified against WZ here.
- `MobHitboxIndex`-style geometry is untouched: the solvers only move to points the data gives them, so a stage whose
  anchor is wrong will stall visibly (`Wait` notes) rather than teleport.

## Not done on purpose

No new dependency store, no second config file, no table, no client asset. The row is one YAML line in `index.txt` and
the switch is one system property. `docs/PROVENANCE.md` has the credit block for cosmic_agents `3c7e09435`.
