package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import server.maps.Foothold;
import server.maps.FootholdTree;
import soloMapling.Environment.Platform;
import soloMapling.Environment.PlatformSpawner;
import soloMapling.WzTestData;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The foothold check against the real Henesys (100000000) footholds: the town-dressing filler range
 * "left side top plat" in EnvironmentManager.spawnFillerBotsHenesys runs from (-286, 101) to (7, 94) across a ledge
 * that climbs from 101 to 94. 1.5.1: PlatformSpawner used to put every bot of a FLAT range at the range's single Y
 * (101), so the right part of the range stood 7 px inside the platform and the sweep called it SUNK; the range now
 * settles each point on the map's ground. Needs wz/Map.wz.
 */
class HenesysGroundWzTest {

    static List<FootholdCheck.Seg> footholds(int mapId) throws Exception {
        String id = String.valueOf(mapId);
        Path img = WzTestData.dir().resolve("Map.wz/Map/Map" + id.charAt(0) + "/" + id + ".img.xml");
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(img.toFile()).getDocumentElement();
        List<FootholdCheck.Seg> out = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && "foothold".equals(e.getAttribute("name"))) {
                collect(e, out);
            }
        }
        return out;
    }

    /** The same footholds as a real FootholdTree, which is what placement settles points onto. */
    static FootholdTree ground(int mapId) throws Exception {
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        int id = 1;
        for (FootholdCheck.Seg s : footholds(mapId)) {
            tree.insert(new Foothold(new Point(s.x1(), s.y1()), new Point(s.x2(), s.y2()), id++));
        }
        return tree;
    }

    private static void collect(Element e, List<FootholdCheck.Seg> out) {
        Map<String, Integer> ints = new HashMap<>();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element k && "int".equals(k.getTagName())) {
                ints.put(k.getAttribute("name"), Integer.parseInt(k.getAttribute("value")));
            }
        }
        if (ints.containsKey("x1")) {
            out.add(new FootholdCheck.Seg(ints.get("x1"), ints.get("y1"), ints.get("x2"), ints.get("y2")));
            return;
        }
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element k && "imgdir".equals(k.getTagName())) {
                collect(k, out);
            }
        }
    }

    @Test
    void theTownspeopleTheOwnerSawSinkingAreFlagged() throws Exception {
        // the rc5 stored rows before the ground fix (87c9df5ef): NachoHat08, MochiUltra, CandyClock in Henesys
        WzTestData.assumePresent("Map.wz");
        List<FootholdCheck.Seg> fh = footholds(100000000);
        int[][] bots = {{1148, 363, 29}, {813, 303, 29}, {1227, 122, 28}};
        for (int[] b : bots) {
            FootholdCheck.Verdict v = FootholdCheck.check(fh, b[0], b[1], FootholdCheck.Pose.GROUNDED);
            assertEquals(FootholdCheck.Kind.SUNK, v.kind(), b[0] + "," + b[1]);
            assertEquals(b[2], v.dy(), 0.01);
        }
    }

    @Test
    void fillerBotsOnTheLeftTopPlatformOfHenesysStandOnTheirGround() throws Exception {
        WzTestData.assumePresent("Map.wz");
        List<FootholdCheck.Seg> fh = footholds(100000000);
        assertTrue(fh.size() > 100, "Henesys has its footholds");
        Platform band = new Platform(-286, 7, List.of(new Point(-286, 101), new Point(7, 94)),
                Platform.Type.FLAT, ground(100000000));

        for (Point spawn : PlatformSpawner.findUnoccupiedPoints(band, List.of(), 8)) {
            FootholdCheck.Verdict v = FootholdCheck.check(fh, spawn.x, spawn.y, FootholdCheck.Pose.GROUNDED);
            assertEquals(FootholdCheck.Kind.OK, v.kind(),
                    spawn + " sits off its ground " + v.groundY() + " by " + v.dy() + " px");
        }

        assertEquals(101, band.getYAtX(-286), "the left end is on the 101 ledge");
        assertEquals(94, band.getYAtX(7), "the right end is on the 94 ledge, 7 px above the range's first point");
    }

    @Test
    void theOtherHenesysFillerRangesStandOnTheGround() throws Exception {
        WzTestData.assumePresent("Map.wz");
        List<FootholdCheck.Seg> fh = footholds(100000000);
        int[][] ranges = {{-696, -10, 274}, {-144, 36, 218}, {248, 573, 274}, {2596, 3347, 334}, {3393, 4247, 124},
                {3831, 4382, 454}, {4832, 5762, 454}, {5547, 6232, -176}, {4732, 5424, -116}};
        for (int[] r : ranges) {
            for (int x = r[0]; x <= r[1]; x += 10) {
                assertEquals(FootholdCheck.Kind.OK, FootholdCheck.check(fh, x, r[2], FootholdCheck.Pose.GROUNDED).kind(),
                        "x " + x + " y " + r[2]);
            }
        }
    }
}
