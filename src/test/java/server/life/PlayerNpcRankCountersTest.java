package server.life;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cosmic #193 (0aee9d7c3): the statue rank counters were sized by a static block that ran when the class first
 * loaded, which is on the async startup pool before any world is up (PlayerNPCFactory builds developer NPCs there), so
 * the list stayed empty and the first statue after a restart threw IndexOutOfBoundsException at the world-rank read.
 * The rank for a new statue is now taken through nextWorldRank, which never throws on a world without a counter.
 * Reflection keeps the test compiling on the old code, where it fails on the missing method.
 */
class PlayerNpcRankCountersTest {

    private static int nextWorldRank(int worldId) throws Exception {
        Method m = PlayerNPC.class.getDeclaredMethod("nextWorldRank", int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, worldId);
    }

    private static void load(Connection con, int worldIdBound) throws Exception {
        Method m = PlayerNPC.class.getDeclaredMethod("loadRunningRankData", Connection.class, int.class);
        m.invoke(null, con, worldIdBound);
    }

    /** playernpcs holds one statue in world 0 at world rank 4, overall rank 9; nothing else. */
    private static Connection db() throws Exception {
        Connection con = mock(Connection.class);

        PreparedStatement overall = mock(PreparedStatement.class);
        ResultSet overallRs = mock(ResultSet.class);
        when(overallRs.next()).thenReturn(true, false);
        when(overallRs.getInt(1)).thenReturn(9);
        when(overall.executeQuery()).thenReturn(overallRs);

        PreparedStatement world = mock(PreparedStatement.class);
        ResultSet worldRs = mock(ResultSet.class);
        when(worldRs.next()).thenReturn(true, false);
        when(worldRs.getInt(1)).thenReturn(0);
        when(worldRs.getInt(2)).thenReturn(4);
        when(world.executeQuery()).thenReturn(worldRs);

        PreparedStatement job = mock(PreparedStatement.class);
        ResultSet jobRs = mock(ResultSet.class);
        when(jobRs.next()).thenReturn(false);
        when(job.executeQuery()).thenReturn(jobRs);

        when(con.prepareStatement(anyString())).thenReturn(job);
        when(con.prepareStatement(contains("max(overallrank)"))).thenReturn(overall);
        when(con.prepareStatement(contains("max(worldrank)"))).thenReturn(world);
        return con;
    }

    @Test
    void theFirstStatueAfterARestartGetsTheNextWorldRank() throws Exception {
        load(db(), 1);   // what Server does once world 0 is up

        assertEquals(5, nextWorldRank(0), "world 0 holds rank 4, so the next statue there is 5");
        assertEquals(6, nextWorldRank(0));
    }

    @Test
    void aWorldWithoutACounterStartsAtOneInsteadOfThrowing() throws Exception {
        load(db(), 0);   // the old failure: the counters were sized while no world was up

        assertEquals(1, nextWorldRank(0), "no counter for world 0 yet: start at 1, never IndexOutOfBounds");
        assertEquals(1, nextWorldRank(3), "a world id past the end grows the list");
        assertEquals(2, nextWorldRank(3));
    }
}
