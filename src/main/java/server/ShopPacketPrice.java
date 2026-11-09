package server;

import constants.inventory.ItemConstants;

/**
 * The price an NPC shop window shows for one entry (PacketCreator.getNPCShop).
 * <p>
 * Every shop window also lists every rechargeable the shop does not sell, at price 0, so the client can recharge it
 * (Shop.load, upstream behaviour). 1.6.4 b289 made the window show a rechargeable's whole-stack WZ price, which is
 * right for a star the shop sells (Subi, Bullet: that is what the buy charges) but made those price-0 recharge entries
 * look for sale: Ilbi at 29,999 in every general store (owner, 8 Nov 2026, shop-ammo-tiers). They cannot be bought
 * (Shop.buy needs a shop price), so the window now shows them at 0 again, as upstream does.
 */
public final class ShopPacketPrice {
    private ShopPacketPrice() {
    }

    /**
     * {@code stackPrice} (what Shop.buy charges for a whole stack) for a rechargeable the shop sells; the shop's own
     * price for everything else, which is 0 for a recharge-only entry. A stack price below 0 (no WZ price) falls back
     * to the shop's price, as before.
     */
    public static int forEntry(int itemId, int shopPrice, int stackPrice) {
        if (ItemConstants.isRechargeable(itemId) && shopPrice > 0 && stackPrice >= 0) {
            return stackPrice;
        }
        return shopPrice;
    }
}
