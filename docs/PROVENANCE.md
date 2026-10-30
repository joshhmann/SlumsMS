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
