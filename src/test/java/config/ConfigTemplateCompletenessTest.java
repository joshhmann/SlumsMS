package config;

import com.esotericsoftware.yamlbeans.YamlReader;
import constants.game.GameConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * config.example.yaml is the file README.md:10 tells a new operator to copy, and it has to list EVERY
 * ServerConfig and WorldConfig field.
 *
 * <p>YamlConfig.java:19-29 reads config.yaml with a plain YamlReader and no strictness, so a key that is
 * absent does not fall back to a documented default: the field keeps the JVM default, which is 0 or false
 * for every field without an inline initialiser. The repo has had four incidents from that (see the
 * non-zero test below), and the previous template listed seven keys and no world at all.
 */
class ConfigTemplateCompletenessTest {

    private static final Path TEMPLATE = Path.of("config.example.yaml");

    /** The fields whose JVM default is 0 and whose 0 was measured to break a read-site. */
    private static final List<String> ZERO_BREAKS_THE_READ_SITE = List.of(
            "ITEM_EXPIRE_TIME",                 // every drop expires on the next sweep (MapleMap.java:1057)
            "KITE_EXPIRE_TIME",                 // a cash-shop kite expires immediately
            "ITEM_MONITOR_TIME",                // monitor runs every tick instead of every 5 minutes
            "LOCK_MONITOR_TIME",                // the lock monitor times out at once
            "ITEM_EXPIRE_CHECK",                // TimerManager.register throws on a 0 period inside addPlayer (MapleMap.java:995)
            "ITEM_LIMIT_ON_MAP",                // every new drop evicts the oldest (MapleMap.java:1025)
            "MAP_VISITED_SIZE",                 // IndexOutOfBoundsException at Character.java:2112-2113
            "MAP_DAMAGE_OVERTIME_INTERVAL",     // environment damage on a 0 ms interval
            "MAP_DAMAGE_OVERTIME_COUNT",        // no environment damage at all
            "MOB_STATUS_MONITOR_PROC",          // the mob disease monitor never proc
            "MOB_STATUS_MONITOR_LIFE",          // the monitor closes the moment it opens
            "MOB_STATUS_AGGRO_PERSISTENCE",     // aggro is dropped on the first update
            "MOB_STATUS_AGGRO_INTERVAL");       // divide by zero at MonsterAggroCoordinator.java:98 and :105

    private static YamlConfig template;

    @BeforeAll
    static void loadTheTemplate() throws IOException {
        assertTrue(Files.isRegularFile(TEMPLATE),
                "config.example.yaml is missing from the repository root (" + TEMPLATE.toAbsolutePath() + ")");
        try (YamlReader reader = new YamlReader(Files.newBufferedReader(TEMPLATE))) {
            template = reader.read(YamlConfig.class);
        }
        assertNotNull(template, "config.example.yaml parsed to null");
    }

    @Test
    void everyServerFieldAppearsInTheTemplate() throws IOException {
        Set<String> listed = new LinkedHashSet<>(serverSectionKeys());
        Set<String> declared = declaredFieldNames(ServerConfig.class);

        assertEquals(sorted(declared), sorted(listed), mismatch(
                "server:", listed, declared, ServerConfig.class));
    }

    @Test
    void everyWorldFieldAppearsInTheTemplate() throws IOException {
        List<Map<String, Object>> worlds = worldSections();
        assertTrue(worlds.size() == 1,
                "config.example.yaml should ship exactly one world entry, found " + worlds.size());

        Set<String> listed = new LinkedHashSet<>(worlds.get(0).keySet());
        Set<String> declared = declaredFieldNames(WorldConfig.class);

        assertEquals(sorted(declared), sorted(listed), mismatch(
                "worlds[0]:", listed, declared, WorldConfig.class));
    }

    @Test
    void theTemplateBootsThroughWorldValidation() {
        assertDoesNotThrow(() -> WorldConfigs.validate(template.worlds, GameConstants.WORLD_NAMES.length),
                "the shipped config.example.yaml would fail WorldConfigs.validate at Server.java:957");
        assertTrue(template.server.WORLDS > 0,
                "server WORLDS is " + template.server.WORLDS + "; Server.java:975 starts "
                        + "min(worlds, WORLDS) worlds, so the boot would start none");
        assertTrue(template.server.WORLDS <= template.worlds.size(),
                "server WORLDS is " + template.server.WORLDS + " but the template lists "
                        + template.worlds.size() + " world(s); the server warns and starts fewer");
    }

    /**
     * The regression itself: each field that a 0 breaks must be non-zero in the shipped template.
     * This is the test that would have caught the four incidents this repo already had.
     */
    @Test
    void noFieldThatABreakingZeroReachesIsZeroInTheTemplate() throws IllegalAccessException {
        List<String> zero = new ArrayList<>();
        for (String name : ZERO_BREAKS_THE_READ_SITE) {
            Field field = field(ServerConfig.class, name);
            Object value = field.get(template.server);
            assertTrue(value instanceof Integer || value instanceof Long,
                    name + " is " + field.getType().getSimpleName() + ", not an integral field");
            if (((Number) value).longValue() == 0) {
                zero.add(name);
            }
        }
        if (!zero.isEmpty()) {
            fail("config.example.yaml leaves these ServerConfig fields at 0, which breaks their read-site:\n  "
                    + String.join("\n  ", zero));
        }
    }

    // --- yaml key helpers -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readTemplate() throws IOException {
        try (YamlReader reader = new YamlReader(Files.newBufferedReader(TEMPLATE))) {
            Object root = reader.read();
            assertTrue(root instanceof Map, "config.example.yaml did not parse to a map");
            return (Map<String, Object>) root;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> serverSectionKeys() throws IOException {
        Object server = readTemplate().get("server");
        if (!(server instanceof Map)) {
            fail("config.example.yaml has no 'server:' map");
        }
        return ((Map<String, Object>) server).keySet();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> worldSections() throws IOException {
        Object worlds = readTemplate().get("worlds");
        if (!(worlds instanceof List)) {
            fail("config.example.yaml has no 'worlds:' list");
        }
        return (List<Map<String, Object>>) worlds;
    }

    private static Set<String> declaredFieldNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(type.getSimpleName() + " no longer declares " + name
                    + "; update ZERO_BREAKS_THE_READ_SITE", e);
        }
    }

    private static List<String> sorted(Set<String> names) {
        return new ArrayList<>(new TreeSet<>(names));
    }

    /** Names both sides of the mismatch; that message is the value of the test. */
    private static String mismatch(String section, Set<String> listed, Set<String> declared, Class<?> type) {
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(listed);
        Set<String> extra = new TreeSet<>(listed);
        extra.removeAll(declared);
        if (missing.isEmpty() && extra.isEmpty()) {
            return section + " matches " + type.getSimpleName();
        }
        StringBuilder message = new StringBuilder("config.example.yaml ").append(section)
                .append(" does not match the fields of ").append(type.getName()).append(":");
        if (!missing.isEmpty()) {
            message.append("\n  missing from the template (they would silently be 0/false): ")
                    .append(String.join(", ", missing));
        }
        if (!extra.isEmpty()) {
            message.append("\n  not a ").append(type.getSimpleName()).append(" field (typo, or a removed field): ")
                    .append(String.join(", ", extra));
        }
        return message.toString();
    }
}
