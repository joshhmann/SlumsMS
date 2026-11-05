# Handlers: quests

Packet-level opcode detail for `QUEST_ACTION` is thin — one handler dispatches five action codes — so the packet
side and the engine side are documented together here. Graal/JS plumbing (engines, sandboxes, script bindings) lives
in [scripting.md](scripting.md); this file covers the quest model, the requirement/action engine, the handler
gates and the persistence the engine writes through.

## Purpose

Quests are data-driven: `Quest.wz` declares each quest's text, its state requirements and its start/completion
actions, and `server.quest.Quest` parses that into typed requirement and action objects once at boot
(`src/main/java/server/quest/Quest.java:81-182`). A player request (start, complete, forfeit, lost-item restore) is
gated by the handler, checked by the requirement objects, then applied by the action objects; the engine never
trusts the client's idea of quest state.

SlumsMS additionally *hides* quest content it cannot finish (`HiddenQuests`), so "the client shows it" and "the
server will run it" have deliberately diverged since 1.6.x (`src/main/java/server/quest/HiddenQuests.java:12-32`).

## Entry points

| Entry | File:line | What it does |
|---|---|---|
| `RecvOpcode.QUEST_ACTION` | `src/main/java/net/PacketProcessor.java:150` → `src/main/java/net/server/channel/handlers/QuestActionHandler.java:81` | the only quest packet handler; dispatches action codes 0–5 |
| Scripted quest start (`startscript`) | `src/main/java/scripting/quest/QuestScriptManager.java:60-93` | runs `scripts/quest/<id>.js` `start()`; used by NPC scripts |
| Scripted quest end (`endscript`) | `src/main/java/scripting/quest/QuestScriptManager.java:108-145` | runs `end()`; requires `STARTED` and an NPC on the map |
| Script API `forceStartQuest` / `forceCompleteQuest` / `startQuest` / `completeQuest` | `src/main/java/scripting/AbstractPlayerInteraction.java:531-587` | bypass path used by NPC scripts; calls `Quest.forceStart/forceComplete` |
| Quest-action script API | `src/main/java/scripting/quest/QuestActionManager.java:57-88` | same four methods plus `gainExp`/`gainMeso`/`getMedalName` for JS |
| GM `!queststart` / `!questcomplete` / `!questrate` / `!clearquestcache` / `!zakumpq` | `src/main/java/client/command/commands/gm3/QuestStartCommand.java:50-52`, `src/main/java/client/command/commands/gm3/QuestCompleteCommand.java:50-52`, `src/main/java/client/command/commands/gm4/QuestRateCommand.java:45`, `src/main/java/client/command/commands/gm6/ClearQuestCacheCommand.java:39`, `src/main/java/client/command/commands/gm4/ZakumPqCommand.java:57` | force start/complete with the quest's own NPC id, live rate change, cache drop, Zakum prequest staging |
| Boot load | `src/main/java/net/server/Server.java:951` | `Quest.loadAllQuests()` on the init executor (`:946`) |
| Bot questing | `src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuests.java:29-58` | bots drive the *real* `canStart/start/canComplete/complete`, never `forceStart` |
| Quest window raise | `src/main/java/net/server/channel/handlers/RaiseUIStateHandler.java:21-38` | `raiseOpen` for an `infoNumber`, force-starts on first open |

## How it works

1. **Boot load.** `Quest.loadAllQuests()` (`src/main/java/server/quest/Quest.java:688-713`) iterates every child id
   of `QuestInfo.img`, builds one `Quest` per id, and records `infoNumber → questid` for ids whose start or
   complete requirement is `INFO_NUMBER` and non-zero (`:711-712`). Both static maps are then swapped atomically
   under a `volatile` field (`src/main/java/server/quest/Quest.java:52-53`).
2. **Parse one quest.** The constructor (`src/main/java/server/quest/Quest.java:81-182`) reads
   `Check.img/<id>`; a missing node returns early — "most likely infoEx" (`:84-87`). `QuestInfo.img/<id>` supplies
   name, parent, `timeLimit`, `timeLimit2`, `autoStart`, `autoPreComplete`, `autoComplete` and `viewMedalItem`
   (→ `medals`, `:89-108`). `Check.img/<id>/0` fills `startReqs`, and `INTERVAL` also sets `repeatable` (`:110-132`);
   `Check.img/<id>/1` fills `completeReqs` and appends `MOB` ids to `relevantMobs` (`:134-151`). `Act.img/<id>` is
   read only after that, `0` → `startActs`, `1` → `completeActs` (`:152-181`); an action type with no `getAction`
   case is dropped by `continue` and the rest are stored (`:162-166`, `:175-179`).
