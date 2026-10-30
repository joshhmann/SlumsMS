package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.2: runs on the live side ({@link MechanicsRuns}) against mocked event instances.
 * <ul>
 *   <li>b113: every channel's event manager names its first Kerning PQ "Kerning0"; runs were kept by that name, so a
 *   second channel's run was refused and {@code runFor} could return another channel's run.</li>
 *   <li>b118: an aborted run in a PQ a real player stays in sends out an owned alt whose owner is gone.</li>
 * </ul>
 */
class MechanicsRunsInstancesTest {

    final List<String> lines = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopEverything() {
        MechanicsRuns.get().stopAll("test over");
    }

    static Character member(int id, boolean bot) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isBot()).thenReturn(bot);
        when(c.getName()).thenReturn("m" + id);
        return c;
    }

    static EventInstanceManager kpqOn(int channel, List<Character> players, int leaderId) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        EventManager em = mock(EventManager.class);
        Channel ch = mock(Channel.class);
        when(ch.getId()).thenReturn(channel);
        when(ch.getWorld()).thenReturn(1);
        when(em.getChannelServer()).thenReturn(ch);
        when(em.getName()).thenReturn("KerningPQ");
        when(eim.getEm()).thenReturn(em);
        when(eim.getName()).thenReturn("Kerning0");
        when(eim.getPlayers()).thenReturn(new ArrayList<>(players));
        when(eim.getLeaderId()).thenReturn(leaderId);
        return eim;
    }

    static List<Character> bots(int from) {
        List<Character> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(member(from + i, true));
        }
        return out;
    }

    @Test
    void twoChannelsRunTheirOwnKerning0AtOnce() {
        EventInstanceManager ch1 = kpqOn(1, bots(9_100), 9_100);
        EventInstanceManager ch2 = kpqOn(2, bots(9_200), 9_200);
        MechanicsRuns runs = MechanicsRuns.get();
        MechanicsRuns.Active a1 = runs.start(ch1, KpqStageDataTest.kpq(), true, 0, 1, 0, true, lines::add, null);
        MechanicsRuns.Active a2 = runs.start(ch2, KpqStageDataTest.kpq(), true, 0, 1, 0, true, lines::add, null);
        assertFalse(a1.run().ended(), lines.toString());
        assertFalse(a2.run().ended(), "channel 2's Kerning0 is not channel 1's: " + lines);
        assertSame(a1, runs.runFor(ch1));
        assertSame(a2, runs.runFor(ch2));
        assertNotSame(a1.instanceName(), a2.instanceName());
        assertTrue(a2.instanceName().contains("ch 2"), a2.instanceName());
        runs.stop(a2, "stopped");
        assertSame(a1, runs.runFor(ch1), "stopping channel 2 leaves channel 1 alone");
    }

    @Test
    void anAltWhoseOwnerLeftIsSentOutWhenTheRunAbortsUnderAPlayer() throws Exception {
        Character player = member(9_300, false);
        Character gone = member(9_399, false);
        Character stray = member(9_301, false);
        when(stray.isOwnedAlt()).thenReturn(true);
        when(stray.getOwnedAltOwner()).thenReturn(gone);
        Character kept = member(9_302, false);
        when(kept.isOwnedAlt()).thenReturn(true);
        when(kept.getOwnedAltOwner()).thenReturn(player);
        Character bot = member(9_303, true);
        EventInstanceManager eim = kpqOn(3, List.of(player, stray, kept, bot), 9_300);
        MechanicsRuns.Active a = MechanicsRuns.get().start(eim, KpqStageDataTest.kpq(), true, 0, 1, 0, false,
                lines::add, null);
        assertFalse(a.run().ended(), lines.toString());
        MechanicsRuns.get().stop(a, "exception: test");
        verify(eim).unregisterPlayer(stray);
        verify(stray).changeMap(103000890);
        verify(eim, never()).unregisterPlayer(kept);
        verify(eim, never()).invokeScriptFunction("end", eim);
    }

    @Test
    void aStoppedStagedRunEndsItsInstance() throws Exception {
        EventInstanceManager eim = kpqOn(4, bots(9_400), 9_400);
        MechanicsRuns.Active a = MechanicsRuns.get().start(eim, KpqStageDataTest.kpq(), true, 0, 1, 0, true,
                lines::add, null);
        MechanicsRuns.get().stop(a, "stopped by AqClient");
        verify(eim).invokeScriptFunction("end", eim);
        assertTrue(lines.stream().anyMatch(l -> l.contains("ended with the run")), lines.toString());
    }

    /** K7 (bot audit): a scheduler that refuses the tick must not leave the run registered and its bots held. */
    @Test
    void aRunWhoseTickCannotBeScheduledReleasesEverything() {
        var was = MechanicsRuns.scheduler;
        java.util.concurrent.ScheduledExecutorService refusing = mock(java.util.concurrent.ScheduledExecutorService.class);
        when(refusing.scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("shutting down"));
        MechanicsRuns.scheduler = () -> refusing;
        try {
            int heldBefore = MechanicsHold.count();
            EventInstanceManager eim = kpqOn(5, bots(9_500), 9_500);
            MechanicsRuns.Active a = MechanicsRuns.get().start(eim, KpqStageDataTest.kpq(), true, 0, 1, 0, true,
                    lines::add, null);
            assertTrue(a.run().ended(), lines.toString());
            assertTrue(a.run().endedBecause().startsWith("could not start"), a.run().endedBecause());
            assertSame(null, MechanicsRuns.get().runFor(eim), "not left registered");
            org.junit.jupiter.api.Assertions.assertEquals(heldBefore, MechanicsHold.count(), "no bot left held");
        } finally {
            MechanicsRuns.scheduler = was;
        }
    }

    /** K10 (bot audit): a broken NPC script is logged once in a row and abandons the run on the third failure. */
    @Test
    void aBrokenScriptIsLoggedOnceAndEndsTheRun() {
        StepExecutor.Beat beat = new StepExecutor.Beat();
        boolean[] first = StepExecutor.scriptOutcome(beat, false);
        boolean[] second = StepExecutor.scriptOutcome(beat, false);
        boolean[] third = StepExecutor.scriptOutcome(beat, false);
        assertTrue(first[0] && !first[1]);
        assertFalse(second[0] || second[1]);
        assertFalse(third[0]);
        assertTrue(third[1], "abort");
        assertTrue(StepExecutor.scriptOutcome(beat, true)[0]);
        assertFalse(StepExecutor.scriptOutcome(beat, false)[1], "a success resets the count");
    }
}
