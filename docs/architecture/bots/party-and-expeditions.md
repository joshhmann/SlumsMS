# Party, party quests and expeditions

## Purpose

Bots form real `Party` and `Expedition` objects through the same engine paths a player uses, so the party quests and
boss rooms see ordinary members. Four things put a bot into a party: a player inviting it (queued and accepted by an
invite drain), two bots forming one out of an LFG chat thread, a lobby that staffs a party quest out of bots already
standing at the recruit NPC, and a player's expedition auto-registering the party's bots. A fifth, `OPQBot`, is a
scripted dev bot that recruits a party in the Orbis PQ lobby and then plays the instance itself.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `PartyOperationHandler` case 4 (invite) | a real player invites a bot; the engine queues it | src/main/java/net/server/channel/handlers/PartyOperationHandler.java:76 |
| `BotPartyQueue.addPartyInvite` | the invite hook after `InviteCoordinator.createInvite` succeeded | src/main/java/net/server/channel/handlers/PartyOperationHandler.java:109-115 |
| `BotRecruitManager.pollInvites` | every recruit-capable bot tick: `SocialBot`, `TrainingBot`, `OPQBot` | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:155 |
| `BotPartyLogic.checkPartyQueue` | `OPQBot.handleRecruitment` (accepts anything, no armed check) | src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:308 |
| `LfgParty.form` | a bot talk thread decided "yes, let us do the PQ" | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:344 |
| `KpqLobby.tick` | server boot timer, once a minute | src/main/java/soloMapling/mechanics/KpqLobby.java:108 |
| `ContentLobby.startIfEnabled` | `Server` boot; once a minute per on row | src/main/java/soloMapling/mechanics/ContentLobby.java:85 |
| `ExpeditionBots.autoJoin` | `Expedition.start()` (registration still open) | src/main/java/server/expeditions/Expedition.java:201 |
| `ExpeditionBots.startAsCaptain` | `!mechanics boss TYPE [bots]` → `BossStaging.start` | src/main/java/soloMapling/mechanics/BossStaging.java:100 |
| `SocialInitiative.lobbyTick` → `invitePq` | a bot dwelling in a PQ lobby with a real player of the band | src/main/java/soloMapling/ArtificialPlayer/SocialInitiative.java:729 |
| `!bot makeparty/leaveparty/acceptinv/rejectinv/inviteparty/checkpartyqueue` | GM diagnostics | src/main/java/client/command/commands/gm4/ArtificialPlayerCommand.java:394-412 |
| `!opq help/status/start/spawn/...` | OPQ dev harness | src/main/java/client/command/commands/gm4/OPQCommands.java:33-56 |
| `LifePolicy` `PARTY_QUEST` / `INVITE_PQ` | the brain scores the activity and `TrainingBot` runs the trip | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePolicy.java:107 |

## How it works

### 1. Invite queue (player → bot)

| Phase | Action | File:line |
|---|---|---|
| gate | `OwnedAltService.interactionRefusal`, level-10 starter rule, `GuildBossRuns.freeForInvite` | src/main/java/net/server/channel/handlers/PartyOperationHandler.java:79-98 |
| queue | `InviteCoordinator.createInvite` then the `party invite` hook calls `addPartyInvite` | src/main/java/net/server/channel/handlers/PartyOperationHandler.java:109-115 |
| store | last-wins `ConcurrentHashMap`; `wakeBotForInvite` nudges the bot's brain 300 ms later | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyQueue.java:40-51 |
| arm | `armIfWelcome` rolls the chance when the player's own text was a party ask | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:120-148 |
| drain | `pollInvites`: id match → accept; anything else → polite reject | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:155-210 |

`BotPartyQueue` is deliberately last-wins: the coordinator already serialises live invites, so a first-wins entry could
only ever be stale and would wedge the player's live invite (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyQueue.java:40-45).

`armIfWelcome` reads the chance from `BotGuilds.partyChance` (affinity, guildmate, guild boss run,
src/main/java/soloMapling/ArtificialPlayer/BotGuildSystem/GuildRules.java:232-243) and marks the invite as
follower-converting when the bot is a `SocialBot` (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:139-143).

