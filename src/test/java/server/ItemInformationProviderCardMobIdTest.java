package server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * {@code getCardMobId} is called while the character-info packet is being built, before the character reaches its
 * map, from a try that only prints the stack trace - so a cover id with no {@code monstercarddata} row used to stop a
 * login dead. 0 is the "no cover" value that packet already writes.
 */
class ItemInformationProviderCardMobIdTest {

    /** ItemInformationProvider reads its tables on construction, so it needs a database to exist at all. */
    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @Test
    void anUnknownCardIdIsZeroInsteadOfAnException() {
        assertEquals(0, ItemInformationProvider.getInstance().getCardMobId(2380999),
                "a cover id with no monstercarddata row is the no-cover sentinel, not a NullPointerException");
    }

    @Test
    void aKnownCardIdStillAnswersWithItsMob() {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        ii.monsterBookID.put(2380000, 100100);
        try {
            assertEquals(100100, ii.getCardMobId(2380000), "the lookup itself is unchanged");
        } finally {
            ii.monsterBookID.remove(2380000);
        }
    }
}
