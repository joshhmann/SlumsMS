package server.life;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.id.MobId;
import tools.PacketCreator;

/**
 * The one "use this item on that monster" rule that exists in the game data: Ellin PQ's purifier marble.
 *
 * <p>Ellin hands out ten {@code 2270004} (`scripts/npc/2133001.js:74-84`) and the party must weaken a Poison Flower
 * {@code 9300174} and then use one while it is weak, which turns it into a marble {@code 4001169}; twenty of those are
 * the stage. The rule lived only inside `UseCatchItemHandler`'s switch, which meant a bot had no way to do it without a
 * client packet and a second copy of the rule. It lives here now, and both the handler and the mechanics executor call it,
 * so there is one implementation of "when does the marble catch anything".
 */
public final class MonsterCatch {

    /** Below this fraction of its HP a Poison Flower can be caught (`(maxHp / 10) * 4`). */
    static final int POISON_FLOWER_HP_DIVISOR = 10;
    static final int POISON_FLOWER_HP_PARTS = 4;

    /**
     * Uses one purifier marble on {@code mob} if it is a Poison Flower weak enough to be caught, and returns whether it
     * caught it. A failed attempt (too healthy, or the wrong monster) is silent traffic-wise except for the client's own
     * "cannot be caught" message, exactly as a player pressing the item would see.
     */
    public static boolean usePurifierMarble(Client c, Character chr, Monster mob) {
        if (mob == null || mob.getId() != MobId.POISON_FLOWER) {
            return false;
        }
        if (mob.getHp() >= ((mob.getMaxHp() / POISON_FLOWER_HP_DIVISOR) * POISON_FLOWER_HP_PARTS)) {
            c.sendPacket(PacketCreator.catchMessage(0));
            return false;
        }
        chr.getMap().broadcastMessage(
                PacketCreator.catchMonster(mob.getObjectId(), ItemId.EPQ_PURIFICATION_MARBLE, (byte) 1));
        mob.getMap().killMonster(mob, null, false);
        InventoryManipulator.removeById(c, InventoryType.USE, ItemId.EPQ_PURIFICATION_MARBLE, 1, true, true);
        InventoryManipulator.addById(c, ItemId.EPQ_MONSTER_MARBLE, (short) 1, "", -1);
        return true;
    }

    private MonsterCatch() {
    }
}
