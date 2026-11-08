# alt-quest-mirror (1.6.7): expected outcome

Card: alt-qol-batch group E, quest mirror. Written before the code.

Switch: `PartyCompanionPolicy.Feature.QUEST_MIRROR` (key `quests`). Owned alts are on by default
(`-Dslums.companion.quests.alts`, default true) and party regulars off (`-Dslums.companion.quests.regulars`, default
false), so a regular behaves exactly as it does today until an operator turns that switch on.

**Second gate, the owner's call (8 Nov 2026):** mirroring is **off for every owned alt** until its owner types
`@botme quests on <name>`; `@botme quests off <name>` stops it. The state is per alt, in memory (the base has no
settings store for alts yet). Both gates must be on: the policy switch and the owner's command. A party regular has no
owner command in this card; `-Dslums.companion.quests.regulars=true` is its whole opt-in.

## Why

Today an owned alt or a party regular that follows its player into a hunting map does not share the player's quest
book. The player starts "collect 50 horny mushrooms", the alt stands beside them and stays at zero, and when the player
turns the quest in the alt has nothing to turn in. The alt's own quests come only from the bot quest planner
(`BotQuestSystem`), which picks quests for a bot on its own and does not know the player's progress.

The owner's rule (card alt-quest-mirror) is that a companion in the party should *quest along*: it starts what the
leader starts (if it may), it gets the same mob credit while it is beside the leader, and it turns in what it may turn
in, from its own bag, with its own rewards.

## Expected outcome

All of this is gated by `PartyCompanionPolicy.on(Feature.QUEST_MIRROR, companion)`; owned alts additionally need the
owner's `@botme quests on <name>`.

1. **Start.** When a real player starts quest Q through the normal server path (`Quest.start`, i.e. the quest window or
   an NPC), every companion of theirs that is (a) in the player's party, on the player's channel, (b) opted in, and
   (c) able to start Q (`Quest.canStart`: level, job, prior quests, hidden list) starts Q through the same path
   (`Quest.start`, so start actions and their requirements run). A companion that cannot start it is skipped silently,
   with one debug log line. A companion that already has Q started is left alone.
2. **Kill credit.** A companion's own kills count for it exactly as today. In addition, while a real player kills a mob,
   every opted-in companion of theirs in the party on the **same map** gets the same mob credit through the existing
   counting path (`Character.raiseQuestMobCount`), so only quests the companion actually has started and that name that
   mob move, with the normal `QuestStatus.progress` rule and announcement. No new counting rule is introduced. The
   three pseudo mob ids the server uses to merge "green mushroom / zombie mushroom / ghost stump" quests
   (`9101000-9101002`) are not mirrored on their own call, because the companion's own `raiseQuestMobCount` already
   expands them.
3. **Completion.** When a real player completes quest Q (`Quest.complete`), every opted-in companion in the party on the
   same channel that has Q **started** and passes `Quest.canComplete` itself completes Q through `Quest.complete`:
   item requirements are read from the companion's own inventory, the actions take from and give to the companion, and
   the companion gets the rewards. A companion that does not meet the requirements itself stays started. Nothing is
   granted twice: `canComplete` requires the status STARTED and completing sets it to COMPLETED.
4. **Reward choice.** A quest whose completion carries a reward selection (`Quest.complete` with a non-null selection,
   i.e. the client sent a chosen reward item) is **not** mirrored: no companion completes it, and one log line per
   completion says so. A reward-choice policy for companions (BotStory's `AgentQuestRewardChoicePolicy`) is the
   follow-up, written in the report.
5. **Nothing mirrored for bots' own quests.** A bot's own start/complete (the bot quest planner) does not trigger the
   mirror; only a real player's quest does.

## What existing characters keep

Nothing is taken away. The feature only ever *adds* a start, a kill count or a completion to a companion; it never
clears, rewrites or forfeits a quest, never takes an item or meso the character did not have to give (item requirements
are checked and consumed by the existing `complete` path, exactly as when the player turns the quest in), and never
touches a setting.

- A companion that is not opted in, not in the party, not on the same channel/map, or whose owner never typed the
  command is unchanged: its quest book, its `BotQuestSystem` planner and its own kills behave exactly as today.
- Party regulars are unchanged by default (`slums.companion.quests.regulars` is false).
- A companion that already has a quest started or completed, or has its own progress, is never reset: a start is
  skipped when the status is not NOT_STARTED (`canStartQuestByStatus`), and a completion is skipped unless the status is
  STARTED and the companion meets the requirements.
- No migration is needed, because no table, column or saved setting is added: the opt-in state is in memory and is lost
  on a restart, which only turns mirroring off again (the safe direction).

## How the tests prove it

`src/test/java/soloMapling/companions/CompanionQuestMirrorTest.java` (real `Quest` objects from a small WZ fixture, the
`HiddenQuestsTest` style, mocked characters/party/world):

- `altOptedInStartsWhatThePlayerStarts` - a real player starts Q with an opted-in owned alt in the party on the same
  channel -> the alt's status is STARTED and its start actions ran. Fails before the change (the alt stays
  NOT_STARTED).
- `altWithoutTheCommandStartsNothing` - same setup, owner never typed `@botme quests on` -> the alt stays NOT_STARTED;
  after `@botme quests on <name>` (through the command) it starts.
- `altOutsideThePartyOrChannelIsNotStarted` - the alt is not a party member, then is a member on another channel ->
  nothing starts.
- `altThatCannotStartIsSkipped` - a level requirement the alt fails -> nothing starts, no exception.
- `altWithTheItemsCompletesOnceAndKeepsTheReward` - the alt holds the completion item -> the alt completes, receives the
  reward item exactly once, and a second completion attempt changes nothing.
- `altWithoutTheItemsStaysStarted` - the alt holds no item -> stays STARTED, the player's item is untouched.
- `rewardChoiceQuestIsNotMirrored` - the player completes with a selection -> no companion completes it.
- `leaderKillCreditsTheAltOnTheSameMap` - the player's `raiseQuestMobCount` moves the alt's mob progress; an alt on
  another map does not move.
- `regularUnchangedByDefaultAndMirroredWithTheSwitch` - a party regular (not an owned alt, `FollowerBot` leader) gets
  nothing with the defaults and starts/completes with `-Dslums.companion.quests.regulars=true`.
- `playerQuestIsNotMirroredBackToAnotherPlayer` - a real player in the party never gets anything.
