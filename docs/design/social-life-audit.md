# 1.6.0 social life audit: what exists, what's missing, build plan

This was a read-only audit of `/root/work/SlumsMS-160-release` at `8e19ffbc5`, branch `release-1.6.0`. I edited nothing, committed nothing and didn't touch live. For TEST I only read things: `journalctl -u slums-persistence-test` for the last 3 hours (TEST runs `abf51c897`, with the brain flags `slums.botlife.enabled/execute=true`), `data/autoqa/bot-life.json` and `logs/convo/transcript-2026-10-2[3-5].jsonl`. I didn't query any database. TEST row counts below are quoted from `docs/design/player-likeness-audit.md:372-379`.

Path shorthand: **AP** = `src/main/java/soloMapling/ArtificialPlayer`, **BL** = `AP/BotLifeSystem`.

---

## 0. Headline findings (bugs and tells found while auditing)

1. **The brain's per-bot memory gets wiped after a social or town session.**
   - `LifeMemory` (the drifted blend, goals and events) lives only inside `botroster.ActivityState` under the key `"life"`. Only `TrainingBot` writes it (`AP/BotTypes/TrainingBot.java:1025`).
   - Every other bot type uses the default `activitySnapshot()`, which writes an empty `Map.of()` (`AP/BotSM.java:487-490`). `BotRoster.activityOf` saves that as-is (`AP/BotRoster.java:323-327`).
   - So a bot whose next session is SOCIAL or TOWN (the session type is drawn at `AP/BotPopulationDirector.java:561`), or that becomes a FollowerBot, loses its learned state. Anything we "save with the bot" for learning can't stay in `ActivityState`.
2. **Bot-to-bot gossip is made up.** For GOSSIP and TRAINING, `BotThreads.about()` returns null (`AP/BotConvo/BotThreads.java:407-424`). The prompt (`:427-437`) then lets the LLM invent people. On TEST: "did u see xxslayerxx try to ks me", "xXBrawlerXx got scammed out of his Bamboo Sword". None of it comes from `BotMemory`, `BotWorldGossip` or affinity. This breaks the "gossip/reputation" decision and will fail a blind test.
3. **The catchphrase tail is a tell.** `VoiceStyler.java:73-74` glues a catchphrase onto 6-14% of lines, which produces lines like "Left where? I'm just standing in Henesys Not bad." and "...ruin the whole server. Good game." (TEST transcript, 2026-10-24/25).
4. **The friend and party options never fire.** In 3 hours on TEST (~115k decisions):

   | Selected | Count |
   |---|---|
   | GRIND, kept | 45k |
   | HANG_OUT | 53k |
   | BREAK | 6.1k |
   | MOVE_MAP | 2.3k |
   | SELL | 1.8k |
   | RESTOCK | 1.7k |
   | FM | 856 |
   | LOG_OUT | 219 |
   | SHOW_OFF | 6 |
   | HELP_FRIEND, PARTY_QUEST, FISH | 0 |

   - "Friends" counts only real players (`BL/LifeObservation.java:121` uses `isVisibleRealPlayer`).
   - HELP_FRIEND and PARTY_QUEST need a player's chat request (`friendAsk`, called only from `BotChatActions:124`, `BotConversation:481`, `ConvoActions:219`).
   - So today the social side of the brain is purely reactive.
5. **Decision logging is noisy.** Every decision logs at INFO (`BL/LifeRuntime.java:174`), about 44k journal lines an hour at ~2,850 bots. The cost itself is small: 10.9 s of CPU for 19,552 decisions since the 00:05 UTC boot (about 0.55 ms each, roughly 0.5% of one core).
6. **The archetype mix isn't the target.** `LifeProfile.forBot` picks the primary archetype uniformly from 7 (`BL/LifeProfile.java:27`), about 14% each. The decision is Bartle 40/30/20/10.

---

## 1. Decision vs what exists

