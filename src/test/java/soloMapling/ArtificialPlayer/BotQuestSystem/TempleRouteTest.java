package soloMapling.ArtificialPlayer.BotQuestSystem;

import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;
import soloMapling.WzTestData;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 158 item 5: {@link TempleRoute} is a copy of the server's own data, and this keeps it one: the lane exits are
 * what {@code scripts/portal/timeQuest.js} really does (the script is run against a fake portal manager), the scripted
 * steps have their scripts, and, with {@code wz/} present, every quest, NPC map and lane against Quest.wz and Map.wz.
 */
class TempleRouteTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class Player {
        final int mapId;

        Player(int mapId) {
            this.mapId = mapId;
        }

        public int getMapId() {
            return mapId;
        }

        public void dropMessage(int type, String msg) {
        }
    }

    /** What timeQuest.js calls on its {@code pi}, nothing more. */
    public static final class Pi {
        final Player player;
        final Set<Integer> completed;
        int warpedTo = -1;
        String warpedPortal;

        Pi(int mapId, Set<Integer> completed) {
            this.player = new Player(mapId);
            this.completed = completed;
        }

        public Player getPlayer() {
            return player;
        }

        public void playPortalSound() {
        }

        public boolean isQuestCompleted(int id) {
            return completed.contains(id);
        }

        public boolean haveItem(int id) {
            return false;
        }

        public void playerMessage(int type, String msg) {
        }

        public void warp(int map, String portal) {
            warpedTo = map;
            warpedPortal = portal;
        }
    }

    private static Pi enter(int mapId, Set<Integer> completed) throws Exception {
        ScriptEngine engine = new Loader().load("portal/timeQuest.js");
        assertNotNull(engine);
        Pi pi = new Pi(mapId, completed);
        ((Invocable) engine).invokeFunction("enter", pi);
        return pi;
    }

    @Test
    void laneExitsAreWhatTimeQuestDoes() throws Exception {
        for (TempleRoute.LaneExit e : TempleRoute.EXITS) {
            Set<Integer> all = new HashSet<>();
            for (int q = 3500; q <= 3522; q++) {
                all.add(q);
            }
            Pi open = enter(e.fromMap(), all);
            assertEquals(e.toMap(), open.warpedTo, "exit of " + e.fromMap());
            assertEquals("out00", open.warpedPortal);
            Set<Integer> without = new HashSet<>(all);
            without.remove(e.quest());
            Pi shut = enter(e.fromMap(), without);
            assertFalse(shut.warpedTo == e.toMap(), e.fromMap() + " lets through without quest " + e.quest());
            assertEquals("in00", shut.warpedPortal, "sent back to the safe lane");
        }
    }

    @Test
    void theMainlineRunsInOrderAndItsScriptedStepsHaveScripts() {
        int prev = 3499;
        for (TempleRoute.Step s : TempleRoute.MAINLINE) {
            assertEquals(prev + 1, s.questId(), "no gaps in 3500-3520");
            prev = s.questId();
            assertEquals(s.talk(), s.lanes().isEmpty(), s.questId() + ": a kill quest has lanes, a talk quest none");
            if (s.startScript() || s.endScript()) {
                assertTrue(Files.exists(Path.of("scripts/quest/" + s.questId() + ".js")), s.questId() + " script");
            }
        }
        assertEquals(3520, prev);
        for (TempleRoute.SubQuest q : TempleRoute.SUB_QUESTS) {
            assertTrue(Files.exists(Path.of("scripts/quest/" + q.questId() + ".js")), q.questId() + " script");
        }
        // 3507's own end script names every sub-quest it accepts
        String s3507 = read("scripts/quest/3507.js");
        for (TempleRoute.SubQuest q : TempleRoute.SUB_QUESTS) {
            assertTrue(s3507.contains("isQuestCompleted(" + q.questId() + ")"), "3507 accepts " + q.questId());
        }
        String s3514 = read("scripts/quest/3514.js");
        assertTrue(s3514.contains("getMeso() >= " + TempleRoute.SORCERER_FEE));
        assertTrue(s3514.contains("gainItem(" + TempleRoute.SORCERER_POTION + ", 1)"));
        assertEquals(3523, TempleRoute.subQuestFor(112).questId(), "a hero sees Dances with Balrog");
        assertEquals(3524, TempleRoute.subQuestFor(222).questId());
        assertEquals(3525, TempleRoute.subQuestFor(312).questId());
        assertEquals(3526, TempleRoute.subQuestFor(412).questId());
        assertEquals(3527, TempleRoute.subQuestFor(512).questId());
        assertEquals(3529, TempleRoute.subQuestFor(1311).questId());
        assertEquals(3539, TempleRoute.subQuestFor(2112).questId());
    }

    @Test
    void eachCorridorMapNeedsTheLastExitBeforeIt() {
        assertEquals(0, TempleRoute.questToEnter(TempleRoute.HUB));
        assertEquals(0, TempleRoute.questToEnter(270010000));
        assertEquals(0, TempleRoute.questToEnter(270010100), "Memory Lane 1 is open");
        assertEquals(3501, TempleRoute.questToEnter(270010110));
        assertEquals(3501, TempleRoute.questToEnter(TempleRoute.MEMORY_MAP));
        assertEquals(3501, TempleRoute.questToEnter(270010200));
        assertEquals(3504, TempleRoute.questToEnter(270010500));
        assertEquals(3507, TempleRoute.questToEnter(270020000));
        assertEquals(3509, TempleRoute.questToEnter(TempleRoute.SORCERER_MAP));
        assertEquals(3514, TempleRoute.questToEnter(270030100));
        assertEquals(3518, TempleRoute.questToEnter(TempleRoute.RECORD_MAP));
        assertEquals(0, TempleRoute.questToEnter(100000000));
        // every lane a quest hunts on is open once the quests before it are done
        for (int i = 0; i < TempleRoute.MAINLINE.size(); i++) {
            TempleRoute.Step s = TempleRoute.MAINLINE.get(i);
            for (int map : s.lanes()) {
                int need = TempleRoute.questToEnter(map);
                assertTrue(need == 0 || TempleRoute.indexOf(need) < i, s.questId() + " hunts on " + map
                        + " which needs " + need);
            }
            int startNeed = TempleRoute.questToEnter(s.startMap()), endNeed = TempleRoute.questToEnter(s.endMap());
            assertTrue(startNeed == 0 || TempleRoute.indexOf(startNeed) < i, s.questId() + " start map");
            assertTrue(endNeed == 0 || TempleRoute.indexOf(endNeed) < i, s.questId() + " end map");
        }
    }

    // ── against the WZ data (skipped without wz/) ──

    @Test
    void questsAreQuestWzs() throws Exception {
        WzTestData.assumePresent("Quest.wz");
        var check = xml(WzTestData.dir().resolve("Quest.wz/Check.img.xml"));
        for (TempleRoute.Step s : TempleRoute.MAINLINE) {
            Map<String, Map<String, Object>> q = check.get(String.valueOf(s.questId()));
            assertNotNull(q, "quest " + s.questId());
            assertEquals(String.valueOf(s.startNpc()), q.get("0").get("npc"), s.questId() + " start npc");
            assertEquals(String.valueOf(s.endNpc()), q.get("1").get("npc"), s.questId() + " end npc");
            assertEquals(String.valueOf(s.lvmin()), q.get("0").get("lvmin"), s.questId() + " lvmin");
            assertEquals(s.startScript(), q.get("0").containsKey("startscript"), s.questId() + " start script");
            assertEquals(s.endScript(), q.get("1").containsKey("endscript"), s.questId() + " end script");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> mobs = (List<Map<String, String>>) q.get("1").getOrDefault("mob", List.of());
            Map<Integer, Integer> want = new HashMap<>();
            for (Map<String, String> m : mobs) {
                want.put(Integer.parseInt(m.get("id")), Integer.parseInt(m.get("count")));
            }
            assertEquals(want, s.mobs(), s.questId() + " kills");
            if (s.questId() > 3500) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> pre = (List<Map<String, String>>) q.get("0").get("quest");
                assertEquals(String.valueOf(s.questId() - 1), pre.get(0).get("id"), s.questId() + " needs the one before");
            }
        }
        for (TempleRoute.SubQuest sub : TempleRoute.SUB_QUESTS) {
            Map<String, Map<String, Object>> q = check.get(String.valueOf(sub.questId()));
            assertEquals(String.valueOf(sub.npc()), q.get("0").get("npc"), sub.questId() + " npc");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> pre = (List<Map<String, String>>) q.get("0").get("quest");
            assertEquals("3507", pre.get(0).get("id"));
            assertEquals("1", pre.get(0).get("state"), "the sub-quest needs 3507 under way");
        }
    }

    @Test
    void npcsStandOnTheirMapsAndLanesSpawnTheirMonsters() throws Exception {
        WzTestData.assumePresent("Map.wz");
        for (TempleRoute.Step s : TempleRoute.MAINLINE) {
            assertTrue(life(s.startMap(), "n").contains(s.startNpc()), s.questId() + ": npc on " + s.startMap());
            assertTrue(life(s.endMap(), "n").contains(s.endNpc()), s.questId() + ": npc on " + s.endMap());
            for (int lane : s.lanes()) {
                assertTrue(life(lane, "m").containsAll(s.mobs().keySet()), s.questId() + ": monsters on " + lane);
            }
        }
        for (TempleRoute.SubQuest sub : TempleRoute.SUB_QUESTS) {
            assertTrue(life(sub.map(), "n").contains(sub.npc()), sub.questId() + ": npc " + sub.npc() + " on " + sub.map());
        }
        for (TempleRoute.LaneExit e : TempleRoute.EXITS) {
            assertTrue(Files.exists(mapFile(e.toMap())), "map " + e.toMap());
        }
    }

    // ── tiny WZ XML readers ──

    private static String read(String p) {
        try {
            return Files.readString(Path.of(p));
        } catch (Exception e) {
            throw new AssertionError(p, e);
        }
    }

    private static Path mapFile(int mapId) {
        return WzTestData.dir().resolve("Map.wz/Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId)
                + ".img.xml");
    }

    private static List<Integer> life(int mapId, String type) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mapFile(mapId).toFile());
        List<Integer> out = new ArrayList<>();
        var root = doc.getDocumentElement();
        for (var section : children(root)) {
            if (!"life".equals(section.getAttribute("name"))) {
                continue;
            }
            for (var entry : children(section)) {
                String t = null, id = null;
                for (var f : children(entry)) {
                    if ("type".equals(f.getAttribute("name"))) {
                        t = f.getAttribute("value");
                    } else if ("id".equals(f.getAttribute("name"))) {
                        id = f.getAttribute("value");
                    }
                }
                if (type.equals(t) && id != null) {
                    out.add(Integer.parseInt(id));
                }
            }
        }
        return out;
    }

    private static List<org.w3c.dom.Element> children(org.w3c.dom.Element e) {
        List<org.w3c.dom.Element> out = new ArrayList<>();
        var nodes = e.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof org.w3c.dom.Element c) {
                out.add(c);
            }
        }
        return out;
    }

    /** Check.img as quest -> phase ("0"/"1") -> key -> value, or a list of maps for list nodes (mob, quest). */
    private static Map<String, Map<String, Map<String, Object>>> xml(Path p) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(p.toFile());
        Map<String, Map<String, Map<String, Object>>> out = new HashMap<>();
        for (var quest : children(doc.getDocumentElement())) {
            Map<String, Map<String, Object>> phases = new HashMap<>();
            for (var phase : children(quest)) {
                Map<String, Object> keys = new HashMap<>();
                for (var k : children(phase)) {
                    if (k.hasAttribute("value")) {
                        keys.put(k.getAttribute("name"), k.getAttribute("value"));
                    } else {
                        List<Map<String, String>> list = new ArrayList<>();
                        for (var item : children(k)) {
                            Map<String, String> m = new HashMap<>();
                            for (var f : children(item)) {
                                m.put(f.getAttribute("name"), f.getAttribute("value"));
                            }
                            list.add(m);
                        }
                        keys.put(k.getAttribute("name"), list);
                    }
                }
                phases.put(phase.getAttribute("name"), keys);
            }
            out.put(quest.getAttribute("name"), phases);
        }
        return out;
    }
}
