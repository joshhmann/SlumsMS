package server.maps;

import client.Character;
import org.junit.jupiter.api.Test;
import javax.script.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ClassicFreeMarketRouteTest {
    private static final Path SCRIPTS = Path.of(System.getProperty("basedir"))
            .resolve("scripts/portal");

    public static class PortalFixture {
        public final Character player = mock(Character.class);
        public int destination = -1;
        public Object portal;
        public boolean failWarp;
        public Character getPlayer() { return player; }
        public void playPortalSound() {}
        public int getMarketPortalId(int map) { return 3; }
        public void warp(int map, int portal) { performWarp(map, portal); }
        public void warp(int map, String portal) { performWarp(map, portal); }
        private void performWarp(int map, Object portal) {
            if (failWarp) throw new IllegalStateException("intentional warp failure");
            this.destination = map; this.portal = portal;
        }
    }

    private Object enter(String name, PortalFixture fixture) throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        engine.getBindings(ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
        try {
            engine.eval(Files.readString(SCRIPTS.resolve(name)));
            return ((Invocable) engine).invokeFunction("enter", fixture);
        } finally {
            ((com.oracle.truffle.js.scriptengine.GraalJSScriptEngine) engine).close();
        }
    }

    @Test
    void everyTownEntranceUsesClassicFmAndIndependentReturnBookmark() throws Exception {
        List<Path> routes;
        try (var files = Files.list(SCRIPTS)) {
            routes = files.filter(p -> p.getFileName().toString().matches("market(?!00)\\d+\\.js")).toList();
        }
        assertFalse(routes.isEmpty());
        for (Path route : routes) {
            PortalFixture pi = new PortalFixture();
            when(pi.player.getMapId()).thenReturn(100000000);
            assertEquals(true, enter(route.getFileName().toString(), pi), route.toString());
            assertEquals(910000000, pi.destination, route.toString());
            verify(pi.player).saveLocation("CLASSIC_FREE_MARKET");
            verify(pi.player, never()).saveLocation("FREE_MARKET");
        }
    }

    @Test
    void arcaneEntryKeepsTheHubReturnBookmarkIntact() throws Exception {
        PortalFixture pi = new PortalFixture();
        enter("arc_to_classicfm.js", pi);
        assertEquals(910000000, pi.destination);
        verify(pi.player).saveLocation("CLASSIC_FREE_MARKET");
        verify(pi.player, never()).saveLocation("FREE_MARKET");
    }

    @Test
    void exitReturnsToEntryTownAndConsumesOnlyClassicBookmark() throws Exception {
        PortalFixture pi = new PortalFixture();
        when(pi.player.peekSavedLocation("CLASSIC_FREE_MARKET")).thenReturn(100000000);
        enter("classicfm_exit.js", pi);
        assertEquals(100000000, pi.destination);
        assertEquals(3, pi.portal);
        verify(pi.player).getSavedLocation("CLASSIC_FREE_MARKET");
        verify(pi.player, never()).getSavedLocation("FREE_MARKET");
    }

    @Test
    void missingOrSelfReferencingReturnUsesArcaneFallback() throws Exception {
        for (int bookmark : new int[]{-1, 910000000, 910000022}) {
            PortalFixture pi = new PortalFixture();
            when(pi.player.peekSavedLocation("CLASSIC_FREE_MARKET")).thenReturn(bookmark);
            enter("classicfm_exit.js", pi);
            assertEquals(993910000, pi.destination);
        }
    }

    @Test
    void failedExitKeepsReturnBookmarkForRetry() {
        PortalFixture pi = new PortalFixture();
        when(pi.player.peekSavedLocation("CLASSIC_FREE_MARKET")).thenReturn(100000000);
        pi.failWarp = true;
        assertThrows(ScriptException.class, () -> enter("classicfm_exit.js", pi));
        verify(pi.player, never()).getSavedLocation("CLASSIC_FREE_MARKET");
    }

    @Test
    void newReturnTypeDoesNotReorderExistingJavaSlots() {
        assertEquals(0, SavedLocationType.FREE_MARKET.ordinal());
        assertEquals(10, SavedLocationType.DEVELOPER.ordinal());
        assertEquals(11, SavedLocationType.CLASSIC_FREE_MARKET.ordinal());
    }
}
