# Cash shop handlers

Packet-level view of the Cash Shop: which opcodes arrive, how they are gated, what each action does on the wire,
and how the shop relates to the channel/map stage. The merchandise itself — `cashshop/catalog.tsv`, its loader,
`CashShopWindowPurchase`, NX accounting, services and partner rings — is documented in
[cashshop.md](cashshop.md) and is not repeated here.

## Purpose

SlumsMS does not move the player into a cash-shop stage. The stock Cosmic entry (`EnterCashShopHandler`) cancelled
buffs, removed the character from the channel and map, saved the character, and set a `opened` flag that the rest of
the server treats as "this character is off-map". SlumsMS keeps the character exactly where they are and drives a
client-side window instead, over two opcodes that Cosmic never used (`0x3730` up, `0x3731` down).

This file covers the handler layer of that window plus the legacy paths that still exist: the stock `0xE5`
cash-operation packet (only reachable when the stage flag is faked), MTS, coupon codes, and the cash-item *use*
packets.

## Entry points

| Recv opcode | Value | Registered at | Handler |
|---|---|---|---|
| `ENTER_CASHSHOP` | `0x28` (`src/main/java/net/opcodes/RecvOpcode.java:65`) | `src/main/java/net/PacketProcessor.java:189` | `src/main/java/net/server/channel/handlers/EnterCashShopHandler.java:39` |
| `CASHSHOP_WINDOW_ACTION` | `0x3730` (`RecvOpcode.java:25`) | `PacketProcessor.java:190` | `src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:42` |
| `CASHSHOP_OPERATION` | `0xE5` (`RecvOpcode.java:205`) | `PacketProcessor.java:211` | `src/main/java/net/server/channel/handlers/CashOperationHandler.java:64` |
| `CASHSHOP_SURPRISE` | `0xA1` (`RecvOpcode.java:169`) | `PacketProcessor.java:262` | `src/main/java/net/server/channel/handlers/CashShopSurpriseHandler.java:35` |
| `CHECK_CASH` | `0xE4` (`RecvOpcode.java:204`) | `PacketProcessor.java:210` | `src/main/java/net/server/channel/handlers/TouchingCashShopHandler.java:34` |
| `ENTER_MTS` | `0x9C` (`RecvOpcode.java:165`) | `PacketProcessor.java:188` | `src/main/java/net/server/channel/handlers/EnterMTSHandler.java:56` |
| `MTS_OPERATION` | `0xFD` (`RecvOpcode.java:210`) | **not registered** — `PacketProcessor.java:227` is commented out | `src/main/java/net/server/channel/handlers/MTSHandler.java:58` (dead) |
| `COUPON_CODE` | `0xE6` (`RecvOpcode.java:206`) | `PacketProcessor.java:212` | `src/main/java/net/server/channel/handlers/CouponCodeHandler.java:189` |
| `USE_CASH_ITEM` | `0x4F` (`RecvOpcode.java:101`) | `PacketProcessor.java:164` | `src/main/java/net/server/channel/handlers/UseCashItemHandler.java:75` |

| Server-side entry | File:line | Note |
|---|---|---|
| Boot catalogue load | `src/main/java/net/server/Server.java:1036` | only when `-Dcashshop.window.enabled` is set |
| Stage exit | `src/main/java/net/server/channel/handlers/ChangeMapHandler.java:182` | `enterFromCashShop`, the only caller of `CashShop.open(false)` (`:194`) |
| Partner-ring prompt script | `scripts/npc/cashshop_partner_ring.js:21` | started by `CashShopPartnerRings.begin` |

## How it works

### 1. Entering the shop (`ENTER_CASHSHOP`, 0x28)

1. `EnterCashShopHandler.handlePacket` returns immediately when there is no player
   (`src/main/java/net/server/channel/handlers/EnterCashShopHandler.java:40`).
2. `if (Boolean.getBoolean("cashshop.window.enabled"))` (`:41`) selects the window path — on live this is on
   (see Switches).
3. It sends `PacketCreator.enableActions()` **before** validating (`:42`), so a refused player still gets input back.
4. Refusal gate: `!isAlive() || getTrade() != null || getHiredMerchant() != null` (`:43`). Nothing else is checked —
   no `cannotEnterCashShop`, no event check, no MiniDungeon check, no `isOpened` check.
5. `c.sendPacket(server.cashshop.CashShopWindowPackets.open(chr))` (`:44`) — the `RESP_OPEN` frame carrying the three
   balances (`src/main/java/server/cashshop/CashShopWindowPackets.java:79`).
6. `CashShopWindowHandler.sendCatalog(c, chr)` (`:45`) sends the category index and a cash refresh
   (`CashShopWindowHandler.java:69-72`). The player never leaves the map.
7. Fallback when the flag is off: `NPCScriptManager.getInstance().start(c, 9010004, chr)` (`:48`) then
   `enableActions()` (`:49`). `scripts/npc/9010004.js:24` turns that NPC into a menu whose option 0 is
   `cm.openNpc(9010004, "rooty")` — i.e. Rooty / Donor Shop, not a cash shop.

