# Affinity, bonds, gossip and memory

## Purpose

Three related stores decide what a bot thinks of a person and of another bot, and how that opinion travels:

- **Affinity** — one bot's opinion of one *real player*, a signed score plus two event counters plus eight axes and a stage, stored one row per (player, bot). `src/main/java/soloMapling/ArtificialPlayer/BotAffinitySystem/BotAffinity.java:21-36` states the design rule the whole package follows: the numbers stay server-side, the player only ever sees words.
- **Bonds** — the same arithmetic for *bot-to-bot* edges, directionally (a KS sours the victim, not the thief), at most 14 rows per bot (`database/migrations/029-bot-bonds.sql:8-12`). A bot's *crew* is derived from mutual friends, not stored (`BotAffinitySystem/BotBonds.java:863-899`).
- **Memory and gossip** — `botmemory` rows give a bot something to say and something to pass on; `BotGossip` moves a note along one bond per minute, `BotWorldGossip` moves world news, `Reputation` moves a circle's opinion. `BotMemory` also feeds chat prompts and the buddy rules.

The three are wired in one direction: events write affinity/bonds and memory (`BotAffinity.java:488-498`), gossip reads memory and writes affinity as hearsay (`BotGossip.java:166-169`), and affinity changes write new memory notes (`BotAffinity.java:467-486`). No path reads another bot's edge to answer for a bot (`BotBonds.java:760-762`), and no prompt ever carries an affinity number (`BotAffinityTest.thePromptNeverCarriesANumber`, `src/test/java/soloMapling/ArtificialPlayer/BotAffinitySystem/BotAffinityTest.java`).

## Entry points

| Entry | Kind | Detail |
|---|---|---|
| `BotPopulationDirector` tick | scheduler, 1/min | `BotGossip.maybeStep(now)` then `BotBonds.maybeStep(now)` inside the director's social block: `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:534` and `:537` |
| Map entry | hook, per map add | `BotAffinity.ensureLoaded(chr)` — `src/main/java/server/maps/MapleMap.java:2856` |
| Player login | hook | `BotAffinity.ensureLoaded(player)` — `src/main/java/soloMapling/ArtificialPlayer/SocialInitiative.java:325` |
| Player/bot events | hooks | `AffinityHooks.onPartyJoin` / `onBuff` / `beforePlayerHit` / `onBotYield` / `onMobHitBot` / `onPlayerKill` / `onTrade` / `onFame` / `onPickup` — `BotAffinitySystem/AffinityHooks.java:72,93,109,148,179,219,241,264,274` |
| Bot↔bot events | hooks | `BotBonds.onPartyJoin` / `onThread` / `onPqTogether` / `onBuff` / `onTrade` / `beforeBotHit` — `BotAffinitySystem/BotBonds.java:365,379,387,401,408,429` |
| Chat line | hook | `RudeStreak.onLine` + `RudeStreak.spread`, then `AffinityReactions.judgeTone` — `src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:587,590,594` |
| Chat end | hook | `BotMemory.writeConversationNotes`, `BotMemory.progress`, `BotBuddies.afterChat` — `BotConversation.java:524-535` |
| Bot sight of a player | hook | `AffinityReactions.HOOKS` used by `GCMoveSystem/BotPlayerReaction.java:76`; `AffinityReactions.STATIONED` by `BotTypes/SocialBot.java:236-238` |
| `@botdrama off\|on` | command, GM0/player | `client/command/commands/gm0/BotDramaCommand.java:7-12`, registered at `src/main/java/client/command/CommandsExecutor.java:294` |
| `@bonds` | command, GM0/player | `client/command/commands/gm0/BondsCommand.java:13,17`, registered at `CommandsExecutor.java:296`; view built by `RelationView` |
| `!affinity` | command, GM4 | `list` / `set <bot> <friend\|rival\|nemesis\|neutral>` / `grudge` / `stage <state> [social\|training]` / `unstage` — `client/command/commands/gm4/AffinityCommand.java:53,64,81,98,114,124`; registered `CommandsExecutor.java:436` |
| `!botwhy <name>` | command, GM3 | appends `BotBonds.describe` — `client/command/commands/gm3/BotWhyCommand.java:37`; gated by `BotWhyCommand.isEnabled():20-23`, registered `CommandsExecutor.java:382-383` |
| `!qa` scenarios | QA | `af-1`/`af-2`/`af-3` `src/main/java/soloMapling/qa/QaScenarios.java:1288,1304,1361`; `bb-3` `qa/BondScenarios.java:35`; `gs-1` `qa/GossipScenarios.java:46`; `me-2` `qa/MemoryScenarios.java:105`; `rv-5` `qa/BeefScenarios.java:84` |
| YAML | dialogue | `AffinityDialogue.yaml`, root `Affinity` — `BotAffinitySystem/AffinityReactions.java:66-67` |
| Migrations | schema | `011-bot-affinity.sql`, `029-bot-bonds.sql`, `030-bot-memory-kinds.sql`, `031-social-gate.sql`, `062-bot-relationship-axes.sql`, `063-bot-memory-social.sql`, plus `botmemory` in `003-solomapling-bots.sql:96-109` |

