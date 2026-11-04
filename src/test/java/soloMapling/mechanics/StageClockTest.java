package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

/**
 * A stage's own clock, with the outcome the row declares for it. The scripts that time each room separately restart the
 * event's one timer on entering a room and end the whole event when it runs out: {@code scripts/event/PiratePQ.js:241-268}
 * (six minutes a room, eight for the boss; {@code :223-225} {@code scheduledTimeout} is {@code end(eim)}),
 * {@code scripts/event/CWKPQ.js:262-294} (10/10/10/20/60 minutes, {@code :246-248} the same {@code end(eim)}) and, for a
 * boss, {@code scripts/reactor/2401000.js:33} (Horntail's body room restarts it to an hour). The one script whose timer
 * does not end the event when it fires is Guild Quest's strategy phase ({@code scripts/event/GuildQuest.js:203-222}): its
 * first expiry only closes the join window and starts the real 90-minute clock, so there the expiry is a way <i>on</i>.
 *
 * <p>The row format already had {@code timeLimitMinutes:} on a stage, and the orchestrator honoured it for boss rows only
 * ({@code Orchestrator.budgetFor}); a party quest row that wrote it - PiratePQ's comment says the six minutes exist -
 * had it read and ignored, so the room's clock never ran. {@code onTimeLimit: end | advance} is the declared outcome,
 * and every kind of row now holds a stage that declares a clock to it.
 */
class StageClockTest {

    private static final long MIN = 60_000L;
    private static final long START = 1_000_000L;

    /** One stage of a test row: its own fields, then {@code tail} (each line indented to the stage's own level). */
    private static String stage(int n, String tail) {
        String[] clearProperty = {"", "one", "two", "three"};
        StringBuilder sb = new StringBuilder();
        sb.append("  - stage: ").append(n).append('\n');
        sb.append("    map: ").append(970030000 + 100 * n).append('\n');
        sb.append("    name: Room ").append(n).append('\n');
        sb.append("    solver: hold\n");
        sb.append("    objectives:\n      - id: wait\n        kind: TALK_NPC\n        npc: 9000037\n");
        sb.append("    clear:\n      eventProperty: ").append(clearProperty[n]).append('\n');
        for (String line : tail.split("\n")) {
            if (!line.isBlank()) {
                sb.append("    ").append(line.strip()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String yaml(String kind, String... stages) {
        StringBuilder sb = new StringBuilder("""
                id: clock
                name: Rooms with clocks
                kind: %s
                eventScript: ClockEvent
                recruitMap: 970030000
                exitMap: 970030000
                minPlayers: 1
                maxPlayers: 6
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 40
                sources:
                  - "scripts/event/PiratePQ.js:241-268 and scripts/event/CWKPQ.js:262-294 (a clock per room)"
                stages:
                """.formatted(kind));
        for (String s : stages) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static StageData.Result load(Path dir, String yaml) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "clock.yaml\n");
        Files.writeString(dir.resolve("clock.yaml"), yaml);
        return StageData.loadIndexed(dir + "/", Path::of);
    }

    /** A three-room row of {@code kind}; rooms one and two carry the given tails, room three none. */
    private static Content row(Path dir, String kind, String stageOneTail, String stageTwoTail) throws Exception {
        StageData.Result result = load(dir, yaml(kind, stage(1, stageOneTail), stage(2, stageTwoTail), stage(3, "")));
        assertTrue(result.errors().isEmpty(), "the row must read cleanly: " + result.errors());
        return result.content().get(0);
    }

    private static List<String> errorsOf(Path dir, String kind, String stageOneTail) throws Exception {
        return load(dir, yaml(kind, stage(1, stageOneTail), stage(2, ""))).errors();
    }

    private static Orchestrator orchestrator() {
        return new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
    }

    private static RunState start(Orchestrator o, Content c) {
        RunState run = o.start(c, Tier.NORMAL, List.of(new RunMember(1, true, true, MechanicsPersona.PERFECT)));
        run.markStarted(START);
        return run;
    }

    // ---- who honours a stage's clock -----------------------------------------------------------------

    @Test
    void aPartyQuestStageThatDeclaresAClockIsHeldToItNotToTheRowsOne(@TempDir Path dir) throws Exception {
        Content c = row(dir, "PQ", "timeLimitMinutes: 6", "");
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);

        o.tick(run, world, START + 5 * MIN + 59_000);
        assertFalse(run.ended(), "inside the room's six minutes: " + run.endedBecause());

        o.tick(run, world, START + 6 * MIN + 1_000);
        assertTrue(run.ended(), "six minutes in the first room is the end, not the row's forty");
        assertTrue(run.endedBecause().contains("time budget of 6 min"), run.endedBecause());
        assertTrue(run.endedBecause().contains("stage 1"), "the reason names the room: " + run.endedBecause());
    }

    @Test
    void aStageThatDeclaresNoClockKeepsTheRowsWholeRunClock(@TempDir Path dir) throws Exception {
        Content c = row(dir, "PQ", "timeLimitMinutes: 6", "");
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);
        run.setStage(2, START + 5 * MIN); // reached the second room, which names no clock of its own

        o.tick(run, world, START + 39 * MIN);
        assertFalse(run.ended(), "stage 2 carries no clock: the row's forty minutes from the start apply: "
                + run.endedBecause());
        o.tick(run, world, START + 41 * MIN);
        assertTrue(run.ended());
        assertTrue(run.endedBecause().contains("time budget of 40 min"), run.endedBecause());
    }

    @Test
    void theRowsOwnClockStillEndsAPartyQuestWhoseRoomClockIsLonger(@TempDir Path dir) throws Exception {
        Content c = row(dir, "PQ", "timeLimitMinutes: 50", "");
        Orchestrator o = orchestrator();
        RunState run = start(o, c);

        o.tick(run, new World(run), START + 41 * MIN);

        assertTrue(run.ended(), "a room's clock is a tighter limit inside the run's, never a looser one");
        assertTrue(run.endedBecause().contains("time budget of 40 min"), run.endedBecause());
    }

    @Test
    void aClearBeatsTheClockOnTheSameTick(@TempDir Path dir) throws Exception {
        Content c = row(dir, "PQ", "timeLimitMinutes: 6", "");
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);
        world.properties.put("one", "1");

        o.tick(run, world, START + 7 * MIN);

        assertFalse(run.ended(), run.endedBecause());
        assertEquals(2, run.currentStage(), "the room was cleared, so the run goes on as it always did");
    }

