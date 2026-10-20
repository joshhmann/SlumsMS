# Bot chat content review — October 2026 (ticket 47)

Every bot dialogue pack under `src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/` was read in full,
line by line, against one test: could this line have been typed by a teenager playing GMS v83 around 2008, in a
town that is busy? The review removed lines that fail it and rewrote the ones worth keeping. The decision files
(one `D`/`R` line per change, with a reason) are in `docs/chat/content-review-2026-10/decisions/`. The diff on branch
`chat-content` is the record of what changed.

## What was taken out, and why

| Reason | Typical examples (before) |
|---|---|
| Modern slang (post-2010) | "maple at 3am hits different", "no notes.", "periodt", "respect the drip", "we built different", "sure jan", "let him cook", "skill issue", "its giving... something" |
| Anachronisms | discord, venmo, doordash, 144hz, ryzen, "MAPLE HACKS 2024", "BOT 2026 EDITION", jujutsu kaisen, chainsaw man, valorant, "6090" |
| Not in v83 | potential system, chaos zakum helm, "hit 200m damage", "1m range", daily login bonus, achievements, guild applications, global-chat mute, item "legendary" tiers |
| False world claims a player could act on | "server is gonna go down for maintenance soon", "the drop rate seems insane right now", "giving away free chaos scrolls first 5 people", "MASSIVE event starting in 5 mins", "someone said they saw a GM", server-lag/DC complaints, hidden portals |
| Fourth wall / private-server meta | "bots act like real people and I'm here for it", "all the bots have such different vibes", "new to v83?", "anyone else feel nostalgic playing v83 again?", "should just wipe" |
| A dead or dying server | "the channels used to be full. now I pick one and it's just me and NPCs", "where is everyone", the FM-ghost-town lines in FMBot's Rare node |
| Generated filler (most cuts) | the long tails at the end of nodes: "the journey matters more than the goal", "each region has its own vibe and aesthetic", "the social dynamics are lowkey complex", therapist and life-poster voice, retail ad voice ("LIMITED TIME DEAL 70% off select items") |
| Wrong speaker or wrong facts for the service | blackjack *player* bots talking in the dealer's voice, split / double down (the table has neither), the game-zone host's concierge voice, drop-game rules that did not match the code |
| Offensive | one MerchantBot "funny" line using "g4y" as the joke |
| Near-duplicates | 4 "this game is my therapy" megaphones, 3 "account recovery service" RWT lines, a dozen "scroll passed, im shaking" flexes |

Rewrites kept the line and fixed the problem: "make it make sense" → "how", "active discord" → "we have vent",
"is 30 atk work gloves good?" → "15 atk", "touché" → "touche" (ASCII only; the v83 client), "thunderbolt go brrr" →
"thunderbolt is so good", "the FM search function" → "the owl of minerva", "i'm in my town-chilling era" → "im just
chillin in town today".

## Counts per pack

"Rewritten" counts rewrite decisions (an exchange in TownChatter counts once). "Added" is new lines: the world-event
megaphone nodes and 36 new TownChatter exchanges.

| Pack | Lines before | Kept unchanged | Rewritten | Dropped | Added | Lines after |
|---|---:|---:|---:|---:|---:|---:|
| BlackjackDealerBotDialogue | 893 | 382 | 11 | 500 | 0 | 393 |
| ConversationDialogue | 1965 | 1698 | 54 | 213 (36 conversations) | 0 | 1752 |
| DropGameBotDialogue | 352 | 220 | 29 | 103 | 0 | 249 |
| DropGameSpectatorDialogue | 537 | 334 | 2 | 201 | 0 | 336 |
| FMBotDialogue | 594 | 417 | 11 | 166 | 0 | 428 |
| FollowerBotDialogue | 58 | 56 | 1 | 1 | 0 | 57 |
| GameZoneHostBotDialogue | 674 | 100 | 6 | 568 | 0 | 106 |
| HenesysBotDialogue | 568 | 506 | 10 | 52 | 0 | 516 |
| JQBotDialogue | 528 | 433 | 3 | 92 | 0 | 436 |
| LogoutDialogue | 14 | 14 | 0 | 0 | 0 | 14 |
| MegaphoneDialogue | 972 | 663 | 19 | 290 | 33 | 715 |
| MerchantBotDialogue | 514 | 382 | 8 | 124 | 0 | 390 |
| ScrollingBotDialogue | 556 | 454 | 17 | 85 | 0 | 471 |
| ShopOfferDialogue | 599 | 551 | 8 | 40 | 0 | 559 |
| SocialBotDialogue | 1188 | 1019 | 8 | 161 | 0 | 1027 |
| SocialHotPotatoDialogue | 942 | 723 | 7 | 212 | 0 | 730 |
| TownChatterDialogue | 72 | 58 | 14 | 0 | 116 | 188 |
| TownWandererDialogue | 72 | 67 | 2 | 3 | 0 | 69 |
| TrainingBotDialogue | 1737 | 1341 | 33 | 363 | 0 | 1374 |
| TutorialBotDialogue | 325 | 280 | 7 | 38 | 0 | 287 |
| **Total** | **13160** | **9698** | **250** | **3212** | **149** | **10097** |

Megaphone categories, lines before → after: Birthday 110 → 87, ItemSales 123 → 100, GuildRecruitment 115 → 92,
PQRecruitment 108 → 108, RWTSpam 89 → 58, Flex 106 → 60, RandomAnnouncements 160 → 86, SocialMessages 161 → 91.

### Notes per pack

