# Ticket 162: the NX desk (FOREX-style), the 1.6.3 plan

**Not 1.6.2 (owner, 2026-10-31 ~19:50 UTC): the desk is 1.6.3 work; nothing of it is built in 1.6.2 and no desk
switch goes out with 1.6.2** — not `-Dslums.exchange.houseAccount`, not `-Dslums.market.desk.*`, not the NX book
cutover. 1.6.2 ships the NX order book with the **hard absolute price clamp** (2,500,000..10,000,000 per 1,000 NX,
`NxGuard.NX_LOWEST`/`NX_HIGHEST`, see `docs/tickets/113-nx-exchange.md` "The hard window"), and
`-Dslums.exchange.traders.nx` (bot NX book trading) stays OFF for 1.6.2 (`docs/release/1.6.2.conf`). The desk, when
it comes, inherits a book whose every order and fill is already inside that window.

Owner, 2026-10-31 12:45 UTC (via Coordinator): "NX becomes FOREX-style; items keep order books." This is the plan the
owner asked for **before** any code: the model, the rate formula, the caps, what happens to the NX order book and the
official-rate rule, the API the website needs, the offline simulator, and what a ticker costs. **The numbers (spread,
drift, caps) are the owner's to pick: three sets are proposed in §3.** Stage 2 (the build) waits for that pick.

Branch `162-xtraders` already carries the item-side work (trader share, CHAOS/WS supply, a bot NX purse). §10 says
what the desk reuses and what it replaces.

## 1. Today against the desk

| | Today (1.6.2 branch) | The desk |
|---|---|---|
| NX | an order book like the items: players and bots post bids/asks, fills walk the book | the house quotes one buy price and one sell price; every trade fills instantly at the quote; no NX book |
| Price | last fill; the official rate is a 7-day/3-fill VWAP over player-to-player fills | one reference rate `R` (mesos per 1,000 NX), published; the quotes sit around it |
| Movement | whatever fills happened | `R` drifts slowly with net flow, capped per hour and per day, always inside `PRICE_BAND` |
| Items | order books (unchanged) | order books (unchanged) |

Why it fits 1.6.2's population: with 5-15 players and thousands of bots, an NX book is mostly empty (TEST: 5 NX fills in
24 h, all house). A desk always has a price, and the drift gives a reason to hold NX or spend it.

## 2. The model

- `R` = the reference rate, mesos per 1,000 NX, published as **the official NX rate**. Start: 5,000,000 (5,000 mesos
  per NX, the NX merchant's price, owner 2026-10-29) or the rate already stored if one exists.
- Quotes, both sides, always: `buy = floor(R * (1 - spread))` (what the house pays for NX), `sell = ceil(R * (1 +
  spread))` (what the house charges). `spread` is one side, e.g. 2%: a player who buys and immediately sells loses 4%.
- A player (or a bot) trades **at the quote**: `NX bought = mesos paid / sell`, `NX sold = NX * buy / 1,000`. Whole
  NX only, minimum 100 NX (the ticker's `minQty`), no partial fills, no book walking, no queue.
- Every fill is a normal ledger post: player wallet → house wallet (the player sells NX), or house wallet → player
  wallet (the player buys NX), plus the house's fee bucket for the exchange fee if the desk charges one (today: the
  exchange's `feeBps`; propose keeping 100 bps on top of the spread, owner's call).
- Halt, `REAUTH`/step-up and the audit log stay exactly as they are for the NX paths. The **price band must be
  replaced for the desk**, not kept: today's band is 50%..200% of the official rate (see §4), which a few fills can
  walk outward; the desk clamps `R` and every quote to the absolute 2,500,000..10,000,000 per 1,000 NX.

## 3. The rate formula, and three option sets

One step per hour (the drift tick):

```
netFlowNx   = NX the house sold this window - NX the house bought this window   (players and bots alike)
floatNx     = NX in the world: accounts.nxCredit + escrow + payout NX
rawBps      = driftBpsPerPctOfFloat * (netFlowNx / floatNx * 100)
stepBps     = clamp(rawBps, -maxStepBps, +maxStepBps)
R'          = clamp(R * (1 + stepBps/10000), PRICE_BAND_LOW, PRICE_BAND_HIGH)
              and |R' / R_firstOfDay - 1| <= dayCapPct
```

`PRICE_BAND` = 2,500,000..10,000,000 per 1,000 NX (2,500..10,000 mesos per NX), the band live already enforces. Net
buying pushes `R` up, net selling down; the daily cap is the hard stop, the band is the outer wall.

Float today on TEST: **1,399,350 NX across 34 accounts** (SELECT, 2026-10-31). A 100,000 NX net buy is 7.1% of it.
Live's float is not measured (reading the live DB needs the owner's go); the sim in §7 can start from TEST's numbers and
be scaled.