## How it works

### The rules modules

| Module | What it owns | Where |
|---|---|---|
| `AffinityEvent` | the 18 player→bot events with default delta and cooldown (PARTY 6/1800 s, GIFT 9/600, FAME 12/0, APOLOGY 30/0, KS -6/120, DEFAME -18/0, SCAM -20/600, HEARSAY_GOOD 3/3600, HEARSAY_BAD -4/3600) | `BotAffinitySystem/AffinityEvent.java:14-36`, delta/cooldown read `-Dsolomapling.affinity.<EVENT>[.cooldownSec]` at `:62,66` |
| `BondEvent` | the 16 bot↔bot events (PARTY 6/1800, PARTY_TIME 3/300, PQ 8/1800, GIFT 8/1800, GIFT_GIVEN 0/1800, NEAR 1/1800, KS -6/300, ARGUE -8/1200) | `BotAffinitySystem/BondEvent.java:16-47`; `weight()` scales axes by the same tuning `:74-76` |
| `AxisTable` | one 8-column row per event per side (BOND and TIE maps) | `BotAffinitySystem/AxisTable.java:33-34`, rows `:43-61` (bonds) and `:63-81` (ties), `promise` rows `:84-85`; persona scaling `:128-152` (familiarity never scaled, `:148`) |
| `Axis` | the 8 axes and their half-lives: FAMILIARITY 0..1/120 d, TRUST -1..1/56 d (28 negative), AFFECTION 42 d (14), RESPECT 84 d (42), ATTRACTION 21 d, RIVALRY 28 d, RESENTMENT 14 d, OBLIGATION 21 d | `BotAffinitySystem/Axis.java:25-27`; fade `:81-93`, floor `:76` |
| `Axes` | `warmth = 0.6*aff + 0.3*trust + 0.1*respect` `:96`; `significance` `:132`; `legacy` synthesis for pre-062 rows `:147` |
| `Stage` | STRANGER…ESTRANGED with enter/leave hysteresis, one rung per event | thresholds documented `BotAffinitySystem/Stage.java:12-25`, enum `:29-30`, `step` `:67`, `settle` `:128` |
| `AffinityRules` | scoring for player ties: MAX 100, HEARSAY_CAP 15, FADED 3, HEARSAY_AXIS_CAP 0.15; FRIEND ≥25, RIVAL ≤-25, NEMESIS ≤-65 | `BotAffinitySystem/AffinityRules.java:24-30,43,47,51`; `state` `:81`; `apply` `:120-167` |
| `BondRules` | the same for bot edges, with Bartle scaling of the delta (0.6..1.8) | `BotAffinitySystem/BondRules.java:21,34,38,43,54,63,68,89,110-146` |
| `Persona` | agreeableness/jealousy/empathy/loyalty read from `LifeMemoryStore.peek`, never the DB | `BotAffinitySystem/Persona.java:15,18,23,29` |

### Flow 1 — a bond forming (bot↔bot), end to end

1. **Something happens.** `AffinityHooks.onPartyJoin` (`AffinityHooks.java:72`) sees two bots in one party and calls `BotBonds.onPartyJoin` (`BotBonds.java:365`), which records `PARTY` and, if they share a guild, `GUILD`.
2. **Time passes in a party.** The minute step pairs every party's members and records `PARTY_TIME` (+`GUILD`) per pair, capped at the first 6 members — `BotBonds.step` `:494`, `:518-534`.
3. **Score arithmetic.** `BotBonds.record` `:217` guards `enabled()`, distinct bot ids; reads the persona before taking the lock `:222`; scales the delta by `BondRules.scale(event.delta(), social, killer)` `:223`; calls `BondRules.apply` `:233`.
4. **`BondRules.apply`** `:110-146`: rejects a zero move `:113`; enforces the event cooldown `:117-120`; forgets `kind`/`hostile` when the faded score is under `FADED` `:125-128`; clamps score to ±100 `:129`; bumps `kind`/`hostile` `:130-134`; adds the scaled `AxisTable` row `:135`; moves the stage one rung `:136`; stamps `updatedAt` and `dirty` `:137-139`.
5. **Cap.** `BotBonds.bound` `:302-333` keeps at most `-Dsolomapling.bonds.k` (14, clamped 4..32, `:155`) by `BondRules.significance` `:195`; a shared event's own edge is never the one dropped (`keepTouched` `true`), a sampled `NEAR` newcomer competes and loses ties `:240,293`.
6. **State change.** `BondRules.state` `:68` needs score ≥25 *and* kind ≥3 for FRIEND, ≤-65 *and* hostile ≥5 for NEMESIS, ≤-25 *and* hostile ≥2 for RIVAL. On a crossing, `BotBonds.onStateChange` `:335` pushes the bot's Bartle point toward PARTY/CHAT `:338-343`, posts a site feed drama line for a new nemesis `:345-351`, and logs `AFFINITY BONDS a -> b BEFORE -> AFTER (cause, score N, kind N, hostile N)` `:353-359`.
7. **Side effects.** `Reputation.onBond` `:251`, `Reputation.onBondState` `:256`, `LifeLearning.onBond` `:258`; the crew cache of both bots is dropped on a crossing `:254`.
8. **Crew.** `BotBonds.crewOf` `:867` walks mutual friends two hops, at most `CREW_MAX` 6 (`:77`), cached one minute (`:80`); `SocialGate.addCrewSource` `:467` makes that crew the "side-eye" tier for players (`SocialGate.java:243-256`).
9. **Persistence.** The bot id goes into `dirtyBots` `:247`; `maybeStep` requests a flush every 3 minutes (`:484`, `:79`); `flush` `:702` deletes first, drops bonds that faded to nothing, then upserts the dirty rows through `BondStore.write`.

