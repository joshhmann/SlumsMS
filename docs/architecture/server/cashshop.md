# Cash shop item system

Packet-level detail (opcodes, action codes, packet layouts, handler gating) lives in
[handlers-cashshop.md](handlers-cashshop.md). This file covers the merchandise: the catalogue file, its loader,
the purchase engine, NX, services and partner rings.

## Purpose

The server owns the cash-shop merchandise itself. Instead of deriving "what is for sale" from `Commodity.img`
serials, `cashshop/catalog.tsv` is a plain text file whose rows are keyed by item id, and
`server.cashshop.CashShopCatalog` loads it into immutable snapshots
(`src/main/java/server/cashshop/CashShopCatalog.java:20-55`). Buying runs one transactional path
(`CashShopWindowPurchase.buy`) that validates, checks space, delivers every item, then debits NX — all or nothing,
with a rollback scope (`CashShopCartDelivery`) that restores the inventory if anything fails.

## Entry points

| Entry | File:line | What it does |
|---|---|---|
| `RecvOpcode.ENTER_CASHSHOP` handler | `src/main/java/net/PacketProcessor.java:189` → `src/main/java/net/server/channel/handlers/EnterCashShopHandler.java:41` | flag on: sends `CashShopWindowPackets.open` + the catalogue index (`:44-45`); flag off: stock NPC 9010004 (`:48`) |
| `RecvOpcode.CASHSHOP_WINDOW_ACTION` handler | `src/main/java/net/PacketProcessor.java:190` → `src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:41` | catalog/category/buy/buy-cart dispatch (`:55-62`) |
| Boot catalogue load | `src/main/java/net/server/Server.java:1036` | `if (Boolean.getBoolean("cashshop.window.enabled")) CashShopCatalog.load();` |
| GM reload `!reloadcashshop` | `src/main/java/client/command/CommandsExecutor.java:457` → `src/main/java/client/command/commands/gm3/ReloadCashShopCommand.java:21-31` | re-runs `load()`, prints row/category/reject counts |
| Single buy | `src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:120-136` | `CashShopWindowPurchase.buy(c, chr, new int[]{itemId})` |
| Cart buy | `src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:138-165` | bounds `count*4` before reads (`:145`), then the same `buy` |
| Partner-ring buy | `src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:124-127` → `CashShopPartnerRings.begin` | name prompt, then `complete` |
| Coupon schedule load | `src/main/java/net/server/Server.java:963-964` | `loadCouponRates` (`:667`) + `updateActiveCoupons` (`:719`) |
| `@coupons` command | `src/main/java/client/command/commands/gm0/CouponsCommand.java:44-71` | reads `CashShopCatalog.byItemId` for names and `Server.getCouponSchedule` for hours |

## How it works

1. **Catalogue load.** `CashShopCatalog.load()` (`src/main/java/server/cashshop/CashShopCatalog.java:132`) resolves
   the file through `file()` (`:90-92`: `-Dcashshop-path`, else `./cashshop`, then `catalog.tsv`). A missing file
   keeps the previously loaded rows and returns `LoadStats.loaded=false` (`:133-138`). Otherwise each non-`#` line is
   split on tab; fewer than 7 fields is `malformed` (`:152-158`), seven ints are parsed (`:159-175`), and rows are
   rejected when price<0, count<1 or >`Short.MAX_VALUE`, tab/cat outside 0..255, period<0, gender outside 0..2, or a
   pet/equip row with `count != 1` (`:175-180`). Service rows must have count 1 (`:186-189`). An item whose
   `getInventoryType` is `UNDEFINED` and that is not a service is counted `unusable` (`:190-194`). The name comes from
   column 8 or `String.wz` (`:196-200`), is truncated to `MAX_NAME` 40 (`:206-208`) and then trimmed until its UTF-8
   bytes fit 64 (`:210-212`). Duplicate item ids keep the first row (`:215-221`).
2. **Snapshot swap.** `swap()` (`:237-254`) builds `byItemId`, per-`(tab<<8)|category` buckets in file order,
   and `index()` as `{tab, category, count}` sorted by key, then assigns them as `Map.copyOf`/`List.copyOf`. Readers
   mid-request keep the previous set; a failed read never empties a running shop (`:124-131`).
