package soloMapling.qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingScenarioTest {
    @TempDir Path directory;
    @Test void actualTimerPolicyPassesWithoutGameInventoryOrWorldChanges() throws Exception {
        var world = mock(QaWorld.class);
        var gm = QaTestSupport.gm(1, "Admin", 100000000);
        var suite = QaTestSupport.suite(FishingScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
        suite.start(gm, "fh-1", new String[0]);
        assertTrue(Files.readString(suite.runOf(1).logPath()).contains("FISHING POLICY PASS grants=1 duplicate=blocked movement=cancelled"));
        verifyNoInteractions(world);
        suite.stop(1, "done");
    }
}