The stock stage transition is gone. `/root/workspace/cosmicms/src/main/java/net/server/channel/handlers/EnterCashShopHandler.java:41-90` had buff storage,
`cancelAllBuffs`, quest forfeit, `openCashShop`/`showCashInventory`/`showGifts`/`showWishList` packets,
`getChannelServer().removePlayer(mc)` (`:87`), `mc.getMap().removePlayer(mc)` (`:88`), `getCashShop().open(true)` (`:89`) and
`mc.saveCharToDB()` (`:90`). None of those calls exist in ours. `PacketCreator.openCashShop(Client, boolean)`
(`src/main/java/tools/PacketCreator.java:7239`) still compiles but has no caller.

### 2. Browsing (`CASHSHOP_WINDOW_ACTION`, 0x3730)

1. Gate: `!Boolean.getBoolean("cashshop.window.enabled") || chr == null || !chr.isAlive() || chr.getTrade() != null
   || chr.getHiredMerchant() != null` → return (`CashShopWindowHandler.java:44-46`).
2. `if (chr.getCashShop().isOpened()) return;` (`:49`) — the window is refused while the character is flagged as being
   in the legacy stage, so the two UIs can never both be live.
3. `p.available() < 1` guard, then `final int action = p.readByte()` (`:53-54`).
4. `switch`: `ACTION_REQUEST_CATALOG` → `sendCatalog`, `ACTION_REQUEST_CATEGORY` → `sendCategory`,
   `ACTION_BUY` → `buy`, `ACTION_BUY_CART` → `buyCart`, default logs
   `"Cash Shop window: unhandled action {}"` (`:56-60`). Action constants live at
   `CashShopWindowPackets.java:23-26` (`0` catalog, `1` buy, `2` buy cart, `3` category).
5. `sendCatalog` (`:69`) sends `CashShopWindowPackets.index(visibleIndex())` then `cash(chr)`. `visibleIndex()`
   (`:91`) walks `CashShopCatalog.bucket(tab, cat)` and drops categories that end up empty.
6. `sendCategory` (`:103`) reads `tab` and `cat` as bytes masked with `0xFF` (`:107-108`) and sends
   `CashShopWindowPackets.category(...)`, which chunks rows at `CHUNK = 400`
   (`CashShopWindowPackets.java:73`, `:146`). The first chunk carries flag byte `1` = "clear the category"; later
   chunks carry `0` = append. The catalogue therefore never has to fit in one packet.

The wire format is hand-matched to `CashShopWnd_HandleSync` in `DLL/src/cashshopwnd.cpp` — field order is documented
at `CashShopWindowPackets.java:14-18`, and that file is *not* in this repo.

### 3. Buying (`RESP_BUY` / `RESP_BUY_CART`)

1. `buy` (`CashShopWindowHandler.java:120`): `p.available() < 4` → return; `int itemId = p.readInt()` (`:124`).
2. Couple/friendship rings divert to `CashShopPartnerRings.begin(c, chr, itemId)` (`:125-127`) and the reply is sent
   later, when the partner-name prompt is answered (`scripts/npc/cashshop_partner_ring.js:31` calls
   `Rings.complete`).
3. Everything else: `CashShopWindowPurchase.buy(c, chr, new int[]{itemId})` (`:130`). The single-item BUY is a
   one-element cart on the server — "THE CART IS THE PRIMITIVE" (`CashShopWindowPurchase.java:42`).
4. Reply `buyResult(r.code(), itemId)` (`:132`); on `r.ok()` an extra `cash(chr)` frame (`:133-135`).
5. `buyCart` (`:138`): `count` byte (`:142`), then the bounds check **before** the reads —
   `count <= 0 || count > MAX_CART || p.available() < count * 4` → `cartResult(BUY_BAD_CART, 0, 0, 0)` (`:145-150`).
   `MAX_CART = 24` (`CashShopWindowPurchase.java:70`, "matches the client's cap").
6. `CashShopWindowPurchase.buy` then runs the transactional path (`CashShopWindowPurchase.java:189`): client lock
   (`:195`), `synchronized (chr)` (`:199`), a `CashShopCartDelivery` try-with-resources (`:200`), resolve → price →
   space probe → deliver → services → debit. Debit is `cs.gainCash(NX_CREDIT, (int) -total)` (`:352`) immediately
   followed by `delivery.commit()` (`:353`), so a rollback scope exists until the money is taken.
7. Reply `cartResult(r.code(), r.failedSn(), r.delivered(), r.spent())` (`CashShopWindowHandler.java:156-157`) —
   the cart reply carries counts because a cart is all-or-nothing and the player must be told which happened
   (`CashShopWindowPackets.java:102-109`).

Result codes are `CashShopWindowPackets.java:47-63`: `BUY_OK=0`, `BUY_NO_NX=1`, `BUY_UNKNOWN_ITEM=2`,
`BUY_INVENTORY_FULL=3`, `BUY_NOT_ON_SALE=4`, `BUY_BUSY=5`, `BUY_BAD_CART=6`, `BUY_LEVEL_TOO_LOW=7`,
`BUY_LIMIT_REACHED=8`, `BUY_PARTNER_REFUSED=9`; `BUY_CANNOT_WARE = BUY_LEVEL_TOO_LOW` (alias). Codes 7 and 8 have no
`kBuyMsg` text in the DLL and fall back to "Purchase failed." — the server compensates with its own popup.

