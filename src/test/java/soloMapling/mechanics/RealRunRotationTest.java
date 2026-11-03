package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.RealRunPool.Admission;
import soloMapling.mechanics.RealRunPool.Origin;
import soloMapling.mechanics.RealRunPool.RunKey;

/**
 * Rotation: a free place goes to the stalest content and tier that has asked lately, so every row gets a real pass
 * eventually. The ordering itself is a pure function ({@link RealRunPool#stalest}) tested without a clock; the pool tests
 * drive a fake clock only to say who has asked and when.
 */
class RealRunRotationTest {

    private final AtomicInteger cap = new AtomicInteger(1);
    private final AtomicLong now = new AtomicLong(10_000_000L);
    private final RealRunPool pool = new RealRunPool(cap::get, now::get);

    private static final Content KPQ = KpqStageDataTest.kpq();
    private static final Content OPQ = MechanicsRuns.contentFor("OrbisPQ");
    private static final Content LPQ = MechanicsRuns.contentFor("LudiPQ");

    private Admission ask(Content c) {
        return pool.admit(Origin.CONTENT_LOBBY, c, Tier.NORMAL, 0);
    }

    // ---- the order, with no clock ----------------------------------------------------------------------------

    @Test
    void theStalestIsTheOldestLastRealAndANeverRealKeyIsOlderThanAnyPass() {
        RunKey a = new RunKey("a", Tier.NORMAL), b = new RunKey("b", Tier.NORMAL), c = new RunKey("c", Tier.NORMAL);
        Map<RunKey, Long> last = Map.of(a, 500L, b, 100L);

        assertEquals(c, RealRunPool.stalest(List.of(a, b, c), k -> last.getOrDefault(k, 0L)), "never real is the stalest");
        assertEquals(b, RealRunPool.stalest(List.of(a, b), k -> last.getOrDefault(k, 0L)));
        assertEquals(a, RealRunPool.stalest(List.of(a), k -> last.getOrDefault(k, 0L)));
        assertNull(RealRunPool.stalest(List.of(), k -> 0L));
    }

    @Test
    void aTieBreaksOnContentIdThenTierWhateverTheOrderOfArrival() {
        RunKey zebra = new RunKey("zebra", Tier.NORMAL);
        RunKey apple = new RunKey("apple", Tier.HARD);
        RunKey appleNormal = new RunKey("apple", Tier.NORMAL);
        RunKey appleMaster = new RunKey("apple", Tier.MASTER);

        for (List<RunKey> order : List.of(List.of(zebra, apple, appleNormal, appleMaster),
                List.of(appleMaster, appleNormal, apple, zebra), List.of(apple, zebra, appleMaster, appleNormal))) {
            assertEquals(appleNormal, RealRunPool.stalest(order, k -> 7L), "content id, then tier: " + order);
        }
        assertEquals(apple, RealRunPool.stalest(List.of(zebra, apple, appleMaster), k -> 7L), "HARD before MASTER");
    }

    // ---- the pool ---------------------------------------------------------------------------------------------

    @Test
    void aFreePlaceGoesToTheStalestAskerAndTheOtherWaitsForItsTurn() {
        Admission first = ask(KPQ);
        assertTrue(first.granted() && first.leased(), "nobody else wants: kpq takes the place");
        assertFalse(ask(OPQ).granted(), "full");
        now.addAndGet(1_000);
        first.release(); // the place is free; OPQ has asked and has never been real, KPQ was real a moment ago

        Admission kpqAgain = ask(KPQ);

        assertFalse(kpqAgain.granted(), "kpq was real a moment ago: opq waited longer");
        assertTrue(kpqAgain.byRotation());
        assertTrue(kpqAgain.why().contains("opq has waited longer for a real run (last real never)"), kpqAgain.why());
        assertEquals(0, pool.held(), "and the place is still free for the one it is kept for");

        Admission opq = ask(OPQ);
        assertTrue(opq.granted() && opq.leased(), "opq, the stalest, gets it");
        assertFalse(ask(KPQ).byRotation(), "full again: refused because none is free, not by rotation");
    }

