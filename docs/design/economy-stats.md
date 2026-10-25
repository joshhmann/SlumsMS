# Economy stats data layer (1.6.0 Track D)

Written 2026-10-25 for SlumsMS 1.6.0 Track D ("economy stats and balancing").

The owner requested economy telemetry to answer: *"we gotta look into economy stats and figure out balancing"*.
This design specifies the single underlying data layer that feeds three distinct consumers:
1. **Private Admin Dashboard (Ticket 35):** Full view with graphs, drill-down by character ID, anomaly alerts (meso faucets, price crashes, duplicates).
2. **Public Basil Market (Ticket 79) & Hidden Street (Ticket 78):** Item prices (median, p25, p75), active FM listings, aggregate meso supply and level band distribution (strictly anonymous).
3. **Balancing Reports:** Periodic analysis of sources vs sinks, excessive faucets (e.g. high-level mob drops or specific quest rewards), and weak sinks to guide rate/drop tweaks.

---

## 1. Survey of existing economy systems

SlumsMS already has several partial economy mechanisms and telemetry hooks across the codebase:

### 1.1 Bot logs (`[Wealth]`, `[Scroll]`, `[Shop]`, trades)
- **`[Wealth]` (`soloMapling.ArtificialPlayer.BotWealth`):**
  - Logs town-stop upkeep burns: `"[Wealth] %s paid %,d upkeep (%.0f%% of %,d over its ceiling %,d); holds %,d"`.
  - Maintains in-memory lifetime atomic counters: `intakeRefused` (drops left on floor), `intakeCutMesos` (offscreen mesos cut), `decayed` (mesos burned), `decayEvents`.
- **`[Scroll]` (`soloMapling.ArtificialPlayer.BotScrollSystem.BotScrolling`):**
  - Logs bot scroll purchases and use: `"[Scroll] %s (lv %d) bought %d for %d from %s"`, `"[Scroll] %s {}: {}"`.
  - `ScrollLedger` tracks daily scroll counts and expenditure per bot level band.
- **`[Shop]` (`soloMapling.ArtificialPlayer.BotSupplySystem.BotShopping`):**
  - Logs bot purchases at NPC shops: `"[Shop] %s (lv %d %s) at npc %d on map %d: %s for %d mesos (%d left)"`.
- **Trades (`soloMapling.Economy.BotDirectTrade`, `server.Trade`):**
  - Logs direct trades between players and bots; tracks traded mesos and items.

### 1.2 BotWealth (`soloMapling.ArtificialPlayer.BotWealth`)
- Defines a soft meso ceiling per bot level: `max(floor, perLevelSq * level^2) * mesoRate`.
- Above the ceiling:
  - Mob meso intake is throttled, refusing floor drops at 2x ceiling (`intakeFactor`).
  - Excess wealth feeds Gachapon ticket buying (`BotGacha`).
  - Regular upkeep burns 10% of excess over ceiling periodically during town stops.

### 1.3 Free Market & merchant systems
- **`ArtificialFreeMarket` (`soloMapling.FreeMarket.ArtificialFreeMarket`):**
  - Spawns bot merchants in FM rooms (`HiredMerchantArtificial`, `PlayerShopAdapter`).
- **`HiredMerchant` & `PlayerShop` (`server.maps`):**
  - Real and bot shops registered in `World.getActiveMerchants()` and `World.getActivePlayerShops()`.
  - Active shop items represent the live market supply.
- **`MesoSinks` (`soloMapling.Economy.MesoSinks`):**
  - `onShopSale()` intercepts completed FM transactions.
  - Imposes FM shop tax (max of v83 trade fee and configurable `sinks.shopTaxPercent`, default 3%).
  - Forwards sales to `PriceBook` and books tax to `EconomyMeters.TAX`.

### 1.4 Economy-trade work (Migration 020)
- **`pricesamples` table:**
  - Persists completed item trades (`ItemId`, `UnitPrice`, `NormPrice`, `Quantity`, `Source`, `PlayerSide`, `At`).
  - Used by `PriceBook` for median and percentile queries over a rolling window (7-14 days).
- **`economyday` table & `EconomyMeters`:**
  - Daily ledger for macro meters: `botbuy` (bots buying from humans), `gifts`, `giftmesos`, `tax` (FM tax collected), `godlies`, `botshopsales`, `sinkshop` (cosmetic merchant sales).
- **`botgifts` table:**
  - Tracks friend-bot gift timers per account to cap outbound gift transfers.

### 1.5 Dashboard tooling & `DashboardMapExport`
- **`DashboardExporter` (`soloMapling.dashboard`):**
  - Runs a background daemon every 60s (if `-Dsolomapling.dashboard=true`) writing `live.json` and rolling series files to `reports/dashboard/`.
- **`EconomyFeed` / `DerivedEconomy`:**
  - Currently estimates intake by observing tick-to-tick delta in bot meso balances per map.
- **`DashboardMapExport`:**
  - Generates static JSON/SVG map topology and NPC coordinates for dashboard rendering.

### 1.6 Cash Shop logs & special currencies
- **`CashShop` (`server.CashShop`):**
  - Manages NX Credit (`gainCash(1, ...)`), Maple Points (2), and NX Prepaid (4).
  - NX sources: mob NX card drops (100 / 250 NX), daily check-in rewards (`DailyCheckinRewards`).
  - NX sinks: `CashShopWindowPurchase.buy()` purchases cosmetics and rate coupons.
