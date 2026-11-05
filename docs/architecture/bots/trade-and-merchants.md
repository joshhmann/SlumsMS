# Trade and Merchants

## Purpose

A bot's money and its shop, in one subsystem. Three separate trade implementations exist and each has its own contract:

| Path | Who trades | Implementation | Contract |
|---|---|---|---|
| hired-merchant shops | grinding bots that are "merchant minded" | `BotShops` opens a `HiredMerchantArtificial` in an FM room | the shop is a real shop object; a player buys through `HiredMerchant.buy` |
| direct trades, bot selling/buying in person | grinding, social, wandering, following and staged bots | `BotDirectTrade` + `TradeSession` + `PriceBand` | every price is inside a band the chat brain cannot move |
| window trades, merchant types | `SellingMerchantBot`, `BuyingMerchantBot`, `NXMerchantBot` | the legacy `BotTradeSM` state machine driven from `BotSM` | the bot advertises what it wants and posts mesos for it |
| hand-overs between companions | owned alts, party-bot friends | `BotGiveTrade` | every item goes through `Trade.putFromInventory`; nothing is teleported |

`BotShops`'s own docblock states the whole shop design in six bullets: who (about `botShops.ownerPercent` of grinding bots, "merchant minded", fixed per bot id), where (FM rooms 1-12 on channels 1-3, each new shop to the channel furthest under its soft share), what (the bot's own loot plus the resale pool of items bots bought from players, never what `SaleRules` refuses), prices (board fair value × markup, never under twice the NPC price), money (the buyer's price minus the FM tax, collected at the bot's next town stop), closing (sold out or time up; unsold goes back to the bot or the pool) — `src/main/java/soloMapling/Economy/BotShops.java:44-59`.

The pricing rule is deliberately not in the chat model: `PriceBand` decides, and "the chat brain only words what this class decided; nothing a player types can move a bot outside the band" — `src/main/java/soloMapling/Economy/PriceBand.java:5-16`.

## Entry points

| Kind | Entry point | file:line |
|---|---|---|
| tick | `BotSM.tickRunnable` → `updateState()` | src/main/java/soloMapling/ArtificialPlayer/BotSM.java:77-103, :286 |
| tick scheduler | `BotTickService.register(botId, tick, delay, period)`, 100 ms wheel | src/main/java/soloMapling/server/BotTickService.java:89; comment :13-33 |
| tick registration | `BotTickService.register(getChr().getId(), tickRunnable, …)` | src/main/java/soloMapling/ArtificialPlayer/BotSM.java:375 |
| town stop | `BotShops.townTick(getChr())` after the scroll/wealth town ticks | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1574, :1731 |
| trade accepted | `BotSM` RUNNING sees a verified partner → `tradeInitialized(mode)`, `holdForCommitment` | src/main/java/soloMapling/ArtificialPlayer/BotSM.java:309-320 |
| trade ticked | `BotSM` TRADING → `cleanupTradeState()` / `updateTradeSM()` | src/main/java/soloMapling/ArtificialPlayer/BotSM.java:340-348, :601 |
| invite queue | `Trade.inviteTrade` → `BotTradeQueue.addTradeRequest` + `BotDirectTrade.onInvite` | src/main/java/server/Trade.java:682-683 |
| window chat | `Trade.chat` → `BotDirectTrade.onWindowChat` | src/main/java/server/Trade.java:369 |
| chat outside a window | `BotChatActions.trade` → `BotDirectTrade.chat` (merchant bots answer from `MerchantStock`) | src/main/java/soloMapling/ArtificialPlayer/BotChatActions.java:233; src/main/java/soloMapling/ArtificialPlayer/MerchantStock.java:41,54,70 |
| GM command | `!economy` (gm4) — status, `shop [bot]`, `close`, `gift`, `quote`, `price`, `tax`, `haggle`, `reload` | registered src/main/java/client/command/CommandsExecutor.java:434; docblock src/main/java/client/command/commands/gm4/EconomyCommand.java:20-33; `shop` case :54-60 |
| GM command | `!botwhy <name>` (gm3) — life decisions, not trade | src/main/java/client/command/CommandsExecutor.java:383 |
| GM command | `!env spawnfmbots` / `spawnmerchantbots` | src/main/java/client/command/commands/gm4/EnvironmentCommand.java:381-386 |
| spawn | waves spawn FM shoppers and merchant bots | src/main/java/soloMapling/Environment/EnvironmentManager.java:128-136, :157-160; batch builders :546-560; :604-618 (45 FM bots), :620-650 (merchants), :666-673 (gacha) |
| spawn gate | boot populates only with `-Dsolomapling.autopopulate=true` | src/main/java/soloMapling/server/MapleRootSolo.java:51-53 |
| type factory | `BotTypeManager.BotType` FM_BOT / SELLING / BUYING / NX_MERCHANT / GACHA | src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:61,75,82,89,96 |
| YAML | `src/main/java/soloMapling/Economy/economy.yaml` — bands 22-32, botShops 35-49, botBuying 52-56, directTrade 59-61, botBotTrade 65-73, gifts 76+ | src/main/java/soloMapling/Economy/EconomyConfig.java:27-28 |
| `-D` | every economy key is overridable as `-Dsolomapling.economy.<key>=<value>`; `!economy reload` re-reads | src/main/java/soloMapling/Economy/economy.yaml:1-6 |