`pollInvites` accepts on inviter-id match alone, with no window check, because the id proves this is the player the bot
agreed to and `InviteCoordinator`'s own ~3-minute expiry bounds staleness (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:172-186). Capacity is
reserved **before** the join when the accept converts to a follower; a refused reservation fails closed to a reject
(src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:188-199). The reservation is released only if the join itself failed at the coordinator
(src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:206-207).

### 2. LFG thread → real party → PQ trip

| Phase | Action | File:line |
|---|---|---|
| plan | `LfgParty.plan` → `decide`: recruiter's level picks the lobby, both states checked | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:153-171 |
| fit | `fit` rules in order: owned, partied, afk, shop, busy, no trip, too low, too high | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:116-141 |
| cap | `take` reserves one of `lfgPartiesPer10Min` (default 3) slots in a 10-min window | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:213-226 |
| lines | `lines` builds the ask/answer exchange; `promptTurn` asks the model only when the bank cannot answer | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:249-320 |
| form | recheck both online/on-map/free, `Party.createParty(a,true)`, `Party.joinParty(b,…)` | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:344-363 |
| ledger | `SayDo.event(PARTY)` on both, so the "k sending inv" line is backed | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:376-377 |
| trip | `Trip` for `TRIP_MS` = 15 min, both `submit` a `PARTY_QUEST` life activity | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:378-381 |
| end | after 15 min `end` breaks a bot-only party; a party a real player joined is left alone | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:486-500 |

The trip is a commitment: `LfgParty.holds` keeps the bot from logging out (src/main/java/soloMapling/ArtificialPlayer/BotLogout.java:92,
src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:602) and from being picked for `LOG_OUT`
(src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1026). `TrainingBot` learns the destination from
`LfgParty.lobbyMap` and treats it as `lifePqTrip` (src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:996-1006, :1088, :1100, :1105-1108).

### 3. Life-driven PQ trip (no player, no LFG)

`LifePq` holds the three v83 lobbies and the opening hours (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePq.java:28-31,
:50-64). `LifeRuntime.pqDestination` returns the plan's PQ town if the plan's next step is `PARTY_QUEST`, else the
level's lobby, else -1 (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:180-185). The lobby
maps are in the may-load allowlist (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:188-198), so the bot arrives and loads the map like a player.

`TrainingBot.canStartLifeActivity(PARTY_QUEST)` requires a destination, no existing party, not already there, and
either a plan step, an LFG trip, or `LifePq.pqHoursNow()` (src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:996-1006). `pqHoursNow` is true after
16:00 and on weekends after 10:00, unless `-Dslums.botlife.pqAlways` (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePq.java:50-64); with no config read (unit
tests) it returns false rather than throwing (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePq.java:62-63).

Bots that reach a lobby do not idle: `SocialInitiative.lobbyTick` scans for a real player of the band every 20-45 s and
`invitePq` says a line and sends a real party invite (src/main/java/soloMapling/ArtificialPlayer/SocialInitiative.java:729-800).

### 4. Lobby → lobby → stage (bot parties that start a PQ themselves)

**Kerning (live).** `KpqLobby.tick` walks every channel once a minute, takes the bots standing at Lakelis
(103000000) whose `free` check passes, sorts by level descending, caps at `content.maxPlayers()`, and asks
`RealRunPool` before making anything (src/main/java/soloMapling/mechanics/KpqLobby.java:108-152). `begin` creates the
party (`Party.createParty(leader,true)`), joins the rest, talks to Lakelis through `BotNpcTalk`, and hands the resulting
`EventInstanceManager` to `MechanicsRuns.start` (src/main/java/soloMapling/mechanics/KpqLobby.java:366-431). One start a tick, world-wide; a refused
channel is left alone for `REFUSED_BACKOFF_MS` = 10 min (src/main/java/soloMapling/mechanics/KpqLobby.java:43, :223-226).

`free` rejects owned alts, partied bots, bots already in an instance, dead bots, bots named by a simulated run, bots
under a mechanics hold, and bots with fewer than `MIN_FREE_ETC_SLOTS` = 3 free etc slots (src/main/java/soloMapling/mechanics/KpqLobby.java:156-175). The
slot rule is b153's fix: 26 of 35 lobby answerers never handed in a pass (src/main/java/soloMapling/mechanics/KpqLobby.java:170-176).

