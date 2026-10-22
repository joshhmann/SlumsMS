package soloMapling.Economy;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Migrations 020 (economy tables) and 021 (the cosmetics merchant): ledger ids, rollbacks, the merchant's spot. */
class EconomyMigrationTest {

    static final Path DIR = Path.of("database/migrations");

    @Test
    void bothMigrationsWriteTheirLedgerIdAndHaveARollback() throws Exception {
        for (String id : List.of("020-economy-trade", "021-cosmetics-merchant")) {
            String sql = Files.readString(DIR.resolve(id + ".sql"));
            String rollback = Files.readString(DIR.resolve(id + ".rollback.sql"));
            assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + id + "')"), id);
            assertTrue(rollback.contains("migration_id='" + id + "'") || rollback.contains("migration_id = '" + id + "'"),
                    id + " rollback clears its ledger row");
        }
        String merchantRollback = Files.readString(DIR.resolve("021-cosmetics-merchant.rollback.sql"));
        assertTrue(merchantRollback.contains("DELETE FROM plife WHERE id IN ("));
        assertTrue(merchantRollback.contains("SELECT row_id FROM mapleroot_integration_migration_rows"));
        assertTrue(merchantRollback.contains("migration_id = '021-cosmetics-merchant' AND table_name = 'plife'"));
        String econ = Files.readString(DIR.resolve("020-economy-trade.sql"));
        for (String table : List.of("pricesamples", "economyday", "botgifts")) {
            assertTrue(econ.contains("CREATE TABLE IF NOT EXISTS " + table), table);
            assertTrue(Files.readString(DIR.resolve("020-economy-trade.rollback.sql"))
                    .contains("DROP TABLE IF EXISTS " + table), table);
        }
        // the JDBC store names the same tables and columns
        for (String sql : List.of(EconomyStore.Jdbc.INSERT, EconomyStore.Jdbc.METER_UPSERT, EconomyStore.Jdbc.GIFT_UPSERT)) {
            Matcher m = Pattern.compile("INSERT INTO (\\w+) \\(([^)]*)\\)").matcher(sql);
            assertTrue(m.find(), sql);
            for (String col : m.group(2).split(",\\s*")) {
                assertTrue(econ.contains(col.trim() + " "), col + " is a column of " + m.group(1));
            }
        }
    }

    static final Pattern ROW = Pattern.compile("SELECT (\\d{9}) AS map, (\\d{7}) AS life, (-?\\d+) AS x, (-?\\d+) AS y, (\\d+) AS fh");

    @Test
    void theMerchantStandsOnTheGroundClearOfPortalsAndNpcs() throws Exception {
        Matcher m = ROW.matcher(Files.readString(DIR.resolve("021-cosmetics-merchant.sql")));
        assertTrue(m.find());
        int map = Integer.parseInt(m.group(1)), npc = Integer.parseInt(m.group(2)), x = Integer.parseInt(m.group(3)),
                y = Integer.parseInt(m.group(4)), fhId = Integer.parseInt(m.group(5));
        assertEquals(910000000, map);
        assertEquals(MesoSinks.COSMETIC_NPC_DEFAULT, npc, "the NPC the server opens the cosmetics shop for");
        WzTestData.assumePresent("Map.wz");
        Path wz = WzTestData.dir();
        var root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(wz.resolve("Map.wz/Map/Map9/910000000.img.xml").toFile()).getDocumentElement();
        int[] fh = foothold(child(root, "foothold"), fhId);
        assertNotNull(fh);
        assertTrue(x >= Math.min(fh[0], fh[2]) && x <= Math.max(fh[0], fh[2]), "over its foothold");
        assertEquals(fh[1], y, "on the ground");
        for (Map<String, String> p : entries(child(root, "portal"))) {
            int pt = Integer.parseInt(p.get("pt"));
            if (Math.abs(Integer.parseInt(p.get("y")) - y) <= 120 && Set.of(1, 2, 3, 7, 10).contains(pt)) {
                assertTrue(Math.abs(Integer.parseInt(p.get("x")) - x) >= 160, "clear of portal " + p.get("pn"));
            }
        }
        for (Map<String, String> n : entries(child(root, "life"))) {
            if ("n".equals(n.get("type")) && Math.abs(Integer.parseInt(n.get("cy")) - y) < 80) {
                assertTrue(Math.abs(Integer.parseInt(n.get("x")) - x) >= 90, "clear of npc " + n.get("id"));
            }
        }
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
