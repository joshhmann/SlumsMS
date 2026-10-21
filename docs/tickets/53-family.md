# 53: Family system: enable and finish the entitlements

**Area:** Server
**Kind:** ENABLE (config) + FIX (stock Cosmic gaps)
**Effort:** M
**Depends on:** -
**Status:** open

## Today

- **Code:** stock Cosmic. Family, FamilyEntry, FamilyEntitlement and the handlers `OpenFamilyHandler`,
  `OpenFamilyPedigreeHandler`, `FamilyAddHandler`, `AcceptFamilyHandler`, `FamilySeparateHandler`,
  `FamilyPreceptsHandler`, `FamilyUseHandler` and `FamilySummonResponseHandler`.
- **Live config:** `USE_FAMILY_SYSTEM: false` (Cosmic default true).
  - The handlers return early, and `World` and `Server` skip the family load and the daily rep reset.
  - The v83 Family button does nothing.
  - Rep tuning is present: `FAMILY_REP_PER_KILL: 4`, `PER_BOSS_KILL: 20`, `PER_LEVELUP: 200`,
    `FAMILY_MAX_GENERATIONS: 1000`.
- **Live data:** `family_character` has 0 rows.
- **Gaps in stock Cosmic:** in `FamilyUseHandler`, the six EXP and drop entitlements (1.5x / 2x, self and party, 15
  and 30 minutes) and Family Bonding are `//not implemented`. Those choices give nothing. Only Family Reunion
  (teleport) and Summon work.
- **Bots:** `family_character.cid` has a foreign key to `characters(id)`, so bots cannot be family members.

## What

- **Turn it on for test:** `USE_FAMILY_SYSTEM: true`. Check boot (`Server` loads families), pedigree, junior
  invites, precepts, rep gain from kills and levels, the daily reset, separation, Reunion and Summon.
- **Implement the buffs:** the six EXP and drop entitlements, as timed rate multipliers on the character (and party
  members for the party versions).
  - Use the existing coupon-rate plumbing (`Character` rate fields), so they stack like coupons
    (`USE_STACK_COUPON_RATES`).
  - Spend the rep through `useEntitlement`.
- **Family Bonding:** implement it (a party EXP bonus for family members) or hide it.
- **Bots (later, optional):** bots as juniors of players ("a newbie asks you to be their senior"). This needs the
  foreign key dropped or a botstore fallback like ticket 49. Not part of this ticket.

## Done when

- On test, two players form a family; rep accrues and resets daily, and every entitlement does what its tooltip
  says.
- Then live, with the owner's go. The config key ships with the jar (release rule).

## Risks

- **EXP-rate buffs stack with 3x EXP and the rebirth rates:** cap the total and agree the numbers with the owner.
- **Family rep updates on every kill write to the DB:** check that the batch save is used, not one write per kill.
