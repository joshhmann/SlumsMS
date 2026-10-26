package soloMapling.site;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The exporter's runs over a fake server: every file written, mode 644, a failure stopping only its own file. */
class PublicSiteExportTest {

    static final long NOW = 1_793_000_000_000L;

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(NOW);

    static final class Fake implements PublicSiteExport.Sources {
        boolean dbDown;

        public SiteWorld.Online online() {
            return new SiteWorld.Online(Map.of(1, 5, 2, 3), Map.of(100000000, 6, 910000000, 2),
                    Map.of(100000000, "Henesys", 910000000, "Free Market Entrance"));
        }

        public SiteWorld.Headline headline(long now) {
            throw new IllegalStateException("events not up");
        }

        public List<SiteWorld.Running> running() {
            return List.of();
        }

        public PublicSiteExport.Boards boards() {
            if (dbDown) {
                throw new IllegalStateException("db down");
            }
            return new PublicSiteExport.Boards(List.of(new SiteLeaderboards.Person(1_000_000_101, "Bobbins", 120, 0,
                    111, 0, 12, 5_000_000, 1)), List.of(new SiteLeaderboards.GuildRow(1, "Legendz", 2290, 50,
                    1_000_000_101)), Map.of(1, 1), Set.of(), List.of());
        }
    }

    @BeforeEach
    void setUp() {
        SiteFeed.resetForTest(now::get);
    }

    @AfterEach
    void tearDown() {
        SiteFeed.resetForTest(null);
    }

    @Test
    void everyFileIsWrittenReadableAndShaped() throws Exception {
        SiteFeed.eventStarted("Giant Cake", "Henesys", 100000000, 1);
        PublicSiteExport.Runner r = new PublicSiteExport.Runner(dir, new Fake(), now::get);
        PublicSiteExport.Report report = r.all();
        assertEquals(List.of(), report.failed());
        assertEquals(List.of("feed.json", "world.json", "leaderboards.json"), report.written());
        for (String name : PublicSiteExport.FILES) {
            Path p = dir.resolve(name);
            assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(p)), name);
            Object doc = Json.parse(Files.readString(p));
            assertEquals(List.of(), SiteFileShape.shape(name, doc), name);
        }
        Map<?, ?> world = (Map<?, ?>) Json.parse(Files.readString(dir.resolve("world.json")));
        assertNull(world.get("headline"), "a broken event system leaves the headline out, not the file");
        assertEquals(8L, ((Number) world.get("online")).longValue());
        Map<?, ?> feed = (Map<?, ?>) Json.parse(Files.readString(dir.resolve("feed.json")));
        assertEquals(1L, ((Number) feed.get("count")).longValue());
        try (var files = Files.list(dir)) {
            assertEquals(3, files.count(), "no temp files left behind");
        }
    }

    @Test
    void aBrokenDatabaseOnlyCostsTheLeaderboards() {
        Fake fake = new Fake();
        fake.dbDown = true;
        PublicSiteExport.Report report = new PublicSiteExport.Runner(dir, fake, now::get).all();
        assertEquals(List.of("feed.json", "world.json"), report.written());
        assertEquals(1, report.failed().size());
        assertTrue(report.failed().getFirst().startsWith("leaderboards.json: "), report.failed().toString());
        assertFalse(Files.exists(dir.resolve("leaderboards.json")));
    }

    @Test
    void theScheduledRunsNeverThrow() {
        Fake fake = new Fake();
        fake.dbDown = true;
        PublicSiteExport.Runner r = new PublicSiteExport.Runner(dir.resolve("x"), fake, now::get);
        assertDoesNotThrow(r::boardsSafely);
        assertDoesNotThrow(r::feedSafely);
        assertDoesNotThrow(r::worldSafely);
        assertDoesNotThrow(r::seedSafely);
    }

    @Test
    void offByDefault() {
        assertFalse(PublicSiteExport.enabled());
        PublicSiteExport.startIfEnabled(); // does nothing
        assertTrue(PublicSiteExport.dir().toString().endsWith("data/site"));
    }
}
