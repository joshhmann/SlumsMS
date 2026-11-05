# Decision memory and learning

## Purpose

Give every bot a personality that is *stable but movable*: a deterministic seed derived from its character id, a small
bounded drift on top of that seed learned from what the bot actually does, and a durable store so the drift survives a
logout, a bot-type swap and a restart.

| Layer | What it decides | File |
|---|---|---|
| Seed (never changes) | Bartle point, seven like-channels, grind/town durations | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeProfile.java:43 |
| Drift (learned, bounded ±0.15 per channel) | how far the bot has moved from its seed, and in what direction | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:25 |
| Learning taps (what may move the drift) | outcomes, affinity, bonds, events, PQ clears, sweeps | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:23 |
| Durability | one `botlifeprofile` row per roster slot | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:23 |
| Mode | which scripted bot type the character runs as (training / social / town) | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:24 |

Learning deliberately cannot override the hard limits: "Hard limits stay outside" — ContentGuard, PG-13, SocialGate
tiers, scam caps and the never-AI rule are not reachable from a bias
(src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:55-58).

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| Bot tick (2 s engine) | `TrainingBot` runs the brain after `LifeRuntime.enabled()` | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:643 |
| `LifeRuntime.tick` | 30 s decision cadence, refreshes then plans then decides | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:409 |
| Activity end | `LifeActivityQueue.onEnd` → `LifeRuntime.ended` → `LifeOutcomes.report` | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:703 |
| Population director | `LifeLearning.maybeStep(now)` once per director tick | src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:493 |
| Director tick | `TownPulse.refresh(online, peak)` | src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:502 |
| Hourly autosave | `BotRoster.recordSessions(bots, now, false)` (every `HOURS.toMillis(1)`) | src/main/java/net/server/task/CharacterAutosaverTask.java:78, scheduled at src/main/java/net/server/world/World.java:208 |
| Shutdown | `BotRoster.recordSessions(everyone, now, true)` after `BotStore.settleAndSaveAll` | src/main/java/net/server/Server.java:2117 |
| GM command `!botwhy` | `LifeLearning.describe` + last decision + plan | src/main/java/client/command/commands/gm3/BotWhyCommand.java:39 |
| Inspect view | `InspectViews` key `learning` | src/main/java/soloMapling/inspect/InspectViews.java:79 |
| `!qa bl-4` | `LifeProfileProof.run` (profile survives a social session + reload) | src/main/java/soloMapling/qa/GameQaWorld.java:1728 |
| `!qa lr-1` | `GameQaWorld.learningStage` (30 KS turn a plastic Socializer) | src/main/java/soloMapling/qa/GameQaWorld.java:1590 |
| Metrics file | `reportIfDue` writes `data/autoqa/bot-life.json` | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:883 |
| Mode switch | last act of each bot-type tick | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:670, SocialBot.java:219, TownWandererBot.java:162 |

## How it works

1. **Load.** `LifeMemoryStore.of(characterId)` builds the memory once per character for the run, preferring the
   `botlifeprofile` row over the legacy `ActivityState` copy by `savedAt`
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:77-119). A non-roster character gets a
   seeded memory that lives only for the run (:103-106).
2. **Seed.** `LifeMemory.reset` derives the Bartle point from `SocialProfile.forCharacter`, plasticity and pull-back
   from the slot, and the profile from `LifeProfile.fromSocial`
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:108-123). A saved seed always wins over
   today's formula (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:138-171).
3. **Refresh.** Every decision cycle the memory recomputes goals and the plan from the level/job/weapon facts
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:428-430).
4. **Decide.** `LifePolicy.options(context, memory.profile(), situation)` scores activities; personality is
   `profile.bias(activity) + bartle(situation.social(), activity) + plugins(...)`, plugin biases clamped to ±0.5
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePolicy.java:120-121, :173-183).
5. **Accept.** The selected activity is started only through `LifeAcceptance.tryStart`, which re-checks that the bot is
   still free, that the executor supports the activity, and that the executor returned a fact naming *that* activity
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeAcceptance.java:25-42). SHADOW, HELD, NO_CHANGE and
   UNSUPPORTED never reach an executor (same file).
6. **End.** `LifeRuntime.ended` turns a `COMPLETED` end into a true outcome, softens a satisfied soft plan fact, and
   reports `LifeOutcomes.report(botId, activity, success, now, end)` 
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:703-716). Softening marks the memory dirty
   (:708-711) so the next sweep persists it.
