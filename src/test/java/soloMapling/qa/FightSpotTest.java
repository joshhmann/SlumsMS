package soloMapling.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

class FightSpotTest {
    private final List<Point> mobs = List.of(new Point(687, -1046), new Point(600, -565), new Point(300, -85),
            new Point(400, -90));

    @Test
    void topLedgeGmIsMovedToTheLowestLedge() {
        assertEquals(new Point(400, -90), GameQaWorld.fightSpot(new Point(687, -1045), mobs));
    }

    @Test
    void gmAlreadyAmongLowestLedgeMobsStays() {
        assertNull(GameQaWorld.fightSpot(new Point(350, -85), mobs));
    }

    @Test
    void noMobsNoMove() {
        assertNull(GameQaWorld.fightSpot(new Point(0, 0), List.of()));
    }
}
