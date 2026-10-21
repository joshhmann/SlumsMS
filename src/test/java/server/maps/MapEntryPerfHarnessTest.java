package server.maps;

import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map-entry perf harness (1.4.2). Builds the maps the 24 h soak found slow and measures one real player's entry:
 * packets and bytes sent during MapleMap.addPlayer (the burst that lands with SET_FIELD), packets that follow from
 * the deferred placement and over how many 100 ms steps, and the server time of addPlayer. Then a bot's entry into
 * a busy map (the work every bot warp costs the server). Prints a table; run it alone with
 * {@code mvn -q -o test -Dtest=MapEntryPerfHarnessTest}. Timings are medians over repeated entries on this machine;
 * the packet counts are exact.
 */
class MapEntryPerfHarnessTest {

    @BeforeAll
    static void init() throws Exception {
        MapEntryHarness.initItemProvider();
    }

    record Row(String name, boolean trickle, int syncPackets, long syncBytes, int laterPackets, int steps,
               double addPlayerUs) {
        @Override
        public String toString() {
            return String.format("%-44s %-7s %6d pk %8.1f KB | +%5d pk over %2d x 100 ms | addPlayer %7.1f us",
                    name, trickle ? "after" : "before", syncPackets, syncBytes / 1024.0, laterPackets, steps, addPlayerUs);
        }
    }

    interface Setup {
        void build(MapEntryHarness h);
    }

    static Row measure(String name, boolean trickle, Setup setup) {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            h.trickle(trickle);
            setup.build(h);
            Character me = h.player(0);
            // warm up, then time repeated entries
            int reps = 40;
            double[] us = new double[reps];
            int syncPk = 0, laterPk = 0, steps = 0;
            long syncB = 0;
            for (int i = -10; i < reps; i++) {
                h.sent.clear();
                long ns = h.enter(me);
                int s = h.scheduler.drain();
                if (i >= 0) {
                    us[i] = ns / 1000.0;
                }
                syncPk = h.sync().size();
                syncB = MapEntryHarness.bytes(h.sync());
                laterPk = h.later().size();
                steps = s;
                h.leave(me);
            }
            Arrays.sort(us);
            return new Row(name, trickle, syncPk, syncB, laterPk, steps, us[reps / 2]);
        }
    }

    /** A bot leaving and entering a map with this many characters and one real player; median us per entry. */
    static double botEntryUs(int bots) {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            h.bots(bots, 3000);
            Character me = h.player(0);
            h.enter(me);
            h.scheduler.drain();
            Character mover = h.bots.get(bots / 2);
            int reps = 400;
            double[] us = new double[reps];
            for (int i = -100; i < reps; i++) {
                h.map.removePlayer(mover);
                long t0 = System.nanoTime();
                h.map.addPlayer(mover);
                long ns = System.nanoTime() - t0;
                if (i >= 0) {
                    us[i] = ns / 1000.0;
                }
            }
            h.scheduler.drain();
            Arrays.sort(us);
            return us[reps / 2];
        }
    }

    @Test
    void harness() {
        Setup town = h -> h.bots(78, 3000);                       // Henesys at the peak: 78 on screen
        Setup hunting = h -> { h.bots(4, 2400); h.drops(450, 2400); };  // a field next to town: drops at the 450 cap
        Setup marketLeak = h -> { h.bots(50, 2000); h.reactors(9000); }; // Henesys Market after 24 h with the gacha leak
        Setup marketFixed = h -> h.bots(50, 2000);                // the same without the leaked boxes

        StringBuilder out = new StringBuilder("\n== map entry harness (USE_MAXRANGE on, entrant at x=0) ==\n");
        Row[] rows = {
                measure("town, 78 bots over 3000 px", false, town),
                measure("town, 78 bots over 3000 px", true, town),
                measure("hunting field, 4 bots + 450 drops / 2400 px", false, hunting),
                measure("hunting field, 4 bots + 450 drops / 2400 px", true, hunting),
                measure("market, 50 bots + 9000 leaked gacha boxes", false, marketLeak),
                measure("market, 50 bots (gacha boxes retired)", true, marketFixed),
        };
        for (Row r : rows) {
            out.append(r).append('\n');
        }
        double botEntry = botEntryUs(78);
        out.append(String.format("bot entering a map with 78 characters + 1 player: %.1f us median%n", botEntry));
        System.out.println(out);

        // the burst shrinks, nothing is lost
        assertTrue(rows[1].syncPackets() < rows[0].syncPackets());
        assertEquals(rows[0].syncPackets(), rows[1].syncPackets() + rows[1].laterPackets());
        assertTrue(rows[3].syncPackets() < rows[2].syncPackets());
        assertEquals(rows[2].syncPackets(), rows[3].syncPackets() + rows[3].laterPackets());
        assertTrue(rows[3].steps() <= MapEntryPlacement.MAX_STEPS);
        assertTrue(rows[5].syncPackets() + rows[5].laterPackets() < rows[4].syncPackets() / 100);
    }
}
