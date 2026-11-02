package client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debuffs are {@link Disease}'s job, not {@link BuffStat}'s. Both enums are shipped to the client in the same
 * mask space, and the debuff masks are the same numbers in each, so a BuffStat that redeclares a debuff with the
 * disease's mask hands out a value the client reads as the Disease: a poison-style effect built on
 * BuffStat.POISON would silently drive client.Disease.POISON's semantics instead. BuffStat.SLOW survives only
 * because its mask (0x200000000) is not Disease.SLOW's (0x1).
 */
class BuffStatDebuffCollisionTest {

    private static List<String> buffStatsCollidingWithADisease() {
        List<String> clashes = new ArrayList<>();
        for (BuffStat stat : BuffStat.values()) {
            for (Disease disease : Disease.values()) {
                if (stat.name().equals(disease.name()) && stat.getValue() == disease.getValue()) {
                    clashes.add(stat.name() + "=0x" + Long.toHexString(stat.getValue()));
                }
            }
        }
        return clashes;
    }

    private static Set<String> buffStatNames() {
        Set<String> names = new TreeSet<>();
        for (BuffStat stat : BuffStat.values()) {
            names.add(stat.name());
        }
        return names;
    }

    @Test
    void noBuffStatCarriesADiseaseMask() {
        List<String> clashes = buffStatsCollidingWithADisease();

        assertTrue(clashes.isEmpty(), "BuffStat must not reuse a Disease mask, found: " + clashes);
    }

    @Test
    void theDeadBuffStatDebuffConstantsStayDeleted() {
        Set<String> names = buffStatNames();

        // Each of these was a second, unreferenced copy of a live client.Disease mask.
        for (String dead : List.of("CONFUSE", "BERSERK_FURY", "STUN", "POISON", "SEAL", "DARKNESS", "WEAKEN")) {
            assertTrue(!names.contains(dead), "BuffStat." + dead + " was removed; keep debuffs in client.Disease");
        }
    }
}