7. **Fan out.** `LifeOutcomes.report` walks listeners in a `CopyOnWriteArrayList`; a listener that throws is swallowed
   so "a learning hook must never break the bot that reported"
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeOutcomes.java:52-59).
8. **Learn — like channels.** `LifeLearning.onOutcome` uses `peek` (never a DB read) and `observe` moves each channel's
   expected-success rate by 10% of the surprise and the drift by `rate x weight x surprise`, capped at ±0.15
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:268-292; rate/limit at
   src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:641-655).
9. **Learn — Bartle point.** A success also emits one `Push` mapped per activity (`GRIND`→`GRIND`,
   `MOVE_MAP`→`NEW_MAP`, `HANG_OUT`→`CHAT`, …); errands (restock, sell, market, break, logout, cc) emit nothing
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:236-249). `apply` pulls the drift back
   first, checks the daily budget, then pushes (:184-206).
10. **Budget.** Per bot per UTC day: 6 of an event kind, 3 of a routine kind (`GRIND, CHAT, NEW_MAP, HELPED, FLEX,
    FISHED`), 16 in all; likes get their own 6-a-day budget under the `ch:` prefix
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:72-75, :79;
    src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:609-625).
11. **Pull back.** Every day the drift shrinks toward the seed by the bot's stored pull-back rate, capped at 60 days, so
    a single wild day cannot become a permanent personality
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:659-679).
12. **Cross.** `checkCrossing(0.05)` with hysteresis reports a `CrossingEvent` once per quadrant change; that fires
    `[BotLife] QUADRANT_CROSSED` and the listener chain
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:208-230;
    hysteresis at src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:693-707).
13. **Drama hook.** `MapleRootSolo` registers `VillainArc.LISTENER`, so a crossing into KILLER means 7 days of extra
    beef and a friend who may drift; a redemption means less beef
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:162-168;
    src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/VillainArc.java:17-37, :52-56). The arc map itself is
    memory only and dies with the process (src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/VillainArc.java:50).
14. **Sweep.** Every 5 minutes off-thread: pull-back and crossing check for every loaded memory, `TrendIndex.maybeSample`
    and `Fads.step` (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:388-405). Results go
    into `bot-life.json` under `learning`
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:908-910).
15. **Save.** The hourly roster sweep calls `LifeMemoryStore.prepare` per member, writes all dirty profiles in one
    UPSERT batch *before* the roster row, then `attach` removes the v1 `"life"` key from `ActivityState` once the table
    answered (src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:352, :359-372;
    src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:206-252).
16. **Next read.** The next decision reads the same in-memory object, so a learned profile is visible immediately; a
    restart re-reads the row and prefers it when `savedAt` is newer
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:111-116).
17. **Mode switch.** Once a minute, at the end of a bot-type tick, `ModeSwitch.maybeSwitch` scores training↔social/town
    from the same `LifeRuntime.DecisionSnapshot` the brain produced, refuses at any non-safe point, and swaps the
    bot type through `BotTypeManager.convertBotType`, keeping the character id so every keyed store survives
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:188-231, :258-275).
18. **Mode resume.** `BotRoster.resumeChoice` resumes the recorded type only when the brain is on, the mode switch is on
    and a resume is armed; otherwise the bot logs back in as a training bot
    (src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:299-305).

### What is learned, where it lands, when it is written

