package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SocialProfileTest {

    private static final Map<SocialProfile.Type, Double> TARGET = Map.of(SocialProfile.Type.SOCIALIZER, .40,
            SocialProfile.Type.ACHIEVER, .30, SocialProfile.Type.EXPLORER, .20, SocialProfile.Type.KILLER, .10);

    private static void assertMix(int fromSlot, int count) {
        Map<SocialProfile.Type, Integer> leads = new EnumMap<>(SocialProfile.Type.class);
        for (int slot = fromSlot; slot < fromSlot + count; slot++) {
            leads.merge(SocialProfile.forSlot(slot).leading(), 1, Integer::sum);
        }
        for (var type : SocialProfile.Type.values()) {
            double share = leads.getOrDefault(type, 0) / (double) count;
            assertEquals(TARGET.get(type), share, .02, type + " from slot " + fromSlot + ": " + leads);
        }
    }

    @Test
    void threeThousandSlotsLeadWithinTwoPercentOfTheOwnersMix() {
        assertMix(0, 3000);
        assertMix(5000, 3000);                        // the slots LifeProfileGoalsTest uses
        assertMix(BotStore.planSlot(8, 0, 0), 3000);  // wave 8, the training cohorts
        assertMix(BotStore.FIRST_ROSTER_SLOT, 3000);  // members enrolled after the migration
    }

    @Test
    void theSameSlotAlwaysGetsTheSameProfileAndCharacterIdsMapToTheirSlot() {
        for (int slot = 0; slot < 3000; slot += 7) {
            assertEquals(SocialProfile.forSlot(slot), SocialProfile.forSlot(slot));
            assertEquals(SocialProfile.forSlot(slot), SocialProfile.forCharacter(BotStore.BOT_ID_BASE + slot));
        }
        assertEquals(5000, SocialProfile.slotOf(BotStore.BOT_ID_BASE + 5000));
        assertEquals(42, SocialProfile.slotOf(42), "a player id is its own slot");
        // Pinned values: a change of the seeding formula would re-roll every bot, so it must be deliberate.
        assertEquals(SocialProfile.Type.ACHIEVER, SocialProfile.leadingTypeOf(0)); // frac(golden ratio) = .618
        var pinned = SocialProfile.forSlot(5001);
        assertEquals(pinned.leading(), SocialProfile.leadingTypeOf(5001));
    }

    @Test
    void theBlendIsBilinearInThePointSumsToOneAndTheCornersArePure() {
        for (var type : SocialProfile.Type.values()) {
            var corner = new SocialProfile(type.cornerX, type.cornerY);
            assertEquals(1, corner.share(type), 1e-12, type + " corner is pure");
            assertEquals(type, corner.leading());
        }
        var centre = new SocialProfile(0, 0);
        for (var type : SocialProfile.Type.values()) assertEquals(.25, centre.share(type), 1e-12);
        var point = new SocialProfile(-.62, -.41);
        assertEquals(SocialProfile.Type.SOCIALIZER, point.leading());
        assertEquals(1.62 * 1.41 / 4, point.socialPull(), 1e-12);
        assertEquals(1.62 * .59 / 4, point.rivalPull(), 1e-12);
        for (int slot = 0; slot < 3000; slot++) {
            SocialProfile seeded = SocialProfile.forSlot(slot);
            assertEquals(1, seeded.blend().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
            var leading = seeded.leading();
            for (var type : SocialProfile.Type.values()) {
                if (type != leading) assertTrue(seeded.share(type) < seeded.share(leading), "the quadrant leads");
                assertTrue(seeded.share(type) > 0, "every seeded bot is a blend");
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new SocialProfile(1.2, 0));
        assertTrue(point.describe().startsWith("SOCIALIZER (-0.62,-0.41): A "), point.describe());
    }

    @Test
    void depthIsSpreadSomeBotsMixedSomePure() {
        int near = 0, deep = 0;
        for (int slot = 5000; slot < 8000; slot++) {
            var point = SocialProfile.forSlot(slot);
            assertTrue(Math.abs(point.x()) >= SocialProfile.DEPTH_MIN && Math.abs(point.x()) <= SocialProfile.DEPTH_MAX);
            assertTrue(Math.abs(point.y()) >= SocialProfile.DEPTH_MIN && Math.abs(point.y()) <= SocialProfile.DEPTH_MAX);
            if (point.depth() < .35) near++;
            if (point.depth() > .80) deep++;
        }
        // Measured on these slots: 484 near the centre, 696 deep in a corner.
        assertTrue(near > 300 && near < 700, "near the centre: " + near);
        assertTrue(deep > 450 && deep < 950, "deep in a corner: " + deep);
    }

    @Test
    void mostBotsAreSetInTheirWaysAndAMinorityIsImpressionable() {
        int impressionable = 0, set = 0;
        for (int slot = 5000; slot < 8000; slot++) {
            double p = SocialProfile.plasticityOf(slot);
            assertEquals(p, SocialProfile.plasticityOf(slot));
            assertTrue(p >= .05 && p <= .60);
            if (p > .35) impressionable++;
            if (p < .10) set++;
        }
        // 0.05 + 0.55 u^3: P(p > .35) = 1 - (.30/.55)^(1/3) = 18.3%; measured 524 of 3,000 (17.5%).
        assertEquals(.183, impressionable / 3000.0, .03, "impressionable share");
        assertTrue(set > 1000, "set in their ways: " + set);
    }

    @Test
    void driftIsCappedByPlasticityAndOnlyAnImpressionableBotCrossesIntoAnotherQuadrant() {
        var seed = new SocialProfile(-.40, -.30); // a socializer, moderately deep
        double[] high = {0, 0}, low = {0, 0};
        int crossedAt = -1;
        for (int push = 1; push <= 400; push++) {
            high = seed.push(high[0], high[1], SocialProfile.Push.BOSS, .60);
            low = seed.push(low[0], low[1], SocialProfile.Push.BOSS, .08);
            if (crossedAt < 0 && seed.drifted(high[0], high[1], .60).leading() != SocialProfile.Type.SOCIALIZER) {
                crossedAt = push;
            }
            assertTrue(Math.hypot(high[0], high[1]) <= .60 * SocialProfile.RADIUS_PER_PLASTICITY + 1e-12);
            assertTrue(Math.hypot(low[0], low[1]) <= .08 * SocialProfile.RADIUS_PER_PLASTICITY + 1e-12);
        }
        assertTrue(crossedAt > 0 && crossedAt < 40, "the plastic bot left its quadrant after " + crossedAt + " pushes");
        assertEquals(SocialProfile.Type.ACHIEVER, seed.drifted(high[0], high[1], .60).leading(),
                "400 boss kills made it an achiever");
        assertEquals(SocialProfile.Type.SOCIALIZER, seed.drifted(low[0], low[1], .08).leading(), "the set one never");
        assertEquals(new SocialProfile(-.40, -.30), seed, "the seed point never changes");

        var corner = new SocialProfile(.95, .95);
        double[] d = {0, 0};
        for (int i = 0; i < 50; i++) d = corner.push(d[0], d[1], SocialProfile.Push.BOSS, .6);
        assertEquals(1, corner.drifted(d[0], d[1], .6).x(), 1e-12, "the point stays on the chart");
    }

    @Test
    void everyPushHasAUnitDirectionAndLifeEventsPointWhereTheOwnerSaid() {
        for (var push : SocialProfile.Push.values()) assertEquals(1, Math.hypot(push.dx, push.dy), 1e-12);
        assertEquals(SocialProfile.Push.JOB_ADVANCE, LifeMemory.Event.JOB_ADVANCE.push);
        assertTrue(SocialProfile.Push.MARRIAGE.dx < 0 && SocialProfile.Push.MARRIAGE.dy < 0, "toward Socializer");
        assertTrue(SocialProfile.Push.GUILD_WAR.dx < 0 && SocialProfile.Push.GUILD_WAR.dy > 0, "toward Killer");
        assertTrue(SocialProfile.Push.BOOM.dy > -SocialProfile.Push.BOOM.dx && SocialProfile.Push.BOOM.strength < 1);
        assertTrue(SocialProfile.Push.NEW_MAP.dx > 0 && SocialProfile.Push.NEW_MAP.dy < 0, "toward Explorer");
    }
}
