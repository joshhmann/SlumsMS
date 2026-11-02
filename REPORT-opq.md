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
| `RowRunSimulationTest` (7 tests) | `src/test/java/soloMapling/mechanics/RowRunSimulationTest.java` | a whole row end to end through the real orchestrator: Orbis PQ's eight stages, Zakum's arm-then-body chain, Horntail's three rooms, and a stage whose rule is never met |

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

`mvn -o test` on this worktree, whole suite, nothing excluded: **8493 tests, 0 failures, 0 errors, 137 skipped, BUILD
SUCCESS** (`/tmp/opq-fixes.log`, exit 0). That is the number to compare against the rest of the release - 8455 before this
branch's tests, 8461 after its first commit (`OpqSolversTest` 14, `OrbisPqLobbyStageTest` 4,
`OrbisSealedRoomAreaTest` 2), 8479 once the lobby, the sealed room and the finale were added (`OpqSolversTest` 32), 8486
with the whole-row simulation (`RowRunSimulationTest` 7), 8491 with the revive pause (`RevivePauseTest` 5), and 8493 once
the corrections above added their two phase tests (`BossPhasesSolverTest` 15, `HorntailPhasesTest` 9).

Two `mvn` runs in this lane died mid-flight and neither was a test failure: one because a `bash` call is auto-backgrounded
and killed at 60 s (a whole-suite run needs ~27 minutes and `async: true, timeout: 0`), and one because the tree was
edited while surefire was discovering tests (`TestEngine with ID 'junit-jupiter' failed to discover tests`). Freeze the
tree for the whole run.

Earlier in the branch the same command found one real breakage, and it is fixed: `MechanicsDataFilesTest
.theContentRowsLoadFromTheJarWithNoSourceTree` pins the exact list of content rows and had to learn `opq`.
