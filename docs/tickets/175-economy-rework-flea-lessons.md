# Ticket 175: economy rework, lessons from Tarkov's flea market (1.6.3 book item e01, after 1.6.3)

Owner, 2026-11-01 (Website session): "can we also add a ticket for economy rework? based on learning from tarkovs
flea market". Target: after 1.6.3 (1.6.3 is catch-up and stability). Economy changes need the owner's say-so at
each step: this ticket collects the ideas and the questions. It decides nothing.

## Owner's direction (2026-11-01, ~07:35 UTC)
"we dont need to adopt all of those but just trying to figure out the economy. guess just needs time to bake. and
allowing more playerbots to participate". So: no adoption list now. Let 1.6.2's market run and collect the numbers,
and look first at getting more regulars to take part (more trader bots, on the board as well as the exchange, and on
NX once the 1.6.3 desk is in). The Tarkov ideas below are a reference to come back to, not a plan.

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
