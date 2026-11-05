# Movement and travel

## Purpose

Every bot character is driven by one shared, clientless phys/movement core so motion looks like a player's: a self-rescheduling 20 Hz tick integrates a hand-written physics model, emits real `MOVE_PLAYER` fragments to whichever observer is watching, and a 300 ms travel poller routes the bot map-to-map through portals, NPC transports, scripted warps and the hyper rock. Three packages cooperate: `GCMoveSystem` (the engine, a GreenCatMS port), `BotMovementSystem` (the legacy recorded-path engine plus `BotGround` ground snapping), `BotTravelSystem` (our own transporter/edge data). `BotWanderSystem` is the only "go nowhere in particular" driver.

Constraints that shaped it:
- All bots share one `BotClient`, so `portal.enterPortal(client)` would warp the wrong character — the engine resolves map+portal server-side and calls `bot.changeMap` (`src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCPortals.java:7-17`, `:24-60`).
- A bot's `getClient().getPlayer()` is null; everything works on the `Character`.
- Movement must be cheap when nobody watches: `GCMovementDriver.nextDelayMs` drops to 250 ms, then 1000 ms, off-observer (`src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:111-127`).

## Entry points

| Entry | Kind | Detail |
|---|---|---|
| `GCMovement.enable(Character)` | API | src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovement.java:41 — builds `BotMovementState`, snaps to ground, warms the nav graph, starts the ticker |
| `GCMovement.move(bot,x,y[,cb])` | API | GCMovement.java:127 / :144 — 51 call sites in `src/main/java` (e.g. src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/Approach.java:162, src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:527) |
| `GCMovement.travel(bot,destMapId[,cb])` | API | GCMovement.java:471 / :476 — callers src/main/java/soloMapling/ArtificialPlayer/BotConvo/ClaimActions.java:326, src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/JobErrand.java:125, src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1763, :1828, :2057 |
| `GCMovement.follow(bot,owner)` | API | GCMovement.java:199 → `GCFollow` (src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCFollow.java:54) |
| `BotWanderSystem.start(bot[,anchorX,radius])` | API | src/main/java/soloMapling/ArtificialPlayer/BotWanderSystem/BotWanderSystem.java:103 / :110; callers src/main/java/soloMapling/ArtificialPlayer/BotTypes/TownWandererBot.java:183, TrainingBot.java:1722, src/main/java/soloMapling/qa/GameQaWorld.java:518 |
| `GCMovementDriver.start/stop` | scheduler | GCMovementDriver.java:79 / :85; tick loop `:101-108` |
| `GCTravel.travel` | scheduler | src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCTravel.java:164, own pool `:84-89`, `POLL_MS=300` `:50` |
| `!navprobe <bot\|me> <x> <y>`, `!navprobe map` | GM cmd | src/main/java/client/command/commands/gm3/NavProbeCommand.java:17, `MAX_LINES=12` :24 |
| `!botground [fix\|channel]` | GM cmd | src/main/java/client/command/commands/gm3/BotGroundCommand.java:25 |
| `!gcmove bake\|ropecheck\|lod\|learned` | GM cmd | src/main/java/client/command/commands/gm4/GCMoveCommand.java:20 |
| `!botmove …` (movetoportal, pathfinder, idle, fall, map) | GM cmd | src/main/java/client/command/commands/gm4/BotMoveCommand.java:32 |
| `!env travel` | GM cmd | prints `GCMovement.transitCounts()` (GCMovement.java:585) from src/main/java/client/command/commands/gm3/EnvironmentCommand.java:311-332 |
| `bot.nav.cacheDir` graph + stuck logs | -D / files | src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotNavigationGraphProvider.java:83, src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavStuckLog.java:114 |
| `BotTransit.yaml`, `portal_versions.yaml` | YAML | src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.yaml:1-304, src/main/java/soloMapling/server/portal_versions.yaml:1-54 |

## How it works

### 1. Where the bot is going (goal selection)

