package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.Economy.board.FakeBoardWorld;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BoardScenarioTest {
    @TempDir Path directory;

    @Test
    void bd1StagesAPostSeesABotOfferAndTheAlertFireOnce() throws Exception {
        FakeBoardWorld fake = new FakeBoardWorld().account(7, "Admin").item(2040804, "Gloves for ATT 60%");
        fake.bag(1_000_000_001, "Shroomy", 2040804, 1, 2_800_000);
        fake.listings.put(2040804, 2_400_000L);
        fake.goOnline(7, "Admin");
        var old = BoardScenarios.worldFor;
        BoardScenarios.worldFor = gm -> fake;
        try {
            QaWorld world = mock(QaWorld.class);
            when(world.spawnIdleBots(any(), any(), eq(1))).thenReturn(List.of(1_000_000_001));
            when(world.giveBotItem(anyInt(), anyInt(), anyInt())).thenReturn(1);
            Character gm = QaTestSupport.gm(1, "Admin", 910000000);
            when(gm.getAccountID()).thenReturn(7);
            QaSuite suite = QaTestSupport.suite(BoardScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
            var answer = suite.start(gm, "bd-1", new String[0]);
            assertTrue(answer.getFirst().startsWith("QA bd-1 running"), answer.toString());
            Path logPath = suite.runOf(1).logPath();
            String log = "";
            for (int i = 0; i < 100 && !log.matches("(?s).*BD1 (PASS|FAIL).*"); i++) {
                Thread.sleep(100);
                log = Files.readString(logPath);
            }
            assertTrue(log.contains("BD1 PASS "), log);
            assertTrue(log.matches("(?s).*BD1 ALERT FIRED a[0-9a-f]{12} .*"), log);
            assertTrue(log.contains("BD1 OFFER Shroomy -> Admin whisper: "), log);
            assertEquals(1, fake.yellows.size(), "the alert fired once");
            suite.stop(1, "done");
        } finally {
            BoardScenarios.worldFor = old;
        }
    }
}
