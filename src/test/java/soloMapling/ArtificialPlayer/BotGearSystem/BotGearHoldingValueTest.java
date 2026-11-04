package soloMapling.ArtificialPlayer.BotGearSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Ticket 181 prog#P2: the Maker junk test asked "would this piece be worn?" with the bot's own level, so a piece
 * above the bot's level always scored 0 and was broken down. {@link GearScoring#holdingValue} asks the same question
 * at the piece's own required level: what it would bring to someone who can wear it.
 */
class BotGearHoldingValueTest {

    private static final int LOW_HAT = 1002000;
    private static final int HIGH_HAT = 1002003;

    private static final Valuer STR = (weapon, str, dex, int_, luk, watk, matk) -> str + watk * 10.0;
    private final Map<Integer, Known> data = new HashMap<>();

    private void know(int itemId, int reqLevel) {
        data.put(itemId, new Known((short) -1, reqLevel, 0, 0, 0, 0, 0, 0));
    }

    private static Character chr(int id, int level) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setJob(Job.BEGINNER);
        c.setLevel(level);
        return c;
    }

    private static Equip equip(int itemId, short position, int str) {
        Equip e = mock(Equip.class, CALLS_REAL_METHODS);
        doReturn(itemId).when(e).getItemId();
        e.setPosition(position);
        e.setStr((short) str);
        return e;
    }

    @Test
    void holdingValueScoresAPieceAtItsOwnRequiredLevel() {
        know(LOW_HAT, 0);
        know(HIGH_HAT, 50);
        Character bot = chr(10, 30);                                       // level 30, the hat wants 50
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip(LOW_HAT, (short) -1, 2));
        Equip high = equip(HIGH_HAT, (short) 5, 20);

        assertEquals(0.0, BotGear.upgradeValue(bot, high, data::get, worn -> STR), 1e-9,
                "a level 30 bot cannot wear it, so wearing it is no upgrade for it");
        assertEquals(18.0, BotGear.holdingValue(bot, high, data::get, worn -> STR), 1e-9,
                "at its own level 50 it brings 18 more than the hat the bot wears (20 STR instead of 2)");
    }
}
