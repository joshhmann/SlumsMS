# Inventory and item handlers

## Purpose

Everything that turns a client packet into a change of what a character carries: the six inventory tabs and their
equipped view, adding/removing/moving/merging stacks, dropping and picking items on a map (including who may take a
drop and when), consuming USE items, applying scrolls to equips, cash-item sub-types, AP/SP assignment, and the
keymap/macro state that sits next to them. It is the layer that decides what an item *is* on the server (slot, count,
flags, expiration, cash id) and what the client is told changed.

## Entry points

All packet handlers are registered in one place; the inventory-related ones occupy a contiguous block of
`PacketProcessor.registerHandler` calls at src/main/java/net/PacketProcessor.java:153-293.

| Handler | RecvOpcode | Registration | Reads |
|---|---|---|---|
| InventoryMergeHandler | ITEM_SORT | src/main/java/net/PacketProcessor.java:153 | invType byte |
| ItemMoveHandler | ITEM_MOVE | src/main/java/net/PacketProcessor.java:154 | int, type byte, src short, action short, qty short |
| UseCashItemHandler | USE_CASH_ITEM | src/main/java/net/PacketProcessor.java:164 | position short, itemId int (+ per-type payload) |
| UseItemHandler | USE_ITEM, USE_RETURN_SCROLL | src/main/java/net/PacketProcessor.java:164-166 | int, slot short, itemId int |
| ScrollHandler | USE_UPGRADE_SCROLL | src/main/java/net/PacketProcessor.java:167 | int, scrollSlot short, equipSlot short, ws short |
| UseSummonBagHandler | USE_SUMMON_BAG | src/main/java/net/PacketProcessor.java:167-168 | int, slot short, itemId int |
| ItemPickupHandler | ITEM_PICKUP | src/main/java/net/PacketProcessor.java:171 | int, byte, pos, oid int |
| CancelItemEffectHandler | CANCEL_ITEM_EFFECT | src/main/java/net/PacketProcessor.java:171-175 | itemId (negated) |
| DistributeAPHandler / DistributeSPHandler | DISTRIBUTE_AP / DISTRIBUTE_SP | src/main/java/net/PacketProcessor.java:171-180 | int, num int / skillid int |
| KeymapChangeHandler | CHANGE_KEYMAP | src/main/java/net/PacketProcessor.java:180 | mode int + per-mode payload |
| SetItemHandler | SET_ITEM_ACTION | src/main/java/net/PacketProcessor.java:180-192 | byte (1 catalog, 2 pieces) |
| WeaponTintHandler | WEAPON_TINT_ACTION | src/main/java/net/PacketProcessor.java:192-195 | action byte (+ per-action body) |
| UseItemEffectHandler | USE_ITEMEFFECT | src/main/java/net/PacketProcessor.java:200 | itemId int |
| SkillEffectHandler | SKILL_EFFECT | src/main/java/net/PacketProcessor.java:200-207 | skillId, level, flags, speed, aids |
| SkillBookHandler | USE_SKILL_BOOK | src/main/java/net/PacketProcessor.java:207-222 | int, slot short, itemId int |
| SkillMacroHandler | SKILL_MACRO | src/main/java/net/PacketProcessor.java:223 | byte num, then per-macro |
| UseMountFoodHandler | USE_MOUNT_FOOD | src/main/java/net/PacketProcessor.java:226 | int, pos short, itemid int |
| PetAutoPotHandler | PET_AUTO_POT | src/main/java/net/PacketProcessor.java:230 | byte, long, int, slot short, itemId int |
| UseHammerHandler | USE_HAMMER | src/main/java/net/PacketProcessor.java:251 | nothing |
| UseSolomonHandler | USE_SOLOMON_ITEM | src/main/java/net/PacketProcessor.java:259 | int, slot short, itemId int |
| UseGachaExpHandler | USE_GACHA_EXP | src/main/java/net/PacketProcessor.java:260 | nothing |
| ItemRewardHandler | USE_ITEM_REWARD | src/main/java/net/PacketProcessor.java:263 | int, slot short, itemId int |
| UseDeathItemHandler | USE_DEATHITEM | src/main/java/net/PacketProcessor.java:267 | itemId int |
| UseMapleLifeHandler | USE_MAPLELIFE | src/main/java/net/PacketProcessor.java:269 | name string |
| UseCatchItemHandler | USE_CATCH_ITEM | src/main/java/net/PacketProcessor.java:270 | int, short, itemId int, monsterid int |
| InventorySortHandler | ITEM_SORT2 | src/main/java/net/PacketProcessor.java:276 | int, invType byte |
| UseWaterOfLifeHandler | WATER_OF_LIFE | src/main/java/net/PacketProcessor.java:288 | nothing |
| QuickslotKeyMappedModifiedHandler | CHANGE_QUICKSLOT | src/main/java/net/PacketProcessor.java:293 | 8 ints |

Non-packet entry points that lead into the same code: `Character.pickupItem(MapObject)` from the pet loot path
(src/main/java/client/Character.java:2478-2500), `server.quest.actions.ItemAction.run` for quest rewards
(src/main/java/server/quest/actions/ItemAction.java:91,158), and the `@backpack` command which flips the pickup
implementation (src/main/java/client/command/CommandsExecutor.java:246).

## How it works

### 1. The model

1. `Inventory` (src/main/java/client/inventory/Inventory.java:42) holds a `LinkedHashMap<Short,Item> inventory`, an
   `InventoryType type`, a **fair** `ReentrantLock lock`, the owning `Character`, a `byte slotLimit` and a `checked`
   flag used by the save pass (src/main/java/client/inventory/Inventory.java:44-50).
2. `list()` returns a *copy* under the lock — `Collections.unmodifiableList(new ArrayList<>(inventory.values()))` —
   because the old live view threw `ConcurrentModificationException` during saves and pickups
   (src/main/java/client/inventory/Inventory.java:98-107); the comment at :311 records the same reasoning for
   removal. `iterator()` delegates to `list()` (src/main/java/client/inventory/Inventory.java:629).
3. Slots are `short`s. Positive = the tab; negative = equipped, mapped by `BodyPart`
   (src/main/java/client/inventory/BodyPart.java:29-63: -24 is `PET_AUTO_HP_POUCH`, -25 `PET_AUTO_MP_POUCH`, -49 the
   equipped medal used for megaphone prefixes at src/main/java/net/server/channel/handlers/UseCashItemHandler.java:105-110).
