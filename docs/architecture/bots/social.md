# Social (bot-to-bot)

## Purpose

The layer that makes two bots, standing on the same map, do something to each other: a utility-scored registry of
16 social acts, driven by a per-bot "social mind" (12 traits, 9 decaying emotions, 8 growing needs), gated by a
shared budget, slowed by a level-of-detail tier, and extended by intentions that outlive a tick. Bot-to-bot only —
anything aimed at a real player goes through `SocialGate`/`SocialInitiative` instead, and `SocialActions.perform`
refuses a non-bot target outright (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:245).

Three separate things share the word "social" here:

| Layer | What it is | Owner |
|---|---|---|
| Social acts (ticket 271 stage 4-7) | bot→bot acts, budget, LOD, intents, pilot | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/ |
| Social gate / initiative (1.6.0 S5-S6) | bot→**player** unprompted acts, `@quiet`, "started it" ledger | src/main/java/soloMapling/ArtificialPlayer/SocialGate.java, SocialInitiative.java |
| Ambience | idling social bots, megaphone warm-up chatter in Henesys | src/main/java/soloMapling/ArtificialPlayer/SocialHotPotatoManager.java |

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `BotTalk.start()` | `EnvironmentManager.start` at boot; installs the intents observer, then the 10 s tick | src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:190-202 |
| `bot-talk-tick` thread | single daemon thread, `scheduleAtFixedRate(tick, 60, TICK_S = 10 s)` | src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:182-188 |
| `BotTalk.tick` → `socialActs(maps, now)` | every talk tick, after greetings/chats | src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:271 |
| `SocialDecider.upkeep` + `onMap` | the social phase of that tick, per map with ≥2 bots | src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:301-331 |
| `SocialIntents.onTick(chr)` | each bot's own update (promises that fell due) | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:668, src/main/java/soloMapling/ArtificialPlayer/BotTypes/SocialBot.java:218, src/main/java/soloMapling/ArtificialPlayer/BotTypes/TownWandererBot.java:159 |
| `SocialPilot.maybeStep` | population director tick | src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:494 |
| `SocialLod.maybeStep` | population director tick (tier census + slow maintenance) | src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:539 |
| `SocialLod.catchUpOnLogin` | `BotRoster` on login (bounded offline relief) | src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:250 |
| `SocialGate.startAtBoot` | `MapleRootSolo` boot (gate rows, `@quiet`) | src/main/java/soloMapling/server/MapleRootSolo.java:46 |
| `SocialInitiative.onPlayerLogin` | `PlayerLoggedinHandler`, new character only | src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:426 |
| `SocialHooks.onTradeDone` | `Trade.completeTrade` before items move | src/main/java/server/Trade.java:513 |
| `SocialHooks.onPqClear` | `EventInstanceManager.clearPQ` | src/main/java/scripting/event/EventInstanceManager.java:990 |
| `SocialHotPotatoManager.start` | `EnvironmentManager` boot; `!environment` start/stop | src/main/java/soloMapling/Environment/EnvironmentManager.java:145, src/main/java/client/command/commands/gm4/EnvironmentCommand.java:483-487 |
| `!botwhy <name>` | GM diagnostic: mind + intents (`SocialMetrics.whyLines`, `intentLines`) | src/main/java/client/command/commands/gm3/BotWhyCommand.java:33-35 |
| `!socialtrace start\|status\|stop` | stage-by-stage QA trace to `data/autoqa/social-trace/` | src/main/java/client/command/commands/gm3/SocialTraceCommand.java:17-25 |
| Dashboard `SocialExport.build` | modes, budget, acts, yield, intents, pilot blocks | src/main/java/soloMapling/dashboard/SocialExport.java:46-58 |

## How it works

Per social tick, in the order the code runs:

1. **Switch and map scan.** `BotTalk.socialActs` returns immediately unless `SocialActions.enabled()`
   (src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:302). Maps are already bucketed by
   `channel<<32|mapId` in `BotTalk.tick`; a map with <2 bots is skipped (src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:310-312).