    // ---- the declared outcome ------------------------------------------------------------------------

    @Test
    void aRoomThatDeclaresAdvanceSendsTheRunOnWhenItsClockRunsOutAndSaysSo(@TempDir Path dir) throws Exception {
        Content c = row(dir, "PQ", "timeLimitMinutes: 6\nonTimeLimit: advance", "timeLimitMinutes: 6");
        assertEquals(MechanicsModel.OnTimeLimit.ADVANCE, c.stages().get(0).onTimeLimit());
        assertEquals(MechanicsModel.OnTimeLimit.END, c.stages().get(1).onTimeLimit(), "end is the default");
        Orchestrator o = orchestrator();
        RunState run = start(o, c);
        World world = new World(run);

        o.tick(run, world, START + 6 * MIN + 1_000);

        assertFalse(run.ended(), "the row says this room's clock is a way on: " + run.endedBecause());
        assertEquals(2, run.currentStage());
        assertFalse(run.cleared(), "advancing is not clearing");
        assertTrue(run.events().stream().anyMatch(e -> e.line().contains("stage 1")
                        && e.line().contains("6 min") && e.line().contains("advance")),
                "the history says why the run left stage 1: " + run.events());

        // the next room's clock starts on arrival, not at the run's start
        o.tick(run, world, START + 11 * MIN);
        assertFalse(run.ended(), "five minutes into stage 2: " + run.endedBecause());
        o.tick(run, world, START + 12 * MIN + 2_000);
        assertTrue(run.ended(), "six minutes after arriving");
        assertTrue(run.endedBecause().contains("stage 2"), run.endedBecause());
    }

