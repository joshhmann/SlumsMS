package server;

import client.Client;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import provider.Data;
import scripting.npc.NPCConversationManager;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercise the real name lookup and salon filtering without requiring WZ files on CI. */
class CosmeticLookupTest {
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

    private static ItemInformationProvider names() {
        var ii = mock(ItemInformationProvider.class, CALLS_REAL_METHODS);
        ii.nameCache = new HashMap<>();
        ii.eqpStringData = mock(Data.class);
        return ii;
    }

    private static void name(ItemInformationProvider ii, String category, int id) {
        Data entry = mock(Data.class), text = mock(Data.class);
        when(ii.eqpStringData.getChildByPath("Eqp/" + category + "/" + id)).thenReturn(entry);
        when(entry.getChildByPath("name")).thenReturn(text);
        when(text.getData()).thenReturn("Style " + id);
    }

    @Test
    void stockFacesAndExtendedStylesSurviveTheRealSalonNameFilter() {
        var ii = names();
        int[] faces = {20000, 21700, 54859}, hair = {30060, 36000, 44000, 64467};
        for (int id : faces) name(ii, "Face", id);
        for (int id : hair) name(ii, "Hair", id);
        var cm = new NPCConversationManager(mock(Client.class), 1012104, "1012104");
        try (var singleton = mockStatic(ItemInformationProvider.class)) {
            singleton.when(ItemInformationProvider::getInstance).thenReturn(ii);
            for (int[] group : new int[][]{faces, hair}) for (int id : group) {
                assertEquals("Style " + id, ii.getName(id), "string category for " + id);
                assertEquals(id, cm.getCosmeticItem(id), "salon must retain named style " + id);
            }
            assertEquals(-1, cm.getCosmeticItem(29999), "an ID range alone does not invent a style");
        }
    }

    @Test
    void extendedFaceUsesFaceColorFallbackAndComparesTheEquippedFace() {
        var ii = names();
        name(ii, "Face", 54059);
        Client client = mock(Client.class);
        client.Character player = mock(client.Character.class);
        when(client.getPlayer()).thenReturn(player);
        when(player.getFace()).thenReturn(54859);
        when(player.getHair()).thenReturn(30060);
        var cm = new NPCConversationManager(client, 1012104, "1012104");
        try (var singleton = mockStatic(ItemInformationProvider.class)) {
            singleton.when(ItemInformationProvider::getInstance).thenReturn(ii);
            assertEquals(54059, cm.getCosmeticItem(54859), "strip face color, not the final hair digit");
            assertTrue(cm.isCosmeticEquipped(54859));
            assertFalse(cm.isCosmeticEquipped(54059));
        }
    }

    @Test
    void extendedHairKeepsHairColorFallbackAndEquippedComparison() {
        var ii = names();
        name(ii, "Hair", 64460);
        Client client = mock(Client.class);
        client.Character player = mock(client.Character.class);
        when(client.getPlayer()).thenReturn(player);
        when(player.getFace()).thenReturn(20000);
        when(player.getHair()).thenReturn(64467);
        var cm = new NPCConversationManager(client, 1012104, "1012104");
        try (var singleton = mockStatic(ItemInformationProvider.class)) {
            singleton.when(ItemInformationProvider::getInstance).thenReturn(ii);
            assertEquals(64460, cm.getCosmeticItem(64467));
            assertTrue(cm.isCosmeticEquipped(64467));
            assertFalse(cm.isCosmeticEquipped(64460));
        }
    }
}
