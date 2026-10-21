package server.maps;

import client.BotClient;
import client.Character;
import net.opcodes.SendOpcode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.4.2: what an entering player gets at once and what follows, and that the follow-up never spawns a stale object. */
class MapEntryPlacementTest {
    static final int SPAWN_PLAYER = SendOpcode.SPAWN_PLAYER.getValue();
    static final int DROP = SendOpcode.DROP_ITEM_FROM_MAPOBJECT.getValue();

    @BeforeAll
    static void init() throws Exception {
        MapEntryHarness.initItemProvider();
    }

    private static List<Integer> spawnedIds(List<MapEntryHarness.Sent> sent) {
        return sent.stream().filter(s -> s.opcode() == SPAWN_PLAYER).map(s -> s.intAt(2)).toList();
    }

    private static Map<Integer, Long> counts(List<Integer> ids) {
        return ids.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    @Test
    void botsInViewComeAtOnceTheRestFollowNearestFirstEachOnce() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            h.bots(61, 6000); // one every 100 px from -3000 to 3000
            Character me = h.player(0);
            h.enter(me);
            List<Integer> now = spawnedIds(h.sync());
            assertEquals(17, now.size(), "bots within 850 px: -800..800");
            for (int id : now) {
                Character b = h.bots.stream().filter(x -> x.getId() == id).findFirst().orElseThrow();
                assertTrue(Math.abs(b.getPosition().x) <= 850);
            }
            assertTrue(h.scheduler.drain() <= MapEntryPlacement.MAX_STEPS);
            List<Integer> later = spawnedIds(h.later());
            assertEquals(44, later.size());
            int last = 0;
            for (int id : later) {
                int d = Math.abs(h.bots.stream().filter(x -> x.getId() == id).findFirst().orElseThrow().getPosition().x);
                assertTrue(d >= last, "nearest first");
                last = d;
            }
            assertTrue(counts(spawnedIds(h.sent)).values().stream().allMatch(n -> n == 1));
            assertEquals(MapEntryPlacement.STEP_MS, h.scheduler.delays.get(0));
        }
    }

    @Test
    void offSendsEverythingInTheEntryBurst() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            h.trickle(false);
            h.bots(61, 6000);
            h.drops(100, 6000);
            Character me = h.player(0);
            h.enter(me);
            assertEquals(0, h.scheduler.drain());
            assertEquals(61, spawnedIds(h.sync()).size());
            assertEquals(100, h.sync().stream().filter(s -> s.opcode() == DROP).count());
        }
    }

    @Test
    void aBotThatLeftBeforeItsTurnIsNotSpawned() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            h.bots(61, 6000);
            Character far = h.bots.get(0); // x = -3000
            Character me = h.player(0);
            h.enter(me);
            h.leave(far);
            h.scheduler.drain();
            assertFalse(spawnedIds(h.sent).contains(far.getId()));
        }
    }

    @Test
    void aBotThatLeftAndCameBackIsSpawnedOnceByItsOwnEntry() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            h.bots(61, 6000);
            Character far = h.bots.get(0);
            Character me = h.player(0);
            h.enter(me);
            h.map.removePlayer(far);
            h.map.addPlayer(far); // broadcasts its spawn to the entrant
            h.scheduler.drain();
            assertEquals(1L, counts(spawnedIds(h.sent)).get(far.getId()));
        }
    }

    @Test
    void anEntrantThatLeftGetsNoMoreAndItsNextEntryStartsOver() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            h.bots(61, 6000);
            Character me = h.player(0);
            h.enter(me);
            h.leave(me);
            int before = h.sent.size();
            h.scheduler.step(); // a chunk of the old entry after the warp out
            assertEquals(before, h.sent.size());
            h.scheduler.drain();

            h.sent.clear();
            h.enter(me);
            h.leave(me);
            h.enter(me); // re-entered before the first entry's chunks ran
            h.scheduler.drain();
            Map<Integer, Long> c = counts(spawnedIds(h.sent));
            assertEquals(61, c.size());
            // first entry: 17 at once; second entry: all 61 (17 + 44 deferred); the first entry's deferred never went
            assertEquals(17 + 61, spawnedIds(h.sent).size());
        }
    }

    @Test
    void dropsOutsideTheViewFollowUnlessTakenMeanwhile() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            h.bot(0);
            MapItem near = h.drop(100);
            MapItem far = h.drop(2000);
            MapItem gone = h.drop(-2000);
            Character me = h.player(0);
            h.enter(me);
            List<Integer> nowDrops = h.sync().stream().filter(s -> s.opcode() == DROP).map(s -> s.intAt(3)).toList();
            assertEquals(List.of(near.getObjectId()), nowDrops);
            h.map.removeMapObject(gone);
            h.scheduler.drain();
            List<Integer> laterDrops = h.later().stream().filter(s -> s.opcode() == DROP).map(s -> s.intAt(3)).toList();
            assertEquals(List.of(far.getObjectId()), laterDrops);
            assertTrue(me.isMapObjectVisible(far));
        }
    }

    @Test
    void aBotWithAShopOrAGameAndRealPlayersAreNeverDeferred() {
        Character shopBot = mock(Character.class);
        when(shopBot.getClient()).thenReturn(new BotClient(0, 0));
        when(shopBot.getPlayerShop()).thenReturn(mock(PlayerShop.class));
        assertFalse(MapEntryPlacement.deferrableCharacter(shopBot));

        Character gameBot = mock(Character.class);
        when(gameBot.getClient()).thenReturn(new BotClient(0, 0));
        when(gameBot.getMiniGame()).thenReturn(mock(MiniGame.class));
        assertFalse(MapEntryPlacement.deferrableCharacter(gameBot));

        Character player = mock(Character.class);
        when(player.getClient()).thenReturn(mock(client.Client.class));
        assertFalse(MapEntryPlacement.deferrableCharacter(player));

        Character plainBot = mock(Character.class);
        when(plainBot.getClient()).thenReturn(new BotClient(0, 0));
        assertTrue(MapEntryPlacement.deferrableCharacter(plainBot));
    }

    @Test
    void chunksGrowSoTheWholeMapArrivesWithinTheStepCap() {
        assertEquals(MapEntryPlacement.MIN_CHUNK, MapEntryPlacement.chunkSize(10));
        assertEquals(30, MapEntryPlacement.chunkSize(450));
        assertEquals(600, MapEntryPlacement.chunkSize(9000));
        assertTrue(MapEntryPlacement.inView(new Point(0, 0), botAt(850)));
        assertFalse(MapEntryPlacement.inView(new Point(0, 0), botAt(851)));
    }

    private static MapObject botAt(int x) {
        MapObject o = mock(MapObject.class);
        when(o.getPosition()).thenReturn(new Point(x, 0));
        return o;
    }
}
