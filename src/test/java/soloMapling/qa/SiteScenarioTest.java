package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.site.PublicSiteExport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SiteScenarioTest {
    @TempDir Path directory;
    @TempDir Path site;

    private String run(PublicSiteExport.Report report) throws Exception {
        var old = SiteScenarios.ForceExport.exporter;
        SiteScenarios.ForceExport.exporter = () -> CompletableFuture.completedFuture(report);
        try {
            QaWorld world = mock(QaWorld.class);
            Character gm = QaTestSupport.gm(1, "Admin", 100000000);
            QaSuite suite = QaTestSupport.suite(SiteScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
            var answer = suite.start(gm, "sf-1", new String[0]);
            assertTrue(answer.getFirst().startsWith("QA sf-1 running"), answer.toString());
            String log = Files.readString(suite.runOf(1).logPath());
            verifyNoInteractions(world);
            suite.stop(1, "done");
            return log;
        } finally {
            SiteScenarios.ForceExport.exporter = old;
        }
    }

    private void write(String name, String json) throws Exception {
        Files.writeString(site.resolve(name), json);
    }

    private void writeGood() throws Exception {
        String head = "{\"schema\":1,\"generated_at\":\"2026-10-26T07:15:00.120Z\",";
        write("feed.json", head + "\"count\":2,\"items\":[{\"id\":\"e1\",\"t\":\"2026-10-26T07:14:00Z\",\"kind\":\"event\","
                + "\"tag\":\"start\",\"text\":\"Giant Cake began in Henesys (Ch 1).\",\"names\":[],\"map\":100000000,\"channel\":1},"
                + "{\"id\":\"m1\",\"t\":\"2026-10-26T07:13:00.500Z\",\"kind\":\"milestone\",\"tag\":\"level\","
                + "\"text\":\"Bob reached level 70.\",\"names\":[\"Bob\"]}]}");
        String classes = "{\"Beginner\":[],\"Warrior\":[],\"Magician\":[],\"Bowman\":[],\"Thief\":[],\"Pirate\":[]}";
        write("leaderboards.json", head + "\"level\":[{\"rank\":1,\"name\":\"Bob\"}],\"levelByClass\":" + classes
                + ",\"fame\":[],\"guilds\":[],\"traders\":[],\"dojo\":[],"
                + "\"bosses\":{\"Zakum\":[{\"name\":\"Bob\",\"clears\":2}],\"Horntail\":[],\"Pink Bean\":[]}}");
        write("world.json", head + "\"online\":8,\"channels\":[{\"channel\":1,\"online\":8}],\"busiest_maps\":[],\"top_mobs\":[],\"top_mobs_7d\":[],"
                + "\"tonight\":null,\"running\":[]}");
    }

    @Test
    void sf1PassesWhenEveryFileIsThereAndShaped() throws Exception {
        writeGood();
        String log = run(new PublicSiteExport.Report(site, List.of(), List.of()));
        assertTrue(log.matches("(?s).*SF1 FILE feed\\.json ok \\d+ bytes items=2.*"), log);
        assertTrue(log.matches("(?s).*SF1 FILE leaderboards\\.json ok \\d+ bytes level=1 .*zakum=1.*"), log);
        assertTrue(log.matches("(?s).*SF1 FILE world\\.json ok \\d+ bytes online=8 .*tonight=none.*"), log);
        assertTrue(log.contains("SF1 PASS files=3"), log);
        assertFalse(log.contains("SF1 FAIL"), log);
    }

    @Test
    void sf1FailsOnAMissingFileABadShapeOrAFailedStep() throws Exception {
        writeGood();
        write("feed.json", "{\"schema\":1,\"generated_at\":\"2026-10-26T07:15:00Z\",\"items\":[{\"id\":\"x\","
                + "\"t\":\"2026-10-26T07:00:00Z\",\"kind\":\"drama\",\"text\":\"a\",\"names\":[],\"is_bot\":true}]}");
        Files.delete(site.resolve("world.json"));
        String log = run(new PublicSiteExport.Report(site, List.of(), List.of("leaderboards.json: db down")));
        assertTrue(log.contains("SF1 SHAPE feed.json: forbidden key items.is_bot"), log);
        assertTrue(log.contains("SF1 FILE world.json MISSING"), log);
        assertTrue(log.contains("SF1 STEP FAIL leaderboards.json: db down"), log);
        assertTrue(log.contains("SF1 FAIL bad=[feed.json, world.json]"), log);
        assertFalse(log.contains("SF1 PASS"), log);
    }
}
