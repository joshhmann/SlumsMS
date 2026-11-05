# Handlers: NPC and shops

Covers the NPC talk path, NPC shops (buy/sell/recharge), the GraalJS script boundary those handlers sit on,
gachapon as a script-driven feature, the Owl of Minerva / shop-visit pair, and reactors. The cash shop is a
separate subsystem (`docs/architecture/server/cashshop.md`, `docs/architecture/server/handlers-cashshop.md`).

## Purpose

A player clicking an NPC sends one packet; the server decides what that NPC *is* — a special-cased system
(Duey, world event, cosmetics merchant, gachapon machine, Maple TV, rebirth NPC), a GraalJS script, a shop, or
nothing. Everything the player then buys, sells or recharges goes through one `Shop` object held on the
`Character`, and reactor hits run through a second script manager that drops items from the `reactordrops` table.
The goal is that every one of these paths is text/data-driven: NPC behaviour lives in `scripts/npc/*.js`, shop
stock in the `shops`/`shopitems` tables, gachapon pools in a YAML file, and prices in `economy.yaml`.

## Entry points

| Opcode | Registered at | Handler | Reaches |
|---|---|---|---|
| `NPC_TALK` | `src/main/java/net/PacketProcessor.java:148` | `src/main/java/net/server/channel/handlers/NPCTalkHandler.java:42` | script manager, shop, or a special case |
| `NPC_TALK_MORE` | `src/main/java/net/PacketProcessor.java:149` | `src/main/java/net/server/channel/handlers/NPCMoreTalkHandler.java:35` | `NPCScriptManager.action`, quest script manager |
| `NPC_SHOP` | `src/main/java/net/PacketProcessor.java:152` | `src/main/java/net/server/channel/handlers/NPCShopHandler.java:39` | `Shop.buy/sell/recharge` |
| `NPC_ACTION` | `src/main/java/net/PacketProcessor.java:209` | `src/main/java/net/server/channel/handlers/NPCAnimationHandler.java:33` | broadcast relay only |
| `DAMAGE_REACTOR` | `src/main/java/net/PacketProcessor.java:203` | `src/main/java/net/server/channel/handlers/ReactorHitHandler.java:34` | `Reactor.hitReactor` |
| `TOUCHING_REACTOR` | `src/main/java/net/PacketProcessor.java:253` | `src/main/java/net/server/channel/handlers/TouchReactorHandler.java:36` | `ReactorScriptManager.touch/untouch` |
| `SCRIPTED_ITEM` | `src/main/java/net/PacketProcessor.java:252` | `src/main/java/net/server/channel/handlers/ScriptedItemHandler.java:43` | `ItemScriptManager` → `NPCScriptManager` |
| `USE_REMOTE` | `src/main/java/net/PacketProcessor.java:264` | `src/main/java/net/server/channel/handlers/RemoteGachaponHandler.java:37` | gachapon pool by town key |
| `OWL_ACTION` | `src/main/java/net/PacketProcessor.java:232` | `src/main/java/net/server/channel/handlers/UseOwlOfMinervaHandler.java:40` | world owl search counts |
| `OWL_WARP` | `src/main/java/net/PacketProcessor.java:233` | `src/main/java/net/server/channel/handlers/OwlWarpHandler.java:36` | player shop / hired merchant visit |
| `CLICK_GUIDE` | `src/main/java/net/PacketProcessor.java:281` | `src/main/java/net/server/channel/handlers/ClickGuideHandler.java:37` | `NPCScriptManager` on Mimo/Lilin |

Script and shop API (called from `scripts/npc/*.js`):

| Method | File:line | Effect |
|---|---|---|
| `NPCConversationManager.openShopNPC(int)` | `src/main/java/scripting/npc/NPCConversationManager.java:632` | opens shop by shop id; missing id logs and falls back to shop 11000 (`:638-639`) |
| `NPCConversationManager.doGachapon(int)` | `src/main/java/scripting/npc/NPCConversationManager.java:690` | one roll at this NPC's machine |
| `NPCConversationManager.gachaponOdds()` | `src/main/java/scripting/npc/NPCConversationManager.java:713` | per-tier odds text |
| `NPCConversationManager.getShopItems/removeShopItem/setShopItemPrice` | `src/main/java/scripting/npc/NPCConversationManager.java:2179,2183,2196` | GM shop editor (`scripts/npc/ShopEditor.js`) |
| `NPCConversationManager.addShopItem/addShopToDb/getAllShopIds` | `src/main/java/scripting/npc/NPCConversationManager.java:2426,2451,616` | shop rows from a script |

## How it works

### 1. Click NPC → `NPCTalkHandler` (`src/main/java/net/server/channel/handlers/NPCTalkHandler.java:42-107`)

1. Dead player → `enableActions` and return (`:43-46`).
2. Talk faster than `BLOCK_NPC_RACE_CONDT` ms → `enableActions` and return (`:48-51`).
3. Reads the object oid, resolves the map object, requires `obj instanceof NPC` (`:53-55`); records
   `PlayerTrail.npc(...)` for the `@bug` trail (ticket 131) at `:56`, `USE_DEBUG` line at `:57-59`.
4. **Duey** (`NpcId.DUEY`) short-circuits to `DueyProcessor.openOrExplain` + `dueySendTalk` (`:61-64`); nothing
   else runs for him.
