package soloMapling.Gachapon;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import soloMapling.WzTestData;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Migration 013: the six town gachapon machines stand on real ground, clear of portals, and are wired to their pool. */
class GachaponMachinesMigrationTest {

    static final Path SQL = Path.of("database/migrations/013-gachapon-machines.sql");
    static final Path ROLLBACK = Path.of("database/migrations/013-gachapon-machines.rollback.sql");
    static final Pattern ROW = Pattern.compile("SELECT\\s+(?:(\\d{9}) AS map, (\\d{7}) AS life, (-?\\d+) AS x, (-?\\d+) AS y, "
            + "(\\d+) AS fh|(\\d{9}),\\s*(\\d{7}),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(\\d+))");

    record Machine(int map, int npc, int x, int y, int fh) {
    }

    static List<Machine> machines() throws Exception {
        List<Machine> out = new ArrayList<>();
        Matcher m = ROW.matcher(Files.readString(SQL));
        while (m.find()) {
            int o = m.group(1) != null ? 1 : 6;
            out.add(new Machine(Integer.parseInt(m.group(o)), Integer.parseInt(m.group(o + 1)),
                    Integer.parseInt(m.group(o + 2)), Integer.parseInt(m.group(o + 3)), Integer.parseInt(m.group(o + 4))));
        }
        return out;
    }

    @Test
    void sixMachinesEachWiredToItsTownPool() throws Exception {
        List<Machine> ms = machines();
        assertEquals(6, ms.size(), ms.toString());
        GachaPools pools;
        try (FileReader in = new FileReader(GachaPools.FILE)) {
            pools = GachaPools.parse(in, id -> true);
        }
        Set<String> towns = new TreeSet<>();
        for (Machine m : ms) {
            GachaPools.Town t = pools.byNpc(m.npc());
            assertNotNull(t, "npc " + m.npc() + " has a pool");
            assertTrue(t.maps().contains(m.map()), t.key() + " lists map " + m.map() + " (bots play there)");
            assertTrue(m.npc() >= 9100100 && m.npc() <= 9100117, "NPCTalkHandler sends it to gachapon.js");
            towns.add(t.key());
        }
        assertEquals(Set.of("ellinia", "perion", "kerning", "sleepywood", "mushroom_shrine", "new_leaf_city"), towns);
        String rollback = Files.readString(ROLLBACK);
        for (Machine m : ms) {
            assertTrue(rollback.contains("(" + m.map() + ", " + m.npc() + ")"), "rollback removes " + m);
        }
    }

    @Test
    void eachMachineStandsOnItsFootholdAwayFromPortalsAndNpcs() throws Exception {
        WzTestData.assumePresent("Map.wz");
        Path wz = WzTestData.dir();
        for (Machine m : machines()) {
            String id = String.valueOf(m.map());
            Path img = wz.resolve("Map.wz/Map/Map" + id.charAt(0) + "/" + id + ".img.xml");
            var root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(img.toFile()).getDocumentElement();
            Element footholds = child(root, "foothold"), portals = child(root, "portal"), life = child(root, "life");
            int[] fh = findFoothold(footholds, m.fh());
            assertNotNull(fh, m + ": foothold " + m.fh() + " exists");
            int lo = Math.min(fh[0], fh[2]), hi = Math.max(fh[0], fh[2]);
            assertTrue(m.x() >= lo && m.x() <= hi, m + " is over foothold " + m.fh() + " (" + lo + ".." + hi + ")");
            double gy = fh[1] + (double) (fh[3] - fh[1]) * (m.x() - fh[0]) / (fh[2] - fh[0]);
            assertEquals(gy, m.y(), 3, m + " stands on the ground");
            for (Map<String, String> p : entries(portals)) {
                int pt = Integer.parseInt(p.get("pt"));
                int px = Integer.parseInt(p.get("x")), py = Integer.parseInt(p.get("y"));
                if (Math.abs(py - m.y()) > 120) {
                    continue;
                }
                int min = Set.of(1, 2, 3, 7, 10).contains(pt) ? 160 : pt == 6 ? 90 : 0;
                assertTrue(Math.abs(px - m.x()) >= min, m + " is clear of portal " + p.get("pn") + " at " + px);
            }
            for (Map<String, String> n : entries(life)) {
                if (!"n".equals(n.get("type"))) {
                    continue;
                }
                int nx = Integer.parseInt(n.get("x")), ny = Integer.parseInt(n.get("cy"));
                assertTrue(Math.abs(ny - m.y()) >= 80 || Math.abs(nx - m.x()) >= 90, m + " is clear of npc " + n.get("id"));
                assertTrue(!String.valueOf(m.npc()).equals(n.get("id")), "the map has no machine of its own");
            }
            WzTestData.assumePresent("Npc.wz");
            assertTrue(Files.exists(wz.resolve("Npc.wz/" + m.npc() + ".img.xml")), "the client can draw " + m.npc());
        }
    }

    private static Element child(Element e, String name) {
        for (var n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && name.equals(c.getAttribute("name"))) {
                return c;
            }
        }
        return null;
    }

    private static List<Map<String, String>> entries(Element section) {
        List<Map<String, String>> out = new ArrayList<>();
        if (section == null) {
            return out;
        }
        for (var n = section.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                Map<String, String> m = new HashMap<>();
                for (var k = e.getFirstChild(); k != null; k = k.getNextSibling()) {
                    if (k instanceof Element v) {
                        m.put(v.getAttribute("name"), v.getAttribute("value"));
                    }
                }
                out.add(m);
            }
        }
        return out;
    }

    /** {x1, y1, x2, y2} of the foothold with this id (layer/group/id nesting), or null. */
    private static int[] findFoothold(Element footholds, int id) {
        for (var l = footholds.getFirstChild(); l != null; l = l.getNextSibling()) {
            for (var g = l.getFirstChild(); g != null; g = g.getNextSibling()) {
                for (var f = g.getFirstChild(); f != null; f = f.getNextSibling()) {
                    if (f instanceof Element fe && String.valueOf(id).equals(fe.getAttribute("name"))) {
                        Map<String, String> v = new HashMap<>();
                        for (var k = fe.getFirstChild(); k != null; k = k.getNextSibling()) {
                            if (k instanceof Element ke) {
                                v.put(ke.getAttribute("name"), ke.getAttribute("value"));
                            }
                        }
                        return new int[]{Integer.parseInt(v.get("x1")), Integer.parseInt(v.get("y1")),
                                Integer.parseInt(v.get("x2")), Integer.parseInt(v.get("y2"))};
                    }
                }
            }
        }
        return null;
    }
}