## How it works

### Bot shop types

| Type | What it does | Switch |
|---|---|---|
| `BotShops` hired merchant (grinding bot, merchant minded, level ≥ 15, ≥ 3 sellable items, < 8 open world-wide) | lists up to 12 items in an FM room 1-12 on channels 1-3, own loot at fair value and pool items at +15%, min 2× NPC price; stays 6 h | `botShops.enabled` (src/main/java/soloMapling/Economy/economy.yaml:36), `botShops.maxShops` (:37), `botShops.ownerPercent` (:38), `botShops.minLevel` (:39), `botShops.minItems` (:40), `botShops.maxItems` (:41), `botShops.maxOwnItems` (:42), `botShops.hours` (:43), `botShops.lootMarkup`/`resaleMarkup` (:44-45), `botShops.resalePoolMax` (:46), `botShops.channels`/`channelTargets` (:48-49) |
| `FMBot` (FM shopper) | walks FM rooms 1-12, enters shops, buys at most one item per shop visit from a player's shop or an artificial merchant; never from a bot's own listing | always on as a type; its money comes from `FMBotWallet` (`DAILY_ALLOWANCE` 10m/UTC day, src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBotWallet.java:27) |
| `SellingMerchantBot` | stands in the FM entrance, stocks one item at a time (`generateScrollsList`/`generateDarkScrollsList`/`generateThiefStarsList`/`generatePotionsList`), advertises, waits for a trade window, asks its price | `-Dslums.bots.merchantpoolstock` switches stock to the shared resale pool (src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:45; used src/main/java/soloMapling/ArtificialPlayer/BotTypes/SellingMerchantBot.java:112-115, :216-224) |
| `BuyingMerchantBot` | advertises that it buys one item, offers 80-90% of its market value in the window; 1 % chance per idle turn to convert into an NX merchant | no own switch; `-Dslums.bots.merchantpoolstock` does not apply |
| `NXMerchantBot` | advertises "10k nx cash code, 50m TRADE ME", takes the 50m in a window, then delivers the code by notes and messenger | none; conversion from `BuyingMerchantBot` at src/main/java/soloMapling/ArtificialPlayer/BotTypes/BuyingMerchantBot.java:198-204 |
| `GachaBot` | a show, not a shop: pops a prize from the town's real gachapon pool next to itself and emotes at real players' rolls | none; spawned at Henesys market (src/main/java/soloMapling/Environment/EnvironmentManager.java:666-673) |
| `BotGiveTrade` | a companion hands items/mesos to its owner, a sibling alt, its party leader or a friend through a real window | `COMPANION_TRADES_ENABLED` / companion order switches (src/main/java/config/ServerConfig.java:113; src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSwitches.java:93) |

### A sale in a bot's FM shop (numbered)

1. `BotShops.townTick` runs at every town stop, throttled to one try per 2-4 minutes by `nextTryAt`, and first calls `collect` and `PendingGrants.deliver` — src/main/java/soloMapling/Economy/BotShops.java:131-153.
2. `openOnce` builds stock: its own bag via `sellable` priced by `askFor(item, LOOT, 0)` up to `maxOwnItems`, then the resale pool under `synchronized (resale)` up to `maxItems` — :211-236. Anything `SaleRules.whyNotListOwn` refuses is skipped — :230-234.
3. Fewer than `botShops.minItems` (3) items → the pooled ones go back and the bot says "has too little to sell" — :237-243.
4. Channel and spot: `pickChannel` scores the channel furthest under its soft target; `findSpot` shuffles the 12 FM room ids, respects `BotMapCap.hasRoom`, and rejects any spot within 70 px (x) of another shop object — :244-251, :404-462.
5. `spawnMerchant` gets the channel's shared headless `BotClient`, makes a `Character.getDefault`, registers a `HiredMerchantArtificial` (skin 5030000, owner id = bot id) and broadcasts the box — :469-489.
6. The bot's own items leave `BotHoldings` only **after** the shop exists (`destroyHeldItem` then `merchant.addItem` then `listings.put`); a refused listing is handed back via `PendingGrants.giveBack` — :263-278.
7. `shops.put` + `startSweeper` (a 5-minute `scheduleAtFixedRate`, once) — :279-280, :636-646.
8. A player buys: `HiredMerchant.buy` takes the mesos, computes `fee = MesoSinks.shopFee(price)`, books `MesoSinks.onShopSale`, and calls `BotShops.onSold(pItem, price)` for a non-persistent shop — src/main/java/server/maps/HiredMerchant.java:352-377.
9. `onSold` drops the listing, adds the net price to `owed.merge(botId, …)`, books `EconomyMeters.BOT_SHOP_SALES`, logs `SHOP SOLD … owed to the bot`, and closes the shop asynchronously when the shelf is empty — src/main/java/soloMapling/Economy/BotShops.java:514-532.
10. The bot is only paid at its next town stop: `collect` takes `owed`, calls `BotHoldings.grantMeso`, and re-merges what the wallet could not hold — :155-170.
11. Closing (`close`): each unsold item is withdrawn, then granted back to the bot if online, or offered to the resale pool, or held in `PendingGrants` "until it has room"; the merchant is force-closed and removed from the channel — :594-634.