3. **Handler dispatch.** `QuestActionHandler.handlePacket` reads the action byte (`src/main/java/net/server/channel/handlers/QuestActionHandler.java:81`) and
   switches: 0 restore lost item, 1 start, 2 complete, 3 forfeit, 4 scripted start, 5 scripted end
   (`src/main/java/net/server/channel/handlers/QuestActionHandler.java:88-146`).
4. **Proximity gate.** Start and complete call `isNpcNearby` (`src/main/java/net/server/channel/handlers/QuestActionHandler.java:41-68`): the packet's
   player position is only trusted within 1000 px of the server-side position (`:45-47`, fallback
   `playerP.distance(pos) > 1000`), the map must actually hold the NPC (`:55`), and |dx|>1200 or |dy|>800 is
   refused with "Approach the NPC to fulfill this quest operation." (`:61-62`). Auto-start/auto-complete quests skip
   the NPC requirement entirely (`:54`).
5. **Hidden gate on start only.** Case 1 calls `refuseHidden` (`src/main/java/net/server/channel/handlers/QuestActionHandler.java:98`), which prints
   "This quest can't be finished on SlumsMS yet, so it can't be started." and re-enables actions
   (`:71-78`). `Quest.start` and `Quest.forceStart` repeat the guard so scripts cannot bypass it
   (`src/main/java/server/quest/Quest.java:286-290`, `:346-350`). Completion is deliberately *not* gated
   (`src/main/java/net/server/channel/handlers/QuestActionHandler.java:106-120`), so a quest already in progress can still be turned in.
6. **Start requirements.** `if (quest.canStart(player, npc)) quest.start(player, npc);`
   (`src/main/java/net/server/channel/handlers/QuestActionHandler.java:101`). `canStart` (`src/main/java/server/quest/Quest.java:253-268`) checks: not hidden,
   status via `canStartQuestByStatus` (NOT_STARTED, or COMPLETED when `repeatable`, `:219-222`), every
   `startReqs` requirement's `check`, then `canQuestByInfoProgress` (`:224-246`) which string-compares
   `AbstractPlayerInteraction.getQuestProgress(infoNumber, i)`.
7. **Start actions.** `start` runs from `autoStart || canStart` (`src/main/java/server/quest/Quest.java:291`); every start action is
   `check`ed with a null selection and the start aborts on the first false (`:293-297`), then each `run` executes
   (`:298-300`), and finally `forceStart` performs the state transition (`:301`). `forceStart`
   (`src/main/java/server/quest/Quest.java:345-384`) copies the old progress map, applies the total-mob pre-fill when
   `id / 100 == 35 && TOT_MOB_QUEST_REQUIREMENT > 0` by setting progress ids 8200000–8200012 to
   `999 - Math.min(999, cfg)` (`:358-367`), keeps `forfeited`/`completed`, sets expiration from `timeLimit` and
   sends the quest-time-limit packet, then `timeLimit2`.
8. **Completion requirements.** `canComplete` (`src/main/java/server/quest/Quest.java:270-283`) requires status
   `STARTED`, every `completeReqs` check, and the same info-progress match.
9. **Completion actions.** `complete` runs from `autoPreComplete || canComplete` (`src/main/java/server/quest/Quest.java:310`); complete
   actions are `check`ed with the client's `selection` byte (`:312-315`, passed through from
   `src/main/java/net/server/channel/handlers/QuestActionHandler.java:112-116`), `forceComplete` is applied *before* the actions so achievements count even if
   an action fails (`src/main/java/server/quest/Quest.java:317`, comment "counts toward the quest achievements (1.6.1 ticket 93)"), the actions
   run (`:318-320`), and unless a `NEXTQUEST` action exists the client is told to refresh the quest at the end
   (`:321-323`, `hasNextQuestAction` `:656-661`).
10. **State transition and rewards.** `forceComplete` (`src/main/java/server/quest/Quest.java:386-408`) removes the time limit, sets
    `COMPLETED` + `setCompletionTime(now)`, plays effect 9 locally and broadcasts the same to others (`:402-403`),
    and calls `soloMapling.achievements.Achievements.onQuestDone(chr)` only when the completion "counts"
    (`:391`, `:404-406`). `Character.updateQuestStatus` (`src/main/java/client/Character.java:11677-11708`) writes
    the status, increments `completed`, sends the COMPLETE packet and awards a quest point unless the quest is
    same-day repeatable or exploitable (`:11694`). Quest points accumulate into `quest_fame` and convert to fame by
    `QUEST_POINT_REQUIREMENT` (`src/main/java/client/Character.java:11607-11623`).
