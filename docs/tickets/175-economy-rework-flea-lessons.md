# Ticket 175: economy rework, lessons from Tarkov's flea market (1.6.3 book item e01, after 1.6.3)

Owner, 2026-11-01 (Website session): "can we also add a ticket for economy rework? based on learning from tarkovs
flea market". Target: after 1.6.3 (1.6.3 is catch-up and stability). Economy changes need the owner's say-so at
each step: this ticket collects the ideas and the questions. It decides nothing.

## Owner's direction (2026-11-01, ~07:35 UTC)
"we dont need to adopt all of those but just trying to figure out the economy. guess just needs time to bake. and
allowing more playerbots to participate". So: no adoption list now. Let 1.6.2's market run and collect the numbers,
and look first at getting more regulars to take part (more trader bots, on the board as well as the exchange, and on
NX once the 1.6.3 desk is in). The Tarkov ideas below are a reference to come back to, not a plan.

## Owner's calls, 2026-11-01 ~22:30-22:49 UTC (Website session)
- "prices should be driven by merchants": the exchange's item fair values anchor on the merchant/price-board median,
  not the NPC buy-back price (handed to Server Dev; 1.6.3 or 1.6.4 is the Coordinator's call with the owner).
- "we should allow items to run out of stock in stores": shops get limited stock that sells out and restocks over time,
  instead of endless stock. The owner clarified: **NPC merchants** (the game's NPC shops). Open questions: which items
  (potions and ammo too, or only the valuable ones); how fast they restock; per channel or world-wide. A sold-out shop
  pushes buyers to the board and the exchange, which helps "prices driven by merchants" and gives regulars a reason to
  trade.

## NPC shop stock, Tarkov-style restocking (owner, 2026-11-01 22:49 UTC)
Owner: "should just have a stock of items im not sure how much though but there should be restocking, following
tarkovs like restocking". How Tarkov's traders restock (as it has worked across wipes; details change):
- **One stock per item, shared by the whole server**: first come, first served; when it's gone, it's gone until the
  restock.
- **A restock on a fixed timer per trader** (roughly every 1-3 hours), with a visible countdown, so players plan around
  it and rush the good items at restock time.
- **A per-player limit per restock** on the valuable items ("2 per restock"), so one player can't buy out a whole
  stock.
- **Stock sizes per item**: big for basics, small for valuable items.
- **Loyalty levels** unlock better items (SlumsMS has no trader loyalty today; not proposed).
Owner, a few minutes later: "obviously if its like pots or ammo we should be generous but overall pool if its like
higher rated items to be sold on the flea". So: potions and ammo get generous stock (they should almost never run
out); higher-value items come from a small shared pool, so most of them reach players through the "flea" (the
Buy/Sell board and the Slums Exchange) instead of the NPC.
A SlumsMS version to decide with the owner (amounts are the open part: "im not sure how much"):
- every NPC shop item gets a stock and a per-character limit per restock; basics (potions, ammo) large or effectively
  endless, valuable items small;
- each shop restocks on a timer (e.g. every 2 h; a world-wide or per-shop time), shown in the shop window if the client
  allows it, else in `@shop` and on the website;
- stock is world-wide (all channels), not per channel, like Tarkov's server-wide stock;
- the regulars buy from the same stock (they compete with players), which feeds the board and the exchange;
- start with data, not guesses: from TEST (and live aggregates when allowed) measure what each shop sells per hour
  today, then set the stock to about one restock window of normal demand, so basics rarely run out and valuable items
  do.

## Today in SlumsMS (what any rework starts from)
- **Buy/Sell board** (website): players post buy or sell offers, 5 open posts each; regulars keep shops in the FM.
- **Slums Exchange** (1.6.2): order books for NX, Maple Leaves, Chaos and White Scrolls and Power Elixirs, a 1% fee
  each side, an NX band of 2,500–10,000 per NX, and trader bots (25–50% of the regulars) on the item books. The
  NX house desk comes in 1.6.3.
- **Prices**: the market pages show real trades separately from the samples ("counts real, prices keep bots"), and a
  fair value from drops and sinks.
- **Sinks**: NPC shops, fees, the Maker's fees, Dalair's merge; no daily NX or meso caps (owner 2026-10-29).

## What Tarkov's flea market teaches (as it has worked over many wipes; details change wipe to wipe)
1. **An access gate.** The flea opens only at a player level (it has moved between about 15 and 20), so fresh and
   throwaway accounts can't farm or dump on it.
2. **A listing fee that grows with the asking price** against the item's base value. Fair listings cost little;
   gouging costs a lot. It's a big rouble sink, and it pulls asks toward real value.
3. **"Found in raid"**: only items you actually found yourself (not bought from traders or players) can be sold on
   the flea. That breaks buy-low/resell loops and much of the RMT and bot farming.
4. **Offer slots grow with reputation** (flea rating from completed sales): new sellers get few listings, proven ones
   more.
5. **Item bans**: whole categories (best ammo, armour, keys) were taken off the flea to push players back to playing
   for them. Some wipes went further, with tight bans or even no flea for a while.
6. **Barter offers**: item-for-item trades next to cash ones, and trader barters that make crafting materials worth
   holding.
7. **A visible average price and a handbook price**, so everyone sees the going rate and the base value.
8. **Traders as a floor and ceiling**: NPC traders buy everything at a low price and sell some things at a set one, so
   flea prices can't crash below the trader buy price or run far above trader sales.

## How each could map onto SlumsMS (ideas for the owner, not decisions)
| Tarkov idea | SlumsMS version | Touches | Open question |
| --- | --- | --- | --- |
| Access gate | Board posts and exchange orders from level N (e.g. 30, like `@invite`) | server + site | which level; do alts count? |
| Price-scaled listing fee | The fee grows when an ask is far above the fair value (we already compute it) | exchange, board | the curve; a meso sink target per week |
| Found in game | Items a character bought from another player can't be relisted for N hours/days, or only drops/quests/crafts can be sold | inventory flags, exchange | too restrictive for 5–15 friends? |
| Slots by reputation | Open posts/orders grow with completed trades (5 → 10 → 20) | board, exchange | |
| Category bans | Keep boss drops, top scrolls off the board (1.6.2 already took them out of bot shops) | board, exchange, bot shops | which list |
| Barters | Item-for-item posts on the board; barter recipes at NPCs | board, NPCs | |
| Average and base price | Already on the market pages (real trades, fair value); add to the exchange and @nx | site, game | |
| Trader floor and ceiling | NPC buy prices as the floor; the NX house desk (1.6.3) as the NX anchor | shops, desk | |

## Before deciding (data first)
Pull 2–4 weeks of live numbers (aggregates only, per the owner's live-read rule; no player rows): meso supply and
sinks per day, board and exchange volume, price spread versus fair value, how many trades are bots versus players, and
items that only move in one direction. Then pick 2–3 ideas, simulate them (the exchange already has a market
simulation), and try them on TEST before any live change.

## Owner decisions needed
1. Which ideas to explore first (a short list).
2. Any hard lines (e.g. "no caps", "keep the 1% fee", "bots trade freely").
3. Who leads: Server Dev for the game side, with the Website session for the board/exchange pages.