### Flow 2 — gossip propagating

1. **Minute gate.** `BotGossip.maybeStep` `:69` returns unless a minute has passed (`:70-73`); after 3 minutes it kicks `Reputation.loadAsync()` once (`:76-78`), then groups online bots by map (`:79-86`) and runs `step` under `BotPerformanceMonitor.recordSocialTick` (`:87-92`).
2. **Pick a listener.** Per teller: a bonded listener (one of the first four of `bondsOf`, `:184-190`) if there is one, else a random other bot on the same map; maps with one bot are skipped (`:104-120`).
3. **Shuffle and cap.** The round is a shuffled list of at most `TELLERS_PER_STEP` 20 (`:45`, `:122-127`).
4. **One read for the round.** `BotMemory.recentPassableFor(tellerIds, 5)` `:136` makes a single chunked query for all tellers' passable notes (`BotMemory.java:710-762`); notes about *bots* are excluded (`BotMemory.java:740-742`), so gossip is always about a player.
5. **Willingness.** `passChance(socializerShare) = min(0.6, 0.3*(0.5+2*share))` `:178-181`, rolled at `:143`.
6. **Circle opinion first.** `Reputation.spread(teller, listener, 1+2*rivalPull, now, rng)` `:147` picks a subject the teller's circles know (`Reputation.java:519-566`); a wary subject makes the listener take one small hearsay nudge through `warn` `:196-209`.
7. **World news.** `BotWorldGossip.pass(teller, listener, now, rng)` `:156` carries a boss kill, wedding or big drop along the same bond (`BotWorldGossip.java:328`).
8. **A note about a player.** A random passable note is drawn (`:158-162`); it is skipped when `BotAffinity.optedOut(aboutCharId)` (`:163`, i.e. `@botdrama off`).
9. **The transfer.** `BotMemory.hear(listener, note, now)` `BotMemory.java:823-835` rejects the note's own subject, a listener that is the note's teller, `hops > MAX_HOPS` 2 (`:305`) and a duplicate text, then stores it as `Source.HEARSAY`, `hops+1`.
10. **The nudge.** `BotAffinity.onHearsay` `:513` → `hearsayOf` `:520` → `HEARSAY_BAD`/`HEARSAY_GOOD` recorded through `AffinityRules.apply`, which caps the score at ±15 and trust/affection/resentment at ±0.15 and refuses once the tie already sits at the cap (`AffinityRules.java:137-143,155-160`). Hearsay alone never writes a first-hand note (`BotAffinity.java:480-482`).
11. **Saying it out loud.** `AffinityReactions.maybeMentionGossip` `:682` is the only spoken form: it needs a hearsay-worthy line, a 50% roll, 10 minutes since the teller's last line (`:688`), nobody on the map holding that character id (`:692-694`), a visible real player as the audience, and `@botdrama` on; it logs `GOSSIP <teller> to …` `:710-712`.

### Flow 3 — a bot's opinion of a player

