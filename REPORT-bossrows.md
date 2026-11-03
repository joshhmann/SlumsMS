# Boss rows, round 1 (branch `164-bossrows`)

Seven single-room boss rows and the one engine fix they need. Every number below comes from our scripts or from the WZ
dump; the last section says exactly where that dump comes from and what that costs.

## The engine fix: a row can say its room is legitimately empty

`BossPhasesSolver` ends a run when the room has carried no `ARM` and no `BODY` part for
`NOTHING_TO_HIT_GRACE_MS = 30_000` (`BossPhasesSolver.java:40`, the branch at `:138-141`). Every id in these seven rows
is a role `ADD`: `LiveStageView.partRole` returns `ARM`/`BODY` only for Zakum and Horntail parts and `ADD` for
anything else listed in `stage.mobs()`. So `nothingToHit()` was true from the first tick of every one of these fights,
and each row abandoned itself 30 s after its instance started - before a boss that is the map's own life (or the
script's setup spawn) had even appeared.

The decision is now driven by what the row says can still appear, not by the target list:

```yaml
    spawnWaitSeconds: 60
```

- `Stage` carries `spawnWaitSeconds` (`MechanicsModel.java`), parsed and validated in `StageData` (non-negative, and it
  needs `mobs:` - it is a statement about targets that have not appeared yet).
- `BossPhasesSolver.allowedEmptyMs(stage)` is `max(30 s, spawnWaitSeconds * 1000)`; the abandon line names the window it
  used ("has been alive for 60 s after the summon").
- Absent or 0 keeps today's behaviour exactly: an empty room with no declared expectation still abandons at the grace
  deadline, which is what stops a dead instance from burning its clock.

Fail-before evidence, in the order it was seen:

1. `mvn -q -o test -Dtest='BossPhasesSolverTest,RowRunSimulationTest'` failed to compile: `cannot find symbol method
   spawnWaitSeconds()` - the tests below existed before the field did.
2. With the field in place and the rows still missing, the same command failed with eight
   `the row must be listed in content/index.txt` errors and one NPE from `MechanicsRuns.contentFor("TD_Battle2")`.
3. The new solver tests failed on the old `NOTHING_TO_HIT_GRACE_MS` comparison (`Abandon` where a `Wait` was expected).

The tests that pin it:

| Test | What it pins |
| --- | --- |
| `BossPhasesSolverTest.aRoomThatDeclaresItsSpawnWaitIsNotAbandonedAtTheGrace` | a Dunas stage with `spawnWaitSeconds: 60` and nothing on the map: `Wait` at 1 s, `Wait` at 31 s (past the old deadline), `Wait` at 46 s, `Abandon` at 61 s |
| `BossPhasesSolverTest.aRoomWithNoDeclaredSpawnWaitStillAbandonsAtTheGrace` | a Zakum stage with no declaration: `Abandon` at the 30 s grace |
| `RowRunSimulationTest.aDunasRoomThatStandsEmptyForFortyFiveSecondsIsNotAbandonedAtThirty` | a real `Orchestrator` run whose Dunas spawns at t = 45 s, i.e. 15 s past the old deadline: still `cleared` |
| `StageDataTest.aSpawnWaitIsAStatementAboutMobsTheRoomDoesNotHaveYet` | the field parses; 0 is the default; negative and mob-less declarations are errors |
| `BossRunSeamTest.aTdBattleInstanceStaysInertWhileTheBossSwitchIsOff` | a `TD_Battle2` party instance arms nothing with `-Dslums.mechanics.boss` unset, and logs nothing |
| `BossRunSeamTest.aTdBattleInstanceArmsARunForItsBotsWhenTheSwitchIsOn` | the same instance inside the switch is a run: the row's `kind` decides, not which `startInstance` overload was used |

## `killTarget` was deleted, not wired

`killTarget` had exactly four references (`MechanicsModel`, `StageData`, `content/zakum.yaml`, `HorntailPhasesTest`) and
no reader. Wiring it into the phase ordering was the other option and I rejected it: the only thing ordering could use
it for is "fight this id before that one", which `mobs:` order already says, and "this is the run's target", which
`clear:` already says. That would have made a third way to state the same fact and put a second piece of dead data on
top of the first, which is what the ticket forbids. The one meaning it carried - "the part whose death the script turns
into the clear" - is already expressed by `clear:`; the temporal half of it ("the room may stand empty until the boss
arrives") is what `spawnWaitSeconds` now says.

Removed: the `Stage` component and its javadoc bullet (`MechanicsModel.java`), the parse block and error message
(`StageData.java`), `killTarget: 8800002` and its comment (`content/zakum.yaml`), the assertion
`assertEquals(0, stage.killTarget(), ...)` (`HorntailPhasesTest.java:140`), and the sentence in
`docs/tickets/130-bots-play-mechanics.md:544`.

## The seven rows

All seven are one stage, one map, `solver: boss-phases`, and `clear: { instance: true }` - none of the seven scripts
writes an event property the row could compare (they seed `boss: "0"` and never rewrite it, which under a presence test
would clear the instance on its first tick). All seven declare `spawnWaitSeconds: 60`, a policy number (a respawn
animation plus a map instance's build, with margin), never a WZ one; each yaml says so next to the value.

### `dunas.yaml` - Dunas (TD Battle 2)

- **Script:** `scripts/event/TD_Battle2.js`.
- **Gates as the script writes them:** `minPlayers = 1, maxPlayers = 6` (`:27`), `minLevel = 70, maxLevel = 255`
  (`:28`), `maxLobbies = 1` (`:41`), `eventTime = 10` (`:39`). No NPC, item or quest gate.
- **Maps:** entry and stage `240070303` (`:29`, `:34-35`); exit and recruit `240070302` (`:30-31`).
- **Kill target:** `8220010` (`tdBossId`, `:37`). **Spawn source:** the map instance's own life -
  `Map.wz/Map/Map2/240070303.img.xml`, `mobTime -1` at `(-119,254)`. Nothing in `scripts/` or `src/` spawns it, and
  `Mob.wz/8220010.img.xml` has no `info/revive`, so it is one form.
- **Entry:** `scripts/portal/TD_Boss_enter.js:4-5` names the event from the map the portal stands in (`240070302` gives
  `TD_Battle2`) and `:20` calls `em.startInstance(party, map, 1)`. A party, not an expedition: the human party leader's
  click is the only requirement.
- **Clear:** `clearPQ` -> `eim.setEventCleared()` with no property (`:225-228`). Timeout and `playerExit` both
  `changeMap(240070302, 0)` (`:30`), which is the row's `exitMap`.
- **Simulation:** `RowRunSimulationTest.aBotPartyKillsDunasAndTheInstanceClearsItself`.

### `aufheben.yaml` - Aufheben (TD Battle 3)

- **Script:** `scripts/event/TD_Battle3.js`.
- **Gates:** `1..6`, `70..255` (`:27-28`), `maxLobbies = 1` (`:41`), `eventTime = 15` (`:39`).
- **Maps:** `240070403` (`:29`, `:34-35`); exit and recruit `240070402` (`:30-31`).
- **Kill target:** `8220011` (`:37`). **Spawn source:** the map's own life, `Map.wz/Map/Map2/240070403.img.xml`,
  `mobTime -1` at `(-2889,653)`; `Mob.wz/8220011.img.xml` has no revive.
- **Entry:** the same portal script from `240070402`. **Clear:** `clearPQ` (`:225-228`), instance flag.
- **Simulation:** `RowRunSimulationTest.aBotPartyKillsAufhebenAndTheInstanceClearsItself`.

### `oberon.yaml` - Oberon (TD Battle 4)

- **Script:** `scripts/event/TD_Battle4.js`.
- **Gates:** `1..6`, `70..255` (`:27-28`), `maxLobbies = 1` (`:41`), `eventTime = 15` (`:39`).
- **Maps:** `240070503` (`:29`, `:34-35`); exit and recruit `240070502` (`:30-31`).
- **Kill target:** `8220012` (`:37`). **Spawn source:** the map's own life, `Map.wz/Map/Map2/240070503.img.xml`,
  `mobTime -1` at `(201,234)`; no revive in `Mob.wz/8220012.img.xml`.
- **Entry:** the same portal script from `240070502`. **Clear:** `clearPQ` (`:225-228`), instance flag.
- **Simulation:** `RowRunSimulationTest.aBotPartyKillsOberonAndTheInstanceClearsItself`.

### `bergamot.yaml` - Bergamot (TD Battle 1)

- **Script:** `scripts/event/TD_Battle1.js`.
- **Gates:** `1..6`, `70..255` (`:27-28`), `maxLobbies = 1` (`:41`), `eventTime = 10` (`:39`).
- **Maps:** `240070203` (`:29`, `:34-35`); exit and recruit `240070202` (`:30-31`).
- **Kill targets and the revive chain:** `7220003` -> `7220004` -> `7220005`, listed in that order in one stage.
  **Spawn source:** `7220003` is the map's own life (`Map.wz/Map/Map2/240070203.img.xml`, `mobTime -1` at
  `(570,392)`); `7220004` and `7220005` are WZ `info/revive` (`Mob.wz/7220003.img.xml` `revive/0 = 7220004`,
  `Mob.wz/7220004.img.xml` `revive/0 = 7220005`, `Mob.wz/7220005.img.xml` none), consumed by `Monster.killBy`
  (`server/life/Monster.java:903-963`), which schedules the child on the `die1` animation, copies position, foothold
  and drops, and calls `eim.reviveMonster`. Nothing in `scripts/` or `src/` spawns any of the three. `7220005` is the
  script's `tdBossId` (`:37`).
- **Entry:** the portal script from `240070202`. **Clear:** `clearPQ` (`:225-228`), instance flag.
- **Simulation:** `RowRunSimulationTest.aBotPartyWalksBergamotsReviveChainAndTheInstanceClearsItself` - the run kills the three ids in revive
  order, and the world only ever holds one of them at a time.

### `nibelung.yaml` - Nibelung (TD Battle 5)

- **Script:** `scripts/event/TD_Battle5.js`.
- **Gates:** `1..6`, `70..255` (`:27-28`), `maxLobbies = 1` (`:41`), `eventTime = 20` (`:39`).
- **Maps:** `240070603` (`:29`, `:34-35`); exit and recruit `240070602` (`:30-31`).
- **Kill targets and the revive chain:** `8220013` -> `8220014` -> `8220015`, in that order, one stage. **Spawn
  source:** `8220013` is the map's own life (`Map.wz/Map/Map2/240070603.img.xml`, `mobTime -1` at `(270,-511)`);
  `Mob.wz/8220013.img.xml` `revive/0 = 8220014`, `Mob.wz/8220014.img.xml` `revive/0 = 8220015`,
  `Mob.wz/8220015.img.xml` none. `8220015` is the script's `tdBossId` (`:37`).
- **Entry:** the portal script from `240070602`. **Clear:** `clearPQ` (`:225-228`), instance flag.
- **Simulation:** `RowRunSimulationTest.aBotPartyWalksNibelungsReviveChainAndTheInstanceClearsItself`.

### `maha.yaml` - Maha (quest instance)

- **Script:** `scripts/event/MahaBattle.js`.
- **Gates as the script writes them:** none. `init()` is empty (`:38`) and `minPlayers`/`maxPlayers`/`minLevel`/
  `maxLevel` are never declared - yet `minPlayers` is read at `:82` (`getEligibleParty`), `:96` (`playerRevive`) and
  `:105` (`playerDisconnected`), where an undefined value reaches `isEventTeamLackingNow`. The row therefore fills the
  gate itself and keeps the party at one.
- **Maps:** entry and stage `914020000` (`:26`, `:31-32`); exit, recruit and clear `140000000` (`:27-29`).
- **Kill target:** `9001014` (`isMaha`, `:139-142`). **Spawn source:** the map's own life,
  `Map.wz/Map/Map9/914020000.img.xml`, `mobTime -1` at `(-636,73)`; `Mob.wz/9001014.img.xml` has no revive.
- **Entry:** the quest, not a portal - `scripts/quest/21401.js:23-24` calls `em.startInstance(qm.getPlayer())`, a
  single-character instance, hence `minPlayers: 1 / maxPlayers: 1`. The quest's own bar lives in
  `Quest.wz/Check.img.xml` (`imgdir 21401`: `npc 1201002`, `lvmin 120`, prerequisite quest `21400` at state 2, job
  block `2111`, completion counts mob `9001014` once), so the row states `minLevel: 120`. It cannot express "job 2111 and
  quest 21400 done" - a starter who does not meet those never gets the instance, which is the quest's job.
- **Clear:** `monsterKilled` -> `clearPQ` -> `eim.setEventCleared()` with no property (`:134-137`, `:144-151`).
- **Simulation:** `RowRunSimulationTest.oneBotKillsMahaAndTheInstanceClearsItself`.

### `castellan.yaml` - Castellan (expedition)

