# 111: Slums Stamps, a daily check-in currency replacing donor coins and vote points

**Area:** Server / economy / NPCs
**Kind:** FEATURE, 1.6.1 (owner 2026-10-27)
**Status:** grilled, ready to build in 1.6.1

## Why
The donor and vote currencies are dead: the Billion Coin (4001253) has no source in game (GM-given only), so the
Donor NX cosmetics and Donor Shop items in Rooty's catalog (rooty.js sections "Donor NX", "Donor Shop") are out of
reach; vote points (accounts.votepoints, @points, !givevp) have no voting site and nothing spends them. Root Coin
(Perfect Pitch 4310000: drops capped at 3, JQ rewards, NPC trades) and the Maple Leaf already work and stay.

## Owner decisions (2026-10-27)
- **Name:** Slums Stamps.
- **Earn:** a daily check-in: 1 stamp a day for logging in and playing about 10 minutes, plus a streak bonus (day 7:
  +5, so a full week is 12). Missing a day resets the streak, never the stamps.
- **Replace:** the Billion Coin and vote points. Donor sections are priced in stamps; Billion Coins and vote points
  already held convert to stamps. Root Coin and Maple Leaf unchanged.
- **Release:** 1.6.1.

## Owner decisions, grill 2026-10-27
- **Holder:** one balance per account, shared by its characters; one check-in a day per account (alts don't multiply).
- **Not tradeable:** stamps are personal. What they buy follows the item's own trade rules.
- **Day:** rolls over at midnight Pacific.
- **Played:** 10 minutes online that day, summed across the account's characters; AFK counts.
- **Streak:** +5 on every 7th day (repeats weekly); one missed day a week is forgiven, two reset the streak (stamps
  are never lost).
- **Prices:** Donor NX cosmetics 6 stamps; Donor Shop (High-Five Stamps 5590000/5590001) 40 stamps. A full week is 12.
- **Conversion:** each Billion Coin (4001253) held and each vote point converts to 6 stamps (what it bought before:
  one cosmetic). Billion Coins in inventories and storage are removed in the migration.
- **Shown:** `@stamps` (balance, streak, next bonus; replaces the vote line in @points), Rooty's menu header, the
  website's account page; and one quiet yellow chat line when the day's stamp lands: "+1 Slums Stamp (streak 4/7)".
- **Staff and GM accounts** never earn stamps (as with event winners); QA accounts neither.
- Bots never earn or spend stamps (they may wear the cosmetics).

## Build sketch
Migration: accounts.stamps, accounts.checkin_day, accounts.checkin_streak, accounts.checkin_minutes; convert
votepoints and held 4001253 (inventories and storage) at 6 each. A login hook plus a 10-minute playtime check; @points shows stamps and the streak. rooty.js donor
sections switch currency (a stamp balance check instead of an item count if it is an account balance). Remove
!givevp / vote points (or alias !givestamps). Tests for the day boundary, streak reset, conversion and double-claim
across channels. Patch notes + guide (shops, commands).
