package scripting.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.GqStatuesSolver;
import soloMapling.mechanics.Mechanics;
import soloMapling.mechanics.MechanicsModel;
import soloMapling.mechanics.MechanicsPersona;
import soloMapling.mechanics.RunMember;
import soloMapling.mechanics.SolverStep;
import soloMapling.mechanics.StageData;
import soloMapling.mechanics.StageView;

/**
 * Guild Quest's Gatekeeper test, with the SHIPPED scripts and {@link GqStatuesSolver} in one loop: the solver's reading
 * of the pattern (event property {@code stage1combo}, three digits per statue, {@code objectId % 1000}) and its ordering
 * rules (never touch a statue while the pattern is displayed; one clicker; a single click submits) are facts about
 * {@code scripts/npc/9040009.js} and {@code scripts/reactor/9208000-2.js}, so the test runs those files in the same Graal
 * engine the server uses over stand-ins for {@code cm}/{@code rm}, and lets the solver's steps drive them. A solver that
 * hit a statue while the pattern was showing would write a wrong chunk into the script's own combo and fail here.
 */
class GatekeeperScriptParityTest {

    // ------------------------------------------------------------------------------ stand-ins for the script's objects

    public static class Eim {
        final Map<String, String> props = new LinkedHashMap<>();
        boolean wrongShown;

        public void setProperty(String key, Object value) {
            props.put(key, String.valueOf(value));
        }

        public String getProperty(String key) {
            return props.get(key);
        }

        public void showClearEffect(boolean b) {
        }

        public void showWrongEffect() {
            wrongShown = true;
        }

        public void giveEventPlayersStageReward(int stage) {
        }
    }

    public static class Statue {
        final int oid;
        final int id;
        final String name;
        final World world;

        Statue(World world, int oid, int id, String name) {
            this.world = world;
            this.oid = oid;
            this.id = id;
            this.name = name;
        }

        public int getObjectId() {
            return oid;
        }

        public String getName() {
            return name;
        }

        public boolean isRecentHitFromAttack() {
            return false; // a bot's hit is Reactor.hitReactor(client): not an attack (wHit false)
        }

        public void forceHitReactor(int state) {
            world.gateState = state;
        }

        public void delayedHitReactor(Object client, int delay) {
            world.delayed.add(this);
        }
    }

    public static class MapStandIn {
        final World world;

        MapStandIn(World world) {
            this.world = world;
        }

        public List<Statue> getReactors() {
            return world.statues;
        }

        public Statue getReactorByName(String name) {
            return world.statues.stream().filter(s -> s.name.equals(name)).findFirst().orElse(null);
        }

        public Statue getReactorByOid(int oid) {
            return world.statues.stream().filter(s -> s.oid == oid).findFirst().orElse(null);
        }
    }

    public static class Player {
        final World world;

        Player(World world) {
            this.world = world;
        }

        public Eim getEventInstance() {
            return world.eim;
        }

        public MapStandIn getMap() {
            return world.map;
        }
    }

    public static class Guild {
        public void gainGP(int n) {
        }
    }

    public static class Rm {
        final World world;
        final Statue statue;

        Rm(World world, Statue statue) {
            this.world = world;
            this.statue = statue;
        }

        public Player getPlayer() {
            return world.player;
        }

        public Statue getReactor() {
            return statue;
        }

        public void mapMessage(int type, String text) {
        }
    }

    public static class Cm {
        final World world;
        final List<String> said = new ArrayList<>();

        Cm(World world) {
            this.world = world;
        }

        public Player getPlayer() {
            return world.player;
        }

        public Object getActingClient() {
            return "bound-client";
        }

        public boolean isEventLeader() {
            return true;
        }

        public void sendOk(String text) {
            said.add(text);
        }

        public void dispose() {
        }

        public void warp(int map) {
            throw new AssertionError("the Gatekeeper warped the leader out: there is no event instance");
        }

        public void mapMessage(int type, String text) {
        }

        public Guild getGuild() {
            return new Guild();
        }
    }

    // ------------------------------------------------------------------------------------------------------- the world

    static class World implements StageView {
        final Eim eim = new Eim();
        final Player player = new Player(this);
        final MapStandIn map = new MapStandIn(this);
        final List<Statue> statues = new ArrayList<>();
        final List<Statue> delayed = new ArrayList<>();
        final List<RunMember> members = new ArrayList<>();
        final Map<String, Object> memory = new java.util.HashMap<>();
        final Cm cm = new Cm(this);
        final ScriptEngine npc = engine("scripts/npc/9040009.js", "cm", cm);
        final Map<Integer, ScriptEngine> reactorScripts = new java.util.HashMap<>();
        int gateState;
        final List<String> trace = new ArrayList<>();
        long now = 1_000;

        World(int bots) {
            // 20 statues of the three ids, as Map9/990000300.img.xml has them, and the gate; object ids as an instance hands out
            int[] ids = {9208000, 9208001, 9208002};
            for (int i = 0; i < 20; i++) {
                statues.add(new Statue(this, 500_000_100 + i, ids[i % 3], String.valueOf(i + 1)));
            }
            statues.add(new Statue(this, 500_000_150, 9208003, "statuegate"));
            for (int i = 1; i <= bots; i++) {
                members.add(new RunMember(i, i == 1, true, MechanicsPersona.PERFECT, 0, 30));
            }
        }

