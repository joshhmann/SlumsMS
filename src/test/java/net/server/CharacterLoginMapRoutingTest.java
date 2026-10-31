package net.server;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.maps.MapManager;
import soloMapling.WzTestData;
import tools.DatabaseConnection;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * s-maproute round 2: what {@code Character.loadCharFromDB} does with a character the database has saved inside a map
 * nobody can enter. WZ, a fake JDBC connection and a world id 1, no game client.
 *
 * The login path is the one caller that must land somewhere, so it asks {@code MapManager.getMapOrFallback} and keeps
 * the map it actually landed on. That corrected id is what the next save writes, so the character is never saved back
 * into the crash map.
 *
 * Fail-before value: with {@code MapManager.getMap} made strict (it no longer substitutes) and the old login line left
 * as {@code getMap}, this test NPEs on {@code ret.map.getPortal(...)}; that is the state the round-2 follow-up asked
 * to close. On the base commit the login worked only because the factory substituted silently, which is what the
 * table's own direct-getMap callers must not do.
 */
class CharacterLoginMapRoutingTest {

    private static final int FOUNTAIN_SQUARE = 410000300;
    private static final int RAMPART_RESIDENCES = 410000350;   // client-crash
    private static final int STORAGE_CELLAR = 993162016;       // no Map.wz image
    private static final int CHARACTER_ID = 3;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @BeforeEach
    void world() {
        World world = mock(World.class);
        when(world.getId()).thenReturn(1);
        Server.getInstance().registerWorld(world, 0, Map.of(1, "203.0.113.1:7585"), "Welcome to Bera!");
    }

    @AfterEach
    void resetWorlds() {
        Server.getInstance().resetServerWorlds();
    }

    @Test
    void aCharacterSavedInTheCrashMapLogsInAtItsFallbackAndSavesThatId() throws Exception {
        assertLogsInAtFallbackAndSavesIt(RAMPART_RESIDENCES);
    }

    @Test
    void aCharacterSavedInAMapWithNoImageLogsInAtItsFallbackAndSavesThatId() throws Exception {
        assertLogsInAtFallbackAndSavesIt(STORAGE_CELLAR);
    }

    private void assertLogsInAtFallbackAndSavesIt(int savedIn) throws Exception {
        WzTestData.assumePresent("Map.wz");

        FakeLoginDb db = new FakeLoginDb()
                .row("map", savedIn)
                .row("spawnpoint", 7)
                .row("world", (byte) 1);

        Connection con = db.connection();
        try (MockedStatic<DatabaseConnection> mocked = Mockito.mockStatic(DatabaseConnection.class)) {
            mocked.when(DatabaseConnection::getConnection).thenReturn(con);

            Channel channelServer = mock(Channel.class, RETURNS_DEEP_STUBS);
            MapManager maps = new MapManager(null, 1, 1);
            when(channelServer.getMapFactory()).thenReturn(maps);
            Client client = mock(Client.class);
            when(client.getChannelServer()).thenReturn(channelServer);

            Character chr = Character.loadCharFromDB(CHARACTER_ID, client, true);

            assertNotNull(chr.getMap(), "a login never has no map");
            assertEquals(FOUNTAIN_SQUARE, chr.getMapId(), "saved in " + savedIn + ": logs in at its fallback");
            assertNotEquals(savedIn, chr.getMapId());

            chr.saveCharToDB(false);

            assertEquals(FOUNTAIN_SQUARE, db.savedMap(),
                    "the save writes the map the character really is in, not the one it was saved in");
        }
    }

    /**
     * The statements {@link Character#loadCharFromDB} and {@link Character#saveCharToDB} run, as a mocked connection:
     * one characters row, empty results for everything else, and every bound parameter kept so a save can be read back.
     * Not a SQL engine: a column the login reads that this row does not set fails the test by name.
     */
    private static final class FakeLoginDb {
        private final Map<String, Object> row = new HashMap<>();
        private final List<Map<Integer, Object>> statements = new ArrayList<>();
        private final List<String> sqls = new ArrayList<>();

