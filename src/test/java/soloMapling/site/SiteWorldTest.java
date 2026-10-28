package soloMapling.site;

import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** world.json: totals per channel, the busiest maps, the headline and running events. */
class SiteWorldTest {

    static final long NOW = 1_793_000_000_000L;
    static final long HOUR = TimeUnit.HOURS.toMillis(1);

    @Test
    void channelsAndTheTenBusiestMaps() {
        Map<Integer, Integer> perMap = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            perMap.put(100000000 + i, i + 1);
            names.put(100000000 + i, "Map " + i);
        }
        perMap.put(910000000, 14); // a tie with 100000013: lower map id first
        SiteWorld.Online online = new SiteWorld.Online(Map.of(2, 300, 1, 900), perMap, names);
        Map<?, ?> f = (Map<?, ?>) Json.parse(Json.write(SiteWorld.file(online, null, List.of(), NOW)));
        assertEquals(1L, ((Number) f.get("schema")).longValue());
        assertEquals(1200L, ((Number) f.get("online")).longValue());
        List<?> channels = (List<?>) f.get("channels");
        assertEquals(1L, ((Number) ((Map<?, ?>) channels.getFirst()).get("channel")).longValue());
        List<?> maps = (List<?>) f.get("busiest_maps");
        assertEquals(SiteWorld.BUSIEST, maps.size());
        Map<?, ?> top = (Map<?, ?>) maps.getFirst();
        assertEquals(100000014L, ((Number) top.get("map")).longValue());
        assertEquals("Map 14", top.get("name"));
        assertEquals(100000013L, ((Number) ((Map<?, ?>) maps.get(1)).get("map")).longValue());
        assertEquals(910000000L, ((Number) ((Map<?, ?>) maps.get(2)).get("map")).longValue());
        assertEquals("", ((Map<?, ?>) maps.get(2)).get("name"), "an unknown name is empty, not null");
        assertTrue(f.containsKey("tonight"));
        assertNull(f.get("tonight"));
        assertTrue(((String) f.get("generated_at")).endsWith("Z"));
    }

    @Test
    void runningEventsHideATreasureHuntsMap() {
        SiteWorld.Running hunt = new SiteWorld.Running("Treasure Hunt", "treasure hunt", 0, "Victoria Island", 2,
                NOW - HOUR, NOW + HOUR, false);
        SiteWorld.Running cake = new SiteWorld.Running("Giant Cake", "giant cake", 100000000, "Henesys", 1,
                NOW - 2 * HOUR, NOW + HOUR, true);
        Map<?, ?> f = (Map<?, ?>) Json.parse(Json.write(SiteWorld.file(
                new SiteWorld.Online(Map.of(), Map.of(), Map.of()), new SiteWorld.Headline("Giant Cake", NOW - 2 * HOUR,
                        "running", "Henesys"), List.of(hunt, cake), NOW)));
        List<?> events = (List<?>) f.get("running");
        Map<?, ?> first = (Map<?, ?>) events.get(0);
        assertEquals("Giant Cake", first.get("name"), "oldest first");
        assertEquals("Henesys", first.get("map"));
        assertEquals(1L, ((Number) first.get("channel")).longValue());
        assertTrue(((String) first.get("endsAt")).endsWith("Z"));
        assertFalse(((Map<?, ?>) events.get(1)).containsKey("map_id"), "a treasure hunt hides its map");
        assertEquals("Victoria Island", ((Map<?, ?>) events.get(1)).get("map"));
        Map<?, ?> h = (Map<?, ?>) f.get("tonight");
        assertEquals("running", h.get("status"));
        assertEquals("Henesys", h.get("map"));
        assertTrue(((String) h.get("start")).endsWith("Z"));
    }

    @Test
    void theNextHeadline() {
        long today = NOW + 3 * HOUR, tomorrow = today + 24 * HOUR, grace = TimeUnit.MINUTES.toMillis(30);
        // planned for tonight, not yet started
        assertEquals(new SiteWorld.Headline("Giant Cake", today, "scheduled"),
                SiteWorld.nextHeadline(true, "Giant Cake", today, "", false, today, tomorrow, grace, NOW));
        // running now
        assertEquals(new SiteWorld.Headline("Giant Cake", today, "running"),
                SiteWorld.nextHeadline(true, "Giant Cake", today, "started", true, today, tomorrow, grace, today + HOUR));
        // over (or missed): tomorrow, template not picked yet
        assertEquals(new SiteWorld.Headline(null, tomorrow, "scheduled"),
                SiteWorld.nextHeadline(true, "Giant Cake", today, "started", false, today, tomorrow, grace, today + 2 * HOUR));
        assertEquals(new SiteWorld.Headline(null, tomorrow, "scheduled"),
                SiteWorld.nextHeadline(true, "Giant Cake", today, "missed", false, today, tomorrow, grace, NOW));
        // no plan for today yet (just after midnight): tonight, name unknown
        assertEquals(new SiteWorld.Headline(null, today, "scheduled"),
                SiteWorld.nextHeadline(false, null, 0, "", false, today, tomorrow, grace, NOW));
    }

    @Test
    void versionSaysTheServerAndCommitAndTheShapeNeedsIt() {
        Map<?, ?> f = (Map<?, ?>) Json.parse(Json.write(SiteWorld.file(
                new SiteWorld.Online(Map.of(), Map.of(), Map.of()), null, List.of(), NOW)));
        Map<?, ?> v = (Map<?, ?>) f.get("version");
        assertEquals(constants.net.ServerConstants.SLUMSMS_VERSION, v.get("server"));
        String commit = constants.net.BuildInfo.commit();
        if (commit == null) {
            assertFalse(v.containsKey("commit"), "no recorded commit: the key is left out");
        } else {
            assertEquals(commit, v.get("commit"));
        }
        assertEquals(List.of(), SiteFileShape.shape("world.json", f));
        assertEquals(Map.of("server", "1.6.1", "commit", "5e21fd275"), SiteWorld.version("1.6.1", "5e21fd275"));
        assertEquals(Map.of("server", "1.6.1"), SiteWorld.version("1.6.1", null));
        Map<Object, Object> without = new java.util.LinkedHashMap<>(f);
        without.remove("version");
        assertFalse(SiteFileShape.shape("world.json", without).isEmpty(), "world.json without version is refused");
    }
}
