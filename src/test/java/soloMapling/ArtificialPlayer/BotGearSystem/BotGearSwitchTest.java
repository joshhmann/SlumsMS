package soloMapling.ArtificialPlayer.BotGearSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Piece;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Rules;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Wearer;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

// Ticket 162 part 1: -Dslums.gear.optimizer picks the optimizer; the offscreen memo skips without allocating; timing.
class BotGearSwitchTest {

    private static final Valuer ATTACK = (weapon, str, dex, int_, luk, watk, matk) -> watk * 10.0 + str;

    private final Map<Integer, Known> data = new HashMap<>();

    @AfterEach
    void reset() {
        System.clearProperty("slums.gear.optimizer");
        GearMemo.clear();
    }

    private static Character bot(int str) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_000_000 + 1620);
        chr.setJob(Job.FIGHTER);
        chr.setLevel(50);
        chr.updateStrDexIntLuk(str);
        return chr;
    }

    private static Equip equip(int itemId, short position, int watk, int str) {
        Equip e = mock(Equip.class, CALLS_REAL_METHODS);
        doReturn(itemId).when(e).getItemId();
        e.setPosition(position);
        e.setWatk((short) watk);
        e.setStr((short) str);
        return e;
    }

    // An Equip with no mock behind it (mock calls allocate) and no item data (its constructor reads it).
    private static Equip plainEquip(int itemId, short position, int watk, int str) {
        try {
            Equip e = new org.objenesis.ObjenesisStd().newInstance(Equip.class);
            java.lang.reflect.Field id = client.inventory.Item.class.getDeclaredField("id");
            id.setAccessible(true);
            id.setInt(e, itemId);
            e.setPosition(position);
            e.setWatk((short) watk);
            e.setStr((short) str);
            return e;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long touch(List<Equip> equips) {
        long h = 0;
        for (Equip e : equips) {
            h += e.getItemId() + e.getStr() + e.getDex() + e.getInt() + e.getLuk() + e.getWatk() + e.getMatk()
                    + e.getAcc() + e.getUpgradeSlots();
        }
        return h;
    }

    private void know(int itemId, short slot, int reqStr) {
        data.put(itemId, new Known(slot, 10, 0, reqStr, 0, 0, 0, 0));
    }

    // The two-helper chain of BotGearOptimizerTest on a real bot: only the Pareto optimizer dresses it.
    private Character chainedBot() {
        Character bot = bot(55);
        know(1302000, (short) -11, 0);
        know(1302001, (short) -11, 65);
        know(1082000, (short) -8, 0);
        know(1082001, (short) -8, 0);
        know(1072000, (short) -7, 0);
        know(1072001, (short) -7, 0);
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip(1302000, (short) -11, 20, 0));
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip(1082000, (short) -8, 2, 0));
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(equip(1072000, (short) -7, 1, 0));
        bot.getInventory(InventoryType.EQUIP).addItemFromDB(equip(1302001, (short) 1, 40, 0));
        bot.getInventory(InventoryType.EQUIP).addItemFromDB(equip(1082001, (short) 2, 0, 5));
        bot.getInventory(InventoryType.EQUIP).addItemFromDB(equip(1072001, (short) 3, 0, 5));
        return bot;
    }

    @Test
    void theDefaultClimbLeavesTheChainAndParetoWearsIt() {
        Character climbBot = chainedBot();
        assertEquals("climb", BotGear.optimizer());
        assertFalse(BotGear.review(climbBot, data::get, worn -> ATTACK), "live behaviour: the climb misses it");

        System.setProperty("slums.gear.optimizer", "pareto");
        Character paretoBot = chainedBot();
        assertTrue(BotGear.review(paretoBot, data::get, worn -> ATTACK));
        assertEquals(1302001, paretoBot.getInventory(InventoryType.EQUIPPED).getItem((short) -11).getItemId());
        assertEquals(1082001, paretoBot.getInventory(InventoryType.EQUIPPED).getItem((short) -8).getItemId());
        assertEquals(1072001, paretoBot.getInventory(InventoryType.EQUIPPED).getItem((short) -7).getItemId());
    }

    @Test
    void sharedScoringAnswersWhatTheBotWouldWearWithoutMovingAnything() {
        Character bot = bot(55);
        know(1302000, (short) -11, 0);
        know(1302005, (short) -11, 0);
        Equip held = equip(1302000, (short) -11, 20, 0);
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(held);
        Equip offered = equip(1302005, (short) 7, 30, 0);   // in someone else's bag
        assertEquals(100.0, BotGear.upgradeValue(bot, offered, data::get, worn -> ATTACK), 1e-9);
        assertEquals(Map.of((short) -11, offered), BotGear.bestSet(bot, List.of(offered), data::get, worn -> ATTACK));
        assertSame(held, bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11), "nothing moved");
        Equip worse = equip(1302005, (short) 8, 10, 0);
        assertEquals(0.0, BotGear.upgradeValue(bot, worse, data::get, worn -> ATTACK), 1e-9);

        // The switch drives the scoring too: the chain is only an upgrade under the Pareto optimizer.
        Character chained = chainedBot();
        Equip sword = (Equip) chained.getInventory(InventoryType.EQUIP).getItem((short) 1);
        assertEquals(0.0, BotGear.upgradeValue(chained, sword, data::get, worn -> ATTACK), 1e-9);
        System.setProperty("slums.gear.optimizer", "pareto");
        assertTrue(BotGear.upgradeValue(chained, sword, data::get, worn -> ATTACK) > 0);
    }

    @Test
    void theOffscreenMemoSkipsAnUnchangedBotWithoutAllocating() {
        Character bot = bot(55);
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(plainEquip(1302000, (short) -11, 20, 0));
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(plainEquip(1082000, (short) -8, 2, 0));
        for (short slot = 1; slot <= 6; slot++) {
            bot.getInventory(InventoryType.EQUIP).addItemFromDB(plainEquip(1072000 + slot, slot, 0, slot));
        }
        long sig = GearMemo.signature(bot);
        GearMemo.remember(bot.getId(), sig);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        for (int i = 0; i < 20_000; i++) {   // warm up: let the JIT settle
            GearMemo.unchanged(bot.getId(), GearMemo.signature(bot));
        }
        long before = mx.getThreadAllocatedBytes(id);
        boolean all = true;
        for (int i = 0; i < 10_000; i++) {
            all &= GearMemo.unchanged(bot.getId(), GearMemo.signature(bot));
        }
        long allocated = mx.getThreadAllocatedBytes(id) - before;
        // Control: the same Equip getter calls alone. Zero in a clean JVM; but once any test has mocked Equip,
        // Mockito's inline instrumentation makes every Equip getter allocate, so only the memo's own share counts.
        List<Equip> equips = new ArrayList<>();
        for (client.inventory.Item it : bot.getInventory(InventoryType.EQUIPPED).list()) equips.add((Equip) it);
        for (client.inventory.Item it : bot.getInventory(InventoryType.EQUIP).list()) equips.add((Equip) it);
        long sink = 0;
        for (int i = 0; i < 20_000; i++) sink += touch(equips);
        long beforeControl = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 10_000; i++) sink += touch(equips);
        long control = mx.getThreadAllocatedBytes(id) - beforeControl;
        assertTrue(all && sink != 1);
        assertTrue(allocated - control < 8_192, "a skipped review allocated " + allocated + " bytes over 10,000 calls"
                + " (equip getters alone: " + control + ")");

        bot.getInventory(InventoryType.EQUIP).addItemFromDB(plainEquip(1072099, (short) 9, 0, 1));
        assertFalse(GearMemo.unchanged(bot.getId(), GearMemo.signature(bot)), "a new equip is seen");
    }

    @Test
    void theMemoTableKeepsEveryBot() {
        for (int i = 0; i < 5_000; i++) {
            GearMemo.remember(1_000_000_000 + i, i * 31L);
        }
        for (int i = 0; i < 5_000; i += 7) {
            GearMemo.forget(1_000_000_000 + i);
        }
        for (int i = 0; i < 5_000; i++) {
            assertEquals(i % 7 != 0, GearMemo.unchanged(1_000_000_000 + i, i * 31L), "bot " + i);
        }
    }

    // ---- timing: per-call cost of each optimizer on a typical and a heavy bag (printed for REPORT-162) ----

    private static final Valuer FORMULA = (weapon, str, dex, int_, luk, watk, matk) ->
            (4.0 * (60 + str) + (20 + dex)) * (watk + 1) / 100.0;

    private record Case(Wearer wearer, Map<Short, Piece> worn, List<Piece> owned) {
    }

    private static Case randomCase(Random rng, int carried) {
        short[] slots = {-1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11};
        Map<Short, Piece> worn = new LinkedHashMap<>();
        int pos = 1;
        for (short s : slots) {
            if (s == -10 || rng.nextInt(4) == 0) {
                continue;
            }
            int id = s == -11 ? 1302000 : 1000000 + (-s) * 10000;
            worn.put(s, new Piece(new Object(), id, s, new Known(s, 10, 0, 0, 0, 0, 0, 0), rng.nextInt(4),
                    rng.nextInt(4), 0, 0, s == -11 ? 30 : rng.nextInt(2), 0, rng.nextInt(20), rng.nextInt(4)));
        }
        List<Piece> owned = new ArrayList<>();
        for (int i = 0; i < carried; i++) {
            short s = slots[rng.nextInt(slots.length)];
            int id = s == -11 ? 1302001 + i : 1000000 + (-s) * 10000 + 1 + i;
            owned.add(new Piece(new Object(), id, (short) pos++, new Known(s, rng.nextInt(60), 0, rng.nextInt(70),
                    rng.nextInt(40), 0, 0, 0), rng.nextInt(7), rng.nextInt(7), rng.nextInt(3), rng.nextInt(3),
                    s == -11 ? 20 + rng.nextInt(30) : rng.nextInt(4), 0, rng.nextInt(30), rng.nextInt(6)));
        }
        return new Case(new Wearer(1, 60, 0, 50 + rng.nextInt(20), 20 + rng.nextInt(20), 4, 4, 3), worn, owned);
    }

    private static double microsPerCall(boolean pareto, List<Case> cases, int rounds) {
        Rules any = new Rules(id -> true, id -> !BotGearPlanner.twoHanded(id));
        long t0 = System.nanoTime();
        int calls = 0;
        for (int r = 0; r < rounds; r++) {
            for (Case c : cases) {
                if (pareto) {
                    BotGearOptimizer.plan(c.wearer(), c.worn(), c.owned(), FORMULA, any);
                } else {
                    BotGearPlanner.plan(c.wearer(), c.worn(), c.owned(), FORMULA, any);
                }
                calls++;
            }
        }
        return (System.nanoTime() - t0) / 1000.0 / calls;
    }

    @Test
    void perCallCost() {
        Random rng = new Random(1620);
        List<Case> typical = new ArrayList<>();
        List<Case> heavy = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            typical.add(randomCase(rng, 6));
            heavy.add(randomCase(rng, 40));
        }
        microsPerCall(true, typical, 20);   // warm up
        microsPerCall(false, typical, 20);
        microsPerCall(true, heavy, 4);
        double climbTypical = microsPerCall(false, typical, 40);
        double paretoTypical = microsPerCall(true, typical, 40);
        double climbHeavy = microsPerCall(false, heavy, 8);
        double paretoHeavy = microsPerCall(true, heavy, 8);
        System.out.printf("[gear-timing] climb typical %.1f us, pareto typical %.1f us, climb heavy %.1f us, "
                + "pareto heavy %.1f us per call%n", climbTypical, paretoTypical, climbHeavy, paretoHeavy);
        // Event-driven, not per tick: generous ceilings so a slow CI machine passes.
        assertTrue(paretoTypical < 20_000, "typical pareto call " + paretoTypical + " us");
        assertTrue(paretoHeavy < 250_000, "heavy pareto call " + paretoHeavy + " us");
    }

    /*
     * Audit 163 #12/#13 (b119): one gear review on a heavy bag. BotMaker.junk asks upgradeValue for every carried
     * equip at a town stop; each used to be a full Pareto plan (bag size x plan cost). A review of 40 carried equips
     * must stay bounded: < 50 ms on this box (generous; printed for the report).
     */
    @Test
    void aHeavyBagReviewIsBounded() {
        System.setProperty("slums.gear.optimizer", "pareto");
        Random rng = new Random(163);
        short[] slots = {-1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11};
        Character bot = bot(70);
        for (short s : slots) {
            if (s == -10) continue;
            int id = s == -11 ? 1302000 : 1000000 + (-s) * 10000;
            data.put(id, new Known(s, 10, 0, 0, 0, 0, 0, 0));
            bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(plainEquip(id, s, s == -11 ? 30 : rng.nextInt(2), rng.nextInt(4)));
        }
        List<Equip> carried = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            short s = slots[rng.nextInt(slots.length)];
            if (s == -10) s = -5;
            int id = s == -11 ? 1302001 + i : 1000000 + (-s) * 10000 + 1 + i;
            data.put(id, new Known(s, rng.nextInt(50), 0, rng.nextInt(70), rng.nextInt(40), 0, 0, 0));
            Equip e = plainEquip(id, (short) (i + 1), s == -11 ? 20 + rng.nextInt(30) : rng.nextInt(4), rng.nextInt(7));
            e.setDex((short) rng.nextInt(7));
            e.setAcc((short) rng.nextInt(6));
            bot.getInventory(InventoryType.EQUIP).addItemFromDB(e);
            carried.add(e);
        }
        java.util.function.Function<Map<Short, Piece>, Valuer> valuers = worn -> FORMULA;
        double warm = 0;
        for (int r = 0; r < 3; r++) {
            BotGear.clearPlanCache();
            for (Equip e : carried) warm += BotGear.upgradeValue(bot, e, data::get, valuers);
        }
        int reviews = 5;
        long t0 = System.nanoTime();
        double sum = 0;
        for (int r = 0; r < reviews; r++) {
            BotGear.clearPlanCache(); // each review cold: a town stop after the bag changed
            for (Equip e : carried) sum += BotGear.upgradeValue(bot, e, data::get, valuers);
        }
        double msPerReview = (System.nanoTime() - t0) / 1e6 / reviews;
        System.out.printf("[gear-timing] heavy-bag review (40 carried equips, pareto): %.1f ms%n", msPerReview);
        assertTrue(warm >= 0 && sum >= 0);
        assertTrue(msPerReview < 50, "one review took " + msPerReview + " ms");
    }
}
