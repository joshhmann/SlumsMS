package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialLod;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 6b of ticket 271, the login half: a roster member that comes back after hours away is caught up once, at
 * login, by a bounded amount, through the same record the roster stamps.
 */
class BotRosterSocialLodTest {

    private static final long LOGGED_OUT_AT = 1_700_000_500_000L;
    private static final long HOUR = 3_600_000L;

    private final AtomicLong clock = new AtomicLong(LOGGED_OUT_AT + 6 * HOUR);

    @AfterEach
    void restore() {
        BotRoster.useConnectionSourceForTest(null);
        LifeMemoryStore.useConnectionSourceForTest(null);
        SocialSim.reset();
        SocialLod.reset();
        System.clearProperty("slums.sociallod");
    }

    @Test
    void aBotBackAfterSixHoursComesBackPartlyResocialisedOncePerLogin() throws Exception {
        int slot = BotStore.FIRST_ROSTER_SLOT + 3;
        int charId = BotStore.BOT_ID_BASE + slot;
        pool(List.of(row(slot, LOGGED_OUT_AT)));
        SocialSim.useForTest(clock::get, 5);
        BotRoster.member(slot);                                  // the one-time roster read
        LifeMemory memory = LifeMemoryStore.of(charId);
        long now = clock.get();
        double lonely = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now));
        double adrift = memory.mind(m -> m.need(SocialMind.Need.BELONGING, now));

        BotRoster.recordLogin(charId, now, 100000000, 100000000, new Point(0, 0));

        assertEquals(lonely - 6 * 0.03, memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now)), 1e-9);
        assertEquals(adrift - 6 * 0.012, memory.mind(m -> m.need(SocialMind.Need.BELONGING, now)), 1e-9);
        assertEquals(Long.valueOf(1), SocialLod.stats().get("catch-ups"));

        BotRoster.recordLogin(charId, now, 100000000, 100000000, new Point(0, 0));
        assertEquals(lonely - 6 * 0.03, memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now)), 1e-9);
        assertEquals(Long.valueOf(1), SocialLod.stats().get("catch-ups"));
    }

    @Test
    void aMemberThatNeverLoggedOutYetCatchesUpNothing() throws Exception {
        int slot = BotStore.FIRST_ROSTER_SLOT + 4;
        int charId = BotStore.BOT_ID_BASE + slot;
        pool(List.of(row(slot, 0)));
        SocialSim.useForTest(clock::get, 5);
        BotRoster.member(slot);
        LifeMemory memory = LifeMemoryStore.of(charId);
        long now = clock.get();
        double lonely = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now));

        BotRoster.recordLogin(charId, now, 100000000, 100000000, new Point(0, 0));

        assertEquals(lonely, memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now)), 1e-9);
        assertEquals(Long.valueOf(0), SocialLod.stats().get("catch-ups"));
    }

    // ── the mock roster table ──

    private void pool(List<Map<String, Object>> rows) throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        Deque<Map<String, Object>> pending = new ArrayDeque<>(rows);
        Map<String, Object>[] current = new Map[1];
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenAnswer(call -> (current[0] = pending.poll()) != null);
        when(rs.getInt(anyString())).thenAnswer(call -> ((Number) current[0].get(call.getArgument(0))).intValue());
        when(rs.getLong(anyString())).thenAnswer(call -> ((Number) current[0].get(call.getArgument(0))).longValue());
        when(rs.getString(anyString())).thenAnswer(call -> (String) current[0].get(call.getArgument(0)));
        when(select.executeQuery()).thenReturn(rs);
        when(con.prepareStatement(eq(BotRoster.SELECT_ALL))).thenReturn(select);

        PreparedStatement empty = mock(PreparedStatement.class);
        ResultSet none = mock(ResultSet.class);
        when(empty.executeQuery()).thenReturn(none);
        when(none.next()).thenReturn(false);
        when(con.prepareStatement(eq(LifeMemoryStore.SELECT_ALL))).thenReturn(empty);

        PreparedStatement upsert = mock(PreparedStatement.class);
        doAnswer(call -> null).when(upsert).setString(anyInt(), anyString());
        when(upsert.executeUpdate()).thenReturn(1);
        when(con.prepareStatement(eq(BotRoster.UPSERT))).thenReturn(upsert);
        when(con.prepareStatement(eq(LifeMemoryStore.UPSERT))).thenReturn(upsert);

        BotRoster.useConnectionSourceForTest(() -> con);
        LifeMemoryStore.useConnectionSourceForTest(() -> con);
    }

    private static Map<String, Object> row(int botId, long lastLogoutAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("BotId", botId);
        row.put("Status", "ACTIVE");
        row.put("Personality", "{\"grind\":0.7}");
        row.put("Persona", null);
        row.put("PlayWindowStart", 19 * 60);
        row.put("PlayWindowLen", 180);
        row.put("SessionMinutes", 90);
        row.put("HomeMapId", 100000000);
        row.put("Activity", "grind");
        row.put("ActivityState", null);
        row.put("LastLoginAt", lastLogoutAt);
        row.put("LastLogoutAt", lastLogoutAt);
        row.put("EnrolledAt", 1_690_000_000_000L);
        return row;
    }
}