- **Script:** `scripts/event/CastellanBattle.js`.
- **Gates:** `1..6` (`:29`), `1..255` (`:30`), `maxLobbies = 1` (`:41`), `eventTime = 45` (`:39`).
- **Maps:** entry and stage `800040410` (`:31`, `:36-37`); exit, recruit and clear `800040401` (`:32-34`).
- **Kill targets and the revive chain:** `9400408` -> `9400409`, in that order, one stage. **Spawn source:** the map's
  own life list is empty (`Map.wz/Map/Map8/800040410.img.xml` has an empty `<imgdir name="life">`), so `9400408` is the
  script's spawn: `setup()` builds `LifeFactory.getMonster(9400408)` and drops it on the ground at `(466,142)`
  (`:133-134`). `9400409` is its WZ revive (`Mob.wz/9400408.img.xml` `revive/0`), and it is `isCastellan` (`:227-230`).
- **Entry:** the NPC's expedition - `scripts/npc/9111058.js:35` (`ExpeditionType.CASTELLAN`), `:50`, `:173`
  (`startInstance(expedition)`). **A bot cannot lead it** (`ExpeditionBots.autoJoin` returns `List.of()` for a bot
  leader), so this row is only drivable when a human has made the expedition; the expedition minimum is 1 while
  `ServerConfig.USE_ENABLE_SOLO_EXPEDITIONS` is set, which `config.yaml` does.
- **Clear:** `clearPQ` -> `eim.setEventCleared(ExpeditionType.CASTELLAN)`, no property (`:222-225`).
- **Simulation:** `RowRunSimulationTest.aBotPartyWalksCastellansTwoFormsAndTheInstanceClearsItself`.

## Row data for the whole set

`content/index.txt` lists the seven after the twelve existing rows, and `MechanicsDataFilesTest`'s pinned id list was
extended in the same order, so a file added without its index line (or the reverse) fails.

## Not established, and what would settle it

- **The WZ dump is not version-pinned to this branch.** Every `Map.wz`, `Mob.wz` and `Quest.wz` fact in these rows
  comes from `/root/work/SlumsMS-170-evan/wz`, which belongs to a different worktree and is not tied to
  `1242af89a`/`release-1.6.4`. Per id: `Map/Map2/240070303`, `240070403`, `240070503`, `240070203`, `240070603`,
  `Map/Map9/914020000`, `Map/Map8/800040410`; `Mob/8220010`, `8220011`, `8220012`, `7220003`, `7220004`, `7220005`,
  `8220013`, `8220014`, `8220015`, `9001014`, `9400408`, `9400409`; `Quest/Check.img.xml` (`21401`). Each row's
  `sources:` names the file it relied on, so a reader can re-check against a pinned dump. If a future dump disagrees,
  the row's `mobs:`, spawn source and `minLevel` are the things to re-read.
- **Maha's undefined-`minPlayers` throw has not been observed at runtime.** The row avoids the trigger (one member, so
  `getEligibleParty`/`isEventTeamLackingNow` see a party that is not short) but a half-full or reconnecting instance
  would still walk into it. That is the script's bug, not the row's, and it is recorded in `BOSS-ROW-DATA.md`'s
  blocker list.
- **Maha's quest gate is not reproducible from the row.** `minLevel: 120` is the WZ `lvmin`; the job block `2111` and
  prerequisite quest `21400` are not expressible as row data and were not faked.
- **The five TD rows' `setup()` resets `240070203`** (a copy-paste bug in all five scripts, `:121`) - harmless for the
  row (the instance map is `24007x03`) and left alone.
- **No row needed a primitive that does not exist**, so all seven shipped. The "empty room is fine until X" objective
  turned out to be expressible as stage data (`spawnWaitSeconds`), which is the fix above.

## Suite

`mvn -o test` on `164-bossrows` at the head of this branch: **`Tests run: 8554, Failures: 0, Errors: 0, Skipped: 137`,
BUILD SUCCESS** (29 min). The seven rows' simulations are 8 of `RowRunSimulationTest`'s 22 (`party(6)` for the TD and
Castellan rows, `party(1)` for Maha); each drives the real `Orchestrator` until the run ends and asserts the run reached
`cleared`, one stage, the listed ids killed in order, no `Wait` and no `Abandon`, and every member on the stage's map.

# Boss rows, round 2 (same branch)

Round 2's list was nine bosses. Seven shipped; two are refused for a primitive this model does not have, and they are
named at the end with the primitive each needs. One engine change came with this round.

## The engine change: `hitReactors` is an ordered list
Reactors were a single `int` on a stage. Scarga's room needs two (`5511000` and `5511001`), and the order they are
clicked in is the row's business, not the solver's, so `Stage.hitReactor()` is gone and `Stage.hitReactors()` is a
`List<Integer>`, parsed from `hitReactors: [ 5511000, 5511001 ]`. The scalar spelling was **removed**, not kept
alongside: one fact, one spelling. `content/horntail.yaml` is the only other row that ever used it and it now reads
`hitReactors: [ 2401000 ]`.

The solver rule (`BossPhasesSolver.next`, the block after the summon gate):

```java
List<Integer> reactors = view.stage().hitReactors();
boolean opened = reactors.stream().anyMatch(id -> view.stageMemory().containsKey(K_REACTOR + id));
if (opened || parts.isEmpty()) {
    for (int reactorId : reactors) {
        if (view.stageMemory().putIfAbsent(K_REACTOR + reactorId, view.nowMs()) == null) {
            return new SolverStep.HitReactor(reactorId);
        }
    }
}
```

Read it as three statements. The first click happens while the room is genuinely empty (Papulatus, Krexel, Showa), and
`parts.isEmpty()` is what makes it happen at all. Once *any* of the row's reactors has been clicked, the remaining ones
are clicked even though the first reactor's boss may already be standing - that is Scarga, where Targa answers the
first click and must not stop the second click from happening. And each reactor is clicked **once per stage**, because
the reactor scripts are not guarded: `scripts/reactor/2201004.js:22-29` spawns `8500000` on every `act()` it receives,
so a second click would be a second boss on the map. Order is the row's order, first unclicked first.

The fail-before test is `BossPhasesSolverTest.aRoomThatOpensOnlyHalfItsFightIsNeverCalledCleared`: a Scarga-shaped stage
listing one reactor only, Targa's chain dying, then an empty room - the solver fights, then waits, then `Abandon`s at
`60 100 ms`, and never says `Done`. With only one reactor clicked the second half is never spawned, so a clear is
exactly what must not be claimed. Its sibling `bothOfScargasReactorsAreClickedBeforeEitherHalfIsFought` pins the order
and pins that a third click never comes.

One consequence worth writing down, because it shows up in the simulations: on the tick the click is emitted, the other
members of the party see an empty room and take the solver's idle step (`Wait(500, "nothing it may hit ...")`). That is
real - five of six bots have nothing to hit while one bot clicks - so the reactor rows' assertion is "nobody idles once
the fight has started" (`assertOpenedRoomClear`), not "nobody ever idles". Rows whose room is already populated keep the
stricter `waits == 0`.

## The seven rows

### `papulatus.yaml` - Papulatus (expedition)
- **Script:** `scripts/event/PapulatusBattle.js`.
- **Gates as the script writes them:** `minPlayers 1`/`maxPlayers 6` (`:28`), `minLevel 1`/`maxLevel 255` (`:29`). This
  is wider than `ExpeditionType.PAPULATUS`'s own `minLevel 80`; `getEligibleParty` reads the script's, so the row reads
  the script's.
- **Maps:** `220080001` (the fight), `220080000` (exit and recruit, `:31-33`).
- **Kill targets and spawn source:** `8500000` -> `8500001` -> `8500002`. **Spawn source:** the room is empty until a
  player clicks reactor `2201004` - `scripts/reactor/2201004.js:22-28` sets `summoned`, spawns `8500000` and starts the
  map monitor. The rest of the chain is WZ `info/revive` (`Mob.wz/8500000.img.xml`, `8500001.img.xml`); nothing in
  `src/` or `scripts/` spawns a form. `8500002` is `isPapulatus` (`:229-232`). `spawnWaitSeconds: 60` covers the
  click-to-spawn window, so a slow monitor does not abandon a room that is about to have a boss in it.
- **Clear:** `clearPQ` -> `eim.setEventCleared(ExpeditionType.PAPULATUS)` (`:223-227`), no property; `boss` (`:129`)
  is never rewritten, so `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyClicksPapulatusReactorAndWalksTheChainToTheClear`.
- **Bot lane:** `scripts/npc/2041024.js:35` (`ExpeditionType.PAPULATUS`), `:50`, `:173`; a bot still cannot lead an
  expedition, so a human has to have made it.

### `krexel.yaml` - Krexel (expedition)
- **Script:** `scripts/event/KrexelBattle.js`.
- **Gates:** `1`/`6`, levels `1`-`255` (`:28-29`).
- **Maps:** `541020800` (the fight), `541020700` (exit and recruit, `:31-33`).
- **Kill targets and spawn source:** `9420521` -> `9420522`. **Spawn source:** reactor `5411001`
  (`scripts/reactor/5411001.js:22` `act()` sets `summoned`/`canEnter`, `:28` spawns `9420521`, then the map monitor).
  `9420522` is the WZ revive (`Mob.wz/9420521.img.xml` `revive/0`) and the only `isKrexel` id (`:218-221`). **The
  reactor is a bare click** - it asks for no item, so `HitReactor` is enough and `PlaceItemOn` is not needed here.
- **Clear:** `clearPQ` -> `setEventCleared(ExpeditionType.KREXEL)` (`:213-216`), no property; `boss` (`:125`) never
  rewritten -> `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyClicksKrexelsReactorAndKillsBothForms`.
- **Bot lane:** `scripts/npc/3003133.js:35`, `:50`, `:173`.

### `showa.yaml` - Showa (expedition)
- **Script:** `scripts/event/ShowaBattle.js`.
- **Gates:** `minPlayers 3`/`maxPlayers 30`, levels `100`-`255` (`:29-30`).
- **Maps:** `801040100` (the fight), `801040004` (exit and recruit, `:32-33`).
- **Kill targets and spawn source:** `9400112` -> `9400113` -> `9400300`. **Spawn source:** reactor `8001000`
  (`scripts/reactor/8001000.js:22` spawns `9400112`); `9400113`/`9400300` are WZ revives
  (`Mob.wz/9400112.img.xml`, `9400113.img.xml`); `9400300` is `isTheBoss` (`:209-211`). Nothing in `src/` or `scripts/`
  spawns a form.
- **Clear:** `clearPQ` -> `killAllMonsters(801040100)` + `stopEventTimer` + `setEventCleared(ExpeditionType.SHOWA)`
  (`:196-207`), no property; `boss` is a counter that `monsterKilled` only ever reads (`:213-221`), so
  `clear: { instance: true }`.
- **The bonus map is dropped, deliberately.** `801040101` exists in the script's map range (`:36-37`) and `clearPQ`
  spawns `9400114` on it (`:204`) - but it spawns it *inside the same call that clears the instance*, and the party
  never walks there (`:196-207` has no `changeMap`). Making it a second stage would put a stage on a map the run never
  reaches and would need a kill the script performs on the player's behalf. The row says so in its header.
- **Simulation:** `RowRunSimulationTest.aBotPartyClicksShowasReactorAndWalksTheChainToTheClear`, which also asserts no
  `WarpTo[mapId=801040101]` ever happens.

### `balrog.yaml` - Balrog
- **Script:** `scripts/event/BalrogBattle.js`.
- **Gates:** `minPlayers 6`/`maxPlayers 30`, levels `50`-`255` (`:27-28`).
- **Maps:** `105100300` (the fight), `105100100` (exit and recruit, `:30-31`). `clearMap 105100301` is the script's
  `changeMap` on timeout, not a fight room.
- **Kill targets and spawn source:** `8830002` -> `8830001` -> `8830000`. **Spawn source:** the script's own setup
  (`:145-153` `spawnBalrog`): the fake `8830000`, the unsealed body `8830002`, and the left claw `8830006` which
  `releaseLeftClaw` kills after `releaseClawTime * 5000` ms (`:140-143`). `8830001` and `8830000` are WZ revives of
  what dies (`Mob.wz/8830002.img.xml` `revive/0` = `8830005`, `Mob.wz/8830006.img.xml` `revive/0` = `8830001`,
  `8830001` revives `8830004`). The left claw and the two revive forms are **not** listed: they are script-killed or
  cosmetic, and the clear counts two deaths, which the listed three supply.
- **Never targeted while fake:** `8830000` *is* listed, because the script turns it real
  (`makeMonsterReal(8830000)`, `:262-265`) and it is one of the two deaths that can clear. The invariant asserted in
  the simulation is the one the task asked for: `foughtFake` is false, i.e. no `Fight` step ever names a part the
  server reports as fake, and `BossTargetRules` is what keeps it out (`Part.fake()` is skipped before any tier is
  built). A target list containing it therefore cannot fail - it is a body nothing can hit until the script says so.
- **Clear:** `monsterKilled` counts deaths and at `count == 2` calls `clearPQ` -> `setEventCleared()` (`:250-274`).
  No property is written that the row could read, so `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyNeverFightsBalrogsFakeBodyAndTheInstanceClearsItself`.