1. A hook records an `AffinityEvent` (`AffinityHooks.java:72-287`), e.g. `onPickup` `:274` reports `DROP_STEAL` when a bot-owned drop younger than 45 s is taken by a non-party player.
2. `BotAffinity.record(int,int,…)` `:402-465` needs the player's `PlayerTies` already loaded (`:407-410`); otherwise the event is not counted at all.
3. Inside the player's lock: crossing to the persona, `AffinityRules.apply` `:121-167` (cooldown, `@botdrama` refusal of hostile events `:122`, hearsay caps), the tie put back, the cap `bound` dropping the weakest other tie, then the delete/save pair queued in order on the per-player write lane `:435-438`.
4. On a state change `onStateChange` `:467` picks the note (`NOTE_FRIEND`, `NOTE_NEMESIS`, or a RIVAL cause: `NOTE_KS`/`NOTE_STEAL`/`NOTE_DEFAME`/`NOTE_RUDE`/`NOTE_BEEF` `:44-50`) and writes it into memory through the dedupe-checked `MEMORY` seam `:490-497`.
5. `BotEpisodes.onAffinity` `:452`, `Reputation.onAffinity` `:453`, `LifeLearning.onAffinity` `:454`, `BotGuilds.noteAttention` for positive non-hearsay `:455-458`, and `SocialGate.playerStarted` for a provocation `:459-465`.
6. The reaction layer reads the state back: `AffinityReactions.onSight` `:158` picks friendly (`:248`: BUDDY <0.35, INVITE <0.5, HELP if a buff is wanted, else GREET), a side-eye (`:274`) or hostile (`:289`: DEFAME 25% at NEMESIS, KS 60% for a TrainingBot, else TAUNT), and `act` `:313` re-checks `SocialGate.allow` before doing anything.

## Data

| Relationship / score | Stored where | Written by | Decay | Read by |
|---|---|---|---|---|
| bot→player score (SMALLINT, -100..100), `Kind`, `Hostile`, `UpdatedAt` | `botaffinity`, PK `(CharacterId, BotId)` — `database/migrations/011-bot-affinity.sql:18-26` | `AffinityStore.save` (`BotAffinitySystem/AffinityStore.java:143`) on the per-player write lane `BotAffinity.java:435-438` | `AffinityRules.faded` `:64`: halves every 42 d friendly (`:59`) / 14 d grudge (`:55`); dropped when \|score\|<3 `:211` | `BotAffinity.state:199`, `score:215`, `friendsOf` callers, `RelationView` |
| bot→player axes (8 FLOATs), `Stage`, `HasAxes` | same row, `botaffinity` — `database/migrations/062-bot-relationship-axes.sql:34` | `AxisColumns.bind` (`BotAffinitySystem/AxisColumns.java:51-58`) | per-axis half-life `Axis.faded:81`; stage only cools, never rises with time (`AffinityRules.stageAt:223`) | `BotAffinity.axes:234`, `stage:252`, `RelationView` (FRIEND only, `RelationView.java:69-72`) |
| bot→bot score + `Kind`/`Hostile` + axes + `Stage` | `botbond`, PK `(BotId, OtherId)` — `database/migrations/029-bot-bonds.sql:21-29`, axes `062-bot-relationship-axes.sql:26` | `BondStore.write` (`BotAffinitySystem/BondStore.java:117-146`) from `BotBonds.flush:702` every 3 min | `BondRules.faded:43` (same 42 d / 14 d); dropped when faded at flush | `BotBonds.state:746`, `score:807`, `friend:819`, `bondsOf:829`, `friendsOf:851`, `crewOf:867` |
| crew membership | **derived, memory only** (`crewCache`, 1 min) | `BotBonds.crewOf:867-899` | cache TTL `:80` | `SocialGate` crew tier (`SocialGate.java:243-256`), `BotGossip.bondsOf:208` |
| `@botdrama off` | `botdrama(CharacterId PK, OptedOutAt)` — `011-bot-affinity.sql:29-34` | `BotAffinity.setOptedOut:544` via `toggleByPlayer:605` (10 s floor `:598`; purges gossip `:624`) | none (a flag) | `AffinityRules.state:81` forces NEUTRAL for hostile states; `BotGossip:163` |
| "started it" / grudge ledger | `botsocialopt` + `botstartedit` — `031-social-gate.sql:22,31`; seeded once from `botaffinity` (`:52-56`) | `SocialGate.playerStarted`, halving every 14 d (`SocialGate.java:183-191`) | 14 d half-life | `SocialGate.rules` crew/beef tiers `:250-267` |
| bot→player memories (≤10 per pair) | `botmemory` — `003-solomapling-bots.sql:96-109`; kinds/weight/tag `030-bot-memory-kinds.sql:23,30,37,44`; social columns `063-bot-memory-social.sql:25,32,39,46,53,60` | `BotMemory.remember:841`, `episode:875`, `hear:823`, `progress:1105`, `writeConversationNotes:1141` | age+idle sweep `expired:1209` (hearsay 21 d/7 d, heavy 120 d/30 d, light 45 d/14 d), prune every 6 h (`:302,1279`) | prompts (`BotConversation.java:878`), `recall:513`, `recentPassableFor:710` |
| circle opinion (marks SCAM/KS/GIFT/FAVOR/PQ) | **memory only**, rebuilt at boot from `botmemory` first-hand rows (`Reputation.java:660-665,667`) | `Reputation.onAffinity:299`, `onBond:307`, `onBondState:315` | 14 d grudge / 42 d favour half-life (`:110-111,139`); drama kept 6 h (`:119`) | `Reputation.spread:519`, `wary:414`, `known:372` |
| gossip stories (heard, word for word) | **memory only**: 4 per bot, 4096 holders, 8 h TTL (`BotGossip.java:523-527`) | `BotGossip.keep:605` from `BotConvo/BotTalk.java:1044`, `BotThreads.java:1199` | TTL 8 h; freshness weight `:701` | `BotGossip.heard:659`, `heardOf:671`, `GameSocialFlows.java:98` |
| buddy link | stock `buddies` table, bot side keyed by the bot character id — no migration (`BotBuddies.java:37-50`) | `BotBuddies.recordAccept:596`, `Rows.put`, `decide:522` | none; a refusal blocks the pair for a day (`:292`) | `BuddylistModifyHandler.java:108,113,219,226` |

