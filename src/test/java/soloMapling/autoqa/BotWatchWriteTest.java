package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 (owner 2026-10-26): botwatch-latest.json plus a dated copy, and the track's short JSON keys. */
class BotWatchWriteTest {
    @TempDir Path dir;

    @Test
    void writesTheLatestAndADatedCopy() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", 1);
        out.put("summary", "1/1 bots OK");
        Path dated = BotWatch.write(out, dir, 1_793_042_419_453L);
        assertEquals(dir.resolve("botwatch").resolve("botwatch-2026-10-26T1920Z.json"), dated);
        assertEquals(Files.readString(dated), Files.readString(dir.resolve("botwatch-latest.json")));
        assertTrue(soloMapling.server.Json.isValid(Files.readString(dated)));
    }

    @Test
    void anOfflineSampleSaysOnlyThatAndAnOnlineOneLeavesOutDefaults() {
        assertEquals(Map.of("t", 5L, "on", false), BotWatchJudge.Sample.offline(5).toJson());
        var s = new BotWatchJudge.Sample(1, true, 2, 100000000, 10, 20, "HANG_OUT", -1, "LIFE_DWELL", 30, 5, 6, 0, 7,
                1, 2, null, 0, false, true, true, false, false, 0, false);
        Map<String, Object> j = s.toJson();
        assertFalse(j.containsKey("target"));
        assertFalse(j.containsKey("chat"));
        assertFalse(j.containsKey("jumpPx"));
        assertEquals(List.of("t", "on", "ch", "map", "x", "y", "act", "phase", "lv", "exp", "meso", "kills", "pots",
                "etc", "equip", "party", "sit", "town", "mobs", "watched"), List.copyOf(j.keySet()));
    }
}
