package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 188 F9: Amos took the 500,000 mesos before the marriage was broken, and judged "ring still equipped" from a
 * flag that is only set at login. The fee is now taken after the break succeeded, and equipment is read from the
 * inventory. The fee itself is unchanged.
 */
class MarriageDivorceTest {

    private static final int RING = 1112803;

    public static final class FakeRing {
        public final boolean stale;

        FakeRing(boolean stale) {
            this.stale = stale;
        }

        public int getItemId() {
            return RING;
        }

        public boolean equipped() {
            return stale;
        }
    }

    /** What RingActionHandler.breakMarriageRing would do, chosen by the test. */
    public static final class Breaker {
        final MarriageNpcMocks.Pl player;
        boolean works = true;
        boolean throwsError;
        int calls;

        Breaker(MarriageNpcMocks.Pl player) {
            this.player = player;
        }

        public void breakMarriageRing(Object ignoredPlayer, int ringItemId) {
            calls++;
            if (throwsError) {
                throw new IllegalStateException("simulated break failure");
            }
            if (works) {
                player.partnerId = -1;
            }
        }
    }

    private static final class Married extends MarriageNpcMocks.Pl {
        final FakeRing ring;

        Married(MarriageNpcMocks.Log log, boolean staleEquippedFlag) {
            super(log, "A", 11, 22, new MarriageNpcMocks.Map0());
            this.ring = new FakeRing(staleEquippedFlag);
        }

        @Override
        public boolean isMarried() {
            return true;
        }

        @Override
        public Object getMarriageRing() {
            return ring;
        }
    }

    private static final class Run {
        final MarriageNpcMocks.Log log = new MarriageNpcMocks.Log();
        final Married player;
        final MarriageNpcMocks.Cm cm;
        final Breaker breaker;

        Run(boolean staleEquippedFlag) throws Exception {
            player = new Married(log, staleEquippedFlag);
            cm = new MarriageNpcMocks.Cm(log, player, null);
            breaker = new Breaker(player);
            player.items.put(RING, 1);
        }

        void divorce() throws Exception {
            Invocable amos = MarriageNpcMocks.load("9201004.js", cm);
            ScriptEngine engine = (ScriptEngine) amos;
            engine.put("fakeHandler", breaker);
            engine.eval("RingActionHandler = fakeHandler");  // stands in for the real RingActionHandler class
            amos.invokeFunction("start");              // status 0: the menu
            amos.invokeFunction("action", 1, 0, 3);    // "I want a divorce..."
            amos.invokeFunction("action", 1, 0, 0);    // yes
        }
    }

    @Test
    void theFeeIsTakenOnlyAfterTheMarriageIsBroken() throws Exception {
        Run r = new Run(false);
        r.divorce();

        assertEquals(1, r.breaker.calls);
        assertEquals(-1, r.player.partnerId);
        assertTrue(r.log.has("meso -500000"), "fee unchanged at 500,000: " + r.log.events);
        assertEquals(10_000_000 - 500_000, r.cm.meso);
        assertTrue(r.log.lastText.contains("divorced"), r.log.lastText);
    }

    @Test
    void aBreakThatDoesNotHappenCostsNothing() throws Exception {
        Run r = new Run(false);
        r.breaker.works = false; // breakMarriageRing was a no-op: the partner id is still set
        r.divorce();

        assertEquals(10_000_000, r.cm.meso, "no fee: " + r.log.events);
        assertFalse(r.log.lastText.contains("have divorced"), r.log.lastText);
        assertFalse(r.log.has("A gain " + RING + " -1"), "ring not removed: " + r.log.events);
    }

    @Test
    void aBreakThatThrowsCostsNothing() throws Exception {
        Run r = new Run(false);
        r.breaker.throwsError = true;
        r.divorce();

        assertEquals(10_000_000, r.cm.meso, "no fee: " + r.log.events);
        assertFalse(r.log.lastText.contains("have divorced"), r.log.lastText);
    }

    @Test
    void aRingUnequippedAfterLoginIsAccepted() throws Exception {
        Run r = new Run(true); // Ring.equipped() still says true: it is only refreshed at login
        r.divorce();

        assertEquals(1, r.breaker.calls, "the divorce ran: " + r.log.lastText);
        assertTrue(r.log.lastText.contains("divorced"), r.log.lastText);
    }

    @Test
    void aRingThatIsReallyEquippedIsRefusedBeforeAnythingHappens() throws Exception {
        Run r = new Run(false);
        r.player.items.remove(RING);
        r.player.equipped.put(RING, 1);
        r.divorce();

        assertEquals(0, r.breaker.calls);
        assertEquals(10_000_000, r.cm.meso);
        assertTrue(r.log.lastText.contains("unequip"), r.log.lastText);
    }
}
