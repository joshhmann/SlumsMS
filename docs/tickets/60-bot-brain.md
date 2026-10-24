# Ticket 60: the bot brain (weighted daily-life decisions)

Status: designed 2026-10-23 with the owner (grill), planned as the **1.6.0 headline**, alongside fishing, medals and
the daily dungeon, which become options the brain chooses. First local implementation checkpoint by Codex on
2026-10-24: pure scorer and opt-in read-only GM preview; activity execution is not implemented.
Work branch: `160-bot-brain`.

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

**Owner choices still open:** production archetype/blend bounds and event-driven drift amounts; initial one/two-goal
seeding rules for existing bots. Pure scoring with injected fixture values can proceed without deciding these.
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
