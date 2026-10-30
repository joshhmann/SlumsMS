# 162: Fully adopt nutnnut's gear optimization

**Status:** started 2026-10-30, 1.6.2. **Owner:** Server Dev. **Source:** owner, 2026-10-30: "also fully adopt gear
optimizations." Upstream nutnnut/Cosmic (AGPL-3.0, same as ours): credit `nutnnut/Cosmic <sha>` in port commits and
docs/PROVENANCE.md. Matrix section 1: 157-nutnnut-tools/adoption-matrix.md.

## What "fully" covers (matrix section 1, Gear optimizing)

1. **Optimizer:** replace our cut-down BotGearPlanner hill climb (after cosmic_agents) with upstream BotEquipManager's
   stat-chaining optimizer (Pareto DP, requirement chaining so a piece that unlocks a better one counts, dexless and
   other build-aware weights), for every class we support.
2. **Gear-first progression:** upstream BotGrindPlanner/BotGrindAdvisor: "gear first, EXP tiebreak" map choice and
   `farm <item>` goals, as LifeGoals aspirations (this is ticket 160 part 1).
3. **Upgrade sharing:** compare against the recipient's inventory and offer upgrades to the owner and sibling alts
   ("any upgrades?", "request?", "trade upgrades"), normal Trade only (also ticket 99 / NN-012).
4. **Junk sorting:** reserve rules and an owner-facing "sell trash" / "trade trash" for alts on top of our SaleRules.
5. **Scroll planning:** upstream's expected-value scroll planner and reproduction-cost valuer replace our naive
   UpgradeSimulator model for bot scrolling (ticket 160 part 2). The owner's "fully adopt" is the go for this
   economy-adjacent part; prices of scrolled gear will move, so a before/after price report on TEST comes before any
   live switch-on.

## Constraints

- 3,000 bots at ~1.5 GB: the full optimizer runs event-triggered (new item, level-up, job advance) and for watched
  bots first; offscreen bots get a cached or cheaper pass. Measure tick cost before and after.
- Everything behind switches, off on live until the owner's go; credits per port; fail-before tests.
- Interacts with: BotGear, BotGearPlanner, BotScrolling/ScrollRules/ScrollWants, UpgradeSimulator, SaleRules,
  LifeGoals, TrainingMapChooser/LevelingPathChooser, OwnedAlt*, merchant stock prices.

## Part 3: TODO (not in branch 162-gearopt)

Upgrade sharing with the owner and sibling alts goes with ticket 99 (branch 162-companions). It must score through
`soloMapling.ArtificialPlayer.BotGearSystem.GearScoring` (`upgradeValue`, `bestSet`), the single gear-scoring entry
point population bots use too (owner rule 2026-10-30: one implementation for bots and alts).

## Status (branch 162-gearopt, 2026-10-30)

Parts 1, 2, 4, 5 done in code, every one behind a switch that is off by default (live unchanged). Not deployed;
TEST untouched (frozen for 1.6.1). Switches (all `-D` system properties, none in config.yaml):

| Part | Switch | What turns on |
| --- | --- | --- |
| 1 | `-Dslums.gear.optimizer=pareto` | BotGearOptimizer (Pareto DP) for bots on watched maps; climb + skip-if-unchanged memo offscreen; GearScoring follows it |
| 1 | `-Dslums.gear.dexless=true` | AP secondary stat capped by gear (BotBuild secondaryCap; never under the class floor; mages exempt) |
| 2 | `-Dslums.grind.gearFirst=true` | Gear-first map weights, leveling path skipped while gear-led, FARM_ITEM aspiration and farm map pick |
| 4 | `-Dslums.junk.sort=true` | Town junk sale keeps the self-reserve and the valuables shelf |
| 5 | `-Dslums.scroll.ev=true` | Reproduction-cost scrolled-gear prices (UpgradeSimulator) and the EV scroll planner (BotScrolling) |
| 5b | `-Dslums.scroll.marketPrice` (default: follows `slums.scroll.ev`) | Scroll prices learned from real sales (ScrollMarketPrice), used by the EV planner, the reproduction cost, bots' scroll asks and wants |

QA before any switch-on (TEST first): `!botwhy` gear on a watched bot after a loot; a level-up dressing pass; the
price report `mvn -q -o test -Dtest=ScrollPriceImpactReportTest -Dwz-path=<wz>` reviewed by the owner (part 5 moves
FM prices of scrolled gear); bag fill after a day with junk sort on (no bot stuck at 90% full).
Rollback note: a FARM_ITEM goal or FARM_GEAR plan step saved by this build is unknown to older builds; goals now skip
unknown kinds on load, older builds do not, so turn gearFirst off a day before rolling back to a pre-162 build.

## Part 5b: market-learned scroll prices (2026-10-30, branch `162-scrollmarket`)

Owner: "if bots create scrolls they should check the prices of scrolls ... drive it by some learning algo".
`soloMapling.Economy.ScrollMarketPrice` learns a price per scroll id (204xxxx) from every sale the price board keeps
(`PriceBook.record`: FM shop and hired merchant sales, bot shop sales, bot FM buys as bot seeds, window trades,
bot direct trades; never bot-to-bot trades or linked accounts, which the board already refuses). Rule: EWMA,
alpha 0.2 per unit weight (weight 1 player side / 0.5 bot fill, x sqrt(qty) up to 3); sales under 0.2x or over 5x
the current estimate are outliers and ignored; at most 15% move per day; always inside the board's 0.25x..4x band
of the yaml price; blended with the yaml by n/(n+4), n the summed weights halving every 5 days without sales.
Constants are `economy.yaml` keys `scrollMarket.alpha|botWeight|outlierLow|outlierHigh|dayStep|blendK|halfLifeDays`.
Persistence: none of its own; the board's `pricesamples` window (7 days) is replayed in time order at the first
read after a restart. No migration.

Wired into: `UpgradeSimulator.scrollsFor` (reproduction cost of scrolled gear), `BotScrolling.evPlan` (a held
scroll's cost in the planner) and its value curve, `BotScrolling.postWants` (want price), `PriceBook.fairValue`
for scrolls (bot shops, direct trades / haggling bands, bot-bot trades, MegaBuy), the bot FM scroll stock
(`ArtificialShopGenerator`) and `SellingMerchantBot`. Unchanged: SaleRules, PriceBook's static anchors and caps,
the FM shopper's yaml-based buy ceiling. Read-out: `!economy price <scroll id>` (learned, sales, weight,
outliers, yaml, price used); `live.json` `scrollPrices` (top 40). Tests: `ScrollMarketPriceTest`,
`ScrollMarketValuationTest`, `BotScrollingTest.evPlannerDeclinesAScrollWhoseMarketPriceOutgrewWhatItAdds`.
QA check id: none yet (TEST not touched); suggested: on TEST with scroll.ev on, `!economy price 2043001` after a
day of FM traffic shows sales > 0 and a learned price within 15% a day of the yaml.

## Part 3 status (2026-10-30, branch `162-companions`)

Built behind `COMPANION_UPGRADES_ENABLED` (off): `GearUpgrades` asks one question through `GearUpgrades.Scorer`
("how much better is this wearer with this piece, counting what it wears and carries"). The default answers with
`BotGearPlanner` as it stands; when the new optimizer lands, `GearUpgrades.install(scorer)` swaps it in without
touching the companion code. Integration (release-1.6.2): the default scorer is now `GearUpgrades.from(GearScoring.live())`,
the scorer bots dress by, and the `GearUpgrades.Planner` copy of BotGear's `liveFacts`/`liveValuer`/`piece` is deleted.
