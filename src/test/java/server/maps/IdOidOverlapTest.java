package server.maps;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6 id-oid-overlap: a map's object ids used to start at 1,000,000,001, inside the early roster regulars' character
 * ids (pwnkiller 1,000,000,595), and mapobjects keys both by that number. A mob spawned before such a regular entered
 * was overwritten (invisible mob); its death then removed the regular (lost character). Object ids now start at
 * MapleMap.FIRST_OID, above every character id. A MapItem stands in for the mob: a mob's death removes it the same way,
 * removeMapObject(oid). See docs/bots/id-oid-overlap.md.
 */
class IdOidOverlapTest {
    static final int PWNKILLER = 1_000_000_595;

    @BeforeAll
    static void init() throws Exception {
        MapEntryHarness.initItemProvider();
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, MapObject> objects(MapleMap map) throws Exception {
        Field f = MapleMap.class.getDeclaredField("mapobjects");
        f.setAccessible(true);
        return (Map<Integer, MapObject>) f.get(map);
    }

    private static Character regular(MapleMap map) {
        Character b = Character.getDefault(new BotClient(0, 0));
        b.setID(PWNKILLER);
        b.setName("pwnkiller");
        b.setPosition(new Point(0, 0));
        b.setMap(map);
        return b;
    }

    /** {@code n} objects spawned on the map the way it always spawns them (its own oid counter). */
    private static List<MapItem> spawn(MapEntryHarness h, int n) {
        Character owner = h.bot(500);
        List<MapItem> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MapItem item = new MapItem(100, new Point(100, 0), owner, owner, owner.getClient(), (byte) 2, false);
            h.map.addMapObject(item);
            out.add(item);
        }
        return out;
    }

    @Test
    void mobsSpawnedBeforeAnEarlyRegularEntersStayVisible() throws Exception {
        try (MapEntryHarness h = new MapEntryHarness(104040000)) {
            List<MapItem> mobs = spawn(h, 700);
            Character pwn = regular(h.map);
            h.map.addPlayer(pwn);
            Map<Integer, MapObject> objs = objects(h.map);
            for (MapItem m : mobs) {
                assertSame(m, objs.get(m.getObjectId()), "mob " + m.getObjectId() + " overwritten by a regular's entry");
            }
            assertSame(pwn, objs.get(PWNKILLER));
        }
    }

    @Test
    void mobsDyingDoNotTakeAnEarlyRegularOffTheMap() throws Exception {
        try (MapEntryHarness h = new MapEntryHarness(104040000)) {
            List<MapItem> mobs = spawn(h, 700);
            Character pwn = regular(h.map);
            h.map.addPlayer(pwn);
            for (MapItem m : mobs) {
                h.map.removeMapObject(m);
            }
            assertSame(pwn, objects(h.map).get(PWNKILLER), "a mob's death removed the regular's entry (lost character)");
        }
    }

    @Test
    void objectIdsStartAndWrapAboveEveryCharacterId() throws Exception {
        try (MapEntryHarness h = new MapEntryHarness(104040000)) {
            int first = spawn(h, 1).getFirst().getObjectId();
            assertTrue(first >= MapleMap.FIRST_OID, "first oid " + first);
            Field f = MapleMap.class.getDeclaredField("runningOid");
            f.setAccessible(true);
            ((AtomicInteger) f.get(h.map)).set(2_146_999_999);
            int wrapped = spawn(h, 1).getFirst().getObjectId();
            // back to the floor; the floor's own key is still held by the first object, so the next free one
            assertEquals(MapleMap.FIRST_OID + 1, wrapped, "the wrap goes back to the floor, not to 1,000,000,001");
        }
    }

    @Test
    void everyPossibleBotIdIsBelowTheFloor() {
        long highestBotId = (long) BotStore.BOT_ID_BASE + BotStore.FIRST_ROSTER_SLOT + BotStore.ROSTER_SLOT_CAP - 1;
        assertTrue(highestBotId < MapleMap.FIRST_OID, "BotStore's layout plus its roster cap reaches the oid floor: "
                + highestBotId + " >= " + MapleMap.FIRST_OID);
    }
}