| Decision item | What exists (file:line, how it works) | Status | Gap |
|---|---|---|---|
| **Bartle blend per bot, one leading** | Four separate personality layers, none of them Bartle. **(a)** `LifeProfile` (`BL/LifeProfile.java:10-62`): 7 archetypes (GRINDER, SOCIALITE, SHOW_OFF, HELPER, MERCHANT, EXPLORER, SLACKER). The primary gets 55-70% and the rest is spread. `bias()` = 0.6 × archetype share per activity. **(b)** `BotPersonality.Traits` (`AP/BotPersonality.java:131-192`): grind/town/social weights, chattiness, play window and session length; it picks the session's bot *type*. **(c)** `BotPersona` (`AP/BotPersona.java:31-58`): vibe and 2 interests, used in the LLM prompt. **(d)** `ConvoPersona` (`AP/BotConvo/ConvoPersona.java:68-105`): voice (6 families), typo rate, jobPride, guildLoyalty, catchphrases, all derived from the slot. | partial | No Bartle axes. No Killer at all: hostility comes only from affinity events, not personality. The mix is uniform, not 40/30/20/10. The 4 layers aren't linked (a "socialite" LifeProfile can have low BotPersonality.social). |
| Mix 40/30/20/10 | — | missing | Seed the distribution, keyed on the slot and stable. |
| **Memory: who you are** (name, class, level, guild, last topic, inside jokes) | `RelationshipCard.of` (`AP/BotConvo/RelationshipCard.java:284-305`) builds a card of ≤520 characters for each chat prompt: feeling in words, ≤4 facts with "yesterday" or "on Tuesday" (`:206-224`), romance, guild relation, map, mobs, time, world event. Level, job and guild are read live. `BotMemory` notes (`AP/BotMemory.java:151-185`) are written by the LLM when a chat ends. | partial | Only about real players. No "last topic" or inside-joke slot. No notes when the LLM is off (`:177`). Nothing about other bots. |
| **Memory: what we did together** (PQs, bosses, trades, parties) | Affinity events PARTY, TRADE, BUFF, HEAL, RESCUE, GIFT (`AP/BotAffinitySystem/AffinityEvent.java:14-24`) move the score but write no episode. `BotWorldGossip` writes DIRECT notes for boss kills, rare drops and weddings to bots on the map (`AP/BotWorldGossip.java:1-20`). | partial | No episodic record like "did KPQ with X on Tuesday". No PQ-clear hook: `EventInstanceManager.clearPQ` (`scripting/event/EventInstanceManager.java:865`) isn't wired to bots, and `EventType` has no PQ_CLEAR or TRADE_DONE (`soloMapling/server/EventMessageSystem/EventType.java`). |
| **Favors and grudges, with decay** | `Tie` (score -100..100, kind/hostile counters; `AP/BotAffinitySystem/Tie.java:119-139`) and `AffinityRules` (`:223-241`): half-life 72 h for grudges, 14 days for friendships. Under \|3\| it resets its history (`:286-289`). Thresholds: friend ≥25 with 3 kind events, rival ≤-25 with 2 hostile, nemesis ≤-65 with 5 (`:249-265`). Per-event cooldowns. | partial (bot→player done) | Bot→real player only. `record()` refuses bot↔bot (`BotAffinity.java:349`). No SCAM event. Grudges fade in **days**, but the owner said "weeks". |
| **Gossip and reputation that spreads** | `BotGossip.step` (`AP/BotGossip.java:384-422`): once a minute, ≤20 tellers, 30% pass chance, 1 note to a bot on the same map. Stored as HEARSAY with hop ≤2 (`BotMemory.java:136-148`). Hearsay nudges affinity by ≤±15 (`AffinityRules:291-296`). `AffinityReactions.maybeMentionGossip` (`:664`) says it aloud. Opt-out purge: `BotMemory.forgetGossipAbout` (`:233`). | partial | Only players' notes. No aggregate reputation ("known KSer" across a crew or guild). Spreads by map adjacency, not "through friends". The bot-thread gossip doesn't use it (finding 2). |
| **Spice: teasing and banter** | Rival prompt allows "light teasing" (`AffinityReactions.java:650`). The line bank has nostalgia, flex and complaint categories (`AP/SocialHotPotatoManager.java:69`). | mostly missing | No "friendly tease" behaviour for everyone. Tone isn't keyed to closeness. |
| **Spice: rivalries and trash talk** | `BotRivalry` (`AP/BotDramaSystem/BotRivalry.java:41-62`): roasts from real facts (`RivalRoasts`), KS wars, smega call-outs, revenge arcs. `AffinityReactions` TAUNT, KS, DEFAME with caps (`:1-21`). Bot guild rivalries in `GuildWars` and `BotGuilds`. | done for bot→player and guild↔guild | No personal bot↔bot rivalries. BotThreads spats ("Rude. Fine, good luck finding anyone else.") are one-off and nothing remembers them. |
| **Spice: drama** (guild fights, jealousy, rage-quit-and-return, smega call-outs) | Guild wars, poaching, betrayals (`GuildWars.java:1-25`). Jealousy, breakup megaphone, rebound, two-timing confrontation (`BotRomance.java:1-30`). Callouts (`BotRivalry`). | partial | Rage-quit-and-return is missing: only flavour lines exist (`SocialBotDialogue.yaml:616,683`). There's a `LifeMemory.Event.BOOM` (from `BotScrolling.java:273`) to hang it on. |
| **Spice: flirting and crushes** (couple rings, jealousy) | Player↔bot romance, opt-in `@romance on` (`BotRomance.java:1-30`, store `botromance` migration 015). Bot↔bot couples, max 4 in the world (`AP/BotCoupleSystem/BotCouples.java:42-60`), with rings, chairs, love-note megas and weddings (`BotWeddings`). | partial | No crush stage before dating. The bot couple cap of 4 is tiny for "drama". The targeting rule differs from the grill (see §7 Q1). |
| **Targets:** bots spice each other freely; a player only after they started it; teasing for all | Player hostility already needs player-caused hostile events (KS/steal/defame/rude) to reach RIVAL (`AffinityRules:249-265`). `@botdrama off` removes it (`BotAffinity.java:286`). Romance needs `@romance on`. | partial | No explicit "started it" record (e.g. the player flirted or roasted first). Bot↔bot spice is barely there. |
| **Initiative: greet friends at login** | Bot buddies show online (`BotBuddies.java:601-640`). Recognition greets on sight (once per 6 h per pair, after 30 min, 50%; `AP/BotRecognition.java:1-25`, `:107`). Romance partner "shows up". | missing (at login) | Nothing runs on the player's login (`PlayerLoggedinHandler.java` only calls OwnedAlt and the guide, `:148`, `:416`). |
| **Initiative: invite to PQ, party, guild, trade** | Friend INVITE on sight (`AffinityReactions` Action INVITE, 180 min cooldown, 6 per hour world-wide). Guild leaders whisper invites (`BotGuilds.java` header). PQ: OPQ lobby megaphone (`WorldEventMegaphones`, PQ_FORMING). Trade: `MegaBuyDesk` whispers offers on a player's buy megaphone. | partial | No "shared interest" match: invites ignore level/PQ band and goals. No bot-started PQ party; `PARTY_QUEST` needs a player's ask (`TrainingBot.java:806`). |
| **Initiative: buddy requests after lots of play together** | `BotBuddies.afterChat` / `maybeOffer` (`:666-723`): after a chat of ≥3 lines, 15% chance, 1 per player per day, 6 per hour world-wide. `offerAsFriend` for a FRIEND state. | partial | It's based on chat, not on time played together. |
| **Initiative: follow up on memories a day later** | The card shows note age, and the prompt suggests "a goal, an item ... as a casual question" (`AP/BotChatPrompt.java:63-64`), but only while a chat is already open. | missing (proactive) | Nothing schedules a follow-up and notes have no "follow-up" tag. |
| **Frequency "busy server"** plus guardrails | `PlayerBusy.reason` (`AP/PlayerBusy.java:30-45`): event/PQ instance, dungeon, boss map, trade/shop/storage. Used by 14 classes. `BotWhispers`: one unsolicited whisper per player per 10 min (`AP/BotWhispers.java:36-48`). `RecentLines`: 200 lines per player / 60 per bot, bank lines only (`AP/BotConvo/RecentLines.java:14-16`). `@quiet megaphones\|bots\|all` (`client/command/commands/gm0/QuietCommand.java`) hides chatter display only, memory-only. Megaphone gate gap of 45 s. | partial | "No mid-boss or PQ stage" is done via PlayerBusy. "No repeats" covers bank lines only, not LLM lines. `@quiet` doesn't stop whispers, invites or buddy requests, and is lost on restart. Volume is low (§4). |
| **Trends: copy real players** | Nothing for social. Patterns to reuse: `PlayerWealthIndex` (`AP/PlayerWealthIndex.java`: EMA, clamp, daily step, saved) and `NavObservedEdges`/`NavEdgeLearning` (learns from players and failures, 12 h half-life). | missing | Need a map/time/level/slang/price trend index. |
| **Trends: server events** | `WorldEventMegaphones` (BOSS_KILL, RARE_DROP, PQ_FORMING), `BotWorldGossip` (2 h half-life, 8 h TTL, 3 mentions, 4 min gap; `:67-70`), `LevelUpGz`, `MegaReactions` (55% reaction to player smegas). | partial | No patch/release or wedding-of-player trend that changes behaviour (only talk). |
| **Trends: bot-made fads** | — | missing | |
| **Trends: 2009 nostalgia** | Era-slang prompts (`BotChatPrompt.java:70,102`), the "Nostalgia" bank category, LFG threads ("lf ludi pq"), voices. | partial (talk only) | No FM-spam or zhelm-flex behaviours tied to the brain. |
| **Brain: utility seeded by Bartle** | `LifeDecision.evaluate` plus `LifePolicy` (§3). | partial | Seeded by the 7 archetypes, not Bartle. |
| **Brain: GOAP for multi-step goals** | `LifeGoals.seed` (`BL/LifeGoals.java:22-46`): ≤2 goals from JOB_ADVANCE, WEAPON_UPGRADE, LEVEL. They only feed PROGRESS pressure. | missing | No planner and no social or wealth goals (marriage, guild, zhelm, 4th job as a plan). |
| **Brain: per-bot outcome learning** | `LifeMemory.event` (`BL/LifeMemory.java:81-99`): 5 life events each nudge one archetype by +0.5% (×0.995 elsewhere), ≤4 a day, 1 per event per hour, no decay back. | partial (drift, not learning) | No reward signal from outcomes. Persistence is broken (finding 1). |
| **Brain: population learning** | — | missing | |
| **Decision models only read chat meaning** | `ChatTriage` (rules then Decide), `ChatTone` (kind/plain/rude), `ChatDecide` (GLiNER, 400 ms, 8/s), `PlayerChatMeaning`, `HaggleBrain`. The brain itself makes no model calls (ticket 60 decision, `docs/tickets/60-bot-brain.md:188`). | done | Keep it. |
| **Hard limits outside learning** | `ContentGuard` (PG-13, game-only insults, no AI talk; `AP/BotBrainSystem/ContentGuard.java:7-16,79`) is applied to every LLM output via `ReplyCleaner` (`OpenAICompatibleBrain.java:98,129,205,248`) and to drama and threads. The never-AI line is in the prompt (`BotChatPrompt.java:76`). Say-do (`SayDo`, TEST 1,222/1,222 claims backed). Scam rules: trade window only (roadmap). | done | Learned weights must not reach these. Keep them as hard gates. |
| **"More actions = more decisions"** | `LifeDecision.Activity` has 12 values (`BL/LifeDecision.java:19-20`). `TrainingBot.canStartLifeActivity` is the executor gate (`:788-816`). | partial | Social acts (invite, greet, visit a crew, tease) aren't activities. Only TrainingBot runs the brain (`LifeRuntime` is built in `TrainingBot.java:390`). |

