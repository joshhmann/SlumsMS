package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotPopulationDirector.LoginAsk;
import soloMapling.ArtificialPlayer.BotRosterTestAccess;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b269 fix 1: a guild show-up must go through the director's bounded login ask, and a refused ask must not burn the
 * guild's 2-hour token — the old code spent the token first and then read the false answer as "no".
 */
class GuildShowUpLoginTest {

    private static final int GUILD = 9_100;
    private static final int OTHER_GUILD = 9_101;
    private static final int BOT_SLOT = 900_001;
    private static final int OTHER_SLOT = 900_002;

    private final List<Integer> asks = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        BotGuilds.useForTest(new GuildStore.Memory(), id -> null, () -> List.of(BOT_SLOT, OTHER_SLOT));
        BotGuilds.putForTest(member(BOT_SLOT, GUILD, 5, "Rosterbot"));
        rosterHolds(List.of(BOT_SLOT, OTHER_SLOT));
        BotPopulationDirector.useLoginAskForTest(id -> {
            asks.add(id);
            return LoginAsk.REFUSED;
        });
    }

    @AfterEach
    void restore() {
        BotPopulationDirector.useLoginAskForTest(null);
        BotRosterTestAccess.forget();
        BotGuilds.useForTest(null, null, null);
    }

    @Test
    void aRefusedShowUpKeepsItsToken() {
        Character player = player(GUILD);

        assertEquals(0, BotGuilds.showUpNow(GUILD, 1, player, 1_000_000L), "a refusal is not a show-up");
        assertEquals(List.of(BOT_SLOT), asks, "the show-up asked the director, not the pool");

        BotPopulationDirector.useLoginAskForTest(id -> {
            asks.add(id);
            return LoginAsk.QUEUED;
        });
        assertEquals(1, BotGuilds.showUpNow(GUILD, 1, player, 2_000_000L),
                "a refused show-up must not burn the 2-hour token: the next step may ask again");
        assertEquals(0, BotGuilds.showUpNow(GUILD, 1, player, 3_000_000L),
                "once a login was taken on, the token holds for two hours");
    }

    @Test
    void aRefusalMovesOnToTheNextMemberInsteadOfEndingTheShowUp() {
        BotGuilds.putForTest(member(OTHER_SLOT, GUILD, 7, "Zebrabot"));
        BotPopulationDirector.useLoginAskForTest(id -> {
            asks.add(id);
            return id == BOT_SLOT ? LoginAsk.REFUSED : LoginAsk.QUEUED;
        });

        assertEquals(1, BotGuilds.showUpNow(GUILD, 1, player(GUILD), 4_000_000L));
        assertEquals(List.of(BOT_SLOT, OTHER_SLOT), asks, "the refused member is skipped, the next one is asked");
    }

    @Test
    void aShowUpInAnotherGuildsNumberIsNotAskedFor() {
        assertEquals(0, BotGuilds.showUpNow(OTHER_GUILD, 1, player(GUILD), 6_000_000L));
        assertEquals(List.of(), asks, "one guild's show-up never asks for another guild's members");
    }

    private static MemberRow member(int slot, int guildId, int rank, String name) {
        long charId = (long) slot + soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE;
        return new MemberRow((int) charId, guildId, rank, 1L, 1L, name, 30, 100);
    }

    private static Character player(int guildId) {
        Character player = mock(Character.class);
        when(player.getGuildId()).thenReturn(guildId);
        when(player.getMapId()).thenReturn(100_000_000);
        when(player.getName()).thenReturn("Josh");
        return player;
    }

    /** A pool whose roster query returns these slots, so {@code BotRoster.member} answers from it without a database. */
    private static void rosterHolds(List<Integer> slots) throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        int[] next = {-1};
        when(rs.next()).thenAnswer(call -> ++next[0] < slots.size());
        when(rs.getInt("BotId")).thenAnswer(call -> slots.get(next[0]));
        when(rs.getString("Status")).thenReturn("ACTIVE");
        when(rs.getString("Personality")).thenReturn(null);
        when(rs.getString("Persona")).thenReturn(null);
        when(rs.getInt("PlayWindowStart")).thenReturn(0);
        when(rs.getInt("PlayWindowLen")).thenReturn(0);
        when(rs.getInt("SessionMinutes")).thenReturn(90);
        when(rs.getInt("HomeMapId")).thenReturn(100_000_000);
        when(rs.getString("Activity")).thenReturn(null);
        when(rs.getString("ActivityState")).thenReturn(null);
        when(rs.getLong("LastLoginAt")).thenReturn(0L);
        when(rs.getLong("LastLogoutAt")).thenReturn(0L);
        when(rs.getLong("EnrolledAt")).thenReturn(0L);
        when(select.executeQuery()).thenReturn(rs);
        when(con.prepareStatement(anyString())).thenReturn(select);
        BotRosterTestAccess.use(() -> con);
    }
}
