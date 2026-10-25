package server;

import client.Character;
import client.Client;
import client.SkinColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import provider.Data;
import scripting.npc.NPCConversationManager;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** 1.5.2: the Cotton Stylist is open to all, so a forged hair/face/skin id must not reach the character. */
class StylistLookValidationTest {
    @BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    private static void name(ItemInformationProvider ii, String category, int id) {
        Data entry = mock(Data.class), text = mock(Data.class);
        when(ii.eqpStringData.getChildByPath("Eqp/" + category + "/" + id)).thenReturn(entry);
        when(entry.getChildByPath("name")).thenReturn(text);
        when(text.getData()).thenReturn("Style " + id);
    }

    @Test
    void onlyRealStylesAreApplied() {
        var ii = mock(ItemInformationProvider.class, CALLS_REAL_METHODS);
        ii.nameCache = new HashMap<>();
        ii.eqpStringData = mock(Data.class);
        name(ii, "Hair", 30060);
        name(ii, "Face", 20000);
        Client client = mock(Client.class);
        Character player = mock(Character.class);
        when(client.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("Tester");
        var cm = new NPCConversationManager(client, 9900000, "STYLIST");
        try (var singleton = mockStatic(ItemInformationProvider.class)) {
            singleton.when(ItemInformationProvider::getInstance).thenReturn(ii);

            assertTrue(cm.setHair(30060));
            verify(player).setHair(30060);
            assertTrue(cm.setFace(20000));
            verify(player).setFace(20000);
            assertTrue(cm.setSkin(9));
            verify(player).setSkinColor(SkinColor.WHITE);

        }
        try (var singleton = mockStatic(ItemInformationProvider.class)) {
            singleton.when(ItemInformationProvider::getInstance).thenReturn(ii);
            Character fresh = mock(Character.class);
            when(client.getPlayer()).thenReturn(fresh);
            when(fresh.getName()).thenReturn("Tester");
            assertFalse(cm.setHair(1002140), "a hat is not a hair");
            assertFalse(cm.setHair(39999 + 1000000), "out of range");
            assertFalse(cm.setHair(35000), "in range but no such hair");
            assertFalse(cm.setFace(20000 + 5000), "in range but no such face");
            assertFalse(cm.setFace(30060), "a hair id is not a face");
            assertFalse(cm.setSkin(7), "no such skin");
            assertFalse(cm.setSkin(-1));
            verify(fresh, never()).setHair(anyInt());
            verify(fresh, never()).setFace(anyInt());
            verify(fresh, never()).setSkinColor(any());
        }
    }
}
