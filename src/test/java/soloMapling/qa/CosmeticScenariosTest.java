package soloMapling.qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import server.ItemInformationProvider;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CosmeticScenariosTest {
    @TempDir Path directory;

    @BeforeAll static void initializeProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @Test void checksTheSalonFilterWithoutGivingItemsOrChangingAppearance() throws Exception {
        var world = mock(QaWorld.class);
        var gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(gm.getClient()).thenReturn(mock(client.Client.class));
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class);
             var singleton = mockStatic(ItemInformationProvider.class)) {
            var names = mock(ItemInformationProvider.class);
            singleton.when(ItemInformationProvider::getInstance).thenReturn(names);
            when(names.getName(anyInt())).thenReturn("Named style");
            var suite = QaTestSupport.suite(CosmeticScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
            suite.start(gm, "sl-1", new String[0]);
            assertTrue(Files.readString(suite.runOf(1).logPath()).contains("SALON LOOKUP PASS styles=7"));
            verifyNoInteractions(world);
            verify(gm, never()).setFace(anyInt());
            verify(gm, never()).setHair(anyInt());
            suite.stop(1, "done");
            when(names.getName(20000)).thenReturn(null);
            assertThrows(QaRefusal.class, () -> suite.start(gm, "sl-1", new String[0]));
        }
    }
}
