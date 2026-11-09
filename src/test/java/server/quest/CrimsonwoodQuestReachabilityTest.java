package server.quest;

import org.junit.jupiter.api.Test;
import server.TimerManager;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.WzTestData;
import tools.DatabaseConnection;

import java.io.IOException;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Card {@code crimsonwood-pq-unreachable} (1.6.8): the keep PQ's door portal
 * ({@code scripts/portal/glpqEnter.js}) wants key 3992041, whose only source is quest 8223, whose check wants 75
 * kills each of 9400576, 9400578 and 9400579. The card called the chain circular because {@code Map.wz} places
 * 9400578 only on the PQ's own stage maps, which have no portal in.
 *
 * <p>That read misses the other spawn path: {@code Mob.wz/9400577.img.xml} carries a {@code revive} entry naming
 * 9400578, and {@code Monster.killBy} spawns every revive id where the mob died, so killing a Firebrand (9400577,
 * which does spawn on the open keep maps) leaves exactly the Risen Firebrand the quest counts. Quest counts are
 * raised for the dying mob's own id ({@code Monster.distributeExperience} ->
 * {@code Character.raiseQuestMobCount(getId())}), so the risen form counts as 9400578.
 *
 * <p>The tests below hold that chain in place: the quest's own numbers, every counted mob being killable somewhere
 * outside the PQ stages, the revive really firing for a real 9400577, and the door/Lukan gate staying as it is.
 */
class CrimsonwoodQuestReachabilityTest {

    static final int DOOR_QUEST = 8223;
    static final int KEY = 3992041;
    static final int LUKAN = 9201098;
    static final int WINDRAIDER = 9400576;
    static final int FIREBRAND = 9400577;
    static final int RISEN_FIREBRAND = 9400578;
    static final int NIGHTSHADOW = 9400579;

    private static final Pattern MOB_ROW =
            Pattern.compile("<string name=\"type\" value=\"m\"/>\\s*<string name=\"id\" value=\"(\\d+)\"/>");
    private static final Pattern REVIVE_BLOCK = Pattern.compile("(?s)<imgdir name=\"revive\">(.*?)</imgdir>");
    private static final Pattern REVIVE_ID = Pattern.compile("<int name=\"\\d+\" value=\"(\\d+)\"/>");

    // ---- the quest's own numbers -----------------------------------------------------------------------------

    @Test
    void questEightTwoTwoThreeCountsTheThreeKeepMobsAndItsActHandsOutTheDoorKey() throws IOException {
        WzTestData.assumePresent("Quest.wz");
        String check = block(quest("Check.img.xml"), "8223", "8224");
        String act = block(quest("Act.img.xml"), "8223", "8224");

        Map<Integer, Integer> counted = new TreeMap<>();
        Matcher counts = Pattern.compile("<int name=\"id\" value=\"(\\d+)\"/>\\s*<int name=\"count\" value=\"(\\d+)\"/>")
                .matcher(check);
        while (counts.find()) {
            counted.put(Integer.parseInt(counts.group(1)), Integer.parseInt(counts.group(2)));
        }
        assertEquals(Map.of(WINDRAIDER, 75, RISEN_FIREBRAND, 75, NIGHTSHADOW, 75), counted,
                "quest 8223 counts 75 of each of the three keep mobs");
        assertTrue(check.contains("<int name=\"npc\" value=\"" + LUKAN + "\"/>"), "Lukan starts and completes 8223");

        assertTrue(act.contains("<int name=\"id\" value=\"" + KEY + "\"/>"), "8223's act hands out the door key");
        assertTrue(act.contains("<int name=\"exp\" value=\"220000\"/>"), "8223 pays its 220,000 exp");
    }

    // ---- every counted mob is killable outside the PQ stages -------------------------------------------------

    @Test
    void everyMobQuest8223CountsCanBeKilledOnAnOpenKeepMap() throws IOException {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");
        int low = pqStageLow();
        int high = pqStageHigh();

        Map<Integer, Set<Integer>> openMaps = spawnMapsOutside(low, high);
        assertTrue(openMaps.containsKey(WINDRAIDER), "9400576 spawns on the open keep maps");
        assertTrue(openMaps.containsKey(NIGHTSHADOW), "9400579 spawns on the open keep maps");
        assertFalse(openMaps.containsKey(RISEN_FIREBRAND),
                "9400578 has no life row of its own outside the PQ stages - this is what made the card look circular");

        Map<Integer, List<Integer>> revives = revivesOf(openMaps.keySet());
        assertEquals(List.of(RISEN_FIREBRAND), revives.get(FIREBRAND),
                "the Firebrand that does spawn outside rises into the Risen Firebrand the quest counts");
        assertTrue(openMaps.containsKey(FIREBRAND), "9400577 spawns on the open keep maps: " + openMaps.get(FIREBRAND));

        Set<Integer> reachable = new LinkedHashSet<>(openMaps.keySet());
        for (int round = 0; round < 4; round++) {
            Set<Integer> grew = new TreeSet<>(reachable);
            for (int source : reachable) {
                grew.addAll(revives.getOrDefault(source, List.of()));
            }
            if (grew.size() == reachable.size()) {
                break;
            }
            reachable = grew;
        }

        for (int mob : List.of(WINDRAIDER, RISEN_FIREBRAND, NIGHTSHADOW)) {
            assertTrue(reachable.contains(mob), "quest 8223 counts " + mob + ", which is killable on an open map");
        }
    }

    // ---- the revive really fires for the real stats ----------------------------------------------------------

