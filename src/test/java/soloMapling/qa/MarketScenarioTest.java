package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.Economy.market.PublicMarketExport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketScenarioTest {
    @TempDir Path directory;
    @TempDir Path market;

    private String run(PublicMarketExport.Report report) throws Exception {
        var old = MarketScenarios.ForceExport.exporter;
        MarketScenarios.ForceExport.exporter = () -> CompletableFuture.completedFuture(report);
        try {
            QaWorld world = mock(QaWorld.class);
            Character gm = QaTestSupport.gm(1, "Admin", 100000000);
            QaSuite suite = QaTestSupport.suite(MarketScenarios.all(), world, directory,
                    new QaTestSupport.ManualScheduler());
            var answer = suite.start(gm, "mk-1", new String[0]);
            assertTrue(answer.getFirst().startsWith("QA mk-1 running"), answer.toString());
            String log = Files.readString(suite.runOf(1).logPath());
            verifyNoInteractions(world);
            suite.stop(1, "done");
            return log;
        } finally {
            MarketScenarios.ForceExport.exporter = old;
        }
    }

    private void write(String name, String json) throws Exception {
        Path p = market.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, json);
    }

    @Test
    void mk1PassesWhenEveryFileIsThereAndValid() throws Exception {
        write("listings.json", "{\"shops\":2,\"botShops\":1,\"playerShops\":1,\"count\":3,\"listings\":[]}");
        write("sales.json", "{\"count\":0,\"sales\":[]}");
        write("nx-rate.json", "{\"days\":[],\"today\":{\"mesos_per_1000nx\":null}}");
        write("economy-public.json", "{}");
        write("prices/index.json", "{\"dates\":[]}");
        write("prices/" + LocalDate.now(ZoneOffset.UTC).minusDays(1) + ".json", "{\"itemCount\":0}");
        String log = run(new PublicMarketExport.Report(market, List.of(), List.of()));
        assertTrue(log.contains("MK1 FILE listings.json ok "), log);
        assertTrue(log.matches("(?s).*MK1 FILE listings\\.json ok \\d+ bytes listings=\\d+ .*"), log);
        assertTrue(log.contains("MK1 FILE nx-rate.json ok "), log);
        assertTrue(log.contains("MK1 PASS files=6"), log);
        assertFalse(log.contains("MK1 FAIL"), log);
    }

    @Test
    void mk1FailsOnAMissingOrBrokenFileOrAFailedStep() throws Exception {
        write("listings.json", "{not json");
        String log = run(new PublicMarketExport.Report(market, List.of(), List.of("sales.json: db down")));
        assertTrue(log.contains("MK1 FILE listings.json MISSING"), log);
        assertTrue(log.contains("MK1 STEP FAIL sales.json: db down"), log);
        assertTrue(log.contains("MK1 FAIL missing=["), log);
        assertFalse(log.contains("MK1 PASS"), log);
    }
}