3. **Listing.** `CashShopWindowHandler.sendCatalog` sends only the index plus the NX display
   (`src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:69-72`). A category is sent on demand
   (`:103-112`) through `visibleBucket` (`:79-88`), which drops rate coupons while `USE_SUPPLY_RATE_COUPONS` is off,
   and `visibleIndex` re-counts per bucket and drops empty ones (`:91-100`). `CashShopWindowPackets.category`
   (`src/main/java/server/cashshop/CashShopWindowPackets.java:146-173`) chunks at `CHUNK=400` (`:73`), flag 1 on the
   first chunk meaning "clear this category", and always emits at least one packet.
4. **Purchase, phase 1 — resolve and validate, nothing mutated.** `CashShopWindowPurchase.buy`
   (`src/main/java/server/cashshop/CashShopWindowPurchase.java:189`) rejects an empty or >`MAX_CART` (24, `:70`) cart
   (`:190-192`), takes the client lock (`:195-197`), then under `synchronized (chr)` opens the delivery scope
   (`:199-200`). Per row: duplicate id → `BUY_BAD_CART` (`:205-207`); not in the catalogue → `BUY_UNKNOWN_ITEM`
   (`:210-213`); partner ring → refused with a chat line (`:218-221`); `CashShopServices.check` denial →
   `BUY_*` with the denial text (`:223-228`); service rows skip the space arithmetic (`:230-233`);
   `ii.getSlotMax(...) <= 0` → `BUY_UNKNOWN_ITEM` (`:239-241`).
5. **Phase 2 — affordability.** The total is summed as a `long` and compared once against
   `cs.getCash(NX_CREDIT)` (`:245-253`), so 24 near-`MAX_VALUE` rows cannot overflow into a negative total.
6. **Phase 3 — space, cumulatively.** Pickup-restricted duplicates are refused (`:257-268`), pets are counted
   separately because they cannot be probed (`:270-278`), probes are plain `Item` objects, never `toItem()`, so a
   failed check leaves no pet rows (`:279-285`), `canHoldUniques` and `Inventory.checkSpots` run on the whole cart
   (`:293-298`), and a cart with pets additionally requires `CASH.isFull(cashLines - 1)` — deliberately conservative
   (`:300-311`).
7. **Phase 4 — build, deliver.** Every `Item` is built first (`:316-325`, `toItem` at `:92-117`: pet rows via
   `Pet.createPet` tracked by the scope, equips via `ii.getEquipById`, `EXPIRING_ITEMS` + period>0 →
   `setExpiration` in days, `:111-114`), then `deliveryStarted()` and an `InventoryManipulator.addFromDrop` loop
   (`:326-338`). A failed add returns `BUY_INVENTORY_FULL` and the scope restores.
8. **Phase 4b — services.** `grantServices` (`:138-165`) grows each slot-expansion tab by 8 via
   `chr.gainSlotsUnsaved`, undoing earlier growth on failure (`:145-147`), and grants the account character slot last
   through `c.gainCharacterSlot()` (`:153-155`).
9. **Phase 5 — debit.** `cs.gainCash(NX_CREDIT, -total)`, `delivery.commit()`, one log line, then
   `noteCouponHours` prints when a bought rate coupon works (`:351-360`).
10. **Rollback.** `CashShopCartDelivery.close` (`src/main/java/server/cashshop/CashShopCartDelivery.java:51-60`)
    restores originals when not committed (`restore` `:62-90`, chunked `ModifyInventory` at 100 changes, `:79-81`) and
    deletes pet rows created for the failed cart (`:92-101`, `DELETE FROM pets WHERE petid=?`).
11. **NX.** Balances are the account's `nxCredit`/`maplePoint`/`nxPrepaid` columns, read once when the `CashShop`
    object is built (`src/main/java/server/CashShop.java:286-294`) and written back in the character save transaction
    (`CashShop.save` `:511-518`, called from `src/main/java/client/Character.java:10302` under `!isOwnedAlt()`).
    `gainCash` is synchronized and feeds `EconomyCounters.recordNxEarned/recordNxSpent` (`:347-364`). NX enters from
    NX card drops (`src/main/java/client/Character.java:2567`), coupon codes
    (`src/main/java/net/server/channel/handlers/CouponCodeHandler.java:218`), the exchange desk
    (`src/main/java/soloMapling/Economy/exchange/GameLiveWallet.java:216`) and bots; nothing sells NX for cash.
