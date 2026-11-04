package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CWKPQ stage 1 as a row stage: five sigils on `610030400`, the event's own `glpq4`, and a string-valued
 * `clear: { eventProperty: glpq4, equals: 5 }` - the property is written as the string "5" by
 * `scripts/reactor/6109016.js:5`, and `StageView.cleared()` compares strings (`StageView.java:272-273`).
 *
 * <p>This answers the question "can the keep's first stage be a row stage at all" with a test: the row parses, the
 * solver drives five sigils from five members, the counter is the clear, and a lit room is a wait because `act()` is
 * unguarded and a sixth hit would leave `glpq4` at 6, where `== 5` could never match again.
 */
class CwkpqSigilSolversTest {

    private static final int[] PARTY = {1, 2, 3, 4, 5, 6};

    private static MechanicsModel.Content miniRow(Path dir, String equals) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "cwk1.yaml\n");
        Files.writeString(dir.resolve("cwk1.yaml"), """
                id: cwk1
                name: The keep's sign-up
                kind: PQ
                eventScript: CWKPQ
                entryNpc: 9000000
                recruitMap: 610030020
                exitMap: 610030020
                minPlayers: 6
                maxPlayers: 30
                minLevel: 90
                maxLevel: 255
                timeLimitMinutes: 2
                flag: slums.mechanics.cwkpq
                sources:
                  - "scripts/event/CWKPQ.js:26-39 gates, :98-149 the shuffle; scripts/reactor/6109016.js:1-12 the sigil"
                stages:
                  - stage: 1
                    map: 610030400
                    name: The five job sigils
                    solver: cwkpq-sigils
                    roles:
                      - id: signer
                        who: ANY
                        min: 1
                        max: party
                    objectives:
                      - id: sigil1
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109016"
                      - id: sigil2
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109017"
                      - id: sigil3
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109018"
                      - id: sigil4
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109019"
                      - id: sigil5
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109020"
                    clear:
                      eventProperty: glpq4
                """.replace("                ", "").replace("      eventProperty: glpq4", "      eventProperty: glpq4\n      equals: " + equals));
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertTrue(result.errors().isEmpty(), "the row must read cleanly: " + result.errors());
        return result.content().get(0);
    }

    /** A party in the sign-up room, plus the event's counter, which the reactor scripts raise. */
    private static final class View implements StageView {
        private final MechanicsModel.Stage stage;
        private final List<RunMember> members = new ArrayList<>();
        private final Map<Integer, Integer> at = new LinkedHashMap<>();
        private final Map<String, String> props = new LinkedHashMap<>();

        View(MechanicsModel.Stage stage) {
            this.stage = stage;
            for (int id : PARTY) {
                members.add(new RunMember(id, id == PARTY[0], true, MechanicsPersona.PERFECT));
                at.put(id, stage.mapId());
            }
        }

        @Override public MechanicsModel.Stage stage() { return stage; }
        @Override public List<RunMember> members() { return members; }
        @Override public String roleOf(int id) { return stage.roles().get(0).id(); }
        @Override public OptionalInt answerFor(int id) { return OptionalInt.empty(); }
        @Override public int itemCount(int id, int item) { return 0; }
        @Override public Optional<String> eventProperty(String name) { return Optional.ofNullable(props.get(name)); }
        @Override public int[] positionOf(int id) { return new int[]{at.getOrDefault(id, stage.mapId()), 0}; }
        @Override public int mapOf(int id) { return at.getOrDefault(id, stage.mapId()); }
    }

    @Test
    void fiveMembersTakeTheFiveSigilsAndTheFifthHitIsTheClear(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = miniRow(dir, "5").stages().get(0);
        assertEquals("5", stage.clear().equals(),
                "an unquoted YAML 5 is read as the string the script writes, so the comparison can match");
        View v = new View(stage);
        CwkpqSigilSolver solver = new CwkpqSigilSolver();

        List<Integer> ids = new ArrayList<>();
        for (RunMember m : v.members()) {
            ids.add(assertInstanceOf(SolverStep.HitReactor.class, solver.next(v, m)).reactorId());
        }
        assertEquals(CwkpqSigilSolver.SIGILS, ids.subList(0, 5),
                "five of the six cover the five sigils, no two on the same reactor");
        assertEquals(CwkpqSigilSolver.SIGILS.get(0), ids.get(5), "and the sixth wraps round to the first");

        for (int i = 1; i <= 4; i++) {
            v.props.put("glpq4", String.valueOf(i));
            assertFalse(v.cleared(), "after " + i + " hits the stage is still not cleared");
        }
        v.props.put("glpq4", "5");
        assertTrue(v.cleared(), "the fifth hit clears it: the event property is the row's clear");

        for (RunMember m : v.members()) {
            SolverStep step = solver.next(v, m);
            assertFalse(step instanceof SolverStep.HitReactor,
                    "a lit room never hits again: 6109016.js:5 is unguarded, so a sixth hit leaves glpq4 at 6 and a "
                            + "== 5 clear could never match");
            assertTrue(step instanceof SolverStep.Done || step instanceof SolverStep.Wait,
                    "it is done (the clear is already read) or waiting for the orchestrator to advance: " + step);
        }
        // with the clear not yet read, the lit room is a wait rather than a hit
        View lit = new View(stage);
        lit.props.put("glpq4", "7");
        assertInstanceOf(SolverStep.Wait.class, solver.next(lit, lit.members().get(0)),
                "an overshot counter is still no reason to keep hitting");
    }

    @Test
    void aMemberOffTheSignUpMapIsSentBackBeforeAnySigilIsPressed(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = miniRow(dir, "5").stages().get(0);
        View v = new View(stage);
        v.at.put(PARTY[3], 610030100);
        SolverStep.WarpTo w = assertInstanceOf(SolverStep.WarpTo.class,
                new CwkpqSigilSolver().next(v, v.members().get(3)));
        assertEquals(610030400, w.mapId());
    }

    @Test
    void theSameSolverServesTheRelicRoomBecauseTheRowNamesItsReactors(@TempDir Path dir) throws Exception {
        // the Relic of Mastery room: glpq5, reactors 6109005..6109009, also unguarded act() counters to five
        Files.writeString(dir.resolve("index.txt"), "cwk2.yaml\n");
        Files.writeString(dir.resolve("cwk2.yaml"), """
                id: cwk2
                name: The Relic of Mastery
                kind: PQ
                eventScript: CWKPQ
                entryNpc: 9000000
                recruitMap: 610030020
                exitMap: 610030020
                minPlayers: 6
                maxPlayers: 30
                minLevel: 90
                maxLevel: 255
                timeLimitMinutes: 20
                flag: slums.mechanics.cwkpq
                sources:
                  - "scripts/reactor/6109005.js act() counts glpq5 to five; scripts/event/CWKPQ.js:283-288 the 20 minute timer"
                stages:
                  - stage: 1
                    map: 610030500
                    name: The five weapons
                    solver: cwkpq-sigils
                    roles:
                      - id: restorer
                        who: ANY
                        min: 1
                        max: party
                    objectives:
                      - id: weapons
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109005"
                      - id: weapons2
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109006"
                      - id: weapons3
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109007"
                      - id: weapons4
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109008"
                      - id: weapons5
                        kind: HIT_REACTOR
                        role: all
                        reactor: "6109009"
                    clear:
                      eventProperty: glpq5
                      equals: 5
                """);
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertTrue(result.errors().isEmpty(), "the row must read cleanly: " + result.errors());
        MechanicsModel.Stage relic = result.content().get(0).stages().get(0);
        View v = new View(relic);
        assertEquals(List.of(6109005, 6109006, 6109007, 6109008, 6109009), CwkpqSigilSolver.reactors(v),
                "the room's own objectives are the reactor list - no second solver, no constants in Java");

        CwkpqSigilSolver solver = new CwkpqSigilSolver();
        List<Integer> hit = new ArrayList<>();
        for (RunMember m : v.members()) {
            hit.add(assertInstanceOf(SolverStep.HitReactor.class, solver.next(v, m)).reactorId());
        }
        assertEquals(List.of(6109005, 6109006, 6109007, 6109008, 6109009, 6109005), hit,
                "five weapons, one member each, the sixth wrapping round");

        v.props.put("glpq5", "5");
        assertTrue(v.cleared(), "glpq5 == 5 is the room's clear");
        View overshot = new View(relic);
        overshot.props.put("glpq5", "6");
        assertInstanceOf(SolverStep.Wait.class, solver.next(overshot, overshot.members().get(0)),
                "and an overshoot waits instead of hitting a seventh time");
    }

    @Test
    void theSigilAssignmentFollowsThePartysOwnOrder(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = miniRow(dir, "5").stages().get(0);
        View v = new View(stage);
        CwkpqSigilSolver solver = new CwkpqSigilSolver();
        // a party of six over five sigils wraps once, on the highest id
        assertEquals(CwkpqSigilSolver.SIGILS.get(0), solver.sigilFor(v, PARTY[0]));
        assertEquals(CwkpqSigilSolver.SIGILS.get(4), solver.sigilFor(v, PARTY[4]));
        assertEquals(CwkpqSigilSolver.SIGILS.get(0), solver.sigilFor(v, PARTY[5]), "the sixth takes the first sigil");
    }
}