### `scarga.yaml` - Scarga (expedition)
- **Script:** `scripts/event/ScargaBattle.js`.
- **Gates:** `minPlayers 6`/`maxPlayers 30`, levels `100`-`255` (`:29-30`).
- **Maps:** `551030200` (the fight), `551030100` (exit and recruit, `:33-34`).
- **Kill targets and spawn source:** `9420542` -> `9420543` -> `9420544` and `9420547` -> `9420548` -> `9420549`, one
  stage, two chains. **Spawn source:** the two reactors `5511000` (Targa) and `5511001` (Scarlion), each guarded by
  `getMonsterById(...) == null` before `summonBossDelayed(9420542 | 9420547, 3200, ...)`; the rest is WZ `info/revive`
  (`Mob.wz/9420542.img.xml` -> `9420543`, `9420547.img.xml` -> `9420548`). `9420544`/`9420549` are `isScarga`
  (`:192-195`).
- **Clear:** `monsterKilled` -> `defeatedBoss` is a counter, so a property clear would fire one kill early; when it
  reaches 2 the script calls `clearPQ` -> `setEventCleared(ExpeditionType.SCARGA)` (`:187-190`, `:197-210`).
  `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyClicksBothScargaReactorsAndKillsBothHalves` - asserts both reactor ids
  clicked exactly once in the row's order, each chain's own kill order, all six ids dead, and no `Abandon`.
- **Two objectives**, one per half (`targa` on `9420544`, `scarlion` on `9420549`), because `StageData` rejects a stage
  with no objective and a single `DEFEAT` on one half would describe the wrong boss.
- **Bot lane:** `ExpeditionType.SCARGA`; as with the others, a human has to lead.

### `elemental.yaml` - Elemental Thanatos
- **Script:** `scripts/event/ElementalBattle.js`.
- **Gates:** `minPlayers 1`/`maxPlayers 2`, levels `100`-`255` (`:27-28`). The script additionally refuses any member
  whose `jobNiche` is not 2 (`:101`, inside `getEligibleParty`), i.e. magicians only.
- **Maps:** `922020100` (both mobs), `220050300` (exit and recruit, `:30-32`).
- **Kill targets and spawn source:** `9300086` and `9300100`, both the map's own life (`Map.wz/Map/Map9/922020100.img.xml`,
  `mobTime -1`), no reactor, no script spawn, no revive. **`maxPlayers: 2` is the whole job gate the row can express.**
- **The magician gate is not expressible, and the row does not pretend to express it.** Bots are not filtered by job,
  so a mixed party passes the row and is refused by the script's `getEligibleParty` - the row sees the refusal as the
  instance never being created, which is a script-level outcome it cannot pre-empt. The simulation drives the fight
  with `party(2)`, which is the part the row does own.
- **Clear:** clears on the **second** death (`monsterKilled`, `:235-248`, `boss` = `killed + 1` then `clearPQ`). A
  property clear on `boss == 1` would fire after the first kill, so `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyKillsBothElementalThanatosAndTheInstanceClearsItself`.
- **Entry:** NPC `2041023` -> `startInstance(cm.getParty(), ...)` (`:60`, `:82`) - a party instance, no expedition.

### `pinkbean.yaml` - Pink Bean (expedition)
- **Script:** `scripts/event/PinkBeanBattle.js`.
- **Gates:** `minPlayers 6`/`maxPlayers 30`, levels `120`-`255` (`:27-28`).
- **Maps:** `270050100` (the fight), `270050000` (recruit), `270050300` (exit and clearMap, `:31-33`).
- **Kill targets and spawn source:** `8820002` -> `8820003` -> `8820004` -> `8820005` -> `8820006`, then `8820001`.
  **Spawn source:** the script's waves - `startWave` one second after setup (`:93-96`, `:306-313`) puts the first
  statue and the decoy on the map, and each junior's death kills its statue and spawns the next (`:233`
  `spawnJrBoss`); `8820001` is `isPinkBean` (`:219`) and the fake `8820000` is `spawned` at `:116`.
- **Never targeted:** the statues `8820019`-`8820023` and the decoy `8820000` are not in `mobs:` at all, and the
  simulation asserts it: no statue id appears in the kill list or in any step, and no `Fight` step names `8820000`.
- **Clear:** `clear: { eventProperty: defeatedBoss, equals: 1 }` - the script writes it in `monsterKilled` (`:261`),
  after the fifteenth junior kill, so the equality first becomes true exactly at the clear.
- **Simulation:** `RowRunSimulationTest.aBotPartyWalksPinkBeansWavesToTheDefeatedBossProperty`.

## Rows refused in round 2, and the primitives both asked for

Round 3 built the entry-item primitive and shipped `latanica.yaml` on it. The survive-stage half was built, then
reverted in full: `164-opq` @ `ac08ce380` already carries it as `DelliHoldSolver` + `content/dellibattle.yaml` (with an
8-minute budget) + `DelliBattleSolversTest`, and this branch will not hold a second spelling of "won by staying alive".
The record below is why neither could be written before the primitives existed.

- **`latanica.yaml`** - the only way in is portal `captinsg00`, which refuses a player without `4000381`
  (`scripts/portal/captinsg00.js:4`); the instance never re-checks it. A row could not state that its run needs the
  item, so the fight (`HitReactor` `5411000` -> `9420513`, `clear: { instance: true }`) was unreachable from a bot run.
  Wanted: "a row-level entry requirement the run can satisfy".
- **`delli.yaml`** - not shipped here; see the revert note above. The win is the script's own 6-minute
  `scheduledTimeout` (`DelliBattle.js:119-123`), and the
  friendly `9300162` ends the instance if it is killed (`:194-198`). `Objective` had no survive kind, so six minutes of
  trash-killing was all a row could express, and one stray `Fight` on the friendly would end the run. Wanted: "stay
  alive until the instance's own timer runs out" plus a per-stage never-target list.

## Row data for the whole set

`content/index.txt` lists the seven after the twelve existing rows, and `MechanicsDataFilesTest`'s pinned id list was
extended in the same order, so a file added without its index line (or the reverse) fails.

## Not established, and what would settle it

- **The WZ dump is not version-pinned to this branch.** Every `Map.wz`, `Mob.wz` and `Quest.wz` fact in these rows
  comes from `/root/work/SlumsMS-170-evan/wz`, which belongs to a different worktree and is not tied to
  `1242af89a`/`release-1.6.4`. Per id: `Map/Map2/240070303`, `240070403`, `240070503`, `240070203`, `240070603`,
  `Map/Map9/914020000`, `Map/Map8/800040410`; `Mob/8220010`, `8220011`, `8220012`, `7220003`, `7220004`, `7220005`,
  `8220013`, `8220014`, `8220015`, `9001014`, `9400408`, `9400409`; `Quest/Check.img.xml` (`21401`). Each row's
  `sources:` names the file it relied on, so a reader can re-check against a pinned dump. If a future dump disagrees,
  the row's `mobs:`, spawn source and `minLevel` are the things to re-read.
- **Maha's undefined-`minPlayers` throw has not been observed at runtime.** The row avoids the trigger (one member, so
  `getEligibleParty`/`isEventTeamLackingNow` see a party that is not short) but a half-full or reconnecting instance
  would still walk into it. That is the script's bug, not the row's, and it is recorded in `BOSS-ROW-DATA.md`'s
  blocker list.
- **Maha's quest gate is not reproducible from the row.** `minLevel: 120` is the WZ `lvmin`; the job block `2111` and
  prerequisite quest `21400` are not expressible as row data and were not faked.
- **The five TD rows' `setup()` resets `240070203`** (a copy-paste bug in all five scripts, `:121`) - harmless for the
  row (the instance map is `24007x03`) and left alone.
- **No row needed a primitive that does not exist**, so all seven shipped. The "empty room is fine until X" objective
  turned out to be expressible as stage data (`spawnWaitSeconds`), which is the fix above.

## Suite

`mvn -o test` on `164-bossrows` at the head of this branch: **`Tests run: 8554, Failures: 0, Errors: 0, Skipped: 137`,
BUILD SUCCESS** (29 min). The seven rows' simulations are 8 of `RowRunSimulationTest`'s 22 (`party(6)` for the TD and
Castellan rows, `party(1)` for Maha); each drives the real `Orchestrator` until the run ends and asserts the run reached
`cleared`, one stage, the listed ids killed in order, no `Wait` and no `Abandon`, and every member on the stage's map.

# Boss rows, round 2 (same branch)

Round 2's list was nine bosses. Seven shipped; two are refused for a primitive this model does not have, and they are
named at the end with the primitive each needs. One engine change came with this round.

## The engine change: `hitReactors` is an ordered list
Reactors were a single `int` on a stage. Scarga's room needs two (`5511000` and `5511001`), and the order they are
clicked in is the row's business, not the solver's, so `Stage.hitReactor()` is gone and `Stage.hitReactors()` is a
`List<Integer>`, parsed from `hitReactors: [ 5511000, 5511001 ]`. The scalar spelling was **removed**, not kept
alongside: one fact, one spelling. `content/horntail.yaml` is the only other row that ever used it and it now reads
`hitReactors: [ 2401000 ]`.

The solver rule (`BossPhasesSolver.next`, the block after the summon gate):

```java
List<Integer> reactors = view.stage().hitReactors();
boolean opened = reactors.stream().anyMatch(id -> view.stageMemory().containsKey(K_REACTOR + id));
if (opened || parts.isEmpty()) {
    for (int reactorId : reactors) {
        if (view.stageMemory().putIfAbsent(K_REACTOR + reactorId, view.nowMs()) == null) {
            return new SolverStep.HitReactor(reactorId);
        }
    }
}
```

Read it as three statements. The first click happens while the room is genuinely empty (Papulatus, Krexel, Showa), and
`parts.isEmpty()` is what makes it happen at all. Once *any* of the row's reactors has been clicked, the remaining ones
are clicked even though the first reactor's boss may already be standing - that is Scarga, where Targa answers the
first click and must not stop the second click from happening. And each reactor is clicked **once per stage**, because
the reactor scripts are not guarded: `scripts/reactor/2201004.js:22-29` spawns `8500000` on every `act()` it receives,
so a second click would be a second boss on the map. Order is the row's order, first unclicked first.

The fail-before test is `BossPhasesSolverTest.aRoomThatOpensOnlyHalfItsFightIsNeverCalledCleared`: a Scarga-shaped stage
listing one reactor only, Targa's chain dying, then an empty room - the solver fights, then waits, then `Abandon`s at
`60 100 ms`, and never says `Done`. With only one reactor clicked the second half is never spawned, so a clear is
exactly what must not be claimed. Its sibling `bothOfScargasReactorsAreClickedBeforeEitherHalfIsFought` pins the order
and pins that a third click never comes.

One consequence worth writing down, because it shows up in the simulations: on the tick the click is emitted, the other
members of the party see an empty room and take the solver's idle step (`Wait(500, "nothing it may hit ...")`). That is
real - five of six bots have nothing to hit while one bot clicks - so the reactor rows' assertion is "nobody idles once
the fight has started" (`assertOpenedRoomClear`), not "nobody ever idles". Rows whose room is already populated keep the
stricter `waits == 0`.

## The seven rows

### `papulatus.yaml` - Papulatus (expedition)
- **Script:** `scripts/event/PapulatusBattle.js`.
- **Gates as the script writes them:** `minPlayers 1`/`maxPlayers 6` (`:28`), `minLevel 1`/`maxLevel 255` (`:29`). This
  is wider than `ExpeditionType.PAPULATUS`'s own `minLevel 80`; `getEligibleParty` reads the script's, so the row reads
  the script's.
- **Maps:** `220080001` (the fight), `220080000` (exit and recruit, `:31-33`).
- **Kill targets and spawn source:** `8500000` -> `8500001` -> `8500002`. **Spawn source:** the room is empty until a
  player clicks reactor `2201004` - `scripts/reactor/2201004.js:22-28` sets `summoned`, spawns `8500000` and starts the
  map monitor. The rest of the chain is WZ `info/revive` (`Mob.wz/8500000.img.xml`, `8500001.img.xml`); nothing in
  `src/` or `scripts/` spawns a form. `8500002` is `isPapulatus` (`:229-232`). `spawnWaitSeconds: 60` covers the
  click-to-spawn window, so a slow monitor does not abandon a room that is about to have a boss in it.
- **Clear:** `clearPQ` -> `eim.setEventCleared(ExpeditionType.PAPULATUS)` (`:223-227`), no property; `boss` (`:129`)
  is never rewritten, so `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyClicksPapulatusReactorAndWalksTheChainToTheClear`.
- **Bot lane:** `scripts/npc/2041024.js:35` (`ExpeditionType.PAPULATUS`), `:50`, `:173`; a bot still cannot lead an
  expedition, so a human has to have made it.

### `krexel.yaml` - Krexel (expedition)
- **Script:** `scripts/event/KrexelBattle.js`.
- **Gates:** `1`/`6`, levels `1`-`255` (`:28-29`).
- **Maps:** `541020800` (the fight), `541020700` (exit and recruit, `:31-33`).
- **Kill targets and spawn source:** `9420521` -> `9420522`. **Spawn source:** reactor `5411001`
  (`scripts/reactor/5411001.js:22` `act()` sets `summoned`/`canEnter`, `:28` spawns `9420521`, then the map monitor).
  `9420522` is the WZ revive (`Mob.wz/9420521.img.xml` `revive/0`) and the only `isKrexel` id (`:218-221`). **The
  reactor is a bare click** - it asks for no item, so `HitReactor` is enough and `PlaceItemOn` is not needed here.
