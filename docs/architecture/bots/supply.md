# Supply, potions and restock

## Purpose

Keeps a training bot alive and shooting: drink real potions out of its own USE tab, fire real stars and arrows out of
its own ammo stacks, notice when HP, MP or ammo run out, walk to a town, buy the refill at a real NPC shop with real
mesos, walk back to the grind. Two layers share the judgement: `BotSupplies` decides *whether* a bot must leave
(`BotSupplies.Shortage`), and `BotShopping` decides *what to buy and where*. No purchase is simulated - it goes through
the server's own `Shop` pricing and the bot's item/meso ledger, so mesos leave the economy exactly as they would for a
player.

## Entry points

| Entry | Kind | What it starts | file:line |
|---|---|---|---|
| `TrainingBot` update loop | tick | drains `restockCall`, runs the town phases, runs the grind supply gate | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:617-625, :644, :646-665 |
| `supplyRetreat` / `bagRetreat` | grind-tick gate | leave-grind decision on shortage or bag pressure | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2171, :2174, :2493-2513, :2529-2546 |
| `doInTown` | phase | rest to full, plan the resupply, walk to the shop NPC, buy, dwell | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1519-1596 |
| `BotShopping.install()` | boot | installs the resupply hook once per server start | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:88-90, called at src/main/java/soloMapling/server/MapleRootSolo.java:45 |
| `BotResupply.request` | seam | the only path from "needs" to "mesos spent" | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotResupply.java:50-61 |
| `RestockTripExecutor` | life executor | a requested RESTOCK activity becomes a town trip | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/RestockTripExecutor.java:22-35 |
| `ClaimActions.GameSide.restock` | chat claim | "brb pots" turns into `requestRestockTrip()` | src/main/java/soloMapling/ArtificialPlayer/BotConvo/ClaimActions.java:253-260; src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:798-806 |
| `!botyield` | GM command | prints `BotShopping.report()` counters | src/main/java/client/command/commands/gm4/BotYieldCommand.java:32 |
| `!reloadshops` | GM command | drops the shop catalogue cache | src/main/java/client/command/commands/gm3/ReloadShopsCommand.java:40 |
| auto-QA `t-cb-3`, `t-bl-1..3` | QA | potion/restock/death lines, one restock trip per request | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:688, :865, :872, :882 |
| `!qa` `OneRestockTrip` | QA scenario | need -> decision -> start, repeat refused | src/main/java/soloMapling/qa/BotLifeScenarios.java:178-249 |

## How it works

The end-to-end path, mp/ammo low to back at the grind:

| # | Phase | Action | file:line |
|---|---|---|---|
| 1 | Burn | While grinding, `BotVitals.topUp` drinks potions on each hit; `OffscreenSupplies.accrue` burns ammo per swing and potions per offscreen hit | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotVitals.java:264-289; src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/OffscreenSupplies.java:94-150 |
| 2 | Need | `BotSupplies.shortage` answers `HP` (under 25% max HP and zero HP pots), `MP` (skill mpCost > 0, mp below it, zero MP pots), `AMMO` (ranged route, weapon needs ammo, no usable stack) | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:45-70 |
| 3 | Need detail | `BotSupplies.needs` builds `SupplyNeeds`: shortage, ammo item + held count, rechargeable stacks below slot max, HP/MP potion counts, `usesMp` | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:114-135; src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/SupplyNeeds.java:20-34 |
| 4 | Decision (grind) | `supplyRetreat`: `mayResupply` refusal window, then `shortage`, then `runningLow` (per-bot `restockBelow` mark): `leaveGrind`, `enterPhase(GO_TOWN)`, `SayDo.event(Ev.RESUPPLY)`, `noteRetreat` | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2493-2513, :2550-2568 |
| 5 | Decision (brain) | `LifeRuntime` assembles `Need.SUPPLIES` read-only; `LifePolicy` weights `RESTOCK` at SUPPLIES 3.0; the queue starts `RestockTripExecutor` -> `startLifeRestockTrip` | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:447-456; src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifePolicy.java:198; src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/RestockTripExecutor.java:26-35; src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:864-872 |
| 6 | Trip | Town first tick: `BotSupplies.arriveInTown` (rest + fresh needs), junk sale, `buildShopPlan`, `planResupply(needs)` picks a `ShopCatalog.Site` | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1537-1550, :1648-1670 |
| 7 | Walk | Same map: `startResupplyWalk` stands beside the NPC (`25 px` +/- 40 scatter), `resupplyArrived` on arrival, deadline `RESUPPLY_WALK_MAX_MS`; other map: the map id goes to the front of `shopQueue` | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1674-1684, :1666-1668 |
| 8 | Buy | `pollResupply` (arrival or deadline) -> `finishResupply` -> `BotSupplies.restock` -> `BotResupply.request` -> `BotShopping.resupply` -> `shop` (essentials then full kit) -> `buy`/`recharge` | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1686-1708; src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:173-191; .../BotSupplySystem/BotResupply.java:50-61; .../BotSupplySystem/BotShopping.java:220-321, :493-545 |
| 9 | Remember | `noteRestock`: a `NOTHING` restock refuses the next trip for `RESTOCK_REFUSAL_MS`; a still-empty ammo state adds the bot to `meleeWithoutAmmo` so it grinds in melee instead of shuttling | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:180-190, :83-103 |
| 10 | Return | Town deadline -> `shopQueue` -> `SHOP_TRAVEL`/`SHOP_DWELL`/`SHOP_RETURN`, else `DECIDE` -> `GO_TRAIN`; `doDecide` always calls `finishResupply` first | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1587-1595, :646-665, :1898 |