| Step | Action | file:line |
|---|---|---|
| 1.1 | `resolveTarget`: explicit `moveTarget` wins; else a followed owner on the same map → `formationPlace(owner, followOffset)`; else `farmAnchor`; else null | GCMovementDriver.java:526-538, `:543`, `:563` |
| 1.2 | `hasGoal = target != null \|\| inAir \|\| climbing \|\| navEdge != null`; without a goal the bot lands any abandoned coarse trip and idles (or lies prone while `duckUntilMs`) | GCMovementDriver.java:281-296 |
| 1.3 | Town flavour wander picks a spot itself: `BotSpotPicker.pickGroundSpot` ×6 until one is ≥60 px away, then dwell 2.5–6 s | BotWanderSystem.java:298-313, `:315-326` |
| 1.4 | A chat-holding bot cancels its stroll (`BotConversation.heldByChat`) and stands still for the hold | BotWanderSystem.java:215-241 |
| 1.5 | Long trips come from `GCTravel` (chat ask, quest errand, hotel/sauna, follow across maps) | GCTravel.java:164-197 |

### 2. Route: in-map nav graph, or cross-map world graph

| Phase | Action | file:line |
|---|---|---|
| 2.1 | `BotNavigationManager.resolveTarget` turns the goal into a `NavigationDirective`: validates profile/footholds, bakes or warms a graph, else falls back to the raw-steering `BotFallbackMovementManager` (`graphWarmupFallback`) | src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotNavigationManager.java:101-246, src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotFallbackMovementManager.java:15, `:25` |
| 2.2 | Unobserved maps skip physics navigation entirely: a pure `MovementPlan` (edge list with baked ms costs) is advanced by `CoarseExecutor.advance(plan, startedAtMs, nowMs)` and the bot is teleported along it | GCMovementDriver.java:327-401, src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/MovementPlan.java:47, `:65`, src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/CoarseExecutor.java:29 |
| 2.3 | Cross-map route = Dijkstra over portals + scripted warps + `BotTransit` edges + hyper-rock virtual nodes; cost 20 s per walked hop, `MAX_ROUTE_HOPS=160`, cached per (from,to,hops,tier,fares,savedReturn,levelTier), 50 000 entries | src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCWorldGraph.java:112, `:139-148`, `:175-230`, `:255-296` |
| 2.4 | Route gate: a bot may only pass through maps `BotMapGates.mayEnter(map, rebirths, level)` allows, unless it is already inside a gated area (`escaping`) — then it may walk out | GCWorldGraph.java:177, `:199`; src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotMapGates.java:57, `:64`, `:74` |
| 2.5 | Scripted portals are excluded from pure movement (`if (!script.isEmpty()) continue;`), which is why `BotScriptedWarp` carries 6 hand-curated edges | GCWorldGraph.java:467-470, src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotScriptedWarp.java:26-48 |
| 2.6 | Version-gated destinations are dropped from graph, transit and scripted warp alike by `MapleVersionManager.isPortalinCurrentVersion` | src/main/java/soloMapling/server/MapleVersionManager.java:52-72, BotScriptedWarp.java:55-57, src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.java:120-131 |
| 2.7 | Travel executes one hop at a time: walk to the portal/NPC and act (`approachAndAct`), board and wait for the ride, or use the scripted portal; each poll recomputes the route from the current map | GCTravel.java:235-370, `:590-690` |

### 3. Per-tick motion and the move packet

| # | Step | file:line |
|---|---|---|
| 1 | Reschedule itself (`safeTick` → `tick` → `scheduleNext`); delay 50 ms when a real player is on the map, 250 ms unobserved, 1000 ms unobserved and jobless | GCMovementDriver.java:101-127, `:138-155` |
| 2 | Guards in order: no map → stop; map changed → `onMapChange`; chair → hold; `resting` → hold pose; `BotCommitmentHold` → freeze; contact damage; `IdleSafety.tick` (not for owned alts); active-map skip of coarse physics reconstruction | GCMovementDriver.java:157-231 |
| 3 | AI runs at 10 Hz: `consumeAiTick` accumulates `BotPhysicsEngine.cfg.TICK_MS` (50) and fires every `AI_TICK_MS=100` | GCMovementDriver.java:680, `:35`; src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotPhysicsEngine.java:30 |
| 4 | Player-reaction / flaw hesitation / portal-drop float (60 px above ground for 1500 ms + jitter) can each park the bot for that tick | GCMovementDriver.java:233-277, `:58-59`, `:693` |
| 5 | Stalled move check: Manhattan progress < `MOVE_PROGRESS_EPS_PX=16` for `MOVE_NO_PROGRESS_MS=8000` → abandon target, record `GAVE_UP` | GCMovementDriver.java:494-528, `:62-63` |
| 6 | `stepMovementCore`: nav directive (`consumedTick` short-circuits), movement flaws, then phase | GCMovementDriver.java:605-626 |
| 7 | Phase: climbing → `tickClimbing`; swim map + airborne → `tickSwimming`; airborne → `tickAirborne`; else `tickGrounded` (ground step plan, then walk/jump/rope-entry) | GCMovementDriver.java:628-639; src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotMovementManager.java:231, `:348`, `:464`, `:569`, `:663-721` |
| 8 | Arrival: within 8 px (precise target) or `STOP_DIST=30`, and not hanging short of a ground target → clear intent + `fireArrival` | GCMovementDriver.java:644-658, `:665`; BotMovementManager.java:79 |
| 9 | Packet: 15-byte fragment `cmd=1, x, y, velX, velY, fhId, stance, TICK_MS` → `PacketCreator.movePlayer` → `map.broadcastMessage(bot, pk, false)` → pets follow | BotMovementManager.java:963-990, `:992` |
| 10 | Broadcast is skipped entirely (and marked invalid) when no observer is on the map; identical frames are deduped on x/y/velX/velY/stance/fhId | BotMovementManager.java:907, `:916-951`, `:955-962`; GCMovementDriver.java:746 |

