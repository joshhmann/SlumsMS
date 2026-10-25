package client;

import config.ServerConfig;
import net.server.Server;
import net.server.world.Party;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.CashShop;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import tools.DatabaseConnection;

import java.lang.reflect.Field;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OwnedAltCharacterTest {
    static void field(Character chr, String name, Object value) throws Exception {
        Field f = Character.class.getDeclaredField(name); f.setAccessible(true); f.set(chr, value);
    }
    static Character alt(Character owner) {
        BotClient c = new BotClient(1, 1); c.setAccID(owner.getAccountID());
        Character alt = Character.getDefault(c); alt.setID(2); alt.setWorld(1); c.setPlayer(alt);
        alt.markOwnedAlt(owner); return alt;
    }
    static Character owner() {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(1); when(c.getAccountID()).thenReturn(7); when(c.getWorld()).thenReturn(1);
        return c;
    }
    @Test void separatesAltFromPopulationAndHumanAndRejectsWrongIdentity() {
        Character owner = owner(); Character alt = alt(owner);
        assertTrue(alt.isOwnedAlt()); assertFalse(alt.isBot());
        assertTrue(BotHelpers.isBot(alt)); assertFalse(BotHelpers.isRosterBot(alt));
        assertSame(alt, alt.getClient().getPlayer());
        assertThrows(IllegalStateException.class, () -> alt.newClient(mock(Client.class)));
        Character roster = Character.getDefault(new BotClient(1, 1)); roster.setID(1_000_000_100);
        assertTrue(roster.isBot()); assertTrue(BotHelpers.isRosterBot(roster));
        assertThrows(IllegalArgumentException.class, () -> roster.markOwnedAlt(owner));
        assertFalse(new ServerConfig().OWNED_ALTS_ENABLED);
    }
    @Test void gainsOnlyMonsterPartyPlayExpWithOwnerOnSameMapAndParty() throws Exception {
        boolean rebirth = config.YamlConfig.config.server.USE_REBIRTH_SYSTEM;
        config.YamlConfig.config.server.USE_REBIRTH_SYSTEM = true;
        try {
        Character owner = owner(); Character alt = alt(owner);
        Client c = mock(Client.class); when(c.getPlayer()).thenReturn(owner); when(owner.getClient()).thenReturn(c);
        when(owner.isLoggedin()).thenReturn(true); when(owner.isLoggedinWorld()).thenReturn(true); when(owner.isAlive()).thenReturn(true);
        Party party = mock(Party.class); MapleMap map = mock(MapleMap.class);
        field(alt, "party", party); alt.setMap(map);
        when(owner.getParty()).thenReturn(party); when(owner.getMap()).thenReturn(map);
        alt.gainExp(10); assertEquals(0, alt.getExp());
        alt.gainPartyPlayExp(10, 4, false, false, true); assertEquals(14, alt.getExp());
        when(owner.getMap()).thenReturn(mock(MapleMap.class));
        alt.gainPartyPlayExp(10, 4, false, false, true); assertEquals(14, alt.getExp());
        when(owner.getMap()).thenReturn(map); when(owner.isLoggedin()).thenReturn(false); when(owner.isLoggedinWorld()).thenReturn(false);
        alt.gainPartyPlayExp(10, 4, false, false, true); assertEquals(14, alt.getExp());
        } finally { config.YamlConfig.config.server.USE_REBIRTH_SYSTEM = rebirth; }
    }
    @Test void savesRealCharacterTransactionAndInventoryWithoutAccountCashSnapshot() throws Exception {
        Character alt = alt(owner()); field(alt, "loggedIn", true);
        field(alt, "exp", new java.util.concurrent.atomic.AtomicLong(45));
        field(alt, "meso", new java.util.concurrent.atomic.AtomicInteger(123));
        alt.getInventory(client.inventory.InventoryType.USE).addItem(new client.inventory.Item(2000000, (short) 1, (short) 3, -1));
        server.quest.Quest quest = mock(server.quest.Quest.class); when(quest.getId()).thenReturn((short) 2000);
        QuestStatus status = mock(QuestStatus.class);
        when(status.getQuest()).thenReturn(quest); when(status.getStatus()).thenReturn(QuestStatus.Status.COMPLETED);
        when(status.getProgress()).thenReturn(java.util.Map.of()); when(status.getMedalMaps()).thenReturn(java.util.List.of());
        java.util.Map<Short, QuestStatus> quests = new java.util.LinkedHashMap<>(); quests.put((short) 2000, status);
        field(alt, "quests", quests);
        CashShop cash = mock(CashShop.class); field(alt, "cashshop", cash);
        Connection con = mock(Connection.class); PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1); when(ps.executeQuery()).thenReturn(rs);
        when(ps.getGeneratedKeys()).thenReturn(rs);
        Server server = mock(Server.class);
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            alt.saveOwnedAltToDB();
        }
        verify(ps).setLong(7, 45);
        verify(ps).setInt(22, 123);
        verify(ps).setInt(4, 2000000);
        verify(ps).setInt(7, 3);
        verify(ps).setInt(2, 2000);
        verify(con).commit(); verify(con, never()).rollback();
        verify(con).prepareStatement(startsWith("UPDATE characters SET"), anyInt());
        verify(con).prepareStatement(startsWith("DELETE `inventoryitems`"));
        verify(con).prepareStatement(startsWith("INSERT INTO queststatus"), anyInt());
        verify(cash, never()).save(any());
    }
    @Test void ownedAltCannotAccrueOffscreenLevels() {
        Character alt = alt(owner());
        assertEquals(0, alt.applyOffscreenExp(100_000, 200));
        assertEquals(1, alt.getLevel());
        assertEquals(0, alt.getExp());
    }
    @Test void partyLevelUpNeverRunsPopulationBuildOrJobAdvance() throws Exception {
        boolean rebirth = config.YamlConfig.config.server.USE_REBIRTH_SYSTEM;
        config.YamlConfig.config.server.USE_REBIRTH_SYSTEM = true;
        try {
            Character owner = owner(); Character alt = spy(alt(owner));
            Client c = mock(Client.class); when(c.getPlayer()).thenReturn(owner); when(owner.getClient()).thenReturn(c);
            when(owner.isLoggedin()).thenReturn(true); when(owner.isLoggedinWorld()).thenReturn(true); when(owner.isAlive()).thenReturn(true);
            Party party = mock(Party.class); MapleMap map = mock(MapleMap.class);
            field(alt, "party", party); alt.setMap(map);
            when(owner.getParty()).thenReturn(party); when(owner.getMap()).thenReturn(map);
            doAnswer(inv -> { alt.setLevel(2); alt.setExp(0); return null; }).when(alt).levelUp(true);
            try (MockedStatic<soloMapling.server.MapleRootSolo> hooks = mockStatic(soloMapling.server.MapleRootSolo.class)) {
                alt.gainPartyPlayExp(20, 0, false, false, true);
                assertEquals(2, alt.getLevel());
                hooks.verify(() -> soloMapling.server.MapleRootSolo.runHook(eq("bot progression"), any(Runnable.class)), never());
            }
        } finally { config.YamlConfig.config.server.USE_REBIRTH_SYSTEM = rebirth; }
    }
    @Test void failedTransactionIsReportedInsteadOfAllowingLogin() throws Exception {
        Character alt = alt(owner()); field(alt, "loggedIn", true);
        Server server = mock(Server.class);
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("unavailable"));
            assertThrows(IllegalStateException.class, alt::saveOwnedAltToDB);
        }
    }
}
