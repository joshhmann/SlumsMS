# 11: Real scrolling and gachapon

**Area:** Real bots  
**Depends on:** 10  
**Status:** open. Player scrolling was restored in v1.4.0. Bot scrolling is built on branch `economy-bot-scrolling-142` (below). Gachapon is not built.

## What

Real success, fail and destroy outcomes using items bots own; scroll valuer from nutnnut.

## Done when

Scroll results appear in the ledger; destroyed items are gone.

## 1.4.2: bots scroll their own gear (branch `economy-bot-scrolling-142`)

Bot scrolling is built. Gachapon is not.

- **Where it runs:** a grinding bot's town stop and store browse (`TrainingBot` IN_TOWN and SHOP_DWELL) call
  `BotScrolling.townTick`. It takes one step every 4-8 s (60 s when it has nothing to do), in package
  `soloMapling.ArtificialPlayer.BotScrollSystem`.
- **Getting scrolls (`ScrollBudget`, the bot's own mesos):**
  - It loots them, as before.
  - It buys at NPC shops on the map it stands on. In v83 the weapon stores sell the 100% scrolls (35k armour,
    70k weapon): Ellinia 1032103, Kerning 1052104, El Nath 2022002, Ludibrium 2041016, Leafre 2080003. Listings
    under 1,000 mesos (the GM shops' 1-meso scrolls) are ignored. It buys at most 3 of each, and only what fits
    its gear.
  - It posts FM wants (`ScrollWants`): the scroll its appetite would pick, at most the item pool value, for 30
    minutes. FM shopper bots buy a wanted scroll when they sit in a shop that lists it at or under that price.
    Players' shops go first (the 1.4.2 discovery path) and 56A still applies (1.0x ceiling, 5m per seller account
    per day). The wanting bot pays the exact price from its own mesos; the shopper's `FMBotWallet` allowance is
    never used for this. Every failure moves the mesos back.
  - **Budget per bot per day:**
    - It keeps a reserve of `100k + 5k x level`.
    - It spends at most `15k x level` a day (`-Dsolomapling.botScrollPerLevel`).
    - No single buy is over 25% of what it holds above the reserve.
    - It buys at most 12 scrolls a day, 3 of them from real players.
    - It uses at most 20 scrolls a day (`-Dsolomapling.botScrollRollsPerDay`).
    - `-Dsolomapling.botScrolling=false` turns all of this off.
- **Scrolling:**
  - The roll is `ItemInformationProvider.scrollEquipWithId`, the same as a player's. Fit is checked with
    `ScrollHandler.canScroll` (now public) and the scroll's `req` list.
  - It scrolls worn gear first (weapon first), then the equips in its bag, and stops when the slots run out.
  - Appetite is fixed per bot id: 40% careful, 45% normal, 15% gamblers.
    - Careful: the surest scroll; a 10% only with a white scroll and only on gear it does not wear.
    - Normal: the best expected gain, a 10% about a quarter of the time, a dark scroll 3% of the time and never
      on worn gear.
    - Gamblers: the biggest stats, a dark scroll 10% of the time, no white scrolls.
  - Careful and normal bots spend a white scroll they own on 10%/30% scrolls.
  - A curse destroys the item where it is: `BotHoldings.destroyHeldItem` now takes a worn equip off the body too.
    The ledger debit makes the store write the loss.
  - A worn item's new stats are re-derived (`equipChanged`).
  - The map gets the real `getScrollEffect` packet.
- **Market loop:**
  - `BotGear.review` after a pass puts a better piece on.
  - A finished equip it does not wear is listed in a bot FM merchant (channel 1) at its `EquipValuation` price
    (`BotConsignment`). When a player buys it, the bot is paid the price after the fee. That is a transfer, not
    metered by ticket 15. FM shopper bots never buy these listings.
  - Unsold listings come back after 6 h.
  - A finished item with 0 passes and no value is tossed.
  - Listings and money owed live in memory: a restart or a closed shop costs only the bot.
- **Megaphones:** only for real outcomes, through `WorldEventMegaphones.offerEvent`. That gives the server-wide
  gate, a 10-minute cooldown per key and the MegaphoneDialogue pack (new nodes `ScrollPerfect` and `ScrollBoom`),
  plus one per bot per 30 minutes.
  - A perfect item of 5+ slots: "passed 7/7 steelguards omg".
  - A boom of an item that had passed 3+ scrolls: "boomed my 5/7 work gloves lol".
- **Ledger:**
  - `!botyield` now shows:
    - scrolls bought per source with the mesos: NPC and bot shops are sinks, players' shops are transfers;
    - passes, fails, booms and white scrolls used;
    - the value of gear destroyed and megaphones sent;
    - results put on, listed and sold, and tossed.
  - Every roll is also a `[Scroll]` log line.
  - `!botscroll [<bot> [now [appetite]]]` shows one bot's budget and its next roll, or makes a roll now.
- **QA:** `!qa run sc-2 [careful|normal|gambler] [equip id]` (see docs/ops/qa-suite.md).
- **Economy estimate:**
  - Bot spending on scrolls is capped at about 15k x level per bot per day, and only above the reserve. NPC
    scrolls are only sold in five towns.
  - Most bots spend 0-400k a day. An upper bound with the 2026-10-20 population (1,515 bots, average level ~60)
    is ~1.4b a day.
  - In practice it is far less: a bot buys only for gear it holds with open slots, at most 12 a day.
  - Most of it is a sink, against 7.8b a day of bot mob-meso intake.
  - Payments to real players are at most 3 scrolls per bot per day, inside 56A's 5m per seller account per day.
  - Nothing here mints mesos. A restart only resets in-memory counts, which can only let bots spend more of their own.

## 1.5.0: gachapon redesign (branch `economy-gacha-150`)

Owner decision 2026-10-21: "fun + fair". Built:

- **Pools per town, data-driven:** `src/main/java/soloMapling/Gachapon/gachapon-pools.yaml` (12 machines + a shared
  list, 1,751 entries). Seeded from the v83 town lists (the old `server/gachapon/*.java` classes, now deleted) and
  MapleRoot's donor chair machine (the old `scripts/npc/gachapon.js`), cleaned: no GM scrolls, boss summoning sacks,
  return scrolls, pet-equip scrolls, expiring or quest items, untradeable post-v83 knuckle/gun scrolls, NPC-shop
  beginner weapons, cash effects, Korean-only names, a scroll whose name and data disagree (2040507), duplicates.
- **Tiers:** common 70%, uncommon 24.5%, rare 5%, jackpot 0.5% per ticket; items inside a tier by weight (Chaos and
  White Scroll w40 in the jackpot tier). The odds table for the notes: `docs/release/1.5.0-gachapon-odds.md`.
- **Tool:** `python3 tools/gachapon/gachapon_pools.py validate` checks every id against the WZ (exists, named, not
  quest/expiring/GM/boss sack, stack size, scroll % matches its data); `odds [town] [--markdown] [--items]` prints
  the odds. In game `!gachasim <town> [n]` simulates, `!gachasim reload` re-reads the file. `GachaPoolsFileTest` checks
  the shipped file and every id against `String.wz` in the suite.
- **Machines:** the NPC script (`gachapon.js`) and the Remote Gachapon Ticket (`gachaponRemote.js`) roll through
  `GachaMachine`; a ticket is taken only when the prize fits. `@gacha <town>` (players) lists prizes and odds.
- **Notice:** rare and jackpot wins send the v83 yellow item notice server-wide (`GachaAnnouncer`): one per 20 s
  server-wide, one per character per minute, players queue (5 deep, 5 min), bots must get the bot megaphone budget
  (`BotMegaphoneGate`) and are announced at most once per 15 min; a player's notice also claims that budget.
- **Bots play:** `BotGacha` from the town stop (`BotWealth.townTick`): a bot over its wealth ceiling buys a ticket
  each step (up to 11 a day); a "gacha fan" (about a third of bots) under it plays at a 2% chance per step, at most
  2 a day, keeping half its ceiling. 120 bot tickets an hour world-wide. A ticket costs 1,000,000 mesos, burned.
- **Henesys Market show bots** (`GachaBot`) now pop a prize from the Henesys pool instead of always Stormcaster
  Gloves, and cheer real rolls on their map. The box retirement from `perf-map-entry-142` is untouched.

### 1.5.0 follow-up (owner answers 2026-10-21)

- **Machines in all 12 towns:** migration `013-gachapon-machines.sql` (+ rollback) adds `plife` rows (worlds 0 and 1)
  for 9100101 Ellinia, 9100102 Perion, 9100103 Kerning City, 9100104 Sleepywood, 9100105 Mushroom Shrine and 9100109
  New Leaf City, on the ground near the shops and clear of portals (`GachaponMachinesMigrationTest` checks each spot
  against the WZ footholds and portals). The client needs no change: it draws server-spawned NPCs from `Npc.wz`
  (`docs/release/1.5.0-gachapon-machines-client.md`).
- **Bots walk to the machine** when it stands on their map (`TownLoiter.settle` near it), then play on a later step;
  if moved off again within 5 minutes they play from where they stand.
- **Show bots mint nothing:** their pop's drops were already conjured (bots that loot them are credited nothing); now
  whatever is still on the floor 12 s after a pop vanishes (`CustomReactor.sweepShowDrops`), before the 15 s owner
  protection would let a player take it.
- **Remote slot 11 = Ludibrium** stays; checked on a real client in QA `ga-3`.
- Ceiling, chairs as jackpots and the 1m bot ticket: kept as built.

## 1.5.0 rc3: keeping bot-made godlies rare (branch `economy-trade-150`)

From the gear audit in docs/release/1.5.0-economy-trade.md. Every number is in economy.yaml under `scrolling.`:
- **Appetites:** 40% careful, 50% normal, 10% gamblers (was 15% gamblers).
- **Normal bots:** take a 10%/30% scroll 10% of the time (was 25%).
- **White scrolls:** no longer spent on long shots, so careful bots never take a 10%.
- **Daily cap:** after 5 godly results world-wide in a day, every bot sticks to 60%+ non-dark scrolls.
- **"ScrollPerfect" megaphone:** only for a godly result.