Physics constants are calibrated against packet captures: `WALK_VEL=125`, `GRAVITY_PXS2=2000`, `JUMP_SPEED_PXS=555`, `MAX_FALL_PXS=670`, `CLIMB_SPEED_PXS=100`, `SWIM_GRAVITY_PXS2=590` (BotPhysicsEngine.java:28-50, `:87`, `:111`).

### Transport modes

| Mode | Data | Execution | file:line |
|---|---|---|---|
| Walked portal | nav/world graph `PORTAL` edge, cost 20 s | `approachAndAct(portalPos, → GCPortals.enter)`; PQ gate via `PqGates.open`, event-instance map preferred, fallback portal 0 | GCTravel.java:322-331; GCPortals.java:24-60 |
| Scripted portal | `BotScriptedWarp.WarpEdge` (6 rows) | walk to the portal's own position, then `changeMap` server-side | BotScriptedWarp.java:26-48, `:92`; GCTravel.java:344-355 |
| NPC transport (`via: npc`, 118 rows) | fare, `beginner_fare`, `to_portal`, `event`, `sec` | visit ticket seller first, walk to NPC, pay `BotHoldings.spendMeso`, `changeMap` | BotTransit.java:48-83; GCTravel.java:374-421 |
| Ride / ferry (`via: ride`, 34 rows) | `server: true` (30 rows) for boat/train/subway/plane scripts | board, then wait `World.getTransportationTime`; if the map never changes, warp fallback after `limit + max(30 s, limit/5)` | GCTravel.java:490-508 |
| Hyper rock | `hyper_rock:` block, item 5590001, cooldown 300 s, `into`/`out_of`/`home` | consume + `saveLocationOnWarp`, refused when `BotMapGates` or `FieldLimit.CANNOTVIPROCK` blocks landing | BotTransit.java:97-117, `:177`, `:194`; GCTravel.java:431-475 |
| Free Market door | `MARKET_ENTRY_SCRIPTS` / `classicfm_exit` | walk into the door script, `changeMap(910000000,"out00")` in, `warp` out | GCTravel.java:717-757 |
| Recall/saved return | `save_location` / `return_to_saved` (`WORLDTOUR`) | only toward the saved map, else the edge is pruned | BotTransit.yaml:1-35; GCWorldGraph.java:255-296; GCTravel.java:536 |
| GM warp (`warp`/`warpToPortal`) | none | used when walking is impossible and nobody watches; held 45 s while watched, then the trip fails | GCTravel.java:774-812 |
| Owned-alt exception | — | an owned alt travels portals only: no warp, no fare, no boarding (`noWarp`/`noFares`) | GCTravel.java:126-135 |

### Stuck detection and recovery

