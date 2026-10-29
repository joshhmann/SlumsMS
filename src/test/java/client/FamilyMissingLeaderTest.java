package client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST 2026-10-29: two family rows pointed at a senior that no longer existed, the family had no leader, and
 * loadAllFamilies' {@code getLeader().doFullCount()} threw at boot, so the whole server stayed down.
 */
class FamilyMissingLeaderTest {

    @Test
    void aFamilyWithoutALeaderGetsItsOldestRootMemberInsteadOfStoppingTheBoot() {
        Family fam = new Family(901, 0);
        FamilyEntry orphanB = new FamilyEntry(fam, 52, "N1ghtPanda", 40, null);
        FamilyEntry orphanA = new FamilyEntry(fam, 51, "ShadowLuv", 45, null);
        FamilyEntry junior = new FamilyEntry(fam, 53, "Junior", 20, null);
        fam.addEntry(orphanB);
        fam.addEntry(orphanA);
        fam.addEntry(junior);
        junior.setSenior(orphanA, false);
        assertNull(fam.getLeader());

        assertTrue(Family.repairMissingLeader(fam));
        assertSame(orphanA, fam.getLeader(), "the root member with the lowest id leads");
    }

    @Test
    void anEmptyFamilyIsSkipped() {
        Family fam = new Family(902, 0);
        assertFalse(Family.repairMissingLeader(fam));
        assertNull(fam.getLeader());
    }
}