Nothing on this path calls `saveCharToDB` (`CashShopWindowPurchase.java:61`; `CashShopWindowHandler.java:28` mentions
it only in the javadoc). The delivered items and the NX debit persist with the character's next normal save
(`src/main/java/client/Character.java:10302` → `cashshop.save(con)`).

### 4. The legacy stage path (`CASHSHOP_OPERATION`, 0xE5)

`CashOperationHandler` is the stock Cosmic handler and is reachable only if something sets
`CashShop.isOpened() == true`.

1. Hard gate `if (!cs.isOpened()) { enableActions; return; }` (`CashOperationHandler.java:68-71`).
2. `c.tryacquireClient()` (`:73`, comment "thanks Thora for finding out an exploit within cash operations") —
   the whole body runs single-flight per client, released in `finally` (`:470`).
3. `0x03`/`0x1E` (`:76`) buy one item by serial: skip a byte, read `useNX` and `snCS`, `CashItemFactory.getItem(snCS)`,
   `canBuy(chr, cItem, cs.getCash(useNX))` (`:81`, `:487` = on sale && price <= balance), level gates for the cash
   store (16) and Maple Life (30), rate-coupon gate (`:91`), then `cs.gainCash(useNX, cItem, world)` (`:101`),
   `cs.addToInventory(item)` (`:102`), `showBoughtCashItem` (`:103`). `0x1E` is the package form.
4. `0x04` (`:114`) gift: birthday check (`:123`, `checkBirthday` at `:477`), message length 1..73 (`:119`),
   recipient lookup, `cs.gift(...)`, note delivery.
5. `0x05` wish list (`:148`, ten slots), `0x06` inventory slots (`:158`), `0x07` storage slots (`:200`),
   `0x08` character slots (`:247`) — each either 4000 NX for 4 slots or a `911xxxx` cash item for 8.
6. `0x0D` take from cash inventory (`:270`), `0x0E` put into cash inventory (`:287`, refuses equipped pets and
   wedding rings, disconnects on an inventory type outside 1..5).
7. `0x1D` crush ring (`:314`), `0x20` meso purchase (`:347` — serial must be in the `8xxxxxxx` range, `gainMeso`,
   `InventoryManipulator.addById`), `0x23` friendship ring (`:375`).
8. `0x2E` name change (`:407`, needs `ALLOW_CASHSHOP_NAME_CHANGE` and serial `50600000`), `0x31` world transfer
   (`:436`, needs `ALLOW_CASHSHOP_WORLD_TRANSFER` and serial `50600001`).
9. Tail: `c.enableCSActions()` on every exit path, `log.warn("Unhandled action: {}, packet: {}", action, p)` (`:467`).

This path exists in the tree because the window deliberately does **not** use it. The javadoc at
`CashShopWindowHandler.java:20-37` states the reason: `0xE5` gates on `isOpened()`, faking `open(true)` would need the
map/server removal that the window exists to avoid, `Character.commitBuffCoupon`
(`src/main/java/client/Character.java:8191-8193`) silently refuses rate-coupon buffs while the flag is set, and
`saveCharToDB` would persist a stale `mapid` (`Character.java:9945-9948`). Purchases therefore deliver straight to
the inventory rather than the cash locker, because the locker reply (`0x145`) is only routed by the stage UI.

### 5. Cash-shop stage exit

`ChangeMapHandler.java:63` distinguishes a map change from a shop exit by `p.available() == 0`.
`:69-72` disconnects a normal map change while `isOpened()`. `enterFromCashShop` (`:182-195`): `:185` disconnects if the
flag is not set (so a forged empty packet cannot be used to skip the map), then socket checks, then
`chr.getCashShop().open(false)` (`:194`) and a channel change. Other readers of the flag:
`ChangeChannelHandler.java:44` (refuses a channel change), `WhisperHandler.java:87` (reports `RT_CASH_SHOP`),
`NoteActionHandler.java:51`, `TimeoutTask.java:25` (disconnect), `src/main/java/client/Character.java:8191`,
`GameLiveWallet.java:94` (NX desk refuses to trade while the stage is open).

### 6. MTS

`EnterMTSHandler` is the Free Market button (`PacketProcessor.java:188`), not MTS:

1. Dead → notice + `enableActions` (`EnterMTSHandler.java:59-63`).
2. `if (mc.cannotEnterCashShop())` → bare `enableActions` (`:67-70`; `cannotEnterCashShop` is the transient
   `blockCashShop` flag, `Character.java:1002`, only ever toggled by the Vega path,
   `UseCashItemHandler.java:619`/`:641`).
3. Event instance (`:72`) and `MiniDungeonInfo.isDungeonMap` (`:79-82`) refusals.
4. `fmButtonRefusal(mapId, fieldLimit)` (`:88`, `:121`) refuses `JAIL`, `MAPLEROOT_HUB` ("already in the Free Market")
   and `FieldLimit.CANNOTMIGRATE`. This replaces the stock MTS repack's missing map check (comment at `:85-87`).
5. `pressAllowed(charId, now)` (`:95`, `:111`) enforces `PRESS_COOLDOWN_MS = 2_000` (`:107`) via a
   `ConcurrentHashMap` of last press (`:109`).
