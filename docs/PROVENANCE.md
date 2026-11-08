# Source provenance

The starting installation is MapleRoot Full Repack. The client history begins with the existing joshhmann/kaentake checkout at 2a43a01e7f4cdd75cda08ab533049230760d7cfa; pinned upstream submodules are preserved. The companion source is the MapleRoot ijl15 source port, with only relative project paths adjusted for this repository layout.

Server integration was developed against the captured running MapleRoot source and SoloMapling commit 47b61b5381118ebdc1064ddbd0b1c32f75778871. The separate joshhmann/SoloMapling headless-drop branch was deliberately excluded. Original file-level author/licence notices remain in place.

The manually accepted gameplay checkpoint is Accepted-Addons-20261018-r1. The public source baseline additionally replaces the inherited hardcoded registration webhook with an optional private environment setting. Two new tests cover disabled/configured construction without sending messages, and isolated full startup passed. Exactly two server class entries differ from the earlier accepted JAR for that change; all other 22,709 entries are byte-identical.

Full game Data, server WZ definitions, real configuration and database dumps remain external deployment inputs. Their protected checkpoint has complete asset fingerprints and restore notes. No live rollout occurred during repository preparation.

## SoloMapling bot-persistence merge (branch `persistence-on-v120`)

The playerbot code comes from the owner's SoloMapling fork, https://github.com/joshhmann/SoloMapling (upstream
https://github.com/MadaraGameDev/SoloMapling at `47b61b53` plus the bot-persistence series), AGPL-3.0. Parts of
SoloMapling were in turn derived from **GreenCatMS by nutnnut**, https://github.com/nutnnut/Cosmic (AGPL-3.0):
the GC physics/navigation movement system (`soloMapling/ArtificialPlayer/GCMoveSystem/`, "extracted near-verbatim")
and adapted bot combat targeting; those files keep their in-file credit lines. Any later port from nutnnut/Cosmic or
from https://github.com/Kiwiism/cosmic_agents (AGPL-3.0, derived from nutnnut's companion bots) is recorded here.

### Navigation graph v68 (branch `nav-v2`)

Ported from nutnnut/Cosmic (GreenCatMS), https://github.com/nutnnut/Cosmic, AGPL-3.0, `src/main/java/server/bots/`
at commit `59c528899`. Each piece was merged function by function into GCMoveSystem on top of our own graph v60
(drop caps) and v61 (widened rope top-exit). It was not copied file by file. `GRAPH_VERSION` is 68.

| Their version | Commit | What was ported | Our files |
| --- | --- | --- | --- |
| 66 | `7c0d28701` | Ground walk follows the standing foothold's prev/next chain across a joined fork | `BotPhysicsEngine` |
| 67 | `59c528899` | Skip phantom cross-region JUMP/FLASH_JUMP edges that land on the source region's own ground; keep the walked chain across shared ground | `BotNavigationGraph`, `BotNavigationGraphProvider`, `BotNavigationManager`, `BotMovementState` |
| 62 | `23975ce86` | Rope-exit and rope-transfer CLIMB edges carry a Y launch window with per-endpoint cost; steering and fire gate use the window | `BotNavigationGraph`, `BotNavigationGraphProvider`, `BotNavigationManager` |
| 60, 61, 64, 65 | `7d20d650f`, `56d367534` | TELEPORT and FLASH_JUMP edges with X launch windows; `teleportLanding` physics rule; flash-jump landing simulation; skill-savings planning gate; teleport and flash-jump edge execution | `BotPhysicsEngine`, `BotNavigationGraphProvider`, `BotNavigationManager`, `BotMovementManager` |
| — | `6c8896f98` | Tests build nav graphs into `cache/bot-nav-test` (`-Dbot.nav.cacheDir`) | `BotNavigationGraphProvider`, `pom.xml` |

Not ported: v63 (baked portal-route buckets), because it depends on their committed-route and route-cache layer,
which GCMoveSystem does not have. Also not ported: their MP and meso gates on skill use, and their flash-jump
landing-momentum change.

Ours: the job-based skill gate (`NavMovementSkills`, stock classes only), and the exclusion of skill edges from
walk-only, coarse/offscreen and reachability queries.

Tests adapted from theirs: `BotForkWalkChainTest` (from `BotRegion11ForkOscillationTest`),
`BotSharedGroundPhantomJumpTest`, and the rope-window cases in `BotRopeLaunchWindowTest` (from
`BotNavigationManagerTest`).

### Bot combat formula (branch `combat`, real bots ticket 07)

After nutnnut/Cosmic (GreenCatMS), https://github.com/nutnnut/Cosmic, AGPL-3.0,
`src/main/java/server/combat/CombatFormulaProvider.java` at commit `eddb5bc65`. Not copied: their provider works on
registered buffs and their own attack path. We rewrote the formula set they collected as pure arithmetic in
`BotCombatFormula`, behind our attack driver, with our own reader of a bot's numbers (`BotDamage`).

| From theirs | Our files |
| --- | --- |
| The client's mastery floor; the spell min/max with spell mastery; Lucky Seven / Triple Throw and Dragon Roar formulas; Heal against the undead with its target multiplier; the ranged-weapon melee swing ("degenerate") formulas; mob weapon/magic defence and level-gap reduction; Shadow Partner lines as a share of the line they mirror; crit chance from Critical Shot / Throw | `BotCombatFormula`, `BotDamage` |
| `StatEffect` reads the WZ `mastery` grade and exposes `prop` | `server/StatEffect` |

Ours: the physical ceiling taken from `Character.calculateMaxBaseDamage` exactly as `parseDamage` checks it; the
crit as a bonus added to the skill's damage %; bots' shown (unregistered) buffs, ammo, Maple Warrior, Element
Amplification, charges and Berserk; the 32-bit line clamp; the offscreen kill rate and EXP (`OffscreenCombat`).

### Bot gear optimizer (branch `gear`, real bots ticket 10)

After Kiwiism/cosmic_agents, https://github.com/Kiwiism/cosmic_agents, AGPL-3.0,
`src/main/java/server/agents/capabilities/equipment/` (`AgentEquipmentOptimizer`, `AgentEquipmentStatSnapshot`)
and `src/main/java/server/EquipRequirementChecker.java` at commit `3c7e09435`. Not copied: their per-weapon Pareto DP,
their stat snapshot and their inventory gateway. We rewrote the search as a small hill climb for bots that carry a
handful of equips (`BotGearPlanner`), behind our own gateway (`BotGear`).

| From theirs | Our files |
| --- | --- |
| Requirement chaining (a piece worn only once other pieces lift the stat it needs, every pick re-checked without itself); the `reqJob` job-niche bit check; an overall blocks the pants, a two-handed weapon the shield; loadouts compared by damage, then by stats | `BotGearPlanner` |

Ours: damage from our own formula (`BotDamage.gearValue`: the bot's main attack with its mastery, skills and shown
buffs); wearability as an equip order from the base stats; worn gear always counts as wearable; the weapon rules
(`BotWeapons` kinds, the same drawable attacks, 1st jobs and beginners keep their kind); unknown, cash, other-gender,
omit-list and rebirth-locked equips refused; event triggers (loot, level-up) instead of a sweep.

### Monster Book addon (branch `addon-monsterbook`, ticket 19)

Source: the "MonsterBook Publish" addon package. Its server half (`server-cosmic/`) and its data tool
`tools/monsterbook_data/build_book.py` were written for another Cosmic fork.

Kept from it:
- the three query types and their wire layout;
- the per-mob chance in the droppers reply;
- the Episode stat whitelist and element table;
- the spawn and AreaBoss scan.

Ours:
- the opcodes (0x3734/0x3735, because Beauty owns 0x372C);
- `server.monsterbook.DropSearchService`, which answers from an in-memory snapshot instead of a query per call;
- the chance math, matched to this server's drop roll (no world card rate);
- bot exclusion and a flood cap;
- the card list merged from monstercarddata and Item.wz;
- `build_book.py` rewritten over parsed XML, because this server's WZ dump is minified. It takes drops from MySQL
  or a TSV and writes the HP strings the client book needs. MapleLib is not used.

## Later ports

