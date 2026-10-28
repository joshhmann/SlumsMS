package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.volatility.MarketEvents.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 volatility: market events change the right rate, expire, stay inert when off, and come about N a week. */
class MarketEventsTest {

    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final List<String> notices = new ArrayList<>();

    MarketEvents events(MarketConfig c) {
        MarketEvents m = MarketEvents.forTest(c, now::get, notices::add);
        MarketEvents.useForTest(m);
        return m;
    }

    @AfterEach
    void tearDown() {
        MarketEvents.useForTest(null);
    }

    static MarketConfig off() {
        MarketConfig d = MarketConfig.allOn();
        return new MarketConfig(false, d.traderCount(), d.minActMs(), d.maxActMs(), false, d.eventsPerWeek(),
                d.announcePct(), d.leafRushMultiplier(), d.rootyBonusLeaves(), false, d.rumourGapMs(), d.fakePct(),
                d.megaphonePct(), d.actOnRumourPct());
    }

    @Test
    void leafRushTriplesTheLeafChanceForTwoHoursThenItIsBack() {
        MarketEvents m = events(MarketConfig.allOn());
        assertEquals(500, MarketEvents.globalDropChance(4001126, 500));
        m.start(Kind.LEAF_RUSH, 0, true, now.get());
        assertEquals(1500, MarketEvents.globalDropChance(4001126, 500), "x3");
        assertEquals(500, MarketEvents.globalDropChance(4000999, 500), "only the leaf");
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("Leaf Rush") && notices.get(0).contains("3 times"), notices.get(0));
        now.addAndGet(2 * 3_600_000L - 1);
        assertEquals(1500, MarketEvents.globalDropChance(4001126, 500));
        now.addAndGet(1);
        assertEquals(500, MarketEvents.globalDropChance(4001126, 500), "expired after 2 h");
        assertTrue(m.active(now.get()).isEmpty());
        assertEquals("[Cody] Leaf Rush is over.", notices.get(1));
    }

    @Test
    void leafBlightStopsLeavesForADayAndRushAndBlightEndEachOther() {
        MarketEvents m = events(MarketConfig.allOn());
        m.start(Kind.LEAF_BLIGHT, 0, false, now.get());
        assertEquals(0, MarketEvents.globalDropChance(4001126, 500));
        assertTrue(notices.isEmpty(), "a secret event says nothing");
        m.start(Kind.LEAF_RUSH, 30, false, now.get());
        assertFalse(m.isActive(Kind.LEAF_BLIGHT, now.get()), "the rush ended the blight");
        assertEquals(1500, MarketEvents.globalDropChance(4001126, 500));
        now.addAndGet(30 * 60_000L);
        m.start(Kind.LEAF_BLIGHT, 0, false, now.get());
        now.addAndGet(24 * 3_600_000L - 1);
        assertEquals(0, MarketEvents.globalDropChance(4001126, 500));
        now.addAndGet(1);
        assertEquals(500, MarketEvents.globalDropChance(4001126, 500), "24 h");
    }

    @Test
    void rootysBonusOpensTheLeafSinkForADay() {
        MarketEvents m = events(MarketConfig.allOn());
        assertEquals(0, MarketEvents.rootyBonusLeaves());
        m.start(Kind.ROOTY_BONUS, 0, true, now.get());
        assertEquals(40, MarketEvents.rootyBonusLeaves());
        assertEquals(1, MarketEvents.pressure(Kind.ROOTY_BONUS, "LEAF"));
        assertTrue(MarketEvents.pressure(Kind.ROOTY_BONUS, "CHAOS") < 0);
        now.addAndGet(24 * 3_600_000L);
        assertEquals(0, MarketEvents.rootyBonusLeaves());
    }

    @Test
    void offMeansNothingChanges() {
        MarketEvents m = events(off());
        assertNull(m.start(Kind.LEAF_RUSH, 0, true, now.get()));
        assertEquals(500, MarketEvents.globalDropChance(4001126, 500));
        assertNull(m.maybeRandom(now.get(), 30_000, new Random(1)));
        assertTrue(notices.isEmpty());
        MarketEvents.useForTest(null);
        assertEquals(500, MarketEvents.globalDropChance(4001126, 500), "no instance: unchanged");
    }

    @Test
    void secretEventsPushOnlyTradersWhoKnow() {
        MarketEvents m = events(MarketConfig.allOn());
        m.start(Kind.LEAF_BLIGHT, 0, false, now.get());
        assertEquals(0, m.pressure("LEAF", now.get(), true), "secret: traders do not know");
        assertEquals(1, m.pressure("LEAF", now.get(), false));
    }

    @Test
    void randomEventsComeAboutTwiceAWeek() {
        MarketEvents m = events(MarketConfig.allOn());
        Random rng = new Random(42);
        int started = 0, announced = 0;
        long tick = 30_000, weeks = 52;
        for (long t = 0; t < weeks * MarketEvents.WEEK_MS; t += tick) {
            now.addAndGet(tick);
            MarketEvents.Active a = m.maybeRandom(now.get(), tick, rng);
            if (a != null) {
                started++;
                announced += a.announced() ? 1 : 0;
            }
        }
        double perWeek = (double) started / weeks;
        assertTrue(perWeek > 1.4 && perWeek < 2.6, "about 2 a week: " + perWeek);
        double pct = 100.0 * announced / started;
        assertTrue(pct > 40 && pct < 80, "about 60% announced: " + pct);
    }

    @Test
    void namesParse() {
        assertEquals(Kind.LEAF_RUSH, Kind.parse("rush"));
        assertEquals(Kind.LEAF_BLIGHT, Kind.parse("Leaf Blight"));
        assertEquals(Kind.ROOTY_BONUS, Kind.parse("rooty"));
        assertNull(Kind.parse("chaos"));
    }
}