Buy order inside `BotShopping.shop` (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:290-321):
ammo essential stack, `ceil(mpGoal/3)`, `ceil(hpGoal/3)`; then the full kit to `ammoGoal`, `mpGoal`, `hpGoal`; then
`BotAmmo.syncStats`. Recharging is separate (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:436-480): own stacks whose watk is at least the shop's
best are topped up to `slotMax`, cheapest first, then the best ammo on sale is bought to the goal.

## Data

| State | Type | Where | Persisted |
|---|---|---|---|
| `SupplyNeeds` | record | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/SupplyNeeds.java:20 | no (rebuilt per call) |
| `Shortage` | enum NONE/HP/MP/AMMO | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:39 | no |
| `meleeWithoutAmmo` | `Set<Integer>` bot ids | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:42 | no |
| `refusedUntilMs` | `Map<Integer,Long>` | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:84 | no |
| Offscreen supply carry | per-bot `Bill` remainder | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/OffscreenSupplies.java:48 | no |
| Shop catalogue | `byMap`/`byTown` `Map<Integer,List<Site>>` | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/ShopCatalog.java:146-147 | no (cleared by `!reloadshops`) |
| Heading (shop walked to) | `Map<Integer,Site>` | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:194 | no |
| Shop counters (visits, mesos, items, recharges) | `AtomicLong` x4 | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:67-70 | no (since boot) |
| Starter stash granted | `AtomicLong` | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/StarterStash.java:54 | no |
| Starter stash marker | `botstore.StockedAt` | src/main/java/soloMapling/ArtificialPlayer/BotStore.java:159, :535-545 | yes, database/migrations/007-bot-starter-stash.sql |
| Bot bag capacity | int per bot | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotBagRoom.java:76-90 | no (re-widened at spawn, src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java:223) |
| `hpPotsAtGrindStart` | int field | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2491 | no |

## Switches