| Learned | Stored in | Written when | file:line |
|---|---|---|---|
| Bartle point (x, y) + plasticity + pull-back | `LifeMemory.socialSeed`, saved as `seed.bartle` | hourly roster sweep / shutdown | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:734-745 |
| Seven like-channels (drift) | `LifeMemory.delta` (`EnumMap`), saved as `delta.bartle`, each ±0.15 | same sweep, only if dirty | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:25, :782-787 |
| Learned success rate per channel | `learn.rate` map, 6 likes/day/channel | same sweep | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:276-292 |
| Daily budget counters | `learn.budget` / `learn.budgetDay` (max 64 keys) | same sweep | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:609-625, :173-197 |
| Quadrant + crossing history | `learn.quadrant`, `learn.crossings`, `learn.crossed` (last 4) | same sweep | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:62, :74 |
| Goals and GOAP plans | `goals`, `plans`, `soft` in the same JSON | when `refresh`/`plan` change them | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:428-430 |
| Social mind (mood/emotions/needs) | `mind` block, written back untouched unless touched | same sweep | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:337-397 |
| Who the bot met | `met`, last 6 entries | same sweep | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:67-68 |
| Bot type (mode) | `botroster.Activity` name (`social`, `townwanderer`, `grind`) | every roster write | src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:289-305 |
| Villain arc boost | `VillainArc.arcs` static map, **memory only** | never | src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/VillainArc.java:50 |
| Population trends | `reports/bot-trend-index.json` (`-Dsolomapling.trendIndexFile`) | sweep, if dirty | src/main/java/soloMapling/ArtificialPlayer/TrendIndex.java:445-447 |
| Bot-made fads | `reports/bot-fads.json` (`-Dsolomapling.fadsFile`) | sweep, unless `-Dsolomapling.fads.noFile` | src/main/java/soloMapling/ArtificialPlayer/Fads.java:561-563, :604-631 |
| Town targets / pull budget | `TownPulse` statics, **memory only, rebuilt each director tick** | never | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/TownPulse.java:48-56, :164 |

## Data

`botlifeprofile` (database/migrations/028-bot-life-profile.sql:20): `BotId INT UNSIGNED PK` (bot slot = character id
minus `BotStore.BOT_ID_BASE`), `Version SMALLINT DEFAULT 2`, `Profile TEXT` (the `LifeMemory.save(now)` JSON),
`UpdatedAt BIGINT`. Migration ledger id `028-bot-life-profile`. The JSON shape is
`{version, savedAt, seed{bartle{x,y,plasticity,pullBack}, blend}, delta{bartle{dx,dy}, blend}, goals, plans, soft,
quests, lastJob, lastSocial, driftDay, changesToday, events, learn{quadrant, day, budgetDay, budget, rate, crossings,
crossed}, mind, intents, met}` (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:734-775).

Related tables the learning path touches: `botroster` (Activity + `ActivityState`, the v1 fallback carrier,
database/migrations/003-solomapling-bots.sql:66), `botaffinity`/`botdrama`
(database/migrations/011-bot-affinity.sql:18, :30), `botbond` (database/migrations/029-bot-bonds.sql:17), `botmemory`
(database/migrations/003-solomapling-bots.sql:96, extended by 030).

Bounded sizes: 64 learned-budget keys max (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:173-197),
64 entries in `recent` crossings (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:114),
4 crossings and 6 met per memory, 64-sample local observation
(src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeObservation.java:28). About 1 KB per bot while resident
(src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:29-31).

**Persisted across restart:** seed, drift, like rates and budgets, crossings, goals, plans, soft facts, quest log, last
job, last social day, drift day, social mind, intents, met list, and the bot's mode (through the roster activity name).
**Lost on restart:** `VillainArc.arcs`, `TownPulse` targets/offers/budget/counters, `LifeLearning` counters and the
`recent` crossing deque, `ModeSwitch` states/stamps/counters, `LifeTownSlots` reservations, all in-memory
`LifeMemory` objects (rebuilt from rows), and anything the bot did since the last hourly roster write.

## Switches

| Switch | Default | Effect | Live (docs/release/1.6.4.conf) |
|---|---|---|---|
| `-Dslums.botlife.enabled` | `true` | the daily-life brain runs at all | ON — no line in the conf, code default; the social conf notes say live "already has" it (docs/release/1.6.4-social-conf-notes.md:46-47) |
| `-Dslums.botlife.execute` | `true` | brain may actually start activities (else SHADOW) | ON by default, same evidence |
| `-Dslums.botlife.reach` | `all` (`training` restricts) | which bot types get the brain | ON (`all`) |
| `-Dslums.botlife.learning` | `true` | the S9 taps (outcomes, affinity, bonds, sweep) | ON |
| `-Dslums.botlife.metrics` | `true` | writes `data/autoqa/bot-life.json` every 60 s | ON |
| `-Dslums.modeswitch` | `true` | bots may change bot type at runtime | ON (owner plan for 1.6.4, docs/release/1.6.4-social-conf-notes.md:62-63) |
| `-Dslums.modeswitch=false` | — | documented rollback: every bot stays a training bot | not set |
| `-Dsolomapling.drama` | `true` | drama kit, incl. `VillainArc` reactions | ON (no line in 1.6.4.conf) |
| `-Dsolomapling.drama.beef` / `.rivals` / `.romance` / `.wars` | `true` | per-kit switches | ON |
| `-Dsolomapling.fadsFile` / `.trendIndexFile` | `reports/bot-fads.json`, `reports/bot-trend-index.json` | where learning's population state persists | defaults |
| `-Dsolomapling.fads.noFile` | unset | fads never touch the disk | not set |
| `BOT_TOWN_PULL` | `0` = off | town pull strength, clamped to the ±0.5 plugin cap | not in 1.6.4.conf (1.6.0.conf:32-33 set `0.3`) |
| `BOT_TOWN_PULL_PER_MIN` | `3` | per-channel offer budget per minute | default |
| `BOT_TOWN_SHARE_MAX` / `BOT_TOWN_MAP_MAX` / `BOT_TOWN_PLAYER_BOOST` / `BOT_TOWN_CHANNEL_WEIGHTS` | `0.45` / `200` / `1.5` / `1:1.0,2:0.7,3:0.5,4:0.4,5:0.4` | target curve | defaults |
| `BOT_LIFE_TOWN_WEIGHTS` | `henesys:100` | town pick weights | default (1.6.0.conf:32 used `near:55,home:20,henesys:25`) |

