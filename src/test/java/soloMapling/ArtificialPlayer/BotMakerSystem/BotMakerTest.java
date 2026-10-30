package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.EconomyMeters;
import soloMapling.Economy.EconomyStore;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 112 (owner 2026-10-30): bots and alts batch-make monster crystals and disassemble junk through the real Maker
 * path, behind economy.yaml maker.botBatch (off by default), within a daily cap per character and one for the world.
 * One implementation for both; the read-only plan ranks craftable upgrades by attack gain.
 */
class BotMakerTest {

    private final AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.ofEpochDay(200));

    @BeforeEach
    void setUp() {
        EconomyMeters.useForTest(new EconomyStore.Memory(), Runnable::run, day::get);
        BotMaker.useForTest(day::get);
    }

    @AfterEach
    void tearDown() {
        EconomyConfig.useForTest(null);
        EconomyMeters.useForTest(null, null, null);
        BotMaker.useForTest(null);
    }

    private static void config(boolean on, int perBot, int world) {
        EconomyConfig.useForTest(Map.of("maker.botBatch", String.valueOf(on), "maker.perBotDaily", String.valueOf(perBot),
                "maker.worldDaily", String.valueOf(world)));
    }

    private static Character bot(int id) {
        Character chr = Character.getDefault(new BotClient(1, 1));
        try {
            java.lang.reflect.Field f = Character.class.getDeclaredField("id");
            f.setAccessible(true);
            f.setInt(chr, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        chr.setName("Bot" + id);
        return chr;
    }

    private static List<BotMaker.Op> ops(int n, AtomicInteger calls, short status) {
        List<BotMaker.Op> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(() -> {
                calls.incrementAndGet();
                return status;
            });
        }
        return out;
    }

    @Test
    void theSwitchIsOffByDefaultAndOffMeansNothingHappens() throws Exception {
        EconomyConfig.useForTest(Map.of());
        assertFalse(BotMaker.enabled());
        AtomicInteger calls = new AtomicInteger();
        BotMaker.Result r = BotMaker.run(bot(1_000_000_001), ops(3, calls, (short) 0), 99);
        assertEquals(0, r.made());
        assertEquals(0, calls.get());
        assertFalse(BotMaker.townBatch(bot(1_000_000_001)).made() > 0);

        // and the shipped economy.yaml keeps it off, with both caps set
        Path file = Path.of(EconomyConfig.FILE);
        Map<String, Object> shipped;
        try (var in = new StringReader(Files.readString(file))) {
            shipped = parse(in);
        }
        assertEquals("false", String.valueOf(shipped.get("maker.botBatch")));
        assertTrue(Integer.parseInt(String.valueOf(shipped.get("maker.perBotDaily"))) > 0);
        assertTrue(Integer.parseInt(String.valueOf(shipped.get("maker.worldDaily"))) > 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(StringReader in) throws Exception {
        var m = EconomyConfig.class.getDeclaredMethod("parse", java.io.Reader.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(null, in);
    }

    @Test
    void eachCharacterStopsAtItsDailyCapUntilTomorrow() {
        config(true, 3, 100);
        Character a = bot(1_000_000_001);
        AtomicInteger calls = new AtomicInteger();
        BotMaker.Result r = BotMaker.run(a, ops(5, calls, (short) 0), 99);
        assertEquals(3, r.made());
        assertEquals(3, calls.get(), "no operation past the cap");
        assertTrue(r.stoppedBy().startsWith("daily limit"), r.stoppedBy());
        assertEquals(0, BotMaker.run(a, ops(1, calls, (short) 0), 99).made(), "still capped today");
        assertEquals(3, EconomyMeters.get(BotMaker.WORLD_METER));

        day.set(day.get().plusDays(1));
        assertEquals(2, BotMaker.run(a, ops(2, calls, (short) 0), 99).made(), "a new day");
    }

    @Test
    void theWorldCapHoldsAcrossBots() {
        config(true, 10, 4);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(3, BotMaker.run(bot(1_000_000_001), ops(3, calls, (short) 0), 99).made());
        BotMaker.Result second = BotMaker.run(bot(1_000_000_002), ops(3, calls, (short) 0), 99);
        assertEquals(1, second.made());
        assertTrue(second.stoppedBy().startsWith("world daily limit"), second.stoppedBy());
        assertEquals(4, EconomyMeters.get(BotMaker.WORLD_METER));
        assertEquals(4, calls.get());
    }

    @Test
    void aFailedOperationStopsTheBatchAndUsesNoCap() {
        config(true, 10, 10);
        Character a = bot(1_000_000_001);
        AtomicInteger calls = new AtomicInteger();
        BotMaker.Result r = BotMaker.run(a, ops(3, calls, (short) 1), 99);
        assertEquals(0, r.made());
        assertEquals("out of materials", r.stoppedBy());
        assertEquals(1, calls.get());
        assertEquals(0, EconomyMeters.get(BotMaker.WORLD_METER), "the world unit is given back");
        assertEquals(0, BotMaker.doneToday(a.getId()));
    }

    @Test
    void theCallerCanAskForFewer() {
        config(true, 10, 10);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(2, BotMaker.run(bot(1_000_000_001), ops(5, calls, (short) 0), 2).made());
    }

    @Test
    void populationBotsLearnMakerAtMorensLevels() {
        assertEquals(0, BotMaker.makerLevelFor(44));
        assertEquals(1, BotMaker.makerLevelFor(45));
        assertEquals(1, BotMaker.makerLevelFor(74));
        assertEquals(2, BotMaker.makerLevelFor(75));
        assertEquals(2, BotMaker.makerLevelFor(104));
        assertEquals(3, BotMaker.makerLevelFor(105));
        assertEquals(3, BotMaker.makerLevelFor(200));
    }

    @Test
    void aPopulationBotUsesItsLedgerAndAPlayerItsClient() {
        Character b = bot(1_000_000_001);
        assertTrue(BotMaker.hands(b) instanceof BotMakerHands);
        client.Client c = org.mockito.Mockito.mock(client.Client.class);
        Character player = Character.getDefault(c);
        org.mockito.Mockito.when(c.getPlayer()).thenReturn(player);
        assertNotNull(BotMaker.hands(player));
        assertFalse(BotMaker.hands(player) instanceof BotMakerHands);
        Character stranger = Character.getDefault(c);   // a character whose client holds someone else
        assertEquals(null, BotMaker.hands(stranger));
    }

    @Test
    void botHandsTakeAcrossStacksAndGiveThroughTheLedger() {
        Character b = bot(1_000_000_001);
        var etc = b.getInventory(InventoryType.ETC);
        etc.addItem(new Item(4000000, (short) 0, (short) 60));
        etc.addItem(new Item(4000000, (short) 0, (short) 60));
        BotMakerHands h = new BotMakerHands(b);
        assertEquals(120, h.count(4000000));
        h.take(4000000, 100);
        assertEquals(20, h.count(4000000));
        h.give(4260000, 1);
        assertEquals(1, h.count(4260000));
    }

    // --- the plan ---------------------------------------------------------------------------------------------------

    private static MakerPlan.Recipe recipe(int id, int level, int maker, int meso, int... items) {
        List<int[]> req = new ArrayList<>();
        for (int i = 0; i < items.length; i += 2) {
            req.add(new int[]{items[i], items[i + 1]});
        }
        return new MakerPlan.Recipe(id, level, maker, meso, req);
    }

    /* A stand-in equip (a real one reads the item data in its constructor). */
    private static Equip equip(int id) {
        Equip e = org.mockito.Mockito.mock(Equip.class);
        org.mockito.Mockito.when(e.getItemId()).thenReturn(id);
        return e;
    }

    @Test
    void thePlanListsWhatCanBeMadeNowThatIsAnUpgradeBestFirst() {
        Character chr = bot(1_000_000_001);
        chr.setLevel(80);
        chr.gainMeso(100_000, false);
        Map<Integer, Integer> held = Map.of(4011000, 10, 4260000, 5);
        Map<Integer, Double> gains = Map.of(1302001, 4.0, 1302002, 9.0, 1302003, 0.0, 1302004, 50.0, 1302005, 50.0,
                1302006, 50.0, 1302007, 7.0);
        List<MakerPlan.Recipe> recipes = List.of(
                recipe(1302001, 70, 1, 10_000, 4011000, 5),          // ok, +4
                recipe(1302002, 75, 2, 20_000, 4011000, 5, 4260000, 5), // ok, +9
                recipe(1302003, 70, 1, 10_000, 4011000, 1),          // no gain
                recipe(1302004, 85, 1, 10_000),                      // level too low
                recipe(1302005, 70, 3, 10_000),                      // Maker 3
                recipe(1302006, 70, 1, 500_000),                     // too dear
                recipe(1302007, 70, 1, 10_000, 4011000, 11));        // not enough ore
        List<MakerPlan.Entry> plan = MakerPlan.rank(chr, 2, recipes, id -> held.getOrDefault(id, 0),
                BotMakerTest::equip, id -> "Item" + id, (c, e) -> gains.getOrDefault(e.getItemId(), 0.0), 10);
        assertEquals(List.of(1302002, 1302001), plan.stream().map(MakerPlan.Entry::itemId).toList());
        assertEquals(9.0, plan.get(0).gain());
        assertEquals(20_000, plan.get(0).mesoCost());
        assertEquals(1, MakerPlan.rank(chr, 2, recipes, id -> held.getOrDefault(id, 0), BotMakerTest::equip,
                id -> "x", (c, e) -> gains.getOrDefault(e.getItemId(), 0.0), 1).size(), "the limit");
        assertTrue(MakerPlan.lines(plan).get(0).startsWith("Item1302002 (+9.0 attack, 20,000 mesos)"));
    }

    @Test
    void thePlanNeverMovesAnything() {
        Character chr = bot(1_000_000_001);
        chr.setLevel(80);
        chr.gainMeso(100_000, false);
        chr.getInventory(InventoryType.ETC).addItem(new Item(4011000, (short) 0, (short) 10));
        MakerPlan.rank(chr, 3, List.of(recipe(1302001, 70, 1, 10_000, 4011000, 5)),
                id -> chr.getInventory(InventoryType.ETC).countById(id), BotMakerTest::equip, id -> "x",
                (c, e) -> 3.0, 5);
        assertEquals(10, chr.getInventory(InventoryType.ETC).countById(4011000));
        assertEquals(100_000, chr.getMeso());
    }

    @Test
    void reqJobMasksMatchTheJobNiche() {
        assertTrue(AttackGainScore.jobFits(0, 1));
        assertTrue(AttackGainScore.jobFits(1, 1));
        assertFalse(AttackGainScore.jobFits(1, 2));
        assertTrue(AttackGainScore.jobFits(2 | 8, 4));
        assertTrue(AttackGainScore.jobFits(16, 5));
        assertFalse(AttackGainScore.jobFits(16, 0));
    }
}
