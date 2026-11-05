# Decision core

## Purpose

The bot "brain": a utility scorer over 16 daily-life activities (grind, hang out, restock, …), fed by needs, a small
GOAP planner that turns progression goals into ordered steps, and the bot's personality/aspect mix. It runs on the
bot's own update thread, decides rarely (on an interrupt or when an activity is due), and never touches the world
itself: it only asks the bot's host `TrainingBot` to start an activity, and `TrainingBot` re-checks everything before
it does.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `TrainingBot.update` → `LifeRuntime.tick(now)` | every bot update, only when `LifeRuntime.enabled()` | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:643 |
| `LifeRuntime.tick` self-throttle | re-evaluates at most every 30 s unless `dirty` | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:412-413 |
| `LifeActivityQueue.onEnd` → `LifeRuntime.ended` | an activity finished (queue closed) | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:411 |
| `LifeRuntime.phase` / `keepLifeActivity` | host phase changes | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:573 |
| `!botwhy`, auto-QA `bl-*`, `bc-1`, QA `BotLifeScenarios` | operator/QA diagnostics | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:866-957 |
| `-Dslums.botlife.enabled` / `.execute` / `.reach` / `.logEvery` / `.metrics` | switches | LifeRuntime.java:109-131 |

## How it works

1. **Throttle.** `LifeRuntime.tick(long)` (LifeRuntime.java:409) returns at once when the bot is not `dirty`, the next
   check is in the future and no activity is due (LifeRuntime.java:412). Otherwise it sets `nextCheck = now + 30_000`
   (LifeRuntime.java:413).
2. **Needs.** Six needs (`SUPPLIES, BAG_SPACE, BOREDOM, SOCIAL, FATIGUE, PROGRESS` — LifeDecision.java:18) are built
   in `[0,1]`; missing data is recorded in `unknown`, never as a zero (LifeDecision.java:9). `BOREDOM` is derived from
   how long the current activity has run against its expected duration (LifeRuntime.java:462-465 in the same block),
   and `bot.lifeNeeds(needs, now)` lets a light host override (LifeRuntime.java:466).
3. **Situation.** Crowd = bots on this map against its cap, `crowdOf` (LifeRuntime.java:467); a KS war
   `ksWarHere` (LifeRuntime.java:468); a channel target only read when crowded or in a KS war
   (LifeRuntime.java:470-472); a `TownPulse.pullFor` town pull only at a normal decision point (LifeRuntime.java:480).
4. **Eligibility (feasibility gate A).** For every `Activity`, `bot.canStartLifeActivity(activity, now)` fills
   `eligible` (LifeRuntime.java:484-487); `CHANGE_CHANNEL` is dropped when no target channel was found
   (LifeRuntime.java:488).
5. **Plan.** `LifePlanner.next` picks the highest-pressure goal's next step and a bonus
   `STEP_BONUS × max(.25, pressure)` (LifePlanner.java:260-265); the step and bonus go into
   `LifePolicy.Situation` (LifeRuntime.java:494-497).
6. **Cache.** A signature of `phase, free, eligible.keySet(), friends, rivals, friend id, help id, plan step, crowd
   bucket, needs bucket` (LifeRuntime.java:498) short-circuits the whole evaluation when nothing changed
   (LifeRuntime.java:502).
7. **Options.** `LifePolicy.options(context, profile, situation)` builds one `Option` per activity: need weights
   (`tuned()` LifePolicy.java:150, else `weights()` LifePolicy.java:195), a context pull (`options()` switch
   LifePolicy.java:96-130), and a personality term = `LifeProfile.bias(activity)` (LifeProfile.java:101) +
   `LifePolicy.bartle(social, activity)` (LifePolicy.java:81) + plug-in biases clamped to ±.5 (LifePolicy.java:173).
   `LifeRuntime` then adds, for `HANG_OUT`/`SHOW_OFF` only, `townBias(level)` (LifeRuntime.java:505, :569), `+.6` for
   a non-home town and `CREW_PULL = .7` for a crew/come-along (LifeRuntime.java:507-512).
