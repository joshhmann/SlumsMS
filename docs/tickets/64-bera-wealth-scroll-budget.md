# Ticket 64: use Bera's meso rate for the bot wealth scrolling bonus

**Target:** 1.5.1

**Priority:** P2 — existing wealth sink partially disabled

**Status:** open; source-reviewed against 1.5.0 `06a724dfc`, runtime validation pending

**Area:** Server / bot scrolling

## Problem and evidence

`BotWealth.worldMesoRate()` asks `Server.getInstance().getWorld(0)` for the rate. `Server.getWorld(int)` resolves
the actual world ID through `worldsById`; SlumsMS runs world 1 (Bera), so there is no world 0 to supply the rate.
The helper returns zero, and `BotWealth.scrollBonus()` then returns zero too.

`BotScrollSystem.ScrollBudget.maxPrice()` adds that bonus to the ordinary daily budget. Bots on Bera consequently
retain ordinary scrolling but never receive the intended extra spending allowance for mesos above their wealth
ceiling. The character-based intake cap and upkeep paths are separate; do not describe the entire wealth system
as disabled.

Source locations:

- `src/main/java/soloMapling/ArtificialPlayer/BotWealth.java`: `scrollBonus` (1.5.0 line 127), `worldMesoRate` (286).
- `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/ScrollBudget.java`: `maxPrice` (97).
- `src/main/java/net/server/Server.java`: `getWorld`.
- `src/test/java/soloMapling/ArtificialPlayer/BotWealthTest.java`: `noScrollBonusWithoutAWorld` covers no server,
  but not a running world whose ID is 1.

## Scope and acceptance

- Resolve the correct world rate, preferably through the bot/world context rather than another fixed world ID.
  Account for the helper's cached rate if retaining caching.
- Add a regression case with only world 1 available: a wealthy bot receives the intended nonzero bonus, while a
  bot under its ceiling receives none.
- Preserve safe behavior with no world, the feature off, and the ordinary purchase/reserve/count limits.
- Verify that `ScrollBudget.maxPrice()` consumes the corrected bonus, rather than testing only a helper.
- Keep existing wealth formulas and policy unchanged; this repairs the documented behavior, not economy tuning.

No implementation or tests were run when filing this ticket.