4. `InventoryType` has UNDEFINED(0), EQUIP(1), USE(2), SETUP(3), ETC(4), CASH(5), CANHOLD(6) and EQUIPPED(-1)
   (src/main/java/client/inventory/InventoryType.java:29-35); `getByWZName` maps Install→SETUP, Consume→USE, Pet→CASH
   (src/main/java/client/inventory/InventoryType.java:55).
5. `Item` (src/main/java/client/inventory/Item.java:33) carries id, cashId, sn, position, quantity, petid, pet, owner,
   itemLog, flag, expiration, giftFrom (:36-47). A cash id is minted lazily from a static `AtomicInteger runningCashId
   = 777000000` (:35, :97-103); the persistent allocator is `CashIdGenerator.generateCashId()`
   (src/main/java/client/inventory/manipulator/CashIdGenerator.java:76), which keeps the set of live cash ids in memory
   (:38-39) and frees them on removal (:90).
6. `Equip` extends `Item` with upgradeSlots, level/itemLevel, flag, the 16 stat shorts, itemExp, ringid and the Coloring
   Prism tint fields (src/main/java/client/inventory/Equip.java:71-82); `ScrollResult` is FAIL/SUCCESS/CURSE (:44-56)
   and `StatUpgrade` enumerates the stat indices used by scroll stat maps (:58-69).
7. `Item.setFlag` ORs in `ACCOUNT_SHARING` for account-restricted ids (src/main/java/client/inventory/Item.java:158-166);
   `setExpiration` refuses to expire permanent items and gives pets `Long.MAX_VALUE` (:171-173); `isUntradeable()`
   is `UNTRADEABLE` flag **or** drop-restricted without a karma flag (:195-197, the karma test being
   src/main/java/client/inventory/manipulator/KarmaManipulator.java).

### 2. Adding items

1. `InventoryManipulator.addById(...)` has fixed overloads down to
   `addById(Character, int itemId, short quantity, String owner, int petid, short flag, long expiration)`
   (src/main/java/client/inventory/manipulator/InventoryManipulator.java:51-76) and locks the target inventory before
   delegating to `addByIdInternal` (:92). The `Client` overloads reach here with a null player for a bot's client, so
   the null check is load-bearing.
2. `addByIdInternal` (:92-200) computes `slotMax` via `ItemInformationProvider.getSlotMax`
   (src/main/java/server/ItemInformationProvider.java:367), stacks into existing slots with the same owner and flag
   sending one `ModifyInventory(1, …)` per touched slot, sends `ModifyInventory(0, …)` for a new slot, and answers
   `getInventoryFull()` + `getShowInventoryFull()` when no slot is left. Rechargeables and pets never stack. An EQUIP
   with quantity != 1 is a hard `RuntimeException("Trying to create equip with non-one quantity")`.
3. `addFromDrop` (:201-237) / `add` (:335-371) are the item-object variants used by pickups; `addFromDropInternal`
   (:238) refuses a pickup-restricted id the character already owns and refuses an EQUIP dropped with quantity > 1.
4. `checkSpace(...)` (:466-522) mirrors the same rules without mutating anything: rechargeable = one slot, otherwise
   `ceil(quantity/slotMax)` slots after filling compatible stacks; EQUIP just needs a free slot.
   `checkSpaceProgressively(...)` (:523-582) returns `bit0 = has space` and `usedSlots << 1` so a multi-item reward
   can be checked item by item.
5. Quest rewards go through the same door: `ItemAction.run` calls `checkSpaceProgressively` for the give-list
   (src/main/java/server/quest/actions/ItemAction.java:225) and `InventoryManipulator.addById(..., period > 0 ?
   now + MINUTES.toMillis(period) : -1)` (:158) with a `getShowItemGain` packet per item.

### 3. Moving, equipping, dropping (ItemMoveHandler)

1. `ItemMoveHandler.handlePacket` (src/main/java/net/server/channel/handlers/ItemMoveHandler.java:36) skips an int,
   stamps autoban slot 6 with a 300 ms window, then reads `type`, `src`, `action`, `quantity`.
2. Dispatch: `src < 0 && action > 0` → `unequip`; `action < 0` → `equip`; `action == 0` → `drop`; else `move`.
3. `InventoryManipulator.move` (src/main/java/client/inventory/manipulator/InventoryManipulator.java:674) rejects
   negative slots, `dst > slotLimit` and a null source, then calls `Inventory.move(src, dst, slotMax)`
   (src/main/java/client/inventory/Inventory.java:240). That method relocates into an empty target, swaps when the two
   stacks are the same id/rechargeability/owner or when they would overflow `slotMax`, and otherwise merges quantities.
   Packets: one merge sends two `ModifyInventory(1, …)`; a plain move sends `(3, source)` + `(1, target)`.
4. `equip` (:711-838) gates on `ii.canWearEquipment`, refuses explorer mount for Cygnus and vice versa, stamps
   `UNTRADEABLE` for `isUntradeableOnEquip` ids, pre-unequips the conflicting slot (-6 overall vs -5 top, -10/-11
   two-handed, -18 mount) with an inventory-full abort, runs `Equip.equip()` for rings, cancels `BOOSTER` when a
   weapon goes on, and ends with `chr.equipChanged()`. It also emits the `!qa cs-2` log line (:721-722).
5. `drop` (:903-1014) forces `type = EQUIPPED` for negative slots, refuses GMs via `client.GmGiving.blocksDrop`
   (ticket 43) and via `gmLevel() < MINIMUM_GM_LEVEL_TO_DROP`, refuses while trading or in a minigame, refuses the
   hard-coded 1142146 ("Locks monsterbook stat item in inventory"), unequips a pet being dropped, and for a partial
   stack copies the item and decrements the source. Afterwards it clears the item effect / chalkboard / Ariant score
   when the last copy left. Each stack goes to `map.disappearingItemDrop(...)` or `map.spawnItemDrop(...)`
   (:963-966, :994-997) depending on `isDisappearingItemDrop`.

### 4. Who may take a drop

1. `MapItem` keeps `character_ownerid`, `party_ownerid`, `meso`, `questid` (src/main/java/server/maps/MapItem.java:41)
   plus the drop type, `dropTime`, `playerDrop`, `partyDrop`, `conjured` and an `itemLock`; every constructor copies the
   owner's id (:54, :67, :81).