---

## 2. The data model as it stands

### Per bot

| Field(s) | Where | Persisted? |
|---|---|---|
| Identity, level, job, meso, map, looks | `botstore` (migration 003) | yes |
| `Personality` JSON {seed, grind, town, social, chattiness} + PlayWindowStart/Len + SessionMinutes | `botroster` (003); `BotPersonality.Traits.toJson` | yes |
| `Persona` JSON {lowercase, shortForms, laugh, vibe, interests[2]} | `botroster.Persona`; `BotPersona` | yes |
| HomeMapId, Activity, `ActivityState` JSON (TrainingBot: train map + `life`{version 1, blend(7), goals ≤2, lastJob, lastSocial, driftDay, changesToday, events{}}) | `botroster`; `LifeMemory.save` (`BL/LifeMemory.java:101-115`) | yes, but overwritten by non-training sessions (finding 1) |
| LifeProfile grind and town durations | derived from the char id (`LifeProfile.java:41-42`) | derived |
| ConvoPersona (voice, typo, chattiness, jobPride, guildLoyalty, catchphrases) | derived from the slot; GM voice override memory-only (`ConvoPersona.java:107`) | derived |
| MessyChat style, BotHabits restockBelow, merchantMinded (10%, `Economy/BotShops.java:122-125`) | derived | derived |
| Guild membership | `botguildmember` (014) | yes |

