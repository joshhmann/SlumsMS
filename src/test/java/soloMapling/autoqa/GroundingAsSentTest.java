package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import soloMapling.ArtificialPlayer.BotChannels;

import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.autoqa.FootholdCheck.Pose.AIRBORNE;
import static soloMapling.autoqa.FootholdCheck.Pose.CLIMBING;
import static soloMapling.autoqa.FootholdCheck.Pose.COARSE;
import static soloMapling.autoqa.FootholdCheck.Pose.GROUNDED;
import static soloMapling.autoqa.FootholdCheck.Pose.SWIMMING;

/**
 * rc6 inference checks: grounding for everyone. The synthetic players land on a foothold when they enter a map, they
 * are in the foothold sweep, and "foothold (sent)" checks the y the server last sent clients in spawn and move
 * packets, with the server check's tolerance, poses and skip rules.
 */
class GroundingAsSentTest {

    static final int HENESYS = 100000000;
    static final List<FootholdCheck.Seg> GROUND = List.of(new FootholdCheck.Seg(-2000, 274, 2000, 274));

    @AfterEach
    void tearDown() {
        SentPositions.enable(false);
    }

    // ── the synthetic players ──

    @Test
    void aSyntheticPlayerEnteringAtAPortalLandsOnTheFloorBelow() {
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        tree.insert(new Foothold(new Point(-500, 274), new Point(500, 274), 1));
        tree.insert(new Foothold(new Point(600, 94), new Point(800, 94), 2));
        assertEquals(new Point(100, 274), SyntheticPlayers.groundPoint(tree, new Point(100, 230)), "a portal 44 px up");
        assertEquals(new Point(700, 94), SyntheticPlayers.groundPoint(tree, new Point(700, 60)));
        assertEquals(new Point(100, 274), SyntheticPlayers.groundPoint(tree, new Point(100, 290)), "16 px sunk: lifted");
        assertNull(SyntheticPlayers.groundPoint(tree, new Point(5000, 0)), "no ground under it: left alone");
    }