### A haggle (numbered)

1. The player types a line in chat or in the open trade window. In a window `Trade.chat` routes it to `BotDirectTrade.onWindowChat`, which looks up the live session, re-reads the line off the tick thread and calls `session.onChat(read)` — src/main/java/server/Trade.java:369; src/main/java/soloMapling/Economy/BotDirectTrade.java:341-362.
2. `HaggleBrain.read` decides the move: rules first (a price → `OFFER`; "no" → `REFUSE`; "how much" → `ASK_PRICE`; "ok" → `ACCEPT`), and only a line the rules leave as `OTHER` goes to the `ChatDecide` classifier, needing `bands.decideMin` (0.5) confidence — src/main/java/soloMapling/Economy/HaggleBrain.java:124-159.
3. Outside a window the same reading feeds `BotDirectTrade.chat`, which keeps a per-(bot, player) `Quote` for 5 minutes (`QUOTE_MS`) — src/main/java/soloMapling/Economy/BotDirectTrade.java:61, :369-478.
4. With no quote open, an item name matches the bot's `BotShops.sellable` list, and the opening price is `PriceBand.opening` of the `BOT_SELLS` band — :443-460; src/main/java/soloMapling/Economy/PriceBand.java:57-61.
5. An offer inside a window becomes `TradeSession.haggle`: `PriceBand.respond(band, price, theirs, rounds)` returns `ACCEPT`, `COUNTER` or `FINAL`, the round counter increments, and a `BOT_BUYS` session raises the posted mesos through `raiseTo` — src/main/java/soloMapling/Economy/TradeSession.java:206-226.
6. `raiseTo` refuses when the new total passes `wallet.spendable()` ("cant afford that rn sry") or when the caps refuse (`BotSellerCaps.tryReserve` + `EconomyMeters.tryBotBuy`, "im done buying stuff for today, sry") — :229-256; src/main/java/soloMapling/Economy/BotDirectTrade.java:654-691.
7. After `bands.maxRounds` (4) counters the answer is `FINAL` at the band edge — src/main/java/soloMapling/Economy/PriceBand.java:92-118.
8. The bot only confirms when the money and items actually match: `tickSelling` confirms when the partner locked and paid ≥ price, `tickBuying` re-values the partner's window on every signature change and cancels when the posted mesos no longer fit the band — src/main/java/soloMapling/Economy/TradeSession.java:164-181, :109-162.
9. On success `BotDirectTrade.onSuccess` writes a price-board sample (`PriceBook.recordBetween` with both parties) or, for a bot purchase, splits the paid amount across the bought items and hands each to `BotBuying.toResale` — src/main/java/soloMapling/Economy/BotDirectTrade.java:311-334.

### The merchant-type window (BotTradeSM)

`BotTradeSM.update` is a ten-state machine: `INITIALIZE` posts the items for sale (or the wants message in buying mode), `RESPONDING` restates what it wants, `WAITING_RESPONSE` accepts when `isSufficientToAccept()` (or declines when the partner locked and the offer is short), `CONFIRMING` accepts and confirms, `CONFIRMED_LOCKED` waits for the partner to verify, `CLEANUP` speaks "Thank you!"/"Why did you decline?", `TIMED_OUT` declines after 2 s, and a 60 s timeout is checked after every state — src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotTradeSM.java:107-217, :53, :91. Bot mesos are real (`Trade.setMeso` debits them), bot items in that window are generated copies that are never credited back — src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotTradeCommands.java:19-27.

`NXMerchantBot` takes the 50m first and only then mints the code, so every failure path still ends with the buyer holding NX: code minted and credited to the Cash Shop if it cannot be stored, a copy in the buyer's notes, a messenger invite that types the code, and a note pop or whisper if the invite is not accepted within 30 s — src/main/java/soloMapling/ArtificialPlayer/BotTypes/NxCodeDelivery.java:3-20, :83-127.

### Bot-to-bot and companion trades

`BotBotTrade` matches two bots' offers at the meeting point of the two bands (inside `botBotTrade.minPrice`/`maxPrice`), caps them at 3 per bot per UTC day and 30 world-wide per hour, and only ever moves ETC stacks or potions — src/main/java/soloMapling/Economy/BotBotTrade.java:95-97, :184-205; keys `src/main/java/soloMapling/Economy/economy.yaml:65-73`. `BotGiveTrade` refuses population bots outright, because their holdings live in `BotHoldings` and a window cannot hand those over yet — src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotGiveTrade.java:17-30, :185-187; call sites src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionItems.java:140,181,364,406.

## Data