11. **Forfeit.** `Quest.forfeit` (`src/main/java/server/quest/Quest.java:332-343`) only acts on `STARTED`, drops the time limit and creates a
    fresh `NOT_STARTED` status with `forfeited + 1`. The handler's case 3 does no distance check
    (`src/main/java/net/server/channel/handlers/QuestActionHandler.java:121-123`).
12. **Kill counting.** `Character.raiseQuestMobCount` (`src/main/java/client/Character.java:8958-8989`) bumps the
    three-digit progress string for every matching `relevantMobs` id, skips quests already `COMPLETED` or
    completable (`:8974`), and announces an `UPDATE` with `DelayedQuestUpdate` (`:8978`) — buffered while a quest
    window or NPC conversation is open (`src/main/java/client/Character.java:11652-11662`).
13. **Quest-only drops.** `LootManager.isRelevantDrop` (`src/main/java/server/loot/LootManager.java:35-72`) reads
    `dropEntry.questid`: status NOT_STARTED compares the start amount, STARTED compares the complete amount and
    skips the drop once the player already holds it (`:47-60`).
14. **Scripted quests.** `QuestScriptManager` only runs the JS; the state changes still go through `Quest`
    (`src/main/java/scripting/quest/QuestScriptManager.java:60-93`, `:108-145`). A start with
    `hasScriptRequirement(false)` disposes the conversation instead of running anything (`:70`); an uncoded quest
    logs `START Quest {} is uncoded.` (`:77`) and `END Quest {} is uncoded.` (`:129`).

### Requirement classes

All extend `AbstractQuestRequirement` and expose `check(Character, QuestStatus)`; `Quest.getRequirement` is the
factory (`src/main/java/server/quest/Quest.java:498-571`).

| WZ key / type | Class | Gate |
|---|---|---|
| `lvmin` MIN_LEVEL | `src/main/java/server/quest/requirements/MinLevelRequirement.java:50-51` | `chr.getLevel() >= minLevel` |
| `lvmax` MAX_LEVEL | `src/main/java/server/quest/requirements/MaxLevelRequirement.java:52-53` | `maxLevel >= chr.getLevel()` |
| `job` JOB | `src/main/java/server/quest/requirements/JobRequirement.java:57-64` | job N or one of the map, or `chr.isGM()` |
| `end` END_DATE | `src/main/java/server/quest/requirements/EndDateRequirement.java:54-57` | parses `yyyyMMddHH`, quest still valid |
| `fieldEnter` FIELD_ENTER | `src/main/java/server/quest/requirements/FieldEnterRequirement.java:52-53` | `mapId == chr.getMapId()` |
| `interval` INTERVAL | `src/main/java/server/quest/requirements/IntervalRequirement.java:89-99` | not COMPLETED, or completion older than `interval` (WZ value in minutes, `:53`) |
| `questComplete` COMPLETED_QUEST | `src/main/java/server/quest/requirements/CompletedQuestRequirement.java:49-50` | **count** of completed quests ≥ N (not a quest id) |
| `item` ITEM | `src/main/java/server/quest/requirements/ItemRequirement.java:61-96` | inventory count ≥ N, with an equip/equipped fallback and a GM-clothes quirk (`:75-80`) |
| `mob` MOB | `src/main/java/server/quest/requirements/MobRequirement.java:54-60` | progress string for each mob id ≥ count |
| `npc` NPC | `src/main/java/server/quest/requirements/NpcRequirement.java:36-50` | `npcid == reqNPC` |
| `money` MESO | `src/main/java/server/quest/requirements/MesoRequirement.java:46-53` | `chr.getMeso() >= meso`, else "You don't have enough mesos…" |
| `endmeso` END_MESO | `src/main/java/server/quest/requirements/MesoRequirement.java:34-42` | same class, type preserved — the completion price |
| `quest` QUEST | `src/main/java/server/quest/requirements/QuestRequirement.java:56-71` | named quests are in the required state |
| `pet` PET | `src/main/java/server/quest/requirements/PetRequirement.java:56-68` | any pet item id matches (null pets skipped) |
| `pettamenessmin` MIN_PET_TAMENESS | `src/main/java/server/quest/requirements/MinTamenessRequirement.java:53-67` | max tameness over pets ≥ N |
| `mbmin` MONSTER_BOOK | `src/main/java/server/quest/requirements/MonsterBookCountRequirement.java:49-50` | `getTotalCards() >= reqCards` |
| `buff` / `exceptbuff` | `src/main/java/server/quest/requirements/BuffRequirement.java:46-47`, `src/main/java/server/quest/requirements/BuffExceptRequirement.java:46-47` | has / lacks the buff source id |
| `infoNumber` / `infoex` | `src/main/java/server/quest/requirements/InfoNumberRequirement.java:43-51`, `src/main/java/server/quest/requirements/InfoExRequirement.java:50-60` | always true — read via `getInfoNumber`/`getInfoEx` |
| `startscript` / `endscript` SCRIPT | `src/main/java/server/quest/requirements/ScriptRequirement.java:45-50` | always true — read via `hasScriptRequirement` |
| `start`/`normalAutoStart`/`end` | `src/main/java/server/quest/Quest.java:562-565` | returns `null`; no gate |
| `daybyday` DAY_BY_DAY | none (`src/main/java/server/quest/QuestRequirementType.java:49`, `:110-111`) | no `getRequirement` case, so the node is dropped |

