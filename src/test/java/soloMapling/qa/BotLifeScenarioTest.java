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
}
