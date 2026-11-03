package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scripting.event.EventInstanceManager;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Seat;
import soloMapling.mechanics.MechanicsModel.Tier;

/**
 * A party-composition fact: Guild Quest's four-room maze ends in a crown that only a member of level 30 or below can
 * fetch - {@code scripts/portal/under30gate.js:25} refuses everyone else at the door of {@code 990000640}, and the crown
 * is the fourth piece Sharen III's body needs ({@code scripts/reactor/9201001.js}, {@code scripts/npc/9040003.js}), so
 * a party of only high-level members, bots included, can never clear stage 4. That is a fact about the party, like
 * {@code minPlayers}, not a solver trick: the row says it ({@code composition:}), the run refuses to start without it,
 * and nothing makes a high-level member stand in for the missing one.
 */
class CompositionSeatTest {

    @AfterEach
    void cleanUp() {
        MechanicsRuns.get().stopAll("test over");
    }

    private static String yaml(String compositionBlock) {
        return """
                id: seated
                name: A row that wants a junior
                kind: PQ
                eventScript: SeatedEvent
                recruitMap: 101030104
                exitMap: 990001100
                minPlayers: 3
                maxPlayers: 6
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 40
                sources:
                  - "scripts/portal/under30gate.js:25"
                %sstages:
                  - stage: 1
                    map: 990000600
                    name: The maze
                    solver: hold
                    objectives:
                      - id: wait
                        kind: TALK_NPC
                        npc: 9040003
                    clear:
                      eventProperty: stage4clear
                      equals: "true"
                """.formatted(compositionBlock);
    }

