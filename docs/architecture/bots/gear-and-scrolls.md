# Gear, scrolling and Maker

## Purpose

Three bot systems that spend a bot's body and its mesos on gear, all event- or town-driven and never per-tick:

| System | Job | Core class |
|---|---|---|
| Gear | decide which equips a bot wears from what it owns, and move them | `src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGear.java:39-52` |
| Scrolling | buy scrolls, roll them on real equips with the server's own roll, then wear/list/toss the result | `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:37-58` |
| Maker | turn leftover ETC into crystals and disassemble junk (never crafts equips); a read-only craft plan for a player | `src/main/java/soloMapling/ArtificialPlayer/BotMakerSystem/BotMaker.java:9-30`, `src/main/java/soloMapling/ArtificialPlayer/BotMakerSystem/MakerPlan.java:9-22` |

A fourth, unrelated path lives in the same file family: `ScrollingBot` (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/ScrollingBot.java`) is the legacy FM *sales* bot that *pretends* to scroll — it fabricates roll outcomes and never calls the server roll (`ScrollingBot.java:113-133`). The gear-first map choice (`GearFirstAdvisor`/`GearFirstPlanner`) feeds the same `GearScoring` yardstick the gear system publishes.

## Entry points

| Entry | Kind | File:line |
|---|---|---|
| `BotGear.afterLoot(bot,item)` — equip looted (on screen, or offscreen accrual) | event hook | called `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2850`, `src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommands.java:327` |
| `BotGear.afterProgress(bot)` — level/job change | event hook | `src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotProgression.java:164,177,191` |
| `BotGear.afterRebirth(bot)` — unequip what a level-1 reborn cannot wear, re-dress | event hook | `src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotRebirth.java:457`; body `BotGear.java:112-170` |
| `BotGear.wear(takeOff/wearing)` — named-equip moves for mechanics (Guild Quest earring 1032033) | public API | `BotGear.java:190,202,866` |
| `BotScrolling.townTick(bot)` — the scroll loop while the bot stands in town | town step | `TrainingBot.java:1572` (town stop), `TrainingBot.java:1729` (store browse) |
| `BotScrolling.stepNow(bot,appetite)` — one roll now (GM, `!qa sc-2`) | public API | `BotScrolling.java:748` |
| `BotMaker.townBatch(bot)` — crystal/scrap batch at a town stop | town step | `TrainingBot.java:1540`; `BotMaker.java:218` |
| `!botscroll [<name\|id> [now [careful\|normal\|gambler]]]` | GM command (level 4) | `src/main/java/client/command/commands/gm4/BotScrollCommand.java:28-58`, registered `src/main/java/client/command/CommandsExecutor.java:432` |
| `!botwhy <name>` gear line | GM inspection (level 3) | `src/main/java/client/command/commands/gm3/BotWhyCommand.java:37-38` → `BotGearWhy.describe`; gated `BotWhyCommand.java:20-23` |
| `!maker plan\|crystals\|scrap` | GM command | `src/main/java/client/command/commands/gm0/MakerCommand.java:38-63`, registered `CommandsExecutor.java:292` |
| `!botyield` scroll ledger | GM command | `src/main/java/client/command/commands/gm4/BotYieldCommand.java:33` → `ScrollLedger.report` |
| `!qa run sc-2` / `gn-2 (sc-1)` | QA scenario | `src/main/java/soloMapling/qa/QaScenarios.java:750-768` (manual sc-1), `:772-860` (sc-2 bot-driven) |
| `GameQaWorld.botScrollStep` | QA staging | `src/main/java/soloMapling/qa/GameQaWorld.java:1027` → `BotScrolling.stepNow` |
| `GearFirstAdvisor.advise/farmPick` | map choice | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingMapChooser.java:144,263` |
| `BotTypeManager.BotType.SCROLL_BOT` | spawning the legacy sales bot | `src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:68-71`, `src/main/java/client/command/commands/gm4/ArtificialPlayerCommand.java:245,332`, `src/main/java/soloMapling/Environment/EnvironmentManager.java:1098` |

## How it works

### 1. Gear review (BotGear)

| Phase | Action | File:line |
|---|---|---|
| gate | `ENABLED`, bot, map present, `EquipMetadataCache.isInitialized()` | `BotGear.java:166-173` |
| plan source | `pareto()` = `-Dslums.gear.optimizer` equals `pareto`; `fullPass` = observer poll off **or** `LodCounts.isMapActive(mapId)` | `BotGear.java:69-86` |
| cheap pass | offscreen + pareto: `GearMemo.signature` unchanged → return without reading a single equip | `BotGear.java:305-326`, `src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/GearMemo.java:29,76` |
| collect owned | carried equips, skipping rebirth-locked and items whose item data yields no `Known` | `BotGear.java:326-350` |
| collect worn | every slot in `BotGearPlanner.SLOTS` | `BotGear.java:350-358` |
| wearer / rules | `Wearer(jobNiche, level, fame, str, dex, int, luk, freeEquipSlots)`; `Rules(weaponAllowed, BotWeapons.takesShield(kindOf))` | `BotGear.java:359-366` |
| plan | `BotGearOptimizer.plan` (pareto) or `BotGearPlanner.plan` (climb) | `BotGear.java:365-367` |
| apply | `wearableNow` re-runs the game's wear rules, then plain inventory moves | `BotGear.java:370-372`, `:697-850` |
| diagnose | `BotGearWhy.changed(...)` for `!botwhy` | `BotGear.java:373`, `src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGearWhy.java:27` |

Planner internals — `BotGearPlanner` (hill climb, port of cosmic_agents `AgentEquipmentOptimizer`): pools filtered by level/job/fame/weapon and capped at `MAX_PER_SLOT=6` (`BotGearPlanner.java:44,167-179`), up to `MAX_PASSES=4` single-piece / top+pants / requirement-chain moves (`:45,93,228`), placement rules in `place` (`:290`: pants refused under an overall, two-handed or shieldless weapon drops the shield), `finish` refuses a bare-legged body and a move that needs more bag room than is free (`:330`). Score = `Valuer.value` on gear totals; a weapon whose skills cannot use it scores -1 (`:392`).

`BotGearOptimizer` is the Pareto DP port (nutnnut/Cosmic `BotEquipManager`, AGPL-3.0, header `BotGearOptimizer.java:19-52`): `MAX_PARETO_STATES=2000`, `MAX_PER_SLOT=24` (`:57,59`), `plan` `:71`, `pruneDominated` `:166`, and `secondaryTarget` `:561` feeding the dexless AP cap (`BotGear.dexlessSecondaryTarget` `BotGear.java:576-600`).

### 2. The scoring seam (`GearScoring`)

`BotGear.SCORING` (`BotGear.java:383-416`) is the single yardstick every other system asks:

| Method | Meaning | File:line |
|---|---|---|
| `upgradeValue(chr, candidate)` | damage gain if the piece joins the best loadout at the bot's current level | `BotGear.java:385`, `:418-433` |
| `upgradeFraction` | the same gain as a fraction of current main attack | `BotGear.java:393`, `:448-461` |
| `holdingValue` | the same, planned at the piece's own `reqLevel` — a too-low bot still learns its worth | `BotGear.java:409`, `:424-440` |
| `bestSet(chr, extras)` | slot → equip the planner would wear from bag + extras | `BotGear.java:401`, `:463-485` |

All four are answered through one short-lived plan cache: `PLAN_CACHE_MS=60_000`, `PLAN_CACHE_SIZE=4_096`, key = `(charId, GearMemo.signature, optimizer, level, extras identity)` (`BotGear.java:480-530`). Consumers: gear-first advisor (`GearFirstAdvisor.java:109`), Maker junk test (`BotMaker.java:369-396`), Maker plan (`MakerPlan.java:63`), companion/gift offers (`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionItems.java:96,244,274,316`, `src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/BotBotGift.java:265` via `GearUpgrades.java:122-133`).

Weapons: a bot may only take a weapon of its job's kinds that draws the *same* single/aoe/ultimate skills it draws now; a beginner or 1st-job (`job.getId() % 100 == 0`) keeps the kind it holds (`BotGear.java:905-933`).

### 3. Scrolling (BotScrolling)

| Phase | Action | File:line |
|---|---|---|
| gate | `ScrollBudget.enabled()` (= `-Dsolomapling.botScrolling` not `"false"`) | `BotScrolling.java:692-694`, `ScrollBudget.java:61` |
| pace | one step every 4–8 s, `IDLE_MS=60_000` when it has nothing to roll | `BotScrolling.java:64,703-707` |
| collect | finished listings come back (`BotConsignment.collect`) | `BotScrolling.java:709` |
| appetite | `Appetite.of(botId)` — stable per bot: CAREFUL / NORMAL / GAMBLER | `BotScrolling.java:711`, `ScrollRules.java:41-53` |
| buy | on a new map, `ShopCatalog.onMap` NPC scrolls at ≥1000 mesos, at most `MAX_NPC_BUY_PER_SCROLL=3` extra | `BotScrolling.java:602-630` |
| post wants | every 10 min, an FM want for a scroll that fits and is not held | `BotScrolling.java:653-690`, `ScrollWants.java:59` |
| plan | `-Dslums.scroll.ev` → `ScrollPlanner` (EV); else `ScrollRules.choose`; a GAMBLER falls back to rules when EV finds nothing | `BotScrolling.java:238-247` |
| budget | daily mesos cap = `-Dsolomapling.botScrollPerLevel` × level; `MAX_ROLLS=20` (`-Dsolomapling.botScrollRollsPerDay`) | `ScrollBudget.java:69-73,86-101,134` |
| roll | `ItemInformationProvider.scrollEquipWithId(equip, scrollId, white, 0, false)` — **vegaItemId is always 0** | `BotScrolling.java:128-129` |
| outcome | CURSE (null result) → destroy + `LifeRuntime.event(BOOM)` + `BotDrama.onBotBoom`; else SUCCESS if level rose | `BotScrolling.java:421-479` |
| broadcast / log | `PacketCreator.getScrollEffect` to the map, `ScrollLedger.rolled`, godly meter, `QaLog.Topic.SCROLL` | `BotScrolling.java:456-474` |
| after | SUCCESS → `BotGear.review`; finished pieces listed (`BotConsignment.list`) or kept when unvalued; tossed only if level 0 and value ≤ 0 | `BotScrolling.java:553-590` |

The rules layer (`ScrollRules`) only accepts plain `204xxxx` stat scrolls that are not 2049xxx / clean-slate / chaos / modifier (`ScrollRules.java:81`), requires a free slot and category/req match (`:90`), and partitions picks into safe / long-shot / dark. Long shots and dark scrolls vanish once the day's godly meter is spent (`BotScrolling.java:223-231`); a white scroll is only spent on a long shot and never by a GAMBLER (`ScrollRules.java:181-227`).

`ScrollPlanner` (port of nutnnut `BotScrollPlanner`, near-verbatim, `ScrollPlanner.java:51-58`) is a destructive-value EV DP: `SLOT_DECAY=0.9`, `SCROLL_OPPORTUNITY_MARGIN=0.2` (a gain must beat 20% of the scroll's cost), `SCROLL_FOR_PROFIT_ENABLED=false`, and a boom scroll without a fallback item is refused (`ScrollPlanner.java:114-128,155-210,260-262`).

### 4. Maker (BotMaker / MakerPlan)

| Phase | Action | File:line |
|---|---|---|
| gate | `EconomyConfig.getBool("maker.botBatch", false)` and roster bot, Maker skill ≥ 1 | `BotMaker.java:84,218-250` |
| learn | `learnByLevel`: level 45/75/105 → Maker 1/2/3, skipped for beginners | `BotMaker.java:399-410` |
| crystals | ETC `400xxxx`, qty / `LEFTOVERS_PER_CRYSTAL`, not quest, `getMakerCrystalFromLeftover != -1` | `BotMaker.java:340-368` |
| scrap | carried equips (position > 0) skipping LOCK flag, rings, cash, quest items, rebirth-kept pieces, `!canDisassemble`, and any piece with `holdingValue > 0` | `BotMaker.java:369-396` |
| run | per-op world meter `makerbatch` (`EconomyMeters.tryAdd`) + per-bot cap; a failure is skipped, its world unit given back | `BotMaker.java:252-308` |
| plan (read-only) | `makercreatedata` + `makerrecipedata` recipes, gain = `upgradeValue`, keep ≥ `MIN_GAIN=0.5`, sort by gain | `MakerPlan.java:48,56-105,107-130` |

Maker mechanics go through the client's own service (`MakerService.makeLeftoverCrystal/disassemble`), so the fee is charged as a player's would be; `BotMakerHands` implements `MakerService.Hands` over `BotHoldings` with a no-op `send()` (`src/main/java/soloMapling/ArtificialPlayer/BotMakerSystem/BotMakerHands.java:22-90`). **No bot path crafts equips** — `MakerPlan` never moves anything, and the only real craft caller is the player packet `src/main/java/client/processor/action/MakerProcessor.java:126`.

### 5. Legacy ScrollingBot (fabricated rolls)

`ScrollingBot` builds FM equips, strips godly ones, reads the pre-rolled `getLevel()` as "successful scrolls", and shuffles a boolean array of that many successes (`ScrollingBot.java:88-111,113-133`); `scrollItem` then just replays that array with VFX and an emote (`:134-160`). No server roll, no scroll consumed. States cycle RESET → SET_SCROLL_VARS → SCROLLING_ITEMS → ADVERTISE_SALES (10% chance, 5–8 min wait) → WAITING_AFTER_AD → SET_SCROLL_VARS (`:187-228`).

### 6. Gear-first map choice

`GearFirstAdvisor` (behind `-Dslums.grind.gearFirst`) scores every candidate training map by expected gear: `Sources.LIVE` supplies kill pace (`OffscreenCombat.rateOn`), mob list (`MapMobIndex`), equip drops (`MonsterInformationProvider.retrieveEffectiveDrop`, only `itemId/1000000 == 1`, non-quest), and per-item gain via `GearScoring.upgradeFraction` (`GearFirstAdvisor.java:79-118`). `GearFirstPlanner.score` blends EXP and gear: `need = min(1, bestDesire/0.30)`, `unified = (1-need)*expNorm + need*gearNorm` (`GearFirstPlanner.java:29,72-100`). The advisor returns per-map weight multipliers `(0.1 + 0.9*unified) * crowdFactor(saturation)` (`GearFirstAdvisor.java:205-207`) and a `needGear`; `TrainingMapChooser` treats `needGear >= 0.5` as "gear-led" and skips the EXP leveling path entirely (`TrainingMapChooser.java:147-148,245`). `farmPick` answers an explicit FARM_ITEM goal, refusing spent maps (`GearFirstAdvisor.java:222-246`, `:141`), and `LifeRuntime` turns the current `Want` into a `LifeGoals.FarmWant` aspiration (`src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:653-657`).

## Data

| Store | Contents | Where |
|---|---|---|
| `EquipMetadataCache` + `ItemInformationProvider.getEquipStats` | the only item truth: slot type, req level/job/fame/STR/DEX/INT/LUK; unknown → the bot refuses the piece rather than guess | `BotGear.java:940-970` |
| character inventories (EQUIP / EQUIPPED / USE) | the gear itself; bots move equips between the tab and the body with plain inventory moves, persisted by the next bot store sweep | `BotGear.java:795-850` |
| `GearMemo` | per-bot signature (level, job, stats, fame, every worn and carried equip) to skip unchanged offscreen bots | `GearMemo.java:29,76,81` |
| `BotGear` plan cache | in-memory LRU, 4 096 plans / 60 s | `BotGear.java:487-500` |
| `BotGearWhy` | last gear change per bot (cap 5 000) for `!botwhy` | `BotGearWhy.java:20,27,52` |
| `ScrollLedger` | in-memory counters: bought by source (mesos), rolls by outcome, white used, destroyed value, megaphones, put-on / listed / sold / dropped / kept-unvalued | `ScrollLedger.java:19-81` |
| `ScrollWants` | in-memory FM wants, TTL 30 min, ≤ 3 per bot | `ScrollWants.java:31,32,59` |
| `BotConsignment` | in-memory listing book over real hired merchants in FM rooms; TTL 6 h, ≤ 2 per bot, 300 total | `BotConsignment.java:34-37,78-104` |
| `ScrollBudget` | per-bot/day spend, buys, rolls (in memory: a restart gives the day back) | `ScrollBudget.java:39-160` |
| `EconomyMeters` "godlies" / "makerbatch" | persisted daily meters (`economy_store`); `godlies` gates long shots, `makerbatch` is the world Maker cap | `src/main/java/soloMapling/Economy/EconomyMeters.java:30,79,117` |
| `makercreatedata` / `makerrecipedata` | stock Maker tables read by the read-only plan (empty on `SQLException`) | `MakerPlan.java:107-130` |
| scroll/equip market numbers | `ScrollMarketPrice` for scroll price, `EquipValuation.valueOf` for equips, `ItemDatabase.getItemPrice` | `BotScrolling.java:788-830` |

## Switches

| Switch | Default | Effect | Live? |
|---|---|---|---|
| `-Dslums.gear.optimizer` (`BotGear.java:69`) | `climb` | `pareto` = nutnnut DP for on-screen maps, climb + memo skip offscreen | **yes**, `pareto` (`docs/release/1.6.4.conf:271`) |
| `-Dslums.gear.dexless` (`BotProgressionSystem/BotProgression.java:223`) | off | AP stops the secondary stat at the gear's requirement | **yes** (`conf:271`) |
| `-Dslums.grind.gearFirst` (`GearFirstAdvisor.java:41`) | off | gear-weighted map choice, gear-led override, farm goals | **yes** (`conf:271`) |
| `-Dslums.scroll.ev` (`BotScrolling.java:233`) | off | `ScrollPlanner` EV pick instead of the appetite rules | **yes** (`conf:271`) |
| `-Dslums.junk.sort` (`BotBagRoom.java:156`) | off | town sale sorts junk off the shelf | **yes** (`conf:271`) |
| `-Dsolomapling.economy.maker.botBatch` (`BotMaker.java:84` via `EconomyConfig.PROPERTY_PREFIX` `EconomyConfig.java:30`) | `false` (`economy.yaml:159`) | the Maker crystal/scrap batch | **yes** (`conf:271`) |
| `-Dslums.gear.bridge` (`src/main/java/soloMapling/crafting/NormalGearBridge.java:15`) | off | normal-equipment guide activation, read-only | **yes** (`conf:268-269,271`) |
| `-Dsolomapling.botScrolling` (`ScrollBudget.java:61`) | `true` (off only if `"false"`) | the whole scroll loop | unset → **on** |
| `-Dsolomapling.botScrollPerLevel` / `-Dsolomapling.botScrollRollsPerDay` (`ScrollBudget.java:69,73`) | 15 000 / 20 | daily scroll mesos and roll count | unset → defaults |
| `-Dslums.megaphone...` via `BotMegaphoneGate` | capped server-wide | scroll megaphones | cut off in code, not in these packages |
| `economy.yaml` `scrolling.*` (`src/main/java/soloMapling/Economy/economy.yaml:113-120`) | careful 40%, gambler 10%, long-shot 10%, normal dark 3%, gambler dark 10%, `whiteOnLongShots: false`, `godliesPerDay: 5` | appetites and godly cap | file defaults |
| `economy.yaml` `maker.*` (`economy.yaml:158-163`) | `botBatch: false`, per-bot 10, world 300 | Maker caps | overridden by the `-D` above |
| `BotGear.ENABLED` (`BotGear.java:61`) | `true` | global off switch (code constant, not a flag) | on |
| `STARFORCE_BOOST_PERCENTAGE` / `…2` (`config/ServerConfig.java:603-604`) | 0.2 / 0.2 | star-force craft stat share; **not read by any bot code** | `conf:214-217` sets `…2=0.2` (card b296) |

## Invariants

- A bot's `getClient().getPlayer()` is null: everything reads the `Character` and `BotHoldings`, never a client (`BotMaker.java:326-338` routes roster bots through `BotMakerHands`).
- Only real-gear slots are touched; the cash overlay (-1xx) is never displaced, so an NX outfit survives a re-dress (`BotGear.java:45-46,154-162`).
- Nothing is taken off except by an overall replacing top+pants, or a two-handed / shieldless weapon replacing the shield (`BotGear.java:716-790`, `BotGearPlanner.java:290`).
- A bot never ends bare-legged: `wearableNow` refuses the half of a top+pants swap that would leave it so (`BotGear.java:697-714`).
- A bot only wears what the item data knows: cash, wrong gender, `EquipOmitList`, rebirth-locked and unknown ids are refused, not guessed (`BotGear.java:940-970`, `BotGear.java:269-287`).
- Gear moves are plain inventory moves verified against the live stack (`carried.getItem(from) == on`), and the equip is marked untradeable on equip exactly as a player's would be (`BotGear.java:795-850`, `:889-903`).
- Bot scrolling uses the **server's** roll with `vegaItemId = 0` and no GM flag; a curse destroys the item and is persisted (`BotScrolling.java:128-129,421-479`).
- A finished piece is never destroyed for lacking a valuation — it is kept and counted (`ScrollLedger.unknownValue`, b181 F-14, `BotScrolling.java:571-582`).
- Every bot money/roll decision goes through `ScrollBudget`; a failed purchase refunds both mesos and the budget unit (`BotScrolling.java:632-651`).
- Maker caps count **operations**, not items, and a failed operation gives its world unit back (`BotMaker.java:252-308`).
- Maker never crafts an equip for a bot; the craft plan is read-only and moves nothing (`MakerPlan.java:9-22`; only `MakerProcessor.java:126` crafts).

## Origin

| Path | Origin | Evidence |
|---|---|---|
| `BotGear.java`, `BotGearWhy`, `GearMemo`, `GearScoring`, `GearUpgrades` | custom | oldest commit `c54b59784 2026-10-19 Dress bots in the best gear they own` (repo baseline is `03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline`) |
| `BotGearPlanner.java` | **modified port** of cosmic_agents `AgentEquipmentOptimizer` (Kiwiism, AGPL-3.0), cut down to a hill climb | header `BotGearPlanner.java:11-28`; added `c54b59784` |
| `BotGearOptimizer.java` | **modified port** of nutnnut/Cosmic `BotEquipManager` (GreenCatMS, AGPL-3.0, dev `2771d9ea1`); rings, weapon preference and map-mob hit chance not ported | header `BotGearOptimizer.java:19-52`; added `f03748a56 2026-10-30` |
| `BotScrolling`, `ScrollRules`, `ScrollBudget`, `ScrollLedger`, `ScrollWants`, `BotConsignment`, `ScrollEvQa` | custom | oldest commit `29d61aefc 2026-10-21 Let bots really scroll their own gear` |
| `ScrollPlanner.java` | **modified port** of nutnnut `BotScrollPlanner`, near-verbatim | header `ScrollPlanner.java:51-58`; added `8e5b95027 2026-10-30` |
| `BotMaker`, `BotMakerHands` | custom (in spirit: nutnnut/Cosmic `BotMakerManager`) | `ea0103901 2026-10-30`, then `eb154325c` |
| `MakerPlan.java` | custom (ported in spirit from nutnnut/Cosmic `356fbfeb1 .../bots/BotMakerPlanner.java`, AGPL-3.0) | header `MakerPlan.java:9-22` |
| `GearFirstAdvisor` / `GearFirstPlanner` | custom | `e0a7026d8 2026-10-30 Gear-first map choice ... (ticket 162 part 2)` |
| `ScrollingBot.java` | **base** (MapleRoot-derived) | oldest touch `03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline`; later only `639b6e887` megaphone cap and `0f0231983` exploit fixes |

Almost all of `soloMapling/` is custom; the two planner ports and the one planner "in spirit" port are the only third-party lines here.

## Tests and QA

Unit tests (`src/test/java`):

| Test | Covers | File |
|---|---|---|
| `BotGearTest` (15) | review/apply/wearableNow ordering | `src/test/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGearTest.java` |
| `BotGearPlannerTest` (10) | climb: pools, chaining, place, bag room | `.../BotGearSystem/BotGearPlannerTest.java` |
| `BotGearOptimizerTest` (10) | Pareto DP, dominance, secondary target | `.../BotGearSystem/BotGearOptimizerTest.java` |
| `BotGearSwitchTest` (6) | climb vs pareto, memo skip, per-call cost | `.../BotGearSystem/BotGearSwitchTest.java:111,125,147,186,247,275` |
| `BotGearWearTest` (8) | named wear/takeOff, Guild Quest rock, full bag | `.../BotGearSystem/BotGearWearTest.java:80-180` |
| `BotGearHoldingValueTest` (1) | holding value at the piece's own level | `.../BotGearSystem/BotGearHoldingValueTest.java:54` |
| `GearUpgradesTest` (5) | offers, spares, defence-only gain | `.../BotGearSystem/GearUpgradesTest.java` |
| `BotScrollingTest` (24) | the real roll path, curses, white scroll, budget, EV picks | `.../BotScrollSystem/BotScrollingTest.java:169-650` |
| `ScrollRulesTest` (10) | plain-scroll filter, appetites, godly cap, megaphones | `.../BotScrollSystem/ScrollRulesTest.java:30-153` |
| `ScrollPlannerTest` (21) | EV DP, boom without fallback, opportunity margin | `.../BotScrollSystem/ScrollPlannerTest.java:73-309` |
| `ScrollBudgetTest` (3) | reserve, 25% share, daily reset | `.../BotScrollSystem/ScrollBudgetTest.java:30-50` |
| `BotMakerTest` (13) | caps, fees, hands, plan, job masks | `.../BotMakerSystem/BotMakerTest.java:82-281` |
| `BotMakerJunkTest` (1) | a piece above the bot's level is not junk | `.../BotMakerSystem/BotMakerJunkTest.java:101` |
| `BotMakerFeeTest` (5) | disassembly/craft fees, emptied wallet | `.../BotMakerSystem/BotMakerFeeTest.java:197-252` |
| `BotMakerPathTest` (2) | crystal through the Maker path, no skill no output | `.../BotMakerSystem/BotMakerPathTest.java:88,99` |
| `GearFirstTest` (9) | planner choice + advisor weights + spent maps | `.../BotGrindSystem/GearFirstTest.java:33-135` |
| `VegaAppliesFedScrollTest`, `VegaSpellScrollTest` | b290 fix: the fed scroll's own stats/roll apply at level 15+ | `src/test/java/net/server/channel/handlers/VegaAppliesFedScrollTest.java:58` |
| `StarForceFailTest`, `StarForceBoomTest` | b293 fix: a fail takes the star back; a superior boom destroys | `src/test/java/scripting/npc/StarForceFailTest.java:76` |

Auto-QA (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`): `t-gb-1` pareto gear (`:1440`), `t-gb-2` dexless AP (`:1444`), `t-gb-3` gear-first farm (`:1448`), `t-gb-4` junk sort sale (`:1453`), `t-gb-5` EV scrolling (`:1457`), `t-gb-6` AP variants (`:1462`), `t-mk-2` bot Maker batch (`:1561`), `t-ng-8` Nimble weapon core exchange (`:1593`), `t-ng-10` normal gear bridge (`:1596`), `t-sc-1` gambler scrolls a staged sword (`:667`), `t-sc-2` careful bot plays safe (`:671`). Probes: `src/main/java/soloMapling/autoqa/Probes162.java:588,706,811,924`, `src/main/java/soloMapling/autoqa/ProbesMaker.java:199`.

QA scenarios: `gn-2`/`sc-1` manual GM scroll (`QaScenarios.java:750-768`); `sc-2` a bot next to the GM rolls its own one-handed sword for real with a kit of 60% ×4, 10% ×2, dark 70% ×1, white ×1, one roll every 6 s by its appetite (`QaScenarios.java:772-860`).

**Not covered:** no test calls `ScrollingBot.generateExactScrollList` or asserts its fabricated outcomes (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/ScrollingBot.java:113`); the only ScrollingBot-side coverage is `src/test/java/soloMapling/qa/QaScenarioStagingTest.java:163`. No test exercises `BotGear.afterRebirth` end-to-end at a real level-200→1 transition, no auto-QA check covers the Vega spell or Star Force (they are server-side, covered by unit tests only), and nothing tests `MakerPlan` against a real MySQL `makercreatedata` (the query is exercised with an empty/exception path).

## Known issues

| Card | Status in this code | Note |
|---|---|---|
| b290 (S2) Vega's Spell: level-based roll replaces the fed scroll; at 15+ a pure loss | **fixed in tree**, `b0e5e4c58 2026-11-03 Let Vega's Spell apply the scroll it is fed` | the 561 branch used the one-arg `scrollEquipWithId` whose level table is 0 from level 15 up (`src/main/java/server/ItemInformationProvider.java:1166-1221`); the handler now calls the 5-arg overload with `vegaItemId` (`src/main/java/net/server/channel/handlers/UseCashItemHandler.java:627`). **No bot code is involved** — `BotScrolling.java:129,877` always passes 0 |
| b292 (S3) Maker crafting has no level gate (`MakerCrafting.js:381`) | **fixed in tree**, `41a79b006 2026-11-03` | the script now gates on `requiredLevel` at `scripts/npc/interactions/shared/MakerCrafting.js:349-350` and re-checks at `:415-416`. Bot-side there is nothing to fix: `BotMaker` makes crystals and scraps, it never crafts |
| b293 (S2) Star Force fail branch dead (`% 1`), superior boom reports Complete | **fixed in tree**, `2e6b1ac39` | `src/main/java/scripting/npc/NPCConversationManager.java:1745-1760` (`else if (nEquip.getLevel() > 0)`), superior boom destroy `:1766-1784`. Star Force is a player NPC path; no bot package references it |
| b296 (S3) `STARFORCE_BOOST_PERCENTAGE2` absent from production config | **addressed in conf**, `docs/release/1.6.4.conf:214-217` sets `=0.2` | read at `src/main/java/server/StarBoost.java:87-88`; again no bot reads it |
| b287 (S4) t-xc-5: a PANIC-style scroll trader never lists a Chaos/White Scroll | open, adjacent | `ScrollRules.isPlainScroll` deliberately excludes chaos/white from *bot* use (`ScrollRules.java:81`), so bots neither use nor post wants for them |
| b257 (S4) FM bot shops sparse, class gear uneven | open, adjacent | the legacy `ScrollingBot` path (`ScrollingBot.java:88-111`) is what stocks these |
| b279 (S3) Maker quest 2 only counts a specific monster-drop crystal | open, adjacent | the bot batch uses `MakerService.makeLeftoverCrystal`, a different route (`BotMaker.java:340-368`) |

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/ScrollingBot.java:90-95` — the regeneration loop `for (int tries = 0; itemsToScroll.isEmpty(); tries++)` removes godly pieces only while `tries < 20`; if every generated piece stays godly the loop never terminates (unbounded generation on the bot thread). The guard should bound the loop, not the filter.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotTypes/ScrollingBot.java:32,37,97,166` — `listIndex` is initialised to 0 and never incremented anywhere in the file, so a ScrollingBot scrolls and advertises `itemsToScroll.get(0)` forever; the other generated items are dead weight.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:602-630` — `buyAtNpc` computes `need` per scroll id but the `MIN_NPC_PRICE=1000` and `isPlainScroll` filters are applied before the per-bot `MAX_BUYS` check, and every failing `buyOne` breaks the inner loop only, so a shop full of unaffordable scrolls retries the same purchase once per listing per tick (bounded by the 4–8 s pace, not by the budget).

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:787-830` — `LiveFacts.catalogFor` rescans `2040000 + cat*100` item ids on every call and caches nothing, and `scrollPrice` is invoked from `evPlanPriced` per candidate scroll; with the EV planner on live this is a per-tick item-data scan over hundreds of ids for every town bot.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGear.java:576-600` — `dexlessSecondaryTarget` returns `-1` ("no cap") for magicians and on *any* error, so an item-data miss silently disables the AP cap rather than keeping the previous one; the failure mode is invisible except in the log.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGearPlanner.java:167-179` — the pool is sorted by required level then raw stat sum and truncated at `MAX_PER_SLOT=6`, before the weapon/skill rule or the `Valuer` runs, so a genuinely better high-requirement piece can be dropped from consideration purely on insertion order.

## State

**Working.** Evidence: 7 gear test classes (55 `@Test`), 4 scroll test classes (58), 4 Maker classes (21), `GearFirstTest` (9), plus auto-QA `t-gb-1..6`, `t-mk-2`, `t-ng-8/10`, `t-sc-1/2` all registered and lane-only. All seven relevant switches are **ON in live** (`docs/release/1.6.4.conf:271`, owner note `:268-269` after a TEST auto-QA pass), which means the pareto optimizer, dexless AP, gear-first map choice, EV scroll planner, junk sort, Maker batch and the normal-gear bridge are the production configuration, not experiments.

Caveat: the legacy `ScrollingBot` sales path is **partial** — it advertises real items but its scrolling is a fabricated boolean replay (`ScrollingBot.java:113-160`), so anything a player reads off a legacy bot's "scrolled" item is cosmetic.

The three near cards (b290/b292/b293) are all fixed in this tree and confirmed by their own tests; the only purely-open item touching this area is b296, which is a config value now present in `1.6.4.conf` but depends on the deploy actually applying it.

## Open questions

1. For `-Dslums.gear.optimizer=pareto` on live: `fullPass` falls back to "every bot" whenever `LodCounts.trackerRunning()` is false (`BotGear.java:78-86`). Is the observer poll guaranteed running in the live topology, or does a restart window run the full DP for the whole population?
2. `BotConsignment`/`ScrollWants`/`ScrollBudget`/`GearMemo`/`BotGearWhy` are all in-memory (only `EconomyMeters` is persisted). After a restart a bot forgets its daily scroll spend and re-lists against merchants that still hold its old listings — is `BotConsignment.collect` the only recovery, and does it survive a merchant whose bot is gone?
3. No bot path spends the Vega spell (`BotScrolling.java:129` passes 0). Is that deliberate policy (bots must not consume cash items) or simply not wired? Nothing in the code states it.
4. `MakerPlan` reads `makercreatedata`/`makerrecipedata` directly rather than through `MakerService`; if the stock tables change shape the plan silently returns empty (`MakerPlan.java:107-130`). Is an empty plan distinguishable from "nothing worth making" anywhere in `!maker plan` output?
5. The gear-first advisor's `Candidate.mapAreaPx` is always 0 from `candidates` (`GearFirstAdvisor.java:146-180`) while `GearFirstPlanner.seekSeconds` expects it — is that intentional (seek time unused) or an unfinished part of ticket 162?