2. `isFFADrop()` is `type == 2 || type == 3 || hasExpiredOwnershipTime()` (src/main/java/server/maps/MapItem.java:133-138);
   the ownership window is **15 seconds** (`SECONDS.toMillis(15)`, :138).
3. `canBePickedBy(chr, byPet)` (src/main/java/server/maps/MapItem.java:144-181) first refuses when the map's quest id
   needs an item the character does not hold, then refuses a non-meso drop the character has no space for (answering
   `getInventoryFull()`, suppressed for pets), then allows the owner and FFAs, promotes a party drop for the picker's
   own party, and finally falls back to the expired window. `hasClientsideOwnership` (:129) is the same rule minus the
   space check, used to tell the client which drops to dim.
4. The pickup itself is serialised per drop: `mapitem.lockItem()` … `finally unlockItem()`
   (src/main/java/client/Character.java:2526-2644), with `isAlreadyPickedUp()` re-checked inside the lock and
   `MapleMap.pickItemDrop(pickupPacket, mdrop)` removing it from the map
   (src/main/java/server/maps/MapleMap.java:1192).
5. The client-side anti-double-click gate is 400 ms from `dropTime`:
   `System.currentTimeMillis() - mapitem.getDropTime() < 400` → `enableActions()` and return
   (src/main/java/client/Character.java:2515).

### 5. The pickup path

1. `ItemPickupHandler` (src/main/java/net/server/channel/handlers/ItemPickupHandler.java:42) reads oid, resolves it with
   `map.getMapObject(oid)`, drops it silently when null, and refuses with a `log.warn("Chr {} tried to pick up an item
   too far away. …")` beyond 800 px horizontally / 600 px vertically. Then `chr.pickupItem(ob)`.
2. `Character.pickupItem(ob, petIndex)` (src/main/java/client/Character.java:2478-2500) emits the `pickup`
   `PlayerTrail` event (ticket 131), clears the thread-local "picked" marker for a real player pickup
   (`MapleMap.clearPickedOnThisThread()`, :2485), and dispatches on `isUsingModifiedPickupItem()`
   (:351, set only by `@backpack on` at src/main/java/client/command/commands/gm0/BackPackToggleCommand.java:42):
   `modifiedPickupItem` or `originalPickupItem`. The bot-affinity hook (`AffinityHooks.onPickup`) fires **only** for a
   pickup this thread really made of a bot's fresh drop (:2496-2499, ticket 58).
3. `originalPickupItem` (src/main/java/client/Character.java:2509-2653) then: 400 ms + `canBePickedBy` gate; meso
   split over `getPartyMembersOnSameMap()` (:2545-2549); one space check for the whole item (:2559); self-loot maps
   (`MapId.isSelfLootableOnly`, Happyville trees and guild PQ) admit only the dropper (:2562); NX cards pay into the
   cash shop with the optional hint (:2564-2567); `needQuestItem(mapitem.getQuest(), itemId)` refuses quest items
   (:2589); consume-on-pickup items apply and vanish (:2617); `243xxxx` scripted items run their script after the lock
   is released (:2619-2626); everything else goes through `InventoryManipulator.addFromDrop`; the Ariant spirit jewel
   updates the score (:2630) and `server.GodlyDrops.tellLooter` sees the drop (:2632).
4. The `@backpack` variant, `modifiedPickupItem` (src/main/java/client/Character.java:2656-2827), is a **separate copy**
   of that body: it keeps the 400 ms gate, the meso branch, NX cards, consume-on-pickup, `2430030` pet refusal and the
   scripted-item branch, but routes USE/ETC/SETUP items into `infinityBag.storePickup(mItem, AdventurerBag.wzRules())`
   (:2781) and only falls back to the inventory when the bag refuses, and it has **no** `isSelfLootableOnly`,
   `needQuestItem`, `GodlyDrops.tellLooter` or Ariant-score call (see Known issues).
5. `AdventurerBag` (src/main/java/client/inventory/AdventurerBag.java:36) is a per-character count store backed by the
   `infinitystorage` table (`SELECT itemid FROM infinitystorage …` :417, `UPDATE`/`INSERT` :425-426), capped at
   `MAX_PER_ITEM = 100_000_000` (:39). It refuses non-bag types, flagged/expiring/owner-tagged stacks, quest and cash
   items and "one-of-a-kind" ids (:355-363, rules at :71-135).

### 6. Using a USE item

1. `UseItemHandler.handlePacket` (src/main/java/net/server/channel/handlers/UseItemHandler.java:44) refuses a dead
   character, matches slot+itemId, records a `use` trail (:56), then handles the hard-coded cures (ALL_CURE, EYEDROP,
   TONIC, HOLY_WATER, :58-72), town scrolls (:73, `USE_BANISHABLE_TOWN_SCROLL` decides whether the banish point is
   recorded) and the anti-banish scroll (:84); everything else is removed first and applied second (:100-105).
   `ItemId.HAPPY_BIRTHDAY` is the only map-wide item (:102-105); the comment at :108-110 records that MapleRoot's
   Lion King's Castle map-wide "expedition potions" were deleted in 1.5.2.
2. `ItemRewardHandler` (src/main/java/net/server/channel/handlers/ItemRewardHandler.java:48) is the WZ reward-box path:
   `ii.getItemReward(itemId)` gives `(totalWeight, rewards)` (:58), each reward is space-checked (:60), rolled against
   `Randomizer.nextInt(total)` (:63), granted as an equip (`getEquipById` + `rewardExpiration` + `setExpiration`) or as
   a plain `addById` (:71-73), and the box is consumed with `removeById(USE, itemId, 1, false, false)` (:75). A reward
   with a `worldmsg` is also broadcast as a server notice (:77).
   `rewardExpiration(now, period)` (:99) treats the WZ `period` unit as **hours**; the only reachable equip reward is
   Consume 02022503-02022524/02022550 → 1132010 with period 7200 (300 days), and 05530000's period is never read
   because it takes the `addById` branch (:88-97).
