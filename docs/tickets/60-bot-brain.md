# Ticket 60: the bot brain (weighted daily-life decisions)

Status: designed 2026-10-23 with the owner (grill), planned as the **1.6.0 headline**, alongside fishing, medals and
the daily dungeon, which become options the brain chooses. First local implementation checkpoint by Codex on
2026-10-24: pure scorer and opt-in read-only GM preview. Checkpoint 2 adds provisional profiles/goals, memory-only
context and a guarded restock-trip adapter. Checkpoint 3 starts explicitly queued restock requests from each bot's
normal update, with duplicate/old-request protection. Automatic scoring-and-action selection remains off.
Work branch: `160-bot-brain`.

## Finish-the-ticket pass

The owner asked for the remaining ticket in one pass, without stopping after each building block. Finish the
automatic decisions, existing-system action adapters, saved goals and bounded personality changes, player-intent
and truthful chat links, then run the full tests and feature QA on a separate test copy. Keep future fishing,
medal and dungeon options unavailable. Do not invent a new NPC-selling income path or change economy limits.
The separate copy must use test data, spare ports and the documented scratch CPU/memory limits; DeepSeek's shared
TEST instance stays running. Record any genuinely unavailable action with its reason instead of pretending it ran.

## Why

Bots today pick their day from separate systems (the population director, grinder logic, town/social roles,
habits, commitments). Each works, but nothing weighs them against each other, so a bot can't decide "my friend
needs help, I'll go" or "I'm out of pots, brb", and its chat can promise things it never does. The owner's goal is
that **bots do what they say**: chat follows decisions, and decisions follow the bot's situation and personality.
The look to aim for is old-school Henesys: a hangout for every level, high levels dropping into low maps to show off
or help friends.

## Decisions (owner, 2026-10-23)

| Topic | Decision |
|---|---|
| Scope | **Daily life:** where a bot goes and what it does between fights: grind here / move maps, restock, sell, hang out in town, help a friend, show off, party quest, FM, break, log off. Combat stays as tuned in 1.5.0. |
| Personality | **Archetypes plus a random blend per bot:** grinder, socialite, show-off, helper, merchant, explorer, slacker. Each bot has a recognisable vibe but no two are alike. |
| Drift | **Slowly, with life events:** a job advance, marriage, a guild war, a big drop or boom nudge the weights a little. |
| Player pull | **Strong for friends, light for strangers, negative for rivals** (affinity). Respects `@botdrama off`. |
| Decide model | **Reads players only:** it interprets player chat (help asks, LFG, tone) and feeds scores. The brain itself is weighted maths with no model calls. |
| Chat model | Voices what was chosen. The say-do rule: a line that claims an action is only said when the action happens. |
| Visibility | `!botwhy <name>` shows needs, the top 3 options with scores and the reason; every decision is logged for auto-QA. Players never see numbers. |
| Pacing | **Human-like stickiness:** grind sessions of 20-60 min and town hangouts of 5-20 min, unless an interrupt fires. |
| Interrupts | A friend asks or a party with a friend forms; out of pots or bag full ("brb pots", then a real trip); world events, bosses and guild wars; a rival shows up (respecting `@botdrama off`). |
| Goals | **1-2 simple goals per bot:** the next job advance, a gear upgrade, saving for an item, a medal. They bias choices and give chat something real to talk about. |
| Henesys mix | **Old-school:** lots of level 1-30, a solid group of 30-70 selling and chatting, a few 70+ showing off; high levels sometimes visit low maps to help or flex. |

## Shape

