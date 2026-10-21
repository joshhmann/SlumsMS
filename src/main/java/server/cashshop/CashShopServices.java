package server.cashshop;

import client.Character;
import client.Client;
import config.YamlConfig;
import constants.inventory.ItemConstants;

/**
 * Catalogue rows that are not merchandise, and the stock purchase gates, for the Cash Shop
 * window.
 *
 * <p>A SERVICE row is bought like any other row but never reaches the inventory: buying it
 * changes the character or the account instead. These used to live only in
 * {@code CashOperationHandler} (actions 0x06 and 0x08), which the window never reaches, so the
 * window either could not list them at all (the 911xxxx slot expansions have no inventory
 * type, and the loader dropped them) or delivered them as an inert item (5430000).
 *
 * <p>The slot-expansion ids follow {@code Item.wz/Special/0911.img} and the stock handler's
 * {@code (itemId - 9110000) / 1000} decode: 9111000 Equip, 9112000 Use, 9113000 Set-up,
 * 9114000 Etc. 9110000 is STORAGE and is deliberately not a service here -- every storage is
 * already at the server cap.
 */
public final class CashShopServices {
    public static final int EXTRA_CHARACTER_SLOT = 5430000;
    public static final int SLOT_EXPANSION_BASE = 9110000;
    /** Slots one expansion adds -- the stock "+8" row. */
    public static final int SLOTS_PER_EXPANSION = 8;
    /** Mirrors {@code Character.canGainSlots}. */
    public static final int MAX_INVENTORY_SLOTS = 96;

    private static final String[] TAB_NAMES = {"", "Equip", "Use", "Set-up", "Etc"};

    private CashShopServices() {
    }

    /** The inventory type (1-4) a slot-expansion row grows, or -1 when it is not one. */
    public static int slotExpansionType(int itemId) {
        if (itemId < SLOT_EXPANSION_BASE + 1000 || itemId > SLOT_EXPANSION_BASE + 4000
                || itemId % 1000 != 0) {
            return -1;
        }
        return (itemId - SLOT_EXPANSION_BASE) / 1000;
    }

    public static boolean isService(int itemId) {
        return itemId == EXTRA_CHARACTER_SLOT || slotExpansionType(itemId) > 0;
    }

    /**
     * EXP and drop coupons. Narrower than {@link ItemConstants#isRateCoupon}, which also
     * counts the Maple Life cards (543xxxx) -- applying the rate-coupon switch to those would
     * hide a character-creation item behind a flag about rates.
     */
    public static boolean isRateCoupon(int itemId) {
        final int type = itemId / 1000;
        return type == 5211 || type == 5360;
    }

    private static boolean rateCouponsOnSale() {
        return YamlConfig.config.server.USE_SUPPLY_RATE_COUPONS;
    }

    /**
     * Whether the row is shown at all. Rate coupons are hidden while
     * {@code USE_SUPPLY_RATE_COUPONS} is off; that flag is purely a sale switch (the coupons
     * still apply their rates when held), and the stock shop refused to sell them under it.
     */
    public static boolean isListed(int itemId) {
        return !isRateCoupon(itemId) || rateCouponsOnSale();
    }

    /** Why a purchase is refused before anything is touched. */
    public record Denial(int code, String message) {
    }

    /**
     * The stock gates from {@code CashOperationHandler} (merchant level 16, Maple Life level
     * 30, the rate-coupon switch), the equip gender lock the client enforces when equipping,
     * plus the service caps. Returns null when the row may be
     * bought.
     */
    public static Denial check(Client c, Character chr, int itemId) {
        if (ItemConstants.isCashStore(itemId) && chr.getLevel() < 16) {
            return new Denial(CashShopWindowPackets.BUY_LEVEL_TOO_LOW,
                    "You must be at least level 16 to buy a store item.");
        }
        if (ItemConstants.isMapleLife(itemId) && chr.getLevel() < 30) {
            return new Denial(CashShopWindowPackets.BUY_LEVEL_TOO_LOW,
                    "You must be at least level 30 to buy a Maple Life card.");
        }
        if (isRateCoupon(itemId) && !rateCouponsOnSale()) {
            return new Denial(CashShopWindowPackets.BUY_NOT_ON_SALE,
                    "Rate coupons are currently unavailable to purchase.");
        }
        if (!ItemConstants.isWearableByGender(itemId, chr.getGender())) {
            return new Denial(CashShopWindowPackets.BUY_CANNOT_WEAR,
                    "That item is for " + (ItemConstants.getEquipGender(itemId) == 0 ? "male" : "female")
                            + " characters only, so this character could never wear it. You were not charged.");
        }
        final int type = slotExpansionType(itemId);
        if (type > 0 && !chr.canGainSlots(type, SLOTS_PER_EXPANSION)) {
            return new Denial(CashShopWindowPackets.BUY_LIMIT_REACHED,
                    "Your " + TAB_NAMES[type] + " inventory cannot be expanded any further ("
                            + MAX_INVENTORY_SLOTS + " slots is the maximum). You were not charged.");
        }
        if (itemId == EXTRA_CHARACTER_SLOT && !c.canGainCharacterSlot()) {
            return new Denial(CashShopWindowPackets.BUY_LIMIT_REACHED,
                    "Your account already has the maximum number of character slots. You were not charged.");
        }
        return null;
    }

    static String tabName(int type) {
        return TAB_NAMES[type];
    }
}