3. Item-external USE handlers: `UseSummonBagHandler` spawns `getSummonMobs` entries and consumes the bag **before**
   spawning (src/main/java/net/server/channel/handlers/UseSummonBagHandler.java:53-60); `UseCatchItemHandler` is a switch
   over the catchable ids (PHEROMONE_PERFUME, MUTANT_PIG_ROPE, POUCH, ARPQ_ELEMENT_ROCK, MAGIC_CANE, TRANSPARENT_MARBLE_1..3,
   EPQ_PURIFICATION_MARBLE which delegates to `MonsterCatch.usePurifierMarble`, FISH_NET, and a generic `default` using
   `getCreateItem`/`getMobHP` with per-item `AutobanManager` spam windows)
   (src/main/java/net/server/channel/handlers/UseCatchItemHandler.java:45-231); `UseMountFoodHandler` heals up to 30
   tiredness, converts the healed fraction into mount EXP and levels the mount up to 31
   (src/main/java/net/server/channel/handlers/UseMountFoodHandler.java:42-89); `UseSolomonHandler` converts an item into
   gacha exp under a `getMaxLevelById` gate (src/main/java/net/server/channel/handlers/UseSolomonHandler.java:43-79);
   `UseGachaExpHandler` autobans for redeeming exp it does not have (:39-54); `SkillBookHandler` reads the book's
   `skillid`/`masterLevel`, bumps the skill, and broadcasts `skillBookResult` so the whole map sees it
   (src/main/java/net/server/channel/handlers/SkillBookHandler.java:41-105).

### 7. Scroll application

1. `ScrollHandler.handlePacket` (src/main/java/net/server/channel/handlers/ScrollHandler.java:52) takes the client lock,
   parses `scrollSlot`, `equipSlot` and the white-scroll bit `(ws & 2) == 2` (:57-62), resolves the target from the
   EQUIPPED inventory, and when the character has Legendary Spirit (skill 1003) and `equipSlot >= 0` resolves it from
   the EQUIP tab instead (:64-68). The comment at :66-69 records that the MapleRoot repack shipped this handler with an
   empty body and it was restored from Cosmic.
2. Refusals all answer `announceCannotScroll` (a scroll-effect FAIL, or `getInventoryFull` for the legendary-spirit UI):
   missing target or a scroll slot that is not a `204xxxx` stack with quantity ≥ 1 (:75, :197-202), a clean slate on an
   equip that cannot take one (:85), a non-modifier scroll on an equip with 0 slots (:89), a scroll req list that does
   not name the equip (:93-97), and `canScroll(scrollId, itemId)` for everything except chaos/clean-slate (:99-101).
3. Consumption happens under the USE inventory lock: the white scroll first, then the scroll
   (:107-127). Then `ii.scrollEquipWithId(toScroll, scroll.getItemId(), whiteScroll, 0, chr.isGM())` (:134) — the fourth
   argument is the Vega item id and is `0` here, i.e. no probability boost.
4. Result mapping: `null` → CURSE (:137); SUCCESS when the level rose, or a clean slate added a slot, or a
   spikes/cold flag went on (:138); otherwise FAIL. CURSE removes the equip entirely unless it is a wedding ring, which
   is downgraded to FAIL with the mods re-sent (:143-167). Mods are `(3, scrolled)` + `(0, scrolled)` and are followed by
   a map-wide `getScrollEffect` broadcast (:169-170).
5. Side effects: the `!qa gn-2` log line (:179-183), the "beef boom" bot-drama hook on CURSE (:187), and
   `chr.equipChanged()` when a worn equip changed (:190).
6. The engine is `ItemInformationProvider.scrollEquipWithId(equip, scrollId, usingWhiteScroll, vegaItemId, isGM)`
   (src/main/java/server/ItemInformationProvider.java:1047): it needs an upgrade slot (or a clean slate, or the
   `USE_PERFECT_GM_SCROLL` GM override) (:1052), reads `success` from the scroll's stats (:1054), applies the Vega boost
   (10→30, 60→90, Chaos 60 → 100) (:1056-1071), and on success either sets a spikes/cold flag, adds a slot for a clean
   slate, rolls chaos stats (`scrollEquipWithChaos`, :688) or applies `improveEquipStats`. A non-clean-slate scroll then
   spends a slot and raises the equip level (:1098-1104). On failure it spends a slot unless
   `USE_PERFECT_SCROLLING`, a white scroll, a clean slate, the GM override or a modifier scroll applies (:1106), and
   returns `null` (boom) when `Randomizer.nextInt(100) < cursed` (:1109).
7. `canScroll(scrollid, itemid)` (src/main/java/net/server/channel/handlers/ScrollHandler.java:213-224) compares the
   `%100` scroll class against the equip's item class, with an explicit 20492 accessory-scroll case; it is public
   because the bots scroll with the same rule (ticket 11).

### 8. Cash items (UseCashItemHandler)

1. `handlePacket` (src/main/java/net/server/channel/handlers/UseCashItemHandler.java:75) throttles to one cash item per
   3 s (`getLastUsedCashItem`, :77-85), resolves `position`/`itemId` against the CASH tab (falling back to
   `findById`), and builds the `"<medal> "` prefix from equipped slot -49 (:105-110).
2. It then dispatches on `itemId / 10000` (:115-655): 504 teleport rocks (validated against the saved trock list, VIP-only
   cross-continent, `FieldLimit.CANNOTVIPROCK`), 505 AP/SP reset (:148 — SP reset moves one point between two skills,
   clears macros that named the emptied skill, and consumes only on success; AP reset delegates to
   `AssignAPProcessor.APResetAction`), 506 tag/sealing-lock/incubator (:219), 507 megaphones (:285), 508 kites, 509
   notes (through `noteService`), 510 jukebox, 512 map effect, 517 pet name, 520 mesos, 523 Owl of Minerva, 524 pet
   food, 530 item effect, 533 Duey, 537 chalkboard (deliberately not consumed), 539 avatar megaphone (10 s auto-bye),
   540 name/world-transfer cancel, 543 Maple Life (12-slot gate), 545 MiuMiu's shop 1338, 550 extend-expiration (a
   no-op), 552 karma, 557 Vicious Hammer, 561 Vega's Spell (:588).
3. 507 is subdivided by `switch ((itemId / 1000) % 10)` (:285-378): 1 megaphone (level ≥ 10), 2 super megaphone, 5
   Maple TV, 6 item megaphone, 7 triple megaphone. **The `default` arm answers `enableActions()` and returns before
   `remove(...)`** (:375-378), so a 507-class item with no broadcast (5070000, 5073000, 5074000 — all `(itemId/1000)%10
   == 0`) stays in the inventory. That is the b297 fix, commit `3837df51f`.
