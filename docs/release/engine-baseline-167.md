# 1.6.7 engine baseline (branch `167-engine-baseline`)

Five small engine/data changes: four fixes ported from P0nk/Cosmic, one removal of dead MapleRoot code. This file is the
scope-and-proof card: after the branch, each statement below is true, and the named test is the one that shows it.

## 1. The Cash Shop Surprise never hands out a package item

After: a commodity whose item id is a package (`itemId / 10000 == 910`) can never be picked by
`CashShop.CashItemFactory.getRandomCashItem()`, so `openCashShopSurprise()` cannot reward a package item (the v83
client crashes on one). Nothing else about the Surprise changes: same pool of on-sale commodities, same single reward,
same item consumption.

Proved by: `CashShopSurprisePoolTest` (`src/test/java/server/cashshop/`). A fake pool holding one normal serial and one
package serial returns only the normal item over many draws; a pool holding only a package serial returns `null`.
Fails before the change (the package comes back), passes after.

Upstream: P0nk/Cosmic#244, `1791365e0`.

## 2. NPC scripts may pay mesos with a non-integer number

After: `cm.gainMeso(...)` accepts a JavaScript `Number` that is not an integer (`price * 1.5`); the `Double` overload
passes `gain.intValue()` to the existing `gainMeso(int)` path, which is unchanged. Without it the script call fails and
the NPC conversation stops.

Proved by: `GainMesoDoubleTest` (`src/test/java/scripting/npc/`). Reflection finds `gainMeso(Double)` (fails before),
invoking it pays the truncated int amount; the `int` path still pays exactly that amount.

Upstream: P0nk/Cosmic#228, `0d684c140`.

## 3. Chaos scroll results use fair rounding

After: `Randomizer.rand(lbound, ubound)` casts the random double to `int` **before** adding `lbound`, so a negative
lower bound no longer truncates toward zero: `rand(-5, 5)` is uniform over all 11 values (0 no longer comes up twice as
often, -5 is reachable). Positive-bound callers behave exactly as before. The chaos scroll's existing clamp at 0
(`Math.max(0, ...)` on every stat) still holds, so a bad roll on a stat of 1 leaves 0, never a negative stat.

Proved by: `RandomizerTest` (`src/test/java/tools/`): upstream's `randShouldIncludeEntireRange`, a 200,000-roll
distribution test (each of the 11 values within ±10% of 1/11, min/max reached), and a positive-bound test. Plus
`ChaosScrollClampTest` (`src/test/java/server/`) which drives the real `scrollEquipWithChaos` path on an equip with
STR 1 and asserts the stat never goes below 0. The distribution test fails before the change; the clamp test passes
before and after (it guards the clamp).

Owner decision: fix it; the patch notes say chaos scroll results now swing more. Patch notes line needed for 1.6.7.
Upstream: P0nk/Cosmic#261, `ec90df9c5` and its test `215dc4229`.

## 4. Meerkats drop the Cat's Eye quest item

After: Meerkat (2100108) drops Cat's Eye (4031568, quantity 1, chance 80000) for quest 3911 "Making the Fancy Sword",
which needs 4; the item's own text says it comes from Meerkat. The existing Scorpion (2110301) row stays. The migration
is `database/migrations/068-cat-eye-meerkat-drop.sql`; applying it twice changes nothing, and its rollback deletes only
the Meerkat row.

Proved by: `CatEyeMeerkatDropMigrationTest` (`src/test/java/database/`). Asserts both files exist, the migration inserts
exactly `(2100108, 4031568, 1, 1, 3911, 80000)` behind a `NOT EXISTS` guard (idempotent), the ledger row is written,
and the rollback deletes only that pair.

Data source: P0nk/Cosmic `bfcadc069` (`src/main/resources/db/152-drop-data.sql:21102`).

Note for the release: add `068-cat-eye-meerkat-drop` to `docs/release/1.6.7.conf` when that file exists, and run it on
TEST before live.

## 5. The rebirth "change to a previous rebirth class" swap is gone

After: `scripts/npc/rebirth.js` no longer contains the `jobChangeChecker === 1` branch (it was unreachable: the flag is
only ever 0) and no longer parses rebirth history for it. `NPCConversationManager.changeRebirthJobCerezeth(int)` and
`Character.changeRebirthJobCerezeth(Job)` are removed, along with their two `scripts/lib.d.ts` declarations. The normal
rebirth flow (gates, key items, levels, rates, `getAllRebornDataCerezeth`) is untouched.

Proved by: `RebirthJobSwapRemovedTest` (`src/test/java/scripting/`). No file under `scripts/` mentions
`changeRebirthJobCerezeth`; reflection finds no such method on `NPCConversationManager` or `Character`. Fails before the
change.

Owner decision 8 Nov 2026. Not a port: no `Ported-From` trailer on this commit.

## Verification

- Per fix: the new test run before the change (recorded failure) and after (pass).
- Full suite: `mvn -q -o test -Dwz-path=/root/work/SlumsMS-152-maps/wz`; counts from
  `target/surefire-reports/TEST-*.xml` are recorded in `REPORT-deepseek.md`.
- Out of scope: the PlayerNPC static-init fix (#193), the GM shop 1337 tidy, other drops/shops, mob skill rates,
  autoban.