### Per pair

| Pair | Fields | Where | Persisted? | Bound |
|---|---|---|---|---|
| bot→player feelings | Score, Kind, Hostile, UpdatedAt | `botaffinity` (011) | yes; loaded only while the player is online (`BotAffinity.java:118-190`) | 40 ties per player (`:107-108`, `bound()` `:174`) |
| per-event cooldowns | `Tie.lastAt` | memory | no | |
| bot→player notes | Note ≤160, Source DIRECT/HEARSAY, FromBotId, Hops ≤2, CreatedAt, LastUsedAt | `botmemory` (003, index 022) | yes | ≤10 per pair, condensed by LLM (`BotMemory.java:36,206-226`); **the in-memory cache never evicts** (`:50`) |
| buddies | stock `buddies`, bot rows pending 0/1/2 with "sent:day" | stock table | yes | 20 per bot |
| romance | Stage, StartedAt, TogetherAt, EndedAt, rings; opt-in table | `botromance`, `botromanceopt` (015) | yes | |
| bot couples | BotA, BotB, Stage, FormedAt, MarriedAt, LastNoteAt | `botcouple` (012) | yes | 4 in the world |
| guild↔guild | war state | `botguildwar` (016) | yes | |
| gifts | last bot→player gift | `botgifts` (020) | yes | 1 a week |
| opt-outs | `@botdrama off` | `botdrama` (011) | yes | |
| **bot↔bot personal ties** | — | — | **none** | |

### Memory-only (lost on restart)

- `BotRivalry` wars, arms, arcs and death counts (`BotRivalry.java:107-110`)
- `AffinityReactions` cooldowns and world caps
- `BotNoiseFilter` (`@quiet`)
- `BotWhispers.lastSent`
- `RecentLines`
- live `BotThreads` and `lastThreadOf`
- `BotRecognition` timers
- the `BotWorldGossip` news feed
- `BotRomance` askedOut, whispersToday, rebounds, neglect warnings
- `LifeRuntime` friendAsk window (60 s)

### Decay

