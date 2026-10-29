# Ticket 114: bots talk to bots (a goal-driven decision tree), greetings, friend crews, group chat

Status: **built**, branch `161-botchat` merged on top of release-1.6.1 49be99937 as `161-botchat2` (2026-10-28), **not
on TEST yet** (TEST's start-test.sh already has both flags; its jar lacks the code until the next deploy), **on in the
1.6.1 release conf** (`-Dslums.bottalk=true -Dslums.crews=true` in the single UNIT_EDITS array, with achievements).
Checks: `!qa run bt-1`, auto-QA `t-bt-1` (now also: after 5 min of the engine with 20+ bots online, the tick has started
a greeting or chat by itself, `organicTalks` in the stats) and `t-bt-2`. Ticket number: 114 also named "bots play the
mechanics" on 170-mechanics, which was renamed to 130 there, so 114 stays bot talk.
Roadmap rows (docs/roadmap/1.6.1.md): "Bot-to-bot chat skips the classifier", "Bots greet each other", "Friend crews";
and "multi-person chat" from 1.7.0's "The brain learns".

## Say-do: a yes is carried out (161-saydo, 2026-10-29)

TEST 2026-10-28 showed the talk acts mostly did not happen: 7 of 178 come-alongs reached the town, crews sent 1 of 2
followers, 20 PQ yeses broke at the act on "LFG rules now say no", duo parties were dropped as "left the map" and never
told the say-do ledger, help never applied to a helper on the asker's map, and 17 story yeses kept nothing. Per kind:

- **Come-along / crews:** a yes only set a pull on the next life decision; a bot hanging out kept its stay
  (KEEP_CURRENT), a grinder kept grinding. Now `LifeRuntime.goTogether` hands the hang-out in that town to the bot's
  own life queue, ending a plain hang-out stay first (`handOff`); the asker goes first, each yes 2-6 s later, and the
  asker goes even when nobody comes (its line said it was going). Crew followers get the same hand-off after their
  delay (`BotCrews.handOff`, retried while held). A bot that cannot drop what it is doing (`commitBlocker`: on a trip,
  restocking, in a PQ lobby, at an event, held by a trade, chat or log-off, about to start something) says no with
  that reason, and a bot that cannot go never asks.
- **PQ party:** the LFG plan (and its slot of the 3-per-10-min cap) is taken when the ask is made, so the other's
  answer is the rules' own; the act forms that plan. LFG trips end a hang-out stay too (the queue started nothing while
  an activity was active, so the pair's second bot often never left). `LfgParty.form` tells the say-do ledger (PARTY).
- **Duo party:** both online on one channel is enough (no same-map check); the ledger hears it.
- **Help:** the helper must be grinding the asker's map; the yes parties the two and keeps the helper grinding there.
- **Story:** told only to a bot that would keep it (`BotGossip.wouldKeep`).
- **Words:** a bank line is re-checked against this build's claim rules, so a refusal never says "hold on" and a help
  yes never says "omw".

`TalkPromises` judges every yes (party: one party; come-along: reached the town, or on its way when 5 min are up;
help: partied; deal/story: the act went through): `botTalk.promises` in bot-life.json, auto-QA t-bt-3 (>= 80%). The
daily chat-quality line now shows bot talk's own say-do ("bot talk 69% of 314") next to the ad-heavy overall rate, and
t-cq-1 fails it under 80%.

b042 (same branch): any crew member's hang-out trip to a town is the crew's pick (keyed by the leader, at most 4 move,
2-6 s apart, a fresh pick another member made under 2 min ago is not pulled elsewhere), not only the leader's; and
when a bot arrives next to one it knows, either may say hi first (`BotTalk.greetOpener`; the one standing there opens
40% of the time, and greets when the arrival does not), under the same pair gap and world cap.

b068 (161-saydo2, 2026-10-29): t-cq-1 failed at 67% bot-talk say-do 51 min after the say-do build went on TEST, but its
24 h window was almost all the old build (since boot: talk-bank 33/33, talk-lines 50/50, thread-llm 15/15; the yes
ledger 232/239). t-cq-1 now judges the bot-talk rate on the claims since boot (the day's rate stays in the report as
`talk24h`), and a thread speaker already in a party may no longer say "lf party" (7 such lines on 2026-10-28).

## The owner's shape (2026-10-27)

"playerbot says hi to another playerbot -> casual convo -> asks to party -> other bot decides to say yes or no and in a
certain way -> all dependent on bots' internal goals". A speaking bot already knows what it means, so its line carries
that intent and the listener's brain answers it directly: no classifier for bot lines, the model only words lines and
only when a real player can see them.

## What was built

- **The tree** (`BotConvo/TalkPlanner`, decisions in `TalkBrain`, pure and unit-tested): greet -> greet back -> small
  talk on one topic (the opener's memory note about the other, else where they are) -> the opener's ask, picked from
  its goals and plan among what the game allows (PARTY, TRADE, HELP, COME_ALONG, GOSSIP; rivals ask no favours) -> the
  listener's answer YES / NO / COUNTER in a manner WARM / CURT / RUDE -> the act after the settling line -> bye.
  - What the game allows comes first and its reason is the answer: the LFG rules for a PQ party (partied, too low, its
    shop is open...), the trade rules' deal, a town for its level with room, a daily life to travel with.
  - Then the bot's will: bond (friend / rival / score), Bartle point (Socializer warmer and more willing, Killer
    ruder), mood (fatigue, supplies and loneliness from its last brain decision; a seeded hourly mood for bots without a
    daily life), its goal (LEVEL wants parties, not carrying; COMPANY wants to come along; MESOS trades) and its plan
    (a grinder near its goal turns a come-along down, often countering with the town it was going to anyway).
  - A counter (come along to my town instead) is answered by the opener with the same will.
- **Acts** through existing actions: a PQ party (the LFG rules decide again, take a cap slot, form the party and hand
  both bots the trip) or a plain duo party (cap 6 per 10 min, breaks up after 10 min unless a real player joined);
  `BotBotTrade.execute`; HELP sets the helper's HELP_FRIEND target (`LifeRuntime.botHelp`); COME_ALONG sets both bots'
  next hang-out (`LifeRuntime.invite`, a +0.7 hang-out pull, map caps checked on the way); GOSSIP is kept
  (`BotGossip.keep`).
- **Words** (`TalkLines`): the line bank where it has the key (greeting, party/help/come yes and no, trade no, bye) in
  the manner's column (warm = friend, curt = neutral, rude = rival) and the bot's voice; short built-in lines for the
  asks, counters and grounded small talk (PG-13, screened by a test with the content guard). The model words a whole
  talk only with a real player within view (not a pretend auto-QA player, not one with `@quiet bots`), at
  `Priority.THREAD` (only when every slot is free) and at most `slums.bottalk.llmPer10Min` (6); each line it writes is
  kept only when its speaker matches, it keeps the decided answer (a yes that refuses or a no that agrees is dropped),
  and it passes the content guard and the name guard; else the bank line stays.
- **Intent with the line** (`BotLineIntents`): every talk line and every 1.6.0 thread line is noted with its intent.
  `BotConversation` refuses a bot speaker at the door with the switch on (counted `keptFromClassifier`); the count of
  bot lines that reached the classifier (`classifiedBotLines`) is 0 by construction and checked by t-bt-1.
- **Greetings** (`BotTalk.tick`): a bot arriving on a map greets a bot there it knows (a bond, or a memory note),
  strongest bond first, once per pair per 20 min, 20 a minute world-wide; a sociable pair may go on into a chat.
- **Chats and groups**: per map a gap of 2-5 min (`slums.bottalk.gapMin/gapMax`), towns and the FM always, grind maps
  one time in ten, boss/event/dungeon maps never, `slums.bottalk.talksPerMin` (12) world-wide, never on a map where a
  1.6.0 thread is going on. Bots talk where they stand (within 600 x 250 px): nobody walks over, personal space is
  untouched. Three or four friends standing together talk as a group (or 20% of the time any three): turns go round by
  chattiness and never to the one who just spoke, the topic carries over, every listener answers the ask itself (a
  group ask is a come-along or a party), and every yes is acted on.
- **Players join in**: each talk is a live thread for chat routing, so a player's line near it joins it and the bot
  that answers uses the normal player path (classifier + model); a player who names a bot always does.
- **Memory**: each talk queues a short note for both bots ("asked to party a duo (yes)", "went to Ellinia together",
  "said hi in Henesys"); the next tick writes them in one batch into the life memory (`LifeMemory.noteMet`, at most 6
  per bot, saved with the life profile as `met`) and fills the SOCIAL need. Greetings pick known bots from the notes too,
  and small talk brings the last note up.
- **Friend crews** (`BotAffinitySystem/BotCrews`, wiring `BotBonds.crewOf` / `crewTownMap`): the crew's leader on a
  channel is its lowest id online there; when its brain starts a hang-out in a town, that town is the crew's pick for
  10 min and up to 3 friends (`slums.crews.max` 4 with the leader) are nudged to decide after 2-6 s, then 3-5 s more
  each; their next hang-out is the pick (`LifeTowns.Source.CREW`, +0.7 pull). With no fresh pick, a crew mate standing
  in a town pulls the others there. Every arrival still goes through the bot's own map-cap checks.
- **Social trace**: each talk is a trace entry (topics GREET, TALK, TALK_PARTY, TALK_COME_ALONG, TALK_HELP,
  TALK_TRADE, TALK_GOSSIP) with its lines, how they were worded, each answer and why (goal, bond, mood, Bartle), the
  act and the notes; bot talks may happen with no player present (no trigger FAIL for that).
- **Load**: one pass over the online bots per 10 s tick, grouped by map; a map is only read when it is due or a bot
  just arrived; a few talks a minute. Its CPU goes to `data/autoqa/bot-life.json` as `botTalk.cpuNanos` (with
  `ticks`), next to the brain's `cpuNanos`; t-bt-1 fails above 20 ms a tick on average.

- **Chat-split context** (161-botchat2): bot talk lines are public, so they reach `MapChatLog`. The earlier lines
  sent with a player's line to the intent+con model (:8913) now keep only the player's lines, the spoken-to bot's own
  lines and someone else's line naming that bot or the player (`ConvoSense.earlier`, ChatDecideContextTest), so
  bot-to-bot chatter cannot push the player's build-up out of the five context lines.

## Switches

`-Dslums.bottalk=true` (the talk engine and the classifier refusal), `-Dslums.crews=true` (crews). Knobs (code
defaults): `slums.bottalk.gapMin` 2, `.gapMax` 5, `.talksPerMin` 12, `.greetsPerMin` 20, `.llmPer10Min` 6,
`.partiesPer10Min` 6, `.groupChance` 0.2; `slums.crews.max` 4.

## Not done / follow-ups

- Not checked on a real client: the lines' look and pacing in game (the talk engine only uses the bots' normal chat).
- The 1.6.0 threads (`BotThreads`) still run as they were (decided first, the model words them when a player is on the
  map); they now note their intent with each line. Folding their special topics (rival beef, drama, guild beef, tips)
  into the tree is a later step.
- A COME_ALONG or HELP yes is a strong pull on the bot's next decision, not an order: a bot mid-errand finishes it first.
