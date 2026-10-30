package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotYieldCaps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/*
 * 1.6.0 owner report 2026-10-26 (CapeLover on a Spore map with a carpet of loot): a bot's equip and etc tabs filled
 * up and nothing ever emptied them. BotHoldings.canCreditDrop then (rightly) refused every equip and every new etc
 * drop, so GrindLoot skipped them and they lay on the floor for the full drop expiry, while the bot kept killing. On
 * TEST 6,744 of 8,682 bots had a full equip tab and 5,179 of 9,083 a full etc tab. A bot had no way to sell to an
 * NPC: its town stop bought potions, and the life SELL activity is only for the ~10% "merchant-minded" bots and only
 * opens a player shop.
 *
 * Two fixes, both bots-only:
 *  - room: a roster bot's tabs hold BOT_SLOTS slots instead of the player's 24. A bot has no client, so nothing can
 *    desync, and a bot that never reaches a shop does not strand loot as fast. Real players, owned alts and any
 *    character below BotStore.BOT_ID_BASE keep exactly what they had (the client decides their slot counts).
 *  - selling: when the equip or etc tab is nearly full the bot leaves the grind for town (TrainingBot.bagRetreat),
 *    and every town stop sells its junk to the town's store like a player would: the cheapest carried equips and etc
 *    stacks first, down to half full, never a quest item or a cash item. The proceeds go through the ticket 15 yield
 *    caps (BotYieldCaps.takeMesos: the daily meso cap and the 56B wealth cut) like any meso a bot takes from the
 *    world; what the caps refuse is still sold off (a player over the cap would just drop it). The use tab is never
 *    sold: potions, ammo and scrolls are what the bot fights and scrolls with.
 *
 * Tuned with system properties (config.yaml is strict): -Dsolomapling.botBagSlots (default 96, the v83 maximum, 24..96; 24 = off),
 * -Dsolomapling.botJunkSale=false turns the town sale off.
 */
public final class BotBagRoom {

    private static final Logger log = LoggerFactory.getLogger(BotBagRoom.class);

    public static final int PLAYER_SLOTS = 24;
    /** The v83 client's most slots in a tab; owner decision 2026-10-26: every bot starts with it in every tab. */
    public static final int V83_MAX_SLOTS = 96;
    static final int DEFAULT_BOT_SLOTS = V83_MAX_SLOTS;
    /** The tabs a bot sells from, and whose fullness sends it to town. */
    static final List<InventoryType> SOLD_TABS = List.of(InventoryType.EQUIP, InventoryType.ETC);
    /** A sold tab this full (used / capacity) sends a grinding bot to town. */
    static final double NEARLY_FULL = 0.9;
    /** A town sale empties a tab down to this fill. */
    static final double SELL_DOWN_TO = 0.5;

    private BotBagRoom() {
    }

    /** What one carried item fetches at an NPC store; -1 or 0 when a store does not buy it. */
    @FunctionalInterface
    public interface Prices {
        long priceOf(Item item);
    }

    /** True when the item must never be sold off (quest items, cash items). */
    @FunctionalInterface
    public interface Keep {
        boolean keep(Item item);
    }

    public record Sale(int items, long mesosOffered, long mesosCredited) {
        static final Sale NONE = new Sale(0, 0, 0);
    }

    public static int botSlots() {
        return Math.max(PLAYER_SLOTS, Math.min(V83_MAX_SLOTS, Integer.getInteger("solomapling.botBagSlots", DEFAULT_BOT_SLOTS)));
    }

    /** Only population bots get the bigger bag: never a player, never a player's owned alt. */
    public static boolean widenable(Character chr) {
        return chr != null && chr.getId() >= BotStore.BOT_ID_BASE && BotHelpers.isRosterBot(chr);
    }

