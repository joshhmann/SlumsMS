package soloMapling.mechanics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tiers on the three rows that got them, checked against the proposal table rather than against themselves.
 *
 * <p>The numbers below are the {@code HPQ} / {@code LPQ} / {@code OPQ} HARD rows of {@code DIFFICULTY-CHAOS-PLAN.md}
 * section 9, typed a second time here on purpose: a test that reads the row and compares it to the row proves nothing,
 * and this is the one place the transcription can be caught drifting. Every value is the table's, none derived here.
 */
class TierScalingTest {

    /** section 9, HARD rows: minLevel, maxLevel, mobLevelBonus, mobHpMultiplier, mobDamageMultiplier, minutes, mistake. */
    private static final Map<String, int[]> HARD = Map.of(
            "hpq", new int[]{30, 54, 20, 6, 15},   // multipliers checked separately, they are doubles
            "lpq", new int[]{55, 79, 20, 3, 55},
            "opq", new int[]{71, 95, 20, 4, 55});

    @AfterEach
    void switchOff() {
        System.clearProperty(TierScaling.FLAG);
        System.clearProperty(TierScaling.TIER_PROPERTY);
    }

    private static MechanicsModel.Content row(String id) {
        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), "the data folder parses: " + r.errors());
        return r.content().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theThreeRowsCarryTheirHardTierAndItIsTheTables() {
        for (String id : List.of("hpq", "lpq", "opq")) {
            MechanicsModel.Content c = row(id);
            MechanicsModel.TierRules hard = c.rules(MechanicsModel.Tier.HARD).orElseThrow(
                    () -> new AssertionError(id + " carries no HARD tier"));
            int[] table = HARD.get(id);
            assertEquals(table[0], hard.minLevel(), id + " HARD minLevel");
            assertEquals(table[1], hard.maxLevel(), id + " HARD maxLevel");
            assertEquals(table[2], hard.mobLevelBonus(), id + " HARD mobLevelBonus");
            assertEquals(table[3], hard.mobHpMultiplier(), 0.0001, id + " HARD mobHpMultiplier");
            assertEquals(1.5, hard.mobDamageMultiplier(), 0.0001, id + " HARD mobDamageMultiplier (flat 1.5)");
            assertEquals(table[4], hard.timeLimitMinutes(), id + " HARD timeLimitMinutes");
            assertEquals(1.25, hard.botMistakeFactor(), 0.0001, id + " HARD botMistakeFactor (the kpq.yaml example's)");
            // and only HARD: EXPERT and MASTER are in the table but deliberately not copied into the rows
            assertTrue(c.rules(MechanicsModel.Tier.EXPERT).isEmpty(), id + " carries EXPERT unexpectedly");
            assertTrue(c.rules(MechanicsModel.Tier.MASTER).isEmpty(), id + " carries MASTER unexpectedly");
        }
    }

    @Test
    void normalIsStillTheStockQuestAndNoTierMovedIt() {
        // the stock gates, exactly as the rows had them before the tiers were added
        Map<String, int[]> stock = Map.of("hpq", new int[]{10, 255, 10}, "lpq", new int[]{35, 255, 45},
                "opq", new int[]{51, 255, 45});
        for (String id : List.of("hpq", "lpq", "opq")) {
            MechanicsModel.Content c = row(id);
            int[] want = stock.get(id);
            assertEquals(want[0], c.minLevel(), id + " stock minLevel");
            assertEquals(want[1], c.maxLevel(), id + " stock maxLevel");
            assertEquals(want[2], c.timeLimitMinutes(), id + " stock clock");
            MechanicsModel.TierRules normal = c.rules(MechanicsModel.Tier.NORMAL).orElseThrow();
            assertEquals(c.minLevel(), normal.minLevel(), id + " NORMAL is the row's own floor");
            assertEquals(c.maxLevel(), normal.maxLevel());
            assertEquals(1.0, normal.mobHpMultiplier(), 0.0001, id + " NORMAL scales nothing");
            assertEquals(1.0, normal.mobDamageMultiplier(), 0.0001);
            assertEquals(1.0, normal.botMistakeFactor(), 0.0001);
        }
    }

    @Test
    void aTierStartsExactlyWhereItsMobBonusSaysAndLeavesRoomForTheNext() {
        for (String id : List.of("hpq", "lpq", "opq")) {
            MechanicsModel.Content c = row(id);
            MechanicsModel.TierRules hard = c.rules(MechanicsModel.Tier.HARD).orElseThrow();
            // rule 1 + rule 3 of section 9: the tier's floor is the stock floor plus the mob bonus, so the band does not
            // start below where its mobs are scaled for, and rule 2: the ceiling is the next tier's floor minus one
            assertEquals(c.minLevel() + hard.mobLevelBonus(), hard.minLevel(), id + " HARD floor follows its mob bonus");
            assertTrue(hard.maxLevel() > hard.minLevel(), id + " HARD has a band");
            assertTrue(hard.maxLevel() < c.maxLevel(), id + " HARD closes before the stock ceiling");
            assertTrue(hard.maxLevel() < 200, id + " room is left for an EXPERT floor under the table's 200 cap");
            // no gap anywhere the row does name tiers: each named tier's floor is the previous ceiling + 1, or the first
            EnumMap<MechanicsModel.Tier, MechanicsModel.TierRules> named = new EnumMap<>(MechanicsModel.Tier.class);
            for (MechanicsModel.Tier t : MechanicsModel.Tier.values()) {
                c.rules(t).ifPresent(rr -> named.put(t, rr));
            }
            MechanicsModel.Tier previous = null;
            for (MechanicsModel.Tier t : named.keySet()) {
                if (previous != null && previous != MechanicsModel.Tier.NORMAL) {
                    assertEquals(named.get(previous).maxLevel() + 1, named.get(t).minLevel(),
                            id + ": a gap between " + previous + " and " + t);
                }
                previous = t;
            }
        }
    }

    @Test
    void theSwitchDecidesWhetherARowAboveNormalMayBeRead() {
        MechanicsModel.Content hpq = row("hpq");
        System.clearProperty(TierScaling.FLAG);
        assertFalse(TierScaling.enabled());
        assertNull(TierScaling.rulesFor(hpq, MechanicsModel.Tier.HARD), "switch off: HARD is not readable");
        assertNotNull(TierScaling.rulesFor(hpq, MechanicsModel.Tier.NORMAL), "NORMAL needs no switch");
        assertEquals(MechanicsModel.Tier.NORMAL, TierScaling.requested(hpq), "and no run starts above NORMAL");

        System.setProperty(TierScaling.FLAG, "true");
        assertTrue(TierScaling.enabled());
        assertNotNull(TierScaling.rulesFor(hpq, MechanicsModel.Tier.HARD), "switch on and the row names HARD");
        assertNull(TierScaling.rulesFor(hpq, MechanicsModel.Tier.EXPERT), "switch on but the row does not name EXPERT");
        assertNull(TierScaling.rulesFor(hpq, null));

        System.setProperty(TierScaling.TIER_PROPERTY, "HARD");
        assertEquals(MechanicsModel.Tier.HARD, TierScaling.requested(hpq));
        System.setProperty(TierScaling.TIER_PROPERTY, "EXPERT");
        assertEquals(MechanicsModel.Tier.NORMAL, TierScaling.requested(hpq),
                "a tier the row does not carry is simply the stock quest");
        System.setProperty(TierScaling.TIER_PROPERTY, "nonsense");
        assertEquals(MechanicsModel.Tier.NORMAL, TierScaling.requested(hpq));
    }

    @Test
    void aRowWithoutTiersIsUntouchedByTheSwitch() {
        System.setProperty(TierScaling.FLAG, "true");
        System.setProperty(TierScaling.TIER_PROPERTY, "HARD");
        MechanicsModel.Content kpq = row("kpq");
        assertTrue(kpq.rules(MechanicsModel.Tier.HARD).isEmpty(), "kpq.yaml still carries only the commented-out shape");
        assertEquals(MechanicsModel.Tier.NORMAL, TierScaling.requested(kpq), "so Kerning plays exactly as before");
    }
}
