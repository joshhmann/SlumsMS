package soloMapling.ArtificialPlayer.BotTravelSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import constants.id.MapId;
import net.server.channel.handlers.HyperTPHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.server.MapleVersionManager.isPortalinCurrentVersion;

// Ticket 03 follow-up: the hops into the high-level and MapleRoot areas (ticket 31's 120-255 path). Like
// BotTransitTest, these hold every new row to the script it mirrors: the NPC sends you where the row says, the
// scripted portal warps where the row says, the level and rebirth checks are the scripts' own, and the hyper rock
// keeps HyperTPHandler's rules.
class BotTransitHighLevelTest {

    private static final String YAML = "src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.yaml";

    private static List<BotTransit.Edge> table() throws Exception {
        try (FileReader in = new FileReader(YAML)) {
            return BotTransit.parse(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawRows() throws Exception {
        try (FileReader in = new FileReader(YAML)) {
            Map<String, Object> root = (Map<String, Object>) new YamlReader(in).read();
            return (List<Map<String, Object>>) root.get("edges");
        }
    }

    private static BotTransit.Edge row(String name, int from, int to) throws Exception {
        return table().stream().filter(e -> e.name().equals(name) && e.fromMapId() == from && e.toMapId() == to)
                .findFirst().orElseThrow(() -> new AssertionError("no row '" + name + "' " + from + " -> " + to));
    }

    private static String npcScript(int npcId) throws IOException {
        Path p = Path.of("scripts/npc/" + npcId + ".js");
        return Files.exists(p) ? Files.readString(p) : null;
    }

    @AfterEach
    void restore() {
        BotTransit.reload();
    }

    @Test
    void everyScriptedPortalRowWarpsWhereItsScriptWarps() throws Exception {
        int checked = 0;
        for (Map<String, Object> r : rawRows()) {
            if (!"portal".equals(r.get("via"))) {
                continue;
            }
            Object script = r.get("script");
            assertNotNull(script, "portal row " + r + " names no script");
            Path p = Path.of("scripts/portal/" + script + ".js");
            assertTrue(Files.exists(p), "no portal script " + p);
            String js = Files.readString(p);
            String to = r.get("to").toString();
            String toPortal = r.get("to_portal") == null ? null : r.get("to_portal").toString();
            boolean warps = js.contains("warp(" + to + ", " + toPortal + ")")
                    || js.contains("warp(" + to + ", \"" + toPortal + "\")");
            java.util.regex.Matcher menu = java.util.regex.Pattern.compile("openNpc\\(\\d+, \"(\\w+)\"\\)").matcher(js);
            if (!warps && menu.find()) {
                // a portal that opens a menu (the Gate of Time): the menu script lists the map and warps to it
                String npc = Files.readString(Path.of("scripts/npc/" + menu.group(1) + ".js"));
                warps = npc.contains("[" + to + ",") && npc.contains("warp(to, " + toPortal + ")");
            }
            assertTrue(warps, script + ".js does not warp to " + to + " portal " + toPortal);
            checked++;
        }
        assertTrue(checked >= 10);
    }

    @Test
    void everyFreeNewNpcRowGoesWhereItsNpcSends() throws Exception {
        // The cab and boarding rows are BotTransitTest's; these are the guides, gates and lifts this follow-up adds.
        String[] names = {"Jeff's gate", "teleporter", "whale to Rien", "whale to Lith Harbor", "Florina tour",
                "taxi to the Haunted House", "taxi to NLC", "Malaysia tour", "Kampung bus", "Metropolis bus",
                "hideout", "hideout exit", "Fox Tree lift"};
        int checked = 0;
        for (BotTransit.Edge e : table()) {
            if (e.via() != BotTransit.Via.NPC || !List.of(names).contains(e.name())) {
                continue;
            }
            String script = npcScript(e.npcId());
            assertNotNull(script, "no script for npc " + e.npcId());
            assertTrue(script.contains(String.valueOf(e.toMapId())),
                    e.name() + ": npc " + e.npcId() + " never sends anyone to " + e.toMapId());
            if (e.fare() > 0) {
                assertTrue(script.contains(String.valueOf(e.fare())), e.name() + ": fare " + e.fare() + " not in npc "
                        + e.npcId());
            } else {
                assertFalse(script.contains("gainMeso(-"), e.name() + ": npc " + e.npcId() + " charges, the row doesn't");
            }
            checked++;
        }
        assertTrue(checked >= 35, "checked " + checked);
    }

    @Test
    void jeffLetsOnlyLevelFiftyPastIceValley() throws Exception {
        BotTransit.Edge jeff = row("Jeff's gate", 211040200, 211040300);

        assertEquals(50, jeff.minLevel());
        assertEquals(5, jeff.toPortalId());
        String js = npcScript(2030000);
        assertTrue(js.contains("cm.getLevel() >= 50") && js.contains("cm.warp(211040300, 5)"));
    }

    @Test
    void theHenesysTeleporterOffersExactlyItsGrandisAndArcaneMenusAtTheirRebirths() throws Exception {
        String js = npcScript(9010002);
        // ticket 81: the script asks server.maps.AreaGates per destination (level 120 for the Future and lower
        // Grandis; owner 2026-10-29: 1 rebirth for deeper Grandis, 2 for Arcane River), and the bots' rows below carry
        // the same checks
        assertTrue(js.contains("AreaGates.denyReason"), "the Grandis/Arcane menus are gated by AreaGates");
        Matcher m = Pattern.compile("\\[([0-9, ]+)\\][^\\[]*// (Grandis|Arcane River)").matcher(js);
        List<Integer> offered = new ArrayList<>();
        while (m.find()) {
            for (String id : m.group(1).split(",")) {
                offered.add(Integer.parseInt(id.trim()));
            }
        }
        assertEquals(24, offered.size());

        List<Integer> rows = new ArrayList<>();
        for (BotTransit.Edge e : table()) {
            if (e.npcId() == 9010002) {
                assertEquals(100000000, e.fromMapId());
                if (server.maps.AreaGates.isFuture(e.toMapId()) || server.maps.AreaGates.isLowerGrandis(e.toMapId())) {
                    // ticket 81: the Future and lower Grandis open at level 120 (AND-only rows: no rebirth check)
                    assertEquals(120, e.minLevel(), e.toMapId() + " opens at level 120");
                    assertEquals(0, e.minRebirths(), e.toMapId());
                } else {
                    assertEquals(server.maps.AreaGates.requiredRebirths(e.toMapId()), e.minRebirths(),
                            e.toMapId() + " needs the rebirths AreaGates asks for");
                    assertTrue(e.minRebirths() == 1 || e.minRebirths() == 2, e.toMapId() + ": deeper Grandis 1, Arcane 2");
                }
                assertEquals(0, e.fare());
                rows.add(e.toMapId());
            }
        }
        for (int map : offered) {
            if (BotMapGates.requiredRebirths(map) == BotMapGates.NEVER) {
                assertFalse(rows.contains(map), map + " is a map no bot may enter");
            } else {
                assertTrue(rows.contains(map), "the teleporter offers " + map + " but no row does");
            }
        }
        assertEquals(offered.size() - 1, rows.size()); // all but the Knight Stronghold
    }

    @Test
    void theRienWhaleRidesTheServersOwnOneMinuteTimer() throws Exception {
        String mapleMap = Files.readString(Path.of("src/main/java/server/maps/MapleMap.java"));
        assertEquals(200090060, MapId.FROM_LITH_TO_RIEN);
        assertEquals(200090070, MapId.FROM_RIEN_TO_LITH);
        assertEquals(140020300, MapId.DANGEROUS_FOREST);
        assertTrue(mapleMap.contains("chr.changeMap(MapId.DANGEROUS_FOREST, 0)"));
        assertTrue(mapleMap.contains("chr.changeMap(MapId.LITH_HARBOUR, 3)"));

        BotTransit.Edge out = row("whale to Rien", 200090060, 140020300);
        BotTransit.Edge back = row("whale to Lith Harbor", 200090070, 104000000);
        for (BotTransit.Edge ride : List.of(out, back)) {
            assertTrue(ride.isRide() && ride.serverDriven());
            assertEquals(60, ride.sec());
        }
        assertEquals(3, back.toPortalId());
        assertTrue(npcScript(1200004).contains("cm.warp(200090060)"));
        assertTrue(npcScript(1200003).contains("cm.warp(200090070)"));
    }

    @Test
    void malaysiaToursSaveTheTownAndTheWayHomeReturnsToIt() throws Exception {
        BotTransit.Edge cbd = row("Malaysia tour", 540000000, 550000000);
        BotTransit.Edge quay = row("world tour", 541000000, 550000000);
        assertEquals("WORLDTOUR", cbd.saveLocation());
        assertEquals("WORLDTOUR", quay.saveLocation());
        assertEquals(42000, cbd.fare());
        assertEquals(10000, quay.fare());
        assertTrue(npcScript(9000020).contains("541000000") && npcScript(9000020).contains("550000000"));
        for (int home : new int[]{540000000, 541000000}) {
            BotTransit.Edge back = row("Malaysia tour home", 550000000, home);
            assertEquals("WORLDTOUR", back.returnToSaved());
            assertEquals(0, back.fare());
        }
        assertTrue(npcScript(9201135).contains("getSavedLocation(\"WORLDTOUR\")"));
    }

    @Test
    void florinaReturnNamesLithHarbourBecauseOnlyLithHarbourSendsBotsThere() throws Exception {
        List<BotTransit.Edge> into = table().stream().filter(e -> e.toMapId() == 110000000).toList();
        assertEquals(1, into.size());
        assertEquals(104000000, into.get(0).fromMapId());
        assertEquals("FLORINA", into.get(0).saveLocation());
        assertTrue(npcScript(1081001).contains("returnmap = 104000000"), "Pison's default is Lith Harbor");
        assertNotNull(row("Florina tour home", 110000000, 104000000));
    }

    @Test
    void theVersionGateNoLongerHidesTheTownsThePathTrainsIn() {
        for (int map : new int[]{270000000, 130000000, 130000200, 551000100}) {
            assertTrue(isPortalinCurrentVersion(map), map + " is still version-gated for bots");
        }
        assertFalse(isPortalinCurrentVersion(120000000), "Nautilus stays gated");
    }

    // ── The hyper rock ──

    @Test
    void theShippedHyperRockKeepsHyperTPHandlersRules() throws Exception {
        BotTransit.HyperRock rock;
        try (FileReader in = new FileReader(YAML)) {
            rock = BotTransit.parseHyperRock(in);
        }
        String handler = Files.readString(Path.of("src/main/java/net/server/channel/handlers/HyperTPHandler.java"));
        assertTrue(handler.contains("COOLDOWN_TIME = 300000"));
        assertTrue(handler.contains("REQUIRED_ITEM_ID = " + BotTransit.HYPER_ROCK_ITEM));
        assertEquals(300, rock.cooldownSec());
        assertFalse(rock.into().isEmpty());
        for (int map : rock.into()) {
            assertFalse(HyperTPHandler.isRestrictedMap(map), map + " is closed to the rock");
            assertEquals(0, BotMapGates.requiredRebirths(map), map + ": the rock's ungated way in");
            assertFalse(rock.leavesFrom(map) && rock.home().contains(map));
        }
        assertTrue(rock.into().containsAll(List.of(310070000, 310070310)), "Black Heaven");
        assertTrue(rock.leavesFrom(310070120) && rock.leavesFrom(450001011) && rock.leavesFrom(400000000));
        assertFalse(rock.leavesFrom(100000000));
        assertTrue(rock.home().contains(100000000));
    }

    @Test
    void theRockReachesItsAreasFromAnywhereAndLeavesThemOnlyForHome() throws Exception {
        BotTransit.install(List.of());
        BotTransit.installHyperRock(new BotTransit.HyperRock(300, List.of(310070000),
                List.<int[]>of(new int[]{310000000, 311000000}), List.of(100000000)));

        BotTransit.Edge in = BotTransit.edge(200000000, 310070000);
        assertNotNull(in);
        assertEquals(BotTransit.Via.HYPER_ROCK, in.via());
        assertEquals(0, in.fare());
        assertTrue(in.isBoard());
        assertNotNull(BotTransit.edge(310070120, 100000000), "home from Black Heaven");
        assertNull(BotTransit.edge(200000000, 100000000), "not a general teleport between towns");
        assertNull(BotTransit.edge(310070000, 310070000));
    }

    @Test
    void aDisabledOrMissingBlockMeansNoRock() throws Exception {
        assertTrue(BotTransit.parseHyperRock(new StringReader("edges: []\n")).into().isEmpty());
        BotTransit.HyperRock off = BotTransit.parseHyperRock(new StringReader("""
                hyper_rock:
                  enabled: false
                  into: [310070000]
                """));
        assertTrue(off.into().isEmpty());
    }

    @Test
    void anInstalledTestTableHasNoRockUntilGivenOne() {
        BotTransit.install(List.of());
        assertNull(BotTransit.edge(100000000, 310070000));
    }
}