        FakeLoginDb() {
            row.put("name", "Walker");
            row.put("level", 30);
            row.put("fame", 0);
            row.put("fquest", 0);
            row.put("str", 4);
            row.put("dex", 4);
            row.put("int", 4);
            row.put("luk", 4);
            row.put("exp", 0L);
            row.put("gachaexp", 0);
            row.put("hp", 500);
            row.put("maxhp", 500);
            row.put("mp", 100);
            row.put("maxmp", 100);
            row.put("hpMpUsed", 0);
            row.put("HasMerchant", 0);
            row.put("ap", 0);
            row.put("sp", "0,0,0,0,0,0");
            row.put("meso", 0);
            row.put("MerchantMesos", 0);
            row.put("gm", 0);
            row.put("skincolor", 0);
            row.put("gender", 0);
            row.put("job", 100);
            row.put("finishedDojoTutorial", 0);
            row.put("vanquisherKills", 0);
            row.put("omokwins", 0);
            row.put("omoklosses", 0);
            row.put("omokties", 0);
            row.put("matchcardwins", 0);
            row.put("matchcardlosses", 0);
            row.put("matchcardties", 0);
            row.put("hair", 30000);
            row.put("face", 20000);
            row.put("accountid", 1);
            row.put("jailexpire", 0L);
            row.put("rank", 0);
            row.put("rankMove", 0);
            row.put("jobRank", 0);
            row.put("jobRankMove", 0);
            row.put("mountexp", 0);
            row.put("mountlevel", 1);
            row.put("mounttiredness", 0);
            row.put("guildid", 0);
            row.put("guildrank", 5);
            row.put("allianceRank", 5);
            row.put("familyId", -1);
            row.put("monsterbookcover", 0);
            row.put("vanquisherStage", 0);
            row.put("ariantPoints", 0);
            row.put("dojoPoints", 0);
            row.put("lastDojoStage", 0);
            row.put("dataString", "");
            row.put("buddyCapacity", 20);
            row.put("lastExpGainTime", new Timestamp(0L));
            row.put("partySearch", false);
            row.put("reborns", 0);
            row.put("bank", 0L);
            row.put("equipslots", (byte) 24);
            row.put("useslots", (byte) 24);
            row.put("setupslots", (byte) 24);
            row.put("etcslots", (byte) 24);
            row.put("partnerId", 0);
            row.put("marriageItemId", 0);
            row.put("party", 0);
            row.put("messengerid", 0);
            row.put("messengerposition", 0);
        }

        FakeLoginDb row(String column, Object value) {
            row.put(column, value);
            return this;
        }

        Connection connection() throws SQLException {
            Connection con = mock(Connection.class);
            when(con.prepareStatement(anyString())).thenAnswer(a -> statement(a.getArgument(0)));
            when(con.prepareStatement(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(a -> statement(a.getArgument(0)));
            return con;
        }

        private PreparedStatement statement(String sql) throws SQLException {
            PreparedStatement ps = mock(PreparedStatement.class);
            Map<Integer, Object> bound = new HashMap<>();
            statements.add(bound);
            sqls.add(sql);

            doAnswer(a -> bound.put(a.getArgument(0), a.getArgument(1)))
                    .when(ps).setString(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
            doAnswer(a -> bound.put(a.getArgument(0), a.getArgument(1)))
                    .when(ps).setInt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
            doAnswer(a -> bound.put(a.getArgument(0), a.getArgument(1)))
                    .when(ps).setLong(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
            doAnswer(a -> bound.put(a.getArgument(0), a.getArgument(1)))
                    .when(ps).setBoolean(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
            doAnswer(a -> bound.put(a.getArgument(0), a.getArgument(1)))
                    .when(ps).setByte(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyByte());
            doAnswer(a -> bound.put(a.getArgument(0), a.getArgument(1)))
                    .when(ps).setTimestamp(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

            // built before the when() below: Mockito refuses a nested when() inside an unfinished one
            ResultSet rows = sql.contains("FROM characters WHERE id") ? characterRow() : emptyResultSet();
            when(ps.executeQuery()).thenReturn(rows);
            when(ps.executeUpdate()).thenReturn(1);
            return ps;
        }

        private ResultSet emptyResultSet() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(false);
            return rs;
        }

        /** The characters row, answered by column label; a missing one says which column the login wanted. */
        private ResultSet characterRow() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);

            org.mockito.stubbing.Answer<Object> byLabel = call -> {
                String column = call.getArgument(0, String.class);
                if (!row.containsKey(column)) {
                    throw new AssertionError("the login reads characters." + column + ", which the fake row does not set");
                }
                return row.get(column);
            };
            when(rs.getInt(anyString())).thenAnswer(byLabel);
            when(rs.getLong(anyString())).thenAnswer(byLabel);
            when(rs.getString(anyString())).thenAnswer(byLabel);
            when(rs.getByte(anyString())).thenAnswer(byLabel);
            when(rs.getBoolean(anyString())).thenAnswer(byLabel);
            when(rs.getTimestamp(anyString())).thenAnswer(byLabel);
            return rs;
        }

        /** The {@code map} value the save wrote, read from the UPDATE characters SET list by column name. */
        int savedMap() {
            for (int i = 0; i < sqls.size(); i++) {
                String sql = sqls.get(i);
                if (!sql.startsWith("UPDATE characters SET")) {
                    continue;
                }
                int set = sql.indexOf("SET ") + 4;
                String[] columns = sql.substring(set, sql.indexOf(" WHERE")).split(",");
                for (int c = 0; c < columns.length; c++) {
                    String column = columns[c].trim();
                    column = column.substring(0, column.indexOf('=')).trim().replace("`", "");
                    if (column.equals("map")) {
                        Object value = statements.get(i).get(c + 1);
                        assertNotNull(value, "the save did not bind the map column");
                        return (Integer) value;
                    }
                }
            }
            throw new AssertionError("the save never ran UPDATE characters SET; it ran " + sqls);
        }
    }
}
