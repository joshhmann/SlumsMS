package soloMapling.site;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The most-hunted-monsters counter behind world.json's top_mobs and top_mobs_7d. */
class SiteMobKillsTest {

    static final long HOUR = TimeUnit.HOURS.toMillis(1);
    final AtomicLong now = new AtomicLong(1_793_000_000_000L);

    @BeforeEach
    void setUp() {
        SiteMobKills.reset(now::get);
        SiteMobKills.setRecording(true);
    }

    @AfterEach
    void tearDown() {
        SiteMobKills.setRecording(false);
        SiteMobKills.reset(null);
    }

    @Test
    void offCountsNothing() {
        SiteMobKills.setRecording(false);
        SiteMobKills.kill(100100);
        assertEquals(List.of(), SiteMobKills.top(24, now.get(), id -> "x"));
    }

    @Test
    void topTenByKillsWithNamesAndTiesByLowerId() {
        for (int mob = 1; mob <= 12; mob++) {
            for (int i = 0; i < mob; i++) {
                SiteMobKills.kill(mob);
            }
        }
        SiteMobKills.kill(11); // 11 and 12 now tie on 12 kills? 11 has 12, 12 has 12
        List<SiteMobKills.Row> top = SiteMobKills.top(24, now.get(), id -> "Mob " + id);
        assertEquals(SiteMobKills.TOP, top.size());
        assertEquals(new SiteMobKills.Row(11, "Mob 11", 12), top.get(0));
        assertEquals(12, top.get(1).mob());
        assertEquals(10, top.get(2).mob());
    }

    @Test
    void theDayWindowForgetsOldHoursButTheWeekKeepsThem() {
        SiteMobKills.kill(7);
        now.addAndGet(30 * HOUR);
        SiteMobKills.kill(8);
        SiteMobKills.kill(8);
        assertEquals(List.of(8), SiteMobKills.top(24, now.get(), id -> "").stream().map(SiteMobKills.Row::mob).toList());
        assertEquals(List.of(8, 7), SiteMobKills.top(SiteMobKills.HOURS, now.get(), id -> "").stream().map(SiteMobKills.Row::mob).toList());
        now.addAndGet(8 * 24 * HOUR);
        assertEquals(List.of(), SiteMobKills.top(SiteMobKills.HOURS, now.get(), id -> ""), "older than 7 days is gone");
    }

    @Test
    void aReusedSlotStartsEmpty() {
        SiteMobKills.kill(5);
        now.addAndGet(SiteMobKills.HOURS * HOUR); // the same ring slot, a week later
        SiteMobKills.kill(6);
        assertEquals(List.of(6), SiteMobKills.top(SiteMobKills.HOURS, now.get(), id -> "").stream().map(SiteMobKills.Row::mob).toList());
    }

    @Test
    void worldJsonCarriesBothListsAndStaysShaped() {
        for (int i = 0; i < 3; i++) {
            SiteMobKills.kill(100100);
        }
        List<SiteMobKills.Row> day = SiteMobKills.top(24, now.get(), id -> "Snail");
        Map<String, Object> file = SiteWorld.file(new SiteWorld.Online(Map.of(), Map.of(), Map.of()), null, List.of(),
                now.get(), day, day);
        Object doc = Json.parse(Json.write(file));
        assertEquals(List.of(), SiteFileShape.shape("world.json", doc));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) ((Map<?, ?>) doc).get("top_mobs")).getFirst();
        assertEquals(List.of("mob", "name", "kills"), List.copyOf(row.keySet()));
        assertEquals(3L, ((Number) row.get("kills")).longValue());
        assertEquals("Snail", row.get("name"));
        assertNotNull(((Map<?, ?>) doc).get("top_mobs_7d"));
    }
}
