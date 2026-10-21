package soloMapling.FreeMarket;

import client.Character;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import tools.PacketCreator;

import java.awt.*;

// Adapter for HiredMerchant
public class HiredMerchantAdapter implements ShopKeeper {
    private final HiredMerchant merchant;

    public HiredMerchantAdapter(HiredMerchant merchant) {
        this.merchant = merchant;
    }

    @Override
    public String getOwner() {
        return merchant.getOwner();
    }

    @Override
    public Point getPosition() {
        return merchant.getPosition();
    }

    @Override
    public void visitShop(Character fakechar) {
        getMerchant().visitShop(fakechar);
    }

    @Override
    public void chat(Character fakechar, String msg) {
        getMerchant().sendMessage(fakechar, msg);
    }

    @Override
    public void removeVisitor(Character fakechar) {
        getMerchant().removeVisitor(fakechar);
    }

    @Override
    public void botBuyItem(Character fakechar, PlayerShopItem pItem, short quantity) {
        getMerchant().botBuy(fakechar, pItem, quantity);
        getMerchant().broadcastToVisitorsThreadsafe(PacketCreator.updateHiredMerchant(getMerchant(), fakechar));
    }

    @Override
    public void botBuyItemPlayerShop(Character fakechar, PlayerShopItem pItem, int itemPosition, short quantity) {
        // Nothing due to PlayerShop only
    }

    @Override
    public Object shop() {
        return merchant;
    }

    @Override
    public boolean isOpen() {
        return merchant.isOpen();
    }

    @Override
    public int freeVisitorSlots() {
        byte[] info = merchant.getShopRoomInfo();   // {visitors (max+1 when closed), max+1}
        return Math.max(0, info[1] - 1 - info[0]);
    }

    @Override
    public boolean enter(Character fakechar) {
        merchant.visitShop(fakechar);
        return merchant.getVisitorSlotThreadsafe(fakechar) >= 0;
    }

    // Helper method to get the original merchant if needed
    public HiredMerchant getMerchant() {
        return merchant;
    }
}
