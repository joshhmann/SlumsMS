package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.Push;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

/** 1.6.0 S9: per-bot outcome learning on Bartle's chart and the seven likes; bounded, decaying, crossing once. */
class LifeLearningTest {
    static final long DAY = 86_400_000L;
    static final long T0 = 20_000 * DAY; // a UTC midnight
    final AtomicLong clock = new AtomicLong(T0);

    @BeforeEach
    void setUp() {
        LifeLearning.resetForTest(clock::get);
    }

    @AfterEach
    void tearDown() {
        LifeLearning.resetForTest(null);
    }

    /** A run-only memory on a staged seed (like lr-1's bot). */
    static LifeMemory staged(double x, double y, double plasticity) {
        LifeMemory memory = new LifeMemory(4242, null);
        memory.stageSeed(new SocialProfile(x, y), plasticity);
        return memory;
    }

    static double drift(LifeMemory m) {
        double[] d = m.drift();
        return Math.hypot(d[0], d[1]);
    }

    @Test
    void driftStaysInsideThePlasticityCapAndTheDailyBudgetHolds() {
        LifeMemory m = staged(-.6, -.6, .6);
        double radius = SocialProfile.RADIUS_PER_PLASTICITY * .6;
        int moved = 0;
        for (int day = 0; day < 200; day++) {
            int today = 0;
            for (int i = 0; i < 20; i++) {
                double before = drift(m);
                long at = T0 + day * DAY + i * 60_000L;
                if (LifeLearning.apply(1, m, Push.KS, at, "test")) today++;
                assertTrue(drift(m) <= radius + 1e-9, "drift " + drift(m) + " past the cap " + radius);
                assertTrue(drift(m) >= 0 && Double.isFinite(before));
            }
            assertTrue(today <= LifeLearning.PER_KIND_PER_DAY, "day " + day + ": " + today + " KS pushes");
            moved += today;
        }
        assertTrue(moved > 0);
        // the routine cap is lower, and the day's total holds across kinds
        LifeMemory r = staged(.3, .3, .5);
        int grind = 0, all = 0;
        for (int i = 0; i < 40; i++) {
            if (LifeLearning.apply(2, r, Push.GRIND, T0 + i * 1000L, "test")) grind++;
        }
        for (Push p : List.of(Push.KS, Push.PARTY, Push.FAMED, Push.GIFTED, Push.BOSS)) {
            for (int i = 0; i < 10; i++) if (LifeLearning.apply(2, r, p, T0 + 50_000 + i, "test")) all++;
        }
        assertEquals(LifeLearning.ROUTINE_PER_DAY, grind);
        assertEquals(LifeLearning.TOTAL_PER_DAY - grind, all, "the day's total caps every kind together");
    }

    @Test
    void driftDecaysTowardTheSeedByThePullBackRate() {
        LifeMemory m = staged(-.5, -.5, .4);
        for (int i = 0; i < 6; i++) LifeLearning.apply(1, m, Push.KS, T0 + i, "test");
        double start = drift(m);
        assertTrue(start > 0);
        m.pullBackTo(T0 / DAY + 10);
        double expected = start * Math.pow(1 - m.pullBack(), 10);
        assertEquals(expected, drift(m), 1e-3, "ten days of pull-back");
        assertEquals(SocialProfile.pullBackOf(.4), m.pullBack(), 1e-12, "the stored S1 rate is the one applied");
        double[] was = m.drift();
        m.pullBackTo(T0 / DAY + 400); // months away: back near the seed (60 days at most at once), never past it
        double[] is = m.drift();
        assertTrue(drift(m) < start * .05, "nearly home: " + drift(m));
        assertTrue(Math.signum(is[0]) != -Math.signum(was[0]) && Math.signum(is[1]) != -Math.signum(was[1]),
                "the pull-back never overshoots the seed");
        assertFalse(m.pullBackTo(T0 / DAY + 400), "the same day twice does nothing");
    }