| Thing | How it decays |
|---|---|
| Affinity | Exponential: grudge half-life 72 h, friendship 14 days (`AffinityRules.java:223-241`). Counters forgotten below \|3\|. |
| News | Half-life 2 h, TTL 8 h (`BotWorldGossip.java:67-68`). |
| Nav learning | Half-life 12 h. |
| botmemory | No decay. LastUsedAt is stamped (`BotMemory.java:312-322`) but never used to prune. |
| LifeMemory drift | No decay back toward the seed. |

---

## 3. How the brain picks today, and where GOAP and learning fit

### Formula (`BL/LifeDecision.java:112-138`)

`total(activity) = Σ_need need[n]·weight[a][n] + contextBonus + personalityBonus + noise + stickiness`

| Term | Value / source |
|---|---|
| need weights | `LifePolicy.weights` (`BL/LifePolicy.java:49-64`), e.g. GRIND = progress 1.4, supplies -1.6, bag -0.8, fatigue -0.5 |
| personalityBonus | `0.6 × blend[archetype]` (`LifeProfile.bias`, `:50-61`) |
| contextBonus, HANG_OUT and SHOW_OFF | `min(1.2, .6·friends) + min(.2, .04·strangers) − min(.8, .4·rivals)` (`LifePolicy.java:30-32`), plus town bias by level (.4 / .25 / .05), plus .6 if a friend is in a town (`LifeRuntime.java:158-162`) |
| contextBonus, HELP_FRIEND | 1.5 when a friend asked |
| noise | ±0.02, seeded per bot per activity per 30 s (`LifeRuntime.java:164-165`) |
| stickiness | `0.8 × (1 − elapsed/expected)` for the current activity |

- **Keep-current rule:** keep the current activity unless best − current > 0.25 (`INTERRUPT_MARGIN`).
- **HOLD_COMMITMENT** when the bot is busy (trade, conversation, director hold).

### Inputs (`BL/LifeRuntime.java:100-191`, `BL/LifeObservation.java:32-91`)

| Need | Source |
|---|---|
| SUPPLIES | HP pots vs the bot's threshold, MP pots/20, ammo (`:134-143`) |
| BAG_SPACE | fullest bag |
| BOREDOM | time in phase / profile duration |
| SOCIAL | time since `lastSocial` / town duration (`:128-130`) |
| FATIGUE | session elapsed / sessionMinutes |
| PROGRESS | max goal pressure |

- People counts: real players only on the current map, ≤64 sampled.
- Eligibility: `TrainingBot.canStartLifeActivity` (`:788-816`).
- **Cadence:** every 30 s, or when the state changes or the activity is due. It skips if a signature of the needs and context hasn't changed (`:152-156`).
- **Execution:** `prepareLifeActivity` / `submitLifeActivity` → `LifeActivityQueue` → `LifeExecutor` → TrainingBot routines. `accepted()` announces "brb, restocking" etc. (`:193-219`).

### LifeGoals

`LifeGoals` (`BL/LifeGoals.java`) seeds ≤2 goals: next job advance (at 10/30/70/120), a weapon upgrade if the weapon is ≥10 levels behind, otherwise level+1. Their pressure feeds PROGRESS. They also go into the chat prompt as "Goals: ... not completed" (`LifeRuntime.prompt`, `:225-228`). They can't sequence steps.

### Where a GOAP planner would sit

A new `BL/LifePlanner` between `memory.refresh` and `LifePolicy.options` in `LifeRuntime.tick` (`:123-163`):

- Goals become goal states (FOURTH_JOB, MARRIED, IN_GUILD, ZHELM, RICH).
- Actions are the `Activity` catalogue plus new social actions, each with preconditions and effects read from game facts.
- The planner outputs the *next step*, which gets a context bonus. Utility still arbitrates against needs, so restock still interrupts.
- Re-plan only when a goal or a fact changes, never per tick. That fits the existing dirty/signature gate.
- Store the plan in the persisted profile (not `ActivityState`).

### Outcome hooks that already fire (reward taps for learning)

| Hook | Where |
|---|---|
| party join | `net/server/world/Party.java:398` → `AffinityHooks.onPartyJoin` |
| trade completed | `server/Trade.java:441` → `onTrade` |
| buff / heal | `server/StatEffect.java:1139` |
| KS by a player / bot yield / mob hit bot / kill | `server/maps/MapleMap.java:1543,1547`; `BotGrindSystem/GrindBrain.java:239`, `CampStrategy.java:515`; `GCMoveSystem/BotContactDamage.java:112` |
| pickup (drop steal) | `client/Character.java:2346` |
| fame | `net/server/channel/handlers/GiveFameHandler.java:56` |
| chat tone kind/rude | `AffinityReactions.judgeLine/judgeTone` (`:590,615`) |
| chat ended | `BotMemory.writeConversationNotes` (`:175`), `BotBuddies.afterChat` (`:666`) |
| buddy accepted / removed | `BotBuddies.playerAccepted/playerRemoved` (`:538,564`) |
| apology / mock | `BotRivalry` APOLOGY |
| EventBus events | `EventType` LEVEL_UP, BOSS_KILL, RARE_DROP, SCROLLING, CHAT_GENERAL, MAP_ENTERED, PQ_FORMING, megaphones |
| life events | JOB_ADVANCE (`LifeMemory.refresh`), BIG_DROP (`BotHoldings.java:232`), MARRIAGE (`BotWeddings.java:265-267`, `RomanceWedding.java:417`), GUILD_WAR (`GuildWars.java:187,1165`), BOOM (`BotScrolling.java:273`) |
| activity accepted, claim backed | `LifeRuntime.accepted`, `SayDo.stats()` |
| player megaphone | `PlayerMegaphones` |

