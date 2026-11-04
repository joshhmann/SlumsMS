# Social intelligence: architecture audit, integration design and plan (1.6.4)

Ticket 181. Read-only audit of `release-1.6.4` at `6d18f961e` on 2026-11-03 (six scouts, one per slice; this file is the
consolidated result), then the staged plan. Path shorthand: **AP** = `src/main/java/soloMapling/ArtificialPlayer`,
**BL** = `AP/BotLifeSystem`. Anchors are file:line at that commit and move; grep the symbol.

Owner brief (2026-11-03): extend, do not replace, the LLM dialogue and decision systems; persistent personality,
emotion, needs, directed multi-dimensional relationships, structured memory, a social action set, routines and groups,
LOD simulation, a 20-bot Henesys pilot. Owner decisions since: gifts are non-economic (affinity effects, gear that
fits the receiver or fits every job, usables); the brain, bot talk and crews are on by default; any roster bot can
switch between training, social and town modes.

## 1. Headline

The project already has most pieces under other names (about 220k lines of bot code). What is missing is the layer
that joins them: continuous traits, stored decaying emotions, multi-axis relationships, structured memory, one social
action registry, and a social LOD. Build that layer inside the existing systems; a parallel framework would duplicate
`BotLifeSystem`, `BotBonds`, `BotMemory`, `BotTalk`, `Reputation` and `SocialGate`.

## 2. Architecture map (the ten audit questions)

| # | Question | Finding |
|---|---|---|
| 1 | Representation | A bot is a real `Character` (id >= `BotStore.BOT_ID_BASE` 1,000,000,000) with a `BotClient`; behaviour is a `BotSM` subclass from `BotTypeManager.BotType`. Script NPCs are not agents. |
| 2 | Decision model | `LifeDecision.evaluate` (BL): pure utility scorer, 6 needs x 16 activities. `TalkBrain`/`TalkPlanner` (AP/BotConvo): additive score plus one draw for bot-bot talk. `ChatDecide` (GLiNER) only classifies chat lines. |
| 3 | LLM dialogue | `BotConversation.systemPrompt` builds the prompt from `BotChatPrompt`, `RelationshipCard`, `BotMemory.recall`; history in memory per pair; output through `ConvoContract`, content guards, `StateOfMind.check`. |
| 4 | Identity today | Bartle-based `SocialProfile` + `LifeProfile` (7 channels) + canned `BotPersona`; one derived mood scalar; 6 derived needs, none stored. No real emotions. |
| 5 | Decision to action | `LifeExecutor`, `ConvoActions`, `BotTalk.act` call the existing party, trade, movement, buddy systems. Server-authoritative, LLM-voices-only already holds (`ConvoGates`, `ReplyPlanner`). |
| 6 | Persistence | `botbond` (3-minute batch), `botmemory` (write-through, LRU 16,384 pairs), `botaffinity`, `botlifeprofile` (JSON, versioned), `botcouple`. Highest migration 058. Emotions and needs persisted nowhere. |
| 7 | World APIs | `GCMovement.move/follow/teleportTo`, `BotSpeak/BotChatbubble/BotEmote`, `botSitChair`, `LfgParty`, `BotBotTrade`. No single "walk to bot X, face, talk" helper. A bot-to-bot gift now exists (`BotBotGift`, Stage 4); `BotGifts` still
gives to players only. |
| 8 | Bot-bot without a player | Yes: `BotTalk` (needs 2+ bots on a map), `BotGossip`, couples, bond events. `BotThreads` and `BotChatter` need a watching player. |
| 9 | Offscreen | Bots stay online; unobserved, the macro tick drops to 60-120 s and movement goes coarse (`ObserverTracker`). The social layer now has its own LOD (Stage 6b, `BotSocialSystem/SocialLod`): ACTIVE / BACKGROUND / DORMANT, 5-minute background maintenance, one bounded catch-up per login. |
| 10 | Scheduling | `BotTickService` (one driver, virtual threads, governor stretches all periods up to 4x), bot DB access capped at 6 permits (`BotDbGate`; virtual threads drop writes rather than wait). |

## 3. Gap by phase

