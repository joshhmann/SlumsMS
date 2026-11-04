package server.expeditions;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.expeditions.ExpeditionBossLog.BossLogEntry;
import soloMapling.server.SoloMaplingConstants;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChaosZakumQuotaTest {
    private static final int BOT_A = SoloMaplingConstants.GameConstants.BOT_BASE_ID + 1;
    private static final int BOT_B = SoloMaplingConstants.GameConstants.BOT_BASE_ID + 2;
    private boolean daily;

    @BeforeEach
    void on() {
        daily = YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = true;
    }

    @AfterEach
    void off() {
        YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS = daily;
        ExpeditionBossLog.chaosBotRunsToday = () -> 0;
        ExpeditionBossLog.entryCount = ExpeditionBossLog::countPlayerEntries;
    }

    private static Expedition exped(ExpeditionType t) {
        Expedition e = mock(Expedition.class);
        when(e.getType()).thenReturn(t);
        return e;
    }

    @Test
    void aPlayerEntersChaosZakumPastFiveEntries() {
        ExpeditionBossLog.entryCount = (cid, b) -> 99;
        ExpeditionBossLog.chaosBotRunsToday = () -> 99; // bots used everything
        assertTrue(ExpeditionBossLog.attemptBoss(12, 1, exped(ExpeditionType.CHAOS_ZAKUM), false));
        assertTrue(ExpeditionBossLog.hasEntryLeft(12, BossLogEntry.CHAOS_ZAKUM));
    }

    @Test
    void botsStopWhenTheSharedCountIsUsedAcrossDifferentBots() {
        ExpeditionBossLog.chaosBotRunsToday = () -> ExpeditionBossLog.CHAOS_ZAKUM_BOT_DAILY_RUNS - 1;
        assertTrue(ExpeditionBossLog.attemptBoss(BOT_A, 1, exped(ExpeditionType.CHAOS_ZAKUM), false));
        ExpeditionBossLog.chaosBotRunsToday = () -> ExpeditionBossLog.CHAOS_ZAKUM_BOT_DAILY_RUNS;
        assertFalse(ExpeditionBossLog.attemptBoss(BOT_A, 1, exped(ExpeditionType.CHAOS_ZAKUM), false));
        assertFalse(ExpeditionBossLog.attemptBoss(BOT_B, 1, exped(ExpeditionType.CHAOS_ZAKUM), false));
        assertFalse(ExpeditionBossLog.hasEntryLeft(BOT_B, BossLogEntry.CHAOS_ZAKUM));
    }

    @Test
    void normalZakumStaysPerCharacter() {
        ExpeditionBossLog.chaosBotRunsToday = () -> 0;
        ExpeditionBossLog.entryCount = (cid, b) -> 25;
        assertFalse(ExpeditionBossLog.attemptBoss(12, 1, exped(ExpeditionType.ZAKUM), false));
        assertFalse(ExpeditionBossLog.attemptBoss(BOT_A, 1, exped(ExpeditionType.ZAKUM), false));
        ExpeditionBossLog.entryCount = (cid, b) -> 24;
        assertTrue(ExpeditionBossLog.attemptBoss(12, 1, exped(ExpeditionType.ZAKUM), false));
    }
}