| Watchdog | Trigger | Action | file:line |
|---|---|---|---|
| Generic unstuck | no |Δ| > 8 px for 500 ms, cooldown 0, `ENABLE_UNSTUCK` | random left/right ground jump, fail the current nav edge, clear nav state, 5 s cooldown | GCMovementDriver.java:753-783, `:49`; BotMovementManager.java:863-879 |
| Stalled move | no progress toward `moveTarget` for 8 s | `GAVE_UP` + `NavEdgeLearning.failure` so the next plan avoids the leg; target cleared | GCMovementDriver.java:494-528, `:62-63` |
| Rope stall | on a rope, ~0 vertical progress for 4 s (2 s note to `QaLog.Topic.ROPE`) | `unhangFromRope`: climb off at the head when the goal is above and a landing exists (≤4 `advanceClimb`), else `jumpOffRope` toward the goal | GCMovementDriver.java:788, `:801-843`, `:845-875` |
| Frozen airborne | identical position 30 ticks while `inAir` | `AIR_FROZEN`; goal dropped unless `WatchGate.mayReposition`, snap to `recoveryGround`, reseed | GCMovementDriver.java:876-908, `:50` |
| Fell off map | outside the map VR by 400 px | `FELL_OFF_MAP`, snap to recovery ground, `moveTarget = null` to de-thrash | GCMovementDriver.java:914-949, `:55` |
| Pit rescue (b109) | `NavPits.isTrap` at the bot's region for 3 s (20 s when watched — a trap has no walk out) | `PIT` + `NavPits.rescuePoint` teleport | GCMovementDriver.java:953-1002; src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavPits.java:74, `:254` |
| Hop stuck | 12 s without closing 16 px / moving 16 px | `escape`: give up (warp, or `giveUpNoWarp` for chat walks/owned alts) | GCTravel.java:634-660, `:702-714`, `:159` |
| Hop soft-lock | all samples inside 400 px for 20 s | same escape with a `SOFT-LOCK` reason | GCTravel.java:634-660 |
| Hop ceiling | hop 90 s old | escape | GCTravel.java:83, `:596-599` |
| Transit loop | same `from→to` transit hop 3× | trip fails: "travel is looping, goal abandoned" | GCTravel.java:545-555 |
| Boarding wait | event `entry` property not open | counted as progress (`awaitingBoarding`), never as stuck | GCTravel.java:406-414, `:525`; GCTravel.java:230 |
| Watched-warp hold | warp needed on a map with a real player | waits `WATCHED_HOLD_MS=45 s`, then fails rather than snapping visibly | GCTravel.java:774-796 |
| Wander stall | 6 s without closing 12 px | pick a new spot (or dwell) and re-issue the move | BotWanderSystem.java:51-52, `:215-289` |

Everything that repairs by teleport goes through `GCMovement.teleportTo`, which refuses to snap while a real player watches and walks instead (`GCMovement.java:264-282`).

## Data

| File | Shape | Consumed by |
|---|---|---|
| src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.yaml | 164 edges (`via: npc` 118 / `portal` 12 / `ride` 34; `server: true` 30) + `hyper_rock` block; fields documented in the header comment | `BotTransit.index()` (BotTransit.java:120), `loadHyperRock()` (:306) |
| src/main/java/soloMapling/server/portal_versions.yaml | 54 lines, `mapId: minVersion`; 12 mini-dungeon rows at `99` are permanent blocks | `MapleVersionManager` (MapleVersionManager.java:52-72) |
| cache/bot-nav (bot.nav.cacheDir) | `v70<graphTag>/` graph blobs, `stuck/YYYY-MM-DD.json` stuck logs | BotNavigationGraphProvider.java:83-96; NavStuckLog.java:114, `:243-305` |
| src/main/java/soloMapling/server/BoundedMaps.java | `prune(Map,int soft,int hard,Predicate stale)` for cooldown caches — **not** used by movement | BoundedMaps.java:19-32 |

`BotTransit.Edge` = `(name, fromMapId, toMapId, via, npcId, sellerNpcId, portalName, fare, beginnerFare, event, serverDriven, sec, toPortalId, toPortalName, saveLocation, returnToSaved, minLevel, minRebirths, questGate)`; `fareFor(jobId)` charges `beginnerFare` for job 0, and `scaledMs(travelRate)` mirrors `World.getTransportationTime` (BotTransit.java:48-83).

## Switches