2. **Upkeep.** `SocialDecider.upkeep(now)` → `SocialActions.tick(now)`: lapsed invitations are released to
   `SocialFlows` and cost the inviter sadness 0.03; avoid records re-issue a walk at most every 20 s while the
   target is inside `keepPx` and expire; spend-time pairs are judged at their deadline
   (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:577-611).
3. **Due check, then map admission.** Nothing due on the map → no map object read, no allocation
   (src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:314-320). Boss/event maps and mini-dungeons are excluded via `BotThreads.excluded`
   (src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:317-319).
4. **Who is in range (`SocialLod`).** `SocialLod.tierOf` returns `ACTIVE` while the map is observed or for
   `ACTIVE_HOLD_MS = 30 s` after the last sighting, else `BACKGROUND`, and `DORMANT` when offline
   (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialLod.java:108-124). The tier reaches the
   decider as `GameSocialWorld.paceFactor` → 1.0 / 4.0 / `POSITIVE_INFINITY`
   (src/main/java/soloMapling/ArtificialPlayer/BotConvo/GameSocialWorld.java:175-179). `maybeStep` refreshes
   every online bot and gives `BACKGROUND` bots their maintenance only once per
   `MAINTENANCE_PERIOD_MS = 300 s` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialLod.java:157-183).
5. **Pace.** `SocialDecider.onMap` runs two passes over the map's bots: pass 0 = bots holding an open invitation
   (they answer first), pass 1 = the rest; at most `PER_MAP = 2` bots decide per map per tick, and `nextAt` is set
   *before* deciding so a slow act cannot re-enter (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:45-72).
   `pace` = `45 s + 135 s × draw × (1.3 − 0.8 × eager)` where `eager = 0.5×SOCIABILITY + 0.5×COMPANIONSHIP`,
   then `× max(1, paceFactor)`, `Infinity → Long.MAX_VALUE/4` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:165-180).
6. **Candidates.** `SocialDecider.candidates`: `world.nearby(id, SIGHT_X = 900, SIGHT_Y = 300)`, nearest first,
   group mates hoisted, at most `CANDIDATES = 6`; a stranger (`Stage.STRANGER` and familiarity < 0.04) is not a
   candidate, but a bot holding or owing an invitation always is (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:31-33, :124-158).
7. **Busy / no mind checks.** `decideOne` counts `skipped-no-mind` when memory is not loaded and `skipped-busy`
   when `SocialWorld.free` is false (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:75-78).
8. **Intent booking (`IntentBook`).** The decider itself does not book; a due intent is booked earlier, at the
   moment a promise is made, and is stored in the bot's `LifeMemory` under the JSON key `"intents"`
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:770). `IntentBook` keeps at most
   `MAX_LIVE = 3` live and `MAX_RECENT = 5` recent, keys them `(long)actor << 32 | seq`, and ends them
   `KEPT/BROKEN/EXPIRED/CANCELLED` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/IntentBook.java:24-26, :116-134).
   Sources of a booking: a bot-to-bot "yes" in a talk (`PromiseIntents.onYes`, src/main/java/soloMapling/ArtificialPlayer/BotConvo/TalkPromises.java:110), a player's
   accepted `come`/`help` (`PromiseIntents.onPlayerYes`, src/main/java/soloMapling/ArtificialPlayer/BotConvo/ConvoActions.java:239-253).
9. **Decide.** `SocialActions.best(id, candidates, now)` builds one `SocialContext` per candidate
   (`SocialActions.build`: own mind, **own** edge only, own memories, up to 3 known bystanders standing with the
   target, free/idle flags, avoid record, pending invite — src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:391-428), then `bestOf` takes the
   highest `utility + SocialGroups.adjust + noise` across (act × target) that clears
   `bar = 0.30 + 0.15 × (1 − SOCIABILITY)` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:311-359). `rank` pre-filters acts whose
   preconditions fail, whose free/idle requirement fails, or whose pair gap has not elapsed — the budget is not
   asked twice for a choice it would refuse (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:362-382).