5. A live conversation (`getCM() != null || getQM() != null`) is refused with `enableActions` (`:66-69`).
6. First-match wins among the hard-coded specials, in this order:
   world-event NPC → script `worldEventNpc` (ticket 21) `:72-73`; cosmetics merchant → `MesoSinks.openCosmeticShop`
   `:74-76`; `NpcId.GACHAPON_MIN..MAX` → script `gachapon` `:77-78`; name ending `"Maple TV"` → script `mapleTV`
   `:79-80`; `USE_REBIRTH_SYSTEM && id == REBIRTH_NPC_ID` → script `rebirth` `:81-82`.
7. Otherwise `NPCScriptManager.start(c, npcId, oid, null)` (`:84`). A script that exists owns the click.
8. No script: if the NPC has no shop either, `log.warn("NPC {} ({}) is not coded")` and **return at `:87`
   without `enableActions`** (`:85-88`). If it has a shop and one is already open → `enableActions`
   (`:89-91`), else `npc.sendShop(c)` (`:94`).
9. `obj instanceof PlayerNPC` → `rank_user` script for ids below `NpcId.CUSTOM_DEV`, else the NPC-named script
   (`:98-105`).

### 2. Talk-more → `NPCMoreTalkHandler` (`src/main/java/net/server/channel/handlers/NPCMoreTalkHandler.java:35-84`)

1. `lastMsg` and `action` read as bytes (`:36-37`).
2. `lastMsg == 2` (text input): a live quest conversation gets the text and the quest start/end dispatch
   (`:39-47`), a live NPC conversation gets `cm.setGetText` + `NPCScriptManager.action` (`:48-51`);
   `action == 0` disposes whichever manager is live (`:52-56`).
3. Otherwise the selection is read as an `int` when 4 bytes are available, else a signed byte widened with
   `if (selection < 0) selection += 256` (`:59-68`) so >127 choices do not wrap.
4. `lastMsg == 3` (number box) is clamped by `boundNumber` before dispatch (`:69-75`, ticket from commit
   51cdc67b5 2026-10-25).
5. Dispatch to `QuestScriptManager.start/end` or `NPCScriptManager.action` (`:76-84`).

### 3. The GraalJS boundary (`src/main/java/scripting/AbstractScriptManager.java:44-92`)

1. One `GraalJSScriptEngine` factory per manager (`:44-46`).
2. `getInvocableScriptEngine(path)` returns `null` when `scripts/<path>` does not exist (`:49-51`); each call
   builds a fresh engine, enables `polyglot.js.allowHostAccess/allowHostClassLookup/allowIO` (`:84-89`), evaluates
   the file and returns `null` on `ScriptException | IOException` (`:62-67`).
3. The `Client`-scoped overload caches the engine per client path (`:71-78`), so a script's globals survive
   between calls until `resetContext` removes them (`:91-92`).
4. `NPCScriptManager.start` (`src/main/java/scripting/npc/NPCScriptManager.java:122-177`) builds the
   `NPCConversationManager`, disposes a previous conversation (`:125-127`), requires `c.canClickNPC()` (`:128`),
   loads `npc/<file>.js` (or `item/<file>.js` for item scripts, `:131-139`), falls back to `npc/<npcId>.js`
   (`:140-143`), and returns `false` with `dispose` when neither exists (`:144-148`). It binds the manager as
   `cm`/`im` (`:149`) and invokes `start` then, on `NoSuchMethodException`, `start(chr)` (`:154-162`).
5. `action(c, mode, type, selection)` invokes `action` and disposes on `ScriptException | NoSuchMethodException`
   (`:179-192`).
6. `dispose` (`:194-213`) sets the NPC cooldown to `System.currentTimeMillis()` (`:197`), drops the manager and
   engine (`:198-199`), notifies cash-shop partner rings (`:200-203`), resets the engine cache (`:205-210`) and
   flushes delayed quest updates (`:212`). The cooldown is what `BLOCK_NPC_RACE_CONDT` checks in step 1.2.

### 4. Buy / sell / recharge → `NPCShopHandler` (`src/main/java/net/server/channel/handlers/NPCShopHandler.java:39-71`)