| Switch | Default | Live | Effect |
|---|---|---|---|
| `bot.nav.swimWallJump` | off (`"true".equalsIgnoreCase`) | **on** (docs/release/1.6.4.conf:271) | swim wall-jump fix; changes the graph tag → different cache folder (NavPortSwitches.java:19-21, `:41`) |
| `bot.nav.blockedPosWatchdog` | off | **on** (:271) | parked bot gives up the move (NavPortSwitches.java:24-26) |
| `slums.navTrace` | false | **on** (:271; comment :274 says it is unchanged since 1.6.2 and should be dropped when log volume hurts) | per-tick nav trace ring, 20/hour (NavTrace.java:41-44) |
| `bot.nav.teleportPhantomGuard` | off | off | phantom teleport edge guard (NavPortSwitches.java:28-30) |
| `bot.nav.zmassWalls` | off | off | Z-mass wall modelling (NavPortSwitches.java:34) |
| `solomapling.walkWhenWatched` | **on** (`!"false".equalsIgnoreCase`) | on (default) | never snap/teleport in front of a player (WatchGate.java:29, `:41`, `:48`) |
| `bot.idleSafety` | on | on (default) | idle-bot safety bail-out (IdleSafety.java:38) |
| `bot.nav.mobility` | on | on (default) | skill edges (teleport/flash jump) enabled (NavMovementSkills.java:32) |
| `bot.swimnav.enabled` | on | on (default) | swim coarse navigator (SwimNavigator.java:33-38) |
| `bot.nav.graphCache.maxEntries` / `.maxMB` / `minIdleSec` | 3000 / 256 / 120 | defaults | graph cache bounds (NavGraphCache.java:40-42) |
| `bot.nav.portalRoutesCap` | 50 000 | default | learned portal-route table cap (NavPlayerLearning.java:65) |
| `bot.nav.cacheDir` | `cache/bot-nav` | default (surefire uses `cache/bot-nav-test`) | graph + stuck log root (BotNavigationGraphProvider.java:83, NavStuckLog.java:114) |
| `bot.flaws.*` (`skillMin` .85, `skillMax` .99, `hopperShare` .2, `recoverMaxMs` 12000, `missCooldownMs` 45000 …) | on | on (default) | deliberate human imperfections (NavMovementFlaws.java:60-89) |
| `BoundedMaps` soft/hard limits | caller-supplied | n/a to movement | — |

TEST: unknown (start-test.sh not reachable from this review).

## Invariants

1. One tick per bot at a time: `scheduleNext` only chains after `safeTick` returns, so the physics state is single-threaded (GCMovementDriver.java:92-108).
2. Nothing moves a bot after shutdown: every loop (, tick, wander, travel, follow) checks `BotShutdown.isQuiesced()` and stops without firing callbacks (GCMovementDriver.java:104, GCTravel.java:236-240, BotWanderSystem.java:193-198).
3. A logged-out bot is never routed or warped: `GCMovement.hasLeftWorld` gates enable/move/travel (GCMovement.java:91-103, GCTravel.java:244-248).
4. Only one engine drives a bot: `MovementCommands.tryAcquireMovementLock` is held for the whole dynamic session (GCMovement.java:60-66, src/main/java/soloMapling/ArtificialPlayer/BotMovementSystem/MovementCommands.java:718).
5. Packets are broadcast only when an observer is on the map, and never twice for an identical frame (BotMovementManager.java:916-951).
6. A visible snap is forbidden: `WatchGate.mayReposition` must pass for `teleportTo`, travel warps and pit rescue (GCMovement.java:264-282, GCTravel.java:777, GCMovementDriver.java:961-963).
7. A bot never travels out of an open trade — `BotTradeCommands.abortOpenTradeForTravel` runs before the trip starts (GCTravel.java:178-186).
8. Owned alts are second-class: no warp, no fare, no boarding (GCTravel.java:126-135, `:334-336`).
9. An unreachable destination fails the trip; it never falls back to teleporting a watched bot (GCTravel.java:777-796, `:159`).
10. Scripted portals are never planned as walkable movement; they are explicit `BotScriptedWarp` edges only (GCWorldGraph.java:467-470).

## Origin

