package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.mechanics.MechanicsModel.Branch;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

/**
 * A stage whose successor depends on what the room says. Every stage so far went to index + 1; two real scripts do not.
 *
 * <ul>
 *   <li>Pre-Horntail PQ's last-but-one room reads a reactor: {@code scripts/portal/hontale_C.js:30-41} sends the team to
 *   {@code 240050300} when the reactor named {@code light} is at state 1 and to {@code 240050310} when it is at state
 *   3 ("Hit the Lightbulb to determine your fate!"). Two rooms, one of which the party never sees.</li>
 *   <li>Its fifth door goes <i>back</i>: {@code scripts/portal/hontale_Bopen.js:96-124} takes the party from the fifth
 *   mine to the hub {@code 240050100} again, so a room is entered twice.</li>
 * </ul>
 *
 * <p>The row fact is a short list on the stage, {@code branches:}, each entry a {@code when:} (an event property, present
 * or equal to a value, or a reactor at an exact state) and a {@code goto:} (a stage index, or {@code end}: the row is
 * finished here). The first entry whose {@code when:} holds when the stage clears wins; an entry with no {@code when:}
 * always holds; none holding means index + 1, as before. It reads state a row can already read; it interprets no script.
 */
class BranchStageTest {

    private static final long START = 1_000_000L;

    private static String stage(int n, String tail) {
        String[] clearProperty = {"", "one", "two", "three", "four"};
        StringBuilder sb = new StringBuilder();
        sb.append("  - stage: ").append(n).append('\n');
        sb.append("    map: ").append(970030000 + 100 * n).append('\n');
        sb.append("    name: Room ").append(n).append('\n');
        sb.append("    solver: hold\n");
        sb.append("    objectives:\n      - id: wait\n        kind: TALK_NPC\n        npc: 9000037\n");
        sb.append("    clear:\n      eventProperty: ").append(clearProperty[n]).append('\n');
        for (String line : tail.split("\n")) {
            if (!line.isBlank()) {
                sb.append("    ").append(line, indentOf(line), line.length()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Tails are written with their own relative indent; the stage's four spaces are added in front. */
    private static int indentOf(String line) {
        return 0;
    }

    private static String yaml(String... stages) {
        StringBuilder sb = new StringBuilder("""
                id: branchy
                name: A row that forks
                kind: PQ
                eventScript: BranchEvent
                recruitMap: 970030000
                exitMap: 970030000
                minPlayers: 1
                maxPlayers: 6
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 40
                sources:
                  - "scripts/portal/hontale_C.js:30-41 and scripts/portal/hontale_Bopen.js:96-124"
                stages:
                """);
        for (String s : stages) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static StageData.Result load(Path dir, String yaml) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "branchy.yaml\n");
        Files.writeString(dir.resolve("branchy.yaml"), yaml);
        return StageData.loadIndexed(dir + "/", Path::of);
    }

    private static Content content(Path dir, String... stages) throws Exception {
        StageData.Result r = load(dir, yaml(stages));
        assertTrue(r.errors().isEmpty(), "the row must read cleanly: " + r.errors());
        return r.content().get(0);
    }

    /** Four rooms; room one forks to room three when {@code path} is "b" and otherwise goes to room two. */
    private static Content forkOnProperty(Path dir) throws Exception {
        return content(dir,
                stage(1, "branches:\n  - when:\n      eventProperty: path\n      equals: b\n    goto: 3"),
                stage(2, "branches:\n  - goto: end"),
                stage(3, ""),
                stage(4, ""));
    }

    private static Orchestrator orchestrator() {
        return new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
    }

    private static RunState start(Orchestrator o, Content c) {
        RunState run = o.start(c, Tier.NORMAL, List.of(new RunMember(1, true, true, MechanicsPersona.PERFECT)));
        run.markStarted(START);
        return run;
    }

    /** The rooms a run visits, in order, while each room clears on the tick after the run arrives in it. */
    private static List<Integer> play(Orchestrator o, RunState run, World world, int ticks) {
        List<Integer> order = new ArrayList<>();
        int last = -1;
        long now = START;
        for (int i = 0; i < ticks && !run.ended(); i++) {
            if (run.currentStage() != last) {
                last = run.currentStage();
                order.add(last);
            } else {
                world.properties.put(clearProperty(last), "1");
            }
            o.tick(run, world, now += 1_000);
        }
        return order;
    }

    private static String clearProperty(int stage) {
        return new String[]{"", "one", "two", "three", "four"}[stage];
    }

    // ---- what a branch does --------------------------------------------------------------------------

    @Test
    void aRoomWhoseStateSaysSoSendsTheRunPastItsNeighbour(@TempDir Path dir) throws Exception {
        Content c = forkOnProperty(dir);
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);
        world.properties.put("path", "b");

        List<Integer> order = play(o, run, world, 50);

        assertEquals(List.of(1, 3, 4), order.subList(0, 3), "room two was skipped: the room said 'b'");
        assertTrue(run.events().stream().anyMatch(e -> e.line().contains("stage 1") && e.line().contains("path")
                && e.line().contains("stage 3")), "the history says why: " + run.events());
    }

    @Test
    void aRoomWhoseStateSaysNothingGoesToTheNextOneAsEveryRoomDid(@TempDir Path dir) throws Exception {
        Content c = forkOnProperty(dir);
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);
        world.properties.put("path", "a"); // present, but not the value the branch wants

        List<Integer> order = play(o, run, world, 50);

        assertEquals(List.of(1, 2), order.subList(0, 2), "no branch held: index + 1");
    }

    @Test
    void anEndBranchFinishesTheRowHereWithAClear(@TempDir Path dir) throws Exception {
        Content c = forkOnProperty(dir);
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);

        List<Integer> order = play(o, run, world, 50);

        assertEquals(List.of(1, 2), order, "room two says 'goto: end', so rooms three and four are never played");
        assertTrue(run.cleared(), "the row ended where its own branch said it ends, and that is a clear");
        assertEquals("cleared", run.endedBecause());
    }