12. **Delivery destination.** The window never uses the cash locker: items go into the character's real inventories
    by `ItemConstants.getInventoryType` (`src/main/java/constants/inventory/ItemConstants.java:509-523`), i.e. equips
    to EQUIP, use scrolls and coupons to USE, chairs to SETUP, pets and effects to CASH. The stock stage path still
    delivers into `CashShop.inventory` (`src/main/java/server/CashShop.java:409`, the factory-loaded locker at
    `:311-313`), which is what `USE_JOINT_CASHSHOP_INVENTORY` chooses between (`:269-283`).

### Partner rings

`CashShopPartnerRings.begin` (`src/main/java/server/cashshop/CashShopPartnerRings.java:136`) runs the cheap refusals
(catalogue membership, `CashShopServices.check`, NX at `:148`) and parks the purchase in `PENDING` for
`PENDING_MS` = 5 min (`:61`, `:122`, `:153`), asking for the partner name through NPC script
`cashshop_partner_ring` (`:57`) on NPC 9010000 (`:59`). `complete` (`:176`) takes the parked entry once
(`:177-181`) and calls `purchase` (`:240`): blank or self name (`:245-251`), gates re-checked (`:256-259`), partner
must be online in the same world and a real player, not a `BotClient` (`:260-271`), no duplicate shared ring
(`:272-274`), NX again (`:275-278`), then both character monitors taken lower-id-first (`:282-288`). `deliver`
(`:298`) opens two delivery scopes (`:305-308`), checks EQUIP space on both sides (`:309-314`),
`store.createPair` writes the ring rows (`:323`), both equips are added (`:331-336`), NX is debited once (`:338`),
both scopes commit (`:339-341`), and a `finally` deletes the pair if delivery did not complete (`:342-346`), then
`addPlayerRing` on both characters (`:349-350`). `dialogClosed` (`:193`) answers `BUY_PARTNER_REFUSED`.

### Rate coupons

`CashShopServices.isRateCoupon` is deliberately narrower than `ItemConstants.isRateCoupon`: only `5211xxx` and
`5360xxx` (`src/main/java/server/cashshop/CashShopServices.java:50-57`), so the `5431/5432` Maple Life cards are not
hidden by a flag about rates. Schedules come from `nxcoupons` (`src/main/java/net/server/Server.java:667-680`) into
`CouponSchedule` (`fromDb` `src/main/java/server/cashshop/CouponSchedule.java:25`), whose `activeAt` compares the
server clock (`:68-72`) and whose display text is shifted to `America/Los_Angeles` when the offset is whole hours
(`playerShiftHours` `:107-112`, `purchaseNote` `:192`). `fromWz` (`:30`) parses the WZ `info/time` strings used by the
catalogue tool; nothing in `src/main/java` calls it.

## Data

### `cashshop/catalog.tsv`

10,733 lines: 36 comment/blank lines and 10,697 data rows (`wc -l`, `grep -vc '^#'`). Item ids span 1,000,000
(`1000000`) to 9,114,000 (`9114000`). The column header at `cashshop/catalog.tsv:6`:

| Column | Meaning |
|---|---|
| `itemId` | item id AND the key: one row per item, no serial number (`src/main/java/server/cashshop/CashShopCatalog.java:20-33`, `byItemId` `:103`) |
| `price` | NX credit charged for one purchase; 300..31,500 in the shipped file, and no row is free (`CashShopCatalogShippedTest` asserts `price() > 0`) |
| `count` | stack size delivered per purchase; 1..35 in the file, loader caps it at `Short.MAX_VALUE` (`:175`) |
| `tab` | client tab: 2 = Equip, 3 = Other, 5 = Setup, 6 = Pet (`CashShopWindowPackets.category` writes it as a byte) |
| `category` | category inside the tab; `bucket()` is `(tab<<8)\|category` (`src/main/java/server/cashshop/CashShopCatalog.java:65-67`) |
| `period` | days before expiry, 0 = permanent; only two rows are non-zero (`5211048` 7 days `:8913`, `5211060` 1 day `:8914`) |
| `gender` | 0 male / 1 female / 2 both — display only; the purchase gate goes by item id (`src/main/java/server/cashshop/CashShopCatalog.java:44-47`, `ItemConstants.getEquipGender` `:418-424`) |
| `name` | optional; when empty the loader falls back to `String.wz` (`:196-200`) |