    @Test
    void likesLearnFromSurpriseAndStayBounded() {
        LifeMemory m = staged(-.4, -.4, .5);
        // a run of bad PQs, then a good one: it likes PQs (socialite, grinder) more than after the bad run
        for (int i = 0; i < 4; i++) LifeLearning.observe(1, m, Activity.PARTY_QUEST, false, T0 + i);
        double afterBad = m.delta().getOrDefault(LifeProfile.Archetype.SOCIALITE, 0.0);
        LifeLearning.observe(1, m, Activity.PARTY_QUEST, true, T0 + 10);
        double afterGood = m.delta().getOrDefault(LifeProfile.Archetype.SOCIALITE, 0.0);
        assertTrue(afterBad < 0, "bad PQs: likes them less");
        assertTrue(afterGood > afterBad, "a good PQ after bad ones: likes them more");
        // a failed restock trip when trips usually work: likes it (its grinder half) less
        LifeMemory r = staged(.4, .4, .5);
        for (int i = 0; i < 5; i++) LifeLearning.observe(2, r, Activity.RESTOCK, true, T0 + i);
        double ok = r.delta().getOrDefault(LifeProfile.Archetype.GRINDER, 0.0);
        LifeLearning.observe(2, r, Activity.RESTOCK, false, T0 + DAY);
        assertTrue(r.delta().getOrDefault(LifeProfile.Archetype.GRINDER, 0.0) < ok);
        // a thousand days of anything: every channel within +-MAX_CHANNEL_DELTA, and a sure thing stops moving
        LifeMemory s = staged(.5, .5, .6);
        for (int day = 0; day < 1000; day++) {
            for (int i = 0; i < 10; i++) {
                LifeLearning.observe(3, s, Activity.values()[(day + i) % Activity.values().length], (day * 7 + i) % 3 != 0,
                        T0 + day * DAY + i);
            }
            for (double v : s.delta().values()) assertTrue(Math.abs(v) <= LifeMemory.MAX_CHANNEL_DELTA + 1e-12);
        }
        LifeMemory sure = staged(.5, .5, .6);
        for (int i = 0; i < 400; i++) LifeLearning.observe(4, sure, Activity.GRIND, true, T0 + (i / 6) * DAY + i);
        double settled = sure.delta().getOrDefault(LifeProfile.Archetype.GRINDER, 0.0);
        assertTrue(settled < .05, "always grinding fine is no surprise: no creep toward grinder (" + settled + ")");
    }

    @Test
    void crossingFiresOnceWithHysteresisAndRedemptionComesBackTheSameWay() {
        LifeMemory m = staged(-.5, -.01, 1);
        List<LifeMemory.Crossing> seen = new ArrayList<>();
        // up the acting axis in .02 steps (FLEX at plasticity 1): .01, .03 stay; .05 crosses
        for (int i = 0; i < 6; i++) {
            m.push(Push.FLEX);
            var c = m.checkCrossing(LifeLearning.HYSTERESIS, T0 + i);
            if (c != null) seen.add(c);
            if (m.social().y() < LifeLearning.HYSTERESIS) assertNull(c, "inside the border band: no crossing");
            else assertEquals(1, seen.size(), "crossed at the first look past the band: " + m.social());
        }
        assertEquals(1, seen.size(), "crossed once: " + m.social());
        assertEquals(Type.SOCIALIZER, seen.getFirst().from());
        assertEquals(Type.KILLER, seen.getFirst().to());
        // jitter around the border: never flaps
        for (int i = 0; i < 50; i++) {
            m.push(i % 2 == 0 ? Push.CHAT : Push.FLEX);
            assertNull(m.checkCrossing(LifeLearning.HYSTERESIS, T0 + 10 + i), "flapped at " + m.social());
        }
        assertEquals(Type.KILLER, m.quadrant());
        // back down (CHAT): the point crosses below 0 first, but only past -0.05 is it home again
        LifeMemory.Crossing back = null;
        for (int i = 0; i < 40 && back == null; i++) {
            m.push(Push.CHAT);
            back = m.checkCrossing(LifeLearning.HYSTERESIS, T0 + 100 + i);
            if (back == null) assertTrue(m.social().y() > -LifeLearning.HYSTERESIS || m.social().x() >= 0);
        }
        assertNotNull(back);
        assertEquals(Type.SOCIALIZER, back.to());
        assertTrue(m.social().y() <= -LifeLearning.HYSTERESIS);
        assertEquals(2, m.crossings());
        var redemption = new LifeLearning.CrossingEvent(1, back, m.socialSeed().leading());
        assertTrue(redemption.redemption());
        assertEquals(LifeLearning.Arc.REDEEMED, redemption.arc());
    }