### Action classes

All extend `AbstractQuestAction` (`src/main/java/server/quest/actions/AbstractQuestAction.java:36-46`) with
`check(Character, Integer)` and `run(Character, Integer)`; `Quest.getAction` is the factory
(`src/main/java/server/quest/Quest.java:573-618`).

| WZ key / type | Class | Effect |
|---|---|---|
| `exp` EXP | `src/main/java/server/quest/actions/ExpAction.java:49-59` | `gainExp` scaled by `getExpRate` or `getQuestExpRate` |
| `item` ITEM | `src/main/java/server/quest/actions/ItemAction.java:91-161` | weighted/`prop`-selected give or take; takes before gives (`:134`) |
| `money` MESO | `src/main/java/server/quest/actions/MesoAction.java:50-67` | positive → reward (`recordQuestReward`); negative → NPC fee (`recordNpcFee`) |
| `nextQuest` NEXTQUEST | `src/main/java/server/quest/actions/NextQuestAction.java:50-52` | `updateQuestFinish` packet to chain the next quest |
| (no WZ key) QUEST | `src/main/java/server/quest/actions/QuestAction.java:58-62` | force-sets another quest's status — `QuestActionType.getByWZName` has no `quest` case, so this is unreachable from `Act.img` |
| `skill` SKILL | `src/main/java/server/quest/actions/SkillAction.java:75-105` | `changeSkillLevel`; beginner skills only for the matching beginner job (`:100-105`) |
| `pop` FAME | `src/main/java/server/quest/actions/FameAction.java:49-50` | `gainFame` |
| `buffItemID` BUFF | `src/main/java/server/quest/actions/BuffAction.java:43-54` | applies the item's effect |
| pet skill PETSKILL | `src/main/java/server/quest/actions/PetSkillAction.java:51-63` | sets the pet skill flag on a forfeited-quest path |
| pet speed PETSPEED | `src/main/java/server/quest/actions/PetSpeedAction.java:34-60` | adds `OWNER_SPEED` under the client lock |
| pet tameness PETTAMENESS | `src/main/java/server/quest/actions/PetTamenessAction.java:49-63` | `gainTamenessFullness` under the client lock |
| `info` INFO | `src/main/java/server/quest/actions/InfoAction.java:49-50` | `setQuestProgress(questID, info)` |
| `0` ZERO | none — `getAction` has no `ZERO` case (`src/main/java/server/quest/Quest.java:609-616`) | dropped |
| `yes`/`no`/`npc`/`lvmin`/`normalAutoStart` | none (`src/main/java/server/quest/QuestActionType.java:72-81`) | no factory case, silently dropped (`src/main/java/server/quest/Quest.java:163`, `:176`, `:610-616`) |

## Data