    @Test
    void killingAFirebrandRaisesTheRisenFirebrandTheQuestCounts() {
        WzTestData.assumePresent("Mob.wz");
        Monster firebrand;
        Monster risen;
        try (var db = mockStatic(DatabaseConnection.class)) {
            // LifeFactory's MonsterInformationProvider asks the database at class-init; a no-database test still loads
            // the WZ stats, which is all this test wants (same harness as server.life.MobStatOverridesTest).
            db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("no database in this test"));
            firebrand = LifeFactory.getMonster(FIREBRAND);
            risen = LifeFactory.getMonster(RISEN_FIREBRAND);
        }
        assertNotNull(firebrand, "Mob.wz must carry 9400577");
        assertNotNull(risen, "Mob.wz must carry 9400578");
        assertEquals(List.of(RISEN_FIREBRAND), firebrand.getStats().getRevives(),
                "9400577's Mob.wz revive entry names 9400578");
        assertEquals(90, risen.getLevel(), "the risen form is a level-90 mob");
        assertEquals(45_000L, risen.getMobMaxHp(), "the risen form's HP");

        TimerManager.getInstance().start();
        MapleMap map = mock(MapleMap.class);
        firebrand.setMap(map);
        firebrand.killBy(null); // the death schedules the revive on the server timer, as a real kill does

        verify(map, timeout(5_000)).spawnMonster(argThat(mob -> mob != null && mob.getId() == RISEN_FIREBRAND));
    }

    // ---- the door and Lukan are unchanged -------------------------------------------------------------------

    @Test
    void lukanHandsTheKeyOnlyAfterTheQuestAndTheDoorNeverTakesItAway() throws IOException {
        String lukan = Files.readString(Path.of("scripts/npc/9201098.js"), StandardCharsets.UTF_8);
        assertTrue(lukan.contains("getQuestStatus(" + DOOR_QUEST + ") == 2"), "Lukan gates the key on 8223 being done");
        assertTrue(lukan.contains("gainItem(" + KEY + ", 1)"), "Lukan hands out the key");
        assertTrue(lukan.contains("canHold(" + KEY + ")"), "Lukan refuses to give the key when the bag is full");

        String door = Files.readString(Path.of("scripts/portal/glpqEnter.js"), StandardCharsets.UTF_8);
        assertTrue(door.contains("haveItem(" + KEY + ", 1)"), "the door asks for the key");
        assertFalse(door.contains("gainItem(" + KEY) || door.contains("removeItem(" + KEY),
                "the door neither hands out nor consumes the key");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static String quest(String file) {
        return WzTestData.dir().resolve("Quest.wz").resolve(file).toString();
    }

    private static String block(String file, String name, String nextName) throws IOException {
        String xml = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        int from = xml.indexOf("<imgdir name=\"" + name + "\">");
        assertTrue(from >= 0, name + " must be in " + file);
        int to = xml.indexOf("<imgdir name=\"" + nextName + "\">", from);
        assertTrue(to > from, "the block for " + name + " must be closed by " + nextName);
        return xml.substring(from, to);
    }

    /** The PQ's own stage range, read from the event script that runs it. */
    private static int pqStageLow() throws IOException {
        int low = eventInt("minMapId");
        assertEquals(610030100, low, "CWKPQ's first stage map is the keep hallway");
        return low;
    }

    private static int pqStageHigh() throws IOException {
        int high = eventInt("maxMapId");
        assertEquals(610030800, high, "CWKPQ's last stage map is the armory");
        return high;
    }

    private static int eventInt(String key) throws IOException {
        String script = Files.readString(Path.of("scripts/event/CWKPQ.js"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("var\\s+" + key + "\\s*=\\s*(\\d+)\\s*;").matcher(script);
        assertTrue(m.find(), key + " must be declared in CWKPQ.js");
        return Integer.parseInt(m.group(1));
    }

    /** Map id -> mob ids placed there by Map.wz life, for every map outside the PQ's stage range. */
    private static Map<Integer, Set<Integer>> spawnMapsOutside(int low, int high) throws IOException {
        Map<Integer, Set<Integer>> out = new HashMap<>();
        Path root = WzTestData.dir().resolve("Map.wz").resolve("Map");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".img.xml")).forEach(files::add);
        }
        assertTrue(files.size() > 1_000, "the whole Map.wz/Map tree was walked: " + files.size());
        for (Path file : files) {
            String name = file.getFileName().toString();
            String stem = name.substring(0, name.indexOf(".img.xml"));
            if (!stem.chars().allMatch(Character::isDigit)) {
                continue; // Map.wz/Map also holds AreaCode.img.xml and friends
            }
            int mapId = Integer.parseInt(stem);
            if (mapId >= low && mapId <= high) {
                continue;
            }
            Matcher m = MOB_ROW.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                out.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new TreeSet<>()).add(mapId);
            }
        }
        return out;
    }

    /** For each mob that spawns outside the PQ stages, the mob ids its Mob.wz {@code revive} entry names. */
    private static Map<Integer, List<Integer>> revivesOf(Set<Integer> mobs) throws IOException {
        Map<Integer, List<Integer>> out = new HashMap<>();
        for (int mob : mobs) {
            Path file = WzTestData.dir().resolve("Mob.wz").resolve(mob + ".img.xml");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            Matcher block = REVIVE_BLOCK.matcher(xml);
            if (!block.find()) {
                continue;
            }
            List<Integer> ids = new ArrayList<>();
            Matcher id = REVIVE_ID.matcher(block.group(1));
            while (id.find()) {
                ids.add(Integer.parseInt(id.group(1)));
            }
            out.put(mob, ids);
        }
        return out;
    }
}
