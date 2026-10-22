package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.MiniDungeonInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotChatTuningTest {
    @AfterEach void cleanup() {
        for (String key : List.of("townMultiplier", "huntingMultiplier", "dungeonMultiplier",
                "ambientMapGapMs", "ambientWorldPerMinute")) System.clearProperty("solomapling.chat." + key);
        BotChatTuning.resetForTest();
    }

    MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }

    @ParameterizedTest @ValueSource(ints = {280030000, 280030001, 240060000, 240060201, 220080001,
            230040420, 270050100, 270050300, 211070100, 211070101, 271040100, 551030200,
            541020800, 800040410, 801040100, 105100300, 105100401, 450004750, 450004751,
            450007440, 450007441, 450010100, 450010101, 450012210, 450012211, 1064, 1065,
            401060100, 240070603, 541010100})
    void bossArenasAreQuietEvenWhenEmpty(int id) {
        assertTrue(BotChatTuning.isBossMap(id));
        assertEquals(BotChatTuning.MapType.DUNGEON, BotChatTuning.mapType(map(id)));
    }

    @ParameterizedTest @ValueSource(ints = {910000000, 910000001, 910000022, 993910000, 993910022})
    void freeMarketIsLivelyEvenWithoutTownFlag(int id) {
        assertEquals(BotChatTuning.MapType.TOWN, BotChatTuning.mapType(map(id)));
    }

    @Test void townsHuntingInstancesAndLiveBossesGetDifferentMultipliers() {
        MapleMap town = map(100000000);
        when(town.isTown()).thenReturn(true);
        assertEquals(1.5, BotChatTuning.multiplier(town));
        assertEquals(0.15, BotChatTuning.multiplier(map(104040000)));
        MapleMap instance = map(123);
        when(instance.getEventInstance()).thenReturn(mock(scripting.event.EventInstanceManager.class));
        assertEquals(0.01, BotChatTuning.multiplier(instance));
        MapleMap event = map(109040000);
        when(event.isEventMap()).thenReturn(true);
        assertEquals(0.01, BotChatTuning.multiplier(event));
        assertEquals(0.01, BotChatTuning.multiplier(map(MiniDungeonInfo.values()[0].getDungeonId())));
        Monster boss = mock(Monster.class);
        when(boss.isBoss()).thenReturn(true);
        when(town.getAllMonsters()).thenReturn(List.of(boss));
        assertEquals(0.01, BotChatTuning.multiplier(town));
    }

    @Test void mapSpacingUsesMultiplierAndIsSharedByBotsButNotChannels() {
        assertTrue(BotChatTuning.takeAmbient(0, 1, 1, 1.5, 1000));
        assertFalse(BotChatTuning.takeAmbient(0, 1, 1, 1.5, 2999));
        assertTrue(BotChatTuning.takeAmbient(0, 1, 1, 1.5, 3000));
        assertTrue(BotChatTuning.takeAmbient(0, 2, 1, 1.5, 3000));
        assertTrue(BotChatTuning.takeAmbient(0, 1, 2, 0.15, 1000));
        assertFalse(BotChatTuning.takeAmbient(0, 1, 2, 0.15, 20_999));
        assertTrue(BotChatTuning.takeAmbient(0, 1, 2, 0.15, 21_000));
        assertTrue(BotChatTuning.takeAmbient(0, 1, 3, 0.01, 1000));
        assertFalse(BotChatTuning.takeAmbient(0, 1, 3, 0.01, 300_999));
        assertTrue(BotChatTuning.takeAmbient(0, 1, 3, 0.01, 301_000));
    }

    @Test void worldBudgetUsesSlidingWindowAcrossMapsAndChannelsWithNoBurstAfterSilence() {
        System.setProperty("solomapling.chat.ambientWorldPerMinute", "2");
        assertTrue(BotChatTuning.takeAmbient(0, 1, 1, 1.5, 1000));
        assertTrue(BotChatTuning.takeAmbient(0, 2, 2, 1.5, 1000));
        assertFalse(BotChatTuning.takeAmbient(0, 3, 3, 1.5, 60_999));
        assertTrue(BotChatTuning.takeAmbient(1, 3, 3, 1.5, 60_999));
        assertTrue(BotChatTuning.takeAmbient(0, 3, 3, 1.5, 61_000));
        assertFalse(BotChatTuning.takeAmbient(0, 3, 3, 1.5, 61_000));
    }

    @Test void zeroDisablesAmbientAndBadMultipliersFallBackSafely() {
        assertFalse(BotChatTuning.takeAmbient(0, 1, 1, 0, 1000));
        for (String bad : List.of("NaN", "Infinity", "-1", "oops")) {
            System.setProperty("solomapling.chat.townMultiplier", bad);
            assertEquals(1.5, BotChatTuning.multiplier(BotChatTuning.MapType.TOWN));
        }
        System.setProperty("solomapling.chat.townMultiplier", "0");
        assertEquals(0, BotChatTuning.multiplier(BotChatTuning.MapType.TOWN));
        System.setProperty("solomapling.chat.ambientWorldPerMinute", "0");
        assertFalse(BotChatTuning.takeAmbient(0, 1, 1, 1.5, 1000));
    }
}