10. **Act.** `SocialActions.perform` re-checks in this order: switch → actor is a roster bot (id ≥
    `BotStore.BOT_ID_BASE`) → target is not a player → not itself → both on a map → act preconditions →
    `free` (ACCEPT/DECLINE exempt from the target's side) → `needsIdle` → `SocialBudget.allow` →
    `execute` → `SocialBudget.record` unless the wrapped flow records itself → `SocialPilot.onAct` →
    the `observer` (QA trace). Any throwable becomes a refused outcome (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:235-295).
11. **Effect.** Each act writes its own state: bond events via `BotBonds.record`, `SocialMind.feel/satisfy` on
    both sides, and a `BotMemory` note in fixed wordings so repeats inside 20 h collapse
    (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:89-118). `decideOne` counts
    `acted` / `act-refused` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:93-96).

### Acts, cost and effect

`SocialActions.budgetKey` maps an act to a budget family (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:211-226); the family's per-pair gap
comes from `SocialBudget.Limits.ACT_GAPS` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:75-81),
`family()` strips the `:HELP` suffix (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:325), and an unknown family falls back to
`defaultCooldownMs = 30 s` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:108-109).

| Act | What it does | Budget family / gap | Wrapped? | File:line |
|---|---|---|---|---|
| GREET | bot-talk greeting plan; +companionship | `greet` / 10 min | wrapped, records itself | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:273 |
| CONVERSE | bot-talk chat plan, may end in an ask; needs idle | `chat` / 1 min | wrapped, records itself | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:324 |
| COMPLIMENT | bank line + emote 2; target PRIDE +0.15, RECOGNITION +0.25, `BondEvent.COMPLIMENT` on the target's edge | `compliment` / 45 min | built | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:388-444 |
| ASK_HELP | bot-talk forced HELP ask; the other's yes/no is TalkBrain's | `ask:HELP` → `ask` / 15 s | wrapped, records itself | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:449 |
| OFFER_HELP | the help trip: party forms, actor keeps grinding there; target BUFF bond | `offerhelp` / 15 min | wrapped | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:498 |
| GIVE_GIFT | `GiftSeam.give` → `BotBotGift.choose/give` (equip that fits, potions, or a gesture; nothing minted) | `gift` / 6 h | built over the seam | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:549, src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/BotGiftSeam.java:24-35 |
| INVITE | posts an invitation (bubble); the other answers on its own tick, TTL 3 min | `invite` / 10 min | wrapped, records itself | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:606, src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:52 |
| ACCEPT | carries the invitation out (duo/PQ party, trips) | `reply` / 5 s | wrapped | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:657 |
| DECLINE | refuses politely; inviter sadness + embarrassment | `reply` / 5 s | wrapped | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:692 |
| APOLOGIZE | sorry line; target accepts by agreeableness/resentment/pride; `APOLOGY` bond both edges when taken | `apologize` / 20 min | built | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:726 |
| ARGUE | game-only roast from the bank; `ARGUE` bond both edges, 3/day/pair | `argue` / 30 min | built | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:809 |
| RECONCILE | truce line; `RECONCILE` bond both edges, clears avoid records | `reconcile` / 30 min | built | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:886 |
| SHARE_INFO | `BotGossip`/`BotWorldGossip` pass; listener writes `REPORTED` memory | `shareinfo` / 5 min | wrapped | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:964 |
| SPREAD_RUMOR | same pass, negative story to a trusting bot; honest bots do not | `rumor` / 20 min | wrapped | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:1001 |
| AVOID | walks away at once; an avoid record holds 450 px for 5-13 min, re-walked ≤ every 20 s; needs idle | `avoid` / 45 min | built | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:1045, src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:54 |
| SPEND_TIME | stands beside the target 6-12 min, judged at the deadline; needs idle | `spendtime` / 20 min | built | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActs.java:1110 |

### Budget (`SocialBudget`)

`allow` counts a verdict and changes nothing else; `record` consumes windows, pair gap and turn chain
(src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:182-259). `judge` order and reasons: `BACKOFF` → `TURN_LIMIT` → `PAIR_COOLDOWN` →
`ACTOR_RATE` → `MAP_RATE` → `GLOBAL_RATE` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:192-220). Defaults: 10 acts/actor/min,
40/map/min, 200 world/min, `maxTurns = 6` per `turnWindowMs = 90 s` (a turn only advances when the *other* bot
answers, src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:261-270), 3 failures → backoff `30 s << (n−3)` capped at 60 min, streak reset after
6 h (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:273-289). Sweep every minute (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:310-322).