**Every other row (off on live).** `ContentLobby` spawns `content.minPlayers()` fresh bots at the row's `entryNpc` on
its `recruitMap` and calls the same `KpqStaging.start` (src/main/java/soloMapling/mechanics/ContentLobby.java:19-33,
:135-160). `ROWS` lists the twelve event scripts it staffs (src/main/java/soloMapling/mechanics/ContentLobby.java:52-57); `kpq` is deliberately absent
because it has `KpqLobby`, and `DelliBattle` is absent because its script wants one to two players (src/main/java/soloMapling/mechanics/ContentLobby.java:47-51).

**Stage.** `KpqStaging` is the one staging every PQ uses: `RECRUIT_MAP` 103000000, `LAKELIS` 9020000,
`BOT_LEVEL_MIN/MAX` 30/35, `DESPAWN_AFTER_MS` 20 s (src/main/java/soloMapling/mechanics/KpqStaging.java:35-41).
Staged bots get `Math.max(content.minLevel(), 30)` and are spawned 0-5 levels above it (src/main/java/soloMapling/mechanics/KpqStaging.java:228-229).

**Pass hand-over.** `PassHandover` is the stage-1 rule bots follow with coupons/passes: walk within 60 px of the leader
and drop, take back after 20 s if the leader stands more than 100 px from the drop (src/main/java/soloMapling/mechanics/PassHandover.java:21-26,
:40-81). `KillCollectHandInSolver` waits by the NPC at 150 px once it holds `GIVE_AT` = 3 (src/main/java/soloMapling/mechanics/KillCollectHandInSolver.java:27-29). A collector carries up to 3 before walking them over; its last 1-2 go over once the map is clear or the party holds the hand-in count between them (lpq1-pass-shortfall, 1.6.7: a respawning map is never clear).

### 5. OPQ bots (scripted, dev harness)

`OPQBot` is a 20-state machine (src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:144-165) driven by
`updateState` (src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:186-215), with the map as the authority: `detectPhaseFromMap` re-homes the bot whenever the
server teleported it somewhere unexpected (src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:1008). The lobby phase `handleRecruitment` accepts any pending
invite through `BotPartyLogic.checkPartyQueue`, then advertises at a per-lobby rate
(src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:301-340,
src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQLobbyChat.java:34-59).

Once party and idle, `OPQPartyDuty.decide` answers one question: is this party going to run OPQ with us?
`FOLLOW` when the leader is in stage 1, `LEAVE` after the grace period otherwise — 5 min pre-run, 1 min post-run,
90 s elsewhere, 15 s when the leader is offline (src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQPartyDuty.java:26-52).
`FOLLOW` warps the bot in with a deliberate 3 s blocking sleep (src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:378-388).

`OPQOrchestrator` is a 1 Hz blackboard: bots read freely, the only write a bot may make is `markTaskComplete`
(src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQSharedContext.java:11-19,
src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQOrchestrator.java:47). Stage flags are keyed by `MapleMap`
identity, not map id, so two parties in 920010000 cannot cross-finish each other's stage
(src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQSharedContext.java:33-42). Lobby population comes from `EnvironmentManager.spawnOPQBotsInLobby` (10-15 bots, level
50-70, adopted bots keep their level; src/main/java/soloMapling/Environment/EnvironmentManager.java:1101-1136).

### 6. Expeditions

| Phase | Action | File:line |
|---|---|---|
| gate | `autoJoin` returns empty unless registering, leader's expedition, captains allowed, type not ARIANT | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:101-108 |
| pick | per-bot `skip` (bot, member, banned, owner, dead, map, level, quest) then quota, busy, `follows` | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:113-141 |
| follow | an alt or `FollowerBot` already follows; a `TrainingBot` is ordered `Verb.FOLLOW` | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:526-540 |
| register | `addMemberInt`, `ExpeditionHold.hold`, "[Expedition] X joined with you." | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:364-381 |
| captain | `startAsCaptain`: script from `RebirthBosses.scriptFor`, new `Expedition`, `attemptBoss`, `startInstance` | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:220-262 |
| promote | a real player in the party outranks the bot captain and is promoted | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:183-205 |
| hold | `ExpeditionHold.committed` blocks logout and drift on every bot tick | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionHold.java:29-33, src/main/java/soloMapling/ArtificialPlayer/BotCommitments.java:70-72 |
| release | `Expedition.dispose` releases the hold and the quota maps | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:444-452, src/main/java/server/expeditions/Expedition.java:165 |

