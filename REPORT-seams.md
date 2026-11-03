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

## 2. A stage clock, and the outcome the row declares for it

**What already existed, and what did not.** `Stage.timeLimitMinutes` was already a row fact: the orchestrator honoured it for
`kind: BOSS` rows only (`Orchestrator.budgetFor`: `if (kind != BOSS) return run.budgetMs()`), counting from the stage's own
start, which is how Horntail's body room gets its hour (`scripts/reactor/2401000.js:33`). A party quest row that wrote it
had it **read and ignored**: PiratePQ's own header says the script restarts the timer to six minutes in every room
(`scripts/event/PiratePQ.js:241-268`, boss room eight) while the row's `timeLimitMinutes: 40` "is the whole run's", and
CWKPQ restarts it to 10/10/10/20/60 minutes (`scripts/event/CWKPQ.js:262-294`). In both, expiry is `end(eim)`
(`PiratePQ.js:223-225`, `CWKPQ.js:246-248`). And no row could say what the expiry *means* - end, or go on.

**What it does now.**

| Piece | Behaviour |
|---|---|
| `timeLimitMinutes:` on a stage | held by **every** kind of row (before: bosses only). The clock counts from the run's arrival in that stage, as the boss clock already did |
| the run's own clock | still applies to a non-boss row whose stage declares one: a room's clock is a tighter limit inside the run's, never a looser one (a boss row keeps replacing it, exactly as before) |
| `onTimeLimit: end` (default) | the run ends with `time budget of N min ran out on stage S` - the text the boss lane already produced - and the history line `abort: ...` |
| `onTimeLimit: advance` | the run goes on to stage S+1 **without a clear**, and says so in its history: `stage S: its own N min ran out; the row declares onTimeLimit: advance, so the run goes on to stage S+1 without a clear`. The next stage's clock starts on arrival. The one script that times a room this way is `GuildQuest.js:203-222`: its first `scheduledTimeout` closes the join window and starts the real 90-minute clock instead of ending the event |
| refused at load | `advance` without the stage's own `timeLimitMinutes` (no clock to run out), `advance` together with `loopTo`, `advance` on the last stage (nowhere to go), any other value |