6. `saveLocation("FREE_MARKET")`, `changeMap(MapId.MAPLEROOT_HUB, 0)`, `saveCharToDB()` (`:101-103`).

`MTSHandler.java` (852 lines) is fully present but unreachable: `PacketProcessor.java:227` is
`//registerHandler(RecvOpcode.MTS_OPERATION, new MTSHandler());`. If it were enabled its gate is
`if (!c.getPlayer().getCashShop().isOpened()) return;` (`MTSHandler.java:61`), which the window path never satisfies.
`tools/cosmicdiff/classifications.json` classifies `handler-unregistered:...PacketProcessor.java::MTS_OPERATION` as
`DELIBERATE` — "MTS replaced by the FM button".

### 7. Coupon codes (`COUPON_CODE`, 0xE6)

`CouponCodeHandler.handlePacket` (`src/main/java/net/server/channel/handlers/CouponCodeHandler.java:189`):

1. `p.skip(2)`, `String code = p.readString()` (`:190-191`).
2. `c.tryacquireClient()` (`:193`), then `NXCodeManager.normalize(code)` (dashes/spaces stripped,
   `src/main/java/soloMapling/server/NXCodeManager.java:33`) (`:195`).
3. `getNXCodeResult(chr, code)` (`:115`): `c.attemptCsCoupon()` (`:119`; `Client.java:1758` allows two tries then
   refuses), `SELECT * FROM nxcode WHERE code = ?` (`:124`), `-1` missing, `-2` already retrieved, `-3` expired,
   `-4` inventory cannot hold the reward. Then the claim:
   `UPDATE nxcode SET retriever = ? WHERE code = ? AND retriever IS NULL` (`:151`) and
   `if (ps.executeUpdate() != 1) return -2` (`:151-157`) — comment at `:149-150`: *"the claim is the check … two redeems at once
   both passed the SELECT above and both got the NX"*. A `SQLException` returns `-1` with the comment that nothing is
   credited unless the claim committed (`:159-163`).
4. Negative results map to `showCashShopMessage` bytes via `parseCouponResult` (`:169`):
   `-1→0xB0`, `-2→0xB3`, `-3→0xB2`, `-4→0xBB`, else `0xB1`.
5. Reward switch (`:212-258`): type `0` mesos → `gainMeso` (`:213-216`); `4` → `gainCash(1, q)` NX credit
   (`:217-220`); `1` → `gainCash(2, q)` maple point (`:221-224`); `2` → `gainCash(4, q)` prepaid (`:225-228`);
   `3` → `gainCash(1, q)` plus `gainCash(4, q / 5000)` (`:229-234`); default is an item (`:236-257`) — cash items go
   through `CashShop.generateCouponItem(item, qty)` into the cash inventory (`:249-252`, `CashShop.java:589`),
   non-cash through `InventoryManipulator.addById` (`:254`).
6. Reply: cash items are capped at 255 (`:260-268`), then
   `if (nxCredit != 0 || nxPrepaid != 0) showBoughtQuestItem(0) else showCouponRedeemedItems(...)` (`:269-273`) —
   comment: "coupon packet can only show maple points (afaik)". `c.enableCSActions()` (`:274`),
   `releaseClient()` in the `finally` (`:277`).

Reward rows come from `nxcode_items WHERE codeid = ?` (`:59`, `getNXCodeItems` at `:55`). A bad `itemId` is replaced
by item `4000000` quantity 1 with a `log.warn`, and an unholdable reward returns `null` → result `-4`.

### 8. Cash-item *use* (`USE_CASH_ITEM`, 0x4F) — hook only

Only the two paths that touch the paid-item delivery contract:

- **507 megaphones** (`UseCashItemHandler.java:285`): `switch ((itemId / 1000) % 10)` handles `1` (Megaphone, level>9),
  `2` (Super), `6` (item megaphone), `7` (triple). The `default:` at `:375` — *"no broadcast exists for this sub-type:
  keep the item"* — sends `enableActions` and `return`s **before** `remove(c, position, itemId)`, so an unhandled
  megaphone survives. This is the b297 fix (see Known issues).
- **561 Vega's Spell** (`UseCashItemHandler.java:588`): reads the equip slot and the use slot, gates on
  `vegaAccepts(itemId, uitem.getItemId(), scrollSuccess)` (`:614`), sets `player.toggleBlockCashShop()` (`:619`) so
  the player cannot re-enter the shop mid-roll, and applies the scroll with
  `ii.scrollEquipWithId(toScroll, uitem.getItemId(), false, itemId, player.isGM())` (`:627`) — the **fed** scroll id is
  what is applied, not a random one. `0x41`/`0x43` report success/boom, the scroll and the spell are removed, and a
  3-second timer restores the flag (`:636-642`). Comment at `:624-626`: it used to throw, leaving the shop blocked and
  nothing used up, "so a boom was a free retry".

Everything else in `UseCashItemHandler` is inventory-side and documented in
[handlers-inventory-items.md](handlers-inventory-items.md).

### 9. Miscellaneous handlers

- `TouchingCashShopHandler` (`src/main/java/net/server/channel/handlers/TouchingCashShopHandler.java:34-36`) sends
  `PacketCreator.showCash(chr)` and does nothing else; it is byte-identical to the Cosmic version and carries no gates.