    @Test
    void thirtyStagedKsTurnAPlasticSocializerTowardKillerWithOneCrossing() {
        // lr-1's numbers, through the same path: six a day over five days, budget and pull-back included
        LifeMemory m = staged(-.30, -.20, .60);
        List<LifeLearning.CrossingEvent> told = new ArrayList<>();
        LifeLearning.onCrossing(told::add);
        double before = m.social().share(Type.KILLER);
        for (int i = 0; i < 30; i++) LifeLearning.apply(7, m, Push.KS, T0 + 30 * DAY - (29 - i) * 4 * 3_600_000L, "qa ks");
        assertTrue(m.social().share(Type.KILLER) > before);
        assertTrue(drift(m) <= SocialProfile.RADIUS_PER_PLASTICITY * .6 + 1e-9);
        assertEquals(Type.KILLER, m.quadrant());
        assertEquals(1, m.crossings());
        assertEquals(1, told.size(), "told once");
        assertEquals(LifeLearning.Arc.TURNED, told.getFirst().arc());
        assertEquals("SOCIALIZER->KILLER (turned)", told.getFirst().fact());
        assertFalse(LifeLearning.recentCrossings(0).isEmpty());
    }

    @Test
    void aSetInItsWaysBotNeverCrosses() {
        // the S1 promise: plasticity 0.05 caps the drift at 0.06, under any push storm
        LifeMemory m = staged(-.30, -.20, .05);
        for (int day = 0; day < 100; day++) {
            for (int i = 0; i < 10; i++) LifeLearning.apply(1, m, Push.KS, T0 + day * DAY + i, "test");
        }
        assertEquals(Type.SOCIALIZER, m.quadrant());
        assertEquals(0, m.crossings());
    }

    @Test
    void theLearningStateIsSavedAndReadBack() {
        LifeMemory m = staged(-.30, -.20, .60);
        for (int i = 0; i < 30; i++) LifeLearning.apply(7, m, Push.KS, T0 + i * 4 * 3_600_000L, "test");
        LifeLearning.observe(7, m, Activity.PARTY_QUEST, true, T0 + 5 * DAY);
        Map<String, Object> saved = m.save(T0 + 6 * DAY);
        LifeMemory back = new LifeMemory(4242, soloMapling.server.Json.parse(soloMapling.server.Json.write(saved)));
        assertTrue(back.restored());
        assertEquals(m.quadrant(), back.quadrant());
        assertEquals(m.crossings(), back.crossings());
        assertEquals(m.recentCrossings(), back.recentCrossings());
        assertEquals(m.learnDay(), back.learnDay());
        assertEquals(m.successRate(LifeProfile.Archetype.SOCIALITE), back.successRate(LifeProfile.Archetype.SOCIALITE), 1e-12);
        assertEquals(m.budgetToday(T0 + 5 * DAY), back.budgetToday(T0 + 5 * DAY));
        assertArrayEquals(m.drift(), back.drift(), 1e-12);
    }