8. **Score.** `LifeDecision.evaluate` (LifeDecision.java:89) filters `available`, scores each option
   `Σ need×weight + context + personality + noise + stickiness` (LifeDecision.java:120-142), sorts descending, and
   applies: commitment in flight → `HOLD_COMMITMENT`; empty → `NO_AVAILABLE_OPTION`; the current activity wins when it
   is best or within `INTERRUPT_MARGIN = .25` → `KEEP_CURRENT`; else `CHOOSE` (LifeDecision.java:99-118). Noise is
   ±.02 from a seed of bot id, activity ordinal and `now / 30 s` (LifeRuntime.java:521-523). Stickiness is
   `STICKINESS = .8 × (1 − elapsed/expectedDuration)` (LifeDecision.java:61, LifePolicy.java:23).
9. **Counters.** Every pass counts `selectedCounts[activity]` (keeps included, LifeRuntime.java:528) and
   `chosenCounts` only for a transition to something other than the running activity (LifeRuntime.java:531-533).
10. **Start.** A `CHOOSE` whose activity is not what the bot is already doing asks the host to start it
    (LifeRuntime.java:543-556): `bot.prepareLifeActivity(result)` makes a request valid for
    `MAX_WAIT_MS = 30_000` (LifeActivityQueue.java:10), `bot.submitLifeActivity` queues it
    (TrainingBot.java:813-822) and nudges the bot.
11. **Feasibility gate B, at drain.** `TrainingBot.runLifeActivities` (TrainingBot.java:843) drains the queue with
    `LifeExecutor` (TrainingBot.java:847-849). `LifeAcceptance.tryStart` (LifeAcceptance.java:47-64) re-checks
    `stillFree`, `executor.supports(activity)` (→ `canStartLifeActivity` again, LifeExecutor.java:11) and then
    `executor.start` → `TrainingBot.startLifeActivity` (TrainingBot.java:1040).
12. **Start re-check.** `startLifeActivity` re-tests `isStartingHere()`, `lifeExecutionEnabled()`,
    `freeForAutomaticLifeActivity(at)` and `canStartLifeActivity` (TrainingBot.java:1041-1043), then the per-activity
    preconditions (destination, slot reservation at TrainingBot.java:1077, channel move, PQ destination at :1106).
13. **Outcome.** The queue closes the activity on end and notifies `LifeOutcomes` listeners (LifeOutcomes.java:44-57);
    `LifeRuntime.ended` marks a finished plan step's soft fact true and `LifeLearning` adjusts the profile (see
    [decision-memory-learning.md](decision-memory-learning.md)).

### Every activity, how it is scored, and where feasibility is checked

`w` = tuned need weights (LifePolicy.java:150); `pull` = context bonus (LifePolicy.java:96-130); `bias` = personality
(`LifeProfile.bias` × profile blend, LifeProfile.java:101-117 + Bartle table LifePolicy.java:70); `elig` = the
`canStartLifeActivity` condition (TrainingBot.java:969-1029).

