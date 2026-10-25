package client.processor.npc;

import client.Character;
import client.Client;
import config.YamlConfig;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DueyProcessorTest {

    private final boolean dueyWas = YamlConfig.config.server.USE_DUEY;

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_DUEY = dueyWas;
    }

    @Test
    void wellFormedSendsPass() {
        assertNull(DueyProcessor.invalidSendRequest((byte) 0, (short) 0, 1000), "mesos only");
        assertNull(DueyProcessor.invalidSendRequest((byte) 4, (short) 3, 0), "an item stack");
        assertNull(DueyProcessor.invalidSendRequest((byte) 1, (short) 1, 50_000), "an equip and mesos");
    }

    @Test
    void negativeAmountsAndMesosAreRefusedBeforeAnythingIsTaken() {
        // a negative amount made Cosmic's slot removal add items (a dupe); negative mesos came out of the receiver
        assertNotNull(DueyProcessor.invalidSendRequest((byte) 2, (short) -5, 0));
        assertNotNull(DueyProcessor.invalidSendRequest((byte) 2, (short) 0, 0));
        assertNotNull(DueyProcessor.invalidSendRequest((byte) 0, (short) 0, -1000));
        assertNotNull(DueyProcessor.invalidSendRequest((byte) 0, (short) 0, 0), "nothing to send");
        assertNotNull(DueyProcessor.invalidSendRequest((byte) 9, (short) 1, 0), "no such inventory");
    }

    @Test
    void aClosedDueyExplainsItselfInsteadOfOpening() {
        YamlConfig.config.server.USE_DUEY = false;
        Client c = mock(Client.class);
        assertFalse(DueyProcessor.openOrExplain(c, true));
        assertFalse(DueyProcessor.openOrExplain(c, false));
        verify(c, times(4)).sendPacket(any(Packet.class)); // the message + enableActions, twice

        YamlConfig.config.server.USE_DUEY = true;
        Client open = mock(Client.class);
        assertTrue(DueyProcessor.openOrExplain(open, true));
        verify(open, never()).sendPacket(any(Packet.class));
    }

    @Test
    void someoneElsesPackageCanBeNeitherClaimedNorRemoved() throws Exception {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        when(c.tryacquireClient()).thenReturn(true);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.getId()).thenReturn(42);
        when(chr.getName()).thenReturn("Thief");

        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement delete = mock(PreparedStatement.class);
        ResultSet none = mock(ResultSet.class);
        when(con.prepareStatement("SELECT * FROM dueypackages dp WHERE PackageId = ? AND ReceiverId = ?")).thenReturn(select);
        when(con.prepareStatement("DELETE FROM dueypackages WHERE PackageId = ? AND ReceiverId = ?")).thenReturn(delete);
        when(select.executeQuery()).thenReturn(none);
        when(none.next()).thenReturn(false);  // the package is addressed to someone else
        when(delete.executeUpdate()).thenReturn(0);

        try (var db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);

            DueyProcessor.dueyClaimPackage(c, 7);
            DueyProcessor.dueyRemovePackage(c, 7, true);
        }

        verify(select).setInt(2, 42);
        verify(delete).setInt(2, 42);
        verify(chr, never()).gainMeso(anyInt(), anyBoolean());
        verify(c, times(2)).sendPacket(any(Packet.class)); // an error each, no removal packet
        verify(c, atLeastOnce()).releaseClient();
    }

    @Test
    void dueyIsOnByDefault() {
        // owner 2026-10-25 (1.5.2): Duey on, as stock v83 (MapleRoot shipped USE_DUEY: false)
        assertTrue(new config.ServerConfig().USE_DUEY);
    }

    @Test
    void aBotNeverSendsAParcel() {
        YamlConfig.config.server.USE_DUEY = true;
        Client c = mock(Client.class);
        Character bot = mock(Character.class);
        when(c.tryacquireClient()).thenReturn(true);
        when(c.getPlayer()).thenReturn(bot);
        when(bot.getId()).thenReturn(1_000_000_005);
        when(bot.getMeso()).thenReturn(10_000_000);

        try (var db = mockStatic(DatabaseConnection.class)) {
            DueyProcessor.dueySendItem(c, (byte) 0, (short) 0, (short) 0, 50_000, null, "Someone", false);
            db.verify(DatabaseConnection::getConnection, never());
        }
        verify(bot, never()).gainMeso(anyInt(), anyBoolean());
        assertFalse(DueyProcessor.openOrExplain(new client.BotClient(1, 1), true), "a bot never opens Duey");
    }

    @Test
    void aParcelToABotIsRefusedAndNothingIsCharged() throws Exception {
        YamlConfig.config.server.USE_DUEY = true;
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        when(c.tryacquireClient()).thenReturn(true);
        when(c.getPlayer()).thenReturn(chr);
        when(c.getAccID()).thenReturn(1);
        when(chr.getId()).thenReturn(42);
        when(chr.getName()).thenReturn("Player");
        when(chr.getMeso()).thenReturn(10_000_000);

        Connection con = mock(Connection.class);
        PreparedStatement lookup = mock(PreparedStatement.class);
        ResultSet botRow = mock(ResultSet.class);
        when(con.prepareStatement("SELECT id,accountid FROM characters WHERE name = ?")).thenReturn(lookup);
        when(lookup.executeQuery()).thenReturn(botRow);
        when(botRow.next()).thenReturn(true);
        when(botRow.getInt("accountid")).thenReturn(99);
        when(botRow.getInt("id")).thenReturn(1_000_000_007);

        try (var db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            DueyProcessor.dueySendItem(c, (byte) 0, (short) 0, (short) 0, 50_000, null, "SomeBot", false);
        }
        verify(con, never()).prepareStatement(org.mockito.ArgumentMatchers.startsWith("INSERT"), anyInt());
        verify(chr, never()).gainMeso(anyInt(), anyBoolean());
    }

    @Test
    void aWeddingInvitationToABotGuestIsNotParcelled() {
        try (var db = mockStatic(DatabaseConnection.class)) {
            DueyProcessor.dueyCreatePackage(mock(client.inventory.Item.class), 0, "Groom", 1_000_000_001);
            db.verify(DatabaseConnection::getConnection, never());
        }
    }
}