| State | Where | Lifetime |
|---|---|---|
| open shops, listings, `owed`, resale pool, `nextTryAt` | `BotShops` static maps/deques — src/main/java/soloMapling/Economy/BotShops.java:100-108 | memory only; a restart loses the shops, the pool and what was owed (:57-58) |
| `record Listing(botId, botName, kind, price)` / `record Resale(Item, long paid)` / `record ShopStock(...)` | :74-75, :97-98, :683-684 | as above |
| live direct trades, quotes | `BotDirectTrade.live` / `quotes` (bounded, pruned) — src/main/java/soloMapling/Economy/BotDirectTrade.java:88-92 | memory; a 5-minute quote expiry |
| FM shopper allowance | `FMBotWallet.usedToday` (botId → {UTC epoch day, used}) — src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBotWallet.java:33 | memory; a restart resets the day |
| seller caps (1.0× fair, 5m per seller account per day) | `BotSellerCaps` — src/main/java/soloMapling/ArtificialPlayer/BotSellerCaps.java:39-42, :91 | persisted under the `STORE_KEY_BASE` key |
| bot wallet | `Character` mesos + `BotHoldings` ledger (INITIAL_MESO 0) — src/main/java/soloMapling/ArtificialPlayer/BotStore.java:129; src/main/java/soloMapling/ArtificialPlayer/BotHoldings.java:258,272,295,302 | the bot's stored row |
| debit journal | TSV at `System.getProperty("solomapling.botDebitJournal", "data/bot-debit-journal.tsv")` — src/main/java/soloMapling/ArtificialPlayer/BotDebitJournal.java:51-54 | file, atomic tmp+move (:203-225) |
| price board samples | `PriceBook` (`src/main/java/soloMapling/Economy/economy.yaml:9-25`) | database |

**Where mesos come from and go.** A bot's wallet starts at zero (`INITIAL_MESO = 0`, src/main/java/soloMapling/ArtificialPlayer/BotStore.java:129) and is fed by grind income and by these transfer paths. A bot shop sale destroys the player's mesos at the purchase (`gainMeso(-price)`, src/main/java/server/maps/HiredMerchant.java:356), books the tax as a sink, and the bot's share waits in `BotShops.owed` until `collect` mints it into the bot — src/main/java/soloMapling/Economy/BotShops.java:520, :155-170. A bot buying from a real player's shop is the reverse: `BotHoldings.spendMeso` on the bot and `owner.gainMeso(price)` plus `BotHoldings.noteMesoEarned` for the owner — src/main/java/server/maps/PlayerShop.java:670,690,694; a bot buying from an artificial merchant pays mesos that leave the world (src/main/java/server/maps/HiredMerchant.java:942-956). FM shopper top-ups are minted by `FMBotWallet.reserveShortfall` precisely because those purchases would otherwise be impossible — src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBotWallet.java:33-61.

The `BotDebitJournal` closes the one remaining duplication: when a bot paid a player but the shutdown save budget could not write its row, the row would come back still claiming the mesos, minting them twice; the journal stores absolute caps (mesos held, per-item counts) on disk, `applyTo` lowers the restored bot to those caps on boot, and `committed` drops the entry once a row write lands — src/main/java/soloMapling/ArtificialPlayer/BotDebitJournal.java:17-37, :104-170; call sites src/main/java/soloMapling/ArtificialPlayer/BotStore.java:484, :978, :1356, :1455.

## Switches

