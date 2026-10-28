package soloMapling.menus;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import soloMapling.WzTestData;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 15 decorative Lazy Penguins (NPCs 2985-2999, migration 046): the data table, the migration rows, a script each,
 * and (when the WZ is present) every spot on a real foothold, clear of portals and map NPCs.
 */
class LazyPenguinsTest {

    private static final Path TSV = Path.of("tools/data-align/lazy-penguins/penguins.tsv");
    private static final Path SQL = Path.of("database/migrations/046-lazy-penguins.sql");
    private static final List<String> VARIANTS = List.of("Christmas", "Cherry", "Lemon", "Sunny", "Cocoa", "Popcorn",
            "Blueberry", "Tangerine", "Sage", "Teal", "Clown", "Pebble", "Berry", "Mint", "Classic");
    private static final Pattern ROW = Pattern.compile(
            "SELECT (\\d{9})(?: AS map)?, (\\d{4})(?: AS life)?, (-?\\d+)(?: AS x)?, (-?\\d+)(?: AS y)?, (\\d+)(?: AS fh)?, (\\d)(?: AS f)?");

    private record Penguin(int id, String variant, int map, String town, int x, int y, int fh, int flip) {
    }

    private static List<Penguin> table() throws Exception {
        List<Penguin> out = new ArrayList<>();
        for (String line : Files.readAllLines(TSV)) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] c = line.split("\t");
            out.add(new Penguin(Integer.parseInt(c[0]), c[1], Integer.parseInt(c[6]), c[7], Integer.parseInt(c[8]),
                    Integer.parseInt(c[9]), Integer.parseInt(c[10]), Integer.parseInt(c[11])));
        }
        return out;
    }

    @Test
    void fifteenPenguinsWithTheRightIdsVariantsAndOneTownEach() throws Exception {
        List<Penguin> t = table();
        assertEquals(15, t.size());
        List<Integer> maps = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            assertEquals(2985 + i, t.get(i).id());
            assertEquals(VARIANTS.get(i), t.get(i).variant());
            assertFalse(maps.contains(t.get(i).map()), "one penguin per map");
            maps.add(t.get(i).map());
        }
        assertEquals(209000000, t.get(0).map(), "Christmas is in Happyville");
    }

    @Test
    void theMigrationPlacesExactlyThoseRowsAndIsRerunnableAndReversible() throws Exception {
        String sql = Files.readString(SQL);
        Matcher m = ROW.matcher(sql);
        List<Penguin> t = table();
        int n = 0;
        while (m.find()) {
            Penguin p = t.get(n++);
            assertEquals(p.map(), Integer.parseInt(m.group(1)));
            assertEquals(p.id(), Integer.parseInt(m.group(2)));
            int[] moved = movedBy048().get(p.id());   // 046 keeps the original spot; 048 moves it to the tsv's
            if (moved == null) {
                assertEquals(p.x(), Integer.parseInt(m.group(3)));
                assertEquals(p.y(), Integer.parseInt(m.group(4)));
                assertEquals(p.fh(), Integer.parseInt(m.group(5)));
            } else {
                assertEquals(moved[0], Integer.parseInt(m.group(3)));
                assertEquals(moved[1], Integer.parseInt(m.group(4)));
                assertEquals(moved[2], Integer.parseInt(m.group(5)));
            }
            assertEquals(p.flip(), Integer.parseInt(m.group(6)));
        }
        assertEquals(15, n);
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life)"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('046-lazy-penguins')"));
        String rb = Files.readString(Path.of("database/migrations/046-lazy-penguins.rollback.sql"));
        assertTrue(rb.contains("migration_id = '046-lazy-penguins' AND table_name = 'plife'"));
        assertTrue(Files.readString(Path.of("docs/release/1.6.1.conf")).contains("046-lazy-penguins"), "listed in 1.6.1.conf");
    }

    /** npc id to its old (046) x, y, fh, read from migration 048's forward rows; the new spot must equal the tsv. */
    private static Map<Integer, int[]> movedBy048() throws Exception {
        Matcher m = Pattern.compile("(?:SELECT|UNION ALL SELECT) (\\d{9})(?: AS map)?, (\\d{4})(?: AS life)?, (-?\\d+)(?: AS ox)?, (-?\\d+)(?: AS oy)?, (\\d+)(?: AS ofh)?, (-?\\d+)(?: AS nx)?, (-?\\d+)(?: AS ny)?, (\\d+)")
                .matcher(Files.readString(Path.of("database/migrations/048-custom-npc-spots.sql")));
        Map<Integer, int[]> out = new HashMap<>();
        Map<Integer, Penguin> byId = new HashMap<>();
        for (Penguin p : table()) {
            byId.put(p.id(), p);
        }
        while (m.find()) {
            int id = Integer.parseInt(m.group(2));
            Penguin p = byId.get(id);
            assertEquals(p.map(), Integer.parseInt(m.group(1)));
            assertEquals(p.x(), Integer.parseInt(m.group(6)), id + " 048 new x = tsv");
            assertEquals(p.y(), Integer.parseInt(m.group(7)), id + " 048 new y = tsv");
            assertEquals(p.fh(), Integer.parseInt(m.group(8)), id + " 048 new fh = tsv");
            out.put(id, new int[]{Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))});
        }
        assertEquals(9, out.size(), "nine penguins move");
        return out;
    }

    @Test
    void everyPenguinHasASmallPg13ScriptThatNeverNamesTheGame() throws Exception {
        for (Penguin p : table()) {
            String js = Files.readString(Path.of("scripts/npc/" + p.id() + ".js"));
            assertTrue(js.contains("cm.sendOk(\""), p.id() + " says a line");
            assertTrue(js.contains("cm.dispose()"));
            String low = js.toLowerCase();
            assertFalse(low.contains("maplestory") || low.contains("nexon"), p.id() + " stays out of it");
        }
    }

    @Test
    void whenTheHostWzHasThemEachLoadsWithNameAndFunc() throws Exception {
        WzTestData.assumePresent("String.wz");
        Path strings = WzTestData.dir().resolve("String.wz/Npc.img.xml");
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings.toFile()).getDocumentElement();
        Map<String, Map<String, String>> byId = new HashMap<>();
        for (var n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getAttribute("name").matches("29(8[5-9]|9\\d)")) {
                byId.putIfAbsent(e.getAttribute("name"), attrs(e));
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(!byId.isEmpty(), "host WZ not patched yet (add_lazy_penguins.py)");
        for (Penguin p : table()) {
            Map<String, String> a = byId.get(String.valueOf(p.id()));
            assertNotNull(a, p.id() + " has a String.wz entry");
            assertEquals("Lazy Penguin", a.get("name"));
            assertEquals(p.variant(), a.get("func"));
        }
    }

    @Test
    void everySpotIsOnARealFootholdClearOfPortalsAndNpcs() throws Exception {
        WzTestData.assumePresent("Map.wz");
        Path wz = WzTestData.dir();
        for (Penguin p : table()) {
            Path file = wz.resolve("Map.wz/Map/Map" + p.map() / 100000000 + "/" + p.map() + ".img.xml");
            Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            int[] fh = foothold(child(root, "foothold"), p.fh());
            assertNotNull(fh, p.town() + ": foothold " + p.fh() + " exists");
            assertTrue(fh[0] != fh[2], p.town() + ": not a wall");
            assertTrue(p.x() >= Math.min(fh[0], fh[2]) + 20 && p.x() <= Math.max(fh[0], fh[2]) - 20, p.town() + ": over its foothold, off the edge");
            double ground = fh[1] + (fh[3] - fh[1]) * (double) (p.x() - fh[0]) / (fh[2] - fh[0]);
            assertTrue(Math.abs(ground - p.y()) <= 1, p.town() + ": on the ground (" + ground + " vs " + p.y() + ")");
            assertEquals(List.of(), FrontObjects.covering(wz, p.map(), p.x(), p.y(), p.fh(), 25), p.town() + ": no map object drawn over her");
            assertTrue(Math.abs(fh[3] - fh[1]) <= 0.25 * Math.abs(fh[2] - fh[0]), p.town() + ": gentle slope");
            for (Map<String, String> q : entries(child(root, "portal"))) {
                if (Math.abs(Integer.parseInt(q.get("y")) - p.y()) <= 120) {
                    assertTrue(Math.abs(Integer.parseInt(q.get("x")) - p.x()) >= 60, p.town() + ": clear of portal " + q.get("pn"));
                }
            }
            for (Map<String, String> n : entries(child(root, "life"))) {
                if ("n".equals(n.get("type")) && Math.abs(Integer.parseInt(n.get("cy")) - p.y()) < 100) {
                    assertTrue(Math.abs(Integer.parseInt(n.get("x")) - p.x()) >= 90, p.town() + ": clear of npc " + n.get("id"));
                }
            }
        }
    }

    private static Map<String, String> attrs(Element e) {
        Map<String, String> m = new HashMap<>();
        for (var k = e.getFirstChild(); k != null; k = k.getNextSibling()) {
            if (k instanceof Element v) {
                m.put(v.getAttribute("name"), v.getAttribute("value"));
            }
        }
        return m;
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
        for (var n = section == null ? null : section.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                out.add(attrs(e));
            }
        }
        return out;
    }

    private static int[] foothold(Element footholds, int id) {
        for (var l = footholds.getFirstChild(); l != null; l = l.getNextSibling()) {
            for (var g = l.getFirstChild(); g != null; g = g.getNextSibling()) {
                for (var f = g.getFirstChild(); f != null; f = f.getNextSibling()) {
                    if (f instanceof Element fe && String.valueOf(id).equals(fe.getAttribute("name"))) {
                        Map<String, String> v = attrs(fe);
                        return new int[]{Integer.parseInt(v.get("x1")), Integer.parseInt(v.get("y1")),
                                Integer.parseInt(v.get("x2")), Integer.parseInt(v.get("y2"))};
                    }
                }
            }
        }
        return null;
    }
}
