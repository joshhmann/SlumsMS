# 111: Slums Stamps, a daily check-in currency replacing donor coins and vote points

**Area:** Server / economy / NPCs
**Kind:** FEATURE, 1.6.1 (owner 2026-10-27)
**Status:** decided, not started

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

## To grill before building
- Per account or per character (alts): which one checks in, and where stamps live (account column like votepoints,
  or an ETC item that can be traded or not).
- Tradeable? (An item would make stamps a market good on Slums Market; an account balance keeps them personal.)
- Prices: Donor NX items cost 1 Billion Coin and Donor Shop items 10 today; at 12 stamps a week those need a reprice
  (e.g. cosmetics 5-20, stamps/QoL 30-60). Conversion rate for held Billion Coins and vote points.
- Where you see it: a check-in message at login, a line in @points (renamed), the website account page.
- Streak details: the day boundary (UTC or PT), what counts as "played 10 minutes", whether the streak caps or the
  bonus repeats every 7 days, a catch-up for one missed day.
- Bots: regulars could show stamp cosmetics but never earn or spend stamps.

## Build sketch
Migration: accounts.stamps, accounts.checkin_day, accounts.checkin_streak (or an item); conversion of votepoints and
held 4001253. A login hook plus a 10-minute playtime check; @points shows stamps and the streak. rooty.js donor
sections switch currency (a stamp balance check instead of an item count if it is an account balance). Remove
!givevp / vote points (or alias !givestamps). Tests for the day boundary, streak reset, conversion and double-claim
across channels. Patch notes + guide (shops, commands).
