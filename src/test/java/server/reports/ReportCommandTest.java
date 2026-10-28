package server.reports;

import client.Character;
import client.Client;
import client.command.CommandsExecutor;
import client.command.commands.gm0.ReportBugCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import soloMapling.server.Json;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class ReportCommandTest {
    @TempDir Path dir;

    private Client client(Character player) {
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(player);
        when(c.getWorld()).thenReturn(1);
        when(c.getChannel()).thenReturn(3);
        when(c.getAccID()).thenReturn(42);
        return c;
    }

    private Character player(String text) {
        Character p = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(104000000);
        when(map.getMapName()).thenReturn("Lith Harbor");
        when(p.getMap()).thenReturn(map);
        when(p.getName()).thenReturn("Tester");
        when(p.getPosition()).thenReturn(new Point(5, 6));
        when(p.getLastCommandMessage()).thenReturn(text);
        return p;
    }

    @Test
    void bugIsLoggedRepliedAndBroadcast() throws Exception {
        ReportStore store = new ReportStore(dir.resolve("reports.jsonl"), Clock.systemUTC());
        java.util.List<Integer> worlds = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.BiConsumer<Integer, net.packet.Packet> gm = (w, pk) -> worlds.add(w);
        Character p = player("the door is stuck");
        ReportBugCommand.report(client(p), new String[]{"the"}, "bug", "Bug", "bug", store, gm);
        waitFor(() -> worlds.size() >= 2);
        verify(p).dropMessage(5, "Thanks! Bug #B-1 sent.");
        assertEquals(java.util.List.of(1, 1), worlds); // yellow tip + popup

        Character q = player("make a bigger bag");
        ReportBugCommand.report(client(q), new String[]{"x"}, "feature", "Idea", "idea", store, gm);
        waitFor(() -> worlds.size() >= 3);
        verify(q).dropMessage(5, "Thanks! Idea #I-2 sent.");
        assertEquals(3, worlds.size()); // ideas: yellow tip only
        String line = Files.readAllLines(dir.resolve("reports.jsonl")).get(0);
        assertTrue(line.contains("\"where\":\"Lith Harbor (104000000) ch3\""), line);
        assertTrue(line.contains("\"ref\":\"B-1\""), line);
        // a bug carries a context file (sections that cannot be read on a mock still come out as {"error": ...})
        Map<?, ?> ctx = (Map<?, ?>) Json.parse(Files.readString(dir.resolve("context/B-1.json")));
        assertEquals("B-1", ctx.get("ref"));
        assertTrue(ctx.containsKey("versions") && ctx.containsKey("character") && ctx.containsKey("trail"), ctx.keySet().toString());
        assertFalse(Files.exists(dir.resolve("context/I-2.json")), "ideas carry no context");
    }

    @Test
    void bareBugShowsUsage() {
        ReportStore store = new ReportStore(dir.resolve("reports.jsonl"), Clock.systemUTC());
        Character p = player("");
        ReportBugCommand.report(client(p), new String[]{}, "bug", "Bug", "bug", store, (w, pk) -> { });
        verify(p).dropMessage(eq(5), contains("Usage: @bug <what went wrong> (up to 500 characters)"));
    }

    @Test
    void aClientWithLogUploadIsAskedAfterABug() throws Exception {
        ReportStore store = new ReportStore(dir.resolve("reports.jsonl"), Clock.systemUTC());
        Character p = player("the door is stuck");
        when(p.getId()).thenReturn(77);
        Client c = client(p);
        when(c.getClientStampFormat()).thenReturn(2);
        when(c.getClientCaps()).thenReturn(BugClientLogs.CAPS_LOG_UPLOAD | 1);
        ReportBugCommand.report(c, new String[]{"the"}, "bug", "Bug", "bug", store, (w, pk) -> { });
        verify(c, timeout(3000)).sendPacket(any(net.packet.Packet.class));

        Client old = client(player("an old client bug"));
        when(old.getClientStampFormat()).thenReturn(2);
        when(old.getClientCaps()).thenReturn(1);
        ReportBugCommand.report(old, new String[]{"x"}, "bug", "Bug", "bug", store, (w, pk) -> { });
        verify(old.getPlayer(), timeout(3000)).dropMessage(eq(5), startsWith("Thanks! Bug #B-"));
        verify(old, never()).sendPacket(any(net.packet.Packet.class));
    }

    @Test
    void tooShortGetsAMessageAndNoBroadcast() throws Exception {
        ReportStore store = new ReportStore(dir.resolve("reports.jsonl"), Clock.systemUTC());
        Character p = player("hi");
        java.util.List<Integer> worlds = new java.util.concurrent.CopyOnWriteArrayList<>();
        ReportBugCommand.report(client(p), new String[]{"hi"}, "bug", "Bug", "bug", store, (w, pk) -> worlds.add(w));
        verify(p).dropMessage(eq(5), contains("too short"));
        assertTrue(worlds.isEmpty());
        assertFalse(Files.exists(dir.resolve("reports.jsonl")));
    }

    private static void waitFor(java.util.function.BooleanSupplier ok) throws Exception {
        for (int i = 0; i < 100 && !ok.getAsBoolean(); i++) Thread.sleep(20);
        Thread.sleep(50);
    }

    @Test
    void commandsAreRegisteredWithAlias() {
        var names = CommandsExecutor.getInstance().getGmCommands().get(0).getLeft();
        assertTrue(names.containsAll(java.util.List.of("bug", "reportbug", "idea")), names.toString());
    }
}