- **Clear:** `clearPQ` -> `setEventCleared(ExpeditionType.KREXEL)` (`:213-216`), no property; `boss` (`:125`) never
  rewritten -> `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyClicksKrexelsReactorAndKillsBothForms`.
- **Bot lane:** `scripts/npc/3003133.js:35`, `:50`, `:173`.

### `showa.yaml` - Showa (expedition)
- **Script:** `scripts/event/ShowaBattle.js`.
- **Gates:** `minPlayers 3`/`maxPlayers 30`, levels `100`-`255` (`:29-30`).
- **Maps:** `801040100` (the fight), `801040004` (exit and recruit, `:32-33`).
- **Kill targets and spawn source:** `9400112` -> `9400113` -> `9400300`. **Spawn source:** reactor `8001000`
  (`scripts/reactor/8001000.js:22` spawns `9400112`); `9400113`/`9400300` are WZ revives
  (`Mob.wz/9400112.img.xml`, `9400113.img.xml`); `9400300` is `isTheBoss` (`:209-211`). Nothing in `src/` or `scripts/`
  spawns a form.
- **Clear:** `clearPQ` -> `killAllMonsters(801040100)` + `stopEventTimer` + `setEventCleared(ExpeditionType.SHOWA)`
  (`:196-207`), no property; `boss` is a counter that `monsterKilled` only ever reads (`:213-221`), so
  `clear: { instance: true }`.
- **The bonus map is dropped, deliberately.** `801040101` exists in the script's map range (`:36-37`) and `clearPQ`
  spawns `9400114` on it (`:204`) - but it spawns it *inside the same call that clears the instance*, and the party
  never walks there (`:196-207` has no `changeMap`). Making it a second stage would put a stage on a map the run never
  reaches and would need a kill the script performs on the player's behalf. The row says so in its header.
- **Simulation:** `RowRunSimulationTest.aBotPartyClicksShowasReactorAndWalksTheChainToTheClear`, which also asserts no
  `WarpTo[mapId=801040101]` ever happens.

### `balrog.yaml` - Balrog
- **Script:** `scripts/event/BalrogBattle.js`.
- **Gates:** `minPlayers 6`/`maxPlayers 30`, levels `50`-`255` (`:27-28`).
- **Maps:** `105100300` (the fight), `105100100` (exit and recruit, `:30-31`). `clearMap 105100301` is the script's
  `changeMap` on timeout, not a fight room.
- **Kill targets and spawn source:** `8830002` -> `8830001` -> `8830000`. **Spawn source:** the script's own setup
  (`:145-153` `spawnBalrog`): the fake `8830000`, the unsealed body `8830002`, and the left claw `8830006` which
  `releaseLeftClaw` kills after `releaseClawTime * 5000` ms (`:140-143`). `8830001` and `8830000` are WZ revives of
  what dies (`Mob.wz/8830002.img.xml` `revive/0` = `8830005`, `Mob.wz/8830006.img.xml` `revive/0` = `8830001`,
  `8830001` revives `8830004`). The left claw and the two revive forms are **not** listed: they are script-killed or
  cosmetic, and the clear counts two deaths, which the listed three supply.
- **Never targeted while fake:** `8830000` *is* listed, because the script turns it real
  (`makeMonsterReal(8830000)`, `:262-265`) and it is one of the two deaths that can clear. The invariant asserted in
  the simulation is the one the task asked for: `foughtFake` is false, i.e. no `Fight` step ever names a part the
  server reports as fake, and `BossTargetRules` is what keeps it out (`Part.fake()` is skipped before any tier is
  built). A target list containing it therefore cannot fail - it is a body nothing can hit until the script says so.
- **Clear:** `monsterKilled` counts deaths and at `count == 2` calls `clearPQ` -> `setEventCleared()` (`:250-274`).
  No property is written that the row could read, so `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyNeverFightsBalrogsFakeBodyAndTheInstanceClearsItself`.

### `scarga.yaml` - Scarga (expedition)
- **Script:** `scripts/event/ScargaBattle.js`.
- **Gates:** `minPlayers 6`/`maxPlayers 30`, levels `100`-`255` (`:29-30`).
- **Maps:** `551030200` (the fight), `551030100` (exit and recruit, `:33-34`).
- **Kill targets and spawn source:** `9420542` -> `9420543` -> `9420544` and `9420547` -> `9420548` -> `9420549`, one
  stage, two chains. **Spawn source:** the two reactors `5511000` (Targa) and `5511001` (Scarlion), each guarded by
  `getMonsterById(...) == null` before `summonBossDelayed(9420542 | 9420547, 3200, ...)`; the rest is WZ `info/revive`
  (`Mob.wz/9420542.img.xml` -> `9420543`, `9420547.img.xml` -> `9420548`). `9420544`/`9420549` are `isScarga`
  (`:192-195`).
- **Clear:** `monsterKilled` -> `defeatedBoss` is a counter, so a property clear would fire one kill early; when it
  reaches 2 the script calls `clearPQ` -> `setEventCleared(ExpeditionType.SCARGA)` (`:187-190`, `:197-210`).
  `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyClicksBothScargaReactorsAndKillsBothHalves` - asserts both reactor ids
  clicked exactly once in the row's order, each chain's own kill order, all six ids dead, and no `Abandon`.
- **Two objectives**, one per half (`targa` on `9420544`, `scarlion` on `9420549`), because `StageData` rejects a stage
  with no objective and a single `DEFEAT` on one half would describe the wrong boss.
- **Bot lane:** `ExpeditionType.SCARGA`; as with the others, a human has to lead.

### `elemental.yaml` - Elemental Thanatos
- **Script:** `scripts/event/ElementalBattle.js`.
- **Gates:** `minPlayers 1`/`maxPlayers 2`, levels `100`-`255` (`:27-28`). The script additionally refuses any member
  whose `jobNiche` is not 2 (`:101`, inside `getEligibleParty`), i.e. magicians only.
- **Maps:** `922020100` (both mobs), `220050300` (exit and recruit, `:30-32`).
- **Kill targets and spawn source:** `9300086` and `9300100`, both the map's own life (`Map.wz/Map/Map9/922020100.img.xml`,
  `mobTime -1`), no reactor, no script spawn, no revive. **`maxPlayers: 2` is the whole job gate the row can express.**
- **The magician gate is not expressible, and the row does not pretend to express it.** Bots are not filtered by job,
  so a mixed party passes the row and is refused by the script's `getEligibleParty` - the row sees the refusal as the
  instance never being created, which is a script-level outcome it cannot pre-empt. The simulation drives the fight
  with `party(2)`, which is the part the row does own.
- **Clear:** clears on the **second** death (`monsterKilled`, `:235-248`, `boss` = `killed + 1` then `clearPQ`). A
  property clear on `boss == 1` would fire after the first kill, so `clear: { instance: true }`.
- **Simulation:** `RowRunSimulationTest.aBotPartyKillsBothElementalThanatosAndTheInstanceClearsItself`.
- **Entry:** NPC `2041023` -> `startInstance(cm.getParty(), ...)` (`:60`, `:82`) - a party instance, no expedition.

### `pinkbean.yaml` - Pink Bean (expedition)
- **Script:** `scripts/event/PinkBeanBattle.js`.
- **Gates:** `minPlayers 6`/`maxPlayers 30`, levels `120`-`255` (`:27-28`).
- **Maps:** `270050100` (the fight), `270050000` (recruit), `270050300` (exit and clearMap, `:31-33`).
- **Kill targets and spawn source:** `8820002` -> `8820003` -> `8820004` -> `8820005` -> `8820006`, then `8820001`.
  **Spawn source:** the script's waves - `startWave` one second after setup (`:93-96`, `:306-313`) puts the first
  statue and the decoy on the map, and each junior's death kills its statue and spawns the next (`:233`
  `spawnJrBoss`); `8820001` is `isPinkBean` (`:219`) and the fake `8820000` is `spawned` at `:116`.
- **Never targeted:** the statues `8820019`-`8820023` and the decoy `8820000` are not in `mobs:` at all, and the
  simulation asserts it: no statue id appears in the kill list or in any step, and no `Fight` step names `8820000`.
- **Clear:** `clear: { eventProperty: defeatedBoss, equals: 1 }` - the script writes it in `monsterKilled` (`:261`),
  after the fifteenth junior kill, so the equality first becomes true exactly at the clear.
- **Simulation:** `RowRunSimulationTest.aBotPartyWalksPinkBeansWavesToTheDefeatedBossProperty`.

## Refused rows and the primitives they need

- **`latanica.yaml` - Latanica: blocked on a consumable entry item.** The only way in is portal `captinsg00`, which
  refuses the player without `4000381` in hand (`scripts/portal/captinsg00.js:4`). A row cannot put an item in a
  member's inventory before the run enters the instance - `ItemUse` is evaluated inside a stage, and the stage is only
  reached by passing the gate that needs the item. The fight itself would be ordinary (`HitReactor` `5411000`, which
  `act()`-spawns `9420513`, `clear: { instance: true }`); it is only the door that is missing a primitive. The
  primitive is "a row-level entry requirement the run can satisfy" - `ItemUse` hoisted to the row, or a `WarpTo` entry
  that skips the portal script. Not faked: there is no `latanica.yaml`.
- **`delli.yaml` - Delli: blocked on a survive-the-timer objective.** `scripts/event/DelliBattle.js`: gates `1`/`2`,
  levels `120`-`255` (`:27-28`), entry map `925010300`, exit `925010200` (`:29-30`), `eventTime` 6 minutes (`:36`).
  The win is the timer expiry itself - `scheduledTimeout` kills every non-friendly monster and calls `clearPQ`
  (`:119-123`), so no death is the clear signal and the fight is the room's respawning trash
  (`instanceMapRespawn`, `:110`). Worse, the friendly `9300162` is fatal: `friendlyKilled` ends the instance if it dies
  (`:194-198`). `MechanicsModel.Objective` has no survive kind and `SolverStep` has no hold-position step; a
  `boss-phases` row would spend six minutes killing trash that is not the win condition, and one stray `Fight` on the
  friendly ends the run. The primitive is a row-level "stay alive until the instance's own timer runs out" objective
  plus a per-stage never-target list. What *is* provable is the friendly-mob half, and it is tested:
  `RowRunSimulationTest.aFriendlyMobTheRowDoesNotListNeverBecomesATarget` asserts `LiveStageView.partRole` returns
  `null` for `9300162` both when the stage lists nothing and when it lists the boss, while a listed mob comes back as
  `Role.ADD` - i.e. an unlisted friendly mob can never reach a `Fight` step.

## Row data for the whole set

`content/index.txt` lists the seven round-2 rows after round 1's, and `MechanicsDataFilesTest`'s pinned id list was
extended in the same order: `papulatus`, `krexel`, `showa`, `balrog`, `scarga`, `elemental`, `pinkbean`.

## Not established, and what would settle it

- **The WZ dump is still not version-pinned to this branch** - all round-2 map, life and revive facts come from
  `/root/work/SlumsMS-170-evan/wz`. Per id: `Map/Map2/220080001`, `Map/Map5/541020800`, `Map/Map8/801040100`,
  `Map/Map1/105100300`, `Map/Map5/551030200`, `Map/Map9/922020100`; `Mob/8500000`, `8500001`, `9420521`, `9400112`,
  `9400113`, `8830002`, `8830001`, `8830006`, `9420542`, `9420547`, `8820002`-`8820006`, `9300086`, `9300100`.
- **Balrog's revives are read, not driven.** The row lists `8830001`/`8830000` because the script reaches them; the
  left claw `8830006` and the revive forms `8830005`/`8830004` are handled by the script, so the row's kill order is
  the script's *clear-relevant* order, not every id that can appear in `105100300`. The real server's
  `Monster.killBy` revive path (`server/life/Monster.java:903-963`) is the authority; a live instance would confirm it.
- **Showa's `respawnStages` is a self-perpetuating `instanceMapRespawn` every 15 s** (`:112-116`). It puts trash back
  in the room, not the boss chain, so the row ignores it; if a future dump showed the chain respawning from that loop,
  the row's kill order would need re-reading.
- **Elemental's job gate is a script refusal the row cannot see** (above). A mixed party fails at `getEligibleParty`
  and no instance exists; that is the script's behaviour, not a row bug.
- **A reactor click costs the non-clicking members one 500 ms idle tick** (above). Not a stall and not a fix; recorded
  because `assertOpenedRoomClear` exists to say so.

## Suite (round 2 head)

`mvn -o test` on `164-bossrows` with all sixteen rows in place: **`Tests run: 8565, Failures: 0, Errors: 0,
Skipped: 137`, BUILD SUCCESS** (29:28). Round 2's simulations are 8 of `RowRunSimulationTest`'s 30 (seven rows plus the
friendly-mob proof); `BossPhasesSolverTest` is 19 tests (the two Scarga reactor tests and the two spawn-wait tests among
them) and `StageDataTest` is 12 (the `hitReactors` parse among them).

