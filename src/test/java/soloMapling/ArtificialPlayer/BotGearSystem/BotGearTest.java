package soloMapling.ArtificialPlayer.BotGearSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

// Real bots 10: a bot moves the best equips it carries onto its body, and what they replace into its equip tab, where
// the bot store's sweep writes them with its holdings.
class BotGearTest {

    private static final int SWORD = 1302000;
    private static final int BETTER_SWORD = 1302001;
    private static final int SPEAR = 1432000;
    private static final int BOW = 1452000;
    private static final int TOP = 1040000;
    private static final int PANTS = 1060000;
    private static final int OVERALL = 1050000;

    private static final Valuer ATTACK = (weapon, str, dex, int_, luk, watk, matk) -> watk * 10.0 + str + dex;

    private final Map<Integer, Known> data = new HashMap<>();
    private final IntFunction<Known> facts = data::get;

    private void know(int itemId, short slot, int reqLevel) {
        data.put(itemId, new Known(slot, reqLevel, 0, 0, 0, 0, 0, 0));
    }

    private static Character bot(Job job, int level) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_000_000 + 1010);
        chr.setJob(job);
        chr.setLevel(level);
        return chr;
    }

    // A real Equip loads item data the tests do not have; this one runs the real code over its own fields.
    private static Equip equip(int itemId, short position, int watk, int str) {
        Equip e = mock(Equip.class, CALLS_REAL_METHODS);
        doReturn(itemId).when(e).getItemId();
        e.setPosition(position);
        e.setWatk((short) watk);
        e.setStr((short) str);
        return e;
    }

    private static void put(Character bot, InventoryType tab, Equip e) {
        bot.getInventory(tab).addItemFromDB(e);
    }

    private boolean review(Character bot) {
        return BotGear.review(bot, facts, worn -> ATTACK);
    }

    @Test
    void aBetterWeaponGoesOnAndTheOldOneTakesItsBagSlot() {
        Character bot = bot(Job.BEGINNER, 8);
        know(SWORD, (short) -11, 0);
        know(BETTER_SWORD, (short) -11, 5);
        Equip held = equip(SWORD, (short) -11, 17, 0);
        Equip better = equip(BETTER_SWORD, (short) 3, 25, 0);
        put(bot, InventoryType.EQUIPPED, held);
        put(bot, InventoryType.EQUIP, better);

        assertTrue(review(bot));
        Inventory body = bot.getInventory(InventoryType.EQUIPPED);
        Inventory bag = bot.getInventory(InventoryType.EQUIP);
        assertSame(better, body.getItem((short) -11));
        assertSame(held, bag.getItem((short) 3));
        assertTrue(better.isWearing());

        assertFalse(review(bot), "nothing better left: no churn");
    }

    @Test
    void aBeginnerKeepsTheKindOfWeaponItHolds() {
        Character bot = bot(Job.BEGINNER, 8);
        know(SWORD, (short) -11, 0);
        know(BOW, (short) -11, 0);
        put(bot, InventoryType.EQUIPPED, equip(SWORD, (short) -11, 17, 0));
        put(bot, InventoryType.EQUIP, equip(BOW, (short) 1, 60, 0));
        assertFalse(review(bot));
    }

    @Test
    void aFirstJobKeepsTheKindItsBranchComesFrom() {
        // A 1st-job warrior holding a sword is on its way to Fighter or Page; a spear would turn it into a Spearman.
        Character bot = bot(Job.WARRIOR, 20);
        know(SWORD, (short) -11, 0);
        know(SPEAR, (short) -11, 10);
        know(BETTER_SWORD, (short) -11, 10);
        put(bot, InventoryType.EQUIPPED, equip(SWORD, (short) -11, 17, 0));
        Equip spear = equip(SPEAR, (short) 1, 60, 0);
        Equip sword = equip(BETTER_SWORD, (short) 2, 30, 0);
        put(bot, InventoryType.EQUIP, spear);
        put(bot, InventoryType.EQUIP, sword);

        assertTrue(review(bot));
        assertSame(sword, bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11));
        assertSame(spear, bot.getInventory(InventoryType.EQUIP).getItem((short) 1));
    }

    @Test
    void anOverallPutsThePantsInAFreeBagSlot() {
        Character bot = bot(Job.BEGINNER, 10);
        know(TOP, (short) -5, 0);
        know(PANTS, (short) -6, 0);
        know(OVERALL, (short) -5, 0);
        Equip top = equip(TOP, (short) -5, 0, 1);
        Equip pants = equip(PANTS, (short) -6, 0, 1);
        Equip overall = equip(OVERALL, (short) 1, 0, 6);
        put(bot, InventoryType.EQUIPPED, top);
        put(bot, InventoryType.EQUIPPED, pants);
        put(bot, InventoryType.EQUIP, overall);

        assertTrue(review(bot));
        Inventory body = bot.getInventory(InventoryType.EQUIPPED);
        Inventory bag = bot.getInventory(InventoryType.EQUIP);
        assertSame(overall, body.getItem((short) -5));
        assertNull(body.getItem((short) -6));
        assertSame(top, bag.getItem((short) 1));
        assertTrue(bag.list().contains(pants));
    }

    @Test
    void unknownGearIsNeverPutOn() {
        Character bot = bot(Job.BEGINNER, 10);
        know(SWORD, (short) -11, 0);
        put(bot, InventoryType.EQUIPPED, equip(SWORD, (short) -11, 17, 0));
        put(bot, InventoryType.EQUIP, equip(1302999, (short) 1, 99, 0));   // a custom sword: no item data
        assertFalse(review(bot));
    }

    @Test
    void aPieceThatLeftTheBagStopsTheMoves() {
        Character bot = bot(Job.BEGINNER, 8);
        know(SWORD, (short) -11, 0);
        know(BETTER_SWORD, (short) -11, 0);
        Equip held = equip(SWORD, (short) -11, 17, 0);
        Equip better = equip(BETTER_SWORD, (short) 3, 25, 0);
        put(bot, InventoryType.EQUIPPED, held);
        put(bot, InventoryType.EQUIP, better);
        // Sold between the review and the move.
        BotGearPlanner.Piece stale = new BotGearPlanner.Piece(better, BETTER_SWORD, (short) 3,
                data.get(BETTER_SWORD), 0, 0, 0, 0, 25, 0, 0);
        BotGearPlanner.Piece wornSword = new BotGearPlanner.Piece(held, SWORD, (short) -11, data.get(SWORD),
                0, 0, 0, 0, 17, 0, 0);
        bot.getInventory(InventoryType.EQUIP).removeSlot((short) 3);

        assertFalse(BotGear.apply(bot, Map.of((short) -11, wornSword), Map.of((short) -11, stale)));
        assertSame(held, bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11));
    }

    // 1.6.1 ticket 30 (REPORT item 2): a reborn level 1 wears what a level 1 can and keeps the rest, then re-gears.
    @Test
    void aRebornLevelOneWearsWhatItCanKeepsTheRestAndRegearsAsItLevels() {
        Character bot = bot(Job.BEGINNER, 1);
        int bigSword = 1302056;
        int bigTop = 1040111;
        data.put(bigSword, new Known((short) -11, 150, 0, 300, 0, 0, 0, 0));   // a level 150 sword
        data.put(bigTop, new Known((short) -5, 150, 0, 0, 0, 0, 0, 0));
        know(SWORD, (short) -11, 0);
        Equip wornSword = equip(bigSword, (short) -11, 120, 10);
        Equip wornTop = equip(bigTop, (short) -5, 0, 8);
        Equip oldSword = equip(SWORD, (short) 4, 17, 0);
        put(bot, InventoryType.EQUIPPED, wornSword);
        put(bot, InventoryType.EQUIPPED, wornTop);
        put(bot, InventoryType.EQUIP, oldSword);

        assertTrue(BotGear.afterRebirth(bot, facts, worn -> ATTACK) == 2, "both level 150 pieces came off");
        Inventory body = bot.getInventory(InventoryType.EQUIPPED);
        Inventory bag = bot.getInventory(InventoryType.EQUIP);
        assertSame(oldSword, body.getItem((short) -11), "the sword a level 1 can hold went on");
        assertNull(body.getItem((short) -5));
        assertTrue(bag.list().contains(wornSword) && bag.list().contains(wornTop), "kept in its bag");

        // it levels: once its level and stats allow, the old pieces go back on
        bot.setLevel(150);
        assertTrue(review(bot));
        assertSame(wornTop, body.getItem((short) -5), "the top fits at 150");
        assertSame(oldSword, body.getItem((short) -11), "STR 300 not met yet: the big sword waits");
        bot.applyBotBuild(400, bot.getDex(), bot.getInt(), bot.getLuk(), 0, bot.getRemainingSps(), bot.getMaxHp(), bot.getMaxMp());
        assertTrue(review(bot));
        assertSame(wornSword, body.getItem((short) -11));
        assertSame(wornTop, body.getItem((short) -5));
    }

    @Test
    void aRebornBotKeepsOnWhatItStillFitsAndWhatTheDataDoesNotKnow() {
        Character bot = bot(Job.BEGINNER, 1);
        int custom = 1002999;   // a MapleRoot custom the item data has no entry for
        know(SWORD, (short) -11, 0);
        Equip sword = equip(SWORD, (short) -11, 17, 0);
        Equip hat = equip(custom, (short) -1, 0, 0);
        put(bot, InventoryType.EQUIPPED, sword);
        put(bot, InventoryType.EQUIPPED, hat);
        assertEquals(0, BotGear.afterRebirth(bot, facts, worn -> ATTACK));
        assertSame(sword, bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11));
        assertSame(hat, bot.getInventory(InventoryType.EQUIPPED).getItem((short) -1));
    }
}