1. `mode` byte, then `0` buy (`:42-55`), `1` sell (`:56-61`), `2` recharge (`:63-67`), `3` closing the window
   (sets the character's shop to `null`, `:70`).
2. Buy reads slot/itemId/quantity; `quantity < 1` raises `AutobanFactory.PACKET_EDIT`, logs, disconnects
   (`:46-52`), then calls `getShop().buy(c, slot, itemId, quantity)` (`:53`).
3. Sell reads slot/itemId/quantity and calls `getShop().sell(c, ItemConstants.getInventoryType(itemId), slot,
   quantity)` (`:57-60`) — the inventory type is derived server-side from the item id, not trusted from the
   client.
4. Recharge reads a short and narrows it to a byte (`:65`), then `getShop().recharge(c, slot)` (`:66`).

`Shop.buy` (`src/main/java/server/Shop.java:96-200`):

1. Slot must exist and the item id must match, else `log.warn("Wrong slot number in shop {}")` (`:98-106`).
2. Three mutually exclusive currencies, decided by the `ShopItem` row: **mesos** when `price > 0` (`:108`),
   **Perfect Pitch leaves** when `price == 0 && pitch > 0` (`:147`), **Golden Maple Leaf** when a token is held
   (`:173`).
3. Meso path: rechargeables are granted as one whole `slotMax` stack and priced with
   `ii.getPrice(itemId, quantity)` — the same WZ expression sell/recharge uses (`:109-114`); everything else uses
   `ShopMath.total(price, quantity)` (`:116-117`). A negative/oversized total or `quantity < 1` answers `(2)`
   (`:118-121`). Then meso check (`:123`), `checkSpace` (`:124`), `addById` + `gainMeso(-amount,false)` +
   `EconomyCounters.recordNpcBuy` (`:125-128`), the cosmetics-merchant sink hook
   `MesoSinks.onSinkPurchase` (`:129-131`), `shopTransaction((byte) 0)` (`:137`) and the `PlayerTrail` shop event
   (`:138`). Out of space answers `(3)` (`:140`), short of mesos `(2)` (`:144`).
4. Pitch path: total leaves required (`:148-153`), requires `countById(ItemId.PERFECT_PITCH) >= amount` (`:155`),
   then adds the item and removes the leaves (`:156-165`).
5. Token path: `value = amount * 1_000_000_000` (the `tokenvalue` field, `:57`), `cost = price * quantity`, the
   purchase is allowed when `meso + value >= cost` (`:175-181`), the new meso total is clamped into `int` range
   (`:182-183`), the item is added (pets through `Pet.createPet`) and mesos set (`:184-191`).

`Shop.sell` (`src/main/java/server/Shop.java:232-254`): quantity `0xFFFF`/`0` means 1 (`:233`), negatives are
ignored (`:235`), `canSell` gates (`:240`), rechargeables sell whole-stack (`:241`), the item is removed
(`:242`), `MarketFlows.consumed(itemId, quantity)` records the sink for 1.6.1 fair value (`:243`), the payout is
`gainMeso(sellPayout(...))` + `recordNpcSell` (`:245-249`), and the answer is `0x8` on success (`:250`) or `0x5`
on refusal (`:252`). `sellPayout` (`:265-268`) special-cases casino chips through `CasinoChipConfig.sellValue`
(lossless), else `ItemInformationProvider.getPrice`.

`Shop.recharge` (`src/main/java/server/Shop.java:270-297`): USE slots only (`:272`), the item must be
rechargeable (`:273`), the stack must be below `slotMax` (`:276-280`), the price is
`ii.getPrice(itemId, slotMax - quantity)` (`:281-283`), a negative price refuses (`:284-286`), then
`setQuantity(slotMax)` + `forceUpdateItem` + `gainMeso(-price,false,true,false)` + `recordNpcBuy` (`:288-291`),
answering `0x8` (`:292`) or `0x2` (`:294`).

### 5. Where a `Shop` comes from

1. `NPCTalkHandler` → `NPC.hasShop()` → `ShopFactory.getShopForNPC(id)` (`src/main/java/server/life/NPC.java:37-43`).
2. `ShopFactory` keeps two `HashMap`s plus a `Loader` seam (`src/main/java/server/ShopFactory.java:39-50`),
   wired by default to `Shop.createFromDB` / `Shop.loadAllShopIds` (`:52-64`); a package-private constructor takes
   a test loader (`:66`).
3. `loadShop` (`src/main/java/server/ShopFactory.java:86-102`) drops GM shops (ids `1337` and
   `9999992-9999999`, `:82-84`) when the lookup is by NPC id, caches non-GM shops in `npcShops`, and negative-caches
   misses in both maps (`:96-100`).
4. `Shop.createFromDB(id, isShopId)` (`src/main/java/server/Shop.java:311-362`) picks `SELECT * FROM shops WHERE
   shopid = ?` or the NPC query (`:303-305`: `WHERE npcid = ? AND shopid <> 1337 AND shopid NOT BETWEEN 9999992
   AND 9999999 ORDER BY shopid = npcid DESC, shopid`), then reads `SELECT itemid, price, pitch FROM shopitems
   WHERE shopid = ? ORDER BY position DESC` (`:335`).
5. Per `shopitems` row: rechargeables become `ShopItem((short) 1, ...)` (`:341-346`); every other row has its
   price passed through `MesoSinks.npcPrice` (`:348-351`); finally **every rechargeable in `rechargeableItems`
   that the row set did not cover is appended at price 0** (`:354-356`), which is why every shop stocks all
   stars and bullets.
6. The cosmetics merchant bypasses the tables entirely: `Shop.custom(id, npcId, stock)`
   (`src/main/java/server/Shop.java:84-90`) builds the shop in code from `economy.yaml`.

### 6. Gachapon as a script feature

1. `NPCTalkHandler` starts the `gachapon` script for ids in `NpcId.GACHAPON_MIN..MAX`
   (`src/main/java/constants/id/NpcId.java:22-36`), `scripts/npc/gachapon.js:1-45` offers ticket / odds / where
   (`:23-28`) and calls `cm.doGachapon(TICKET)` (`:31`) with `TICKET = 5220000` (`:9`).
2. `NPCConversationManager.doGachapon` (`src/main/java/scripting/npc/NPCConversationManager.java:690-711`) maps
   the NPC to a pool town with `GachaPools.get().byNpc(npc)` (`:695`), rolls with
   `GachaMachine.playForPlayer` (`:696-697`), shows the refusal text (`:698-701`) or
   `"You have obtained #v<item># ..."` with the tier banner (`:703-709`).
3. `GachaMachine.playForPlayer` (`src/main/java/soloMapling/Gachapon/GachaMachine.java:87-131`) refuses when the
   machine or character is missing (`:89-91`) or the ticket is absent (`:92-94`), replays a pending prize for a
   committed roll (`:97-102`), refuses an empty pool (`:103-105`), and on a full inventory stores the prize in
   `pending` and refunds nothing because the ticket was never taken (`:106-115`). The ticket is removed only after
   `checkSpace` passes (`:116-119`), and if the grant still returns `null` the ticket is given back
   (`:120-131`). Announcement and accounting happen in `after` (`:177-195`).
4. Remote use: `RemoteGachaponHandler` (`src/main/java/net/server/channel/handlers/RemoteGachaponHandler.java:38-61`)
   validates the ticket id, mode range 0-11 and ticket count with autoban + disconnect, maps the mode through
   `Gachapon.REMOTE_ORDER` (`src/main/java/server/gachapon/Gachapon.java:47-56`), then starts the `gachaponRemote`
   script as that town's NPC (`:60-61`).

### 7. Owl of Minerva and shop visits

1. Using the owl (523) records the search in the world (`src/main/java/net/server/channel/handlers/UseCashItemHandler.java:441`
   → `src/main/java/net/server/world/World.java:1376`) unless `USE_ENFORCE_ITEM_SUGGESTION` is on.
2. `OWL_ACTION` (`src/main/java/net/server/channel/handlers/UseOwlOfMinervaHandler.java:41-62`) turns the world's
   search counts into a leaderboard: fewer than 5 stored searches falls back to `ItemId.getOwlItems()`
   (`:44-49`, `src/main/java/constants/id/ItemId.java:391-395`), otherwise a descending `PriorityQueue` keeps the
   top `min(size, 10)` (`:51-59`) and sends `PacketCreator.getOwlOpen` (`:62`).
3. `OWL_WARP` (`src/main/java/net/server/channel/handlers/OwlWarpHandler.java:37-113`) validates owner id, map,
   open state and the searched item separately for player shops (`:58-84`) and hired merchants (`:86-113`), then
   warps and opens the visit, with distinct owl message ids for "own shop", "closed", "banned", "another
   channel", "not in the Free Market".

### 8. Reactor hit path

1. `DAMAGE_REACTOR` (`src/main/java/net/server/channel/handlers/ReactorHitHandler.java:38-46`) reads oid, char
   position, stance, skips 4 bytes, reads the skill id and calls
   `reactor.hitReactor(true, charPos, stance, skillid, c)`.
2. `Reactor.hitReactor` (`src/main/java/server/maps/Reactor.java:246-324`) ignores inactive reactors (`:248`),
   takes `hitLock` (`:252`), calls `ReactorScriptManager.onHit` (`:261`), looks the state up in
   `stats.getType(state)` (`:263`), and for an end state either destroys the reactor or broadcasts
   `triggerReactor` (`:274-286`); states below 1000 run `act` (`:288`), looping states re-`act` (`:291-293`),
   type 100 hands off to `searchItemReactors` (`:297-299`), other states just advance (`:304-316`). The finally
   block unlocks and catches all exceptions with `printStackTrace` (`:317-324`).
3. `TOUCHING_REACTOR` (`src/main/java/net/server/channel/handlers/TouchReactorHandler.java:37-47`) routes a
   non-zero byte to `touch` and zero to `untouch`; `ReactorScriptManager.touching`
   (`src/main/java/scripting/reactor/ReactorScriptManager.java:117-129`) invokes the optional `touch`/`untouch`
   JS function and logs a missing function at error level.
4. Drops: a script calls `rm.sprayItems(...)` / `rm.dropItems(...)`
   (`src/main/java/scripting/reactor/ReactorActionManager.java:124-156`), which builds the drop list from
   `getDropChances()` → `ReactorScriptManager.getDrops(reactorId)` (`:230-232`) — the `reactordrops` table,
   `"SELECT itemid, chance, questid FROM reactordrops WHERE reactorid = ? AND chance >= 0"`, cached per reactor id
   (`src/main/java/scripting/reactor/ReactorScriptManager.java:84-102`). `generateDropList` rolls
   `Math.random() < dropRate / chance` per row and pads to `minItems` with meso entries
   (`:234-250`); the drops are then laid out in a row and either spawned immediately or through
   `TimerManager` (`:156-227`), each item going through `map.dropFromReactor` with its quest id.

### 9. Scripted items

`ScriptedItemHandler` (`src/main/java/net/server/channel/handlers/ScriptedItemHandler.java:43-81`) reads the
stamp, slot and item id, resolves `getScriptedItemInfo` (`:48`), and every exit path ends in
`c.sendPacket(PacketCreator.enableActions())` (`:79`) because the client locks itself after the packet
(javadoc `:51-54`); a failure is logged with the outcome string rather than thrown away (`:70-77`).

## Data

| Data | Where | Fed by |
|---|---|---|
| Shop rows | MySQL `shops` (`shopid`, `npcid`) | live DB (Cosmic stock seed). No `CREATE TABLE` for it exists in `database/migrations/`; five migrations touch `shopitems`: `021-cosmetics-merchant.sql`, `024-restore-stock-shop-items.sql` (+ rollback), `064-casino-chip-shop.sql` (+ rollback) |
| Shop stock | MySQL `shopitems` (`shopid`, `itemid`, `price`, `pitch`, `position`) | same; read at `src/main/java/server/Shop.java:335`. **Not YAML, not itemPool** |
| Shop prices | `src/main/java/soloMapling/Economy/economy.yaml:125-127` `sinks.shopPrices` | overrides the `shopitems` price in every shop via `MesoSinks.npcPrice` (`src/main/java/soloMapling/Economy/MesoSinks.java:104-110`, applied at `src/main/java/server/Shop.java:348-351`) |
| Cosmetics stock | `src/main/java/soloMapling/Economy/economy.yaml:130-141` `cosmeticShop.items` | `MesoSinks.cosmeticStock()` (`src/main/java/soloMapling/Economy/MesoSinks.java:132-144`), default list at `:146-162` |
| Gachapon pools | `src/main/java/soloMapling/Gachapon/gachapon-pools.yaml` (`GachaPools.FILE`, `src/main/java/soloMapling/Gachapon/GachaPools.java:40`) | 13 towns with `npc:` ids (`src/main/java/soloMapling/Gachapon/gachapon-pools.yaml:261-1900`), tier weights `:19-27`, shared pool `:31` |
| Reactor drops | MySQL `reactordrops` (`reactorid`, `itemid`, `chance`, `questid`) | live DB; only referenced by migration 025's rollback in-repo |
| NPC scripts | `scripts/npc/*.js` (795 files) | `NPCScriptManager` file lookup |
| Reactor scripts | `scripts/reactor/*.js` (294 files) | `ReactorScriptManager` file lookup |
| Remote gachapon order | `Gachapon.REMOTE_ORDER` (`src/main/java/server/gachapon/Gachapon.java:47-48`) | code constant, 12 town keys |
| Shop packet | `PacketCreator.getNPCShop` (`src/main/java/tools/PacketCreator.java:2436-2466`) | rechargeables show `ii.getPrice(itemId, slotMax)` (`:2443-2447`), tokens/pitch show `pitch` when price is 0 (`:2449`) |

Answer to "where does shop restock come from": **nowhere — shops never restock.** Stock is a fixed set of
`shopitems` rows read once per shop and cached in `ShopFactory`; there is no timer, no per-character limit and no
sold-out state. The only ways stock changes are a migration, `!reloadshops` (`ShopFactory.reloadShops`,
`src/main/java/server/ShopFactory.java:118-121`, wired to `!economy reload`,
`src/main/java/client/command/commands/gm4/EconomyCommand.java:129-130`), or a GM edit through `ShopEditor.js`.
`soloMapling/itemPool` (`ItemDatabase.getItemPrice`) is read only by `@price` and `!economy` reporting
(`src/main/java/client/command/commands/gm0/PriceCommand.java:71`,
`src/main/java/client/command/commands/gm4/EconomyCommand.java:103`) — it does not feed shops.

## Switches

| Switch | Declared | Default | Read at | Live | TEST |
|---|---|---|---|---|---|
| `BLOCK_NPC_RACE_CONDT` | `src/main/java/config/ServerConfig.java:406` | `config.example.yaml:312` = 500 | `NPCTalkHandler.java:48` | not in `docs/release/1.6.4.conf` SERVER_SET (`:201+`) → value unknown | unknown |
| `REBIRTH_NPC_ID` | `src/main/java/config/ServerConfig.java:418` | `config.example.yaml:320` = 1300009 | `NPCTalkHandler.java:81` | not in SERVER_SET | unknown |
| `USE_REBIRTH_SYSTEM` | `src/main/java/config/ServerConfig.java:353` | `config.example.yaml:261` = true | `NPCTalkHandler.java:81`, `rebirth.js` | not in SERVER_SET | unknown |
| `USE_CPQ` | `src/main/java/config/ServerConfig.java:305` | `config.example.yaml:211` = true | `scripts/npc/2042000.js:33` | not in SERVER_SET | unknown |
| `USE_ENABLE_CUSTOM_NPC_SCRIPT` | `src/main/java/config/ServerConfig.java:382` | `config.example.yaml:289` = false | `scripts/npc/2042000.js:35`, `src/main/java/soloMapling/autoqa/Probes160.java:1013`, `src/test/java/scripting/CustomNpcScriptsTest.java` — **not** read by `NPCScriptManager` | not in SERVER_SET | unknown |
| `USE_MAKER_PERMISSIVE_ATKUP` | `src/main/java/config/ServerConfig.java:376` | `config.example.yaml:284` = false | `src/main/java/scripting/npc/NpcMakerCraftingService.java:13-15` | not in SERVER_SET | unknown |
| `USE_ENFORCE_ITEM_SUGGESTION` | `src/main/java/config/ServerConfig.java:336` | `config.example.yaml` (`USE_ENFORCE_ITEM_SUGGESTION`) | `World.getOwlSearchedItems` (`src/main/java/net/server/world/World.java:1390-1392` returns an empty list when on), `UseCashItemHandler.java:440` | not in SERVER_SET | unknown |
| `USE_DEBUG` | `src/main/java/config/ServerConfig.java:293` | `config.example.yaml:200` = false | `NPCTalkHandler.java:57`, `Reactor.java:258` | not in SERVER_SET | unknown |
| `-Dsolomapling.economy.<key>` | `src/main/java/soloMapling/Economy/EconomyConfig.java:33,97` | file values | overrides any `economy.yaml` key incl. `sinks.shopPrices`, `cosmeticShop.*` | unknown | unknown |
| `-Dsolomapling.botGacha` | `src/main/java/soloMapling/Gachapon/BotGacha.java:65` | true | bot gachapon rolls | not in `1.6.4.conf` | unknown |
| `-Dsolomapling.gachaNotices` | `src/main/java/soloMapling/Gachapon/GachaAnnouncer.java:73` | true | rare/jackpot broadcast | not in `1.6.4.conf` | unknown |
| `-Dsolomapling.dataDir` | `src/main/java/soloMapling/DataFiles.java:48` | repo root | resolves `gachapon-pools.yaml` and `economy.yaml` (`:72-73`) | unknown | unknown |

TEST's `start-test.sh` lives on another host and was not read, so every TEST column is unknown.

## Invariants

- A click never reaches two handlers: `NPCTalkHandler` refuses when `getCM()`/`getQM()` is live
  (`NPCTalkHandler.java:66-69`), and the specials are a first-match `if/else` chain (`:72-84`).
- The NPC cooldown set in `NPCScriptManager.dispose` (`:197`) is the only thing preventing script spam; it is
  compared against `BLOCK_NPC_RACE_CONDT` at `NPCTalkHandler.java:48`.
- Shop prices are never negative-side effects: every price goes through `ShopMath.total`
  (`src/main/java/server/ShopMath.java:27-38`), which returns `-1` on negative input, overflow or a total over
  `MAX_MESO` (`:12`), and callers refuse on `-1` (`Shop.java:118-121`, `:149-152`).
- A buy never mints mesos: `gainMeso(-amount)` and `addById` happen together, and a rechargeable is priced with
  the same WZ expression that sell pays out (`Shop.java:109-117`).
- The sell path derives the inventory type from the item id (`NPCShopHandler.java:60`), never from the client.
- Rechargeables are always stocked: any rechargeable id missing from a shop's rows is appended at price 0
  (`Shop.java:354-356`).
- A gachapon ticket is only consumed after a prize has passed `checkSpace`, and a prize that did not fit is
  replayed rather than rerolled (`GachaMachine.java:97-119`).
- A GM shop is never reachable by talking to an NPC (`ShopFactory.java:86-90`, `:82-84`).
- Scripted items always release the client (`ScriptedItemHandler.java:73-80`).
- Reactor drops for a state change only happen while `hitLock` is held and the reactor is active
  (`Reactor.java:248-252`, `:317-320`).

## Origin

| Path | Class | Evidence |
|---|---|---|
| `src/main/java/net/server/channel/handlers/NPCShopHandler.java` | base | byte-identical to `/root/workspace/cosmicms/src/main/java/net/server/channel/handlers/NPCShopHandler.java` |
| `src/main/java/net/server/channel/handlers/UseOwlOfMinervaHandler.java`, `OwlWarpHandler.java`, `ReactorHitHandler.java`, `TouchReactorHandler.java`, `ClickGuideHandler.java` | base | byte-identical |
| `src/main/java/server/life/NPC.java`, `src/main/java/scripting/reactor/ReactorScriptManager.java` | base | byte-identical |
| `src/main/java/net/server/channel/handlers/NPCTalkHandler.java` | modified | differs from base: base ends with the "not coded" warn and falls through; ours adds the Duey/world-event/cosmetics/gachapon/Maple-TV/rebirth branches and returns at `:87`. Oldest commit `03d13cb21`, then `88afaed00` (world events, ticket 21) |
| `src/main/java/net/server/channel/handlers/NPCMoreTalkHandler.java` | modified | differs; history `03d13cb21` → `ddfa41b18` (2024-01-03, upstream `selection += 256`) → `51cdc67b5` 2026-10-25 (number-box bounds) |
| `src/main/java/net/server/channel/handlers/NPCAnimationHandler.java` | modified | differs from base; `:35` null guard, `:38` `isChangingMaps` guard |
| `src/main/java/net/server/channel/handlers/RemoteGachaponHandler.java` | modified | differs: base computed `NpcId.GACHAPON_HENESYS + gacha`; ours resolves a pool town via `Gachapon.remote` (`:55-61`) |
| `src/main/java/net/server/channel/handlers/ScriptedItemHandler.java` | modified | differs; `:51-54` javadoc and the always-`enableActions` finally are ours |
| `src/main/java/server/Shop.java` | modified | differs; `bf32af563` (1.5.0 rc3), `0f0231983`, `c503af87a` after `03d13cb21`. `tools/cosmicdiff/classifications.json` has no entry for `src/main/java/server/Shop.java` |
| `src/main/java/server/ShopFactory.java` | modified | differs; `c503af87a` ("GM shops out of NPC lookups") |
| `src/main/java/server/ShopItem.java` | modified | differs: `price` is mutable + `setPrice` (`:52`) |
| `src/main/java/server/ShopMath.java` | custom | absent upstream; first `0f0231983` 2026-10-22, then `da44184ef` 2026-10-23 (haggle P0) |
| `src/main/java/scripting/npc/NpcMakerCraftingService.java` | custom | absent upstream |
| `src/main/java/soloMapling/Gachapon/*`, `src/main/java/soloMapling/Economy/MesoSinks.java` | custom | absent upstream; `MesoSinks` first `bf32af563` 2026-10-21, `gachapon-pools.yaml` first `319c1555b` 2026-10-21 |
| `src/main/java/server/gachapon/Gachapon.java` | modified | differs; `319c1555b` (gachapon redesign) |
| `src/main/java/scripting/npc/NPCScriptManager.java` | modified | differs; `447672bbf` (rings) after `03d13cb21` |
| `src/main/java/scripting/npc/NPCConversationManager.java` | modified | differs; `319c1555b` |
| `src/main/java/scripting/reactor/ReactorActionManager.java` | modified | differs; `03d13cb21` then `febe3752a` 2026-10-31 |
| `src/main/java/scripting/AbstractScriptManager.java` | modified | differs |
| `scripts/npc/gachapon.js` (45 lines), `scripts/npc/gachaponRemote.js` | custom | `classifications.json`: `script-rewritten:scripts/npc/gachapon.js` → `DELIBERATE`, "MapleRoot's gachapon rewrite (256 lines vs 45)" — the entry's line counts are stale against the 45-line file read here |

`tools/cosmicdiff/classifications.json` entries that bear on this subsystem:
`script-missing:scripts/npc/2110000.js` → `RESTORED` ("NPCTalkHandler logs 'not coded' and returns without
enableActions, so clicking Rosen does nothing and leaves the player stuck"); `command-unregistered:…::gacha` →
`DELIBERATE`; the refiner scripts (`1022003.js`, `2020000.js`, `2040016.js`, `2100001.js`) → `DELIBERATE`,
centralised on NPC 9977779 / Nimble.

## Tests and QA

| Test | Count | Covers |
|---|---|---|
| `src/test/java/server/ShopMathTest.java` | 2 | exact totals, overflow/negative refusal |
| `src/test/java/server/ShopRechargePriceParityTest.java` | 3 | buy/sell/recharge use the same WZ price expression |
| `src/test/java/server/ShopRechargeableStockPriceTest.java` | 1 | rechargeable counter price in the shop packet |
| `src/test/java/server/GmShopLookupTest.java` | 4 | GM shop ids never returned by an NPC lookup |
| `src/test/java/server/CasinoNpcShopFallbackTest.java` | 2 | widget/stamp shop fallback |
| `src/test/java/server/ShopPickListTest.java` | 3 | `getAllShopIds` shape for `scripts/npc/ShopEditor.js:93-103` |
| `src/test/java/server/ShopStockRestoreMigrationTest.java` | 2 | migration 024 rows exist in WZ |
| `src/test/java/database/CasinoChipShopMigrationTest.java` (+ `…IT`) | — | migration 064 rows |
| `src/test/java/scripting/NpcScriptIntegrityTest.java` / `AllScriptsLoadTest.java` | 4 / 1 | every script parses; API calls resolve |
| `src/test/java/scripting/CustomNpcScriptsTest.java` | 12 | `USE_ENABLE_CUSTOM_NPC_SCRIPT` NPCs |
| `src/test/java/scripting/GachaponTicketScriptTest.java` | 3 | machine dialogue and ticket flow |
| `src/test/java/scripting/RebirthKeyItemTest.java`, `PinkBeanExitNpcTest.java`, `CpqStockLimitsTest.java`, `RootyShopSelectionTest.java` (13) | — | ticket 102 items |
| `src/test/java/scripting/reactor/ZakumAltarReactorTest.java` | 2 | altar reactor states |
| `src/test/java/soloMapling/Gachapon/*` | 6 files | pools parse, odds, pending prize, announcements, migration 013 |

Auto-QA (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`): `t-ga-1` "Play a town machine" (`:565`),
`t-ga-2` "Six new machines stand in town" (`:575`), `t-ga-3` half-cover "Remote ticket reaches the last machine"
(`:578`), `t-ga-4` manual "Bots' show rolls vanish" (`:582`), `t-bs-1` "Buy from a bot's shop" (`:594`),
`t-bs-2` bot shops never list boss-only drops (`:597`), `t-sk-1` "Buy from Hanako" (`:651`), `t-sk-2` "New NPC
prices" (`:654`), `t-ps-2` "Town bots keep clear of NPCs" (`:720`), `t-vb-5` "Buy and sell at a shop and use a
Cash Shop EXP card" (`:813`), `t-np-4` "Custom NPC caps: Coco, Dalair, Maple TV" (`:1257`), `t-nv-1` town
portals and NPCs reachable (`:1406`).

`!qa` scenarios: `vb-5` (`src/main/java/soloMapling/qa/V83Scenarios.java:150-157`) drives the real shop path in
`GameQaWorld.shopStep` (`src/main/java/soloMapling/qa/GameQaWorld.java:2449-2512`) — stages a GM on 100000100,
opens NPC 1012004's shop, buys the first affordable item and logs `VB SHOP buy …` plus the
`SHOP_TRANSACTION` answer, then sells one item. Trade-side scenarios `tr-*` (`src/main/java/soloMapling/qa/QaTradeScenarios.java:71-120`)
cover bot FM shops and the tax, not NPC shops.

**Not covered:** `Shop.buy`'s pitch and Golden-Maple-Leaf branches have no test; `Shop.recharge` has no test that
exercises the packet answer; `NPCShopHandler` has no handler-level test at all (no opcode fuzz for
`quantity < 1`); `OwlWarpHandler` and `UseOwlOfMinervaHandler` have no test; `TouchReactorHandler` has no test;
`ReactorActionManager.dropItems` delayed path has no test; no test asserts that a scriptless, shopless NPC still
un-sticks the client; the gachapon remote-ticket path (`RemoteGachaponHandler`) has no test.

## Known issues

| Card | Where | One line |
|---|---|---|
| ticket 89 | `docs/tickets/89-mapleroot-npc-decisions.md:1-30` | open; ten NPC decisions including inert NPCs, seven Maple TVs, boss recruiters with no placement, Glowing Treasure leaving the dialog open |
| ticket 102 | `docs/tickets/102-quick-npc-fixes.md:7-15` | marked **open, target 1.6.0** with all five boxes unchecked, but Nimble (migration 027), rebirth (`scripts/npc/rebirth.js:134-141`), Pink Bean (`2141002.js`, commit 1d78d6ae1) and CPQ (`scripts/npc/2042000.js:18-22`, commit a05b6e94f) landed 2026-10-24/25 — the checklist is stale |
| ticket 175 | `docs/tickets/175-economy-rework-flea-lessons.md:14-24` | owner asked for Tarkov-style limited stock + timed restock for NPC shops; none of it is built (see Data) |
| ticket 09 | `docs/tickets/09-shop-resupply.md:5` | closed 2026-10-25; bot shopping uses these same shops via `ShopCatalog` |
| ticket 12 | `docs/tickets/12-sell-trade.md:5` | closed 1.5.0; bots never sell, so worse stars keep their slot (`09-shop-resupply.md`, "Open issues") |
| `b257` / `s20` | `/root/work/STATUS-1.6.4.md:74,134` | FM bot shops feel sparse, per-class gear share not built |
| `e11` | `/root/work/STATUS-1.6.4.md:121` | exchange fair value anchored on merchant prices, not the NPC buy-back |
| `L05` / `b277` | `/root/work/STATUS-1.6.4.md:96,79` | lane check; NX desk audit formatting |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/NPCTalkHandler.java:85-88` | a scriptless NPC with no shop logs and **returns without `enableActions`**, leaving the client frozen. `classifications.json` records this exact hazard for `scripts/npc/2110000.js`; the return is what makes it lethal |
| NEW-FINDING | `src/main/java/server/Shop.java:155-171` | the pitch branch has no `else`: a player short of Perfect Pitch Leaves gets **no packet at all** and the shop window waits |
| NEW-FINDING | `src/main/java/server/Shop.java:184-195` | the token branch sends `(byte) 3` (inventory full) inside the `else` and then `(byte) 0` unconditionally at `:195`, so a failed token purchase also reports success. Identical in base `Shop.java:139-158` — inherited |
| NEW-FINDING | `src/main/java/server/Shop.java:359-361` | `createFromDB` swallows `SQLException` with `printStackTrace` and returns the half-built shop, so a failing `shopitems` query yields a valid shop with zero stock (and negative-caches it) |
| NEW-FINDING | `src/main/java/server/ShopFactory.java:96-100` | negative caching means a shop row added while the server runs (e.g. `addShopToDb`, `src/main/java/scripting/npc/NPCConversationManager.java:2451-2462`) is invisible until `reloadShops` |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/NPCShopHandler.java:53,60,66` | no null check on `getPlayer().getShop()`: a client that sends `NPC_SHOP` without an open shop NPEs the handler. Base-identical |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/NPCShopHandler.java:65` | `(byte) p.readShort()` truncates the recharge slot; slot 128+ becomes negative. Base-identical |
| NEW-FINDING | `src/main/java/scripting/reactor/ReactorActionManager.java:240-242` | `Math.random() < (dropRate / (double) mde.chance)` treats `chance` as a denominator, but the SQL filter is only `chance >= 0`, so a `reactordrops` row with `chance = 0` always drops |
| NEW-FINDING | `src/main/java/scripting/npc/NpcMakerCraftingService.java:17-57` | `sanitizeReagentQuantities` is entirely commented out, so Maker reagent packet-edit checks are dormant |

## State

**partial.**

- Shop buy/sell/recharge: **working** — `ShopMathTest` (2), `ShopRechargePriceParityTest` (3) and
  `ShopRechargeableStockPriceTest` (1) pass against the same expression the handler uses, and the `vb-5`
  scenario drives `Shop.buy`/`Shop.sell` on a live server (`GameQaWorld.java:2449-2512`).
- NPC talk routing: **partial** — every branch except the "scriptless and shopless" one has a test or an
  auto-QA check; that branch returns without `enableActions` (`NPCTalkHandler.java:85-88`) and has none.
- Gachapon: **working** — six `soloMapling/Gachapon` tests plus `t-ga-1`/`t-ga-2`/`t-sk-1` and the migration test.
  Pending-prize replay is covered by `GachaPendingPrizeTest`.
- Reactors: **partial** — `ZakumAltarReactorTest` covers state transitions for one altar; the drop path
  (`ReactorScriptManager.getDrops` + `generateDropList`) has no test, and no `reactordrops` DDL or fixture is in
  the repo, so its behaviour depends on live data that is not verifiable here.
- Shop restock: **unknown** — there is nothing to be in a state; ticket 175 is a request, not an implemented
  feature.
- Owl of Minerva / owl warp: **unknown** — no test, no auto-QA id, and the search table is only fed by
  `UseCashItemHandler.java:441`.

## Open questions

- Whether live's `config.yaml` sets `BLOCK_NPC_RACE_CONDT`, `REBIRTH_NPC_ID`, `USE_CPQ`,
  `USE_ENABLE_CUSTOM_NPC_SCRIPT` or `USE_MAKER_PERMISSIVE_ATKUP`: none appear in `docs/release/1.6.4.conf`
  SERVER_SET, and `config.yaml` was not opened (secrets).
- TEST's values for the same keys (`start-test.sh` is on another host).
- Whether the shop's `buyable` column (written as `1000` for meso rows, `1` for rechargeables,
  `src/main/java/server/Shop.java:341-356`) is meant to be a real per-restock limit: `ShopItem.getBuyable()` is
  only ever written to the packet (`PacketCreator.java:2456`) and never read by `Shop.buy`. This looks like the
  hook ticket 175's restock would use, but nothing states that.
- Why `NPCTalkHandler` returns without `enableActions` at `:87`: whether any client build recovers on its own was
  not determinable from the repo.
- Whether the `1337` GM shop and `9999992-9999999` range are still reachable at all: `ShopFactory` refuses them
  by NPC lookup (`:86-90`), and no handler or command in this subsystem opens them by shop id except
  `openShopNPC`'s fallback (`NPCConversationManager.java:639`, which asks for shop `11000`).
- The reactor drop table's live contents: no seed, no DDL, no fixture in the repo (`database/migrations/` only
  mentions `reactordrops` in migration 025's rollback).
