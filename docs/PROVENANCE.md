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

### Later ports: movement fixes NN-011 (branch `162-navfixes`, 1.6.1)

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

### Later ports

Ports from nutnnut/Cosmic after the merge above, one entry per port. (The 1.5.2/1.6.x ports listed in
docs/tickets/157-nutnnut-tools/adoption-matrix.md section 8 still need their entries here.)

#### Bot questing (branch `162-questing`, ticket 158), nutnnut/Cosmic `77e0c5bc9` (branch `dev`)

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

#### KPQ automation, 1.6.1 (branch `162-contentruns`, ticket 130)

Ideas only, no code copied: our orchestrator, solvers and stage data are our own, checked against our scripts.

| Source @ commit | What it gave | Our files | Switch |
| --- | --- | --- | --- |
| Kiwiism/cosmic_agents (https://github.com/Kiwiism/cosmic_agents, AGPL-3.0) @ `3c7e09435`, `AgentKpqDefinition`, `AgentKpqCombinationOrder` | Cross-check of the KPQ answers, rectangles and combos (theirs list the stage-2 ropes in another order; ours follow `9020001.js`); the "one mover" order for wrong formations (two members stay, one moves), reimplemented as a chain ending one mover from the answer | `content/kpq.yaml`, `CombinationSolver.plannedWrongCombos` | `-Dslums.mechanics=true` |
| nutnnut/Cosmic (https://github.com/nutnnut/Cosmic, AGPL-3.0) @ `77e0c5bc9` (dev), `pq/BotKpqStage1`, `pq/BotKpqStage5` | Read for the stage flow (coupon target, the stage-5 reward). Not taken: their stage-5 reward without the NPC (`giveEventReward` called directly) and cosmic_agents' missing-pass recovery (sets the clear property / adds passes): our bots claim the prize by talking to Cloto and every pass comes from a monster | `KillCollectHandInSolver`, `Orchestrator.afterClearTick` | `-Dslums.mechanics=true` |

#### Temple of Time driver, 1.6.1 (branch `162-contentruns`, ticket 158 item 5), nutnnut/Cosmic `77e0c5bc9` (branch `dev`)

| From theirs | Our files |
| --- | --- |
| `server.bots.BotTempleProgressionManager`: the mainline chain 3500-3521 with its NPCs and lanes, the job sub-quest that unlocks 3507, the Sorcerer's potion, a per-bot "ambition" level to start at, stepping aside when a lane is crowded | `soloMapling/ArtificialPlayer/BotQuestSystem/TempleRoute.java`, `TempleOfTime.java`, `TempleLanes.java` |

Ours: the driver feeds ticket 158's quest path (a BotLife `QUEST` goal, `BotQuestLog`) instead of an autopilot errand;
lane dispersal is a cap per map and channel with a channel change before stepping aside; the lane exits are bot
transit edges gated by the exit's quest (`timeQuest.js`, run in a test) and Temple maps are gated by progress; the
sub-quests, 3507's end and 3514 run through their own quest scripts (`BotQuestScript`), where upstream calls
`forceStart`/`forceComplete` and grants the potion itself; 3521 is not driven; every number is checked against
Quest.wz, Map.wz and our scripts (`TempleRouteTest`).

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
  `calibration.json` and `!killcalib`, off on live (`-Dslums.killcalib`). No code copied.
- Ticket 157, live bot inspect: the idea of `/api/botdebug`, `/api/mapinfo` and the world map's bot positions from
  nutnnut/Cosmic dev `77e0c5bc9`. Ours: `BotInspectApi` / `InspectViews`, loopback-only, secret header, GET-only,
  cached, off on live (docs/design/inspect-api.md). Their web server is not ported.

### Later ports (1.6.2, ticket 161: bot builds and party support)

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