        static ScriptEngine engine(String file, String name, Object stand) {
            try {
                ScriptEngine e = new ScriptEngineManager().getEngineByName("graal.js");
                e.getBindings(ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
                e.put(name, stand);
                e.eval(Files.readString(Path.of(file)));
                return e;
            } catch (Exception ex) {
                throw new AssertionError(file, ex);
            }
        }

        /** The statue's own script, run as the server runs {@code act()}: with {@code rm} for that reactor. */
        void hit(Statue s) {
            if (s.id == 9208003) {
                return;
            }
            try {
                ScriptEngine e = engine("scripts/reactor/" + s.id + ".js", "rm", new Rm(this, s));
                ((Invocable) e).invokeFunction("act");
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }

        /** The Gatekeeper's click: {@code start()}, then one {@code action(1,0,answer)} per answer. */
        void converse(List<Integer> answers) {
            try {
                Invocable inv = (Invocable) npc;
                inv.invokeFunction("start");
                for (int a : answers) {
                    inv.invokeFunction("action", 1, 0, a);
                }
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }

        /** The ten-second-ish display: the delayed hits the Gatekeeper queued land one by one, in order. */
        void runDisplay() {
            List<Statue> due = new ArrayList<>(delayed);
            delayed.clear();
            for (Statue s : due) {
                hit(s);
            }
        }

        void apply(RunMember m, SolverStep step) {
            trace.add(m.characterId() + " " + step.getClass().getSimpleName() + " " + step);
            switch (step) {
                case SolverStep.Converse c -> {
                    converse(c.answers());
                    runDisplay();
                }
                case SolverStep.TalkNpc t -> converse(List.of());
                case SolverStep.HitReactorByOid h -> hit(map.getReactorByOid(h.objectId()));
                default -> {
                }
            }
        }

        // ------------------------------------------------------------------------------------------------ StageView

        @Override
        public MechanicsModel.Stage stage() {
            return StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR)).content().stream()
                    .filter(c -> c.id().equals("guildquest")).findFirst().orElseThrow().stages().get(2);
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public String roleOf(int characterId) {
            return "all";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return 0;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.ofNullable(eim.props.get(key));
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public int mapOf(int characterId) {
            return 990000300;
        }

        @Override
        public boolean wearing(int characterId, int itemId) {
            return true;
        }

        @Override
        public List<ReactorPlacement> reactors() {
            List<ReactorPlacement> out = new ArrayList<>();
            for (Statue s : statues) {
                out.add(new ReactorPlacement(s.oid, s.id, s.name, 0, s.oid % 1000, 0));
            }
            return out;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
        }

        @Override
        public long nowMs() {
            return now;
        }
    }

    // ------------------------------------------------------------------------------------------------------- the tests

    @Test
    void theShippedScriptsAndTheSolverClearTheGatekeeperInThreeRounds() {
        World w = new World(6);
        GqStatuesSolver solver = new GqStatuesSolver();
        int rounds = 0;
        String lastPhase = "";

        for (int tick = 0; tick < 200 && !"true".equals(w.eim.props.get("stage1clear")); tick++) {
            for (RunMember m : w.members) {
                String status = w.eim.props.get("stage1status");
                SolverStep step = solver.next(w, m);
                if ("display".equals(status)) {
                    assertFalse(step instanceof SolverStep.HitReactorByOid, "nobody hits a statue while the pattern is shown");
                }
                w.apply(m, step);
            }
            String phase = w.eim.props.getOrDefault("stage1phase", "");
            if (!phase.equals(lastPhase)) {
                rounds++;
                lastPhase = phase;
            }
            w.now += 1_000;
        }

        assertEquals("true", w.eim.props.get("stage1clear"), "the Gatekeeper's own script cleared the stage; trace tail: "
                + w.trace.subList(Math.max(0, w.trace.size() - 6), w.trace.size()));
        assertEquals(1, w.gateState, "and forced the statue gate open");
        assertEquals(3, rounds, "phases 1, 2 and 3 were each played once: no wrong answer reset it");
        assertFalse(w.eim.wrongShown, "the script never judged a guess wrong: every hit was the right statue in order");
    }

    @Test
    void aHitDuringTheDisplayWritesIntoThePatternSoTheSolverDoesNotMakeOne() {
        World w = new World(2);
        w.eim.setProperty("stage1phase", 1);
        w.converse(List.of(0)); // the leader starts the display; the statue hits are queued
        assertEquals("display", w.eim.props.get("stage1status"));

        // what a bot that hit during the display would have done: the script appends its statue to the combo
        w.hit(w.statues.get(5));
        assertEquals("105", w.eim.props.get("stage1combo"),
                "the stray hit was written into the pattern: statue 6 is object 500000105, chunk 105");

        // the solver, asked at the same moment, waits
        SolverStep step = new GqStatuesSolver().next(w, w.members.get(1));
        assertInstanceOf(SolverStep.Wait.class, step);
        assertTrue(((SolverStep.Wait) step).why().contains("pattern"), ((SolverStep.Wait) step).why());
    }

    @Test
    void theFirstClickOnlyShowsTheInstructionAndTheAnswerStartsTheDisplay() {
        World w = new World(2);

        SolverStep first = new GqStatuesSolver().next(w, w.members.get(0));
        SolverStep.Converse c = assertInstanceOf(SolverStep.Converse.class, first);
        assertEquals(9040009, c.npcId());
        assertEquals(List.of(0), c.answers());

        w.converse(List.of());
        assertEquals(null, w.eim.props.get("stage1status"), "the click alone only shows the instruction (status 0)");
        w.converse(List.of(0));
        assertEquals("display", w.eim.props.get("stage1status"), "the click and its first answer start the display");
        assertEquals(4, w.delayed.size(), "phase 1 shows four statues (stage + 3)");
    }
}