    private static StageData.Result load(Path dir, String composition) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "seated.yaml\n");
        Files.writeString(dir.resolve("seated.yaml"), yaml(composition));
        return StageData.loadIndexed(dir + "/", Path::of);
    }

    private static final String JUNIOR = """
            composition:
              - id: junior
                minLevel: 10
                maxLevel: 30
                min: 1
                note: under30gate.js:25, and the earring needs level 10
            """;

    private static Content seated(Path dir) throws Exception {
        StageData.Result r = load(dir, JUNIOR);
        assertTrue(r.errors().isEmpty(), "the row must read cleanly: " + r.errors());
        return r.content().get(0);
    }

    private static List<RunMember> party(int... levels) {
        List<RunMember> out = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            out.add(new RunMember(10 + i, i == 0, true, MechanicsPersona.PERFECT, 110, levels[i]));
        }
        return out;
    }

    // ---- the fact -----------------------------------------------------------------------------------

    @Test
    void theSeatIsARowFactWithItsLevelBand(@TempDir Path dir) throws Exception {
        Content c = seated(dir);

        assertEquals(1, c.composition().size());
        Seat junior = c.composition().get(0);
        assertEquals("junior", junior.id());
        assertEquals(10, junior.minLevel());
        assertEquals(30, junior.maxLevel());
        assertEquals(1, junior.min());
        assertTrue(junior.fits(10) && junior.fits(30) && junior.fits(20));
        assertFalse(junior.fits(9) || junior.fits(31) || junior.fits(0), "level 0 means 'not carried' and fits nothing");
        assertEquals("level 10-30", junior.describeLevels());
    }

    @Test
    void aRowThatAsksNothingOfThePartyHasNoSeats(@TempDir Path dir) throws Exception {
        StageData.Result r = load(dir, "");
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertTrue(r.content().get(0).composition().isEmpty());
        assertNull(r.content().get(0).compositionProblem(List.of(55, 58, 60)), "every party fills an empty list of seats");
    }

    @Test
    void theProblemNamesTheSeatTheBandAndWhoIsThere(@TempDir Path dir) throws Exception {
        Content c = seated(dir);

        String why = c.compositionProblem(List.of(55, 58, 60));

        assertTrue(why.contains("junior") && why.contains("level 10-30") && why.contains("55, 58, 60"), why);
        assertNull(c.compositionProblem(List.of(55, 58, 25)));
        assertNull(c.compositionProblem(List.of(30)), "level 30 passes under30gate.js:25 (<= 30)");
        assertTrue(c.compositionProblem(List.of(31, 9)).contains("junior"), "31 is over and 9 cannot wear the earring");
        assertTrue(c.compositionProblem(List.of(0, 0, 0)).contains("junior"), "levels not carried fill nothing");
    }

    @Test
    void aSeatThatCouldNeverBeFilledIsRefusedAtLoad(@TempDir Path dir) throws Exception {
        String[][] bad = {
                {"composition:\n  - id: a\n    min: 0\n", "min must be 1 or more"},
                {"composition:\n  - id: a\n    minLevel: 40\n    maxLevel: 30\n", "levels"},
                {"composition:\n  - id: a\n    minLevel: 300\n", "no level the row admits"},
                {"composition:\n  - id: a\n  - id: a\n", "duplicate seat"},
                {"composition:\n  - id: a\n    min: 7\n", "more than maxPlayers"},
                {"composition: junior\n", "not a list"},
        };
        for (int i = 0; i < bad.length; i++) {
            Path d = dir.resolve("r" + i);
            Files.createDirectories(d);
            List<String> errors = load(d, bad[i][0]).errors();
            String must = bad[i][1];
            assertTrue(errors.stream().anyMatch(e -> e.contains(must)), "case " + i + " (" + must + "): " + errors);
        }
    }

    // ---- the run -------------------------------------------------------------------------------------

    @Test
    void aPartyOfOnlyHighLevelMembersDoesNotStart(@TempDir Path dir) throws Exception {
        Content c = seated(dir);
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);

        RunState run = o.start(c, Tier.NORMAL, party(55, 58, 60));

        assertTrue(run.ended(), "no junior, no run: nothing stands a high-level member in for one");
        assertTrue(run.endedBecause().contains("seat \"junior\""), run.endedBecause());
        assertTrue(run.endedBecause().contains("55, 58, 60"), "it says who was there: " + run.endedBecause());
    }

    @Test
    void aPartyWithAJuniorStartsAndTheJuniorIsNotSpecial(@TempDir Path dir) throws Exception {
        Content c = seated(dir);
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);

        RunState run = o.start(c, Tier.NORMAL, party(55, 58, 20));

        assertFalse(run.ended(), String.valueOf(run.endedBecause()));
    }

    @Test
    void membersWhoseLevelsWereNotCarriedFillNoSeat(@TempDir Path dir) throws Exception {
        Content c = seated(dir);
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        List<RunMember> uncounted = List.of(
                new RunMember(1, true, true, MechanicsPersona.PERFECT),
                new RunMember(2, false, true, MechanicsPersona.PERFECT),
                new RunMember(3, false, true, MechanicsPersona.PERFECT));

        assertTrue(o.start(c, Tier.NORMAL, uncounted).ended(), "an unknown level is not taken for a junior");
    }

    @Test
    void theLiveRunReadsEachMembersRealLevel(@TempDir Path dir) throws Exception {
        Content c = seated(dir);
        EventInstanceManager high = instanceOf(1, 55, 58, 60);
        EventInstanceManager withJunior = instanceOf(11, 55, 58, 22);

        MechanicsRuns.Active refused = MechanicsRuns.get().start(high, c, true, 0, 1, 0, false, line -> { }, null);
        MechanicsRuns.Active allowed = MechanicsRuns.get().start(withJunior, c, true, 0, 1, 0, false, line -> { }, null);

        assertTrue(refused.run().ended());
        assertTrue(refused.run().endedBecause().contains("seat \"junior\""), refused.run().endedBecause());
        assertFalse(allowed.run().ended(), String.valueOf(allowed.run().endedBecause()));
    }

    private static EventInstanceManager instanceOf(int firstId, int... levels) {
        List<Character> players = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            Character chr = mock(Character.class);
            when(chr.getId()).thenReturn(firstId + i);
            when(chr.isBot()).thenReturn(true);
            when(chr.getName()).thenReturn("Guild" + (firstId + i));
            when(chr.getLevel()).thenReturn(levels[i]);
            players.add(chr);
        }
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getPlayers()).thenReturn(players);
        when(eim.getLeaderId()).thenReturn(firstId);
        when(eim.getName()).thenReturn("Guild" + firstId);
        return eim;
    }
}
