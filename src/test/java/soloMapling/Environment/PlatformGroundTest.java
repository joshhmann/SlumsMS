package soloMapling.Environment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;

import java.awt.Point;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.5.1: where a platform's bots stand. A platform is given a range and a first guess at its height (recorded
 * movement, or a hand-drawn band); the height that counts is the map's foothold under the x. A "flat" range used to
 * put every bot at one Y, so a band across a slope or across two levels stood bots in the air or inside the ground -
 * the health sweep's FLOATING and SUNK (24 of 2,502 bots on the test server, 2026-10-24).
 */
class PlatformGroundTest {

    /**
     * The spawner's scattering is a shared unseeded generator in production; every test here pins it. Without this the
     * sample was the JVM's luck, and one spawn in a few hundred thousand landed exactly on the seam between two
     * footholds - where the two ways of asking for the level disagree.
     */
    @AfterEach
    void unpinTheGenerator() {
        PlatformSpawner.useRandom(null);
    }

    private static FootholdTree ground(int[]... footholds) {
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        int id = 1;
        for (int[] f : footholds) {
            tree.insert(new Foothold(new Point(f[0], f[1]), new Point(f[2], f[3]), id++));
        }
        return tree;
    }

    @Test
    void aBandAcrossASlopePutsEverySpawnOnTheSlope() {
        // one ledge falling 80 px over 400 px: a FLAT-detected recording (the slope check only looks at >50 px rise
        // per recorded range) still climbs, so one Y for the whole range was wrong by up to 80 px.
        FootholdTree slope = ground(new int[]{-200, 100, 200, 20});
        Platform band = new Platform(-200, 200, List.of(new Point(-200, 100), new Point(200, 20)),
                Platform.Type.FLAT, slope);

        // the ground itself is read with the server's own footing math (Foothold slopes are ints), so allow 1 px
        assertEquals(100, band.getYAtX(-200), 1.0);
        assertEquals(30, band.getYAtX(150), 1.0, "350 px along an 80-over-400 slope");
        assertEquals(20, band.getYAtX(200), 1.0);

        PlatformSpawner.useRandom(new Random(3));
        for (Point spawn : PlatformSpawner.findUnoccupiedPoints(band, List.of(), 8)) {
            assertEquals(spawn.y, groundY(slope, spawn.x), 1.0, spawn + " is off the slope");
        }
    }

    @Test
    void aBandAcrossTwoLevelsPutsEachSpawnOnItsOwnLevel() {
        // the two levels meet at x=0; the band's recorded points are both on the lower one, so the guess is wrong for
        // the whole right half - the nearest level is the one the bot belongs on.
        FootholdTree steps = ground(new int[]{-400, 300, 0, 300}, new int[]{0, 250, 400, 250});
        Platform band = new Platform(-400, 400, List.of(new Point(-400, 300), new Point(400, 300)),
                Platform.Type.FLAT, steps);

        assertEquals(300, band.getYAtX(-400));
        assertEquals(250, band.getYAtX(400), "the right half is on the 250 level, not the band's 300");

        PlatformSpawner.useRandom(new Random(5));
        for (Point spawn : PlatformSpawner.findUnoccupiedPoints(band, List.of(), 8)) {
            assertEquals(spawn.y, groundY(steps, spawn.x), 1.0, spawn + " is off its level");
        }
    }

    /**
     * The flake, pinned. x=0 is where the 300 level and the 250 level touch, and at that one pixel the recorded height's
     * settle (300) and a drop from above (250) disagree - so a spawn there is a coin toss nobody can see. The spawner
     * nudges one pixel inside a level, and the level that follows is the recorded height's own.
     */
    @Test
    void aSpawnThatDrawsTheSeamBetweenTwoLevelsLandsOnePixelInsideALevel() {
        FootholdTree steps = ground(new int[]{-400, 300, 0, 300}, new int[]{0, 250, 400, 250});
        Platform band = new Platform(-400, 400, List.of(new Point(-400, 300), new Point(400, 300)),
                Platform.Type.FLAT, steps);

        PlatformSpawner.useRandom(seedThatDraws(800, 400)); // the draw that would have put the spawn at x=0

        Point spawn = PlatformSpawner.findUnoccupiedPoint(band, List.of());

        assertEquals(1, spawn.x, "the seam at x=0 is a tie-break, not a level: the spawn sits one pixel inside one");
        assertEquals(250, spawn.y, "and it is the level under that pixel, not the band's recorded 300");
        assertEquals(spawn.y, groundY(steps, spawn.x), 1.0, spawn + " is off its level");
    }

    /** A seed whose first draw of {@code nextInt(width)} is {@code want}: the sample the flake was made of. */
    private static Random seedThatDraws(int width, int want) {
        for (int seed = 0; seed < 100_000; seed++) {
            if (new Random(seed).nextInt(width) == want) {
                return new Random(seed);
            }
        }
        throw new AssertionError("no seed of the first 100000 draws x=" + want + " of " + width);
    }

    @Test
    void aSlopedPlatformsRecordedPointsSitOnTheGroundUnderThem() {
        // the recorded point is the only x used, but its y came from a packet a few px off the line
        FootholdTree slope = ground(new int[]{-200, 100, 200, 20});
        Platform platform = new Platform(-200, 200, List.of(new Point(-200, 104), new Point(200, 24)),
                Platform.Type.SLOPED, slope);

        PlatformSpawner.useRandom(new Random(7));
        for (Point spawn : PlatformSpawner.findUnoccupiedPoints(platform, List.of(), 4)) {
            assertEquals(spawn.y, groundY(slope, spawn.x), 1.0, spawn + " is off the slope");
        }
    }

    @Test
    void aGenuinelyFlatPlatformIsUnchanged() {
        FootholdTree flat = ground(new int[]{-100, 150, 100, 150});
        Platform platform = new Platform(-100, 100, List.of(new Point(-100, 150), new Point(100, 150)),
                Platform.Type.FLAT, flat);

        assertEquals(150, platform.getYAtX(-100));
        assertEquals(150, platform.getYAtX(37));
        assertEquals(150, platform.getYAtX(100));
        PlatformSpawner.useRandom(new Random(9));
        for (Point spawn : PlatformSpawner.findUnoccupiedPoints(platform, List.of(), 4)) {
            assertEquals(150, spawn.y);
        }
    }

    @Test
    void withoutAMapTheRecordedHeightIsAllThereIs() {
        // no map loaded (a unit test, or a channel not up yet): the recorded points are interpolated, as before
        Platform band = new Platform(-286, 7, List.of(new Point(-286, 101), new Point(7, 94)),
                Platform.Type.FLAT, null);

        assertEquals(101, band.getYAtX(-286));
        assertEquals(94, band.getYAtX(7));
        assertEquals(94, band.getRecordedYAtX(0), "the band interpolates between its two ends");
    }

    /** The y of the foothold line under x, or NaN when none spans x. */
    private static double groundY(FootholdTree tree, int x) {
        Foothold fh = tree.findBelow(new Point(x, -30000));
        if (fh == null) {
            return Double.NaN;
        }
        if (fh.getX1() == fh.getX2() || fh.getY1() == fh.getY2()) {
            return fh.getY1();
        }
        return fh.getY1() + (double) (fh.getY2() - fh.getY1()) * (x - fh.getX1()) / (fh.getX2() - fh.getX1());
    }
}
