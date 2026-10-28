package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 1.6.1 ticket 113: xc-1 and xc-2 (the LEAF ticker) are registered and refuses (changing nothing) when the exchange is off. */
class ExchangeScenarioTest {
    @TempDir
    Path directory;

    @Test
    void xc1RefusesWhileTheExchangeIsOff() {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = QaTestSupport.suite(ExchangeScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        QaRefusal r = assertThrows(QaRefusal.class, () -> suite.start(gm, "xc-1", new String[0]));
        assertTrue(r.getMessage().contains("NX exchange is off"), r.getMessage());
        assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("xc-1")), "registered in !qa");
    }

    @Test
    void xc2TheLeafTickerRefusesWhileTheExchangeIsOff() {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = QaTestSupport.suite(ExchangeScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        QaRefusal r = assertThrows(QaRefusal.class, () -> suite.start(gm, "xc-2", new String[0]));
        assertTrue(r.getMessage().contains("exchange is off"), r.getMessage());
        assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("xc-2")), "registered in !qa");
    }

    @Test
    void xc3MarketVolatilityRefusesWhileItsFlagsAreOff() {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = QaTestSupport.suite(ExchangeScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        QaRefusal r = assertThrows(QaRefusal.class, () -> suite.start(gm, "xc-3", new String[0]));
        assertTrue(r.getMessage().contains("off"), r.getMessage());
        assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("xc-3")), "registered in !qa");
    }
}
