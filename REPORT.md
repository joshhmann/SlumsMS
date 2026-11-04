# REPORT: ticket 270, the exchange item catalog

**Result:** done. Branch `164-excatalog`, worktree `/root/work/SlumsMS-164-excatalog` (from `release-1.6.4`
`6d18f961e`), on top of `b35d0aeee` + `b074509e4`. Not pushed, not merged, nothing on TEST or live, no migration.

**What changed:**
- `Economy/exchange/AssetRegistry.java` (new) — `Asset` is a registry, not the six-value enum: id ≤ 8 chars (the live
  columns are `varchar(8)`), item id, name, category (NX/MESO/LEAF/SCROLL/USE/ETC/ITEM), anchor. `Asset.NX` …
  `Asset.ELIXIR` keep their names, so `S2049100` → `CHAOS` and `U2000005` → `ELIXIR`: no data migration.
- `Economy/exchange/ExchangeLimits.java`, `ExchangeCatalog.java` (new) + `itemPool/itemConfig/exchangeTickers.yaml`
  (new, 709 rows: 518 scrolls, 27 use, 164 ETC; three fold into a 1.6.3 book, so 706 new tickers open).
- `Ticker` (catalog tickers under `-Dslums.exchange.catalog`, per-row caps, `-Dslums.exchange.tickers` still
  narrows), `FairValue`/`MarketFlows`/`MarketTraders`/`MarketVolatility` (registry instead of hardcoded lists; LEAF's
  band unchanged), `Exchange` (item maker, daily item-sell ceiling, item funding for the house), `ExchangeSite`
  (`category` per ticker), `Ledger`/`ExchangeStore`/both stores (`itemExposure`, registry assets), and
  `NxExchangeCommand` (`!exchange fund <regular> <nx|meso|ticker> <amount>`).
- The owner's follow-ups folded in: the item maker quotes from every regular (`Regulars.quotes`, the same ±3/6/9%
  ladder as NX), bot traders now offer the ETC they hold most as well as the scroll they hold most
  (`MarketTraders.heldItem`), and `volatility/MarketEventPressure` feeds the world events into trader pressure
  (invasion → USE up, treasure hunt → ETC down, a handout floods its item, a quest consumes the item it asks for).
- Docs: ticket 270 §11, `113-nx-exchange.md`, `tickets/README.md`, `docs/economy/exchange-item-catalog.md`.

**Proof:** `./mvnw -o -Dtest='soloMapling.Economy.**' test` — Tests run: 512, Failures: 0, Errors: 0, Skipped: 10
(+8 `ExchangeCatalogTest`, +3 `volatility/ExchangeItemMakerTest`, +5 `volatility/MarketEventPressureTest`; 496 before
the branch). Fail-before: on
`release-1.6.4` the new tests do not compile (`AssetRegistry`, `ExchangeCatalog`, `Ticker.fromSystem(config)`,
`store.itemExposure`, `makerQuotes` absent), so the failure is a compile error rather than a red run.

**Economy:** 706 new tickers open (709 rows minus three legacy folds); 203 of them anchored from the item pool (121 scrolls incl. `darkscrolls.yaml` v39, 27 use, 55 ETC), 397
scrolls sibling-derived by slot/band, 109 ETC at anchor 0 (player-priced only). Owner's caps applied: scroll
`1/3/10`, use `1000/10/20`, ETC `10000/10/20`, item-sell ceiling 20,000,000 mesos per account per UTC day, maker on
(5 units, 12 books a tick, house-funded with real items — mints nothing), ammo excluded.

**Not done, on purpose:** 238 scrolls that can never be tickers (209 `tradeBlock`, 29 GM), the 109 anchor-0 prices,
the website's filter UI, `1.6.4.conf` flags, TEST proof, merge, deploy.

**Next:** owner answers (a) tighten `maxPrice` to 2× anchor for scrolls, (b) price the 109 ETC rows or leave them
player-only; then cut over with `-Dslums.exchange.catalog=scrolls,use,etc -Dslums.exchange.itemMaker=true
-Dslums.exchange.items.sellCapMesosPerDay=20000000`.
