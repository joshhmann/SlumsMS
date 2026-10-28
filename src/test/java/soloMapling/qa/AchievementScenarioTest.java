package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 1.6.1 ticket 93: ac-1 is registered and refuses (changing nothing) while achievements are off. */
class AchievementScenarioTest {
    @TempDir
    Path directory;

    @Test
    void ac1RefusesWhileAchievementsAreOff() {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = QaTestSupport.suite(AchievementScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        QaRefusal r = assertThrows(QaRefusal.class, () -> suite.start(gm, "ac-1", new String[0]));
        assertTrue(r.getMessage().contains("Achievements are off"), r.getMessage());
        assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("ac-1")), "registered in !qa");
    }
}