### Intents (`SocialIntents`, `IntentBook`)

`onTick(chr)` takes the bot's due intents (at most `MAX_LIVE = 3`) and for each: `expireOne` ends it `EXPIRED`
past its deadline; `attempt` defers on `retry gap` (45 s), `actor offline`, `target offline`, `not reachable`,
`busy`, `budget <reason>`, or cancels on `opted out or gated`, then performs
`intent:<KIND>` through `SocialActions` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:179 wires the seam;
src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialIntents.java:368-500). A `KEPT` intent raises
trust/promises via `BotBonds.promise`; a `BROKEN` one records a `BROKEN` episode (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialIntents.java:303-330).
`dueSoon(now, DUE_SOON_MS = 2 min)` makes the actor count as committed to the brain (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialIntents.java:62, :194).

### Pilot, routine, groups (stage 7)

`SocialPilot` selects a sticky set of 20 roster bots by mixed hash rank, holds them through the director's
session trim, boosts their login, re-homes their town stays to Henesys and reserves slots
(src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialPilot.java:30-55). `SocialRoutine` gives each
pilot bot a chronotype and a per-hour day shape (work/gather/evening/rest)
(src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialRoutine.java:128-160). `SocialGroups` finds
connected components of mutual FRIEND/CLOSE_FRIEND edges among pilots, `MIN_SIZE = 3`, recomputed every 3 min, and
adds `MATE_BONUS = 0.10` to SPEND_TIME/INVITE toward mates plus up to `MAX_CONFLICT_BONUS = 0.12` to
ARGUE/AVOID across a tense group pair (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialGroups.java:44-59, :335-346).

## Data

| Store | What | Where |
|---|---|---|
| `botsocialopt` | per-player `QuietMegaphones, QuietBots, RomanceOff` | src/main/java/soloMapling/ArtificialPlayer/SocialGateStore.java:47, :51-54 |
| `botstartedit` | per (player, bot) `Spice`(beef)`, SpiceAt, Flirt, FlirtAt, LastKind`; column names predate the Beef rename | src/main/java/soloMapling/ArtificialPlayer/SocialGateStore.java:48-58 |
| `LifeMemory` JSON `"mind"` | traits/emotions/needs, written back untouched when not asked about | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:231, :768 |
| `LifeMemory` JSON `"intents"` | live + recent intents | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:232, :770-771 |
| `SocialLedger` (memory) | beef/flirt weights, halve each `SocialGate.halfLifeMs()`, `ACTIVE = 0.5`, `MAX_WEIGHT = 4.0`, ≤64 bots/player | src/main/java/soloMapling/ArtificialPlayer/SocialLedger.java:22-24 |
| `SocialBudget` (memory) | pair gaps, actor/map/world 1-min windows, turn chains, failure streaks | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:154-167 |
| `SocialActions` (memory) | `invites`, `avoids`, `avoidWalkAt`, `togethers`, `lastAct`, `argueLog`; `resetForTest` clears all | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:200-208 |
| `SocialLod` (memory) | per-bot `lastActiveAt`, tier, maintenance clock | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialLod.java:157-183 |
| `SocialYield` | per-kind count/mesos/receivers handed to **real** players + player-hours denominator; bot receivers ignored | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialYield.java:22-56 |
| `SocialMetrics` | decision and act counters (`ConcurrentHashMap<String, LongAdder>`) | src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:38-72 |

Bots are `Character`s with ids ≥ `BotStore.BOT_ID_BASE` (1,000,000,000); `getClient().getPlayer()` is null and a
packet sent to a bot goes nowhere, so every effect here is a direct `Character`/`BotMemory`/`BotBonds` call.

## Switches

All are `-D` system properties, all default **ON** (any value other than the literal `false` leaves them on).