| Activity | w (tuned) | pull | bias | elig (gate A) | extra failure after scoring |
|---|---|---|---|---|---|
| `GRIND` | PROGRESS 1.4, SUPPLIES −1.6, BAG_SPACE −0.4, FATIGUE −0.5 | `.3 − min(.8, max(0, 2(crowd−.8)))` (+`.5×killer` in a KS war) | .6×GRINDER | `true` | none beyond gate B |
| `HANG_OUT` | SOCIAL 1.0, BOREDOM .3 | `min(1.2,.6×friends) + min(.2,.04×strangers) − (drama on ? min(.8,.4×rivals)×rivalFactor : 0)` + townBias + `.6` if not home town + `.7` crew | .6×SOCIALITE | safe phase **and** `lifeDestinationAvailable(townDestination())` | `LifeTownSlots.reserve` may still refuse (TrainingBot.java:1077) |
| `SHOW_OFF` | SOCIAL .8, BOREDOM .3 | as HANG_OUT + `min(.3,.05×strangers×killer)` | .6×SHOW_OFF | level ≥ 70 and a destination | as HANG_OUT |
| `HELP_FRIEND` | SOCIAL .6, PROGRESS .3 | `1.5` if a friend asked, else `.55` for an unasked friend/guild mate | .6×HELPER | `helpTarget(at) != null` and its map available | `helpTarget` can be null at start (TrainingBot.java:1049) |
| `PARTY_QUEST` | PROGRESS .8, SOCIAL .5 | `.2` when a fitting lobby is open | .5×(GRINDER+SOCIALITE) | lobby map, level fit, PQ hours or an LFG party or the plan | PQ destination ≤ 0 at start (TrainingBot.java:1106) |
| `MOVE_MAP` | BOREDOM 1.0 | 0 | .6×EXPLORER | `phase == GRIND` | 2-min map cooldown recorded only when started (TrainingBot.java:1081) |
| `RESTOCK` | SUPPLIES 3.0 | 0 | .3×GRINDER | `phase == GRIND && restockReady && restockTownSells()` | `startLifeRestockTrip` needs `freeForLifeActivity` (TrainingBot.java:864-866) |
| `SELL` | BAG_SPACE 2.0 | 0 | .6×MERCHANT | `BotShops.enabled && merchantMinded` and `at >= sellNothingUntilMs` | cooldown set only after an empty trip (TrainingBot.java:1287) |
| `FREE_MARKET` | BAG_SPACE .8, BOREDOM .3 | 0 | .6×MERCHANT | FM map available | as HANG_OUT |
| `BREAK` | FATIGUE 1.7, BOREDOM .3 | 0 | .6×SLACKER | `phase == GRIND` | `breaks.begin` can refuse |
| `LOG_OUT` | FATIGUE 2.0 | 0 | .6×SLACKER | session minutes done, no guild, not on an errand, not in an LFG trip | `BotPopulationDirector.logOut` decides |
| `FISH` | BOREDOM .7, SOCIAL .3, FATIGUE .5, SUPPLIES −.5 | `.3` | .6×SLACKER | `GameFishing.choosable` (owns a chair, map has room) | as HANG_OUT |
| `CHANGE_CHANNEL` | BOREDOM .2 | `crowd ≥ .8 ? min(1.2,.5+2.5(crowd−.8)) : 0` + `ksWar ? max(0,.7(1−2.5k))` | 0 | `crowd ≥ .8 || ksWar`, a target channel, `LifeChannelMove.why == null` | `changeChannelNow` |
| `INVITE_PQ` | SOCIAL .6, PROGRESS .4 | 0 | .3×SOCIALITE | `phase == LIFE_DWELL`, dwelling at a PQ lobby, `SocialInitiative.pqTarget` non-null | `invitePqNow` |
| `INVITE_GUILD` | SOCIAL .4 | 0 | .3×SOCIALITE | **never** — event-driven after a login hello | n/a |
| `EVENT` | BOREDOM .8, SOCIAL .4, PROGRESS .3, SUPPLIES −.8 | `.8` | .5×(GRINDER+SOCIALITE) | `WorldEventSteer.mapFor < 0`, not party-tied, not recruiting, `WorldEvents.mayAccept` | `joinEventNow` |

Feasibility summary: gate A (before scoring) is a **necessary but not sufficient** check. Everything in the last
column happens *after* the score, and a refusal is not fed back into the scorer (no penalty, no cooldown), except
`SELL` (`sellNothingUntilMs`) and `MOVE_MAP` (`mapCrowdCooldown`). That is the structural reason an activity can be
selected repeatedly and started rarely.

## Data

| State | Where | Persisted |
|---|---|---|
| Life profile (7-channel blend + drift, grind/town durations) | `LifeProfile`, `LifeMemory` | yes, see [decision-memory-learning.md](decision-memory-learning.md) |
| Goals (≤2 seeded + a quest goal) | `LifeGoals.seed` (LifeGoals.java:81-124), memory-only, re-seeded from game facts | no |
| Plans (steps + fact mask) | `LifePlanner.Plan` (LifePlanner.java:237-265), held in `LifeMemory` | via memory |
| Soft facts (`AT_INSTRUCTOR, GUILD_CONTACT, ZAKUM_READY, LOOT, GEAR_FARMED`) | `LifePlanner.SOFT` (LifePlanner.java:57-59), cleared when the goal changes | no |
| Current activity + due time | `LifeRuntime.dueAt`, `TrainingBot` phase | no |
| Counters (`decisions, accepted, cpuNanos, replans`, per-activity selected/chosen) | static `LongAdder`s, LifeRuntime.java:26-36 | reported to bot-life.json every 60 s (LifeRuntime.java:881-905) |
| Queue request | `LifeActivityQueue.Request`, one waiting + one active per bot | no |

## Switches

