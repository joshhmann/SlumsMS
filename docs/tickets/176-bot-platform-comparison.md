# 176: Adopt from four upstream bot platforms, in the owner's order

Requested by the owner, 2026-11-01, after a read-only architecture review of
[Incluwin/BotStory](https://github.com/Incluwin/BotStory), [nutnnut/Cosmic](https://github.com/nutnnut/Cosmic),
[MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling) and
[Kiwiism/cosmic_agents](https://github.com/Kiwiism/cosmic_agents).

Target: not a release gate, and it expands nothing in 1.6.3. It records an owner-ordered adoption sequence over these
four platforms, the evidence behind each item, and what each must satisfy first. Server Dev owns selection; status
belongs in the release book and exported STATUS file, not here.

**Note (Server Dev, 2026-11-01 13:4x UTC):** this review read `1c5952fbe`. Since then `release-1.6.3` gained the b269
rounds 2-3 (`1225800eb`, `bc8998fbd`: refused bot writes and departures are retried or kept, never dropped) and b272
(`edd0b81ef`: login reads wait on a platform thread, a refused load turns the login away). Item A.1 below still holds
as a visibility item: what still gives up after its retries should be counted per site and shown.

## Goal

Four bounded work streams, ordered by need not interest: what loses data, what costs capacity, what bots cannot do,
what reads as scripted.

## Review basis and limits

- SlumsMS `release-1.6.3` **`1c5952fbe97dbe71c6392843dc47ca3513a71be8`**; BotStory **`81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`**; nutnnut/Cosmic **`b684bf78588d50512bcd62db9bb7cb85e4f73829`**; SoloMapling **`47b61b5381118ebdc1064ddbd0b1c32f75778871`**; cosmic_agents **`3c7e094357329932734a860e5a831d57add27a00`**.
- Local checkouts `/root/work/research/{BotStory,Cosmic,SoloMapling,cosmic_agents}`, plus the pre-existing `/root/work/research/{BotStory-qa-review,cosmic-agents-qa-review}` (the latter at `3c7e094`, the sha [ticket 170](170-cosmic-agents-qa-reference-adoption.md) already cites).
- Source, documentation and selected tests were read only. **No upstream server, build or test suite was run**, so any coverage count an upstream README states is an upstream claim, not SlumsMS proof. The report and per-repo studies live at `/root/work/research/COMPARISON.md` and `/root/work/research/findings/`, cited by path as [ticket 169](169-botstory-qa-reference-adoption.md) and 170 cite their review directories.
- Lineage: SoloMapling `47b61b53` is the bot foundation we run on and already carried nutnnut's `GCMoveSystem`, so that stack reached us before any direct port. BotStory is the only repo fusing all three upstreams — best reference for a fused stack, worst to port from.

## Decisions recorded

Owner direction, 2026-11-01: **A correctness debt, then B cost headroom, then C capability coverage, then D realism
instrumentation** — that is the execution order. It supersedes the report's §9 "best first" ranking for planning; that
ranking stays an estimate of effort and value, not a schedule.

Open sub-decision, not decided here: for A, whether the first deliverable is **visibility** (count and attribute
every lost write) or **recoverability** (bounded retry). The report recommends visibility and rejects retry against a
starved pool specifically, because that is the b269 shape and would re-create the livelock the gate exists to prevent.

## A. Correctness debt (first)

1. **Write-loss visibility.** `BotDbGate.acquire` (`BotDbGate.java:239-273`) increments `refusals`, warns at most
   once per 60 s (`OVERRUN_WARNING_INTERVAL_MS`), then throws; `BotMemory.java:1036-1043` catches the `SQLException`
   and logs at DEBUG, so a refused insert leaves the note in memory only and dies with the process. Adapt: per-site,
   per-table, attributable loss counts visible to QA and to the dashboard, which already consumes `BotDbGate.stats()`
   (`LiveSnapshot.java:34`, `ServerSources.java:171`), reconciled against `BotStore.saveAll`/`settleAndSaveAll` so a
   refusal is distinguishable from a write that never happened. **No retry queue.** Acceptance: a forced refusal
   yields a counted, attributed record a focused test asserts.
2. **The six writes outside the gate.** `BotRebirth.record`/`.unrecord` (`log.warn`), `FamilyQa` dissolve (two deletes
   plus an update, error returned to the caller), `EconomyCounterPersistence.save`/`.pruneBefore` (warn once per
   process, then DEBUG). Decide per site whether to gate it; `BotRebirth` is permanent progression, not a cache note.
   Measured shape, so scope is not re-litigated: 14 classes / 24 direct pool sites across `soloMapling/ArtificialPlayer/**`
   and `Economy/**`, 19 of them SELECTs. Acceptance: each site gated or documented as intentional, failure mode stated.
3. **Selection thrash.** Adopt lease-plus-hysteresis — cosmic_agents `SHARED_SELECTION_LEASE_MS`,
   `REPLAN_SWITCH_MARGIN_COST`; nutnnut `MAP_SWITCH_HYSTERESIS=1.3` — and BotStory's soft-then-hard ranking
   (`rankingMode: ranked-soft-capacity-then-hard-capacity`, `preserveCurrentMapWhenEligible`,
   `currentMapMaximumRank=4`) against the live occupancy structure: `TrainingMapChooser.BOTS_PER_MAP` (`:57`),
   `reserve` (`:271`), the `BotMapCap` exclusion. Acceptance: a level band does not trade maps under contention, and a
   bot merely rank-4 in place is not moved.
4. **Exchange invariants as durable data.** cosmic_agents' `JdbcEconomyInvariantAuditor` asserts six CRITICAL
   invariants (unbalanced event, negative holding, listing/lot mismatch, open-listing escrow mismatch, orphan
   transaction listing, quarantined receipt) as persisted rows, not log lines. Ours throws on first break from
   `OrderBook.check()` (`OrderBook.java:386-425`, tests and recovery only). Acceptance: queryable after a run, in CI.

## B. Cost shape and observation granularity (second)

**The premise "100 ms for every bot" is false and must not be acted on.** 100 ms is the wheel's scan period
(`BotTickService.java:34`, scheduled `:166-167`); `drive()` (`:237-256`) dispatches only entries with `nextDueMs <=
now`, CAS-guards `ticking`, reschedules `due = now + e.periodMs * throttleFactor` (`:290-291`). Per-bot cadence is
`2000 + random.nextInt(4000)` (`BotSM.java:366`, `:478-480`), and the tiers already exist: low/unobserved `9000 +
nextInt(3000)` (`BotSM.java:461-463`), training-GRIND `60_000 + nextInt(60_000)` (`TrainingBot.java:255-260`),
follower 750 / 200 in FIGHT (`FollowerBot.java:78`), social 30 s (`SocialBot.java:180`), town crowd 2000
(`InvasionTownCrowd.java:197,405`), governor stretch ×1..×4 (`BotTickService.java:75,263-281`), movement
`AI_TICK_MS=100`/`UNOBSERVED_TICK_MS=250`/`UNOBSERVED_IDLE_TICK_MS=1000` (`GCMovementDriver.java:35,40,46`, applied
`:101-123`), observability already a scheduler concern in `BotSM.checkPrioritySpeed` (`:416-435`). The normal path
performs **0 DB touches per bot per tick**.

Only the second half is new behaviour. (a) **Instrument the ladder.** `LodMetrics.stats()` (`LodMetrics.java:33-84`)
buckets dynamic bots into FULL/HALO/dwell/coarse per map and `BotTickService.throttleFactor()` exposes the governor
(`GOVERNOR_WINDOW_MS 5000`, `LAG_HI_MS 1000`, `LAG_LO_MS 300`, `FACTOR_MAX 4.0`); missing is CPU attribution — which
bots at 9–12 s and 60–120 s still burn time, and what the ×1..×4 stretch absorbs. (b) **Sharpen `observed`.** It is
per-map: `ObserverTracker` polls every `POLL_MS = 1000`, promotes immediately, demotes only past `DWELL_MS = 4000`,
and warms neighbours within `HALO_PORTAL_RADIUS_PX = 350` of the portal — map-level with a proximity halo, not
per-viewer. Go capability by capability, behind a provable cannot-mutate check the way cosmic_agents gates its 5000 ms
tier; `CoarseExecutor.advance(plan, planStartedAtMs, nowMs)` (`CoarseExecutor.java:29`), position as a pure function
of wall clock, is the strong form, and `BotTransitionBoundary` lists what must survive a tier flip (DURABLE) versus be
rebuilt (RECONSTRUCTIBLE). Any change sits behind a switch defaulting OFF. Acceptance: a bounded TEST run reports the
ladder's measured saving; any granularity change is proven not to skip a mutation before it is enabled.

## C. Capability coverage (third)

Lead with the largest gap: **boss runs do not fight.** Ours is player-observable and rewards — `gainGP`, guild chat,
megaphone, exchange news, staging on a real channel map, players able to join a crew member's party — but
`GuildBossRuns.java:552` decides the outcome with `GuildRules.runClears(avgLevel, boss, rng.nextDouble())` while the
bots are logged out for `GuildRules.minutes("runMinutes", 25)` (`:264-265`; QA compresses to 2 min), and loot is named
only (`results()`, `:536-573`). Upstream fights: BotStory's Zakum/Balrog/CWKPQ/GPQ expeditions share a per-run death
budget; cosmic_agents runs an easy-Balrog lobby (`AgentExpeditionLobbyService`, `AgentEasyBalrogScenario`). Our wrapper
is the state machine a real encounter plugs into — smaller than it looks, not a reason to keep the roll. Descending:

- **Generalize scripted quests.** `BotQuestScript.java:16-70` runs a quest's own JS in a fresh engine with a `BotQm`
  (`QuestActionManager` whose player is the bot), live for Temple of Time's fifteen ids (`TempleOfTime.java:303,325,
  354,371,378`; 3507 / 3514 / 3523-3539, behind `-Dslums.temple.enabled=true` at `:44`). Coverage looks narrow because
  of the picker: `BotQuestIndex.java:25-26`,`:125` exclude scripted and timed quests, and `BotQuests.java:65`/
  `:99-100`/`:279` cap at 6/day. BotStory's `CosmicHeadlessQuestScriptGateway` with `MAX_SCRIPT_ADVANCES` is the
  reference for generalizing one hand-wired chain, the script still deciding.
- **Useful-DPS attack-plan scoring** (`AgentAttackPlanScoringPolicy:19-75`): expected damage from the shared formula,
  capped at target HP, preferring guaranteed kills, tie-broken deterministically. Our damage path already holds the
  shared formula, so the exposure is the plan choice only.
- **Objective interrupt stack with per-transition checkpoints** (`AgentObjectiveKernel.suspendFor`/
  `completeAndResume` plus a checkpoint on a single-thread persistence lane): resupply preempts, quest resumes.

Acceptance: each item names the behaviour it changes and proves failure-before, behind a switch defaulting OFF; the
boss item also needs a bounded TEST run in which a fight is observable.

## D. Realism instrumentation (last)

- **Chat burst ceilings and likeness measurement.** BotStory `ChatterEngine`: `SELF_COOLDOWN_MS = 15_000`,
  `CHATTER_TOWN_LINES_PER_MINUTE: 18`, `CHATTER_RECENT_MEMORY: 12`, `TellAudit` for how bot-like output reads, and
  per-feature `LIVE`/`SHADOW` judge modes (`AGENT_TYPESAFE_CHAT_INTENT_MODE: LIVE`, `..._TRADE_REPLY_MODE: SHADOW`).
- **Tiered spend guardrails** (nutnnut): `AMMO_RESERVE_MESO 10k`, `POT_SPEND_MIN_MESO 20k`, `TAXI_MIN_MESO 50k`,
  `AMMO_BUY_FLOOR_MESO 1k` no-deadlock floor, `HP_REST_ENTER 0.5` / `HP_REST_EXIT 0.85` hysteresis. Realism only.
- **Map-wide crowd fairness** (`AgentMapActivityPolicy`: slots = `ceil(peers × 70% / 100)`, capped by live mobs,
  epoch rotation, resting agents sit rather than teleport).
- **Seeded, replayable behaviour PRNG** (`floorMod(mix(seed ^ salt ^ ++decisionSequence), 100)`) — the precondition for a harness that means anything.

We already run two relationship stores (`BotAffinitySystem`/`AffinityStore`, the `botdrama` table, migration 011); BotStory carries three and says so. **Do not add a third.**

## Do not import

Config as a second codebase (6,924 / 4,402 mandatory keys, missing key throws at first use, no Java fallback);
`XRuntime`→`XService`→`XState` triads as the default decomposition; ~70 boolean flags on the combat/movement hot path;
zero map locking with pointwise concurrency defences; unauthenticated admin surfaces (`/api/command`, `/api/spawnbot`);
`id > 20000` identity predicates; one shared template row for every bot; self-creating tables outside the migration
pipeline; committed `wz/` trees and test debris; several stores for one concept.

## Gates that need a measurement

1. What does the tick ladder actually save, and how much more would finer observation recover for the DB pool (b269)? — gates B.
2. Does the useful-DPS scorer close the DK DPS gap (b087), or is the gap in the formula? — gates C's scorer.
3. Are our graphs keyed by movement profile (`speed`,`jump`) at `GRAPH_VERSION` 70, or by map only? — gates any navigation work.
4. Does our chat path have a per-map lines/minute ceiling, or only per-bot gaps? — gates D's burst work.
5. How many quests would general scripted support unlock versus the 6/day picker plus Temple of Time? — gates C's quest item.
6. Is our Exchange invariant-checked as durable data anywhere, or only in tests that can stop being run? — gates A.4.
7. How often does `BotDbGate.acquire` refuse in practice, and how much state is lost at peak? — gates A.1's priority.

## Work order and completion

1. Execute A, then B, then C, then D. Nothing here enters 1.6.3 without the lane's normal gates; nothing may become a
   release blocker for it.
2. Per item: fail-before evidence, a switch defaulting OFF, verification on TEST only, TEST accessed read-only, no
   live access, no economy change unless the item is explicitly the economy item (A.4).
3. Hand back branch, commits, tests, TEST evidence, limits. Upstream behaviour seen in source is not evidence ours works.
4. Record unselected work as deferred, never as prose-hidden follow-ups. One landed section is not an implemented catalog.

For any copied source, pin and credit the upstream commit in `docs/PROVENANCE.md` under the existing porting rules,
function by function, AGPL-3.0. Creating this ticket authorises no merge, deployment, live access or feature adoption.