| Flag | Default | Effect | Live? |
|---|---|---|---|
| `slums.socialacts` | on | the act registry, decision tick, invites/avoids (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:49,57`) | on (not in 1.6.4.conf) |
| `slums.socialbudget` | on | per-pair gaps, turn limit, rate caps (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:38,127`) | on |
| `slums.socialbudget.<limit>` | see ACT_GAPS | `greetCooldownS, chatCooldownS, askCooldownS, actorPerMin, mapPerMin, globalPerMin, maxTurns, turnWindowS, failsBeforeBackoff, backoffBaseS, backoffMaxS` (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:89-101`) | unset |
| `slums.intents` | on | promises that outlive a tick (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialIntents.java:59,77`) | on |
| `slums.botgifts` | on | bot→bot gifts (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/BotBotGift.java:208-209`) | on |
| `slums.botgifts.{giver.cooldownMin,pair.cooldownMin,receiver.perDay,perHour,valueCap}` | 360 / 1440 / 3 / 15 / 10000 | gift limits (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/BotBotGift.java:212-219`) | unset |
| `slums.sociallod` | on | tiering + catch-up (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialLod.java:95-97`) | on |
| `slums.socialpilot` | on | pilot membership/hold/bias/counters (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialPilot.java:59,77`) | on |
| `slums.socialpilot.size` / `.map` | 20 / 100000000 | pilot size and home map (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialPilot.java:60-63`) | unset |
| `slums.socialroutine` / `.zone` | on / server zone | daily shape, preferred map (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialRoutine.java:37,68`) | unset |
| `slums.socialgroups` | on | groups and tension (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialGroups.java:44,58`) | on |
| `slums.modeswitch` | on | mode switch; also needs `LifeRuntime.reachesAllBots()` (`src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:86-88`) | on |
| `solomapling.socialGate` | on | player-facing gate (`src/main/java/soloMapling/ArtificialPlayer/SocialGate.java:174`) | on |
| `solomapling.initiative` | on | login greet / PQ invite / follow-up (`src/main/java/soloMapling/ArtificialPlayer/SocialInitiative.java:203`) | on |
| `solomapling.memory.scamShare` / `.scamMin` | 0.25 / 100000 | scam read thresholds (`src/main/java/soloMapling/ArtificialPlayer/SocialHooks.java:160-175`) | unset |

`docs/release/1.6.4.conf` UNIT_EDITS and SERVER_SET (:201-214, :271) contain **no** social key, so every switch
above runs at its default ON on live; the OFF lines are listed in
docs/release/1.6.4-social-conf-notes.md:32-45.

## Invariants

- **A real player is never a social-act target.** `perform` refuses `targetCharId < BotStore.BOT_ID_BASE` before
  building a context (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:245); `candidates` skips `o.player()` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:145). The
  player-facing path is `SocialGate` only.
- **A bot acts only on what it knows.** A stranger with familiarity < 0.04 is never a candidate
  (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:157-161), and `build` reads only the actor's own edge (`BotBonds.axes(actor, target)`),
  never the target's edge back (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:401-402).
- **`nextAt` is written before the act runs**, so one slow act cannot make a bot decide twice in a tick
  (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:62).
- **`SocialBudget.allow` is read-only.** Only `record` consumes a window, and only for an act that really
  happened; a wrapped flow that already recorded is not recorded twice (`recordsItself`,
  src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:276-284, src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialAction.java:48-52).
- **A turn advances only when the other bot answers.** The same bot speaking again does not raise the count
  (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialBudget.java:265-269).
- **Intent ids are `(long)actor << 32 | seq`**, so they are unique across bots without a shared counter
  (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/IntentBook.java:116).
- **A missing bank is never silence.** `SocialLines.pick` falls back to the node's built-in line when
  `DramaDialogue.yaml` cannot be read, and `ContentGuard` filters every line
  (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialLines.java:29-51).
- **The model never writes these lines** — the act is decided by the server, the bank only voices it
  (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialLines.java:12-17).
- **Nothing throws out of the tick.** `socialActs` wraps the whole phase, `perform` and `best` each catch
  `RuntimeException | LinkageError` (src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:327-329, src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:296-299).
- **A bot whose memory is not loaded feels nothing** and reads as an even-tempered mind
  (`Self.flat(0.5, 0.3)`, src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:440-443).

## Origin

Custom. The whole `soloMapling/` tree is a fork side tree, and every social file lands in the 1.6.x ticket series:

| File | Oldest commit | Subject |
|---|---|---|
| SocialHotPotatoManager.java | `03d13cb21` 2026-10-18 | Establish deployable MapleRoot server baseline |
| SocialGate.java | `1fa8db0f0` 2026-10-25 | Add the social gate: started-it ledger, crew seam, quiet that persists |
| SocialGateStore.java | `1fa8db0f0` 2026-10-25 | (then `48ae36516` 2026-10-25, "spice" → "Beef" rename) |
| SocialInitiative.java | `2e8bd73a1` 2026-10-25 | Let friend bots greet you at login, invite you to PQs and follow up a day later |
| SocialHooks.java | `84fffe0fb` 2026-10-25 | S3 memory kinds: episodes, slots, scam reads and migration 030 |
| SocialActions.java | `66c23e7ea` 2026-11-03 | Add the social act registry, 16 acts and the decision tick (stage 4, wip) |
| SocialMind.java | `43c1128c4` 2026-11-03 | Bots get a social mind: traits, decaying emotions and growing needs |

`SocialCrew`/`SocialLedger` are part of the same S5 gate commit (`1fa8db0f0`). Board card L11 records the lane as
"Social intelligence for bots (ticket 271)" merged from `164-social-int-renum` (`a226edfdf`).

## Tests and QA

Unit tests (`src/test/java/soloMapling/ArtificialPlayer/BotSocialSystem/`, 21 files, ~200 `@Test`):

| Class | Tests | Class | Tests |
|---|---|---|---|
| SocialActsTest | 32 | SocialIntentsTest | 24 |
| BotBotGiftTest | 25 | SocialRoutineTest | 17 |
| SocialBudgetTest | 16 | SocialGroupsTest | 16 |
| SocialPilotTest | 13 | SocialLodTest | 12 |
| SocialMindTest | 10 | SocialUtilityTest | 10 |
| SocialDeciderTest | 9 | SocialMetricsTest | 7 |
| SocialIntentsConsequencesTest | 4 | SocialSimTest | 4 |
| SocialPilotSimulationTest | 3 | SocialFlagsDefaultOnTest | 2 |
| SocialSimulationTest | 1 | | |

Helpers: `SocialTestKit`, `SocialActTestBase`, `SocialFlagsOff`, `SocialPilotSim`. Outside the package:
`SocialGateTest` 19, `SocialInitiativeTest` 19, `SocialHooksTest` 5, `SocialPilotDirectorTest` 4,
`SocialGateBeefTest` 3, `SocialGateMigrationTest` 1, plus `BotTalkSocialActsTest`,
`BotCommitmentsIntentsTest`, `BotRosterSocialLodTest`, `BotMemorySocialTest`, `SocialGateAffinityTest`.
`SocialSimulationTest` runs 12 bots over 3 simulated days with no real player.

Auto-QA checks (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`): `t-sg-1` (:913), `t-sg-2` (:923),
`t-in-1` (:991), `t-in-2` (:1001), `t-in-3` (:1010), `t-st-1` (:1158), `t-ms-1` (:1571), `t-sm-1` (:1576),
`t-sa-1` (:1583), `t-pi-1` (:1589). Probe `Probes164Social` supplies the counters (socialActs :352, socialPilot
:395, :438). `!qa` scenarios: `SocialGateScenarios` (sg-1 :49, sg-2 :127), `InitiativeScenarios` (in-1 :42,
in-2 :83, in-3 :158), `SocialTraceScenarios` (st-1).