| Switch | Default | Effect | Read at | Live 1.6.4 |
|---|---|---|---|---|
| `-Dsolomapling.botJunkSale` | `true` (only `false` disables) | town junk sale on/off | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotBagRoom.java:122-124 | not in `UNIT_EDITS` -> **on** |
| `-Dsolomapling.botBagSlots` | `96` (`DEFAULT_BOT_SLOTS` = `V83_MAX_SLOTS`) | bot tab capacity, clamped 24..96 | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotBagRoom.java:76-77 | not set -> **96** |
| `-Dslums.junk.sort` | `false` (`Boolean.getBoolean`) | extra self-reserve/shelf keep ahead of the junk sale | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/JunkSort.java:41-42 | `-Dslums.junk.sort=true` (docs/release/1.6.4.conf:271) -> **on** |
| `-Dslums.bots.magicguard` | `false` | Magic Guard splits damage between HP and MP, changing potion burn | src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:37, read at src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotVitals.java:132-141 | `true` (docs/release/1.6.4.conf:271) -> **on** |
| `COMPANION_SUPPLIES_ENABLED` (config.yaml) | `false` in code | owned-alt asks siblings for pots/ammo/rocks (`SupplyShare`) | src/main/java/config/ServerConfig.java:117; src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSwitches.java:95 | `true` (docs/release/1.6.4.conf:212) -> **on** |
| `-Dslums.botlife.enabled` | on (only `false` disables) | whether the brain may choose `RESTOCK` at all | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:95-98 | not set -> **on** |
| `-Dslums.botlife.execute` | on (only `false` disables) | whether the choice is carried out (off = shadow) | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:101-103 | not set -> **on** |
| `-Dslums.bots.merchantpoolstock` | `false` | merchant bots stock from the resale pool instead of minting an item per trade (ticket 181 market#F-07) | src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:45 | not in `UNIT_EDITS`; docs/release/1.6.4.conf:33-38 keeps it off unless appended -> **off** |

TEST: unknown (its `start-test.sh` was not readable for this review). No switch in this subsystem covers the
thresholds below - they are compile-time constants.

Thresholds:

| Threshold | Value | Meaning | file:line |
|---|---|---|---|
| `HP_RETREAT_AT` | 0.25 | HP shortage line (with zero HP pots) | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotVitals.java:39 |
| `HP_POT_AT` / `MP_POT_AT` | 0.50 / 0.30 | drink lines (per-bot `hpPotAt` may override the HP one) | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotVitals.java:37-38, :153-162 |
| `MAX_DRINKS` | 6 | potions one top-up may drink | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotVitals.java:40 |
| `REGEN_TICK_MS`, base regen | 10 000 ms; HP 10 / MP 3 | on-screen recovery | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotVitals.java:43-45 |
| `LOW_AMMO` / `LOW_POTIONS` | 200 / 20 | "wants anything" floor in `SupplyNeeds` | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/SupplyNeeds.java:33-34 |
| `hpGoal` | `clamp(40+3*level, 50, 400)` | HP potion kit size | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:95 |
| `mpGoal` | mage `clamp(60+4*level,80,500)`; usesMp `clamp(20+2*level,30,250)`; else 0 | MP kit | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:100 |
| `ammoGoal` | `min(4000, 1000+40*level)` | ammo kit | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:113 |
| `budget` | `meso - max(200*level, ceil(0.25*meso))` | spendable mesos | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:60-61, :118-121 |
| `POTION_HEAL_SHARE` / `POTION_VALUE_SLACK` | 0.25 / 1.5 | potion pick target and price slack | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:62-63, :332-370 |
| `ESSENTIAL_POTION_SHARE` | 1/3 | first-pass potion buy | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:64, :305-308 |
| `RESTOCK_REFUSAL_MS` | 10 min | no trip after a `NOTHING` restock | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:83 |
| restock cadence | 300 000 ms | `LifeRuntime.restockReady` minimum gap | src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:56 |
| `RESUPPLY_WALK_MAX_MS` / `RESUPPLY_STAND_OFF_PX` | 25 000 ms / 25 px (+40 scatter) | walk to the shop NPC, buy on arrival or after | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:133-134 |
| `SHOP_DWELL_MIN/MAX_MS` | 30 000 / 90 000 ms | dwell in a shop | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:131-132 |
| `SHOP_VISIT_CHANCE` | 0.4 | chance a town visit includes a shop trip | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:130 |
| `TOWN_DEPTH` | 2 | portals from the town map a shop may sit behind | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/ShopCatalog.java:43 |
| `BAG_RETREAT_COOLDOWN_MS` | 15 min | bag-full retreat rate limit | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2521 |
| `NEARLY_FULL` / `SELL_DOWN_TO` | 0.9 / 0.5 | bag pressure, how far a town sale empties | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotBagRoom.java:53-55 |
| `restockBelow` (per bot) | 0 for 35% of bots, else `3 + rnd(18)` | personal HP-potion line used by `runningLow` | src/main/java/soloMapling/ArtificialPlayer/BotCombatSystem/BotHabits.java:47 |
| `HITS_PER_KILL` / `MAX_HITS_PER_MINUTE` | 0.1 / 24 | offscreen contact damage rate | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/OffscreenSupplies.java:43-45 |
| `DMG_FACTOR` / spread / miss | 0.5 / 0.20 / 0.10 (thief 0.40) | mob touch damage model | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/MobContactDamage.java:16-19 |
| `SupplyShare` keep/give/low | pots 50/100/15, ammo 500/1000/150, rocks 20/30/5 | sibling hand-over | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/SupplyShare.java:34-44 |
| `AMMO_STACKS` | 2 | starter stash ammo stacks | src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/StarterStash.java:49 |

## Invariants

- A bot never gets items it did not pay for: `buy` checks `canHold`, then `spendMeso`, then `grantItem`, refunding on a
  failed grant (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:529-545); `recharge`
  refunds the same way (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:462-480).
- A purchase costs the counter's price, never a bot price: rechargeables use `SupplyData.get().price(itemId, qty)`,
  everything else the listing price (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:493-524), matching
  src/main/java/server/ItemInformationProvider.java:498-511.
- A rechargeable is bought as one whole `slotMax` stack and priced once for the stack
  (src/main/java/server/Shop.java:108-117; src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:493-524).
- Buying is map-local: `siteFor(bot)` only returns a heading when the bot stands on that site's map
  (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:208-212).
- A town stop never leaves with its restock undone: `doDecide` calls `finishResupply` first
  (src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1898) and `finishResupply` is idempotent
  (`if (resupplySite == null) return`, :1699-1708, synchronized because the sauna shop buys from a movement callback).
- One refusal, ten minutes: `noteRestock(NOTHING)` arms `refusedUntilMs`, `mayResupply` gates both the grind retreat and
  the life trip (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:87-103, read at
  src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2494).
- No ammo in town means melee, not another trip: `meleeWithoutAmmo` forces `shortage` to `NONE` and
  `shouldRetryAmmoTrip` to false until ammo appears (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:62-69, :183-190).
- The life brain reads supply state read-only: it must not call `shortage()`, "which changes the ammo-fallback state"
  (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:447).
- A RESTOCK request cannot start from the wrong thread or a busy bot: `isStartingHere`, `freeForLifeActivity`,
  `!BotCommitments.inFlight`, `!BotConversation.hasAnyChat`, `mayLogOut`
  (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/RestockTripExecutor.java:26-35;
  src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:834-841).
- Shop reads are serialized against the server's shop factory, which caches in plain HashMaps
  (`synchronized (ShopFactory.class)`, src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/ShopCatalog.java:84-142).
- The bag can always sell down: `JunkSort` bounds its absolute keeps at `MAX_ABSOLUTE`
  (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/JunkSort.java:33-49).

## Origin

| File | Origin | Evidence |
|---|---|---|
| `BotTypes/TrainingBot.java` | modified (custom server; the town/restock phases are ours) | oldest commit `03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline` |
| `BotVitals`, `OffscreenSupplies`, `SupplyNeeds`, `MobContactDamage`, `SwingSupply`, `SupplyData`, `BotAmmo` | custom | oldest `bb265a61e 2026-10-19 Make bots drink real potions and fire real ammo` |
| `BotShopping`, `ShopCatalog`, `StarterStash`, `BotResupply`, `BotSupplies` | custom | oldest `9aa10cff7 2026-10-19 Buy potions and ammo at real NPC shops, and give bots a one-time starter stash` |
| `RestockTripExecutor` | custom | oldest `97c74c095 2026-10-24 Require accepted bot work before exposing an action fact` |
| `BotBagRoom` | custom | oldest `921f4a852 2026-10-25 Let bots with a full bag sell junk in town, and give bots 96-slot tabs` |
| `JunkSort` | modified upstream derivation | oldest `3c91e2da7 2026-10-30 Junk sorting and reserve rules ... (ticket 162 part 4)`; header cites nutnnut/Cosmic AGPL-3.0 ports (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/JunkSort.java:22-34) |
| `SupplyShare` | custom | oldest `59e3eaad1 2026-10-30 Give alts and party bot friends the companion orders, trades, upgrades and supply sharing` |

Evidence from `git log --follow --format='%h %ad %s' --date=short -- <path> | tail -3`. HEAD for this review:
`c1d6650dc 2026-11-05`. Everything under `src/main/java/soloMapling/` is custom to this server; the only upstream line
is the JunkSort derivation above.

## Tests and QA

| Test | Covers | file:line |
|---|---|---|
| `BotSuppliesTest` (17 cases) | hit->HP->drink, death floor, elixir choice, amp mp cost, unpayable cast -> plain attack, regen + town rest, ammo handler rules, swing consumes a stack, zero-quantity star slot, melee fallback, Shadow Partner/Soul Arrow/Shadow Stars, offscreen ammo burn, offscreen touch + drinking, offscreen retreat at a quarter HP, retreat to town then the hook restocks, default hook buys nothing | src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSuppliesTest.java:199-446 |
| `BotShoppingTest` (14 cases) | goals follow level, best ammo, listed price + ledger, reserve + essentials first, whole star stack + own recharge, arrows by the unit, cannot-afford -> melee, full tab buys nothing, town BFS depth 2, buy only on the standing map, 10-minute refusal, bought kit allows the next trip, starter stash contents, stash granted once | src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShoppingTest.java:207-464 |
| `BotShopPriceParityTest` | bot pays the player's counter price for a star stack; a budget below the stack price buys none | src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopPriceParityTest.java:144, :158 |
| `JunkSortTest` | off by default, undominated pieces kept, other class never reserved, worn piece is a rival, 3 reserves per track, shelf bounded | src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/JunkSortTest.java:23-69 |
| `SupplyShareTest` | donor keeps its share, handover from the fullest stack, low counts only what it uses, need words | src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/SupplyShareTest.java:53-84 |
| `BotMagicGuardTest`, `BotMortalityTest` | magic guard split, death rules | src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotMagicGuardTest.java, src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotMortalityTest.java |
| `RestockTripExecutorTest` | uses only the existing trip hook, no action fact on decline, QA and execute both required, chat/trade/party holds refuse | src/test/java/soloMapling/ArtificialPlayer/BotLifeSystem/RestockTripExecutorTest.java:18-50 |
| `BotBagRoomTest`, `TrainingBotBagRetreatTest`, `TrainingBotErrandTest`, `BotStoreStashTest` | bag fill/sale, retreat rate limit, `restockPossible`/`homeTown`/`dropUnanswerableSupplies`, stash marker | src/test/java/soloMapling/ArtificialPlayer/BotBagRoomTest.java; src/test/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBotBagRetreatTest.java:16-29; src/test/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBotErrandTest.java:21-100; src/test/java/soloMapling/ArtificialPlayer/BotStoreStashTest.java |

Auto-QA (src/main/java/soloMapling/autoqa/AutoQaCatalog.java):

| Check | What it asserts |
|---|---|
| `t-cb-3` (:688) | staged grinders: `HABITS POT`, `HABITS RESTOCK`, `HABITS DEATH`, `HABITS RESPAWN` lines |
| `t-bl-1` (:865) | `LIFE SHADOW case=empty-potions selected=RESTOCK`; a rejected executor exposes no action fact |
| `t-bl-2` (:872) | one request starts exactly one restock trip; a replay is DUPLICATE or STALE |
| `t-bl-3` (:882) | a real supply need (HP pots drained) leads to `LIFE DECISION ... selected=RESTOCK` then `LIFE START activity=RESTOCK` |

`!qa` scenarios (src/main/java/soloMapling/qa/BotLifeScenarios.java): `OneRestockTrip(false)`/`(true)` (:178-249) drive
the flow by hand - `triggerLifeNeed` drains HP pots (src/main/java/soloMapling/qa/GameQaWorld.java:1506-1516) - and
`GoldenPolicy` (:251) pins the `empty-potions` case at `Activity.RESTOCK`.

Not covered: no test walks to a shop and buys on a live map (the tests call `BotShopping.shop` directly); no test for
`OffscreenSupplies.payAndPot`'s stepwise drinking, for `MobContactDamage` distributions, for `ShopCatalog.SERVER` reading
real map objects, for the `heading` entry's one-shot consume under a map change, or for `creditSale`'s partial-payout
path.

## Known issues

- b289 (S1, live): throwing-star sell-back minted mesos - a 9,999 Subi stack bought for 500 sold back for 10,249. Fixed
  on the buy side by charging the whole-stack `getPrice` (src/main/java/server/Shop.java:108-117,
  src/main/java/server/ItemInformationProvider.java:498-511); `src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopPriceParityTest.java:144` is the guard.
- b284 (S3): auto-QA at 10 lanes gives false failures (8 of 22). `t-cb-3`/`t-bl-1..3` are in the affected run.
- b276 (S4): a bot's map-scoped PLAN/ROUTE lines never reach the GM QA feed, which is where
  `LIFE DECISION ... selected=RESTOCK` is read during triage.
- b257 (S4): FM bot shops feel sparse and class gear is uneven - the shop side of the same economy, not the restock path.
- b285 (S3): ticket 178 alt upgrade trade refused - neighbouring `CompanionSwitches`/`OwnedAltCompanion` code, not
  `SupplyShare` itself.
- b287 (S4): t-xc-5 PANIC scroll trader never lists a Chaos/White Scroll - same catalogue, bot shop sanity.
- NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:45 mutates
  `meleeWithoutAmmo` inside `shortage`, a query. The read-only duplicate in
  src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:447-456 exists only because of that, so the
  ammo-fallback judgement has two implementations that can drift.
- NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/SupplyNeeds.java:34 `LOW_POTIONS = 20` is a
  third potion constant next to `BotVitals` and `BotHabits`; nothing ties the "wants anything" floor to
  `BotShopping.hpGoal` (50-400), so a bot can want a shop it will not meaningfully restock.
- NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:208-212 removes the
  `heading` entry before checking the map, so a restock reached while the bot stands elsewhere (sauna callback,
  `doDecide`'s `finishResupply`) spends the entry, buys nothing, and `noteRestock(NOTHING)` arms the 10-minute refusal
  for a trip that was only on the wrong map.
- NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1592 sends a Sleepywood bot to the
  sauna on `SHOP_VISIT_CHANCE` even with no shortage, so a town stop's dwell budget can go to a sauna instead of a
  restock.
- NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:547 reports "since boot"
  counters with no reset except `resetForTest` (:553), so `!botyield` mixes QA traffic into numbers a GM reads on live.

## State

**working**, with economy caveats. The mp/ammo -> need -> trip -> buy -> return path is wired end to end
(src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2493-2513 -> :1537-1550 -> :1674-1708 ->
src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:173-191 ->
.../BotSupplySystem/BotShopping.java:220-321), priced through the server's own `Shop`
(src/main/java/server/Shop.java:108-140), covered by 17 + 14 unit cases and gated by three auto-QA checks plus two `!qa`
scenarios. Partial pieces: the brain route only *requests* a trip and does not confirm the purchase
(src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/RestockTripExecutor.java:33-34;
src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeObservation.java:66 "purchase not guaranteed"); MP and ammo
pressure are declared unknown to the preview (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeInspection.java:59-68);
the restock refusal and melee fallback live in memory only, so a bot restart forgets them.

## Open questions

- Should `SupplyNeeds.LOW_AMMO`/`LOW_POTIONS` (src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/SupplyNeeds.java:33-34)
  derive from `BotShopping.ammoGoal`/`hpGoal` and the per-bot `restockBelow` instead of being fixed floors?
- Is the 10-minute refusal (`BotSupplies.RESTOCK_REFUSAL_MS`) the right answer to a shop that sells nothing the bot
  needs, or should the shop be marked unavailable in `ShopCatalog` until `!reloadshops`?
- docs/release/1.6.4.conf:271 runs `-Dslums.junk.sort=true` while src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/JunkSort.java:22
  calls it "Off unless set": is it a rollout switch that should stay off by default, or is it now the intended setting?
- Does the Sleepywood sauna trip (src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1592) belong in
  the restock budget at all, or should `saunaResupply` win over the random visit?
- The brain's `< 20 potions` pressure (src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:447-456)
  and `restockBelow` (src/main/java/soloMapling/ArtificialPlayer/BotCombatSystem/BotHabits.java:47) are independent
  lines; which one should a `RESTOCK` decision trust when they disagree?