- **Needs** (0-1, rising over time or with events): supplies, bag space, boredom (time on the same activity),
  social (time since talking or partying), fatigue (session length, time of day), progress (EXP and gear behind the
  bot's goal).
- **Context:** level vs the map, gear tier, friends online and where, party and PQ openings, world events, rivals
  nearby, the hour (the population curve), the channel and the map cap.
- **Options:** each option scores `sum(need × weight) + context bonuses + personality weights + small noise`. The
  current activity gets a stickiness bonus that decays over its expected length. An interrupt can only win if it
  beats the current activity by a margin.
- **Execution:** the existing systems do the work (grind paths, travel, BotResupply/BotShopping, social roles, PQ
  and party code, the director's logout). The brain only chooses.
- **Chat:** the chosen option and its reason go into the conversation card ("heading to Ant Tunnel to grind for 2nd
  job"); ambient lines with an action claim are only picked when that option was chosen.
- **Cost:** a re-score is a few dozen multiplications per bot; bots re-score on interrupts and when their activity
  ends, not every tick. It must stay cheap at 3,000 bots.

## Testing

- **Unit:** scoring is pure functions. Personas get golden tests (a socialite with friends in Henesys goes there; a
  grinder out of pots restocks; a helper's friend asks for help and it goes).
- **`!qa` scenarios:** stage a need or event and assert the decision. Auto-QA reads `!botwhy`-style decision logs, the
  say-do metric, and the Henesys level mix at peak (target bands per the owner's mix).
- **Soak:** decisions per minute and CPU at 3,000 bots.

## Open questions for build time

- The exact archetype list and blend ranges, tuned against live data from 1.5.0.
- How goals are seeded for the existing roster (from current level and gear).
- Fishing, medals and the daily dungeon: each needs its option definition when it lands.

## Implementation plan (Codex, 2026-10-24)

1. **Pure decision core first.** Add a separate `soloMapling.ArtificialPlayer.BotLifeSystem` package for needs,
   options, context, score breakdowns and a deterministic picker. The existing `BotBrainSystem` is the chat/model
   backend; it remains separate. Inject the clock, noise and all weights into the core. No model calls, database
   writes, purchases or movement in the scorer. Test fixtures may use the seven archetypes already listed above;
   they are not approval of production blend ranges.
2. **Read-only adapters and explanations.** Inspect `BotPopulationDirector`, `BotRoster`, `BotCommitments`,
   `BotCombatSystem/BotHabits`, `BotGrindSystem/GrindBrain` and `BotSupplySystem/BotResupply`/`BotShopping`.
   Build a bounded snapshot from existing state and show top-three choices with reasons. Add/wire the GM-only
   `!botwhy` command and decision logs. Begin in an opt-in shadow mode that cannot change a bot's activity.
3. **Execution in small steps.** Hand chosen activities to existing systems, preserving combat tuning, map caps,
   player-facing commitments, supply costs and economy caps. Narrate an action only after its executor accepts it.
   Re-score on interrupts and activity completion; do not add per-tick model calls or roster-wide map scans.
4. **Goals and drift after decisions below.** Derive goals from existing roster state only once seeding rules and
   production personality ranges are settled. Reuse persistence where possible; any necessary migration follows
   AGENTS.md's idempotent apply/rollback requirements. Fishing, medals and dungeon options stay unavailable until
   their systems and owner-approved rewards exist.

**Tests/checkpoints:** deterministic scoring and ties; stickiness/interrupt margins; socialite/friend, empty-potion
and helper scenarios; commitment refusal and executor failure; chat never claiming a rejected action; three-thousand
bot CPU/decision-rate measurements. Add staged `!qa`/auto-QA checks before enabling execution. Run the full offline
suite before a handoff, and follow the release runbook for any coordinated test deployment.

**Coordination:** DeepSeek owns the 1.5.1 bug-fix pass. This branch starts from `origin/release-1.5.0`, as prompt 7
requires; merge forward the current 1.5.1 fixes before runtime integration so they are not lost. Never merge 1.6.0
features back into `release-1.5.1`. Do not replace or restart DeepSeek's shared test build for feature work without
coordinating the checkpoint. Keep early work local; plan an isolated test instance if concurrent runtime testing is
needed, within the host's test resource caps. Live and the owner's desktop remain under AGENTS.md's restrictions.

**Owner decision on continuation:** provisional blends and goal seeding from current level/gear are approved for
the first test version (checkpoint 2 below). Final production tuning and event-driven drift amounts remain open;
they do not block the approved provisional implementation.
Fishing reward items/rarities, the medal list and chaos-boss difficulty remain separate owner decisions; this ticket
must not silently pick them. No live database reads are needed for the first slice.

## Checkpoint 1: scorer and inspection (2026-10-24)

**Next checkpoint approved by the owner:** use provisional blends and seed goals from each bot's current level
and gear for the first test version. Implement stable, versioned seven-archetype blends; derive job/gear goals
without granting anything; adapt loaded session, local affinity and activity context without lazy database reads;
show eligibility and full score breakdowns in the shadow inspector. Add an acceptance boundary that rechecks
commitments and only makes action facts available after an executor accepts. Keep automatic execution disabled
until its adapters and staged QA are ready. Document provisional constants and test the persona/context fixtures,
unknown/stale inputs, rejected/failed executors and say-do ordering. Do not replace DeepSeek's shared TEST build.

Implemented in `BotLifeSystem/LifeDecision.java`:

- Immutable needs, options and score breakdowns, with caller-supplied weights, context/personality bonuses, time,
  bounded noise, duration, stickiness and interrupt margin. No production profile defaults or random assignment.
- Deterministic tie ordering; unavailable options excluded; linearly decaying stickiness; a challenger must
  strictly exceed the interrupt margin. A commitment holds selection even if the current activity is unknown.
  Scores and final selection are separate so a high-scoring interrupt is never mistaken for an accepted action.
- Missing needs remain absent in explanations. Invalid/non-finite inputs, duplicate options and score overflow
  are rejected. There is no executor, chat integration, background job, migration or population-policy change.

`LifeInspection` reads one bot's Character directly, its existing role/state, HP potions, existing restock threshold,
fullest carried bag, trade/invite commitment and conversation status. It does not call `BotSupplies.needs()`:
that helper calls `shortage()`, which can clear the existing ammo-fallback tracking. It must not be used for a
read-only inspection. Inventory reads take existing inventory locks; the complete snapshot is best-effort across
systems, not an atomic world snapshot.

`!botwhy <online bot name>` is GM level 3, registered only with **QA enabled and
`-Dslums.botlife.preview=true`**, and rechecks that gate when invoked. It reports to the GM and logs
`[BotLifePreview]` lines. The flag is off by default; no config YAML changes are needed. The preview compares only
two explicitly labelled diagnostic candidates: HP-potion restocking pressure and fullest-bag selling pressure.
Empty HP potions maps to 1, the existing positive per-bot restock threshold maps to 0 (for an only-when-out bot,
one potion maps to 0); bag pressure is the occupied share of the fullest equip/use/setup/etc bag.
Both diagnostic weights are 1. These are **inspection calibration, not approved
production decision weights**. It reports MP/ammo, boredom, social, fatigue, progress, destinations and activity
duration as unknown. Shop reachability and sellable contents are unverified. It neither assigns personalities nor
pretends those two candidates are a complete top-three plan. Active conversations also hold this preview.

Verification: 19 new focused tests pass, including the three golden personas, timing/margin boundaries, commitment
holds, unavailable options, ties/noise, input validation, immutable inputs, unknown needs, inventory read-only
inspection and the command gate. A deterministic 3,000-input work-count test passes; this is not a CPU benchmark
or server soak. Full `mvn -q -o test`: **4,509 tests, 0 failures, 0 errors, 38 skipped** (398 report classes),
log `/tmp/160-bot-life-full.log`. Core commit `ba7833379`; inspector/command commit `c0ce7dbe6`.

The current `release-1.5.1` fixes through `2c7e5bf22` were merged forward before implementation. No shared TEST
deployment or restart was performed for this checkpoint. Before execution is enabled: finish state/context and
availability adapters, agree production blends and goal seeding, add executor/acceptance and say-do tests, stage
`!qa`/auto-QA scenarios, measure the 3,000-bot load, and use a coordinated or isolated TEST environment.

### Future coordinated GM check (not run yet)

1. Deploy this feature branch only at an agreed checkpoint or into an isolated test instance. Preserve the current
   1.5.1 TEST receipt and do not restart a running bug-pass QA session. Enable QA and the preview JVM property on
   that feature instance; do not add it to YAML.
2. As GM 3, run `!botwhy <online bot name>` using a bot in the same world (world 1 on TEST). Expect its role/state,
   HP potions and bag pressure, exactly two diagnostic candidate scores, the unknown-input list and PREVIEW ONLY.
   Check `[BotLifePreview]` log lines match the GM's output. A real player name must report no active bot.
3. Inspect a bot during an existing trade/invite or active conversation. Expect HOLD_COMMITMENT and no selected
   activity. Normal bot systems keep running; the inspector itself must not move the bot, consume/grant items,
   spend mesos or announce any action. Do not interpret normal movement as proof of a life-brain decision.
4. With the preview flag absent, verify `!botwhy` is unregistered. Ordinary player accounts must not access this
   GM 3 diagnostic. Real-client output, registry permissions and runtime log checks remain unverified until this
   checkpoint is deployed; the current evidence is unit tests only.

## Checkpoint 2: provisional profiles, context and acceptance (2026-10-24)

**Next work (owner request):** finish one-at-a-time activity execution. Give each TrainingBot a bounded request
slot and one active activity; consume requests only on its normal update. Reject duplicate, old, expired and
previous-instance requests; newer waiting decisions replace older waiting decisions. Recheck trades, invitations,
conversations and phase ownership at start. Use the existing restock routine from that update, remember the
accepted trip until return/cancellation, and close the slot on stop. Add concurrent/replay/lifecycle tests and a
staged QA check. Keep flags off by default and preserve the shared bug-fix TEST build. Owner updates should use
simple wording: one request at a time, no repeat starts, and queued work can still be refused.

The owner approved **provisional blends and level/gear goal seeding** for the first feature test version.

- `LifeProfile` v1 uses the character ID to choose one of all seven archetypes, with a dominant share of 55-70%.
  The remaining share is distributed among the other six using positive random weights, normalised once per
  derivation. Profiles are deterministic, do not reroll on inspection, and do not change the existing roster's
  personality columns. Grind duration is 20-60 minutes, town duration 5-20 minutes. Activity bias is 0.6 times the
  corresponding archetype share (restock gets half the grinder share; PQ averages grinder/socialite).
- `LifeGoals` derives at most two aspirations from current job stage and cached weapon requirements. Recognised
  Explorer/Cygnus/Aran stages point to their next 10/30/70/120 advance, including an overdue advance. A weapon
  at least one ten-level tier behind prompts a weapon-tier aspiration. Unknown gear creates no upgrade fact;
  custom fifth jobs and Evan do not get invented advance goals. A next-level fallback respects 120/200 caps.
  Goals do not grant anything, spend money or force equipment changes. They are recomputed aspirations for now,
  not persisted commitments. Required level is only a rough gear proxy: future execution must use the existing
  gear optimiser so a strong old weapon is not replaced by a worse one.
- `LifeObservation` reads existing HP/bag pressure, goal pressure, loaded session fatigue, TrainingBot phase age
  and local affinity. `BotRoster.loadedMember()` is explicitly memory-only; `member()` could lazily load the DB.
  Weapon requirements come only from an already initialised metadata cache. No shop lookup, new map load, model
  call or database read is performed. A local sample stops at 64 characters; hidden GMs and bots are excluded,
  unloaded affinity stays unknown, and per-player drama opt-outs remove hostile pull. Whole observations remain
  best-effort across independently changing systems, not an atomic world snapshot.
- TrainingBot publishes immutable phase-name/start-time telemetry at its existing transitions. It does not give
  the life engine ownership of the FSM. Travel/shopping and existing player, party, guild, scene and conversation
  commitments hold selection. Newborn/future/invalid session timestamps are unknown; sessions older than 24 h
  are treated as stale. MP/ammo pressure, time since social activity, distant friends, destinations, PQ/events,
  shop routes and sellable contents still need adapters.

The shadow policy uses **provisional**, explicit coefficients:

| Option | Need coefficients |
|---|---|
| Grind | progress 1.4, supplies -1.6, bag pressure -0.8, fatigue -0.5 |
| Restock / sell | supplies 3.0 / bag pressure 2.0 |
| Hang out / move maps | social 1.0 + boredom 0.4 / boredom 1.2 |
| Help / show off | social 0.3 / social 0.5 + boredom 0.2 |
| PQ / FM | progress 0.8 + social 0.5 / bag pressure 0.6 + boredom 0.3 |
| Break / logout | fatigue 1.7 + boredom 0.3 / fatigue 2.0 |

Friends add 0.6 each up to 1.2 to town options; strangers 0.04 each up to 0.2; rivals subtract 0.4 each up to 0.8
unless drama is off. A verified friend help request adds 1.5; no chat-intent hook is connected yet. Initial
stickiness is 0.8 and an interrupt must beat the current option by more than 0.25. Shadow noise is zero.
Eligibility is explicit: current grinding, current-town presence and the existing restock trip can be inspected;
unsupported destinations, selling, PQ, FM and logout are excluded by the observation adapter. A grind-break
candidate can be previewed but its executor is not installed. Preview eligibility is not execution support.

`!botwhy` now prints the provisional blend, derived goals, measured/unknown needs, local affinity counts,
top-three eligible options with component scores, held/selected outcome and unavailable options. It remains
QA + `slums.botlife.preview` gated and read-only. The earlier two-candidate inspector is the checkpoint 1 baseline.

`LifeAcceptance` separates selection from accepted work. Shadow/held/unchanged results do not execute; eligibility
is rechecked before starting, unsupported/rejected/failed/wrong-action receipts produce no action fact.
`RestockTripExecutor` additionally requires **QA and `-Dslums.botlife.execute=true`**, rechecks commitments,
conversation and the director's hold rules, and calls only TrainingBot's existing `requestRestockTrip()` hook.
Its receipt says the trip was requested and that purchase is unconfirmed. There is no narrator connection,
automatic scheduler or bot-tick integration. Future integration must serialise decisions through each activity's
owner, prevent replaying a receipt, and record completed actions separately from accepted requests.

`!qa run bl-1` / auto-QA `t-bl-1` exercise the actual provisional policy's socialite, empty-potion grinder and helper
fixtures plus rejected-executor/no-action-fact ordering. They run without spawning a bot or changing holdings.
This checks the feature jar's policy, not real travel, purchases, population mix or a server soak.

Focused verification: **23 new tests** plus the existing catalogue checks pass; prior checkpoint tests also pass.
New cases cover stable varied profiles across 3,000 IDs, goal stages/unknown gear, local affinity and drama opt-out,
bounded sampling, missing/future/stale sessions, real phase telemetry, cached gear lookup, all acceptance failure
paths and the restock adapter's gates/holds. Full `mvn -q -o test`: **4,532 tests, 0 failures, 0 errors, 38 skipped**,
404 report classes (`/tmp/160-life-v2-full.log`). Profiles/context commit `1fd3d5700`;
acceptance/restock adapter and staged QA commit `97c74c095`.

Local pure-policy microbenchmark on 187: 60,000 evaluations, 365.6 ms wall / 363.1 ms thread CPU, averaging
18.3 ms wall per 3,000 evaluations after warm-up. Profiles and synthetic contexts were prebuilt; all 11 options
were eligible. Runner `/tmp/LifeShadowBenchmark.java`, output `/tmp/160-life-v2-benchmark.log`.
This excludes context capture, scheduling, execution and real server load; it is **not a 3,000-bot server soak**.

No TEST deployment/restart or real-client verification for this checkpoint. Keep DeepSeek's shared TEST build.
Next: finish supply/destination/event adapters, persist goals and bounded event-driven drift, integrate serialised
activity acceptance and truthful chat facts, then coordinate a feature TEST checkpoint and measure real load.

## Checkpoint 3: one request at a time (2026-10-24)

The owner asked to finish the one-at-a-time activity work and use simpler explanations.
Queue commit `0f9ed4de5`; bot-update integration and staged QA `f51ad81f4`.

**What now happens:** another thread may send a request, but it cannot start a trip itself. The bot checks that
request during its normal update. If it is still free, it leaves grinding and starts the existing town trip. A
trade, invite, conversation or other commitment can still block the request before it starts. The bot remembers
the trip until it returns to grinding, is stopped, dies, changes channel or another event takes over.

`LifeActivityQueue` belongs to one TrainingBot instance, with one waiting request and one running activity. There
is no extra worker thread or global request history. A request carries a queue-issued number, its queue identity,
the current phase generation and a maximum 30-second waiting life. The newest waiting plan replaces an older
waiting plan; it never replaces work already running. Old/expired plans are discarded rather than held for an
unbounded time. A blocked/rejected/failed request is spent: a caller must make a fresh decision before trying again.

- Repeating the same request never calls the start hook or exposes its action fact twice. Numbers already handled
  stay handled without retaining a growing list of old requests.
- Changes of phase discard waiting and prepared-but-not-submitted plans. Another bot or a new bot instance cannot
  use an old instance's request. Stop permanently closes that instance's request slot.
- The start callback runs under the queue lock. Overlapping or nested drains cannot start twice. The concrete bot
  start method also requires that the current thread is inside its own queue's start callback.
- Only the running request's number may finish or cancel it. A late callback cannot clear a newer running activity.
  Stop clears both waiting and running work, including error and reentrant-callback cases.

TrainingBot checks its waiting slot after normal pause/death handling. It does not score every tick or scan the
world. `RestockTripExecutor` now calls `startLifeRestockTrip()` there, which rechecks the holds, leaves grinding and
enters GO_TOWN immediately. It no longer hands new life requests to the old cross-thread `restockCall` flag.
Existing chat-triggered restock behavior is unchanged. An accepted receipt now says **started a trip**, followed
by **purchase not yet confirmed**. Returning to GRIND ends the running request; it is not proof that the bot
reached a shop or bought anything. The existing travel/shop routine still owns that work.

`!botwhy` stays read-only and now also shows the waiting/running request numbers and the last ended request.
Starting new work still requires QA plus `-Dslums.botlife.execute=true`, and a request must actually be submitted.
The flag alone does not create decisions, and the life engine does not yet choose activities automatically or
speak about them in chat. Legacy conversation/wander behavior outside this new request path remains bug-pass work.

### Test-server check, prepared but not run

`!qa run bl-2` stages one temporary training bot on a map with mobs. It refuses before spawning anything if the
execute flag is off. When the bot is grinding, `!qa do restock` sends the same request twice; `!qa do repeat` retries
that same request again. The log must contain one `HABITS LIFE START activity=RESTOCK` and reject the retries as
duplicate or stale. `!qa stop` removes the fixture and closes its requests. The scenario takes an exclusive claim
so two copies cannot confuse the trip count. Its run log reports waiting/running state every two seconds.

Auto-QA `t-bl-2` performs these steps and checks exactly one start. The owner still needs to watch the trip in a
real client. With the flag off the auto-QA check skips for its missing prerequisite. This check does not prove a
purchase or a successful return; those need completion checks in the existing travel/shop routines.

Local tests cover overlapping starts, retries, newest-waiting behavior, expiration and backwards time, phase
changes, stale callbacks, errors, shutdown and old-instance requests. Real TrainingBot tests cover the actual
GRIND → GO_TOWN start, one movement stop/one action event, return to GRIND, direct-call refusal, flags, a conversation
or trade starting after submission, and stop cleanup. Scenario tests verify request reuse, refusal of a bad replay
and fixture cleanup. **20 new tests pass**. Full `mvn -q -o test`: **4,552 tests, 0 failures, 0 errors, 38 skipped**,
407 report classes (`/tmp/160-life-serial-full.log`).

No shared TEST deployment/restart, live operation, Discord post or real-client check was done for this work.
Next: coordinate a feature TEST run of `bl-2`, check the actual trip, and connect automatic decisions and truthful
chat only after the remaining context and completion checks are ready.