    @Test
    void aReactorAtAnExactStatePicksTheRoom(@TempDir Path dir) throws Exception {
        // pre-Horntail's lightbulb: state 1 is the light cave, state 3 the dark one (hontale_C.js:30-41)
        Content c = content(dir,
                stage(1, "branches:\n  - when:\n      reactor: light\n      state: 1\n    goto: 2\n"
                        + "  - when:\n      reactor: light\n      state: 3\n    goto: 3"),
                stage(2, "branches:\n  - goto: end"),
                stage(3, ""));
        for (int state : new int[]{1, 3}) {
            Orchestrator o = orchestrator();
            RunState run = start(o, c);
            World world = new World(run);
            world.reactors.put("light", state);

            List<Integer> order = play(o, run, world, 50);

            assertEquals(state == 1 ? List.of(1, 2) : List.of(1, 3), order.subList(0, 2), "light at state " + state);
        }
    }

    @Test
    void aReactorByIdIsReadByIdAndAMissingOneMatchesNothing(@TempDir Path dir) throws Exception {
        Content c = content(dir,
                stage(1, "branches:\n  - when:\n      reactor: 2408001\n      state: 3\n    goto: 3"),
                stage(2, ""),
                stage(3, ""));
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);
        world.reactorsById.put(2408001, 1); // standing, but not at the wanted state

        assertEquals(List.of(1, 2), play(o, run, world, 20).subList(0, 2));

