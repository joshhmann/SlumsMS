package net.server.channel.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IncubatorHatchTest {
    static class Fake implements IncubatorHatch.Ops {
        int target;
        boolean space = true;
        boolean eggGone, incGone;
        int grantedId, grantedQty;
        boolean grantFails;

        public int targetItemId() { return target; }
        public boolean hasSpaceFor(int id, int q) { return space; }
        public boolean consumeEgg() { eggGone = true; return true; }
        public boolean consumeIncubator() { incGone = true; return true; }
        public boolean grant(int id, int q) {
            if (grantFails) return false;
            grantedId = id; grantedQty = q; return true;
        }
        public void restoreEgg() { eggGone = false; }
        public void restoreIncubator() { incGone = false; }
    }

    @Test
    void eggHatchesAndConsumesBoth() {
        Fake f = new Fake();
        f.target = 4170000;
        var r = IncubatorHatch.hatch(f, n -> 4); // 2000004 x240
        assertTrue(r.hatched());
        assertTrue(f.eggGone && f.incGone);
        assertEquals(2000004, f.grantedId);
        assertEquals(240, f.grantedQty);
    }

    @Test
    void wrongItemConsumesNothing() {
        Fake f = new Fake();
        f.target = 5060002; // the incubator itself
        var r = IncubatorHatch.hatch(f, n -> 0);
        assertFalse(r.hatched());
        assertNotNull(r.refusal());
        assertFalse(f.eggGone || f.incGone);
        assertEquals(0, f.grantedId);
    }

    @Test
    void missingSlotConsumesNothing() {
        Fake f = new Fake();
        f.target = -1;
        assertFalse(IncubatorHatch.hatch(f, n -> 0).hatched());
        assertFalse(f.eggGone || f.incGone);
    }

    @Test
    void fullRewardInventoryConsumesNothing() {
        Fake f = new Fake();
        f.target = 4170003;
        f.space = false;
        var r = IncubatorHatch.hatch(f, n -> 0);
        assertFalse(r.hatched());
        assertFalse(f.eggGone || f.incGone);
    }

    @Test
    void failedGrantUndoesConsumes() {
        Fake f = new Fake();
        f.target = 4170009;
        f.grantFails = true;
        assertFalse(IncubatorHatch.hatch(f, n -> 0).hatched());
        assertFalse(f.eggGone || f.incGone);
    }
}
