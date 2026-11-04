package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotRunOutcome;
import soloMapling.ArtificialPlayer.BotRunOutcome.Pool;
import soloMapling.ArtificialPlayer.BotRunOutcome.Slot;
import soloMapling.ArtificialPlayer.BotSM;

/**
 * 1.6.4 run chatter on the live side: {@link MechanicsRuns} ends a run and leaves the line on the bots' own slots; it
 * says nothing itself. Mocked instances and brains, as {@link MechanicsRunsInstancesTest}.
 */
class RunChatterRunsTest {

    final List<String> lines = new CopyOnWriteArrayList<>();
    final Map<Integer, Slot> slots = new java.util.HashMap<>();

    @AfterEach
    void cleanUp() {
        MechanicsRuns.get().stopAll("test over");
        for (int id : slots.keySet()) {
            CharacterStorage.removeActiveBot(id);
        }
        System.clearProperty(Mechanics.CHATTER_FLAG);
    }

    /** The population registry's brain for a character, with a real slot (a mocked brain would forget what is set). */
    private Slot brainFor(int id) {
        BotSM brain = mock(BotSM.class);
        Slot slot = new Slot();
        when(brain.runOutcome()).thenReturn(slot);
        CharacterStorage.addActiveBot(id, brain);
        slots.put(id, slot);
        return slot;
    }

    private List<Character> botsWithBrains(int from) {
        List<Character> out = MechanicsRunsInstancesTest.bots(from);
        for (Character c : out) {
            brainFor(c.getId());
        }
        return out;
    }

    private MechanicsRuns.Active run(List<Character> players, int leader) {
        EventInstanceManager eim = MechanicsRunsInstancesTest.kpqOn(1, players, leader);
        MechanicsRuns.Active a = MechanicsRuns.get().start(eim, KpqStageDataTest.kpq(), true, 0, 1, 0, true, lines::add, null);
        assertTrue(!a.run().ended(), lines.toString());
        return a;
    }

    private Set<Integer> speakers(int from) {
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            if (slots.get(from + i).get() != null) {
                out.add(from + i);
            }
        }
        return out;
    }

    @Test
    void aClearedRunLeavesAWinLineOnTheLeaderAndOneOther() {
        MechanicsRuns.Active a = run(botsWithBrains(7_100), 7_100);
        a.run().markCleared(60_000);

        MechanicsRuns.get().stop(a, "cleared");

        Set<Integer> who = speakers(7_100);
        assertEquals(2, who.size(), who.toString());
        assertTrue(who.contains(7_100), "the leader speaks: " + who);
        Set<String> wins = new HashSet<>(BotRunOutcome.lines(Pool.WIN_PQ, "Kerning PQ", 99, new java.util.Random(0)));
        for (int id : who) {
            assertTrue(wins.contains(slots.get(id).get().line()), slots.get(id).get().line());
        }
    }

    @Test
    void aFailedRunLeavesALossLineAndNothingIsSaidByTheRunItself() {
        MechanicsRuns.Active a = run(botsWithBrains(7_200), 7_200);
        a.run().fail("time budget of 30 min ran out on stage 2");

        MechanicsRuns.get().stop(a, "after the failure");

        Set<String> losses = new HashSet<>(BotRunOutcome.lines(Pool.LOSS_PQ, "Kerning PQ", 99, new java.util.Random(0)));
        Set<Integer> who = speakers(7_200);
        assertEquals(2, who.size());
        for (int id : who) {
            assertTrue(losses.contains(slots.get(id).get().line()), slots.get(id).get().line());
        }
    }

    @Test
    void aRunStoppedByAGmOrDisbandedByAHumanLeavesNothing() {
        MechanicsRuns.Active a = run(botsWithBrains(7_300), 7_300);
        MechanicsRuns.get().stop(a, "disposed by Zak");
        assertTrue(speakers(7_300).isEmpty());

        MechanicsRuns.Active b = run(botsWithBrains(7_400), 7_400);
        MechanicsRuns.get().stop(b, "the leader left (no longer in the stage)");
        assertTrue(speakers(7_400).isEmpty());
    }

    @Test
    void withTheSwitchOffNothingIsRecordedAtAll() {
        System.setProperty(Mechanics.CHATTER_FLAG, "false");
        MechanicsRuns.Active a = run(botsWithBrains(7_500), 7_500);
        a.run().markCleared(60_000);
        MechanicsRuns.get().stop(a, "cleared");

        assertTrue(speakers(7_500).isEmpty());
        assertTrue(lines.stream().noneMatch(l -> l.contains("chatter")), "and the run's own report is what it always was");
    }

    @Test
    void anOwnedAltNeverSpeaksEvenWithAPlaceInTheRegistry() {
        List<Character> party = new ArrayList<>(botsWithBrains(7_600).subList(0, 3)); // KPQ takes four: three bots and the alt
        Character alt = MechanicsRunsInstancesTest.member(7_650, false);
        when(alt.isOwnedAlt()).thenReturn(true);
        party.add(alt);
        Slot altSlot = brainFor(7_650);

        MechanicsRuns.Active a = run(party, 7_600);
        a.run().markCleared(60_000);
        MechanicsRuns.get().stop(a, "cleared");

        assertNull(altSlot.get(), "an alt's voice is its owner's party chat, never a public line");
        assertEquals(2, speakers(7_600).size(), "the bots still speak");
    }

    @Test
    void aBotInTwoRunsInARowIsGivenALineForEach() {
        List<Character> party = botsWithBrains(7_700);
        MechanicsRuns.Active first = run(party, 7_700);
        first.run().markCleared(60_000);
        MechanicsRuns.get().stop(first, "cleared");
        Slot leader = slots.get(7_700);
        BotRunOutcome.Pending one = leader.get();
        assertNotNull(one, "the leader has a line about the first run");

        // it says it (the slot is emptied by the bot's tick), then plays and loses the next one
        assertTrue(BotRunOutcome.sayIfDue(leader, mockOnMap(), one.expiresAtMs() - 1_000, c -> true, (c, l) -> true));
        assertNull(leader.get());
        MechanicsRuns.Active second = run(party, 7_700);
        second.run().fail("time budget ran out");
        MechanicsRuns.get().stop(second, "after the failure");

        BotRunOutcome.Pending two = leader.get();
        assertNotNull(two, "and one about the second");
        assertTrue(BotRunOutcome.pool(Pool.LOSS_PQ).stream().anyMatch(raw ->
                two.line().equals(raw.replace(BotRunOutcome.CONTENT_TOKEN, "Kerning PQ"))), two.line());
    }

    private static Character mockOnMap() {
        Character chr = mock(Character.class);
        when(chr.getMap()).thenReturn(mock(server.maps.MapleMap.class));
        when(chr.isAlive()).thenReturn(true);
        return chr;
    }
}
