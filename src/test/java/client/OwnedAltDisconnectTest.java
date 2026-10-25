package client;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.companions.OwnedAltService;
import static org.mockito.Mockito.*;

class OwnedAltDisconnectTest {
    @Test void headlessDisconnectUsesLifecycleInsteadOfPlayerLogoutSave() {
        BotClient c = new BotClient(1,1); Character alt = mock(Character.class);
        when(alt.isOwnedAlt()).thenReturn(true); c.setPlayer(alt);
        var service = mock(OwnedAltService.class);
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            c.forceDisconnect();
            verify(service).disconnectAlt(alt);
            verify(alt, never()).saveCharToDB(true);
            verify(alt, never()).logOff();
        }
    }
    @Test void changingAccountClosesOldOwnersAltsBeforeChangingAccountId() {
        Client c = mock(Client.class, CALLS_REAL_METHODS); c.setAccID(7);
        Character owner = mock(Character.class); c.setPlayer(owner);
        var service = mock(OwnedAltService.class);
        try (MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            services.when(OwnedAltService::getInstance).thenReturn(service);
            doAnswer(inv -> { org.junit.jupiter.api.Assertions.assertEquals(7,c.getAccID()); return 0; })
                    .when(service).dismissAll(owner,true);
            c.setAccID(8);
            verify(service).dismissAll(owner,true);
            org.junit.jupiter.api.Assertions.assertEquals(8,c.getAccID());
        }
    }
}
