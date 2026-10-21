package server.monsterbook;

import org.junit.jupiter.api.Test;
import tools.Pair;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropSearchServiceTest {

    private static final int SNAIL = 100100;      // carded, not a boss
    private static final int MUSHMOM = 6130101;   // carded boss
    private static final int NO_CARD = 9999999;   // drops things, has no card
    private static final int SHELL = 4000019;     // Snail Shell
    private static final int RED_POTION = 2000000;
    private static final int UNNAMED = 4031999;

    /** Test source: counts how often the tables are read, so caching can be asserted. */
    private static class FakeSource implements DropSearchService.Source {
        final AtomicInteger dropReads = new AtomicInteger();
        final List<DropSearchService.DropRow> rows = new ArrayList<>(List.of(
                new DropSearchService.DropRow(SNAIL, SHELL, 600_000),
                new DropSearchService.DropRow(SNAIL, RED_POTION, 40_000),
                new DropSearchService.DropRow(SNAIL, RED_POTION, 90_000),   // second quest row, better chance
                new DropSearchService.DropRow(SNAIL, UNNAMED, 1_000),
                new DropSearchService.DropRow(MUSHMOM, RED_POTION, 200_000),
                new DropSearchService.DropRow(NO_CARD, RED_POTION, 999_999),
                new DropSearchService.DropRow(NO_CARD, 4000000, 500_000)));
        boolean fail;

        @Override
        public List<DropSearchService.DropRow> drops() throws SQLException {
            dropReads.incrementAndGet();
            if (fail) {
                throw new SQLException("down");
            }
            return rows;
        }

        @Override
        public Map<Integer, Integer> cardByMob() {
            return Map.of(SNAIL, 2380000, MUSHMOM, 2388000);
        }

        @Override
        public List<Pair<Integer, String>> itemNames() {
            return List.of(new Pair<>(SHELL, "Snail Shell"), new Pair<>(RED_POTION, "Red Potion"),
                    new Pair<>(4000000, "Blue Snail Shell"), new Pair<>(2000001, "Orange Potion"),
                    new Pair<>(UNNAMED, "NO-NAME"));
        }

        @Override
        public boolean isBoss(int mobId) {
            return mobId == MUSHMOM;
        }
    }

    private static DropSearchService.Rates rates(int drop, int boss, double item) {
        return new DropSearchService.Rates() {
            @Override
            public int dropRate() {
                return drop;
            }

            @Override
            public int bossDropRate() {
                return boss;
            }

            @Override
            public double itemRate(int itemId) {
                return item;
            }
        };
    }

    @Test
    void dropTableUsesLiveRatesKeepsTheBestQuestRowAndSkipsUnnamedItems() {
        DropSearchService s = new DropSearchService(new FakeSource());
        LinkedHashMap<Integer, Integer> t = s.mobDropChances(rates(2, 1, 1.0), SNAIL);
        assertEquals(List.of(SHELL, RED_POTION), new ArrayList<>(t.keySet()));   // best first
        assertEquals(1_000_000, t.get(SHELL));        // 600k x2 clamps to certain
        assertEquals(180_000, t.get(RED_POTION));     // the 90k row, not the 40k one
    }

    @Test
    void bossesRollAtTheBossRateAndItemBuffsMultiply() {
        DropSearchService s = new DropSearchService(new FakeSource());
        assertEquals(400_000, s.mobDropChances(rates(5, 2, 1.0), MUSHMOM).get(RED_POTION));
        assertEquals(600_000, s.mobDropChances(rates(5, 2, 1.5), MUSHMOM).get(RED_POTION));
    }

    @Test
    void ppmIsClampedToOneInAMillionAndCertainty() {
        assertEquals(1, DropSearchService.ppm(0, 1, 1.0));
        assertEquals(1_000_000, DropSearchService.ppm(999_999, 3, 1.0));
        assertEquals(1_000_000, DropSearchService.ppm(Integer.MAX_VALUE, Integer.MAX_VALUE, 2.0));
    }

    @Test
    void droppersAreCardedMobsOnlyEachWithItsOwnRate() {
        DropSearchService s = new DropSearchService(new FakeSource());
        LinkedHashMap<Integer, Integer> d = s.itemDroppers(rates(2, 1, 1.0), RED_POTION);
        assertEquals(List.of(MUSHMOM, SNAIL), new ArrayList<>(d.keySet()));
        assertEquals(200_000, d.get(MUSHMOM));   // boss rate 1
        assertEquals(180_000, d.get(SNAIL));     // normal rate 2, best row
    }

    @Test
    void itemSearchIsCaseInsensitiveRankedAndLimitedToItemsCardedMobsDrop() {
        DropSearchService s = new DropSearchService(new FakeSource());
        // "Blue Snail Shell" only drops from a card-less mob; "Orange Potion" drops from nothing
        assertArrayEquals(new int[]{SHELL}, s.findBookItems("snail"));
        assertArrayEquals(new int[]{RED_POTION}, s.findBookItems(" POTION "));
        assertArrayEquals(new int[]{SHELL}, s.findBookItems("Snail Shell"));
        assertArrayEquals(new int[0], s.findBookItems("sh"));     // under the minimum
        assertArrayEquals(new int[0], s.findBookItems(null));
    }

    @Test
    void theTablesAreReadOnceUntilAReload() {
        FakeSource src = new FakeSource();
        DropSearchService s = new DropSearchService(src);
        for (int i = 0; i < 50; i++) {
            s.findBookItems("snail");
            s.mobDropChances(rates(1, 1, 1.0), SNAIL);
            s.itemDroppers(rates(1, 1, 1.0), RED_POTION);
        }
        assertEquals(1, src.dropReads.get());

        src.rows.add(new DropSearchService.DropRow(SNAIL, 2000001, 10_000));
        assertTrue(s.findBookItems("orange").length == 0);   // cached until the reload
        s.clearCaches();
        assertArrayEquals(new int[]{2000001}, s.findBookItems("orange"));
        assertEquals(2, src.dropReads.get());
    }

    @Test
    void aFailedReadIsNotCachedSoTheNextQueryRetries() {
        FakeSource src = new FakeSource();
        src.fail = true;
        DropSearchService s = new DropSearchService(src);
        assertTrue(s.mobDropChances(rates(1, 1, 1.0), SNAIL).isEmpty());
        src.fail = false;
        assertEquals(Set.of(SHELL, RED_POTION), s.mobDropChances(rates(1, 1, 1.0), SNAIL).keySet());
    }

    @Test
    void droppersAreCappedAtTwoHundredBestFirst() {
        FakeSource src = new FakeSource();
        Map<Integer, Integer> cards = new java.util.HashMap<>();
        src.rows.clear();
        for (int i = 0; i < 250; i++) {
            src.rows.add(new DropSearchService.DropRow(1_000_000 + i, SHELL, 1_000 + i));
            cards.put(1_000_000 + i, 2380000 + i);
        }
        DropSearchService s = new DropSearchService(new FakeSource() {
            {
                rows.clear();
                rows.addAll(src.rows);
            }

            @Override
            public Map<Integer, Integer> cardByMob() {
                return cards;
            }
        });
        LinkedHashMap<Integer, Integer> d = s.itemDroppers(rates(1, 1, 1.0), SHELL);
        assertEquals(DropSearchService.MAX_RESULTS, d.size());
        assertEquals(1_000_249, d.keySet().iterator().next());
    }
}