| | **A calm** | **B balanced** | **C lively** |
|---|---|---|---|
| spread, each side | 3% | 2% | 1% |
| drift per 1% of float turned over | 3.5 bps | 7 bps | 14 bps |
| max step, per hour | 25 bps (0.25%) | 50 bps (0.5%) | 100 bps (1%) |
| max move, per day | 10% | 20% | 35% |
| 100k NX net buy, hour | +25 bps (capped) | +50 bps (capped) | +100 bps (capped) |
| a full day of one-way flow | 6%, capped at 10% | 12%, capped at 20% | 24%, capped at 35% |
| who it suits | a quiet economy, the desk earns the spread, the chart barely moves | some movement every session, the chart is readable | a market that visibly moves; the desk's NX inventory swings hardest |

A player buying 10,000 NX at `R` 5,000,000 pays 51,000,000 under A (2% spread + fee), 50,500,000 under C. Under B a
1% float turnover in a day moves `R` by about 7% — the sort of move players notice without it being noise.

`floatNx` grows as the desk accumulates NX and shrinks as it sells, which damps the drift automatically.

## 4. What happens to the existing parts

- **The NX order book.** At cutover every open NX order is cancelled and its escrow returned to the owner's
  wallet/payout: a one-shot `!exchange cancel-nx` (idempotent, audited) run from `!exchange resume`. TEST's NX book is
  empty today (TEST has no `-Dslums.exchange.houseAccount`, b206), so the step is live-only and harmless. The NX
  `FillRow` history stays for the chart; the NX ticker row stays but is marked `book=off`, and `place`/`placeBot`
  refuse NX orders with a message that points at the desk.
- **The official-rate rule.** For NX, `R` replaces the 7-day/3-fill VWAP: the desk's mid *is* the official rate, and it
  is what `NxGuard.band` and the site's NX page read. Item tickers keep the VWAP `change` rule (it already leaves out
  regulars).