Config keys live at src/main/java/config/ServerConfig.java:165-187. TEST: unknown (the TEST start script is not
reachable from this worktree).

## Invariants

1. A bot id below `BotStore.BOT_ID_BASE` is never a life-profile row; `of`/`load` treat it as an ephemeral seed
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:99-106).
2. The seed never changes across saves; only `delta`, bounded to ±0.15 per channel, moves
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:25, :734-745).
3. The learning sweep never reads or writes a profile the roster has not loaded: `peek`, not `of`
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:268-274).
4. No listener exception escapes `LifeOutcomes.report`; a memory hook cannot break the bot
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeOutcomes.java:52-59).
5. Plugin biases, including the learned one (`MAX_BIAS = .15`) and the town pull, are clamped so no hook can take over
   the decision (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:82;
   src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePolicy.java:60, :173-183).
6. Activity facts only exist for accepted work: `tryStart` re-checks availability and rejects a receipt for a different
   activity (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeAcceptance.java:34-40).
7. Daily caps are per bot per UTC day and grouped by prefix, so likes cannot spend the event budget
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:609-625).
8. Learning is off when the brain is off; `enabled()` ands `LifeRuntime.enabled()`
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:123-124).
9. A mode swap only happens where nothing of the old runtime is still owed: not trading, not committed, not in a party
   or chat, in a town, no errand, not coupled (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:258-275).
10. At most 3 mode switches per hour and a per-bot minimum stay of 20 min × 0.75–1.5
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:74-75, :233-237).
11. The life profiles are written before the roster row, so a crash cannot lose the v2 copy while the v1 copy is gone
    (src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:359-372).
12. Without migration 028 the profile degrades to `ActivityState.life` for every bot type instead of being lost
    (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:34-36, :206-210).

## Origin

custom — every file in scope was created during the 1.6.0 sprint (Oct 2026); none predates soloMapling's bot work.
Oldest commits (`git log --follow ... | tail -1`):

| File | First commit |
|---|---|
| LifeMemory.java | `b837da282` 2026-10-25 "WIP: Bartle social profile and durable life memory" |
| LifeMemoryStore.java | `9f6662146` 2026-10-25 "Keep each bot's life profile in its own table so social sessions stop wiping it" |
| LifeLearning.java | `08a5eb170` 2026-10-25 |
| LifeOutcomes.java | `b079359e8` 2026-10-25 |
| LifeAcceptance.java | `97c74c095` 2026-10-24 "Require accepted bot work before exposing an action fact" |
| LifeObservation.java / LifeProfile.java | `1fd3d5700` 2026-10-24 "Preview stable bot personalities and level and weapon goals" |
| LifeInspection.java | `c0ce7dbe6` 2026-10-24 "Inspect bot life needs through an opt-in GM preview" |
| LifeProfileProof.java | `6e8dcef50` 2026-10-25 "Add !qa bl-4 and t-bl-4" |
| LifeTowns.java | `8d62c9109` 2026-10-28 |
| LifeTownLife.java | `5ba601dc4` 2026-10-25 "WIP S8: light brain for social and town bots" |
| LifeTownSlots.java | `4d78245d3` 2026-10-24 |
| TownPulse.java | `3abd7820a` 2026-10-27 |
| ModeSwitch.java | `188bcc83c` 2026-11-03 "Bots now switch between training, social and town modes while online" |

