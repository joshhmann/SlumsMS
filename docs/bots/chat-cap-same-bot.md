# chat-cap-same-bot (1.6.6): expected outcome

Card: chat-cap-same-bot (t-pl-1 "A player heard one regular twice in 35 min, against the one-bot-chat-per-player-hour
rule"). Written before the code (1.6.6 pilot rule).

## Why

The owner's answer 4 (m02, 31 Oct, 2567554d7): at most one bot-started chat per player per hour, from any bot.
`SocialGate.botChatAllowed` lets the bot that started the last chat through at any time ("the same bot may carry its
own chat on"), so the same regular can open a brand-new chat inside the hour. Usually the pair's 40-minute greet
cooldown hides it; on TEST 7 Nov (c614ea6f7) a PQ-lobby wrap-up (`SocialInitiative.java:1163`, `forgetBotBudget`)
had cleared that cooldown, and bladestar98 opened two chats with AutoQaOne 34 min 44 s apart (10:15:51 and 10:50:35
UTC). t-pl-1 failed on it.

## Expected outcome

1. A regular may open a chat with a player only if no regular opened one with that player in the last hour - the
   same regular included - unless that regular's chat with the player is still going (`BotConversation.isTalking`,
   the chat's own idle window), which is the "carry its own chat on" the rule meant.
2. Another regular inside the hour still waits (unchanged); the hour up, anyone may (unchanged); the cap is per
   player (unchanged); the gap stays `-Dsolomapling.chat.botStartedPlayerGapMs` (1 h).
3. t-pl-1's finding names chats, not bots: "player X heard 2 bot-started chats in 35 min from: bladestar98,
   bladestar98".
4. Nothing else a player sees changes: replies inside a live chat are not openers and never read the cap.

## Proof

- SocialGateTest: the same bot, no live chat, at +35 min is refused (fails before, passes after); with its chat live
  at +1 min it is allowed; +60 min allowed.
- PlannerRepliesTest: the finding's wording.
- After the TEST deploy: t-pl-1 passes on the next nightly (it judges the transcript since boot).
