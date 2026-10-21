package soloMapling.FreeMarket;

import client.Character;
import server.maps.PlayerShopItem;

import java.awt.*;

public interface ShopKeeper {
    String getOwner();
    Point getPosition();
    void visitShop(Character fakechar);
    void chat(Character fakechar, String msg);
    void botBuyItem(Character fakechar, PlayerShopItem pItem, short quantity);
    void botBuyItemPlayerShop(Character fakechar, PlayerShopItem pItem, int itemPosition, short quantity);
    void removeVisitor(Character fakechar);

    /** The shop's map object; two adapters over the same shop return the same object. */
    Object shop();

    /** True while customers can walk in. */
    boolean isOpen();

    /** Visitor slots still free (a shop seats three visitors). */
    int freeVisitorSlots();

    /** Enters the shop as a visitor; true when the bot is now seated inside. */
    boolean enter(Character fakechar);

}

