package soloMapling.ArtificialPlayer.BotGearSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear.Wear;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear.WearResult;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;

/**
 * Guild Quest's Protector Rock, 1032033, is the first thing a mechanics run has to put on a bot <em>by id</em>. Every map
 * from 990000300 on has {@code decHP 30000} and {@code protectItem 1032033} ({@code MapFactory.java:358-359}) and
 * {@code Character.doHurtHp} ({@code Character.java:3606-3609}) subtracts the 30000 from anyone whose equipped inventory
 * lacks it. The gear review ({@link BotGear#review}) dresses a bot by what its attack gains, never by id: for this
 * earring ({@code Character.wz/Accessory/01032033.img.xml}: level 10, +10 magic defence, nothing else) it wears it into an
 * empty earring slot only through the defence tie-break, and never over an earring that already has more defence.
 * {@link BotGear#wear} is the same move with the choice made by the caller.
 */
class BotGearWearTest {

    private static final int ROCK = 1032033;
    private static final int GOLD_STUDS = 1032001;
    private static final short EAR = -4;
    /** What a bot's attack is worth: nothing here changes it, as for an earring that only defends. */
    private static final Valuer ATTACK = (weapon, str, dex, int_, luk, watk, matk) -> watk * 10.0 + str;

    private final Map<Integer, Known> data = new HashMap<>();

    @AfterEach
    void reset() {
        GearMemo.clear();
    }

    private static Character bot(int level) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_000_000 + 1640);
        chr.setJob(Job.FIGHTER);
        chr.setLevel(level);
        return chr;
    }

    private static Equip equip(int itemId, short position, int mdef) {
        Equip e = mock(Equip.class, CALLS_REAL_METHODS);
        doReturn(itemId).when(e).getItemId();
        e.setPosition(position);
        e.setMdef((short) mdef);
        return e;
    }

    private void know(int itemId, int reqLevel) {
        data.put(itemId, new Known(EAR, reqLevel, 0, 0, 0, 0, 0, 0));
    }

    private Character botCarryingTheRock(int level) {
        know(ROCK, 10);
        Character bot = bot(level);
        bot.getInventory(InventoryType.EQUIP).addItemFromDB(equip(ROCK, (short) 3, 10));
        return bot;
    }

    @Test
    void theGearReviewDoesNotPutTheRockOnByIdAndWearIsTheMoveWithTheChoiceMadeByTheCaller() {
        // the bot already wears a studded earring worth 30 defence: the review keeps it, as it should for its own purposes
        know(GOLD_STUDS, 0);
        Character bot = botCarryingTheRock(40);
        Equip studs = equip(GOLD_STUDS, EAR, 30);
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(studs);

        assertFalse(BotGear.review(bot, data::get, worn -> ATTACK), "nothing improves its attack, and the studs defend more");
        assertFalse(BotGear.wearing(bot, ROCK), "so on a decHP map this bot would die");

        WearResult result = BotGear.wear(bot, ROCK, data::get);

        assertEquals(Wear.WORN, result.outcome(), result.why());
        assertEquals(ROCK, bot.getInventory(InventoryType.EQUIPPED).getItem(EAR).getItemId());
        assertTrue(BotGear.wearing(bot, ROCK));
        assertSame(studs, bot.getInventory(InventoryType.EQUIP).getItem((short) 3),
                "what it displaced went to the bag slot the earring came from");
    }

    @Test
    void anEmptyEarringSlotTakesTheRockAndTheBagSlotIsFreed() {
        Character bot = botCarryingTheRock(40);

        WearResult result = BotGear.wear(bot, ROCK, data::get);

        assertEquals(Wear.WORN, result.outcome(), result.why());
        assertEquals(ROCK, bot.getInventory(InventoryType.EQUIPPED).getItem(EAR).getItemId());
        assertNull(bot.getInventory(InventoryType.EQUIP).getItem((short) 3), "it left the bag");
    }

    @Test
    void aRockAlreadyOnIsLeftWhereItIs() {
        Character bot = botCarryingTheRock(40);
        BotGear.wear(bot, ROCK, data::get);
        Equip worn = (Equip) bot.getInventory(InventoryType.EQUIPPED).getItem(EAR);

        WearResult again = BotGear.wear(bot, ROCK, data::get);

        assertEquals(Wear.ALREADY_WORN, again.outcome());
        assertTrue(again.worn());
        assertSame(worn, bot.getInventory(InventoryType.EQUIPPED).getItem(EAR));
    }

    @Test
    void aBotThatDoesNotCarryItSaysSoAndNothingMoves() {
        know(ROCK, 10);
        Character bot = bot(40);

        WearResult result = BotGear.wear(bot, ROCK, data::get);

        assertEquals(Wear.NOT_CARRIED, result.outcome());
        assertFalse(result.worn());
        assertTrue(result.why().contains(String.valueOf(ROCK)), "it names the item: " + result.why());
        assertNull(bot.getInventory(InventoryType.EQUIPPED).getItem(EAR));
    }

    @Test
    void aBotTooLowForItIsRefusedWithTheGameOwnRuleAndNothingMoves() {
        Character bot = botCarryingTheRock(8);

        WearResult result = BotGear.wear(bot, ROCK, data::get);

        assertEquals(Wear.REFUSED, result.outcome());
        assertTrue(result.why().contains("level 10") && result.why().contains("level 8"), result.why());
        assertNull(bot.getInventory(InventoryType.EQUIPPED).getItem(EAR), "still bare: the level need is not waived");
        assertNotNull(bot.getInventory(InventoryType.EQUIP).getItem((short) 3), "and still in the bag");
        assertEquals(result.why(), BotGear.cannotWear(bot, ROCK, data::get), "the same rule can be asked beforehand");
        assertNull(BotGear.cannotWear(botCarryingTheRock(10), ROCK, data::get), "level 10 is enough");
    }

    @Test
    void anItemTheDataDoesNotKnowIsRefusedNotGuessed() {
        Character bot = bot(40);
        bot.getInventory(InventoryType.EQUIP).addItemFromDB(equip(ROCK, (short) 3, 10)); // carried, but no facts

        WearResult result = BotGear.wear(bot, ROCK, data::get);

        assertEquals(Wear.REFUSED, result.outcome());
        assertTrue(result.why().contains("item data"), result.why());
        assertNull(bot.getInventory(InventoryType.EQUIPPED).getItem(EAR));
    }
}