## Tests and QA

| Kind | Name | What it pins | file:line |
|---|---|---|---|
| Unit | `LifeMemoryTest` | saved goals/blends survive the JSON format; drift small, deduplicated and bounded; corrupt data falls back; v1 blend read once as seed+delta | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryTest.java:8-120 |
| Unit | `LifeMemoryMindTest` | party lifts happiness; KS anger fades; mind survives save/restart; unreadable mind ignored | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryMindTest.java:39-102 |
| Unit | `LifeLearningTest` | drift inside plasticity cap and daily budget; decay; likes stay bounded; one crossing with hysteresis; 30 KS turn a Socializer; a set-in-its-ways bot never crosses; state saved and read back; pre-S9 memory starts where it stands; taps map to the table; sweep health | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearningTest.java:46-244 |
| Unit | `LifeOutcomesTest` | the queue tells once how an activity ended; success/failure reach listeners; a plugin bias is clamped | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeOutcomesTest.java:33-73 |
| Unit | `LifeAcceptanceTest` | SHADOW/HELD/NO_CHANGE never reach the executor; commitment re-checked; no fact on stale/unsupported/failure; wrong receipt rejected | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeAcceptanceTest.java:17-66 |
| Unit | `TownPulseTest` | knob default off; peaks from table and override string; targets follow curve/channel/players; share cap; pull band + continent + budget; bots en route count; bias only for an offered bot; Free Market filled from anywhere | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/TownPulseTest.java:41-172 |
| Unit | `ModeSwitchPolicyTest`, `ModeSwitchGateTest`, `ModeSwitchIntentsTest`, `ModeSwitchSimulationTest` | policy maths, blocker reasons, intent interaction, population simulation | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitchGateTest.java:140-216 |
| Unit | `LifeInspectionTest`, `LifeObservationTest`, `LifeTownsTest`, `LifeTownLifeTest`, `LifeTownSlotsTest`, `LifeProfileGoalsTest` | preview-only inspection, observation capture, town table/weights, light host, slot reservation, seed→goals | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/ |
| Auto-QA | `t-bl-4` | profile survives a social session and a reload: `rosterCarriesLife=false`, `deltaEqual/profileEqual/bartleEqual` | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:894-899 |
| Auto-QA | `t-lr-1` | staged Socializer takes 30 KS, one crossing, `!botwhy` shows it | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1021-1028 |
| Auto-QA | `t-ms-1` | census holds all three modes; switches both ways; "refused on at most 5% of switches"; no bot over 3/hour (probe lane, `Probes164Social`) | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1571-1577 |
| Auto-QA | `t-tw-1` | towns fill toward targets, ch1 busiest, no map over max | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1236 |
| Auto-QA | `t-bl-1` | shadow policy and rejected-action safeguards | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:865 |
| Auto-QA | `t-bl-11` | a goal turns into plan steps `!botwhy` shows | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:942 |
| Scenario | `lr-1` | run-only staged bot, so "nothing it learns is saved" | src/main/java/soloMapling/qa/LearningScenarios.java:8-13 |
| Scenario | `bl-4` | `LifeProfileProof` end-to-end, requires migration 028 | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeProfileProof.java:31-103 |

**Not covered.** There is no `LifeMemoryStoreTest`: the store is only exercised indirectly (`LifeLearningTest`,
`ModeSwitchIntentsTest`, `BotTalkSocialActsTest`, `BotWhyCommandTest`, `BotRosterSocialLodTest`). No test uses a real
MySQL — `botlifeprofile` runs on memory fakes and a mocked JDBC pool (docs/design/social-intelligence-audit.md:323). No
unit test asserts the *write ordering* in `BotRoster.record` or the `attach` fallback. `ModeSwitch` has no scenario
(`t-ms-1` is a probe lane, src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1571`.laneOnly()`), and no test covers
`ModeSwitch.maybeSwitch` against a real `BotTypeManager.convertBotType`.

## Known issues