| Source | Shape | Read by |
|---|---|---|
| `Quest.wz/QuestInfo.img/<id>` | name, parent, `timeLimit`, `timeLimit2`, auto flags, `viewMedalItem` | `src/main/java/server/quest/Quest.java:89-108` |
| `Quest.wz/Check.img/<id>/0`,`/1` | start and complete requirements, keyed by WZ name | `src/main/java/server/quest/Quest.java:110-151`, `src/main/java/server/quest/QuestRequirementType.java:66-123` |
| `Quest.wz/Act.img/<id>/0`,`/1` | start and complete actions | `src/main/java/server/quest/Quest.java:152-181`, `src/main/java/server/quest/QuestActionType.java:54-93` |
| `scripts/quest/<id>.js` | `start`/`end` functions for `startscript`/`endscript` quests | `src/main/java/scripting/quest/QuestScriptManager.java:51-58` |
| `queststatus` table | one row per character×quest: status, time, expires, forfeited, completed | `src/main/java/client/Character.java:8664`, `:10226` |
| `questprogress` table | mob-progress strings | `src/main/java/client/Character.java:8691`, `:10227` |
| `medalmaps` table | medal quest → map | `src/main/java/client/Character.java:8704`, `:10228` |
| `drop_data.questid` | quest-only drops | `src/main/java/server/loot/LootManager.java:35-72`; sample rows `database/migrations/065-lkc-quest-drops.sql:1-12` |

There is no `QuestFactory`/`QuestData` and no quest YAML: `Quest.loadAllQuests` over `WZFiles.QUEST`
(`src/main/java/provider/wz/WZFiles.java:7`) is the entire loader. Rows are written per status and skipped when the
status is an empty placeholder (`src/main/java/client/Character.java:10232`, `:5723-5736`).

## Switches

| Switch | Where | Default | Effect |
|---|---|---|---|
| `quest_rate` | `config.yaml` → `src/main/java/config/WorldConfig.java:15`, read via `World.getQuestRate` (`src/main/java/net/server/world/World.java:419-421`) | `1` (`config.example.yaml:40`) | multiplies quest EXP and meso when `USE_QUEST_RATE` is on; settable live by `!questrate` |
| `USE_QUEST_RATE` | `src/main/java/config/ServerConfig.java:485` | `false` (`config.example.yaml:407`) | on: `getQuestExpRate`/`getQuestMesoRate` (`src/main/java/client/Character.java:6037-6049`) instead of the global rates |
| `TOT_MOB_QUEST_REQUIREMENT` | `src/main/java/config/ServerConfig.java:407` | `0` (`config.example.yaml:313`) | total-mob quests (id/100 == 35) start pre-filled to `999 - N` (`src/main/java/server/quest/Quest.java:358-367`) |
| `QUEST_POINT_REPEATABLE_INTERVAL` | `src/main/java/config/ServerConfig.java:488` | `25` (`config.example.yaml:410`) | repeatable-quests interval used by `isSameDayRepeatable` (`src/main/java/server/quest/Quest.java:210-217`) |
| `QUEST_POINT_REQUIREMENT` | `src/main/java/config/ServerConfig.java:489` | `0` (`config.example.yaml:411`) | quest points per fame point; `0` disables points (`src/main/java/client/Character.java:11607-11623`) |
| `QUEST_POINT_PER_QUEST_COMPLETE` / `_PER_EVENT_CLEAR` | `src/main/java/config/ServerConfig.java:490-491` | `0` (`config.example.yaml:411-413`) | points per completion / event clear |
| `-Dslums.botquests.enabled` | `src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuests.java:63` | unset (off) | bots quest at all |
| `-Dslums.temple.enabled`, `-Dslums.mechanics.zakumpq` | `docs/architecture/switches.md:45,52` | unset | Temple quest lanes, Zakum prequest route |

Live: `docs/release/1.6.4.conf:271` (`UNIT_EDITS`) carries no quest-rate, quest-point or `TOT_MOB` key, so live's
deployed `config.yaml` (path only, not read here) decides those values. TEST's `start-test.sh` runs on another host
and is not reachable from this worktree — TEST's values are unknown. History for comparison: 1.5.1 live ran rates
3/10/4/3/1 with `USE_QUEST_RATE` on and `TOT_MOB_QUEST_REQUIREMENT` 200 (`docs/tickets/68-mapleroot-vs-cosmic-audit.md:111-115`),
and `docs/design/divergence-ledger.md:115` (QU-013) records quest rate as a deliberate MapleRoot divergence.

## Invariants