    @Test
    void everyRowGetsARealPassInTurn() {
        List<Content> rows = List.of(KPQ, OPQ, LPQ);
        List<String> passes = new java.util.ArrayList<>();
        for (int round = 0; round < 9; round++) {
            Admission got = null;
            Content granted = null;
            for (Content c : rows) { // a lobby tick: the rows ask in a fixed order, and a refusal moves on to the next row
                Admission a = ask(c);
                if (a.granted()) {
                    got = a;
                    granted = c;
                    break;
                }
            }
            assertTrue(got != null, "a free place is always given to somebody: round " + round);
            passes.add(granted.id());
            for (Content c : rows) { // the run plays for minutes; every other row asks each tick and is refused: full
                if (c != granted) {
                    now.addAndGet(60_000);
                    assertFalse(ask(c).granted());
                }
            }
            now.addAndGet(60_000);
            got.release();
        }
        assertEquals(3, new java.util.HashSet<>(passes).size(), "all three rows were real: " + passes);
        for (int i = 3; i < passes.size(); i++) {
            assertEquals(passes.get(i - 3), passes.get(i), "and the order repeats, nobody is skipped twice: " + passes);
        }
    }

    @Test
    void anAskThatIsOldEnoughNoLongerBlocksAnyone() {
        cap.set(0);
        ask(KPQ); // kpq asks while no place is free, so it is waiting for one (and wins a tie: "kpq" < "opq")...
        cap.set(1);
        Admission opq = ask(OPQ);
        assertFalse(opq.granted(), "within the window kpq still counts as waiting");
        assertTrue(opq.byRotation());

        now.addAndGet(RealRunPool.WANT_WINDOW_MS + 1); // ...and never comes back (its channel is busy, say)

        assertTrue(ask(OPQ).granted(), "an ask older than the window is not waiting any more");
    }

    @Test
    void aHumansOrAGmsRunIsARealPassForItsRow() {
        // kpq asks while no place is free (a human is in the way, say) and so is waiting; opq has just had a real pass
        pool.notePass("opq", Tier.NORMAL);
        now.addAndGet(5_000);
        cap.set(0);
        ask(KPQ);
        cap.set(1);
        Admission opq = ask(OPQ);
        assertFalse(opq.granted(), "opq just had a real pass (a human played it): kpq is staler");
        assertTrue(opq.byRotation());
        assertTrue(ask(KPQ).granted());
        assertEquals(now.get(), pool.lastReal(new RunKey("kpq", Tier.NORMAL)));
        assertTrue(pool.lastReal(new RunKey("opq", Tier.NORMAL)) > 0);
        assertEquals(-1, pool.lastReal(new RunKey("lpq", Tier.NORMAL)));
    }

    @Test
    void tiersOfOneContentRotateAsTheirOwnKeys() {
        Admission normal = pool.admit(Origin.KPQ_LOBBY, KPQ, Tier.NORMAL, 0);
        assertTrue(normal.granted());
        assertFalse(pool.admit(Origin.KPQ_LOBBY, KPQ, Tier.HARD, 0).granted());
        normal.release();

        assertFalse(pool.admit(Origin.KPQ_LOBBY, KPQ, Tier.NORMAL, 0).granted(), "hard has never been real: it goes first");
        assertTrue(pool.admit(Origin.KPQ_LOBBY, KPQ, Tier.HARD, 0).granted());
    }

    @Test
    void anUnlimitedPoolTracksNothingAndRotatesNothing() {
        cap.set(-1);
        pool.notePass("kpq", Tier.NORMAL);
        for (int i = 0; i < 20; i++) {
            Admission a = ask(i % 2 == 0 ? KPQ : OPQ);
            assertTrue(a.granted() && !a.leased() && !a.byRotation());
        }
        assertEquals(-1, pool.lastReal(new RunKey("kpq", Tier.NORMAL)), "no pass is remembered while it does not count");
        assertEquals(0, pool.held());
    }

    @Test
    void aQuotaOfZeroNeverGrantsWhoeverIsStalest() {
        cap.set(0);
        Admission a = ask(KPQ);
        assertFalse(a.granted());
        assertFalse(a.byRotation(), "none is free: that is the reason, not rotation");
        assertTrue(a.why().contains("admits none"));
    }
}