Shipped distribution: 2/0 Hat 1792, 2/1 Face 243, 2/2 Eye 128, 2/3 Overall 1292, 2/4 Top 399, 2/5 Bottom 291,
2/6 Shoes 655, 2/7 Glove 153, 2/8 Weapon 797, 2/9 Ring 387, 2/11 Cape 629, 3/1 Messenger 31, 3/2 Weather 37,
5/0 Beauty Parlor 106, 5/1 Store 22, 5/2 Game 1925, 5/3 Facial Expression 15, 5/4 Wedding 5, 5/5 Effect 435,
5/6 Character 67, 6/0 Pet 842, 6/1 Pet Equip 421, 6/2 Pet Use 25 — 23 buckets, 10,697 rows.
Per tab: tab 2 = 6766 rows (ids 1000000..1702941), tab 3 = 68 (5071000..5390006), tab 5 = 2575 (1902008..9114000),
tab 6 = 1288 (1802000..5380000). Gender column: 530 male, 645 female, 9522 both.

Service rows are never delivered as items (`CashShopServices.isService`, `src/main/java/server/cashshop/CashShopServices.java:45-47`):
`5430000` Extra Character Slot Coupon at `cashshop/catalog.tsv:9439`, and `9111000`-`9114000` (+8 slots for
Equip/Use/Set-up/Etc) at `:9442-9445`. Storage +8 (`9110000`) is deliberately not sold (`cashshop/catalog.tsv:11-13`).

### Runtime views

`CashShopCatalog` holds four `volatile` snapshots (`src/main/java/server/cashshop/CashShopCatalog.java:76-79`): `all` (file order), `byItemId`,
`byBucket` (per-bucket `List.copyOf`, sorted keys), `index`; `lastLoad` is the last `LoadStats`
(`rows, categories, unusable, duplicates, malformed, loaded` + `rejected()`, `:84-89`).

### Persistence

`accounts.nxCredit`, `accounts.maplePoint`, `accounts.nxPrepaid` (`src/main/java/server/CashShop.java:286`, `:511`);
`accounts.characterslots` for the extra-character-slot grant (`src/main/java/client/Client.java:1528-1545`);
`rings` rows for partner rings (`src/main/java/server/cashshop/CashShopPartnerRings.java:400`, delete at `:434`); `pets` rows created by
`Pet.createPet`; `gifts` for stock-stage gifting (`src/main/java/server/CashShop.java:443-455`); `nxcoupons` for schedules
(`src/main/java/net/server/Server.java:668`); `wishlists` and the cash locker tables via the item factory (`src/main/java/server/CashShop.java:297-305`, `:311-313`).

### Generator tooling

`tools/cashshop/` builds the file: `build_catalog.py` (307 lines), `catalog_rules.py` (325), `lint_catalog.py` (158),
`wzindex.py` (218), `coupon_schedule.py` (102), `overrides.yaml` (90), `featured.tsv` (329), `catalog_header.txt` (18,
prepended to the TSV), `test_coupon_schedule.py` (151). `tools/cashshop/catalog_rules.py:22-34` holds the category table; the `NEVER`
list (`:108-118`) excludes e.g. `5222000` Cash Shop Surprise, `5400000-5401999` name change/transfer and
`5070000-5070999` cheap megaphones.

## Switches

