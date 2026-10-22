# 56: Cap what bots pay real players, and a bot wealth ceiling

**Area:** Economy
**Kind:** BUILD
**Effort:** S (part A), S-M (part B)
**Depends on:** 15 (yield caps, live). Part of ticket 38's safety rules, pulled forward.
**Status:** open. Recommendation only (the owner deprioritised economy work). Part A should land before real
players open FM shops.

## Today (live, 2026-10-21)

- **Bot wealth:** 3,488 stored bots hold **17.69 billion mesos**. Average 5.07m, max 19.1m; average 11.7m at
  level 90+.
- **Bot intake:** 7.83 billion on 2026-10-20 (1,515 bots, the daily cap of 9.6m reached at the top), all from mob
  mesos at 10x through `BotYieldCaps`.
- **Players:** 7 characters with 27.9 million mesos between them, 0 player shops, 0 hired merchants.
- **Bot sinks:** pots and ammo (`BotShopping`), fares (`GCTravel`), the 4th-job package. Small next to intake.
  Nothing caps how much a bot holds.
- **`FMBot` buys from any shop in the FM rooms, players' included:**
  - It buys when ask ÷ YAML value ≤ 1.2, with 100% odds at ≤ 0.7, falling to 5% at 1.2.
  - There is no daily, per-player or per-item cap. Any item listed near its YAML value turns into mesos from the
    bots' 17.7 billion.

## What

### A. Bot purchase cap from real players (S)

- Count `FMBot` purchases from non-bot shops (`PlayerShop` and non-artificial `HiredMerchant`) in a
  `BotYieldCaps`-style meter:
  - a per-seller daily cap (for example 2m × meso rate);
  - a world daily cap;
  - a per-item-id daily cap.
- Bots buy only items a bot would use (ticket 10's gear score, ticket 09's supply list) or ones on a short whitelist
  (scrolls, ores), and at most once per item per bot per day.
- Log the totals and add them to `!botyield`.

### B. Wealth ceiling (S-M)

- Per level band, mesos above a ceiling (for example 3x a band savings goal: 1m at level 30, 5m at 70, 15m at 120)
  are burned through an in-world sink at the next town visit: NPC gear, scroll failures and fares, or simply not
  credited.
- Daily mob intake stops once a bot is at its ceiling. The cap check already exists.
- Tune with the owner. The goal is a flat `SUM(Meso)` in `botstore` over a week.

## Done when

- **A:** a test seller listing 100 items at book value is paid at most the daily cap. `!botyield` shows the meter.
- **B:** `SUM(botstore.Meso)` stops growing week over week with the full population, and bot purchases still
  happen.

## Risks

- **Too tight a cap kills the "my stuff sold!" feeling:** start generous and log first.
- **Burned mesos are invisible:** keep them in the ledger so the dashboard (ticket 35) can show faucet vs sink.

## 1.4.2 follow-up: FM bots actually shop at players' shops (branch `economy-fm-buyers-142`)

Owner QA (`!qa run fm-1`, 2026-10-21): 450 FM_BUY events in 90 s, none in the GM's shop, no successful buy at all.

- **Discovery:** an FM bot built its shop list once, on walking into a room, and walked it in a shuffled order, so
  a shop opened after it arrived was never seen and an existing one could be 20 shops away. Now real players'
  shops go first, the room is re-checked after every shop (a new one is next in line), and a bot comes back to a
  given real shop at most once a minute. It enters only while a visitor slot stays free for players after it,
  only one FM bot sits in a real shop at a time, and it leaves after one browse beat. A bot that cannot sit down
  does not buy.
- **Equip pricing:** the item pool prices only chased equips; every other equip had no normal value, so bots
  skipped all of them. `EquipValuation` now values them from the required level, the stats over the clean WZ
  stats, slots left and slots burned (never under 2x the NPC price, capped at 8m; cash and untradeable: none).
- **"buy failed ... bot shop":** FM bots never earn (they start at 0 mesos), so every purchase failed at
  `spendMeso`. An FM bot now tops up exactly the shortfall of the item in hand from a 10m/day allowance
  (`FMBotWallet`); a top-up that does not end in a purchase is taken back. A full equip tab lets go of the
  oldest equip the bot bought. The QA log says why instead of "buy failed".
- 56A is unchanged: 1.0x normal value ceiling and 5m per seller account per day for real players.

## 1.5.0: B built, the wealth ceiling (branch `economy-gacha-150`)

`soloMapling.ArtificialPlayer.BotWealth`:

- **Ceiling** = `max(100,000, 120 x level^2) x meso rate`: at 10x 1.08m (lv 30), 5.9m (70), 9.7m (90), 17.3m (120).
  Hard line 2x the ceiling.
- **Intake:** between the ceiling and the hard line, mob mesos (`BotYieldCaps.takeDrop` / `takeMesos`) are cut in
  proportion (a meso drop is taken with that chance, an offscreen share is cut); at the hard line they stop. What is
  refused stays on the floor for players.
- **Spending above it** (TrainingBot town stop and store browse, about once a minute):
  - gachapon tickets (`BotGacha`, 1m each, up to 11 a day);
  - half the excess on top of the scroll budget (`ScrollBudget.maxPrice`, still 12 buys a day);
  - upkeep: 10% of what is still over, burned, at most every 10 minutes.
  Potions/ammo are not limited by a rich bot's mesos already (its kit is), so unchanged.
- **Read-outs:** `!botwealth` (bands, counts over, ledger), `!botwealth <bot> [spend]`, the ledger line in `!botyield`.
  QA: `!qa run ga-2`.
- **Properties:** `-Dsolomapling.botWealthCeiling=false`, `botWealthPerLevelSq` (120), `botWealthFloor` (100000),
  `botWealthHardFactor` (2.0), `botWealthDecayShare` (0.10), `botWealthDecayMinutes` (10).

**Measured 2026-10-21 (test DB, read-only), ceiling at 10x:**

| Schema | Bots | Mesos | Over ceiling | At hard line | Over by | After convergence |
|---|---|---|---|---|---|---|
| `mapleroot_soak` (live-like) | 3,510 | 20.9b | 2,288 (65%) | 1,322 | 10.0b | 10.9b (-48%) |
| `mapleroot_persist` (owner's test server) | 2,836 | 239.5b | 1,801 | 1,616 | 230.7b | 8.9b |

`mapleroot_persist` is an outlier: 1,242 bots hold over 25m (234b between them), all created 2026-10-19, while the
yield meter shows at most 12.6m per bot per day. That cannot come from metered mob mesos; it predates the meter or
came from a run with the caps off. Worth a look before reading its economy numbers.

## 1.5.0 rc3: world budget, fair prices, resale (branch `economy-trade-150`)

Owner decision 2 (2026-10-21 grill), see docs/release/1.5.0-economy-trade.md:
- **World budget:** bots together pay real players at most `botBuying.worldDailyBudget` (40m) a day, persisted in
  `economyday` (migration 020). This covers FM shopper purchases and direct trades, on top of 56A's 5m per seller.
- **Price:** for a player's item the FM shopper uses the price board's fair value (the 7-day median of real trades,
  else the static value).
- **Gear:** from players, bots take gear only when it is above average (`GearGrade`). Godly gear and average gear stay
  on the market.
- **Resale:** what bots buy from players moves to the resale pool. Merchant-minded bots resell it in their own FM
  shops at +15%.
