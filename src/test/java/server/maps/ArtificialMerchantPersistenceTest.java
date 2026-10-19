package server.maps;

import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.HiredMerchantArtificial;
import tools.DatabaseConnection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtificialMerchantPersistenceTest {
    @Test
    void artificialMerchantDoesNotPersistInventoryOrOwnerState() throws Exception {
        HiredMerchantArtificial artificial = mock(HiredMerchantArtificial.class, CALLS_REAL_METHODS);
        HiredMerchant real = mock(HiredMerchant.class, CALLS_REAL_METHODS);
        assertFalse(((HiredMerchant) artificial).isPersistent());
        assertTrue(real.isPersistent());
        try (var database = mockStatic(DatabaseConnection.class)) {
            artificial.saveItems();
            database.verifyNoInteractions();
        }
    }
}
