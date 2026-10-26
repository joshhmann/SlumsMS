package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotLifeScenarioTest {
    @TempDir Path directory;

    @Test
    void runningScenarioLogsActualPolicyAndRefusalWithoutMutatingTheWorld() throws Exception {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        suite.start(gm, "bl-1", new String[0]);
        String log = Files.readString(suite.runOf(1).logPath());
        assertTrue(log.contains("LIFE SHADOW case=socialite selected=HANG_OUT"));
        assertTrue(log.contains("LIFE SHADOW case=empty-potions selected=RESTOCK"));
        assertTrue(log.contains("LIFE SHADOW case=helper selected=HELP_FRIEND"));
        assertTrue(log.contains("LIFE REFUSAL accepted=false actionFact=false"));
        verifyNoInteractions(world);
        suite.stop(1, "done");
    }

    @Test
    void bl4LogsTheRoundTripTheWorldRanAndRefusesWhereItCannot() throws Exception {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(world.lifeProfileProof(gm, "Fixture")).thenReturn(java.util.List.of(
                "LIFE PROFILE bot=Fixture slot=5001 SOCIALIZER-led: A 10% E 20% S 60% K 10% restored=true",
                "LIFE PROFILE PASS bot=1000005001 drift survived a social session and a reload"));
        QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        suite.start(gm, "bl-4", new String[]{"Fixture"});
        String log = Files.readString(suite.runOf(1).logPath());
        assertTrue(log.contains("LIFE PROFILE bot=Fixture slot=5001 SOCIALIZER-led"), log);
        assertTrue(log.contains("LIFE PROFILE PASS bot=1000005001"), log);
        assertFalse(log.contains("LIFE LIFE"), log);
        suite.stop(1, "done");

        Character other = QaTestSupport.gm(2, "Other", 100000000);
        when(world.lifeProfileProof(other, null)).thenReturn(java.util.List.of());
        assertThrows(QaRefusal.class, () -> suite.start(other, "bl-4", new String[0]));
        assertNull(suite.runOf(2), "refused: nothing to prove in this world");
    }
}