## Switches

| Switch | Default | Where read | Live (docs/release/1.6.4.conf) |
|---|---|---|---|
| `-Dsolomapling.affinity` | true (only `false` turns it off) | `BotAffinity.java:98-100` | not set → on |
| `-Dsolomapling.affinity.hostility` | true | `BotAffinity.java:103-105` | not set → on |
| `-Dsolomapling.affinity.maxTiesPerPlayer` | 40 | `BotAffinity.java:107-109` | not set |
| `-Dsolomapling.affinity.<EVENT>` / `.cooldownSec` | per event | `AffinityEvent.java:62,66` | not set |
| `-Dsolomapling.affinity.friendAt/rivalAt/nemesisAt` | 25 / -25 / -65 | `AffinityRules.java:43,47,51` | not set |
| `-Dsolomapling.affinity.grudgeHalfLifeHours` | 336 | `AffinityRules.java:55` | not set |
| `-Dsolomapling.affinity.friendHalfLifeDays` | 42 | `AffinityRules.java:59` | not set |
| `-Dsolomapling.affinity.gossipLineMin` | 10 min | `AffinityReactions.java:688` | not set |
| `-Dsolomapling.affinity.toneMin` | 0.6 | `ChatTone.java:117` | not set |
| `-Dsolomapling.social.sideEyeChance` | 0.2 | `AffinityReactions.java:280-283` | not set |
| `-Dsolomapling.bonds` | **true** | `BotBonds.java:151-153` | not set → on |
| `-Dsolomapling.bonds.k` | 14, clamped 4..32 | `BotBonds.java:155-161` | not set |
| `-Dsolomapling.bonds.pairsPerMap` | 3, 0..10 | `BotBonds.java:180` | not set |
| `-Dslums.bonds.background` | true | `BotBonds.java:163-166` | not set → on |
| `-Dslums.bonds.background.maps` | 2, 0..20 | `BotBonds.java:168-174` | not set |
| `-Dslums.bonds.background.pairs` | 1 | `BotBonds.java:176-178` | not set |
| `-Dsolomapling.bonds.friendHalfLifeDays` / `.grudgeHalfLifeDays` | 42 / 14 | `BondRules.java:34,38` | not set |
| `-Dsolomapling.bonds.<EVENT>` / `.cooldownSec` | per event | `BondEvent.java:78,82` | not set |
| `-Dsolomapling.axes.<axis>.halfLifeDays` / `.negHalfLifeDays` | per axis | `Axis.java:49-66` | not set |
| `-Dsolomapling.axes.familiarity.floor` | 0.08 | `Axis.java:76-78` | not set |
| `-Dsolomapling.memory.cachePairs` | 16384 | `BotMemory.java:348` | not set |
| `-Dsolomapling.reputation` | true | `Reputation.java:225-227` | not set → on |
| `-Dsolomapling.chat.recognizeChance` / `recognizeAfterMin` / `recognizeEveryHours` | 0.5 / 30 / 6 | `BotRecognition.java:88-100`, props documented `:22-25` | not set |
| `-Dsolomapling.buddy.*` (`maxPlayerBuddies` 20, `baseAccept` 0.2, `knownBonus` 0.45, `talkBonus` 0.25, `partyBonus` 0.3, `refusalDays` 1, `offerMinLines` 3, `offerRetryDays` 7, `offerChance` 0.08, `afterPlayChance` 0.35, `acceptDelayMinSec/MaxSec` 10/180) | as listed | `BotBuddies.java:225,247,262-273,292,310,326,347,357,507-508` | not set |
| `-Dslums.crews` / `-Dslums.crews.max` | on / clamp 2..4 | `BotCrews.java:66-69,71-74` | `-Dslums.crews=true` in `UNIT_EDITS` (`docs/release/1.6.4.conf:271`) |
| `-Dslums.bonds` (`@bonds` view) | OFF | `RelationView.java:31` (`Boolean.getBoolean`) | **`-Dslums.bonds=true` in `UNIT_EDITS`** (`docs/release/1.6.4.conf:271`), though the prose at `:36-38` still says OFF by default |

