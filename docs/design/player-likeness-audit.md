# Player-likeness audit: where SlumsMS bots still read as bots (for 1.6.0)

Date 2026-10-25. Read-only audit of `152-integration` (live 1.5.2 + docs) and the unmerged `origin/160-bot-brain`,
`origin/160-life-in-town` and `origin/160-convo-scope`. The yardstick is how a 2009 GMS player behaved. Code paths are
relative to `src/main/java/soloMapling/ArtificialPlayer/` (written `AP/`) unless they start with `src/` or `docs/`.

**Evidence used**
- **TEST:**
  - Auto-QA files `data/autoqa/chat-quality-2026-10-2{3,4,5}.json`, `health-latest.json` and `health/*.jsonl` (226-283 sweeps a day).
  - Convo transcripts `logs/convo/transcript-2026-10-2{3,4,5}.jsonl` (1,484 lines).
  - The DB `mapleroot_persist`, read-only: `botstore` (9,010 bots), `botroster`, `botmemory` (1,994 notes), `botaffinity` (16 rows) and `buddies`.
  - `bot-life.json` does not exist on TEST: the brain is not deployed there.
- **Live, read-only:**
  - The journal from 2026-10-25 18:14 UTC. In practice this is mostly the hour after the 12:14 PT (19:14 UTC) restart, about 37k lines, almost all DEBUG offscreen combat and supply lines.
  - The convo transcripts of 10-24/10-25 (358 lines; nothing written since 07:16 UTC). The live DB was not queried.
- The nutnnut comparison report from this session, and three code sweeps (movement/combat/progression, social/chat/group, rhythm/economy/cosmetics).

**Caveat:**
- `{player}` in the transcripts and in chat-quality is privacy anonymisation (`AP/BotConvo/ConvoQuality.java:231-242`), not a template leak. Players saw real names.
- The `korean`/`spanish` voice tics and the insult rate below come from bot-bot threads plus replies, both TEST and live.

## Summary: the top 15 gaps, ranked by impact for effort

Impact means how much it makes the world "feel like players" for a friend logged in at peak. Effort is S (days),
M (1-2 weeks) or L (more).