        RunState again = start(o, c); // no such reactor on the map at all (the view answers -1)
        assertEquals(List.of(1, 2), play(o, again, new World(again), 20).subList(0, 2));
    }

    @Test
    void aBranchCanGoBackToARoomTheRunAlreadyPlayed(@TempDir Path dir) throws Exception {
        // the fifth mine's door leads back to the hub (hontale_Bopen.js:96-124): the room is entered twice
        Content c = content(dir,
                stage(1, ""),
                stage(2, "branches:\n  - when:\n      eventProperty: again\n      equals: 1\n    goto: 1"),
                stage(3, ""));
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);
        world.properties.put("again", "1");

        // stage 1's own clear property stays set, so the run bounces between 1 and 2 until the room stops saying
        // "again": the clock is what stops a row that never does, exactly as it stops loopTo
        List<Integer> order = new ArrayList<>();
        int last = -1;
        long now = START;
        for (int i = 0; i < 12 && !run.ended(); i++) {
            if (run.currentStage() != last) {
                last = run.currentStage();
                order.add(last);
                if (order.size() == 4) {
                    world.properties.remove("again");
                }
            } else {
                world.properties.put(clearProperty(last), "1");
            }
            o.tick(run, world, now += 1_000);
        }

        assertEquals(List.of(1, 2, 1, 2, 3), order.subList(0, 5), "back to the room it came from, then on once the room agrees");
    }

    @Test
    void theFirstBranchThatHoldsWinsAndAnUnconditionalOneAlwaysHolds(@TempDir Path dir) throws Exception {
        Content c = content(dir,
                stage(1, "branches:\n  - when:\n      eventProperty: x\n      equals: 1\n    goto: 3\n"
                        + "  - when:\n      eventProperty: x\n    goto: 4\n  - goto: 2"),
                stage(2, ""), stage(3, ""), stage(4, ""));

        for (Object[] row : new Object[][]{{"1", 3}, {"7", 4}, {null, 2}}) {
            Orchestrator o = orchestrator();
            RunState run = start(o, c);
            World world = new World(run);
            if (row[0] != null) {
                world.properties.put("x", (String) row[0]);
            }
            assertEquals(row[1], play(o, run, world, 20).get(1), "x = " + row[0]);
        }
    }

    @Test
    void aBranchPastTheLastPlayableStageEndsTheRowInsteadOfStrandingIt(@TempDir Path dir) throws Exception {
        Content c = forkOnProperty(dir);
        Orchestrator capped = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 2); // stages 1-2 only
        RunState run = start(capped, c);
        World world = new World(run);
        world.properties.put("path", "b"); // wants stage 3, which this run does not play

        List<Integer> order = play(capped, run, world, 30);

        assertEquals(List.of(1), order, "stage 3 is beyond the last stage this run plays");
        assertTrue(run.cleared(), "reaching the end of what it plays is the end of the row, as it always was");
    }

    @Test
    void aRowWithoutBranchesIsUnchanged(@TempDir Path dir) throws Exception {
        Content c = content(dir, stage(1, ""), stage(2, ""), stage(3, ""));
        assertTrue(c.stages().stream().allMatch(s -> s.branches().isEmpty()));
        Orchestrator o = orchestrator();
        RunState run = start(o, c);

        assertEquals(List.of(1, 2, 3), play(o, run, new World(run), 50));
        assertTrue(run.cleared());
    }

    // ---- the row format ------------------------------------------------------------------------------

    @Test
    void theBranchesParseIntoFacts(@TempDir Path dir) throws Exception {
        Content c = forkOnProperty(dir);

        List<Branch> one = c.stages().get(0).branches();
        assertEquals(1, one.size());
        assertEquals("path", one.get(0).eventProperty());
        assertEquals("b", one.get(0).equals());
        assertEquals(3, one.get(0).stage());
        assertFalse(one.get(0).end());
        assertTrue(c.stages().get(1).branches().get(0).end(), "goto: end");
        assertTrue(c.stages().get(1).branches().get(0).unconditional());
    }

    @Test
    void aBranchThatCannotWorkIsRefusedAtLoad(@TempDir Path dir) throws Exception {
        String[][] bad = {
                {"branches:\n  - when:\n      eventProperty: p\n    goto: 9", "goto", "9"},
                {"branches:\n  - when:\n      eventProperty: p\n    goto: 1", "goto", "itself"},
                {"branches:\n  - when:\n      eventProperty: p\n    goto: sideways", "goto", "sideways"},
                {"branches:\n  - when:\n      eventProperty: p\n", "goto", ""},
                {"branches:\n  - when:\n      state: 1\n    goto: 2", "when", ""},
                {"branches:\n  - when:\n      reactor: light\n    goto: 2", "state", ""},
                {"branches:\n  - when:\n      reactor: light\n      state: -1\n    goto: 2", "state", ""},
                {"branches:\n  - when:\n      eventProperty: p\n      reactor: light\n      state: 1\n    goto: 2", "when", "one"},
                {"branches:\n  - goto: 2\n  - when:\n      eventProperty: p\n    goto: 2", "unreachable", ""},
        };
        for (int i = 0; i < bad.length; i++) {
            Path d = dir.resolve("r" + i);
            Files.createDirectories(d);
            List<String> errors = load(d, yaml(stage(1, bad[i][0]), stage(2, ""))).errors();
            assertFalse(errors.isEmpty(), "case " + i + " must be refused: " + bad[i][0]);
            String mustName = bad[i][1];
            String mustAlsoName = bad[i][2];
            assertTrue(errors.stream().anyMatch(e -> e.contains("stage 1") && e.contains(mustName)
                    && e.contains(mustAlsoName)), "case " + i + ": " + errors);
        }
    }

    /** The instance as the orchestrator sees it: the run's current stage, its properties and its reactors. */
    private static final class World implements StageView {
        private final RunState run;
        final Map<String, String> properties = new ConcurrentHashMap<>();
        final Map<String, Integer> reactors = new ConcurrentHashMap<>();
        final Map<Integer, Integer> reactorsById = new ConcurrentHashMap<>();
        private final Map<String, Object> memory = new ConcurrentHashMap<>();

        World(RunState run) {
            this.run = run;
        }

        @Override public Stage stage() { return run.stageData(); }
        @Override public List<RunMember> members() { return run.members(); }
        @Override public String roleOf(int id) { return "any"; }
        @Override public OptionalInt answerFor(int id) { return OptionalInt.empty(); }
        @Override public int itemCount(int id, int item) { return 0; }
        @Override public Optional<String> eventProperty(String key) { return Optional.ofNullable(properties.get(key)); }
        @Override public int[] positionOf(int id) { return new int[]{0, 0}; }
        @Override public int mapOf(int id) { return run.stageData().mapId(); }
        @Override public Map<String, Object> stageMemory() { return memory; }
        @Override public int reactorState(String name) { return reactors.getOrDefault(name, -1); }
        @Override public int reactorState(int reactorId) { return reactorsById.getOrDefault(reactorId, -1); }
    }
}
