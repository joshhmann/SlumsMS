package soloMapling.qa;

import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 1.6.1 ticket 30: rb-1 is registered and refuses (changing nothing) while bot rebirths are off. */
class RebirthScenarioTest {
    @TempDir
    Path directory;

    @Test
    void rb1RefusesWhileBotRebirthsAreOff() {
        boolean saved = YamlConfig.config.server.BOT_REBIRTH_ENABLED;
        YamlConfig.config.server.BOT_REBIRTH_ENABLED = false;
        try {
            QaWorld world = mock(QaWorld.class);
            Character gm = QaTestSupport.gm(1, "Admin", 100000000);
            QaSuite suite = QaTestSupport.suite(RebirthScenarios.all(), world, directory,
                    new QaTestSupport.ManualScheduler());
            QaRefusal r = assertThrows(QaRefusal.class, () -> suite.start(gm, "rb-1", new String[0]));
            assertTrue(r.getMessage().contains("bot rebirths are off"), r.getMessage());
            assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("rb-1")), "registered in !qa");
        } finally {
            YamlConfig.config.server.BOT_REBIRTH_ENABLED = saved;
        }
    }
}
