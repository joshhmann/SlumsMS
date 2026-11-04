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
| 7 | World APIs | `GCMovement.move/follow/teleportTo`, `BotSpeak/BotChatbubble/BotEmote`, `botSitChair`, `LfgParty`, `BotBotTrade`. No single "walk to bot X, face, talk" helper; no bot-to-bot item gift (`BotGifts` gives to players only). |
| 8 | Bot-bot without a player | Yes: `BotTalk` (needs 2+ bots on a map), `BotGossip`, couples, bond events. `BotThreads` and `BotChatter` need a watching player. |
| 9 | Offscreen | Bots stay online; unobserved, the macro tick drops to 60-120 s and movement goes coarse (`ObserverTracker`). No social LOD. |
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
| 5 Actions | PARTIAL | Bespoke flows (greet, chat, party, trade, help, gossip, spat, crush, couple, wedding, guild beef). No registry. Missing: compliment, bot-bot gift, apologize/reconcile, avoid, spend-time-together. |
| 6 LLM | PARTIAL | Memory reaches player chats only; `BotThreads`/`BotTalk` do not read `BotMemory`. `BotCommitments` is only a tick keep-awake; promises are judged after the fact by `SayDo`/`TalkPromises`, never scheduled. |
| 7 Routines, groups | PARTIAL | Crews, guilds, couples, `Reputation`, `Fads`. Schedule = login window and session length only. |
| 8 LOD, guards, observability | PARTIAL | Movement LOD, `BotTalk`/`SocialGate` budgets, `SocialTrace`. No social LOD, no generic bot-bot loop breaker, no social metrics in the dashboard. |
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
6. **LOD.** `ObserverTracker` is the active/background signal; background bots get bond and need catch-up on a slow
   timer, dormant bots on login. New work is costed in the `social-tick` phase.
7. **Test seam.** `SocialSim` (one clock, per-(bot, purpose) seeded RNG) for all new code.
8. **Gifts (owner, non-economic).** A gift is an affinity effect, or gear that fits the receiver's job and level, or gear
   that fits every job (earrings and similar), or a usable; chosen by the receiver's `SocialMind` needs, with a per-pair
   cooldown. The item comes from the giver's own holdings, so nothing is minted. Needs the bot-to-bot item hand-over
   that does not exist yet (Stage 4).

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

## 6. Staged plan

| Stage | Deliverable | Proof | State |
|---|---|---|---|
| 0 | Flags on by default, `SocialSim`, `social-tick` phase | unit + full suite | done (`cea8d6671`) |
| 0b | Mode switching | unit, gate, multi-day policy simulation, full suite | done (this branch), TEST run pending |
| 1 | `SocialMind` (traits, emotions, needs), decay, persistence | decay and restart round-trip tests | built (`164-social-mind`); TEST persistence check pending; no decision reads it yet |
| 2 | 8-axis bonds, migration 059, background formation | axis table, directedness, hysteresis, eviction, restart and legacy-row round trips, background tier, simulated-days tests | built (`164-social-bonds`, `f03304844`); migration 059 not yet applied on TEST; no decision reads the axes yet |
| 3 | Memory extension, relevance recall, knowledge boundaries | knowledge-boundary tests | planned |
| 4 | `SocialAction` registry, missing actions, gifts, emotion/memory into `TalkBrain` and `LifeDecision` | per-action outcome tests | planned |
| 5 | Intentions; memory in `BotThreads`/`BotTalk` prompts | dialogue-to-intention test | planned |
| 6 | Social LOD, loop breaker, metrics in dashboard and inspect | perf measurement | planned |
| 7 | 20-bot Henesys pilot, TEST (mapletest) live check | real execution evidence | planned |

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
