# TrainingBot

## Purpose

`TrainingBot` is the grind-loop bot: the one bot type that fights, loots, restocks, changes maps and (when the
brain is on) also takes social/town life trips. It is the "full host" of the life brain — `LifeHost`'s javadoc names
it as such and the town/social bots as light hosts (`src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeHost.java:9-13`).

Two motors drive it, deliberately at different speeds:

| Motor | Period | Where | What it does |
|---|---|---|---|
| Shared combat sweep | 250 ms | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:114,207-215,227-241` | For every bot in `ACTIVE_GRINDERS` (`:202`), one `sweepVisit` → `grind.tick(chr)`; only for observed maps |
| Per-bot macro tick | 2–6 s observed, 9–12 s unobserved, 60–120 s while GRINDing unsaw | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:470-489`; override `TrainingBot.java:255-259` | One `updateState()` → one phase step |

The macro tick is the phase machine; the combat sweep is presentation ("Combat is presentation - nothing needs
draining for it", `TrainingBot.java:219`).

## Entry points

| Entry | Kind | Detail |
|---|---|---|
| `BotTypeManager.TRAINING_BOT.createAndSetBot` | construction | `new TrainingBot(character)` at `src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:166-169` |
| `BotSM.startScheduledTask(long)` | tick registration | `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:374-376` → `BotTickService.register(id, tickRunnable, initialDelay, getRandomDelay())`; TrainingBot's override reopens the life queue first (`TrainingBot.java:3053-3057`) |
| `BotTickService` wheel | scheduler | `DRIVER_PERIOD_MS = 100` (`src/main/java/soloMapling/server/BotTickService.java:34,167`), per-entry `period/nextDue` |
| `BotSM.tickRunnable` | tick body | `BotSM.java:75-97`; skips while `isWaiting()` or `AfkCampers.holds(this)`, counts `BotPerfStats.MACRO_TICKS`, wraps `updateState()` in try/catch |
| `TrainingBot.updateState()` | the machine | `TrainingBot.java:589-672` |
| `ensureCombatTicker()` | shared sweep start | `TrainingBot.java:207-215`, called from `doInit()` `:1471`; cancelled by `BotShutdown.onQuiesce` `:216` |
| `!bot trainbot <cid>` | GM command | converts a bot to `TRAINING_BOT` via stop→set→start `src/main/java/client/command/commands/gm4/ArtificialPlayerCommand.java:284-289` |
| `!bot breaknow <cid>` | GM command | `tb.forceBreakNow()` `ArtificialPlayerCommand.java:290-300`, help text `:931` |
| `!bot restspot <cid>` | GM command | `RestSpotFinder.debug(chr)` `ArtificialPlayerCommand.java:301-309`, help `:932` |
| `!botwhy <name>` | GM inspection | `src/main/java/client/command/commands/gm3/BotWhyCommand.java:17-24,47+` (gated on `LifeRuntime.enabled()` or QA + `-Dslums.botlife.preview`) |
| `CombatStaging` | QA staging | `tb.forceRopeBreakNow()` `src/main/java/soloMapling/ArtificialPlayer/BotCombatSystem/CombatStaging.java:268`; `tb.forceGrindOn(map)` `:383` |
| `GameQaWorld` life hooks | QA staging | `triggerLifeNeed` `src/main/java/soloMapling/qa/GameQaWorld.java:1506-1516`, `prepareLifeRestock` `:1520-1531`, `submitLifeRestock` `:1533-1537`, `botLifeReport` `:1539-1549`, `forceLifeChannel` `:1673+` |
| `TrainingBotDialogue.yaml` | YAML rows | dialogue path set in the constructor `TrainingBot.java:407-414` |
| `config.yaml` `BOT_COMBAT_*` etc. | config keys | see `## Switches` |

## How it works

### The tick, in order

| # | Step | file:line |
|---|---|---|
| 1 | `super.updateState()` — base FSM, `checkPrioritySpeed()` on RUNNING | `TrainingBot.java:590` → `BotSM.java:289-308` |
| 2 | `checkIfNotRunningOrPaused()` → return (not running, or `BotState.PAUSE`) | `TrainingBot.java:591-593`, `BotSM.java:546-552` |
| 3 | null character/map → return | `TrainingBot.java:595-598` |
| 4 | rebirth boss run or job errand holds it → `leaveGrind()` once, return; on release `enterPhase(DECIDE)` | `TrainingBot.java:599-610` |
| 5 | `handleDeath(chr)` true → `cancelLifeActivity()`, return | `TrainingBot.java:614-617`, `:737-767` |
| 6 | `restockCall` → when `GRIND` and not `recruitingNow()`: `leaveGrind()`, `GO_TOWN`, `SayDo.event(RESUPPLY)` | `TrainingBot.java:619-627` |
| 7 | `eventCall` → `cancelLifeActivity()`, `answerEventCall(chr)` | `TrainingBot.java:628-632`, `:1436-1462` |
| 8 | `leaveEventMapIfOver(chr)` | `TrainingBot.java:633`, `:1155-1181` |
| 9 | `pausedForChat(chr)` true → poll recruit/solo/party menus, `return` ("a player is talking to it: no trip out of here until the chat hold ends") | `TrainingBot.java:635-640` |
| 10 | `if (LifeRuntime.enabled()) dailyLife.tick(now());` then `runLifeActivities()` | `TrainingBot.java:643-644`, `:843-861` |
| 11 | `switch (phase)` — the 14 phase handlers | `TrainingBot.java:646-663` |
| 12 | `pollRecruitInvite()`, `soloMenu.poll()`, `partyMenu.poll()` ("kept last: a 'Follow me!' selection converts this bot away"), `SocialIntents.onTick`, `ModeSwitch.maybeSwitch(this, dailyLife, now())` | `TrainingBot.java:665-670` |

`updateState` swallows nothing itself; exceptions are caught by `BotSM.tickRunnable` (`BotSM.java:97`).

### Phase table

`enum Phase { INIT, IN_TOWN, SHOP_TRAVEL, SHOP_DWELL, SHOP_RETURN, DECIDE, GO_TRAIN, GRIND, GO_TOWN, BREAK_TRAVEL,
BREAK_REST, LIFE_TRAVEL, LIFE_DWELL, REBIRTH_TRAVEL }` — `TrainingBot.java:294-295`.

| Phase | Entered by (file:line) | What the tick runs | Exits to (file:line) |
|---|---|---|---|
| `INIT` | field default `TrainingBot.java:300` | `doInit()` `:1470-1480`: `ensureCombatTicker()`, `homeMapId = homeTown(here, returnMapId, MapMobIndex::level)`, `resumeGrind(BotRoster.takeResume(id))` | `GRIND` when a roster resume matched (`:1487-1503`, entered `:1501`); else `IN_TOWN` if `MapMobIndex.level(here) < 0`, else `DECIDE` (`:1479`) |
| `IN_TOWN` | `GO_TOWN` arrival, `handleDeath` `:765`, `stayHere` `:2932`, `doDecide` no-mob fallback `:1999`, `leaveEventMapIfOver` `:1179`, sauna finish `:1530` | `doInTown()` `:1519-1597`: the sauna trip parks it (`:1522-1531`); first tick does `BotSupplies.arriveInTown`, `BotMaker.townBatch`, `BotBagRoom.sellJunk`, `buildShopPlan`, resupply plan / hub errand (skipped while `BotConversation.isHoldingChat` `:1549-1553`), `TownLoiter.settle`, deadline `:1560-1564`; later ticks `pollResupply`, `PersonalSpace.maybeKeep`, `BotScrolling/BotWealth/BotShops.townTick`, chat hold extends the deadline `:1575-1580`, `questVisitTick(false)` else `TownIdle.tick` `:1583-1585` | deadline expired `:1586` → `SHOP_TRAVEL` with `shopQueue.remove(0)` `:1589-1591`; else Sleepywood home and (`saunaResupply` or `rng < SHOP_VISIT_CHANCE`) → `startSaunaTrip()` `:1592-1593`; else `DECIDE` `:1595` |
| `SHOP_TRAVEL` | `doInTown` `:1591`, `doShopDwell` `:1737` | `doTravel(shopTargetMapId, SHOP_DWELL, SHOP_RETURN)` `:2041-2081` | `SHOP_DWELL` on arrival, `SHOP_RETURN` on fail/timeout (`:649`) |
| `SHOP_DWELL` | `SHOP_TRAVEL` arrival | `doShopDwell()` `:1711-1745`: first tick `phaseDeadlineMs = now + shopDwellMs()`, emote, `sayContext("ShopRestock")`, `startResupplyWalk()` or `BotWanderSystem.start`; later `pollResupply` → `BotWanderSystem.start`, `BotScrolling.townTick`, `BotWealth.townTick`, `BotShops.townTick` | deadline → next `shopQueue` item `SHOP_TRAVEL` `:1737`, else `SHOP_RETURN` `:1739` |
| `SHOP_RETURN` | `SHOP_DWELL` done `:1739`, `SHOP_TRAVEL` failure | `doTravel(homeMapId >= 0 ? homeMapId : MapId.HENESYS, DECIDE, DECIDE)` (`:651-652`) | `DECIDE` both ways |
| `DECIDE` | `SHOP_RETURN`/`GO_TRAIN`/`LIFE_TRAVEL`/`REBIRTH_TRAVEL` fail or arrive, `handleDeath` release, `leaveForChannelMove` `:784`, boss-run end `:609`, `startLifeActivity` GRIND+MOVE_MAP `:1082`, `answerEventCall` `:1448-1457`, `grindWatchdog` bail `:2254`, `grindCrowdBail` `:2313`, `startSaunaTrip` null-char `:1752`, `stayPutOnly` | `doDecide()` `:1896-2040`: `finishResupply`, `stayPutOnly`→`stayHere`, `BotRecruitManager.consumeStationHere`, party `steer`, `WorldEventSteer.decide` + `BotMapGates.mayEnter` + `BotMapCap.admit`, `decideRebirth`, `TrainingTips.take`, `walkBackMapId`, quest `huntMap`, then `TrainingMapChooser.choose(chr, homeMapId, crowdExcludedMaps(), ::debugChat)` | `REBIRTH_TRAVEL` `:1432`; `GRIND` (station, steer, event, walk-back, no-pick-with-mobs `:1994`, first-trip warp `:2027`); `IN_TOWN` (`:1999`); `GO_TRAIN` `:2035` |
| `GO_TRAIN` | `doDecide` `:2035`, `startLifeActivity` HELP_FRIEND `:1086`, `changeChannelNow` `:1205` | `doTravel(currentTrainMapId, GRIND, DECIDE)` (`:654`) | `GRIND` on arrival, `DECIDE` on fail |
| `GRIND` | `doInit`/`resumeGrind` `:1501`, `doDecide` `:1994,2027`, `stayHere` `:2930`, `forceGrindOn` `:2394`, break end `:2419` | `doGrind()` `:2106-2213` (see below) | `BREAK_TRAVEL` `:2199-2200`; `GO_TOWN` on session end `:2208-2211`, supply retreat `:2512`, bag retreat `:2544`, `runningLow` `:2570`, crowd-bail TOWN `:2305`; `DECIDE` from watchdog `:2189`→`:2254`, crowd-bail HOP `:2193`→`:2313` |
| `GO_TOWN` | `doGrind` session end / retreats, `restockCall` `:623`, `startLifeRestockTrip` `:867` | `doTravel(homeMapId >= 0 ? homeMapId : MapId.HENESYS, IN_TOWN, IN_TOWN)` (`:659-660`) | `IN_TOWN` both ways |
| `BREAK_TRAVEL` | `doGrind` when `breaks.begin(...)` returned true `:2199-2200` | `doBreakTravel()` `:2409-2414`: `breaks.tickTravel(getChr())` | `BREAK_REST` when `tickTravel` returns true `:2411-2412`; stays otherwise (retarget once, then rests in place) |
| `BREAK_REST` | `BREAK_TRAVEL` arrival | `doBreakRest()` `:2416-2421`: `breaks.tickRest(getChr())` | `GRIND` when the rest is over and `!recruitingNow()` `:2418-2419` |
| `LIFE_TRAVEL` | `startLifeActivity` `:1091` (destination ≠ here) | `doTravel(lifeDestination, LIFE_DWELL, DECIDE)` (`:655`) | `LIFE_DWELL` on arrival, `DECIDE` on fail |
| `LIFE_DWELL` | `LIFE_TRAVEL` arrival, `startLifeActivity` already-there `:1091` | `doLifeDwell()` `:1265-1334` (see below) | `DECIDE` `:1328-1334`; FISH guards `:1306,1309,1314-1316` |
| `REBIRTH_TRAVEL` | `decideRebirth` `:1432` | `doTravel(BotRebirth.NPC_MAP, DECIDE, DECIDE)` (`:656`) | `DECIDE` both ways (the next `doDecide` runs the rebirth) |

`enterPhase(next)` (`TrainingBot.java:551-586`) is the single transition point: it clears `questVisitDone`/
`questWalkDeadlineMs` (`:552-553`), stops `BotWanderSystem` when leaving `SHOP_DWELL` (`:554-556`), stops `TownLoiter`
and closes a `FISH` visit (finish COMPLETED/CANCELLED by deadline, `GameFishing.cancel`, `botCancelChair`) when leaving
`IN_TOWN`/`LIFE_DWELL` (`:557-566`), assigns `phase` (`:567`), resets `townBeat` (`:568`), releases `LifeTownSlots`
unless the next phase is `LIFE_TRAVEL`/`GO_TRAIN` (`:569-571`), records `LifePhase` and tells `dailyLife`
(`:572-573`), `lifeActivities.invalidateWaiting()` (`:574`), finishes an active life request as COMPLETED with
`lifeNote("RETURNED request=…")` on entering `GRIND` (`:575-581`), clears `phaseEntered/moveDone/moveOk`
(`:582-584`), and `debugChat("phase -> " + next)` (`:585`).

### `doGrind` in detail

First tick (`TrainingBot.java:2108-2135`): settle `pendingHomeMapId` when the map matches, `GCMovement.setGrinding`,
`clearIdle`, `grind.start(chr)`, `breaks.resuming()` → `grindUntilMs = now + (resume ? breaks.consumeResumeRemaining()
: grindSessionMs())` (`:2121`), `breaks.schedule(grindUntilMs, resumingFromBreak)` (`:2122`), `ACTIVE_GRINDERS.add(this)`
(`:2125`), `BotDeaths.setEligible(partyTargetMapId() == null && !recruitingNow())` (`:2126`), `hpPotsAtGrindStart`,
`LevelingPathCensus.grinding`, `lastKnownLevel`, `sayContext("GrindStart")` unless resuming (`:2130-2132`).

Later ticks (`:2137-2212`), in order:

| # | Check | Effect | file:line |
|---|---|---|---|
| 1 | map unobserved and `currentMobLevel > 0` | `OffscreenCombat.rate` + `OffscreenSupplies.accrue` → `accrueAbstractExp`, family rep, `BotQuests.offscreenKills`, `SiteMobKills.offscreen`, `accrueAbstractLoot` | `:2141-2166` |
| 2 | map observed | `BotVitals.regen` | `:2167-2168` |
| 3 | `supplyRetreat(chr)` | out of potions → `leaveGrind` + `GO_TOWN` | `:2171-2173`, `:2493-2518` |
| 4 | `bagRetreat(chr)` | bag nearly full, once per `15 min` → `GO_TOWN` | `:2174-2176`, `:2529-2548` |
| 5 | level rose | `sayContext("LevelUp")` | `:2179-2183` |
| 6 | `maybeGrindChatter`, `maybeSelfBuff`, `BotPartySupport.tick` | ambient line, buffs, party heal | `:2185-2187` |
| 7 | `grindWatchdog(chr)` | 30 s no hit + reachable mob → teleport to portal; 30 s unreachable or 60 s after teleport → `DECIDE` | `:2189-2191`, `:2221-2263` |
| 8 | `grindCrowdBail(chr)` | saturated map past `MAP_SATURATED_DWELL_MS` → hop map (`DECIDE`) or `GO_TOWN` | `:2193-2195`, `:2266-2315` |
| 9 | `breaks.due() && !recruitingNow()` | `breaks.begin(chr, grind, max(MID_SESSION_FLOOR_MS, grindUntilMs - now))` true → `BREAK_TRAVEL`; due flag consumed either way (`:2198`) | `:2197-2203` |
| 10 | on an event map / in aftermath past the session | `grindUntilMs = nowMs + 60_000` | `:2205-2207` |
| 11 | session over and `!recruitingNow()` | `sayContext("MapTransition")`, `leaveGrind()`, `GO_TOWN` | `:2208-2212` |

### `doLifeDwell` in detail

`TrainingBot.java:1265-1334`: first tick sets `phaseDeadlineMs = now() + dailyLife.memory().profile().townDurationMs()`
(`:1268`); `FISH` settles a loiter point (`:1272`); `SELL`/`FREE_MARKET` open a shop when `BotShops.enabled()` and
`BotShops.merchantMinded` (`:1276-1278`), and `SELL` with no shop sells junk through `BotBagRoom.sellJunk` using
`sellStayMs` and arms `sellNothingUntilMs` (`SELL_NOTHING_COOLDOWN_MS` 30 min, `:1287`); a `PARTY_QUEST` visit in a
lobby emits `BotFullChat("lf " + pq)` (`:1294-1299`); `lifeDwellGraceMs` remembers the shortfall between the planned
stay and the interrupt (`Ticket 181 F1`, `:333`). Exit at `:1328-1334`:

```java
if (now() >= phaseDeadlineMs) {
    boolean blockedTooLong = lifeDwellGraceMs > 0 && now() >= phaseDeadlineMs + lifeDwellGraceMs;
    if (blockedTooLong || freeForAutomaticLifeActivity(now())) enterPhase(Phase.DECIDE);
}
```

### The interrupt gates (what the assignment calls the "200 ms gate")

**There is no 200 ms gate anywhere in this path.** The literal `200` appears in `TrainingBot.java` only as map ids and
`TownLoiter.settle(getChr(), point.x, point.y, 200, false)` (`:1272`, a pixel radius). Verified 200 ms cadences in the
repo belong to other subsystems: `src/main/java/soloMapling/companions/OwnedAltCompanion.java:41,260` (alt driver),
`src/main/java/net/server/channel/handlers/GeneralChatHandler.java:19` (`CHAT_SPAM_MS = 200`, player chat spam guard),
`src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/GrindLoot.java:27` (`LOOT_SWEEP_GAP_MAX_MS = 200`),
`src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerFight.java:50` (`FIGHT_TICK_MS = 200`),
`src/main/java/soloMapling/FreeMarket/ArtificialFreeMarket.java:129`, `src/main/java/soloMapling/MapVFX/CustomReactor.java:312`.

What actually gates automatic life activity:

| Gate | Rule | file:line |
|---|---|---|
| `freeForAutomaticLifeActivity(long at)` | `getRunning() && chr != null && chr.getMap() != null` **and** phase ∈ {`GRIND`,`IN_TOWN`,`LIFE_DWELL`} **and** `!BotCommitments.inFlight(this)` **and** `!BotConversation.hasOtherChat(id, requester)` **and** `BotPopulationDirector.mayLogOut(this, requester)` **and** (`!recruitingNow() || requester >= 0`) | `TrainingBot.java:959-966` |
| `requester(at)` | the friend who asked, else `-1` | `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:404` |
| `recruitingNow()` | menu open, `BotRecruitManager.isArmed(id)`, or brain on and any chat hold | `TrainingBot.java:469-473` |
| `freeForLifeActivity()` (legacy non-brain variant) | `GRIND` only, no recruit, no commitment, no chat at all, `mayLogOut` | `TrainingBot.java:834-841` |
| `canStartLifeActivity(activity, at)` | per-activity eligibility, world-event lockout (only GRIND/RESTOCK/LOG_OUT allowed mid-event and in the aftermath), destination room via `lifeDestinationAvailable` | `TrainingBot.java:969-1029` |
| `startLifeActivity(activity)` | must be `lifeActivities.isStartingHere()` **and** `lifeExecutionEnabled()` **and** free **and** can-start | `TrainingBot.java:1040-1043` |
| `lifeExecutionEnabled()` | `(LifeRuntime.enabled() \|\| QaSuite.isEnabled()) && LifeRuntime.executeOn()` | `TrainingBot.java:830-832` |
| `LifeRuntime.tick` | 30 s re-check: `if (!enabled() \|\| !bot.getRunning()) return;` / `if (now < promisedUntil) return;` / `if (!dirty && now < nextCheck && now < dueAt) return;` then `nextCheck = now + 30_000` and phase whitelist `{GRIND, IN_TOWN, LIFE_DWELL}` | `LifeRuntime.java:409-417` |
| `LifeRuntime.commitBlocker` | FREE_PHASES / DROPPABLE sets, ends with `if (!bot.freeForAutomaticLifeActivity(now)) return "busy (a commitment, a chat or logging off)";` | `LifeRuntime.java:247-270` |
| `LifeActivityQueue.drain` | a request is dropped when `epoch`/`number` no longer match or it is older than `MAX_WAIT_MS = 30_000` | `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeActivityQueue.java:11,106-134` |
| `keepLifeActivity` / `holdLifeActivity` / `interruptLifeDwell` | re-decision keeps the stay; a say-do promise extends it (never shortens); a friend interrupt cancels exactly that request | `TrainingBot.java:1233-1247` |

Real cadences in the path, for comparison: macro tick 2–6 s observed (`BotSM.java:487-489`), 9–12 s unobserved
(`BotSM.java:470-472`), 60–120 s while GRINDing unsaw (`TrainingBot.java:255-259`), fast pin 2000 ms
(`BotSM.java:479-481`), combat sweep 250 ms (`TrainingBot.java:114`), life re-check 30 s (`LifeRuntime.java:416`),
nudge debounce 1500 ms (`BotSM.java:166,393-416`), driver wheel 100 ms (`BotTickService.java:34`).

### Commands and chat interrupting the loop

| Interrupt | Mechanism | file:line |
|---|---|---|
| A player talks to it during a trip | `pausedForChat(chr)` for `tripPhase` only — `SHOP_TRAVEL`, `SHOP_RETURN`, `DECIDE`, `GO_TRAIN`, `GO_TOWN` — while `BotConversation.isHoldingChat`, no event map, no party target, not in transit: `chatPaused = true` and `GCMovement.stop(chr)` once; on release `phaseEntered/moveDone/moveOk` are cleared so the trip restarts from where it stands. `HOLD_MS = 45 s` (`src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:56`) | `TrainingBot.java:683-691,694-720`, `BotConversation.java:430-455` |
| A chat during `GRIND` or `BREAK_REST` | not paused by the above; instead `breaks.due()` and the stand-up are gated on `!recruitingNow()`, and the session-end `GO_TOWN` is too | `TrainingBot.java:2197,2208`, `GrindBreakRoutine.java:245` |
| A chat during `IN_TOWN` | the town deadline is extended by the hold (`:1578`), and set to at least one hold at entry (`:1562-1564`) | `TrainingBot.java:1560-1564,1575-1580` |
| `!bot breaknow` | `Module.forceBreakNow()` requires `phase == GRIND`, sets `breaks.forceNow()`, `nudgeSoon(300)` | `TrainingBot.java:2400-2407`, `GrindBreakRoutine.java:85-87` |
| `!bot restspot` | read-only dump of `RestSpotFinder.debug` | `ArtificialPlayerCommand.java:301-309` |
| A restock line said (`brb pots`) | `requestRestockTrip()` only while `GRIND` and not recruiting, `restockCall = true`, `nudgeSoon(300)`; consumed at step 6 of the tick | `TrainingBot.java:798-805`, `:619-627` |
| A world event calls it | `callToEvent()` → `eventCall` → `cancelLifeActivity()` + `answerEventCall` (leaves GRIND/IN_TOWN/LIFE_DWELL/LIFE_TRAVEL, never while recruiting, party-bound or in an active sauna) | `TrainingBot.java:1395-1400,1436-1461` |
| A menu is open | `checkPrioritySpeed()` pins the fast cadence (2 s) while `menuActive()` | `TrainingBot.java:481-489`, `BotSM.java:462-463,479-481` |
| Channel/couple/guild move | `leaveForChannelMove()` cancels life work, abandons a break, `leaveGrind()`, `DECIDE` | `TrainingBot.java:773-790` |
| QA `settleHere()` | pins the bot where it stands (`stayPutOnly`) and cancels a first trip; `INIT` is only pinned so its own init runs | `TrainingBot.java:2911-2941` |
| Death | `handleDeath` abandons the break, `leaveGrind`, respawns to the return town (falling back to `homeMapId`/Henesys when the town is a mob map), sets `walkBackMapId`, `IN_TOWN` | `TrainingBot.java:737-767` |

### GrindBreakRoutine

`final class GrindBreakRoutine` (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/GrindBreakRoutine.java:24`), one
instance per TrainingBot (`TrainingBot.java:` `breaks` field, constructed with the bot).

| Member | Behaviour | file:line |
|---|---|---|
| Constants | `BREAK_CHANCE_PER_SESSION 0.30`, `BREAK_MIN_MS 60_000`, `BREAK_MAX_MS 100_000`, `BREAK_EDGE_MS 60_000`, `BREAK_TRAVEL_STALL_MS 9_000`, `BREAK_TRAVEL_MAX_MS 60_000`, `BREAK_SIT_CHANCE 0.75`, `BREAK_SIGN_CHANCE 0.15`, `ROPE_HANG_CHANCE 0.35`, `PROGRESS_EPS_PX 24`, `ROPE_ARRIVE_X 14`, `ROPE_ARRIVE_Y 44` | `:31-42` |
| `schedule(grindUntilMs, resumingFromBreak)` | rolls this session's break time inside `[edge, len-edge]`; never re-rolls a resumed session; skips sessions shorter than `2*edge` | `:68-78` |
| `due()` | `breakAtMs > 0 && now >= breakAtMs` | `:80-82` |
| `forceNow()` / `forceRopeNow()` | `!bot breaknow` hook / forces the rope variant | `:85-96` |
| `hangsOnRope(ropeAvailable, ropeAsked, roll)` | `ropeAvailable && (ropeAsked \|\| roll < 0.35)` | `:98-100` |
| `resuming()` / `consumeResumeRemaining()` | one-shot saved remainder, consumed by `doGrind` first tick | `:102-114` |
| `begin(chr, grind, savedRemainingMs)` | consumes the due flag, resolves `RestSpotFinder.find` first (null → skip the break, unobserved only), picks rope vs ground, `grind.release(chr)`, `GCMovement.stop`, sit decision, `sayContext("BreakStart")`; true → caller enters `BREAK_TRAVEL` | `:118-158` |
| `tickTravel(chr)` | rope arrival = climbing and `|Δx| <= 14`, `|Δy| <= 44` → `setRestHold(true)`; ground arrival = `|dx| <= 60 && |dy| <= 120` → `stop` + `setIdle("rest")`; progress (`PROGRESS_EPS_PX`, or `isNavigatingClimb`) pushes a 9 s stall deadline; stall or 60 s cap → one retarget to `RestSpotFinder.findLocal`, second time rests in place | `:160-214` |
| `tickRest(chr)` | one-time setup (60–100 s rest, mid-rest `sayContext("Fatigue")`, chair when sitting and the spot is not mob-occupied, chalkboard with `BREAK_SIGN_CHANCE`); finishes only when `!bot.recruitingNow()` — clears sign, dismounts rope or cancels the chair, `sayContext("BreakOver")`; true → caller re-enters `GRIND` | `:216-263` |
| `abandon(chr)` | death or channel move mid-break: clears the sign, forgets break and remainder | `:265+` |

### LifeHost implementation

`TrainingBot implements LifeHost` (`TrainingBot.java:105`). Each method and where the runtime calls it:

| Method | TrainingBot | Used by |
|---|---|---|
| `getChr()` / `getRunning()` | inherited from `BotSM` | every runtime pass |
| `canStartLifeActivity(Activity, long)` | `TrainingBot.java:969-1029` | eligibility map in `LifeRuntime.java` (see gate table) |
| `freeForAutomaticLifeActivity(long)` | `TrainingBot.java:959-966` | `LifeRuntime.java:269,479,491`; `TrainingBot.java:835,1042,1334` |
| `lifeActivityState()` / `prepareLifeActivity` / `submitLifeActivity` | `TrainingBot.java:822,808-810,813-820` (submit returns `DISABLED` when `!lifeExecutionEnabled`, nudges on `QUEUED`/`REPLACED`) | `LifeRuntime` plan submission; `GameQaWorld.java:1520-1537` |
| `keepLifeActivity` / `holdLifeActivity` / `interruptLifeDwell` | `TrainingBot.java:1233-1247` | re-decision, say-do promise, friend interrupt |
| `nudgeSoon(long)` | `BotSM.java:393-416` (debounced 1500 ms, skips TRADING/FINISHED and unregistered bots) | `LifeRuntime.friendAsk`, `requestRestockTrip`, `forceBreakNow`, `GameQaWorld` |
| `townOnly()` / `runsQuests()` / `questHomeMap()` / `lifeRuntime()` / `lifeCurrent()` / `lifeNeeds()` | overridden at `TrainingBot.java:313,317,320,924` | town-only hosts return true; a grinder is `runsQuests` true and `questHomeMap` its home town |

## Data

| Store | Content | file:line |
|---|---|---|
| `phase`, `phaseEntered`, `phaseDeadlineMs` | machine state, one-shot entry flag, per-phase deadline | `TrainingBot.java:300,329,330` |
| `lifePhase` (`record LifePhase(String name, long sinceMs)`) | externally readable phase, read by `BotWatch.doing` | `TrainingBot.java:302-303`; `src/main/java/soloMapling/autoqa/BotWatch.java:427-434` |
| `grindUntilMs`, `currentTrainMapId`, `currentMobLevel`, `pendingHomeMapId`, `homeMapId` | session length and target map | `TrainingBot.java:2106-2134,2891-2909` |
| `breaks` (`GrindBreakRoutine`) | break timing, rest spot/kind/rope, remainder | `GrindBreakRoutine.java:47-61` |
| `lifeActivities` (`LifeActivityQueue`) | waiting/active/ended requests, epoch | `TrainingBot.java:304` |
| `dailyLife` (`LifeRuntime`) | profile, memory, plan, needs, friends | `TrainingBot.java:305`; constructor `:407-414` |
| `lifeDestination`, `lifeVisit`, `lifeFishingStarted`, `lifePqTrip` | current life trip | `TrainingBot.java:306-312` |
| `shopQueue`, `shopTargetMapId`, `resupplySite`, `sellNothingUntilMs` | town/shop itinerary | `TrainingBot.java:139-148,1591,1711-1745` |
| `restockCall`, `eventCall`, `inBossRun`, `walkBackMapId`, `stayPutOnly` | latched interrupts | `TrainingBot.java:327-328,1397,735,2905` |
| `ACTIVE_GRINDERS` (`Set<TrainingBot>`) | who the shared 250 ms sweep visits | `TrainingBot.java:202` |
| `BotRoster.Activity("grind", homeMapId, {TRAIN_STATE: currentTrainMapId})` | persisted across logout | `TrainingBot.java:1506-1517`, read back in `resumeGrind` `:1487-1504` |
| `lifeActivityState().activity()` / `currentLifeVisit()` | what `BotWatch` reports as the bot's doing | `BotWatch.java:427-464` |

## Switches

| Switch | Default | Effect | Live 1.6.4 (`docs/release/1.6.4.conf`) |
|---|---|---|---|
| `-Dslums.botlife.enabled` | on (unset ⇒ on) | master brain switch: `!"false".equalsIgnoreCase(System.getProperty(...))` at `LifeRuntime.java:94` | not set ⇒ on (`SERVER_SET`/`UNIT_EDITS` carry no botlife entry; grep for `botlife` in the conf finds nothing) |
| `-Dslums.botlife.execute` | on | `executeOn()` `LifeRuntime.java:100`; with it false the brain plans but never acts (`lifeExecutionEnabled` `TrainingBot.java:830-832`) | not set ⇒ on |
| `-Dslums.botlife.reach` | `all` | `training` restricts the brain to TrainingBots (`LifeRuntime.java:109-110`) | not set ⇒ `all` |
| `-Dslums.botlife.logEvery` | 500 | log sampling `LifeRuntime.java:38` | not set |
| `-Dslums.botlife.pqAlways` | false | PQ hours ignored (`LifePq.java:58`) | not set |
| `-Dslums.modeswitch` | on | brain may convert a TrainingBot into a social/town bot (`src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:86-88`) | not set ⇒ on |
| `-Dslums.botlife.preview` | false | `!botwhy` allowed under QA without the brain (`BotWhyCommand.java:20-23,90`) | not set |
| `-Dslums.qa` | false | `QaSuite.isEnabled()` `src/main/java/soloMapling/qa/QaSuite.java:33,77-79`; also unlocks `lifeExecutionEnabled` without the brain | TEST: unknown |
| `-Dslums.fishing.enabled` / `-Dslums.fishing.rewards` | false | FISH visits only (`src/main/java/soloMapling/fishing/FishingPolicy.java:15-16`); the `LIFE_DWELL` FISH arm exits to `DECIDE` without them (`TrainingBot.java:1306`) | not set ⇒ off; the conf says fishing is "not in 1.6.4 at all" (`docs/release/1.6.4.conf:104`) |
| `-Dslums.botquests.enabled` | false in code (`BotQuests.java:96` requires it **and** the brain) | quest NPC visits, offscreen quest kills | **on** — `UNIT_EDITS` `docs/release/1.6.4.conf:271` |
| `-Dslums.grind.gearFirst` | false (`GearFirstAdvisor.java:42`) | map choice prefers drop maps | **on** (`UNIT_EDITS`) |
| `-Dslums.bots.partysupport` | false (`src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:29`) | `BotPartySupport.tick` heals party / buff pots (`TrainingBot.java:2191`) | **on** (`UNIT_EDITS`) |
| `-Dslums.junk.sort`, `-Dslums.scroll.ev`, `-Dslums.gear.optimizer`, `-Dslums.gear.dexless`, `-Dslums.bots.apvariants`, `-Dslums.bots.famerequest`, `-Dslums.bots.magicguard`, `-Dsolomapling.jobErrand` | off | the town/shop/gear/quest sub-behaviours the phases call | **on** (`UNIT_EDITS`) |
| `BOT_COMBAT_HUMAN` / `BOT_COMBAT_AOE_MIN_MOBS` / `BOT_COMBAT_SLOPPINESS` / `BOT_COMBAT_DEATHS` / `BOT_KS_MANNERS` / `BOT_SPREAD` | `true` / `3` / `1.0` / `true` / `true` / `true` (`src/main/java/config/ServerConfig.java:266-276`) | combat realism, read via `BotCombatTuning` | not overridden in the conf |
| `BOT_REBIRTH_ENABLED` + `USE_REBIRTH_SYSTEM` | `false` / unset (`ServerConfig.java:477,353`) | enables `REBIRTH_TRAVEL` via `BotRebirth.enabled()` (`src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotRebirth.java:114-117`) | not set ⇒ no rebirth trips |
| `-Dsolomapling.botBagSlots` / `-Dsolomapling.botJunkSale` | `DEFAULT_BOT_SLOTS` / `true` (`src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotBagRoom.java:77,123`) | bag capacity and the town junk sale | not set |
| `-Dsolomapling.chat.busyRetryMs` | 20000 (`BotConversation.java:63-67`) | chat retry delay that can hold a phase | not set |
| `botShops.enabled` / `botShops.ownerPercent` (economy config) | `true` / `10` (`src/main/java/soloMapling/Economy/BotShops.java:114-125`) | `SELL`/`FREE_MARKET` shops in `LIFE_DWELL` and `SHOP_DWELL` takings | not set |

TEST: unknown — `start-test.sh` was not reachable from this review.

## Invariants

1. Only `enterPhase` changes `phase` (`TrainingBot.java:551-586`); `phase` itself is `volatile` (`:300`) and read off
   the bot thread by QA (`BotWatch.java:427-434`).
2. The combat sweep never fights for a bot that is not in `ACTIVE_GRINDERS`; membership is added in the `doGrind` first
   tick (`:2125`) and removed in `leaveGrind` (`:2577`) and `stopScheduledTask` (`:3069,3076`).
3. `grind.tick` only runs while `phase == GRIND`, running, and not dead (`:262-267`).
4. A break never starts, never ends, and a grind session never ends while `recruitingNow()` — a conversation or an
   armed recruit owns the bot (`:2197,2208`, `GrindBreakRoutine.java:245`).
5. `BREAK_REST` ends into `GRIND` with the saved remainder, never a fresh session (`GrindBreakRoutine.java:107-112`,
   `TrainingBot.java:2121,2419`).
6. Every life action passes `lifeActivities.isStartingHere()` and `lifeExecutionEnabled()` before touching the FSM
   (`:1040-1043`); a request that outlived its epoch or `MAX_WAIT_MS` is dropped, not run
   (`LifeActivityQueue.java:11,106-134`).
7. `freeForAutomaticLifeActivity` is the single definition of "interruptible"; `LifeRuntime` and the FSM both call it
   rather than re-deriving the rule (`LifeRuntime.java:269,479,491`; `TrainingBot.java:1042,1334`).
8. Travel is progress-aware, not fixed-timeout: the 120 s `TRAVEL_TIMEOUT_MS` is pushed out while the map changes or the
   bot clears `TRAVEL_PROGRESS_EPS_PX` (`:2041-2104`).
9. A boss run or job errand outranks the whole machine: the bot releases its grind and does not tick its phases until
   the external owner lets go (`:599-610`).
10. A death always re-enters `IN_TOWN` with `walkBackMapId` set (`:765-770`), and a break is abandoned first
    (`:748-751`).
11. `stopScheduledTask` must be safe on a bot whose `Character` is gone — the town-slot/fishing release happens after a
    null guard (Ticket 181 F2, `:3059-3065`).

## Origin

custom. The whole `soloMapling/` tree enters the history in the baseline import
`03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline`, and every later commit on these files is
SoloMapling work:

| Path | Commits | Oldest | Newest |
|---|---|---|---|
| `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java` | 86 | `03d13cb21 2026-10-18` | `a8047c5d1 2026-10-19 Keep high-level bots grinding when nothing is in their band` |
| `src/main/java/soloMapling/ArtificialPlayer/BotTypes/GrindBreakRoutine.java` | 5 | `03d13cb21 2026-10-18` | `71c3f96b2 2026-10-22 Fix bot combat and population bugs from the 1.5.0-rc3 review` |
| `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeHost.java` | 4 | `b079359e8 2026-10-25 WIP S8: Bartle utility, GOAP planner, CHANGE_CHANNEL, outcome hooks` | `f4d5ee0dd 2026-10-29 Hold a bot's brain after its yes until the act runs (t-bt-3)` |

`LifeHost` is the youngest of the three: the interface was introduced for the 1.6.0 S8 "brain reach" work and
`TrainingBot` was retrofitted onto it (`LifeHost.java:9-13`, comment "The TrainingBot is the full host").

## Tests and QA

Unit tests (`src/test/java`):

| File | Covers |
|---|---|
| `src/test/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBotLifeExecutionTest.java` | 11 tests (`:28,52,73,102,114,135,163,180,200,217,262`): one request starts one trip through the existing phases, friend interrupt ends exactly the dwell, a party with a real player still ends the stay, dead character stops, town stay does not expire through a player commitment, conversation/trade after submission refuses the trip, disabled flags cannot start trips, phase change discards old plans, restart after stop, town arrival keeps profile duration and chat hold, fishing only through the owner queue |
| `src/test/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBotChatHoldTest.java` | every trip phase waits for a chat and resumes (`:45`); `GRIND`/`IN_TOWN`/`BREAK_REST`/`SHOP_DWELL` are not paused by that check (`:65`) |
| `.../TrainingBotCombatFixesTest.java` | players-only platform map is left (`:59`), a death on a break clears the break (`:89`), a channel move leaves the grind cleanly (`:117`) |
| `.../TrainingBotErrandTest.java` | town without a store cannot answer a restock (`:23`), unanswerable supply pressure dropped (`:33`), restock trip holds logout (`:46`), life trip/PQ lobby hold logout (`:58`), empty sell stop cut short (`:71`), death respawn in another town shops there (`:82`), field login takes the field's town as home (`:92`) |
| `.../TrainingBotBagRetreatTest.java` | nearly full bag → town (`:15`), room in bag or player talking keeps it grinding (`:20`), once per cooldown (`:26`) |
| `.../TrainingBotStagingHoldTest.java`, `.../TrainingBotSettleHereTest.java`, `.../TrainingBotThreadFreeTest.java`, `.../TrainingBotSaunaLogoutTest.java` | staging hold vs party map (`:44,56,65,78`), settle-here vs first trip (`:23,39,52`), `freeForThread` truth table (`:16,22`), stopping mid-sauna disarms the way out (`:40`) |
| `src/test/java/soloMapling/ArtificialPlayer/BotTypes/RopeRestTest.java` | an asked rope rest hangs when there is a rope (`:12`) |
| `src/test/java/soloMapling/ArtificialPlayer/BotGrindSystem/*` | the grind sub-FSM the phases drive: `ApproachTest`, `GearFirstTest`, `KsMannersTest`, `KsWarSpotTest`, `LevelPathSweepCheck`, `LevelingPathChooserTest`, `LevelingPathTest`, `LootPickerTest`, `OffscreenProgressionTest`, `OffscreenRewardBudgetTest`, `PlayerPlatformExclusionTest`, `RepositionClearanceTest`, `RoamApproachReissueTest`, `StackReissueTest`, `StandOffsetTest`, `TrainingMapFinderTest`, `TrainingRegionsTest`, `TrainingTravelTest` |
| `src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/*` | `LifeActivityQueueTest`, `LifeAcceptanceTest`, `LifeFriendInterruptTest`, `LifeRuntimeTest`, `LifeRuntimePlanTest`, `LifeRuntimeSayDoTest`, `LifeRuntimeTownsTest`, `LifeTownLifeTest`, `LifeTownSlotsTest`, `ModeSwitch*Test`, `RestockTripExecutorTest`, `TownPulseTest` |

Auto-QA checks (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`):

| Id | What it asserts |
|---|---|
| `t-bl-1` `:865` | bot life shadow policy and rejected-action safeguards |
| `t-bl-2` `:872` | one request starts one bot restock trip |
| `t-bl-3` `:882` | the bot chooses restocking from a real supply need |
| `t-bl-4` `:894` | a bot's learned profile survives a social session and a reload |
| `t-bl-11` `:942` | a bot's goal turns into plan steps that `!botwhy` shows |
| `t-bt-1` `:1387` / `t-bt-2` `:1392` / `t-bt-3` `:1397` | talk greet/ask/answer/act, friend crew to a town, say-do carried out |
| `t-gb-1..t-gb-6` `:1440-1462` | gear-first grinding, junk sort, EV scrolling, AP variants (all enter through `GRIND`) |
| `t-bw-1` `:1151` | bot watch: 20 bots for 13 min, ≥80% OK, idle ≤10%, no teleport in front of a player — it reads `TrainingBot.lifePhase()`/`lifeActivityState()`/`watchTarget()` |

`!qa` staging hooks: `GameQaWorld.triggerLifeNeed`/`prepareLifeRestock`/`submitLifeRestock`/`botLifeReport`
(`GameQaWorld.java:1506-1549`), `lifePlanStage`, `lifeCrowdStage`, `forceLifeChannel` (`:1650-1690`),
`CombatStaging.stage`/`unstage` (`CombatStaging.java:268,383`), `WorldEventScenarios.java:136` (waits on
`tb.isGrinding()`).

Not covered: no test builds a bot and walks all 14 phases through a full day; `INIT`→`SHOP_TRAVEL`→`SHOP_DWELL`→
`SHOP_RETURN` round trips are only exercised indirectly (`TrainingBotErrandTest`, `TrainingBotLifeExecutionTest` mock
the gate); `REBIRTH_TRAVEL` has no test at all, and `BotRebirth` is off by default; there is no test for
`grindCrowdBail`, `maybeGrindChatter`, `updateScheduleDelay` priority flapping, or `enterPhase`'s `LifeTownSlots`
release asymmetry; nothing asserts the 250 ms sweep's per-bot exception isolation.

## Known issues

| Card | One line |
|---|---|
| b225 (S3) | KPQ: passes a bot hands to the leader never show on the ground or in the leader's Etc tab, though Cloto credits |
| b232 (S3) | a follower inside an instance does not leave with its leader: when the leader warps out, the bot says it "waits" |
| b240 (S3) | regular bots say they are grinding while standing in town |
| b241 (S4) | KPQ bonus stage: the player keeps getting messages until everyone leaves |
| b276 (S4) | QA feed: a bot's map-scoped PLAN/ROUTE lines never reach the GM's QA feed |
| b284 (S3) | auto-QA at 10 lanes gives false failures (8 of the 22:00 run's FAILs pass when rerun one at a time) |
| b285 (S3) | ticket 178: an alt can offer an upgrade for a sibling alt, but the trade command refuses it unless the owner is present |
| b287 (S4) | `t-xc-5`: a PANIC-style scroll trader holding a Chaos/White Scroll never lists it |

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/GrindBreakRoutine.java:118-126` — `begin()` sets
`breakAtMs = 0` (line `:119`) before it resolves the rest spot, and returns false at `:125` when `RestSpotFinder.find`
yields nothing; the caller (`TrainingBot.java:2197-2203`) just carries on, so that grind session silently loses its
break with no reschedule — `schedule` only runs again on the next `doGrind` first tick (`:2122`).

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2003-2035` — `doDecide` records
`currentTrainMapId = pick.mapId()` at `:2003` and then has three exits that can bypass `GO_TRAIN` (`:2027` warp,
`:2035` walk); any early return added between them leaves `currentTrainMapId` pointing at a map the bot is not heading
to, and `doTravel(currentTrainMapId, …)` (`:654`) would then drive it to the wrong place.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1560-1564` — the town deadline
already takes the chat hold into account at entry (`:1562-1564`) and then extends it again each later tick
(`:1575-1580`), so a bot talked to repeatedly can stay in town for `townDurationMs + every hold`, with no cap.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1283-1290` — the `SELL`-with-no-shop
path arms `sellNothingUntilMs` at `:1287` whenever `sellStayMs` shortened the stay, which `:1350-1352` does for
`shopOpen || soldItems > 0 ? plannedMs : min(plannedMs, SELL_NOTHING_STAY_MS)` — i.e. only when nothing sold, so the
arming matches the documented case; the actual gap is that the cooldown is armed from `now()` rather than from the
shortened deadline, so the bot is blocked from the next FM trip for 30 min from mid-visit.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2222` vs
`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2599` — `grindWatchdog` and `maybeGrindChatter`
each call `GCMovement.isMapObserved(chr)` separately in the same tick, and `sweepVisit` (`:274-282`) reads it too; the
observation state is live, so a player walking in mid-tick makes the watchdog treat the map as unobserved while the
chatter gate treats it as observed one line later. Harmless today (each gate is independent), but the three rules
disagree on the same input within one tick.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:487-489` — `getRandomDelay()` returns
`2000 + random.nextInt(4000)` (2–6 s) while its own comment says "2000 to 3000 ms"; every cadence derived from it
(`setPriorityNormal`, `updateScheduleDelay`) therefore runs at up to twice the documented rate.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1237-1240` — `keepLifeActivity`
assigns `phaseDeadlineMs = until` for any non-`GRIND` activity without checking that the current phase actually reads
`phaseDeadlineMs`: `doBreakTravel`/`doBreakRest` never do (`:2409-2421`), so a keep arriving in a break phase writes a
deadline that only the *next* dwell consumes.

## State

working, with gaps. Evidence: the 14-phase machine is complete and reachable (`TrainingBot.java:294-295` switch
`:646-663` covers every constant); the life gate has a dedicated 11-test suite that passes the phase/trip/interrupt
matrix (`TrainingBotLifeExecutionTest.java:28-262`); the break routine has its behaviour tests
(`TrainingBotCombatFixesTest.java:89`, `RopeRestTest.java:12`) and its four live entry points
(`TrainingBot.java:2199,2400,2409,2416`); auto-QA watches it end-to-end (`AutoQaCatalog.java:1151`). Gaps: no
`REBIRTH_TRAVEL` test and the feature is off by default (`ServerConfig.java:477`); the FISH arm is dead in 1.6.4
(`TrainingBot.java:1306` + `docs/release/1.6.4.conf:104`); the 200 ms gate the review brief describes does not exist in
this path (see `## How it works`); b240 ("says it is grinding while standing in town") matches the `phaseActivity`
mapping that reports `GRIND` for `DECIDE`/`GO_TRAIN` (`BotWatch.java:455-464`).

## Open questions

1. Is the "200 ms" gate the brief mentions a stale name for `freeForAutomaticLifeActivity`, or a cadence that existed
   before the `BotTickService` rewrite (`BotTickService.java:34` is 100 ms)? No 200 ms constant exists in the training
   path today.
2. Should `b240` be fixed in the phase machine (a town phase that reports `HANG_OUT`) or in `BotWatch.phaseActivity`
   (`src/main/java/soloMapling/autoqa/BotWatch.java:455-464`), which is what maps `DECIDE`/`GO_TRAIN` to `GRIND`?
3. Why does `enterPhase` release `LifeTownSlots` for every phase except `LIFE_TRAVEL`/`GO_TRAIN`
   (`TrainingBot.java:569-571`) — is a `SHOP_TRAVEL` in the same town meant to hold the slot?
4. `doDecide`'s first-trip warp is gated on `!GCMovement.isMapObserved` (`TrainingBot.java:2022`) but the crowd-bail is
   explicitly *not* (`CROWD_BAIL_UNOBSERVED = true`, `:190`); is that asymmetry intentional?
5. Is `SELL_NOTHING_COOLDOWN_MS` (30 min) meant to be armed before the sale attempt or after a confirmed empty sale?
   See the NEW-FINDING at `:1288-1296`.