Not covered: no unit test drives `BotTalk.tick` end-to-end with a real map (only `BotTalkSocialActsTest` at the
phase level); `SocialHotPotatoManager` has no test in this package; nothing asserts the act table's documented
"legal when" column against `preconditions` beyond the per-act tests; `SocialGate`'s megaphone channel and
`SocialInitiative`'s buddy-after-play path have only scenario coverage.

## Known issues

- b240 — regular bots say they're grinding while standing in town (planner path `23fe8f08f` + scripted town-greet
  `3aba87f15`); not owned by this subsystem but visible through `SocialMetrics.mode`.
- b225 — KPQ passes handed to the leader never show on the ground (bot hand-over drops as a player drop); touches
  the INVITE/party flow indirectly.
- b232 — a follower inside an instance does not leave with its leader.
- b241 — KPQ bonus stage keeps messaging until everyone leaves.
- b276 — a bot's map-scoped PLAN/ROUTE lines never reach the GM's QA feed (triage: QA-tool defect).
- b284 — auto-QA at 10 lanes produces false FAILs (scheduler key declarations miss shared state) — affects
  `t-sa-1`/`t-sm-1` reliability.
- b285, b287 — alt upgrade handover and a PANIC-style scroll trader; adjacent bot economy, not social-owned.
- NEW-FINDING: docs/release/1.6.4-social-conf-notes.md:59-63 claims `t-pi-1` is "deliberately not in the
  catalogue yet", but src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1589 registers it — the release note is
  stale and a reader would wrongly believe the pilot's check cannot FAIL a release run.
