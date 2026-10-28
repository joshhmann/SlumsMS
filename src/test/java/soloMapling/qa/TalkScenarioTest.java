package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 1.6.1 ticket 114: bt-1 is registered and refuses (changing nothing) while bot talk is off. */
class TalkScenarioTest {
    @TempDir
    Path directory;

    @Test
    void bt1RefusesWhileBotTalkIsOff() {
        System.clearProperty("slums.bottalk");
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = QaTestSupport.suite(TalkScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
        QaRefusal r = assertThrows(QaRefusal.class, () -> suite.start(gm, "bt-1", new String[0]));
        assertTrue(r.getMessage().contains("-Dslums.bottalk=true"), r.getMessage());
        assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("bt-1")), "registered in !qa");
    }
}
