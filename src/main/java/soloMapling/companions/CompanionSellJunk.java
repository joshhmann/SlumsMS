package soloMapling.companions;

// Ported from https://github.com/nutnnut/Cosmic.git
//   src/main/java/server/bots/AgentInventorySellTrashPolicy.java @ b684bf7 (the protection list: a scrolled piece, one
//   rolled above its clean stats, an equip with open upgrade slots and a better-than-worn piece are never sold)
// and from https://github.com/Kiwiism/cosmic_agents.git
//   src/main/java/server/agents/capabilities/shop/AgentShopService.java @ 3c7e0943 (requestSellTrashVisit: find the
//   shop on this map, walk to it, else answer "no shop here")
// changed: their InventoryGateway seams are our own Character/Inventory code; the protection list is expressed with
// our SaleRules ("why not") plus GearUpgrades scoring instead of their stat thresholds; the sale itself is
// BotBagRoom's (one sell path for bots and companions, the same mesos credit); the walk is GCMovement, which every
// bot already uses, so there is no shop-visit state machine of its own and no new shop protocol.

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.ShopFactory;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGearSystem.GearUpgrades;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotBagRoom;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.Economy.SaleRules;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 1.6.7 alt-qol-batch group D: the owner-ordered {@code sell junk} - never automatic for a companion (the owner's
 * decision: an alt sells nothing unless told). The companion finds a shop NPC on the map it stands on, walks to it
 * and sells what the protection list allows, then says what it sold and for how much.
 *
 * <p>The protection list (the owner's, 8 Nov 2026: ETC and USE are sold, with a keep-list) keeps every potion and food
 * the bots drink, cure potions, return and teleport scrolls, skill and mastery books, summoning sacks, pet food, scrolls,
 * stars, bullets and arrows, quest items of its own or its owner's, the rebirth items ({@code BotRebirth.keeps}), cash
 * and untradeable items, and anything {@link SaleRules} refuses. {@code sell junk equips too} adds plain equips only: a
 * piece with an upgrade used, one worth wearing over what the companion or its owner wears ({@link GearUpgrades}), an
 * untradeable or cash piece and anything the SaleRules refuse still stay.
 *
 * <p>The sale is {@link BotBagRoom#sellAll}: the same candidate scan and the same meso credit the town stop uses, so
 * the mesos are exactly what today's junk sale pays and no price or rate changes.
 */
public final class CompanionSellJunk {

    /** The v83 slots a scroll is listed under: every scroll's item id starts 204. */
    static final int SCROLL_PREFIX = 204;

    private CompanionSellJunk() {
    }

    /** A shop NPC on this map, with the point to stand on. */
    public record Shop(NPC npc, Point at) {
    }

    /** Whether an NPC has a shop (the real one asks {@link ShopFactory}; a test says so by id). */
    @FunctionalInterface
    public interface ShopFinder {
        boolean hasShop(int npcId);
    }

    private static final ShopFinder REAL_SHOPS = npcId -> {
        // ShopFactory caches in plain HashMaps; bots ask from many threads, so they at least never race there
        synchronized (ShopFactory.class) {
            return ShopFactory.getInstance().getShopForNPC(npcId) != null;
        }
    };
    private static volatile ShopFinder shops = REAL_SHOPS;

    /** Tests: which NPCs have a shop (null restores the real lookup). */
    public static void useForTest(ShopFinder finder) {
        shops = finder == null ? REAL_SHOPS : finder;
    }

    /** The shop NPC nearest {@code at} on the map, or null when the map has none. */
    public static Shop shopOn(MapleMap map, Point at) {
        if (map == null) {
            return null;
        }
        Shop best = null;
        double bestSq = Double.MAX_VALUE;
        try {
            for (MapObject object : map.getMapObjects()) {
                if (!(object instanceof NPC npc) || npc.getPosition() == null) {
                    continue;
                }
                if (!shops.hasShop(npc.getId())) {
                    continue;
                }
                double sq = at == null ? 0 : at.distanceSq(npc.getPosition());
                if (sq < bestSq) {
                    best = new Shop(npc, npc.getPosition());
                    bestSq = sq;
                }
            }
        } catch (RuntimeException | LinkageError noShopData) {
            return null; // no shop data (a headless run): the companion says there is no shop, as it would on an
            // empty map, rather than selling into nothing
        }
        return best;
    }

    /** The tabs the ordered sale looks in: etc always, use always, equips only with "sell junk equips too". */
    public static List<InventoryType> tabs(boolean equipsToo) {
        return equipsToo
                ? List.of(InventoryType.ETC, InventoryType.USE, InventoryType.EQUIP)
                : List.of(InventoryType.ETC, InventoryType.USE);
    }

    /**
     * What the ordered sale never sells: the owner's protection list plus {@link SaleRules}. {@code owner} may be
     * null (a companion with no owner link).
     */
    public static BotBagRoom.Keep keep(Character companion, Character owner, boolean equipsToo) {
        return item -> item == null || protectedItem(companion, owner, item, equipsToo);
    }

    /** One carried item's protection answer. */
    public static boolean protectedItem(Character companion, Character owner, Item item, boolean equipsToo) {
        int id = item.getItemId();
        if (SaleRules.whyNot(item) != null) {
            return true; // uncashable anyway: cash, untradeable, a pet or ring, a boss-only drop, godly scrolled gear
        }
        if (isScroll(id) || isPotionOrAmmo(id) || keptUse(id)) {
            return true; // what it fights, travels and scrolls with (the owner's USE keep-list)
        }
        if (soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.keeps(id)) {
            return true; // a rebirth key or ingredient: the town stop keeps these too (townStopKeep)
        }
        if (isQuestItem(id)) {
            return true; // a quest item, whether or not either of them has the quest right now
        }
        if (neededByAQuest(companion, id) || neededByAQuest(owner, id)) {
            return true; // an active quest of either of them still wants it
        }
        if (item instanceof Equip equip) {
            return equipProtected(companion, owner, equip, equipsToo);
        }
        return false; // a plain etc/use drop: junk
    }

    /** An equip is kept unless the owner said "sell junk equips too" and the piece is plain. */
    static boolean equipProtected(Character companion, Character owner, Equip equip, boolean equipsToo) {
        if (!equipsToo) {
            return true;
        }
        if (equip.getLevel() > 0) {
            return true; // an upgrade slot was used: somebody chose to scroll it
        }
        if (equip.getRingId() > 0) {
            return true;
        }
        return betterThanWorn(companion, equip) || betterThanWorn(owner, equip);
    }

    /** True when {@code wearer} would take this piece over what it has ({@link GearUpgrades}' own scorer). */
    static boolean betterThanWorn(Character wearer, Equip piece) {
        if (wearer == null) {
            return false;
        }
        try {
            return GearUpgrades.scorer().gain(wearer, piece) > 0;
        } catch (RuntimeException | LinkageError noItemData) {
            return true; // no item data to weigh it: keep it rather than sell something that may be better
        }
    }

    /** An item an active quest of this character still needs (its turn-in requirement names it). */
    static boolean neededByAQuest(Character chr, int itemId) {
        if (chr == null) {
            return false;
        }
        try {
            for (client.QuestStatus status : chr.getStartedQuests()) {
                if (status == null || status.getQuest() == null) {
                    continue;
                }
                if (status.getQuest().getCompleteItemAmountNeeded(itemId) != Integer.MAX_VALUE) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError noQuestData) {
            return true; // cannot tell: keep it
        }
        return false;
    }

    /** Whether the item data calls it a quest item (the town stop's own question, and it keeps the item). */
    static boolean isQuestItem(int itemId) {
        try {
            return ItemInformationProvider.getInstance().isQuestItem(itemId);
        } catch (RuntimeException | LinkageError noItemData) {
            return true; // cannot tell: keep it
        }
    }

    static boolean isScroll(int itemId) {
        return itemId / 10000 == SCROLL_PREFIX;
    }

    /**
     * The owner's USE keep-list (8 Nov 2026, "nvm add use"), by item family: every potion, elixir and food the bots
     * drink (200-202, the families {@code SupplyData.recovery} reads), cure potions (205), return and teleport scrolls
     * (203), summoning sacks (210), pet food (212), skill and mastery books (228, 229). Scrolls, stars, bullets and
     * arrows are {@link #isScroll} and {@link #isPotionOrAmmo}.
     */
    static final java.util.Set<Integer> KEPT_USE_FAMILIES = java.util.Set.of(200, 201, 202, 203, 205, 210, 212, 228,
            229);

    static boolean keptUse(int itemId) {
        return KEPT_USE_FAMILIES.contains(itemId / 10000);
    }

    public static boolean isPotionOrAmmo(int itemId) {
        return ItemConstants.isPotion(itemId) || ItemConstants.isFood(itemId)
                || ItemConstants.isRechargeable(itemId)
                || ItemConstants.isArrowForBow(itemId) || ItemConstants.isArrowForCrossBow(itemId);
    }

    /** Prices through the same item data the town stop's sale uses. */
    static long priceOf(Item item) {
        try {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return ii.getPrice(item.getItemId(), item.getQuantity());
        } catch (RuntimeException | LinkageError noItemData) {
            return 0;
        }
    }

    /**
     * The free sale with the keep rule and price list passed in (headless tests have no WZ item data): everything the
     * list allows out of {@link #tabs}, mesos credited exactly as the town stop's are.
     */
    public static BotBagRoom.Sale sell(Character companion, Character owner, boolean equipsToo,
                                       BotBagRoom.Prices prices) {
        if (companion == null) {
            return new BotBagRoom.Sale(0, 0, 0);
        }
        return BotBagRoom.sellAll(companion, tabs(equipsToo), prices,
                keep(companion, owner, equipsToo));
    }

    /** What the companion says afterwards. */
    public static String said(BotBagRoom.Sale sale) {
        if (sale.items() <= 0) {
            return "nothing worth selling in my bag";
        }
        return "sold " + sale.items() + " junk item" + (sale.items() == 1 ? "" : "s") + " for "
                + sale.mesosCredited() + " mesos";
    }

    /** What it says when the map has no shop NPC. */
    public static String noShopSaid() {
        return "there's no shop on this map";
    }

    /**
     * The owner's order: walk to a shop NPC on this map and sell, or say there is none. {@code say} is the
     * companion's voice (party chat for an owned alt); null is a silent run. True when a sale started.
     */
    public static boolean order(Character companion, Character owner, boolean equipsToo, Consumer<String> say) {
        if (companion == null || companion.getMap() == null) {
            return false;
        }
        if (!carriesSomething(companion, tabs(equipsToo))) {
            tell(say, said(new BotBagRoom.Sale(0, 0, 0)));
            return false;
        }
        Shop shop = shopOn(companion.getMap(), companion.getPosition());
        if (shop == null) {
            tell(say, noShopSaid());
            return false;
        }
        Point at = shop.at();
        GCMovement.move(companion, at.x, at.y, () -> {
            BotBagRoom.Sale sale = sell(companion, owner, equipsToo, CompanionSellJunk::priceOf);
            tell(say, said(sale));
        });
        return true;
    }

    /** Whether anything at all is carried in these tabs (so a needless walk is never started). */
    static boolean carriesSomething(Character companion, List<InventoryType> tabs) {
        try {
            for (InventoryType type : tabs) {
                client.inventory.Inventory inv = companion.getInventory(type);
                inv.lockInventory();
                try {
                    for (Item item : new ArrayList<>(inv.list())) {
                        if (item.getPosition() > 0 && item.getItemId() > 0) {
                            return true;
                        }
                    }
                } finally {
                    inv.unlockInventory();
                }
            }
        } catch (RuntimeException | LinkageError noInventory) {
            return false;
        }
        return false;
    }

    private static void tell(Consumer<String> say, String line) {
        if (say != null && line != null) {
            say.accept(line);
        }
    }
}