- **GameZoneHost (568 dropped).** Two things were wrong. The code read out *every* line of DrinkOffer,
  DrinkSelection, ServeDrink and DeclineResponse, one every 5 seconds, so offering a drink recited about 80 lines. It
  now says one line (`executeBotFlavorDialogue`, `GameZoneHostBot.java`). Most lines were also in a hotel-concierge
  voice, or promised things the zone does not have (slots, chips, a rotating drinks menu; the drinks are Coke,
  Water and Elixir, all free). Lines carrying `%PLAYER_NAME%` outside PassiveGreeting were dropped, because the
  token is only filled there.
- **Blackjack (500 dropped).** The Player*, Hit and Stand nodes are spoken by the AI *players* at the table, but
  about half of their lines were in the dealer's voice ("card coming your way", "player stands. dealers turn.").
  DealerBanter and TableTalk are not referenced by any code. They were cleaned anyway.
- **DropGame.** The lines now match the code: entry by trade of exactly 10m or 50m, one paying player, items only,
  accept the party invite within 15 seconds. DropGameBot's TradeRules, PartyTimeout, Flavor, WinnerCelebration and
  GamePitch nodes, and the spectators' Disappointment and Commentary nodes, are not used by the code today.
- **TrainingBot.** Its `{MAP}` token is the map the bot is *on*, so MapTransition lines like "gonna go try {MAP}"
  named the map it was leaving; those were dropped or turned around ("heading out of {MAP}"). Three groups of
  `- {{line: ..., emote: N}}` items were malformed (yamlbeans kept the line but lost the emote). They are fixed.
  Wrong skills were also fixed ("drain ... bandit" → sin, "poison brace" → poison breath).
- **ShopOffer.** 13 CounterResponse lines never stated `{counter_price}`, so the player got a counter with no
  price. They are dropped.
- **TownChatter.** Several exchanges announced things that were not happening ("you see the drop rate today? it's
  been generous", "did the maintenance fix the lag?", "double exp this weekend"). They were rewritten. The pack had
  only 24 exchanges, so an hour in town replayed each one 2-3 times, sometimes a minute apart. 36 exchanges were
  added, and they are now dealt like a shuffled deck: none repeats until all have been used.
- **SocialBot Rare / FMBot Rare.** Most of these lines were generated melancholy about an emptying server. A
  handful of lighter wistful lines are kept.
- **Not changed:** TutorialBot's functional nodes (admin grant, gifts, sendoff) and LogoutDialogue.

## RWT spam — owner's decision

RWT megaphones were 19% of ambient megaphone picks. **They are now 3% by default.** That is about one RWT ad every
2-3 hours of play at the current megaphone rate (13-16 megaphones an hour): enough to feel like a 2008 server, without the spam. The RWT lines
themselves were cut from 89 to 58. The ones kept are period-plausible fake-site, AIM/MSN and paypal jokes. The ones
removed named venmo or used years after 2009, or were "trust me bro" jokes that broke tone.

This is the owner's call. Every category weight is a `-D` property, so it can change without a build:

```
-Dsolomapling.megaWeight.RWTSpam=3            # default; 0 turns RWT off, 19 restores the old share
-Dsolomapling.megaWeight.BirthdayMessages=5
-Dsolomapling.megaWeight.ItemSales=12
-Dsolomapling.megaWeight.GuildRecruitment=10
-Dsolomapling.megaWeight.PQRecruitment=7
-Dsolomapling.megaWeight.Flex=19
-Dsolomapling.megaWeight.RandomAnnouncements=22
-Dsolomapling.megaWeight.SocialMessages=22
```

The weights do not have to add up to 100. A category's share is its weight divided by the sum.

## What an hour in town reads like now

`docs/chat/content-review-2026-10/town-hour-sample-47.txt` (made by `town-hour-sim.py` in the same folder) is a one-hour Henesys transcript. It uses the reviewed packs
and the live code's timings: barks every 8-20 seconds, a scripted conversation every 20-40 seconds, town chatter
exchanges every 30-90 seconds, and the megaphone ticker gated to one every 2 minutes or more. Three seeds give
about 12 lines a minute and 13-16 megaphones an hour. About 1 line in 12 repeats within the hour, mostly
"lol"/"ty"/"kk"-length lines. At 3%, RWT works out at about one megaphone every 2-3 hours. A stretch:

```
03:06  lilbishop:     should i dark scroll my cape or just use 60%s
03:10  Slimey:        how much do u love that cape
03:15  lilbishop:     a lot. spent 40m on it
03:19  Slimey:        then do NOT dark scroll it. one fail and its gone forever
03:28  Sk8erboi:      you in a guild?
03:31  Snowfox:       looking for one actually
03:34  Sk8erboi:      i'll ask mine for you
04:44  Pinkiepie:     afk real quick gotta restart my router
04:47  Mapler4Life:   how much are 60% scrolls these days?
04:50  Slimey:        way too much lol
05:11  zKnight:       wait do i need a prequest for zak or can i just walk in
05:15  Takoyaki:      u gotta do the whole key thing first
05:30  ResMe:         [chalkboard] B> mesos yes I know how that sounds
35:47  Tiger:         [MEGAPHONE] S> gachapon hat 12m clean lvl 0 req ch3 fm
37:49  Pinkiepie:     [MEGAPHONE] LF> consistent OPQ group, tired of finding randoms every night
```

## Follow-ups not done here

- **Prune by reaction (ticket 37's tier log).** That log is on branch `chat-natural`, which is not merged yet. Once
  it is on main, count which ambient lines get a player reply, and drop lines that never do.
- **Unused nodes** (listed above) can be wired up or deleted.
