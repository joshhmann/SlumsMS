package scripting.npc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.expeditions.ExpeditionType;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos Zakum (round 11, item 7): Adobis offers the chaos expedition only when the server turns it on with
 * {@code -Dslums.chaos.zakum=true}. Off, the NPC says exactly what it always said and never asks for the chaos event
 * manager; on, one extra menu line switches the whole conversation to {@code CHAOS_ZAKUM} / {@code ChaosZakumBattle}
 * and the expedition that gets created is the chaos one.
 *
 * <p>Runs {@code scripts/npc/2030013.js} through the same Graal engine the server uses, with the host access
 * {@code scripting/AbstractScriptManager.java:84-88} grants, over a stand-in {@code cm}.
 */
class ChaosZakumNpcTest {

    static final String SWITCH = "slums.chaos.zakum";

    @AfterEach
    void clearSwitch() {
        System.clearProperty(SWITCH);
    }

    /** The NPC action manager, as far as this script is concerned. */
    public static class Cm {
        final List<String> askedFor = new ArrayList<>();
        final List<ExpeditionType> created = new ArrayList<>();
        final List<String> simple = new ArrayList<>();
        final List<String> ok = new ArrayList<>();
        final Player player = new Player();
        boolean disposed;

        public Player getPlayer() {
            return player;
        }

        public Object getExpedition(ExpeditionType type) {
            return null; // nobody has assembled a team yet: the assemble prompt is the one under test
        }

        public Em getEventManager(String name) {
            askedFor.add(name);
            return new Em(name);
        }

        public boolean haveItem(int itemId) {
            return true;
        }

        public int createExpedition(ExpeditionType type) {
            created.add(type);
            return 0;
        }

        public void sendSimple(String text) {
            simple.add(text);
        }

        public void sendOk(String text) {
            ok.add(text);
        }

        public void dispose() {
            disposed = true;
        }
    }

    public static class Em {
        final String name;

        Em(String name) {
            this.name = name;
        }

        public String getProperty(String key) {
            return "Number of players: 6 ~ 30";
        }
    }

    public static class Player {
        public int getLevel() {
            return 120;
        }

        public String getName() {
            return "Zak";
        }
    }

    private static ScriptEngine engine(Cm cm) throws Exception {
        Path script = Path.of("scripts/npc/2030013.js");
        assertTrue(Files.isRegularFile(script), "scripts/npc/2030013.js is missing");
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostClassLookup", true);
        engine.put("cm", cm);
        engine.eval(Files.readString(script));
        return engine;
    }

    private static void select(ScriptEngine engine, int selection) throws ScriptException,
            NoSuchMethodException {
        ((Invocable) engine).invokeFunction("action", 1, 0, selection);
    }

    @Test
    void withTheSwitchOffTheMenuHasNoChaosLineAndOnlyTheNormalEventIsAskedFor() throws Exception {
        Cm cm = new Cm();
        ScriptEngine engine = engine(cm);
        ((Invocable) engine).invokeFunction("start");

        assertEquals(1, cm.simple.size());
        assertFalse(cm.simple.get(0).contains("Chaos"), cm.simple.get(0));
        assertEquals(List.of("ZakumBattle"), cm.askedFor, "the chaos event manager is never asked for");
    }

    @Test
    void withTheSwitchOnTheMenuOffersChaosZakum() throws Exception {
        System.setProperty(SWITCH, "true");
        Cm cm = new Cm();
        ScriptEngine engine = engine(cm);
        ((Invocable) engine).invokeFunction("start");

        assertTrue(cm.simple.get(0).contains("Take on Chaos Zakum instead"), cm.simple.get(0));
        assertEquals(List.of("ZakumBattle"), cm.askedFor, "nothing chaos is asked for until the player picks it");
    }

    @Test
    void pickingTheChaosLineCreatesAChaosZakumExpedition() throws Exception {
        System.setProperty(SWITCH, "true");
        Cm cm = new Cm();
        ScriptEngine engine = engine(cm);
        ((Invocable) engine).invokeFunction("start");

        select(engine, 3); // "Take on Chaos Zakum instead"
        assertTrue(cm.askedFor.contains("ChaosZakumBattle"), "the chaos event serves the chaos expedition");
        assertTrue(cm.simple.get(cm.simple.size() - 1).contains("Chaos Zakum"),
                "the re-shown menu is the chaos one: " + cm.simple.get(cm.simple.size() - 1));

        select(engine, 1); // "Lets get this going!"
        assertEquals(List.of(ExpeditionType.CHAOS_ZAKUM), cm.created, "the expedition created is the chaos one");
    }

    @Test
    void pickingTheNormalLineStillCreatesTheNormalExpedition() throws Exception {
        System.setProperty(SWITCH, "true");
        Cm cm = new Cm();
        ScriptEngine engine = engine(cm);
        ((Invocable) engine).invokeFunction("start");

        select(engine, 1);
        assertEquals(List.of(ExpeditionType.ZAKUM), cm.created);
        assertNull(cm.created.stream().filter(t -> t == ExpeditionType.CHAOS_ZAKUM).findFirst().orElse(null));
    }
}