- `CashShopSurpriseHandler` (`:35-48`): only acts `if (cs.isOpened())` (`:38`), so it is **unreachable on the window path**.
  It calls `cs.openCashShopSurprise()` and replies `onCashGachaponOpenSuccess` or `onCashItemGachaponOpenFailed`.
  Unlike Cosmic it reads no packet fields (Cosmic read a `long cashId`); ours ignores the client's cash id.
  `CashShop.openCashShopSurprise` (`src/main/java/server/CashShop.java:560`) needs item `5222000` in the locker and
  that id is deliberately not sold in `cashshop/catalog.tsv`.

## Data

| Store | Where | Written by |
|---|---|---|
| `accounts.nxCredit / maplePoint / nxPrepaid` | `CashShop.java:286` (read), `:511-543` (save) | every `gainCash`; persisted in `saveCharToDB` (`Character.java:10302`) |
| Cash-shop inventory (`ItemFactory` `CASH_EXPLORER`/`CASH_CYGNUS`/`CASH_ARAN`/`CASH_OVERALL`) | `src/main/java/client/inventory/ItemFactory.java:34-46`; loaded `CashShop.java:309-312` | `CashShop.addToInventory` / `removeFromInventory` |
| `wishlists` | `CashShop.java:298` (read), `:529-543` (delete+insert) | action `0x05` |
| `nxcode` / `nxcode_items` | `CouponCodeHandler.java:124` / `:59` | `UPDATE nxcode SET retriever` (`:151`) |
| `mts_items` | `MTSHandler.java:115-151` | dead — handler unregistered |
| `rings` | `cashshop/CashShopPartnerRings.java:397` | partner-ring purchase |
| `pets` | deleted by `CashShopCartDelivery.removeUnclaimedPets` (`src/main/java/server/cashshop/CashShopCartDelivery.java:92`) | rollback |

The window catalogue is read-only at runtime: `cashshop/catalog.tsv` → `server.cashshop.CashShopCatalog` (loader and
row semantics in [cashshop.md](cashshop.md)). The cash-shop state itself hangs off `Character`, not `Client`
(`src/main/java/client/Character.java:209`, `:8641`, `:11892`); `grep cashShop src/main/java/client/Client.java` finds
nothing.

Wire formats (all `SendOpcode.CASHSHOP_WINDOW_SYNC` = `0x3731`, `src/main/java/net/opcodes/SendOpcode.java:27`):

| Reply | Constant | Payload |
|---|---|---|
| open | `RESP_OPEN=0` | three ints: NX credit, maple point, prepaid (`CashShopWindowPackets.java:79`) |
| cash refresh | `RESP_CASH=3` | same three ints (`:87`, `:175`) |
| buy result | `RESP_BUY=2` | byte code, int itemId (`:94`) |
| cart result | `RESP_BUY_CART=4` | byte code, int failed itemId, byte delivered, int spent (`:110`) |
| category index | `RESP_INDEX=5` | short entry count, then byte tab, byte cat, int count (`:126`) |
| category body | `RESP_CATALOG=1` | chunked ≤400 rows: byte clear-flag, then int itemId, int price, short count, byte tab, byte cat, string name (`:146`) |

## Switches

| Switch | Type | Default | Read at | Live |
|---|---|---|---|---|
| `cashshop.window.enabled` | `-D` | absent in code; **true on live** | `EnterCashShopHandler.java:41`, `CashShopWindowHandler.java:44`, `Server.java:1036` | on — `deploy/slumsms.service.example:11` passes `-Dcashshop.window.enabled=true`, `README.md:23`, `release/baseline.json:8` |
| `cashshop-path` | `-D` | `cashshop` (repo-relative) | `CashShopCatalog.java:92` | not in `docs/release/1.6.4.conf`; TEST `start-test.sh` unreachable → unknown |
| `CASHSHOP_CATALOG` | shell var in `docs/release/1.6.4.conf:293` | empty | deployment script only | empty; `:291` notes `cashshop/catalog.tsv` unchanged since 1.6.2 |
| `USE_SUPPLY_RATE_COUPONS` | `config.yaml` | `true` in `config.example.yaml:205` | `CashOperationHandler.java:91`, `CashShopServices.isListed` | unknown (live `config.yaml` not printable); toggled at runtime by `src/main/java/client/command/commands/gm6/SupplyRateCouponCommand.java:40-41` |
| `ALLOW_CASHSHOP_NAME_CHANGE` | `config.yaml` | `true` (`config.example.yaml:280`) | `CashOperationHandler.java:414` | unknown |
| `ALLOW_CASHSHOP_WORLD_TRANSFER` | `config.yaml` | `true` (`config.example.yaml:281`) | `CashOperationHandler.java:443` | unknown |
| `USE_JOINT_CASHSHOP_INVENTORY` | `config.yaml` | `false` (`config.example.yaml:278`) | `CashShop.java:269` | unknown |
| `USE_ENFORCE_ITEM_SUGGESTION` | `config.yaml` | `false` (`config.example.yaml:241`) | `CashShop.java:368` | unknown |
| `USE_MTS` | `config.yaml` | `false` (`config.example.yaml:210`) | **no reader anywhere in `src/`** | irrelevant — MTS handler unregistered |