| Card | One line |
|---|---|
| b240 | Regular bots say they're grinding while standing in town — the reported activity does not always follow the phase (`activitySnapshot` returns a constant `"grind"` for training bots, src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1508-1514). |
| b276 | The GM's QA feed never receives a bot's map-scoped PLAN/ROUTE lines, so plan learning cannot be watched live. |
| b284 | Auto-QA at 10 lanes produces false failures (8 of 22), which also makes the learning/town lanes' reds untrustworthy. |
| b225 | KPQ pass hand-overs: an accepted activity's fact is not visible to the player (a `LifeAcceptance`-adjacent claim/receipt gap). |
| b232 | A follower inside an instance does not leave with its leader — the follower's activity keeps it committed (blocks `ModeSwitch` via "on an errand"/"committed"). |
| b241 | KPQ bonus stage keeps messaging the player after the activity ended (`LifeOutcomes` reports the end but the message path ignores it). |
| b285 | Alt upgrade hand-over refused for a sibling alt unless the owner is online. |
| b287 | `t-xc-5`: a PANIC-style scroll trader holding a Chaos/White Scroll never lists it. |

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:206-238 — `write` returns
`false` on any DB failure and `BotRoster.record` then keeps the v1 copy in `ActivityState`, but `prepare` at :176-185
swallows a `RuntimeException` and returns `null`, so a bot whose memory cannot even be snapshotted silently keeps its
last roster copy with no log above `warn` once per sweep — a persistently failing bot looks identical to an idle one.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:301 — `switchesLastHour` rings
over the three stored stamps while `MAX_PER_HOUR` is 3, so the fourth switch inside one hour is refused only if three
stamps are still inside the window; correct, but the ring length is hard-coded to `stamps[3]` at :169-186 with no
assertion tying it to `MAX_PER_HOUR`, so raising the constant silently weakens the cap.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:173-197 — `readLearning` caps the
restored budget map at 64 keys and drops the rest silently; a bot that used more distinct budget kinds in one day loses
those counters across a restart and can exceed its daily allowance that day.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:409-449 — `sweep` reads
`LifeMemoryStore.view()`, which is "memory only" (:272-274); bots that are on the roster but have never been asked for
a memory during this run are therefore not pulled back or crossing-checked until something calls `of`. Harmless for
decay, but it means `learned` in `bot-life.json` counts the resident set, not the roster.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/TownPulse.java:48-56 — every town counter is a
static `LongAdder`/map that is never cleared when `BOT_TOWN_PULL` goes to 0 (`refresh` only empties the index at
:169-172), so `bot-life.json` keeps reporting a pull and a target sum for a feature that is switched off.

NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeTownLife.java:111-115 — an accepted switch
activity is finished as `COMPLETED` immediately ("a switch, not a trip"), so `LifeOutcomes` reports a success for an
activity that only changed the bot type; a learning tap keyed to that activity would count it as real work.

## State

partial. The learning loop itself is complete and test-covered end to end (unit `LifeLearningTest`,
`LifeMemoryTest`, `LifeMemoryMindTest`, `LifeOutcomesTest`; scenarios `!qa bl-4` and `!qa lr-1`; auto-QA
`t-bl-4`/`t-lr-1`), and durability is designed with a documented fallback for a missing migration. What keeps it below
working: no test exercises the store against a real MySQL (docs/design/social-intelligence-audit.md:323), the mode
switch has no scenario — only the `t-ms-1` probe lane (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1571) —
and the audit's own harness row for restart survival was run on fakes. Documented live blockers that touch these paths:
b240 (wrong reported activity) and b276 (plan lines unreachable from the QA feed).

## Open questions

1. `t-ms-1` reports "refused on at most 5% of switches"; refusal is counted per reason in `ModeSwitch.stats`
   (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:278-285) — is the 5% threshold measured
   against evaluations or against decided switches? The catalog text does not say
   (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1571).
2. The town pull is `0` (off) in the shipped `ServerConfig` default but `0.3` in `docs/release/1.6.0.conf:32-33`, and no
   later conf repeats it. Was the 1.6.4 live intent to run towns pulled or purely organic? `1.6.4-social-conf-notes.md`
   does not mention `BOT_TOWN_*` at all.
3. `LifeLearning.MAX_BIAS = .15` and `LifePolicy.MAX_PLUGIN_BIAS = .5` are tuned by hand; the audit wants "a bot's
   learning never overrides a strong need" (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:55-58)
   but no test asserts the combined bound for learning + initiative + town pull together.
4. Should `VillainArc`'s 7-day boost be persisted (it is memory only today,
   src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/VillainArc.java:50)? A restart forgives a bot's turn to
   villainy the same day it happened.
