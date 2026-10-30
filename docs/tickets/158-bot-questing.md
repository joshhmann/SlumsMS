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
- **Not done:** the auto-QA check (a watched bot completes one mob quest legally), a `!qa` scenario, TEST switch-on
  and the 24 h row/size check; no real-client check of the quest effect a player sees.