`docs/release/1.6.4.conf:271` (`UNIT_EDITS`) lists 69 flags and none of them is cash-shop related; the live unit
already carries the window flag from a previous release. TEST's launcher is on another host and not readable here —
whether TEST runs the window or the NPC 9010004 fallback is unknown.

## Invariants

1. **The window is not the stage.** `CashShopWindowHandler` returns without a packet when `isOpened()` is true
   (`CashShopWindowHandler.java:49`) and never calls `open(true)` anywhere; the only `open(false)` caller in the
   server is `ChangeMapHandler.java:194`.
2. **Input is returned before validation.** `EnterCashShopHandler.java:42` sends `enableActions` before the
   alive/trade/merchant check, so a refused player is never left without a client lock.
3. **Client lock is single-flight.** `CashShopWindowPurchase.buy` takes `c.tryacquireClient()` and answers
   `BUY_BUSY` if it fails (`CashShopWindowPurchase.java:195-197`); `CashOperationHandler` and `CouponCodeHandler` use
   the same guard.
4. **One character at a time.** `synchronized (chr)`
   (`CashShopWindowPurchase.java:199`) plus per-inventory locks in `CashShopCartDelivery`
   (`CashShopCartDelivery.java:23`, order `EQUIPPED, EQUIP, USE, SETUP, ETC, CASH`) — a fixed order, so two buyers
   cannot deadlock on each other's inventories.
5. **Money moves last.** The debit (`CashShopWindowPurchase.java:352`) is immediately followed by
   `delivery.commit()` (`:353`); every earlier failure path leaves the NX untouched and restores the inventory
   (`CashShopCartDelivery.close`, `:51`).
6. **Price is a long.** The cart total is summed as `long` before comparison with the int balance
   (`CashShopWindowPurchase.java:248`); an int sum could wrap into an affordable figure
   (regression test `CashShopPurchaseTest.priceTotalCannotWrapIntoAnAffordableInt`).
7. **No packet is trusted for its length.** Every reader checks `p.available()` before consuming:
   action byte (`CashShopWindowHandler.java:53`), category bytes (`:104`), single buy int (`:121`), cart count and
   `count * 4` (`:145`).
8. **A coupon code is claimed exactly once.** `UPDATE … WHERE code = ? AND retriever IS NULL` and a
   `!= 1` row count means "someone else got it" (`CouponCodeHandler.java:149-157`).
9. **An unhandled paid item is not consumed.** `UseCashItemHandler.java:375-378` returns before the
   `remove(...)` call.

## Origin

| File | Origin | Evidence |
|---|---|---|
| `CashShopWindowHandler.java` | **custom** | absent from `/root/workspace/cosmicms`; `git log --follow` bottoms out at `03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline`, i.e. it was added inside the repack squashed baseline and later touched by `b40e5c651 2026-10-20 Fix the Cash Shop window's dead and exploitable purchases` and `447672bbf 2026-10-22 Sell friendship and couple rings from the Cash Shop window`; `classifications.json` has no base-side entry |
| `server/cashshop/*` (7 files) | **custom** | the directory does not exist in `/root/workspace/cosmicms`. Four files came in with the repack squash — `git ls-tree 03d13cb21 src/main/java/server/cashshop/` lists `CashShopCartDelivery`, `CashShopCatalog`, `CashShopWindowPackets`, `CashShopWindowPurchase` — and three were added after it: `CashShopServices` (`b40e5c651 2026-10-20`), `CashShopPartnerRings` (`447672bbf 2026-10-22`), `CouponSchedule` (`fb0169ec8 2026-10-24`) |
| `EnterCashShopHandler.java` | **modified** (heavy) | 68 changed lines vs `/root/workspace/cosmicms`; base is the full stage transition, ours is window-or-NPC; `classifications.json`: `"java-short/java-shrunk:…/EnterCashShopHandler.java"` → `DELIBERATE`, "SlumsMS cash shop window (see 1.4.0 cash shop work)" |
| `CashOperationHandler.java` | **modified** | 29 changed lines; structure is Cosmic's, gates/constants differ |
| `CouponCodeHandler.java` | **modified** | 22 changed lines; the `UPDATE … retriever IS NULL` claim is ours (`a4136a1c3 2026-10-27 "NX merchant: never leave a 50m buyer without their code; one redeem per code"`) |
| `UseCashItemHandler.java` | **modified** | 252 changed lines; 507 `default:` (b297) and the 5-arg `scrollEquipWithId` (b290) are ours |
| `EnterMTSHandler.java` | **modified** (repurposed) | 247 changed lines; `classifications.json`: `"java-short/java-shrunk/…/EnterMTSHandler.java"` → `DELIBERATE`, "MTS button repurposed as the Free Market button" |
| `MTSHandler.java` | **modified** (dead) | 10 changed lines (import + `getCash(4)`/`gainCash(4,…)` replacing `CashShop.NX_PREPAID`); registration removed — `classifications.json`: `"handler-unregistered:…PacketProcessor.java::MTS_OPERATION"` → `DELIBERATE`, "MTS replaced by the FM button" |
| `CashShopSurpriseHandler.java` | **modified** | 30 changed lines; drops the `long cashId` read |
| `TouchingCashShopHandler.java` | **base, unmodified** | `diff -q` against `/root/workspace/cosmicms/.../TouchingCashShopHandler.java` is identical |
| `server/CashShop.java` | **modified** | 326 changed lines; `classifications.json`: `"java-method-missing:…/server/CashShop.java::CashShop.getItemsSize/0"` → `UPSTREAM-NEW` (Cosmic, 2024-06-13) |

