# A. 1.7.0 Architecture RFC: execution authority, capabilities, learning

Status: **final for sign-off**, Nov 5 2026. Strategy: `docs/plans/1.7.0-stabilization.md` (round 2).
Source read: `release-1.6.4` @ `e52e19c6e`. Implementation starts only after 1.6.4 is released and verified; the
released commit becomes the 1.7.0 baseline (see E).

Evidence labels used throughout:
- **[CONFIRMED]**: read in current source, file:line given.
- **[PROPOSED]**: this RFC's design.
- **[RUNTIME]**: depends on TEST/live behaviour or config not provable from source.

---

## 1. What exists today (and is kept)

The action contract is not new. Most of its pieces already exist in `soloMapling/ArtificialPlayer/BotLifeSystem/`.
1.7.0 hardens and extends them; it does not add a second controller.

| Class | Today's responsibility [CONFIRMED] | 1.7.0 role |
|---|---|---|
| `LifeDecision` | pure scorer: needs x options -> ranked `Result`; "cannot execute or announce an action" (class doc) | unchanged; decision record gets a `decisionId` |
| `LifePlanner`, `LifeGoals`, `LifePolicy` | GOAP goals/plans, policy weights and biases | unchanged; read world knowledge (3.3) for feasibility |
| `LifeRuntime` (920 lines) | owns intent: decides, plans, `ended(activity, End)` reports outcomes (`:703-714`) | **owns decision-making** (unchanged role); consumes typed outcomes |
| `LifeActivityQueue` (225) | per-bot: one waiting decision + one active activity, numbered requests, epoch, `MAX_WAIT_MS` 30 s (`:11`), `finish(number, End)` idempotent (`number != active` -> false), `close()` cancels the active one (logout/shutdown) | **becomes the per-actor ledger** (ActionAuthority's state holder) |
| `LifeAcceptance` | admission boundary: `Status {SHADOW, HELD, NO_CHANGE, UNSUPPORTED, REJECTED, FAILED, ACCEPTED}`, `Executor` interface | **admission step**; statuses map to the lifecycle (2.2) |
| `LifeExecutor` | adapter: `TrainingBot.canStartLifeActivity` / `startLifeActivity` (booleans, no reason) | **first activity adapter**; returns typed reasons |
| `LifeOutcomes` | fan-out of `Outcome(botId, activity, success, at, detail)` to listeners (learning, initiative) | **terminal event bus**; event gains attempt id, reason, cause |
| `LifeLearning` | listener: every outcome moves the activity's "like" channel; success also pushes the Bartle point (`:268-290`) | consumes only **experience** outcomes (3.3) |
| `LifeMemory` / `LifeMemoryStore` | saved seed + learned delta, goals, events (table from migration 028) | gains a **world-knowledge** section (3.3) |
| `TrainingBot` (3,104 lines) | the executor: phase machine `INIT ... GO_TRAIN, GRIND ... LIFE_DWELL` (`:294`), local watchdogs (grind/climb/wander) | keeps all mechanics; **requests** transitions instead of ending activities itself |
| `LifeTownLife` | light `LifeHost` for town/social bots | second host on the same contract |
| `IntentBook` / `SocialIntents` | social intents with `End {KEPT, BROKEN, EXPIRED, CANCELLED}` | wrapped by a social adapter later (W1 phase 3) |
| `BotGiveTrade` | trade lifecycle `End {TRADED, DECLINED, TIMED_OUT, CANCELLED, COULD_NOT_START}` | wrapped by a trade adapter later |
| `companions/OwnedAlt*` | alts' own 200 ms tick: death, recovery, catch-up, gate, fight, orders (`OwnedAltCompanion` doc) | same ledger, **OWNER policy context** (2.6) |

### Defects in today's path that this RFC closes [CONFIRMED]

1. **Silent success.** `TrainingBot.enterPhase(GRIND)` marks any active activity COMPLETED with the note "returned to
   grinding, not proof of a purchase" (`TrainingBot.java:575-579`). HELP_FRIEND enters GRIND on arrival (`:1086`),
   so it is counted as a success, and learning rewards it, with no evidence the bot helped anyone.
2. **Environment failure teaches personality.** `LifeRuntime.ended` turns every non-COMPLETED end into
   `success=false` (`:706`); `LifeLearning.observe` lowers the activity's like on failure with no reason check
   (`:276-285`). A logout (`close()` -> CANCELLED), an unreachable map or a missing script all make the bot like
   that activity less.
3. **No deadline on an active activity.** `drain` returns BUSY while `active != 0` (`LifeActivityQueue` drain);
   only the *waiting* request expires (30 s). A path that leaves without `finish` (e.g. `doTravel` failing to
   DECIDE, `TrainingBot.java:654`, which does not finish the activity) can starve that bot's life decisions.
   [RUNTIME: how often this happens is unmeasured.]
4. **Refusals carry no reason.** `canStartLifeActivity` / `startLifeActivity` return booleans (`:969`, `:1040`), so
   the planner cannot tell "busy" from "this map has no mobs" (HOLES bots/decision-core).
5. **Town EXP.** The mobless sentinel -1 is coerced to 1 by `Math.max(1, MapMobIndex.level(..))` at **11 call
   sites** in TrainingBot (HELP_FRIEND `:1085`, plus `:1499, 1909, 1920, 1930, 1950, 1965, 1976, 1992, 2392, 2928`),
   and the abstract-EXP gate only checks `currentMobLevel > 0` (`:2141`) (HOLES #8, b240). The root fix is
   `setTrainTarget` keeping -1 and the gate requiring a real mob template, not a HELP_FRIEND-only patch.

---

## 2. ActionAuthority: the contract

ActionAuthority is **a contract plus the per-actor ledger**, not a service. It has three parts:
- `ActionLedger`: `LifeActivityQueue` grown from "one active long" to "attempts + claims".
- `ActivityAdapter`: `LifeAcceptance.Executor` grown to return reasons and postconditions.
- `LifeOutcomes`: the terminal event.

There is no global registry, worker or lock. [PROPOSED]

### 2.1 Types [PROPOSED]

```java
// soloMapling/ArtificialPlayer/BotLifeSystem/action/
record AttemptId(int actorId, int bootEpoch, long seq) {}      // printed "1000421-k3f-42"; unique per process run
enum AttemptState { PROPOSED, REJECTED, ADMITTED, RUNNING, SUCCEEDED, FAILED, INTERRUPTED, TIMED_OUT }
enum Resource { BODY, HANDS, BAG, DIALOG, PARTY }               // see 2.4
enum Priority { SAFETY, OWNER, COMMITMENT, AUTONOMOUS }         // see 2.4
enum Policy { AUTONOMOUS, OWNER_DIRECTED }                      // roster bot vs owned alt (2.6)

enum Reason {                                                   // one reason on every non-success terminal
  // structural: never retried until the dependency changes (3.4)
  ENGINE_CAPABILITY_MISSING, CONTENT_REFERENCE_MISSING, CLIENT_FEATURE_UNAVAILABLE, CONFIG_DISABLED, UNSUPPORTED_ACTION,
  // feasibility of this actor now
  PRECONDITION_FAILED, INVENTORY_FULL, RESOURCE_SHORTAGE, TARGET_GONE, TARGET_REFUSED, TARGET_MAP_NO_MOBS,
  // transient contention
  BUSY, CONFLICT, MAP_CAPACITY, PATH_NOT_FOUND,
  // execution
  NO_PROGRESS, TIMEOUT, EXECUTOR_ERROR, CHILD_FAILED,
  // interruptions (never teach, 3.3)
  DIED, LOGOUT, SHUTDOWN, MOVED_BY_WORLD, PREEMPTED, OWNER_COMMAND, PARENT_ENDED }

record Attempt(AttemptId id, AttemptId parentId, AttemptId rootId, long decisionId, Activity activity,
               Policy policy, Priority priority, EnumSet<Resource> claims, long admittedAt, long deadlineAt,
               AttemptState state, Reason reason, String evidence) {}

interface ActivityAdapter {                       // replaces LifeAcceptance.Executor; one per activity family
  Admission admit(Attempt proposed, ActorView actor, CapabilityView caps);  // pure check, no side effects
  void start(Attempt admitted, Steps steps);      // begins mechanics in the existing executor
  Progress poll(Attempt running, long now);       // cheap; called on the actor's own tick
  Postcondition proof(Attempt running);           // the observable success test (2.5)
  void compensate(Attempt ending, Reason why);    // finish or roll back a transactional step before release
  ChildPolicy onChildEnd(Attempt parent, Attempt child);   // CONTINUE | RETRY | RECOVER(child) | FAIL
}
record Admission(boolean admitted, Reason reason, EnumSet<Resource> claims, long budgetMs, String fact) {}
```

### 2.2 The single transition mechanism

All state changes go through **one method**, `ActionLedger.transition(AttemptId, AttemptState to, Reason, evidence)`,
on the actor's own tick thread, under the ledger's existing per-actor `ReentrantLock`.
- It checks the transition table below. An illegal move is refused, counted (`illegalTransition`), and logged with
  a stack trace under a new `QaLog.Topic.ACTION`.
- A terminal move is compare-and-set: the first one wins, and a second terminal call returns false and counts
  `duplicateTerminal`. Today's `finish(number != active) -> false` already behaves this way.
- Only a terminal transition emits `LifeOutcomes` (exactly once).

| From \ To | ADMITTED | RUNNING | SUCCEEDED | FAILED | INTERRUPTED | TIMED_OUT | REJECTED |
|---|---|---|---|---|---|---|---|
| PROPOSED | admit ok | - | - | - | - | - | admit refused |
| ADMITTED | - | adapter `start` | - | start threw (EXECUTOR_ERROR) | safety/preempt | - | - |
| RUNNING | - | - | `proof` true | adapter fail / CHILD_FAILED / NO_PROGRESS | safety, preempt, owner, parent ended | deadline passed | - |

Mapping from today's `LifeAcceptance.Status`:
- ACCEPTED -> ADMITTED then RUNNING.
- REJECTED / UNSUPPORTED -> REJECTED with a reason.
- FAILED -> FAILED(EXECUTOR_ERROR).
- HELD / NO_CHANGE / SHADOW are pre-proposal outcomes of the decision, not attempts.

**No bypass.** `TrainingBot` no longer calls `finish` directly; it calls `ledger.request(...)` (2.3).
`LifeActivityQueue.finish` becomes package-private and is used only by `transition`.

The implicit "enterPhase(GRIND) => COMPLETED" rule is deleted. Entering GRIND during a HELP_FRIEND is progress
toward a step, and the attempt only succeeds through `proof`.

A unit test fails if any class outside `action/` calls `finish`. It's a grep-style architecture test, like the
existing `OwnedAltSwitchLineTest` style.

### 2.3 Parent/child attempts (ambiguity A)

Every attempt, including a subtask, has its own `AttemptId`. A child carries `parentId` and its tree's `rootId`.

| Rule | Decision |
|---|---|
| Ownership | A parent owns its children. **At most one RUNNING child per parent**: steps are sequential. Parallel work across domains is separate root attempts (2.4), not siblings. |
| Child state | Children use the same table. A child's claims must be a subset of the parent's: the parent claims for the whole tree at admission. Admission is atomic per tree, so a child is never refused for a claim its parent lacks. |
| Child failure | The parent's adapter decides with `onChildEnd`. Structural reasons -> parent FAILED(CHILD_FAILED, cause = child reason), never retried. Transient reasons (BUSY, CONFLICT, MAP_CAPACITY, PATH_NOT_FOUND, NO_PROGRESS) -> RETRY once per step with backoff, or RECOVER by inserting a recovery child (e.g. RESTOCK when RESOURCE_SHORTAGE). The per-tree retry budget is 3, and exhausting it fails the parent. A child never ends the parent directly. |
| Progress | Each SUCCEEDED child adds its postcondition to the parent's evidence. The **parent succeeds only on its own `proof`**: "all children succeeded" is not proof (a quest succeeds when the quest state is COMPLETED). |
| Cancellation | Propagates down, deepest first: children are INTERRUPTED(PARENT_ENDED), then the parent ends with the original reason. Claims are released after compensation, children before parent. |
| Timeout | Each attempt has `deadlineAt`. A child's deadline is no later than the parent's remaining time. A parent deadline -> children INTERRUPTED(PARENT_ENDED), parent TIMED_OUT. Separately, a NO_PROGRESS watchdog (2.7) ends a RUNNING attempt whose `poll` reports no progress for its budget. |
| Idempotent end | Compare-and-set in `transition`; a duplicate terminal is ignored and counted. |
| Trace | `rootId` is the trace id. The root carries `decisionId` (the `LifeDecision.Result` that chose it). Every terminal event carries `attemptId`, `parentId`, `rootId`, `decisionId`, reason and evidence. Learning events reference the `attemptId` they learned from. |
| Resume | **Nothing resumes mid-attempt.** Logout, restart and shutdown end the tree INTERRUPTED. Transactional steps first finish or roll back in `compensate`: a shop purchase completes or is not taken; a trade window closes through `BotGiveTrade`'s cancel path. The *goal* survives because it lives in `LifeMemory` (plans persist), so the next decision proposes a new root attempt with `resumesRootId` set for the trace. |

**Acceptance test (A):** a deterministic test drives QUEST -> {TRAVEL, RESTOCK -> {TRAVEL, SHOP}, COMBAT, TURN_IN}.
It runs once to success, once with the shop failing transiently (RECOVER), once with a logout mid-COMBAT, and once
with a parent deadline. Each run must produce:
- one connected tree, every attempt ending in exactly one terminal state;
- no RUNNING attempt left after the root ends;
- claims empty at the end.

### 2.4 Resources, conflicts and priority (ambiguity B)

Each attempt declares the **actor resources** it needs. Two attempts of one actor conflict exactly when their claim
sets intersect. Unrelated work (chatting while grinding) stays concurrent because chat claims nothing.

| Resource | Meaning | Typical claimers |
|---|---|---|
| BODY | position, movement, map changes | TRAVEL, GRIND, HANG_OUT, FISH, alt FOLLOW |
| HANDS | attacks, skills, item use in combat | GRIND, COMBAT, alt FIGHT |
| BAG | inventory and mesos changes | SHOP, SELL, TRADE, LOOT-SORT, gifting |
| DIALOG | an open NPC, shop, trade or storage window | SHOP, SELL, TRADE, quest turn-in |
| PARTY | party/guild membership changes | INVITE_PQ, INVITE_GUILD, JOIN |

Examples:
- Trading claims {BODY, BAG, DIALOG}: it can't walk, loot or shop at the same time.
- Grinding claims {BODY, HANDS}: chat replies and PARTY invites can run beside it.

| Rule | Decision |
|---|---|
| Atomic admission | All of an actor's claims are taken in one `admit` call on that actor's tick thread under its ledger lock: all or nothing. No actor ever takes another actor's lock. |
| Cross-actor actions (trade, invite, help) | Each side admits its **own** attempt. The initiator holds only its own claims and waits on the partner's answer within a deadline. Partner refusal or silence -> initiator FAILED(TARGET_REFUSED / TIMEOUT). This is no deadlock by construction: no cycle of held-and-wanted locks exists. |
| World-shared admission | Unchanged and reused: `BotMapCap.admit`, `LifeTownSlots`, PQ lobby caps. An adapter calls them inside `admit` and maps a refusal to MAP_CAPACITY. (HOLES: the party GRIND path skips `BotMapCap.admit`; that is ticket P1-B6.) |
| Priority and preemption | SAFETY (death, logout, shutdown, GM warp) > OWNER (an alt owner's command) > COMMITMENT (in a transaction step, or committed to a party PQ) > AUTONOMOUS. A higher class may preempt a lower one whose claims intersect: the lower attempt ends INTERRUPTED(PREEMPTED / OWNER_COMMAND). |
| Non-preemptible sections | While an attempt is inside a BAG+DIALOG transaction step it is COMMITMENT for at most 10 s; then `compensate` runs and it ends. SAFETY never waits: compensation runs inline. |
| Release order | Children first, then the parent; compensation before releasing claims; claims released in one step. |
| Starvation | An AUTONOMOUS attempt preempted 3 times in 10 min backs off that activity for 5 min (world knowledge, not personality). Waiting requests already expire at 30 s. A world-shared cap that refuses the same actor 5 times in 10 min adds MAP_CAPACITY backoff for that map. |
| Logout / shutdown | `close()` ends every attempt INTERRUPTED(LOGOUT / SHUTDOWN) after compensation (today it ends as CANCELLED). |
| Death | `BotDeaths` -> INTERRUPTED(DIED) for all attempts. |
| Teleport by the world (GM warp, scripted warp, catch-up) | Attempts holding BODY end INTERRUPTED(MOVED_BY_WORLD). Attempts not holding BODY continue. |
| Owner commands (alts) | An owner order is an OWNER attempt and preempts AUTONOMOUS ones. See 2.6. |

### 2.5 Terminal proof (postconditions)

`SUCCEEDED` requires the adapter's `proof` to observe a world-state change during this attempt. Initial set:

| Activity | Proof |
|---|---|
| TRAVEL / MOVE_MAP | actor's map id == target map (and on a foothold) |
| GRIND | EXP or kill count rose by at least the step's goal on the target map, from real kills or valid abstract EXP (mob level from `MapMobIndex.level`, never coerced from -1) |
| RESTOCK / SHOP | item count or mesos delta matches the purchase order |
| SELL / FREE_MARKET | item removed and mesos/listing recorded |
| HELP_FRIEND | 2.8 |
| PARTY_QUEST | PQ stage/clear event for this party |
| INVITE_PQ / INVITE_GUILD | target joined (the callbacks already report the real answer, `TrainingBot.java:1225`, `SocialInitiative.java:754,956`) |
| QUEST step | quest state/objective changed in the character's quest record |
| HANG_OUT / BREAK / FISH | dwell reached its planned length on the planned map (a *routine*: excluded from AMPR, 3.5) |

### 2.6 One implementation, two policy contexts

Shared by roster bots and owned alts: the ledger, attempts, claims, priority, adapters, postconditions,
interruptions, outcomes and observability. Different between them: the **Policy** that proposes attempts.

| | AUTONOMOUS (roster bots) | OWNER_DIRECTED (owned alts) |
|---|---|---|
| Who proposes | `LifeRuntime` (needs, goals, plans) | the owner's orders (`OwnedAltOrders`, `CompanionStance`) and the companion tick (`OwnedAltCompanion`) |
| Default activity | its own choice | FOLLOW owner (BODY), FIGHT engaged mobs (HANDS), per today's rules |
| Priority of its own wants | AUTONOMOUS | none: an alt proposes nothing for itself that leaves the owner (`OwnedAltGate` stays the limit) |
| Learning | experience + world knowledge + social | world knowledge + experience only. Alts have no personality drift. |

Acceptance: the same adapter tests run under both policies. One alt test proves an alt never admits an AUTONOMOUS
attempt that moves it off its owner's map.

### 2.7 Watchdog and deadlines

- Every admitted attempt gets `deadlineAt = admittedAt + budget`. The adapter proposes the budget, capped per
  activity family (e.g. TRAVEL 3 min, RESTOCK 6 min, HELP_FRIEND 15 min, PQ 30 min). The caps are constants in one
  class, tuned from the baseline.
- `poll` reports `Progress(lastProgressAt)`. No progress for the family's stall window (TRAVEL 45 s, GRIND 60 s,
  DWELL n/a) -> FAILED(NO_PROGRESS).
- Checked on the actor's own tick, so it costs nothing extra.
- Today's local watchdogs (grind/climb/wander) stay as executor mechanics. They report progress and never end an
  attempt themselves.
- The open-ended town stay (HOLES bots/training-bot: `phaseDeadlineMs` re-armed every tick while a player talks)
  gets a ceiling via the attempt deadline.

### 2.8 Initial vertical slice: HELP_FRIEND (ambiguity C)

There are two separate scenarios, and both run at L1 (unit, fake world) and at L2 (an auto-QA check on TEST).

**Positive: `bl-hf-ok`**
1. A friend (QA character) stands on a valid training map (`MapMobIndex.level(map) > 0`, inside the bot's level
   band) and asks for help.
2. The bot selects HELP_FRIEND and is ADMITTED with claims {BODY, HANDS}.
3. It runs child TRAVEL (proof: same map), then child ASSIST.
4. ASSIST's proof: the bot damaged a mob on that map within the attempt and the friend was on the map at that time.
5. The parent proof is the same.
6. Assert:
   - one decision -> one root -> two children, all terminal;
   - root SUCCEEDED;
   - `LifeOutcomes` fired once;
   - experience memory records it;
   - HELPER like moved up and the HELP_GIVEN push applied;
   - no world-knowledge suppression added.

**Negative: `bl-hf-town`**
1. The friend stands in a mobless town (`MapMobIndex.level(map) == -1`).
2. Decision: admission refuses with `REJECTED(TARGET_MAP_NO_MOBS)`, and the policy may then propose HANG_OUT with the
   same friend as the fallback. It isn't silently converted.
3. Assert:
   - abstract EXP stays at 0 over at least 3 unobserved accrual ticks (the town-EXP regression, HOLES #8);
   - no attempt RUNNING past its deadline;
   - exactly one REJECTED terminal for HELP_FRIEND;
   - **social/personality memory unchanged** (HELPER like not lowered);
   - world knowledge records "map X: no grind targets" with its dependency keys (3.4);
   - the fallback, if taken, is a separate root with its own outcome.

No other activity migrates until both pass on TEST.

---

## 3. Capabilities and learning

### 3.1 Capability record (ambiguity D)

A capability is something the engine or content must do for an activity to be possible, e.g. `nav.portal.edge`,
`quest.objective.credit`, `combat.mob.template`, `npc.script.present`, `economy.shop.listing`. It is **global**:
true or false for the world, not for one character. **Actor feasibility** (level, job, items, permissions, location)
is separate. It is computed at admission for that actor and never cached beyond the attempt.

```
CapabilityVerdict {
  id                  e.g. quest.objective.credit
  state               MISSING | BROKEN | UNVERIFIED | VERIFIED | CONFIG_DISABLED
  serverSha           build the verdict was observed on
  environment         SIM (L1/L2 fake world) | TEST | LIVE
  contentRev          WZ manifest digest + scripts tree digest
  configFingerprint   hash of ONLY the switches this capability declares it depends on (values never printed)
  clientBuild         only for CLIENT/PROTOCOL capabilities
  evidence            QA run id / check id / attempt id
  observedAt
}
```

**Matching rule:**
- A verdict applies only when every context field matches the current context. Any mismatch reads as UNVERIFIED,
  never VERIFIED.
- SIM never satisfies TEST, and TEST never satisfies LIVE. LIVE verdicts come only from live observation (read-only
  telemetry), never from inference.
- CONFIG_DISABLED is computed live from the current switch values, so it can't go stale.

### 3.2 Where the registry lives

- **Declarations** are a checked-in table: capability id, domain, the switches it depends on, and the checks and
  scanners that prove it. They are code-reviewed, not discovered at runtime.
- **Verdicts** are an in-memory immutable snapshot, rebuilt at boot and on context change. The snapshot is written
  to `data/capabilities.json` next to `data/autoqa/latest.json`, so `test-status`, the dashboard and QA read the same
  file.
- No new DB table: verdicts are reproducible from QA results and scanners. Bot world knowledge persists in
  `LifeMemory` (3.3).

### 3.3 Three kinds of memory

| Memory | Stored in | Written by | Never written by |
|---|---|---|---|
| **World knowledge** (routes, map limits, capability suppressions, temporary backoffs) | new `world` key inside the **v2** `LifeMemory` map, saved by `LifeMemoryStore`. **No version bump:** `LifeMemory(int, Object)` resets on any unknown version (`LifeMemory.java:84-93`), so a v3 would wipe learned memory if 1.7.0 were rolled back to 1.6.4. A v2 reader skips the extra key. | REJECTED and FAILED with structural, feasibility or contention reasons; preemption backoff | interruptions (DIED, LOGOUT, SHUTDOWN, MOVED_BY_WORLD, PREEMPTED, OWNER_COMMAND) |
| **Experience** (attempts and verified outcomes, effectiveness per activity/map) | `LifeLearning` likes and success rate, now fed only by these | SUCCEEDED; FAILED with an *execution* reason the bot owns (NO_PROGRESS while it had resources, TARGET_REFUSED for social asks) | REJECTED, structural reasons, interruptions |
| **Social / personality** (Bartle point, bonds, rivalry, gossip) | existing `LifeMemory` delta, `BotAffinity`, bonds | social outcomes and player/bot interactions (unchanged hooks) | any environment or engine reason |

`LifeLearning.observe` gets the outcome's reason and applies this table. That fixes defect 2 (1, above).

**AMPR and the outcome rates** are defined in D (QA and capability spec, section 6), with windows and denominators.

### 3.4 Reassessment after repair

Every world-knowledge suppression stores its **dependency keys**: capability id(s), map id, route edge, content
revision, config fingerprint, server SHA.
- On boot and on any context change (deploy, content revision, switch flip), the registry publishes a new context.
- Suppressions whose keys changed become **eligible for a probe**.
- A probe is a normal attempt with a `probe` flag, limited to **one per capability per world per 10 min**, by one
  bot, and only if a bot proposes that activity anyway.
- A probe success clears the suppression for all bots that share the key. The verdict becomes
  UNVERIFIED -> "operational"; VERIFIED needs a QA check.
- A probe failure re-suppresses the activity with doubled backoff, capped at 24 h, until the next context change.
- Temporary backoffs (MAP_CAPACITY, PREEMPTED) expire by time only.

---

## 4. Instrumentation and QA integration

- **Counters** (LongAdder, per world): proposed, admitted, rejected by reason, running, succeeded, failed by reason,
  interrupted by reason, timed out, duplicateTerminal, illegalTransition, probes. Exported in
  `data/bot-life.json`, the file behind `test-status`'s "bot life" line, which today has `decisions` and `accepted`.
- **Trace:**
  - every terminal event goes into a per-world ring buffer (50k events, about 10 MB);
  - full child-level trace for QA-tagged actors and for `!botwhy <bot>`.

  `!botwhy` shows the current tree: root, children, claims, deadline and last progress.
- **QA:** auto-QA scenario stages assert on the ledger through `QaWorld` (FakeQaWorld in L1). The new checks are
  `bl-hf-ok`, `bl-hf-town` and `bl-tree` (the 2.3 tree). Each test result includes the attempt ids it observed.

## 5. Migration and compatibility

1. **Phase 1 (slice)** adds `action/` and makes `LifeActivityQueue` the ledger. It moves TrainingBot's `finish` calls
   behind `ledger.request`, deletes the GRIND auto-complete, and fixes learning by reason. It migrates HELP_FRIEND
   only. Other activities keep their current behaviour through a **legacy adapter**:
   - their old COMPLETED/CANCELLED becomes SUCCEEDED/FAILED(EXECUTOR_ERROR) with `evidence="legacy"`;
   - learning ignores legacy failures, which is the safe default.

   Behind `-Dslums.action.authority=true`, default on for TEST and off on live until 1.7.0 ships.
2. **Phase 2:** TRAVEL, GRIND, RESTOCK/SHOP, SELL, PARTY_QUEST, INVITE_* get real adapters and proofs.
3. **Phase 3:**
   - social intents (`IntentBook`) and trades (`BotGiveTrade`) map their End values to reasons;
   - owned alts' FOLLOW, FIGHT and orders run as OWNER_DIRECTED attempts.
4. The only save-format change is an extra `world` key in the v2 `LifeMemory` map. It is not a version bump: a
   1.6.4 jar resets on unknown versions. A rollback test must load a 1.7.0-written row with the 1.6.4 reader and
   keep its seed, delta and goals.

## 6. Performance constraints (3,000+ bots)

- No global locks. The per-actor ledger lock already exists, and adapters run on the actor's own tick.
- Admission is pure and in memory: a target of p99 at or under 50 microseconds, measured in a JMH-free
  microbenchmark test. At about 3,200 bots deciding every 2-6 s, that is under 1,600 admissions/s.
- No DB I/O on the tick. Memory writes stay batched through `LifeMemoryStore` (bot DB share is already gated:
  `BotDbGate`).
- The capability snapshot is immutable and swapped atomically, so readers never lock.
- Watchdog checks are one comparison per tick per running attempt.
- The trace ring is bounded, and the full trace is opt-in.
- Measure before enforcing: Phase 1 records `poll` and `admit` timings into the existing tick metrics
  (`tickMsPerS`).
