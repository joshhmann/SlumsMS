# 100: Busy FM (1.6.0, rc1)

**Area:** Bots / population
**Kind:** BUG + TUNE
**Depends on:** 60 (merchant/social personalities)
**Status:** open, target 1.6.0 (rc1). Investigation running.

Owner, 2026-10-25: the Free Market has been bare since 1.5.2.

## Goal

Keep the FM busy: merchant and social personalities spend real time in the FM main area (shops, shout trades, chatter),
with a **floor of bots per channel** in the main FM area so it never feels empty.

## Steps

1. Finish the investigation: why the FM emptied in 1.5.2 (population director, nutnnut stability port ticket 80, logout
   spreading, shop placement).
2. Fix the cause.
3. Add the per-channel floor (config value) and a health check that reports bots in the FM main area per channel.
4. Verify on TEST, then live.
