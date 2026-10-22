package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.Trade;

import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotWhispersTest {
    @AfterEach void cleanup() {
        BotWhispers.resetForTest();
        System.clearProperty("solomapling.chat.whisperPlayerGapMs");
    }

    @Test void allBotsShareOneRecipientBudgetAndBoundary() {
        assertTrue(BotWhispers.take(1, false, 1000));
        assertFalse(BotWhispers.take(1, false, 600_999));
        assertTrue(BotWhispers.take(2, false, 600_999));
        assertTrue(BotWhispers.take(1, false, 601_000));
    }

    @Test void repliesDoNotConsumeOrResetUnsolicitedBudget() {
        assertTrue(BotWhispers.take(1, true, 1000));
        assertTrue(BotWhispers.take(1, false, 1000));
        assertTrue(BotWhispers.take(1, true, 600_999));
        assertFalse(BotWhispers.take(1, false, 600_999));
        assertTrue(BotWhispers.take(1, false, 601_000));
    }

    @Test void concurrentBotsCannotRacePastTheCap() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 100; i++) attempts.add(() -> BotWhispers.take(1, false, 1000));
            int sent = 0;
            for (Future<Boolean> result : pool.invokeAll(attempts)) if (result.get()) sent++;
            assertEquals(1, sent);
        }
    }

    @Test void configurableIntervalAndNoCatchUpBurst() {
        System.setProperty("solomapling.chat.whisperPlayerGapMs", "100");
        assertTrue(BotWhispers.take(1, false, 1000));
        assertFalse(BotWhispers.take(1, false, 1099));
        assertTrue(BotWhispers.take(1, false, 100_000));
        assertFalse(BotWhispers.take(1, false, 100_000));
    }

    @Test void repliesGoThroughWhileBusyButUnsolicitedWhispersAreDropped() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(1);
        when(player.getTrade()).thenReturn(mock(Trade.class));
        assertFalse(BotWhispers.send(player, "bot", 0, "hi", false));
        verify(player, never()).sendPacket(any());
        assertTrue(BotWhispers.send(player, "bot", 0, "hi", true));
        verify(player).sendPacket(any());
        assertTrue(BotWhispers.take(1, false, System.currentTimeMillis()));
    }

    @Test void successfulDeliveryIsCappedAcrossDifferentBotNames() {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(42);
        assertTrue(BotWhispers.send(player, "first", 0, "hi", false));
        assertFalse(BotWhispers.send(player, "second", 1, "hi", false));
        assertTrue(BotWhispers.send(player, "second", 1, "answer", true));
        verify(player, times(2)).sendPacket(any());
    }
}