**Missing taps:** PQ clear (`EventInstanceManager.clearPQ:865`), trade *fairness* or scam, a reply received to a bot's own unprompted line, invite accepted or declined by a player, bot↔bot events of any kind.

---

## 4. What reaches real players today

**Aimed at players:**

- greet on sight (recognition and affinity friends)
- friend party invite, buddy request, Bless/Haste-type buff, gifts (3% per sighting, 1 a week)
- rival taunt/roast, KS spree (90 s, ≤3 mobs), KS war (2-4 bots), defame (1 a day), callout smega, revenge follow, trade/party/buddy refusals
- romance (opt-in): flirt, ask out, follow, cute whispers, jealousy, breakup
- guild invites and poaching whispers; war on player guilds
- MegaBuy offers; smega reactions (55%); gz
- party chat replies; buddy chat; whisper and map chat replies; joining a bot thread

**Bot-to-bot only:**

- BotThreads exchanges (TEST: 230-418 lines a day, only on maps a real player can see)
- BotChatter pairs and hot-potato barks
- bot couples and weddings
- bot guild wars, turf and alliances
- bot-only party chat
- gossip passing (invisible except `maybeMentionGossip`)

**Guardrails in place:**

- `PlayerBusy` (instance, PQ, dungeon, boss, trade/shop)
- one unsolicited whisper per player per 10 min
- per-pair cooldowns: greet 120, invite 180, buddy 1440, taunt 20, ks 15, defame 1440 min
- world caps per hour: taunts 20, KS 10, invites 6, buddy offers 6
- megaphone gate at 45 s; threads ≤6 per 10 min world-wide with a 3-6 min gap per map
- `@botdrama off`, `@romance` opt-in, `@quiet` (display only)
- ContentGuard, PG-13, never AI, say-do
- RecentLines anti-repeat (bank lines only)

**Volume reality:** 146-227 LLM replies to players a day across all players. That's far below "busy real server", mostly because the caps above and the observed-map gating keep it low.

---

## 5. CPU and memory at 3,000 bots

**OK today:**

- `BotGossip.step` is O(online bots) per minute plus ≤6 DB reads.
- Affinity is O(players × 40).
- `LifeRuntime` is ~0.55 ms per decision at ~850 decisions a minute.
- `LifeObservation` caps sampling at 64 players; `knownPlayers` at 32.

**Risks once bot↔bot is added:**

1. **All pairs is 9M.** Never store or scan all pairs. Keep the top K ties per bot (say 12-16): 3,000 × 16 = 48k ties, a few MB, 48k rows. Evict the weakest (copy `BotAffinity.bound`). Only update from shared events (same party, same thread, same PQ, a KS on an observed map, a sampled co-presence tick on observed maps). Never from an O(N²) per-map loop: sample ≤M pairs per map per minute.
2. **The `BotMemory` cache has no eviction** (`:50`). Bot-about-bot notes would grow without bound. For bots, store reputation as aggregates (subject × crew/guild counters) rather than per-listener notes, and add an LRU.
3. **Hearsay as rows.** At ~2k rows today, bot-about-bot notes could add 10-100× that. Cap rows per subject and expire them by `LastUsedAt`.
4. **LLM load.** The `.17` box has 2 slots, also used by live chat, and threads run at THREAD priority only when every slot is free. More initiative and grounded gossip needs the bank and scripted paths first.
5. **Log volume.** Drop the per-decision INFO log to DEBUG or sample it before adding social decisions.
6. **Writes.** Batch dirty ties every few minutes on `SerialWrites`, as `BotAffinity` does.

---

## 6. Slice plan (dependency order)

Reserve migration numbers up front so parallel slices don't collide: 028 profile, 029 bonds, 030 memory, 031 quiet.

