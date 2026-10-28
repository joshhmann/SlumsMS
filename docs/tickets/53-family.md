# 53: Family system: enable and finish the entitlements

**Area:** Server
**Kind:** ENABLE (config) + FIX (stock Cosmic gaps)
**Effort:** M
**Depends on:** -
**Status:** done for 1.6.1 (branch 161-family), on on TEST; on for live through docs/release/1.6.1.conf (SERVER_SET
USE_FAMILY_SYSTEM=true, migration 041). Owner 2026-10-28: on, with all six perks; regulars may be your juniors.

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

## Decision (Owner decision 2026-10-25)

Turn the family system on (USE_FAMILY_SYSTEM) and implement the six stubbed entitlements. Replaces the "coming soon" button. Target: 1.6.0.

## Done (1.6.1, branch 161-family)

- **Switch:** `USE_FAMILY_SYSTEM: true` on TEST (config.yaml) and in 1.6.1.conf SERVER_SET. Rep tuning unchanged.
- **Boot load:** `Server` never called `Family.loadAllFamilies` (lost in the fork), so a restart forgot every family.
  Restored behind the switch. Bot rows load from `botstore` (id - 1,000,000,000).
- **Entitlements** (`FamilyBuffs`, `FamilyUseHandler.applyBuff` / `useBuffEntitlement`): the eight EXP / drop
  choices give what their tooltips say: 1.5x or 2x, 15 or 30 minutes, self or party (party = members online on your
  channel; alone = you). Applied to mob EXP (`Monster.giveExpToCharacter`, personal and party share) and to the
  drop rate (`MapleMap.dropFromMonster`; a fraction such as 1.5x of 1x is rolled per kill). They stack with coupons
  and world rates as a coupon does (`USE_STACK_COUPON_RATES` is about coupons only); Family buffs don't stack with
  each other: the higher rate wins, never above 2x (the ticket's cap risk). They last through a relog, not a restart.
  Bots are never given one. The "nullified during an EXP event" line in the tooltips is not enforced.
- **Family Bonding:** 2x EXP and drop for 30 minutes to the user and every player below them in the pedigree, when
  at least 6 (`-Dslums.family.bondingMin`) members below are online; bot juniors count. Refused (nothing spent) if not.
- **Costs and cooldowns:** stock v83 / Cosmic: Reunion 300, Summon 500, Drop 1.5x 700, EXP 1.5x 800, Bonding 1,000,
  Drop 2x 1,200, EXP 2x 1,500, Drop 2x 30 min 2,000, EXP 2x 30 min 2,500, Party Drop 4,000, Party EXP 5,000 rep;
  each once a day (reset at midnight by `FamilyDailyResetTask`). A refused use spends nothing.
- **Bots as juniors** (`BotFamily`; the "later, optional" item above, owner said yes): a regular answers a player's
  Add Junior after 10-60 s: yes (80%) only to a player it knows, is partied with or just talked with (3+ lines); never
  to a stranger, when wary, or when already in a family. Bots never have juniors and never invite. After a real chat
  with a known player a bot may whisper an offer to be their junior: once a week per pair, once a day per player
  across bots, 3 an hour a world, a 10% roll, only if the player has room, is at least its level and within 20 levels,
  and the social gate allows a whisper. A bot junior gives its senior at most 300 rep a day
  (`-Dslums.family.botJuniorDailyRep`); saved when it logs out. `-Dslums.family.botsOff=true` turns bot juniors off.
- **Migration 041-family-bot-juniors:** drops the `characters(id)` foreign keys on `family_character.cid` (and
  `family_entitlement.charid` if present), kept in `family_041_fks`; the rollback deletes bot rows and restores them.
  Tested apply x2 / rollback x2 on MySQL 8.0 (`FamilyBotJuniorsMigrationIT`).
- **Also fixed:** an entry going offline cached nothing (stock passed the null), so the pedigree showed a login-time
  level; the use handler no longer throws on a player with no family or a bad entitlement index.
- **Checks:** auto-QA `t-fy-1` (a bot accepts, a junior's kill gives rep, 1.5x drop and 2x EXP for their rep, a second
  use refused, the family dissolved); `!qa run fy-1` stages a bot junior and 20,000 rep so every entitlement can be
  clicked in the client. Not yet checked on a real client: the Family window and the entitlement clicks.
- **Batch save risk:** rep is saved with the character's save (stock), not per kill; bot juniors save on logout.
