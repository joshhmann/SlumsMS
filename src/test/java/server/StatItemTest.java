package server;

import client.inventory.Equip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class StatItemTest {

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @Test
    public void testStatItemAssignsLukAndIntCorrectly() {
        Equip equip;
        try (var ii = mockStatic(ItemInformationProvider.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(mock(ItemInformationProvider.class));
            equip = new Equip(1302000, (short) 1);
        }

        short str = 10;
        short dex = 20;
        short luk = 30;
        short int_ = 40;

        ItemInformationProvider.getInstance().statItem(equip, str, dex, luk, int_);

        assertEquals(str, equip.getStr());
        assertEquals(dex, equip.getDex());
        assertEquals(luk, equip.getLuk());
        assertEquals(int_, equip.getInt());
    }
}