| Switch | Where | Default | Effect |
|---|---|---|---|
| `-Dcashshop.window.enabled` | `src/main/java/net/server/Server.java:1036`, `src/main/java/net/server/channel/handlers/EnterCashShopHandler.java:41`, `src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:44`, `src/test/java/server/cashshop/CashShopButtonTest.java:19` | absent = false (`Boolean.getBoolean`) | true = the custom window and the TSV catalogue; false = stock NPC 9010004 stage. The catalogue is not even loaded when false (`src/main/java/net/server/Server.java:1036`) |
| `-Dcashshop-path` | `src/main/java/server/cashshop/CashShopCatalog.java:91` | `./cashshop` | directory holding `catalog.tsv` |
| `USE_SUPPLY_RATE_COUPONS` | `src/main/java/config/ServerConfig.java:298`, example `config.example.yaml:205` | example `true` | false hides `5211xxx`/`5360xxx` from the listing (`src/main/java/server/cashshop/CashShopServices.java:68-69`, `src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:83`) and refuses their purchase (`src/main/java/server/cashshop/CashShopServices.java:91-94`) |
| `USE_JOINT_CASHSHOP_INVENTORY` | `src/main/java/config/ServerConfig.java:370`, example `:278` | example `false` | chooses a shared `CASH_OVERALL` locker vs per-job lockers (`src/main/java/server/CashShop.java:269-283`); stock stage only |
| `USE_ENFORCE_ITEM_SUGGESTION` | `src/main/java/config/ServerConfig.java:336`, example `:241` | example `false` | stock path only: when false, `addCashItemBought` records the sale (`src/main/java/server/CashShop.java:366-371`) |
| `USE_CLEAR_OUTDATED_COUPONS` | `src/main/java/config/ServerConfig.java:371`, example `:279` | example `true` | `src/main/java/net/server/Server.java:639` cleanup of expired coupon items |
| `USE_ANNOUNCE_NX_COUPON_LOOT` | `src/main/java/config/ServerConfig.java:367`, example `:275` | example `false` | NX card pickup hint (`src/main/java/client/Character.java:2570-2573`) |
| `USE_STACK_COUPON_RATES` | `src/main/java/config/ServerConfig.java:466`, example `:383` | example `false` | `@coupons` text only (`src/main/java/client/command/commands/gm0/CouponsCommand.java:65`) |
| `ALLOW_CASHSHOP_NAME_CHANGE` | `src/main/java/config/ServerConfig.java:372`, example `:280` | example `true` | `src/main/java/net/server/channel/handlers/TransferNameHandler.java:51` |
| `ALLOW_CASHSHOP_WORLD_TRANSFER` | `src/main/java/config/ServerConfig.java:373`, example `:281` | example `true` | `src/main/java/net/server/channel/handlers/TransferWorldHandler.java:50` |
| `ENABLE_PIC` | `src/main/java/config/ServerConfig.java:33`, example `:67` | example `true` | unrelated to purchases; named here only because it is the same block of cash-shop-ish keys |

Live: `docs/release/1.6.4.conf` carries no `cashshop.window` and no cash-shop config key; its `UNIT_EDITS`
(`:271`) has 69 `-D` flags, none of them `cashshop.window.enabled`, and it only ships the data file via
`CASHSHOP_CATALOG=""` (`:293`, comment `:291-292`: the file is unchanged since 1.6.2). The flag is documented as on
in `README.md:23`, `deploy/slumsms.service.example:11` and `docs/release/1.3.0.md:79,87`, i.e. live's unit is
maintained outside this repo; whether that unit still passes it cannot be proven from here. TEST: unknown
(`start-test.sh` is on another host and was not read).

## Invariants

- One row per item id; the id is the buy key, there are no serial numbers (`src/main/java/server/cashshop/CashShopCatalog.java:20-33`).
- Readers never see a half-built catalogue: every view is swapped as an immutable copy (`src/main/java/server/cashshop/CashShopCatalog.java:237-254`).
- A missing or unreadable file never empties a running shop (`:124-138`, `:226-230`).
- Nothing is mutated before phase 4, and nothing is debited before phase 5 (`src/main/java/server/cashshop/CashShopWindowPurchase.java:203`, `:245`,
  `:257`, `:351`): every refusal after delivery starts is undone by the delivery scope
  (`src/main/java/server/cashshop/CashShopCartDelivery.java:51-60`).
- A cart costs the sum of its rows, computed in `long` (`src/main/java/server/cashshop/CashShopWindowPurchase.java:249-251`), and is charged exactly
  once (`:352`).
- Pets always need their own CASH slot; the space check over-reserves rather than under-reserves
  (`src/main/java/server/cashshop/CashShopWindowPurchase.java:300-311`).
- Slot expansion caps: 96 slots per tab (`src/main/java/client/Character.java:10880-10883`), 15 character slots
  (`src/main/java/client/Client.java:1514`), both re-checked at grant time (`src/main/java/server/cashshop/CashShopWindowPurchase.java:144`, `:153`).
- Partner rings are all-or-nothing across two characters: pair rows deleted if either side fails
  (`src/main/java/server/cashshop/CashShopPartnerRings.java:342-346`), monitors always taken lower-id-first (`:282-288`).
- Gender is enforced on the id digit rule, not on the catalogue `gender` column
  (`src/main/java/constants/inventory/ItemConstants.java:418-430`, `src/main/java/server/cashshop/CashShopServices.java:96-99`).