4. Vega's Spell (:588-651) reads the equip slot, then the fed scroll, refuses when the equip has no slot left, and
   requires `vegaAccepts(spellId, useItemId, success)` — `useItemId` must be a `204xxxx` scroll whose WZ `success` is
   exactly 10 (for 5610000) or 60 (for 5610001) (:712-720). It blocks the cash shop, sends `sendVegaScroll(0x40)`,
   applies **the fed scroll** through the 5-arg `scrollEquipWithId(..., itemId, isGM)` (:627), treats a boom as a
   failure that keeps the item (:628), answers 0x41/0x43, consumes both the scroll and the spell, and finishes in a
   3 s timer that re-sends the item, broadcasts the scroll effect and re-enables actions (:634-651). Commit
   `4e5a5e4…`/`4e5a3851f` + `b0e5e4c58`.
5. Anything else hits the final `else`: `log.warn("NEW CASH ITEM TYPE: {}, packet: {}", …)` and nothing is consumed
   (:657-660). `remove(c, position, itemid)` re-resolves the slot by item id if the recorded position drifted and calls
   `InventoryManipulator.removeFromSlot(CASH, position, 1, true, false)` (:748-764).

### 9. Stat and skill-point assignment

1. `DistributeAPHandler` (:32) and `DistributeSPHandler` (:31) are thin: they read the packed target / skill id and hand
   off to the processors.
2. `AssignAPProcessor.APAssignAction(Client, int num)` (src/main/java/client/processor/stat/AssignAPProcessor.java:600)
   locks the client and switches on the packed AP target (64 STR, 128 DEX, 256 INT, 512 LUK, 2048 HP, 8192 MP), calling
   `assignStr/assignDex/assignInt/assignLuk/assignHP/assignMP`; each refusal messages "Couldn't execute AP assign
   operation." and re-enables actions.
3. `AssignSPProcessor.SPAssignAction(Client, int skillid)` (src/main/java/client/processor/stat/AssignSPProcessor.java:61)
   derives the skill book from `skillid/10000`, refuses with `canSPAssign`, recomputes beginner-skill points without
   consuming SP, and clamps the new level to `getMasterLevel` for 4th-job skills before `changeSkillLevel`.

### 10. Keymap, quickslot, macro, effects, tints

1. `KeymapChangeHandler.handlePacket` (:35) handles mode 0 (key rebinds; a banned/GM/off-job skill bind is silently
   skipped with `continue;   // fk that`), mode 1 (auto-HP: item must exist in the USE tab or the client is
   disconnected) and mode 2 (auto-MP, same rule).
2. `QuickslotKeyMappedModifiedHandler` (:13) accepts exactly `QuickslotBinding.QUICKSLOT_SIZE * Integer.BYTES` and
   forwards to `chr.changeQuickslotKeybinding`.
3. `SkillMacroHandler` (:34) caps at 5 macros, disconnects with a `PACKET_EDIT` autoban alert when a name exceeds 12
   chars, and stores `SkillMacro(skill1..3, name, shout, position)`.
4. `UseItemEffectHandler` (:34) validates the item for BUMMER/GOLDEN_CHICKEN effects, then `setItemEffect(itemId)` and a
   map broadcast — the item is **not** consumed. `CancelItemEffectHandler` (:32) negates the id, refuses
   `noCancelMouse` effects and calls `cancelEffect`. `UseDeathItemHandler` (:31) only sends the effect.
5. `UseHammerHandler` (:33) answers `sendHammerMessage()` and nothing else; the real hammer path is the 557 cash branch.
6. `WeaponTintHandler` (:84) is the Coloring Prism window: it refuses non-players (`ColorPrism.isPlayer`) and the
   feature gate (`ColorPrism.enabled()`, src/main/java/server/colorprism/ColorPrism.java:37-39), then handles
   snapshot/apply/restore/apply-look/restore-look under `synchronized (player)` so two Confirms cannot spend one prism.
7. `SetItemHandler` (:20) serves the set-item catalog (1) and piece list (2) from
   `server.setitem.SetItems.sendCatalog/sendPieces`.

### 11. Sorting and merging

1. `InventoryMergeHandler` (:40) bails when `USE_ITEM_SORT` is off, disconnects on an invalid invType, then runs
   "RonanLana's SLOT MERGER": for every `dst`, merge every later slot with the same item id into it until `slotMax`, then
   compact the tab by moving the first occupied slot above each hole into it (:60-110). Answers `finishedSort`.
2. `InventorySortHandler` (:289) copies every item, removes every slot (emitting `ModifyInventory(3, …)`), sorts with
   `PairedQuicksort` (:42, ctor :258) on itemId/quantity/name/level with a secondary sort inside each item-id run, and
   re-adds them (`ModifyInventory(0, item.copy())` "to prevent crashes"), then `finishedSort2`. Projectile subtypes 206,
   207 and 233 are reversed so the stronger stack sorts first (:253-275).

## Data

| Store | Where | Contents |
|---|---|---|
| `Inventory.inventory` | src/main/java/client/inventory/Inventory.java:38 | `LinkedHashMap<Short,Item>`, keyed by slot; per-tab `Character.inventory[]` |
| `inventoryitems` | src/main/java/client/inventory/ItemFactory.java:214,234,367,401 | one row per item: position, quantity, itemid, owner, petid, flag, expiration, cashid |
| `inventoryequipment` | src/main/java/client/inventory/ItemFactory.java:132,161 | equip extension row (stats, slots, level, ringid, tints via `ColorPrismStore.equipTintJoin`) |
| `infinitystorage` | src/main/java/client/inventory/AdventurerBag.java:417-426 | `(charid, itemid, amount, type, position)` counts for the Adventurer's Bag |
| `keymap`, `skillmacros`, `queststatus` | src/main/java/client/Character.java:3159 | per-character UI and quest state deleted/loaded with the character |
| `CashIdGenerator.existentCashids` | src/main/java/client/inventory/manipulator/CashIdGenerator.java:38-39 | in-memory set of live cash ids, loaded from the DB at boot (:54) |

Item identity rules worth knowing: cash ids start at 777000000 (src/main/java/client/inventory/Item.java:35), pets and
rings share the cash-id space (comment at :35), and `ItemFactory` deletes/inserts the two inventory tables in one
statement pair per tab (src/main/java/client/inventory/ItemFactory.java:204,348).