Later commits inside the baseline (i.e. our own work, post-squash): `CashShopServices.java` `b40e5c651 2026-10-20`,
`CashShopPartnerRings.java` `447672bbf 2026-10-22`, `CouponSchedule.java` `fb0169ec8 2026-10-24`,
`CashShopCatalog.java` `7efeb2ca4 2026-10-20`.

## Tests and QA

| Test | Covers |
|---|---|
| `src/test/java/server/cashshop/CashShopButtonTest.java:17-36` | `existingButtonOpensWindowAndReleasesInputWithoutMapTransition` — sets `-Dcashshop.window.enabled=true`, asserts 4 packets, opcode `0x3731`, first payload byte `RESP_OPEN`, and `verify(player, never()).changeMap(anyInt())` + `never()).open(true)` |
| `src/test/java/net/server/channel/handlers/CouponCodeRedeemTest.java:55,78,93,107` | credits once (dashes tolerated), lost claim credits nothing, DB error credits nothing, unknown code |
| `src/test/java/net/server/channel/handlers/EnterMTSHandlerTest.java:47,55,62,72` | jail, `CANNOTMIGRATE`, one warp per cooldown, refusal reasons |
| `src/test/java/net/server/channel/handlers/UseCashItemUnhandledMegaphoneTest.java` | b297: an unhandled megaphone is not consumed |
| `src/test/java/net/server/channel/handlers/VegaAppliesFedScrollTest.java`, `VegaSpellScrollTest.java` | b290: the fed scroll's stats apply |
| `src/test/java/server/cashshop/CashShopPurchaseTest.java:53-405` | rollback, single debit, no NX, duplicate carts, pet cleanup, long total, slot services, character slot race, level gates, hidden rate coupons, gender, expiry |
| `src/test/java/server/cashshop/CashShopPartnerRingsTest.java:184-301` | both ring rows, same-gender, offline/self/bot refusals, NX, full equip tab, rollback on partner failure, ring-table failure |
| `src/test/java/server/cashshop/CashShopCatalogShippedTest.java:31`, `CashShopCatalogFixesTest.java:40`, `CashShopPartnerRingCatalogWzTest.java`, `CouponScheduleTest.java` | catalogue loader against the shipped file and a WZ dump |

