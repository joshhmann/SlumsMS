package server.maps;

import client.Character;
import org.junit.jupiter.api.Test;
import testutil.Mocks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A closed omok/matchcard room used to stay in {@code MapleMap.mapobjects} forever: {@code closeRoom} only nulled the
 * owner/visitor references, so the room kept its object id and a later VISIT with that id reached
 * {@code MiniGame.isOwner} with {@code owner == null}.
 *
 * <p>The map here is a real {@code MapleMap} — its constructor is inert — so the assertions are about the map's own
 * object table rather than about a call being made on a mock. The room's map is the one captured at construction, not
 * {@code owner.getMap()} at close time, which is why the owner is pointed at the map before construction.
 */
class MiniGameCloseRemovesFromMapTest {

    private static final int MAP_ID = 910000000;

    @Test
    void closingTheRoomTakesItOffTheMap() {
        Character owner = Mocks.chr("owner");
        MapleMap map = new MapleMap(MAP_ID, 0, 1, 0, 1f);
        when(owner.getMap()).thenReturn(map);

        MiniGame game = new MiniGame(owner, "", "");
        map.addMapObject(game);
        int oid = game.getObjectId();
        assertSame(game, map.getMapObject(oid), "the room is on the map to begin with");

        game.closeRoom(true);

        assertNull(map.getMapObject(oid), "a closed room must not stay reachable by its object id");
    }

    @Test
    void closingAnOpenRoomWithAVisitorStillTakesItOffTheMap() {
        Character owner = Mocks.chr("owner");
        Character visitor = Mocks.chr("visitor");
        MapleMap map = new MapleMap(MAP_ID, 0, 1, 0, 1f);
        when(owner.getMap()).thenReturn(map);

        MiniGame game = new MiniGame(owner, "", "");
        game.addVisitor(visitor);
        map.addMapObject(game);
        int oid = game.getObjectId();
        assertSame(game, map.getMapObject(oid), "the room is on the map to begin with");

        game.closeRoom(false);

        assertNull(map.getMapObject(oid), "a closed room must not stay reachable by its object id");
        verify(visitor).setMiniGame(null);
        verify(owner).setMiniGame(null);
    }

    @Test
    void closingARoomThatNeverKnewItsMapDoesNotThrow() {
        Character owner = Mocks.chr("owner");
        when(owner.getMap()).thenReturn(null);

        MiniGame game = new MiniGame(owner, "", "");

        assertDoesNotThrow(() -> game.closeRoom(true));
    }
}