| Path | Verdict | Evidence |
|---|---|---|
| src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovement.java | custom (GreenCatMS port, vendored) | header "Ported from GreenCatMS. Credit: NutNNut." (:3-8); `git log --follow` tail: 03d13cb21 2026-10-18, 1512c08a6 2026-10-19, 825f97a26 2026-10-19 |
| src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java | custom (adapted port) | header "Adapted from GreenCatMS — GCMoveSystem tick driver… Credit: NutNNut."; same commit tail |
| src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCTravel.java | custom (port + our own additions) | header "Ported from GreenCatMS."; first commit 03d13cb21 2026-10-18 |
| src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCWorldGraph.java | custom | 595139740 2026-10-19 "Ride boats, trains and cabs between continents" |
| src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.java (+ .yaml) | custom, original | header "Our own creation."; 595139740 2026-10-19, deaa92740 2026-10-20 |
| src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotScriptedWarp.java | custom, original | header "Our own creation (not a GreenCatMS port)"; 591127f79 2026-10-30 "Give Rien, Black Mountain and the Cave of Life their way back… (b230)" |
| src/main/java/soloMapling/ArtificialPlayer/BotWanderSystem/BotWanderSystem.java | custom, original | header "Our own creation (not a GreenCat extraction)"; 03d13cb21 |
| src/main/java/soloMapling/ArtificialPlayer/BotMovementSystem/** | custom (legacy engine) | 03d13cb21 2026-10-18 baseline, 4d125f57e 2026-10-19 |
| src/main/java/soloMapling/server/portal_versions.yaml | custom data | 03d13cb21, deaa92740 2026-10-20 |
| src/main/java/soloMapling/server/BoundedMaps.java | custom utility | 33-line shared pruner, no movement caller |

`03d13cb21` ("Establish deployable MapleRoot server baseline") is the repository's first commit, so `soloMapling/` is custom from the start rather than an upstream tree.

## Tests and QA

| Layer | Coverage | Where |
|---|---|---|
| Unit — engine | 68 test classes (GCWorldGraphRoutingTest, GCTravelTransitTest, GCTravelMarketDoorTest, GCTravelNoWarpTest, GCTravelTempleExitTest, MoveStallGiveUpTest, NavPitsTest, RopeTopStallTest, SwimWallJumpBurstTest, NavGraphCacheTest, WatchGateTest, FormationPlaceTest, CoarseGroundTest …) | src/test/java/soloMapling/ArtificialPlayer/GCMoveSystem/ |
| Unit — travel data / ground | BotMapGatesTest, BotScriptedWarpTest, BotTransitTest, BotTransitHighLevelTest; BotGroundTest, BotStandUpTest | src/test/java/soloMapling/ArtificialPlayer/BotTravelSystem/, src/test/java/soloMapling/ArtificialPlayer/BotMovementSystem/ |
| Unit — cross-cutting | src/test/java/server/bounce/BotFlashJumpZoneTest.java, src/test/java/server/maps/MapRoutingTableTest.java, src/test/java/client/command/commands/gm3/BotGroundCommandTest.java, src/test/java/soloMapling/ArtificialPlayer/BotGrindSystem/ApproachTest.java, JobErrandRouteTest.java, BotTravelTradeAbortTest.java, TempleOfTimeTest.java |
| Gate routing evidence | `MoveStallGiveUpTest` documents the t-mx-2 round-2 bug: re-issuing the same target re-armed the 8 s clock, stage 3 idled 1164 s | src/test/java/soloMapling/ArtificialPlayer/GCMoveSystem/MoveStallGiveUpTest.java:32-38 |
| Auto-QA t-nv-1 | "Town portals and NPCs are reachable (nav probe)" — laneOnly 5 min, `run("nv-1")`, `has("NV1 DONE maps=[1-9]")`, `lacks("NV1 ERROR ")` | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1404-1409; scenario src/main/java/soloMapling/qa/NavProbeScenarios.java:13-35, registered src/main/java/soloMapling/qa/QaScenarios.java:94 |
| Auto-QA t-nv-2 | "No new one-way trap maps in the bots' world graph" (`probe("trapMaps")`) | AutoQaCatalog.java:1538-1541 |
| Auto-QA t-nv-3 | "Debug tools answer: bot inspect API, kill calibration, nav path trace" | AutoQaCatalog.java:1543-1546 |
| Auto-QA t-nn-1 / t-nn-2 | half-checks needing a human watching a real client: swim wall jump, blocked-position watchdog | AutoQaCatalog.java:1488-1497; probes src/main/java/soloMapling/autoqa/Probes162.java:1768, `:1786` |
| Manual `!qa ld-1` | "Ladder watch": 10 min in Ellinia/Perion/Kerning, `Topic.ROPE`, "no bot should hang at a rope top for more than ~4 s" | src/main/java/soloMapling/qa/QaScenarios.java:562-590 |

Not covered: no tests at all for `BotWanderSystem` (no `src/test/java/soloMapling/ArtificialPlayer/BotWanderSystem/`); no automated assertion that a broadcast move packet is well-formed for a real client (only estimated by `BotMovementManager` unit tests through `broadcastRawMovement` paths); nothing exercises the `BoundedMaps` idiom against movement caches (none exist); the FAKE-clock coarse path is tested but no test drives `GCMovementDriver` on an observed map end-to-end.

## Known issues

| Ref | Issue |
|---|---|
| b225 | KPQ pass hand-overs are invisible (`/root/work/STATUS-1.6.4.md:57`) |
| b232 | A follower inside an instance does not leave with its leader (:58) — `GCFollow` cross-map path is the relevant code |
| b240 | Bots say they are grinding while standing in town (:59) |
| b241 | KPQ bonus-stage messages wrong (:60) |
| b274 | Client never sends portal-enter for the Will/Magnus portals (:76) |
| b276 | QA feed map-scoped PLAN/ROUTE lines never reach the GM feed (:78) |
| b284 | Auto-QA 10-lane run produces false failures (:80) |
| b285 | Alt upgrade hand-over refused (:81) |
| b287 | PANIC-style scroll trader never lists Chaos/White Scroll (:83) |
| e07 | Nav routes for the pirate job-advance map: owner wants the errand instant because that map is funky (:117) |

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.java:306-318` loads the hyper-rock block with `new FileReader(YAML_PATH)` while `loadFile()` (:361-366) resolves through `soloMapling.DataFiles`. In a jar-only or `-Dsolomapling.dataDir` deployment the working-dir relative path does not exist, so `hyperRock()` silently returns `HyperRock.NONE` and every hyper-rock route disappears from the planner — the exact cutover failure `DataFiles` exists to prevent.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/MovementPlan.java:25` declares `Kind.CROSS_MAP`, referenced by nothing (only `IN_MAP` is produced at `:47`/`:65`), so the enum advertises a plan shape the executor never sees.

NEW-FINDING: `src/main/java/soloMapling/server/portal_versions.yaml:6-8` gates maps at version `99`, but `src/main/java/soloMapling/server/MapleVersionManager.java:10` initializes `version = 55` and no production assignment was found, so every row whose value is ≤ 55 permits entry by default while 99 permanently blocks — the YAML's "min rebirth/version" semantics read as a progression gate that in practice is a static allow/deny list.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:49` hardcodes `ENABLE_UNSTUCK = true` although every neighbouring recovery behaviour is switchable (NavPortSwitches, WatchGate, IdleSafety), so on a live shard the random-jump unstuck cannot be disabled to diagnose a suspected jump-oscillation report.

## State

`working`, with two caveats.

Evidence: 68 engine tests + 4 travel-data tests + 2 ground tests exist and encode the hard-won regressions (MoveStallGiveUpTest, GCTravelMarketDoorTest, NavPitsTest, RopeTopStallTest, SwimWallJumpBurstTest, NavGraphCacheTest); live runs the engine with three nav switches on (docs/release/1.6.4.conf:271) and the graph cache is at v70 (:286); the three recovery layers (coarse unobserved advance, physics when observed, and the six watchdogs) all reachable; travel has 164 curated edges plus the hyper rock. Caveats: the hyper-rock loader bypasses `DataFiles` (NEW-FINDING above), and the only end-to-end movement coverage is auto-QA t-nv-1 plus half-checked t-nn-1/t-nn-2 that need a human in a real client, so "working" is asserted from unit tests and reads, not from an observed live shard.

## Open questions

1. Is `MapleVersionManager.version` ever set at boot (e.g. from a release config this review did not open)? If not, `portal_versions.yaml` rows below 56 are dead data.
2. Which TEST-server switch set is actually applied at start-up? TEST: unknown — `start-test.sh` was not reachable.
3. Does the live jar-only deployment ship `BotTransit.yaml` on the classpath in a way that `new FileReader` can still resolve, or is the hyper rock silently off in production?
4. `bot.nav.zmassWalls` and `bot.nav.teleportPhantomGuard` are off everywhere and change the graph tag; is anyone planning to enable them, and would that require a graph re-bake at scale?
5. How is `NavStuckLog` volume bounded on a busy shard — 20 spots/day per kind (NavStuckLog.java:61-66) with a 5-minute flush was not measured against a full 1.2k-bot population.
