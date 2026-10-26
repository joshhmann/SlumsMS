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
    void oneTypeAlwaysLeadsAndTheKillerDispositionSeparatesKillers() {
        double killerMin = 1, othersMax = 0;
        for (int slot = 0; slot < 3000; slot++) {
            SocialProfile profile = SocialProfile.forSlot(slot);
            double lead = profile.share(profile.leading());
            assertTrue(lead >= SocialProfile.LEAD_MIN - 1e-9 && lead <= SocialProfile.LEAD_MAX + 1e-9, "lead " + lead);
            for (var type : SocialProfile.Type.values()) {
                if (type != profile.leading()) assertTrue(profile.share(type) < lead - .1, "a clear leader");
                assertTrue(profile.share(type) > 0, "every bot is a blend");
            }
            if (profile.leading() == SocialProfile.Type.KILLER) killerMin = Math.min(killerMin, profile.rivalPull());
            else othersMax = Math.max(othersMax, profile.rivalPull());
        }
        assertTrue(killerMin >= .5, "killer-led bots pull hard toward rivalry: " + killerMin);
        assertTrue(othersMax < .36, "everyone else much less: " + othersMax);
    }

    @Test
    void learnedDeltaIsBoundedAndNeverChangesTheSeed() {
        SocialProfile seed = SocialProfile.forSlot(5003);
        var delta = Map.of(SocialProfile.Type.KILLER, 5.0, SocialProfile.Type.SOCIALIZER, Double.NaN);
        SocialProfile moved = seed.withDelta(delta);
        assertEquals(SocialProfile.forSlot(5003), seed, "the seed is a value; nothing mutates it");
        assertEquals(1, moved.blend().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        double raw = seed.share(SocialProfile.Type.KILLER) + SocialProfile.MAX_DELTA;
        assertTrue(moved.share(SocialProfile.Type.KILLER) <= raw + 1e-9, "clamped to +MAX_DELTA before renormalising");
        assertSame(seed, seed.withDelta(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SocialProfile(Map.of(SocialProfile.Type.KILLER, 1.0)));
        assertTrue(seed.describe().contains("-led: A "), seed.describe());
    }
}