A row that names neither field behaves byte-for-byte as before: the stage clock only exists where a stage declares one, and no
shipped party-quest row declares one (`grep -n "^    timeLimitMinutes"` finds Horntail's three boss rooms only). **No shipped row was
changed**: PiratePQ and CWKPQ could now declare their room clocks, but that changes what those rows do and belongs to the
session committing rows to `164-opq`.

**Fail-before.** `StageClockTest` (10). With the field read and the orchestrator unchanged - the old code's behaviour for this
fact - three of ten fail for the right reason: `aPartyQuestStageThatDeclaresAClockIsHeldToItNotToTheRowsOne` (a six-minute
room still running at 6:01: *expected true but was false*), `aRoomThatDeclaresEndEndsTheRunWithTheReasonInTheHistory` (the
same, through the history) and `aRoomThatDeclaresAdvanceSendsTheRunOnWhenItsClockRunsOutAndSaysSo` (*expected stage 2 but was
1*: the declared outcome is read and nothing acts on it - the silent stall). The other seven are regression guards and parse
checks: a stage with no clock keeps the row's whole-run clock, a longer room clock never outlives the row's, a clear on the same
tick still wins, a boss row keeps exactly what it did (and `BossStageBudgetTest`, `RevivePauseTest`, `LoopStageTest`,
`RowRunSimulationTest` stay green), the outcome parses in any case and defaults to `end`, and the four refusals above.

**Still unproven.** (a) No live run: the orchestrator is driven with a fake `StageView`, as `BossStageBudgetTest` is. (b) `advance`
has no shipped consumer yet - the one script that wants it, Guild Quest's strategy phase, is covered by the portal's own
`entryTimestamp` gate in the row built on Item 4, so the primitive ships with its tests and waits for a row that needs it,
the way `graceMs:` did. (c) The row-level clock is unchanged, so a row that declares room clocks *and* is slower than the
script's real timers (which restart on entry) is still only as honest as its numbers.

## 3. A stage whose successor depends on what the room says

**The two scripts that need it** (both Pre-Horntail PQ, which stays unbuilt for the reasons in `REPORT-opq.md` - the engine
shape is what this item is):

- the lightbulb room reads a reactor: `scripts/portal/hontale_C.js:30-41` sends the leader's team to `240050300` when the
  reactor named `light` (`2408001`, on `240050200`) is at state 1 and to `240050310` when it is at state 3, and refuses
  ("Hit the Lightbulb to determine your fate!") at any other state - two rooms, one of which a given run never sees;
- the fifth door goes back: `scripts/portal/hontale_Bopen.js:96-124` takes the party from the fifth mine `240050105` to the hub
  `240050100` again, so a room is entered twice.

**What it does.** A stage may carry `branches:`, a short list; each entry is a `when:` and a `goto:`.

```yaml
branches:
  - when: { reactor: light, state: 1 }      # a reactor, by WZ name (or by WZ id when all digits), at exactly this state
    goto: 8
  - when: { eventProperty: path, equals: b }  # an event property: present, or equal to a value
    goto: 9
  - goto: end                                # no when: always holds, so it goes last; `end` = the row is finished here
```

When the stage clears (its own `clear:`, unchanged), the first entry whose `when:` holds wins: the run goes to that stage - at or
before the current one is a re-entry - or, for `goto: end`, the row is finished there, **cleared**, through the same end-of-row
path the last stage takes (including `afterClear`). None holding means index + 1, exactly as before. A branch to a stage this run
does not play (past `lastStage`) finishes the row the same way instead of stranding it. The history says which branch fired
(`stage 7: reactor light at state 3: on to stage 9`). It reads state a row can already read (`StageView.eventProperty`,
`reactorState`); it interprets no script. `loopTo:` is unchanged and runs after the branches find nothing.

Refused at load: a `goto` that is not a stage of the row, or the stage itself, or neither a number nor `end`; a missing `goto`;
a `when` with neither an `eventProperty` nor a `reactor`, with both, or a reactor without a state (or a negative one); an entry
after an unconditional one (unreachable).

A re-entered room whose clear property is already set clears on its next tick: the row author owns making a loop terminate,
and the run's clock is what stops one that does not, exactly as it stops `loopTo`.

**Fail-before.** `BranchStageTest` (11). With `branches:` parsed and the orchestrator unchanged - the old code, which skips
unknown keys - five fail for the right reason: a run whose room says `path = b` still walks `[1, 2, 3]` instead of `[1, 3, 4]`;
a `goto: end` row still plays all four rooms (`[1, 2, 3, 4]`); a reactor at state 3 still goes to room 2; the first-match order
and the beyond-`lastStage` end both read `index + 1`. The rest are guards and parse checks: a row without branches is
unchanged, the state-1 light cave still works, a missing reactor matches nothing, a re-entry goes back and then on, and nine
bad `branches:` are refused at load, each named.

**Still unproven.** No shipped row uses it: the one quest that needs it (Pre-Horntail) is not completable, so the primitive is
tested with a synthetic row and the `hontale_C.js` shape. The reactor read is `reactorState(String)` / `reactorState(int)`, which
`LiveStageView` already implements against the stage's map; `holds()` itself is a default method on `StageView` and is
exercised through fakes, not through a real map.

## 4. Guild Quest (Sharenian Ruins): the world-queue start, the party rules, and the row

Commits on `164-seams` (all on top of `c19adc3a06b59d2cbf931efceb6a2cd6c150ddc7`), oldest first after items 1-3:
`3d8e7ee79` equip step, `3d934becc` composition, `1c7ce6586` queue seam, `1f7271226` queue staging, `cbc9fa7a0` stage-view
reads, `30d8d61c0` + `fc3a279aa` population-bot reactor fix, `676f4ec09` unequip step, `7ea86c686` the row.

### 4a. A bug fix first: a population bot's reactor and item-reactor scripts had no player

**This is not "our feature". It is a defect on live content that our rows only made reachable.** Every population bot of a
channel shares one headless `BotClient` whose `getPlayer()` is null. Every reactor hit, every item dropped into an item
reactor and every NPC conversation hands the script *that* client, and the scripts reach the character through it:

- `ReactorActionManager.dropItems` returns without dropping anything when `c.getPlayer()` is null
  (`ReactorActionManager.java:157-159`) - a chest, an ellin stone, a Guild Quest rock hit by a bot gave nothing;
- scripts whose first line is `rm.getPlayer().getEventInstance()` throw: `9208000.js:36` (statues), `9208007.js:27`
  (spear altars), `9208009.js:26` (Ergoth's gate);
- **the headline, in plain words:** `MapleMap.searchItemReactors` (`MapleMap.java:2683-2711`) walks the drops lying in an item
  reactor's box and reads each drop's `getOwnerClient()`, which did `ownerClient.getPlayer().isAwayFromWorld()`. For an item
  dropped by a population bot that is a `NullPointerException` - *thrown out of the scan, so the other items in that box were
  never looked at and the reactor did not respond to them* (`Reactor.hitReactor` catches it and prints a stack trace, so the
  server did not crash and nobody was told). Release-note sentence: *"Items dropped by bots on the ground could stop a
  gate, chest or altar from reacting to the other items lying on it."* (The pickup rules were never affected: see below.)

**Scope note (for ticket 168).** On `release-1.6.4` (origin `c0e0a2cd7`, local `6d18f961e`) the same three
`activateItemReactors(mdrop, ....getClient())` sites exist (`MapleMap.java:1298`, `:2613`, `:5535`), `MapItem` takes
`ownerClient = owner.getClient()` (`:57`, `:70`) and `BotCm` has no override, so population bots see the shared client there
too. **It is a latent live defect; the fix is a bug fix, not part of the Guild Quest feature**, and can be admitted on its own.
`164-seams` does not contain `6d18f961e`; the three sites are textually identical there and the catch-up merge must keep the
binding (the lead does that merge).

**The fix.** `BotClient.forScripts(bot)`: a per-action headless client whose `getPlayer()` is the bot, for a population bot
only; anyone with a client of their own (a player, an owned alt) gets it back unchanged. Nothing is written to the shared
client, so two bots acting at once never see each other's player. Its javadoc says, in bold: *scripts only; anything that
sends a packet must use the bot's shared client ... do not reach for this because `getPlayer()` is null.*

**Call-site discipline** (every site, touched or deliberately left alone):

| Site | Decision | Why |
|---|---|---|
| `StepExecutor.hitReactorAt` | **bound** | the reactor's script reads its player |
| `StepExecutor.touchReactor` | **bound** | same, for `touch` scripts |
| `MapleMap.java:1298` `spawnItemDrop(.., chr, ..)` `activateItemReactors(mdrop, ..)` | **bound** | `ActivateItemReactor.run` calls `reactor.hitReactor(c)` 5 s later and the script asks `c` for its player |
| `MapleMap.java:2613` `spawnItemDrop(dropper, owner, ..)` | **bound** | same |
| `MapleMap.java:5535` `spawnItemDropNoExpire` (the path `BotHoldings.payOut`, i.e. `PlaceItem`, drops through) | **bound** - *missed in the first commit, found by the audit* | same |
| `MapItem` constructors x3 (`ownerClient`) | **bound** | read back only by `getOwnerClient()` <- `searchItemReactors:2705`; this is the cached-client case that threw |
| `NPCConversationManager.getActingClient()`, overridden in `BotCm` | **new accessor**, one caller: `scripts/npc/9040009.js:110` | the Gatekeeper schedules display hits (`delayedHitReactor(client, delay)`) that run the statue script later with that client |
| `BotCm.getClient()` and the ~25 dialogue/packet sends in `NPCConversationManager`/`AbstractPlayerInteraction` | **left alone** (I had overridden `getClient()` first; reverted) | packets must go to the shared client |
| `ReactorHitHandler`, `TouchReactorHandler` | left alone | human packets, own client |
| `ReactorActionManager.hitReactor()` (`rm.hitReactor`) | left alone | uses the `c` the script was started with: already bound |
| `MapleMap.java:5180` Monster Carnival guardian hit with the first player's client | left alone | carnival is humans only; bots never enter |
| `GameQaWorld` GM paths, `OPQBot` `CustomReactor` | left alone | GM client / no client |
| `BotCm.gainItem`'s `bot.getClient().getPlayer() == bot` | left alone | the bot's own client |
| `Character.getClient()`, `bot.setClient` | untouched | the bound client is never stored on a character and never is the character's client |

Caches re-audited: `ActivateItemReactor.c` lives 5 s and is used only for `reactor.hitReactor(c)`; `MapItem.ownerClient` only via
`getOwnerClient`; **`Client.engines`** - `AbstractScriptManager.getInvocableScriptEngine(path, c)` caches *one script engine per
path on the client*, so a fresh bound client per hit would have built a new Graal engine per hit (a slow-motion denial of service
on our own server): the bound client delegates `get/set/removeScriptEngine` to the shared client, so only `getPlayer()` differs;
`Reactor.delayedHitReactor`'s closure holds the client up to ~25 s (statue display), used only for the hit.

**The three `MapleMap` lines are verified by reading, not by test** (the 5 s `ActivateItemReactor` is scheduled privately in the
map). Each is `activateItemReactors(mdrop, BotClient.forScripts(<owner/chr>))` and each needs the bound client for the one reason
above.

**Presence and loot ownership - checked, owner question withdrawn.** `getOwnerClient()` now treats a population bot as present
(`MapItem.java:190-198`: a bot's away flag is never cleared - only `PlayerLoggedinHandler` and `OwnedAltBackend` call
`setEnteredChannelWorld` - so by the old rule it could never count as present). That does **not** touch who may loot: loot
eligibility is `MapItem.canBePickedBy`/`hasClientsideOwnership`/`isFFADrop` (`MapItem.java:127-176`) off `character_ownerid`,
`party_ownerid`, `type` and the 15 s `dropTime`, never off `ownerClient`; `getOwnerClient()` has exactly one caller in the tree
(`MapleMap.searchItemReactors:2705`). So a human standing on a bot's drop may not take it in the 15 s window unless he is in its
party, and may after, before and after the fix; the bot does not gain ownership it lacked. Exp/party sharing
(`Monster.distributeExperience:714`, `giveExpToCharacter:844`) works from `Character` and party, never the client, and drop
attribution is the six `new MapItem(...)` sites (`MapleMap.java:1281,1313,1331,1343,2592,5486`), all of which pass
`owner.getClient()`, so for a player the constructor now yields the very client that was passed before.
**No owner question is needed** (the "keep or revert to unowned" line does not apply).

**Tests.** `BotClientBindingTest` (5), `ReactorScriptClientTest` (2), `MapItemOwnerClientTest` (4), `BotCmActingClientTest` (2).
Fail-before, run against the previous commit's `BotClient`/`MapItem`: `MapItemOwnerClientTest` errors with
`Cannot invoke "client.Character.isAwayFromWorld()" because the return value of "client.Client.getPlayer()" is null`; the
engine-cache test gets `null` for an engine the shared client holds. **Alts and humans unaffected**, asserted by identity:
the executor hits an alt and a player with *their own client object* (`ReactorScriptClientTest`), `MapItem` returns a player's
and an alt's own client and their away flag still counts (`MapItemOwnerClientTest`), `getActingClient()` is `getClient()` for a
player and `getClient()` is unchanged for a bot (`BotCmActingClientTest`), and the loot answers are the same on the pre-fix and
the fixed `MapItem` (`whoMayLootABotsDrop...` passes on both; on the old one it errors only at the later `getOwnerClient` line).

### 4b. Two steps: `EquipItem` and `UnequipItem` (the explicit halves of putting a protection on, and of paying it)

- **`EquipItem(itemId, why)`** goes through the gear system (`BotGear.wear`): equip this id, verify it is worn, and if the bot
  does not hold it a `Wait` that names the item. `BotGear.review` values by attack and would only wear a +10 magic-defence earring
  through its sturdiness tie-break into an empty slot, so the step is its own entry reusing `wearableNow`/`apply`. Fail-before:
  `EquipItemStepTest` 4 of 6 red while the executor ignored the step; `BotGearWearTest` (6) covers the gear move.
- **`UnequipItem(itemId, why)`** takes a worn item off into the bag (`BotGear.takeOff`: `TAKEN_OFF`/`NOT_WORN`/`NO_ROOM`; a full
  bag keeps it on), paced like equip; the log line is `UNEQUIP <bot> takes off <id> - <why>`, naming the member who gave it up. It
  is never inside a generic step. Fail-before: two executor tests red while it ignored the step (`takingOffAsksTheGearSystemAndNamesWhoPaid`,
  `aBagWithNoRoom...`); two gear tests cover `takeOff`.

### 4c. The party rules the row carries

- **`queue:`** (commits `1c7ce6586`, `1f7271226`): Guild Quest is entered through the event's world queue
  (`9040000.js:54-91` registers the *guild*; `EventManager.java:959-976` starts the instance with the master alone inside). The row
  names how to register and join; `LobbyWatch` polls every 5 s and `MechanicsRuns.onQueueInstanceStarted` arms the run when the
  join window closes (`canJoin = 0`). `QueueStaging` stages it for a guild master who is a bot or a GM: guild checks, the queue
  not full and nobody ahead, size 6-30. `QueueSeamTest` (11), `QueueStagingTest` (14).
- **`composition:`** (`3d934becc`): a party-composition fact, like `minPlayers`. The maze's door `under30gate.js:25` admits only a
  member of level <= 30 and its crown is the fourth piece of Sharen III's body (`9201001.js`/`9040003.js`), so a party of only
  high-level members can never clear. The row says `junior: 10..30` (10 because the earring needs level 10); `Orchestrator.start`
  refuses a party that does not fill the seats, and `QueueStaging` seats a low-level bot (through `KpqStaging.spawnBots` and
  `BotGuilds.join`) or refuses with a clear line. **No stand-in and no hidden level override.** `CompositionSeatTest` (8).

### 4d. The row: `content/guildquest.yaml`, flag `slums.mechanics.guildquest` (OFF unless switched on)

| # | Stage | Solver | Clear |
|---|---|---|---|
| 1 | waiting room `990000000` | `gq-lobby`: read `entryTimestamp`, then the door's own script | party on `990000100` |
| 2 | outskirts `990000100`/`200` | `gq-earring`: each member earns and **wears** his own Protector Rock 1032033 | party on `990000300` |
| 3 | Gatekeeper `990000300` | `gq-statues`: the pattern is in `stage1combo`; one hitter, only while `stage1status` is `active`; the leader's click starts, one click submits | `stage1clear` |
| 4 | spear hall `990000400` + 301/410/420/430/431/440 | `gq-spears`: four spears; the tower **and** the golem room are both mandatory | `speargate` at state 4 |
| 5 | vassals `990000500` + 501/502 | `gq-vassals`: the combo's four offerings | `stage3clear` |
| 6 | maze `990000600` + clothing rooms + 700 | `gq-maze`: junior alone in 640/641; marks 9/8/20; body dressed shoes-pants-top-crown; the Soul | `stage4clear` |
| 7 | Ergoth's gate `990000800` + 900 | `gq-boss`: **the sacrifice**, Ergoth, the Rubian | instance cleared (`eim.clearPQ()`) |

`afterClear`: reward NPC `9040001` on `990001100`. No stage sets `nextPortal`: the doors between the stages are pt-8 script portals
that a plain `EnterPortal` plain-warps to 999999999, so each solver walks its members in from the previous stage's rooms (listed
under `maps:`). A stage-by-stage `map-integrity` pass over all 28 maps is clean (`tools/ops/map-integrity --wz
/root/work/SlumsMS-152-maps/wz`: 0 dead portals, 0 missing mobs/NPCs/reactors).

**The sacrifice, in one paragraph for the release notes.** *Ergoth's gate only opens for one Protector Rock. The earring is
"only 1", so nobody can carry a spare: one guild member has to give his earring to the gate. Without it that member is
unprotected on the last two maps, where the air takes 30,000 HP, so he stays behind on the gate hall while the others go through
and face Ergoth.* In the engine: the highest-id **bot** that is not the leader is the sacrificer (the leader loots and hands in
on the boss map; if the leader is the party's only bot he pays; a party of players has nobody to steer, so nobody can pay and the
stage says so); on 800 it takes the earring off (`UnequipItem`, log `UNEQUIP <name> takes off 1032033 - sacrifice: Ergoth's gate
(kinggate, reactor 9208009) opens only for one 1032033 (Protector Rock, only 1) in its box, so <id> pays it and is unprotected on
990000800 and 990000900 afterwards (decHP 30000)`), sets it in the gate's box (`PlaceItem`, count exactly 1; the server acts 5 s
later) and sits out. Nobody else takes anything off, nobody enters 800/900 unprotected; both are asserted in `GqBossSolverTest`.

