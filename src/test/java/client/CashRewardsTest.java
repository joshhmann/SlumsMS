package client;

import constants.id.ItemId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CashRewardsTest {
    @Test void nxCardStacksPayPerCard() {
        assertEquals(100, ItemId.nxCardValue(ItemId.NX_CARD_100, 1));
        assertEquals(300, ItemId.nxCardValue(ItemId.NX_CARD_100, 3));
        assertEquals(500, ItemId.nxCardValue(ItemId.NX_CARD_250, 2));
        assertEquals(250, ItemId.nxCardValue(ItemId.NX_CARD_250, 0), "a bad quantity still pays one card");
        assertEquals(0, ItemId.nxCardValue(4000000, 5));
    }

    @Test void levelUpSlotsReportNothingAtTheCap() {
        Character chr = mock(Character.class);
        when(chr.gainLevelUpSlots()).thenCallRealMethod();
        when(chr.gainSlots(anyInt(), anyInt(), anyBoolean())).thenReturn(false);
        assertFalse(chr.gainLevelUpSlots());
        verify(chr, times(4)).gainSlots(anyInt(), eq(4), eq(true));
    }

    @Test void levelUpSlotsReportAGainWhenAnyTabGrew() {
        Character chr = mock(Character.class);
        when(chr.gainLevelUpSlots()).thenCallRealMethod();
        when(chr.gainSlots(anyInt(), anyInt(), anyBoolean())).thenReturn(false);
        when(chr.gainSlots(3, 4, true)).thenReturn(true);
        assertTrue(chr.gainLevelUpSlots());
    }
}
