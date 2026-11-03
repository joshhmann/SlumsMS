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
  `Mob.wz/08220010.img.xml` has no `info/revive`, so it is one form.
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
  `mobTime -1` at `(-2889,653)`; `Mob.wz/08220011.img.xml` has no revive.
- **Entry:** the same portal script from `240070402`. **Clear:** `clearPQ` (`:225-228`), instance flag.
- **Simulation:** `RowRunSimulationTest.aBotPartyKillsAufhebenAndTheInstanceClearsItself`.

### `oberon.yaml` - Oberon (TD Battle 4)

- **Script:** `scripts/event/TD_Battle4.js`.
- **Gates:** `1..6`, `70..255` (`:27-28`), `maxLobbies = 1` (`:41`), `eventTime = 15` (`:39`).
- **Maps:** `240070503` (`:29`, `:34-35`); exit and recruit `240070502` (`:30-31`).
- **Kill target:** `8220012` (`:37`). **Spawn source:** the map's own life, `Map.wz/Map/Map2/240070503.img.xml`,
  `mobTime -1` at `(201,234)`; no revive in `Mob.wz/08220012.img.xml`.
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
- **Gates:** `1..6` (`:28`), `1..255` (`:29`), `maxLobbies = 1` (`:41`), `eventTime = 45` (`:39`).
- **Maps:** entry and stage `800040410` (`:31`, `:36-37`); exit, recruit and clear `800040401` (`:32-34`).
- **Kill targets and the revive chain:** `9400408` -> `9400409`, in that order, one stage. **Spawn source:** the map's
  own life list is empty (`Map.wz/Map/Map8/800040410.img.xml` has an empty `<imgdir name="life">`), so `9400408` is the
  script's spawn: `setup()` builds `LifeFactory.getMonster(9400408)` and drops it on the ground at `(466,142)`
  (`:129-134`). `9400409` is its WZ revive (`Mob.wz/9400408.img.xml` `revive/0`), and it is `isCastellan` (`:227-230`).
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

`mvn -q -o test` on `164-bossrows` at the head of this branch: see the commit message; the run reported
`Tests run: <see commit>, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.