- Not a finding (checked): src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:257 mixes
  `&&` and `||` without parentheses — `!ctx.actorFree() || !ctx.targetFree() && kind != ACCEPT && kind != DECLINE`
  parses as `!actorFree || (!targetFree && not reply)`, i.e. the *actor* must always be free and only the *target*
  is exempt for ACCEPT/DECLINE. That is exactly the rule the flow above states (`free` step, line 10), so the
  precedence is intentional; it is worth parenthesis only for readability. Same expression at `:370`.
- NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:165-180 multiplies the
  pace by `max(1, detail)` and special-cases only `Infinity`; a `DORMANT` bot whose `tierOf` returns `ACTIVE`
  because `SocialLod.enabled()` is false is fine, but a `BACKGROUND` bot on a map with two bots still consumes one
  of the `PER_MAP = 2` decision slots (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:45-60) before the tier is consulted — the LOD slows a
  bot's own cadence but not the map's decision budget.
- NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialLod.java:157-176 marks every bot
  absent from the `online` snapshot as `DORMANT` by writing `e.getValue().tier` directly without bumping
  `tierChanges`, so `stats()` under-reports background→dormant transitions.
- NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:28 keeps
  `REST = 0.02` and `BOND_ROWS = 6` beside a `Pattern BOND_TOTALS` that parses `BotBonds.describe` output text
  (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:31, :250) — the dashboard's bond totals are coupled to a human-readable string, so a wording
  change in `BotBonds.describe` silently zeroes the metric.

## State

**working, with two residual gaps.** Evidence: 200+ unit tests pass in the package (SocialActsTest 32,
SocialIntentsTest 24, BotBotGiftTest 25), `SocialSimulationTest` runs 12 bots × 3 days, ten auto-QA checks are
registered (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:913-1589) and all switches default ON with no unit edit needed
(docs/release/1.6.4.conf:201-214). Residual gaps: a `BACKGROUND`-tier bot still consumes a per-map decision slot
(src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:45-60), and the release note for `t-pi-1` is stale
(docs/release/1.6.4-social-conf-notes.md:59-63 vs src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1589). The `&&`/`||` precedence at
src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:257 was checked and is intentional (actor free, target exempt for replies).

## Open questions

- Does `SocialBudget` need a per-*map* spacing for AVOID? Avoid records re-walk every 20 s per pair
  (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:594-601) with no map-level cap, so a crowded town with many grudges issues many walks.
- Is `PER_MAP = 2` (src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:32) still right at 20 pilot bots on one Henesys map, or should the pilot
  raise it? No measurement exists either way.
- The yield-per-bot-hour runbook measurement has not been run (docs/release/1.6.4-social-conf-notes.md:47-49), so
  whether the light-tab share needs capping is unknown and no switch exists to cap it.
- Should `t-pi-1` be on the release allow-list, given it is now catalogued but the notes still say it is not?
