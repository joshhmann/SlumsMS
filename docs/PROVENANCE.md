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
