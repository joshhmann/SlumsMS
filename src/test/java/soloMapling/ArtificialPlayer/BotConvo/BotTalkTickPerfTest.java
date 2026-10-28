package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.maps.MapObject;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 (t-bt-1 tick cost): ~3,000 synthetic bots over 200 maps. Asserts operation counts and the tick's decisions
 * (never wall time, so it cannot flake); prints the average tick time for the record.
 */
class BotTalkTickPerfTest {
    static final int BOTS = 3000, MAPS = 200, BASE = 880_000;
    final AtomicInteger monsterScans = new AtomicInteger();
    final List<Integer> ids = new ArrayList<>();
    final Map<Integer, Integer> mapOfBot = new HashMap<>();
    final Map<Integer, Character> chars = new HashMap<>();
    MapleMap[] maps;

    @BeforeEach
    void world() {
        System.setProperty("slums.bottalk", "true");
        System.setProperty("slums.bottalk.gapMin", "1000");
        System.setProperty("slums.bottalk.gapMax", "1000");
        BotTalk.resetForTest();
        maps = new MapleMap[MAPS];
        for (int m = 0; m < MAPS; m++) {
            MapleMap map = mock(MapleMap.class, Mockito.withSettings().stubOnly());
            when(map.getId()).thenReturn(100_000_000 + m * 10);
            when(map.getChannelId()).thenReturn(1);
            when(map.isTown()).thenReturn(m % 4 == 0);          // a quarter are towns
            when(map.isEventMap()).thenReturn(m % 50 == 7);     // a few event maps (weight 0)
            when(map.getMonsters()).thenAnswer(i -> {
                monsterScans.incrementAndGet();
                return List.<MapObject>of(mock(MapObject.class));
            });
            when(map.getAllMonsters()).thenAnswer(i -> {
                monsterScans.incrementAndGet();
                return List.<server.life.Monster>of();
            });
            maps[m] = map;
        }
        Random rng = new Random(42);
        for (int i = 0; i < BOTS; i++) {
            int id = 900_000_000 + i;
            int m = rng.nextInt(MAPS);
            Character c = mock(Character.class, Mockito.withSettings().stubOnly());
            when(c.getId()).thenReturn(id);
            when(c.getMap()).thenReturn(maps[m]);
            int mapId = 100_000_000 + m * 10;
            when(c.getMapId()).thenReturn(mapId);
            BotSM sm = mock(BotSM.class, Mockito.withSettings().stubOnly());
            when(sm.getChr()).thenReturn(c);
            CharacterStorage.addActiveBot(id, sm);
            ids.add(id);
            mapOfBot.put(id, m);
            chars.put(id, c);
        }
    }

    @AfterEach
    void clean() {
        for (int id : ids) {
            CharacterStorage.removeActiveBot(id);
        }
        BotTalk.resetForTest();
        System.clearProperty("slums.bottalk");
        System.clearProperty("slums.bottalk.gapMin");
        System.clearProperty("slums.bottalk.gapMax");
    }

    @Test
    void theTickDecidesTheSameAndReadsFewMaps() {
        BotTalk.tick(1_000_000);
        // every bot is remembered on its map; nobody arrived on a first look
        for (int id : ids) {
            assertEquals(BotTalk.key(1, (100_000_000 + mapOfBot.get(id) * 10)), BotTalk.lastMapOfForTest(id));
        }
        // a map that was due (2+ bots, not an event map) got its next talk time; the others did not
        Map<Integer, Integer> count = new HashMap<>();
        mapOfBot.values().forEach(m -> count.merge(m, 1, Integer::sum));
        int expectedDue = 0;
        for (int m = 0; m < MAPS; m++) {
            boolean should = count.getOrDefault(m, 0) >= 2 && m % 50 != 7;
            if (should) {
                expectedDue++;
            }
            assertEquals(should, BotTalk.nextTalkAtForTest(BotTalk.key(1, 100_000_000 + m * 10)) != null, "map " + m);
        }
        assertTrue(expectedDue > 100);
        // the monster list is read only for non-town, non-event maps
        int mobScans = 0;
        for (int m = 0; m < MAPS; m++) {
            if (count.getOrDefault(m, 0) >= 2 && m % 4 != 0 && m % 50 != 7) {
                mobScans++;
            }
        }
        assertEquals(mobScans, monsterScans.get(), "monster scans (town and event maps decide without one)");
        // a second tick while nothing is due: no map read at all
        monsterScans.set(0);
        BotTalk.tick(1_010_000);
        assertEquals(0, monsterScans.get());
        // a bot leaves the registry: forgotten; one moves: arrived (and counted as such next tick)
        int gone = ids.get(0);
        CharacterStorage.removeActiveBot(gone);
        BotTalk.tick(1_020_000);
        assertEquals(null, BotTalk.lastMapOfForTest(gone));
        for (int id : ids.subList(1, 50)) {
            assertEquals(BotTalk.key(1, (100_000_000 + mapOfBot.get(id) * 10)), BotTalk.lastMapOfForTest(id));
        }
        CharacterStorage.addActiveBot(gone, mock(BotSM.class));
    }

    @Test
    void benchmark() {
        for (int i = 0; i < 20; i++) { // warm up
            BotTalk.resetForTest();
            BotTalk.tick(2_000_000 + i);
        }
        long total = 0;
        int ticks = 200;
        int lastScans = 0;
        for (int i = 0; i < ticks; i++) {
            BotTalk.resetForTest(); // every map due again: the worst tick, as after a restart
            long t0 = System.nanoTime();
            BotTalk.tick(3_000_000 + i);
            total += System.nanoTime() - t0;
            lastScans = monsterScans.getAndSet(0);
        }
        System.out.printf("TICKPERF worst-case tick (all maps due): %.3f ms avg%n", total / (double) ticks / 1e6);
        long m0 = System.nanoTime();
        for (int r = 0; r < 20; r++) {
            for (int id : ids) {
                chars.get(id).getMap().getChannelId();
                chars.get(id).getId();
            }
        }
        System.out.println("TICKPERF monster-list reads on the last worst-case tick: " + lastScans);
        System.out.printf("TICKPERF mock-call floor (3 mock calls per bot): %.3f ms%n", (System.nanoTime() - m0) / 20e6);
        monsterScans.set(0);
        total = 0;
        for (int i = 0; i < ticks; i++) {
            long t0 = System.nanoTime();
            BotTalk.tick(4_000_000 + i * 10L);
            total += System.nanoTime() - t0;
        }
        System.out.printf("TICKPERF steady tick (nothing due): %.3f ms avg%n", total / (double) ticks / 1e6);
    }
}