Every row simulation drives the real `Orchestrator` through the real content loader: the run reaches `cleared`, one
stage, the row's ids killed in the row's order (Scarga: each chain's own order, since the halves interleave), no
`Abandon`, and every member on the stage's map. Rows whose room is already populated also assert zero `Wait` steps; the
reactor rows assert none after the fight starts, for the reason given above.

# Boss rows, round 3 (same branch)

Round 3 built the entry-item primitive round 2 refused to fake, shipped `latanica.yaml` on it, and then the five
quest-gated bosses. It is additive: no solver learned a new step type, no portal was special-cased, and nothing in
`Orchestrator`, `StageView` or the PQ engine moved. The switch is unchanged too: all six rows are `kind: BOSS`, which is
what `MechanicsRuns.onInstanceStarted` tests against `-Dslums.mechanics.boss`, so with the flag off none of them arms
anything - the same content-independent path `BossRunSeamTest` pins both ways (the new primitive is unreachable without
a run to apply it to).

## Primitive A: a door the row says it is not through yet

**`entryItems: [ 4000381 ]`** on a stage means: before the stage's first step, at least one member of the run must hold
each listed item. The check is the first thing `BossPhasesSolver` does after the map warp and before any reactor click:

```yaml
entryItems: [ 4000381 ]   # scripts/portal/captinsg00.js:4 - the only place that asks, and it never takes it
```

- **One member, not everyone.** `captinsg00.js:4` asks the player who walks through the portal; the item is not
  consumed, and nothing inside the instance asks again (`LatanicaBattle.getEligibleParty` checks the map, the level band
  and that a leader exists, nothing else). Requiring it of all six would refuse runs the server lets in.
- **Satisfied by an existing step when there is one.** The check reads `StageView.itemCount`, so a row that collects the
  item earlier in the same stage satisfies it with no new machinery; a party that arrives carrying it satisfies it
  immediately.
- **Not satisfied is a `Wait`, and nothing else.** The solver returns
  `Wait(1_000, "the run has no entry item [4000381] in hand, so stage 1 cannot be entered: Capt. Latanica")` - it does not click the reactor, does not fight, does not emit
  `EnterPortal`, and never `Abandon`s. A dry run stays a dry run and says which item is missing, on the channel log,
  instead of standing at a portal script that will refuse it silently.
- **The declaration is checked where rows are loaded.** `StageData` rejects `entryItems: not an item id`, a
  non-positive id, and a stage that declares the same item twice.

Fail-before, at the solver: `BossPhasesSolverTest.aDoorItemTheRunDoesNotHoldIsAWaitThatNamesItAndTouchesNothingElse` -
no item gives a `Wait` naming `4000381` at tick one *and* at four grace periods; the same room with the item gives the
`HitReactor` the row was written for. Whole run:
`RowRunSimulationTest.aRunWithoutTheWhiteEssenceWaitsAtLatanicasDoorAndNeverEnters` (a full run through the real
`Orchestrator`: no `EnterPortal`, no `HitReactor`, no `Fight`, no `Abandon`, nothing killed, run not ended) and
`aRunHoldingTheWhiteEssenceOpensLatanicasRoomAndClearsIt`.

## Built and reverted: the survive stage and the never-target list

Round 3 first carried a second pair of declarations - `objective kind: SURVIVE` and a stage-level `neverTargets` - so
`boss-phases` could hold Delli's room for the script's own clock and keep the friendly `9300162` out of every `Fight`.
They worked (the solver tests and the whole-run simulation for `delli.yaml` were green), and they are gone again:
`164-opq` @ `ac08ce380` ships the same idea as `DelliHoldSolver`, `content/dellibattle.yaml` and
`DelliBattleSolversTest`, and two spellings of "won by staying alive" is worse than one. So this branch carries no
`ObjectiveKind.SURVIVE`, no `Stage.neverTargets`, no `delli.yaml`, no Delli test, and no Delli index line; the reports
below name what was removed rather than leaving it in the tree.

What remains, and is worth keeping from that attempt: `StageView` has no timer accessor, so "the instance's timer
expired" is observable only as what the script does when it expires (`scheduledTimeout` -> `clearPQ` ->
`setEventCleared`, `DelliBattle.js:119-123`, `:208-211`). A solver that kept its own stopwatch would be a second clock
able to disagree with `EventScheduler`. That is why the Delli row on `164-opq` names the server's clock and why this
branch adds none.

The general friendly-mob guarantee that is still tested here:
`RowRunSimulationTest.aFriendlyMobTheRowDoesNotListNeverBecomesATarget` - `LiveStageView.partRole` returns `null` for
`9300162` both when the stage lists nothing and when it lists other monsters, while a listed mob comes back as
`Role.ADD`. A row that stays inside its own target list can therefore never order a friendly into a `Fight`.

## The six rows

## The seven rows

### `latanica.yaml` - Capt. Latanica

- **Script** `LatanicaBattle`. **Gates as the script writes them:** `minPlayers 1`, `maxPlayers 6` (`:27`),
  `minLevel 1`, `maxLevel 255` (`:28`), `eventTime` 10 minutes (`:37`), `maxLobbies 1` (`:39`). Entry is a portal, not
  an NPC or a party script (`scripts/portal/captinsg00.js`), so the "gate" the row cannot express is the door itself -
  hence `entryItems: [ 4000381 ]`.
- **Maps** `541010100`; exit and recruit `541010110` / `541010060`.
- **Kill target** `9420513`, spawn source `scripts/reactor/5411000.js:22-25` (`act()`: `spawnMonster(9420513, -146,
  225)`), clicked by `hitReactors: [5411000]`. `Map/Map5/541010100` holds the reactor and no mobs; the mob has no
  `info/revive`.
- **Clear** `clear: { instance: true }` - `monsterKilled` -> `clearPQ` (`:233-241`, `:223-226`) sets no property. The
  reactor's own `setIntProperty("boss", 1)` is a summon flag, so the row does not clear on it.
- **Simulation** `aRunHoldingTheWhiteEssenceOpensLatanicasRoomAndClearsIt`, plus the refusal above.

### `magnus.yaml` - Magnus; `nwill.yaml`, `nlucid.yaml`, `nverus.yaml`, `ndarknell.yaml`

All five are the same shape, so they are listed together:

| row | script | gates | map / exit | target | spawn source | clear |
| --- | --- | --- | --- | --- | --- | --- |
| `magnus.yaml` | `MagnusBattle` | 1/6, 200-256, 120 min, 1 lobby | `401060100` / `401053002` | `8880002` | `:91-92`, `:120-125` at 5 s | `clearPQ` `:206-209`, no property |
| `nwill.yaml` | `NWillBattle` | 1/6, 200-256, 120 min, 1 lobby | `450007441` / `450007240` | `9990301` | `:116-117`, `:145-150` at 5 s | `clearPQ` `:231-234`, no property |
| `nlucid.yaml` | `NLucidBattle` | 1/6, 200-256, 120 min, 1 lobby | `450004751` / `450003600` | `9990150` | `:115-116`, `:144-149` at 5 s | `clearPQ` `:238-241`, no property |
| `nverus.yaml` | `NVerusBattle` | 1/6, 200-256, 120 min, 1 lobby | `450010101` / `450011660` | `9990415` | `:116-117`, `:145-150` at 5 s | `clearPQ` `:231-234`, no property |
| `ndarknell.yaml` | `NDarknellBattle` | 1/6, 200-255, 120 min, 1 lobby | `450012211` / `450012600` | `9995009` | `:116-117`, `:145-150` at 5 s | `clearPQ` `:245-248`, no property |

- **Clear** `clear: { instance: true }` on all five: `setEventCleared` writes no property, and `boss` is seeded `"0"`
  and never rewritten (a property clear would fire on tick one).
- **No `spawnWaitSeconds`**: the script's own `spawnMob1` is the source and lands at 5 s, inside the solver's 30 s
  grace; declaring a wait would only hide a regression in that.