A bot captain that cannot reach `minSize` gives the room back with `dispose` + `removeChannelExpedition`
(src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:250-256). The one live caller is `!mechanics boss`, and the release conf says so explicitly — no
scheduler, no lobby and no tick starts a bot-captained run (docs/release/1.6.4.conf:69-72).

Chaos Zakum bots share a world-wide daily budget of `CHAOS_ZAKUM_BOT_DAILY_RUNS` = 25 entries, cut from the bots picked
now, with one line telling the leader (src/main/java/server/expeditions/ExpeditionBossLog.java:248,
src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:157-180).

### 7. Party chat

`BotPartyChat` answers a party line every 1.5 s and other lines every 6 s, at most 8 bot lines a minute, with a 4 %
solo-chatter chance (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyChat.java:37-42).
`PartyBotOrders.fromPartyChat` runs first, so an order in party chat is obeyed before the bot replies
(src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyChat.java:70).

## Data

### Party quests a bot can be put into, and their gates

| Row | Script | Recruit map | Players | Min level | Flag | Live |
|---|---|---|---|---|---|---|
| kpq | KerningPQ | 103000000 | 3-4 | 21 | (none; KpqLobby) | on |
| lpq | LudiPQ | 221024500 | 5-6 | 35 | `slums.mechanics.lpq` | off |
| opq | OrbisPQ | 200080101 | 5-6 | 51 | `slums.mechanics.opq` | off |
| hpq | HenesysPQ | 100000200 | 3-6 | 10 | `slums.mechanics.hpq` | off |
| cwkpq | CWKPQ | 610030020 | 6-30 | 90 | `slums.mechanics.cwkpq` | off |
| cafepq1-6 | CafePQ_1..6 | 193000000 | 3-6 | 21 | `slums.mechanics.cafepq` | off |
| ludimazepq | LudiMazePQ | 220000000 | 3-6 | 51 | `slums.mechanics.ludimazepq` | off |
| ellinpq | EllinPQ | 300030100 | 4-6 | 44 | `slums.mechanics.ellinpq` | off |
| piratepq | PiratePQ | 251010404 | 3-6 | 55 | `slums.mechanics.piratepq` | off |
| treasurepq | TreasurePQ | 674030100 | 4-6 | 140 | `slums.mechanics.treasurepq` | off |
| magatiapq / magatiapqz | MagatiaPQ_A / _Z | 261000021 / 261000011 | 4-4 | 71 | `slums.mechanics.magatiapq(z)` | off |
| zakumpq | ZakumPQ | 211042300 | 1-6 | 50 | `slums.mechanics.zakumpq` | off |
| guildquest | GuildQuest | 101030104 | 6-30 | 10 | `slums.mechanics.guildquest` | off |
| bossrush0-7 | BossRushPQ | 970030000 | 1-6 | 1 | `slums.mechanics.bossrush` | off |
| dellibattle | DelliBattle | 925010200 | 1-2 | 120 | `slums.mechanics.dellibattle` | off, no lobby |