## Origin

| Path | Class | Evidence |
|---|---|---|
| `src/main/java/server/cashshop/*` (7 files) | **custom** | `git log --follow` on `CashShopWindowPurchase.java` shows `03d13cb21` baseline then `b40e5c651`, `ea0185a31`, `7efeb2ca4`, `447672bbf`, `fb0169ec8`, `dc950eda5`; `/root/workspace/cosmicms/src/main/java/server/cashshop` does not exist |
| `cashshop/catalog.tsv` | **custom** | same recipe; commits `03d13cb21`, `b40e5c651`, `ea0185a31`, `7efeb2ca4`, `11898a01c`; no `cashshop/` dir upstream |
| `src/main/java/server/CashShop.java` | **modified** (base file) | `diff -q` against `/root/workspace/cosmicms/...` differs; `tools/cosmicdiff/classifications.json:187-190` marks `CashShop.getItemsSize` as `UPSTREAM-NEW`, i.e. the file is base and upstream moved on |
| `src/main/java/net/server/channel/handlers/EnterCashShopHandler.java` | **modified** | `tools/cosmicdiff/classifications.json:133-139` classes `java-short:` and `java-shrunk:` both `DELIBERATE`, why "SlumsMS cash shop window" |
| `tools/cashshop/*` | **custom** | no upstream counterpart; generated-file header at `cashshop/catalog.tsv:1-2` |

Repo docs call the work "1.4.0 cash shop" (`tools/cosmicdiff/classifications.json:136`) and the catalogue tool
`cashshop-catalog-tool` (`docs/tickets/52-marriage-and-rings.md:6`).

## Tests and QA

| Where | Count | Covers |
|---|---|---|
| `src/test/java/server/cashshop/CashShopPurchaseTest.java` | 19 `@Test` | cart phases, price overflow, rollback of pets/rows |
| `src/test/java/server/cashshop/CashShopPartnerRingsTest.java` | 15 | prompt lifecycle, refusals, pair deletion, both-sided delivery (fake `RingStore`/`Directory`/`Prompt`) |
| `src/test/java/server/cashshop/CashShopCatalogFixesTest.java` | 8 | loader keeps service rows/drops packages, shipped prices, inert items, rate-coupon expiry, ring-only-via-partner, WZ cash flag, equip gender |
| `src/test/java/server/cashshop/CashShopCatalogShippedTest.java` | 1 | every shipped line loads: 0 malformed/duplicates/unusable, row count == data lines, no free row, names <= 40 |
| `src/test/java/server/cashshop/CashShopPartnerRingCatalogWzTest.java` | 2 | ring ids against WZ |
| `src/test/java/server/cashshop/CouponScheduleTest.java` | 6 | day masks, hour windows, zone shift |
| `src/test/java/server/cashshop/CashShopButtonTest.java` | 1 | enter button sends `0x3731` and releases input |
| `tools/cashshop/test_coupon_schedule.py` | 151 lines | coupon names from real WZ `info/time` strings |