## Switches

All are `config.yaml` keys, i.e. fields of src/main/java/config/ServerConfig.java. That file declares most of them
without an initialiser, so the Java default is `false`/`0` when the key is absent; the two with explicit defaults are
noted. `config.yaml` itself is not in this tree (only `config.example.yaml` and a release-fixture copy), and
docs/release/1.6.4.conf is a `SERVER_SET` of `-D` flags that does not mention any of these keys — so **live and TEST
values are unknown for every row below**; only the code default is evidence.

| Key | ServerConfig | Default | Effect |
|---|---|---|---|
| `USE_ITEM_SORT` | :319 | false | gates both InventorySortHandler and InventoryMergeHandler; off = buttons do nothing |
| `USE_ITEM_SORT_BY_NAME` | :320 | false | primary sort becomes name instead of item id |
| `USE_ENFORCE_ITEM_SUGGESTION` | :336 | false | Owl of Minerva stops adding the search to the world suggestion list |
| `USE_ENFORCE_UNMERCHABLE_CASH` | :337 | false | cash items refuse to become map drops |
| `USE_ENFORCE_UNMERCHABLE_PET` | :338 | false | same for pets |
| `USE_ERASE_UNTRADEABLE_DROP` | :345 | false | untradeable items disappear instead of dropping |
| `USE_BANISHABLE_TOWN_SCROLL` | :350 | false | a town scroll also records the banish return point |
| `USE_ANNOUNCE_NX_COUPON_LOOT` | :367 | false | hint text on an NX-card pickup |
| `USE_PERFECT_GM_SCROLL` | :442 | false | GM scrolls always succeed and never spend a slot |
| `USE_PERFECT_SCROLLING` | :443 | false | a failed scroll never spends an upgrade slot |
| `SCROLL_CHANCE_ROLLS` | :447 | 0 | passed to `rollSuccessChance` (src/main/java/server/ItemInformationProvider.java:641) |
| `CHSCROLL_STAT_RATE` / `CHSCROLL_STAT_RANGE` | :449 | 0 | chaos-scroll stat loop and its range (src/main/java/server/ItemInformationProvider.java:689,725) |
| `USE_EQUIPMNT_LVLUP` / `_SLOTS` / `_POWER` / `_CASH` | :512-518 | false/0 | equip levelling on kills; `Equip.gainLevel` was cut down to WZ-only stats (classifications entry below) |
| `USE_COMPULSORY_AUTOPOT` | :535 | false | pet autopot drinks enough pots to reach the ratio instead of one |
| `PET_AUTOHP_RATIO` / `PET_AUTOMP_RATIO` | :537,538 | 0.0 | target ratios for the compulsory mode |
| `COLOR_PRISM_ENABLED` | :57 | **false** | with `ColorPrismStore.schemaPresent()` gates WeaponTintHandler (commit `11898a01c`, "off by default") |
| `MINIMUM_GM_LEVEL_TO_DROP` | :586 | **4** | GM drop refusal threshold |

`-D` properties: none of the files in this subsystem call `System.getProperty`, so there is no property switch here.

## Invariants

1. Every mutation of a tab happens under that tab's `ReentrantLock` and every read handed to a caller is a snapshot —
   `Inventory.list()` (:98) and `lockInventory()` (:667) exist for exactly that reason; `Inventory.dispose()` nulls the
   owner so a post-save mutation cannot reach a character (:693).
2. Slot keys are the identity of a stack: `Inventory.move` never leaves two stacks at one slot, and a merge only
   happens for the same item id, the same owner and a non-rechargeable id (src/main/java/client/inventory/Inventory.java:240-279).
3. A rechargeable id is always exactly one slot of `slotMax`; `addById` skips stacking for it and `checkSpace` counts it
   as one slot (src/main/java/client/inventory/manipulator/InventoryManipulator.java:92-200, :466).
4. A drop is taken by exactly one picker: `MapItem.itemLock` + `isAlreadyPickedUp()` under the lock, and
   `MapleMap.pickItemDrop` is the only removal (src/main/java/client/Character.java:2526-2528,
   src/main/java/server/maps/MapleMap.java:1192).
5. Ownership has one window: 15 s, then the drop is FFA (src/main/java/server/maps/MapItem.java:138).
6. No packet path may consume an item it did not also validate: every branch either `return`s before `remove(...)` or
   removes only after its own checks (the b297 fix at src/main/java/net/server/channel/handlers/UseCashItemHandler.java:375-378
   formalised this for cash items).
7. `scrollEquipWithId` consumes nothing; the caller spends the scroll and the slot. It returns `null` **only** for a
   boom, and callers must treat that as "item destroyed" (ScrollHandler) or "item kept" (Vega) deliberately
   (src/main/java/server/ItemInformationProvider.java:1047-1115).
8. `Item.getCashId()` is minted once and must be freed on removal (`CashIdGenerator.freeCashId`) so pet/ring ids stay
   unique across a session.

## Origin

Baseline is commit `03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline`; upstream comparison tree is
`/root/workspace/cosmicms`.

