package client.command.commands.gm3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 1.6.6 mechanics-pq-staging: {@code !mechanics pq ROW [me] [N] [rangedK]} stages any party quest row - QA had no way
 * to put a party of regulars together for Ludi, Ellin, Magatia, Orbis or Zakum PQ (b332: no ZPQ staging either).
 */
class MechanicsPqStagingTest {

    @Test
    void qasLudiRunParses() {
        MechanicsCommand.PqRequest r = MechanicsCommand.parsePq(new String[]{"pq", "lpq", "me", "4", "ranged2"}, 1);
        assertEquals(new MechanicsCommand.PqRequest("lpq", true, 4, 2), r);
        assertEquals(new MechanicsCommand.PqRequest("zakumpq", false, 0, 0),
                MechanicsCommand.parsePq(new String[]{"pq", "ZakumPQ"}, 1), "row only: the row's minimum party");
        assertNull(MechanicsCommand.parsePq(new String[]{"pq"}, 1), "a row is required");
        assertNull(MechanicsCommand.parsePq(new String[]{"pq", "lpq", "2", "ranged3"}, 1), "more ranged than regulars");
        assertNull(MechanicsCommand.parsePq(new String[]{"pq", "lpq", "perfect"}, 1), "an unknown word is refused");
    }

    @Test
    void theRowsQaNeedsAreFoundAndBossRowsAreNot() {
        for (String id : new String[]{"lpq", "ellinpq", "magatiapq", "opq", "zakumpq", "kpq"}) {
            assertNotNull(MechanicsCommand.pqRow(id), id);
        }
        assertNull(MechanicsCommand.pqRow("papulatus"), "a boss row has !mechanics boss");
        assertNull(MechanicsCommand.pqRow("nosuchrow"));
    }

    @Test
    void rangedRegularsAlternateBowmanAndMagician() {
        assertEquals(3, soloMapling.mechanics.KpqStagingAccess.baseClassFor(0, 2), "bowman");
        assertEquals(2, soloMapling.mechanics.KpqStagingAccess.baseClassFor(1, 2), "magician");
        assertEquals(0, soloMapling.mechanics.KpqStagingAccess.baseClassFor(2, 2), "the rest any class");
        assertEquals(0, soloMapling.mechanics.KpqStagingAccess.baseClassFor(0, 0));
    }
}