- **Never listed**: `8880000` (Magnus's fake) is not in `magnus.yaml`; `WZ` holds none of these five targets as map
  life, so nothing can stand in the room before the script's spawn.
- **Simulations** (the 5 s spawn is queued by the fake world exactly as `spawnMob1` does):
  `aBotPartyKillsMagnusFiveSecondsAfterSetup`, `aBotPartyKillsWillFiveSecondsAfterSetup`,
  `aBotPartyKillsLucidFiveSecondsAfterSetup`, `aBotPartyKillsVerusFiveSecondsAfterSetup`,
  `aBotPartyKillsDarknellFiveSecondsAfterSetup`.

## The quest gate on those five, and why the rows ship anyway

The gate is a quest-state check on the character - `31166` for Magnus, `31162` / `31161` / `31164` / `31163` for Will,
Lucid, Verus and Darknell - and it lives in two places, neither of which a run can influence: the expedition recruiters
the human talks to, and, for the bots, `ExpeditionBots.memberQuest(ExpeditionType)`, which skips a bot that has not
done the quest with the reason `quest` (`:217-228`, `:283-284`). Nothing in `EventManager.startInstance` re-checks it,
and no script inside the instances does either.

That makes it a **row precondition, not a row gate**: a party whose human has the quest gets a working boss, and a human
who does not is refused at the recruiter, where they can see it. Writing quest state for a bot is out of the question,
and the row declares no level trickery to fake it - the level band (`200`-`256`, `200`-`255`) is the script's own and
stays.

What this means for the proof: the fight half is simulated in full (the five tests above), and the entry half is
**unproven by construction** - `StageView` has no view of a quest state, so no simulation can be made to fail or pass on
it. It is recorded here rather than pretended.

## Row selection: a script one row owns, or a map that decides

GuardianNex forced the arming seam to get precise, and the rule is small: **one row for a script name wins outright,
whatever map the instance is on; several rows mean the map the instance was built on decides; no evidence, or evidence
that fits none or two of them, arms nothing and says so once.** `MechanicsRuns.contentFor(String, Collection<Integer>)`
is that rule, fed by `EventInstanceManager.instanceMapIds()` (a new getter over the map ids the script itself asked for
through `getInstanceMap`, `:1020-1025`), and `onInstanceStarted` uses it. Every row but GuardianNex's six is a single
row per script, so nothing about them changed - which the seam tests pin one by one (`ZakumBattle`, `TD_Battle2`, and
`contentFor(String)` still answers for every shipped row).

Fail-before, `BossRunSeamTest.aScriptWithSeveralRowsIsSelectedByTheMapTheInstanceWasBuiltOn`, with two synthetic rows
sharing `eventScript: GuardianNex` on maps `240070010` and `240070020`: before the change the instance built on
`240070020` armed the *first* row (`expected: <240070020> but was: <240070010>`), after it arms its own, and an instance
on a third map (`240070090`) arms nothing and logs `2 rows claim this script and none of them is built on this
instance's map [240070090], so no row is armed rather than the wrong one`.

The row's contribution is only that it says which map it is: `stage.mapId()`. When the `164-all-merge` tree lands, the
match should be widened to the row's whole `mapSet()` - a one-line change here, not a second seam.

## The six GuardianNex caves

`scripts/event/GuardianNex.js` is one script for six caves: `minPlayers 1` (`:1`), `timeLimit 15` (`:2`),
`exitMap 240070000` (`:4`), `eventMap 240070010` (`:5`), `eventBossIds` = `[7120100, 7120101, 7120102, 8120100,
8120101, 8140510]` (`:6`). `setup(difficulty, lobbyId)` (`:10-19`) builds instance `Nex_<lobbyId>` and resets the cave
that lobby names, `playerEntry` (`:25-28`) warps to `eventMap + 10 * nex`, and the clear is that cave's own boss dying
(`monsterKilled` `:99-103` -> `clearPQ` `:94-97`, `stopEventTimer` + `setEventCleared`, so `{ instance: true }`).

| Row | Map | Boss | Quest | Level | Simulation |
| --- | --- | --- | --- | --- | --- |
| `guardiannex1.yaml` | `240070010` | `7120100` | `3719` | 70 | `eachOfGuardianNexSixCavesArmsItsOwnRowAndFightsItsOwnBoss` |
| `guardiannex2.yaml` | `240070020` | `7120101` | `3724` | 73 | same test |
| `guardiannex3.yaml` | `240070030` | `7120102` | `3730` | 77 | same test |
| `guardiannex4.yaml` | `240070040` | `8120100` | `3736` | 81 | same test |
| `guardiannex5.yaml` | `240070050` | `8120101` | `3742` | 85 | same test |
| `guardiannex6.yaml` | `240070060` | `8140510` | `3748` | 90 | same test |

- Gates as the script writes them: `minPlayers 1`, 15 minutes, exit `240070000`. `maxPlayers` is nowhere in the script
  and the instance takes one character (`startInstance(i, player)`), so the rows say `1`/`1` like `maha.yaml`.
- Entry: portal `scripts/portal/TD_neo_inTree.js`, which takes the first quest of `[3719, 3724, 3730, 3736, 3742, 3748]`
  the player has *active* (`:8-13`), refuses a quest that already has progress on its mob (`:13-16`), and starts the
  instance (`:18`). Level ladder and NPC `2082004` from `Quest.wz/Check.img.xml`.
- Spawn source: the map's own life, one entry per cave, `mobTime -1`
  (`Map.wz/Map/Map2/240070010`-`240070060.img.xml`); no reactors, no script spawn, and `Mob.wz/<id>.img.xml` has no
  `info/revive` for any of the six. So no `spawnWaitSeconds`: the room never waits on an arrival.
- Clear: `monsterKilled` -> `clearPQ` -> `setEventCleared`, no property the row could read.
- The quest is a **precondition, never written**: no run touches quest state, so a starter without the quest is refused
  at the portal where the player sees it. The rows state this cave's `minLevel` (70/73/77/81/85/90) and say in the
  header that the quest id is the real door.
- The simulation walks all six caves through the real `Orchestrator`, asserting each run arms the row for its own map
  and kills only that cave's boss - which is the whole point of the selection change.

## Content defects found, not ours to fix

Two boss scripts are machines for a fight that cannot start, because the *map* is missing the first link. That is not a
row's problem and not something the content engine can paper over - `SolverStep` has no spawn (`BOSS-ROW-DATA.md` B1),
and the pair is exactly why.

- **Cygnus (`scripts/event/CygnusBattle.js`).** `setup` (`:118-138`) creates instance `Cygnus<lobby>`, resets
  `271040100` (`:132`) and starts the timer; it spawns nothing, and `respawnStages` is empty (`:140`).
  `Map/Map2/271040100.img.xml` holds one life entry, NPC `2143004`, and no mobs. The Cygnus chain lives in
  `MapleMap.afterBossKilled`: a guard `8850000`-`8850003` dying spawns `8850011` (`:1609-1618`), `8850004` dying spawns
  `8850012` (`:1620-1626`), and `beforeBossDamage` (`:1546`) continues the phases after that. So the machine only
  *continues* a chain that must already be alive, and nothing in the script, the map or `src/` puts a guard or
  `8850011` there. The working path today is the rebirth system: `RebirthBosses.CYGNUS` (`:53-54`, `summonMob 8850011`
  at `(-104, 115)`) driven by `RebirthBossRuns.fight` (`:591`, the spawn at `:605`
  `room.spawnMonsterOnGroundBelow(r.boss.summonMob, r.boss.summonX, r.boss.summonY)`).
- **Von Leon (`scripts/event/VonLeonBattle.js`).** Same shape: `setup` (`:120-138`) names the instance, resets
  `211070100` (`:132`) and starts the timer, spawning nothing; `Map/Map2/211070100.img.xml` holds one life entry, NPC
  `2161005`, and no mobs. `MapleMap.afterBossKilled:1594-1600` turns `8840000`'s death into `8847777`, and `8847777` is
  the script's clear (`isVonleon` `:241-244`, `monsterKilled` `:249-252`); nothing spawns `8840000`. Working path:
  `RebirthBosses.VONLEON` (`:51-52`, `summonMob 8840000` at `(49, -181)`) through the same `RebirthBossRuns.fight`.

Neither would become a row even with a spawn step: they need the *party* to place the boss, which is what
`RebirthBossRuns` already does. Recorded so that "Cygnus and Von Leon are blocked" reads as a map fact, and so nobody
"fixes" it by having a row summon a boss into a room the script never fills.

## A row-data integrity test, against the dump

`src/test/java/soloMapling/mechanics/ContentRowWzIntegrityTest.java` reads every row that `content/index.txt` names
(`StageData.load()`) and checks each number against the dump: the stage map is a `Map.wz/Map/Map<k>/<id>.img.xml`, every
`mobs` id is `Mob.wz/<id>.img.xml`, every `hitReactors` id is a `Reactor.wz` entry, every `entryItems` id appears as
`name="<%08d>"` in its `Item.wz/<category>/<%04d>.img.xml` bag, and no stage declares a negative `spawnWaitSeconds`
(zero is the model's "no expectation", so it cannot be required to be positive).

It points at the dump with the switch every other WZ test already uses - `-Dwz-path`, else `wz`, through
`WzTestData.assumePresent` - so on a machine without `wz/` it skips (reported skipped, never failed) and the suite stays
green. Run here against `/root/work/SlumsMS-170-evan/wz`: **38 rows, 59 stage maps, 89 mobs, 7 reactors, 1 door item**,
all present; no typo was found, so no row changed.

- maps: `103000800`-`103000804`, `105100300`, `190000000`, `191000000`, `192000000`, `195000000`, `196000000`,
  `197000000`, `220080001`, `240060000`, `240060100`, `240060200`, `240070203`, `240070303`, `240070403`, `240070503`,
  `240070603`, `270050100`, `280030000`, `401060100`, `450004751`, `450007441`, `450010101`, `450012211`, `541010100`,
  `541020800`, `551030200`, `800040410`, `801040100`, `910010000`, `914020000`, `922020100`, the six GuardianNex caves
  `240070010`-`240070060`, and the PQ sets `920010000`/`920010100`/`920010200`/`920010300`/`920010400`/`920010500`/
  `920010601`/`920010700` plus `922010100`-`922010900` - 59 in all, the exact set the test prints
- reactors: `2201004`, `2401000`, `5411000`, `5411001`, `5511000`, `5511001`, `8001000`
- door items: `4000381`
- mobs: the 89 ids across the rows, from `7120100` through `9995009` (the test prints the full set).

## Row data for the whole set

`content/index.txt` lists, in round order: round 1's seven, round 2's seven, round 3's six (`latanica`, `magnus`,
`nwill`, `nlucid`, `nverus`, `ndarknell`), then round 5's six (`guardiannex1`..`guardiannex6`) - **thirty-eight content
rows, twenty-eight of them `kind: BOSS`**. `MechanicsDataFilesTest`'s pinned id list carries the same order. Both files
take additions **appended at the end, in that order**, which is the order the integration on `164-opq` should keep: the
two files conflict on their shared tail, and the resolution is this list after whatever `164-opq` has added.

## Not established, and what would settle it

- **The WZ dump is still not version-pinned to this branch** (third round running). Round 3's map and mob facts come
  from `/root/work/SlumsMS-170-evan/wz`: `Map/Map5/541010100` (reactor `5411000`, no mobs), `Map/Map4/401060100`
  (NPC `3004006` only),
  and `Map/Map4/450007441`, `450004751`, `450010101`, `450012211` (empty life); `Mob/9420513`, `8880002`, `9990301`,
  `9990150`, `9990415`, `9995009` all with no `info/revive`.
- **Round 3's map files disagree with themselves in the dump.** `Map/Map0/401060100.img.xml` is a 25 KB stub while
  `Map/Map4/401060100.img.xml` is the real one; a version-pinned dump would say which the client loads. Nothing in the
  row depends on it (the mob is spawned by the script, not by map life).
- **Delli is not on this branch at all** (section above). `164-opq` @ `ac08ce380` owns the room, its 8-minute budget
  and `DelliBattleSolversTest`; nothing here should be merged over it.
- **`NLucidBattle.js` defines `afterSetup`, `respawnStages` and `playerEntry` twice** (`:151-157` and `:159-165`,
  identical bodies, the second wins). Harmless as written; recorded because a future edit to the first copy would look
  applied and not be.
- **The entry half of the five quest rows is unproven** (section above). A hand-run on a character with the quest
  would settle it; nothing in this suite can.

## Suite (round 5 head)

`mvn -o test` on this head: **`Tests run: 8577, Failures: 0, Errors: 0, Skipped: 138, BUILD SUCCESS`**. Round 4 closed
at 8575; round 5 adds the selection fail-before in `BossRunSeamTest` and the six-cave simulation in
`RowRunSimulationTest` (two tests). `ContentRowWzIntegrityTest` is the 138th skip on a machine without the dump - with
`-Dwz-path=/root/work/SlumsMS-170-evan/wz` it runs and reports **38 rows, 59 maps, 89 mobs, 7 reactors, 1 door item**,
green, the six new caves and six new boss ids included.

# The expedition door (round 7, same branch, `-Dslums.exped.door`)

## What the friends were actually seeing

"they don't do anything, they just stand there" is not the door. A party bot follows its human leader to
`211042400`, `ExpeditionBots.autoJoin` registers it there, `EventInstanceManager.registerExpeditionTeam` accepts it
because it is standing on the recruiting map, `playerEntry` warps it to `280030000`, and inside the room nothing drives
it - the live server has no `mechanics` row for an expedition instance, and `BotDeaths.protectedFromDeath` makes the
room harmless. So the audit's gap 1 is a real hole, but it is not the cause of the standing: the bots *are* inside. The
content rows are the cause-side fix, and that is what rounds 1-5 shipped.

This round is the door around that, all of it behind one switch, off by default.

## 1. A held bot is never stranded - verified, no fix

`ExpeditionBots.register` holds a bot at create time (`ExpeditionBots.java:184`, `ExpeditionHold.hold`), which is what
keeps it from logging out mid-transition (`BotCommitments.inFlight`). The hold is released by exactly one thing:
`ExpeditionBots.release(this)`, the last line of `Expedition.dispose(boolean)` (`Expedition.java:149-159`). All three
routes reach it:

- **The registration window expires.** `scheduleRegistrationEnd()` (`Expedition.java:133-147`) fires
  `expireRegistration()` after the type's `registrationMinutes` (Zakum: 5), which removes the channel expedition and
  calls `dispose(false)`.
- **The leader can never start.** Same timer: the size check failing at the NPC (`scripts/npc/2030013.js:150-157`) does
  not stop the clock, so the expedition is disposed and the hold released at five minutes.
- **The run ends with the bot unregistered.** `EventInstanceManager.disposeExpedition()` (`:1387-1401`, reached from
  `dispose(boolean)` at `:817`) calls `expedition.dispose(eventCleared)` - past the early return, so a failed run
  releases too.

So there was nothing to fix, and no test that could fail by design. What was missing was anything pinning it: the timer
body lived inside a lambda, so it could not be called without waiting five minutes. It is now `Expedition.expireRegistration()`,
the scheduling line is a method reference, and three tests call the three routes directly:
`ExpeditionBotsTest.aBotIsLetGoWhenTheRegistrationWindowExpires`,
`aBotHeldWhileTheLeaderCannotStartIsLetGoWhenTheWindowLapses`, `aBotTheRunLeftBehindIsFreeOnceTheRunStarts`.

## 2. A party change while registering - already covered, no hook added

The premise ("a bot added to the party after the expedition was made is outside") does not hold on this branch.
`Expedition.start()` re-runs `autoJoin` inside its `if (registering)` block (`Expedition.java:198-202`), and every
recruiter step re-runs it again through `AbstractPlayerInteraction.getExpedition(ExpeditionType)`
(`AbstractPlayerInteraction.java:1205-1215`). So the start step *is* the party-change boundary, and a third hook would
be a third spelling of one call. `ExpeditionBotsTest.aBotAddedToThePartyAfterTheExpeditionWasMadeJoinsAtTheStart` pins
it - it fails without the hook (`solomapling.enabled` + `solomapling.template-id`, `MapleRootSolo.java:16-18`), which is
also the reason this looked uncovered: in a plain test JVM the hook is switched off and does nothing.

Found while proving it: `new Expedition(...)` throws on a leader whose `getName()` is unstubbed (a `ConcurrentHashMap`
does not take null), and `verify(never()).dropMessage(6, contains(...))` needs `eq(6)`.

## 3. Who is not coming

`ExpeditionBots.warnNotAtTheAltar(Expedition)`, called from `Expedition.start()` right after the autoJoin hook, walks
the bots this module registered (`ExpeditionHold.botsOf`), asks the recruiting map's player storage where each one is,
and gives the leader one line per bot that is not standing there:
`[Expedition] Clock4 is not at the altar and will not join.` It never warps anybody - a bot walking to the altar is
follower travel and already works, and a faked warp would be a second answer to the same question. Nothing is said when
there is nothing to say: `aBotAddedToThePartyAfterTheExpeditionWasMadeJoinsAtTheStart` (all present) and
`withTheDoorShutTheLeaderHearsNothingAboutIt` both assert silence.
`theStartNamesEachRegisteredBotThatIsNotAtTheAltarAndNothingElse` moves one bot off the altar and checks that exactly
one name comes out, for that bot.

## 4. A dismissed alt no longer lingers in the member list

Confirmed, and it was the real bug of the four. A dismiss goes through `OwnedAltService.finish` -> `BotEventExit.leave`
(`BotEventExit.java:23-27`), which returns immediately when the alt has no event instance and never touches
`Expedition.members`. The id stayed there until the channel disposed the expedition: a ghost in the member list, and a
`registerExpeditionAttempt` boss-log entry spent in the name of a character that had gone home.

`ExpeditionDoor.leaveExpedition(Character)` removes the character from whichever channel expedition holds it
(`exped.contains(chr)` -> `removeMember(chr)`), and `OwnedAltService.finish` calls it beside the existing
`backend.leaveEvent` (`OwnedAltService.java:328`) - the one place dismiss, dismiss-all, login and disconnect all pass
through. `ExpeditionBotsTest.aDismissedAltLeavesItsExpeditionAndAVanishedOneLeavesNothingElse` proves the exit path
alone leaves the member behind and the door removes it; `OwnedAltServiceTest.aDismissedAltIsTakenOutOfItsExpedition`
proves the wiring end to end; `withTheDoorShutADismissedAltStillLingers` pins the off state.

## The switch

`-Dslums.exped.door`, read only by `ExpeditionDoor.enabled()` (`soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionDoor.java`),
default off. It gates the two behaviour changes (3 and 4). Items 1 and 2 changed no behaviour, so there is nothing for a
switch to gate; if the owner would rather have 4 unconditional - it is a correction, not a preference - the call site is
one line in `OwnedAltService.finish`.

## Commits and suite

One commit for the four items rather than four: items 3 and 4 share the new `ExpeditionDoor` class and all four share
`ExpeditionBotsTest`, so a per-item split would put a tree on the branch that does not compile.

`mvn -o test`: **`Tests run: 8586, Failures: 0, Errors: 0, Skipped: 138, BUILD SUCCESS`** (30:54). Round 5 closed at
8577; round 7 adds nine tests (eight in `ExpeditionBotsTest`, one in `OwnedAltServiceTest`). The 138th skip is still
`ContentRowWzIntegrityTest` on a machine without the dump.

# What a held bot still does for itself (round 8, audit + one fix)

Question: while `MechanicsRuns` holds a bot for a run, what does it keep doing on its own, and what has it stopped?
The hold stops the bot's brain — `MechanicsRuns.holdAndSchedule` calls `BotTypeManager.manuallyStopBot`
(`MechanicsRuns.java:204-215`) — so whatever the bot's own type ran in its tick is gone unless the pipeline replaces it.
Six answers, each with what the code does and a verdict. One fix (buff upkeep), behind the boss switch.

## 1. Buffs — wrong, fixed

Who casts normally: the bot's own tick. `FollowerBot.supportParty` runs `BotPartySupport.tick` and
`BotBuffDriver.selfBuff` (`FollowerBot.java:214-219`), `TrainingBot` does the same for a grinder
(`TrainingBot.java:2164`), and `BotBuffDriver.maintain` recasts each class buff at a fraction of its WZ duration
(`BotBuffDriver.java:139-160`, `LOOK_EVERY_MS` throttle). Nothing in the run replaces it: `StepExecutor` only walks
and swings (`StepExecutor.java:269-330`), no step casts a buff (the `SolverStep` set has `Fight`, `Loot`, `Wait`,
`HitReactor`, `WarpTo`, `ScriptedPortal`, `EnterPortal`, `Abandon`, `Done`). With the brain stopped, the last buffs the
bot entered with lapse on their own timer and are never renewed — a human watches a Zakum fight where the bot's buff
visuals disappear and never come back.

Fix: `MechanicsRuns.upkeep(bots, nowMs)`, called once per run tick beside `keepPhysics`, runs exactly the loop the bot
would run (`BotBuffDriver.selfBuff(bot, now, next, habits::rebuffLateNowMs, Pay.PARTY_BUFFS, rng)`), remembered per bot
so the loop's own throttle still applies. Gated by `-Dslums.mechanics.boss` (`Mechanics.bossesEnabled()`), the same
switch that arms the row. Tests: `MechanicsRunsBuffsTest` — `aRunKeepsEachHeldBotsOwnBuffsUpWhileTheBossSwitchIsOn`,
`withTheBossSwitchOffARunCastsNothing`, `theBuffLoopIsHandedBackItsOwnTimerSoItDoesNotCastEveryTick`. Fail-before: the
method did not exist, so the test cannot compile against the old tree (the same shape of fail-before as
`spawnWaitSeconds` and `hitReactors`); there is no packet-level fail-before because a held bot had no cast path at all to
observe.

## 2. Potions — right, no change

A held bot drinks like a follower, because neither path lives in the brain:
- **HP.** Damage comes through `BotVitals.hurt` (`BotVitals.java:60-97`), which takes the hit off HP and immediately
  calls `topUp` (`:262-286`) — HP potions under 75%/50%, MP under 30%. Touch damage still reaches a held bot:
  `BotContactDamage.tickMobDamage` (`BotContactDamage.java:74-180`) is called by `GCMovementDriver.tick` *before* its
  movement branches (`GCMovementDriver.java:216`), and `GCMovement.stop` only cancels follow/travel and clears the move
  intent (`GCMovement.java:242-254`) — the movement state stays and keeps sampling mobs. Knockback and the 2.5 s
  i-frames come with it.
- **MP.** Every cast pays through `BotVitals.spend` (`:244-256`), which tops up MP potions on the spot;
  `BotAttackDriver` checks `BotVitals.canPay` before a swing (`BotAttackDriver.java:574`).

So "a bot that cannot drink potions, and would die the moment `protectedFromDeath` is removed" does not hold on this
branch: it drinks on every hit and every cast. One real caveat, LOD and not the run: on a map no real player observes,
`BotContactDamage` returns early (`isMapObserved`), so nothing hits the bot and it drinks no HP potions — the same as
for any unobserved bot, and in these runs the leader is on the map.

## 3. Death and revive — owner decision, unchanged

`BotDeaths.protectedFromDeath` (`BotDeaths.java:74-90`) returns true for any bot with an event instance, so `rollsDeath`
never fires inside a run (`:100-103`). A human next to them sees bots that do not fall, in a fight where the humans do.
What *could* revive a bot: `StatEffect.partyBuffTargets` (`StatEffect.java:1951-1985`) is the Resurrection selection and
does **not** filter bots — for a resurrection it takes the caster's party members that are not alive — so a real Bishop's
Resurrection would reach a dead bot in the party. It never gets the chance: no bot in an instance is ever dead, and a bot
that dies outside one revives on its own `lyingUntil` timer (`BotDeaths.die`, `:145+`). Nothing else revives a bot; no
revive item is routed to one.

Needs owner sanction before anyone touches it, for three reasons: it changes what a human sees (bots dying beside them
is a parity statement, not a bug fix); the death penalty would hit the owned alt's real character (EXP loss on the
player's account); and `ExpeditionDeathCount` spends the expedition's death limit on humans today, so a dying bot would
start consuming it.

## 4. Loot — right, no change

A held bot picks up nothing. `BossPhasesSolver` never emits `Loot`, and `StepExecutor.fight` picks a drop up only when
the step carries an item goal (`f.itemId() > 0 && bot.getItemQuantity(...) < f.untilItemCount()`,
`StepExecutor.java:282-289`) — a boss `Fight` has none. Pet looting is a grind behaviour: `GrindLoot` asks
`BotPets.petMayTake` (`GrindLoot.java:169`, `BotPets.java:307-317`) from the loot loop, which the hold stops. So no boss
drop, meso or otherwise, can go to a bot while a run drives it; the humans take the drops. If a row ever wants a collect
step, `Loot` is there to name one deliberately — that would be a row decision, not a bot habit.

## 5. Aggro and knockback — as-is, worth knowing

There is no aggro: each bot picks its own target (`BotAttackDriver.pickTarget`), so nothing pulls a boss onto a human —
a boss's attention follows whoever the server's own rules give it. Where a bot stands is `Approach.fightSpot`
(`Approach.java:170-177`), from `standBand` (`:67-73`): the mob's half-width plus the bot's own reach, ranged bots at
`KITE_MIN_PX`, and `standX` refuses any spot inside *another* mob's body (`:93`) so bots do not stack inside a pack.
`BossPhasesSolver` passes `Fight(order, 0, 0, band[0], band[1], ...)` with the band taken from an objective's `at`;
our rows have none, so the band is 0 and the walk comes from `Approach` alone. A human sees the bot on the boss's
flank at its own swing reach, taking touch hits and the odd knockback when the boss's touch box reaches that spot, and
walking back — never inside the boss, never dragging it. Verdict: right, with one wart — `Fight`'s own
`nearX`/`nearY`/`leashPx` are not read by `StepExecutor.fight`, so they are dead data for this solver.

## 6. The human's view — a gap, proposal only (nothing added)

What the leader is told today: `ExpeditionBots` says "X joined with you." per registered bot
(`ExpeditionBots.java:186`), the new "is not at the altar and will not join" line, and `ExpeditionBots.shortfall` when
the size check fails. Nothing anywhere says the bots are driving the fight. `EventManager.warnBossRowUnattended`
(`EventManager.java:762-776`) prints to `System.out` and is about the *absence* of a run; `MechanicsRuns`' run log goes
to the GM command or the QA reporter, not to a player. So the only signal a human gets is bots moving.

Proposition (not implemented): one line at run start, to the party leader, from `MechanicsRuns.holdAndSchedule` right
after the "RUN start" line — something like
`[Expedition] 4 bots are running this fight with you (Zakum).` — behind the boss switch, once per run. It is worth it
because "bots stand there" and "bots are driving" look identical from the player's chair, and the difference is exactly
what a bug report needs to say.

## What the audit did not find

Nothing in these six needed a primitive the row engine cannot express: an empty room is `spawnWaitSeconds`, a target
order is the row's `mobs:` list, a fake body is `Mob.fake()`/`partRole`, a revive chain is WZ `revive` plus the row's
order, and a held bot's upkeep is a call in the run's tick. The engine (`Orchestrator`, `StageSolver`, `SolverStep`,
`RoleClaims`, `BossPhasesSolver`, `BossTargetRules`, `RevivePause`) is untouched by this round; the only code change is
`MechanicsRuns.upkeep` (item 1). A second engine beside the row engine was written and thrown away during this round —
it is recorded here so nobody wonders what happened: it was reverted in full, and the reason it was wrong is that two
engines spell "this room is won", "hold this bot" and "abandon this run" twice.

## Suite and commit

One commit: `MechanicsRuns.upkeep` + `MechanicsRunsBuffsTest` + this section.
`mvn -o test`: **`Tests run: 8589, Failures: 0, Errors: 0, Skipped: 138, BUILD SUCCESS`** (31:44). Round 7 closed at
8586; this round adds three (`MechanicsRunsBuffsTest`). The 138th skip is still `ContentRowWzIntegrityTest` here, because
this machine has no `wz/` dump to check the rows against.

# Round 9 (same branch): the line a human gets, and three switches back to off

## 1. The leader is told the bots are driving (item 6, shipped)

Round 8 found the gap and, per that round's brief, proposed one line without adding it. This round adds it.

`MechanicsRuns.holdAndSchedule` now calls `tellLeader(eim, content, bots.size())` on the line after the run's own
`RUN start ...` report line — inside the branch that arms the run, so it fires once per instance and never per tick.

```
[Expedition] 4 bots are running this fight with you (Zakum).
```

`tellLeader` returns early unless all three hold: the row is `kind: BOSS`, `Mechanics.bossesEnabled()`
(`-Dslums.mechanics.boss`) is on, and at least one bot was actually taken. The count is `bots.size()` — the bots the run
holds and drives — not the party size, so two humans and two bots in a six-member Zakum party reads `2 bots`. Singular is
spelled out (`1 bot is running this fight`). The row's own name is cut at its qualifier (`shortName`: `Zakum (expedition)`
-> `Zakum`, `Dunas (TD Battle 2)` -> `Dunas`) because the leader should not have to parse our data model in chat. The
message goes only to the leader (`eim.getLeaderId()`, `dropMessage(6, ...)`, the orange notice); nobody else sees it.

