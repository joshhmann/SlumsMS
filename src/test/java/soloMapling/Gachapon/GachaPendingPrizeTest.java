package soloMapling.Gachapon;

import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.AbstractPlayerInteraction;

import java.io.StringReader;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 1.5.0 rc6 bug hunt (P1): a prize that didn't fit was refused without using the ticket and thrown away, so a full
 * inventory turned every ticket into free rerolls until a rare came up. A ticket's roll is now committed: the same
 * prize is offered until it fits, and only then is the ticket taken.
 */
class GachaPendingPrizeTest {

    static final String POOL = """
            tiers:
              common:
                weight: 1
              uncommon:
                weight: 1
              rare:
                weight: 1
            towns:
              henesys:
                name: Henesys
                npc: 9100100
                maps:
                  - 100000100
              ellinia:
                name: Ellinia
                npc: 9100101
                maps:
                  - 101000000
            shared:
              name: All machines
              common:
                - 2000004 x20
                - 4000000 x5
              uncommon:
                - 2000005 x10
              rare:
                - 2049100
                - 2049101
            """;

    MockedStatic<server.ItemInformationProvider> itemData;
    MockedStatic<InventoryManipulator> inv;
    final AtomicBoolean room = new AtomicBoolean(false);
    Character chr;
    AbstractPlayerInteraction api;

    @BeforeAll
    static void initItemProvider() throws Exception {
        BotGachaTest.initItemProvider();
    }

    @BeforeEach
    void setUp() throws Exception {
        itemData = mockStatic(server.ItemInformationProvider.class);
        itemData.when(server.ItemInformationProvider::getInstance).thenReturn(mock(server.ItemInformationProvider.class));
        GachaPools.setForTest(GachaPools.parse(new StringReader(POOL), id -> true));
        GachaMachine.clearPendingForTest();
        inv = mockStatic(InventoryManipulator.class);
        inv.when(() -> InventoryManipulator.checkSpace(any(Client.class), anyInt(), anyInt(), anyString())).thenAnswer(a -> room.get());

        chr = mock(Character.class);
        Client c = mock(Client.class);
        api = mock(AbstractPlayerInteraction.class);
        when(chr.getId()).thenReturn(42);
        when(chr.getName()).thenReturn("Tester");
        when(chr.getClient()).thenReturn(c);
        when(chr.getItemQuantity(eq(GachaMachine.TICKET), anyBoolean())).thenReturn(5);
        when(c.getAbstractPlayerInteraction()).thenReturn(api);
        when(api.gainItem(anyInt(), anyShort(), anyBoolean(), anyBoolean()))
                .thenAnswer(a -> new Item(a.getArgument(0), (short) 0, a.getArgument(1)));
    }

    @AfterEach
    void tearDown() {
        inv.close();
        itemData.close();
        GachaPools.setForTest(null);
        GachaMachine.clearPendingForTest();
    }

    private GachaPools.Town town(String key) {
        return GachaPools.get().town(key);
    }

    @Test
    void aFullInventoryCannotRerollTheTicket() {
        GachaPools.Town henesys = town("henesys");
        Random rng = new Random(1);
        GachaMachine.Result first = GachaMachine.playForPlayer(chr, henesys, GachaMachine.TICKET, "test", rng);
        assertFalse(first.ok());
        assertNotNull(first.prize());
        for (int i = 0; i < 40; i++) {
            GachaMachine.Result again = GachaMachine.playForPlayer(chr, henesys, GachaMachine.TICKET, "test", rng);
            assertFalse(again.ok());
            assertEquals(first.prize(), again.prize(), "the same prize is offered again, not a fresh roll");
        }
        inv.verify(() -> InventoryManipulator.removeById(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()),
                never());

        room.set(true);
        GachaMachine.Result claimed = GachaMachine.playForPlayer(chr, henesys, GachaMachine.TICKET, "test", rng);
        assertTrue(claimed.ok());
        assertEquals(first.prize(), claimed.prize(), "the waiting prize is what the ticket finally pays out");
        inv.verify(() -> InventoryManipulator.removeById(any(), any(), eq(GachaMachine.TICKET), eq(1), anyBoolean(),
                anyBoolean()), times(1));
        assertNull(GachaMachine.pendingPrize(chr, henesys), "claimed, so the next ticket rolls fresh");
    }

    @Test
    void aPendingPrizeBelongsToItsTown() {
        GachaMachine.playForPlayer(chr, town("henesys"), GachaMachine.TICKET, "test", new Random(3));
        assertNotNull(GachaMachine.pendingPrize(chr, town("henesys")));
        assertNull(GachaMachine.pendingPrize(chr, town("ellinia")));
    }

    @Test
    void aGmForcedRollLeavesNothingPending() {
        GachaMachine.playForPlayer(chr, town("henesys"), 0, "gm", new Random(3));
        assertNull(GachaMachine.pendingPrize(chr, town("henesys")));
    }
}