| Phase | Status | Evidence |
|---|---|---|
| 1 Traits | PARTIAL | Sociability exists (derived); empathy, curiosity, ambition, loyalty, impulsiveness are proxies; confidence, agreeableness, patience, jealousy, honesty, risk tolerance missing. |
| 1 Emotions | MISSING | Only a derived mood scalar, `RudeStreak`, `wary`. Sadness, fear, anxiety, embarrassment absent; nothing stored or decaying. |
| 1 Needs | PARTIAL | Achievement=`PROGRESS`, rest=`FATIGUE`; companionship and belonging are one `SOCIAL`; autonomy missing. |
| 2 Relationships | PARTIAL | Directed `Bond` (bot-bot) and `Tie` (bot-player) exist; one scalar -100..100 plus `kind`/`hostile` counters, 4 derived states. No 8 axes, no player-to-bot edge, no hysteresis. |
| 3 Memory | PARTIAL | `botmemory`: DIRECT/HEARSAY, kind, weight, half-life decay, 10-note cap, LLM merge. Subject is a real player only; no location, confidence, relevance recall. |
| 4 Reasoning | PARTIAL | `TalkBrain` uses traits, bond, mood, goal; no emotion or memory input. |
| 5 Actions | PARTIAL | Bespoke flows (greet, chat, party, trade, help, gossip, spat, crush, couple, wedding, guild beef). No registry. Missing: compliment, apologize/reconcile, avoid, spend-time-together (bot-to-bot gifts exist as `BotBotGift`, not yet
reachable from a decision). |
| 6 LLM | PARTIAL | Memory reaches player chats only; `BotThreads`/`BotTalk` do not read `BotMemory`. `BotCommitments` is only a tick keep-awake; promises are judged after the fact by `SayDo`/`TalkPromises`, never scheduled. |
| 7 Routines, groups | PARTIAL | Crews, guilds, couples, `Reputation`, `Fads`. Schedule = login window and session length only. |
| 8 LOD, guards, observability | PARTIAL | Movement LOD, `BotTalk`/`SocialGate` budgets, `SocialTrace`; social LOD built in Stage 6b (`SocialLod`, tiers, counters in `stats()` and a 10-minute info line). No generic bot-bot loop breaker, no social metrics in the dashboard yet. |
| 9 Henesys pilot | MISSING | Henesys targets about 63 bots over 4 maps plus `HenesysBot` waves; no 20-agent social pilot. |

Defects found that shape the design: bonds form only on maps a real player observes (so unwatched bots never relate);
the 14-bond cap evicts the weakest bond (erodes estrangement); `botbond` flushes every 3 minutes (crash window);
`Reputation`, crushes, gossip stories, `TalkPromises`, `SocialInitiative` state are RAM-only; `BotWorldGossip` is global
(a bot can voice news it never witnessed); two rule books (`AffinityRules`, `BondRules`) can drift; bot-bot LLM lines
need every one of 2 local slots free; `BotTalk` recorded 25 ms per tick against a 20 ms limit (`docs/roadmap/1.6.1.md`);
about 426 uses of `ThreadLocalRandom`/`new Random` and no global clock seam. The 1.6.0 audit's finding that town and
social bots lose their `LifeMemory` is stale: `LifeMemoryStore` saves it for every bot type.

## 4. Integration design (extend, never replace)

1. **Identity.** Add a `SocialMind` (12 traits, 9 emotions, 8 needs as small floats with a timestamp, decayed on read) to
   `LifeMemory`, saved in `botlifeprofile` (JSON, versioned, about 120 B per bot), exposed through `SocialSelf`.
2. **Relationships.** An 8-float vector inside `Bond` and `Tie`; `score` stays as the derived summary, so `state()`,
   crews, `Reputation`, `GuildRules`, couples are untouched. New columns by migration 059 (+ rollback). Events plug into
   `BondEvent` / `BondRules.apply`. Bonds form in the background tier too, not only on observed maps.
3. **Memory.** Extend `botmemory` (subject type so a bot can be the subject, location, confidence, emotional weight);
   relevance scoring in `BotMemory.pick`/`RECALL_ORDER`; `BotThreads` and `BotTalk` read it; world news gets a transmission
   path (witnessed or told) instead of global.
4. **Actions.** A small `SocialAction` interface (preconditions, target selection, utility, execute, outcome) over the
   existing `TalkPlanner` acts and `LifeDecision.Activity`; existing flows become registered actions first, missing ones
   after. Player-facing ones keep `SocialGate`.
5. **Intentions.** A persisted intent record written at `ConvoActions.carryOut` / `TalkPromises.promised`, consumed through
   `BotCommitments.inFlight` and a `SocialInitiative`-style scheduler.
6. **LOD.** `ObserverTracker` (through `GCMovement.isMapObserved`) is the active/background signal; `SocialLod` turns
   it into three tiers with a 30 s hold, gives background bots their need maintenance at most once per 5 minutes, and
   replays a bounded catch-up at login for bots that were away. New work is costed in the `social-tick` phase. Bonds are
   still not caught up this way (Stage 2 work).
7. **Test seam.** `SocialSim` (one clock, per-(bot, purpose) seeded RNG) for all new code.
8. **Gifts (owner, non-economic).** A gift is an affinity effect, or gear that fits the receiver's job and level, or gear
   that fits every job (earrings and similar), or a usable; chosen by the receiver's `SocialMind` needs, with a per-pair
   cooldown. The item comes from the giver's own holdings, so nothing is minted.
   Built as `AP/BotSocialSystem/BotBotGift`: `choose(giver, receiver, minds, now)` returns a plan (equip, potions or a
   gesture with no item) and `give(plan)` performs it. The equip side reuses `GearUpgrades.spares` and the optimizer's
   own `Scorer` for "the receiver would wear it, the giver would not"; potions come from `BotVitals.potionStacks` with
   `SupplyNeeds.LOW_POTIONS` and a giver that keeps a trip's worth; a gift records `BondEvent.GIFT` for the receiver
   only (the `BotBonds.onTrade` precedent) and pushes `SocialProfile.Push.GIFTED` plus a memory note. The transfer is
   `BotHoldings`' primitives under its own locks, not `BotBotTrade.swap` (that one prices in mesos). Limits are one
   `Limits` record from `-D` properties (giver 6 h, pair 24 h, 3 a day per receiver, 15 an hour world-wide, 100k mesos
   at NPC prices), flag `-Dslums.botgifts` on by default. Not yet called by the brain or the talk planner.

