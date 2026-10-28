package soloMapling.qa;

import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 1.6.1 ticket 53: fy-1 is registered and refuses (changing nothing) while the Family system is off. */
class FamilyScenarioTest {
    @TempDir
    Path directory;

    @Test
    void fy1RefusesWhileFamilyIsOff() {
        boolean saved = YamlConfig.config.server.USE_FAMILY_SYSTEM;
        YamlConfig.config.server.USE_FAMILY_SYSTEM = false;
        try {
            QaWorld world = mock(QaWorld.class);
            Character gm = QaTestSupport.gm(1, "Admin", 100000000);
            QaSuite suite = QaTestSupport.suite(FamilyScenarios.all(), world, directory,
                    new QaTestSupport.ManualScheduler());
            QaRefusal r = assertThrows(QaRefusal.class, () -> suite.start(gm, "fy-1", new String[0]));
            assertTrue(r.getMessage().contains("Family system is off"), r.getMessage());
            assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("fy-1")), "registered in !qa");
        } finally {
            YamlConfig.config.server.USE_FAMILY_SYSTEM = saved;
        }
    }
}