| Path | Origin | Evidence |
|---|---|---|
| `client/inventory/Inventory.java` | modified | oldest `03d13cb21`; 90 diff lines vs cosmicms (the `list()` copy, the ownership hashes) |
| `client/inventory/Item.java` | base | oldest `03d13cb21`; byte-identical to cosmicms |
| `client/inventory/Equip.java` | modified | 168 diff lines; `tools/cosmicdiff/classifications.json`: `"java-shrunk:src/main/java/client/inventory/Equip.java::Equip.gainLevel/1": {"class": "DELIBERATE", "why": "MapleRoot dropped HeavenMS's random stat/slot gains on equip level-up, keeping only WZ-defined (elemental) level stats; …"}` |
| `client/inventory/InventoryType.java` | modified | 1 diff line vs cosmicms (an enum comment) |
| `client/inventory/ModifyInventory.java`, `manipulator/KarmaManipulator.java`, `manipulator/CashIdGenerator.java` | base | byte-identical to cosmicms |
| `client/inventory/manipulator/InventoryManipulator.java` | modified | oldest `03d13cb21`; 351 diff lines vs cosmicms |
| `client/inventory/AdventurerBag.java` | custom | not in cosmicms; oldest `5cd04c926 2026-10-28 Stop the Adventurer's Bag washing flags off items and losing stock` |
| `server/Shop.java` | modified | 130 diff lines; b289 fix `e8ca08368 1.6.4 b289: a shop charges for a star stack the price it pays for it` |
| `server/ItemInformationProvider.java` | modified | 1100 diff lines vs cosmicms (the scroll engine, slotMax, the predicate helpers) |
| `server/maps/MapItem.java` | modified | oldest `03d13cb21`; 75 diff lines vs cosmicms |
| `net/server/channel/handlers/ScrollHandler.java` | modified | oldest `03d13cb21`; 64 diff lines; `9a3c9d1e8 2026-10-21 Restore scrolling: the MapleRoot repack shipped ScrollHandler with an empty body` |
| `UseCashItemHandler.java` | modified | 250 diff lines; fixes `b40e5c651`, `fa79de256`, `3837df51f` (b297), `4e5a3851f`, `b0e5e4c58` (b290) |
| `ItemRewardHandler.java` / `UseItemHandler.java` / `UseCatchItemHandler.java` / `UseSummonBagHandler.java` / `SkillBookHandler.java` / `SkillEffectHandler.java` / `PetAutoPotHandler.java` / `InventorySortHandler.java` / `InventoryMergeHandler.java` | modified | diff counts 41 / 21 / 56 / 14 / 13 / 22 / 25 / 6 / 2 vs cosmicms |
| `ItemMoveHandler.java`, `ItemPickupHandler.java`, `UseHammerHandler.java`, `UseItemEffectHandler.java`, `CancelItemEffectHandler.java`, `UseDeathItemHandler.java`, `UseGachaExpHandler.java`, `UseMapleLifeHandler.java`, `UseMountFoodHandler.java`, `UseSolomonHandler.java`, `UseWaterOfLifeHandler.java`, `SkillMacroHandler.java`, `DistributeAPHandler.java`, `DistributeSPHandler.java`, `KeymapChangeHandler.java`, `QuickslotKeyMappedModifiedHandler.java` | base | byte-identical to cosmicms |
| `WeaponTintHandler.java` | custom | not in cosmicms; `11898a01c 2026-10-20 Add the Coloring Prism server side (off by default)` |
| `SetItemHandler.java` | custom | not in cosmicms; `438747f19 2026-10-20 Add SetEquip set bonuses with a written set table (ticket 18)` |

`Character.pickupItem`/`originalPickupItem`/`modifiedPickupItem` live in a modified file (`client/Character.java`, not
itemised here — it is shared with a dozen subsystems); the two pickup bodies are both present on the baseline-relative
tree, the `modified` one being the `@backpack` fork.

## Tests and QA

| Test | Covers |
|---|---|
| src/test/java/net/server/channel/handlers/VegaAppliesFedScrollTest.java:58 | b290: the fed scroll's stats land on a level-15 equip (spies `getEquipStats(2043101)` → success 60, STR 2, PAD 3) |
| src/test/java/net/server/channel/handlers/VegaSpellScrollTest.java | the 561 sub-type's packet shape |
| src/test/java/net/server/channel/handlers/UseCashItemUnhandledMegaphoneTest.java:40 | b297: 5070000/5073000/5074000 stay in the inventory |
| src/test/java/net/server/channel/handlers/UseCashItemTrockTest.java, UseCashItemNoteTest.java | teleport-rock destination validation, note failure messages |
| src/test/java/net/server/channel/handlers/ItemRewardHandlerTest.java:18,24,30,36,50,56,63 | reward period unit (absent → -1, 0 → now, hours, 7200 h = 300 days) and the `/name` `/item` substitution including regex metacharacters |
| src/test/java/net/server/channel/handlers/ScrollHandlerTest.java | only `ItemConstants.isChaosScroll` id classification — **no handler-level scroll test** |
| src/test/java/net/server/channel/handlers/UseItemPotionTest.java, UseSummonBagHandlerTest.java, ScriptedItemHandlerTest.java, MapleLifeSlotTest.java, WeaponTintHandlerTest.java, PetAutoPotQaTest.java | potion effects, summon bag spawn/consume order, scripted items, the 12-slot Maple Life gate, prism action gating, pet autopot |
| src/test/java/server/ShopRechargeableStockPriceTest.java:40, ShopRechargePriceParityTest.java, CasinoChipSellTest.java, ShopMathTest.java | b289: every rechargeable on sale has a stack price the counter can charge, and quick-sell goes through `Shop.sellPayout` |
| src/test/java/client/inventory/InventoryListSnapshotTest.java:29,48 | a write during a `list()`/iterator walk does not throw |
| src/test/java/client/inventory/manipulator/CheckSpaceWithoutPlayerTest.java:21 | `checkSpace` with a client that has no player answers false instead of throwing |
| src/test/java/client/inventory/AdventurerBagTest.java, BotInventoryAddTest.java | bag flag preservation, adding to a bot's empty inventory |

QA hooks in production code: `QaLog.Topic.EQUIP` tagged `!qa cs-2`
(src/main/java/client/inventory/manipulator/InventoryManipulator.java:721), `Topic.SCROLL` tagged `!qa gn-2`
(src/main/java/net/server/channel/handlers/ScrollHandler.java:179), `Topic.PETPOT` tagged `!qa pp-1`
(src/main/java/net/server/channel/handlers/PetAutoPotHandler.java:43, scenario
src/main/java/soloMapling/qa/PetPotScenarios.java:35). `AutoQaCatalog` drives scroll flows only through the bots —
`t-gb-5` "EV scrolling: a careful bot declines an EV-negative scroll, a gambler gambles"
(src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1457) and `t-sc-1`/"A gambler bot scrolls its sword" (:667).

**Not covered anywhere:** no test applies a scroll through `ScrollHandler.handlePacket`; no test crosses
`ItemPickupHandler` → `Character.pickupItem` (distance gate, 400 ms gate, ownership, party split); nothing tests
`InventoryManipulator.drop`/`equip`/`unequip`; nothing tests `InventoryMergeHandler` or `InventorySortHandler` beyond
compilation; nothing tests the cash-item dispatch for 504/505/506/508/509/520/523/539/543/545/557; nothing tests the
`@backpack` (`modifiedPickupItem`) variant at all; the quest `ItemAction` grant path has no test.

## Known issues

