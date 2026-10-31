package soloMapling.ArtificialPlayer.BotTravelSystem;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b230 (auto-QA t-nv-2): the curated scripted warps that give Rien, Korean Folk Town's Black Mountain and Leafre's Cave
 * of Life their way back, each checked against the map's real portal and the script it runs.
 */
class BotScriptedWarpTest {

    private static final int[][] WAYS_BACK = {
            {140020200, 140020300}, // Rien: Snow-covered Field 3 -> the whale's dock (enterPort.js)
            {222010300, 222010200}, // Korean Folk Town: Fox Ridge -> Tiger Ridge (foxLaidy_map.js)
            {240040700, 240040600}, // Leafre: Cave of Life - Entrance -> Peak of the Big Nest (hontale_morph2.js)
    };

    @Test
    void theWaysBackAreScriptedWarps() {
        for (int[] w : WAYS_BACK) {
            assertNotNull(BotScriptedWarp.edge(w[0], w[1]), w[0] + " -> " + w[1]);
            assertTrue(java.util.Arrays.stream(BotScriptedWarp.destinations(w[0])).anyMatch(d -> d == w[1]));
        }
    }

    /** Every edge stands on a scripted portal of its map whose script warps where the edge goes. */
    @Test
    void everyEdgeIsItsMapsScriptedPortal() throws Exception {
        WzTestData.assumePresent("Map.wz");
        for (int[] w : WAYS_BACK) {
            BotScriptedWarp.WarpEdge e = BotScriptedWarp.edge(w[0], w[1]);
            Path xml = WzTestData.dir().resolve("Map.wz/Map/Map" + (w[0] / 100000000)
                    + String.format("/%09d.img.xml", w[0]));
            String map = Files.readString(xml);
            Matcher m = Pattern.compile("<string name=\"pn\" value=\"" + e.portalName()
                    + "\"/>\\s*(?:<(?!/imgdir)[^>]*>\\s*)*?<string name=\"script\" value=\"([^\"]+)\"").matcher(map);
            Matcher m2 = Pattern.compile("<string name=\"script\" value=\"([^\"]+)\"/>\\s*(?:<(?!/imgdir)[^>]*>\\s*)*?"
                    + "<string name=\"pn\" value=\"" + e.portalName() + "\"").matcher(map);
            String script = m.find() ? m.group(1) : m2.find() ? m2.group(1) : null;
            assertNotNull(script, "portal " + e.portalName() + " on " + w[0] + " runs a script");
            String js = Files.readString(Path.of("scripts/portal", script + ".js"));
            assertTrue(js.contains("warp(" + w[1]), script + ".js warps to " + w[1]);
        }
    }
}