| # | Gap | Today (evidence) | Fix | Impact | Effort |
|---|---|---|---|---|---|
| 1 | **Bots are silent in party chat** | `src/main/java/net/server/channel/handlers/MultiChatHandler.java:64-66` sends party chat to no bot logic; no `partyChat` call in soloMapling. Any party with a bot feels like one with a mute. | Ticket 85: route party chat through ChatRoute/BotConversation with the party as the room, add party-only lines ("ty for buff", "omw", "wait mp"), and let the brain speak its decisions in party ("brb pots" → real trip). | High (it blocks Companions) | S-M |
| 2 | **The day is a fixed loop** | Grind for 10-20 min, then 4-10 s in town, plus an occasional 30-90 s shop browse (`AP/BotTypes/TrainingBot.java:109-125`). There's no hanging out, helping, showing off or real FM time. | Merge `160-bot-brain` forward onto 152 and enable it on TEST, as already decided (ticket 60). It adds 20-60 min grind blocks, 5-20 min town stays, friend interrupts, restocks and FM visits. Then tune the mix from `bot-life.json`. | High | M (merge + tune) |
| 3 | **Nobody AFKs and the FM is bare** | The longest real idle is a 60-100 s grind break (`AP/BotTypes/GrindBreakRoutine.java:31-39`). Chalkboards clear after 5 min (`AP/SocialHotPotatoManager.java:331-335`). Nothing holds bots in FM main 910000000 (ticket 100). | Add an AFK-camper role: 1-6 h in Henesys, the FM entrance or the Ellinia tree, on a chair with a lasting chalkboard ("S> 4 att gloves pm", "afk", "LF guild"). Wakes up when whispered or named. Add the per-channel FM floor (ticket 100). It costs almost no CPU (no grind). | High | S-M |
| 4 | **Players' megaphones get no reaction** | `src/main/java/net/server/channel/handlers/UseCashItemHandler.java` has no soloMapling hook. Bot smegas run on a timer only (`AP/SocialHotPotatoManager.java:46-47`). | Ticket 86 for B>/S> megas (a whisper offer from a bot that really holds the item), plus 0-3 all-chat reactions to any player smega ("lol", "who is that", "gl"), and an occasional reply smega from a friend or rival. | High | M |
| 5 | **No "gz" where people grind** | `AP/BotFlavorSystem/LevelUpCongrats.java` is called only from SocialBot:203 and TownWandererBot:230. TrainingBots on hunting maps never react to a level-up. Boss kills, weddings and rare drops never enter gossip (`AP/BotGossip.java` copies only player notes). | Hook LevelUpCongrats into TrainingBot (lower chance, 1-2 bots, "gz"/"grats"/"gj"). Turn EventBus BOSS_KILL/RARE_DROP/LEVEL_UP(≥70)/WEDDING into gossip facts that bots in town mention for 1-2 h ("did u see X killed zak"). | Med-High | S |
| 6 | **The population looks like a demo** | A "for demo" tier roll is active (S 30 / A 30 / B 20 / C 10 / D 10, `AP/BotDecoratorSystem/BotDecorate.java:55-66`; realistic 5/15/40/30/10 is commented out). TEST roster: only 16% under Lv30 (1,447/9,010) and 57 under Lv10; mass at Lv50-80. Pirates excluded (`BotDecorate.java:136`): 10 pirates and ~35 Cygnus/Aran out of 9,010. Exactly 50/50 gender (5,077/5,079). | Restore the realistic tier split, bottom-heavy level bands (≥40% under Lv30), and add Pirate, Aran and some Cygnus to the job roll (Aran was *the* 2009 class). Skew gender about 65/35 male. Apply to new and veteran fills; drift the existing roster through quit/return. | High | S (+ a roster drift plan) |
| 7 | **Bot-bot threads read as an LLM arguing** | Over 1,842 transcript lines: 11.6% mention a level number ("u lvl 35 need to quit", "your lvl 26 build"), 6.0% are insults (14.5% of live lines), and 12% are capitalised sentences ending in a period ("I have a level 70 strider and he can solo ludi. Indeed."). The KOREAN voice says "kekeke ... fighting!" in 61 lines. Gossip is generic ("patch de ayer", "new update is broken"). | Thread prompts: drop the level from the card unless it's the topic, cap hostility per thread, and cut PROPER from 18% to about 8% with tighter wording. Give each voice tic a per-bot cooldown. Feed real facts into gossip (from #5). Add these three rates to the scorecard. | Med-High | S |
| 8 | **Ambient lines repeat and all sound alike** | YAML picks are uniform random with no repeat memory (`AP/BotDialogueHandler.java:214, 239`); TownChatter has about 60 lines. VoiceStyler runs only on replies and threads (`AP/BotConversation.java:823`, `AP/BotConvo/BotThreads.java:532`), so barks, megaphones and reactions all share one casing. Chat-quality repeats rose 0% → 6% → 7% (10-23 to 10-25). | Put RecentLines (per map, per bot) in front of YAML picks and run VoiceStyler on ambient lines with the bot's persona. Grow TownChatter from the 9,902-line bank. | Med | S |
| 9 | **Visible snaps and teleports on watched maps** | CampStrategy teleports to the anchor after 2.5 s off it (`AP/BotGrindSystem/CampStrategy.java:710`). No hit for 30 s: teleport to a portal; 60 s: leave (`TrainingBot.java:166-168, 1344-1368`). Portal push at 1.2 s within 100 px (`AP/GCMoveSystem/GCTravel.java`). | When observed, walk back instead of `teleportTo`; take a 30 s "no hit" as a cue to say "cc?" or walk off; keep teleports for unobserved maps only. Add a teleport-while-observed counter. | High (a friend sees it) | S-M |
| 10 | **Same-pixel stacks and floaters** | TEST health: overlap FAIL every day, median 188-237 pairs (max 810) at 1.9-2.9k bots. Triple stacks at dx=dy=0 (`541010010` ch1). Foothold median 10-13 bad per sweep (FLOATING/NO_GROUND in FM rooms 9100000xx). Meanwhile town spacing is *too* clean (25 px personal space, 3 per ledge). | Port nutnnut `235b6aefa` (ground continuity at overlapping footholds) and their v69-71 drop fixes (nutnnut report §3a). Then allow tight, natural clusters at NPCs and portals (a "crowd point" exception to PersonalSpace). | High | M |
| 11 | **Parties and PQs don't happen** | An unsolicited player invite is accepted at 0.3 for a neutral bot (`AP/BotGuildSystem/GuildRules.java` partyAcceptChance; `AP/BotPartySystem/BotRecruitManager.java:116-190`). Bot-to-bot invites are always rejected. Only OPQ stages 1-2 exist, with no KPQ/LPQ code. Boss runs are staged logouts (`AP/BotGuildSystem/GuildBossRuns.java`). | Companions epic (99) for player parties. Meanwhile: KPQ and LPQ lobby crowds that shout "LF> KPQ need 2 lv 25", form bot parties and vanish into the PQ map (staged like boss runs). Raise neutral accept to about 0.5 when the bot is grinding solo near the player. | High | M (lobbies) / L (real PQ) |
| 12 | **Fame is a static number** | `AP/BotFame.java:14-35` seeds fame from id and level. No bot gives, asks for or trades fame; F4F exists only as flavour lines. | Owner goal: real F4F (bots fame back, ask "f4f?"), fame for mesos ("S> fame 50k"), and defame by rivals, all under normal once-a-day rules, wired to affinity (FAME +12 already exists). | Med | S-M |
| 13 | **No schedules a friend would learn** | One play window per bot, the same every day. Weekends get a flat ×1.1 (`AP/BotPopulationCurve.java:84-87`). The channel is redrawn each login (`AP/BotChannels.java:43`). No 5-20 min brb. | Per-bot weekday/weekend windows (school kids later on weekdays, all-day Saturday), a home channel (sticky about 80%), and the brain's BREAK at 5-20 min with a brb line and a chalkboard. | Med | S |
| 14 | **Memory is flat and one-way** | `botmemory` notes never decay (`AP/BotMemory.java:36-48`); they are written only when an LLM is live (:175-184). 472 bots × 5 characters on TEST. 16 affinity rows. No bot↔bot friendships. Recognition greetings: 12 lines (`AP/BotRecognition.java:95`). | Time-aware recall ("long time no see" after 3+ days, forget small talk after about 30 days), scripted note writing when the LLM is off, bot↔bot friend pairs that hang out together (the brain's friend pull), and a bigger recognition bank. | Med | M |
| 15 | **Combat and progression are too clean** | Swings re-fire exactly when the pose ends (`AP/BotAttackSystem/BotSwingClock.java`). Textbook builds, job set directly at 10/30/70/120 with no NPC trip (`AP/BotProgressionSystem/BotProgression.java:44, 274`). Gear always strictly optimal. Offscreen EXP at 1/3 rate pops in as levels (`AP/BotGrindSystem/OffscreenProgression.java`). | Per-bot swing jitter and an occasional pause, about 5% odd builds (DEX warrior, LUK mage), job advance as a real trip to the instructor with a "finally 3rd job!!" line, and "looks over stats" gear choices for show-off bots. | Med | M |

Just outside the top 15:
- Omok/match-card rooms (77).
- Real small scams and flipping (owner's "bad intentions").
- KS drama with words ("ks?", "this is my spot").
- Mounts and multi-pet setups.
- Long-lived AFK chalkboard sellers.
- Visible bot deaths.

These are in the per-dimension sections.

**Suggested order for rc1 ("bots feel alive"):**
1. First, the cheap wins: 1, 5, 7, 8 and 13.
2. In parallel with the Companions build: 2 (merge the brain), 3 (AFK campers and the FM floor), 4 (smega reactions/86) and 6 (population).
3. 9-11 and 14-15 go in rc2.

## Player-likeness scorecard (track per rc)

Run each metric over a 24 h TEST window at the normal population. Every number comes from data we already write, or
from a small counter (marked *new*). Put the scorecard in `test-status` and in each release notes page. The baseline
column is 1.5.2 on TEST (10-24/25), or live where marked.

| Area | Metric | Source | Baseline (1.5.2) | Target 1.6.0 |
|---|---|---|---|---|
| Movement | Overlap pairs per 1k bots (median sweep) | health/*.jsonl `overlap` | ~80-100 (188-237 at 1.9-2.9k) | < 20 |
| Movement | Off-foothold bots per sweep (median) | health `foothold.bad` | 10-13 | ≤ 3 |
| Movement | Teleports/snaps while a player watches, per player-hour | *new* counter in `GCMovement.teleportTo` + `changeMap` when observed | not measured | < 1 |
| Movement | Town clusters at crowd points (3+ within 40 px at NPC/portal) | health `clumps` variant | 0 by design | some (> 0 per busy town) |
| Rhythm | Median session length / share of bots in a 5-20 min break | botroster SessionMinutes, `bot-life.json` | 120 min planned; breaks ≤ 100 s | spread 20-360 min; ≥ 5% on break at peak |
| Rhythm | Share of logins on the home channel | *new* login log | ~channel weights (random) | ≥ 75% |
| Rhythm | AFK campers in towns+FM at peak, per channel | *new* health check | ~0 | 5-15 |
| Hangouts | Bots in FM main 910000000 per channel at peak | ticket 100 health check | "bare" (owner) | ≥ floor (config) |
| Hangouts | Henesys level mix 1-30 / 31-70 / 71+ | `bot-life.json` (brain) | n/a on TEST (brain run: 59/120/60) | about 50/35/15 |
| Population | Share of roster under Lv30 / pirates+Aran / male | botstore | 16% / 0.5% / 50% | ≥ 40% / ≥ 15% / about 65% |
| Chat | Repeat rate (chat-quality `repetition.score`) | chat-quality-*.json | 0.074 | < 0.03 |
| Chat | Unanswered player questions | chat-quality `unanswered` | 7/66 (10.6%) | < 5% |
| Chat | Say-do backed rate (excluding ads) and `thread-llm` rate | chat-quality `sayDo` | 97.4% scripted, 86.4% thread-llm | ≥ 99% / ≥ 95% |
| Chat | Level-number mention rate in bot lines | transcripts regex | 11.6% | < 3% |
| Chat | Insult/hostile rate in bot-bot threads | transcripts regex | 6.0% (live 14.5%) | < 3% (rivals only) |
| Chat | "Essay" rate (capitalised sentence ending in a period) | transcripts regex | 12.0% | < 5% |
| Chat | Line-length median (chars) | transcripts | 39 | 15-30 |
| Chat | Relevance judged | chat-quality `relevance` | 0 judged (judge not running) | ≥ 30 judged/day, ≥ 85% on topic |
| Social | Party-chat lines answered by a bot in parties with a player | *new* (85) | 0 (no hook) | ≥ 80% of addressed lines |
| Social | "gz" reactions per player level-up on hunting maps | *new* LevelUpCongrats counter | 0 (training bots never react) | 0.3-0.6 |
| Social | Player smegas with ≥ 1 bot reaction | *new* UseCashItem hook | 0 | 40-70% |
| Social | Neutral-bot party accept rate; bot-sent buddy requests/day | BotRecruitManager log, BotBuddies | 0.3; ≤ 6/h cap | about 0.5; real count |
| Social | Fame given by bots/day (F4F, sold, defame) | *new* | 0 | > 0, within once-a-day rules |
| Social | Gossip lines that cite a real event in the last 2 h | *new* tag on gossip facts | 0 | ≥ 20% of gossip |
| Group | KPQ/LPQ lobby parties formed per peak hour | *new* | 0 | ≥ 2 per busy channel |
| Brain | Activity entropy per bot-session; share of decisions that are not GRIND | `bot-life.json` | n/a (not on TEST) | entropy > 1.2 bits; 25-40% non-grind |

## Per-dimension findings

Each dimension uses the same layout: **Today** (with evidence), **2009 player**, **Gap** (with severity), **Build on**
and **Proposal**.

### 1. Daily rhythm and sessions

**Today**
- Population curve `0:0.45 … 20:1.0, 22:0.8` (`src/main/java/config/ServerConfig.java:86-130`,
  `AP/BotPopulationCurve.java:17`), weekend ×1.1.
- One play window per bot: 55% start between 17 and 21 h; length 90-360 min; session `min(window, 40-240)` min
  (`AP/BotPersonality.java:74-80, 104-106`). The director jitters sessions by ×0.75-1.35 and enforces a 20 min relog
  cooldown (`AP/BotPopulationDirector.java:44-69`).
- TEST roster SessionMinutes: 1,408 at about 60 min, 1,673 at 90, 1,746 at 120, 1,505 at 150, 1,185 at 180, 875 at 210,
  382 at 240. A bot resumes exactly where it logged out (`AP/BotResume.java:153-170`).
- Logout lines ("gtg dinner", "gn all") are said only when a player can see the bot (`AP/BotDialoguePack/LogoutDialogue.yaml`).

**2009 player**
- Kids after school and late on weekends; all-day Saturday sessions.
- AFK for hours in town, or "brb dinner" for 20 minutes.
- Same channel every day ("ch 6 is my channel").
- Patch-day and event spikes.

**Gap**
- The same window every day.
- No home channel.
- No long brb or AFK.
- No event spikes.

Severity: Med. Friends who log in daily will notice who is "always there", which is a good thing to fake.

**Build on**
- `Traits.playWindowStart/Len`
- `Candidate.inWindow`
- `BotRoster.Member.homeMapId` (add a home channel)
- Brain BREAK / LOG_OUT

**Proposal**
- Per-bot weekday/weekend offsets.
- A home channel (sticky about 80%).
- A BREAK of 5-20 min with "brb" plus a chalkboard.
- A world-event or patch-day lift on the curve.
- Effort S.

### 2. Where they hang out

**Today**
- Fixed town headcounts (`AP/BotTownSystem/TownPresence.yaml`: Kerning 14+6, Ludi 14+6, …).
- Henesys has its own HenesysBot (`AP/BotTypes/HenesysBot.java:43-47`).
- 509 LevelingPath entries cover the classic spots and are followed 75% of the time (`AP/BotGrindSystem/LevelingPathChooser.java:49`).
- PQs are listed only as "lobbies for a future party system" (`LevelingPath.yaml:59-68`).
- The FM has artificial hired merchants (`src/main/java/soloMapling/FreeMarket/ArtificialFreeMarket.java:353-360`) but no floor in the main area (ticket 100: "bare since 1.5.2").
- Convo lines by map (TEST): Henesys 100000000 has 50% (741/1,484), Amoria 680000000 15%, the FM 2%.
- The brain's town is hard-coded to Henesys unless a friend is elsewhere (`origin/160-bot-brain` `LifeRuntime.java:29, 112-120`).

**2009 player**
- Crowds at the Henesys and FM entrances.
- KPQ and LPQ lobbies full of "LF>" shouts.
- "Ant Tunnel ch1 is full, cc".
- Leech lobbies; HT and Zak waiting rooms.

**Gap**
- No PQ lobby crowds.
- An empty FM entrance.
- Training maps spread out evenly by caps, so nothing is ever overcrowded.

Severity: High (the FM and lobbies are where friends look first).

**Build on**
- The ticket 100 floor
- `TownPresenceSampler`
- The OPQ orchestrator
- `BotMapCap`

**Proposal**
- **FM floor + AFK campers** (top-15 #3). Effort S-M.
- **PQ lobby crowds** that form bot parties and leave (staged, like boss runs; top-15 #11). Effort M.
- **Brain town choice weighted by level band:** Kerning for thieves, Perion for warriors, Orbis/El Nath 50+, Ludi 35-50. Effort S.
- **Allow some popular maps to run hot** (cap ×1.5 on 2-3 classic maps per band) so "crowded, cc" can happen. Effort S.

### 3. Movement

**Today**
- Ticket 33 flaws exist and are good: hesitation, overrun, early takeoff, rope slips and post-fall emotes (`AP/GCMoveSystem/NavMovementFlaws.java:61-62, 295-296, 578, 588, 673`).
  - They run only on observed maps.
  - Teleport and flash jump never fail.
- Idle fidget: one action every 5-14 s, picked from 4 kinds (`AP/GCMoveSystem/GCFidget.java:26-32`).
- Reaction to a passing player: ignore 55%, stop 15%, walk 30% (`AP/GCMoveSystem/BotPlayerReaction.java:37-51`).
- Spacing:
  - Town personal space is 25 px, with 3 bots per ledge (`AP/BotTownSystem/PersonalSpace.java:53-72`, `TownLoiter.java:36`).
  - Grinding needs 34 px.
- Travel is real (fares, boats, portals walked to: `AP/GCMoveSystem/GCTravel.java`, `AP/BotTravelSystem/BotTransit.java`).
- Snaps:
  - CampStrategy teleports after 2.5 s off the anchor (`CampStrategy.java:45, 710`).
  - No hit in 30 s: teleport to a portal (`TrainingBot.java:1344-1368`).
  - First trip after boot: 70% warp straight to the grind map, but only when unobserved (`TrainingBot.java:158`).
- TEST health: overlap FAIL every day (median 188-237 pairs, max 810); 10-13 off-foothold per sweep, clustered in FM rooms 9100000xx; stuck checks OK.

**2009 player**
- Stands perfectly still for minutes.
- Jump-spams while waiting.
- Bunches on one pixel at an NPC or portal.
- Never teleports except with the Teleport skill.
- Flash jumps over the edge and falls.

**Gap**
- Visible snaps (High).
- Same-pixel stacks in odd places and floaters (High).
- Town spacing too even, and fidgets too regular (Med).

**Build on**
- The NavMovementFlaws model
- GCFidget
- BotSpotClaims
- nutnnut fixes `235b6aefa`, `8a7e7b3a5`, `362926fdf`, `26b372e7c`, `43b9f06c8`

**Proposal**
- No `teleportTo` when observed: walk back or give up (S-M).
- Port the ground-continuity fix and the drop fixes (M).
- Fidget variety:
  - long still periods (30 s-5 min);
  - jump-spam bursts;
  - occasional Flash Jump and Teleport overshoot (S).
- "Crowd points" at NPCs and portals that are exempt from personal space (S).

### 4. Combat

**Today**
- Up to three attacks per job: single target, area at 3+ mobs, and a throttled ultimate. Extra rotations exist only for pirates and Aran (`AP/BotAttackSystem/BotAttackConfig.java:114-225`, `BotSkillChoice.java`).
- Live offscreen logs show one skill per class: F/P Wizard 2101004, Page 1001005, Assassin 4001344.
- Habits are seeded per bot (`AP/BotCombatSystem/BotHabits.java:44-52`):
  - pot at 40-60% HP, 3-23% late;
  - rebuff 0-25 s late;
  - sloppy 2-12%.
- Buffs are visual only (`AP/BotAttackSystem/BotBuffDriver.java`).
- Swing timing is exact (`BotSwingClock.java`).
- Deaths:
  - only on observed maps, with mobs 3+ levels higher, a 20 min per-bot gap and fewer than 6 deaths per 10 min server-wide (`AP/BotCombatSystem/BotDeaths.java:35-39`);
  - offscreen bots never die;
  - the live journal shows no bot deaths.
- Offscreen bots carry big pot stacks (e.g. 1,278 HP / 1,372 MP pots on a Lv66 F/P).
- KS manners:
  - normal bots leave a player's platform alone (`AP/BotGrindSystem/KsManners.java:30-34`);
  - only rivals KS;
  - ticket 66: war helpers land on the wrong ledges.

**2009 player**
- Clerics Heal-spam mobs.
- Mages at 30 still use Magic Claw.
- Pots at the last second, or dies.
- "ks?" / "this is my spot" fights.
- Stops to chat mid-grind.
- Buffs every 2 min with an audible pattern.

**Gap**
- Machine rhythm (Med).
- Too polite on KS (Med).
- Deaths almost never seen (Low-Med).

Cleric heal-attack and similar signature moves are a nice touch (Low).

**Build on**
- BotHabits
- BotSwingClock
- BotDeaths gates
- KsManners plus affinity

**Proposal**
- Swing jitter (+0-180 ms, with a pause of 1-4 s every 20-60 swings) (S).
- Cleric Heal as an attack on undead maps (S).
- KS words:
  - a neutral bot that loses a mob to a player says "ks?" 10% of the time;
  - a bot claiming a spot types "spot taken pls" (S).
- Visible deaths: allow one per busy observed map per 30 min ("omg lag", tombstone, walk back) (S).
- Make buffs real stats only if combat tuning is reopened (skip).

### 5. Progression

**Today**
- Textbook builds (`AP/BotProgressionSystem/BotBuild.java:24-47`).
- The job is set directly at 10/30/70/120 (mages at 8) with no NPC or quest (`BotProgression.java:44, 274`).
- No bot code starts or completes quests.
- Offscreen EXP runs at 1/3 rate, and levels pop in on the next spawn.
- Gear is strictly optimal (`AP/BotGearSystem/BotGearPlanner.java:14-29`).
- Scrolling is real: live logged 444 scroll uses in about 55 min (248 SUCCESS, 115 FAIL).
- TEST level bands:

| Level | 0-9 | 10s | 20s | 30s | 40s | 50s | 60s | 70s | 80s | 90s | 100+ |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Bots | 57 | 540 | 850 | 1,036 | 1,228 | 1,446 | 1,592 | 1,468 | 1,218 | 622 | 33 (+66 at Lv200, staff/showcase) |

**2009 player**
- Odd builds.
- Job advances are an event: "brb 2nd job test", "omw to perion".
- 4th-jobbers flexing in Henesys.
- A slow grind with long plateaus (stuck at 69 for weeks).

**Gap**
- No visible job-advance moments (Med).
- Uniform builds (Low-Med; only visible through `@stats`-style chat).
- Few 120+ bots to flex (Med).
- Weapon goals in the brain branch are good, but no "saving for X" talk ties to real meso progress (Low).

**Build on**
- The BotBuild `Choice` seed
- LifeGoals (brain)
- LifeMemory JOB_ADVANCE

**Proposal**
- When a bot reaches an advance level on an observed map:
  1. LIFE_TRAVEL to the instructor town;
  2. stand by the NPC 20-60 s;
  3. come back with a party/guild/all-chat line ("finally 3rd job!!"), plus gossip (#5) (M).
- 5% quirky builds (S).
- Seed a thin tier of Lv120-140 veterans (S, roster config).
- Ticket 30 (earned rebirth) is separate.

### 6. Economy

**Today**
- Ads are real:
  - S>/B> ads only for held or wanted items (`AP/BotConvo/ClaimActions.java:214-216`); say-do for ads is 40,286/40,287 backed.
  - Haggling: HaggleBrain + PriceBand (`src/main/java/soloMapling/Economy/HaggleBrain.java:36-40`).
- Bot shops: at most 8 world-wide, 6 h each (`Economy/BotShops.java:41-55`).
- Gachapon for bots over their wealth ceiling (`Gachapon/BotGacha.java:27-33`).
- The wealth upkeep sink runs very often: 2,624 live lines in about 1 h, invisible to players.
- Fame: display only (`AP/BotFame.java`).
- Scams and RWT: flavour text only (`MerchantBotDialogue.yaml:48, 75, 133`).
- Flipping: only a 1.15 resale markup.

**2009 player**
- "S> fame 50k".
- F4F on sight.
- Drop-trade and trade-window swap scams.
- Flipping FM underpricers.
- "pc?" whispers.
- Overpricing and lowballing.

**Gap**
- Fame (Med).
- Small real scams and flipping (Med; owner goal).
- Bot shops too few at 8 world-wide (Med for a busy FM).

**Build on**
- BotTradeSystem
- PriceBook
- SaleRules
- BotSellerCaps
- Affinity FAME/DEFAME deltas

**Proposal**
- F4F and fame selling (S-M).
- "Bad intentions" for greedy personalities (M), all inside the existing caps and without new meso sources, as the owner set:
  - lowball offers;
  - a trade-window "oops wrong item" that the player must accept;
  - flipping underpriced player shop items.
- Raise the bot shop cap per channel for the busy FM (S, with economy sign-off).
- "pc?" whisper answers from the price board (S).

### 7. Social (memory, relationships, guild, buddy, marriage, gossip)

**Today**
- Memory:
  - `botmemory` notes are persisted with no time decay (`AP/BotMemory.java:36-48`) and written only with the LLM on (:175-184).
  - TEST: 1,994 notes (776 DIRECT / 1,218 HEARSAY) across 472 bots and 5 characters.
  - Note quality is LLM-ese at times ("Admin plans to raise the user's fame.").
- Affinity: friend, rival and nemesis, with decay (grudges halve in 72 h, friendships in 14 days; `AP/BotAffinitySystem/AffinityRules.java:24-58`). Only 16 rows on TEST.
- Buddies: accept 0.2 base up to 0.95; bots also send requests (`AP/BotBuddies.java:49-55`). 61 bot buddy rows on TEST.
- Guilds: about 10 bot guilds with scripted guild chat every 8 min (`GuildRules.java:80`).
- Couples: bot+bot only, at most 4 (`AP/BotCoupleSystem/BotCouples.java:42-60`).
- Gossip passes only player notes (`AP/BotGossip.java:22-26`).
- Recognition greeting banks hold 12 lines (`AP/BotRecognition.java:95`).

**2009 player**
- Remembers you from last week ("omg ur back").
- Has a crew of real friends.
- Gossips about who got married and who scammed whom.
- Guild chat is busy at peak.

**Gap**
- No sense of time in memory (Med).
- No bot-bot friend crews that hang out together (Med-High: two named bots always seen together sells "players").
- Gossip has no world facts (Med).
- Guild chat is a fixed 8 min ticker (Low-Med).

**Build on**
- `botmemory`
- AffinityReactions
- LifeMemory (brain branch)
- WorldEventReactions memory notes

**Proposal**
- Note age in the recall card ("3 days ago").
- Scripted note writes ("partied with X at Y") when the LLM is off.
- Bot↔bot friend pairs and crews that the brain pulls together (M).
- World-fact gossip (#5).
- Guild chat driven by guild events (a member levels, a war score, a boss run) instead of a timer (S).

### 8. Chat voice

**Today**
- The prompt asks for a 2008 teen voice under 60 chars (`AP/BotChatPrompt.java:68-79`).
- Voices: LOWER 55, PROPER 18, LEET 8, KOREAN 7, SPANISH 7, TERSE (`AP/BotConvo/ConvoPersona.java:18-32`).
- Typos 0.5-4%.
- Typing delay (`AP/BotConvo/HumanTiming.java:40-83`):
  - player replies measured at p10/p50/p90 = 2.2/4.0/5.8 s (TEST) and 2.5/4.6/6.0 s (live);
  - the 6 s ceiling is visible.
- The bank holds 9,902 lines (the `ChatBank.java:5` doc says "hundreds of thousands").
- Chat-quality (TEST, 24 h to 10-25 16:29 UTC):

| Measure | Value |
|---|---|
| Lines | 789 |
| Repeats | 7.4% (top: "chatting with u rn", "im at henesys rn come by if u want", "oh hey") |
| Unanswered | 7/66 |
| Say-do | 100% of 40,873 (scripted 97.4%, thread-llm 86.4%) |
| Relevance | **0 judged** |

- Unbacked examples: "lvling at Henesys Hunting Ground I today" said by 3 bots that never went there; "out of mana, brb" said while the bot kept playing.
- Transcript rates (1,842 lines):

| Measure | Value |
|---|---|
| Median length | 39 chars |
| Level mentions | 11.6% |
| Insults | 6.0% |
| Capitalised sentence with a period | 12.0% |

- Unmerged help: `160-convo-scope` fixes negation, quotes and past tense in request detection (fresh-v2: 70.2 → 85.7%).

**2009 player**
- Lines of 5-25 chars ("lol", "ya", "gz", "wat", "kk brb").
- Many lines are just reactions.
- Lots of ignoring.
- The same friend says the same catchphrase *on purpose*.

**Gap**
- Lines too long and too complete (Med-High).
- Level-number parroting and hostility in bot threads (Med-High).
- Ambient repetition and one shared voice (Med).
- The relevance judge is not running, so quality is unmeasured (Med, tooling).

**Build on**
- VoiceStyler
- RecentLines
- HumanTiming
- ChatScope (convo-scope)
- ConvoQuality metrics

**Proposal**
- Short-reply mode: 40% of replies and thread turns at ≤ 20 chars or a pure reaction (S).
- Thread card changes (top-15 #7) (S).
- Repeat memory and styling on ambient lines (#8) (S).
- Widen HumanTiming:
  - a 10% "answers 15-90 s later";
  - a 15-25% "ignores small talk" for grinders (S).
- Fix the 2 unbacked scripted line families (train_at, away) (S).
- Get the relevance judge running again (S).

### 9. Group play (parties, PQs, bosses)

**Today**
- Recruiting:
  - chat asks accepted at 0.70 (social) and 0.80 (training);
  - unsolicited invites at about 0.3 for a neutral bot;
  - bot-to-bot invites are rejected;
  - follower cap 30 (`AP/BotPartySystem/BotRecruitManager.java:26-34, 116-190`).
- OPQ covers stage 1, the tower and stage 2 only (`AP/BotTypes/OPQ/OPQConstants.java:26-28`).
- No KPQ or LPQ code.
- GuildBossRuns: gather 4 min, then log out for 25 min and brag (`AP/BotGuildSystem/GuildBossRuns.java`).
- No party chat (#1).
- Coordination lines ("wait", "ready", "go") don't exist.
- Brain branch: PARTY_QUEST and HELP_FRIEND start only on a real friend's request.

**2009 player**
- "LF> 1 more for KPQ".
- Leech parties.
- "pt ludi pq?".
- Zak runs with "rdy?".

**Gap**
- Parties feel mute and refusing (High).
- PQ worlds are empty (High).

**Build on**
- The Companions epic (99)
- nutnnut's `PartyAutopilotState` and KPQ/ZPQ step scripts (ticket 87)
- The OPQ orchestrator

**Proposal**
1. Party chat (85) (S-M).
2. Companions (99) (L).
3. Staged PQ lobby parties (M).
4. Real KPQ worker roles with a human leader (M, per ticket 87).

### 10. Cash and cosmetics

**Today**
- Pets: about 25% of Lv30+ bots, one pet, names like "Mochi", "Boba" and "Oreo" (`AP/BotPets.java:35-58`). No pet commands or 3-pet setups.
- No mounts.
- NX pieces: `NX_BASE_CHANCE` 0.20, rings S 0.40 to D 0.02 (`AP/BotDecoratorSystem/BotDecorateNX.java:40-63`).
- Chairs: 63 ids. SocialBot sits at 4%/tick with a 60-180 s cooldown (`AP/BotTypes/SocialBot.java:76-78`).
- Chalkboards: 8% of hot-potato actions, cleared after 5 min.
- Skin: 80% white / 20% tan only (`BotDecorateBody.java:15`).
- No omok or match-card rooms (ticket 77 open, owner said yes for 1.6.0).
- Emotes are mostly smiles (flavour faces {smile, smile, bewildered}).

**2009 player**
- Pet names like "Fluffy", "xXPuppyXx" or the owner's name.
- Hogs and silver mounts at 70+.
- An Omok table in every Henesys corner.
- Hours-long chalkboards.
- Every v83 skin tone.
- The full emote set (F1-F7 spam when happy or angry).

**Gap**
- Omok (Med-High in towns).
- Mounts (Low-Med).
- Pet names (Low).
- Chalkboard length (in #3).
- Emote range (Low).

**Build on**
- Ticket 77
- BotCustomization chair list
- ReplyEmote (faces 1-7)

**Proposal**
- Omok and match-card rooms with heuristic AI (M, ticket 77).
- Mount on 70+ travel when observed (S, needs a real-client check).
- Era pet names (S).
- Full skin set (S).
- An emote mood table (S).

### 11. Other tells

- **Offscreen versus onscreen.** Everything human (flaws, deaths, chairs, sloppy casts) runs only on observed maps. Offscreen bots level, fight and pot perfectly, which shows only as level pop-in. This is acceptable for CPU; just keep the pop-in small (cap offscreen gains to under 1 level per session).
- **Henesys bias.** 50% of convo lines are in Henesys, and the brain hard-codes Henesys as its town. See §2.
- **Names** are good: 12k era-style plus 1,636 real IGNs. A few anachronisms ("ImMochi") are Low.
- **Wealth upkeep** logs about 2.6k lines an hour on live. It's invisible to players, but trim it to DEBUG for log hygiene.

## What the brain branch already covers, and what it doesn't

**Covers (once merged and enabled):**
- Varied days: grind blocks, town stays, FM visits, sell trips, help-friend and PQ on request.
- Restock trips that match "brb pots".
- Goals.
- Bounded personality drift.
- `!botwhy`.
- `bot-life.json` metrics.

Measured in isolation: about 1,050-1,100 decisions/min at 3k bots, 0.7-1.2 mean cgroup cores, all strong claims backed.

**Doesn't cover:**
- Movement snaps and overlaps.
- Party chat.
- Smega reactions.
- Fame, scams and flipping.
- AFK campers.
- Population makeup.
- Combat rhythm.
- Chat style.

Most of the top 15 sit next to the brain, not inside it. Several (#3, #5, #12, #13) should become brain options or
interrupts once it is merged.
