package server.maps;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 b039, against the real Map.wz (skips without wz/): lower Grandis is the part the player notes open at level
 * 120, so no map whose name says Heliseum, the Fox Tree or Cernium, and none of Savage Terminal's hidden back streets,
 * is on the list; and every portal from a lower map into a closed Grandis map is one the server checks: unscripted
 * (GenericPortal asks AreaGates before the warp) or a script that asks AreaGates itself.
 */
class LowerGrandisPortalsWzTest {

    private static final Pattern IMGDIR = Pattern.compile("<imgdir name=\"(\\d+)\">(.*?)</imgdir>", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("<(?:string|int) name=\"(\\w+)\" value=\"([^\"]*)\"/>");

    private static Map<Integer, String> mapNames() throws IOException {
        Map<Integer, String> out = new HashMap<>();
        String xml = Files.readString(WzTestData.dir().resolve("String.wz/Map.img.xml"));
        Matcher m = Pattern.compile("<imgdir name=\"(\\d{9})\">(.*?)</imgdir>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            StringBuilder name = new StringBuilder();
            Matcher f = FIELD.matcher(m.group(2));
            while (f.find()) {
                name.append(f.group(2)).append(" : ");
            }
            out.put(Integer.parseInt(m.group(1)), name.toString());
        }
        return out;
    }

    /** (portal index, target map, script or "") for each portal of the map with a target. */
    private static List<String[]> portals(int mapId) throws IOException {
        Path file = WzTestData.dir().resolve("Map.wz/Map/Map" + (mapId / 100000000) + "/" + mapId + ".img.xml");
        List<String[]> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        String xml = Files.readString(file);
        int at = xml.indexOf("<imgdir name=\"portal\">");
        if (at < 0) {
            return out;
        }
        Matcher p = IMGDIR.matcher(xml.substring(at));
        while (p.find()) {
            Map<String, String> v = new HashMap<>();
            Matcher f = FIELD.matcher(p.group(2));
            while (f.find()) {
                v.put(f.group(1), f.group(2));
            }
            String tm = v.getOrDefault("tm", "999999999");
            if (v.containsKey("pn") && !tm.equals("999999999")) { // portals; later sections have no pn
                out.add(new String[]{p.group(1), tm, v.getOrDefault("script", "").trim()});
            }
        }
        return out;
    }

    @Test
    void theDeeperPartsTheNotesNameAreNotOnTheList() throws IOException {
        WzTestData.assumePresent("String.wz");
        Map<Integer, String> names = mapNames();
        for (int map : AreaGates.lowerGrandis()) {
            String name = names.getOrDefault(map, "").toLowerCase();
            assertFalse(name.contains("heliseum") || name.contains("fox tree") || name.contains("cernium"),
                    map + " " + name + " is deeper Grandis (closed below the 1st rebirth)");
        }
        for (int alley = 402000400; alley <= 402000402; alley++) {
            assertFalse(AreaGates.isLowerGrandis(alley), "Savage Terminal back street " + alley);
        }
    }

    @Test
    void everyWayOutOfLowerGrandisIntoAClosedMapIsChecked() throws IOException {
        WzTestData.assumePresent("Map.wz");
        int boundary = 0;
        for (int map : AreaGates.lowerGrandis()) {
            for (String[] portal : portals(map)) {
                int to = Integer.parseInt(portal[1]);
                if (!AreaGates.isDeeperGrandis(to) && !AreaGates.isArcaneRiver(to)) {
                    continue;
                }
                boundary++;
                String script = portal[2];
                Path fallback = Path.of("scripts/portal/" + map + "." + portal[0] + ".js");
                if (script.isEmpty() && Files.exists(fallback)) {
                    script = map + "." + portal[0];
                }
                if (script.isEmpty()) {
                    continue; // GenericPortal.attemptTeleportForUnscriptedPortal asks AreaGates
                }
                Path js = Path.of("scripts/portal/" + script + ".js");
                assertTrue(Files.exists(js) && Files.readString(js).contains("AreaGates"),
                        "portal " + portal[0] + " of " + map + " -> " + to + " runs " + script + " without AreaGates");
            }
        }
        // Dimensional Door, Heliseum Hideout, Fox Tree, three back streets, Scrapyard, Sketchy Back-Alley, Dingy club
        assertEquals(9, boundary, "boundary portals out of lower Grandis");
    }
}
