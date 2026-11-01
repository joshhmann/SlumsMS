package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import net.packet.InPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.StatEffect;
import server.reports.PlayerTrail;
import soloMapling.qa.QaLog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.3 b280: a pet's auto-potion request leaves a QA line (when a QA run watches {@code PETPOT}) and always a
 * {@code petpot} entry in the player's trail, with the reason when the server refused it. Before this, a pet that
 * would not drink (wrong slot, no pots, a dead character) was invisible: the client sent the request and nothing said
 * why nothing happened. Ticket s-petpotqa; logging only, no gameplay change.
 */
class PetAutoPotQaTest {

    private static final int RED_POTION = 2000000;

    private QaLog.SinkHandle handle;
    private final List<String> lines = new ArrayList<>();

    @BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @AfterEach
    void closeSink() {
        if (handle != null) {
            handle.close();
            handle = null;
        }
        lines.clear();
    }

    @Test
    void aRefusedRequestIsALoggedLineAndATrailEvent() {
        listen();
        Character chr = gm();
        PlayerTrail trail = trailOf(chr);

        handle(new PetAutoPotHandler(), chr, (short) 3, RED_POTION);

        assertEquals(1, lines.size(), "one PETPOT line per request");
        String line = lines.get(0);
        assertTrue(line.startsWith("PETPOT recv Admin slot 3 item " + RED_POTION + " hp 40/100 mp 10/50 map 100000000"),
                "the line carries the HP the client's pet saw: " + line);
        assertTrue(line.endsWith("refused empty-slot"), "the reason is in the line: " + line);

        PlayerTrail.Entry event = trail.events().getLast();
        assertEquals("petpot", event.kind());
        assertEquals(RED_POTION, event.id());
        assertEquals("empty-slot", event.note(), "the trail says why, so a report can be explained later");
    }

    @Test
    void theTrailRecordsItEvenWhenNoQaRunWatches() {
        Character chr = gm();
        PlayerTrail trail = trailOf(chr);

        handle(new PetAutoPotHandler(), chr, (short) 3, RED_POTION);

        assertTrue(lines.isEmpty(), "the QA line waits for a QA run: no line without one");
        assertEquals("petpot", trail.events().getLast().kind(), "the trail is always on");
    }

    private void listen() {
        handle = QaLog.sink((topic, mapId, ids, message) -> lines.add(message));
        handle.topics(EnumSet.of(QaLog.Topic.PETPOT));
    }

    private static PlayerTrail trailOf(Character chr) {
        return chr.bugTrail();
    }

    /** A GM on map 100000000 at 40/100 HP and 10/50 MP, with an empty USE slot 3 (the usual "it did not drink"). */
    private static Character gm() {
        Character chr = mock(Character.class);
        when(chr.getHp()).thenReturn(40);
        when(chr.getMaxHp()).thenReturn(100);
        when(chr.getMp()).thenReturn(10);
        when(chr.getMaxMp()).thenReturn(50);
        when(chr.getId()).thenReturn(7);
        when(chr.getName()).thenReturn("Admin");
        when(chr.getMapId()).thenReturn(100000000);
        when(chr.isAlive()).thenReturn(true);
        when(chr.countItem(RED_POTION)).thenReturn(0);
        when(chr.bugTrail()).thenReturn(new PlayerTrail());
        when(chr.getInventory(InventoryType.USE)).thenReturn(mock(Inventory.class));
        return chr;
    }

    private static void handle(PetAutoPotHandler handler, Character chr, short slot, int itemId) {
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        InPacket p = mock(InPacket.class);
        when(p.readShort()).thenReturn(slot);
        when(p.readInt()).thenReturn(0, itemId);

        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.getItemEffect(itemId)).thenReturn(mock(StatEffect.class));
        try (var iip = mockStatic(ItemInformationProvider.class)) {
            iip.when(ItemInformationProvider::getInstance).thenReturn(ii);
            handler.handlePacket(p, c);
        }
    }
}
