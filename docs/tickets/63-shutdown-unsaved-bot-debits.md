# Ticket 63: preserve bot debits when the shutdown save budget expires

**Target:** 1.5.1

**Priority:** P1 — potential duplicate value after restart

**Status:** open; source-reviewed against 1.5.0 `06a724dfc`, runtime reproduction pending

**Area:** Server / bot persistence

**Related:** ticket 62 (player-save ordering)

## Problem and evidence

The budgeted `BotStore.settleAndSaveAll(Collection, long, int)` stops taking bots from its queue once the deadline
expires. Remaining bots are returned as names in `SweepReport.unsaved`; the server warns that they resume from
their last save. Their `saveOne(chr, true)` calls never run, including its unpersisted-debit recovery.

That fallback is safe for some lost progress, but not for a bot whose stored row still claims items or mesos it
already paid to someone else. `BotHoldings.hasUnpersistedDebit()` explicitly describes this invariant: restoring
the older bot row while the recipient keeps the payment duplicates value. `Trade.setMeso()` records bot spending
through `BotHoldings.noteMesoSpent()`; the completed trade path does not itself persist the bot through `BotStore`.

Example to reproduce: persist a bot with 10 million mesos, complete a 1 million meso payment to a real player,
persist the recipient, then expire the shutdown budget before processing that bot. If its old row is restored,
the bot returns with 10 million while the player retains the extra million. This is a proposed reproduction,
not an observed live incident. A successful intervening autosave would remove the stale-debit condition.

Source locations:

- `src/main/java/soloMapling/ArtificialPlayer/BotStore.java`: budgeted `settleAndSaveAll` (1.5.0 line 1002),
  queue deadline check (1022), `saveOne`, `recordDebit`.
- `src/main/java/soloMapling/ArtificialPlayer/BotHoldings.java`: `hasUnpersistedDebit` (655), `noteMesoSpent`.
- `src/main/java/server/Trade.java`: `setMeso` and trade completion.
- `src/main/java/net/server/Server.java`: `shutdownBots` and handling of `SweepReport.unsaved`.
- Existing `BotStoreTest.theShutdownSweepStopsAtItsBudgetAndNamesTheBotsItDidNotWrite` checks timing/reporting,
  but does not establish debit safety for unprocessed bots.

## Scope

Design bounded shutdown behavior that protects outstanding debits, including bots still queued when time runs
out. Prioritizing debit-bearing bots may help, but cannot by itself guarantee durability if the DB remains
unavailable; document the remaining failure behavior and recovery strategy. Preserve the player-save priority
and shutdown limits. Do not change rewards, prices, or other economy tuning.

## Acceptance and regression checks

- Reproduce the payment scenario on a throwaway database, with a deliberately exhausted shutdown budget.
- Cover both meso and item payouts, including a debit-bearing bot never dequeued, a write still in flight, and a
  write failure. Reload both sides and assert that no value is restored twice in the supported recovery path.
- Exercise zero/short budgets and DB unavailability. Record what is durable, what needs recovery, and what is
  reported; a warning naming a skipped bot is not by itself a durability guarantee.
- Preserve bounded shutdown behavior and ordinary successful saves; add regression coverage beyond the existing
  timing test.
- Rehearse only on isolated test assets. This ticket does not authorize live changes or player-data repair.

No implementation, DB experiment, or in-game reproduction was performed when filing this ticket.