Auto-QA (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`): `t-rg-1` (`:657-660`) and `t-rg-2` (`:661-662`) are
**manual** — "the Cash Shop purchase flow is client-side cash shop packets the synthetic player does not speak yet";
`t-cl-2` (`:773`) manual for the mouse wheel; `t-vb-5` (`:813-817`) automated buys and uses EXP card `5211048`
(`VB CASH card=5211048 given=true rate 1 -> [2-9]`).

`!qa` scenarios: `pr-1`/`pr-2` in `src/main/java/soloMapling/qa/PartnerRingScenarios.java:50-51` drive the partner-ring
flow with the `RING` QA topic (`src/main/java/soloMapling/qa/QaLog.java:56`); `nx-1`
(`src/main/java/soloMapling/qa/NxScenarios.java`) reads `getCash(1)` after a meso→NX conversion.

Not covered: no headless test drives `CashShopWindowPurchase.buy` through the packet path (`t-rg-1` is manual for
exactly that reason); no test covers `CashShopWindowHandler` gating beyond the enter button; nothing exercises the
`Restore` path against a real client; no test asserts the catalogue's delivered inventory tab per item; `loadGifts`
has no test because it has no caller (see below).

## Known issues

| Card | What | Where |
|---|---|---|
| b297 (S2, Server) | Cash items `5070000` / `5073000` / `5074000` are consumed without effect | `/root/work/STATUS-1.6.4.md:47`; `docs/tickets/182-server-code-audit-1-6-4.md:58`; the tool's `NEVER` list drops `5070000-5070999` and the `5073xxx`/`5074xxx` families (`tools/cashshop/catalog_rules.py:118`), so the shipped file lists only `5071000` (`cashshop/catalog.tsv:6803`) and `5072000` (`:6804`) — the card is about the items, not about them being sold |
| b290 (S2) | Vega's Spell level-based roll replaces the fed scroll: paid-item loss at level 15+ | `/root/work/STATUS-1.6.4.md:40`; `5251000-5251003` wedding tickets are in the catalogue (`docs/tickets/52-marriage-and-rings.md:20`) |
| NEW-FINDING | The cash-shop stage cannot open at all in this fork: `CashShop.open(boolean)` (`src/main/java/server/CashShop.java:377`) is called once, always with `false` (`src/main/java/net/server/channel/handlers/ChangeMapHandler.java:194`), and `src/test/java/server/cashshop/CashShopButtonTest.java:33` asserts `open(true)` is never called. So everything behind `isOpened()` is dead: `src/main/java/net/server/channel/handlers/CashOperationHandler.java:68` always refuses, and with it the gift writers at `:134`, `:335`, `:395`. The one caller of `CashShop.loadGifts()` in the base (`/root/workspace/cosmicms/src/main/java/net/server/channel/handlers/EnterCashShopHandler.java:83`) is gone here, so `gifts` rows are neither written nor read in production — the table is orphaned, and the window's own ring flow works around it on purpose (`src/main/java/server/cashshop/CashShopPartnerRings.java:36-38`). No card, no test notices. |
| NEW-FINDING | `CashShop.gift` inserts with `INSERT INTO \`gifts\` VALUES (DEFAULT, ?, ?, ?, ?, ?)` (`src/main/java/server/CashShop.java:443-455`), which depends on the physical column order of the live table; any column added out of order silently shifts `sn`/`ringid`. |
| NEW-FINDING | The window path deliberately skips `World.addCashItemBought` (`src/main/java/server/cashshop/CashShopWindowPurchase.java:28-40`), so the "suggested items" counters (`src/main/java/net/server/world/World.java:1409`) never see window sales; the comment says this is on purpose, but no switch exposes it. |
| NEW-FINDING | `CouponSchedule.fromWz` (`src/main/java/server/cashshop/CouponSchedule.java:30`) has no production caller — only the Python tool mirrors its rules, so a WZ-only schedule change cannot reach the server without an `nxcoupons` row. |

## State

**partial.** The catalogue loader, purchase/cart engine, services, rollback scope and partner rings are exercised by
52 JUnit tests including a shipped-file assertion that every row loads
(`src/test/java/server/cashshop/CashShopCatalogShippedTest.java:31-60`), and the loader/relaunch path is used by
`!reloadcashshop` (`src/main/java/client/command/commands/gm3/ReloadCashShopCommand.java:21-31`). What is not proven: the client-facing flow end to end (AutoQA
marks the ring purchase manual, `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:657-662`), the flag's presence on live (absent from
`docs/release/1.6.4.conf:271`; the example unit has it, `deploy/slumsms.service.example:11`), and gift delivery,
which is dead code today (NEW-FINDING above).

## Open questions

- Is `-Dcashshop.window.enabled=true` still in live's `/etc/systemd/system/slumsms.service`? `docs/release/1.6.4.conf`
  re-issues a 69-flag block (`:271`) that does not contain it, and the block is described as "self-healing", so a flag
  dropped from the unit would come back — but a flag never in the block (this one) tells nothing either way.
- Does TEST run the window (its `start-test.sh` was not readable)? `t-vb-5` expects a `5211048` purchase to work, which
  requires it, but the check is written against the stage path too.
- Which of the 10,697 rows a player can actually wear/use is not asserted anywhere: the catalogue tool's `NEVER` list
  decides, and only `CashShopCatalogFixesTest` samples it.
- Do the two non-zero `period` rows expire as intended? `EXPIRING_ITEMS` is a hard-coded `true`
  (`src/main/java/constants/inventory/ItemConstants.java:73`), so both cards get an expiration, but nothing here
  proves the coupon task stops honouring an expired card.
