package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;
import server.life.Monster;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 zakum reach (16602c02b) sends a fighter to a boss part's body edge (Approach.fightSpot through BossBodyReach),
 * but the owned-alt and follower fight paths still asked "in reach?" of the part's anchor only: an alt sent to
 * Horntail's legs edge stood there without swinging until its 10 s stall gave the part up (found 9 Nov 2026 checking
 * the owner's Horntail run on TEST). Their swing test is now the same as the executor's: anchor OR body. Real Mob.wz.
 */
class AltBodyReachTest {

    private static final Point HT_ANCHOR = new Point(71, 260); // every Horntail part spawns at this one point
    private static final int[] MELEE = {70, 90};

    private static Monster part(int id) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        when(m.isBoss()).thenReturn(true);
        when(m.isAlive()).thenReturn(true);
        when(m.isFacingLeft()).thenReturn(false);
        when(m.getPosition()).thenReturn(HT_ANCHOR);
        return m;
    }

    @Test
    void aMeleeAltAtTheLegsEdgeIsInSwingReach() {
        WzTestData.assumePresent("Mob.wz");
        Monster legs = part(8810008); // body x -186..328, y 77..260 at the anchor

        assertTrue(Approach.inSwingReach(new Point(-180, 260), legs, MELEE),
                "where fightSpot sends it (the legs' near edge, 251 px from the anchor) it must swing, not stand");
    }

    @Test
    void aHeadHighAboveIsStillReachedOnlyThroughTheAnchorAsBefore() {
        WzTestData.assumePresent("Mob.wz");
        Monster headB = part(8810003); // body y -368..-36: no floor swing meets it

        assertFalse(Approach.inSwingReach(new Point(-180, 260), headB, MELEE), "far from the anchor and the body");
        assertTrue(Approach.inSwingReach(new Point(100, 260), headB, MELEE), "beside the anchor, as on 1.6.6");
    }

    @Test
    void theAltAndFollowerFightPathsAskTheSwingTestNotTheAnchorOnly() throws Exception {
        String alt = Files.readString(Path.of("src/main/java/soloMapling/companions/OwnedAltCompanion.java"));
        String follower = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerFight.java"));
        assertTrue(alt.contains("Approach.inSwingReach(at, target, reach)"), "OwnedAltCompanion.fight");
        assertTrue(follower.contains("Approach.inSwingReach("), "FollowerFight.closeEnough");
    }
}