Why it earns a line: the owner's own bug report was "they just stand there". A run that is driving and a run that never
started look identical from a player's chair, and the difference is exactly what a bug report has to say.

Tests (`soloMapling.mechanics.ExpeditionLineTest`):

| test | what it pins |
|---|---|
| `theLeaderIsToldOnceHowManyBotsAreDrivingTheFight` | the line appears exactly once, with the row's short name |
| `theCountIsTheHeldBotsAndNotThePartySize` | 2 bots among 6 members reads `2 bots`; the non-leader human gets nothing |
| `withTheBossSwitchOffTheLeaderIsToldNothing` | `-Dslums.mechanics.boss` unset -> no message at all |
| `aRowsQualifierIsNotReadOutLoud` | `shortName` cuts at `" ("` and leaves a plain name alone |

## 2. Three defaults moved to off

The rule is: a switch is off unless a brief says otherwise, and the release conf carries the turn-on line. Three switches
from the boss lane were on by default because they were written that way while being tested; they are now off, with the
behaviour unchanged for anyone who sets them.

| switch | read at | was | now | behaviour with it on |
|---|---|---|---|---|
| `slums.altfight.instanceleash` | `OwnedAltCombat.instanceLeashOn()` (`OwnedAltCombat.java:55`) | on | **off** | inside an event instance an owned alt reads the leash as its party's engagement rule instead of the owner's 650 px box |
| `slums.bossfight.reach` | `FollowerFight.reachGateOn()` (`FollowerFight.java:66`) | on | **off** | a follower skips a boss part it cannot get into its own swing's reach of, instead of standing and giving up |
| `slums.bossfight.revivepause` (+ `.ms`, `.ms.<row id>`) | `RevivePause.on()` (`RevivePause.java:36`) | on | **off** | a boss run holds itself for a member who died and is walking back, instead of ending on the `minPlayers` abort |

