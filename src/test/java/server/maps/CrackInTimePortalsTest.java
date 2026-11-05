package server.maps;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.wz.XMLDomMapleData;
import soloMapling.WzTestData;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b300 (1.6.4 blocker): Crack in Time 272000000 (Leafre of the Past) and the Temple of Time 270000000.
 * (1) The Gate of Time portal in the real map data must bind to the menu script through PortalFactory (the fallback
 * script name is map id + the portal's INDEX node name, so portal1 at index 10 runs scripts/portal/270000000.10.js).
 * (2) Every portal of the Temple and of 272000000..272000699 that targets a map must name a portal that exists on
 * the target, in the stock Map.wz plus tools/data-align/portal-fixes.tsv (skips without wz/).
 */
class CrackInTimePortalsTest {

    private static final Pattern IMGDIR = Pattern.compile("<imgdir name=\"(\\d+)\">(.*?)</imgdir>", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("<(?:string|int) name=\"(\\w+)\" value=\"([^\"]*)\"/>");
    private static final Path FIXES = Path.of("tools/data-align/portal-fixes.tsv");

    private static Path mapFile(int mapId) {
        return WzTestData.dir().resolve("Map.wz/Map/Map" + (mapId / 100000000) + "/" + mapId + ".img.xml");
    }

    /** portal index -> fields (pn, tm, tn, pt, script), with the portal-fixes.tsv target names applied. */
    private static Map<String, Map<String, String>> portals(int mapId) throws IOException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        String xml = Files.readString(mapFile(mapId));
        int at = xml.indexOf("<imgdir name=\"portal\">");
        int end = xml.indexOf("<imgdir name=\"reactor\">", at);
        Matcher p = IMGDIR.matcher(xml.substring(at + 1, end < 0 ? xml.length() : end));
        while (p.find()) {
            Map<String, String> v = new HashMap<>();
            Matcher f = FIELD.matcher(p.group(2));
            while (f.find()) {
                v.put(f.group(1), f.group(2));
            }
            out.put(p.group(1), v);
        }
        String suffix = "Map.wz/Map/Map" + (mapId / 100000000) + "/" + mapId + ".img.xml";
        for (String line : Files.readAllLines(FIXES)) {
            String[] c = line.split("\t");
            if (line.startsWith("#") || c.length < 4 || !c[0].equals(suffix) || !c[1].matches("portal/\\d+/tn")) {
                continue;
            }
            Map<String, String> portal = out.get(c[1].split("/")[1]);
            if (portal != null && portal.get("tn").equals(c[2])) {
                portal.put("tn", c[3]);
            }
        }
        return out;
    }

    private static List<Integer> checkedMaps() throws IOException {
        List<Integer> maps = new ArrayList<>();
        maps.add(270000000);
        for (int id = 272000000; id < 272000700; id += 10) {
            if (Files.exists(mapFile(id))) {
                maps.add(id);
            }
        }
        return maps;
    }

    @Test
    void everyPortalTargetNamesAPortalThatExists() throws IOException {
        WzTestData.assumePresent("Map.wz");
        List<String> bad = new ArrayList<>();
        for (int map : checkedMaps()) {
            for (Map<String, String> portal : portals(map).values()) {
                int tm = Integer.parseInt(portal.get("tm"));
                String tn = portal.get("tn");
                if (tm == 999999999 || tn == null || tn.isEmpty()) {
                    continue;
                }
                if (!Files.exists(mapFile(tm))) {
                    bad.add(map + " " + portal.get("pn") + " -> missing map " + tm);
                    continue;
                }
                boolean found = portals(tm).values().stream().anyMatch(t -> tn.equals(t.get("pn")));
                if (!found) {
                    bad.add(map + " " + portal.get("pn") + " -> " + tm + "/" + tn + " does not exist");
                }
            }
        }
        assertTrue(bad.isEmpty(), "portals to nowhere: " + bad);
    }

    @Test
    void crackInTimeWestDoorLandsOnTheGateOfTime() throws IOException {
        WzTestData.assumePresent("Map.wz");
        Map<String, String> west = portals(272000000).get("1");
        assertEquals("west00", west.get("pn"));
        assertEquals("270000000", west.get("tm"));
        assertEquals("portal1", west.get("tn"));
        assertTrue(portals(270000000).values().stream().anyMatch(t -> "portal1".equals(t.get("pn"))));
    }

    @Test
    void theGateOfTimePortalOfTheRealMapRunsTheMenuScript() throws Exception {
        WzTestData.assumePresent("Map.wz");
        Path file = mapFile(270000000);
        Data map;
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            map = new XMLDomMapleData(in, file.getParent());
        }
        PortalFactory factory = new PortalFactory();
        Portal gate = null;
        for (Data p : map.getChildByPath("portal").getChildren()) {
            if ("portal1".equals(String.valueOf(p.getChildByPath("pn").getData()))) {
                gate = factory.makePortal(Integer.parseInt(String.valueOf(p.getChildByPath("pt").getData())), 270000000, p);
            }
        }
        assertNotNull(gate, "Temple of Time has the portal named portal1");
        assertEquals("270000000.10", gate.getScriptName(), "the menu script is bound, not a straight warp to the Future");
    }
}
