# Ticket 270: the exchange item catalog — all scrolls, some use items, then ETC

**Status:** implemented in `164-excatalog` (worktree `/root/work/SlumsMS-164-excatalog`), not merged, not deployed.
The engine, the catalog file and the tests are in; §11 records what shipped and what it costs. §5 is now the record of
the owner's numbers as given on 2026-11-03, and §10 keeps only the questions still open.
Owner direction 2026-11-03 (Website session): "if i wanted to add all scrolls, some use items, then etc, provide me a
full list, and then following the paperwork process to create a ticket you can start work on". Earlier the same
session wrote "we should def add all scrolls in forsure", and before that (2026-11-02) asked for "etc items would be
good, but if theres more usable items", a ruthless/volatile market, categories and a guard against "users easily
sellout and make billions of mesos".

The list is `docs/economy/exchange-item-catalog.md` (generated 2026-11-03, 938 lines, every id with its price,
live holdings and state). The evidence behind it is `agent-runs/server/exchange-items-audit/REPORT.md` (live
measurements, code map, findings F1–F11).

This ticket is the engine work plus the owner's numbers. It adds no prices of its own: every number in §5 is a
question with options, and none of them is applied until the owner picks.

## 1. What ships

Three item sets, one engine change, on 1.6.4 (open for new content and new capabilities, so the items themselves are
in scope; the caps and anchors still need the owner's numbers).

| Set | Ids | State today | What shipped |
|---|---|---|---|
| All scrolls | 518 tradeable of 756 (209 untradeable, 29 GM-only) | 121 with a pool price (107 before the dark pass) | all 518 registered: 121 pool, 397 derived (§4), 0 parked |
| Some use items | 27 in the file (16 shortlisted + 11 that fill a real holder's bag) | 27 anchored | all 27 registered, ammo out (§3) |
| ETC | 55 pool-priced + the ids players actually hold | 55 anchored | 164 registered: 55 pool, 109 unquoted guests |
| Engine | — | item tickers had no maker and no volume cap | item maker + per-account sell cap (§6) |

`FairValue` drops any ticker whose floor and anchor are both under `TOKEN_FLOOR` (`FairValue.java:101-107`), so an
unpriced ticker is safe to register: it quotes nothing until it has a price. That is what "registered" means below.

## 2. The catalog

`docs/economy/exchange-item-catalog.md`. Ticker ids are 8 characters, the limit of the `varchar(8)` columns
(`exchange_orders.ticker`, `exchange_fills.ticker`, `exchange_ledger.asset`, `exchange_escrow.asset`, verified in
`information_schema`):

| Class | Ticker id | Example |
|---|---|---|
| Scroll | `S` + item id | `S2040805` |
| Use item | `U` + item id | `U2000005` |
| ETC | `E` + item id | `E4006001` |
| Already live | unchanged | `NX` `MESO` `LEAF` `CHAOS` `WS` `ELIXIR` |

Counts as shipped (`exchangeTickers.yaml`, 709 rows; each row is one line with its caps):

| class | in the file | anchored from the pool | derived anchor | anchor 0 (registered, unquoted) |
|---|---|---|---|---|
| scrolls | 518 | 121 | 397 | 0 |
| use | 27 | 27 | 0 | 0 |
| ETC | 164 | 55 | 0 | 109 |
| **total** | **709** | **203** | **397** | **109** |

The 238 skipped scrolls (209 `tradeBlock=1`, 29 "GM Scroll for ..." staff scrolls) stay out: they cannot be traded, and
the GM ones are staff-only. The generated list with every id, price, holding and state is
`docs/economy/exchange-item-catalog.md`.

**One ticker per item, legacy id wins.** Three rows fold into a 1.6.3 book rather than opening a twin — `S2049100`
(Chaos), `U2000005` (Power Elixir) and `E4001126` (Maple Leaf) — so the switch opens **706 new tickers**; `WS`
(2340000) has no row at all. `CHAOS` is already item 2049100 and `ELIXIR` is 2000005, so the registry
must resolve `S2049100` → `CHAOS` and `U2000005` → `ELIXIR` rather than create a second ticker for the same item.
History, escrow and ledger rows keep the old ids; no migration.

## 3. What the audit measured (why the sets are these)

From `agent-runs/server/exchange-items-audit/REPORT.md`, live, read-only:

- Every LEAF fill and every NX fill is bot-vs-bot; ELIXIR has one player buyer and one player seller in 2,071 fills.
- CHAOS (5,419 orders) and WS (2,259) have **never had a single buy order** — the asks sit at 45–57M and 34–57M and
  bot budgets are 6–35% of a wallet that cannot afford one unit. The mechanism is `TraderBrain.afford`
  (`TraderBrain.java:413-417`).
- The price board is 99% bot seeds: 119 of 11,355 samples have a real player on a side, because bot-to-bot sales are
  never recorded (`PriceBook.record`, `PriceBook.java:361-392`).
- Player-held scroll hoards at pool anchors are the print risk: CHAOS 300 × 20M = 6.0B, WS 125 × 46.2M = 5.77B, against
  958M of total player mesos.
- Arrows and basic potions sell at 1–10 mesos from NPC shops; `TOKEN_FLOOR` is 1,000, so they cannot be tickers at
  all. Stars, elixirs and buff foods can (and `U2070005` Steely has a board average of 6.72M over 4 samples).

## 4. The anchor chain (the crux of "all scrolls")

411 tradeable scrolls have no pool price (518 tradeable ids minus the 107 with an anchor). Without a derived default,
"all scrolls" means writing 411 price rows by hand or shipping a museum. The chain proposed:

```
anchor(ticker) = pool price            (itemPool/itemConfig/*.yaml, version 1)
               | -Dslums.exchange.<id>.anchor       (new per-ticker property)
               | derived default by slot+stat+tier  (§5.1, owner's pick)
               | 0 → ticker dark (no quotes)

floor   = NPC price when it is >= TOKEN_FLOOR, else anchor
ceiling = max(1.2 * floor, 1.5 * anchor)
```

Today `FairValue.floor` (`FairValue.java:158-168`) is the NPC price with LEAF forced to 500,000, and `anchor`
(`:169-178`) is the board or `MarketTraders.FALLBACK`. The chain above keeps both and adds the two new sources. It
also removes the hardcoded `List.of("CHAOS","WS","ELIXIR","LEAF")` at `FairValue.java:101` — the list every new
ticker used to have to be added to.

Honest consequence to state up front: a derived default is a price the owner did not set from data. It is bounded by
the item-pool band (0.25×–4× of the static value) and the exchange ceiling (1.5× board), so a bad default cannot
become a 100× print, but it will be wrong for some scrolls on day one and the board will pull it toward what players
actually pay.

## 5. Owner decisions (no number here is applied before the owner picks)

### 5.1 A price for scrolls that have none (411 tradeable ids)

| | Option | Effect |
|---|---|---|
| A | Copy the nearest priced sibling: same slot + same stat + same tier from `scrolls.yaml`, else the slot's median | data-driven, tracks the pool; 411 rows cost nothing to maintain |
| B | Flat by tier: 100% = 100,000, 60/70% = 1,000,000, 10–30% = 3,000,000 | simplest, but wrong for cheap slots (helm DEF) and good ones (glove ATT) alike |
| C | Base price × tier multiplier from the owner (e.g. 500,000 × {1, 3, 10}) | one number to pick, uniform distortion |

Recommended: **A, falling back to B** where the pool has no sibling. The catalog already carries slot, stat and tier
per scroll, so A is a generator, not 411 decisions.

### 5.2 Scroll caps

Today `Ticker.ITEM_MAX_OPEN` = 10 and `ITEM_PER_DAY` = 20 for every item ticker. Proposed for scrolls:
`maxQty=1`, `maxOpen=3`, `perDay=10` — a player's median holding is 1–5 per scroll id, so this costs nothing
legitimate and turns a 300-unit CHAOS dump into a 30-day operation. Keep 1e9 `maxPrice` or tighten to 2× anchor.

### 5.3 A per-account extraction cap

New knob `-Dslums.exchange.items.sellCapMesosPerDay`. Options: 5,000,000 (conservative, ~200 days to clear the
CHAOS+WS hoard), **20,000,000** (≈2% of the money supply per day, ~40 days), 50,000,000 (a quarter of the supply in
20 days). Checked when an item sell is placed and again when a bot payout is collected, summed per account over all
item tickers.

### 5.4 The item maker

`tickRegulars()` posts only on `tickers.get(Ticker.NX)` (`Exchange.java:2689-2695`) and the house `Desk` is gated on
`!t.item()` (`ExchangeChat.java:68`). Every new scroll ticker would therefore repeat the CHAOS wall: asks only, no
fills. Options: (a) let the existing `Regulars` quote item tickers at ±spread around fair value, size
`-Dslums.exchange.regularItems=<n>` per side, gated `-Dslums.exchange.itemMaker=true` (recommended); (b) leave item
tickers makerless and accept that the ETC set is where liquidity happens; (c) wait for the 1.6.3 NX desk to settle
first.

### 5.5 Use items

Recommended enabled set = the 16 ON in the catalog (Elixir, Power Elixir, Red Bean Sundae, All Cure, Drake's Blood,
Sap of Ancient Tree, Melting Cheese, Reindeer Milk, Sunrise Dew, Sunset Dew, Heartstopper, Gelt Chocolate, Onyx Apple,
Ssiws Cheese, Naricain's Demon Elixir, Steely Throwing-Knives). Ammo and basic potions stay out for the
`TOKEN_FLOOR` reason in §3; say so if the exchange should carry them anyway.

### 5.6 ETC

55 pool-priced ids, no new numbers. Two guards to confirm: `maxQty` 10,000 and `maxOpen` 10 per account are already
the item defaults, but the bot hoards are 40M (Monster Card) to 1.6M (Black Crystal Ore), so an ETC ticker is a
sell wall on day one and the wall pushes the price to the floor — which for ETC is the pool anchor. That is
acceptable (thin spread, price stays anchored) but the owner should see it before it happens.

## 6. The build, in order

1. **`Economy/exchange/AssetRegistry.java`** (new): immutable `id → Entry(id, itemId, name, category, unitLabel)`,
   plus a reverse `itemId → id` that prefers legacy ids. Legacy six seeded first; catalog entries built from the
   class switches. Assert `id.length() <= 8` at construction.
2. **Replace `Ledger.Asset`** (`Ledger.java:36-37`), `Ticker.ITEM_MAX`/`ITEM_NAME`/`itemDefault`
   (`Ticker.java:44-52,94-100`), and every `Asset` use in `Exchange`/`ExchangeSite`/`MarketFlows` with the registry
   entry. `Ticker.item()` and `perCharacter()` move to `Entry`.
3. **`Ticker.catalogEntry(Entry)`**: caps per category (SCROLL 1/3/10, USE 1,000, ETC 10,000; `ITEM_EXPIRY_MS` 168 h;
   `ITEM_MAX_PRICE` 1e9), overridable per ticker by `-Dslums.exchange.<id>.maxQty|maxOpen|perDay|expiryHours|
   minPrice|maxPrice|anchor`. Registration switches: `-Dslums.exchange.catalog=scrolls,use,etc` (default empty =
   today's behaviour).
4. **`FairValue`**: iterate the registry (delete `TICKERS`, `ITEM`, the hardcoded list at line 101 and the LEAF
   special case becomes a `-Dslums.exchange.LEAF.anchor` default); implement the §4 chain.
5. **`MarketFlows.TICKER_OF`** (`MarketFlows.java:23-24`) → reverse registry lookup; **`MarketTraders.FALLBACK`**
   (`MarketTraders.java:52-53`) → pool price for item tickers, `FALLBACK` only for NX and the legacy four.
6. **Item maker** (if 5.4 A): `tickRegulars` iterates the registry for item tickers, quoting around
   `FairValue` ± `regularSpreadBps`, size `regularItems`, house-funded, same halt/cooldown rules.
7. **`items.sellCapMesosPerDay`**: count in the item-sell place path (where `LOG_IN_FIRST` is returned today) and in
   `collectBotLocked`, both per account per UTC day.
8. **Site**: `ExchangeSite` already iterates `ex.tickers()` (`:484/:509/:528`); add `category` to the payload and a
   category filter in the page. No migration.
9. **The conf line** for `docs/release/1.6.4.conf` (the cut creates that file from `1.6.3.conf`; this ticket does not
   author a release conf). Append to that file's `UNIT_EDITS` jar line:

   ```
   -Dslums.exchange.catalog=scrolls,use,etc -Dslums.exchange.itemMaker=true -Dslums.exchange.items.sellCapMesosPerDay=20000000
   ```
   `-Dslums.exchange.tickers=...` keeps its 1.6.3 value and still narrows the set when it is set (an id the file does
   not name stays off, legacy ids included), so the two switches compose instead of fighting.
10. **Docs**: `docs/economy/exchange-item-catalog.md` (this commit), and a line in `docs/tickets/113-nx-exchange.md`
    pointing here.

## 7. Tests

`src/test/java/soloMapling/Economy/exchange/ExchangeCatalogTest.java` — 8 cases, all green in `164-excatalog`:

- the file registers its rows, no id exceeds 8 characters, and `CHAOS`/`WS`/`ELIXIR`/`LEAF` keep their 1.6.3 ids
  (one book per item, both directions of the lookup);
- `-Dslums.exchange.catalog=scrolls` opens only scrolls, with the owner's caps (1 per order, 3 open, 10 a day) and the
  pool anchor; the 1.6.3 item tickers stay on;
- `=etc` opens only ETC, and an anchor-0 row is still listable at `min_price` 1;
- `-Dslums.exchange.tickers` narrows the catalog as well as the legacy four;
- with the catalog off the ticker list is byte-for-byte 1.6.3's, and the cap is off;
- the site payload carries the category for a catalog row and for the legacy rows;
- the ceiling refuses a 60M sell, allows 18M, refuses the next 6M that would cross 20M, and is per account (NX
  untouched);
- a fill counts against the ceiling (10 leaves filled = 6M used, 20 more fit, 4 more do not).

The failing state on `release-1.6.4` is a compile failure (`AssetRegistry`, `ExchangeLimits`, `ExchangeCatalog`,
`ExchangeStore.itemExposure` and the six-argument `Exchange` ctor do not exist there), which is why the tests live
beside the code rather than before it.

## 8. Done when

- `mvn -q -o test` green in the worktree, the new tests included.
- On TEST (the lane session deploys; this ticket does not): one scroll ticker, one use ticker and one ETC ticker are
  visible on the site with their categories; a player sells a scroll in game and buys one from the site; both fills
  settle with the new asset in `exchange_ledger`/`exchange_escrow`; no ticker id exceeds 8 characters; a dark ticker
  shows no quotes.
- Every number added or changed is listed in `REPORT.md` for the owner's review (STATUS-1.6.4 rule).

## 9. Out of scope

- Changing the existing LEAF / CHAOS / WS / ELIXIR prices or caps beyond §5.2.
- The NX desk (ticket 162) and the merchant repricing path (`BotShops.askFor`).
- Pricing the 109 held-but-unpriced ETC ids and the 275 dark scrolls by hand — that is data, and it follows §5.1.
- Ammo and basic potions as tickers (dust prices under `TOKEN_FLOOR`).
- Any live deployment, any `release-*` merge, any push.

## 10. Questions still open for the owner

1. **`maxPrice` for scrolls**: rows ship with the engine's 1e9 ceiling (a plant is caught by `maxPrice` only when it
   is tight). §5.2's option was 2× the anchor. Tightening it is one column in `exchangeTickers.yaml`.
2. **The 109 anchor-0 ETC rows** (§2): they are registered and a player can place an order, but no quote appears until
   they get a price. Price them by the §4 chain (sibling derivation) or leave them as a player-only list?

## 11. What shipped (2026-11-03)

| Piece | Where | Notes |
|---|---|---|
| Asset registry | `Economy/exchange/AssetRegistry.java` | `Asset(id, itemId, name, Category, anchor)`; categories `NX MESO LEAF SCROLL USE ETC ITEM`; constants kept as `Asset.NX` … `Asset.ELIXIR`; `of(id)` resolves a registered id, else a derived `S/U/E` id via `parseItemId`; throws above 8 characters; first registration of an item id wins, so the legacy four never get a twin |
| Catalog reader | `Economy/exchange/ExchangeCatalog.java` + `itemPool/itemConfig/exchangeTickers.yaml` | 709 rows; `DataFiles.optional`, so a missing file is 1.6.3's behaviour with a warning; rows whose item id is already seeded are dropped |
| Caps and switches | `Economy/exchange/ExchangeLimits.java` | `-Dslums.exchange.catalog` (plural or singular token), `-Dslums.exchange.itemMaker`, `-Dslums.exchange.regularItems` (default 5), `-Dslums.exchange.items.sellCapMesosPerDay` (default 0) |
| Ticker build | `Ticker.fromSystem` | NX + 1.6.3 seeds narrowed by `-Dslums.exchange.tickers` + catalog rows the category switch opens, each through the per-ticker and item-wide overrides |
| Fair value | `FairValue` | no hardcoded ticker list: `update(now, flows, src, tickers)` walks the exchange's tickers (CHAOS first, LEAF last); floor = NPC price (LEAF keeps `-Dslums.exchange.LEAF.anchor`, default 500,000); anchor = board value ≥ 1,000 else the row's anchor; ceiling = `max(board, anchor) × 1.5`; a floor and anchor both under 1,000 still publishes nothing |
| Flow hooks | `MarketFlows.tickerOf(int)`, `MarketTraders.anchorOf(Ticker)`, `MarketTraders.heldScroll` | reverse lookup through the registry; an item ticker's fallback price is its own anchor; the off-screen scroll finder now prefers whatever scroll the bot holds most, not just CHAOS/WS |
| Item maker | `Exchange.tickRegulars` → `makerQuotes`/`makerQuote` | with `-Dslums.exchange.itemMaker=true` each open item book gets one house-funded bid and ask at the fair value ∓ `regularSpreadBps`, `regularItems` units, LEAF skipped (it has players), order ids `maker-<regular>-<side>-<ticker>-<n>`. A bid's affordability is read from the HOUSE's MESO bucket, an ask's from the HOUSE's item bucket. The pass is budgeted (`MAKER_BOOKS_PER_TICK = 12` books, rotating cursor) so a catalog this size costs at most 24 house-bucket reads per tick instead of 1,400, and a full sweep is an hour at the default poll |
| Maker funding | `Exchange.fundHouseLive`, `!exchange fund <regular> <nx|meso|ticker> <amount>` | the house cannot quote an ask without real items, so an item asset is accepted exactly when the maker is on (otherwise `BAD_REQUEST`, exactly 1.6.3's refusal); the character's bags are written in the same batch (`inventoryWriter`); the offline `fundHouse` still refuses item assets because there is no offline item wallet |
| Sell cap | `Exchange.sellCap`, `ExchangeStore.itemExposure`, JDBC + memory stores | counts an account's item-sell fills since UTC midnight plus the mesos its open item sells still hope for; refused at `place()` with `ITEMS_CAP`; NX and MESO are not item sells |
| Site payload | `ExchangeSite.tickerJson` | each ticker now carries `"category"` (`nx`, `meso`, `leaf`, `scroll`, `use`, `etc`, `item`) for the page's filter |
| Ledger | `Ledger`, `Exchange`, `JdbcExchangeStore`, the wallets, `volatility/*` | `Ledger.Asset` enum deleted; the ledger reads and writes `Asset.id()`, and every read-back goes through `AssetRegistry.of(String)`, so 1.6.3 rows (`NX`, `LEAF`, `CHAOS`, `WS`, `ELIXIR`) and catalog rows (`S…`, `U…`, `E…`) resolve the same way |

Tests: `ExchangeCatalogTest` (8 cases), `volatility/ExchangeItemMakerTest` (2 cases), plus the existing Economy suite.
The maker test pins that the house refuses item funding with the maker off, that it holds 20 rocks and 5M mesos after
funding, that one `tickRegulars()` posts a bid under and an ask over the seeded 10,000 fair value, and that a second
pass adds nothing while both sides rest. The catalog test pins the file's shape (no
twin book, every id ≤ 8), the scroll caps 1/3/10, the `etc` switch, the `tickers` narrowing, anchor-0 rows, the site's
category key, and the daily ceiling both at placement (resting exposure) and after a fill.

Migration: none. The four ledger columns are already `varchar(8)` and no legacy id changes.

Not done, on purpose: the 238 untradeable and GM scrolls (§9), the 109 unpriced ETC rows' prices (§10), the website's
category filter itself (the payload key is there; the page is the Website session's), any deploy.
