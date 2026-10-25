package client.inventory.manipulator;

import client.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.2: InventoryManipulator.checkSpace took c.getPlayer() and used it straight away, so a client whose character had
 * already gone - a proposal or a shop packet read after the disconnect - threw "Cannot invoke
 * Character.getInventory(InventoryType) because chr is null" from inside the packet handler (live server, via
 * RingActionHandler.sendEngageProposal) and the handler logged "Error in packet handler". There is no inventory to
 * check for a player who is gone, so the answer is "no space".
 */
class CheckSpaceWithoutPlayerTest {

    @Test
    void spaceCheckForAClientWithNoPlayerSaysNoInsteadOfThrowing() {
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(null);

        assertDoesNotThrow(() -> InventoryManipulator.checkSpace(c, 4000000, 1, ""));
        assertFalse(InventoryManipulator.checkSpace(c, 4000000, 1, ""));
    }
}
