# 38: Bot economy behaviour (meso plans, FM pricing, sinks)

**Area:** Real bots / Economy
**Depends on:** 09 (shop resupply), 12 (sell and trade), 15 (yield caps and ledger)
**Status:** open

Research: [docs/real-bots/economy-research.md](../real-bots/economy-research.md).

## Today

- **Rates:** the live world runs 3x EXP, 10x meso, 4x drop, 3x boss drop (MapleRoot config). Mob and quest
  mesos are x10; NPC buy and sell prices and Pickpocket mesos are not.
- **Bot FM:** `ArtificialFreeMarket` spawns merchant shops with **minted** stock from static price tables
  (`itemPool/itemConfig/*.yaml`). Prices move with a sine "market index", a room premium and the weekday. They
  never react to real trades, and the stock does not come from bot loot.
- **Bot buying:** `BuyingMerchantBot` offers 80-90% of a price; `OfferEvaluator` haggles.
- **Bot mesos:** bots have no plan for what to earn or spend per level. Tickets 09 and 12 add buying and selling,
  and ticket 15 adds per-map budgets and a ledger, but nothing decides *what* to keep, list or NPC, or *at what
  price*.

## What

Bots earn and spend mesos the way v83 players did, priced from real trades, without inflating the world.

- **Meso plan per band and job** (data file, owner-tunable):
  - income weights (mob mesos, NPC sales, FM sales, PQ, services);
  - spend priority (pots and ammo, then the next gear upgrade, then scrolls, then luxuries);
  - a savings goal per band (for example Work Gloves, an Ilbi set, a Zakum helm);
  - job tweaks: mages sell leech on undead maps from 3rd job, Hermits party for Meso Up, archers and assassins
    budget ammo, warriors budget extra pots.
- **Loot rule: keep, list or NPC.**
  - Keep own upgrades, scrolls for own scrolling, quest ETC and own consumables.
  - List on FM when `book × (1 − tax) × P(sell in 3 days) > NPC price × 1.5`: all scrolls, better stars,
    ores and crystals, quest-set ETC, PQ rewards (Onyx Apples, Maple Coins), books, clean godly or classic
    equips.
  - NPC the rest on the next town trip (ticket 12). Never drop sellable items.
- **Price book from real trades.**
  - Seed from the existing YAML prices × a world factor (start 2.5x).
  - Learn from real player shop sales, single-item meso trades, player shop asks (a bot "Owl" scan) and Owl
    searches as demand.
  - Ignore bot-to-bot trades and minted-shop sales.
  - 14-day trimmed median; at most ±10% a day; floor NPC price × 1.2; no more than 3x seed without 5 real sales.
- **Listing and buying.**
  - Ask = `min(book × persona markup, lowest real player ask − 1-3%)`, ending in 9s.
  - Personas: pro, average, casual, rare noob. Undercut real players at most once a day; never undercut bots.
  - Reprice −5% per unsold day down to the floor.
  - Bots buy from real players only what their plan needs, up to the book price, within a daily cap.
- **Merchant stock.** Real bot loot first. Minted shops stay as filler, with every minted price ≥ NPC price × 2,
  a daily minted-value cap per channel, and their takings ledgered as a sink.
- **Economy safety.**
  - All bot mob mesos and NPC sales come through ticket 15's per-map budget at the 10x meso rate, and count
    toward the daily meso cap.
  - Bot NPC spending (pots, ammo, NPC gear, scroll failures, travel, permits, Owl, tax, star force, rebirth)
    targets at least 80% of bot mob-meso and NPC-sale income per week.
  - Wealth ceiling: excess above about 3x the band's savings goal goes to sinks, not the FM.
  - Per-day world caps on bot leech sold, bot purchases from player shops and bot boss services.
  - A basket price index (WS, CS, Onyx Apple, Ilbi, Glove ATT 60%, Helmet INT 60%, Elixir, Power Elixir, an hour
    of leech) on the dashboard (ticket 35). If it rises more than 15% a week, tighten bot caps automatically.
  - Bots never corner a market, sell under the NPC floor, trade with each other to set prices, or move mesos
    outside a value-safe trade.
- **Chat:** bots read and use FM slang (B>, S>, c/o, a/w, ea, clean, perf, CS/WS, PC) and may quote very large
  deals in WS/CS.

## Done when

- Each band and job has a meso plan in data, and bots follow it: a level sweep shows mesos, spending and savings
  goals by level.
- Bots keep, list or NPC loot by the rule; no bot NPCs a scroll or lists below the NPC floor.
- The price book moves with real player trades and ignores bot-only trades; bot asks track it.
- The ledger shows bot NPC spending at or above 80% of bot faucet income over a week.
- The basket index is on the dashboard and stays within ±15% over a test month with the full bot population.