**The EQUIP step, in one paragraph for the release notes.** *Every member earns his own Protector Rock from the glowing rocks of
the first two maps and puts it on before he walks on: every map from the Gatekeeper's room on takes 30,000 HP a tick from a
member without it.* In the engine: `gq-earring` has each member keep to one rock (by map object id: the rocks share two WZ ids)
until it is spent, loot the drop, `EquipItem` it, and only then take the door; a member who cannot wear it ends the run with the
reason, and a member found on the Gatekeeper's map without it does the same.

**Finding - population bots are most likely never hurt by that decHP at all.** `World.runPlayerHpDecreaseSchedule`
(`World.java:1808`) skips every character whose `isAwayFromWorld()` is set, and that flag is `true` by default and cleared only by
a player's login and `OwnedAltBackend` - **not for a population bot**. Read, not run. So the earring is load-bearing for owned
alts and players and belt-and-braces for population bots, and the sacrifice costs a population bot nothing in practice. The row
nevertheless requires protection for every bot (safe if that ever changes, and the gate still needs one earring), and the
sacrificer stays out of 800/900 either way. The tick interval/count (`MAP_DAMAGE_OVERTIME_*`) are not in `config.example.yaml`
and are unknown. *Owner question (only one): do population bots keep farming an earring each, or does the row skip protection
for them? Not decided here.*