TEST: unknown — `start-test.sh` was not reachable in this review.

## Invariants

- **No numbers reach a player.** Affinity numbers, kinds, hostile counts and stages stay server-side (`BotAffinity.java:21-36`); `@bonds` prints words only and filters to FRIEND (`RelationView.java:19-25,69-72`), which a unit test pins (`BotAffinityTest.thePromptNeverCarriesANumber`).
- **One bot's edge answers only for that bot.** `BotBonds.axes` `:760-762` documents and enforces it; nothing reads `otherId`'s edge to speak for `botId`.
- **Bots are never players here.** `record` refuses two bots on the player side (`BotAffinity.java:404-406`) and two non-bots on the bot side (`BotBonds.java:218-220`); the affinity load hook skips bot ids (`BotAffinity.java:119-121`).
- **Hearsay cannot create a relationship.** `HEARSAY_CAP` 15 is below `friendAt` 25, and the axis cap 0.15 keeps the stage below FRIEND's thresholds (`AffinityRules.java:25,30,137-160`); gossip never writes a first-hand note (`BotAffinity.java:480-482`). Test: `BotAffinityTest.hearsayNudgesButNeverMakesAState`.
- **A state needs history, not one moment.** FRIEND needs score ≥25 *and* kind ≥3; NEMESIS ≤-65 *and* hostile ≥5 (`AffinityRules.java:87,89`). Tests: `aFriendNeedsHistoryNotOneNiceMoment`, `oneKsIsNotARivalryButRepeatedKsIs`, `aNemesisNeedsALongHistory`.
- **A faded history starts over.** Under `FADED` 3 the counters are zeroed before the next event is applied (`AffinityRules.java:133-136`, `BondRules.java:125-128`). Test: `aFadedHistoryStartsOver`, `aFadedBondForgetsItsHistory`.
- **Bounded everywhere.** ≤14 bonds per bot (`BotBonds.java:155`), ≤40 ties per player (`BotAffinity.java:107`), ≤10 notes per pair (`BotMemory.java:248`), ≤2 gossip hops (`:305`), crew ≤6 (`BotBonds.java:77`), one defame per player per day through the fake row `DEFAME_MARK` (`AffinityStore.java:68,194`).
- **Cooldowns are per event per pair** and are the anti-spam mechanism (`AffinityEvent.java:66`, `BondRules.java:117-120`). Test: `cooldownsCountASpamOnce`, `cooldownsKeepASpammedEventToOne`.
- **`@botdrama off` makes hostile states unreadable and unwritable**: `state()` returns NEUTRAL for a hostile tie (`BotAffinity.java:210`), negative events are refused (`AffinityRules.java:122`), gossip about the player is skipped (`BotGossip.java:163`) and purged on the toggle (`BotAffinity.java:624-628`). Test: `dramaOffMakesEveryBotNeutral`.
- **All persistence is off the game thread**: affinity writes ride a per-player serial lane (`BotAffinity.java:435-438`), bonds a single writer (`BotBonds.java:697-702`), memory a striped lock plus a background prune (`BotMemory.java:322-329,1279`).

## Origin

| Part | Verdict | Evidence |
|---|---|---|
| Whole package | **custom** (SoloMapling-only; no upstream maplestory source has bot affinity) | oldest commits: `BotAffinity.java` `f35cecda6` 2026-10-21 "Bots like or dislike players: affinity, @botdrama and GM tools (ticket 58)"; `BotBonds.java` `8f0ea7361` 2026-10-25 "Give bots bounded bonds with other bots (S2, wip)"; `BotGossip.java` oldest `1512c08a6` 2026-10-19 (persistence fork merge), then `f35cecda6`; `BotBuddies.java` `84595d13d` 2026-10-21 (ticket 49); `BotRecognition.java` `ef4a88021` 2026-10-20 |
| Axes and stages | **custom, newest layer** | `database/migrations/062-bot-relationship-axes.sql` `f03304844` 2026-11-03 "Bots now hold eight-axis directed relationships, and unwatched bots form them too", renumbered `a226edfdf` 2026-11-03 |
| `botmemory`, `buddies` | mixed | `botmemory` table is custom (`003-solomapling-bots.sql:96-109`); the buddy rows reuse the **base** `buddies` table unchanged (`BotBuddies.java:37-50`) |

## Tests and QA

