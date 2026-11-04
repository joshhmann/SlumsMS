package soloMapling.Economy.exchange.volatility;

import soloMapling.Economy.exchange.AssetRegistry.Asset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.MonsterDropEntry;
import server.life.MonsterGlobalDropEntry;
import server.life.MonsterInformationProvider;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * b261: a trader bot's off-screen hunt walked every {@code drop_data} row of every mob on its map, plus the map's
 * global rows, on every loot tick - thousands of bots over hundreds of maps. The rows and the boss flags do not change
 * between drop-data reloads, so {@link ScrollDropChances} keeps the summed chance per scroll slot and
 * {@link MarketVolatility#scrollChancePerKill} applies the per-call parts (the world's drop rates, the mob count).
 * These check the table against the rows, that the rows are read once, that a reload rebuilds it, that the answer is
 * the one the tick-by-tick walk gave, and what it costs against doing that walk every tick.
 */
class ScrollDropChancesTest {

    static final int CHAOS = Asset.CHAOS.itemId();
    static final int WS = Asset.WS.itemId();
    static final int NOT_A_SCROLL = 4000000;
    static final int MAP = 100000000;
    static final int PLAIN = 100100;
    static final int BOSS = 100101;

    // mockStatic on MonsterInformationProvider runs its static init, which queries drop_data_global
    @BeforeAll
    static void initProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            Class.forName("server.life.MonsterInformationProvider");
        }
    }

    private MockedStatic<MonsterInformationProvider> providerMock;
    private MonsterInformationProvider drops;
    private AtomicLong version;

    @BeforeEach
    void stubProvider() {
        version = new AtomicLong(7);
        drops = mock(MonsterInformationProvider.class);
        providerMock = mockStatic(MonsterInformationProvider.class);
        providerMock.when(MonsterInformationProvider::getInstance).thenReturn(drops);
        providerMock.when(MonsterInformationProvider::dropDataVersion).thenAnswer(call -> version.get());

        when(drops.retrieveDrop(PLAIN)).thenReturn(List.of(
                new MonsterDropEntry(CHAOS, 1200, 1, 1, (short) 0),
                new MonsterDropEntry(WS, 300, 1, 1, (short) 0),
                new MonsterDropEntry(NOT_A_SCROLL, 500, 1, 1, (short) 0)));
        when(drops.retrieveDrop(BOSS)).thenReturn(List.of(
                new MonsterDropEntry(CHAOS, 100, 1, 1, (short) 0),
                new MonsterDropEntry(CHAOS, 99_999, 1, 1, (short) 7)));      // a quest drop: not a find
        when(drops.isBoss(BOSS)).thenReturn(true);
        when(drops.getRelevantGlobalDrops(MAP)).thenReturn(List.of(
                new MonsterGlobalDropEntry(CHAOS, 1200, (byte) -1, 1, 1, (short) 0),
                new MonsterGlobalDropEntry(WS, 600, (byte) -1, 1, 1, (short) 0),
                new MonsterGlobalDropEntry(NOT_A_SCROLL, 100, (byte) -1, 1, 1, (short) 0)));
    }

    @AfterEach
    void closeProvider() {
        providerMock.close();
    }

    private ScrollDropChances chances() {
        return new ScrollDropChances(new int[]{CHAOS, WS});
    }

    @Test
    void theTableHoldsTheSumsOfTheMobsOwnRows() {
        ScrollDropChances chances = chances();
        assertEquals(MarketTraders.SCROLL_IDS.size(), chances.slots(), "one column per scroll the traders find");

        ScrollDropChances.Look look = chances.look(MAP, List.of(PLAIN, BOSS));
        assertFalse(look.mobs[0].boss);
        assertArrayEquals(new double[]{1200, 300}, look.mobs[0].chance, "chance per kill, before the rates and share");

        assertTrue(look.mobs[1].boss, "the boss drop rate applies to it");
        assertArrayEquals(new double[]{100, 0}, look.mobs[1].chance, "its quest row is not a find");

        assertArrayEquals(new double[]{1200, 600}, look.global, "the map's global rows");
    }

    @Test
    void theRowsAreReadOncePerMobAndMap() {
        ScrollDropChances chances = chances();
        for (int tick = 0; tick < 100; tick++) {
            chances.look(MAP, List.of(PLAIN, BOSS));
        }

        verify(drops, times(1)).retrieveDrop(PLAIN);
        verify(drops, times(1)).retrieveDrop(BOSS);
        verify(drops, times(1)).isBoss(BOSS);
        verify(drops, times(1)).getRelevantGlobalDrops(MAP);
    }

    @Test
    void aDropDataReloadRebuildsTheTable() {
        ScrollDropChances chances = chances();
        assertArrayEquals(new double[]{1200, 300}, chances.look(MAP, List.of(PLAIN)).mobs[0].chance);

        when(drops.retrieveDrop(PLAIN)).thenReturn(List.of(
                new MonsterDropEntry(WS, 4000, 1, 1, (short) 0)));
        version.incrementAndGet();      // !reloaddrops

        assertArrayEquals(new double[]{0, 4000}, chances.look(MAP, List.of(PLAIN)).mobs[0].chance, "the reloaded rows");
        verify(drops, times(2)).retrieveDrop(PLAIN);
    }

    @Test
    void aClearedTableAnswersOnTheNextLook() {
        ScrollDropChances chances = chances();
        chances.look(MAP, List.of(PLAIN));
        chances.clear();
        chances.look(MAP, List.of(PLAIN));

        verify(drops, times(2)).retrieveDrop(PLAIN);
    }

    @Test
    void aTickReReadsNothingOnceTheTableIsWarm() {
        List<Integer> mobs = List.of(PLAIN, BOSS);
        ScrollDropChances chances = chances();
        for (int tick = 0; tick < 100; tick++) {
            MarketVolatility.scrollChancePerKill(mobs, MAP, 1, 2, chances);     // the bots' path, 100 ticks
        }

        verify(drops, times(1)).retrieveDrop(PLAIN);
        verify(drops, times(1)).retrieveDrop(BOSS);
        verify(drops, times(1)).getRelevantGlobalDrops(MAP);
    }

    /** The tick-by-tick walk this replaced, row by row, for the equality and timing checks. */
    private static double[] workItOutEveryTick(List<Integer> mobs, int mapId, int dropRate, int bossRate) {
        double[] perKill = new double[]{0, 0};
        double share = 1.0 / mobs.size();
        MonsterInformationProvider provider = MonsterInformationProvider.getInstance();
        for (int slot = 0; slot < perKill.length; slot++) {
            int itemId = slot == 0 ? CHAOS : WS;
            for (int mobId : mobs) {
                boolean boss;
                try {
                    boss = provider.isBoss(mobId);
                } catch (RuntimeException e) {
                    boss = false;
                }
                int rate = boss ? bossRate : dropRate;
                for (MonsterDropEntry drop : provider.retrieveDrop(mobId)) {
                    if (drop.itemId == itemId && drop.chance > 0 && drop.questid == 0) {
                        perKill[slot] += (double) drop.chance * rate * share;
                    }
                }
            }
            for (MonsterGlobalDropEntry drop : provider.getRelevantGlobalDrops(mapId)) {
                if (drop.itemId == itemId && drop.chance > 0) {
                    perKill[slot] += drop.chance;
                }
            }
        }
        return perKill;
    }

    @Test
    void theAnswerIsTheOneTheTickByTickWalkGave() {
        List<Integer> mobs = List.of(PLAIN, BOSS);

        // an even share of 1/2 times whole-number rates is exact arithmetic, so the two agree to the bit
        assertArrayEquals(workItOutEveryTick(mobs, MAP, 1, 2),
                MarketVolatility.scrollChancePerKill(mobs, MAP, 1, 2, chances()), "rate 1, boss rate 2");

        // a 1/7 share does not divide evenly: the sums differ only in the order the products are added
        List<Integer> seven = new ArrayList<>(mobs);
        for (int i = 0; i < 5; i++) {
            seven.add(100200 + i);
        }
        double[] expected = workItOutEveryTick(seven, MAP, 3, 4);
        double[] cached = MarketVolatility.scrollChancePerKill(seven, MAP, 3, 4, chances());
        for (int slot = 0; slot < expected.length; slot++) {
            assertEquals(expected[slot], cached[slot], Math.max(1e-9, expected[slot] * 1e-12),
                    "slot " + slot + ": the same chance per kill");
        }
    }

    @Test
    void theTableCostsAFractionOfWorkingItOutEveryTick() {
        // a map like the ones bots hunt: ten mobs, twelve drop rows each
        List<Integer> mobs = new ArrayList<>();
        int rowsPerMob = 23;      // TEST's drop_data: 30 731 rows over 1 364 mobs
        for (int mob = 0; mob < 10; mob++) {
            int mobId = 200_000 + mob;
            mobs.add(mobId);
            List<MonsterDropEntry> own = new ArrayList<>();
            own.add(new MonsterDropEntry(CHAOS, 1200, 1, 1, (short) 0));
            own.add(new MonsterDropEntry(WS, 1200, 1, 1, (short) 0));
            for (int i = 2; i < rowsPerMob; i++) {
                own.add(new MonsterDropEntry(4000000 + i, 900, 1, 1, (short) 0));
            }
            when(drops.retrieveDrop(mobId)).thenReturn(own);
        }
        when(drops.getRelevantGlobalDrops(MAP)).thenReturn(List.of(
                new MonsterGlobalDropEntry(CHAOS, 1200, (byte) -1, 1, 1, (short) 0)));

        ScrollDropChances warm = chances();
        MarketVolatility.scrollChancePerKill(mobs, MAP, 1, 2, warm);     // the first tick builds the table

        int ticks = 20_000;
        double[] sink = new double[2];
        long cachedNanos = Long.MAX_VALUE, walkedNanos = Long.MAX_VALUE, checkNanos = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {       // best of five
            long started = System.nanoTime();
            for (int tick = 0; tick < ticks; tick++) {
                sink = MarketVolatility.scrollChancePerKill(mobs, MAP, 1, 2, warm);
            }
            cachedNanos = Math.min(cachedNanos, System.nanoTime() - started);

            started = System.nanoTime();
            for (int tick = 0; tick < ticks; tick++) {
                sink = workItOutEveryTick(mobs, MAP, 1, 2);
            }
            walkedNanos = Math.min(walkedNanos, System.nanoTime() - started);

            // the table's one provider call per tick, so the report can take the mocked call out of its figure
            started = System.nanoTime();
            for (int tick = 0; tick < ticks; tick++) {
                if (MonsterInformationProvider.dropDataVersion() != 7) {
                    sink[0] = -1;
                }
            }
            checkNanos = Math.min(checkNanos, System.nanoTime() - started);
        }

        System.out.printf("b261: %d ticks over %d mobs x %d rows: table %.0f ns/tick (of which the mocked reload"
                        + " check %.0f ns), walk %.0f ns/tick (%.1fx)%n",
                ticks, mobs.size(), rowsPerMob, cachedNanos / (double) ticks, checkNanos / (double) ticks,
                walkedNanos / (double) ticks, walkedNanos / (double) cachedNanos);
        assertTrue(sink[0] > 0);
        assertTrue(cachedNanos * 1.5 < walkedNanos,
                "the table is at least a third quicker: " + cachedNanos + " ns vs " + walkedNanos + " ns");
    }
}