Smaller ports from nutnnut/Cosmic (https://github.com/nutnnut/Cosmic, AGPL-3.0) after the merges above, one line each
(the commit message of each port credits the same sha). Earlier 1.5.2/1.6.x ports (ticket 80 stability fixes,
7a7a03de7, owned alts 51b88ee7b/cc5058459, megaphone buy requests 257ee6b48) are credited in their commits and in
the adoption ledger (ticket 157 matrix, section 8).

- Ticket 157, path trace on stuck: the idea of `BotPathLogger` (a 120-tick nav ring buffer, zero cost when off) from
  nutnnut/Cosmic dev `77e0c5bc9`. Ours: `NavTrace` (no code copied), attached to `NavStuckLog` spots and the stuck
  report, kept only on full simulation, capped per hour.
- Ticket 157, `!navprobe` and the nightly portal/NPC probe: the idea of `/api/navprobe` (start and target regions,
  the edge path, the reason) from nutnnut/Cosmic dev `77e0c5bc9`. Ours: `NavProbe`, `NavProbeCommand`, `!qa nv-1` /
  auto-QA `t-nv-1`; a GM command behind the TEST-only `DebugTools` switch, never a web route.
- Ticket 157, `!env lod pin`: the idea of `/api/lod` (pin maps to full fidelity until a restart) from nutnnut/Cosmic
  dev `77e0c5bc9`. Ours: `ObserverTracker` pins (at most 5), a GM 4 `!env` subcommand, TEST only, audited.
- Ticket 157, gear-pick reason in `!botwhy`: the idea of `autoequip debug` from nutnnut/Cosmic dev `77e0c5bc9`. Ours:
  `BotGearWhy` over our own `BotGearPlanner` plan.
- Ticket 157, `switches` in the dashboard's live.json: the read-only half of the `/admin` settings view from
  nutnnut/Cosmic dev `77e0c5bc9`. Ours: `EffectiveSwitches` (no writes, secrets and paths left out).
- Ticket 157, kill calibration: the idea of `BotKillCalibration` and `/api/killcalib` (real kill rates of watched bots
  against the offscreen model, by job and level band, billed as kills over elapsed time) from nutnnut/Cosmic dev
  `77e0c5bc9`. Ours: `KillCalibration` against our `OffscreenCombat`, report-only (no auto-tuning, no persistence),
  `calibration.json` and `!killcalib`, switch `-Dslums.killcalib` (off by default; 1.6.2 ships it on for live). No code
  copied.
- Ticket 157, live bot inspect: the idea of `/api/botdebug`, `/api/mapinfo` and the world map's bot positions from
  nutnnut/Cosmic dev `77e0c5bc9`. Ours: `BotInspectApi` / `InspectViews`, loopback-only, secret header, GET-only,
  cached, switch `-Dslums.inspect` (off by default; 1.6.2 ships it on for live), docs/design/inspect-api.md. Their web
  server is not ported.

### Bot builds and party support (1.6.2, ticket 161)

From nutnnut/Cosmic (GreenCatMS), https://github.com/nutnnut/Cosmic, AGPL-3.0. Each piece is ours in code and tuning, behind
a switch that is off by default; the upstream commit is the source of the idea or the rule.

| Their commit | What | Our files |
| --- | --- | --- |
| `3fb120faf`, `47afdaa1e` | AP builds named "dexless", "lukless", "strless", "pure" and "25 dex" (`BotBuildManager`); ours rolls them for new and reborn bots from the bot's id, with our own shares per class (`slums.bots.apvariants`) | `BotBuild`, `BotProgression`, `BotSupportSwitches` |
| `930ca5a01` | Magic Guard damage split: the share of a hit that comes off MP, the MP shortfall falling back on HP (`slums.bots.magicguard`). Their `CRITICAL_SURVIVAL_BUFFS` recast priority and `effectiveMaxHp` valuation are not ported | `BotVitals` |
| `67359bdce` | Fame on request ("fame me"); ours is gated by affinity, level 15, the server's own fame rules and once a day per pair (`slums.bots.famerequest`) | `BotFameRequest`, `Dispatcher` |
| (`BotBuffManager`, no single commit) | Party support: a Cleric line heals the hurt party member for MP on a cooldown, party buffs cost MP and are re-cast on a member who lacks them, held buff pots are drunk in a fight (`slums.bots.partysupport`) | `BotPartySupport`, `BotBuffDriver`, `BotBuffEffects` |

### Ticket 162: nutnnut's gear optimization (branch `162-gearopt`)

From nutnnut/Cosmic (GreenCatMS), https://github.com/nutnnut/Cosmic, AGPL-3.0, `src/main/java/server/bots/`, branch
`dev`. Rewritten onto our types and rules (each file's header says what was kept and what is ours); every part is
behind a switch that is off by default.

| Their file @ commit | What was ported | Our files | Switch |
| --- | --- | --- | --- |
| `BotEquipManager.java` @ `2771d9ea1` | The per-weapon Pareto DP over body slots (frontier dedup, wearable-only pruning, frontier cap), per-slot dominance pruning with requirement comparison, requirement-relevant and accuracy dimensions, and `chooseSecondaryTarget` (the dexless AP target) | `BotGearOptimizer`, `GearMemo`, `BotGear` (switch, shared `GearScoring`), `BotBuild`/`BotProgression` (secondary cap) | `-Dslums.gear.optimizer=pareto`, `-Dslums.gear.dexless=true` |
| `BotGrindPlanner.java` @ `ad57bf2c3`, `BotGrindAdvisor.java` @ `2943481dd` | Gear-first map scoring (seek time, kill rate capped by respawns, attainability-discounted DPS gain, gear need blending EXP and gear), farm-an-item pick by expected copies an hour, the dropper-map candidate build | `GearFirstPlanner`, `GearFirstAdvisor`, `TrainingMapChooser`, `OffscreenCombat.rateOn`, `LifeGoals`/`LifePlanner`/`LifeMemory`/`LifeRuntime` (FARM_ITEM aspiration) | `-Dslums.grind.gearFirst=true` |
| `BotEquipManager.java` @ `2771d9ea1`, `BotInventoryManager.java` @ `f19fed568` | Self-reserve per slot track (relevant stats per job, effective values, Pareto with level need, 3 per track by ceiling) and the valuables shelf (trade value above clean stats, bounded shelf, never-sell score) | `JunkSort`, `BotBagRoom` (town sale keeps them) | `-Dslums.junk.sort=true` |
| `BotScrollValuer.java` @ `502bb23e2` | The reproduction-cost valuer (restart-on-ruin DP), near-verbatim; ours: the restart fixed point is solved by Newton steps on the optimal policy (upstream's 120 plain iterations capped long-shot values) | `soloMapling.itemPool.ScrollValuer`, `UpgradeSimulator` (price), `BotScrolling` (value curve) | `-Dslums.scroll.ev=true` |
| `BotScrollPlanner.java` @ `a38171b6f` | The EV scroll planner (stochastic DP over score and slots, boom priced, fallback gate, opportunity margin), near-verbatim | `ScrollPlanner`, `BotScrolling.evPlan` | `-Dslums.scroll.ev=true` |

Tests adapted from theirs: `ScrollValuerTest` (from `BotScrollValuerTest`), `ScrollPlannerTest` (from
`BotScrollPlannerTest`).

### Job-advance errand (ticket 159, branch `162-joberrands`)
Both ticket 159 ports are AGPL-3.0 to AGPL-3.0. Concepts from nutnnut/Cosmic `dev`; the code is ours, on our travel,
progression and economy systems.
Credit: `nutnnut/Cosmic 77e0c5bc9` (`server/bots/BotAutopilotManager.java`, the job-change detour errand and its
"route unreachable" give-up). Taken: the idea of walking to the class instructor and advancing there, and of giving up
to an instant advance when the trip cannot finish. Ours: `JobErrand`, the gate in `BotBuild.settle`, the
watched-only rule (`WatchGate`), the timeout, the trip through `GCMovement`/`BotTransit`. Switch
`-Dsolomapling.jobErrand=true` (default off).

### 4th-job skill and mastery books (ticket 159, branch `162-joberrands`)
Credit: `nutnnut/Cosmic 77e0c5bc9` (`server/bots/BotSkillBookManager.java`: "wanted when it raises a skill in the
bot's build, even before reqSkillLevel"). Taken: the want rule and the use-one-book-at-a-time pause. Ours:
`BotSkillBooks`, master-level caps in `BotSkillPlan`/`BotBuild.State`, the keep/sell rule on `SaleRules`, the
bot-shop stock hook. Switch `-Dsolomapling.botSkillBooks=true` (default off).
### Ticket 112: the Maker plan and batch (branch `162-maker`)

`dev`. Rewritten onto our types and rules (each file's header says what was kept and what is ours). The batch is
behind economy.yaml `maker.botBatch` (off by default); the plan is read-only.

| `BotMakerPlanner.java` @ `356fbfeb1` | The question and its shape: equips the character can Maker-craft right now (Maker level, materials) that would be upgrades, ranked best first. Ours: one plan for players, alts and bots, valued through `MakerGearScore` (162-gearopt's `GearScoring` plugs in), plain items only (upstream also samples stimulant and gem rolls) | `MakerPlan`, `MakerGearScore`, `AttackGainScore`, `@maker plan` | none (read-only) |
| `BotMakerManager.java` @ `6fd0512a0`, `MakerProcessor.java` @ `1ff686027` | "Make monster crystals" and "disassemble trash" through the player's Maker path (upstream split `makeLeftoverCrystal` / `disassembleEquip` out of the packet handler). Ours: a `Hands` seam so population bots use the bot ledger, daily per-bot and world caps, monster leftovers only, junk = not an upgrade by the gear score, run at a bot's town stop or on the alt owner's `@maker crystals|scrap` | `BotMaker`, `BotMakerHands`, `MakerService.makeLeftoverCrystal` / `disassemble` | `maker.botBatch` (+ `maker.perBotDaily`, `maker.worldDaily`) |

### Companion command and sharing set (ticket 99 / 162 part 3, 1.6.2, branch `162-companions`)

nutnnut/Cosmic, https://github.com/nutnnut/Cosmic, AGPL-3.0 (the same licence as ours). Each port names the upstream
commit or file in its commit message.

From `dev` at `77e0c5bc9` (files) and the two commits named. Behind the switches `COMPANION_ORDERS_ENABLED`,
`COMPANION_TRADES_ENABLED`, `COMPANION_UPGRADES_ENABLED`, `COMPANION_SUPPLIES_ENABLED`, `OWNED_ALTS_INVITE_SUMMON_ENABLED`
(all off by default).

| From theirs | Our files |
| --- | --- |
| `BotChatManager` command words (patrol, sentry/guard/camp, move here, trade/drop category words, "show me your hat", "any upgrades?", "request?", "trade upgrades", "need pot/ammo") and `BotCommandParser` targeting (name, prefix, slot) | `BotCommandParser` (ticket 14's parser, extended; ours already had targeting after cosmic_agents) |
| `8fd725404` formations (split/stagger, spread, stack, random, left, right; tight/loose presets; px qualifier) | `Formation`, `CompanionStance`, `GCMovement.setFollowOffset`, `GCMovementDriver.formationPlace` |
| `BotTradePacing` (step 900-2200 ms, confirm 1500-3000 ms) | `TradePacing` |
| `BotInventoryManager.startTradeSequence/tickTrade/collectItems` (bot-driven window: invite, one item a beat, chat, wait for the partner, confirm) | `BotGiveTrade`, `TradeCategories`, `CompanionItems` |
| `BotOfferManager` (offer spare gear to the owner and siblings, compared against what they wear and carry) | `GearUpgrades` (scored by our `BotGearPlanner` through a `Scorer` interface ticket 162 can replace) |
| `BotPotionManager`, `BotAmmoManager`, `BotRockManager` (best donor, keep a reserve, share by trade) | `SupplyShare`, `CompanionItems.need/shareWithSibling` |
| `59f9bc091` party invite of an offline own character spawns it | `OwnedAltInvite` (party-window invite only; the buddy-list "fake online" half is not ported) |

Ours: one implementation for owned alts and party bot friends; every item moves through the player's own window
path (`Trade.putFromInventory`, now shared with `PlayerInteractionHandler`) or a normal drop; stances stay inside the
owned-alt activity gate (no autopilot); wording never gives a companion away.

### Movement fixes NN-011 (branch `162-navfixes`, 1.6.1)

From nutnnut/Cosmic (GreenCatMS), https://github.com/nutnnut/Cosmic, AGPL-3.0, `src/main/java/server/bots/`, dev
branch. Each is behind its own switch in `NavPortSwitches` (default off). Already ported before this branch (1.5.2,
`2582286c9`): `235b6aefa` (ground continuity at overlapping footholds), `f5787ab0c` `8a7e7b3a5` `362926fdf`
`26b372e7c` (walk-off drops, our graph v69), `f424904a8` (swim wall unfreeze); the frozen-air watchdog
(`7c4a2d517`) came with the GC movement system.

| Their commit | What was ported | Switch | Our files |
| --- | --- | --- | --- |
| `3516aa238` | A swimming bot pressed into a wall fires the cooldown-gated swim-jump burst to rise over it (UP-hold alone loses to swim gravity) | `-Dbot.nav.swimWallJump=true` | `BotMovementManager` |
| `43b9f06c8` (runtime part) | The parked-at-a-launch-gate watchdog survives incidental nav clears and counts freshly planned hops, so a hop re-served every tick is still given up. Their other runtime part (drop a committed route when the goal point moves) has no counterpart: GCMoveSystem has no committed routes | `-Dbot.nav.blockedPosWatchdog=true` | `BotNavigationManager`, `BotMovementManager`, `BotMovementState` |
| `43b9f06c8` (their v70 part b) | The v67 phantom shared-ground guard also drops TELEPORT edges that land on the source region's own ground. Graph-affecting: with it on the graph cache folder is `v69-tp` | `-Dbot.nav.teleportPhantomGuard=true` | `BotNavigationGraphProvider`, `NavDeepOverlay`, `NavDashboardData` |
| `43b9f06c8` (their v70 part a) | Client wall collidability: ground walking scans no walls on region-model maps (only the map side), airborne walls collide only for a mover of their own zMass group or the map's base group (mover group from `lastRegionId`; sims from the foothold under their start); foothold layer/zMass parsed from WZ. The `Foothold.isCollidableWall` heuristic stays for the switch-off path. Graph-affecting: cache folder `v69-zm` | `-Dbot.nav.zmassWalls=true` | `BotPhysicsEngine`, `BotNavigationGraphProvider`, `BotNavigationMapLoader`, `server/maps/Foothold`, `server/maps/MapFactory` |

Tests adapted from theirs: `ZMassWallTest` (from `BotDeityRoomSharedGroundTest` and their 193000000 wall test),
`TeleportPhantomGuardTest` (from the same file's teleport case).

### Bot questing (branch `162-questing`, ticket 158), nutnnut/Cosmic `77e0c5bc9` (branch `dev`)

| From theirs | Our files |
| --- | --- |
| `server.bots.BotQuestIndex`: which quests a bot can run, from Quest.wz (unscripted, start and end NPC, complete reqs limited to kills plus gates `canComplete` re-checks) | `soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestIndex.java` |
| `server.bots.BotQuestScorer`: quest worth as a multiple of the bot's grind EXP per minute (value / forgone EXP over the NPC trip) | `.../BotQuestSystem/BotQuestScorer.java` |
| `server.bots.BotQuestManager` ideas: piggyback on the grind map, the real `canStart`/`start`/`canComplete`/`complete` gate, NPC map from the current map or its return town, quest chat | `.../BotQuestSystem/BotQuests.java`, `BotQuestChat.java` |

Ours: mob quests only (no talk, fetch or auto quests, no disk cache); quests whose actions a headless bot can't take
(random or chosen items, items taken back, chained quests, info strings) are left out; required mobs on the grind map
count as neither value nor cost in the score; a quest is a BotLife goal with GOAP steps (`LifeGoals.Kind.QUEST`,
`LifePlanner` TAKE_QUEST / HUNT_QUEST / TURN_IN_QUEST), not an autopilot errand; offscreen bots only record the quest
in a capped log in their life profile (`BotQuestLog`); rewards go through `BotYieldCaps`; the chat answers come from
the bot's own log (ticket 156).

### KPQ automation, 1.6.1 (branch `162-contentruns`, ticket 130)

Ideas only, no code copied: our orchestrator, solvers and stage data are our own, checked against our scripts.

| Source @ commit | What it gave | Our files | Switch |
| --- | --- | --- | --- |
| Kiwiism/cosmic_agents (https://github.com/Kiwiism/cosmic_agents, AGPL-3.0) @ `3c7e09435`, `AgentKpqDefinition`, `AgentKpqCombinationOrder` | Cross-check of the KPQ answers, rectangles and combos (theirs list the stage-2 ropes in another order; ours follow `9020001.js`); the "one mover" order for wrong formations (two members stay, one moves), reimplemented as a chain ending one mover from the answer | `content/kpq.yaml`, `CombinationSolver.plannedWrongCombos` | `-Dslums.mechanics=true` |
| nutnnut/Cosmic (https://github.com/nutnnut/Cosmic, AGPL-3.0) @ `77e0c5bc9` (dev), `pq/BotKpqStage1`, `pq/BotKpqStage5` | Read for the stage flow (coupon target, the stage-5 reward). Not taken: their stage-5 reward without the NPC (`giveEventReward` called directly) and cosmic_agents' missing-pass recovery (sets the clear property / adds passes): our bots claim the prize by talking to Cloto and every pass comes from a monster | `KillCollectHandInSolver`, `Orchestrator.afterClearTick` | `-Dslums.mechanics=true` |

### Temple of Time driver, 1.6.1 (branch `162-contentruns`, ticket 158 item 5), nutnnut/Cosmic `77e0c5bc9` (branch `dev`)

| From theirs | Our files |
| --- | --- |
| `server.bots.BotTempleProgressionManager`: the mainline chain 3500-3521 with its NPCs and lanes, the job sub-quest that unlocks 3507, the Sorcerer's potion, a per-bot "ambition" level to start at, stepping aside when a lane is crowded | `soloMapling/ArtificialPlayer/BotQuestSystem/TempleRoute.java`, `TempleOfTime.java`, `TempleLanes.java` |

Ours: the driver feeds ticket 158's quest path (a BotLife `QUEST` goal, `BotQuestLog`) instead of an autopilot errand;
lane dispersal is a cap per map and channel with a channel change before stepping aside; the lane exits are bot
transit edges gated by the exit's quest (`timeQuest.js`, run in a test) and Temple maps are gated by progress; the
sub-quests, 3507's end and 3514 run through their own quest scripts (`BotQuestScript`), where upstream calls
`forceStart`/`forceComplete` and grants the potion itself; 3521 is not driven; every number is checked against
Quest.wz, Map.wz and our scripts (`TempleRouteTest`).

### Boss run and Zakum phases, 1.6.4 (branch `164-bossfight`, ticket 130 boss lane), BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`

| From theirs | Our files |
| --- | --- |
| `server.agents.capabilities.expedition.boss.AgentBossRunSession` (the run's phase machine: registering, fighting, post-clear, ended) | the phase shape only, as `MechanicsRuns` + `Orchestrator` stages; no session class is ported |
| `server.agents.capabilities.expedition.boss.BossTargetRules` (144 lines): the boss fight's phases `WAITING_FOR_SUMMON`, `ARMS`, `TRANSITION`, `BODY`, `DONE`, the arm/body/add roles, the melee floor-arms-first tiers, the adds threshold, "a fake part is never a target", "a boss part needs a human engaged on it", and the immunity and reach filters | `soloMapling/mechanics/BossTargetRules.java` |

Ours: the rules are fed from our `StageView` (the live `EventInstanceManager` and map) and turned into one ordered
candidate list per member by `BossPhasesSolver` (`solver: boss-phases`) instead of their `AgentBossDefinition` catalog;
Zakum's body is ordered as the chain our server actually runs (their `BossTargetRules` sees one body at a time and has
no chain, so the three phases `8800000 -> 8800001 -> 8800002` and the "any phase on the map is a target" rule are ours
and not ported: the chain is WZ `revive` in `Mob.wz/8800000.img.xml` (0 = `8800001`, `8800001` -> `8800002`, `8800002`
none) consumed by `Monster.killBy` (`Monster.java:899-973`, `:903` `getRevives()`, `:924-933` spawns the next phase);
`MapleMap.makeMonsterReal` only un-fakes the body `8800000` when the last arm dies, `MapleMap.java:1785-1810`);
the arm order and the "eight arms before the body" rule come from our own scripts and map code
(`scripts/reactor/2111001.js`, `MapleMap.java:4939-4947`, `:1786-1810`), the clear from the script's own
`defeatedBoss=1`; `reachPx` is 0 for every part because our `MobHitboxIndex` has no vertical box origin, so every arm
lands in the floor tier; their Horntail catalog entry (`agent-bosses/boss-rules.json`) is **not** ported: phase B's
Horntail row is ours, read from `scripts/event/HorntailBattle.js`, `scripts/reactor/2408003.js`, `2401000.js`,
`scripts/portal/hontale_BR.js` and `MapleMap.java:4908-4954,4939-4947`, and it reuses the same `BossTargetRules` port
(the three rooms, the door gating and the `defeatedHead` count have no counterpart in their draft).

### Attack floor on a high-defence target, 1.6.4 (branch `164-bossfight`, ticket 130 boss lane), BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`

| From theirs | Our files |
| --- | --- |
| `server.agents.capabilities.combat.AgentAttackPlanScoringPolicy` (73 lines): score a plan by expected damage, but track `minimumKillsFullHpTargets` from `CombatFormulaProvider.estimateMinimumDamage`, and let a guaranteed kill (every target's minimum at or above its full HP) outrank the average | `soloMapling/ArtificialPlayer/BotAttackSystem/BotCombatFormula.estimateMinimumDamage` and the floor overload of `BotSkillChoice.choose` |

Ours: the floor is read from the same `Range` the average already comes from (`Range.min()`, after the target's
defence, no crit - a crit only raises a line), so there is no second damage model; their full scoring policy
(useful-vs-raw DPS, cooldown tie-break, kill guarantee) is **not** ported, only its idea that a plan's minimum decides
when the average is blind. The choice applies it as one extra rule behind `-Dslums.bossfight.damagefloor` (off by
default): a boss always prefers the higher floor, an ordinary mob only when the rule's own pick would do nothing.

### Expedition death count, 1.6.4 (branch `164-bossfight`, ticket 130 boss lane), BotStory `81a3b1df`

| From theirs | Our files |
| --- | --- |
| `server.expeditions.ExpeditionDeathCount`: one atomic budget per run, charged on a player death, armed only when the mod is on, bots and companions never spend it, the death that spends the last one ends the run | `soloMapling/mechanics/ExpeditionDeathCount.java` |

Ours: the budget is armed from the `-Dslums.bossfight.deathcount` system property (off by default) with a cap of
`-Dslums.bossfight.deathcount.cap` (default 10) and a per-boss override, instead of their `config.yaml`
`mods.DEATH_COUNTS` map; "who is a player" is our `Character.isBot()`/`isOwnedAlt()` pair instead of their BotClient
and `BotHelpers` tests; the run ends through the event script's own `scheduledTimeout` with a fallback that moves
everyone out and disposes the instance, instead of their client's death-count panel, which needs the `DEATH_COUNT`
opcode only their patched client has. Their `set(int)` GM override is not ported.

### Boss row data for the TD battles, Maha and Castellan, 1.6.4 (branch `164-bossrows`, ticket 130 boss lane), BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`

BotStory was read for one thing only: the recorded kill order of the revive chains, and the min-level/target entries that
our own scripts already state. No component model, focus/keepOut band, ledge list or `stagePortal` is taken from it - the
rows are `solver: boss-phases` stages over our own `StageView`, and their maps, gates, spawn sites and clear signals are
read from our scripts and our WZ dump.

| From theirs | Our files |
| --- | --- |
| `agent-bosses/boss-rules.json` `neo_bergamot.targetPriority` = `[7220003, 7220004, 7220005]` and `neo_nibelung.targetPriority` = `[8220013, 8220014, 8220015]`, the only upstream entries that record a chain's order | `content/bergamot.yaml` and `content/nibelung.yaml`'s `mobs:` lists, in that order; the order itself is confirmed independently from WZ `info/revive` (`Mob.wz/7220003.img.xml` -> `7220004` -> `7220005`, `Mob.wz/8220013.img.xml` -> `8220014` -> `8220015`) as consumed by `Monster.killBy` (`Monster.java:903-963`), so the yaml quotes the dump and only the ordering was cross-checked against them |
| `neo_dunas` (`minLevel` 70, `targetPriority` `[8220010]`), `neo_aufheben` (`minLevel` 70, `[8220011]`), `neo_oberon` (`[8220012]`) | `content/dunas.yaml`, `content/aufheben.yaml`, `content/oberon.yaml`: the same numbers, but each is our script's own (`TD_Battle2/3/4.js:28`, `:37`), so nothing new is imported - the agreement is recorded because the dossier proposed the comparison |

Nothing is taken from BotStory for `maha.yaml` or `castellan.yaml`: neither project carries a definition for them.

Ours, and not portable from anywhere: every gate is the script's own (`TD_Battle*.js:26-28,39,41`;
`CastellanBattle.js:29-30,39,41`; `MahaBattle.js` declares none at all and reads `minPlayers` at `:82`, `:96` and `:105`,
which is why its row states `minPlayers: 1 / maxPlayers: 1`); the entry paths are `scripts/portal/TD_Boss_enter.js:4-5,20`
(party), `scripts/quest/21401.js:23-24` (one character) and `scripts/npc/9111058.js:35,173` (expedition); every clear is
the script's `clearPQ` with no property, hence `clear: { instance: true }`; the spawn sites are the map's own life or the
script's `setup()` (`CastellanBattle.js:133-134`). The `spawnWaitSeconds: 60` on these rows is a policy number - how long
a room may stand empty while its boss appears - and is not measured data from any source.

The WZ facts (map life, revive chains, `Quest.wz/Check.img.xml`'s `21401` block) come from the dump at
`/root/work/SlumsMS-170-evan/wz`, which is **not** version-pinned to this branch; each row's `sources:` names the file it
relied on, and `REPORT-bossrows.md` lists them per id.

### Boss row data for the reactor rooms and Pink Bean, 1.6.4 (branch `164-bossrows`, ticket 130 boss lane), BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`

Same rule as the round above: BotStory was read for the kill order and the never-target list, nothing else. Every row's
maps, gates, reactor ids, spawn sites and clear signal come from our own scripts and the WZ dump; where we disagree with
them the row follows our script and the disagreement is written down.

| From theirs | Our files |
| --- | --- |
| `balrog_normal.targetPriority` = `[8830002, 8830001, 8830000]` and `neverTarget` = `[8830004, 8830005, 8830006]` | `content/balrog.yaml`'s `mobs:` in that order, and the same three ids left out. Independently confirmed from our script: `BalrogBattle.js:145-153` spawns `8830002` and the fake `8830000`, `Mob.wz/8830006.img.xml` revives `8830001`, and `:140-143` kills the left claw `8830006` on a timer, so it must not be fought |
| `scarga.targetPriority` = `[9420542, 9420543, 9420544, 9420547, 9420548, 9420549]`, the order of the two chains | `content/scarga.yaml`'s `mobs:`, in that order. Also confirmed from WZ `info/revive` (`Mob.wz/9420542.img.xml` -> `9420543`, `9420547.img.xml` -> `9420548`) and from `scripts/reactor/5511000.js`/`5511001.js` spawning the first form of each chain |
| `showa.targetPriority` = `[9400112, 9400113, 9400300, 9400122]` - the chain, plus one of the room's seven trash mobs | `content/showa.yaml` takes the chain `[9400112, 9400113, 9400300]` and drops `9400122`: the trash is respawned every 15 s by the script (`ShowaBattle.js:112-116`), so listing one of seven would put a kill in the row's order that the row cannot promise |
| `pinkbean.neverTarget` = `[8820000, 8820019, 8820020, 8820021, 8820022, 8820023]` | `content/pinkbean.yaml` lists none of them, and `RowRunSimulationTest.aBotPartyWalksPinkBeansWavesToTheDefeatedBossProperty` asserts no statue reaches a `Fight` step. Their `targetPriority` is **not** used: theirs is `[8820006, 8820002, 8820005, 8820004, 8820003, 8820001]`, which is their runtime's focus order; ours is the order `PinkBeanBattle.js:306-313` waves them in (`8820002` -> ... -> `8820006`, then `8820001`) |
| `papulatus.targetPriority` = `[8500001, 8500002]` | **not used**: our reactor spawns `8500000` (`scripts/reactor/2201004.js:28`), so our row starts the chain at `8500000`. Their `adds` (`8500000`, `8500003`, `8500004`) do not exist in our room |
| `latanica.targetPriority` = `[9420513]` | not used: `content/latanica.yaml` (round 3) lists `9420513` because our
`scripts/reactor/5411000.js:24` spawns exactly that id. The value coincides; the source is our script |

Nothing is taken from BotStory for `krexel.yaml` or `elemental.yaml`: neither project carries a definition for them
(`cosmic_agents` and `nuttnut/Cosmic` carry no boss targeting at all, and BotStory's 26 keys do not include Krexel or
Elemental Thanatos).

Ours, and not portable from anywhere: the reactor ids and their order (`2201004`, `5411001`, `8001000`, `5511000` then
`5511001`) are the scripts' `act()` bodies; the fact that each is clicked once per stage is our solver's rule, derived
from those scripts being unguarded (`scripts/reactor/2201004.js:22-29` spawns on every `act()`); every clear is
`clear: { instance: true }` because the scripts write `setEventCleared(...)` and no property our `StageView` can read
(`PapulatusBattle.js:223-227`, `KrexelBattle.js:213-216`, `ShowaBattle.js:196-207`, `ScargaBattle.js:187-190`,
`ElementalBattle.js:225-228`, `BalrogBattle.js:250-274`) - Pink Bean's `defeatedBoss == 1` (`:261`) is the one equality;
the gates are `PapulatusBattle.js:28-29`, `KrexelBattle.js:28-29`, `ShowaBattle.js:29-30`, `BalrogBattle.js:27-28`,
`ScargaBattle.js:29-30`, `ElementalBattle.js:27-28`, `PinkBeanBattle.js:27-28`; Showa's bonus map `801040101` is dropped
rather than made a stage, because `clearPQ` spawns its mob inside the call that clears and the party never walks there;
and `spawnWaitSeconds: 60` is again a policy number - how long an emptied room may stand while its boss appears - not
measured data.

The WZ facts (map life, revive chains) come from `/root/work/SlumsMS-170-evan/wz`, which is **not** version-pinned to
this branch; each row's `sources:` names the file it relied on, and `REPORT-bossrows.md` lists them per id.

### Boss row data for Latanica and the five quest instances, 1.6.4 (branch `164-bossrows`, ticket 130 boss lane): no outside source

Nothing in round 3 comes from another bot project. None of these six has a definition in BotStory, cosmic_agents or the
nuttnut fork, and none was needed: each row is the script read line by line.

| From theirs | Our files |
| --- | --- |
| - (no definition of `latanica`, `magnus`, `nwill`, `nlucid`, `nverus` or `ndarknell` in any of the three) | - |

Ours, and the reason the new primitive exists:

- **The door item.** `scripts/portal/captinsg00.js:4` is the only place that asks for `4000381`, and
  `LatanicaBattle.getEligibleParty` never asks again, so the row declares `entryItems: [ 4000381 ]` and the solver waits
  on it rather than special-casing a portal script. The item is not consumed, so one member carrying it opens the door
  for the party - which is what the portal does for the player who walks through it.
- **The quest gate on the five.** `31166` (Magnus), `31162`/`31161`/`31164`/`31163` (Will, Lucid, Verus, Darknell) are
  quest states checked by the expedition recruiters and by `ExpeditionBots.memberQuest`
  (`:217-228`, `:283-284`), not by the instances. The rows state it as a precondition; no run writes quest state.
- **The five 5 s spawns.** `MagnusBattle.js:91-92`/`:120-125`, `NWillBattle.js:116-117`/`:145-150`,
  `NLucidBattle.js:115-116`/`:144-149`, `NVerusBattle.js:116-117`/`:145-150`, `NDarknellBattle.js:116-117`/`:145-150`
  are the only sources; the WZ dump holds none of their targets as map life, so there is nothing for the room to stand
  up before the script's own spawn.

Delli is deliberately absent: its win is the instance's own clock, and that room is owned by `164-opq` @ `ac08ce380`
(`DelliHoldSolver`, `content/dellibattle.yaml`, `DelliBattleSolversTest`). No second spelling lives here.

`GuardianNex` ships as six rows (`content/guardiannex1.yaml`-`guardiannex6.yaml`), and nothing was taken from anyone for
them either: the six caves are `GuardianNex.js`'s `eventBossIds` paired with `Map.wz`'s map life
(`Map/Map2/240070010`-`240070060`), the cave is chosen by the player's active quest in
`scripts/portal/TD_neo_inTree.js:8-18`, and the quest ladder (70/73/77/81/85/90, NPC `2082004`) is
`Quest.wz/Check.img.xml`'s. The one thing the rows needed from the engine was a way to tell six rows for one event
script apart: `MechanicsRuns.contentFor(eventScript, instanceMapIds)`, fed by the map ids the script asked for through
`EventInstanceManager.getInstanceMap`. Single-row scripts are unaffected. See `REPORT-bossrows.md`.

Every id in every row is now re-checked against the dump by `ContentRowWzIntegrityTest` (`-Dwz-path`, else `wz`), so a
dump that disagrees with a row fails a test instead of emptying a room.

The map and mob facts again come from `/root/work/SlumsMS-170-evan/wz`, **not** version-pinned to this branch:
`Map/Map5/541010100` (reactor `5411000`, no mobs), `Map/Map4/401060100` (one NPC, `3004006`), `Map/Map4/450007441`,
`450004751`, `450010101`, `450012211` (empty life), and `Mob/9420513`, `8880002`, `9990301`, `9990150`, `9990415`,
`9995009` (no `info/revive`). `REPORT-bossrows.md` names each file per id.

### Orbis PQ stage data, 1.6.4 (branch `164-opq`, ticket 164), cosmic_agents `3c7e094357329932734a860e5a831d57add27a00`

| From theirs | Our files |
| --- | --- |
| `server.agents.capabilities.partyquest.opq.AgentOpqDefinition` (136 lines): the map ids, the item ids, the monster ids, the level window, the statue-scar table and the room exit portal ids | `soloMapling/mechanics/content/opq.yaml` (`minLevel`/`maxLevel`, the map and item ids of each stage) and `soloMapling/mechanics/OpqSubRoomSolver.EAK_MAP` |
| `AgentOpqCoordinator.tickStatueRestoration` / `tickPieceCarrier` / the four-room split (`LOUNGE_SUBROOM_ENTRY_PORTALS`, one bot per side room) | `soloMapling/mechanics/OpqSubRoomSolver`: the same "the piece is in the room, Eak is elsewhere" split, but ours sends the whole party into one side room because the four rooms share the drop |
| `AgentOpqCoordinator.MUSIC_PLAYER` / `ROOT_POT` / `hitNearestLegalReactor` (the item is dropped inside the reactor's box) | `SolverStep.PlaceItem` + `StepExecutor.placeItem`: the step carries the reactor (name or id) and the executor drops inside its box, because `MapleMap.activateItemReactors` matches the reactor's item *and* quantity |
| `AgentOpqDefinition.STATUE_SCAR_BY_ITEM` / `STATUE_SCAR_POSITION` (the six-scar pairing and where the scars stand) | `OpqStatueSolver.SCAR_PIECE`: the same pairing, but taken from our own `Reactor.wz/2008000-2008005.img.xml`, where each scar carries exactly one item trigger; the positions are not copied because `PlaceItem` resolves the scar through `MapleMap.getReactorByName` and drops at its own position |
| `AgentOpqDefinition.CENTER_PORTALS` / `ROOM_EXIT_PORTALS` / `LOUNGE_SUBROOM_ENTRY_PORTALS` (WZ portal **ids**, not names) | not used: our row warps stage to stage through `SolverStep.WarpTo`, because a WZ portal id cannot be looked up by name from the engine (`Orchestrator` accepts a solver's `WarpTo` on a map the stage does not own) |

### Henesys PQ stage data, 1.6.4 (branch `164-opq`, ticket 164), BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`

BotStory was read for the seed-bed pairing only; the row's numbers are the client WZ's, and every gate is our script's.

| From theirs | Our files |
| --- | --- |
| `capabilities/partyquest/hpq/AgentHpqDefinition.java:75-81` (`SEED_BEDS`: the six bed-to-seed pairs) | `soloMapling/mechanics/HpqBedsSolver.BEDS`: the same six pairs (`9108000`->`4001095` ... `9108005`->`4001100`), read from the beds' own WZ event slots instead (`Reactor.wz/9108000..9108005.img.xml`, `event/0`: `type 100, state 1, quantity 1`); the pairs appear in `HpqBedsSolver` and in `content/hpq.yaml`'s six `PLACE_ITEM` objectives, which is what the tests pin |
| their per-bed "drop and retry" loop | not copied: our `SolverStep.PlaceItemOn` + `StepExecutor.placeItemOn` drop once per bed, honour `MapleMap.activateItemReactors`' 5000 ms delay (`MapleMap.java:2665-2680`) as `ITEM_REACTOR_SETTLE_MS`, refuse a bed whose state has left zero, and stop after `PLACE_ON_TRIES` |

Nothing else in the row comes from another project: the gates (`3`-`6` players, level `10`-`255`, `10` minutes, recruit
`100000200`, front door `1012112`) are `scripts/event/HenesysPQ.js:26-40`, the clear signals are
`scripts/reactor/9108000.js:4-9` (the `stage` property) and `scripts/npc/1012114.js:12,66-76` (`1stageclear`, which
`setup()` never initialises - `HenesysPQ.js:111-114`), the cakes are `4001101` x `10` from that same NPC, and the run-ender
(any hit on the Moon Bunny `9300061`, `HenesysPQ.js:248-258`) is why no step in the row names it.

### Cafe PQ stage data, 1.6.4 (branch `164-opq`, ticket 164): no outside source

Nothing in Cafe PQ's six rows comes from another bot project. cosmic_agents and BotStory carry no Cafe PQ definition at
all (no `cafe` file in either tree), and the dossier's suggestion to read the scripts first is what was followed: the
gates (`3`-`6` players, level `21`-`255`, `45` minutes, recruit and front door `193000000`), the six areas' `eventMaps`
and `couponsNeeded` counts read `400`/`350`/`350`/`450`/`500`/`300`, the per-kill drop (`:251-259`) and the clear
(`clearPQ` sets the instance's flag and nothing else) are `scripts/event/CafePQ_1.js` .. `_6.js:27-31,36,38,39,131-138,
239-249,251-277`, and the closer is `scripts/npc/1052013.js:56-63`.

The one thing worth recording as a *correction* to the plan: `1052013.js:59` is `cm.gainItem(4001007, couponsNeeded)`, a
positive quantity, so the Computer pays the party rather than charging it - the plan's "drop the coupons by the leader
and hand them in" would have asked the bots for what the NPC is giving them. The rows therefore carry the count as a
`COLLECT` objective and talk to the NPC with no item.

### LudiMaze PQ stage data, 1.6.4 (branch `164-opq`, ticket 164), cosmic_agents `3c7e094357329932734a860e5a831d57add27a00`

cosmic_agents was read for the *representation* of a maze PQ and nothing else.

| From theirs | Our files |
| --- | --- |
| `capabilities/partyquest/lmpq/` (a sixteen-room maze with one goal, a coordinator that walks rooms) | `soloMapling/mechanics/LudiMazeSolver`: the same shape - a stage over many maps, a goal map, a walk between them - but the graph is read from our client data at run time (`StageView.portalsOn(int)` over `Map.wz/Map/Map8/8090500*.img.xml`) and kept per instance in `stageMemory()`. Their arm layout is their instance's; nothing of it is copied, and our maze's passages are fixed, not shuffled |

Nothing else in the row comes from another project: the gates (`3`-`6` players, level `51`-`255`, `15` minutes, recruit
`220000000`, front door Rolly `9103001`, exit `809050017`) are `scripts/event/LudiMazePQ.js:27-32,37`; the clear is
`9103000.js:66-73` (`clearPQ` and nothing else); the pass and its count are `9103000.js:62` and
`scripts/reactor/8098000.js`; and the frontier - each member starting on a random one of fifteen maps - is
`LudiMazePQ.js:144-150`.

### Pirate PQ stage data, 1.6.4 (branch `164-opq`, ticket 164), cosmic_agents `3c7e094357329932734a860e5a831d57add27a00`

cosmic_agents was read for the *representation* of a ship PQ - a party moved through six rooms on one stage - and
nothing else; it carries no Pirate PQ definition.

| From theirs | Our files |
| --- | --- |
| `capabilities/partyquest/ppq/` (a coordinator that walks a party through a stage's rooms in order) | `soloMapling/mechanics/PirateQuestSolver`: the same shape - one stage, six maps, a chain of conditions - but every condition is one of our own scripts, and the ids are our WZ. Nothing of their instance's layout is copied |

The gates are `scripts/event/PiratePQ.js:29-39` (`3`-`6` players, level `55`-`255`, entry `925100000`, exit
`925100700`, recruit `251010404`, six maps, `eventTime` four minutes restarted per stage at `:241-268`); the exclusives
are `:73-76` (`4001117`, `4001120`, `4001121`, `4001122`); the clear is `clearPQ` `:337-344`, which sets the instance's
flag and writes no property. The chain is our scripts' own: `scripts/portal/davy_next0..4.js` (the five gates),
`scripts/npc/2094002.js:47-78` (the three medal sets, in order, through `stage2`) and `scripts/npc/2094001.js:45-58`
(the rescued leader's talk, the only writer of the clear); the four doors are item reactors `2519000`..`2519003` taking
one `4001117` each, which is `Reactor.wz/2519000..2519003.img.xml` and `Map.wz/Map/Map9/925100400.img.xml` (names
`sMob1`..`sMob4`), not anything from another project.

### Treasure PQ stage data, 1.6.4 (branch `164-opq`, ticket 164): no outside source

Nothing in the row comes from another bot project - neither cosmic_agents nor BotStory carries a Treasure PQ definition.
Every id is ours: the gates (4-6 players, level 140-255, 45 minutes, `674030000`/`674030100`/`674030300`) are
`scripts/event/TreasurePQ.js:25-37`, the exclusive item `4032118` is `:59-62`, the front door is `scripts/npc/9220018.js:50-80`,
the summon is `scripts/reactor/6741001.js`, and the clear is `clearPQ` (`:232-237`) reached only by `9400589`'s death
(`:244-252`).

### DelliBattle stage data, 1.6.4 (branch `164-opq`, ticket 164): no outside source

Nothing in the row comes from another bot project - neither cosmic_agents nor BotStory carries a DelliBattle definition.
The gates (one to two players, level 120-255, six minutes, `925010300` with recruit and exit `925010200`) are
`scripts/event/DelliBattle.js:26-36`, the front door is `scripts/npc/2095000.js:48-70`, and the clear is the script's own
`scheduledTimeout` -> `clearPQ` (`:119-124`, `:208-211`); `friendlyKilled` (`:194-199`) is the only failure and
`monsterKilled` (`:213`) is empty, which is why the row has no count of its own.

### Magatia PQ stage data, 1.6.4 (branch `164-opq`, ticket 164): no outside source

Nothing in the row comes from another bot project: cosmic_agents and BotStory carry no Magatia definition. The orders
come from our scripts - the gates and property seeds from `scripts/event/MagatiaPQ_A.js:27-32,37,127-133,218-246`, the
four modelled stages from `scripts/npc/2112013.js` (books), `MagatiaPQ_A.js:418-426` (empty room),
`scripts/reactor/2618000.js:27-41` (beaker) and `scripts/npc/2112005.js:72-87` (both files). The index used to find them
was this repository's own scan notes in `/root/work/.pqscan/our-pq-b.md`, which is our material, not another project's;
every claim it gave was re-read in the script before being written into a row.

Where our server's scripts disagree, the scripts win and the row follows them:

- **The altar takes exactly twenty in one drop.** `scripts/reactor/2006000.js:1-5` spawns Eak from the reactor's item
  reaction, and `MapleMap.activateItemReactors` (`src/main/java/server/maps/MapleMap.java:2664-2679`) compares
  `getReactItem().getRight() == item.getQuantity()`, so the stack has to land whole. Their coordinator never puts the
  clouds on the altar at all (it only hits the reactors and then talks); ours places `20` in one stack.
- **The storage room clears on an empty floor.** `scripts/npc/2013001.js` (map `920010300`) asks
  `countMonsters() == 0 && countItems() == 0`, and `MapleMap.countItems()` counts mesos too, so our `opq-sweep` loots
  with `Loot(0)` ("anything on the floor") instead of their reward-box-only pickup.
- **The statue and the garden are one stage.** Their coordinator keeps both in one tick machine as well, but ours has to
  be one *row* stage: the scar condition is reactor state (`getReactorByName("scar" + i).getState()`), and the only
  writable property is `statusStg8`, which `scripts/reactor/2006001.js` sets when the Root of Life reaches the base.
- **The lobby's pass is armed, not listed.** `scripts/event/OrbisPQ.js:162-163` sets the `music` reactor's event state to
  the weekday, and `Reactor.getReactItem(byte)` reads that as the slot, so our `opq-lobby` asks the reactor
  (`StageView.reactorEventState`) instead of carrying seven one-line tables.
- **The levers are a five-character string in `stage6_c`.** `scripts/npc/2013001.js` (map `920010700`) is the only
  place that picks the combination and checks it; their coordinator keeps its own lever model. Ours reads the property
  and clicks only the reactors named `"1"`..`"5"` whose state differs, shared out over the bots so no lever is toggled
  twice.
- **Three stages are not in the row.** The statue (`920010100`) needs six scar pieces, two of which come from the lobby
  and the sealed room; the garden (`920010800`) is only reachable from the finished statue; and the lobby and the sealed
  room themselves are left out for want of a solver, **not** for want of working code - `scripts/reactor/2008006.js` does
  set `statusStg3 = 0` when the day's pass lands in the music player's box (`scripts/event/OrbisPQ.js:162-163` arms it),
  and the sealed room's three platform areas come from the map file through `MapFactory.java:273-279`. Both are pinned by
  `src/test/java/scripting/reactor/OrbisPqLobbyStageTest.java` and
  `src/test/java/server/maps/OrbisSealedRoomAreaTest.java`; `REPORT-opq.md` names each stage with the exact line.
- **Nothing in the lobby or sealed-room work comes from another project's code.** The seven pass ids (`4001056`-`4001062`,
  in `Date().getDay()` order), the reactor id `2008006` and the three platform rectangles are read from our own
  `Map.wz/Map/Map9/920010400.img.xml`, `Reactor.wz/2008006.img.xml` and `Map.wz/Map/Map9/920010500.img.xml`; they appear
  only in the two tests above.

### Boss-fight potion lines and the out-of-pots warning, 1.6.7 (branch `167-alt-supplies-in-boss-fights`, ticket 167), nutnnut/Cosmic `b684bf78588d50512bcd62db9bb7cb85e4f73829` (AGPL-3.0)

Taken from `/root/work/research/Cosmic` (`https://github.com/nutnnut/Cosmic.git`), the bot autopot idea only:

- `src/main/java/server/bots/BotManager.java` - `AUTOPOT_HP_THRESH = 0.7f` and `AUTOPOT_MP_THRESH = 0.5f`, the boss
  lines our `CompanionSupplies.BOSS_HP_POT_AT` / `BOSS_MP_POT_AT` now use, and `POT_LOW_WARN = 100` / `POT_STOP = 10`
  as the shape of "tell someone that the potions are running out" (our `WARN_AT = 10`).
- `src/main/java/server/bots/BotPotionManager.java` - `setupAutopotForBot`, `computeAutopotChoice`, `PotionTier` and
  `PotionRanking`, read for how their code ranks a bag's HP and MP potions before it sets a threshold.

Changed in the port, all marked `// SlumsMS:` in
`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSupplies.java` and
`src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotVitals.java`: the ratios are a boss-run override of our
`BotVitals` lines (habit / 0.50 HP, 0.30 MP out in the world) rather than per-bot settings; the potion choice stays
ours (`BotVitals.drink` uses `SupplyData.Recovery` and `BotHoldings`); and none of the keymap/keybinding and
client-autopot-key code was taken, because our bots drink through `BotVitals.drink`, not a client autopot key.

## 1.6.7 wave 1: what a companion does when it dies in a boss run

### Boss-run revive and the rest after it, 1.6.7 (branch `167-alt-death-rules`, card `alt-revive-respawn`), BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`

BotStory's `src/main/java/server/living/roster/CompanionFieldBossRest.java` (AGPL-3.0, no header in their file) was
read for one thing only: a companion that died beside a boss rests after it gets up.

| From theirs | Our files |
| --- | --- |
| `CompanionFieldBossRest.resting(int, long)`: the rest ends when HP and MP are back to a percentage of max or a time limit passes, its `diedAtMs` map, `isAlive()` check, recovered test `hp*100 >= maxHp*percent && mp*100 >= maxMp*percent` | `soloMapling/ArtificialPlayer/BotPartySystem/CompanionBossRevive.java` (`resting`, `restingSince`, `restPercent`, `restMaxMs`) |
| `LivingWorldConfig` defaults `COMPANION_FIELD_BOSS_REST_PERCENT = 100`, `COMPANION_FIELD_BOSS_REST_MAX_MS = 120000` | `-Dslums.companion.rest.percent` (100) and `-Dslums.companion.rest.maxMs` (120000), never `config.yaml` keys |

Ours (the new file's header comment names the source and what changed; the file is a rewrite around the one ported
rule, so it carries no per-line `// SlumsMS:` marks - corrected in the Bots audit): their runtime lookups became
ours - "in a boss fight" is `ExpeditionHold.inRun` or `MechanicsHold.committed` at the death instead of
`AgentBossField.near` plus a LivingWorld roster, the owner/leader is `Character.getOwnedAltOwner()` for an owned alt
and `FollowerBot.leaderId()` for a regular instead of `AgentRuntimeRegistry`/`AgentPartyGatewayRuntime`, and the rest
clock starts at the revive instead of at the death, because the wait before the revive is a separate rule of ours.
Their sit-out rule (`SIT_OUT_DEATHS`, `SIT_OUT_MAX_MS`, `SIT_OUT_LINES` and its party-chat lines) is **not** ported:
the owner declined it. The doubling revive wait, the mesos price for an instant revive and `@botme revive` are ours,
not theirs.

### Player NPC rank counters load after the worlds, 1.6.7 (branch `167-playernpc-ranks`, card cosmic-upstream-fixes #193), P0nk/Cosmic `0aee9d7c3` (AGPL-3.0)

From https://github.com/P0nk/Cosmic/pull/193 ("Fix unable to create playernpc"): the static block in `PlayerNPC` that
filled the rank counters is gone, and the counters load in a `loadRunningRankData(con, worlds)` that `Server` calls.

Changed in the port:
- `Server` calls it after the worlds are up, with `getWorldIdBound()` (our world ids can have gaps, ticket 41), and
  not inside the startup DB block. Upstream calls it before the worlds, with the configured count.
- A load failure is logged rather than ending the boot.
- New `PlayerNPC.nextWorldRank` grows the counter list for a world id it hasn't seen, so a statue can never throw.
- `getAndIncrementRunningWorldJobRanks` is synchronized.
- Upstream's removal of `PlayerNPCFactory.loadFactoryMetadata` (the developer NPCs) is **not** taken: our developer
  NPCs stay.

## 1.6.7 wave 2: following that looks like a player

### Anti-stuck, reserved spots, climb following and the arrival / welcome-back lines, 1.6.7 (branch `167-alt-follow-plus`, card `alt-follow-plus`, ticket 167), cosmic_agents `3c7e09435` and BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364` (AGPL-3.0)

Read for shapes and thresholds only; no code copied. Files: `/root/work/research/cosmic_agents`
(`https://github.com/Kiwiism/cosmic_agents.git`), `/root/work/research/BotStory`
(`https://github.com/Incluwin/BotStory.git`).

| From theirs | Our files |
| --- | --- |
| cosmic_agents `src/main/java/server/agents/capabilities/movement/AgentStuckDetectionService.java`: the drift radius, the "grounded" versus "suspended" (in the air or on a rope) split, the per-bot stuck clock that resets whenever the bot is doing something, and the post-recovery cooldown | `BotPartySystem/CompanionStuck.java` (`DRIFT_PX = 8`, `STUCK_MS = 8_000`, the `busy` verdict, `COOLDOWN_MS = 30_000`) |
| cosmic_agents `src/main/java/server/agents/capabilities/recovery/AgentRecoveryTeleportService.java`: the recovery being a snap of the agent to the thing it follows | `GCMovement.setDownBeside` (same primitives as our `teleportTo`, but it snaps even while a player watches and counts it: the owner asked for the follow) |
| BotStory `src/main/java/server/living/roster/CompanionFollowGuard.java` `waitsAt`: companions keep clear of a PQ-entrance spot, each at its own ordered place beside the anchor | `BotPartySystem/CompanionSpots.java` (`NEAR_PX = 150`, `STILL_MS = 2_000`, `SLOT_PX = 40`, `CLEAR_PX = 40`; the order is the companion's index among its owner's loaded alts by character id, not a LivingWorld roster) |
| cosmic_agents `capabilities/navigation/AgentNavigationGraph.java` CLIMB edges and the `Climb*ExecutionService` files; BotStory `capabilities/navigation/AgentNavigationTargetService.java` `recentExitNudgeTarget` (the rope-top exit waypoint) | `BotPartySystem/CompanionClimb.java` (`CLIMB_DY = 60`, `ROPE_EXIT_PX = 24`, `CLIMB_TIMEOUT_MS = 12_000`); the climb itself is our existing `BotNavigationManager` CLIMB planning, so nothing new was added there |
| BotStory `server/agents/capabilities/dialogue/AgentTownLifeDialogueReactionService.java` (arrival intent per town) and `AgentChatWelcomeBackFlow.java` (`AFK_THRESHOLD_MS = 5 min`, didn't-move-then-moved) | `BotPartySystem/CompanionLines.java` (`OWNER_AFK_MS = 300_000`, one arrival per map with `MAP_COOLDOWN_MS = 600_000`, `GAP_MS = 30_000`) |

Changed in the port, marked `// SlumsMS:` where a line is theirs in shape: their agent runtimes, navigation graph
regions and dialogue-intent bus became our per-companion static helpers keyed by character id; the lines go through
our party chat / direct chat (`BotPartyChat.sayInParty`, `PacketCreator.multiChat`) and are silenced by our
`@quiet bots` (`BotNoiseFilter`); and the "welcome back" is driven by our own still-clock on the owner's position
rather than an NPC conversation state.

## 1.6.7: the chat side of companions (branch `167-alt-chat-reports`, card `alt-chat-reports`)

### Reports, the typo hint and the confirm before a drop, 1.6.7, BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364` and cosmic_agents `3c7e094357329932734a860e5a831d57add27a00` (both AGPL-3.0)

Read from `/root/work/research/BotStory` and `/root/work/research/cosmic_agents`. Both projects' source files carry
no licence header of their own; the licence is the repository's AGPL-3.0, quoted at the top of
`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionReports.java` together with the paths and the
"changed:" notes.

| From theirs | Our files |
| --- | --- |
| BotStory `src/main/java/server/agents/capabilities/dialogue/AgentDialogueReportFormatter.java`: `potionCount` (count the HP and MP stacks in the bag), `stats` (one line of level, job, hp/mp, the four stats, free AP) | `CompanionReports.pots`, `CompanionReports.stats` |
| BotStory `src/main/java/server/agents/capabilities/dialogue/AgentInventoryDialogueReporter.java`: `slotsReport` (used/total per tab, tab order equip, use, etc, setup) | `CompanionReports.slots` |
| BotStory `src/main/java/server/agents/capabilities/dialogue/AgentSupplyDialogueReporter.java`: `grindStartMessage` (the acknowledgement carries a low-supply aside) | `CompanionReports.lowNote` / `lowLine`, `OwnedAltOrders.ack`, `PartyBotOrders.carryOut` |
| BotStory `src/main/java/server/agents/commands/AgentCommandTypoSuggester.java`: `suggest`, `MIN_DISTANCE`-style bounded Levenshtein with an early exit, the 1-edit-under-6 / 2-edit rule and `CHAT_DENYLIST` | `CompanionReports.typoSuggestion`, `CompanionReports.distance`, `PartyBotOrders.typoHint`, `AltsCommand` |
| cosmic_agents `src/main/java/server/agents/dialogue/AgentDialogueCatalog.java`: the "just to confirm ... say yes" prompt shape | `CompanionReports.confirmFirst` |
| cosmic_agents `src/main/java/server/agents/dialogue/AgentPendingChatActionFlow.java`: one pending action per player; anything that is not the confirmation cancels it | `CompanionReports.confirmDrop` (one `Pending` per asker, `PENDING.remove` first) |
| cosmic_agents `src/main/java/server/agents/chat/AgentChatCommandClassifier.java`: the yes words | `CompanionReports.YES` |
| cosmic_agents `src/main/java/server/agents/inventory/AgentInventoryDropService.java`: refuse a drop on a map whose `FieldLimit` forbids it | `CompanionReports.dropBlocked` / `dropBlockedLine`, `CompanionItems.drop` |
| cosmic_agents `src/main/java/server/agents/capabilities/loot/AgentPassiveLootService.java`: a time window on the bag-full warning | `OwnedAltLoot.maySayFull` (per alt and tab, `CompanionReports.FULL_SAY_MS`) |

Changed in the port, all marked `// SlumsMS:` or stated in the file header: everything sits behind
`PartyCompanionPolicy.Feature.CHAT_REPORTS` (owned alts on, regulars off) and one class serves both kinds of companion;
the pot and slot counts come from our own code (`BotVitals.potionCount`, `Inventory.getSlotLimit/getNumFreeSlot`) and
the typo dictionary is our parser's `Verb` words, not BotStory's separate command list; the typo hint fires only on a
line addressed to a companion; the confirmation is one pending drop per player keyed by character id, 30 s, with no
item-choice, trade or skill-tree flows; and the bag-full window is a suppression inside `OwnedAltLoot.pick` because the
line is said by `OwnedAltCompanion.loot`.

## 1.6.7 wave 2: the quest mirror

### Party quests mirrored to companions, 1.6.7 (branch `167-alt-quest-mirror`, card `alt-qol-batch` group E), cosmic_agents `3c7e094357329932734a860e5a831d57add27a00` (AGPL-3.0)

From `/root/work/research/cosmic_agents` (`https://github.com/Kiwiism/cosmic_agents.git`),
`src/main/java/server/agents/capabilities/quest/AgentPartyQuestSyncService.java`:

| From theirs | Our files |
| --- | --- |
| `partyAgents(Character)`: the party-members loop that skips the source itself and keeps the headless-controlled members, `onlineMembers`, and the early return when the source has no party | `soloMapling/companions/CompanionQuestMirror.java` (`companions`, `isWith`) |
| `syncPartyAgentsQuestStart` / `syncPartyAgentsQuestComplete` and their hooks in their `server/quest/Quest.java` `start`, `complete`, `forceStart` and `forceComplete`: the shape of "after the source's own start or turn-in, walk the party", plus passing the source's NPC id on (`resolveQuestNpc`) | `server/quest/Quest.java` (`start`, `complete` only), called through `CompanionQuestMirror.onQuestStarted` / `onQuestCompleted` |
| `syncPartyAgentsQuestProgress` / `syncKingPepeYetiPartyProgress`: read, then **not** ported (see below) | `Character.raiseQuestMobCount` calls `CompanionQuestMirror.onLeaderKill`, which uses our own counting call |

Changed in the port, all marked `// SlumsMS:` where the code is a rewrite of theirs (the class carries the source line
in its header comment): they force every party agent's quest (`forceStartWithActions` / `forceCompleteWithActions`);
ours only mirrors when the companion passes our own `Quest.canStart` / `Quest.canComplete`, so a companion that is too
low a level or holds no item is skipped instead of being forced. A quest whose turn-in carries the player's reward
selection (`Quest.complete(chr, npc, selection)` with a non-null selection) is skipped for companions instead of being
forced with the leader's pick. Their progress mirroring is not used: our server has no party kill sharing to plug into
(their `syncKingPepeYetiPartyProgress` exists because their parties credit quest kills to the highest damager), so the
companion simply gets the same `raiseQuestMobCount` call a party member's own kill makes. Their gateway runtimes
(`AgentQuestSyncGatewayRuntime`, `AgentPartyGatewayRuntime`, `AgentCharacterGatewayRuntime`) are replaced by the party
and the bot registry we have: `Character.getOwnedAltOwner()` for an owned alt, `FollowerBot.leaderId()` for a regular,
and `PartyCompanionPolicy` for the switches. The owner's per-alt command gate (`@botme quests on|off <name>`) is ours.

## 1.6.7 wave 3: companion settings

### Per-owner companion settings, 1.6.7 (branch `167-alt-settings`, card `alt-qol-batch` group B), BotStory `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364` and nutnnut/Cosmic `b684bf7` (AGPL-3.0)

| From theirs | Our files |
| --- | --- |
| BotStory `server/living/roster/CompanionSettings.java`: the switches and potion percentages, the 10..90 bounds, "not written at its default" | `soloMapling/companions/CompanionSettings.java` |
| BotStory `server/living/roster/CompanionSettingsApplier.java` (`settings(ownerId, personaId)`: memory, else read once, else defaults) and nutnnut `server/bots/BotPrefsStore.java` (a small prefs table, upsert on change) | `soloMapling/companions/CompanionSettingsStore.java`, `database/migrations/069-companion-settings.sql` |
| BotStory `server/living/roster/ui/CompanionCardScreens.java`: the card's switch lines and potion counts | `soloMapling/menus/CompanionCardMenu.java` (our `MenuWindow`, no packet code) |

Changed:
- our own migration 069 replaces their self-creating table;
- one JSON blob per (owner, companion);
- no formation or loot-policy types (the loot mode is a stored string);
- 0 means "the bot's own potion line", so an untouched companion behaves exactly as before;
- the gates are in our shared `BotPartySupport` / `BotBuffDriver` / `BotVitals`;
- the load is lazy, and a bot's first read after a restart starts it (Bots).