- **Bot NX fills.** They move `R` through the same drift as players' — the owner's 12:33 UTC answer ("bot NX fills count
  toward the official rate") falls out of the design; there is no separate count rule to keep.
  Today they already count: `OfficialRate.vwap` only skips *regular* fills (`buyerRegular`/`sellerRegular`), and a
  `FillRow` carries no account or bot flag, so a bot's NX fill is indistinguishable from a player's and feeds the
  7-day/3-fill VWAP (measured 2026-10-31: one fill at 9,900,000 sets the official rate to 9,900,000).
- **The band is relative, not absolute (finding, 2026-10-31).** `Exchange.priceBand` reads `nxBand()`, which is
  `NxGuard.band(officialRate, tickerMin, tickerMax)` = **50%..200% of the official rate** (defaults
  `-Dslums.exchange.nxBand.minPct/maxPct`), falling back to the start price only when no rate exists. So the owner's
  "always clamped to 2,500..10,000 per NX" holds only until the first official rate: with 3 fills at 9,900,000 the rate
  is 9,900,000 and the accepted band becomes 4,950,000..19,800,000 (probe: a 10,500,000 buy was accepted). Two more
  fills at that ceiling double it again. The desk's `R` must therefore be clamped to the absolute
  [`PRICE_BAND` 2,500,000, 10,000,000] in code, and its quotes are the authority; the relative band then only guards
  the retired book. Owner decision: hard absolute clamp (**recommended**), or a wider band with a per-day cap.
  **Decided and shipped in 1.6.2 (owner 2026-10-31 ~19:50 UTC): the hard clamp**, not the desk. The window is now in
  code (`NxGuard.NX_LOWEST`/`NX_HIGHEST`), `nxBand()` cannot leave it, every NX order on either side is refused
  outside it (players, bots, house regulars, the site), and fills follow because a fill trades at the resting order's
  price. The probe above no longer reproduces: with the rate at 9,500,000 a 10,500,000 buy is `PRICE_BAND`. The desk
  will therefore start from a book that is already inside the window; its `R` clamp is unchanged by that.
- **`NxGuard`.** The band becomes a safety net (R is inside it by construction). NX buy limits, the site's step-up and
  the halt are unchanged.
- **The 1.6.2 branch's bot NX book work.** Reused: the bot purse (`Exchange.botNx`, a bot's NX in its own PAYOUT
  bucket, so it survives a restart and cannot be double-spent) and `TraderBrain`'s per-1,000-NX maths, lots and
  `minQty`. Replaced: quoting NX on the book (`MarketTraders.act`'s NX ticker branch) — a trader becomes a desk
  customer, not a market maker. It is off by default (`-Dslums.exchange.traders.nx`) and **not** in 1.6.2's live flags.

## 5. Without a house account

The desk is **closed**: no quotes, no NX fills, no drift (`R` stays where it is), and `!exchange status` and the site
say `NX desk closed: no house account (-Dslums.exchange.houseAccount)`. Bots skip NX. This is TEST today (b206) and
would be live if the owner does not pick a staff account. Items are unaffected.

**Liquidity consequence (owner decision, §9).** The house can only sell NX it owns: the desk starts with 0 NX, so its
first NX sales wait until someone sells to it. The plan is to show the desk's inventory ("the desk holds N, is buying /
is not selling") rather than mint a starting balance.

## 6. What the website needs (Website lane)

```
GET /api/nx/quote      -> {at, mid, buy, sell, spreadBps, driftBpsLastHour, dayCapPct, movedTodayPct, floor, ceil,
                           open, deskNx, minQty}
GET /api/nx/history?hours=168 -> [{hour, mid, buy, sell, volumeNx, fills}]   (hourly; mid for the chart)
GET /api/nx/me         -> {nx, openOrders (empty after cutover), fills (last 20), stepUpFreeMesos}
```

- The chart plots `mid` and can shade the day's cap band (`mid +/- dayCapPct`).
- Fill flow: instant, but **b005 stays** — a buy over `stepUpFreeMesos` (10,000,000) and every site NX sale need the
  password step; the confirm dialog shows the quote and `mid`, and the quote is held for `confirmMs` (60,000 ms today)
  or re-quoted with a message when it moved.
- Halt and the band still refuse a fill; the site shows the game's message verbatim.

## 7. The offline simulator (stage 2, plan only)

Extend the existing `MarketSim` (`src/test/java/soloMapling/Economy/exchange/volatility/`, `MarketVolatility`'s tick) —
no second simulator. Inputs measured from TEST (SELECT-only) and the sim's own packs: trader share (35%), bot meso
holdings, per-ticker loot rates including world-event and event-boss drop rows, player activity (an hour profile), and
the desk's three option sets. Run 4 weeks of game time per set, hourly steps, and output per set:

1. the `R` path (hourly) and how often the hourly step cap and the daily cap bind;
2. the house account's P&L and its NX and meso inventory over time, and the longest stretch it cannot sell NX;
3. fills per ticker per day (NX plus the item books) and the split player/bot;
4. band touches (should be zero), and the desk's average spread revenue vs its inventory drift.

Then the lane session runs the chosen set on TEST with traders on and compares: `R`'s first 48 h against the sim's,
NX fills per day, the house's P&L, cap hits, and the exchange tick cost (the 20 ms budget that `t-bt-1` measures).

## 8. What a ticker costs (every scroll, "vital items")

Today one ticker is **code**, in six places: a `Ledger.Asset` constant carrying the item id (`CHAOS(2049100)`), the
`Ticker` defaults (name, lot, min/max qty, limits), `FairValue.ITEM`, `MarketTraders.FALLBACK`, `Ticker.ITEM_MAX`, plus
the site's page/route and a `!qa` scenario — and a jar rebuild. That is why 1.6.2 has four item tickers.

Make it **data-only**: a tickers pack (`cashshop`-style TSV/YAML: id, item id, name, unit, lot, min/max qty, price band,
open/day limits) read into the existing `Ticker` record, with the asset enum replaced by data. Then a ticker is one row
+ a QA row + nothing else, and the site reads the pack. Cost: ~2-3 days once, then minutes per ticker.

- **First batch (10 item tickers):** the four today (LEAF, CHAOS 60%, WS, ELIXIR) + Clean Slate, Potential, Equip
  Enhancement, Chaos 100%, Power Elixir, Onyx Apple. Each needs a real source — a drop row, a Maker recipe or an event
  — or it never trades.
- **Full set (~50):** every scroll type that still has a v83 source (~40) + the vital consumables (~10). Needs the
  data-only refactor, site pagination/search, and a supply check per item — a ticker with no source never fills and only
  adds load and confusion. Book load is small: 50 tickers x the 10-open/20-a-day limits is well inside the exchange's
  per-second budget; the real cost is UI and support.

## 9. Owner decisions this plan needs

1. **Which set: A, B or C** (§3). The sim (§7) is what the numbers above are for.
2. **The house account** (§5): a staff account (SlumsBank), its starting mesos, and whether NX sales wait for inventory
   (planned: yes, no minting) or the desk may hold a negative NX balance (not in the ledger today).
3. **The fee on top of the spread**: keep the exchange's 100 bps, or the spread alone.
4. Whether the desk's own `houseAccount` trading is allowed when the owner plays on it (`!exchange` already restricts
   the house account to `qa` or itself).
