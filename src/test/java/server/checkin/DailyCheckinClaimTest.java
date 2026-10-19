package server.checkin;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyCheckinClaimTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final int ACCOUNT = 42;

    private final List<DailyCheckinStreak> saved = new ArrayList<>();

    private DailyCheckin.Store store(boolean failSaves) {
        return new DailyCheckin.Store() {
            @Override
            public DailyCheckinStreak load(int accountId) {
                return DailyCheckinStreak.NEW;
            }

            @Override
            public void save(int accountId, DailyCheckinStreak streak) throws SQLException {
                if (failSaves) {
                    throw new SQLException("down");
                }
                saved.add(streak);
            }
        };
    }

    private static Character player(boolean roomForMesos) {
        Character chr = mock(Character.class);
        when(chr.getName()).thenReturn("Claimer");
        when(chr.canHoldMeso(anyInt())).thenReturn(roomForMesos);
        return chr;
    }

    @AfterEach
    void tearDown() {
        DailyCheckin.useForTest(null);
    }

    @Test
    void aValidClaimIsRecordedThenRewarded() {
        DailyCheckin.useForTest(store(false));
        Character chr = player(true);

        assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(chr, ACCOUNT, DailyCheckinStreak.NEW, 1, NOW));
        assertEquals(1, saved.size());
        assertEquals(1, saved.get(0).day());
        verify(chr).gainMeso(DailyCheckinRewards.of(1).mesos(), true);
    }

    @Test
    void aDayThatIsNotClaimableGrantsNothing() {
        DailyCheckin.useForTest(store(false));
        Character chr = player(true);

        assertEquals(DailyCheckin.ClaimResult.NOT_CLAIMABLE, DailyCheckin.claim(chr, ACCOUNT, DailyCheckinStreak.NEW, 5, NOW));
        assertTrue(saved.isEmpty());
        verify(chr, never()).gainMeso(anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void noRoomKeepsTheDayClaimableAndGrantsNothing() {
        DailyCheckin.useForTest(store(false));
        Character chr = player(false);

        assertEquals(DailyCheckin.ClaimResult.NO_ROOM, DailyCheckin.claim(chr, ACCOUNT, DailyCheckinStreak.NEW, 1, NOW));
        assertTrue(saved.isEmpty(), "the day is not used up when the reward cannot be delivered");
    }

    @Test
    void aFailedWriteGrantsNothing() {
        DailyCheckin.useForTest(store(true));
        Character chr = player(true);

        assertEquals(DailyCheckin.ClaimResult.NOT_SAVED, DailyCheckin.claim(chr, ACCOUNT, DailyCheckinStreak.NEW, 1, NOW));
        verify(chr, never()).gainMeso(anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