| Coverage | Where |
|---|---|
| 20 unit tests on player affinity (history, cooldowns, fade, hearsay, drama off, stage/grudge, persistence, defame-once, prompt has no numbers) | `src/test/java/soloMapling/ArtificialPlayer/BotAffinitySystem/BotAffinityTest.java` |
| 23 unit tests on bonds (friend needs 3 kind things, 6-week/2-week halves, one push on crossing, cap drops the weakest, sampler caps, party time, crew = mutual 2 hops ≤6, `!botwhy` text) | `BotAffinitySystem/BotBondsTest.java` |
| Axes/stage arithmetic, migration fallbacks, simulated days, crew hand-off/settle, drama toggle, force taunt, KS-spree credit, relation axes, rude streak, gate integration, staging holds, stationed notice | `BotAffinitySystem/{BotBondsAxesTest,BotBondsMigrationTest,BotBondsSimulatedDaysTest,BotCrewsTest,BotCrewsHandOffTest,BotCrewsSettleTest,BotDramaToggleTest,ForceTauntTest,KsSpreeCreditTest,RelationAxesTest,RudeStreakTest,SocialGateAffinityTest,StagedFriendHoldTest,StationedNoticeTest,TieAxesTest,AxisColumnsTest,AffinityForgetBotTest,AffinitySafetyTest}.java` |
| Gossip: seams, a KS on a crew mate told with real names, victim tells it as "me", bad gossip in front of the player needs the gate, no-people topics, bond drama, world news, Bartle weighting, socializers pass more, reputation travels along a bond to another map, one read per round | `src/test/java/soloMapling/ArtificialPlayer/BotGossipTest.java` (11), `BotGossipStoriesTest.java` |
| Memory: kinds, social columns, legacy rows, bot-vs-player id separation, slots, bot episodes | `BotMemoryTest`, `BotMemoryKindsTest`, `BotMemorySocialTest`, `BotMemoryIndexMigrationTest` |
| Buddy rules: 25 tests (accept chance, gates, caps, refusal day, login load) | `src/test/java/soloMapling/ArtificialPlayer/BotBuddiesTest.java` |
| Recognition / partner memory / relationship card / buddy list | `BotRecognitionTest`, `BotConvo/PartnerMemoryTest`, `BotConvo/RelationshipCardTest`, `src/test/java/client/BuddyListBotTest.java` |
| Auto-QA `t-af-1` friend bot acts like a friend | `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:103` |
| `t-af-2` rival bot holds a grudge | `AutoQaCatalog.java:112` |
| `t-af-3` `@botdrama off` keeps bots friendly (pattern `AF3_DRAMA_ON` `:57-58`) | `AutoQaCatalog.java:123` |
| `t-bb-1`/`t-bb-2` buddy add and buddy chat (accept roll forced certain) | `AutoQaCatalog.java:175,185` |
| `t-bb-3` two partied bots become friends and a crew | `AutoQaCatalog.java:933` |
| `t-me-1` KPQ clear remembered, `t-me-2` scam is a grudge + SCAM memory + started-it | `AutoQaCatalog.java:961,967` |
| `t-gs-1` a KS on a bot becomes true gossip from its crew mate | `AutoQaCatalog.java:977` |
| `t-rv-5` two rival bots trash-talk, grounded in their bond | `AutoQaCatalog.java:1033` |
| `!qa` staging hooks (`BotBonds.stage`, `accelerateParty`, `accelerateUntilFriends`) | `qa/BondScenarios.java:93,100`, `qa/GossipScenarios.java:71,128`, `qa/BeefScenarios.java:93,178,271-272` |

**Not covered.** No test drives `BotWorldGossip` against a real database; no test asserts that `Reputation`'s boot rebuild sees a defame or a rude-chat grudge (see Known issues); nothing exercises `AxisColumns.Availability` re-probing after 062 is applied while the server is up (`AxisColumns.java:105-130`); `ChatTone` has no test of its own (only through `AffinityReactions.judgeTone`); `BotCrews.led`'s pull has a hand-off test but no live-scale test.

## Known issues