- **Maple Leaf (`4001126`):**
  - Special server currency used for Rooty / Donor shop rewards (`donorshop.js`, `rooty.js`), mastery books (`masterybooks.js`), and QoL items (`qolitems.js`).
  - Sources: boss PQ events (`scripts/event/*Battle.js`), exchanges (`2131003.js` - `2131007.js`), and mob drops (capped at 4 in `MapleMap.java`).
  - Historical bug in 1.5.2 fixed: NPC shop sell price was previously 500,000 mesos; corrected to stock 1 meso.

---

## 2. Telemetry metrics & gaps

| Category | Metric | Current source / status | Gap & resolution |
|---|---|---|---|
| **Meso supply** | Total human mesos & bot mesos | Partial (online only via `CharacterStorage` / `World.getPlayerStorage`) | Need total universe snapshot: query `characters` (humans) and `botstore` (bots) plus in-memory online overrides. |
| **Meso supply** | Breakdown by level bands (1-30, 31-70, 71-120, 121-200) | Only in GM commands (`!botwealth`) | Aggregate bot and player meso counts and sum into fixed level bands. |
| **Wealth distribution** | Top 1% share, top 10% share, Gini coefficient | None | Calculate exact Lorenz curve / Gini formula and top-decile/percentile shares for humans and bots separately. |
| **Sources (faucets)** | Mob meso drops | Only bot yield tracked in `BotYieldCaps` | Missing human mob drops: add lightweight atomic counter on `Character.gainMeso` during floor/party drop pickup. |
| **Sources (faucets)** | NPC sell (vendor sales) | Not aggregated | Hook `Shop.sell()` with an atomic counter for mesos paid to players. |
| **Sources (faucets)** | Quest rewards | Not aggregated | Hook `MesoAction.java` / `NPCConversationManager.gainMeso()` when positive. |
| **Sinks (drains)** | NPC buy & recharges | Not aggregated | Hook `Shop.buy()` and `Shop.recharge()` with atomic counters. |
| **Sinks (drains)** | Job Master fees | Present in `JobMasterFees` & `9010003.js` | Add atomic counter when Job Master charges fees. |
| **Sinks (drains)** | Upkeep burn | Tracked in `BotWealth.decayed` | Expose delta since last snapshot. |
| **Sinks (drains)** | FM / trade taxes | Tracked in `EconomyMeters.TAX` | Expose delta since last snapshot. |
| **Sinks (drains)** | Cosmetic / sink shop | Tracked in `EconomyMeters.SINK_SHOP` | Expose delta since last snapshot. |
| **Transfers** | Direct player/bot trades | Session logs only | Add atomic counter for trade meso volume in `Trade.java` and `BotDirectTrade.java`. |
| **Transfers** | FM sales | Logged in `MesoSinks.onShopSale` | Add atomic counter for gross GMV (gross merchandise value) in FM sales. |
| **Market prices** | Per-item median, p25, p75, count | `PriceBook` has memory samples & `pricesamples` | Combine `pricesamples` trade history with active FM listings (`PlayerShopItem` across active merchants/shops). |
| **Special currencies** | Maple Leaf (4001126) flows | Event scripts & NPC scripts | Track atomic counters for leaves awarded vs leaves redeemed. |
| **Special currencies** | NX flows | `CashShop.gainCash`, `CashShopWindowPurchase` | Add atomic counters for NX earned (faucets) and NX spent in Cash Shop (sinks). |

---

## 3. Architecture & snapshot job

### 3.1 Non-blocking design & performance
- **Thread safety:** All event hooks update thread-safe `LongAdder` or `AtomicLong` counters. Zero locks, zero database queries, and zero I/O on the game threads ($O(1)$ per event).
- **Asynchronous snapshot:** A single background daemon thread (or scheduled task on `WorldTimer` / `ScheduledExecutorService`) takes the snapshot off-peak (midnight UTC / 00:00 server day) without stalling channel loops.
- **On-demand execution:** GM command `!econstats` runs the snapshot job asynchronously and prints a concise yellow summary in chat.
- **Fast database reads:** Roster queries read indexed columns (`SELECT id, level, meso FROM characters WHERE world = ?` and `SELECT BotId, Level, Meso FROM botstore`), completing in <20 ms for 10k rows.

### 3.2 Output files & public/private separation
Snapshots are written atomically to `Server/data/econ/` (configurable via `-Dslums.econ.dir` or property `econDir`):
1. **Private file (`YYYY-MM-DD-private.json`):**
   - Full audit data.
   - Top wealth lists (character IDs and bot IDs allowed; no sensitive credentials/IPs).
   - Detailed faucet and sink breakdowns.
2. **Public file (`YYYY-MM-DD-public.json`):**
   - Strictly aggregated metrics: level bands, population counts, total meso supply, Gini coefficient, percentiles.
   - Market item prices (median, p25, p75, sample counts).
   - **Zero player names, zero character IDs, zero account IDs.**
3. **Convenience pointers:** Atomic update of `latest-private.json` and `latest-public.json` symlinks or file copies for quick consumption by dashboard and web frontends.
