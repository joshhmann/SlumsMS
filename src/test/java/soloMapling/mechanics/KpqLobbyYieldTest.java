package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 (owner 2026-10-30: bots yield). The KPQ lobby held a channel's one Kerning PQ for its 30 minutes and real
 * players (and auto-QA's pretend player) got "Another party has already entered". Now a real leader's start ends a bot
 * lobby run at once (bots out, instance ended, its lobby freed now) and the lobby keeps off that channel for 10 min;
 * a player-led or staged run, a run with a real player inside, and a bot leader never make a run yield.
 */
class KpqLobbyYieldTest {

    final List<String> lines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        KpqLobby.clearForTest();
    }

    @AfterEach
    void stopEverything() {
        MechanicsRuns.get().stopAll("test over");
        KpqLobby.clearForTest();
    }

    record Kpq(EventManager em, EventInstanceManager eim, Channel ch) {
    }

    static Kpq kpqOn(int channel, List<Character> players, int leaderId) {
        EventInstanceManager eim = MechanicsRunsInstancesTest.kpqOn(channel, players, leaderId);
        EventManager em = eim.getEm();
        when(em.getInstances()).thenReturn(List.of(eim));
        when(em.releaseInstanceNow(eim)).thenReturn(true);
        return new Kpq(em, eim, em.getChannelServer());
    }

    MechanicsRuns.Active run(Kpq k, boolean lobby) {
        MechanicsRuns.Active a = MechanicsRuns.get().start(k.eim(), KpqStageDataTest.kpq(), true, 0, 1, 0, true,
                lines::add, null);
        assertFalse(a.run().ended(), lines.toString());
        if (lobby) {
            KpqLobby.markLobbyRunForTest(a.instanceName());
        }
        return a;
    }

    @Test
    void aRealLeaderTakesTheChannelFromABotLobbyRun() throws Exception {
        Kpq k = kpqOn(7, MechanicsRunsInstancesTest.bots(9_700), 9_700);
        MechanicsRuns.Active a = run(k, true);
        Character player = MechanicsRunsInstancesTest.member(9_790, false);
        long before = System.currentTimeMillis();

        assertTrue(KpqLobby.yieldFor(k.em(), player));

        assertTrue(a.run().ended());
        assertEquals("yielded to m9790's party", a.run().endedBecause());
        verify(k.eim(), org.mockito.Mockito.atLeastOnce()).invokeScriptFunction("end", k.eim()); // bots out, instance ended (the stop path)
        verify(k.em()).releaseInstanceNow(k.eim());           // its lobby freed now: the party starts on this talk
        verify(player).dropMessage(anyInt(), anyString());
        assertEquals("[Mechanics] lobby yields ch 7 to m9790's party", KpqLobby.lastYield());
        int key = 1 * 100 + 7;
        assertFalse(KpqLobby.mayTry(key, before + KpqLobby.REFUSED_BACKOFF_MS - 1_000, false),
                "the lobby does not re-take the channel for 10 min");
        assertTrue(KpqLobby.mayTry(key, System.currentTimeMillis() + KpqLobby.REFUSED_BACKOFF_MS, false));
    }

    @Test
    void neverWithARealPlayerInside() throws Exception {
        List<Character> inside = new ArrayList<>(MechanicsRunsInstancesTest.bots(9_710).subList(0, 3));
        inside.add(MechanicsRunsInstancesTest.member(9_719, false));
        Kpq k = kpqOn(8, inside, 9_710);
        MechanicsRuns.Active a = run(k, true);
        assertFalse(KpqLobby.yieldFor(k.em(), MechanicsRunsInstancesTest.member(9_791, false)));
        assertFalse(a.run().ended());
        verify(k.eim(), never()).invokeScriptFunction("end", k.eim());
    }

    @Test
    void aStagedOrPlayerLedRunNeverYields() {
        Kpq k = kpqOn(9, MechanicsRunsInstancesTest.bots(9_720), 9_720);
        KpqLobby.markLobbyRunForTest("some other run"); // the lobby has a run, just not this one
        MechanicsRuns.Active a = run(k, false);
        assertFalse(KpqLobby.yieldFor(k.em(), MechanicsRunsInstancesTest.member(9_792, false)));
        assertFalse(a.run().ended());
    }

    @Test
    void aBotOrAltLeaderNeverMakesItYield() {
        Kpq k = kpqOn(10, MechanicsRunsInstancesTest.bots(9_730), 9_730);
        MechanicsRuns.Active a = run(k, true);
        assertFalse(KpqLobby.yieldFor(k.em(), MechanicsRunsInstancesTest.member(9_793, true)));
        Character alt = MechanicsRunsInstancesTest.member(9_794, false);
        when(alt.isOwnedAlt()).thenReturn(true);
        assertFalse(KpqLobby.yieldFor(k.em(), alt));
        assertFalse(a.run().ended());
    }

    @Test
    void otherEventsAreLeftAlone() {
        Kpq k = kpqOn(11, MechanicsRunsInstancesTest.bots(9_740), 9_740);
        MechanicsRuns.Active a = run(k, true);
        when(k.em().getName()).thenReturn("LudiPQ");
        assertFalse(KpqLobby.yieldFor(k.em(), MechanicsRunsInstancesTest.member(9_795, false)));
        assertFalse(a.run().ended());
    }

    @Test
    void onlyALiveUnclearedLobbyRunWithNoPlayerYields() {
        assertTrue(KpqLobby.mayYield(true, false, false, false));
        assertFalse(KpqLobby.mayYield(false, false, false, false), "a player-led or staged run");
        assertFalse(KpqLobby.mayYield(true, true, false, false), "cleared: the PQ is free already");
        assertFalse(KpqLobby.mayYield(true, false, true, false), "already disposed");
        assertFalse(KpqLobby.mayYield(true, false, false, true), "a real player inside");
    }

    /**
     * b153 (TEST 10-30: 26 of 35 lobby answerers never handed in a pass): a lobby bot whose etc tab is (nearly) full
     * cannot pick up a coupon or a pass, so it is not picked for a lobby party.
     */
    @Test
    void aBotWithNoEtcRoomIsNotPickedForTheLobby() {
        int id = 1_999_999_001;
        soloMapling.ArtificialPlayer.BotSM sm = mock(soloMapling.ArtificialPlayer.BotSM.class);
        when(sm.getRunning()).thenReturn(true);
        var bots = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots();
        bots.put(id, sm);
        try {
            client.inventory.Inventory etc = mock(client.inventory.Inventory.class);
            Character c = MechanicsRunsInstancesTest.member(id, true);
            when(c.getHp()).thenReturn(500);
            when(c.getInventory(client.inventory.InventoryType.ETC)).thenReturn(etc);
            when(etc.getNumFreeSlot()).thenReturn((short) 20);
            assertTrue(KpqLobby.free(c), "room in the bag: free");
            when(etc.getNumFreeSlot()).thenReturn((short) 0);
            assertFalse(KpqLobby.free(c), "a full etc tab cannot take the stage's coupons");
            when(etc.getNumFreeSlot()).thenReturn((short) (KpqLobby.MIN_FREE_ETC_SLOTS - 1));
            assertFalse(KpqLobby.free(c));
        } finally {
            bots.remove(id);
        }
    }

    /** b153: a farmer whose count stands still for 3 minutes is reported once, again after it moves and stalls. */
    @Test
    void aStalledFarmerIsReportedOnce() {
        StepExecutor.Beat beat = new StepExecutor.Beat();
        assertFalse(StepExecutor.stalled(beat, 4, 0));
        assertFalse(StepExecutor.stalled(beat, 4, StepExecutor.STALL_MS - 1));
        assertTrue(StepExecutor.stalled(beat, 4, StepExecutor.STALL_MS));
        assertFalse(StepExecutor.stalled(beat, 4, StepExecutor.STALL_MS * 2), "once");
        assertFalse(StepExecutor.stalled(beat, 5, StepExecutor.STALL_MS * 2), "it moved");
        assertTrue(StepExecutor.stalled(beat, 5, StepExecutor.STALL_MS * 3));
    }

    @Test
    void aRunSaysWhenAMemberHasNoBagRoom() {
        client.inventory.Inventory etc = mock(client.inventory.Inventory.class);
        Character c = MechanicsRunsInstancesTest.member(9_750, true);
        when(c.getInventory(client.inventory.InventoryType.ETC)).thenReturn(etc);
        when(etc.getNumFreeSlot()).thenReturn((short) 0);
        assertTrue(MechanicsRuns.fullBag(c).startsWith("has 0 free etc slot(s)"));
        when(etc.getNumFreeSlot()).thenReturn((short) 30);
        assertEquals(null, MechanicsRuns.fullBag(c));
    }
}