- **b276** (S4, QA-tool defect): a bot's map-scoped PLAN/ROUTE lines never reach the GM's QA feed. The same `QaLog.map` scope carries every `AFFINITY EVENT` and `RUDE-SPREAD` line (`BotAffinity.java:443`, `RudeStreak.java:127-131`), so a watched-character feed loses them the same way.
- **b284** (S3, QA-tool defect): auto-QA at 10 lanes gives false failures (scheduler key declarations miss shared state). `t-af-*`/`t-gs-1`/`t-bb-3` are `laneOnly()` checks that stage and forget global bot state (`BotBonds.forgetBot`, `SocialGate.forgetBot` in `qa/GossipScenarios.java:60-66`), so they are exposed to it.
- **b240** (S3): regular bots say they're grinding while standing in town — the copy a bot says about itself, adjacent to this subsystem's chat layer but not an affinity bug.
- **b232, b225, b241, b285, b287** are listed in `/root/work/STATUS-1.6.4.md` but belong to followers, KPQ, trades and the scroll trader; nothing in this subsystem.
- **NEW-FINDING:** `docs/release/1.6.4.conf:36-38` still says `-Dslums.bonds=true` is "OFF by default … TEST first, owner turns on", while `UNIT_EDITS` at `:271` already ships it on. The doc-drift guard `src/test/java/soloMapling/DocSwitchDriftTest.java` only watches the 1.6.2 diagnostics, so nothing catches this.
- **NEW-FINDING:** `Reputation.LOAD` (`src/main/java/soloMapling/ArtificialPlayer/Reputation.java:660-665`) rebuilds boot-time marks from `Kind IN ('SCAM','GIFT','FAVOR','PQ','BOSS') OR Note = NOTE_KS`. A defame, a stolen drop or a rude-chat grudge writes `NOTE_DEFAME`/`NOTE_STEAL`/`NOTE_RUDE` (`BotAffinity.java:47-48`) and is counted live at `:299` but lost at the next restart, so a nemesis made by defame stops being "wary" for its circle after a reboot.
- **NEW-FINDING:** `AffinityEvent.APOLOGY` has `delta 30` and `cooldown 0` (`BotAffinitySystem/AffinityEvent.java:24`), and FRIEND needs only `score ≥ 25` with `kind ≥ 3` (`AffinityRules.java:87`). Three apology lines in a row therefore flip a stranger to FRIEND with no time cost; the cooldown test (`BotAffinityTest.cooldownsCountASpamOnce`) covers KS, not APOLOGY.
- **NEW-FINDING:** the minute gossip round (`BotGossip.step:95-176`) passes notes along bonds without consulting `SocialGate.Act.GOSSIP`, while the story path does consult it (`BotGossip.java:344,695` via the `gossipInFront` seam `:61`). Note passing is memory-only, so this may be deliberate, but `@quiet bots` and `@botdrama off` only stop the *spoken* form (`AffinityReactions.java:682-712`), not the memory transfer.
- **NEW-FINDING:** `BotGossip.warn` (`BotGossip.java:196-209`) always nudges the listener with `NOTE_KS` whatever the subject actually did; a scam and a KS produce the same hearsay record, so the listener's score is indistinguishable and `BotMemory` never learns which (`hearsayOf` maps both to `HEARSAY_BAD`, `BotAffinity.java:520-538`).
- **NEW-FINDING:** `BotAffinity.record` silently drops every event for a player whose ties are not loaded (`BotAffinity.java:407-410`, comment "they just logged in"). The only load triggers are map entry (`MapleMap.java:2856`) and login (`SocialInitiative.java:325`), so an event that lands between login and the first map add is lost with no counter to notice it.
- **NEW-FINDING:** `AxisColumns.Availability` caches a "no" for 10 minutes (`BotAffinitySystem/AxisColumns.java:37,99-130`). Between applying 062 and the next probe, bond writes go through the legacy `UPSERT` and the new axes of those rows are written as zeros.

## State

**Working**, with two caveats that keep it short of complete:

- Evidence it works: 20 + 23 affinity/bond unit tests plus 11 gossip tests pass in the unit suite; auto-QA `t-af-1..3`, `t-bb-1..3`, `t-me-1..2`, `t-gs-1`, `t-rv-5` exist and are wired into the catalog (`AutoQaCatalog.java:103-135,175-190,933,961-990,1033`); bonds are ON for live (`docs/release/1.6.4.conf:271`); the owner answered "Bonds: ON for live, but fix the gap first" (`/root/work/STATUS-1.6.4.md:12`).
- Caveat 1: `@bonds` (`RelationView`) is a read-only view of the *player's* relationships and is the part the owner wants fixed (alts that are out must show). Its switch default contradicts the shipped flag (see Known issues).
- Caveat 2: the player-facing score write path is conditioned on a load that only two hooks trigger (`BotAffinity.java:407-410`), and the boot rebuild of `Reputation` loses non-KS/non-SCAM grudges (see Known issues), so a restart quietly changes what bots remember about grudges.

## Open questions

- Was `BotGossip`'s minute round deliberately exempted from `SocialGate.Act.GOSSIP`, or is the gate meant to apply to memory transfer too? No comment or test says which.
- Should `Reputation.LOAD` cover every negative `FEELING` note (`NOTE_DEFAME`, `NOTE_RUDE`, `NOTE_NEMESIS`) instead of only `NOTE_KS`? Nothing records the intent.
- Is `APOLOGY`'s zero cooldown intended to let a real player repair a grudge instantly? `AffinityRules.apply` refuses `delta == 0` but has no daily cap for a positive event.
- `-Dslums.bonds` appears twice with different meanings: `RelationView.enabled()` (`Boolean.getBoolean`) for the `@bonds` view and `BotBonds.enabled()` (`solomapling.bonds`) for the bond engine. Is the near-identical naming deliberate, or should the view have its own flag?
- Which live number does `t-bb-3`'s `accelerateUntilFriends(a, b, 240)` stand in for (`BotBonds.java:1073`)? The QA path replaces real party time with synthetic ticks, and no card records how long a comparable live pair actually takes.
