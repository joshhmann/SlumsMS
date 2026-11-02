# Orbis PQ, ticket 164 - report (branch `164-opq`, 1.6.4)

Worktree `/root/work/164-opq`, reset onto `0488c420e` (the `ex-2` auto-QA base) so the boss lane's engine is underneath.
Nothing pushed, nothing merged, live server untouched. The whole row is behind its **own** switch: a run starts only with
`-Dslums.mechanics=true` **and** `-Dslums.mechanics.opq=true`, so the Kerning PQ lanes behave exactly as they did.

Nothing in the two stages this ticket was asked to unblock is behind that switch, or behind any switch: the lobby and the
sealed room already work for a player who walks in, and this ticket changed neither - it added the two tests that keep them
working and the paragraph below that says why they were reported broken.

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
| `OrbisPqLobbyStageTest` (4 tests) | `src/test/java/scripting/reactor/OrbisPqLobbyStageTest.java` | the lobby: today's pass on the music player writes `statusStg3 = 0`, another day's matches nothing |
| `OrbisSealedRoomAreaTest` (2 tests) | `src/test/java/server/maps/OrbisSealedRoomAreaTest.java` | the sealed room: three platforms count their own players, and without them the stage has no answer at all |

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
| 3 lobby | 920010400 | out of the row - **the stage works**, see below | nothing blocks it; the row has no solver for "drop today's pass on the music player, then hit `stone3`" |
| 4 sealed room | 920010500 | out of the row - **the stage works**, see below | nothing blocks it; the row has no solver that splits the party over the three platforms |
| 5 lounge | 920010600 + 601-604 | in the row (as 601) | - |
| 6 on the way up | 920010700 | in the row | - |
| 7 garden | 920010800 | **out** | it is where Papa Pixie (`9300039`) is fought for the Root of Life (`4001055`) the statue base takes (`scripts/reactor/2006001.js`), and the only way in is the statue stage's `warpEventTeam(920010800)` once the six scars are full - so it sits downstream of the statue, not blocked on its own. What it needs beyond that is the Nependeath-pot rule (`scripts/reactor/2001000.js`, `2001001.js`), which our scripts state in full. |
| 8 statue | 920010100 | **out** | six pieces must be in the six scars before Eak lets the team into the garden (`scripts/npc/2013001.js:11-19`). Four of the six already come out of the row (`4001044`, `4001045`, `4001048`, `4001049`); `4001046` and `4001047` come with stages 3 and 4, which work. The stage itself is then walk up, drop each piece on its scar, drop the Root of Life on the base - the mechanic this ticket added. |

So the last stage the row drives is **stage 5, the five levers**; clearing it opens the box that holds
the piece for `scar2` (`scripts/npc/2013001.js`, map `920010700`), and the run then ends with the party still inside the tower
holding four of the six pieces - `4001044` (walkway), `4001045` (storage), `4001048` (lounge) and `4001049` (levers). That is a
designed stop, not a stall inside a stage, and it is why the row is off by default.

Two of the four stages left out are **not blocked by anything in the tree** - both were reported broken and both work, see
the next section. They are out because a solver has not been written for them: the lobby wants "walk to the music player, drop
the day's pass in its box, then hit `stone3`", and the sealed room wants "one bot on each of the three platforms". Neither
needs a new engine feature - `PlaceItem` and `HitReactorNamed` above already cover both.

## The two stages we stopped at, verified rather than assumed

Both were reported as broken for humans and both are fine; the two tests below are what settles it, and the numbers are from
our own WZ, not from upstream.

| Claim | What the code actually does | Evidence |
| --- | --- | --- |
| "nothing writes `statusStg3 = 0`" | the reactor the pass is dropped on is **named `music`** (id `2008006`), not one of the `lp` boxes; its script is exactly `rm.getEventInstance().setProperty("statusStg3", "0")` | `scripts/reactor/2008006.js`; map reactors of `920010400` in `Map.wz/Map/Map9/920010400.img.xml` (2008006 `music`, 2002011 `stone3`, 2002004-2010 `lp`) |
| "the day is never armed" | `setup()` arms it: `getReactorByName("music").setEventState(new Date().getDay())` | `scripts/event/OrbisPQ.js:162-163` |
| "one slot per reactor, so only Sunday's pass can match" | a map's reactors are built by `ReactorFactory.getReactor` (`ReactorFactory.java:127-200`), which keeps **every** `event` child, so state 0 has seven slots and `getReactItem(day)` is `4001056 + day`; the one-slot builder is `getReactorS`, used only by `CustomReactor` | `MapFactory.java:406` (map path) vs `CustomReactor.java:67`; `Reactor.wz/2008006.img.xml` state 0..7, 7 slots each |
| "the drop always activates it" | `MapleMap.activateItemReactors` (`MapleMap.java:2664-2679`) needs the item **and** the quantity to equal the armed slot and the drop to sit inside the reactor's own box, so another day's pass matches nothing and does nothing | `Reactor.getArea()` (`Reactor.java:391`), `ReactorStats.getReactItem` (`ReactorStats.java:114`) |
| "`areas` is always empty, so three players can never be counted" | `MapFactory` fills them: it reads the map's `area` nodes and calls `map.addMapleArea(...)`, which is `MapleMap.addMapleArea` (`MapleMap.java:3684`), the writer of the same list `getArea`/`getNumPlayersInArea` read | `MapFactory.java:273-279`; the three rects in `Map.wz/Map/Map9/920010500.img.xml` imgdirs 1-3 |

Two tests keep it that way, and both fail if the chain breaks:

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

## Checks run

`mvn -o test` on this worktree, whole suite, nothing excluded: **8461 tests, 0 failures, 0 errors, 137 skipped, BUILD
SUCCESS**. That is the number to compare against the rest of the release - 8455 before this branch's six new tests
(`OpqSolversTest` 14, `OrbisPqLobbyStageTest` 4, `OrbisSealedRoomAreaTest` 2 are part of the 8461).

Earlier in the branch the same command found one real breakage, and it is fixed: `MechanicsDataFilesTest
.theContentRowsLoadFromTheJarWithNoSourceTree` pins the exact list of content rows and had to learn `opq`.