- **A completion price must be a requirement and a matching negative action.** `Quest 6007` charges
  20,000,000 mesos; the price is declared as `endmeso` in `Check.img` and mirrored as `money = -20000000` in
  `Act.img`. Before `9fa7d63f2` the requirement type had no `endmeso` case, so nothing checked the price, and
  `Character.gainMeso` floors a negative gain at zero — a player with 3M paid 3M and still received the Dragon's
  Perfume Bottle (commit message `9fa7d63f2`; also `docs/HANDOFF.md:276-279`). The mapping now lives at
  `src/main/java/server/quest/Quest.java:531-533` and the type at `src/main/java/server/quest/QuestRequirementType.java:54` (`END_MESO(24)`), mapped from the WZ key at `:115`, with the distinction
  documented in-line: "the completion-side meso price (`endmeso`), distinct from `money`, which is paid to start the
  quest". 54 live quests declare `endmeso` (648,071,700 mesos); 50 carry the matching negative `money` action and
  four (20526, 6030, 6031, 6032) charge from their scripts — audit new quests against both halves.
- `MESO` and `END_MESO` must construct `MesoRequirement` **with their own type**: `src/main/java/server/quest/requirements/MesoRequirement.java:34-37`
  takes the type and passes it to `super(type)`; hardcoding `MESO` is what made the two collapse.
- Hidden quests cannot start, by packet, script or force; `Quest.start`/`forceStart` re-check
  (`src/main/java/server/quest/Quest.java:286-290`, `:346-350`) because `autoStart` short-circuits `canStart` (`:291`).
- Quest state is per character and persisted atomically by status; bots hold an in-memory state that is never
  written (`src/main/java/client/Character.java:6952-6958`, `:11678`).
- Every quest whose `Check.img` declares `startscript`/`endscript` needs `scripts/quest/<id>.js` with that function
  (`src/main/java/scripting/quest/QuestScriptManager.java:51-58`); a missing script is a warning in the log, not an error on the client.
- Mob progress is a zero-padded 3-char string and all comparison is string equality
  (`src/main/java/client/Character.java:11598`, `src/main/java/client/QuestStatus.java:153-168`).
- Action selection: at most one action per `QuestActionType` per phase, because the maps are `EnumMap`s
  (`src/main/java/server/quest/Quest.java:69-72`) — two WZ nodes mapping to the same type cannot coexist.

## Origin

| Path | Class | Evidence |
|---|---|---|
| `src/main/java/server/quest/Quest.java` | modified | in `/root/workspace/cosmicms` but differs; history `6b9f1a07f` (ticket 93), `1494da578` (hidden guards), `9fa7d63f2` (endmeso), baseline `03d13cb21` |
| `src/main/java/server/quest/QuestRequirementType.java` | modified | differs; `END_MESO` added in `9fa7d63f2` |
| `src/main/java/server/quest/requirements/MesoRequirement.java` | modified | differs; constructor now takes the type (`9fa7d63f2`) |
| `src/main/java/server/quest/actions/ItemAction.java` | modified | differs; character-based refactor `82fb07dc1` ("Make quest item actions character-based so a bot can run them") |
| `src/main/java/server/quest/actions/MesoAction.java` | modified | differs; `499e36217` / `41ca56583` (NPC-fee and reward counters) |
| `src/main/java/server/quest/actions/SkillAction.java` | modified | differs; `a4731fa9f` (beginner/Maker skill gate, ticket 112) |
| `src/main/java/net/server/channel/handlers/QuestActionHandler.java` | modified | differs; `1494da578` added `refuseHidden` and the NPC/position guards |
| `src/main/java/server/quest/HiddenQuests.java` | custom | absent upstream; `a4731fa9f`, `de3252a5a`, `1494da578` |
| `src/main/java/server/quest/ZakumPrequest.java` | custom | absent upstream; `b69b73e3c` ("Add !zakumpq to stage the Zakum prequest for QA") |
| every other action/requirement, `QuestActionType.java`, `QuestActionManager.java`, `QuestScriptManager.java` | base | byte-identical to `/root/workspace/cosmicms` (`diff -q`), oldest commit `03d13cb21` |

`tools/cosmicdiff/classifications.json` carries no entry for any quest source path — only script-level entries such
as `script-short:scripts/quest/2001.js` (RESTORED) and `…/3414_free10rate.js` (RESTORED, dormant) — so the
classifications above rest on `diff -q` against the upstream tree and on `git log --follow`.

## Tests and QA