| Switch | Default | Live (docs/release/1.6.4.conf) | Evidence |
|---|---|---|---|
| `botShops.enabled` | true | on (economy.yaml, no override) | src/main/java/soloMapling/Economy/economy.yaml:36; src/main/java/soloMapling/Economy/BotShops.java:114-116 |
| `botShops.*` numeric keys (maxShops 8, ownerPercent 10, minLevel 15, minItems 3, maxItems 12, maxOwnItems 10, hours 6, markups 1.00/1.15, pool 200, channels 3, targets "4,2,2") | see yaml | same defaults, no `-Dsolomapling.economy.*` override in the conf | src/main/java/soloMapling/Economy/economy.yaml:35-49 |
| `bands.*` (0.70/0.95/0.95/1.25, friendBonus 0.05, maxRounds 4, decideMin 0.5) | see yaml | same defaults | src/main/java/soloMapling/Economy/economy.yaml:22-32 |
| `directTrade.enabled`, `reserveShareOfCeiling` 0.25 | true / 0.25 | same defaults | src/main/java/soloMapling/Economy/BotDirectTrade.java:100-102; src/main/java/soloMapling/Economy/economy.yaml:59-61 |
| `botBotTrade.*` | enabled, 3/bot/day, 30/world/hour, 1 000-300 000 | same defaults | src/main/java/soloMapling/Economy/BotBotTrade.java:95-97; src/main/java/soloMapling/Economy/economy.yaml:65-73 |
| `botBuying.worldDailyBudget` 40m, `equipsAboveAverageOnly`, `npcCapBelow` 100k, `npcValueMultiple` 3.0 | see yaml | same defaults | src/main/java/soloMapling/Economy/EconomyMeters.java:129-131; src/main/java/soloMapling/Economy/economy.yaml:52-56 |
| `-Dslums.offer.pricefloor` | false | **off** — appears only in a comment (:33), not in `UNIT_EDITS` (:271) | src/main/java/soloMapling/FreeMarket/ShopOfferSystem/ShopOfferPriceFloor.java:34; docs/release/1.6.4.conf:33 |
| `-Dslums.bots.merchantpoolstock` | false | **off** — appears only in a comment (:38), not in `UNIT_EDITS` | src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:45; docs/release/1.6.4.conf:38 |
| `-Dslums.tradePacing.scale` | 1.0 | tests only | src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/TradePacing.java:11,41 |
| `-Dsolomapling.economy.maker.botBatch` | false | **on** (in `UNIT_EDITS`) | docs/release/1.6.4.conf:271 |
| `-Dslums.exchange=true`, `-Dslums.exchange.traders=true`, `…traders.pct=35` | off / off / — | **on**; without it `t-xc-5` returns "SKIP no scroll ticker is open" | docs/release/1.6.4.conf:271; src/main/java/soloMapling/Economy/exchange/volatility/MarketQa.java:227-228 |
| `COMPANION_TRADES_ENABLED` | false | **on** (`SERVER_SET`, :210) | src/main/java/config/ServerConfig.java:113; docs/release/1.6.4.conf:210 |
| `-Dsolomapling.autopopulate` | false | not in the conf (deploy ships it off) | src/main/java/soloMapling/server/MapleRootSolo.java:51-53 |
| `sinks.shopTaxPercent` | 3.0 | same default | src/main/java/soloMapling/Economy/MesoSinks.java:40-48 |
| `solomapling.botDebitJournal` | `data/bot-debit-journal.tsv` | not set | src/main/java/soloMapling/ArtificialPlayer/BotDebitJournal.java:51-54 |
| `-Dslums.botlife.preview` (gates `!botwhy`) | false | not in the conf; `!botwhy` therefore needs LIFE or QA | src/main/java/client/command/commands/gm3/BotWhyCommand.java:21-24 |

QA switches: `botShops.enabled` drives both `townTick` and `offerResale`; `EconomyConfig.reload()` is what `!economy reload` calls (src/main/java/soloMapling/Economy/EconomyConfig.java:51-55). TEST: unknown (start-test.sh unreachable).

## Invariants

| Invariant | Enforced at | Test |
|---|---|---|
| a bot never pays more than its band high, and never sells under its band low | `PriceBand.respond` src/main/java/soloMapling/Economy/PriceBand.java:92-118 | src/test/java/soloMapling/Economy/PriceBandTest.java; src/test/java/soloMapling/Economy/TradeSessionTest.java:106,121 |
| the bot buy ceiling is ≤ every bot's sell floor, so bot→bot round trips cannot profit | `PriceBand.of` takes `hi = min(min(1.0, sellFloor), botBuysHigh)` — src/main/java/soloMapling/Economy/PriceBand.java:38-54 | src/test/java/soloMapling/Economy/BotBotTradeTest.java |
| after `bands.maxRounds` counters the price is final, never a silent reset | `PriceBand.respond`; `BotDirectTrade.asksAboutQuote` repeats the standing price instead of the opening one — src/main/java/soloMapling/Economy/BotDirectTrade.java:405-420 | src/test/java/soloMapling/Economy/EconomyReviewFixesTest.java:203 |
| nothing `SaleRules` refuses is ever listed or bought | `BotShops.sellable`/`offerResale`, `FMBot.purchaseShopItems` — src/main/java/soloMapling/Economy/SaleRules.java:55,64; src/main/java/soloMapling/Economy/BotShops.java:229-234,297-325,536; src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:522 | src/test/java/soloMapling/FreeMarket/BotShopStockRulesTest.java:37,75,102,120; auto-QA `t-bs-2` |
| an FM shopper never pays a bot shop (that would mint mesos) | `BotShops.isListed` skip — src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:522 | src/test/java/soloMapling/Economy/BotGiftsAndShopsTest.java:71 |
| a real seller's payment is capped at 1.0× fair and 5m per account per day, plus the world daily budget | `BotSellerCaps.tryReserve` + `EconomyMeters.tryBotBuy`, released on every failure path — src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:571-587 | src/test/java/soloMapling/ArtificialPlayer/BotTypes/FMBotSellerTest.java:31 |
| the bot's own items leave its holdings only after the shop object exists | `BotShops.openOnce` ordering — src/main/java/soloMapling/Economy/BotShops.java:263-278 | src/test/java/soloMapling/Economy/BotGiftsAndShopsTest.java:61 |
| an item taken off the shelf always lands somewhere (bot, pool, or `PendingGrants`) | `BotShops.close` — :594-634 | src/test/java/soloMapling/Economy/EconomyReviewFixesTest.java:268 |
| one purchase per shop visit, and a bot leaves one seat free in a real player's shop | src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:528,640; :432-497 | src/test/java/soloMapling/ArtificialPlayer/BotTypes/FMBotRealShopTest.java:55 |
| a paid NX code still reaches the buyer even if the messenger is declined | `NxCodeDelivery` paths — src/main/java/soloMapling/ArtificialPlayer/BotTypes/NxCodeDelivery.java:3-20 | src/test/java/soloMapling/ArtificialPlayer/BotTypes/NxCodeDeliveryTest.java:68,83,94 |