**Stage 2, the route I can prove.** The golem room (430) and the tower (440) are **not** alternatives: `speargate` (9208004) needs
four spears (states 0-4 from four forced hits of the `spear1-4` altars, `9208007`), only four `9202001` spear reactors exist
(three on 410, one on 431) and 431 is behind `metalgate` in the invincible-golem room 430. So 410 + 430/431 give the spears and 440
is where they are placed. Population bots take contact damage with an HP floor of 1 (`BotContactDamage`) and only while a real
player is on the map, so the golems are pathing obstacles for them; **owned alts die like players there** - recorded as a risk.

**Behaviour change outside the row.** `Orchestrator.tick` reminded a real leader (and picked the bot that speaks) only while he
stood on the stage's *first* map. A multi-map stage whose business ends elsewhere - the maze's leader leaves for 700, the boss
stage ends on 900 - never prompted him. It now uses any map the stage drives (`MultiMapLeaderPromptTest`, red on the old code:
no `Say`). It applies to every row with a player leader and a solver that implements `leaderPrompt`.

### 4e. Drops the run depends on - every one **live table unverified** (seed data in `131-reactordrops-data.sql`, `152-drop-data.sql`)

| Drop | From | Used by | If missing |
|---|---|---|---|
| 1032033 Protector Rock | reactors 9202000, 9202009 | every member, stage 2; the gate's one rock, stage 7 | `Wait` naming 1032033 |
| 4001025 Longinus Spear | reactor 9202001 (x4) | stage 4 | `Wait`, then `Abandon` after 120 s (one-shot sources) |
| 4001026 Key of the stone gate | mob 9300019 | stage 4 | same |
| 4001037 Rusty Key | reactor 9202002 | stage 4 | same |
| 4001027 / 4001028 | mobs 9300022 / 9300023 | stage 5 digits 0/1 | `Wait` naming mob and item |
| 4001029 / 4001030 | reactors 9202003 / 9202004 | stage 5 digits 2/3 | `Wait` naming reactor and item |
| 4001031..34 clothes | reactors 9202005..9202008 | stage 6 | `Wait` naming item |
| 4001035 Mark of Evil | mobs 9300024, 9300027, 9300033 | stage 6 | `Wait` |
| 4001036 Gargoyle token | mob 9300025 | stage 6 | `Wait` |
| 4001024 Rubian | mob 9300028 (Ergoth) | stage 7 | `Wait` naming 4001024 |

### 4f. Unproven (nothing below was run against a live server or a live DB)

- All **navigation**: 410's upper floor, the golem room 430's route, 440's tower chain, 500's ropes, 620/631/641, the walk to the
  maze rooms (the executor's own pathing, not the solvers').
- The **live drop rows** above.
- **Sacrifice death behaviour**: whether an alt or player without the earring dies on 800/900 and what `canRevive=1` does to him
  afterwards (`GuildQuest.js:264-277`), and the damage tick (`MAP_DAMAGE_OVERTIME_*`).
- **Combat**: that level 10-30 bots can kill 20 Devil Slimes, Jr. Gargoyles, a level-66 knight, a level-65 `9300019`, and a level
  115 / 1,700,000 HP Ergoth. The row does not check DPS; nothing in the scripts does either.
- **Owned alts at 430** (they die like players), and the level-10 floor (derived from the earring's `reqLevel`, not run).
- `guild1F00`'s `gridCheck` landing spots on 600 for a bot with a second task; the 5 s item-reactor delay under load.
- The five-hit count of the larder/clothes reactors (derived from `Reactor.java`; the solvers read the state and adapt).
