package server.checkin;

import client.Character;
import client.Client;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.1: the level 10 and 48-hour rules are shown where players claim, and refusals say why. */
class DailyCheckinMessagesTest {

    private static final long DAY = 86_400_000L;
    private boolean enabledWas;
    private final List<String> said = new ArrayList<>();
    private DailyCheckinStreak stored = DailyCheckinStreak.NEW;

    @BeforeEach
    void setUp() {
        enabledWas = YamlConfig.config.server.DAILY_CHECKIN_ENABLED;
        YamlConfig.config.server.DAILY_CHECKIN_ENABLED = true;
        DailyCheckin.useForTest(new DailyCheckin.Store() {
            @Override
            public DailyCheckinStreak load(int accountId) {
                return stored;
            }

            @Override
            public void save(int accountId, DailyCheckinStreak streak) {
                stored = streak;
            }
        });
    }

    @AfterEach
    void tearDown() {
        YamlConfig.config.server.DAILY_CHECKIN_ENABLED = enabledWas;
        DailyCheckin.useForTest(null);
    }

    private Character chr(int level) {
        return client(level).getPlayer();
    }

    private Client client(int level) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(5000);
        when(chr.getLevel()).thenReturn(level);
        doAnswer(c -> said.add(c.getArgument(1))).when(chr).dropMessage(anyInt(), anyString());
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        when(c.getAccID()).thenReturn(77);
        return c;
    }

    @Test
    void dailyShowsBothRules() {
        DailyCheckin.notes(chr(30), DailyCheckinStreak.NEW, System.currentTimeMillis());
        String all = String.join(" | ", said);
        assertTrue(all.contains("level " + YamlConfig.config.server.DAILY_CHECKIN_MIN_LEVEL), all);
        assertTrue(all.contains("48 hours") && all.contains("restarts at day 1"), all);
    }

    @Test
    void aLapsedStreakIsExplained() {
        long now = System.currentTimeMillis();
        stored = new DailyCheckinStreak(5, 31, now - 3 * DAY);
        DailyCheckin.notes(chr(30), stored, now);
        assertTrue(String.join(" | ", said).contains("more than 48 hours passed"), said.toString());
    }

    @Test
    void aLiveStreakIsNotCalledLapsed() {
        stored = new DailyCheckinStreak(5, 31, System.currentTimeMillis() - 30 * 3_600_000L);
        DailyCheckin.notes(chr(30), stored, System.currentTimeMillis());
        assertFalse(String.join(" | ", said).contains("more than 48 hours passed"), said.toString());
    }

    @Test
    void belowTheLevelTheMessageSaysWhichLevelAndWhere() {
        DailyCheckin.open(client(4));
        assertEquals(1, said.size());
        assertTrue(said.get(0).contains("Level 10") && said.get(0).contains("Level 4"), said.get(0));
    }

    @Test
    void aClaimFromTheWindowBelowTheLevelSaysWhy() {
        DailyCheckin.handleRequest(client(4), 1);
        assertEquals(1, said.size());
        assertTrue(said.get(0).contains("Level 10"), said.get(0));
    }

    @Test
    void aTooEarlyClaimSaysWhenTheNextOneUnlocks() {
        long now = System.currentTimeMillis();
        stored = new DailyCheckinStreak(1, 1, now - 3_600_000L);
        assertEquals(DailyCheckin.ClaimResult.NOT_CLAIMABLE, DailyCheckin.claim(chr(30), 77, stored, 2, now));
        assertTrue(said.stream().anyMatch(s -> s.contains("already claimed today") && s.contains("23h")), said.toString());
    }

    @Test
    void aWrongDaySaysWhichDayIsToday() {
        String msg = DailyCheckin.refusal(DailyCheckinStreak.NEW, 5, System.currentTimeMillis());
        assertTrue(msg.contains("day 1") && msg.contains("day 5"), msg);
    }
}
