package soloMapling.achievements;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The shipped achievements.yaml and the strict reader. */
class GoalCatalogTest {

    static final Path FILE = Path.of("src/main/java/soloMapling/achievements/achievements.yaml");

    /** Every medal the yaml may use: checked present in the server's Character.wz and the client's Data on 2026-10-28. */
    static final Set<Integer> VERIFIED_MEDALS = Set.of(1142107, 1142108, 1142109, 1142110, 1142000, 1142004, 1142005,
            1142079, 1142033, 1142034, 1142040, 1142052, 1142063, 1142083, 1142007, 1142196, 1142146, 1142036,
            1142041, 1142044, 1142047, 1142100, 1142002, 1142081, 1142091, 1142084, 1142112, 1142119, 1142115,
            1142160, 1142161, 1142111);

    static GoalCatalog shipped() throws Exception {
        return GoalCatalog.read(FILE, id -> true);
    }

    @Test
    void theShippedFileHasAboutThirtyGoalsWithDistinctVerifiedMedals() throws Exception {
        GoalCatalog c = shipped();
        assertTrue(c.goals().size() >= 30 && c.goals().size() <= 34, "goals: " + c.goals().size());
        Set<Integer> medals = new HashSet<>();
        for (Goal g : c.goals()) {
            assertTrue(medals.add(g.medal()), "medal reused: " + g.medal());
            assertTrue(VERIFIED_MEDALS.contains(g.medal()), g.id() + " uses an unverified medal " + g.medal());
        }
        assertEquals(VERIFIED_MEDALS, medals, "every verified medal is awarded by one goal");
    }

    @Test
    void everyTriggerTheTicketNamesHasAGoal() throws Exception {
        GoalCatalog c = shipped();
        for (Trigger t : Trigger.values()) {
            assertFalse(c.forTrigger(t).isEmpty(), "no goal for " + t);
        }
        assertNotNull(c.get("clear-zakum"));
        assertEquals(100, c.get("fish-100").needed());
        assertEquals(28, c.get("checkin-28").needed());
        assertEquals(31, c.get("visit-victoria").needed(), "five maps -> mask 11111");
        assertEquals(List.of("zakumbattle"), c.get("clear-zakum").scripts());
    }

    static Map<String, Object> goal(String id, int medal, String trigger) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("title", "T " + id);
        m.put("text", "Do " + id);
        m.put("medal", String.valueOf(medal));
        m.put("trigger", trigger);
        return m;
    }

    @SafeVarargs
    static Object doc(Map<String, Object>... goals) {
        return Map.of("goals", List.of(goals));
    }

    private static void rejects(String why, Object doc) {
        assertThrows(IllegalArgumentException.class, () -> GoalCatalog.parse(doc, id -> true), why);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReaderIsStrict() {
        assertEquals(1, GoalCatalog.parse(doc(goal("a", 1142000, "fish")), id -> true).goals().size());
        Map<String, Object> unknownKey = goal("a", 1142000, "fish");
        unknownKey.put("reward", "mesos");
        rejects("unknown key", doc(unknownKey));
        rejects("duplicate id", doc(goal("a", 1142000, "fish"), goal("a", 1142001, "fish")));
        rejects("duplicate medal", doc(goal("a", 1142000, "fish"), goal("b", 1142000, "fish")));
        rejects("not a medal", doc(goal("a", 1002000, "fish")));
        rejects("unknown trigger", doc(goal("a", 1142000, "dance")));
        rejects("mob_kill needs mobs", doc(goal("a", 1142000, "mob_kill")));
        Map<String, Object> stray = goal("a", 1142000, "fish");
        stray.put("mobs", List.of("2220000"));
        rejects("mobs on a fish goal", doc(stray));
        Map<String, Object> badTarget = goal("a", 1142000, "fish");
        badTarget.put("target", "0");
        rejects("target 0", doc(badTarget));
        rejects("no goals", Map.of("goals", List.of()));
        rejects("extra top-level key", Map.of("goals", List.of(goal("a", 1142000, "fish")), "x", "y"));
        assertThrows(IllegalArgumentException.class, () -> GoalCatalog.parse(doc(goal("a", 1142000, "fish")), id -> false),
                "an item the server does not know");
    }

    @Test
    void theFileIsInTheKnownDataFiles() throws Exception {
        assertTrue(Files.readString(Path.of("src/main/java/soloMapling/DataFiles.java")).contains(GoalCatalog.DATA_PATH));
    }
}