## Origin

Custom throughout. Every file in scope was introduced by this repo's own history; the oldest commit touching the tree is the deployable baseline, and each file has its own later commits:

| File | Earliest commit (`git log --follow … | tail`) |
|---|---|
| src/main/java/soloMapling/Economy/BotShops.java | `bf32af563 2026-10-21 Economy lane 1.5.0 rc3: bot shops, direct trades, price board, meso sinks, gear audit` |
| src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotTradeSM.java | `03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline` |
| src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java | `03d13cb21 2026-10-18 baseline` (then `6b58d62b9 2026-10-21 Cap what bots pay real players' shops (ticket 56A)`) |
| src/main/java/soloMapling/ArtificialPlayer/BotDebitJournal.java | `22181bfe1 2026-10-25 Keep bot payments across a shutdown whose save budget ran out` (single commit) |
| src/main/java/soloMapling/ArtificialPlayer/BotTypes/NXMerchantBot.java | `a4136a1c3 2026-10-27 NX merchant: never leave a 50m buyer without their code; one redeem per code` |
| src/main/java/soloMapling/ArtificialPlayer/BotTypes/BuyingMerchantBot.java | `93dfde640 2026-10-23 Make bots do what they say, and let auto-QA measure grounding, chat and scenes` |
| src/main/java/soloMapling/ArtificialPlayer/BotTypes/GachaBot.java | `319c1555b 2026-10-21 Redesign the gachapon per town and add a bot wealth ceiling (56B)` |
| src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotGiveTrade.java, TradeCategories.java | `59e3eaad1 2026-10-30` (1.6.2 ticket 99) |
| src/main/java/soloMapling/Economy/HaggleBrain.java | `bf32af563 2026-10-21`, then `f42dcdfdc 2026-10-30` (ticket 156) |

The declared upstream for the ported pieces is in the docblocks: `BotGiveTrade`/`TradeCategories` say they follow nutnnut/Cosmic's `BotInventoryManager`/`BotTradePacing` — src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotGiveTrade.java:17-30; src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/TradeCategories.java:20-27; src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/TradePacing.java:5-15. The `BotTradeSM` merchant flow itself is baseline code (v83-style `Trade` semantics) later wrapped by `BotTradeCommands`.

## Tests and QA

| Test class | What it pins |
|---|---|
| src/test/java/soloMapling/Economy/BotGiftsAndShopsTest.java:51,61,71,85 | one bot in ten is merchant minded; prices follow the board with the resale markup; a sold listing is owed and shoppers skip it; the pool takes only what bots may sell and is bounded |
| src/test/java/soloMapling/Economy/EconomyReviewFixesTest.java:203,248,268,288 | haggling counts rounds and names a last price; the resale hand-off finds the bought instance not the id; an item a bot cannot take back waits; shops spread over channels 1-3 with channel 1 busiest |
| src/test/java/soloMapling/Economy/BotShopsSkillBookTest.java:64 | only the spare non-boss skill book is offered |
| src/test/java/soloMapling/Economy/TradeSessionTest.java:42-121 | band buying, swapping the item after the offer, what a bot may not resell, caps/purse, selling floor, confirming when paid |
| src/test/java/soloMapling/Economy/HaggleBrainTest.java:13,24,38 | price parsing, rules-first moves, the decide model only filling what the rules leave open |
| src/test/java/soloMapling/Economy/PriceBandTest.java, HaggleAnswersOfferTest.java, PriceBookTest.java, MesoSinksTest.java, SaleRulesTest.java, SaleRulesNpcSoldTest.java, BotBotTradeTest.java | band, quote answering, board, tax, sale rules, bot-to-bot |
| src/test/java/soloMapling/FreeMarket/BotShopStockRulesTest.java:37,56,75,102,120,131 | boss-only drops never stocked; godly exclusion is its own switch; generated scroll/use/etc stock follows SaleRules |
| src/test/java/soloMapling/ArtificialPlayer/BotTypes/FMBotSellerTest.java:31,36,41 | real shop capped by account, bot shop not capped, artificial merchant not capped |
| …/FMBotEquipRoomTest.java:47,64 | making room drops the bought copy, not an older one of the same equip; the purchase is recorded as the instance added |
| …/FMBotRealShopTest.java:35,45,55,65 | real shops discovered, ordered first, one seat kept free |
| …/FMBotMarketValueTest.java:18-52 | no market value → no buy, warned once per id |
| …/FMBotWalletTest.java, SellingMerchantBotMarketValueTest.java, src/test/java/soloMapling/ArtificialPlayer/BotTypes/SellingMerchantBotPoolStockTest.java:101,114,133, src/test/java/soloMapling/ArtificialPlayer/BotTypes/MerchantAdHonestyTest.java:14,22, src/test/java/soloMapling/ArtificialPlayer/BotTypes/NxCodeDeliveryTest.java:54,68,83,94 | allowance, the b271 skip, pool stocking on/off, ads match holdings, every code-delivery path |
| src/test/java/soloMapling/ArtificialPlayer/BotStoreTest.java | the only test that touches `BotDebitJournal` |