## 5. Modes: training, social, town (Stage 0b, done)

Before: with the brain on, `BotRoster.resumedType` forced every roster login to `TrainingBot`; social and town bots
existed only in staged scenes; the brain could not change a bot's type. After (`BL/ModeSwitch`): each roster bot asks
`ModeSwitch.maybeSwitch` from its own tick at most once a minute. `decide` (pure) reads needs (SOCIAL, PROGRESS, FATIGUE,
BOREDOM), the profile blend and the social pull, with a per-bot minimum stay (20 min x 0.75..1.5), at most 3 switches an
hour and a per-evaluation chance under 50%. The swap is `BotTypeManager.convertBotType` (stop old, wrap the same
`Character`, start new), run as the last act of the tick, only at a safe point: the brain's own `commitBlocker`, the
log-out gate `BotPopulationDirector.mayLogOut`, no party, no open chat, no trade, a town map, no errand, still the
registered instance. A switched mode survives a restart (`BotRoster.resumeChoice`). `-Dslums.modeswitch=false` is the
rollback. What a swap drops (grind spot, the old runtime's pending friend or plan) is why it waits for a safe point.

## 6. Social level of detail (Stage 6b)

Before: every online bot paid the social layer's full cadence whether or not a real player could see it, and a bot that
came back after hours away came back with exactly the needs it left with, as if the time had not passed. After
(`BotSocialSystem/SocialLod`, one accessor `SocialLod.tierOf(BotSM)`):

- **Tiers.** The tier is a pure function of (observed, online, lastActiveAt, now): offline is DORMANT, an observed map
  instance (`GCMovement.isMapObserved`) is ACTIVE, and the bot stays ACTIVE for 30 s after the last observer left, so a
  player walking past does not make bots flicker in and out of detail. `-Dslums.sociallod=false` makes every bot ACTIVE
  and is the rollback.
- **ACTIVE** keeps every current behaviour; the tier only says "this bot may be watched, keep it detailed".
- **BACKGROUND** (online, nobody watching): the population director's tick calls `SocialLod.maybeStep` once, which
  refreshes every online bot's tier and gives each BACKGROUND bot its need maintenance at most once per 5 minutes - its
  companionship and belonging needs fall by a small fixed rate per hour covered (0.03/h and 0.012/h), a coarse stand-in
  for the contact it would have had. A bot with no `LifeMemory` in use this run is skipped rather than given a row just
  to nudge it. Never per bot tick, never a new poller.
- **DORMANT** (not online): nothing runs and nothing is stored per tick. `BotRoster.recordLogin` calls
  `SocialLod.catchUpOnLogin` once per login with the bot's last logout time; the relief covers the time away, capped at
  24 h, skipped below one minute and when the logout time is unknown, idempotent for the same login, and the mind clamps
  the needs at zero. The record is written with the next profile save (the hourly autosave or the logout), not at login.
- **Cost and census.** The whole sweep is timed into the existing `social-tick` phase (`BotPerformanceMonitor`), and
  `SocialLod.stats()` / `census()` report tier changes, maintenance done and skipped, catch-ups and relief minutes next
  to `ModeSwitch`'s counters, with one info line every 10 minutes.

Not in this stage: bonds are not caught up in the background (Stage 2), no loop breaker, and the counters are not yet on
the dashboard.

## 7. Staged plan

| Stage | Deliverable | Proof | State |
|---|---|---|---|
| 0 | Flags on by default, `SocialSim`, `social-tick` phase | unit + full suite | done (`cea8d6671`) |
| 0b | Mode switching | unit, gate, multi-day policy simulation, full suite | done (this branch), TEST run pending |
| 1 | `SocialMind` (traits, emotions, needs), decay, persistence | decay and restart round-trip tests | built (`164-social-mind`); TEST persistence check pending; no decision reads it yet |
| 2 | 8-axis bonds, migration 059, background formation | axis table, directedness, hysteresis, eviction, restart and legacy-row round trips, background tier, simulated-days tests | built (`164-social-bonds`, `f03304844`); migration 059 not yet applied on TEST; no decision reads the axes yet |
| 3 | Memory extension (migration 060), relevance recall, knowledge boundaries, memory in bot-bot talk | knowledge-boundary tests | built (`164-social-memory`): subject type, place, confidence, valence, provenance, other party; `BotMemory.recall(.., Query, ..)` and `remember(..)` writer; repeats merge; world news only from witnesses or tellers; circle-restricted counts; `BotThreads`/`BotTalk` prompts carry the partner's real memories. Unit-tested only; TEST not run |
| 4 | `SocialAction` registry, missing actions, gifts, emotion/memory into `TalkBrain` and `LifeDecision` | per-action outcome tests, utility ordering, Alice/Bob/Clara scene, 3-day 12-bot simulation | built (`164-social-acts`): section 9; unit and simulation tests only, TEST not run |
| 5 | Intentions (memory in `BotThreads`/`BotTalk` prompts moved to Stage 3) | dialogue-to-intention test | built (`164-social-intents`, `81a7faa31`): see section 8; unit-tested only; performer is a seam, wired to `SocialActions.perform` by Stage 4 (section 9); TEST not run |
| 6 | Social LOD, loop breaker, metrics in dashboard and inspect | perf measurement | 6a built (`164-social-observe`): `SocialBudget` loop breaker (per-pair cooldown, per-bot, per-map and global rates, A-B turn limit, failed-act backoff; hooked into `BotTalk`), `social` block in `live.json`, mode/mind/bonds in `/bots/{cid}`, mode and mind lines in `!botwhy`; unit tests only, no perf measurement and no TEST run yet. 6b social LOD planned **6b:** 6b social LOD built (`164-social-lod`: tier/hold, 5-minute maintenance, catch-up bounds, flag-off tests, full suite); loop breaker and dashboard metrics planned |
| 7 | 20-bot Henesys pilot, TEST (mapletest) live check | real execution evidence | built (`164-social-pilot`): section 10; routine, groups, pilot selection, hold, counters; a 7-day, 20-bot harness asserts the ten pilot criteria in a fake world; nothing run on TEST or in a client **Auto-QA and runbook:** auto-QA and runbook written (`164-social-qa`): `Probes164Social` with `t-ms-1` (all three modes, switches both ways, swap refusals under 5%, no bot over 3 an hour, via the new `ModeSwitch.switchesLastHour` accessor), `t-sm-1` (minds finite, mood in [-1,1], events moved off the seeds) and `t-sa-1` (three act kinds, error and refusal shares, turn cap), each SKIPping with a reason; `t-pi-1` reads `SocialPilot` through a reflection seam and is registered with the pilot. Runbook `docs/qa/social-test-runbook.md` (migrations 059/060 before the jar, SELECT-only checks, restart survival of the `mind` key, yield per bot hour before/after the mode switch, Henesys walkthrough, rollback) and `docs/release/1.6.4-social-conf-notes.md`. The pilot itself is still planned |

Nothing here has run in a real client or on TEST yet.

## 7. Stage 2 as built: directed multi-axis relationships

`Bond` (bot to bot) and `Tie` (bot to player) each carry, besides the legacy `score`/`kind`/`hostile`, eight axes and a
`Stage`; both live in `AP/BotAffinitySystem`.

- **Axes** (`Axis`): FAMILIARITY 0..1, TRUST -1..1, AFFECTION -1..1, RESPECT -1..1, ATTRACTION 0..1, RIVALRY 0..1,
  RESENTMENT 0..1, OBLIGATION -1..1 (negative: the other owes the holder). Stored as of `updatedAt` and faded on read, each by
  its own half-life (liking 42 days, dislike 14, resentment 14, trust 56/28, respect 84/42, attraction 21, rivalry 28,
  obligation 21, familiarity 120 toward a floor of 0.08 so a met pair never forgets). Tunable: `-Dsolomapling.axes.<axis>.halfLifeDays`.
- **One table** (`AxisTable`): the per-axis delta of every `BondEvent` and `AffinityEvent`, and the one place the Bartle
  share and the `SocialMind` traits (agreeableness, jealousy, empathy, loyalty, read memory-only through
  `LifeMemoryStore.peek(id).mind(..)`) scale it (`Persona`). A neutral bot scales by exactly 1. Tuning an event's weight
  (`-Dsolomapling.bonds.<event>`) tunes its axes with it.
- **`score` is unchanged**: it is still moved by the event's own delta, so `state()`, `friend()`, `hostile()`, crews,
  `Reputation`, `GuildRules`, couples and `AffinityReactions` read what they read before. Axes ride along. (Deriving the
  score from the axes would have moved every threshold and every existing test; the axes are the new truth for stage 4 on.)
- **Directed, and no omniscience**: events give a row to the edge of the one they happened to. A KS sours only the
  victim's edge; a gift warms the receiver's affection and obligation (+0.09, +0.12) more than the giver's
  (`BondEvent.GIFT_GIVEN`: +0.03, and obligation -0.06, the receiver owes it). A bot's belief about how the other feels is
  its own edge: no code reads the other direction to answer for a bot.
- **`Stage`** (STRANGER, ACQUAINTANCE, FRIEND, CLOSE_FRIEND, RIVAL, ROMANTIC, ESTRANGED): derived from the faded axes and
  the stored previous stage, entered at a higher threshold than it is left at (thresholds in `Stage`'s javadoc), one rung
  per event, never raised by time. ESTRANGED needs a friend-level edge that then fell with resentment, not one that faded;
  a friendship that merely faded drops to ACQUAINTANCE. The legacy `AffinityState` derivation is unchanged.
- **Persistence**: migration 059 adds ten columns (eight axes, `Stage`, `HasAxes`) to `botbond` and `botaffinity`,
  idempotently, with a rollback. Rows with `HasAxes = 0` (every older row) get axes and stage derived from score, kind and
  hostile. Without the migration the stores probe once (again after 10 minutes), fall back to the older six columns and
  derive at every load. Writes stay in the 3-minute batched flush.
- **Eviction**: the 14-bond cap (and the tie cap) drops the least *significant* edge (score, affection, resentment,
  rivalry, attraction, familiarity, plus a stage bonus), so estranged, rival, romantic and close edges outlive faded
  neutral ones. A bond is only dropped as faded when no axis but familiarity still holds a feeling.
- **Background tier**: `BotBonds.step` also samples `slums.bonds.background.maps` (2) maps nobody watches, one pair each
  (`...pairs`, 1, never above the observed tier's 3), NEAR or GUILD. `-Dslums.bonds.background=false` is the rollback. Its
  cost is inside the existing `social-tick` phase; the last step's pairs, maps and microseconds are in `BotBonds.stats()`.
- **Read API**: `BotBonds.axes(botId, otherId)` and `.stage(..)` (and `BotAffinity.axes/stage` for ties) return immutable
  views; `!botwhy` shows a `Stages:` line.

Proof: `RelationAxesTest`, `BotBondsAxesTest`, `TieAxesTest`, `AxisColumnsTest`, `BotBondsSimulatedDaysTest`. Not done: no
database run of migration 059 (no MySQL in the unit tests; the JDBC mapping is tested against mocked result sets and the
SQL by string checks), nothing on TEST, no decision reads the axes yet.

## 8. Stage 5 as built: persisted intentions

A bot that agrees to meet, help, come along or party has made a promise. Before: `TalkPromises` judged a bot-bot yes after
the fact and `ConvoActions` acted on a player's request at once; nothing was remembered once the lines and the act were
over, and `BotCommitments` held a bot only for trades, invites, expeditions and runs. After:

- **Records** (`AP/BotSocialSystem`): `SocialIntent(id, actor, target, kind, createdAt, dueAt, expiresAt, mapId, origin)`;
  `IntentBook` (at most 3 live per actor, ordered by due time then id, plus the last 5 endings; id = actor in the high 32
  bits, a never-reused per-actor sequence below); `SocialIntents` (create, due, complete, cancel, expire, tick, counters,
  `whyLine`). The book lives in the bot's `LifeMemory` JSON under `"intents"` (lazy, written back as read, a read never
  dirties it, a bad row is skipped), so it is saved and restored with the rest of the bot's life memory and the roster's
  existing sweep. Nothing reads a table; a bot whose memory is not loaded gets no intent.
- **Creation is server-only.** Two existing yes paths call `PromiseIntents`: `TalkPromises.promised` (bot-bot yes: PARTY
  gives INVITE, HELP gives OFFER_HELP, COME_ALONG gives SPEND_TIME; a deal or a story promises nothing later) and
  `ConvoActions.carryOut` (a player's "come" or "help" that passed `ConvoGates` and sends the bot to another map: SPEND_TIME,
  OFFER_HELP). Both run after the act that already ran; nothing was removed. The model's text, a `[do:x]` tag the gates refused
  and a `SayDo` claim reach none of it. A real player is only a target if `SocialGate.check` (so `@quiet bots`, PlayerBusy)
  and `BotAffinity.optedOut` (`@botdrama off`) say yes, at creation and again at the due time.
- **Lifecycle.** A bot-bot yes falls due 2 minutes after `TalkPromises`' own window, so a promise the talk's act kept is
  ended KEPT by that verdict (`PromiseIntents.onBacked`); one that was not gets a second try. A player-addressed intent is due 4
  (come) or 5 (help) minutes out. `SocialIntents.tick` (from the bot's own tick, in the three bot types, before
  `ModeSwitch.maybeSwitch`): at the due time, if the target is online, reachable, the actor is free (the brain's
  `commitBlocker` and `BotPopulationDirector.mayLogOut`, with the actor's own intent exempt) and `SocialBudget.allow`, the
  `Performer` carries it out; success ends it KEPT; failure retries every 45 s; at the deadline it ends BROKEN (CANCELLED if the
  target went offline, EXPIRED with no consequence if the deadline passed over 10 minutes ago).
- **Consequences.** Kept: the promised-to bot's own edge toward the actor gains trust, respect and obligation
  (`BotBonds.promise`, axes only, `AxisTable` rows), the actor's pride rises. Broken: that edge loses trust and respect and gains a
  little resentment, the promised-to bot keeps a `BROKEN` note (`BotMemory.Kind.BROKEN`, valence -0.6; named short because the Kind
  column is VARCHAR(8), so no migration), the actor's anxiety and embarrassment rise. The legacy score never moves.
- **Committed while due soon.** `BotCommitments.inFlight` has one more clause, `SocialIntents.dueSoon` (an intent due within 2
  minutes, memory only), so `BotPopulationDirector.mayLogOut` keeps the bot online and `ModeSwitch.blocker` (which asks
  `inFlight`) does not swap its mode; `LfgParty` and the brain's own free check see it as busy too.
- **Switch and metrics.** `-Dslums.intents=false`: nothing is created, ticked or counted as a commitment. Counters (created,
  kept, broken, expired, cancelled, refused, deferred, each by kind) in `live.json` `social.intents`; `!botwhy` has an `Intents:`
  line.
- **The one integration point.** `SocialIntents.performer` is a static field that starts as "no performer" (every try fails
  with that reason). When Stage 4 is merged: `SocialIntents.performer = (a, t, k, now) -> { var o = SocialActions.perform(a, t, k,
  now); return new SocialIntents.Result(o.happened(), o.reason()); };`. Until then intents are created and persisted but only the
  talk's own verdict can keep one.

Proof: `SocialIntentsTest`, `SocialIntentsConsequencesTest`, `PromiseIntentsTest`, `BotCommitmentsIntentsTest`,
`ModeSwitchIntentsTest`. Not done: no TEST run, no client, no run with the real `SocialActions`; the performer is a seam.

## 9. Stage 4 as built: the social act registry and the decision tick

Package `AP/BotSocialSystem` (new: `SocialActKind`, `SocialAction`, `SocialActions`, `SocialActs`, `SocialContext`,
`SocialDecider`, `SocialLines`, and the seams `SocialWorld`, `SocialFlows`, `GiftSeam`); `AP/BotConvo` holds the game
side of the seams (`GameSocialWorld`, `GameSocialFlows`). Switch: `-Dslums.socialacts` (on unless `false`).

- **Registry.** `SocialActions.perform(actor, target, kind, now)` is the one entry: switch, both bots on a map, the act's
  preconditions, free/idle requirement, `SocialBudget.allow`, execute, `SocialBudget.record`, counters (`SocialMetrics`,
  `social.acts` in `live.json`). Never throws. Acts toward a real player are refused (player-facing talk stays on `SocialGate`).
  `best(actor, candidates, now)` scores every legal (act, target) with noise from `SocialSim.random(actor, "social-act")`
  scaled by impulsiveness and patience, and returns the best or nothing under the bot's own bar (loners need more pull).
- **The 16 acts** (table in `SocialActs`' javadoc: preconditions, utility terms, success/failure, state changed). Wrapped
  without behaviour change: GREET, CONVERSE (`BotTalk.run`), ASK_HELP (`run` with HELP forced), OFFER_HELP (the help trip,
  extracted from the HELP act as `BotTalk.helpTrip`), INVITE/ACCEPT/DECLINE (an open invitation the other answers on its own
  tick; ACCEPT runs `LfgParty.form` / `BotTalk.plainParty` / `BotTalk.comeAlong`), SHARE_INFO and SPREAD_RUMOR
  (`BotWorldGossip.pass`, `BotGossip.keep`). Built: COMPLIMENT, APOLOGIZE, RECONCILE, ARGUE, AVOID, SPEND_TIME, GIVE_GIFT.
- **New bond events** (`BondEvent`, rows in `AxisTable`): COMPLIMENT, APOLOGY and RECONCILE (resentment and rivalry fall,
  trust rises), ARGUE (a harder SPAT). The stage still climbs one rung per event, so a pair recovers gradually.
- **Memory.** Acts write short, name-free notes (`Kind.CHAT`, wording rotated by the minute because the memory drops the
  same text within 20 h); the next decisions read them back with `BotMemory.recall` (an open fight, an apology, a
  making-up decide APOLOGIZE and RECONCILE).
- **Utility** is built from the SocialMind (needs, emotions, traits), the actor's OWN edge and stage, memory, the target's
  company (jealous bots hesitate beside a distrusted company; a hothead confronts someone standing with a friend), norms
  (never argue with a close friend unless resentment is 0.5+; honest bots do not spread rumors; PG-13 game-only insults
  from the drama bank; nothing aimed at a player), habituation, and the budget's per-pair gaps. Friendly acts scale by a
  social drive from unmet company needs, so met needs quieten a bot.
- **Decision tick.** `SocialDecider.onMap` is called from `BotTalk.tick` after the greetings and chats: per map at most 2
  due bots decide (each every 45-180 s, sooner for the sociable and the lonely; an invitee answers within seconds), from
  at most 6 visible bots it knows. No model call, no database read. A bot with no loaded mind is skipped.
- **Inputs into existing decisions.** `BotTalk.WorldSense.mind` blends the mind's mood into the mood, sociability and
  loneliness into the social share, anger into the killer share, and an unmet companionship need into the COMPANY goal.
  `LifeRuntime` adds the mind's companionship/loneliness pull (at most about 0.4) to the SOCIAL need. AVOID records are
  honoured by the arrival greeting and the idle chat.
- **Seams, wired to the sibling stages once `164-social-int` was merged in.** `GiftSeam` (live: `BotGiftSeam` over
  `BotBotGift`, which records the receiver's bond and push; the act adds the giver's GIFT_GIVEN, both memories and the
  feelings; `AFFINITY_ONLY` is the no-item gesture for tests) so GIVE_GIFT gives what the gift primitive picks and is
  refused, never faked, when it cannot; `SocialIntents.performer` is set to `SocialActions.perform` at `BotTalk.start`
  (a landed act is a promise kept); `SocialWorld.paceFactor` reads `SocialLod` (an unwatched bot decides four times less
  often). `SocialActions.avoiding/avoidRecords/pendingInvite` are read APIs for later stages and `SocialActions.observer`
  sees every carried-out act. With `-Dslums.socialacts=false` a promise cannot be performed, so pair it with
  `-Dslums.intents=false`.

Proof: `SocialActsTest` (every act: outcome, refusal, failure), `SocialUtilityTest` (utility ordering, the Alice/Bob/Clara
scene with seeded runs, stability, norms), `SocialDeciderTest`, `SocialSimulationTest` (12 bots, 3 simulated days, counts
printed), `BotTalkSocialActsTest` (mind inputs on and off, avoid records, the tick hook). Not done: no sitting together
(SPEND_TIME stands beside the target), no PQ choice on INVITE beyond what `WorldSense.offers` allows, nothing on TEST or in
a client.

## 10. Stage 7 as built: the Henesys pilot, routines and groups

Package `AP/BotSocialSystem`: `SocialPilot`, `SocialRoutine`, `SocialGroups`. Switches (all `-D`, on unless `false`): `slums.socialpilot` (the whole stage; `.size` 20,
`.map` 100000000), `slums.socialroutine`, `slums.socialgroups` (each also needs the pilot). `=false` restores the old behaviour exactly: no members, no hold, no bias, no groups.

- **Who.** `SocialPilot.select`: the active roster slots with the lowest `rank(slot)` (a mixed 64-bit hash, fixed), sticky: a member stays while it is active, a replacement is the next
  lowest hash; the roster is read at most every 10 minutes from the director's tick. Same roster, same 20; enrolling new bots never reshuffles them.
- **Director hold.** In `BotPopulationDirector.candidates()` a pilot bot inside its own play window is built with a zero session length and no leave-now permission, so `plan()` neither
  ends its session nor trims it above the curve; pilot bots also get a login weight of 3. Outside the window, and for every other reason a session ends (a stop, a GM, a rage quit, death
  or a commitment gate), nothing changed. `LifeTownSlots.reserve` lets a pilot bot into a full Henesys map (`+size` headroom over the usual cap).
- **Placement.** `LifeRuntime.townDestination` returns the bot's `SocialRoutine` spot (source `LifeTowns.Source.PILOT`) after friend, crew and plan and before the generic town pull;
  `TownWandererBot.pickNextMap` prefers it; `SocialPilot.LIFE_BIAS` (a `LifePolicy` plug-in, at most 0.25) adds a hang-out pull at the hours its routine gathers. The gathering places are the
  home map's town family (Henesys: town, market, park, pet park); no in-map anchors (no map data here to place them on).
- **Routine as soft structure.** `SocialRoutine`: four smooth hour weights per bot (work/training, a lunchtime gathering, an evening hangout, rest) from its traits and a per-bot phase, read
  in the director's zone. They only ever reach decisions as (i) `ModeSwitch.decide(.., ModeBias)`: two multipliers on the pull scores, always in `[0.5, 1.8]`, pulled toward 1 by a strong
  companionship need, loneliness or tiredness (the old three-argument overload is the neutral bias); (ii) `spotFor`: the bot's taste for each place (traits: sociable to the square, ambitious
  to the market, agreeable and quiet to the park, curious to the pet park) times what the place is for at that hour, plus its group's gathering, one stable draw per 30 minutes; (iii) the
  hang-out bias. Bots with like minds like the same place and so meet there again and again; that repeated proximity is what forms the bonds.
- **Groups.** `SocialGroups`: connected components, of at least 3, of the mutual FRIEND/CLOSE_FRIEND graph over the pilot (recomputed at most every 3 minutes by whichever decision asks,
  memory only). A group's place is its members' most common favourite at its event hour (the circular mean of their evening hours); `adjust` adds 0.10 to SPEND_TIME and INVITE toward a group mate
  and up to 0.12 to ARGUE and AVOID toward members of a group in conflict (tension = mean over cross pairs and both ways of max(resentment, 0.8 x rivalry), above 0.10); the decision tick lists group
  mates first; `reputation(group, subject)` averages the members' `Reputation.known` (no store); `/bots/{cid}` has a `group` field.
- **Counters.** `SocialPilot.stats()` (also `social.pilot` in `live.json`): members, online, held, `mode.*`, `acts.<KIND>`, `friendships.edges/formed/mutualPairs`, `conflicts.*`, `memories`,
  `intents.kept/broken`, `gifts`, `avoids`, `restartsSurvived`, `placements`, `routineBiases`, `groups`, `groupedBots`.

### The ten pilot criteria, as shown

`SocialPilotSimulationTest` (with `SocialPilotSim`): 20 bots from the real selection, 7 simulated days on the `SocialSim` clock (seed 20261103), 3 visits of a familiar and a stranger player, one
restart on day 4 at noon. Real: the mind, `ModeSwitch.decide` with the real bias, `SocialPilot.destination`, `SocialDecider`, `SocialActions`, `SocialBudget`, `SocialGroups`, `BotBonds` (axes, stages)
over a memory store, `BotMemory` over `FakeMemoryTable`, the intent book, `BotAffinity`, `AffinityReactions.promptLine`, `RelationshipCard`. Faked: positions, who is busy, who stood near whom (the
sampling of `BotBonds.step` replayed over ids with the same caps and 12 other maps competing for the background tier), the chat flow, what a grind and a hang-out do to needs, the map from needs to
`ModeSwitch.Inputs`, the sessions, mob thefts and the players. Same seed, same report (compared byte for byte).

| # | criterion | how shown (last run) | needs TEST |
|---|---|---|---|
| 1 | independent routines | at 12 of 13 afternoon/evening hours online bots stood at 3 or more different places (training, square, market, park, pet park); mean pairwise agreement of hourly place 0.30 (lock-step 1.0); each bot's town share 0.43..0.75; 4 favourite places | harness only for the numbers; TEST: look at 20 bots at three hours of a day, `stats()` mode census, the real director hold and the real mode swap |
| 2 | friendships by repeated interaction | 75 directed edges reached FRIEND with 6..44 NEAR+SPEND_TIME+CONVERSE first (median 16); of the most-interacting quarter of pairs 72% are friends, of the least 0% | harness only (the fake replays `BotBonds` sampling); TEST: bond stages in `/bots/{cid}` after a few hours |
| 3 | conflicting personalities, different acts | the most agreeable/patient and the most impulsive/disagreeable pilot bot choose different acts in 2 of 4 fixed situations (GREET or APOLOGIZE against ARGUE); ARGUE actors' mean agreeableness 0.52, APOLOGIZE actors' 0.57 | harness only (utilities of the real acts) |
| 4 | relationships change after events | 4 scripted events (gift, two stolen mobs, a PQ): affection +0.48 after the gift, resentment +0.22 and +0.40 and trust down after the thefts; 3 changed the stage | harness only |
| 5 | agents remember | 21 apologies, each decided from an earlier remembered fight read back through `BotMemory.recall` | the SQL path of migration 060 on a real database: TEST |
| 6 | bots start conversations | 718 CONVERSE by 20 different bots across 102 pairs (695 greetings) | real lines (`BotTalk.run`, the model): TEST |
| 7 | emotion and memory change decisions | ARGUE utility 0.25 calm -> 0.61 angry, SPEND_TIME 0.09 met -> 0.76 lonely; a remembered open fight makes APOLOGIZE legal (0.78) and changes the choice from ARGUE to APOLOGIZE | harness only |
| 8 | a familiar player against a stranger | after the first visit 3 bots treat the player as a friend; on later visits the bots there read FRIEND, a warm prompt line and a card of shared memories for the familiar player, "you don't really know them" and no line for the stranger | harness covers the state, the prompt line and the card; the chosen act (`AffinityReactions.pickFriendly`/`pickSideEye`) is package-private and needs a `BotSM`; real players and a client: TEST |
| 9 | state survives a restart | mind (12 traits, 9 emotions, 8 needs) identical to 1e-9, 110 edges with a feeling within 3e-8 (floats) and the same stages, 109 pairs of memories, the intent books and the resumed modes equal; lost: faint edges (familiarity only: 88 of 270 are pruned by `BotBonds` on flush and load, by design), open avoid records, invitations and spend-time pairs, `SocialBudget` history, the decision pace, act timestamps, all counters | harness only: no real MySQL (botbond, botmemory, botlifeprofile run on memory fakes and a mocked JDBC pool), no real roster rows: TEST |
| 10 | interaction with no player | 1,609 landed acts on the 4 days with no visit (days 1, 3, 5, 7) | harness only |

Safety over the week (asserted): no pair repeats an act family inside its budget gap, busiest bot 2 acts in a minute (limit 10), memory notes per pair at most 8 (cap 10), no act failed more than 2 times in an hour
for one (actor, target), at most 13 acts in one bot-hour (mean 1.70).

Not in this stage: nothing run on TEST or in a client; the harness world is the only world; component groups merge a dense town into one big group (14 of the 20 here), so the conflict between two groups is
only exercised by unit tests on constructed graphs; no in-map gathering anchors; group reputation has a read API and no consumer yet.

