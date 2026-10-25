package client;

import org.junit.jupiter.api.Test;
import server.maps.Summon;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic ab7253d97): getSummonsValues() returned the live values() view of a
 * LinkedHashMap that other threads change (summon expiry, a recast). A handler walking it (summon damage, summon
 * movement, the beholder, map spawn data) while a summon came or went threw ConcurrentModificationException. The
 * write here stands in for the other thread, so the race is hit every run.
 */
class SummonsSnapshotTest {

    private static Character newCharacter() throws Exception {
        Constructor<Character> ctor = Character.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    void aSummonAddedDuringAWalkDoesNotBreakTheWalk() throws Exception {
        Character chr = newCharacter();
        chr.addSummon(1, mock(Summon.class));
        chr.addSummon(2, mock(Summon.class));

        Collection<Summon> summons = chr.getSummonsValues();
        Iterator<Summon> it = summons.iterator();
        it.next();
        chr.addSummon(3, mock(Summon.class));

        assertDoesNotThrow(() -> {
            while (it.hasNext()) {
                it.next();
            }
        });
        assertEquals(2, summons.size(), "the caller gets a copy, not the live map");
        assertEquals(3, chr.getSummonsValues().size());
    }
}
