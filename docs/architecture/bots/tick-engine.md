# Bot tick engine

## Purpose

One process-wide "wheel" decides *when* each bot's macro brain runs, instead of one OS thread per bot. `BotTickService` is a single driver task that, every 100 ms, dispatches the ticks that have come due onto virtual threads (`src/main/java/soloMapling/server/BotTickService.java:9-26`). It replaces the per-bot `Executors.newScheduledThreadPool(1)` that `BotSM` used to own (`src/main/java/soloMapling/server/BotTickService.java:9-11`).

The wheel is only the *macro* brain. Three other schedules exist beside it and are documented here because they share the same pools and the same shutdown gate: the per-type combat sweep for training bots (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:206-246`), the LOD movement driver (`src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:35-46`, `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:111-123`), and the delayed-side-effect/chain toolkit bots use instead of `Thread.sleep` (`src/main/java/soloMapling/server/BotTiming.java:9-46`, `src/main/java/soloMapling/server/MethodScheduler.java:10-29`).

## Entry points

| Trigger | What it does | Where |
| --- | --- | --- |
| First `register()` of any bot | `ensureDriver()` starts the driver if it is not running | `src/main/java/soloMapling/server/BotTickService.java:89-95`, `src/main/java/soloMapling/server/BotTickService.java:159-171` |
| Bot comes online in the world | `manuallyStartBot` → `bot.startScheduledTask(initialDelay)` → `BotTickService.register` | `src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:184-196`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:374-376` |
| Chat dispatch finds a bot with no session | `bot.startScheduledTask()` (0 delay) | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java:484-493` |
| GM showcase / attack-test bot | `startScheduledTask(2000L)` | `src/main/java/client/command/commands/gm3/BotShowcaseCommand.java:104`, `src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:208-212` |
| Bot re-typed in place | stop old FSM, create new bot, `manuallyStartBot` (fresh `Entry`) | `src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:214-227` |
| First `TrainingBot` reaches `INIT` | `ensureCombatTicker()` starts the 250 ms combat sweep | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1471`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:207-216` |
| Player enters a bot's map | `nudgeSoon(jitter)` pulls the bot's *next* tick forward (150–700 ms) | `src/main/java/soloMapling/ArtificialPlayer/BotMapEntryResponder.java:115-128`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:393-416` |
| Shutdown | `BotShutdown.begin()` runs cancel hooks, then `BotTickService.stop()` drains | `src/main/java/net/server/Server.java:2084-2093` |
| Operator readout | `!env perf` prints wheel dispatch rate, lag, throttle factor | `src/main/java/client/command/commands/gm4/EnvironmentCommand.java:512-513`, `src/main/java/client/command/commands/gm4/EnvironmentCommand.java:625-635`, `src/main/java/soloMapling/server/BotPerfStats.java:88-90` |
| Read-only inspect endpoint | per-bot `lastMs`/`avgMs` of its macro tick | `src/main/java/soloMapling/inspect/InspectViews.java:96-102` |
| Test seam | `BotShutdown.resetForTest()` reopens the one-way gate | `src/main/java/soloMapling/server/BotShutdown.java:80-83` |

No packet, YAML row or `-D` flag starts or re-tunes the wheel; see **Switches**.

## How it works

### One driver pass (every 100 ms)

| # | Action | Where |
| --- | --- | --- |
| 1 | `safeDrive()` wraps the pass so a throwable can never kill the driver task | `src/main/java/soloMapling/server/BotTickService.java:223-229` |
| 2 | Quiesce check: a pass racing `BotShutdown.begin()` dispatches nothing | `src/main/java/soloMapling/server/BotTickService.java:231-234` |
| 3 | Linear scan of `ENTRIES.values()` (volatile reads only) | `src/main/java/soloMapling/server/BotTickService.java:238-240` |
| 4 | Skip when `now < nextDueMs` | `src/main/java/soloMapling/server/BotTickService.java:239-241` |
| 5 | `ticking.compareAndSet(false,true)` — a bot whose previous tick is still running is skipped, never queued | `src/main/java/soloMapling/server/BotTickService.java:242-244` |
| 6 | `lag = now - nextDueMs`; `nextDueMs = Long.MAX_VALUE` ("parked while ticking") | `src/main/java/soloMapling/server/BotTickService.java:245-246` |
| 7 | Satisfy/clear `pendingDueMs` if the requested fire time has already passed | `src/main/java/soloMapling/server/BotTickService.java:247-249` |
| 8 | `BotPerfStats.recordWheelDispatch(lag)`, accumulate `lagSum`/`dispatched` | `src/main/java/soloMapling/server/BotTickService.java:250-252` |
| 9 | `ExecutorServiceManager.runAsync(() -> runTick(e))` — one virtual thread per dispatched tick | `src/main/java/soloMapling/server/BotTickService.java:253` |
| 10 | After the scan: `governorTick(now, lagSum, dispatched)` | `src/main/java/soloMapling/server/BotTickService.java:255-256` |

### One dispatched tick

| # | Action | Where |
| --- | --- | --- |
| 1 | `runTick` calls `e.tick.run()`; any `Throwable` is swallowed by design | `src/main/java/soloMapling/server/BotTickService.java:283-287` |
| 2 | `finally`: `due = now + (noThrottle ? periodMs : periodMs * throttleFactor)` — the period is measured from **completion** | `src/main/java/soloMapling/server/BotTickService.java:288-291` |
| 3 | A pending nudge/reschedule (set while the tick was in flight) pulls the next fire to `min(due, max(pending, now))` | `src/main/java/soloMapling/server/BotTickService.java:292-296` |
| 4 | `nextDueMs` is written **before** `ticking.set(false)` (happens-before tick N → N+1) | `src/main/java/soloMapling/server/BotTickService.java:297-298` |
| 5 | The tick body itself is `BotSM.tickRunnable` | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:75-98` |
| 6 | Skip while an invasion crowd owns the bot, while `isWaiting()` (an FSM pause), or while `AfkCampers.holds(this)` | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:77-85` |
| 7 | `MACRO_TICKS.increment()`; `System.nanoTime()` around `updateState()` | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:86-89` |
| 8 | `finally`: `BotPerformanceMonitor.recordMacroTick(took)` + `noteTickCost(took)` (1/8 fading average, first 3 samples warm-up) | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:90-94`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:104-124` |
| 9 | `BotSM.updateState()` switches on `BotState` IDLE/RUNNING/PAUSE/TRADING/FINISHED; `FINISHED` unregisters the bot | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:286-359`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:493-500` |
| 10 | Subclasses override `updateState()`; `TrainingBot` runs `super.updateState()` first, then its phase machine | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:589-591`, phase enum at `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:294-295` |

There is no `TrainingBot.update()`; `BotSM.updateState()` is the only FSM entry, overridden per bot type and always calling `super.updateState()` first (20 files under `src/main/java/soloMapling/ArtificialPlayer/BotTypes/` call `super.updateState()`; `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:589-591` is the training one, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:940` the FM one).

`BotTiming`/`MethodScheduler` are what tick bodies use for anything that used to sleep: `after()` for one delayed side effect, `chain()` for a scripted sequence (whole chain parks one virtual thread), `pause()` inside a chain (`src/main/java/soloMapling/server/BotTiming.java:54-70`, `src/main/java/soloMapling/server/BotTiming.java:82-104`, `src/main/java/soloMapling/server/BotTiming.java:106-132`; `src/main/java/soloMapling/server/MethodScheduler.java:16-29`). Pacing the FSM's own next step is `BotSM.waitFor(ms)`, which the wheel honours without holding any thread (`src/main/java/soloMapling/ArtificialPlayer/BotSM.java:148-161`).

### Intervals and sizes

| Knob | Value | Set at |
| --- | --- | --- |
| Driver pass | 100 ms, fixed delay (never overlapping passes) | `src/main/java/soloMapling/server/BotTickService.java:34`, `src/main/java/soloMapling/server/BotTickService.java:166-168` |
| Governor window / hi / lo / max / step | 5000 ms / 1000 ms / 300 ms / ×4.0 / ×1.5 | `src/main/java/soloMapling/server/BotTickService.java:72-76` |
| Drain wait after `stop()` | 5000 ms, polled every 25 ms | `src/main/java/soloMapling/server/BotTickService.java:63`, `src/main/java/soloMapling/server/BotTickService.java:190-208` |
| Macro tick, observed | 2000–5999 ms (`2000 + nextInt(4000)`) | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:487-489` |
| Macro tick, unobserved | 9000–11999 ms | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:470-472` |
| Macro tick, commitment in flight | 2000 ms (high priority) | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:449-463`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:479-481` |
| Macro tick, `TrainingBot` GRINDing unobserved | 60 000–119 999 ms | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:254-259` |
| Macro tick, nudge debounce | 1500 ms per bot | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:166`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:401-403` |
| Map-entry nudge | 150–700 ms, max 12 bots per sweep, 300–1200 ms across bots | `src/main/java/soloMapling/ArtificialPlayer/BotMapEntryResponder.java:35-36`, `src/main/java/soloMapling/ArtificialPlayer/BotMapEntryResponder.java:42-44` |
| Spawn stagger for the first tick | 7000 ms + 0–3000 ms random | `src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:192-195`, `src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java:44` |
| Combat sweep | 250 ms fixed rate (was 500) | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:114`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:213-215` |
| Combat swing re-arm cap | `MAX_ARM_MS` 10 000 ms | `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingClock.java:42`, `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingClock.java:92-93` |
| Follower cadence | 750 ms, 200 ms while fighting | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerBot.java:44`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerFight.java:50`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerBot.java:76-79` |
| Blackjack dealer cadence | 2000 ms | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/Blackjack/BlackjackDealerBot.java:73` |
| Social bot cadence | 30 000 ms | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/SocialBot.java:180` |
| Chat dispatch | 2000 ms fixed rate | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java:44` |
| Movement driver | 50 ms physics (active map) / 250 ms moving / 1000 ms idle | `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:111-123`, `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotPhysicsEngine.java:30` |
| Life re-check | 30 000 ms per bot | `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:409-420` |
| Shared scheduler pool | 10 threads (one timer resource for all of the above) | `src/main/java/soloMapling/server/ExecutorServiceManager.java:16` |
| Bot DB / logout platform pools | 8 ("bot-db-N") + 4 ("bot-logout-N"), daemon | `src/main/java/soloMapling/server/ExecutorServiceManager.java:22-38` |
| Shutdown budgets | players 60 000 ms, bots 60 000 ms + 5000 ms grace, 4 savers/writers each | `src/main/java/soloMapling/server/ShutdownSaves.java:29-44`, `src/main/java/soloMapling/server/ShutdownSaves.java:63-79` |

**Bots per tick.** A pass dispatches **every** entry whose `nextDueMs` has passed and whose previous tick has finished — there is no per-pass cap, no semaphore and no batching (`src/main/java/soloMapling/server/BotTickService.java:238-256`). Concurrency is therefore "number of bots whose turn has come and which are not already ticking", one virtual thread each. At a 3000-bot roster (`docs/release/1.6.4.conf:201`) on a 2–6 s cadence that is roughly 500–1500 dispatch/s average, in bursts after a stall. The combat sweep is the only bounded fan-out: it visits `ACTIVE_GRINDERS.size()` bots once per 250 ms (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:226-241`).

### CPU budget mechanism

There is **no CPU budget, tick-time cap, rate limiter or watchdog** anywhere in the wheel: the only budget-shaped controls are cadence and one lag-driven governor.

1. **Cadence tiers (per bot, by LOD visibility).** `checkPrioritySpeed()` asks `checkMainPlayersOnMap()` (observer tracker when running, else a map scan) and picks 2–6 s observed / 9–12 s unobserved (`src/main/java/soloMapling/ArtificialPlayer/BotSM.java:425-444`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:273-284`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:470-489`). A player-facing commitment pins 2 s regardless of visibility (`src/main/java/soloMapling/ArtificialPlayer/BotSM.java:449-463`).
2. **Governor v1 (whole wheel).** `governorTick` sums dispatch lag over a 5 s window; above 1000 ms average it multiplies `throttleFactor` by 1.5 up to ×4, below 300 ms it divides back to ×1, logging `"[BotTickService] wheel lag avg %dms - stretching cadences x%.2f"` / `"wheel recovered (avg %dms) - cadence factor x%.2f"` (`src/main/java/soloMapling/server/BotTickService.java:258-281`). Stretching is applied only to steady-state periods in `runTick`; nudges and explicit reschedules bypass it (`src/main/java/soloMapling/server/BotTickService.java:289-291`, `src/main/java/soloMapling/server/BotTickService.java:148-154`), and bots marked `noThrottle` (FollowerBot) never stretch (`src/main/java/soloMapling/server/BotTickService.java:45-46`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerBot.java:84-89`).
3. **Deep-background override.** `TrainingBot` drops to 60–120 s while GRINDing off-screen because abstract EXP accrues by elapsed time (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:254-259`).
4. **DB share (not CPU).** Bot database work is capped by a fair semaphore, `PERMITS = max(3, MAX_POOL_SIZE 10 − PLAYER_RESERVE 4) = 6`, with platform threads waiting up to 15 s and virtual threads failing fast (`src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:81-103`, `src/main/java/tools/DatabaseConnection.java:251-259`; `src/main/java/tools/DatabaseConnection.java:33`).
5. **Measurement only.** `BotPerformanceMonitor` records per-phase ms/s and `BotPerfStats` records dispatch lag/combat sweep cost; nothing reads them to throttle anything (`src/main/java/soloMapling/server/BotPerfStats.java:8-11`, `src/main/java/soloMapling/server/BotPerfStats.java:62-99`).

### Thread model

| Layer | Thread | Why it matters |
| --- | --- | --- |
| Driver | One task on the shared 10-thread scheduled pool, `scheduleWithFixedDelay` | Passes cannot overlap; if a pass is slow the next starts 100 ms after it ends (`src/main/java/soloMapling/server/BotTickService.java:166-168`) |
| Tick body | `ExecutorServiceManager.runAsync` → `Executors.newVirtualThreadPerTaskExecutor()` | A tick that blocks (bot code still calls `Thread.sleep`/`BotHelpers.blockingSleep` at 77 sites under `src/main/java/soloMapling/ArtificialPlayer`) parks only its own virtual thread (`src/main/java/soloMapling/server/ExecutorServiceManager.java:17`, `src/main/java/soloMapling/server/BotTickService.java:62-64`; `src/main/java/soloMapling/server/BotTickService.java:20-21`) |
| Virtual-thread carrier | Default JDK `ForkJoinPool` scheduler; no `jdk.virtualThreadScheduler.*` tuning anywhere in `src/main` or `docs/release/1.6.4.conf` | Carrier count is the JDK default (one per core) |
| Bot DB / logout | Dedicated platform pools, not virtual threads | On Java 21 a virtual thread that blocks inside a `synchronized` block pins its carrier and `BotDbGate` cannot see monitors, so it refuses to make a virtual thread wait (`src/main/java/soloMapling/server/ExecutorServiceManager.java:18-21`; `src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:42-47`, `src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:63-66`, `src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:251-259`) |
| Life activity queue | `ReentrantLock`, deliberately not `synchronized` | Comment: "Start callbacks can wait on game locks. Keep serialization without pinning Java 21 carriers." (`src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeActivityQueue.java:38-39`) |
| Swing clock | `tryLock` on the same pattern; missed sweeps skip instead of blocking | `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingClock.java:44-45`, `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingClock.java:76-93` |

Java 21 is the compilation target (`pom.xml:49-51`).

### What happens when a tick overruns

| Overrun shape | Consequence | Where |
| --- | --- | --- |
| A tick simply takes longer than its period | No pile-up: the next fire is computed from completion, so the bot's effective cadence is `tickCost + period` | `src/main/java/soloMapling/server/BotTickService.java:288-291` |
| Many ticks late at once | Dispatch lag accumulates; `!env perf` shows `lag avg`/`max`; the governor stretches steady cadences ×1.5 per 5 s up to ×4 | `src/main/java/soloMapling/server/BotTickService.java:258-281`, `src/main/java/soloMapling/server/BotPerfStats.java:88-90` |
| A tick hangs forever | The entry's `ticking` flag stays `true`; the driver skips that bot forever (`compareAndSet` never succeeds) — no timeout, no watchdog, no log line naming the bot | `src/main/java/soloMapling/server/BotTickService.java:242-244`, `src/main/java/soloMapling/server/BotTickService.java:283-298` |
| A tick throws | Swallowed twice (in `runTick` and in `tickRunnable`); the schedule survives | `src/main/java/soloMapling/server/BotTickService.java:284-287`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:95-97` |
| Long wait inside an FSM | `waitFor(ms)` skips ticks without holding a thread | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:151-161`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:80-82` |
| Shutdown with a tick in flight | `stop()` cancels the driver, polls `tickingCount()` for up to 5 s, then clears every entry and logs `"[BotShutdown] macro wheel drained in Nms (residue N ticks)"` | `src/main/java/soloMapling/server/BotTickService.java:190-208`, `src/main/java/soloMapling/server/BotTickService.java:213-221` |
| Combat sweep overruns | The sweep is fixed-rate on one pool thread; a slow sweep delays the next sweep (no overlap guard beyond the pool), and `BotPerfStats.recordCombatSweep` records last/max | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:213-215`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:226-241`, `src/main/java/soloMapling/server/BotPerfStats.java:41-47` |
| Bot-phase save overruns | Bounded by `slums.shutdown.botBudgetMs` + 5 s grace, then the shutdown proceeds with a warning | `src/main/java/soloMapling/server/ShutdownSaves.java:63-79` |

## Data

| Store | Shape | Where |
| --- | --- | --- |
| `BotTickService.ENTRIES` | `ConcurrentHashMap<Integer, Entry>`, keyed by character id (bots are ≥1,000,000,000) | `src/main/java/soloMapling/server/BotTickService.java:55` |
| `Entry` | `final Runnable tick`, `volatile long periodMs`, `volatile long nextDueMs`, `volatile long pendingDueMs` (0 = none), `volatile boolean noThrottle`, `AtomicBoolean ticking` | `src/main/java/soloMapling/server/BotTickService.java:36-52` |
| Governor state | `volatile double throttleFactor` (1.0–4.0) + three non-volatile window accumulators touched only by the driver thread | `src/main/java/soloMapling/server/BotTickService.java:65-81` |
| `BotShutdown` | `AtomicBoolean QUIESCED` + `CopyOnWriteArrayList<Runnable> HOOKS`, one-way per JVM | `src/main/java/soloMapling/server/BotShutdown.java:31-32`, `src/main/java/soloMapling/server/BotShutdown.java:42-71` |
| `BotPerfStats` | `LongAdder` per counter: `MACRO_TICKS`, `MOVEMENT_TICKS`, `IDLE_BROADCASTS`, `NUDGES`, `WHEEL_DISPATCHES`, `WHEEL_LAG_MS`; `volatile` maxima (never reset) and combat last/max/count | `src/main/java/soloMapling/server/BotPerfStats.java:18-47` |
| Per-bot tick cost | `lastTickNanos`, `avgTickNanos` (1/8 fading average), `tickSamples`, all volatile; exposed by the inspect endpoint | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:104-134`; `src/main/java/soloMapling/inspect/InspectViews.java:96-102` |
| Combat roster | `ConcurrentHashMap.newKeySet()` of training bots in `GRIND` | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:202`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2125`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2577`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:3069` |

Nothing is persisted; every value above is process-local and dies with the JVM.

## Switches

| Switch | Default | Live (per `docs/release/1.6.4.conf` SERVER_SET / `UNIT_EDITS`) | Where |
| --- | --- | --- | --- |
| `-Dslums.shutdown.playerBudgetMs` | 60000 | not in `UNIT_EDITS` (`docs/release/1.6.4.conf:271` has no such flag) | `src/main/java/soloMapling/server/ShutdownSaves.java:29` |
| `-Dslums.shutdown.botBudgetMs` | 60000 | not in `UNIT_EDITS` | `src/main/java/soloMapling/server/ShutdownSaves.java:32` |
| `-Dslums.botswingclock` | `true` | not in `UNIT_EDITS` | `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingClock.java:22-26`, `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingClock.java:56-59` |
| `BOT_ROSTER_PEAK_ONLINE` / `BOT_POPULATION_PEAK` | 1200 / 1600 | `SERVER_SET` 3000 / 3500 | `src/main/java/config/ServerConfig.java:139`, `src/main/java/config/ServerConfig.java:154`; `docs/release/1.6.4.conf:201-214` |
| `BOT_DIRECTOR_BOOT_FILL_PER_SECOND` / `_IN_FLIGHT` / `BOT_DIRECTOR_TICK_SECONDS` | 8 / 8 / 30 | `SERVER_SET` 2 (fill rate) | `src/main/java/config/ServerConfig.java:143-149`; `docs/release/1.6.4.conf:201` |
| `-Dslums.qa` (`QaSuite`) | off | QA-only, not a live flag | `src/main/java/soloMapling/qa/QaSuite.java:33`, `src/main/java/soloMapling/qa/QaSuite.java:77-79` |
| `-Dsolomapling.enabled` + `-Dsolomapling.template-id` | false / 0 (both required: `isEnabled()` ANDs a template id > 0) | `UNIT_EDITS` carries other `-Dsolomapling.*` bot flags but not these two, so the on/off pair is live-inherited, not set per release (`docs/release/1.6.4.conf:271`) | `src/main/java/soloMapling/server/MapleRootSolo.java:16-25` |

There is **no switch at all** for the driver period, the governor thresholds, `DRAIN_MS`, the combat tick period, the macro cadences or any CPU budget — all are `private static final` constants (`src/main/java/soloMapling/server/BotTickService.java:34`, `src/main/java/soloMapling/server/BotTickService.java:72-76`; `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:114`; `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:166`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:470-489`). Re-tuning means an edit and a rebuild.

TEST: unknown (`docs/release/1.6.4.conf`'s live set is the only release-side evidence available here).

## Invariants

1. **Never two concurrent ticks for one bot.** The per-entry CAS is the only gate; every dispatch path goes through it (`src/main/java/soloMapling/server/BotTickService.java:242-244`). `reschedule`/`nudge` move due times, never create futures (`src/main/java/soloMapling/server/BotTickService.java:120-154`).
2. **Wakeups are never lost.** `pendingDueMs` is written before the "can I move `nextDueMs`?" test, so a nudge that lands mid-tick is consumed by that tick's completion (`src/main/java/soloMapling/server/BotTickService.java:148-154`, `src/main/java/soloMapling/server/BotTickService.java:292-296`).
3. **Tick N happens-before tick N+1.** `nextDueMs` is written before the `ticking` release, and both are volatile (`src/main/java/soloMapling/server/BotTickService.java:297-298`; `src/main/java/soloMapling/server/BotTickService.java:13-15`).
4. **The driver never dies.** `safeDrive` catches `Throwable` (`src/main/java/soloMapling/server/BotTickService.java:223-229`).
5. **A quiesced wheel accepts no work.** `register`, `reschedule`, `nudge` and `ensureDriver` all return early when `BotShutdown.isQuiesced()` (`src/main/java/soloMapling/server/BotTickService.java:89-91`, `src/main/java/soloMapling/server/BotTickService.java:120-123`, `src/main/java/soloMapling/server/BotTickService.java:133-136`, `src/main/java/soloMapling/server/BotTickService.java:160-162`), and `drive()` checks again per pass (`src/main/java/soloMapling/server/BotTickService.java:232-234`).
6. **One cancel hook per wheel instance.** `CANCEL_DRIVER` is a stable method reference so the gate's identity dedupe cannot stack hooks across stop/start (`src/main/java/soloMapling/server/BotTickService.java:155-158`, `src/main/java/soloMapling/server/BotShutdown.java:42-56`).
7. **A bot's own error does not kill its schedule.** Caught in `runTick` and again in `tickRunnable` (`src/main/java/soloMapling/server/BotTickService.java:284-287`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:95-97`).
8. **Combat is presentation.** The sweep may be cancelled without draining; a dead bot does nothing (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:218-224`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:262-267`).
9. **Virtual threads never wait on the bots' DB share.** Platform threads may (`src/main/java/soloMapling/server/ExecutorServiceManager.java:18-21`, `src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:251-259`).

## Origin

custom — the whole `soloMapling/` tree is absent from upstream Cosmic (`/root/workspace/cosmicms/src/main/java` has `client config constants database model net provider scripting server service tools` only — no `soloMapling`). Oldest commits (`git log --follow --format='%h %ad %s' --date=short -- <path> | tail -2`):

| File | Oldest commits |
| --- | --- |
| `src/main/java/soloMapling/server/BotTickService.java` | `1512c08a6 2026-10-19 Merge the SoloMapling persistence fork onto the live baseline`; `03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline` |
| `src/main/java/soloMapling/server/BotTiming.java`, `src/main/java/soloMapling/server/MethodScheduler.java` | same two |
| `src/main/java/soloMapling/server/BotPerfStats.java` | `03d13cb21` only |
| `src/main/java/soloMapling/server/ExecutorServiceManager.java` | `a87a50dd7 2026-10-31 Bot DB gate: hard cap on the bots' share of the pool (verbatim import)`; `03d13cb21` |
| `src/main/java/soloMapling/server/ShutdownSaves.java` | `32806f0a3 2026-10-24 Report failed player saves accurately at shutdown`; `4d08a0cba 2026-10-23 Save real players first at shutdown and give the bot save a 60 s budget` |
| `src/main/java/soloMapling/server/BotShutdown.java` | `1512c08a6` only |
| `src/main/java/soloMapling/ArtificialPlayer/BotSM.java` | `16885cd81 2026-10-20 Name a bot for a chat reply; window only on double-click (ticket 37)`; `1512c08a6` |
| `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java` | `a8047c5d1 2026-10-19 Keep high-level bots grinding when nothing is in their band`; `1512c08a6` |

`docs/architecture/server/boot-and-shutdown.md:253` independently records `BotTickService.java`, `ShutdownSaves.java` and `BotShutdown.java` as custom (ticket 09, commit `4d08a0cba`).

## Tests and QA

| Coverage | What it proves | Where |
| --- | --- | --- |
| `BotShutdownTest` (6 tests, runs against the real 100 ms driver) | gate closes once and runs every hook exactly once; a hook added after the close runs immediately; a failing hook does not stop the rest; a quiesced wheel registers/nudges/reschedules nothing (`aQuiescedWheelAcceptsNoNewWork`); `stop()` waits for an in-flight tick and empties the map (`stopWaitsForATickThatIsAlreadyRunning`); a stopped wheel restarts after `resetForTest` | `src/test/java/soloMapling/server/BotShutdownTest.java:15-18`, `src/test/java/soloMapling/server/BotShutdownTest.java:29-121` |
| `ShutdownSavesTest` (4 tests) | every "player" is saved before any bot work starts (`src/test/java/soloMapling/server/ShutdownSavesTest.java:24`); a bot phase that ignores its budget does not hold the shutdown past budget + grace (`src/test/java/soloMapling/server/ShutdownSavesTest.java:50`); a hung player save is reported and does not hold the shutdown forever (`src/test/java/soloMapling/server/ShutdownSavesTest.java:78`); success/failure/timeout are reported distinctly (`src/test/java/soloMapling/server/ShutdownSavesTest.java:102`) | `src/test/java/soloMapling/server/ShutdownSavesTest.java:24-138` |
| `BotScaleProof` (test-scope main, not a unit test) | measures `WHEEL_LAG_MS`/`WHEEL_DISPATCHES`/`throttleFactor()` and heap at 3300 and 4100 bots with a 200-bot churn, window 30 s, writes `/tmp/botscale/results.txt` | `src/test/java/soloMapling/ArtificialPlayer/BotScaleProof.java:22-34`, `src/test/java/soloMapling/ArtificialPlayer/BotScaleProof.java:144-165` |
| Mocked seams | `MethodScheduler.runAfterDelay` mocked in `src/test/java/soloMapling/ArtificialPlayer/BotAttackSystem/KeydownBurstDeathTest.java:57-59`, `src/test/java/soloMapling/FreeMarket/ShopOfferSystem/PendingOfferHoldTest.java:47,73-74`; `BotTiming` in `src/test/java/soloMapling/ArtificialPlayer/BotConvo/BotTalkSayDoTest.java:193,229`, `src/test/java/soloMapling/ArtificialPlayer/BotFamilyTestSeams.java:48`; `BotTickService` in `src/test/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBotLifeExecutionTest.java:204` | as listed |
| Auto-QA | only `t-bt-1` ("Bot talk: greet, ask, answer by goals, act…") touches bots, and its probe never reads wheel state | `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1387-1389`, `src/main/java/soloMapling/autoqa/Probes160.java:2927` |
| `!qa` scenarios (62 files under `src/main/java/soloMapling/qa/`, e.g. `src/main/java/soloMapling/qa/BotLifeScenarios.java:36`, `src/main/java/soloMapling/qa/BotLifeScenarios.java:79`, `src/main/java/soloMapling/qa/BotLifeScenarios.java:190`) | drive bots through goals/life/trade flows; none assert scheduler behaviour | `src/main/java/soloMapling/qa/QaScenario.java:55` |

**Not covered:** no test asserts the governor ever stretches or recovers; nothing asserts `pendingDueMs` survives a nudge landing mid-tick (the "wakeups are never lost" invariant is only a comment); no test asserts a hung tick's residue is logged or that a hung bot is ever noticed; the combat sweep and `BotSwingClock` are untested at the scheduling level; the `noThrottle` exemption is untested; no auto-QA check reads `throttleFactor`, wheel lag, macro-tick cost or `DUE_TICKS`. `!env perf` and `BotScaleProof` are the only readouts and both are manual/opt-in.

## Known issues

| Id | Issue |
| --- | --- |
| NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:487-489` | `getRandomDelay()` returns `2000 + random.nextInt(4000)` = 2000–5999 ms but the comment says `// 2000 to 3000 ms`; the two other comments in the same file correctly say 2–6 s (`src/main/java/soloMapling/ArtificialPlayer/BotSM.java:386`, `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:408`). Readers tuning cadence from the comment will be off by 2×. |
| NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:95` | Header still says "one shared 0.5s ticker" while `COMBAT_TICK_MS` is 250 (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:114`) — the header describes the pre-change cadence. |
| NEW-FINDING: `src/main/java/soloMapling/server/BotTickService.java:242-244` | No watchdog: a tick that blocks forever leaves `ticking = true` permanently, so that bot never runs again and nothing logs it. Only `stop()` (5 s drain) notices, as a residue count (`src/main/java/soloMapling/server/BotTickService.java:190-208`). |
| NEW-FINDING: `src/main/java/soloMapling/server/BotTickService.java:238-256` | No dispatch cap per pass and no semaphore: one pass can submit thousands of virtual threads at once after a stall, which is exactly when the machine is already behind. The governor reacts only after 5 s of lag. |
| NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:218-224` | `stopCombatTicker` cancels the future but never clears `combatTickerStarted`, so the sweep can never be restarted in the same JVM (matters for the `resetForTest` path and for tests). |
| NEW-FINDING: `src/main/java/soloMapling/server/BotPerfStats.java:26`, `src/main/java/soloMapling/server/BotPerfStats.java:28-34`, `src/main/java/soloMapling/server/BotPerfStats.java:90` | `wheelLagMaxMs` is never reset, so `!env perf`'s "lag max" is a lifetime maximum, not the window the other numbers describe (contrast the delta-based rates at `src/main/java/soloMapling/server/BotPerfStats.java:84-98`). |
| NEW-FINDING: `src/main/java/soloMapling/server/ExecutorServiceManager.java:140-145` | `shutdown()` has no caller anywhere in `src/main/java`; the shared pools (including the wheel's driver thread) are only stopped by `System.exit`. |
| NEW-FINDING: `src/main/java/soloMapling/server/MethodScheduler.java:36-38` | `getPendingTaskCount()` returns a hardcoded 0 and has no callers — a leftover from the pre-F12 pool. |
| NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:58-62` | `updateAllBots()` still loops active bots calling `bot.updateState()` directly, bypassing the wheel's no-overlap guard. No callers today, but it is a second, unrouted tick path left in the tree. |
| NEW-FINDING: `src/main/java/soloMapling/server/BotTickService.java:258-281` | The governor's "avg lag" is dominated by whichever cohort just came due, not by real load: a single 3500-bot pass that happens to coincide inflates the 5 s window and stretches everyone (including bots that were never late). |
| b240 (`/root/work/STATUS-1.6.4.md:59`) | "Regular bots say they're grinding while standing in town" — a cadence/phase interaction, fixed by `23fe8f08f` + `3aba87f15`. |
| e03 (`/root/work/STATUS-1.6.4.md:113`) | "1.6.4 scoping: tiered bot ticking by visibility" — owner-approved plan that would replace `BotSM.checkPrioritySpeed`'s observed/unobserved tiers; not started, so the tiers described above are still the code. |
| b284 (`/root/work/STATUS-1.6.4.md:80`) | Auto-QA at 10 lanes gives false failures (scheduler key declarations miss shared state) — QA-tool defect, i.e. the harness around this engine is itself unreliable under load. |

No status card names the wheel or the governor directly, so no card can be cited as evidence that the 100 ms wheel or the ×1.5 governor is proven in production.

## State

**working, with watchdog gaps.** Evidence:

- The wheel is exercised end-to-end against the real 100 ms driver by `src/test/java/soloMapling/server/BotShutdownTest.java:15-18`, including drain (`src/test/java/soloMapling/server/BotShutdownTest.java:87-107`) and restart (`src/test/java/soloMapling/server/BotShutdownTest.java:110-121`).
- Shutdown ordering is proven by `src/test/java/soloMapling/server/ShutdownSavesTest.java:23-48` and wired at `src/main/java/net/server/Server.java:2084-2093`.
- Production cadence control (tiers + governor) is live code with no test and no QA check: the only measurement is `!env perf` (`src/main/java/soloMapling/server/BotPerfStats.java:88-90`), and the load proof `BotScaleProof` is an opt-in test-scope main (`src/test/java/soloMapling/ArtificialPlayer/BotScaleProof.java:22-34`).
- The overrun story is partial: overrun-by-lateness is handled by design (`src/main/java/soloMapling/server/BotTickService.java:288-291`), overrun-by-hang is not handled at all (`src/main/java/soloMapling/server/BotTickService.java:242-244`).

## Open questions

1. Was `COMBAT_TICK_MS = 250` validated at 3000+ bots? The code comment explicitly says "UNTESTED: validate the combat sweep cost with !env perf before/after; revert to 500 if it climbs" (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:112-114`) — is there a `BotScaleProof`/`!env perf` reading that closes it?
2. Is `throttleFactor` ever observed above 1.0 on live? Neither `docs/release/1.6.4.conf` nor `/root/work/STATUS-1.6.4.md` records a "wheel lag avg … stretching cadences" line; without a log sample the governor is unproven at live population.
3. Should the `noThrottle` exemption (FollowerBot, `src/main/java/soloMapling/server/BotTickService.java:45-48`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerBot.java:85`) extend to other player-facing types (Blackjack dealer at 2 s, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/Blackjack/BlackjackDealerBot.java:73`), or is the commitment path (`src/main/java/soloMapling/ArtificialPlayer/BotSM.java:449-463`) considered sufficient?
4. Was there a reason the shutdown drain is 5 s (`src/main/java/soloMapling/server/BotTickService.java:63`) while the bot save budget is 60 s (`src/main/java/soloMapling/server/ShutdownSaves.java:32`)? A tick that overruns the drain is abandoned while its bot is still being settled.
5. Is `CharacterStorage.updateAllBots()` (`src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:58-62`) safe to delete, or is it kept as an offline bulk seam for QA?
