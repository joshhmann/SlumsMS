package server.maps;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Orbis PQ's sealed room (map 920010500) tells the party "There needs to be exactly 3 players on these platforms."
 * and then asks {@code cm.getMap().getNumPlayersInArea(i)} for {@code i} 0..2 ({@code scripts/npc/2013001.js:150});
 * the three platforms are the map's {@code area} nodes, which {@code server/maps/MapFactory.java:273-279} turns into
 * {@link MapleMap#addMapleArea(Rectangle)} calls in file order. With no areas on the map the question has no answer at
 * all, so no party could ever satisfy it; with them, each platform counts the players standing on it.
 *
 * <p>The rectangles are the three from {@code Map.wz/Map/Map9/920010500.img.xml} imgdirs 1..3, in the order the map
 * loader reads them; the harness map is WZ-less, so they are added here exactly as the loader adds them.
 */
class OrbisSealedRoomAreaTest {

    static final int SEALED_ROOM = 920010500;
    /** imgdir 1, 2, 3 of {@code Map.wz/Map/Map9/920010500.img.xml}, i.e. {@code getNumPlayersInArea(0..2)}. */
    static final List<Rectangle> PLATFORMS = List.of(
            new Rectangle(-205, -850, 86, 83),
            new Rectangle(-71, -963, 61, 87),
            new Rectangle(40, -850, 76, 74));

    @Test
    void withoutTheMapAreasThePlatformQuestionCannotBeAnswered() {
        try (MapEntryHarness harness = new MapEntryHarness(SEALED_ROOM)) {
            assertEquals(0, harness.map.getAreas().size());
            assertThrows(IndexOutOfBoundsException.class, () -> harness.map.getNumPlayersInArea(0),
                    "no area 0 means the stage can never be cleared");
        }
    }

    @Test
    void eachPlatformCountsThePlayersStandingOnIt() {
        try (MapEntryHarness harness = new MapEntryHarness(SEALED_ROOM)) {
            PLATFORMS.forEach(harness.map::addMapleArea);
            assertEquals(3, harness.map.getAreas().size());

            var first = harness.bot(0);
            var second = harness.bot(0);
            var third = harness.bot(0);

            stand(first, PLATFORMS.get(0));
            stand(second, PLATFORMS.get(0));
            stand(third, PLATFORMS.get(1));
            assertEquals(2, harness.map.getNumPlayersInArea(0));
            assertEquals(1, harness.map.getNumPlayersInArea(1));
            assertEquals(0, harness.map.getNumPlayersInArea(2));

            // 1 / 1 / 1, the only arrangement that also satisfies 3 players, is the one the script accepts.
            stand(second, PLATFORMS.get(2));
            assertEquals(1, harness.map.getNumPlayersInArea(0));
            assertEquals(1, harness.map.getNumPlayersInArea(1));
            assertEquals(1, harness.map.getNumPlayersInArea(2));

            // standing off the platforms counts nowhere: the sum has to come from the three areas themselves.
            second.setPosition(new Point(0, 0));
            assertEquals(0, harness.map.getNumPlayersInArea(2));
        }
    }

    static void stand(client.Character chr, Rectangle platform) {
        chr.setPosition(new Point(platform.x + platform.width / 2, platform.y + platform.height / 2));
    }
}