| # | Slice | Scope | Files | QA that proves it | Size | Parallel? |
|---|---|---|---|---|---|---|
| **S0** | Measure first (the owner's method) | Chat judge on for TEST. Add social counters to `bot-life.json`: initiatives per player-hour, spice-without-started-it (must be 0), repeats to the same player, made-up names in gossip. Freeze the fresh-v2 social set. | `BL/LifeRuntime.reportIfDue`, `soloMapling/autoqa/ChatQuality`, `LlmJudge`, tools | auto-QA health check `t-so-0` reads the counters; baseline recorded | S | runs first, alone |
| **S1** | Social profile + safe persistence | Bartle blend (A/E/S/K, a leading type drawn 40/30/20/10 per slot), mapped onto today's 7 bias channels (MERCHANT and SLACKER become flavour traits). LifeProfile v2 reads v1. Move the life/social state out of `ActivityState` into its own column or table (fixes finding 1). One read-model joining Personality, Persona and ConvoPersona. | `BL/LifeProfile`, `LifeMemory`, new `BL/SocialProfile`, `AP/BotRoster`, migration 028 (+rollback, `docs/release/1.6.0.conf`) | unit: distribution over 3,000 slots within ±2%, v1→v2 load; `!qa bl-4`: drift survives a SOCIAL session and a restart; `!botwhy` shows Bartle | M | foundation |
| **S2** | Bot↔bot ties ("bonds") and crews | Generalise Tie/AffinityRules to bot pairs, top-K per bot, decay in weeks. Events: co-party, thread exchange, same guild, PQ together, bot↔bot KS, gifts. LifeObservation counts bot friends and rivals. Crews = connected FRIEND bonds pulled into HANG_OUT/MOVE_MAP together. | new `AP/BotAffinitySystem/BotBonds` + store, migration 029, small hooks in `GrindBrain`, `BotThreads`, `BotPartyChat`, `LifeObservation.people` | unit: K bound, decay; `!qa bb-3`: two bots partied N min → FRIEND, seen together; soak: memory and CPU at 3k | L | after S1; parallel with S3/S5/S8 (reads AffinityRules, doesn't edit it) |
| **S3** | Memory kinds | Episodic scripted notes without the LLM ("did KPQ with X", "traded me a claw"), tagged Kind and Weight. A PQ_CLEAR/TRADE_DONE event (one `SocialHooks` facade owns the stock-file hooks). SCAM, GIFT and FAVOR ledger in AffinityEvent. Grudge half-life to weeks (owner Q2). Last topic and inside joke per pair. Prune by age and LastUsedAt. LRU cache. | `AP/BotMemory`, migration 030, `AffinityEvent/Rules`, `EventType`, `EventInstanceManager:865`, `server/Trade.java:441`, `RelationshipCard` | `!qa me-1`: staged KPQ clear → note plus card line; `me-2`: scam trade → grudge; unit: prune and decay | M | parallel with S2/S5/S8 |
| **S4** | Grounded gossip and reputation | Reputation per subject per crew/guild (aggregate, not per pair). BotGossip spreads along bonds, not just maps. BotThreads GOSSIP/TRAINING `about()` uses real facts (news, reputation, bond drama). A guard rejects names that aren't known characters. Remove the catchphrase tail tell (`VoiceStyler:73`). | `AP/BotGossip`, `BotWorldGossip`, `BotConvo/BotThreads` (about/threadPrompt), `VoiceStyler`, new `Reputation` | auto-QA transcript check: 0 made-up names in gossip; `!qa gs-1`: KS → crew-mate references it | M | after S2+S3 |
| **S5** | SocialGate (targets and guardrails) | One gate for every unprompted bot→player act: a "started it" ledger (KS'd, scammed, flirted, roasted first → spice allowed toward that player, decaying), teasing allowed for all, PlayerBusy plus PQ stage, no-repeat across LLM lines (hash per bot→player), `@quiet` persisted and also blocking unsolicited whispers/invites/requests, a "busy server" budget replacing scattered caps. | new `AP/SocialGate`, `BotWhispers`, `BotNoiseFilter`, `QuietCommand`, `BotBuddies.maybeOffer`, `AffinityReactions.act`, `BotGuilds` invites, migration 031 | `!qa sg-1`: `@quiet` → 0 whispers/invites from a staged friend; `sg-2`: a fresh player gets no spice until they KS/flirt; auto-QA log check | M | parallel with S2/S3/S8; must land before S6/S7 |
| **S6** | Initiative | Greet at login (one line in `PlayerLoggedinHandler` → friend/bond bots whisper or buddy-chat). Day-later follow-up from tagged notes ("u get ur zhelm?"). Interest-matched invites (bot-started PQ party via a new activity, guild, trade). Buddy request after X episodes together. | new `AP/SocialInitiative`, `PlayerLoggedinHandler`, `BotBuddies`, `AffinityReactions`, `BL/LifeDecision` (INVITE_PQ), `TrainingBot.canStartLifeActivity` | `!qa in-1`: login with a staged friend → greet ≤60 s; `in-2`: clock seam +1 day → follow-up; `in-3`: bot invites to KPQ at a matching level | M-L | after S3+S5 |
| **S7** | Spice | Friendly teasing class (bank plus LLM, keyed to closeness). Bot↔bot rivalries on RIVAL bonds (threads, KS spats on observed maps). Rage-quit-and-return on BOOM (logout line, return hours later, "im back lol"). Bot crush stage before BotCouples; jealousy among bot couples; guild fights from bond drama. | `BotThreads` topics, new `BotDramaSystem/BotBotRivalry`, `BotCouples`, `BotLogout`, `LifeMemory` BOOM | `!qa rv-5`: bot↔bot rivalry thread on a watched map; `dr-6`: forced boom → rage-quit then return; ContentGuard unit cases | L | after S2+S5; parallel with S6 if `BotThreads` edits are coordinated with S4 |
| **S8** | Bartle utility + GOAP | Bias from Bartle (Killer → rival pull, PvP-ish KS spats; Socializer → HANG_OUT/crew; Explorer → MOVE_MAP; Achiever → GRIND/goals). `LifeGoals` adds MARRIAGE, GUILD, ZHELM, FOURTH_JOB, WEALTH. `LifePlanner` (GOAP over the Activity catalogue) gives a next-step bonus. Plan persisted in the S1 profile. `!botwhy` shows the plan. | `BL/LifePolicy`, `LifeGoals`, new `BL/LifePlanner`, `LifeRuntime`, `LifeObservation.describe` | unit: plans for staged facts; `!qa bl-5`: staged goal → plan steps in `!botwhy`; TEST: HELP_FRIEND/PARTY_QUEST/crew selections > 0 | L | after S1; only BL files, but coordinate with S6 on `LifeDecision` |
| **S9** | Learning | Per bot: bounded reward updates (\|Δ\| ≤ ~0.15 from seed per channel, decay toward seed, saved with S1) from the hooks in §3. Population: a `TrendIndex` (maps/time/level, slang tokens, prices; EMA, clamped like `PlayerWealthIndex`, per-player share capped); bot-made fads spread through bonds and players can join. Hard limits stay outside. | new `BL/LifeLearning`, `AP/TrendIndex`, `Fads`, hook taps | unit: caps hold; auto-QA: after 24 h every bot's blend is within caps and the leading type rarely flips; `!botwhy` shows learned deltas | L | last; after S1, S3, S8 |
| **S10** | Blind test | 20-minute real-client social script with the owner; labelled player/bot logs per rc | docs/qa | owner labels at chance level | S | each rc |

**Parallel lanes after S1:**
- Lane A: S2 → S4 → S7
- Lane B: S3 → S6
- Lane C: S5
- Lane D: S8 → S9

**Shared-file hot spots to assign to one owner each:**
- `BotThreads`: S4, then S7
- `LifeDecision` / `TrainingBot.canStartLifeActivity`: S6 and S8
- `AffinityEvent` / `AffinityRules`: S3 only
- stock hooks `Trade.java`, `Party.java`, `EventInstanceManager`: S3's `SocialHooks` facade

---

## 7. Risks and open questions that change the build

1. **Romance targeting.** `AGENTS.md` §7 and BotRomance say romance only with `@romance on`. The grill says spice toward a player "after that player flirted first". Does a player's flirt unlock bot flirting without `@romance on`, or does opt-in stay required? This decides how S5 and S7 gate.
2. **Grudge fade speed.** It's a 72 h half-life today; the owner said "fading over weeks". The new half-lives for grudges, favors and bonds change S3 tuning and how long rivals last.
3. **Bartle vs the existing 7 archetypes.** Replace them, or layer Bartle on top (my proposal: layer, keep merchant/slacker as flavour)? TEST bots already hold v1 blends; live has the brain off.
4. **Caps vs "busy server, no hard per-player cap".** Which of the current caps stay?
   - whisper 1 per 10 min per player
   - buddy offers 1 a day per player, 6 an hour world-wide
   - invites 6 an hour
   - taunts 20 an hour
   - threads 6 per 10 min

   This sets S5's budget and the LLM load on `.17`, which also serves live chat.
5. **`@quiet` semantics.** Should `@quiet bots` also stop unsolicited whispers, invites and buddy requests, and persist across restarts (needs a migration)?
6. **Bot couple cap of 4 world-wide.** Raise it for crushes and jealousy drama?
7. **Learning from 5-15 real players.** A tiny sample risks bots visibly copying one friend's slang or spots. OK to cap any one player's share (e.g. ≤20%), and is copying a real player's slang acceptable at all?
8. **"Started it" spreading through crews.** If a player roasts bot A, may A's crew and guild spice back too, or only A? This changes the ledger shape (per pair vs per crew).
9. **Brain coverage.** Only TrainingBots run `LifeRuntime`. Social and town bots don't decide anything. Should S8 extend the brain to SocialBot and TownWanderer, or keep it training-only for 1.6.0?

Not verified: nothing here was tested in a real client. I didn't read the TEST DB (row counts are the audit doc's). The decision distribution and transcript stats come from one 3-hour log window and 3 daily transcripts on TEST.