    /** Raises a population bot's equip/use/setup/etc capacity to botSlots(); never lowers it, never touches a player. */
    public static void widen(Character chr) {
        if (!widenable(chr)) {
            return;
        }
        int slots = botSlots();
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC)) {
            Inventory inv = chr.getInventory(type);
            if (Byte.toUnsignedInt(inv.getSlotLimit()) < slots) {
                inv.setSlotLimit(slots);
            }
        }
    }

    /** True when the equip or etc tab is nearly full, so drops of that tab will soon be left on the floor. */
    public static boolean nearlyFull(Character chr) {
        if (chr == null || !BotHelpers.isBot(chr)) {
            return false;
        }
        for (InventoryType type : SOLD_TABS) {
            if (fill(chr.getInventory(type)) >= NEARLY_FULL) {
                return true;
            }
        }
        return false;
    }

    public static double fill(Inventory inv) {
        inv.lockInventory();
        try {
            int capacity = Byte.toUnsignedInt(inv.getSlotLimit());
            return capacity <= 0 ? 0 : 1 - inv.getNumFreeSlot() / (double) capacity;
        } finally {
            inv.unlockInventory();
        }
    }

    public static boolean saleEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.botJunkSale", "true").trim());
    }

    /** The town stop's sale at the store: sells junk out of every sold tab that is over half full. Never throws. */
    public static Sale sellJunk(Character chr) {
        if (!saleEnabled() || chr == null || !BotHelpers.isBot(chr)) {
            return Sale.NONE;
        }
        boolean over = false;
        for (InventoryType type : SOLD_TABS) {
            over |= fill(chr.getInventory(type)) > SELL_DOWN_TO;
        }
        if (!over) {
            return Sale.NONE; // nothing to sell: the item data is never touched on an ordinary town stop
        }
        try {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            // Ticket 162 part 4 (-Dslums.junk.sort): reserve and valuables-shelf equips stay out of the sale.
            java.util.Set<Item> sorted = JunkSort.keeps(chr);
            Sale sale = sellJunk(chr, item -> ii.getPrice(item.getItemId(), item.getQuantity()),
                    item -> ii.isQuestItem(item.getItemId()) || ii.isCash(item.getItemId())
                            || soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.keeps(item.getItemId())
                            || sorted.contains(item));
            if (sale.items() > 0) {
                log.info("[Bag] {} (lv {}) sold {} junk item(s) at the store on map {} for {} mesos ({} credited)",
                        chr.getName(), chr.getLevel(), sale.items(), chr.getMapId(), sale.mesosOffered(),
                        sale.mesosCredited());
            }
            return sale;
        } catch (RuntimeException | LinkageError e) {   // the town stop must go on (no item data, a headless run)
            log.warn("Bot junk sale failed for {}", chr.getName(), e);
            return Sale.NONE;
        }
    }

    /** The sale with its price list and keep rule passed in (headless tests have no WZ item data). */
    public static Sale sellJunk(Character chr, Prices prices, Keep keep) {
        int sold = 0;
        long offered = 0;
        long credited = 0;
        for (InventoryType type : SOLD_TABS) {
            Inventory inv = chr.getInventory(type);
            List<Item> candidates = new ArrayList<>();
            int toFree;
            inv.lockInventory();
            try {
                int capacity = Byte.toUnsignedInt(inv.getSlotLimit());
                int used = capacity - inv.getNumFreeSlot();
                toFree = used - (int) Math.floor(capacity * SELL_DOWN_TO);
                if (toFree <= 0) {
                    continue;
                }
                for (Item item : inv.list()) {
                    if (item.getPosition() > 0 && !keep.keep(item) && !(item instanceof Equip equip && equip.getRingId() > 0)) {
                        candidates.add(item);
                    }
                }
            } finally {
                inv.unlockInventory();
            }
            // Cheapest first: the bot keeps its best gear and its valuable stacks, like a player clearing junk.
            candidates.sort(Comparator.comparingLong((Item item) -> Math.max(0, prices.priceOf(item)))
                    .thenComparingInt(Item::getPosition));
            for (Item item : candidates) {
                if (toFree <= 0) {
                    break;
                }
                long price = Math.max(0, prices.priceOf(item));
                if (!BotHoldings.destroyHeldItem(chr, item)) {
                    continue; // moved or gone since the scan
                }
                toFree--;
                sold++;
                offered += price;
                credited += creditSale(chr, price);
            }
        }
        if (credited > 0) {
            soloMapling.Economy.stats.EconomyCounters.recordNpcSell(credited);
        }
        return sold == 0 ? Sale.NONE : new Sale(sold, offered, credited);
    }

    // The sale's mesos come out of the world: they pass the yield caps like a looted meso drop (ticket 15).
    private static long creditSale(Character chr, long price) {
        if (price <= 0) {
            return 0;
        }
        long allowed = Math.min(Integer.MAX_VALUE, BotYieldCaps.takeMesos(chr, price));
        if (allowed <= 0) {
            return 0;
        }
        if (!BotHoldings.grantMeso(chr, (int) allowed)) {
            BotYieldCaps.returnMesos(chr, allowed);
            return 0;
        }
        return allowed;
    }
}