| Switch | Default | Effect | Read at |
|---|---|---|---|
| `-Dslums.botlife.enabled` | on (only `false` disables) | brain runs at all | LifeRuntime.java:108-112 |
| `-Dslums.botlife.execute` | on (only `false` disables) | choices are carried out; off = shadow | LifeRuntime.java:115-117 |
| `-Dslums.botlife.reach` | `all`; `training` = training bots only | which bot types run the brain | LifeRuntime.java:126-128 |
| `-Dslums.botlife.logEvery` | 500 | one decision in N logged at INFO | LifeRuntime.java:39 |
| `-Dslums.botlife.metrics` | `true` | 60 s metrics report | LifeRuntime.java:882 |
| `-Dslums.qa` / `QaSuite.isEnabled()` | off | lets QA drive life activities even with the brain off | TrainingBot.java:829-831 |

Live: `docs/release/1.6.4.conf` drops `-Dsolomapling.fads=false` (docs/HANDOFF.md:212 records the 1.6.0.4 hang) but no
botlife `=false`, so by these defaults the brain is **on** in live 1.6.4 builds. TEST: unknown (its `start-test.sh`
was not readable for this review).

## Invariants

- The scorer never executes anything: `LifeDecision` is pure and `LifePolicy` is pure (`LifePolicy` class comment
  LifePolicy.java:12-20; `LifeAcceptance` is "deliberately not installed on any bot tick", LifeAcceptance.java:10).
- One memory per bot for the run, shared across bot types: `LifeMemoryStore.of(id)` (LifeRuntime.java:104-105).
- A plug-in bias can never take over: the sum of all biases is clamped to ±`MAX_PLUGIN_BIAS = .5`
  (LifePolicy.java:58, :173-183).
- A request belongs to one bot and expires: `FOREIGN`/`STALE`/`DUPLICATE` offers are refused
  (LifeActivityQueue.java:82-90), lifetime capped at 30 s (LifeActivityQueue.java:10).
- Only the bot's own update thread may start an activity; the queue refuses start callbacks from elsewhere
  (`isStartingHere`, TrainingBot.java:1041 and the `startingThread` field LifeActivityQueue.java:38).
- `LifeRuntime.accepted` may not hold its monitor while reading the game (the b267 deadlock family):
  LifeRuntime.java:790-794 documents the rule; `friend(now)` and `townAccepted(now)` run outside the `synchronized`.
- An activity that was never accepted must not claim arrival, purchases or rewards:
  `LifeExecutor.start` returns a fact string that says exactly that (LifeExecutor.java:12-14).

## Origin

