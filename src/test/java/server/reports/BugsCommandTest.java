package server.reports;

import client.command.CommandsExecutor;
import client.command.commands.gm2.BugsCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BugsCommandTest {
    @TempDir Path dir;

    @Test
    void listsAndDescribes() throws Exception {
        ReportStore store = new ReportStore(dir.resolve("reports.jsonl"), Clock.systemUTC());
        assertEquals(List.of("No in-game reports yet."), BugsCommand.list(store, 10));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("versions", Map.of("server", "1.6.1", "commit", "abc1234", "client", Map.of("build", 10680, "release", "1.6.1", "caps", 1)));
        ctx.put("character", Map.of("level", 30, "job", Map.of("name", "FIGHTER"), "hp", 5, "maxHp", 10, "mp", 1, "maxMp", 2,
                "x", 3, "y", 4, "foothold", 9));
        ctx.put("trail", Map.of("maps", List.of(Map.of("id", 1, "name", "Henesys")), "npcs", List.of(),
                "events", List.of(Map.of("kind", "attack", "id", 1001004, "name", "Power Strike", "count", 12))));
        ctx.put("serverLog", List.of("2026-10-28T10:00:00.000Z WARN X - stuck for Tester"));
        ReportStore.Context who = new ReportStore.Context("Tester", 42, 1, 1, 100000000, "Henesys", 0, 0, null);
        store.submit("bug", "cannot leave the map", who, () -> ctx).get();
        store.submit("feature", "bigger bags please", who).get();

        List<String> list = BugsCommand.list(store, 10);
        assertEquals(3, list.size());
        assertTrue(list.get(1).startsWith("I-2 "), list.get(1));
        assertTrue(list.get(2).startsWith("B-1 ") && list.get(2).contains("Tester @ Henesys: cannot leave the map"), list.get(2));
        assertEquals(2, BugsCommand.list(store, 1).size());

        String all = String.join("\n", BugsCommand.describe(store, "b-1"));
        assertTrue(all.contains("B-1 (bug)"), all);
        assertTrue(all.contains("Says: cannot leave the map"));
        assertTrue(all.contains("Server 1.6.1 abc1234, client 10680 1.6.1 caps 1"), all);
        assertTrue(all.contains("Lv 30 FIGHTER"), all);
        assertTrue(all.contains("Last maps: Henesys"), all);
        assertTrue(all.contains("attack Power Strike x12"), all);
        assertTrue(all.contains("Server WARN/ERROR lines about them: 1"), all);
        assertEquals(List.of("No in-game report B-9."), BugsCommand.describe(store, "B-9"));
    }

    @Test
    void registeredForGms() {
        assertTrue(CommandsExecutor.getInstance().getGmCommands().get(2).getLeft().contains("bugs"));
    }
}
