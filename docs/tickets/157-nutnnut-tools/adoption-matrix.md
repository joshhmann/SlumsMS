# nutnnut/Cosmic README_SERVER_BOTS.md: adoption matrix (2026-10-30)

Read only. Sources: nutnnut clone fetched 2026-10-30 (`/root/work/nutnnut/Cosmic`). Ours: `/root/work/SlumsMS-161-release`
(release-1.6.1 at a917ef1c9). Earlier passes: scratchpad `nutnnut-bots-report.md` / `nutnnut-misc-report.md` (10-24),
ticket 87 + `docs/design/nutnnut-second-pass.md` (10-25), ledger section 8 (NN-001..021).

**Nothing new upstream since our review.** The fetch brought only a P0nk/Cosmic branch (`upstream/feat/postgresql-database`).
Heads: master `b684bf785` 2026-07-01, experimental `0c00c507e` 2026-07-13, dev `77e0c5bc9` 2026-08-28 (the same head the
10-25 pass read). README last changed on master `1e8a93789` 2026-07-01; dev `356fbfeb1` 2026-07-10 adds only a
Shoutouts section (names a combined SoloMapling+GreenCat fork, Seikoxi/BotlingMaple, and "wisteria" for the bot inventory
client edit). The README is on master, but most of the code it describes is larger on dev (for example BotScrollManager is
77 KB on master and 159 KB on dev, and the market, FM, ZPQ, Temple and skill-book code is dev only). Paths below are under
`src/main/java/server/bots/` unless stated.

Status key: **A** adopted, **P** partly, **N** not adopted, **R** rejected earlier, **S** superseded by ours.
Effort is to port onto our design (S under 2 days, M up to a week, L more).

## 0. Web admin (`BotWorldGraphWebServer`, 2,972 lines on dev, plus `src/main/resources/web/{admin,worldmap,mapgraph,market}.html`)

