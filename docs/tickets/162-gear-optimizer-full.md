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
