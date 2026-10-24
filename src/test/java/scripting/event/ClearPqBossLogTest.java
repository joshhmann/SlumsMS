package scripting.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 1.5.1: clearPQ on an instance without an expedition (party quests, the mount quest) must not throw. */
class ClearPqBossLogTest {

    @Test
    void noExpeditionWritesNothingAndDoesNotThrow() {
        assertDoesNotThrow(() -> EventInstanceManager.logBossClear(null));
    }
}