    @Test
    void aMemorySavedBeforeS9StartsWhereItStandsWithoutACrossing() {
        // an S1-era save with a drift into another quadrant and no learn block: no crossing burst at deploy
        Map<String, Object> s1 = new java.util.LinkedHashMap<>(new LifeMemory(4242, null).save(T0));
        s1.remove("learn");
        Map<String, Object> seed = new java.util.LinkedHashMap<>();
        seed.put("bartle", Map.of("x", -.1, "y", -.1, "plasticity", .6, "pullBack", .05));
        seed.put("blend", ((Map<?, ?>) s1.get("seed")).get("blend"));
        s1.put("seed", seed);
        s1.put("delta", Map.of("bartle", Map.of("dx", 0.0, "dy", .3), "blend", Map.of()));
        LifeMemory m = new LifeMemory(4242, s1);
        assertEquals(Type.KILLER, m.social().leading());
        assertEquals(Type.KILLER, m.quadrant());
        assertNull(m.checkCrossing(LifeLearning.HYSTERESIS, T0));
    }

    @Test
    void affinityAndOutcomeTapsMapToTheTable() {
        assertEquals(Push.KSED, LifeLearning.affinityPush(AffinityEvent.KS));
        assertEquals(Push.KSED, LifeLearning.affinityPush(AffinityEvent.DROP_STEAL));
        assertEquals(Push.GIFTED, LifeLearning.affinityPush(AffinityEvent.GIFT));
        assertEquals(Push.HELPED, LifeLearning.affinityPush(AffinityEvent.HEAL));
        assertEquals(Push.PARTY, LifeLearning.affinityPush(AffinityEvent.PARTY));
        assertEquals(Push.ROAST, LifeLearning.affinityPush(AffinityEvent.RUDE_CHAT));
        assertNull(LifeLearning.affinityPush(AffinityEvent.SCAM), "S3's episode pushes SCAMMED: never twice");
        assertNull(LifeLearning.affinityPush(AffinityEvent.HEARSAY_BAD), "gossip alone never moves who a bot is");
        assertEquals(Push.PARTY, LifeLearning.outcomePush(Activity.PARTY_QUEST));
        assertNull(LifeLearning.outcomePush(Activity.RESTOCK));
        for (Activity a : Activity.values()) assertNotNull(LifeLearning.channelsOf(a));
        // KS toward Killer, gifts toward Socializer
        assertTrue(Push.KS.dx < 0 && Push.KS.dy > 0);
        assertTrue(Push.GIFTED.dx < 0 && Push.GIFTED.dy < 0);
    }

    @Test
    void theSweepReportsHealthAndPullsEveryMemoryBack() {
        LifeMemoryStore.useConnectionSourceForTest(() -> { throw new java.sql.SQLException("no db in tests"); });
        try {
            LifeMemory a = LifeMemoryStore.of(7001);
            a.stageSeed(new SocialProfile(-.3, -.2), .6);
            for (int i = 0; i < 6; i++) LifeLearning.apply(7001, a, Push.KS, T0 + i, "test");
            LifeMemoryStore.of(7002); // pristine: skipped, never dirtied
            double start = drift(a);
            Map<String, Object> health = LifeLearning.sweep(T0 + 3 * DAY);
            assertEquals(2, health.get("bots"));
            assertEquals(1, health.get("learned"));
            assertEquals(0, health.get("overCap"));
            assertTrue(((Number) health.get("maxDriftOfCap")).doubleValue() <= 1);
            assertTrue(drift(a) < start, "pulled back");
            assertTrue(LifeMemoryStore.peek(7002).pristine());
            Map<String, Object> report = LifeLearning.report(T0 + 3 * DAY);
            assertTrue(report.containsKey("crossingsLastDay") && report.containsKey("fads") && report.containsKey("trends"));
        } finally {
            LifeMemoryStore.useConnectionSourceForTest(null);
        }
    }
}
