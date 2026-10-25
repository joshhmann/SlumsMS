/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Matze
 */
public class ShopFactory {
    private static final ShopFactory instance = new ShopFactory();

    public static ShopFactory getInstance() {
        return instance;
    }

    /** Loads a shop row by shop id (true) or by NPC id (false); swapped out in tests. */
    interface Loader {
        Shop load(int id, boolean isShopId);
    }

    private final Map<Integer, Shop> shops = new HashMap<>();
    private final Map<Integer, Shop> npcShops = new HashMap<>();
    private final Loader loader;

    private ShopFactory() {
        this(Shop::createFromDB);
    }

    ShopFactory(Loader loader) {
        this.loader = loader;
    }

    /**
     * Shops only GMs may open: !gmshop's 1337 and the stock 9999992-9999999 GM shops (pets, cash items, chairs,
     * rings and GM gear at 1 meso). Their shop rows still name an NPC: 1337 names Maple Island's NPC 11000 (whose
     * own shop is 11000) and 9999992-9999999 name Polar Bear Poch (9001002), who stands in Coke Town (219000000)
     * with no script. Looking a shop up by NPC never returns one of these, so a player talking to Poch, and a bot
     * reading Maple Island's shops, no longer get the GM stock.
     */
    public static boolean isGmShop(int shopId) {
        return shopId == 1337 || (shopId >= 9999992 && shopId <= 9999999);
    }

    private Shop loadShop(int id, boolean isShopId) {
        Shop ret = loader.load(id, isShopId);
        if (ret != null && !isShopId && isGmShop(ret.getId())) {
            ret = null; // the DB query already skips them; this keeps a stray row from ever coming back by NPC
        }
        if (ret != null) {
            shops.put(ret.getId(), ret);
            if (!isGmShop(ret.getId())) {
                npcShops.put(ret.getNpcId(), ret); // opening a GM shop by id must not become its NPC's shop
            }
        } else if (isShopId) {
            shops.put(id, null);
        } else {
            npcShops.put(id, null);
        }
        return ret;
    }

    public Shop getShop(int shopId) {
        if (shops.containsKey(shopId)) {
            return shops.get(shopId);
        }
        return loadShop(shopId, true);
    }

    public Shop getShopForNPC(int npcId) {
        if (npcShops.containsKey(npcId)) {
            return npcShops.get(npcId);
        }
        return loadShop(npcId, false);
    }

    public void reloadShops() {
        shops.clear();
        npcShops.clear();
    }
}