Custom. The whole `soloMapling/ArtificialPlayer/BotLifeSystem` package is ours: `git log --follow` reaches the 1.6.0
sprint (ticket 60's scorer), not the repack baseline. `LifeDecision`'s comment ties it to "Ticket 60's pure daily-life
scorer" (LifeDecision.java:8-12) and the S-numbered slices (S6, S8, S9) in the class comments.

## Tests and QA

Unit tests, all under `src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/`:

- `LifeDecisionTest` — scoring, filtering and the outcome precedence.
- `LifePolicyTest`, `LifePolicyFishTest` — option construction, pulls and weights.
- `LifePlannerTest` — plans, fact masks, soft facts.
- `LifeGoalsFarmTest`, `LifeProfileGoalsTest`, `LifeQuestGoalTest` — goal seeding and aspiration choice.
- `LifeAcceptanceTest`, `LifeActivityQueueTest` — the accept/stale/duplicate boundary.
- `LifeExecutor` behaviour via `TrainingBotLifeExecutionTest` and `BotLifeExecutionScenarioTest`.
- `LifeRuntimeTest`, `LifeRuntimePlanTest`, `LifeRuntimeTownsTest`, `LifeRuntimeFishingTest`, `LifeRuntimeLockOrderTest`,
  `LifeRuntimeSayDoTest`, `LifeFriendInterruptTest`, `LifeChatDeferralTest`, `LifeMixTest`, `LifeInspectionTest`,
  `LifeTownSlotsTest`, `LifeTownsTest`, `LifeTownLifeTest`, `LifeChannelMoveTest`, `LifeObservationTest`,
  `LifeOutcomesTest`, `ModeSwitch*Test` (4 classes).

Auto-QA (`AutoQaCatalog.java`): `bl-1` (shadow selection: socialite→HANG_OUT, empty potions→RESTOCK, helper→
HELP_FRIEND, plus a refusal line), `bl-2` (restock queue/duplicate/at-most-once), `bl-3` (need-driven restock),
`bl-4` (profile round-trip), `bl-11` (plan staged + `LIFE WHY`), `bc-1` (crowded-map channel move). `!qa` side:
`BotLifeScenarios.java` stages the same cases for a human run.

Not covered: no test asserts that a *selected* activity ever reaches `accepted` at a realistic ratio; nothing pins the
`HANG_OUT` selected-vs-started gap; `LifeTownSlots` contention between many bots is only unit-tested with two bots;
`LifePlanner` search quality is not measured (only that a plan exists).

## Known issues

- **Selection runs ahead of feasibility (gate A vs gate B).** The known symptom — HANG_OUT selected ~2,734 times vs
  ~172 starts — matches this design: `eligible` is computed per decision and cached by signature, but the decisive
  refusals happen later (`LifeTownSlots.reserve`, TrainingBot.java:1077; `freeForLifeActivity`, TrainingBot.java:861;
  `helpTarget` becoming null, TrainingBot.java:1049) and no refusal feeds back into the scorer.
  **NEW-FINDING:** `LifeTownSlots.reserve` counts bots *in flight* (`incoming`, LifeTownSlots.java:12, :26) while the
  scoring-side check `lifeDestinationAvailable` uses `BotMapCap.hasRoom(map)` on the loaded map only
  (TrainingBot.java:1031-1038), so a town can pass the eligibility check and refuse the reservation one tick later.
- **The 2,734 : 172 ratio is partly arithmetic, not a bug.** `selected` counts every decision pass *including*
  `KEEP_CURRENT` (LifeRuntime.java:26-31, :528), and a town stay lasts `townDurationMs` = 5–20 min
  (LifeProfile.java:35) at a 30 s decision cadence, i.e. 10–40 counts per real hang-out. The metric that measures the
  claimed gap is `chosen` (transitions) vs `accepted` (starts): LifeRuntime.java:531-533, :876-880 vs :798.
  Anyone reading "selected 2,734, run 172" as a 6% success rate is reading the wrong counter.
  **NEW-FINDING:** the classifier between them is only explained in a source comment, and `bot-life.json` writes both
  without a note, so the audit table reads as a failure rate.
- **Refusals are invisible to the operator.** A refused start only appends `LIFE REQUEST … outcome=REJECTED`
  to the bot's life note (TrainingBot.java:850-853); it is not counted per activity and does not reach
  `bot-life.json`.
  **NEW-FINDING:** no counter for gate-B refusals exists anywhere (`LifeAcceptance.Status` has 7 values,
  LifeAcceptance.java:12, and none is aggregated).
- `INVITE_GUILD` is permanently ineligible (TrainingBot.java:1019), yet it carries weights and a personality bias
  (LifePolicy.java:206-208, LifeProfile.java:114) — dead scoring configuration.
  **NEW-FINDING:** a fifth of the catalogue is unreachable through the scorer; harmless, but it makes the option list
  read as if guilds were a brain activity.

## State

**Partial.** The scorer, planner, queue and acceptance boundary are implemented, well factored and heavily
unit-tested; the shadow mode (`-Dslums.botlife.execute=false`) and the `bl-1` QA check prove selection works. What is
not working as a whole is the *feedback* half: a refused start costs nothing, so the mix drifts toward whatever the
scorer likes and the host refuses. Evidence: the gate-A/gate-B split above, the per-activity cooldowns existing for
only two activities (TrainingBot.java:1287, :1081), and the absence of any refusal counter.

## Open questions

- Which counter produced "HANG_OUT picked 2,734 vs run 172"? `selected`/`accepted` and `chosen`/`accepted` give very
  different readings, and only the `chosen` ratio measures a failure rate. The source of that figure is not in this
  worktree.
- Is the 30 s decision cadence measured against `dueAt` in wall-clock time or tick time on a loaded server? `dueAt` is
  set from `now` passed by the bot update, and `nextCheck` too, so a starved bot's decisions stretch; the effect on the
  mix under load was not measured.
- `LifePolicy.preview` exists for shadow runs (LifePolicy.java:185) but no caller in this package was found — is it
  used by an auto-QA probe only?
