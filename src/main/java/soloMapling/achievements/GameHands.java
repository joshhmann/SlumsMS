package soloMapling.achievements;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;

/** {@link Hands} on the real inventory. */
public final class GameHands implements Hands {

    @Override
    public boolean has(Character chr, int medal) {
        return chr != null && chr.haveItemWithId(medal, true);
    }

    @Override
    public boolean give(Character chr, Goal goal) {
        Equip medal = medal(goal.medal());
        if (chr == null || medal == null) {
            return false;
        }
        var inv = chr.getInventory(InventoryType.EQUIP);
        if (inv == null || inv.isFull()) {
            return false;
        }
        return InventoryManipulator.addFromDrop(chr, medal, false, -1);
    }

    @Override
    public void save(Character chr) {
        if (chr != null && !soloMapling.ArtificialPlayer.BotHelpers.isBot(chr)) {
            chr.saveCharToDB();
        }
    }

    @Override
    public void tell(Character chr, String text) {
        if (chr != null && !soloMapling.ArtificialPlayer.BotHelpers.isBot(chr)) {
            chr.dropMessage(6, text);
        }
    }

    @Override
    public String name(int medal) {
        return ItemInformationProvider.getInstance().getName(medal);
    }

    @Override
    public void announce(Character chr, Goal goal) {
        soloMapling.site.SiteFeed.achievement(chr, goal.title(), name(goal.medal()));
    }

    /** A copy of the v83 medal with every stat removed: cosmetic only. Null when the server has no such item. */
    public static Equip medal(int itemId) {
        Equip e = ItemInformationProvider.getInstance().getEquipByIdAsEquip(itemId);
        if (e != null) {
            zeroStats(e);
        }
        return e;
    }

    public static void zeroStats(Equip e) {
        e.setStr((short) 0);
        e.setDex((short) 0);
        e.setInt((short) 0);
        e.setLuk((short) 0);
        e.setHp((short) 0);
        e.setMp((short) 0);
        e.setWatk((short) 0);
        e.setMatk((short) 0);
        e.setWdef((short) 0);
        e.setMdef((short) 0);
        e.setAcc((short) 0);
        e.setAvoid((short) 0);
        e.setHands((short) 0);
        e.setSpeed((short) 0);
        e.setJump((short) 0);
        e.setVicious((short) 0);
        e.setUpgradeSlots((byte) 0);
    }
}