Auto-QA (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`):
`t-vb-5` (`:813-817`) auto, buys and uses EXP card `5211048` and checks `VB CASH card=5211048 given=true rate 1 -> [2-9]`;
`t-nx-1` / `t-nx-2` (`:1200-1225`) drive `nxRedeem` (`:1687`, `:1693` →
`((SyntheticPlayer) r.actor(2)).redeemCoupon(c.replace("-", ""))`) — the synthetic player types into the coupon box.
Manual only: `t-rg-1` / `t-rg-2` (`:657-661`, "the Cash Shop purchase flow (enter the shop, Buy, the partner-name
prompt) is client-side cash shop packets the synthetic player does not speak yet") and `t-cl-2` (`:773`, "Cash Shop
mouse wheel"). QA scenario `src/main/java/soloMapling/qa/CouponScenarios.java:6,17` (`co-1`) checks the Setup/Game
category contents and a 3x EXP card purchase.

**Not covered:** `EnterCashShopHandler`'s window branch end-to-end (only the packet shape is asserted, with mocks);
`CashShopWindowHandler` `buy`/`buyCart` packet parsing and its `available()` bounds; every `CashOperationHandler`
action (no test class exists for `0xE3`…`0x31`); `CashShopSurpriseHandler`; `TouchingCashShopHandler`; `MTSHandler`
(no test, and unregistered); the 507 megaphone *dispatch* (only the unhandled default is tested); the actual DLL-side
wire compatibility — the DLL is not in this repo.

## Known issues

| Card | One line |
|---|---|
| `b297` (`/root/work/STATUS-1.6.4.md:47`, S2) | Cash items `5070000` / `5073000` / `5074000` are consumed without effect. Fixed in `UseCashItemHandler.java:375-378` (keep the item) with `UseCashItemUnhandledMegaphoneTest` (`:39` `@ValueSource(ints = {5070000, 5073000, 5074000})`); the open owner decision in `/root/work/COORDINATOR-REPORT-1.6.4.md:124,552` ("remove them from the shop or build a broadcast") was settled by removing them from Rooty on 2026-11-04 — `src/test/java/scripting/RootyShopSelectionTest.java:131` counts the shop as `-3` for exactly those ids, and `grep 5070000 scripts/npc/rooty.js` is now empty. The three ids are still not handled by any broadcast, so any other source of them is a paid item that does nothing. |
| `b290` (`/root/work/STATUS-1.6.4.md:40`, S2) | Vega's Spell: a level-based roll replaced the fed scroll, a pure loss at level 15+. Fixed by the 5-arg `scrollEquipWithId` (`UseCashItemHandler.java:627`, commit `b0e5e4c58`) with `VegaAppliesFedScrollTest`; `/root/work/COORDINATOR-REPORT-1.6.4.md:300` notes b290/b297 share `UseCashItemHandler.java` with "low" overlap |
| `NEW-FINDING:` `EnterCashShopHandler.java:41-45` | The window path skips every stocking gate the stock handler had — no `cannotEnterCashShop`, event, or MiniDungeon check, and no `isOpened()` check. A player allowed into an event instance can open the window; `EnterMTSHandler.java:67-83` shows the intended refusals exist, so the window path is the odd one out. Cosmetic only while purchases are inventory-delivered, but it contradicts the sibling handler. |
| `NEW-FINDING:` `CashShopSurpriseHandler.java:38` | Guarded by `cs.isOpened()`, which the window path never sets, so the packet is dead on live. `CashShop.openCashShopSurprise` (`CashShop.java:560`) is reachable only through the dead stage, and item `5222000` is not in `cashshop/catalog.tsv`, so the surprise box can be neither bought nor opened. |
| `NEW-FINDING:` `MTSHandler.java:58-65` | 852 lines of unreachable MTS code retained in the tree with DB writes (`mts_items` INSERT/UPDATE/DELETE at `:135`, `:151`, `:253`, `:323`). Keeping the class is `DELIBERATE` per `classifications.json`, but nothing prevents a future `registerHandler` line from enabling an untested economy path. |
| `NEW-FINDING:` `CashShopWindowHandler.java:121-123` | A short `p.available() < 4` single buy returns **silently** — no `buyResult`, no `enableActions`. The cart path answers `BUY_BAD_CART` for the equivalent malformed case (`:145-150`), so the two disagree; a crafted short packet leaves a real client waiting. |
| `NEW-FINDING:` `CouponCodeHandler.java:260-268` | The 255 cash-item cap silently drops items beyond 255 (`cashItems = Arrays.asList(new Item[255])` discards the tail) *after* the code has been claimed, so a big reward is unrecoverable. |
| `NEW-FINDING:` `CashOperationHandler.java:114` | `//TODO check for gender` on the gift action `0x04` — a gift can be sent for a wearable the recipient cannot wear, unlike the window path which refuses with `BUY_CANNOT_WEAR` (`CashShopServices.check`). |
| `NEW-FINDING:` `CashShop.java:269-283` | `factory` stays `null` for any `jobType` outside 0/1/2 when `USE_JOINT_CASHSHOP_INVENTORY` is false, and `factory.loadItems(...)` at `:311` then NPEs — so the character cannot load. `jobType` is `job.getId() / 1000` (`src/main/java/client/Character.java:6403-6405`) and `Job.EVAN1(9900)` (`src/main/java/client/Job.java:63`) divides to `9`, so an Evan character load is the concrete trigger. Inherited from the base (`/root/workspace/cosmicms/src/main/java/server/CashShop.java:84-96` has the same switch), not introduced here, but it is on the cash-shop load path this file documents. |

## State

**partial.** The window path works and is exercised (button test asserts the exact packet sequence; purchase engine has
broad unit coverage; `-Dcashshop.window.enabled=true` is what live's service unit passes at
`deploy/slumsms.service.example:11`). What is partial:

- the *legacy* stage path is intact but unreachable — `CashOperationHandler` is gated on `isOpened()`
  (`CashOperationHandler.java:68`), `CashShop.open(true)` has no caller, `CashShopSurpriseHandler` is dead as a result,
  and `UseCashItemHandler`'s locker-facing branches (take/put) are server-side without a UI.
- `MTSHandler` is dead code (`PacketProcessor.java:227`).
- No test covers `CashShopWindowHandler`'s packet parsing, nor any `CashOperationHandler` action.
- The catalogue content is explicitly unfinished (chair/mount "PENDING" categories and unsold `5222000` per
  `cashshop/catalog.tsv:1-30`), which is [cashshop.md](cashshop.md)'s scope.

## Open questions

1. **Does TEST run the window or the NPC fallback?** `-Dcashshop.window.enabled` must be passed on the java command;
   live's unit does (`deploy/slumsms.service.example:11`) and `docs/release/1.6.4.conf` does not re-issue it, but TEST's
   `start-test.sh` is on another host and unreachable from here.
2. **Is the DLL's `CashShopWnd_HandleSync` in sync with `CashShopWindowPackets`?** The comment at
   `CashShopWindowPackets.java:14-18` asserts field-order equality with `DLL/src/cashshopwnd.cpp`, and that file is not
   in this repo, so the claim cannot be checked here. Same for `kBuyMsg` lacking text for codes 7/8.
3. **Why is `CashShopSurpriseHandler` still registered** (`PacketProcessor.java:262`) when its only precondition can
   never hold on the window path? Either it is meant to be re-gated or the registration is leftover.
4. **What should the legacy `0xE5`/MTS surface do long-term?** They are kept "for the stock path" with no way to reach
   it. Whether the intent is deletion after a release of soak time is not recorded in
   `/root/work/OWNER-DECISIONS.md` (no cash-shop entry there).
5. **Where do coupon codes come from operationally now?** `nxcode` is only read here; the minting side
   (`src/main/java/soloMapling/server/NXCodeManager.java`) and how live codes are issued is outside this file's scope
   and was not traced.
