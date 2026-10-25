package server.life;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * LifeFactory.monsterStats and the MonsterInformationProvider caches are filled from map spawns, channel threads and
 * bot threads at once, so they must be ConcurrentHashMaps (plain HashMaps lose entries or corrupt under that load).
 * Both classes need WZ data or a DB pool to initialise, so this inspects the compiled class: neither may reference
 * java.util.HashMap any more, and both must reference ConcurrentHashMap.
 * Ported from nutnnut/Cosmic 3c094c453 (nutnnut), server/life.
 */
class MobCacheConcurrencyTest {

    private static String classBytes(Class<?> cls) throws IOException {
        try (InputStream in = cls.getResourceAsStream(cls.getSimpleName() + ".class")) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static void assertNoPlainHashMap(Class<?> cls) throws IOException {
        String bytes = classBytes(cls);
        assertFalse(bytes.contains("java/util/HashMap"), cls.getSimpleName() + " still builds a plain HashMap");
        assertTrue(bytes.contains("java/util/concurrent/ConcurrentHashMap"), cls.getSimpleName());
    }

    @Test
    void lifeFactoryMonsterStatsIsConcurrent() throws IOException {
        assertNoPlainHashMap(LifeFactory.class);
    }

    @Test
    void providerCachesAreConcurrent() throws IOException {
        assertNoPlainHashMap(MonsterInformationProvider.class);
    }
}
