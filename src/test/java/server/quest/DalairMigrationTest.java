package server.quest;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration 026: Dalair (9000066), who takes in the medal quests, stands on no map in the server data, so those
 * quests could never be turned in (QuestActionHandler wants the NPC on the player's map). The migration places him
 * in Henesys; this checks the ledger, the rollback and that the spot is real ground clear of portals and NPCs.
 */
class DalairMigrationTest {

    private static final Path DIR = Path.of("database/migrations");
    private static final String ID = "026-dalair-henesys";
    private static final int DALAIR = 9000066;
    private static final Pattern ROW =
            Pattern.compile("SELECT (\\d{9}) AS map, (\\d{7}) AS life, (-?\\d+) AS x, (-?\\d+) AS y, (\\d+) AS fh");

    @Test
    void theMigrationWritesItsLedgerIdAndItsRollbackRemovesOnlyItsOwnRows() throws Exception {
        String sql = Files.readString(DIR.resolve(ID + ".sql"));
        String rollback = Files.readString(DIR.resolve(ID + ".rollback.sql"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life)"),
                "applying twice adds nothing");
        assertTrue(rollback.contains("DELETE FROM plife WHERE id IN ("));
        assertTrue(rollback.contains("migration_id = '" + ID + "' AND table_name = 'plife'"));
        assertTrue(rollback.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id = '" + ID + "'"));
    }

    @Test
    void dalairStandsOnTheHenesysGroundClearOfPortalsAndNpcs() throws Exception {
        Matcher m = ROW.matcher(Files.readString(DIR.resolve(ID + ".sql")));
        assertTrue(m.find());
        int map = Integer.parseInt(m.group(1)), npc = Integer.parseInt(m.group(2)), x = Integer.parseInt(m.group(3)),
                y = Integer.parseInt(m.group(4)), fhId = Integer.parseInt(m.group(5));
        assertEquals(100000000, map, "Henesys");
        assertEquals(DALAIR, npc);

        WzTestData.assumePresent("Map.wz");
        Path wz = WzTestData.dir();
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(wz.resolve("Map.wz/Map/Map1/100000000.img.xml").toFile()).getDocumentElement();
        int[] fh = foothold(child(root, "foothold"), fhId);
        assertNotNull(fh, "foothold " + fhId + " exists");
        assertTrue(x >= Math.min(fh[0], fh[2]) && x <= Math.max(fh[0], fh[2]), "over its foothold");
        assertEquals(fh[1], fh[3], "a flat foothold");
        assertEquals(fh[1], y, "on the ground");
        for (Map<String, String> p : entries(child(root, "portal"))) {
            if (Math.abs(Integer.parseInt(p.get("y")) - y) <= 120) {
                int need = "0".equals(p.get("pt")) ? 100 : 160;   // spawn points vs. real portals
                assertTrue(Math.abs(Integer.parseInt(p.get("x")) - x) >= need, "clear of portal " + p.get("pn"));
            }
        }
        boolean placedInWz = false;
        for (Map<String, String> n : entries(child(root, "life"))) {
            if (!"n".equals(n.get("type"))) {
                continue;
            }
            placedInWz |= String.valueOf(DALAIR).equals(n.get("id"));
            if (Math.abs(Integer.parseInt(n.get("cy")) - y) < 80) {
                assertTrue(Math.abs(Integer.parseInt(n.get("x")) - x) >= 150, "clear of npc " + n.get("id"));
            }
        }
        assertTrue(!placedInWz, "the WZ does not already place him here (else the row would be a duplicate)");

        WzTestData.assumePresent("Npc.wz");
        assertTrue(Files.exists(wz.resolve("Npc.wz/" + npc + ".img.xml")), "the client can draw " + npc);
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

    private static int[] foothold(Element footholds, int id) {
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
