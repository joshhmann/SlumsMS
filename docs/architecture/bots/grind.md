# Grind system

## Purpose

How a bot that is already a `TrainingBot` picks a map, holds a slice of it, kills what spawns there, picks up the
drops, and gives the map up again. Two independent decisions:

| Decision | Scale | Owner |
|---|---|---|
| Which map, for the next session (level band, region, crowd, gear, path) | once per `DECIDE` | `TrainingMapChooser` (`src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingMapChooser.java:69`) |
| Which spot on that map, and how to work it (camp / patrol / roam / stack) | once per grind episode | `GrindBrain` + `SpotFinder` + one `GrindStrategy` (`src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/GrindBrain.java:104`) |

Everything here treats the bot as an ordinary `Character`: `getClient().getPlayer()` is `null` on the shared headless
client, so combat, loot and map state are read off the `Character` itself
(`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2626` reads `chr`, never a `Player`).

The package is 41 files / ~9,983 lines under `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/`, plus the
map-cap registry (`src/main/java/soloMapling/ArtificialPlayer/BotMapCap.java`, 405 lines) and the generic map bounder
(`src/main/java/soloMapling/server/BoundedMaps.java`, 33 lines) outside it.

## Entry points

| Entry | Kind | Detail |
|---|---|---|
| `TrainingBot.updateState()` → `case GRIND -> doGrind()` | macro tick | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:658`, body at `:2106` |
| `TrainingBot.combatTickAll()` | shared 250 ms sweep | one `ScheduledExecutorService` task for every bot in `ACTIVE_GRINDERS` (`TrainingBot.java:202`), armed by `ensureCombatTicker` (`:207-215`, period `COMBAT_TICK_MS = 250` at `:114`), cancelled on quiesce (`:219`) |
| `TrainingBot.combatTick()` | per-bot gate | only `phase == GRIND`, not dead, ticker running → `grind.tick(chr)` (`TrainingBot.java:262-269`) |
| `GrindBrain.tick()` | one combat beat | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/GrindBrain.java:148` |
| `BotTickService` | macro driver wheel | `DRIVER_PERIOD_MS = 100` (`src/main/java/soloMapling/server/BotTickService.java:34`, driver at `:167`); GRINDing bots are slowed to 60–120 s on purpose (`TrainingBot.java:253-255`) |
| `TrainingBot.doDecide()` | map choice | `TrainingBot.java:1896` → `TrainingMapChooser.choose` at `:1986` |
| `!bot grindstyle <cid\|map> <camp\|patrol\|roam\|stack\|auto>` | GM command | `src/main/java/client/command/commands/gm4/ArtificialPlayerCommand.java:113` (dispatch), `:769` (doc), `:777` (usage), `:910` (help) → `TrainingBot.forceGrindStyle` `:2349` → `GrindBrain.forceStyle` `GrindBrain.java:348` |
| `!env grindprofile` / `spotdump` | GM read-out | `src/main/java/client/command/commands/gm4/EnvironmentCommand.java:508-511` → `dumpGrindProfile` `:722` (regime, walkable span, spots, roam/camp, stacks) |
| `!env path reload\|dist\|sweep` | GM | `EnvironmentCommand.java:91-93` → `handlePath` `:346` (`reload` `:350`, `dist` → `LevelingPathCensus.report(4)` `:353`, `sweep` `:358`) |
| `!env travel reload` / `!env regions` | GM | `EnvironmentCommand.java:101-104` → `TrainingRegions.reload` (`src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingRegions.java:95`) |
| `!botmapcap [n\|mapId]` | GM read-out | registered `src/main/java/client/command/CommandsExecutor.java:442`; `src/main/java/client/command/commands/gm4/BotMapCapCommand.java:14-21`, syntax hint `:48`; renders `BotMapCap.describe` (`src/main/java/soloMapling/ArtificialPlayer/BotMapCap.java:367`) |
| `!bot breaknow <cid>` | GM | `TrainingBot.forceBreakNow` `TrainingBot.java:2400` (a rest is scheduled 30 % of sessions, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/GrindBreakRoutine.java:31`) |
| `CombatStaging.forceGrindOn(map)` / `TrainingBot.settleHere()` | QA staging | `src/main/java/soloMapling/ArtificialPlayer/BotCombatSystem/CombatStaging.java:383` → `forceGrindOn` `TrainingBot.java:2381-2395`; `settleHere` `TrainingBot.java:2925` |
| `LevelingPath.yaml` (852 lines) | data rows | path bands/groups; loaded by `LevelingPath.load` (`src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/LevelingPath.java:135`) |
| `TrainingRegions.yaml` (104 lines) | data rows | grind windows + relocation hubs, `travel:` block at `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingRegions.yaml:22-27` |
| `TrainingBotDialogue.yaml` `GrindStart` / `GrindAmbient` | flavour lines | `src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/TrainingBotDialogue.yaml:128`, `:15` |
| `-Dslums.grind.gearFirst`, `-Dsolomapling.levelPath*` | switches | see `## Switches` |

## How it works

### Map choice: `TrainingMapChooser.choose` (one `DECIDE`)

| # | Phase | Action | file:line |
|---|---|---|---|
| 1 | full maps out | `BotMapCap.excludeFull(chr, excluded)` adds every map at/over its channel cap, minus the bot's own | `TrainingMapChooser.java:73-79` |
| 2 | admission band | `LEVEL_BAND = 12` above the bot, `LOWER_SPAN = 25` below (`minMobFor`) | `:21`, `:27`, `:94-95`, `:382-384` |
| 3 | chill roll | 6 % of decisions at level ≥ 30 drop the band 25 levels and cap reach at 3 hops | `:37-40`, `:93-96` |
| 4 | deep hubs | `DeepHub.of(homeMapId)`: origin admitted when it is the home hub and has mobs; `downwardOnly` raises the floor to `level − 10` and forbids chill | `:85-87`, `:98-102`, `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/DeepHub.java:26-39` |
| 5 | finder | `TrainingMapFinder.findTrainingMaps(...)` over `GCMovement.mapsWithinHopsByDepth` | `:108-110`, `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingMapFinder.java:62-70` |
| 6 | gear-first farm | active `FARM_ITEM` goal → `farmPick` may short-circuit the whole decision | `:130-143`, `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/GearFirstAdvisor.java:222-246` |
| 7 | gear advice | `GearFirstAdvisor.advise` → per-map multipliers, `needGear ≥ 0.5` (`GEAR_LED_NEED`) suppresses the path | `:144-147`, `:245` |
| 8 | leveling path | `LevelingPathChooser.wantsPath` then `choose`; a path pick is all-or-nothing (over capacity → fall through) | `:148-164`, `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/LevelingPathChooser.java:124` |
| 9 | region move | `TrainingTravel.consider` may retag every candidate as relocating to a new hub and widen reach to `hubReachHops` | `:165-174`, `:209-213`, `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingTravel.java:94-140` |
| 10 | draw | `weightedPick` over the eligible maps | `:185`, `:327-366` |
| 11 | reserve | `reserve(mapId)` **before** travelling; over-capacity after the increment → `release` + re-pick, `MAX_REDECIDES = 2` | `:186-193`, `:53`, `:271-280` |
| 12 | census | `LevelingPathCensus.decided(..., Source.LOCAL\|PATH)` records band/job/map/relocation | `:138-139`, `:158-159`, `:188-189`, `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/LevelingPathCensus.java:31-33` |

### Map-choice weight inputs

Weight = `levelFit × distance × trend × gear × headroom × crowd` (`TrainingMapChooser.java:337-341`), with
`crowdFactor` folded into the gear and path weights instead.

| Input | Weight / behaviour | file:line |
|---|---|---|
| Mob level above the bot | hard reject above `level + 12` | `TrainingMapChooser.java:95` |
| Mob level below the bot | hard reject below `max(1, level − 25)` | `:382-384` |
| Level fit (fallback edge only) | `1.0` at/above the floor, else `exp(−gap/10)` (`LEVEL_FIT_DECAY`) | `:371-379`, `:30` |
| Distance | `0.15 + pref`, `pref = (1−venture)(1−hopFrac) + venture·hopFrac`, `venture = min(1, level/50)` | `:390-399`, `:50-51` |
| Hop radius | interpolates `{1→1, 15→2, 30→4, 50→8, 70→10}`; level ≥ 70 → `MAX_HOPS = 20` | `:43-47`, `:304-322` |
| Trend (1.6.0 S9) | `×(1 + 0.5·mapScore(level, mapId, now))` (`MAP_BOOST = .5`) | `src/main/java/soloMapling/ArtificialPlayer/TrendIndex.java:276-277`, `:62` |
| Gear need | `×(0.1 + 0.9·unified[i])`; unscorable map → `0.1` | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/GearFirstAdvisor.java:206-209` |
| Yield saturation | `×(1 − 0.75·s)` (`crowdFactor`, s = `BotYieldCaps.mapSaturation`) | `TrainingMapChooser.java:239-241` |
| Capacity headroom | `×((cap − n)/cap)`, hard `0` when `n ≥ cap` | `:340` |
| Everything full | soft fallback `levelFit × distance / (1 + botsOnMap)` | `:344-352` |
| Nothing scored at all | uniform random map | `:357` |
| Leveling-path row weight | `e.weight × favourite(botId, map) [0.5..2.0 log-uniform] × (cap−n)/cap × crowd × partyWeight(0.5)` | `LevelingPathChooser.java:185-186`, `:269-277`, `:53` |
| Path: local vs abroad | local `× homeBias(3.0)`; abroad needs a fare the bot can pay and a home it may enter | `LevelingPathChooser.java:191`, `:195-203` |
| Path: abroad permit | world-wide 20 relocations/minute window, else redraw local | `LevelingPathChooser.java:154`, `:281-300` |
| Path: mob ceiling | `mob > level + maxAbove(15)` rejected; `mobCount < 2` rejected | `LevelingPathChooser.java:51`, `:175` |
| Path rows | `groupOf(job)` group at full weight + `general` at `generalMix = 0.35`; larger weight wins on a duplicate map | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/LevelingPath.java:63-96` |
| Region gate | `TrainingRegions.isAllowed(mapId, rebirths, level)` (yaml windows, excludes, rebirth gates) | `TrainingMapFinder.java:107`, `TrainingRegions.java:109-123` |
| Mini-dungeon | rejected outright | `TrainingMapFinder.java:104-106` |
| Mob count on map | `< 2` rejected (`MIN_MOB_COUNT`) | `TrainingMapFinder.java:26`, `:111` |
| Fallback when nothing is in band | up to `FALLBACK_K = 3` closest-level maps (deliberately not one) | `TrainingMapFinder.java:30`, `:124-152` |
| Crowd cooldown | maps just left for saturation (`MAP_EXCLUDE_MS = 45 s`) arrive as `excluded` | `TrainingBot.java:182`, `:1981`, `:2883-2887` |
| Per-map bot cap | `BotMapCap.cap(mapId)` (default 50, overrides, town max, world-event ceiling) | `src/main/java/soloMapling/ArtificialPlayer/BotMapCap.java:158-180`, `:194-197` |
| Per-map capacity (spots) | `SpotFinder.mapBotCapacity`: roam → `walkableSpanX/1400`, else Σ spot `shareCap`, else a WZ estimate | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/SpotFinder.java:135-150`; chooser call `TrainingMapChooser.java:295-297` |

### One grind session, in order (including looting)

| # | Step | file:line |
|---|---|---|
| 1 | `DECIDE` runs its priority branches first: station-here, steer (staging hold / party map), world event, rebirth, tip, death walk-back, quest hunt — each may enter GRIND directly if the bot is already standing on the target | `TrainingBot.java:1909-1981` |
| 2 | Otherwise `TrainingMapChooser.choose(...)`; `null` + mobs here → grind the current map, else `IN_TOWN` | `TrainingBot.java:1986-2001` |
| 3 | A relocating pick sets `pendingHomeMapId` so the new home is adopted on arrival | `TrainingBot.java:2008-2012`, `:2110-2115` |
| 4 | First trip only: 70 % chance to warp straight in (`FIRST_TRIP_TELEPORT_CHANCE`) instead of walking | `TrainingBot.java:2017-2027` |
| 5 | `GO_TRAIN` → `doTravel(currentTrainMapId, GRIND, DECIDE)`, progress-aware `TRAVEL_TIMEOUT_MS = 120 s` | `TrainingBot.java:654`, `:2041-2075` |
| 6 | First GRIND tick: `GCMovement.setGrinding(true)`, `clearIdle`, `grind.start(chr)`, `grindUntilMs = now + grindSessionMs()` (10–20 min, shortened to ≥ 90 s when the bot warped in), `breaks.schedule`, `ACTIVE_GRINDERS.add`, `BotDeaths.setEligible`, `LevelingPathCensus.grinding`, `sayContext("GrindStart")` | `TrainingBot.java:2117-2134`, `:116-117`, `:121`, `:2952-2959` |
| 7 | `GrindBrain.start`: claim lock on, `MovementStylePolicy.forBot`, `FlashJumpTiers.capFor`, loot habit (`BotCombatTuning.human()` → `BotHabits.lootNow`, else `BATCH`), then `resolveStrategy` + `s.start(chr)` **outside** the claim lock | `GrindBrain.java:104-146` |
| 8 | Strategy picked once per episode: a traversable stack → STACK, else `profile.roam()` → ROAM, else SPREAD & ≥ 3 spots → PATROL, else CAMP | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/GrindStylePolicy.java:25-38` |
| 9 | CAMP: `SpotFinder.profile(map)` (cached cluster/spot profile), `selectAndClaim` picks by score and claims a slot (`BotSpotClaims.claim`, capacity = `shareCap`), retried 3× then shared uncapped | `CampStrategy.java:195-285`, `SpotFinder.java:614-661` |
| 10 | Spot scoring: `10·sameLedgeSpawns + 8·tightness + extent/100 + 6·liveMobs − 0.015·dist − 30·holders − 100000·overCap + 25·playerHeat + jitter` | `SpotFinder.java:646-655` |
| 11 | `TRAVEL_TO_SPOT` → `GCMovement.move`; inside the radius → `FIGHT` | `CampStrategy.java:218-229`, `:325-345` |
| 12 | Each 250 ms beat: `combatTickAll` → `sweepVisit` → `grind.tick` → `GrindBrain.tick` (map change or forced style → swap; unobserved → return; climbing → recover; personal space → return; else `s.tick`) | `TrainingBot.java:227-269`, `GrindBrain.java:148-197` |
| 13 | `FIGHT`: sticky target, else `AffinityReactions.ksPick(acquireTarget)` (KS manners), no target → `WAIT` for the respawn lull | `CampStrategy.java:348-372`, `:423-441` |
| 14 | **Loot, passive:** `lootAtFeet` → `GrindLoot.grabLootAtFeet` — one settled drop within `PICKUP_PX = 60` (160 with a looting pet), staggered 100–350 ms, never moves the bot, never interrupts a swing | `CampStrategy.java:376`, `GrindLoot.java:53-82`, `LootPicker.java:31-35` |
| 15 | **Loot, instant:** an INSTANT-habit bot out of range walks up to 180 px for a drop within 3 s of its last kill | `CampStrategy.java:377-379`, `GrindBrain.java:503-518`, `GrindLoot.java:148-149` |
| 16 | In range → `preSwingAdjust` (knockback step-back, step out of a mob's body, face the mob, kite for RANGED/keepsDistance, AoE re-centre) then `engageAndSwing`; otherwise approach, and on no progress for 4 s drop the target | `EngageBeat.java:68-84`, `:242`, `CampStrategy.java:380-408` |
| 17 | **Loot, sweep:** a dry beat (no target / respawn lull) runs `walkAndLoot` over the leash — near end = closest drop, far end = farthest on that side, blink/dash to the near end for advanced classes, one continuous drive-by walk while vacuuming up to 2 drops per beat | `RoamStrategy.java:60-66`, `CampStrategy.java:442-470`, `GrindLoot.java:84-137`, `:199-217` |
| 18 | Loot eligibility: ownership (`DropCommands.botCanLoot`), holdings room, SKIP_JUNK habit leaves non-quest ETC junk, pet rules when the pet fetches | `GrindLoot.java:152-158`, `:181-183` |
| 19 | Unobserved map: no combat at all — `OffscreenCombat.rate` + `OffscreenSupplies.accrue` pay supplies and return the affordable share, then abstract EXP, family rep, quest kills, site kills and abstract loot accrue by elapsed time | `TrainingBot.java:2141-2166`, `:2861-2877`, `:2813-2830`, `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/OffscreenCombat.java:84-126`, `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/OffscreenSupplies.java:83-91` |
| 20 | Abstract loot is not minted: `OffscreenRewardBudget.draw(mapId, occupants, elapsedMs, now, world)` shares one 60 s window of the map instance's own expected yield, then `BotYieldCaps` per-map/daily caps | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/OffscreenRewardBudget.java:37`, `:118-150`, `TrainingBot.java:2816`, `:2828-2830` |
| 21 | Watchdogs (observed only): 30 s without a landed hit + a reachable mob → teleport to the nearest portal and re-select; no reachable mob or 60 s after a failed teleport → leave and re-decide | `TrainingBot.java:2216-2261` |
| 22 | Crowd bail: saturated (or only players' platforms left) past an 8 s dwell → leave the map, cooldown 45 s, `HOP` up to 2 (4 for yield) then `TOWN`; a bot on a player's map says "cc" first | `TrainingBot.java:2266-2330`, `:181-186`, `:2324-2330` |
| 23 | Break: 30 % of sessions schedule a 60–100 s rest 60 s off either edge; `BREAK_TRAVEL`/`BREAK_REST` hold the saved remainder | `GrindBreakRoutine.java:31-42`, `TrainingBot.java:2197-2201`, `:2419` |
| 24 | Session timer (extended 60 s at a time on an event map/aftermath) → `leaveGrind` + `GO_TOWN` | `TrainingBot.java:2202-2210` |
| 25 | `leaveGrind`: drop the ticker membership and the swing clock, clear death eligibility, `grind.release` (spot claim), `clearTrainTarget` (occupancy slot), clear rope/idle/grind nav flags | `TrainingBot.java:2576-2584`, `:2936-2942` |

**Town bots in GRIND (card b240).** Yes — a bot can hold `GRIND` while standing in a town, and there are three
independent ways in:

1. `startLifeActivity`'s `HELP_FRIEND` branch sets `setTrainTarget(player.getMapId(), Math.max(1, MapMobIndex.level(...)))`
   and then `enterPhase(chr.getMapId() == player.getMapId() ? GRIND : GO_TRAIN)` — **no mob check at all**, and the
   `Math.max(1, −1)` coercion turns a mobless town's sentinel into mob level 1
   (`TrainingBot.java:1083-1086`; sentinel `-1` from `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/MapMobIndex.java:43`, `:52-54`).
2. The steer / event / tip / walk-back / quest branches use the same `chr.getMapId() == target ? GRIND : GO_TRAIN`
   ternary; four of the five validate the target's mob level first (`:1944`, `:1960`, `:1974`, and the event branch
   `:1926` via `BotMapGates`), but the party/steer branch's `partyTargetMapId()` accepts a bot couple's or guild
   leader's map **without** a `MapMobIndex` check on the party member's map when the party has no real player
   (`:1917-1923`, `:2638-2660` — only the couple/guild fallbacks at `:2646-2652` end in a mob check,
   `src/main/java/soloMapling/ArtificialPlayer/BotCoupleSystem/BotCouples.java:846-847`,
   `src/main/java/soloMapling/ArtificialPlayer/BotGuildSystem/BotGuilds.java:936-938`).
3. Once in `GRIND` on a mobless map nothing evicts it: the only unconditional eviction is
   `leaveEventMapIfOver` for an **event** map that went mobless (`TrainingBot.java:1176-1180`); the watchdog needs an
   observed map *and* fails to find a reachable mob before bailing (`:2218`, `:2239`, `:2253`), so an unobserved town
   "grinder" stays until `grindUntilMs` (up to 20 min) and meanwhile accrues abstract EXP/loot through the
   `currentMobLevel > 0` gate (`:2141`, and `:2862` falls back to `KILLS_PER_MIN × mobLevel`).

The dialogue side of b240 is mitigated separately — a bot standing in a town never draws a bank line that claims it is
training, grinding or farming here (`src/main/java/soloMapling/ArtificialPlayer/BotConvo/ConvoLines.java:114-122`,
`:183`; thread lines are re-checked against "no monsters here"
`src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotThreads.java:1067-1075`). The **phase** was never fixed.

### The four grind archetypes

| Style | Chosen when | Behaviour | Source |
|---|---|---|---|
| CAMP | default | one scored spot, `FIGHT ⇄ WAIT`, respawn lulls waited out | `CampStrategy.java:96-480` |
| PATROL | `Regime.SPREAD` and ≥ 3 spots | ring of 2–4 top spots, rotates to the next on a dry beat, shares the least-crowded when the ring is claimed | `PatrolStrategy.java:20-21`, `:60-95`, `:97-118` |
| ROAM | `bestSameLedge < 4` (no campable ledge) | anchor-free seek in a 900×140 box, 700 px leash, 12 s starved → saturated | `SpotFinder.java:49`, `:221`, `RoamStrategy.java:20-34`, `:55-104` |
| STACK | a hop-traversable vertical stack exists | tether across stacked ledges (trees, towers, subway roofs), 5 s patience / 20 s unproductive → relocate | `GrindStylePolicy.java:29-40`, `SpotFinder.java:232-292`, `StackStrategy.java:25-36` |

## Data

| Store | Kind | Lifecycle | file:line |
|---|---|---|---|
| `TrainingMapChooser.BOTS_PER_MAP` | `ConcurrentHashMap<Integer, AtomicInteger>` occupancy reservations (world-wide) | incremented by `reserve` before travel, decremented by `clearTrainTarget`/`release`; survives channel changes | `TrainingMapChooser.java:57`, `:271-286` |
| `BotMapCap.FULL` | `Set<Long>` of `channel<<32\|mapId` at/over cap | read as a hint, each entry re-checked live; never authoritative | `BotMapCap.java:53`, `:266-275` |
| `BotMapCap.TURNED_AWAY` | `AtomicLong` refusals | `!botmapcap` read-out only | `BotMapCap.java:55`, `:261-267` |
| `BotMapCap.RAISED` | event ceilings per map instance | `raise`/`lower` from `WorldEvents` | `BotMapCap.java:174`, `:186-192` |
| `SpotFinder.CACHE` | `MapGrindProfile` per map id (spots, stacks, regime, roam) | built once per map on first touch, static for the process | `SpotFinder.java:92`, `:104-118`, `:196-229` |
| `SpotFinder.ESTIMATED_SPOTS` | capacity estimate from raw WZ spawns | for maps no bot has ground yet | `SpotFinder.java:96`, `:148-150` |
| `BotSpotClaims.CLAIMS` | map-instance key → spot → (botId → slot) | per-channel claim key; **no TTL** — a leaked claim is permanent | `src/main/java/soloMapling/ArtificialPlayer/BotSpotClaims.java:25-29`, `:63` |
| `MapMobIndex.CACHE` | `MapMobInfo(medianLevel, mobCount, mobIds, spawnPoints, respawnMs)`; `NONE = (-1, 0, …)` | WZ life data, memoised | `MapMobIndex.java:41-49`, `:78-79` |
| `GrindBrain` claim state | `claimLock`, `claimActive`, `episodeMapId`, `forcedStyle`, `styleSwapRequested`, shared combat fields (`targetOid`, `retargetDeadline`, `lastKillMs`, `lootStyle`, …) | lives with the bot | `GrindBrain.java:34-100` |
| `OffscreenRewardBudget.METERS` | per-map 60 s window meter | one window of yield per 60 s regardless of tick length | `OffscreenRewardBudget.java:37`, `:103-150` |
| `LevelingPath.cached` | parsed path (bands, job ranges) | volatile, replaced by `install`/`reload` | `LevelingPath.java:109-120`, `:124-133` |
| `TrainingRegions` config | windows, hubs, `Tuning(wanderChance .05, outgrownChance .80, relocationsPerMinute 20, hubReachHops 20)` | installed from `config.yaml` at boot, `!env travel reload` | `TrainingRegions.java:59-65`, `:90-108` |
| `TrainingTravel.HUB_MAPS` | per-region hub cache | `clearCaches` on reload | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingTravel.java:205-207` |
| `LevelingPathCensus.PICKS` | per-bot `Pick(level, jobId, mapId, source, relocates, atMs, grindingOn)` | dropped on bot dispose (`TrainingBot.java:3087`) | `LevelingPathCensus.java:25-42` |
| `GearFirstAdvisor.WANTS` | bot → `Want(itemId, name, desirability)` | rewritten every advice, removed when no gear need | `GearFirstAdvisor.java:49`, `:215-219` |
| `TrainingBot.mapCrowdCooldown` | map → cooldown end (45 s) | pruned on read into `excluded` | `TrainingBot.java:378`, `:2883-2887` |

`BoundedMaps.prune(map, soft, hard, stale)` is the only bounding helper in scope and is **not** used by the grind path —
its call sites are chat/affinity cooldown maps (`src/main/java/soloMapling/server/BoundedMaps.java:19`;
e.g. `src/main/java/soloMapling/ArtificialPlayer/BotAffinitySystem/RudeStreak.java:95`).

## Switches

| Switch | Default | Where | Live (1.6.4) |
|---|---|---|---|
| `-Dslums.grind.gearFirst` | `false` (`GearFirstAdvisor.java:41-42`) | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/GearFirstAdvisor.java:42` | **on** — `docs/release/1.6.4.conf:271` (`UNIT_EDITS` `-Dslums.grind.gearFirst=true`), documented `docs/release/1.6.2.conf:233` |
| `-Dsolomapling.levelPath` | on; the literal `false` disables the path | `LevelingPathChooser.java:46-49` | on (not present in `docs/release/1.6.4.conf`, so the default applies) |
| `-Dsolomapling.levelPath.follow` | `0.75` | `LevelingPathChooser.java:50` | default |
| `-Dsolomapling.levelPath.generalMix` | `0.35` | `LevelingPathChooser.java:51` | default |
| `-Dsolomapling.levelPath.maxAbove` | `15` | `LevelingPathChooser.java:52` | default |
| `-Dsolomapling.levelPath.homeBias` | `3.0` | `LevelingPathChooser.java:53` | default |
| `-Dsolomapling.levelPath.belowChance` | `0.08` | `LevelingPathChooser.java:54` | default |
| `-Dsolomapling.levelPath.aheadChance` | `0.10` | `LevelingPathChooser.java:55` | default |
| `-Dsolomapling.levelPath.partyWeight` | `0.5` | `LevelingPathChooser.java:56` | default |
| `-Dsolomapling.levelPath.reportSeconds` | `0` (off) | `LevelingPathCensus.java:96-107` | off |
| `-Dsolomapling.levelPath.sweep` | `false` | `LevelingPathCensus.java:114` | off |
| `-Dsolomapling.offscreenExpPace` | `1/3.0` | `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/OffscreenCombat.java:50-51`, `:150` | default |
| `BOT_MAP_CAP` | `50` (`DEFAULT_CAP`) | `src/main/java/config/ServerConfig.java:197`; `BotMapCap.java:50`, `:139` | default 50 — the key is absent from `docs/release/1.6.4.conf` (it was in `SERVER_SET` up to 1.6.0, e.g. `docs/release/1.6.0.conf:26`) |
| `BOT_MAP_CAP_OVERRIDES` | `""` | `ServerConfig.java:199`; parse `BotMapCap.java:139-150` | default |
| `BOT_TOWN_PULL` | `0` | `ServerConfig.java:171` | default (OFF) — so the town cap raise below is inert live |
| `BOT_TOWN_MAP_MAX` | `200` | `ServerConfig.java:183`; `BotMapCap.java:158-168` | default, inert while `BOT_TOWN_PULL = 0` |
| `WORLD_EVENT_MAP_MAX` | `200` | `ServerConfig.java:203`; `BotMapCap.java:174-200` | default |
| `BOT_COMBAT_HUMAN` | `true` | `ServerConfig.java:266`; `BotCombatTuning.java:33-40` | default (`1.6.4.conf` `SERVER_SET` sets `BOT_ROSTER_PEAK_ONLINE`, `BOT_POPULATION_PEAK`, `BOT_DIRECTOR_BOOT_FILL_PER_SECOND` only, `docs/release/1.6.4.conf:201-215`) |
| `BOT_SPREAD` | `true` | `ServerConfig.java:276`; `BotCombatTuning.java:84`; used by `SpotFinder.claimKey` `:551` | default |
| `BOT_KS_MANNERS` | `true` | `ServerConfig.java:274` | default |
| `BOT_COMBAT_DEATHS` | `true` | `ServerConfig.java:272` | default |
| `BOT_COMBAT_AOE_MIN_MOBS` | `3` | `ServerConfig.java:268`; `BotCombatTuning.java:42-49` | default |

`TrainingRegions.yaml` (`travel.wander_chance`, `outgrown_chance`, `relocations_per_minute`, `hub_reach_hops`) and
`LevelingPath.yaml` rows are data, not switches: reload with `!env travel reload` / `!env path reload`
(`EnvironmentCommand.java:350`). TEST server start script: unknown (not inspected).

## Invariants

1. Bots are `Character`s with a shared headless client; `getClient().getPlayer()` is `null`, so every grind read goes
   through the `Character` (`TrainingBot.java:2626`, `GrindLoot.java:53`). Bot ids start at 1,000,000,000, and packets
   sent to a bot go nowhere.
2. **All claim mutations happen under `GrindBrain.claimLock`**, and a select re-checks `claimActive` under it
   (`GrindBrain.java:34-42`, `CampStrategy.java:232-235`). `BotSpotClaims` has no TTL, so a ghost claim would repel
   bots for the rest of the uptime; movement calls stay outside the lock.
3. `TrainingMapChooser.choose` returns a map whose occupancy slot is **held**; the caller must either travel there or
   `release` it. GRIND entry always goes through `leaveGrind()` first, which releases both the spot claim and the
   occupancy slot (`TrainingBot.java:2576-2584`).
4. Capacity is enforced at *pick* time, not at spawn time: `weightedPick` zeroes a map at `n ≥ cap`
   (`TrainingMapChooser.java:340`) but the accept test uses `≤` (`:155`, `:187`) and the farm short-circuit uses `<`
   (`:134`) — a map at exactly capacity is still admissible.
5. An unobserved map gets **no** combat: `GrindBrain.tick` returns before the strategy runs
   (`GrindBrain.java:175-178`), and EXP/loot accrue abstractly from the elapsed wall clock
   (`TrainingBot.java:2141-2166`). Only observed bots can be judged stuck (`:2218`).
6. Loot is never teleported: a drop must be settled `SETTLE_MS = 1500` and on the bot's ledge (±120 px) to be swept
   (`src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/LootPicker.java:31-35`), and at-feet pickup never
   moves the bot (`GrindLoot.java:59-81`).
7. Abstract loot is drawn from a shared per-map budget, never minted, and a reward the bot cannot hold is forfeited
   (`TrainingBot.java:2810-2812`, `:2828-2830`).
8. A KO'd, paused, quiesced, or mid-rebirth/mid-errand bot does nothing in GRIND
   (`TrainingBot.java:228-231`, `:262-266`, `:589-614`), and a quiesce cancels the shared ticker (`:219`).

## Origin

Custom. The whole `BotGrindSystem` package is SoloMapling's own work on top of the fork baseline: the oldest commit
touching `CampStrategy`, `RoamStrategy`, `StackStrategy`, `GrindBrain`, `GrindLoot` is
`03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline` (`git log --follow`), with the archetype split in
`97942a236 2026-10-21 Make grinding bots fight like people and spread out (1.5.0 rc3 bots lane)` (`PatrolStrategy`,
`SpotFinder`) and `6ca8830ab 2026-10-21 Time bot attacks from the client's pose lengths, not fixed cooldowns`
(`EngageBeat`).

| Part | Origin | Evidence |
|---|---|---|
| `GrindStyle`, `MovementStyle`, `MapGrindProfile`, `GrindStylePolicy` | custom | header comments "Ours (SoloMapling)" / "Our own creation" (`GrindStyle.java:6-8`, `MovementStyle.java:3-4`, `MapGrindProfile.java:1-12`) |
| `TrainingMapChooser` | custom (rewrote TrainingBot's old REGIONS table) | `TrainingMapFinder.java:20`; commits `03d13cb21` → `a8047c5d1 2026-10-19 Keep high-level bots grinding when nothing is in their band` → `cf4a0a01b 2026-10-19 Send outgrown training bots to other regions` |
| `LevelingPath` / `LevelingPathChooser` | custom | `6abcc6fcf 2026-10-20 Ticket 31: pick bots' training maps from a leveling path by band and job`; `e83641da8 2026-10-20`; `20372d455 2026-10-28` (lv120 gates, LKC) |
| `GearFirstPlanner` | **port** of nutnnut/Cosmic `BotGrindPlanner` (GreenCatMS, AGPL-3.0, `dev ad57bf2c3`), adapted | header `GearFirstPlanner.java:6-17`; `docs/PROVENANCE.md:148` |
| `GearFirstAdvisor` | custom (our map index + drop table + capacity-aware weighting) | `e0a7026d8 2026-10-30 Gear-first map choice and farm-<item> goals behind -Dslums.grind.gearFirst (ticket 162 part 2)`; `988eb4ef0 2026-10-30 Weigh a crowded map in the gear-first choice too` |
| `BotMapCap` | custom | `3fde80134 2026-10-23 Cap how many bots the bot systems put on one map instance`, `956c55486`/`23b10cfa3 2026-10-27` (towns) |
| `BoundedMaps` | custom generic bounder | `c2b1198b9 2026-10-23 Bound the per-player and per-pair cooldown maps (P3)` |
| `OffscreenRewardBudget`, `OffscreenProgression` | custom | `f226794cd 2026-10-19 Cap what bots take from each map and from the world each day` |

## Tests and QA

Unit tests (`src/test/java`), all in `soloMapling/ArtificialPlayer/BotGrindSystem/` unless noted:

| Test | Covers |
|---|---|
| `LevelingPathTest` | shipped YAML shape and content: 15 bands, 515 entries, last band hi 255, every level 1–255 mapped, 120+ bots in Lower Grandis/Future, job→group, group-vs-general weighting (`:117`), party flags, unreadable file leaves the path empty |
| `LevelingPathChooserTest` | job/level preference, cohort spread, `BotMapGates` refusal, rebirth gates, `maxAbove`, walkable vs abroad + home move, hop gates, fare affordability, full/cooldown maps, crowd weighting, world-wide trip rationing (`:230`) |
| `GearFirstTest` | need-gear 0 → pure EXP (`:33`), dominant attainable upgrade → gear-led (`:41`), jackpot unattainable (`:52`), kill rate capped by respawn supply (`:61`), farm scores = copies/hour (`:67`), off by default (`:95`), switched on the drop map weighs more and becomes the want (`:103`) |
| `TrainingMapFinderTest` | in-band vs fallback, deep-hub pro, current-map origin, rebirth gate, nothing admitted → nothing |
| `TrainingTravelTest` | region attraction, wanderlust vs outgrown, fares, gated towns, world-wide departure pacing |
| `TrainingRegionsTest` | install/restore only |
| `KsMannersTest`, `KsWarSpotTest`, `PlayerPlatformExclusionTest` | player-owned mobs/platforms, KS-war camping, a player's platform never picked |
| `SpotFinder`-adjacent: `StandOffsetTest`, `RepositionClearanceTest`, `RoamApproachReissueTest`, `StackReissueTest`, `ApproachTest`, `LootPickerTest` | stand offsets, step clearance, walk re-issue, loot filters |
| `OffscreenRewardBudgetTest` | yield follows spawns/respawn/occupancy, rates scale it, item limit clamps, mobless map yields nothing, occupants share one window, `mapLeftAloneRefillsOnlyOneWindow` (`:101`), per-item units |
| `OffscreenProgressionTest` | partial/whole/multi-level accrual, grind cap keeps surplus |
| `soloMapling/ArtificialPlayer/BotMapCapTest` (+ `server/BoundedMapsTest`, `server/maps/MapBotCountTest`) | town max only while the pull is on, shipped default 50, override parsing, 0 = no cap, config reload, login channel choice, `excludeFull`, read-out, event ceiling never lowers a cap, bot counting |

`LevelPathSweepCheck` is a dev `main`, not a test: it sweeps the path offline against a real WZ tree.

Auto-QA (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`) — **there is no `t-gr-*` check**; grinding is covered
only indirectly:

| Check | What it asserts | Line |
|---|---|---|
| `t-cb-1` "Skill choice and buffs" | skills/buffs used while grinding | `:677` |
| `t-cb-2` "Facing and spacing" | swings and steps read right | `:682` |
| `t-cb-3` "Potions, restocking and deaths" | potions/restock/death handling ("give the grinders time to start grinding", `:689`) | `:688` |
| `t-cb-4` "Neutral bots give you space" | holds `map:104040000`, `map:104040001` | `:698` |
| `t-sp-1` "Bots spread out" | `TALLY SPREAD map` | `:710` |
| `t-pt-1` "Bots walk with pets that loot" | pet looting | `:726` |
| `t-gb-3` "Gear-first grinding" | a bot wanting an item picks an in-band map that drops it; probe `Probes162.gearFirstFarm` (`src/main/java/soloMapling/autoqa/Probes162.java:811-860`), SKIPs when `GearFirstAdvisor.enabled()` is false (`:813`) | `:1448-1450` |
| `t-ld-1` 120+ ladder | lv120 gates and new drop tables | `:1369` |
| `t-rv-2` KS war | nemesis starts a KS war | `:143` |

`!qa` scenarios: `src/main/java/soloMapling/qa/CombatScenarios.java` (shared grind fixture `:40`,
`grinderReport` `:86`, habits/loot `:182-204`, KS manners `:224-240`, spread `:290-337`, pets `:356-370`),
`src/main/java/soloMapling/qa/BotLifeScenarios.java` (`stageGrinders` `:41`, `:84`, `:198`).

**Not covered:** no test or check asserts the *phase* of a bot standing on a mobless map (the b240 mechanism above);
no test asserts capacity-comparison consistency across `TrainingMapChooser.java:134/155/187`; no check drives a full
grind session end-to-end (travel → spot claim → kills → loot → leave); `CampStrategy`, `RoamStrategy`,
`StackStrategy`, `PatrolStrategy`, `GrindBrain`, `GrindLoot` have **no direct unit tests** (only source-text scans such
as `StackReissueTest.java:12-18`).

## Known issues

From `/root/work/STATUS-1.6.4.md`:

| Card | Sev | Issue |
|---|---|---|
| b240 | S3 | "Regular bots say they're grinding while standing in town" (`/root/work/STATUS-1.6.4.md:59`, refs `23fe8f08f` planner path + `3aba87f15` town-greet). Dialogue is gated (`ConvoLines.java:114-122`, `BotThreads.java:1067-1075`); the GRIND phase itself is not — see the three entry paths under `## How it works`. |
| b276 | S4 | A bot's map-scoped PLAN/ROUTE lines never reach the GM QA feed (`/root/work/STATUS-1.6.4.md:78`). Same class as the grind's `debugChat("GRIND on map …")` / `spotLabel()` output; triaged as a QA-tool defect. |
| b284 | S3 | Auto-QA at 10 lanes reports false failures (`/root/work/STATUS-1.6.4.md:80`) — the grind-adjacent lanes (`t-cb-*`, `t-sp-1`, `t-gb-3`) are inside that blast radius. |
| b287 | S4 | `t-xc-5`: a PANIC-style scroll trader holding a Chaos/White Scroll never lists it (`/root/work/STATUS-1.6.4.md:83`) — the offscreen scroll finds that feed it are called from the grind's abstract loot (`TrainingBot.java:2824`). |
| b225 / b232 / b241 | S3/S4 | Party/PQ cards (`/root/work/STATUS-1.6.4.md:57-60`); they intersect grind only through the party steer that pre-empts map choice (`TrainingBot.java:1917-1923`). |

Own findings:

- `NEW-FINDING:` `TrainingBot.java:1085` — `setTrainTarget(player.getMapId(), Math.max(1, MapMobIndex.level(...)))`
  coerces the mobless sentinel `-1` (`MapMobIndex.java:43`) to **1**, so the `currentMobLevel > 0` guard at
  `TrainingBot.java:2141` passes on a town map. Combined with `OffscreenSupplies.accrue` returning `1.0` for a null
  rate (`OffscreenSupplies.java:87-90`) and `accrueAbstractExp`'s `KILLS_PER_MIN × mobLevel` fallback
  (`TrainingBot.java:2862`, `:115`), a bot in a town "grinds" free abstract EXP at ~0.5 exp/s plus map-budget loot
  (`:2165`) with no supply cost. That is very likely the *reported* b240, not just its wording.
- `NEW-FINDING:` `TrainingBot.java:1917-1923` — the party/steer branch never consults `BotMapCap` (only the staging
  hold does, `:1887`) and `partyTargetMapId()` returns a bot couple's/guild leader's map without a mob check when the
  party has no real player (`:2638-2660`), so a party can stack unbounded on one map instance while every other pick
  path is capped. The comment at `:1915-1916` says this is deliberate ("deliberately skip the capacity/crowd
  weighting") — worth confirming it is still wanted with 3k bots.
- `NEW-FINDING:` `TrainingMapChooser.java:134`, `:155`, `:187` vs `:340` — three different comparisons against the
  same map capacity (`<`, `<=`, `<=`) while `weightedPick` hard-zeroes at `n >= cap`. A map at exactly capacity is
  admissible by the accept path but unreachable by the weighted draw, so the two halves disagree about what "full"
  means.
- `NEW-FINDING:` `SpotFinder.java:135-150` — `mapBotCapacity` mixes two estimators: a live profile's **snapped** spot
  `shareCap` sum, or `estimateShareCapacity` over **raw WZ** spawn x/cy with a WZ foothold group standing in for the
  nav region (`:148-150`, `:154-190`). For a map with no live profile the number the chooser admits against can differ
  from the capacity `CampStrategy` later discovers, which then reports `mapSaturated` (`CampStrategy.java:271-283`) and
  feeds the crowd bail (`TrainingBot.java:2266-2330`) for a purely estimated over-admission.
- `NEW-FINDING:` `OffscreenRewardBudget.java:37-39` — the comment claims `WINDOW_MS = 60_000` "matches the floor of
  TrainingBot's offscreen GRIND cadence (60-120s), so a bot normally sees one window per tick and the bucket cap never
  bites", but the GRIND macro delay is `60_000 + rng(60_000)` (`TrainingBot.java:253-255`), i.e. up to 120 s. The test
  `mapLeftAloneRefillsOnlyOneWindow` (`src/test/java/soloMapling/ArtificialPlayer/BotGrindSystem/OffscreenRewardBudgetTest.java:101`)
  shows a long gap refills **one** window, so every tick past 60 s silently forfeits a window of the bot's loot.

## State

**Working, with two open defects** (b240's phase hole and the capacity-comparison inconsistency).

Evidence:

- Maps are chosen and held end to end: `choose` → `reserve` → `GO_TRAIN` → `GRIND` → `leaveGrind`/`clearTrainTarget`
  (`TrainingMapChooser.java:185-193`, `TrainingBot.java:2036`, `:2106-2134`, `:2576-2584`).
- The four archetypes are all wired and reachable (`GrindStylePolicy.java:25-38`, `GrindBrain.java:369-384`), each
  with its own claim/travel/fight loop (`CampStrategy.java:101-110`, `RoamStrategy.java:55`, `StackStrategy.java:72`,
  `PatrolStrategy.java:60`).
- Looting runs on every path (at-feet, instant, sweep, pet) and is unit-tested at the filter layer
  (`LootPickerTest.java:67-105`) and exercised live by auto-QA `t-pt-1` (`AutoQaCatalog.java:726`).
- 41 test classes' worth of assertions pass on the pure decision layers (path, chooser, gear-first, finder, regions,
  travel, caps, budget); the IO/orchestration classes (`CampStrategy`, `RoamStrategy`, `StackStrategy`, `GrindBrain`,
  `GrindLoot`, `TrainingBot`'s phase machine) are covered only by QA checks and source-text scans.
- Live runs the gear-first path (`docs/release/1.6.4.conf:271`) and the default leveling path, so both are
  battle-exercised; `BOT_MAP_CAP` is on its code default 50 live.

## Open questions

1. Is the party/steer bypass of `BotMapCap` (`TrainingBot.java:1917-1923`) still intended at 3k bots, or is it a
   leftover from before the cap existed (`BotMapCap` landed 2026-10-23, the steer comment is undated)?
2. Should a mobless target ever be able to enter `GRIND` — i.e. should the `Math.max(1, MapMobIndex.level(...))`
   coercion be replaced by a mob check in every ternary, or should `doGrind` refuse to start on a mobless map
   (`TrainingBot.java:2106-2134`)?
3. `BotMapCap` treats a map id as the unit, but `TrainingMapChooser.BOTS_PER_MAP` counts world-wide while
   `BotSpotClaims` counts per channel instance (`BotSpotClaims.java:25-29`, `SpotFinder.java:546-555`). Is the
   world-wide reservation intentionally more conservative than the per-instance claim, or is one of them stale?
4. `LevelingPath.yaml` documents "5 entries disabled with # INVALID" and "109 entries lie outside TrainingRegions.yaml's
   [regions]" in its header; are those 109 rows intentionally dead, or should the sweep (`LevelPathSweepCheck`,
   `!env path sweep`) be run in CI?
5. No test exercises `CampStrategy`/`RoamStrategy`/`StackStrategy`/`GrindBrain` directly — is a headless `MapleMap`
   fixture feasible (the QA world already stages grinders, `BotLifeScenarios.java:41`), or is QA the only intended
   gate for these?
