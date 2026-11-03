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

## GuardianNex: six caves, one script - refused, one primitive short

`scripts/event/GuardianNex.js` is the one room-of-bosses script left: `minPlayers 1` (`:1`), `timeLimit 15` (`:2`),
`exitMap 240070000` (`:4`), `eventMap 240070010` (`:5`), `eventBossIds` = `[7120100, 7120101, 7120102, 8120100,
8120101, 8140510]` (`:6`). `setup(difficulty, lobbyId)` (`:10-19`) builds instance `Nex_<lobbyId>` and resets the cave
that lobby names, `playerEntry` (`:25-28`) warps to `eventMap + 10 * nex`, and the clear is that cave's own boss dying
(`monsterKilled` `:99-103` -> `clearPQ` `:94-97`, `stopEventTimer` + `setEventCleared`, so `{ instance: true }`).

The six caves are real, and each holds its boss as map life - verified in the dump: `Map/Map2/240070010.img.xml` life
`7120100`, `240070020` -> `7120101`, `240070030` -> `7120102`, `240070040` -> `8120100`, `240070050` -> `8120101`,
`240070060` -> `8140510`; no reactors, no other mobs. The door is portal `scripts/portal/TD_neo_inTree.js`, which picks
the cave from the player's *active quest* (`quests = [3719, 3724, 3730, 3736, 3742, 3748]`, `:8`), refuses if that quest
already has progress (`:13`), and calls `nex.startInstance(i, pi.getPlayer())` (`:18`) - a single-character instance,
like Maha. The quests are real too (`Quest.wz/Check.img.xml`: levels 70/73/77/81/85/90, NPC `2082004`).

So the honest form is six rows, one per cave - and six rows cannot be armed. `MechanicsRuns.onInstanceStarted`
(`:349-353`) picks the row by `contentFor(eim.getEm().getName())`, and that name is `GuardianNex` for all six caves;
`contentFor` (`:377-386`) returns the first row whose `eventScript` matches. Six `eventScript: GuardianNex` rows would
collapse onto the first one, and a party standing in cave `240070050` would be warped to cave `240070010` by the
solver's own first step - a wrong-map bug, not coverage. A single row cannot help either: it can name exactly one
`mapId`.

**Missing primitive:** row selection by something the six caves differ in. The instance already carries both candidates
- its own name (`Nex_<n>`, `GuardianNex.js:11`) and the map it was built on (`:14-15`) - but the arming seam only looks
at the event script name. That is `MechanicsRuns.contentFor` plus a matching rule in `Content`, i.e. engine the merge
should decide, not something to bolt on here. No GuardianNex row is shipped and none was faked: a row naming cave 0
would be right for one quest and silently wrong for the other five.

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
green. Run here against `/root/work/SlumsMS-170-evan/wz`: **32 rows, 53 stage maps, 83 mobs, 7 reactors, 1 door item**,
all present; no typo was found, so no row changed.

- maps: `103000800`-`103000804`, `105100300`, `190000000`, `191000000`, `192000000`, `195000000`, `196000000`,
  `197000000`, `220080001`, `240060000`, `240060100`, `240060200`, `240070203`, `240070303`, `240070403`, `240070503`,
  `240070603`, `270050100`, `280030000`, `401060100`, `450004751`, `450007441`, `450010101`, `450012211`, `541010100`,
  `541020800`, `551030200`, `800040410`, `801040100`, `910010000`, `914020000`, `922020100`, and the PQ sets
  `920010000`/`920010100`/`920010200`/`920010300`/`920010400`/`920010500`/`920010601`/`920010700` plus
  `922010100`-`922010900` - 53 in all, the exact set the test prints
- reactors: `2201004`, `2401000`, `5411000`, `5411001`, `5511000`, `5511001`, `8001000`
- door items: `4000381`
- mobs: the 83 ids across the rows, from `7220003` through `9995009` (the test prints the full set).

## Row data for the whole set

`content/index.txt` now lists six more rows after round 2's, and `MechanicsDataFilesTest`'s pinned id list was extended
in the same order: `latanica`, `magnus`, `nwill`, `nlucid`, `nverus`, `ndarknell` - thirty-two content rows in all,
twenty-two of them `kind: BOSS`. Both files take the additions **appended at the end, in that order**, which is the
order the integration on `164-opq` should keep: the two files are expected to conflict on their shared tail, and the
resolution is this list after whatever `164-opq` has added.

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

## Suite (round 4 head)

`mvn -o test` on this head: **`Tests run: 8575, Failures: 0, Errors: 0, Skipped: 138, BUILD SUCCESS`** (29:31).
Round 3 closed at 8574, so round 4 added exactly one: `ContentRowWzIntegrityTest`, which is the 138th skip on a machine
without the dump (`mvn -o test -Dwz-path=/root/work/SlumsMS-170-evan/wz` runs it, 1 test, green). Round 3 itself added
nine over round 2's 8565 - the entry-item solver test, the stage-data parse test, two whole-run `latanica` tests (with
and without the item) and the five quest-instance simulations. The seven Delli tests the revert removed are gone from
that count with the code they exercised.