| Area | Tests | What is asserted |
|---|---|---|
| endmeso price | `src/test/java/server/quest/EndMesoRequirementTest.java:79,85,98` | `endmeso` is its own type, `money` stays the start price, underwater player cannot complete and keeps mesos, funded player pays to zero |
| hidden quests | `src/test/java/server/quest/HiddenQuestsTest.java:147-253` | medal lists, hidden quest refuses window/script/force start, hidden auto-start does not run acts, non-hidden twin still grants, job-specific hiding |
| Maker skill gate | `src/test/java/net/server/channel/handlers/MakerChainSkillGateTest.java:104,128,140,156` | chain refused without the Maker skill, unrelated quest unaffected, opens once the skill exists, follows the job family |
| Zakum prequest | `src/test/java/server/quest/ZakumPrequestTest.java` (2) | eye-of-fire completion path |
| scripts | `src/test/java/scripting/QuestScriptCoverageTest.java:67-68`, `QuestScriptHandInTest.java` (3), `CosmicRestoredQuestScriptTest.java` (5), `MakerQuestChainTest.java` (5), `FamilyQuestScriptsTest.java` (8), `TempleForceFieldQuestTest.java` (5), `BalrogQuestCreditTest`, `RoseGardenQuestRecordTest` (2), `TimeLeapQuestRecordTest` (2) | every `startscript`/`endscript` quest has its JS with that function; hand-in, restored-script and family/theme record behaviour |
| persistence | `src/test/java/client/QuestPlaceholderSaveTest.java` (7) | empty placeholder statuses are not written back |
| accounting | `src/test/java/soloMapling/Economy/stats/ScriptMesoFeeTest.java:85` | mesos a quest takes are booked as an NPC fee, not a sink elsewhere |
| achievements | `src/test/java/soloMapling/achievements/LoginFactsAndQuestHookTest.java:83` | a scripted completion counts once; repeating a done quest does not |
| bots | `src/test/java/soloMapling/ArtificialPlayer/BotQuestStateBoundaryTest.java:17-21`, `BotQuestSystem/*` (17+8+5+5+5+4) | bots carry no persisted quest state, index/scorer/log/gate-fault behaviour, kill-quest coverage of the shipped WZ |
| data QA | `tools/questcheck/quest_check.py` (+ `test_quest_check.py`) | offline `Quest.wz` integrity: dangling item/NPC/mob/map/skill/job/prereq refs, unreachable quests, optional `--cosmic-wz` diff; deliberately "leads, not verdicts" |
| auto-QA | `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:768`, `:802-812`, `:1328`, `:1431-1438` | jump-quest bounce check (manual), mount chain incl. `fee=20000000` and the refund/bottle assertions, Maple Island opt-in, bot-quest and temple probes |
| `!qa` | `src/main/java/soloMapling/qa/QaScenarios.java:297-312` | `jq-1` bounce block in `FOREST_OF_PATIENCE_1` (101000100) |

Not covered: no test drives `QuestActionHandler` packet bytes end to end (the proximity window, the `selection`
short and the forfeit path have no test); `ExpAction`, `FameAction`, `BuffAction`, `PetSkillAction`,
`PetSpeedAction`, `PetTamenessAction`, `NextQuestAction`, `QuestAction` and `Quest.getInfoEx`/`getMatchedQuests`
have no unit tests; no test asserts live/TEST switch values; `TOT_MOB_QUEST_REQUIREMENT > 0` pre-fill is untested;
`questprogress`/`medalmaps` schema drift is only covered indirectly by the placeholder test.

## Known issues