What it is: the JDK `HttpServer` on **0.0.0.0:8089**, with **no auth**. Its javadoc says "localhost-only read-only", but
the code comment says "bound to all interfaces for LAN access... No auth". Its write routes include **stateful GETs**
(`/api/perf?on=`, `/api/lod`, `/api/bot/pathlog`, `/api/spawnbot`), so any web page a GM opens can trigger them by
CSRF. Only wipe has a confirm token. Ledger NN-020 rejected it, and that still stands. We already have the safe base:
the ticket 35 dashboard (`soloMapling.dashboard.DashboardExporter`, `DashboardMapExport`, `NavDashboardData`;
static app in `tools/dashboard/web` with tabs Map, Learning, Bots, Economy, Health, GM audit, Accounts; nginx basic auth,
TEST on :8091), plus `gm_command_audit` (`GmAudit`), and `SiteAuthApi` (127.0.0.1, which returns `gm_level` for the
site's staff login).

| Their page/route | What it does | Helps our QA / nav learning / behaviour learning | Ours today | Adopt how | Data source | Effort |
|---|---|---|---|---|---|---|
| `/` world graph | Maps as nodes; portal, taxi and ferry edges; live count per map. Flags **one-way-in traps** (reachable from Lith Harbor with no way back). | Nav: finds BotTransit/GCWorldGraph dead ends. QA: an auto-QA check "no trap maps" after every data change. | Dashboard Map tab is per-map only. No world-level graph and no trap check. | Static export of GCWorldGraph from DashboardMapExport; the trap check as an auto-QA check plus a unit test. | **Read-only over existing data** (GCWorldGraph); live counts are already in live.json. | S-M |
| `/map` worldmap.html (main UI) | WorldMap.wz continent images with live bot dots, search, sort, per-map panel (bots, chat, mobs), inspect, pathlog and perf buttons, "RTS mode" controls, spawn. | QA: watch a TEST world live and click a bot to see why it does what it does (today that needs being in game with `!botwhy`). Behaviour: see goal and plan step per bot. | Nothing live. live.json is aggregate, once a minute. | Read-only version first: dots and the inspect panel. Controls later and TEST only (see "control" row). | **New hook:** a positions snapshot (every 5-10 s, capped, only on when the dashboard flag is on) and a per-bot inspect JSON. | M |
| `/api/mapinfo` | One map: every bot with its status line, recent chat, mobs, spawns. | QA: map-level spot checks (crowding, stuck clusters, chat spam). | `!inmap`, `!botground`, `!botwatch` (in game). | JSON on demand, one map per click. | New hook, read-only. | S |
| `/api/botdebug` | Per bot: owner, party, destination, errand, grinding, transit, LOD, tick ms, real vs abstract kills, ammo. | Behaviour: this is our `!botwhy` (Bartle blend, bonds, goals, plan step, LifeLearning, memory) as JSON. | `BotWhyCommand`, `LifeInspection` (in game text only). | Put a JSON renderer behind `LifeInspection`/BotWhy and show it in the dashboard inspect panel. | New hook over existing state (read-only). | S-M |
| `/api/killcalib`, `/api/grindmodel` (`BotKillCalibration`) | Measured kills/h of watched bots vs the model, bucketed by job and level band. Model broken down into parts. | Behaviour/economy: validates `OffscreenCombat` (EXP, mesos, drops, the "most hunted" site counter, ticket 15 yield caps). QA: a drift alarm. | **None.** Offscreen rates are modelled only. | Port the idea: sample real kills while a bot is watched or promoted, compare with OffscreenCombat's rate, write a TSV and a dashboard table. | New hook (kill hook plus store). | M |
| `/api/lod` | Pin maps to full fidelity (real combat and physics); releases on restart. | QA: make auto-QA and BotWatch see real behaviour on chosen maps without a GM standing there. Nav: more real moves feed NavEdgeLearning. | ObserverTracker tiers, `LodCounts`/`LodMetrics` (`!env`). No pin. | GM command `!env lod pin <map>`, TEST only, audited. Never a GET. | New hook (control). | S |
| `/api/bot/pathlog` (`BotPathLogger`, 120-tick ring buffer) | Attach to one bot, then a dump of the last 120 nav ticks (region, edge, state) to a file. Zero cost when off. | **Nav learning:** auto-attach when NavStuckLog fires, so each stuck report carries the trace that led to it. QA: attach during t-* nav checks. | **None.** NavStuckLog records the stuck event only. RouteLog is chat routing, not nav. | Port: our BotMovementState hook, on by GM command or auto-on-stuck with a per-hour cap. | New hook. | S-M |
| `/api/navprobe` | "Can bot X reach (x,y) on its map?" Start and target regions, ground/rope/midair, the full edge path. | Nav: explains NavStuckReport hotspots. QA: probe every portal approach and NPC spot per map, as a nightly check alongside NavDeepBake. | `!move pathfinderaerial` test hook (`BotMoveCommand`) only. | GM command plus a dashboard form (read-only pathfind on the cached graph). | New hook (read-only). | S |
| `/mapgraph`, `/api/mapgraph`, `/api/pathfind` | One map's nav graph per movement profile (speed/jump/snow); pick a source and target region and preview the route with an honest reachable flag. | Nav: same as our Map tab plus route preview. | Map tab already has footholds, edges, night-bake, player-learned and learned-cost marks, stuck heat, a profile picker. **No pathfind** (0 hits in mapview.js). | Add pathfind in JS over the exported graph (A*), so no server hook is needed. | **Read-only over existing export.** | S-M |
| `/admin`, `/api/settings` | View every tunable (manager, combat, log), population on/off/multiplier/sweep, LLM on/off, perf log, **disconnect all**, **wipe**. | QA: one view of "which switches are on TEST" (a recurring question). | Switches are -D props; `!botcombat set`, `!env`. Notes pages list them by hand. | Read-only: write the effective switches into live.json (S). Writes: TEST only, through GM commands. **Never port disconnect-all or wipe.** | Read-only over existing config; writes need the command bridge. | S (view) |
| `/api/command` ("RTS": idle, fidget, move, move+attack, follow, dance, jump, cheer, by bot ids or map) | Drive bots from the browser. | QA: stage repros ("send 5 bots to this ledge"). | `!botmove`, `!botshowcase`, `!qa do`. | Only as a **command bridge**: the dashboard posts to an authenticated endpoint that runs the matching GM command as the staff member's account, so CommandsExecutor and GmAudit log it. TEST only. POST plus a CSRF token. | New hook (control). | M |
| `/api/spawnbot` | Bring an offline character back online for a repro, then moveto. | QA repro. | `!botshowcase`, `!qa` staging, `!roster`. | Covered. Skip. | — | — |
| `/api/perf` (+ `!botperflog` CSV) | Toggle the monitor, read hot phases; export a CSV. | QA / soak. | `BotPerformanceMonitor`, `BotPerfStats`, `!env perf`, dashboard Health. | Add only the CSV export (S). | Existing. | S |
| `/market`, `/api/market/*` | Candles, listings, bot wallets/beliefs. | — | Exchange site, `PublicMarketExport`, Basil (79). | **S** (ours). Never expose bot wallets. | — | — |
| `/api/economyreset` | Wipes the bot market. | — | — | **Reject.** | — | — |

**Safe adoption shape (proposal):**
1. **Read-only first, no new port on 0.0.0.0.** Everything static goes through the existing exporter files (DashboardExporter / DashboardMapExport) behind nginx basic auth (TEST :8091) or the site's staff pages (GM 3+ via SiteAuthApi).
2. Live views (bot dots, inspect, mapinfo, navprobe) come from one **127.0.0.1-only** JSON endpoint, off unless `-Dslums.dashboard.live=true`, shaped like SiteAuthApi (shared secret header, nginx or site proxy in front, staff check for GM 3+). GET is read-only. Rate-limit it and cache for 1-2 s, as their 750 ms cache does.
3. **Controls:** POST only, a CSRF token, a TEST-only flag (`-Dslums.dashboard.control`). Each action maps to an existing GM command run under the staff member's character id, so `gm_command_audit` records it. No bulk-destructive actions (wipe, disconnect all, economy reset).
4. Live keeps the read-only views at most; controls never go live (ticket 108 locked-key rule).

## 0b. Debug tools and commands

| Theirs | What | Ours | Gap | Effort |
|---|---|---|---|---|
| `autopilot debug` / `ap debug` / `why autopilot` (`BotAutopilotDebug`) | Full grind-map decision (every candidate and its score) dumped to a file | `!botwhy`, `LifeInspection`, `DecideShadow` (chat) | No dump of the map-choice candidates (TrainingMapChooser / LevelingPathChooser scores) | S |
| `grind profile` | Cost of the real grind decision under live load | `!env grindprofile` (DebugCommand) | Have it | — |
| `inv debug`, `@autosell`, `!inspectsell` | Keep/sell classification dump; preview the sell pipeline | `SaleRules`, `BotBagRoom` (no read-out) | A `!botwhy <bot> bag` read-out of what SaleRules would sell | S |
| `scroll debug` | Scroll decision dumped to a file | `!botscroll` (BotScrollCommand: "now" only) | A why-dump | S |
| `autoequip debug` | Explains the optimizer's picks | `BotGear`/`BotGearPlanner`, no read-out | Add to `!botwhy` | S |
| `!inspect <bot>` (bot inventory in the F8 UI) | Needs their custom client edit ("wisteria") | None | Our client is custom too, but this is client RE work, and the edit's licence/permission is unknown | M + client |
| `!botperfdebug`, `!botperflog` | Console spam, CSV | `!env perf`, dashboard Health | CSV only | S |
| `!botnav` / `!pos` overlay (`BotNavigationDebugOverlay`) | The nav region under the GM, with the GM's own movement profile | `!pos` (position and foothold only), `!botground` | Add region and edges at your feet to `!pos` | S |
| `@botstatus` | Private list of every bot on the map with its status | `!inmap`, `!roster status`, `!botwatch` | Close enough; a one-line status per bot would help | S |
| `BotOpsConsole` (Maple Messenger "mmc connect") + `BotConsoleTap` (live decision stream for one bot) | Subscribe to one bot's decisions live | `MapleMessengerConsole` (SoloMapling) exists; `SocialTrace`, `BotWatch`, `RouteLog` are batch/QA logs | **Live tap missing:** "follow bot X's decisions" in the Messenger or dashboard. Good for behaviour learning. | S-M |
| `BotPathLogger` | 120-tick nav trace | NavStuckLog (events only) | Missing (see 0) | S-M |
| `BotNavigationProbe` | Sweep of A* heuristic vs optimal (h=0) | None | Offline nav QA tool. Ours: NavDeepBake, NavPlayerReplay | S |
| `BotKillCalibration` | Measured vs modelled kills | None | Missing (see 0) | M |
| debug stats / crit / pot / buff / skill-buff debug (chat) | Combat internals | `!botcombat`, BotRealStats, `!qa mv-1`/attack capture | Have the equivalents | — |
| `docs/bot/kb/*` (62 KB notes) | Bug-class write-ups (double register, self-owned owner assumptions, perf GC storm, HPET) | `docs/perf/capacity-soak-2026-10.md` | Already read in the 10-25 pass | — |
| Ours with no match there | — | `!autoqa` (auto-QA + health sweeps), `!qa` scenario menu, `!socialtrace`, `!botwatch` + BotWatchJudge, NavStuckReport, NavEdgeLearning, NavObservedEdges/NavPlayerLearning/NavPlayerReplay, NavDeepBake, DecideShadow/Replay, RouteLog, `!roster`, `!botyield`, `!botwealth`, MoveTrace, MapEntryMeter, `@bug` context | **We are ahead on learning and QA.** They have no player-learned nav and no LLM judge. | — |

## 1. Gear optimizing

| Feature | nutnnut | Ours | Status | Effort |
|---|---|---|---|---|
| Auto-equip optimizer with stat chaining (Pareto DP, dexless builds) | `BotEquipManager` (146 KB dev) | `BotGear` + `BotGearPlanner` (hill climb after **cosmic_agents** AgentEquipmentOptimizer, credited in PROVENANCE) | **P/S**: ours is cut down for 3k bots and event-triggered, which suits our scale | — |
| Gear progression grind ("gear first, EXP tiebreak", `farm <item>`) | `BotGrindPlanner`, `BotGrindAdvisor` | TrainingMapChooser / LevelingPathChooser (EXP first); LifeGoals aspirations | **N** for item-targeted grinding | M |
| Share upgrades with owner and siblings (compare against the recipient's inventory), `any upgrades?`, `request?`, `trade upgrades` | `BotOfferManager`, `BotTradePacing` | Owned alts trade by hand (OwnedAlt*); `BotGifts` | **N** (planned NN-012) | M |
| Junk sorting / reserve rules / `sell trash` | `BotInventoryManager` (175 KB) | `SaleRules`, `BotBagRoom`, BotSellerCaps | **S/P**: ours exists for population bots; no owner-facing "trade trash" | S |
| Self-scrolling with EV planner (boom priced, fallback gear) and reproduction-cost valuer | `BotScrollManager`/`Planner`/`Valuer`/`ReactionManager` (dev 159 KB) | `BotScrolling`, `ScrollRules`, `ScrollWants`, `UpgradeSimulator` (naive cost/success) | **P**: ours scrolls; their valuer is better (the 10-24 audit flagged our naive model). Economy-adjacent | M |
| Scroll reactions (streak/rarity chatter) | `BotScrollReactionManager` | Bot chat stack / GachaAnnouncer | **S** (ours: LLM + lines) | — |
| Gachapon loop (NX from cards, best town by EV, walk to NPC) | `BotGachaponManager` | `BotGacha`, `GachaBot` (paced) | **S** (ours has pacing and the wealth ceiling) | — |
| Starter kit / weapon line at 1st job | `BotStarterKitManager` | `BeginnerEquip`, `QuickEquip`, `BotWeapons` | **S** | — |
| Godly drops idea | — | NN-010 | **A** (as idea) | — |

## 2. Crafting (Maker)

| Feature | nutnnut | Ours | Status | Effort |
|---|---|---|---|---|
| `maker plan` (which craftable equips would be upgrades, ranked by offence gain) | `BotMakerPlanner` (read-only) | None. Maker skill itself is ticket 112 (1.6.1, not started, needs a client) | **N**, blocked by 112 | S after 112 |
| Batch "make monster crystals" / "disassemble trash" through the player `MakerProcessor` path | `BotMakerManager` | None. Crystals-only-improve rule is in (NN-009 / EC-005) | **N**: was "economy faucet, owner must ask" (bots report). Fits ticket 127 batch Maker modes | M |

## 3. Quests

| Feature | nutnnut | Ours | Status | Effort |
|---|---|---|---|---|
| Quest index: unscripted, mob-only-complete quests with start and end NPCs from Quest.wz | `BotQuestIndex` | `tools/questcheck` (player quest audit), none for bots | **N** | S |
| Auto quests (autoStart+autoComplete) and mob-quest piggyback (detour to NPC, `Quest.start`/`complete`, legal path) | `BotQuestManager` | None (ExpeditionBots only reads quest status) | **N**: deferred for `queststatus` growth at 9k roster | M-L |
| Quest worth model (EXP/min value vs travel cost) | `BotQuestScorer` | — | **N** | S (with the above) |
| Temple of Time mainline driver with lane dispersal | `BotTempleProgressionManager` (dev) | None | **N**. Long chain; a model for 1.7.0 content quests | M |
| Zakum prequest bot run (supervised, room partition, key courier) | `BotZakumPqRun`, `BotZakumPrequestManager` (dev) | `!zakumpq` staging (NN-007); ticket 114 scaffold | **P** (planned, second-pass rank 7) | M |
| `quests`, `recommend quest` chat | `BotChatManager` | — | **N** | S with the above |

## 4. Trading

| Feature | nutnnut | Ours | Status | Effort |
|---|---|---|---|---|
| Trade by category (`trade pots/scrolls/use/etc/equips/100k/<item>/<slot>`), `drop`, `show me your hat` | `BotChatManager`, `BotInventoryManager` | Owned alts: normal Trade only; `BotDirectTrade` (merchants); `ClaimActions` "trade me" | **N** for companions (NN-012) | M |
| Pot/ammo/rock sharing among siblings and to the owner ("need pot") | `BotPotionManager`, `BotAmmoManager`, `BotRockManager` | `BotSupplies`/`BotResupply`/`BotAmmo` (self only); `BotBuffRequestSystem` | **N** (NN-012). Rocks: none | S-M |
| Trade pacing (human beats inside a trade window) | `BotTradePacing` | BotTradeSM / HumanTiming | **S/P**: check ours covers owned-alt trades | S |
| Trade item-loss fix | `e8b44ffc4` | `Trade.java` | **A** (NN-002) | — |
| Shout market (`S>`/`B>` grammar, price parse, claim bus) | `BotMarketGrammar`, `BotMarketShoutBus` (dev) | MegaBuy* (ticket 86) | **A** (adapted, 257ee6b48) | — |
| Market beliefs/consensus/candles, bot FM stalls | `BotMarket*`, `BotFreeMarketManager` (dev) | HaggleBrain, PriceBook, Exchange, FMBot, MarketTraders | **R/S** (NN-013 ideas only) | — |
| Ownership transfer (`transfer`, `!takebotowner`, `dismiss`, `recruit`) | `BotOwnershipService` | OwnedAltService (same account, same world) | **R** for cross-account (second pass 1.1) | — |

## 5. Jobs / advancement / build

| Feature | nutnnut | Ours | Status | Effort |
|---|---|---|---|---|
| AP builds incl. `dexless`/`lukless`/`strless`/`pure`/"25 dex", owner prompt | `BotBuildManager` | `BotBuild` (one primary/secondary target per class) | **P**. Variant builds would add persona variety (a "dexless" regular) | S-M |
| SP builds per branch from player guides (claw vs dagger, etc.) | `build/*Builds.java` | `BotSkillPlan` (MapleRoot skill set, Cygnus, Aran) | **S** (ours covers more classes and MapleRoot's set) | — |
| Job advance as an errand (walk to the instructor, ferry/fare-blocked fallback, celebration) | `BotAutopilotManager` job errand | `BotProgression` sets the job directly (no NPC visit) | **N**. Visible-likeness win for watched bots | M |
| 4th-job skill/mastery books: want, keep, use | `BotSkillBookManager` (dev) | None (book master levels not modelled, `BotSkillPlan` note) | **N** | M (also economy: books get held back) |
| `respec sp/ap`, `change build` | BotBuildManager | — | **N**; not wanted for population bots | — |

## 6. Population (`@botpop`)

| Feature | nutnnut | Ours | Status |
|---|---|---|---|
| Scheduler on an hourly curve, per-bot active days and liked hours | `BotScheduler`, `BotScheduleMath`, `ManagedBotService` | `BotPopulationDirector`, `BotPopulationCurve`, `BotHabits`, `BotRoster` (~9k roster, 3k peak) | **R/S** (NN-021: ours ahead) |
| Autogen and turnover (career length) | `BotGenerator`, `BotNameGenerator` | `BotGeneration`, `BotRosterLifecycle`, `NameGuard` | **S** |
| Crews (co-spawn, party, share) | `@botpop crew` | `BotCrews`, `SocialCrew` | **S** (no supply sharing) |
| Login-break seeding for equilibrium (`897e4c139`) | BotScheduler | Director logout spread (76ea6cd7) | **S/P**: worth a look only if the curve oscillates |
| LOD (unobserved maps abstract) | `living-server-design.md` | ObserverTracker, OffscreenCombat | **S**; **their kill calibration is the missing piece** (see 0) |

## 7. Everything else in the README

| Feature | nutnnut | Ours | Status | Effort |
|---|---|---|---|---|
| Follow / stop / grind / patrol / sentry / move here, target by name/prefix/slot | `BotChatManager`, `BotCommandParser` | Ticket 14 `BotCommandParser` (FOLLOW, STAY, GRIND only) | **P** (NN-012) | S-M |
| Formations (split, spread, stack, random, left, right, tight, loose, snap) | `8fd725404` | None | **N** (NN-012, alts only) | S |
| Autopilot / party autopilot (`PartyAutopilotState`) | `BotAutopilotManager` | Population director + LifePlanner; owned alts have no autopilot (owner rule) | **S / R** for alts | — |
| Support: skill buffs and heals on party, buff-pot modes | `BotBuffManager` | `BotBuffRequestSystem`, `BotBuffDriver` | **P** | S-M |
| Fame on request | chat | `BotFame` (fame values) | **N** (minor) | S |
| LLM chat (Ollama, addressed only) | `llm/*` | ConvoSense / ChatTriage / LLM + ContentGuard + judge | **R/S** (NN-021) | — |
| KPQ stage 1/5 automation | `pq/BotKpq*`, `BotScript*` | OPQOrchestrator; ticket 114 scaffold (off) | **P** (second-pass rank 6; don't copy the NPC bypasses) | M |
| Bots as real login-able characters, alts as bots | `@botme`, `@registerbot`, `@spawnbot` | Owned alts `@botme <alt>` (51b88ee7b, cc5058459) | **A** (adapted) | — |
| Buddy-menu invite spawns a bot | `59f9bc091` | Not ported | **N** (nice extra for alts) | S |
| Grind doctrine (camp/patrol/stack/roam) | `BotGrindDoctrine`, `BotGrindSpots` | `GrindStyle`, Camp/Patrol/Stack/RoamStrategy | **S**: theirs is ported *from* SoloMapling v0.3, which is ours | — |
| Chairs when idle, dragon flight, airshow, ferry | `BotChairManager`, `BotDragonFlightManager`, `BotAirshowManager`, `BotFerryManager` | `StarterChairs`/BotFishingChairs, GCTravel (ferries, rides) | **S/P** | — |
| Nav/movement fixes (zMass v70 etc.) | nav files | Ledger NN-011 (planned) | **P** | M |
| Magic Guard split, Shadow Partner lines, staff melee | `930ca5a01`, `eddb5bc65`, `d5ee9413e` | BotCombatFormula (credited eddb5bc65) | **A/P**: check Magic Guard | S |

## 8. Provenance / licence notes

- Same licence (AGPL-3.0). Rule: credit `nutnnut/Cosmic <sha>` in commits, patch notes **and** docs/PROVENANCE.md.
- **Gap:** PROVENANCE.md records only nav v68, combat formula, gear optimizer (cosmic_agents) and the GC move system. The
  1.5.2/1.6.x ports (ticket 80 stability fixes, the misc ports in 7a7a03de7, owned alts 51b88ee7b/cc5058459, megaphone
  buy requests 257ee6b48) carry credits in commit messages and the ledger, but **not in PROVENANCE.md**, which says
  "any later port ... is recorded here". Add a section.
- The bot inventory UI client edit ("wisteria") is not in their repo and has no stated licence. Don't copy it without
  permission. Write our own client UI if we want `!inspect`.
- Seikoxi/BotlingMaple (a combined fork) is only a pointer. Licence not checked.

## 9. Top picks for 1.6.2 / 1.7.0 (all behind switches, credited `nutnnut/Cosmic <sha>`)

| # | Pick | Why | Risks (3k bots, conflicts) | Test |
|---|---|---|---|---|
| 1 | Nav trace recorder (`BotPathLogger` idea): per-bot 120-tick ring buffer, auto-attached on a NavStuckLog event, capped per hour; GM `!botnav trace <bot>` | Every stuck report gets its cause, which speeds up NavEdgeLearning fixes | Off by default, null field when off, so no per-tick cost. Cap auto-attach (e.g. 20/h). Offscreen bots have no ticks, so watched bots only | Unit test the buffer; on TEST, force a stuck on a known bad map and check the dump |
| 2 | Kill calibration (`BotKillCalibration` idea) | OffscreenCombat drives EXP, mesos, drops, the site's "most hunted" and the yield caps from a model nobody has measured | Kill hook on the watched path only (LongAdder buckets); a TSV flush on the scheduled executor, never a tick thread. Must not auto-correct the model at first: report only | Unit test the bucket maths; a TEST soak for 24 h, then compare buckets with OffscreenCombat; a dashboard table |
| 3 | Read-only dashboard additions: world graph and one-way-trap check, pathfind in Map tab, effective switches in live.json | No new attack surface (static files). Answers "which switches are on TEST". The trap check catches travel bugs | Exporter runs on its own daemon thread. World graph is exported once per data change | Auto-QA check "no trap maps"; open the dashboard on TEST :8091 |
| 4 | Live inspect panel (BotWhy/LifeInspection as JSON; bot dots per map) on a 127.0.0.1 endpoint behind staff auth | Behaviour learning: goals, plan step, bonds and memory for any bot without being in game | One bot per request and a map's dots at most every 5 s, cached; a secret header; off by default; read-only | Auth tests (no secret: 401; GM < 3: refused); load test 10 viewers |
| 5 | GM `!navprobe <bot> x y` and `!env lod pin <map>` (TEST, audited) | Explains unreachable spots; lets auto-QA watch real behaviour on chosen maps | A LOD pin raises the CPU cost of that map. Cap pins at 5, release them on restart | `!qa` scenario; check GmAudit rows |
| 6 | Bot questing slice: quest index (mob-only, unscripted) + piggyback for **watched** bots, offscreen modelled as a completion record | Real-player likeness (bots turning in quests at NPCs); feeds 1.7.0 quest pass | `queststatus` rows × 9k roster (keep only completed rows, NOT_STARTED skip already in); conflicts with LifePlanner/goals (has to be a goal, not a side errand); quest EXP and mesos are a faucet, so they must count against the yield caps | Index unit test against Quest.wz; TEST: a watched bot completes a Henesys mob quest at the NPC; row-count check after 24 h |
| 7 | Job advance errand: a watched bot walks to its instructor (BotTransit route) before `BotProgression` advances it; offscreen stays instant | Looks like a player, and the town gets busier at 10/30/70 | Ferry or fare deadlocks (they hit two). Needs a timeout fallback to the instant path. LifePlanner priority | TEST: level a watched bot to 10 and 30 near Henesys and Ellinia; check the fallback when the ferry is blocked |
| 8 | Companion command set and sharing for owned alts (NN-012): formations, "need pot", "any upgrades?" via `BotOfferManager` compare onto BotGearPlanner, trade by category | Owner's companion promise; low scale (at most 5 per player) | Dupes: vanilla Trade only plus our trade log. No bot-speak giveaways (second pass 1.3) | Trade checksum test; t-* alt checks; real client |

Also worth a look, smaller: AP build variants (dexless etc.) for persona variety; 4th-job mastery books (economy, so owner say);
scroll EV valuer (economy, so owner say); Maker plan after ticket 112.
