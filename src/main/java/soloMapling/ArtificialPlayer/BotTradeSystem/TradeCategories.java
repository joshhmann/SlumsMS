package soloMapling.ArtificialPlayer.BotTradeSystem;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.KarmaManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyShare;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Give;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1.6.2 ticket 99: which of a companion's items a "trade pots / scrolls / use / etc / equips / ammo / rocks / <item>
 * / <slot>" or "drop ..." means - after nutnnut/Cosmic {@code BotInventoryManager.collectItems} and the category words
 * of its {@code BotChatManager}. Only what a player could put in a window themselves: never a worn item (that is
 * "show me your hat"), never cash, pets or untradeable items. Shared by every companion; pure apart from the item data
 * ({@link Facts}).
 */
public final class TradeCategories {

    /** Equip slot word (BotCommandParser) -> body slot. */
    public static final Map<String, Short> SLOTS = Map.ofEntries(
            Map.entry("hat", (short) -1), Map.entry("face", (short) -2), Map.entry("eye", (short) -3),
            Map.entry("earring", (short) -4), Map.entry("top", (short) -5), Map.entry("pants", (short) -6),
            Map.entry("shoes", (short) -7), Map.entry("gloves", (short) -8), Map.entry("cape", (short) -9),
            Map.entry("shield", (short) -10), Map.entry("weapon", (short) -11), Map.entry("ring", (short) -12),
            Map.entry("pendant", (short) -17), Map.entry("medal", (short) -49), Map.entry("belt", (short) -50));

    /** What the item data says (the server's item data in game; a fake in tests). */
    public interface Facts {
        boolean tradeable(Item item);

        String name(int itemId);

        boolean potion(int itemId);

        /** The body slot this equip goes in (negative), 0 when it is no equip. */
        short slotOf(int itemId);
    }

    static final Facts LIVE = new Facts() {
        @Override
        public boolean tradeable(Item item) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return !ii.isUnmerchable(item.getItemId()) && !item.isUntradeable()
                    && !(ii.isDropRestricted(item.getItemId()) && !KarmaManipulator.hasKarmaFlag(item));
        }

        @Override
        public String name(int itemId) {
            String n = ItemInformationProvider.getInstance().getName(itemId);
            return n == null ? "" : n;
        }

        @Override
        public boolean potion(int itemId) {
            int family = itemId / 10000;
            return family >= 200 && family <= 202; // SupplyData.recovery's families: potions, foods, drinks
        }

        @Override
        public short slotOf(int itemId) {
            int t = ItemConstants.getEquipSlotType(itemId);
            return t > 0 ? (short) -t : 0;
        }
    };

    private static volatile Facts facts = LIVE;

    private TradeCategories() {
    }

    public static void useForTest(Facts f) {
        facts = f == null ? LIVE : f;
    }

    public static Facts facts() {
        return facts;
    }

    /** "pots" -> "pots", "slot:hat" -> "hat", "name:red potion" -> "red potion": how a companion says it. */
    public static String said(String category) {
        if (category == null) {
            return "that";
        }
        if (category.startsWith("slot:") || category.startsWith("name:")) {
            return category.substring(5);
        }
        return switch (category) {
            case "pots:hp" -> "hp pots";
            case "pots:mp" -> "mp pots";
            case "use" -> "use items";
            case "etc" -> "etc items";
            case "ammo" -> "ammo";
            default -> category;
        };
    }

    /** The meso amount of a "mesos:N" category, or -1. */
    public static int mesos(String category) {
        if (category == null || !category.startsWith("mesos:")) {
            return -1;
        }
        try {
            return Integer.parseInt(category.substring(6));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Everything of a category the holder could hand over, whole stacks, in inventory order. */
    public static List<Give> select(Character holder, String category) {
        List<Give> out = new ArrayList<>();
        if (holder == null || category == null || category.startsWith("mesos:")) {
            return out;
        }
        if (category.equals("rocks")) {
            addWhere(out, holder, InventoryType.ETC, item -> SupplyShare.ROCKS.contains(item.getItemId()));
            return out;
        }
        if (category.startsWith("slot:")) {
            Short slot = SLOTS.get(category.substring(5));
            if (slot != null) {
                addWhere(out, holder, InventoryType.EQUIP, item -> slotMatches(facts.slotOf(item.getItemId()), slot));
            }
            return out;
        }
        if (category.startsWith("name:")) {
            String piece = category.substring(5).toLowerCase(Locale.ROOT).trim();
            if (piece.length() < 2) {
                return out;
            }
            for (InventoryType t : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                    InventoryType.ETC)) {
                addWhere(out, holder, t, item -> facts.name(item.getItemId()).toLowerCase(Locale.ROOT).contains(piece));
            }
            return out;
        }
        switch (category) {
            case "pots", "pots:hp", "pots:mp" -> {
                // HP or MP alone: the same recovery data the bots drink by (BotVitals), through SupplyShare
                if (category.equals("pots")) {
                    addWhere(out, holder, InventoryType.USE, item -> facts.potion(item.getItemId()));
                } else {
                    SupplyShare.Kind kind = category.endsWith("hp") ? SupplyShare.Kind.HP : SupplyShare.Kind.MP;
                    List<Item> stacks = SupplyShare.stacksOf(holder, kind);
                    addWhere(out, holder, InventoryType.USE, stacks::contains);
                }
            }
            case "scrolls" -> addWhere(out, holder, InventoryType.USE, item -> item.getItemId() / 10000 == 204);
            case "use" -> addWhere(out, holder, InventoryType.USE, item -> !isAmmo(item.getItemId()));
            case "ammo" -> addWhere(out, holder, InventoryType.USE, item -> isAmmo(item.getItemId()));
            case "etc" -> addWhere(out, holder, InventoryType.ETC, item -> true);
            case "equips" -> addWhere(out, holder, InventoryType.EQUIP, item -> true);
            default -> {
            }
        }
        return out;
    }

    static boolean isAmmo(int itemId) {
        return ItemConstants.isRechargeable(itemId) || ItemConstants.isArrow(itemId);
    }

    private static boolean slotMatches(short itemSlot, short asked) {
        if (asked == -12) { // any ring
            return itemSlot == -12 || itemSlot == -13 || itemSlot == -15 || itemSlot == -16;
        }
        return itemSlot == asked;
    }

    private static void addWhere(List<Give> out, Character holder, InventoryType type,
                                 java.util.function.Predicate<Item> which) {
        Inventory inv = holder.getInventory(type);
        List<Item> items = new ArrayList<>();
        inv.lockInventory();
        try {
            for (Item item : inv.list()) {
                if (item != null && item.getQuantity() > 0 && which.test(item) && facts.tradeable(item)) {
                    items.add(item);
                }
            }
        } finally {
            inv.unlockInventory();
        }
        items.sort(Comparator.comparingInt(Item::getPosition));
        for (Item item : items) {
            out.add(new Give(type, item, item.getQuantity()));
        }
    }
}
