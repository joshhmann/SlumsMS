# 158: Bots do quests

**Status:** open, 1.6.2 / 1.7.0. **Owner:** Server Dev. Source: owner, 2026-10-30, after the nutnnut/Cosmic README_SERVER_BOTS.md review ("what we havent adopted yes update tickets or create new ones"). Upstream is AGPL-3.0 like ours: credit `nutnnut/Cosmic <sha>` in port commits and docs/PROVENANCE.md. Matrix: 157-nutnnut-tools/adoption-matrix.md.

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
