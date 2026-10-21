package soloMapling.worldevents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorldEventFeedTest {

    private static final long T0 = 1_760_000_000_000L;

    @AfterEach
    void clean() {
        System.clearProperty(WorldEventFeed.PROPERTY);
    }

    private static ActiveEvent event(EventTemplate.Kind kind, String by) {
        EventTemplate t = new EventTemplate(kind.name().toLowerCase(), kind, "Test " + kind.label(), 1, 30, 0, 0, 0, 4,
                List.of(EventTemplate.Spot.map(100000000)), List.of(), 0, 0, 1.5, List.of(), true);
        ActiveEvent e = new ActiveEvent(7, t, t.spots().get(0), 1, T0, by, 1, 255);
        e.mapName = "The Forest of Evil I";
        e.regionName = "Sleepywood";
        e.subject = "Faust";
        return e;
    }

    @Test
    void offWithoutTheProperty() {
        assertNull(WorldEventFeed.file());
        System.setProperty(WorldEventFeed.PROPERTY, "  ");
        assertNull(WorldEventFeed.file(), "blank is off");
    }

    @Test
    void onlyPlayerFacingKindsAreWritten() {
        assertNull(WorldEventFeed.startLine(event(EventTemplate.Kind.INVASION, "scheduler"), T0));
        assertNull(WorldEventFeed.startLine(event(EventTemplate.Kind.WANDERING_NPC, "scheduler"), T0));
        for (EventTemplate.Kind k : WorldEventFeed.KINDS) {
            assertEquals("start", WorldEventFeed.startLine(event(k, "scheduler"), T0).get("type"));
        }
    }

    @Test
    void bossStartNamesBossAndMap() {
        Map<String, Object> m = WorldEventFeed.startLine(event(EventTemplate.Kind.BOSS_SIGHTING, "scheduler"), T0);
        assertEquals("boss_sighting", m.get("kind"));
        assertEquals("Faust", m.get("boss"));
        assertEquals("The Forest of Evil I", m.get("map"));
        assertEquals(30L, m.get("minutes"));
        assertEquals("scheduler", m.get("by"));
        assertEquals("we-" + T0 + "-7-start", m.get("id"));
    }

    @Test
    void treasureStartNeverGivesTheMapAway() {
        Map<String, Object> m = WorldEventFeed.startLine(event(EventTemplate.Kind.TREASURE_HUNT, "Josh"), T0);
        assertEquals("Sleepywood", m.get("region"));
        assertFalse(m.containsKey("map"));
        assertEquals("gm", m.get("by"), "a GM's name is not published");
    }

    @Test
    void hotSpotCarriesTheBonus() {
        Map<String, Object> m = WorldEventFeed.startLine(event(EventTemplate.Kind.HOT_SPOT, "scheduler"), T0 + 60_000);
        assertEquals(50L, m.get("bonus"));
        assertEquals(29L, m.get("minutes"));
    }

    @Test
    void winLinesOnlyForARealWinner() {
        ActiveEvent boss = event(EventTemplate.Kind.BOSS_SIGHTING, "scheduler");
        assertNull(WorldEventFeed.wonLine(boss, T0), "no winner (timed out or a bot killed it)");
        boss.winnerName = "Jimbo";
        Map<String, Object> m = WorldEventFeed.wonLine(boss, T0 + 5_000);
        assertEquals("won", m.get("type"));
        assertEquals("Jimbo", m.get("player"));
        assertEquals("Faust", m.get("boss"));
        assertNotEquals(WorldEventFeed.startLine(boss, T0).get("id"), m.get("id"));

        ActiveEvent hot = event(EventTemplate.Kind.HOT_SPOT, "scheduler");
        hot.winnerName = "Jimbo";
        assertNull(WorldEventFeed.wonLine(hot, T0), "a hot spot has no winner");
        ActiveEvent inv = event(EventTemplate.Kind.INVASION, "scheduler");
        inv.winnerName = "Jimbo";
        assertNull(WorldEventFeed.wonLine(inv, T0));
    }

    @Test
    void appendsOneJsonLinePerMomentOffThread(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("announce.jsonl");
        System.setProperty(WorldEventFeed.PROPERTY, file.toString());
        ActiveEvent e = event(EventTemplate.Kind.TREASURE_HUNT, "scheduler");
        e.mapName = "Line \"one\"\nbreak";
        WorldEventFeed.started(e, T0);
        e.winnerName = "Jimbo";
        WorldEventFeed.won(e, T0 + 1_000);
        WorldEventFeed.started(event(EventTemplate.Kind.INVASION, "scheduler"), T0); // not announced
        WorldEventFeed.drainForTest();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        ObjectMapper json = new ObjectMapper();
        Map<?, ?> first = json.readValue(lines.get(0), Map.class);
        Map<?, ?> second = json.readValue(lines.get(1), Map.class);
        assertEquals("start", first.get("type"));
        assertEquals("won", second.get("type"));
        assertEquals("Line \"one\"\nbreak", second.get("map"), "names are escaped, one line each");
    }

    @Test
    void aBadPathNeverThrows(@TempDir Path dir) throws Exception {
        System.setProperty(WorldEventFeed.PROPERTY, dir.resolve("missing/dir/announce.jsonl").toString());
        WorldEventFeed.started(event(EventTemplate.Kind.HOT_SPOT, "scheduler"), T0);
        WorldEventFeed.drainForTest();
        assertFalse(Files.exists(dir.resolve("missing")));
        WorldEventFeed.write(dir.resolve("missing/x"), Map.of("id", "x")); // logs and returns
        assertFalse(Files.exists(dir.resolve("missing/x")));
    }

    @Test
    void offMeansNoFile(@TempDir Path dir) throws Exception {
        WorldEventFeed.started(event(EventTemplate.Kind.HOT_SPOT, "scheduler"), T0);
        WorldEventFeed.drainForTest();
        try (var s = Files.list(dir)) {
            assertEquals(0, s.count());
        }
    }
}