    @Test
    void syntheticPlayersAreInTheFootholdSweepButNotTheBotChecks() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> all = List.of(
                new HealthSweep.BotSample(1, "Bot1", 1, HENESYS, 0, 274, GROUNDED, false, true, null),
                new HealthSweep.BotSample(44, "AutoQaOne", 1, HENESYS, 100, 230, GROUNDED, false, false, "GM map 180000000",
                        true));
        Map<String, Object> r = s.run(new HealthSweep.Snapshot(0, all, 1, 1, new int[]{1, 0, 0, 0, 0},
                BotChannels.shares("100,85,50,30,20", 5), 0, "t"), m -> GROUND);
        Map<String, Object> fh = HealthSweepTest.check(r, "foothold");
        assertEquals(1, fh.get("syntheticChecked"));
        assertEquals("WARN", fh.get("status"), "AutoQaOne floats 44 px above the ground");
        assertEquals(true, ((Map<?, ?>) ((List<?>) fh.get("worst")).get(0)).get("synthetic"));
        assertEquals("OK", HealthSweepTest.check(r, "invalidMaps").get("status"), "a pretend player may stand anywhere");
    }

    // ── what clients were sent ──

    /** A MOVE_PLAYER packet: opcode, character id, an int, then the movement list. */
    static byte[] move(int... fragmentBytes) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) {
            b.write(0);
        }
        for (int v : fragmentBytes) {
            b.write(v);
        }
        return b.toByteArray();
    }

    static int[] absolute(int x, int y, int fh, int stance) {
        return new int[]{0, x & 0xFF, (x >> 8) & 0xFF, y & 0xFF, (y >> 8) & 0xFF, 0, 0, 0, 0, fh & 0xFF, (fh >> 8) & 0xFF,
                stance, 30, 0};
    }

    static int[] concat(int count, int[]... parts) {
        int n = 1;
        for (int[] p : parts) {
            n += p.length;
        }
        int[] out = new int[n];
        out[0] = count;
        int k = 1;
        for (int[] p : parts) {
            System.arraycopy(p, 0, out, k, p.length);
            k += p.length;
        }
        return out;
    }

    @Test
    void decodesTheLastPositionStanceAndFootholdOfAMoveList() {
        SentPositions.Decoded d = SentPositions.decode(move(concat(1, absolute(-120, 274, 88, 4))), 10);
        assertNotNull(d);
        assertEquals(-120, d.x());
        assertEquals(274, d.y());
        assertEquals(88, d.fh());
        assertEquals(4, d.stance());
        // an absolute anchor then a flash jump (cmd 6: relative, its own stance)
        int[] fj = {6, 10, 0, 0, 0, 7, 0, 0};
        d = SentPositions.decode(move(concat(2, absolute(500, 200, 0, 6), fj)), 10);
        assertEquals(500, d.x());
        assertEquals(7, d.stance());
        assertNull(SentPositions.decode(move(1, 99), 10), "an unknown fragment does not parse");
    }

    @Test
    void sentPosesFollowTheServerCheckRules() {
        assertEquals(GROUNDED, SentPositions.pose(4, 88, false, null));
        assertEquals(AIRBORNE, SentPositions.pose(4, 0, false, null), "standing stance with foothold 0: in the air");
        assertEquals(AIRBORNE, SentPositions.pose(6, -1, false, null), "the spawn drop (stance 6)");
        assertEquals(AIRBORNE, SentPositions.pose(12, -1, false, null), "a jump");
        assertEquals(CLIMBING, SentPositions.pose(16, -1, false, null), "a rope");
        assertEquals(SWIMMING, SentPositions.pose(4, 88, true, null));
        assertEquals(COARSE, SentPositions.pose(4, 88, false, "COARSE"));
    }

    @Test
    void movesKeepTheMapOfTheLastSpawnAndAMapEntryForgetsIt() {
        SentPositions.enable(true);
        SentPositions.moved(7, move(concat(1, absolute(10, 300, 5, 4))));
        assertNull(SentPositions.get(7), "a move with no spawn record has no map");
        SentPositions.spawned(7, HENESYS, 10, 274, 4);
        SentPositions.moved(7, move(concat(1, absolute(40, 301, 5, 4))));
        SentPositions.Sent s = SentPositions.get(7);
        assertEquals(HENESYS, s.mapId());
        assertEquals(301, s.y());
        assertEquals("move", s.how());
        SentPositions.entered(7);
        assertNull(SentPositions.get(7));
    }

    static HealthSweep.SentSample sent(int id, String kind, int y, FootholdCheck.Pose pose) {
        return new HealthSweep.SentSample(id, "C" + id, 1, HENESYS, id * 10, y, pose, kind, 1000, "move");
    }

    @Test
    void sunkSentPositionsFailTheSentCheckAlongsideTheServerOne() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.SentSample> sent = List.of(
                sent(1, "bot", 274, GROUNDED),
                sent(2, "bot", 301, GROUNDED),       // the client draws it 27 px inside the ground
                sent(3, "synthetic", 274, GROUNDED),
                sent(4, "player", 232, AIRBORNE),    // the spawn drop: skipped
                sent(5, "bot", 200, CLIMBING));      // on a rope: skipped
        HealthSweep.Snapshot snap = new HealthSweep.Snapshot(0, List.of(), 0, 1, new int[]{0, 0, 0, 0, 0},
                BotChannels.shares("100,85,50,30,20", 5), 0, "t", List.of(), sent);
        Map<String, Object> r = s.run(snap, m -> GROUND);
        Map<String, Object> c = HealthSweepTest.check(r, HealthSweep.SENT_CHECK);
        assertEquals("FAIL", c.get("status"));
        assertEquals(3, c.get("checked"));
        assertEquals(2, c.get("skipped"));
        assertEquals(Map.of("SUNK", 1), c.get("byKind"));
        Map<?, ?> worst = (Map<?, ?>) ((List<?>) c.get("worst")).get(0);
        assertEquals(27.0, worst.get("dy"));
        assertEquals("bot", worst.get("who"));
        assertTrue(String.valueOf(r.get("summary")).contains("foothold (sent) FAIL"));
        assertEquals("OK", HealthSweepTest.check(r, "foothold").get("status"), "the server check had nothing to say");
    }

    @Test
    void noWatchedMapIsOk() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        Map<String, Object> r = s.run(new HealthSweep.Snapshot(0, List.of(), 0, 0, new int[]{0, 0, 0, 0, 0},
                BotChannels.shares("100,85,50,30,20", 5), 0, "t"), m -> GROUND);
        assertEquals("OK", HealthSweepTest.check(r, HealthSweep.SENT_CHECK).get("status"));
    }
}
