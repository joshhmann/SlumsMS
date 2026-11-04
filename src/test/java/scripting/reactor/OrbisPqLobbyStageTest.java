package scripting.reactor;

import org.junit.jupiter.api.Test;
import server.maps.Reactor;
import server.maps.ReactorStats;
import tools.Pair;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lobby stage of Orbis PQ, end to end without a server: map 920010400 has seven {@code lp} boxes <em>and</em> one
 * reactor named {@code music} (id {@code 2008006}), and {@code scripts/event/OrbisPQ.js:162-163} arms that reactor with
 * {@code setEventState(new Date().getDay())}. Dropping yesterday's pass matches no slot of the reactor's state, so it is
 * silently ignored; dropping today's inside the reactor's own box is what runs {@code scripts/reactor/2008006.js} and
 * writes {@code statusStg3 = 0}, the value {@code scripts/npc/2013001.js:118-131} waits for before it clears the stage
 * and hits {@code stone3}.
 *
 * <p>This runs the shipped script file through the same Graal engine the server uses
 * ({@code scripting/AbstractScriptManager.java:45}) over a stand-in {@code rm}; the reactor is built the way
 * {@code server/maps/ReactorFactory.java:127-200} builds the reactors of a map, which is the builder
 * {@code server/maps/MapFactory.java:406} uses.
 */
class OrbisPqLobbyStageTest {

    static final int MUSIC_PLAYER = 2008006;
    /** {@code 4001056} Sunday through {@code 4001062} Saturday, in {@code Date().getDay()} order. */
    static final int SUNDAY_PASS = 4001056;
    static final int DAYS = 7;
    static final Point MUSIC_PLAYER_AT = new Point(-1706, -240);

    /** What the script calls on {@code rm}; only the event instance matters to it. */
    public static class Rm {
        final Eim eim = new Eim();

        public Eim getEventInstance() {
            return eim;
        }
    }

    /** {@code EventInstanceManager} as far as this script is concerned. */
    public static class Eim {
        final Map<String, String> props = new LinkedHashMap<>();

        public void setProperty(String key, String value) {
            props.put(key, String.valueOf(value));
        }

        public String getProperty(String key) {
            return props.get(key);
        }

        public int getIntProperty(String key) {
            String v = props.get(key);
            return v == null ? -1 : Integer.parseInt(v);
        }
    }

    @Test
    void theMusicPlayerScriptWritesTheLobbyStage() throws Exception {
        Path script = Path.of("scripts/reactor/2008006.js");
        assertTrue(Files.isRegularFile(script), "scripts/reactor/2008006.js is missing");

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
        Rm rm = new Rm();
        engine.put("rm", rm);
        engine.eval(Files.readString(script));
        ((Invocable) engine).invokeFunction("act");

        assertEquals("0", rm.eim.getProperty("statusStg3"),
                "dropping the pass on the music player has to leave statusStg3 at 0 for Eak's 920010400 talk");
    }

    /**
     * {@code MapleMap.activateItemReactors} (MapleMap.java:2664-2679) compares the dropped item with
     * {@code getReactItem(getEventState())}, so the armed day alone decides which pass the player may drop.
     */
    @Test
    void onlyTheArmedDaysPassMatchesTheMusicPlayer() {
        for (int day = 0; day < DAYS; day++) {
            Reactor music = musicPlayer();
            music.setEventState((byte) day);

            Pair<Integer, Integer> accepted = music.getReactItem(music.getEventState());
            assertEquals(SUNDAY_PASS + day, accepted.getLeft(), "day " + day + " accepts its own pass");
            assertEquals(1, accepted.getRight(), "a pass is dropped one at a time");
        }
    }

    @Test
    void aPassFromAnotherDayMatchesNothing() {
        Reactor music = musicPlayer();
        music.setEventState((byte) 0); // armed for Sunday

        assertEquals(SUNDAY_PASS, music.getReactItem(music.getEventState()).getLeft());
        assertFalse(music.getReactItem(music.getEventState()).getLeft() == SUNDAY_PASS + 3,
                "a Wednesday pass must not be accepted by a Sunday-armed music player");
    }

    @Test
    void thePassHasToLandInsideTheReactorBox() {
        Reactor music = musicPlayer();

        assertTrue(music.getArea().contains(MUSIC_PLAYER_AT), "the reactor's own spot arms it");
        assertTrue(music.getArea().contains(new Point(MUSIC_PLAYER_AT.x + 30, MUSIC_PLAYER_AT.y + 40)),
                "a pass dropped just beside it still counts");
        assertFalse(music.getArea().contains(new Point(MUSIC_PLAYER_AT.x + 200, MUSIC_PLAYER_AT.y)),
                "a pass dropped across the room arms nothing");
    }

    /** Built the way {@code ReactorFactory.getReactor} builds it: every state keeps every {@code event} child. */
    static Reactor musicPlayer() {
        ReactorStats stats = new ReactorStats();
        for (byte state = 0; state < DAYS + 1; state++) {
            List<ReactorStats.StateData> slots = new ArrayList<>();
            for (int day = 0; day < DAYS; day++) {
                slots.add(new ReactorStats.StateData(100, new Pair<>(SUNDAY_PASS + day, 1), null, (byte) (day + 1)));
            }
            stats.addState(state, slots, -1);
        }
        stats.setTL(new Point(-52, -64));
        stats.setBR(new Point(40, 79));
        Reactor music = new Reactor(stats, MUSIC_PLAYER);
        music.setPosition(MUSIC_PLAYER_AT);
        return music;
    }
}
