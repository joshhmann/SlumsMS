package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotStore;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 slice S7: crushes before couples (bot with bot), the couple cap of about one per 150 bots, and a couple's
 * jealousy and breakups.
 */
class BotCrushesTest {

    static final int A = BotStore.BOT_ID_BASE + 31, B = BotStore.BOT_ID_BASE + 32, C = BotStore.BOT_ID_BASE + 33;
    static final int D = BotStore.BOT_ID_BASE + 34;
    static final long HOUR = TimeUnit.HOURS.toMillis(1), DAY = TimeUnit.DAYS.toMillis(1);

    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final Map<Integer, Character> online = new HashMap<>();
    final CoupleStore.Memory store = new CoupleStore.Memory();
    final MapleMap map = mock(MapleMap.class);

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get, id -> new double[]{.25, .25},
                (id, p) -> { }, online::get);
        BotCouples.useForTest(store, online::get);
        BotCouples.useRosterSizeForTest(() -> 3_000);
        BotCrushes.useForTest(online::get, id -> 0.25, bot -> true);
        BotWeddings.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotCrushes.useForTest(null, null, null);
        BotCouples.useRosterSizeForTest(null);
        BotCouples.useForTest(null, null);
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        System.clearProperty("solomapling.couples.maxCouples");
    }

    Character bot(int id, String name, int x) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getLevel()).thenReturn(40);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(100000000);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        online.put(id, c);
        return c;
    }

    // ── the cap ──

    @Test
    void aboutOneCouplePer150BotsNeverUnderTheOldFour() {
        assertEquals(20, BotCouples.capFor(3_000), "the owner's ~20 at 3,000");
        assertEquals(4, BotCouples.capFor(0), "before the roster is read");
        assertEquals(4, BotCouples.capFor(600));
        assertEquals(6, BotCouples.capFor(900));
        assertEquals(20, BotCouples.maxCouples());
        assertEquals(40, BotCrushes.maxCrushes(), "two crushes per couple slot");
        System.setProperty("solomapling.couples.maxCouples", "7");
        assertEquals(7, BotCouples.maxCouples(), "the property still wins");
    }

    // ── crushes ──

    @Test
    void aCrushIsOnePerBotNeverOnACoupledBotAndBounded() {
        long now = clock.get();
        assertNull(BotCrushes.start(A, A, false, now), "not on itself");
        assertNotNull(BotCrushes.start(A, B, false, now));
        assertNull(BotCrushes.start(A, C, false, now), "one crush at a time");
        assertTrue(BotCrushes.hasCrush(A));
        assertEquals(java.util.List.of(A), BotCrushes.admirersOf(B));
        BotCouples.form(C, D, false, now);
        assertNull(BotCrushes.start(B, C, false, now), "never on a bot in a couple");
        assertNull(BotCrushes.start(C, B, false, now), "a bot in a couple has no crush");

        BotCouples.useRosterSizeForTest(() -> 0); // cap 4 couples -> 8 crushes
        int made = 1;
        for (int i = 0; i < 20; i++) {
            if (BotCrushes.start(BotStore.BOT_ID_BASE + 100 + i, BotStore.BOT_ID_BASE + 200 + i, false, now) != null) {
                made++;
            }
        }
        assertEquals(8, made, "bounded by twice the couple cap");
        assertNotNull(BotCrushes.start(BotStore.BOT_ID_BASE + 300, B, true, now), "a staged (QA) crush is not counted");
    }

    @Test
    void aSocializerFallsMoreEasily() {
        assertTrue(BotCrushes.fallChance(0.8) > BotCrushes.fallChance(0.1));
        assertEquals(0.08 * 0.75, BotCrushes.fallChance(0.25), 1e-9);
    }

    @Test
    void aCrushRipensAfterTwelveHoursWhenReturnedAndFadesAfterThreeDays() {
        long now = clock.get();
        BotCrushes.Crush c = BotCrushes.start(A, B, false, now);
        assertFalse(BotCrushes.ripe(c, true, now + 11 * HOUR));
        assertTrue(BotCrushes.ripe(c, true, now + 12 * HOUR));
        assertFalse(BotCrushes.ripe(c, false, now + 12 * HOUR), "only when the crush likes it back");
        assertFalse(BotCrushes.returned(c));
        BotBonds.stage(A, B, AffinityState.FRIEND);
        assertTrue(BotCrushes.returned(c), "a friendship back counts");
        assertNull(BotCrushes.endReason(c, now + 2 * DAY));
        assertEquals("it faded", BotCrushes.endReason(c, now + 3 * DAY));
        BotBonds.stage(A, B, AffinityState.RIVAL);
        assertEquals("it turned sour", BotCrushes.endReason(c, now));
    }

    @Test
    void aRipeCrushBecomesACoupleAndTheCrushEnds() {
        bot(A, "Amy", 0);
        bot(B, "Bob", 20);
        BotBonds.stage(A, B, AffinityState.FRIEND);
        long now = clock.get();
        BotCrushes.start(A, B, true, now);
        assertTrue(BotCrushes.coupleNow(A, now).startsWith("not ripe"));
        BotCrushes.ripenNow(A);
        String made = BotCrushes.coupleNow(A, now);
        assertTrue(made.startsWith("couple #"), made);
        assertNotNull(BotCouples.coupleOf(A));
        assertFalse(BotCrushes.hasCrush(A), "a couple now, not a crush");
    }

    @Test
    void aCrushStandsNearSaysCheesyThingsAndIsJealousOfARivalByItsCrush() {
        Character a = bot(A, "Amy", 0);
        Character b = bot(B, "Bob", 500);
        long now = clock.get();
        BotCrushes.Crush c = BotCrushes.start(A, B, false, now);
        assertEquals("near", BotCrushes.signs(c, a, b, now, new Random(1)), "apart: it walks over");
        when(a.getPosition()).thenReturn(new Point(470, 0));
        assertNull(BotCrushes.signs(c, a, b, now + 60_000, new Random(1)), "signs are minutes apart");
        String sign = BotCrushes.signs(c, a, b, now + BotCrushes.SIGN_GAP_MS, new Random(1));
        assertTrue(sign == null || sign.equals("line"), sign);

        bot(C, "Cid", 520);
        BotBonds.stage(A, C, AffinityState.RIVAL);
        double before = BotBonds.score(A, C);
        assertEquals("jealous", BotCrushes.signs(c, a, b, now + 20 * 60_000, new Random(1)));
        assertTrue(BotBonds.score(A, C) < before, "the jealousy is a spat on their bond");
        assertNotEquals("jealous", BotCrushes.signs(c, a, b, now + 21 * 60_000, new Random(1)),
                "jealousy is a quarter hour apart");
    }

    @Test
    void anUnwatchedMapShowsNoSigns() {
        BotCrushes.useForTest(online::get, id -> 0.25, bot -> false);
        Character a = bot(A, "Amy", 0);
        Character b = bot(B, "Bob", 500);
        BotCrushes.Crush c = BotCrushes.start(A, B, false, clock.get());
        assertNull(BotCrushes.signs(c, a, b, clock.get(), new Random(1)));
    }

    // ── couple drama ──

    @Test
    void aPartnerIsJealousOfAnAdmirerAndASouredCoupleBreaksUpRarely() {
        Character a = bot(A, "Amy", 0);
        Character b = bot(B, "Bob", 20);
        bot(C, "Cid", 60);
        long now = clock.get();
        assertNotNull(BotCrushes.start(C, B, false, now)); // Cid likes Bob, who then pairs up with Amy
        BotCouples.Couple k = BotCouples.form(A, B, false, now);
        assertNotNull(k);
        assertNull(BotCrushes.endReason(BotCrushes.crushOf(C), now + HOUR), "a crush on a taken bot lingers");
        assertNull(BotCrushes.ripeOnline(now + DAY), "but never ripens into a couple");
        String jealous = BotCouples.jealousy(k, a, b, now, new Random(1), false);
        assertNotNull(jealous);
        assertTrue(jealous.startsWith("COUPLE JEALOUS Amy at Cid (likes Bob)"), jealous);
        assertNull(BotCouples.jealousy(k, a, b, now + 60_000, new Random(1), false), "not every minute");

        assertFalse(BotCouples.soured(k));
        BotBonds.stage(A, B, AffinityState.RIVAL);
        assertTrue(BotCouples.soured(k));
        String broke = BotCouples.breakupDrama(k, now);
        assertNotNull(broke);
        assertNull(BotCouples.coupleOf(A), "broken up");
        assertNotNull(soloMapling.ArtificialPlayer.BotDramaSystem.DramaNews.latest(B,
                soloMapling.ArtificialPlayer.BotDramaSystem.DramaNews.Kind.BREAKUP, now), "news for their friends");

        bot(D, "Dee", 0);
        BotCouples.Couple k2 = BotCouples.form(C, D, false, now);
        BotBonds.stage(C, D, AffinityState.RIVAL);
        assertNull(BotCouples.breakupDrama(k2, now + HOUR), "one breakup a day world-wide");
        assertNotNull(BotCouples.breakupDrama(k2, now + DAY));
    }
}