Each read is now `Boolean.getBoolean(...)`, so the switch is off when unset and only `true` (any case) turns it on. The
javadoc of each says so, `RevivePause`'s class doc says so, and `docs/tickets/130-bots-play-mechanics.md` (the
revive-pause bullet) says so.

Tests updated so they set the property they exercise instead of inheriting it: `RevivePauseTest` sets
`-Dslums.bossfight.revivepause=true` in a `@BeforeEach` (and clears it plus `.ms` in `@AfterEach`),
`FollowerFightReachTest` sets `-Dslums.bossfight.reach=true` in a `@BeforeEach`,
`OwnedAltInstanceFightTest` sets `-Dslums.altfight.instanceleash=true` in a `@BeforeEach`. The old
`assertTrue(RevivePause.on(), "... on unless a server says otherwise")` in the first test is now an assertion that *this
test* turned it on.

One new test per switch pins the default itself, so it cannot drift back:

| switch | pinning test |
|---|---|
| `slums.bossfight.revivepause` | `RevivePauseTest.theRevivePauseIsOffUnlessAServerTurnsItOn` (unset -> off, `true` -> on, `FALSE` -> off) |
| `slums.bossfight.reach` | `FollowerFightReachTest.theReachGateIsOffUnlessAServerTurnsItOn` |
| `slums.altfight.instanceleash` | `OwnedAltInstanceFightTest.theInstanceLeashIsOffUnlessAServerTurnsItOn` (also asserts an instance arm outside the box is *not* faced with the switch unset) |

The whole suite below ran with none of the three properties set.

## 3. Every switch in the boss lane, and its default

| switch | default | what it turns on |
|---|---|---|
| `slums.mechanics` | off | any mechanics run at all (PQ and boss) |
| `slums.mechanics.boss` | off | boss rows are armed; the leader line above needs it too |
| `slums.bossfight.damagefloor` | off | the guaranteed minimum hit on a high-defence boss (`BotSkillChoice.FLOOR_SWITCH`) |
| `slums.bossfight.reach` | **off** (round 9) | skip a boss part out of swing reach instead of stalling |
| `slums.bossfight.revivepause` | **off** (round 9) | hold a boss run for a member walking back from a death |
| `slums.altfight.instanceleash` | **off** (round 9) | an owned alt in an instance fights the party's target, not the owner's box |
| `slums.exped.door` | off | the expedition door: name the bots a start leaves behind, drop a dismissed alt out of its expedition |

The release conf carries the on lines for the lane; nothing in this branch turns any of them on for you.

## 4. Suite

`mvn -o test`, all seven properties above unset: **`Tests run: 8634, Failures: 0, Errors: 0, Skipped: 138, BUILD`
**`SUCCESS`** (53:36). The 138th skip is `ContentRowWzIntegrityTest` (no `wz/` dump on this machine).

## 5. What the merge turn should add to `docs/PROVENANCE.md`

Not touched this round (the PQ branch has newer edits; the merge turn does the provenance pass). What I believe is
missing there, checked line by line:

- **nuttnut/Cosmic at the boss-row pin.** `docs/PROVENANCE.md:241` credits nuttnut/Cosmic at `77e0c5bc9` for the KPQ
  stage flow. The boss-row work read the same repo at **`b684bf78588d50512bcd62db9bb7cb85e4f73829`** (the pin recorded in
  `/root/work/BOSS-ROW-DATA.md`) and took nothing from it, so this is a "read, not taken" row at a different sha — the
  sha differs, which is why the existing row does not cover it.
- **The `Kiwiism` org name**: already present at `:18` and `:240` (`Kiwiism/cosmic_agents` @ `3c7e09435`). Nothing missing;
  if the merge wants the full 40-character sha, it is `3c7e094357329932734a860e5a831d57add27a00`.
- **SoloMapling beyond the header**: the header (`:5`) and the merge section (`:11-14`) name SoloMapling at
  `47b61b5381118ebdc1064ddbd0b1c32f75778871`; there is no per-feature table row for the boss lane. I believe none is owed —
  the rows, the solver and the world knowledge in `BossTargetRules` are ours, read off our scripts and the WZ dump — but
  the merge turn should make that call rather than assume it.
- **GuardianNex**: `:390-395` already carries the paragraph (six rows, `GuardianNex.js` `eventBossIds` + `Map.wz` map
  life, the quest ladder from `Quest.wz/Check.img.xml`, nothing taken from any project). It is a paragraph rather than a
  table row because there is no upstream to cite; if the merge wants it as a row, the "read" column is our own scripts and
  dump, and the upstream column is empty.

# Round 11 (same branch): Chaos Zakum end to end, and three engine questions answered

One commit per item, each with its fail-before test: the test fails on the previous commit and passes on this one
(`ZakumArmShieldTest` for the shield pair, `BossPhasesSolverTest.theBodyPhasesFollowTheRowsOwnOrderNotAHardcodedFamily` for
the row-order sort - it is deterministic against the old hardcoded family, `ZakumAltarReactorTest` for the altar,
`ChaosZakumBattleTest` for the script, `ChaosZakumNpcTest` for Adobis, `MonsterMissingReviveTest` for the death task,
`DifficultyTierTest` for the tier, `TierScalingTest` for the numbers, and `MechanicsDataFilesTest` for the row and the
KPQ block). Where the old code would not compile against the new shape (a switch that did not exist, a field that did not
exist), the fail-before is the missing test itself, and it says so.

**Economy: none.** Nothing here changes a drop, a meso, an EXP number or a shop; the only numbers touched are a mob's
HP when a tier asks for it, and a boss log row. The switch is **`-Dslums.chaos.zakum`, default OFF**, and
"off" is the part that matters: with it unset the altar reactor, Adobis, the run starter and the boss log all take the
paths they took in round 10 - the only new bytes a chaos-off server can see are in `MobId`/`MapleMap` (helpers that answer
exactly what the inline code answered) and in the row, which is never armed.

## 1. What the row claims, and what the server actually does

| claim (`content/zakum-chaos.yaml`) | what the server does with it | verdict |
|---|---|---|
| map `280030001`, one stage | the instance map the script resets (`ChaosZakumBattle.js` `resetPQ` on 280030001) | agrees |
| `summonProperty: summoned` | `2111001.js` sets `summoned=true` and `canEnter=false` before it spawns anything, on both altars | agrees |
| mobs `8800103..8800110` then `8800100/101/102`, in that order | `BossPhasesSolver` sorts every tier by the row's `mobs:` order (item 3); the arms are ARM role, the three bodies BODY | agrees |
| the three bodies are a chain | WZ `revive`: `8800100` -> `8800101` -> `8800102`, and `8800102` names nothing (`wz/Mob.wz/880010*.img.xml`) | agrees - the row's order is the WZ order |
| the body is shielded while its arms stand | `MapleMap.bossDamageBlocked` now asks `MobId.shieldingArms`, and a chaos arm wakes `8800100` only (`wakeBodyIfLastArm`) | agrees, and a chaos arm can never wake the normal body |
| clear is `defeatedBoss: 1` | `ChaosZakumBattle.js` `isZakum` answers for `8800102` only, and that is what clears | agrees |
| a clear is recorded | `ExpeditionType.typeToEntry` maps `CHAOS_ZAKUM` to `BossLogEntry.CHAOS_ZAKUM` (item 6) | agrees; before, a chaos clear was silently dropped |
| the fight is 120+ and 6-30 members | the script's own globals, and `EventManager.startInstance` sees the row's party | agrees |
| the row is opt-in | `Mechanics.rowFlagOn` gates `MechanicsRuns.onInstanceStarted` on `flag:` | agrees |

Numbers left out on purpose: the row carries no HP/EXP of its own. They are WZ's (`900M/1000M/1500M`,
`50M` EXP on the last form) and the engine reads them from the mob, so a row that repeated them could only go stale.

## 2. Item A - a revive id `Mob.wz` does not have no longer kills the death task

`Monster.killBy` schedules the revive spawn on the TimerManager, and inside that task `LifeFactory.getMonster` answers
null for an id no `Mob.wz` entry has; the next `setPosition` NPE'd, the timer's wrapper swallowed it, and the death never
finished - for the whole map, not just that mob. The lookup now goes through `Monster.reviveTarget`, a null is skipped,
the id is named once at WARN (DEBUG after that) and the rest of the death runs (`MonsterMissingReviveTest`, 2 tests).
The 8810120/8810121 pair is deliberately **not** special-cased: the guard is about a stale chain, whatever the mob.

Environment honesty: this machine has no `wz/` dump, so `LifeFactory` cannot even initialise here, and the test reaches
the null through `reviveTarget` rather than through `LifeFactory` itself. That is the same value `getMonster` returns in
production for an absent id (`LifeFactory.getMonster` catches its own NPE and answers null), but it is a seam, not a WZ
read, and it is named as one in the test's javadoc.

## 3. Item B - a run carries its difficulty tier

Plumbing only, no numbers: `MechanicsRuns.start(...)` takes a `Tier` (the old signature delegates with `NORMAL`), so
`RunState.tier()` is the run's own; `Orchestrator.describe` names the tier in the `RUN start` line **only when it is not
NORMAL**, so a stock run logs exactly the line it always logged; the run's clock comes from `rules(tier)`, which for
NORMAL is the row's own minutes; and `KpqStaging.start(...)` hands `startInstance` `difficulty(tier)`, where NORMAL is 1 -
the argument every party quest was started with before tiers existed. `DifficultyTierTest`, 5 tests.

## 4. Item C - the tier's numbers reach the room, behind `-Dslums.mechanics.tiers`

Off by default; on, the mobs the current stage **names**, on the stage's own map, get the tier's HP multiplier through
`Monster.setOverrideStats` - the call the party quest scripts already use. Unlisted mobs, neighbours and corpses are left
alone, the base is always the pre-override HP so a second tick cannot scale twice, and a respawn is scaled when it
appears. `TierScalingTest`, 8 tests.

**What I did not honour, and why.** `mobDamageMultiplier` stays inert: touch damage comes from the mob's WZ stats
(`Monster.getPADamage()` answers `stats.getPADamage()`, `Monster.java:1845-1846`), so honouring it means deciding what a
scaled touch hit does to knockback, death counts and the bots' kite bands - an owner decision. `mobLevelBonus` stays inert
for the same reason: a level is not a number the HP curve gives back without inventing the curve. Both fields are carried
by the row and read by nothing, and the class says so where a reader will find it.

## 5. Item D - KPQ's HARD tier, as data

`content/kpq.yaml` gains one `tiers:` block (`HARD: 41-65, +20 levels, x3.0 HP, x1.5 damage, 40 min, 1.25 mistakes`),
cell for cell from `DIFFICULTY-CHAOS-PLAN.md` section 9, with the derivation in the comment above it and the note that
every cell is the owner's to overwrite. NORMAL needs no entry; EXPERT and MASTER are deliberately absent because no
number is settled and a tier the row does not name is never applied. `MechanicsDataFilesTest` pins the block as written
so a typo fails, no other row is touched, and no script learns about tiers.

## 6. Commits on `164-bossrows`

| commit | what |
|---|---|
| `b8a91143b` | the leader is told bots are driving; three switches default OFF |
| `fc2e25bf4` | chaos ids, the altar's own shield pair, the body chain read from the row |
| `fee9c3ccf` | the altar reactor serves both Zakums |
| `3407e94c9` | `ChaosZakumBattle`, the script the row and the NPC drive |
| `fdacbc8ec` | a chaos clear lands in the boss log |
| `223c6add7` | Adobis offers Chaos Zakum only when the switch is on |
| `b249bf304` | the chaos row, and a row that names its own switch |
| `9c0383abe` | a revive id `Mob.wz` does not have no longer kills the death task |
| `a2fc00099` | a run carries its difficulty tier |
| `b51dcc095` | a tier's numbers reach the room, behind their own switch |
| `6cc9c18be` | KPQ's HARD tier, as data and as a proposal |
| `f9aa7d73e` | the tier test names a row that has no tier (`orbis.yaml`) |

## 7. Suite (round 11 head)

`mvn -o test` with no switches set: **`Tests run: 8634, Failures: 0, Errors: 0, Skipped: 138, BUILD SUCCESS`** (53:36, on
the head that includes every commit in the table above).

## 8. Things in the plan I found to be wrong, and one I could not honour

- **The plan's `8800100/01/02` "orbit" reading was right about the numbers and wrong about the shape written in the
  row**: WZ gives the chain through `info/revive` on the first two forms, not through three independent pages, so the row
  states the order and cites `revive`, which is also what `BossPhasesSolver` reads (item 3).
- **`BossLogEntry` had no chaos slot at all** - the plan assumed an unused one was waiting. `19` was free, which is also
  the packet's boss byte (index + 1).
- **"Off means byte-identical" needed one more edit than the plan counted**: `MapleMap`'s un-fake block had to become a
  named method to be asked about two families, so the chaos-off bytes differ in shape while answering the same ids.
- **Not honoured**: `mobDamageMultiplier` and `mobLevelBonus` (section 4). Everything reachable was built.
