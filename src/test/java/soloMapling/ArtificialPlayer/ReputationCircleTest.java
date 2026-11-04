package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMemory.Kind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 271 Stage 3: what a bot knows of a reputation comes from its own circle (guild, crew, bond neighbours), never
 * from bots it has no bond to. The all-bots counts ({@code Reputation.totals}, {@code BotMemory.kindCounts(int)}) are
 * deliberately aggregate statistics for operators; the restricted forms are what a bot may act on.
 */
class ReputationCircleTest {

    static final int B = BotStore.BOT_ID_BASE;
    static final int PLAYER = 4242;
    /** Bots 1 and 2 are a crew; bot 3 is a stranger to them. */
    static final Map<Integer, long[]> GROUPS = Map.of(B + 1, new long[]{Reputation.CREW | (B + 1)},
            B + 2, new long[]{Reputation.CREW | (B + 1)}, B + 3, new long[]{Reputation.SOLO | (B + 3)});

    private final long now = System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        Reputation.useForTest(id -> GROUPS.getOrDefault(id, new long[]{Reputation.SOLO | (id & 0xffffffffL)}), () -> now,
                id -> false);
    }

    @AfterEach
    void tearDown() {
        Reputation.useForTest(null, null, null);
        BotMemory.useConnectionSourceForTest(null);
    }

    @Test
    void aCrewKnowsWhatItsMembersSawAndAnOutsiderDoesNot() {
        Reputation.witnessed(B + 1, PLAYER, Reputation.Mark.SCAM, now);
        assertTrue(Reputation.known(B + 1, PLAYER).any(), "the witness");
        assertTrue(Reputation.known(B + 2, PLAYER).any(), "its crew mate");
        assertFalse(Reputation.known(B + 3, PLAYER).any(), "a bot outside the circle knows nothing of it");
        assertTrue(Reputation.subjectsKnownBy(B + 3).isEmpty());
        assertFalse(Reputation.wary(B + 3, PLAYER));
        // the aggregate is an operator view, and says so: it does see it
        assertEquals(1.0, Reputation.totals(PLAYER).get(Reputation.Mark.SCAM), 0.01);
    }

    @Test
    void theBootReadCountsOnlyNotesAboutPlayersAndLandsThemInTheHoldersOwnCircle() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(1, PLAYER, "SCAM", "scammed me on a trade"));
        rows.add(row(3, B + 2, "GIFT", "gave me a potion")); // bot 3's note about another BOT: not a player's reputation
        rows.add(row(3, 5151, "SCAM", "scammed me on a trade")); // bot 3's own first-hand note about another player
        Map<Integer, Object> bound = new HashMap<>();
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setString(anyInt(), anyString());
        when(ps.executeQuery()).thenAnswer(c -> {
            Iterator<Map<String, Object>> it = rows.iterator(); // the "database" hands back everything: Java filters too
            Map<String, Object>[] cur = new Map[1];
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenAnswer(n -> it.hasNext() && (cur[0] = it.next()) != null);
            when(rs.getInt(anyString())).thenAnswer(n -> ((Number) cur[0].get(n.getArgument(0))).intValue());
            when(rs.getLong(anyString())).thenAnswer(n -> ((Number) cur[0].get(n.getArgument(0))).longValue());
            when(rs.getString(anyString())).thenAnswer(n -> (String) cur[0].get(n.getArgument(0)));
            return rs;
        });
        Connection con = mock(Connection.class);
        java.util.concurrent.atomic.AtomicReference<String> sql = new java.util.concurrent.atomic.AtomicReference<>();
        when(con.prepareStatement(anyString())).thenAnswer(c -> {
            sql.set(c.getArgument(0));
            return ps;
        });
        Reputation.useForTest(id -> GROUPS.getOrDefault(id, new long[]{Reputation.SOLO | (id & 0xffffffffL)}), () -> now,
                id -> false, () -> con, null);
        assertEquals(2, Reputation.load(), "the bot-subject row is not counted");
        assertTrue(sql.get().contains("AboutCharacterId < ?"), "and the statement says so: " + sql.get());
        assertEquals(B, bound.get(1), "players are the ids below the bot id base");
        assertTrue(Reputation.known(B + 2, PLAYER).any(), "bot 1's scam counts for its own crew");
        assertFalse(Reputation.known(B + 3, PLAYER).any(), "and not for a bot outside it");
        assertFalse(Reputation.known(B + 1, B + 2).any(), "a bot's private note about another bot is not a reputation");
        assertTrue(Reputation.subjectsKnownBy(B + 3).contains(5151), "bot 3's own first-hand note lands in its own circle");
        assertFalse(Reputation.subjectsKnownBy(B + 3).contains(B + 2), "and its note about a bot is not a reputation");
    }

    private static Map<String, Object> row(int slot, int about, String kind, String note) {
        Map<String, Object> m = new HashMap<>();
        m.put("BotId", slot);
        m.put("AboutCharacterId", about);
        m.put("Kind", kind);
        m.put("Note", note);
        m.put("CreatedAt", System.currentTimeMillis());
        return m;
    }

    @Test
    void aBotsCircleCountReadsOnlyItsCircleAndRespectsAnOptOut() {
        FakeMemoryTable table = new FakeMemoryTable(false);
        table.install();
        for (int slot = 1; slot <= 3; slot++) {
            BotMemory.episode(slot, PLAYER, Kind.SCAM, "scammed me on a trade", 9, "", now);
        }
        BotMemory.episode(2, PLAYER, Kind.GIFT, "gave me a potion", 6, "", now);
        Reputation.useForTest(null, () -> now, id -> id == 777, null, id -> List.of(B + 1, B + 2));
        Map<Kind, Integer> mine = Reputation.circleKindCounts(B + 1, PLAYER);
        assertEquals(2, mine.get(Kind.SCAM), "bots 1 and 2; bot 3 is not read");
        assertEquals(1, mine.get(Kind.GIFT));
        assertEquals(3, BotMemory.kindCounts(PLAYER).get(Kind.SCAM), "the aggregate sees all three");
        assertTrue(Reputation.circleKindCounts(B + 1, 777).isEmpty(), "@botdrama off: nothing about them");
        Reputation.useForTest(null, () -> now, id -> false, null, id -> List.of(B + 3));
        assertEquals(1, Reputation.circleKindCounts(B + 3, PLAYER).get(Kind.SCAM));
        assertFalse(Reputation.circleKindCounts(B + 3, PLAYER).containsKey(Kind.GIFT));
    }
}