Sources: src/main/java/soloMapling/mechanics/content/*.yaml (each row's `recruitMap`/`minPlayers`/`maxPlayers`/`minLevel`/`flag`),
`KpqStaging.BOT_LEVEL_MIN/MAX` (src/main/java/soloMapling/mechanics/KpqStaging.java:38-39).

The bot's own lobby table is narrower than the content rows: `LifePq.LOBBIES` only knows kpq 21-30 / lpq 35-50 / opq
51-70 (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePq.java:28-31), so a life-driven trip only ever goes to those three.

### Expedition types bots are admitted to

`ExpeditionType` carries `(minSize, maxSize, minLevel, maxLevel, minutes)` (src/main/java/server/expeditions/ExpeditionType.java:114).
Bots are excluded from ARIANT/ARIANT1/ARIANT2 (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:411-414); the level gate is the type's own
(src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:390). Zakum 6-30 lv 50, Chaos Zakum 6-30 lv 120, Scarga 6-30 lv 100
(src/main/java/server/expeditions/ExpeditionType.java:35-40).

### Stored state

| Store | Key | What | File:line |
|---|---|---|---|
| `BotPartyQueue.queues` | `Character` | latest invite (inviter, partyId) | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyQueue.java:29-51 |
| `BotRecruitManager.ARMED` | bot id | inviter id, expiry, follower flag | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:44, :143 |
| `LfgParty.trips` | char id | `Trip(plan, until, threadId, started[3])` | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:104 |
| `ExpeditionHold.joined` | bot id | the expedition it joined | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionHold.java:20 |
| `OPQSharedContext` | map identity | phase, stage-complete sets, assignments | src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQSharedContext.java:33-42 |
| `KpqLobby.running` | `EventInstanceManager` identity | runs in flight; name is not identity | src/main/java/soloMapling/mechanics/KpqLobby.java:46-52 |

## Switches

| Switch | Default | Live | File:line |
|---|---|---|---|
| `-Dslums.mechanics=true` | off | yes | src/main/java/soloMapling/mechanics/Mechanics.java:196-200, docs/release/1.6.4.conf:271 |
| `-Dslums.mechanics.lobby=true` (KpqLobby) | off | yes (since 1.6.1) | src/main/java/soloMapling/mechanics/KpqLobby.java:40, :58-59, conf:45 |
| `-Dslums.mechanics.lobbyRuns` | 2 | unset | src/main/java/soloMapling/mechanics/KpqLobby.java:62 |
| `-Dslums.mechanics.<row>.lobby` | off | no (conf:90-91) | src/main/java/soloMapling/mechanics/ContentLobby.java:36-38 |
| `-Dslums.mechanics.<row>.runs` | 1 | unset | src/main/java/soloMapling/mechanics/ContentLobby.java:79 |
| `-Dslums.exped.botcaptain` | ON (only `false` disables) | on | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:58, :68-69, conf:69-72 |
| `-Dslums.exped.altfollow` | ON | on | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:66, :72-73, conf:74 |
| `-Dslums.exped.door` | off | no | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionDoor.java:24-30 |
| `-Dslums.bots.partysupport` | off | yes | src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:28-30, conf:271 |
| `-Dslums.botlife.pqAlways` | false | n/a (QA) | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePq.java:58 |
| `-Dslums.chaos.zakum=true` | off | yes (conf:53) | content/zakum-chaos.yaml:flag |
| `solomapling.partyChat` | true | defaults on | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyChat.java:57-59 |
| `solomapling.opq.lobbyRecruitPerMinute` | 3 | dev only | src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQLobbyChat.java:34-36 |
| `lfgPartiesPer10Min` (`ConvoSwitches`) | 3 | default | src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:213-214 |

TEST: unknown (start-test.sh unreachable from this checkout).

## Invariants

1. A bot's relation to a party/expedition is the real `Party`/`Expedition` object — no parallel bot-party registry. The
   only side tables (`ExpeditionHold`, `BotPartyQueue`) cache an identity the engine already owns.
2. Every queued invite is answered. `BotPartyQueue` is last-wins, so a silent bot would wedge the player's live invite
   (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyQueue.java:40-45, src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:153-158).
3. Follower capacity is reserved before the join, never after (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:186-199).
4. A real player always outranks a bot captain or recruiter (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:183-205, src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:153-160).
5. A lobby start goes through `RealRunPool` first; a refused run gets no party, no NPC talk and no instance
   (src/main/java/soloMapling/mechanics/KpqLobby.java:133-152, src/main/java/soloMapling/mechanics/ContentLobby.java:136-149).
6. Stage progress is per map instance, never per map id (src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQSharedContext.java:33-42).
7. An LFG trip never breaks a party a real player joined (src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:493-497).

## Origin

**custom** — the party/PQ/expedition bot layer is SlumsMS's own. Oldest commits:

| File | Oldest visible commit |
|---|---|
| `ExpeditionBots.java` | `62745c56e` 2026-10-26 "Let party bots join their player's boss expedition" |
| `LfgParty.java` | `53af04873` 2026-10-26 "LFG threads form a real party and PQ trip; PQ picked from the recruiter's level" |
| `KpqLobby.java` | `9f13610b4` 2026-10-30 "Let bot-only lobby parties start Kerning PQ on their own" |
| `ContentLobby.java` | `4a64807a7` 2026-11-02 "164: one party-quest lobby for every row, not an Orbis one" |
| `BotRecruitManager.java` | `03d13cb21` 2026-10-18 "Establish deployable MapleRoot server baseline" (file present at baseline, content since rewritten) |
| `OPQBot.java` | `03d13cb21` 2026-10-18 baseline |

The engine side (`PartyOperationHandler`, `Expedition`, `ExpeditionType`, `InviteCoordinator`) is the MapleRoot base;
the `soloMapling` hooks inside it are ours (`// SlumsMS rc6` markers, src/main/java/net/server/channel/handlers/PartyOperationHandler.java:98).

## Tests and QA

| Kind | Names | Covers |
|---|---|---|
| Unit | `ExpeditionBotsTest`, `ExpeditionBotsCaptainTest`, `ExpeditionBotsAccess`, `ExpeditionBotsFixture` (src/test/java/soloMapling/ArtificialPlayer/BotPartySystem/) | skip rules, captain door, quota |
| Unit | `BotRecruitCapacityTest`, `GrudgePartyAskTest`, `BotPartyChatTest`, `PartyChatRouteTest`, `BotLeaveStalePartyTest`, `BotCommandParserTest` | invite cap, refusal rules, chat routing |
| Unit | `LfgPartyTest` (src/test/java/soloMapling/ArtificialPlayer/BotConvo/) | fit/decide/cap/form |
| Unit | `OPQPartyDutyTest`, `OPQOrchestratorLeaderTest`, `OPQStageOneCompletionTest`, `OPQStageTwoCompletionTest`, `OPQRunCleanupTest`, `OPQLobbyChatTest`, `OPQStageBoxAnnouncementTest`, `OPQInstanceFlagsTest` | OPQ state machine and blackboard |
| Unit | `KpqLobbyTest`, `KpqLobbyDisbandTest`, `KpqLobbyYieldTest`, `KpqFullRunTest`, `KpqPassHandoverTest`, `KpqStrayPassTest`, `KpqStage1OrchestrationTest`, `KpqStagedChannelPickTest`, `KpqStagingEntryTest`, `KpqStagingPartyFollowTest`, `KpqStagingRoomTest`, `KpqStagingStageEventTest`, `ContentLobbyTest`, `StagingChatTest` (src/test/java/soloMapling/mechanics/) | lobby pick, staging, pass hand-over, chat dedupe |
| Auto-QA | `t-vb-3` (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:797) | KPQ clear rewards and warps |
| Auto-QA | `t-me-1` (:961) | a KPQ clear is remembered on the bot's card |
| Auto-QA | `t-in-3` (:1010) | a lobby bot invites you when your level fits |
| Auto-QA | `t-ex-1` (:1165), `t-ex-2` (:1174), `t-ex-3` (:1187) | party bots join Zakum; real Zakum with GM+bots; Chaos Zakum |
| Auto-QA | `t-ac-2` (:1355) | PQ clear medal through the real path |
| Auto-QA | `t-mx-1` (:1413) … `t-mx-6` (:1519) | four bots stage 1 / whole KPQ / stopped run / two channels / player-led alts / player takes the channel |
| `!qa` | `pq-1`, `pq-2` (src/main/java/soloMapling/qa/PqScenarios.java:141-176), `ex-1` (src/main/java/soloMapling/qa/ExpeditionScenarios.java:27), `mx-1..mx-4` (src/main/java/soloMapling/qa/MechanicsScenarios.java:18-360) | in-process scenarios |

**Not covered:** no test drives a real client's party invite end-to-end through `PartyOperationHandler`; no auto-QA
check for lpq/opq bots starting a run (the `ContentLobby` rows are switch-off on live); nothing exercises
`ExpeditionDoor.warnNotAtTheAltar` from a real start, only its own unit path; `BotPartyLogic` (the OPQ unconditional
accept) has no unit test.

## Known issues

| Id | One line |
|---|---|
| b225 | KPQ passes a bot hands to the leader never showed on the ground or in the leader's Etc tab; fixed by `205acddac` (drop as a player drop), status S3 waiting for proof. |
| b232 | A follower inside an instance did not leave with its leader; fixed by `625948ac9` (`GCFollow.leavesWithLeader` → `BotEventExit.leave`), S3 waiting for proof. |
| b240 | Regular bots said they were grinding while standing in town; planner path + word bank, S3. |
| b241 | KPQ bonus stage kept messaging the player until everyone left; fixed by `6c257d926` (`StagingChat` one line per stage), S4. |

status source: /root/work/STATUS-1.6.4.md:57-60.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyLogic.java:9-14 — `checkPartyQueue`
accepts any pending invite with no armed check and no follower-cap reservation, so an `OPQBot` can be pulled into a
stranger's party (and, if the bot is a converting follower type, past `FOLLOWER_CAP`) by any player who invites it; this
is the one path that bypasses invariant 3.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:176-186 — accepting on
inviter id alone means the `Armed` window is decorative for the accept decision; if a player invites the same bot twice
in one invite window after a declined first attempt, the second invite is accepted regardless of `INVITE_WINDOW_MS`.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:385 — `blockingSleep(3000)` runs on the
bot's own update thread inside `FOLLOW`; the comment says nothing may overlap it, but nothing stops `updateState` from
being called re-entrantly from another caller while the thread is parked, and the warp follows a wall-clock sleep rather
than the arrival event.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:213-226 — the cap is a `static
synchronized` deque with no pruning on `release`, so a `removeFirstOccurrence` of an older timestamp leaves the window
accounting only correct while `take` is called at least once per window; `release` alone never expires entries.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQOrchestrator.java:56-58 — the stage-2 box
index is keyed by map *instance* but comments say "cleared on reset"; `resetForNewRun` (:295) is the only clearer, so a
process that runs many OPQ instances accumulates one entry per dead instance until a reset is called.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:127 — `follows` converts a
`TrainingBot` to a `FollowerBot` (`trainer.order(Verb.FOLLOW, …)` at :535) as a side effect of the *eligibility check*.
The order of the guards is `skip` → `quotaFull` → `quota.hasEntry` → `busy` → `follows` (:119-129), so the quota/busy
refusals happen first; the residual case is `order()` not taking effect (`follows` returns false after mutating), which
leaves the bot demoted to a follower in the same pass that reports "busy".

## State

**Partial.** The Kerning path is live and proven by card-fixed bugs plus `t-mx-1..t-mx-6`: `KpqLobby` is on in the
release conf (docs/release/1.6.4.conf:271) and its staging/hand-over bugs (b225, b241) are fixed and only waiting for
proof. The general PQ-lobby path (`ContentLobby`, 12 rows) is written and unit-tested but switched off on live
(conf:90-91) and unreachable in practice. Expeditions auto-join is on and unit-tested; bot-captained runs exist but the
release conf states the GM command is their only caller. OPQ is a dev harness with no content switch that turns it on
for live. The gaps are the `BotPartyLogic` accept path (no armed check, no reservation) and the absence of any
end-to-end test through `PartyOperationHandler`.

## Open questions

- Does an `OPQBot` accepting an arbitrary invite (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyLogic.java:9-14) ever happen on live, or are OPQ bots only
  started by `!opq`/`EnvironmentManager` in TEST runs? Nothing in the tree gates it.
- `ContentLobby`'s rows need `-Dslums.mechanics.<row>=true` *and* `<row>.lobby=true`; is the family flag intended to be
  the real gate and `.lobby` the promotion, or is one of them redundant?
- `LfgParty` picks the lobby from the recruiter's level only; two bots whose levels fit different lobbies cannot
  thread together. Is that intended?
- `ExpeditionDoor.warnNotAtTheAltar` is gated on a switch that is off on live; was the "say who is left behind" line
  ever reachable in a real player's run?
