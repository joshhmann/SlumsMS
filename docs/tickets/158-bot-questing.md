# 158: Bots do quests

**Status:** items 1-4 **built, switch off, not on TEST yet** (branch `162-questing`, 2026-10-30); item 5 open.
1.6.2 / 1.7.0. **Owner:** Server Dev. Source: owner, 2026-10-30, after the nutnnut/Cosmic README_SERVER_BOTS.md review ("what we havent adopted yes update tickets or create new ones"). Upstream is AGPL-3.0 like ours: credit `nutnnut/Cosmic <sha>` in port commits and docs/PROVENANCE.md. Matrix: 157-nutnnut-tools/adoption-matrix.md.

Ours today: bots never take or finish quests (ExpeditionBots only reads quest status). Upstream: BotQuestIndex,
BotQuestManager, BotQuestScorer, BotTempleProgressionManager, quest chat.

1. **Quest index** (S): from Quest.wz, the unscripted quests a bot can finish by killing mobs or collecting drops,
   with start and end NPCs.
2. **Mob-quest piggyback** (M-L): a bot training on a map takes a matching quest, detours to the NPC, finishes it
   through the real quest path. A BotLife goal, not a side errand. Watched bots only; offscreen bots just record a
   completion.
3. **Quest worth** (S): EXP per minute vs travel, so bots take quests real players would.
4. **Chat** (S): "what quests r u doing", "recommend quest" answered from the bot's own state (ticket 156).
5. Later, a model for content chains: Temple of Time mainline with lane dispersal (M).

Risks: `queststatus` rows across the ~9k roster (store only active and completed, cap per bot); quest rewards count
against the yield caps; never NPC bypasses. Tests: fail-before unit tests per step; an auto-QA check that a watched
bot completes one mob quest legally.

## Built (2026-10-30, branch 162-questing; nutnnut/Cosmic 77e0c5bc9)

- **Switch:** `-Dslums.botquests.enabled=true` (default off; needs the life brain, `slums.botlife.enabled` and
  `slums.botlife.execute`). Tunables `-Dslums.botquests.perDay` (6 quest completions per bot per UTC day) and
  `-Dslums.botquests.keepDone` (32 completed ids kept per bot). Not yet in `start-test.sh` or a release conf.
- **1 Index** `BotQuestIndex`: 195 runnable kill quests over 167 mobs in the server's Quest.wz (unscripted, both NPCs,
  kills only, no time limit, actions a headless bot can take).
- **2 Piggyback** `BotQuests` + `LifeGoals.Kind.QUEST` + `LifePlanner` steps TAKE_QUEST -> HUNT_QUEST ->
  TURN_IN_QUEST (shown by `!botwhy`). A grinding TrainingBot picks a quest whose every required mob spawns on its map
  and whose NPCs stand on that map, its return town or its home town (at most 4 hops). Watched (full-sim map): walks
  to the NPC, real `Quest.canStart/start` and `canComplete/complete` within the player handler's NPC distance; kills
  count through `raiseQuestMobCount`. Offscreen: the log records taken, modelled kills (offscreen kill rate x spawn
  share) and a completion with no rewards. Meso and item rewards are taken through `BotYieldCaps` first; refused =
  the turn-in waits, 3 waits = recorded without rewards. Stale quests drop (2 h untaken, 24 h unfinished).
- **Storage:** `BotQuestLog` inside the existing `botlifeprofile` row (key "quests": one active quest + capped
  completed ids + counters). Roster bots never write `queststatus` (they are not saved through `saveCharToDB`);
  `Character.enableBotQuestState()` lets a questing bot hold quest state in memory only.
- **3 Worth** `BotQuestScorer`: reward EXP (at the quest/kill rate ratio) plus off-map kill EXP over the bot's grind
  EXP/min forgone on the trip; take at >= 1.5x.
- **4 Chat** `ChatTriage.Intent.QUESTS` / `RECOMMEND_QUEST` (rules) -> `BotChatActions` -> `BotQuestChat` from the
  bot's log; the model gets the fact, the reply must name the quest, a bot with no quest may not claim one. The
  brain's prompt facts carry the active quest.
- **Not done:** a `!qa` scenario (the auto-QA check now exists: `t-bq-1`, `Probes162.botQuest`, see docs/qa/QA.md), TEST switch-on
  and the 24 h row/size check; no real-client check of the quest effect a player sees.

## Item 5 built (2026-10-30, branch 162-contentruns; nutnnut/Cosmic 77e0c5bc9)

The Temple of Time mainline driver, on this ticket's quest path (merged from `162-questing`), no second quest system:

- **Switch:** `-Dslums.temple.enabled=true` (default off), on top of `-Dslums.botquests.enabled` and the life brain.
  Tunable `-Dslums.temple.laneCap` (3). Not in `start-test.sh` or a release conf.
- **A BotLife goal:** when a grinding bot asks `BotQuests.consider` for a quest, `TempleOfTime.consider` hands it the
  next mainline quest (3500-3520) as its active quest, so the life brain's `QUEST` goal and its TAKE_QUEST ->
  HUNT_QUEST -> TURN_IN_QUEST steps play it (watched: the real `Quest.start`/`complete` at the NPC; offscreen: a
  recorded completion, no rewards), like any quest. A bot starts the chain at its own "ambition" level (90-125,
  steady per bot) and each quest at its Quest.wz level. Progress is `BotQuestLog.templeDone` (saved in the life
  profile), so the chain survives the capped completed list.
- **Lane dispersal** (`TempleLanes`): the lane map and channel are picked with the quest. At most `laneCap` Temple
  hunters share one lane map of one channel (real players count double); for the overflow the life brain is offered
  a "cc" to a channel with room (`LifeRuntime`'s channel target, carried out by `LifeChannelMove` with its checks);
  when every channel is full the bot grinds elsewhere and asks again in 1-3 minutes.
  Each v83 lane quest has one reachable map (the lane monster's second map lies past the quest's own exit), so the
  spread is over channels.
- **The corridor:** the lane exits (`timeQuest.js`, portal `in00`) are transit edges only a bot that completed the
  exit's quest may take (`BotTransit.templeExits`, `questGate`, refused at boarding in `GCTravel`), and `BotMapGates`
  only sends a bot to a Temple map its progress opens. Both only with the switch on.
- **Scripted steps through their scripts** (`BotQuestScript`, a bot-safe `qm`): 3507's end at the Memory Keeper after
  the job's sub-quest (3523-3539) at its first instructor (the driver makes the detour and back), and 3514's start
  (the Sorcerer charges 1,000,000 mesos for the potion; offscreen the fee is still paid) and end (the bot drinks the
  potion first). No `forceStart`/`forceComplete` from outside a script (upstream forces them).
- **Not driven:** 3521 (Force Field, a six-item turn-in from the lane bosses and masks): the kill-quest path has no
  item hunt yet. No `!qa` scenario or auto-QA check yet (a lane is 999 kills: needs a staged-progress scenario).
- **Tests:** `TempleRouteTest` (lane exits by running `timeQuest.js`; with `wz/`: every quest, NPC map, lane and
  sub-quest against Quest.wz/Map.wz - run with `-Dwz-path`, all pass), `TempleOfTimeTest` (the chain in order,
  progress saved, ambition/level/fee, lane cap over channels, the corridor gates, the 3507 detour, the fee offscreen),
  `TempleScriptsForBotsTest` (the scripted steps' scripts against the bot-safe `qm` surface), `GCTravelTempleExitTest`.
  Not run on TEST; no real-client check.