Auto-QA (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`): `t-bs-1` buy from a bot's shop, asserts an opened shop, ≥ 3 listings and no Chaos Scroll listing (:594-597); `t-bs-2` bot shops never list boss-only drops or anything SaleRules refuses, via probe `botShopStock` (:600-601); `t-td-1` sell a scroll to a bot (half-manual, :603-607); `t-td-2` buy from a bot by asking, with the "a counter never drops more than ~5 % under fair" rule (:608-621); `t-td-3` haggling cannot be tricked, 11 in-band stages (:623-627); `t-gf-1`/`t-gf-2` gifts (:629,638); `t-pr-1` `@price` (:645); `t-wc-1` a rich bot spends down (:586); `t-xc-5` a trader holding a Chaos/White Scroll lists it (:1302, body src/main/java/soloMapling/Economy/exchange/volatility/MarketQa.java:201-300).

`!qa` scenarios (src/main/java/soloMapling/qa/QaTradeScenarios.java): `tr-1` opens a bot shop in FM room 1 and gives the bot scrolls/ore/sword/chaos (:71-127), `tr-2` selling to a bot (:128-160), `tr-3` buying from a bot by asking (:163-210), `tr-4`/`tr-9` gifts (:225+, :260-271), `tr-5` `@price`, `tr-6` eleven in-band haggle lines plus `!qa do line <text>` (:347-380), `tr-7` meso sinks, `tr-8` megaphone seller.

Probes: `botShops` counts artificial merchants per channel in rooms 910000001-22 and requires all three channels non-empty with channel 1 busiest (src/main/java/soloMapling/autoqa/GameAutoQaWorld.java:199, :599-620); `botShopStock` walks every bot shop and every FM room on every channel and then forces the stock generators 40 times per tier × job, failing on anything `SaleRules.whyNot` refuses, with `BS2_NEVER = {1002357, 1002390, 1002430, 2049100, 2340000}` (src/main/java/soloMapling/autoqa/Probes160.java:118, :2571-2642).

**Not covered:** `BotShops` has no unit test class of its own (it is exercised only through `BotGiftsAndShopsTest`, `BotShopsSkillBookTest`, `EconomyReviewFixesTest`); `BotDebitJournal` has no test of its own (only a reference from `BotStoreTest`); `BotTradeSM`/`BotTradeQueue`/`BotTradeLogic` have no test; `GachaBot` has none; `BotGiveTrade` has none; the sweeper/close race and the `collect` path have none.

## Known issues

| Card | One line |
|---|---|
| b257 (/root/work/STATUS-1.6.4.md:74) | FM bot shops feel sparse and class gear is uneven (ticket 155's per-class share not built) — investigation only, numbers to the owner first |
| b287 (/root/work/STATUS-1.6.4.md:83) | `t-xc-5`: a PANIC-style scroll trader holding a Chaos/White Scroll never lists it |
| b284 (:80) | auto-QA at 10 lanes gives false failures; economy checks are among the lanes that pass only when rerun serially |
| b285 (:81) | ticket 178's sibling-alt upgrade hand-over is refused by the trade command (the `BotGiveTrade` path) |
| b276 (:78) | map-scoped PLAN/ROUTE QA lines never reach the GM feed, which hides what a bot said around a trade |

b257 mechanics: the shop count is a hard world cap of 8 (`botShops.maxShops`, src/main/java/soloMapling/Economy/economy.yaml:37) behind a 10 % merchant-minded share (:38) that only opens a shop on a town stop with ≥ 3 priced items (src/main/java/soloMapling/Economy/BotShops.java:131-153, :237-243) — three throttles multiply before a player sees one shop. The class-gear half is real: generated stock goes through `generateEquipListIIPU(tier, job)` (src/main/java/soloMapling/FreeMarket/EquipListGenerator.java:89-123) but the "common" list is beginner-only (`generateCommonEquipList` → `ClassStyle.BEGINNER`, src/main/java/soloMapling/FreeMarket/ArtificialShopGenerator.java:173-174), and there is no per-class share or quota anywhere in `src/main/java/soloMapling/FreeMarket` or `src/main/java/soloMapling/Economy` — the ticket-155 split was never built.

b287 mechanics: `MarketTraders.act` picks a ticker from the dice first (70 % LEAF, else random, src/main/java/soloMapling/Economy/exchange/volatility/MarketTraders.java:287-290) and then overrides it with `heldItem(...)` when the trader holds a scroll (:293, :304-305), and `heldItem` does put `SCROLL` before `ETC` (:598-620). The scroll is therefore selected — but the decision is the archetype's: `TraderBrain.panic` only places a SELL while "scared" (`drop > 0.12 || (drop > 0.06 && pressure < -0.5) || pressure < -1.5`, src/main/java/soloMapling/Economy/exchange/volatility/TraderBrain.java:185-188) and otherwise only considers a BUY when `reference <= fair * 1.05` and a 3-in-10 roll passes (:189-192), so a panic trader holding a scroll usually places no order at all and `t-xc-5` has nothing to see.

NEW-FINDING: src/main/java/server/maps/HiredMerchant.java:356 vs src/main/java/soloMapling/Economy/BotShops.java:520 — the buyer's mesos are destroyed at the purchase and the bot's share is only minted later by `collect`; if the bot is offline for the rest of its life, or the JVM restarts first, that share is never minted. `owed` is explicitly memory-only (:57-58), so a restart silently deletes it.
NEW-FINDING: src/main/java/soloMapling/Economy/BotShops.java:44-46 — the class docblock still says a shop opens "on channel 1" while the code spreads shops across channels 1-3 by `channelTargets` (:244-245, :404-419) and `EconomyReviewFixesTest:288` pins the spread; the docblock is stale from rc3.
NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotTradeWants.java:94-121 — `verifySufficientMeso` accepts a shortfall ("allows for a small shortfall based on the magnitude"), so a merchant-type bot can complete a window for slightly less than `mesoWanted`, while the direct-trade path requires `partnerMeso() >= price` exactly (src/main/java/soloMapling/Economy/TradeSession.java:168-171). Two different acceptance rules for the same economy.
NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotTypes/GachaBot.java:110-119 — `processReward()` returns immediately with its whole body commented out, so `reactToReward` (:121-129) and the `rewardQueue` it would poll are dead code; the state chain still walks STAND_BY_3 → STAND_BY_4 (:212) → PROCESS_REWARD (:172) → RUN_ROULETTE (:176) on every lap to do nothing there.
NEW-FINDING: src/main/java/soloMapling/Economy/BotShops.java:607-619 — an offline bot's own-loot item (`Kind.LOOT`) never enters the resale pool; it goes to `PendingGrants` and is held until the bot has room, which for a deleted or permanently offline bot is forever. Unbounded growth of an in-memory list.
NEW-FINDING: src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBotWallet.java:29-54 — the FM shopper allowance is per JVM (`usedToday` in memory) while its intent is per UTC day; a restart mid-day re-arms 10m of purchasing per bot, which the class docblock (:14) does not mention as a cost.
NEW-FINDING: src/main/java/soloMapling/Economy/BotShops.java:131-153 — `townTick` throttles on a shared `nextTryAt` map but keys only by bot id and never removes the entry, and `shops`/`nextTryAt` grow for every bot that ever stops in town; no eviction exists.

## State

**working (with the documented gaps).** Evidence: the shop lifecycle is covered end to end by `t-bs-1`/`t-bs-2` and by unit tests on stock rules, pricing, channel spread and the resale pool (`BotGiftsAndShopsTest`, `EconomyReviewFixesTest:288`); the haggle band is enforced in code and tested (`PriceBandTest`, `TradeSessionTest`, `t-td-3`); the direct-trade meso provenance is pinned by `BotSellerCaps`/`EconomyMeters` tests and by the `BotDebitJournal` call sites in `BotStore` (src/main/java/soloMapling/ArtificialPlayer/BotStore.java:484,978,1356,1455). The two open cards are scoped: b257 is investigation-only with no per-class code anywhere, b287 is a trader-archetype decision bug with a live QA check that fails on it. Nothing in the scope is broken outright; the dead `GachaBot.processReward` and the stale `BotShops` docblock are the only outright wrong code found.

## Open questions

1. b257: is the intended fix a higher `botShops.maxShops`/`ownerPercent`, or the ticket-155 per-class share (a generator change in `EquipListGenerator`/`ArtificialShopGenerator`)? No code or ticket file in this repo states which; the backlog entry (/root/work/STATUS-1.6.4.md:74, docs/tickets/185-bot-alt-improvement-backlog-1-6-4.md:62) says "investigation only, numbers to the owner first".
2. Should `BotShops.owed` survive a restart? The class docblock accepts the loss ("a restart costs only the bots", :57-58), but the mesos were already destroyed on the player's side, so the loss is one-sided and no test pins the intent.
3. Should merchant-type bots (`BotTradeSM`) be migrated onto `TradeSession`/`PriceBand`? They are the only trade path with no band enforcement and with the tolerance-based `verifyTrade` above; nothing in the repo decides this.
4. `BotGiveTrade.canGive` refuses population bots because `BotHoldings` items cannot go through a window (src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotGiveTrade.java:30). Whether that path is planned is not recorded here.
5. Whether `-Dslums.offer.pricefloor` and `-Dslums.bots.merchantpoolstock` are supposed to ship on in 1.6.4: the conf's comment block calls them OFF-unless-passed while STATUS L05 lists both as "behind switches" for the release lane — the conf and the board disagree, and the conf decides it today (off).