| Id | Line | Issue |
|---|---|---|
| b289 | /root/work/STATUS-1.6.4.md:39 | S1: a 9,999 throwing-star stack bought for 500 mesos sold back for 10,249. Old bug. Fixed on this branch by `e8ca08368` — `Shop.buy` now charges `ii.getPrice(itemId, slotMax)` for a rechargeable and `Shop.sellPayout` pays the same expression (src/main/java/server/Shop.java:109-117, :265). STATUS still lists it open with the owner's "just add it to 1.6.4". |
| b290 | /root/work/STATUS-1.6.4.md:40 | S2: Vega's Spell discarded the fed scroll and rolled the item's own level, a pure loss at level 15+. Ticket 182 (docs/tickets/182-server-code-audit-1-6-4.md:51) and brief `s-b290/BRIEF.md`. Fixed here (`4e5a3851f`, `b0e5e4c58`) and tested; STATUS still records it as a live paid-item loss with no closer assigned. |
| b297 | /root/work/STATUS-1.6.4.md:46 | S2: megaphones 5070000/5073000/5074000 were consumed with no broadcast. Fixed by the `default` arm at src/main/java/net/server/channel/handlers/UseCashItemHandler.java:375-378 (`3837df51f`) and tested. Still open in STATUS ("paid-item loss"). |
| b287 | /root/work/STATUS-1.6.4.md:83 | S4, adjacent: the t-xc-5 scroll trader bot holding a Chaos/White Scroll never lists it — a supply-side scroll bug, not this subsystem's handler. |
| b291 | /root/work/STATUS-1.6.4.md:41 | S3: items 4036550-4036553 are never minted, so three side systems are dead. |
| L10 | /root/work/STATUS-1.6.4.md:101 | Scrolls, USE and ETC items become tradable exchange tickers (ticket 270, branch `164-excatalog`) — an economy change on top of this inventory model. |
| b280 | src/main/java/soloMapling/qa/QaLog.java:73 | the `PETPOT` topic comment names b280: a pet's auto-potion request and whether it was refused. Not in STATUS-1.6.4.md's table; only the QA instrumentation exists. |
| NEW-FINDING | src/main/java/net/server/channel/handlers/UseCashItemHandler.java:771 | `if (i == id)` compares the **array index** with the **item id**, so `getIncubatedItem` can only ever match an id equal to an index (0-19) — it grants `amount = 0` of every one of its 20 listed ids, then still removes the incubator and the target item (`:274-277`). |
| NEW-FINDING | src/main/java/net/server/channel/handlers/UseCashItemHandler.java:571 | a second `else if (itemType == 552) { //DS EGG THING` is unreachable: 552 is already handled at :558, so the "DS EGG THING" branch is dead code and no DS-egg behaviour exists. |
| NEW-FINDING | src/main/java/client/Character.java:2656-2827 | `modifiedPickupItem` (reached by any player through `@backpack on`, src/main/java/client/command/CommandsExecutor.java:246) is a hand-copied fork of `originalPickupItem` that dropped four gates: `MapId.isSelfLootableOnly` (:2562), `needQuestItem` (:2589), `GodlyDrops.tellLooter` (:2632) and `updateAriantScore` (:2630). With the backpack on, a Happyville tree / guild-PQ drop can be taken by someone who did not drop it, a quest item can be picked up without the quest, and godly-drop and Ariant-PQ scoring never fire. It also carries `itemId == 2430030 && isPet` twice with a leftover scaffold comment ("Insert the condition for itemId == 2430030 and isPet here", :2708-2709 and :2733) where `originalPickupItem` has it once (:2614), and reroutes USE/ETC/SETUP into `infinityBag` (:2781). |
| NEW-FINDING | src/main/java/net/server/channel/handlers/UseCashItemHandler.java:605-608 | refusing a Vega cast answers `getInventoryFull()` — a wrong packet for "the equip has no upgrade slots", which the client renders as a full inventory. |
| NEW-FINDING | src/main/java/net/server/channel/handlers/KeymapChangeHandler.java:46-58 | a banned / GM-only / off-job skill bind hits the combined condition at :50 and is silently `continue`d at :56 with the autoban, the `FilePrinter` line and the disconnect all commented out (:52-55), so a packet-edited keymap leaves no trace at all. |

## State

**partial.**

Evidence for "working": the scroll engine is exercised end-to-end by `VegaAppliesFedScrollTest` and the reward-box
period by `ItemRewardHandlerTest`; the ownership model is coherent in one place (src/main/java/server/maps/MapItem.java:144-181
plus the lock at src/main/java/client/Character.java:2526); b289, b290 and b297 all have a named fix commit and at least
one test on this branch.

Evidence against "working": three cards that are fixed here are still open as live defects in
/root/work/STATUS-1.6.4.md:39-46, and the release they target is 1.6.4 — the fixes are not what live runs. The pickup
path exists twice and the second copy has lost the quest/self-loot/godly-drop gates (NEW-FINDING above), reachable by
any player. The incubator cash item is provably broken (grants 0). Half of the handlers in scope have no test at all,
including every branch of `ScrollHandler.handlePacket`.

## Open questions

1. Live and TEST values for every switch in `## Switches`: `config.yaml` is not in this tree and
   docs/release/1.6.4.conf carries only `-D` bot/mechanics flags. In particular `USE_ITEM_SORT` (the sort/merge buttons
   are inert when off), `COLOR_PRISM_ENABLED` (the prism handler no-ops when off) and `USE_COMPULSORY_AUTOPOT` change
   observable behaviour and cannot be confirmed from the repo.
2. Whether `@backpack` is meant to be a public command or a leftover experiment: it is registered for gm0
   (src/main/java/client/command/CommandsExecutor.java:246), its hint text says "Loot will now go into your backpack",
   and no card, ticket or QA scenario mentions it. Its pickup fork is the largest un-reviewed duplicate in this
   subsystem.
3. Whether a Vega boom should destroy the item: the code currently keeps it (`enhanced != null ? enhanced : toScroll`,
   src/main/java/net/server/channel/handlers/UseCashItemHandler.java:628) with a comment saying it is undecided.
4. Whether `server.GodlyDrops.tellLooter` and the Ariant score update are required for the backpack path (they are
   absent) or deliberately excluded.
5. `InventorySortHandler`'s secondary sort inside equal item ids depends on `PairedQuicksort`'s internal `intersect`
   bookkeeping, which no test pins; the intended player-visible order (id, then quantity/name/level, with arrows and
   stars reversed) is only documented in the code comments (:253-275).
