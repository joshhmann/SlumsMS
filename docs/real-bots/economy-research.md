# Bot economy research (ticket 38)

First-draft research for [ticket 38](../tickets/38-bot-economy-behaviour.md). It covers how v83-era players (GMS
2007-2010, before Big Bang) made and spent mesos, how the Free Market worked, and what changes on a high-rate
server. It ends with concrete rules for bots. It follows on from the
[leveling path research](leveling-path-research.md). Nothing reads this yet, and no code changed.

## How this was built

- **Sources:** web research over era guides and v62-v83 private-server forums. Each claim names its source code;
  codes are listed under [Sources](#sources).
  - The best period sources are Ayumilove's 2008 meso guide and a 2011 MapleSEA merchanting blog.
  - Most numbers come from MapleRoyals forum threads (2014-2025). Royals is a v62-v83-style server, so its
    methods and price *relationships* mirror v83, but its absolute prices are inflated by a decade of play.
  - Many classic sites are unreachable, as for the leveling research: Basilmarket, Sleepywood and Southperry
    archives (archive.org is blocked for the fetcher), StrategyWiki (403), the Fandom wiki (402), the MapleLegends
    forum (DNS failure) and the Royals price-guide site (DNS failure). A few rows rely on search-result snippets
    of threads that could not be opened; those are marked *(snippet)*.
- **Server facts:** read from code and config, not from guides.
  - Rates come from the live MapleRoot config, read-only over SSH:
    `root@192.168.0.164:/opt/mapleroot/MapleRoot Full Repack/Server/config.yaml`. Nothing on that host changed.
    The repo's own `config.yaml` has `worlds: []`, so the live file is the one that counts.
  - Mechanics (trade tax, Owl of Minerva, Pickpocket, Meso Up, Fredrick) come from this repo's Cosmic code.
  - Star prices come from the Cosmic WZ data (`Item.wz/Consume/0207.img.xml`).
- **Labels:** *CON* marks common knowledge that many guides repeat with no single source. *EST* marks a working
  estimate made for this doc; it is a starting value for tuning, not a fact.

## Headline findings

1. **SlumsMS is a 3x EXP / 10x meso / 4x drop / 3x boss-drop world** (Scania, live config). Meso rate is the big
   one. It multiplies mob meso drops and quest mesos (`USE_QUEST_RATE: true`), but **not** NPC sell prices, NPC
   shop prices or Pickpocket mesos. So compared with v83:
   - Raw mob mesos dominate income. NPC-selling equips, a third or more of farming income on a 1x server
     (RMMG, RMAP), becomes a small share.
   - NPC consumables (pots, stars, arrows) are about 10x cheaper relative to income. Running out of pots stops
     being a real constraint above the first job.
   - A character earns about 10/3 ≈ 3.3x the v83 mesos per level gained (10x mesos, but levels come 3x faster).
     It finds about 4/3 ≈ 1.3x the items per level. So FM prices in mesos should sit about **2.5-3.3x v83
     levels** (EST), and the price *ratios between items* should stay close to v83.
2. **Price ratios are stable even when absolute prices inflate.** On Royals, White and Chaos Scrolls went from
   200-270M (2014) to about 300M (2016) to 510-550M (2020-2022), but they stayed level with each other, and the
   10%/60%/30% ordering held (RSCR14, RINF, RWSCS). Bots should store prices as ratios to a reference basket and
   scale the basket, not hard-code mesos.
3. **What sold is well known.** Cosmic hard-codes the v83 Owl of Minerva default "top searched" list: Work Gloves,
   Steely Throwing-Knives, Ilbi Throwing-Stars, Owl Ball Mask, Pink Adventurer Cape, Claw 30%, White Scroll,
   Helmet 60% ACC, Maple Shield and Gloves ATT 60% (`ItemId.getOwlItems`). That list plus the scroll price table
   below is a good first merchant stock list.
4. **Mages were the meso generators of the late game.** Leech selling (bishops and archmages on Skeles, Petris,
   Ulu) is the single biggest income on Royals, and Royals staff blame it for inflation (RINF, RNEWB). Bots that
   sell leech to real players would be a large meso faucet *to players*; that must sit under ticket 15's caps.
5. **Only two things create mesos: mob drops and NPC sales** (plus quest rewards). Player-to-player trades and
   shop sales just move mesos (MLSINK, RSINK). NPC purchases, taxes and fees destroy them. For a 1,200-bot world
   this gives one simple rule: every meso a bot gains from a mob or NPC must be matched over time by a meso a
   bot (or a player) spends at an NPC, or the economy inflates.
6. **Today's bot Free Market mints its stock.** `ArtificialFreeMarket` builds merchant shops from static price
   tables (`itemPool/itemConfig/*.yaml`), scaled by a sine "market index", a room premium and the weekday. Items
   do not come from bot loot, and prices never react to real trades. A player buying from these shops destroys
   mesos (a sink) but gains minted items (a faucet of goods). Ticket 38 keeps this as a fallback and moves real
   bots onto their own loot and observed prices.
7. **Pickpocket is weak here.** Cosmic spawns Pickpocket mesos at a skill-capped amount with no meso-rate
   multiplier (`AbstractDealDamageHandler`), while mob drops get 10x. The classic "Chief Bandit farms mesos" plan
   is therefore weaker on SlumsMS than in v83. Hermit's Meso Up (`MESOUP`) multiplies the base drop *before* the
   rate, so it keeps its value.

## Server calibration (live MapleRoot config, Scania)

| Setting | Value | What it means for the economy |
|---|---|---|
| `exp_rate` | 3 | Bands pass 3x faster; less time to farm each band |
| `meso_rate` | 10 | Mob and quest mesos x10; NPC prices unchanged |
| `drop_rate` | 4 | Scrolls, ETC, equips, ores x4 supply |
| `boss_drop_rate` | 3 | Boss drops (Zakum helm, HT necklace pieces, books) x3 |
| `quest_rate` | 1 (with `USE_QUEST_RATE: true`) | Quest mesos follow the world meso rate (x10) |
| `PQ_BONUS_EXP_RATE` | 10.0 | PQs are strong EXP; PQ item rewards are not multiplied |
| `PARTY_BONUS_EXP_RATE` | 2.0 | Party play is favoured |
| `USE_ENFORCE_MERCHANT_SAVE` | true | Merchant stock is saved on every change |
| `USE_BUYBACK_WITH_MESOS` | false | No meso buyback sink |
| `STARFORCE_MAX_MESO_COST` | 50,000,000 | MapleRoot star force is a large meso sink at the top end |
| `REBIRTH_*_RATE` | 3/2/1/1 | Rebirths reset level, so mesos carry over and accumulate |

Cosmic mechanics that matter (this repo's code):
- **Trade and shop tax** (`Trade.getFee`), taken from the seller's mesos on trades, personal stores and hired
  merchants: 0% under 100k, 0.8% from 100k, 1.8% from 1M, 3% from 5M, 4% from 10M, 5% from 25M, 6% from 100M.
  This is a real meso sink; bot sales pay it too.
- **Fredrick** holds unsold merchant goods; after 100 days they are gone (`FredrickProcessor`).
- **Owl of Minerva** search exists, and the world tracks what players search for (`UseOwlOfMinervaHandler`).
  That search log is free demand data for bots.
- **Star prices** (WZ `price`, the NPC sell value; recharge cost per star in brackets): Subi 250 (0.3), Wolbi
  500 (0.4), Mokbi 1,000 (0.5), Kumbi 1,500 (0.6), Tobi 2,500 (0.6), Steely 10,000 (0.7), Ilbi 20,000 (0.8),
  Hwabi 25,000 (1.0). A full 800-star Ilbi recharge costs about 640 mesos. That is why Ilbis were a one-time FM
  purchase, not an ongoing cost.

## 1. How players made mesos, by band

Meso/hour figures from Royals are at 1x meso and late-server prices. Use them only to rank methods, not as
targets.

| Band | Method | Why it worked | Who | Source |
|---|---|---|---|---|
| 1-30 | Kill and loot; NPC everything except scrolls | Low drops; anything sells to NPC | All | AYU08, CON |
| 1-30 | Sell found scrolls on FM, never to NPC | Even a 10% scroll beats its NPC price | All | AYU08, RSCR14 |
| 21-30 | Kerning PQ | Bonus can give scrolls (10% Overall DEX "1M+") | All | MS11 |
| 30-50 | Monster Carnival PQ | Maple Coins forge Spiegelmann necklaces worth "several hundred thousand" | All | MS11 |
| 30-70 | Ore and crystal farming for quest buyers | Quests need 60+ ores; e.g. 60 Sapphire Ore at 10k each for a 60% Overall scroll quest | All | AYU08 |
| 35-70 | Quest scrolls (e.g. Subani's Legacy, a 60% Glove ATT) | Quest rewards resold on FM | All | MS11 |
| 50-70 | Coolie Zombie NPC farming | Equip drops for NPC, ~10-11m/hr on Royals | One-hit attackers | RMAP |
| 50-70 | Gold Zombie Teeth (Dead Mine) | 30 teeth per Zakum prequest; ~10/hr; always demanded | All, clerics best (undead) | MS11, RMMG |
| 51-70 | Ludibrium Maze PQ | Potion rewards NPC'd for "several million" | All | MS11 |
| 50-90 | Scroll farming (Drum Bunnies, Cold Eyes, Zombie Mushrooms) | Good scroll drops | All | MS11 |
| 50+ | Sell leech | Priests/bishops on undead (Skeles, Petris); archmages on Ulu | Mages | RNEWB, RINF |
| 70 | HP quest set (Water from the Spring of Youth) | Full set sold as a bundle; honey ~5m each | Fast killers | RMMG |
| 70+ | Silver Mane / mount set ETC | Set ETC always demanded | Fast killers | RMMG |
| 70+ | Leprechauns, Sakura Cellions | High mesos per kill (800-2,000 and 200-300) | Mappers | MS11 |
| 70+ | Chief Bandit Pickpocket / Meso Explosion | Mesos per hit; Explosion recycles them | Bandits | MEOW-PP |
| 70+ | Hermit Meso Up in a party | Party meso drop buff | Hermits | Cosmic code |
| 40-120 | Amoria PQ (Onyx Apples) | Apples always in demand; also used as payment | Married chars | RMMG, RNEWB |
| 50+ | Zakum runs, sell helm loot rights | Buyers pay 50-80m (Royals) to loot a helm | Bossers | RZAK *(snippet)* |
| 120+ | Papulatus, Horntail, skill/mastery books | Book drops (Hurricane 30 ~20-25m on Royals) | Bossers | RMB *(snippet)* |
| Any | FM flipping (merchanting) | Buy under average, resell; scrolls first, big items later | Traders | AYU20, MS11 |
| Any | Buy cheap in PQ lobbies, resell in FM | PQ players don't take time to sell | Traders | AYU20 |
| Any | Gachapon | Mushroom Shrine/NLC: CS, WS, Elemental wands | NX spenders | RNEWB |
| Any | Services: fame, Mystic Door, HB, SE | Small daily income | High-level | MS11 |
| Any | Maker | Crafting; no profit numbers found | Maker users | - (no source) |

Notes:
- **Target income.** Ayumilove (2008) advised at least 1M/hr at level 70+ (1x GMS). Worked example: Red Drakes drop
  300-450 per kill, but an I/L's Cold Beam costs about 192 mesos of MP pots per kill, so net is ~100/kill, a
  "don't farm this" case (AYU08). Bots should weigh pot cost against drop value the same way.
- **NPC selling was real income.** On Royals, NPC equips are 30-50% of a farming hour: Petris 120-130m/hr with
  30m from NPC, Skeles 40-50m of 100m from NPC (RMAP, RMMG).
- **Quest ETC.** Players farmed ETC that quests need (teeth, ores, set ETC, hearts) because other players pay
  more than the NPC price to skip the grind (RMMG). "Medicine with Weird Vibes" at 3k each was cheaper to buy
  than to farm, so buying parts of a set is normal.
- **Shadow Partner** does not drop mesos. It made Hermits kill faster, so they earned more per hour (MEOW-SP).
- **Pirates** had no meso skill in v83 (CON).

## 2. What players spent on

| Stage | Main spend | Notes | Source |
|---|---|---|---|
| 1-30 | Pots, then 1st-job weapon and armour | NPC armour felt expensive (a lv35 hat ~160k) | RMESO |
| 1-30 | Ammo: arrows, Subi/Wolbi | Thieves and archers burn ammo | CON |
| 30-70 | Pots (largest), NPC or FM gear, first 60% scrolls | Glove ATT, weapon 60%, overall stat scrolls | RSCR14, CON |
| 30-70 | Work Gloves, Ilbi or Steely set | Owl list top items; Ilbi a one-time buy | Cosmic Owl list |
| 70-120 | Scrolled gear, mount (Hog), Zakum prequest teeth, Zakum helm rights | Helm loot 50-80m (Royals) | RZAK *(snippet)* |
| 120+ | CS/WS, mastery books, perfect gear, HT, merchant permits | WS/CS act as the currency; perfect gear priced "in scrolls" | RMESO, RINF |
| Any | Travel, AP resets, Smegas, chairs | Convenience | RMESO, RSINK |

Rough shares of spend by stage, for tuning bots (*EST*, built from the table above and the 10x calibration):

| Stage | Pots/ammo | NPC gear | FM gear and scrolls | Services/bossing | Saved |
|---|---|---|---|---|---|
| 1-30 | 50% | 35% | 5% | 0% | 10% |
| 30-70 | 30% | 15% | 35% | 5% | 15% |
| 70-120 | 15% | 5% | 45% | 15% | 20% |
| 120+ | 10% | 0% | 50% | 20% | 20% |

At 10x mesos the pot share will be lower in practice. Bots should keep the *order* of priorities rather than
the exact shares: never short of pots, then the next gear upgrade, then scrolls, then luxuries.

## 3. Free Market behaviour

### Where and how

- **Rooms.** FM 1-6 on the first channels sold fastest and at the best prices; door spots in FM 7-9 were next;
  edge spots beat top platforms (MS11, RNEWB). The existing code already models this
  (`FMEconomyManager.HOT_ROOMS`, `FMRoomPremiumCalculator`).
- **Shops.** A Personal Store or Hired Merchant holds 16 items, open 24 hours (MS11). Players filled several
  shops' worth of stock before opening one (RNEWB). After a server restart, spots are free, so people raced for
  them (MS11, RNEWB).
- **Other channels.** Players also sold through Basilmarket/Sleepywood forum auctions, by shouting or Smega in
  FM, and directly to quest buyers (AYU08).

### What sold, and price relationships

Royals 2014 scroll price table (RSCR14), at the time WS ≈ 200-220M and CS ≈ 220-270M:

| Scroll | 10% | 60% | 30% | 70% |
|---|---|---|---|---|
| Glove ATT | 2M | 6M | 12M | 8M |
| Glove M.ATT | 4M | 15M | 25M | 10M |
| Claw ATT | 1M | 5M | 20M | 4M |
| 1H Sword ATT | 1.5M | 2M | 12M | 1.5M |
| Wand M.ATT | 1M | 0.5M | 15M | 0.75M |
| Helmet INT | 2M | 22M | 9M | 10M |
| Helmet DEX | 1M | 15M | 7M | 7M |
| Overall INT | 5M | 25M | 30M | 15M |
| Overall DEX | 1.5M | 6M | 8M | 4M |
| Shield M.ATT | 6M | 65M | 30M | 25M |
| Cape STR | 250k | 250k | 2M | 250k |
| Shoe Speed | 500k | 500k | 1M | 500k |
| 1H/2H Axe, Blunt ATT | - | 500k | 2-3M | 500k |

Patterns bots can use:
- **30% (dark) scrolls usually beat 60% and 70%** for weapons and gloves; 10% is usually the cheapest, but not
  always (Face 10% 2M vs 60% 20-25M).
- **Mage stats carry a premium.** INT and M.ATT versions cost 2-5x the STR versions.
- **Junk scrolls** (STR cape, speed shoe, axe/blunt, HP/MP) are worth little. They still beat their NPC price;
  some buyers use them to practise (AYU08).
- **WS and CS track each other** and work as the real currency at the top (RINF, RWSCS, RMESO).
- **Onyx Apple ≈ CS/42** on Royals in 2023 (about 10m each against a 420m CS) (RSTEEL *(snippet)*).
- **Stars:** Ilbi sets 750k, Steely sets 400k in a 2023 Royals listing (RSTEEL *(snippet)*), against an NPC value
  of 20,000 and 10,000. Players pay 20-40x NPC value because Ilbis cannot be bought at NPC shops.
- **Elixir / Power Elixir** are NPC-sold in some towns and drop from gachapon. The current bot tables price them
  at 4,000 and 9,500 (`useables.yaml`), close to NPC cost; they sell as a convenience.
- **Equips.** "Clean" (unscrolled, all slots) and above-average stats sell; average equips go to the NPC. Players
  kept anything 1-5 points above the normal max ("godly") and NPC'd the rest (RNEWB). Scrolled gear sells for
  roughly the value of the scrolls that passed, not their cost with failures (RMESO).
- **Leech** priced per hour: one-hit skele leech went 65m (2016) → 100-120m (2020) → 150m solo / 75m split
  (2025); Ulu 80-90m (RINF, RLEECH *(snippet)*). This is also a clean inflation gauge.

### Price discovery and merchant habits

- **Check prices first.** Walk FM, note each shop's price, average them, then buy anything clearly under average
  (AYU20). Use the Owl for items over 50m (RNEWB).
- **Buy at the reference price, sell at the FM price.** "Always buy scrolls/items for the basilmarket price, and
  sell for the FM price"; e.g. a 60% Overall DEX at 9-10M on Basil sold for 12-14M in FM (MS11).
- **Undercut by a little.** For a quick sale, price one item just under the lowest listing, or a bulk slightly
  under a similar bulk (RNEWB). Lower prices sell faster; buying out cheaper rivals only pays if you can resell
  higher (MS11).
- **Price endings.** "Price items ending in 9s" (MS11). The existing code already has `formatWithTrailingNines`.
- **Prices move slowly.** A scroll "might go up a few million" but does not double overnight (RSCR14). Daily WS
  and CS moves on Royals were within about ±4% (RWSCS). Throw out outliers (RWSCS).
- **Seller types** (MS11): pro sellers with firm prices; average sellers who haggle; "noob" sellers who accept
  80-90% of the low price; unsure sellers with random prices; gacha sellers who don't know prices.
- **Flipping.** Start with scrolls: liquid, high return on small money (e.g. buy 4M, sell 7M). Move to big items
  only with 100-150m behind you (MS11).
- **Market cornering** ("owl-chess": buy out a commonly traded item and reprice it) existed but needed about 10x
  the item's market value in reserve (RNEWB). Bots must not do this.
- **Shop layout.** Expensive godly gear at the top, scrolls in the middle, cheap items last (MS11).

### Slang bots should understand and use

| Term | Meaning | Source |
|---|---|---|
| B> / WTB, S> / WTS, T> / WTT | Buying, selling, trading | MEOW-G |
| c/o, a/w (or "s/b ... a/w"), RES | Current offer, autowin (buy-now), reserve | MEOW-G, RZAK |
| ea | Price each | MEOW-G |
| clean | Unscrolled, all slots free | MEOW-G |
| perf / godly | Perfectly scrolled / above max normal stats | RNEWB |
| CS, WS | Chaos Scroll, White Scroll | RNEWB |
| PC | Price check | CON |
| NPC (verb) | Sell to a shop NPC | RMMG |
| merch / merching | Flip items for profit; also a Hired Merchant | MEOW-G |
| leech, split/solo | Paid EXP service; shared or one buyer | RLEECH |
| 10k, 1m, 1b | Thousand, million, billion | CON |

## 4. High-rate servers

- **Inflation is the norm on private servers.** A server review puts MapleRoyals at about 2-6x the prices of
  MapleLegends (which runs 2x EXP, 1x meso, 1x drop) (MSPSES *(snippet)*). On Royals itself, WS/CS rose about 2.5x
  from 2014 to 2020, then flattened (RSCR14, RINF, RWSCS).
- **Where the mesos came from.** Royals players blamed high-level mages: ~50-70m/hr of drops while leeching, or
  ~240m/hr for four mages on Ulu (RINF). Equip drops on rich maps were also blamed (RSINK).
- **Sinks players proposed or got:** higher NLC % potion and star recharge prices (done on Royals in 2020), Owl
  fees (reported to remove ~2b early on), merchant fees, AP resets for mesos, gachapon tickets for mesos, and
  limited-time meso items (RINF, RSINK, MLSINK). Players rejected sinks that made rare items buyable, since that
  kills the grind (RSINK).
- **What stays valuable at high rates:** things with fixed or tight supply. WS, CS, top skill books, perfect
  gear, boss-only items and services (leech, boss runs). Items whose supply scales with drop rate (common
  scrolls, ore, ETC) lose value; NPC goods at fixed prices become trivially cheap (RINF, RMESO, and finding 1).
- **Non-meso currencies appear.** When mesos inflate, players price big items in WS/CS ("ws/cs @ 380m") or
  coins (RZAK *(snippet)*, RMESO). Bots should accept and quote WS/CS for very large deals.
- **For SlumsMS** (3/10/4/3): expect fast meso inflation from mob drops unless bot faucets are capped, and expect
  low-level scroll and ETC prices to stay low because 4x drops flood them. MapleRoot's own sinks (star force up
  to 50m a try, rebirth) help at the top but do nothing for the 1-120 economy.

## 5. Suggestions for bots

These feed [ticket 38](../tickets/38-bot-economy-behaviour.md). They build on tickets 09 (shop resupply),
12 (sell and trade) and 15 (yield caps and ledger).

### 5.1 A meso plan per band and job

A data file (YAML, like the leveling draft) gives each band a plan. Bots read it; the owner tunes it.

| Band | Income (weights) | Spend priority | Savings goal |
|---|---|---|---|
| 1-30 | Mob mesos 70, NPC sales 25, FM scrolls 5 | Pots, ammo, NPC gear | Next NPC weapon |
| 30-50 | Mob mesos 55, NPC 20, FM drops 15, PQ 10 | Pots, gear, glove/weapon 60% | Work Gloves, Ilbi/Steely (thieves) |
| 50-70 | Mob mesos 50, quest ETC 20, NPC 15, FM 15 | Gear upgrades, 60% scrolls | 3rd-job gear set |
| 70-120 | Mob mesos 45, FM 25, services 15, NPC 15 | Scrolled gear, mount, Zakum | Zakum helm |
| 120+ | Mob mesos 35, bossing 25, FM 25, services 15 | CS/WS, books, perfect gear | Next big item |

Job tweaks:
- **Mages (cleric line):** sell leech to real players on undead maps from 3rd job, within a per-day cap.
- **Thieves:** Hermits join farming parties for Meso Up; Chief Bandits use Pickpocket but get no rate bonus.
- **Archers and assassins:** budget ammo first; buy Ilbi or Steely once, from FM.
- **Warriors:** budget more for pots (they use more HP pots) (RINF).

### 5.2 Keep, list or NPC

A loot rule for every item a bot picks up:
1. **Keep** if it is an upgrade for the bot's own gear (ticket 10), a scroll for an item it is scrolling
   (ticket 11), ETC for a quest it is doing, or ammo/pots it uses.
2. **List on FM** if the expected FM sale beats the NPC price by a margin:
   `book_price × (1 − tax) × P(sell in 3 days) > NPC_price × 1.5`. In practice:
   - all scrolls (never NPC a scroll);
   - all stars and bullets above Kumbi;
   - ores, jewels and crystals;
   - quest-set ETC (teeth, hearts, mount sets, HP-quest items);
   - PQ currencies and rewards (Onyx Apples, Maple Coins);
   - skill and mastery books;
   - equips that are clean and godly (above max normal stats), or level-in-demand classics (Work Gloves,
     Maple Shield, Pink Adventurer Cape).
3. **NPC** everything else, in town on the bot's next resupply trip (ticket 12).
4. **Never drop** sellable items. If the bag is full, NPC the lowest-value stack first.

### 5.3 Price discovery from real trades

A shared **price book**: one row per item id.
- **Seed** from today's `itemConfig/*.yaml` prices, scaled by a world factor (start at 2.5x v83 per finding 1,
  *EST*).
- **Observe** real player activity only:
  - completed sales from player Personal Stores and Hired Merchants (the strongest signal);
  - meso trades of a single item (`Trade`);
  - asks in real player shops, by a regular scan (the bot's Owl), weighted below sales;
  - Owl searches as a demand signal (`getOwlSearchedItems`).
- **Ignore** bot-to-bot trades and sales from minted shops, so bots cannot feed their own prices.
- **Compute** a 14-day trimmed median (drop the top and bottom 10%). Move the book at most ±10% a day (RSCR14,
  RWSCS).
- **Floors and ceilings:** never below NPC price × 1.2 (a buyer could NPC it at a profit); never above 3x the
  seed without at least 5 real sales.
- **Basket index:** track WS, CS, Onyx Apple, Ilbi, Glove ATT 60%, Helmet INT 60%, Elixir, Power Elixir and an
  hour of leech. Show it on the dashboard (ticket 35). It is the inflation gauge.

### 5.4 Listing and buying behaviour

- **Ask price** = `min(book × persona_markup, lowest real player ask − small undercut)`, rounded to end in 9s.
  - Personas from MS11: *pro* (book +10%, firm), *average* (book, haggles), *casual* (book −10%), *noob*
    (book −15 to −20%, rare). The existing `OfferEvaluator` haggling stays.
- **Undercut rules:** undercut a real player by 1-3%, at most once a day per item. Never undercut another bot.
  Never go below book × 0.85 or the NPC floor.
- **Reprice** unsold stock −5% after 24 hours, down to the floor; then NPC or hold.
- **Room choice:** bots with the best stock prefer hot rooms; they leave some hot spots free for real players.
- **Buying:** bots buy from real players only what their meso plan needs (a glove 60% for their own gloves, an
  Ilbi set), at up to the book price. `BuyingMerchantBot`'s 80-90% offer stays for flippers, capped per day.
- **Big deals** over about 30x the price of a CS-equivalent may be quoted in WS/CS.

### 5.5 Merchant stock

- **Real stock first.** A bot merchant sells what bots looted (via 5.2), so FM supply follows the world's real,
  budget-capped drops.
- **Minted shops** (today's `ArtificialFreeMarket`) stay as filler while the bot population is small. Rules:
  - every minted price ≥ NPC sell price × 2, so a player can't buy and NPC for profit;
  - a daily minted value cap per channel;
  - mesos paid to minted shops are destroyed and ledgered as a sink (ticket 15).
- **Mix** (*EST*, from the Owl list and the guides): scrolls 35%, equips 25%, stars/ammo 10%, pots and elixirs
  10%, ores/ETC 10%, chairs and odd items 10%.

### 5.6 Economy safety

- **Faucets are capped at the source.** Bot mob mesos and NPC sales come only through the per-map budget
  (`OffscreenRewardBudget` in SoloMapling, ported and extended by ticket 15). The budget must use the world's
  10x meso rate, and NPC sale proceeds count against the bot's daily meso cap.
- **Sinks match faucets.** Bots spend at NPCs like players: pots, ammo, NPC gear, scroll failures, travel, store
  permits, Owl fees, trade tax and, for veterans, star force and rebirth costs. Target: total bot NPC spend over
  a week ≥ 80% of bot mob-meso and NPC-sale income (*EST*). The ledger checks this.
- **Wealth ceiling.** A bot holding more than about 3x its band's savings goal spends the excess on sinks (more
  scrolling, star force) instead of buying from the FM.
- **Faucets to players are capped.** Leech sold by bots, bot purchases from player shops and bot boss services
  each get a per-day world cap. These pay real players, so they count as inflation.
- **Feedback.** If the basket index rises more than 15% in a week (*EST*), lower bot meso caps and raise bot
  sink spending for the next week. Report it on the dashboard.
- **Never:** bots corner a market, sell under the NPC floor, trade with each other to set prices, or move mesos
  to real players outside a value-safe trade.

## Sources

| Code | Source |
|---|---|
| AYU08 | Ayumilove 2008, Ultimate Meso Guide: https://ayumilovemaple.wordpress.com/2008/09/18/maplestory-ultimate-meso-guide-where-is-the-best-place-to-earn-meso-quickly-without-hack/ |
| AYU20 | Ayumilove, MapleStory Money Making Guide (updated 2020): https://ayumilove.net/maplestory-money-making-guide/ |
| MS11 | MapleSecrets 2011, How to make mesos fast (MapleSEA, merchanting): http://maplesecrets.blogspot.com/2011/07/how-to-make-mesos-on-maplestory-fast.html |
| RMMG | MapleRoyals, meso making guide (2024): https://royals.ms/forum/threads/meso-making-guide.236414/ |
| RNEWB | MapleRoyals, Newbie's road to max meso: https://royals.ms/forum/threads/guide-newbies-road-to-max-meso.214637/ |
| RMAP | MapleRoyals, best meso farming map: https://royals.ms/forum/threads/whats-the-best-mesos-farming-map.108529/ |
| RSCR14 | MapleRoyals, scroll prices guide (Sep 2014): https://royals.ms/forum/threads/guide-scrolls-prices.14178/ |
| RWSCS | MapleRoyals, WS & CS price observation log (2022): https://royals.ms/forum/threads/white-scroll-chaos-scroll-price-observation-log.203684/ |
| RINF | MapleRoyals, reduction of inflation rates (2020): https://royals.ms/forum/threads/reduction-of-inflation-rates-removal-price-hikes-of-nlc-percentage-potions.152369/ |
| RSINK | MapleRoyals, money sink ideas (2014): https://royals.ms/forum/threads/money-sink-ideas.16062/ |
| RMESO | MapleRoyals, what are mesos good for: https://royals.ms/forum/threads/what-are-mesos-good-for.129701/ |
| RZAK | MapleRoyals Zakum helm threads *(snippets)*: https://royals.ms/forum/threads/what-do-i-exactly-need-to-do-to-purchase-zakum-helmet.23572/ , https://royals.ms/forum/threads/how-do-you-buy-zakum-helm-in-mapleroyals.116012/ , https://royals.ms/forum/tags/zakum-helmet/ |
| RLEECH | MapleRoyals leech prices *(snippets)*: https://royals.ms/forum/threads/current-leech-prices.211397/ , https://royals.ms/forum/threads/play2chills-service-skele-leech.252980/ |
| RMB | MapleRoyals 4th job book guide *(snippet)*: https://royals.ms/forum/threads/zancks%E2%80%99-4th-job-skill-mastery-book-guide.16552/ |
| RSTEEL | MapleRoyals seller listing (Steely/Ilbi) and Onyx/CS trade *(snippets)*: https://royals.ms/forum/threads/s-a-bunch-of-stuff-robes-danas-m-eqp-steely-ilbi.247749/ |
| MLSINK | MapleLegends, gachapon tickets for mesos as a sink *(snippet)*: https://forum.maplelegends.com/index.php?threads/meso-sink-idea-gachapon-tickets-buyable-for-mesos.48092/ |
| MSPSES | Private server reviews (rates, relative inflation) *(snippet)*: https://codeberg.org/deer/mspses |
| MEOW-G | MeowDB MapleStory Classic glossary: https://meowdb.com/msclassic/es/guides/maplestory-classic-glossary |
| MEOW-PP | MeowDB Pickpocket / Meso Explosion: https://meowdb.com/msclassic/skills/chief-bandit/pickpocket , https://meowdb.com/msclassic/skills/chief-bandit/meso-explosion |
| MEOW-SP | MeowDB Shadow Partner: https://meowdb.com/msclassic/skills/hermit/shadow-partner |
| Cosmic code | This repo: `server/Trade.java` (`getFee`), `constants/id/ItemId.java` (`getOwlItems`), `net/server/channel/handlers/UseOwlOfMinervaHandler.java`, `AbstractDealDamageHandler.java` (Pickpocket), `server/maps/MapleMap.java` (meso drops, `MESOUP`), `client/processor/npc/FredrickProcessor.java`, `soloMapling/FreeMarket/*`, `soloMapling/itemPool/itemConfig/*.yaml` |
| MR-CFG | Live MapleRoot config (read-only): `/opt/mapleroot/MapleRoot Full Repack/Server/config.yaml` on 192.168.0.164 |
| CON | Common to many guides; no single source |
| EST | Working estimate for this doc |