| Card | What | Where |
|---|---|---|
| 82 | Hidden quests are hidden server-side but the client still lists them (it reads its own `Quest.wz`); finish content to un-hide | `docs/tickets/82-finish-mapleroot-quest-chains.md:8-15` |
| 101 | v83 quest pass is open: no comparison against a reference quest list, `quest_check.py` findings untriaged, no TEST quest runner, 20–30 quests unspotted by the owner | `docs/tickets/101-v83-quest-pass.md` |
| 112 | Maker chain 6029–6036 gated on the character actually having the Maker skill; Moren lists all four skills | `docs/tickets/112-maker-skill.md:5`, `src/main/java/server/quest/HiddenQuests.java:90-93` |
| 158 | Bot questing built but switched off and not on TEST; Temple chain still closed | `docs/tickets/158-bot-questing.md`, `/root/work/STATUS-1.6.4.md:115` (`e05`) |
| b279 | Maker quest 2 (6033) only ever counts an Intermediate Monster Crystal 1 made from monster drops | `/root/work/STATUS-1.6.4.md:62` |
| e15 | Lion King's Castle quest line not audited end to end | `/root/work/STATUS-1.6.4.md:125` |
| Q3 | Quest-gated bosses (Magnus 31166, Normal Lucid 31161, Will 31162, Darknell 31163, Verus 31164) deliberately not seeded for bots | `/root/work/OWNER-DECISIONS.md:117`, `src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:218-231` |
| Q18 | Elnath PQ gate asks quest 6192, which nothing grants; delete or re-gate | `/root/work/OWNER-DECISIONS.md:122`, `scripts/npc/2020008.js:29` |
| PQ-159 | Same gate seen from the PQ side: `6192` appears only in that NPC script, and `ElnathPQ.js` is a 15 s respawn loop with no `clearPQ` — blocks humans too | `/root/work/PQ-UNFINISHED-REPORT.md:159-175` |
| NEW-FINDING | `PetSpeedAction` constructs itself as `QuestActionType.PETTAMENESS`, so a quest declaring both `petspeed` and `pettameness` loses one silently through the `EnumMap` | `src/main/java/server/quest/actions/PetSpeedAction.java:34-37`, `src/main/java/server/quest/Quest.java:169-179` |
| NEW-FINDING | `QuestActionType.QUEST` (id 4) is never produced by `getByWZName`, so `QuestAction` — the action that force-sets another quest's status — cannot run from `Act.img` at all | `src/main/java/server/quest/QuestActionType.java:54-93`, `src/main/java/server/quest/actions/QuestAction.java:58-62` |
| NEW-FINDING | `QuestActionType.INFO` and `ZERO` share the id byte 16; `getAction` has no `ZERO` case, so every `0` action node in `Act.img` is dropped silently while `info` works | `src/main/java/server/quest/QuestActionType.java:45-46`, `:87-89`, `src/main/java/server/quest/Quest.java:609` |
| NEW-FINDING | `CompletedQuestRequirement` requires *N finished quests total*, not quest N — a WZ author reading `questComplete` as a quest id gets a silently different gate | `src/main/java/server/quest/requirements/CompletedQuestRequirement.java:49-50` |
| NEW-FINDING | Handler cases 0 (restore lost item) and 3 (forfeit) skip `isNpcNearby`, so a crafted packet can forfeit or restore from anywhere | `src/main/java/net/server/channel/handlers/QuestActionHandler.java:88-92`, `:121-123` |
| NEW-FINDING | `InfoNumberRequirement`, `InfoExRequirement` and `ScriptRequirement` return `true` unconditionally while still occupying a requirement slot — they are data, not gates, and look like gates in `getRequirement` | `src/main/java/server/quest/requirements/InfoNumberRequirement.java:49-51`, `src/main/java/server/quest/requirements/InfoExRequirement.java:58-60`, `src/main/java/server/quest/requirements/ScriptRequirement.java:45-46`, `src/main/java/server/quest/Quest.java:513-516`, `:559` |

## State

**partial.** The engine loads and runs: the parser covers all three `Quest.wz` images and is exercised by
`src/test/java/server/quest/EndMesoRequirementTest.java` and `HiddenQuestsTest.java`, boot calls
`Quest.loadAllQuests()` (`src/main/java/net/server/Server.java:951`), and the endmeso price gate is proven by test.
What is incomplete is content, not mechanism: hidden chains are still unfinished by design
(`src/main/java/server/quest/HiddenQuests.java:12-32`), the v83 pass (ticket 101) and the Maker quest
accounting (b279) are open, bot questing is off (`src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuests.java:63`),
and the live/TEST quest-rate values could not be read from this worktree.

## Open questions

- Which quest-rate / `QUEST_POINT_*` / `TOT_MOB_QUEST_REQUIREMENT` values live and TEST actually run? Live's
  `config.yaml` was deliberately not opened (`docs/release/1.6.4.conf:271` lists no quest key) and TEST's
  `start-test.sh` is on another host.
- Is the `endmeso` audit of all 54 live quests (requirement + negative `money` action, or a script charge) still
  outstanding? `9fa7d63f2` explicitly names 20526, 6030, 6031, 6032 as script-charged; none has a regression test.
- Does any WZ quest declare both `petspeed` and `pettameness`, or both `info` and `0`? That decides whether the two
  `EnumMap` collisions lose real content.
- Whether `case 0`/`case 3` skipping the proximity check is intentional (they mutate state with no NPC present) or an
  inherited gap.
- Whether `CompletedQuestRequirement`'s count semantics match the intended `questComplete` meaning on the live dataset
  — `tools/questcheck/quest_check.py` does not currently report it.
- How much of the 266 `scripts/quest/*.js` set is reachable given `HiddenQuests`; `QuestScriptCoverageTest` proves
  scripts exist, not that the quests can be started.