    @Test
    void aRoomThatDeclaresEndEndsTheRunWithTheReasonInTheHistory(@TempDir Path dir) throws Exception {
        Content c = row(dir, "PQ", "timeLimitMinutes: 6\nonTimeLimit: end", "");
        Orchestrator o = orchestrator();
        RunState run = start(o, c);

        o.tick(run, new World(run), START + 7 * MIN);

        assertTrue(run.ended());
        assertTrue(run.events().stream().anyMatch(e -> e.line().contains("abort") && e.line().contains("stage 1")),
                "the run's history carries the reason too: " + run.events());
    }

    @Test
    void aBossRowKeepsExactlyWhatItDid(@TempDir Path dir) throws Exception {
        Content c = row(dir, "BOSS", "timeLimitMinutes: 6", "");
        Orchestrator o = orchestrator();
        RunState run = start(o, c);

        o.tick(run, new World(run), START + 5 * MIN);
        assertFalse(run.ended());
        o.tick(run, new World(run), START + 7 * MIN);
        assertTrue(run.ended());
        assertTrue(run.endedBecause().contains("time budget of 6 min"), run.endedBecause());
    }

    // ---- the row format ------------------------------------------------------------------------------

    @Test
    void theOutcomeParsesAnyCaseAndDefaultsToEnd(@TempDir Path dir) throws Exception {
        assertEquals(MechanicsModel.OnTimeLimit.ADVANCE,
                row(dir, "PQ", "timeLimitMinutes: 6\nonTimeLimit: Advance", "").stages().get(0).onTimeLimit());
        Path other = dir.resolve("b");
        Files.createDirectories(other);
        assertEquals(MechanicsModel.OnTimeLimit.END, row(other, "PQ", "timeLimitMinutes: 6", "").stages().get(0).onTimeLimit());
    }

    @Test
    void anOutcomeThatMeansNothingIsRefusedAtLoadNotAtRunTime(@TempDir Path dir) throws Exception {
        List<String> unknown = errorsOf(dir, "PQ", "timeLimitMinutes: 6\nonTimeLimit: sideways");
        assertTrue(unknown.stream().anyMatch(e -> e.contains("onTimeLimit") && e.contains("sideways")), unknown.toString());

        Path b = dir.resolve("b");
        Files.createDirectories(b);
        List<String> noClock = errorsOf(b, "PQ", "onTimeLimit: advance");
        assertTrue(noClock.stream().anyMatch(e -> e.contains("onTimeLimit") && e.contains("timeLimitMinutes")),
                "an outcome without a clock is a row that thinks it has one: " + noClock);

        Path c = dir.resolve("c");
        Files.createDirectories(c);
        List<String> looping = errorsOf(c, "PQ", "timeLimitMinutes: 6\nonTimeLimit: advance\nloopTo: 1");
        assertTrue(looping.stream().anyMatch(e -> e.contains("onTimeLimit") && e.contains("loopTo")), looping.toString());
    }

    @Test
    void advancingPastTheLastStageIsRefusedAtLoad(@TempDir Path dir) throws Exception {
        List<String> errors = load(dir, yaml("PQ", stage(1, "timeLimitMinutes: 6\nonTimeLimit: advance"))).errors();

        assertTrue(errors.stream().anyMatch(e -> e.contains("stage 1") && e.contains("onTimeLimit")
                && e.contains("last")), "there is no next room to go on to: " + errors);
    }

    /** The instance as the orchestrator sees it: the run's current stage, its properties, nobody lost. */
    private static final class World implements StageView {
        private final RunState run;
        private final List<RunMember> members;
        final Map<String, String> properties = new ConcurrentHashMap<>();
        private final Map<String, Object> memory = new ConcurrentHashMap<>();

        World(RunState run) {
            this.run = run;
            this.members = run.members();
        }

        @Override public Stage stage() { return run.stageData(); }
        @Override public List<RunMember> members() { return members; }
        @Override public String roleOf(int id) { return "any"; }
        @Override public OptionalInt answerFor(int id) { return OptionalInt.empty(); }
        @Override public int itemCount(int id, int item) { return 0; }
        @Override public Optional<String> eventProperty(String key) { return Optional.ofNullable(properties.get(key)); }
        @Override public int[] positionOf(int id) { return new int[]{0, 0}; }
        @Override public int mapOf(int id) { return run.stageData().mapId(); }
        @Override public Map<String, Object> stageMemory() { return memory; }
    }
}
