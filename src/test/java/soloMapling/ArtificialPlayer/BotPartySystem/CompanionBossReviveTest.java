package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.SoloMaplingUtilities;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-revive-respawn: the rules of a companion's death inside a boss run (card alt-death-rules). The clock is
 * passed in, so every wait, every price and the rest rule are checked to the millisecond.
 */
class CompanionBossReviveTest {

    static final long NOW = 90_000_000L;
    static final int OWNER = 1, ALT = 2, REGULAR = 3;
    static final int MAX_HP = 1_400, MAX_MP = 500;

    private MockedStatic<GCMovement> movement;
    private MockedStatic<SoloMaplingUtilities> world;
    private MapleMap ownerMap, bossMap;
    private ExpeditionBotsFixture.Joined joined;
    private final java.util.List<ExpeditionBotsFixture.Joined> runs = new java.util.ArrayList<>();
    private EventInstanceManager eim;

    @BeforeEach
    void stage() {
        movement = Mockito.mockStatic(GCMovement.class);
        ownerMap = mock(MapleMap.class);
        bossMap = mock(MapleMap.class);
        eim = mock(EventInstanceManager.class);
        joined = ExpeditionBotsFixture.join(ownerMap, OWNER, ALT);
        runs.add(joined);
        world = Mockito.mockStatic(SoloMaplingUtilities.class);
        System.clearProperty("slums.companion.deathrules.regulars");
        System.clearProperty("slums.companion.rest.percent");
        System.clearProperty("slums.companion.rest.maxMs");
        System.clearProperty("slums.companion.revive.waitMs");
        System.clearProperty("slums.companion.revive.maxWaitMs");
        System.clearProperty("slums.companion.revive.pricePerLevel");
    }

    @AfterEach
    void unstage() throws Exception {
        for (ExpeditionBotsFixture.Joined run : runs) {
            run.release();
        }
        runs.clear();
        CompanionBossRevive.forget(ALT);
        CompanionBossRevive.forget(REGULAR);
        BotDeaths.clearBot(ALT);
        BotDeaths.clearBot(REGULAR);
        CharacterStorage.removeActiveBot(REGULAR);
        System.clearProperty("slums.companion.deathrules.regulars");
        System.clearProperty("slums.companion.rest.percent");
        System.clearProperty("slums.companion.rest.maxMs");
        System.clearProperty("slums.companion.revive.waitMs");
        System.clearProperty("slums.companion.revive.maxWaitMs");
        System.clearProperty("slums.companion.revive.pricePerLevel");
        world.close();
        movement.close();
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, Long> lying() throws Exception {
        Field f = BotDeaths.class.getDeclaredField("lyingUntil");
        f.setAccessible(true);
        return (Map<Integer, Long>) f.get(null);
    }

    Character owner(int meso) {
        Character o = mock(Character.class);
        when(o.getId()).thenReturn(OWNER);
        when(o.getName()).thenReturn("Main");
        when(o.getMap()).thenReturn(ownerMap);
        when(o.getPosition()).thenReturn(new Point(0, 0));
        when(o.getMeso()).thenReturn(meso);
        when(o.getEventInstance()).thenReturn(eim); // the owner is in the run with its alt
        return o;
    }

    Character alt(Character owner, MapleMap map) {
        Character a = mock(Character.class);
        when(a.getId()).thenReturn(ALT);
        when(a.getName()).thenReturn("Second");
        when(a.isOwnedAlt()).thenReturn(true);
        when(a.getOwnedAltOwner()).thenReturn(owner);
        when(a.getClient()).thenReturn(mock(BotClient.class));
        when(a.getLevel()).thenReturn(120);
        when(a.getMap()).thenReturn(map);
        when(a.getPosition()).thenReturn(new Point(10, 0));
        when(a.getEventInstance()).thenReturn(eim);
        when(a.isAlive()).thenReturn(true);
        when(a.getHp()).thenReturn(0);
        when(a.getMp()).thenReturn(100);
        when(a.getCurrentMaxHp()).thenReturn(MAX_HP);
        when(a.getCurrentMaxMp()).thenReturn(MAX_MP);
        return a;
    }

    /** The alt dies: BotDeaths holds it down, and the run is the one the fixture joined. */
    void dies(Character alt) throws Exception {
        lying().put(alt.getId(), 0L);
    }

    @Test
    void theWaitingDoublesWithEachDeathAndStopsAtTwoMinutes() {
        assertEquals(10_000L, CompanionBossRevive.waitMs(1));
        assertEquals(20_000L, CompanionBossRevive.waitMs(2));
        assertEquals(40_000L, CompanionBossRevive.waitMs(3));
        assertEquals(80_000L, CompanionBossRevive.waitMs(4));
        assertEquals(120_000L, CompanionBossRevive.waitMs(5));
        assertEquals(120_000L, CompanionBossRevive.waitMs(9)); // the cap holds however long the fight takes
    }

    @Test
    void theFirstDeathWaitsTenSecondsAndTheSecondTwenty() throws Exception {
        Character owner = owner(0);
        Character alt = alt(owner, ownerMap);

        dies(alt);
        assertTrue(CompanionBossRevive.gate(alt, NOW), "a dead companion in a run answers its own tick");
        verify(owner).yellowMessage("Second is down, back in 10s (or @botme revive Second for 120,000 mesos)");
        assertTrue(CompanionBossRevive.gate(alt, NOW + 9_999));
        verify(alt, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
        assertTrue(CompanionBossRevive.gate(alt, NOW + 10_000));
        verify(alt).applyHpMpChange(0, MAX_HP, 0);
        verify(owner).yellowMessage("Second is back up");

        dies(alt); // the same run, so the second death waits twice as long
        assertTrue(CompanionBossRevive.gate(alt, NOW + 20_000));
        verify(owner).yellowMessage("Second is down, back in 20s (or @botme revive Second for 120,000 mesos)");
        assertTrue(CompanionBossRevive.gate(alt, NOW + 39_999));
        assertTrue(BotDeaths.isDead(ALT), "still down: the twenty seconds are not up");
        assertTrue(CompanionBossRevive.gate(alt, NOW + 40_000));
        assertFalse(BotDeaths.isDead(ALT));
        verify(owner, times(2)).yellowMessage("Second is back up");
    }

    @Test
    void aNewRunStartsTheWaitingOverAtTenSeconds() throws Exception {
        Character owner = owner(0);
        Character alt = alt(owner, ownerMap);
        for (int death = 1; death <= 2; death++) { // two deaths, so the run is up to a twenty-second wait
            dies(alt);
            CompanionBossRevive.gate(alt, NOW + death * 100_000L);
            CompanionBossRevive.gate(alt, NOW + death * 100_000L + 20_000);
        }
        joined.release(); // the run ends
        runs.add(ExpeditionBotsFixture.join(ownerMap, OWNER, ALT));
        EventInstanceManager next = mock(EventInstanceManager.class); // the next run: owner and alt both in it
        when(alt.getEventInstance()).thenReturn(next);
        when(owner.getEventInstance()).thenReturn(next);
        dies(alt);
        assertTrue(CompanionBossRevive.gate(alt, NOW + 300_000));
        // the same line as the first run's first death, and a second time only because this run's count is back at one
        verify(owner, times(2)).yellowMessage("Second is down, back in 10s (or @botme revive Second for 120,000 mesos)");
        assertTrue(CompanionBossRevive.gate(alt, NOW + 309_999));
        assertTrue(CompanionBossRevive.gate(alt, NOW + 310_000));
        assertFalse(BotDeaths.isDead(ALT), "the new run's first death is a ten-second wait again");
    }

    @Test
    void whileItWaitsItStaysInTheRunAndGetsUpOnItsOwnersMap() throws Exception {
        Character owner = owner(0);
        Character alt = alt(owner, bossMap); // it fell on the boss's map, its owner is on the map they share
        dies(alt);
        assertTrue(CompanionBossRevive.gate(alt, NOW));
        verify(alt, never()).respawn(anyInt());
        verify(eim, never()).unregisterPlayer(any());
        verify(eim, never()).playerDisconnected(any());
        assertTrue(joined.expedition().hasMember(ALT), "dead or not, it is still in the expedition");

        CompanionBossRevive.gate(alt, NOW + 10_000);
        verify(alt).changeMap(eq(ownerMap), any(Point.class)); // up beside its owner, inside the same instance
        verify(alt).applyHpMpChange(0, MAX_HP, 0);              // the HP a Resurrection gives a player
        verify(alt).broadcastStance(4);                         // and the stance it broadcasts
        assertFalse(BotDeaths.isDead(ALT));
        verify(alt, never()).respawn(anyInt());
        assertTrue(joined.expedition().hasMember(ALT));
    }

    /**
     * Bots audit (owner's rule: "if the owner leaves the run ... the alt leaves the instance as today"): an owner who
     * left the run while the alt was down - died out of Zakum, or walked out - does not pull the alt out of the
     * instance to wherever he is now. The death rules let go and the old path (town, then home) takes over.
     */
    @Test
    void anOwnerWhoLeftTheRunDoesNotPullTheDownAltOutToHim() throws Exception {
        Character owner = owner(0);
        Character alt = alt(owner, bossMap);
        dies(alt);
        assertTrue(CompanionBossRevive.gate(alt, NOW), "down in the run, with its owner in it: it waits");
        assertTrue(CompanionBossRevive.waiting(alt));

        MapleMap town = mock(MapleMap.class);
        when(owner.getMap()).thenReturn(town);           // the owner is out of the run now
        when(owner.getEventInstance()).thenReturn(null);
        assertFalse(CompanionBossRevive.gate(alt, NOW + 10_000), "the old path takes the tick");
        assertFalse(CompanionBossRevive.waiting(alt));
        verify(alt, never()).changeMap(eq(town), any(Point.class));
        verify(alt, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
        verify(owner, never()).yellowMessage("Second is back up");
    }

    @Test
    void anInstantReviveCostsAThousandMesosALevelAndDoublesWithinTheRun() throws Exception {
        Character owner = owner(1_000_000);
        Character alt = alt(owner, ownerMap);

        dies(alt);
        CompanionBossRevive.gate(alt, NOW);
        CompanionBossRevive.Outcome first = CompanionBossRevive.reviveNow(owner, alt, NOW + 1_000);
        assertTrue(first.revived());
        assertTrue(first.told().contains("120,000"), first.told());
        assertTrue(first.told().endsWith("is back up - 120,000 mesos."), first.told());
        verify(owner).gainMeso(eq(-120_000), eq(true), eq(false), eq(true));
        assertEquals(0, CompanionBossRevive.priceOf(alt), "not waiting any more: there is no next price");

        dies(alt);
        CompanionBossRevive.gate(alt, NOW + 2_000);
        assertEquals(240_000, CompanionBossRevive.priceOf(alt));
        verify(owner).yellowMessage("Second is down, back in 20s (or @botme revive Second for 240,000 mesos)");
        CompanionBossRevive.Outcome second = CompanionBossRevive.reviveNow(owner, alt, NOW + 2_500);
        assertTrue(second.revived());
        verify(owner).gainMeso(eq(-240_000), eq(true), eq(false), eq(true));

        joined.release(); // the price goes with the run: the next run starts at the first price again
        runs.add(ExpeditionBotsFixture.join(ownerMap, OWNER, ALT));
        EventInstanceManager next = mock(EventInstanceManager.class); // the next run: owner and alt both in it
        when(alt.getEventInstance()).thenReturn(next);
        when(owner.getEventInstance()).thenReturn(next);
        dies(alt);
        CompanionBossRevive.gate(alt, NOW + 100_000);
        assertEquals(120_000, CompanionBossRevive.priceOf(alt));
    }

    @Test
    void tooFewMesosChargesNothingAndItStaysDown() throws Exception {
        Character owner = owner(100); // not enough for a level 120 revive
        Character alt = alt(owner, ownerMap);
        dies(alt);
        CompanionBossRevive.gate(alt, NOW);

        CompanionBossRevive.Outcome outcome = CompanionBossRevive.reviveNow(owner, alt, NOW + 1_000);
        assertFalse(outcome.revived());
        assertTrue(outcome.told().contains("120,000") && outcome.told().contains("still down"), outcome.told());
        verify(owner, never()).gainMeso(anyInt(), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyBoolean());
        verify(alt, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
        assertTrue(BotDeaths.isDead(ALT), "nothing charged, nothing revived");
        assertTrue(CompanionBossRevive.waiting(alt), "it is still waiting for its own revive");
    }

    @Test
    void afterTheReviveItRestsUntilWholeOrTheLimit() throws Exception {
        Character owner = owner(0);
        Character alt = alt(owner, ownerMap);
        dies(alt);
        CompanionBossRevive.gate(alt, NOW);
        CompanionBossRevive.gate(alt, NOW + 10_000); // up, and resting from here

        when(alt.getHp()).thenReturn(50);
        when(alt.getMp()).thenReturn(100);
        assertTrue(CompanionBossRevive.resting(alt, NOW + 11_000), "hurt: no follow and no fight yet");
        when(alt.getHp()).thenReturn(700); // half
        when(alt.getMp()).thenReturn(250);
        assertTrue(CompanionBossRevive.resting(alt, NOW + 12_000), "half is not REST_PERCENT");
        when(alt.getHp()).thenReturn(MAX_HP);
        when(alt.getMp()).thenReturn(MAX_MP);
        assertFalse(CompanionBossRevive.resting(alt, NOW + 13_000), "whole again: back to work");

        dies(alt); // and it died again, waited its twenty seconds, got up and started to rest once more
        CompanionBossRevive.gate(alt, NOW + 20_000);
        CompanionBossRevive.gate(alt, NOW + 40_000);
        when(alt.getHp()).thenReturn(50);
        when(alt.getMp()).thenReturn(100);
        assertTrue(CompanionBossRevive.resting(alt, NOW + 41_000), "hurt again: the rest starts over with the revive");
        assertFalse(CompanionBossRevive.resting(alt, NOW + 160_000), "the rest runs out REST_MAX_MS after the revive");
    }

    @Test
    void aCompanionThatDiedOutsideARunKeepsItsOldPath() throws Exception {
        Character owner = owner(0);
        Character alt = alt(owner, bossMap);
        when(alt.getEventInstance()).thenReturn(null); // no run, even on a map with a boss on it
        dies(alt);
        assertFalse(CompanionBossRevive.gate(alt, NOW), "outside a run the caller's own path answers");
        assertFalse(CompanionBossRevive.waiting(alt));
        verify(owner, never()).yellowMessage(anyString());
        verify(alt, never()).applyHpMpChange(anyInt(), anyInt(), anyInt());
        assertTrue(BotDeaths.isDead(ALT), "the death is still the caller's to finish");
    }

    @Test
    void aRegularIsLeftAloneByDefaultAndWaitsUnderTheSwitch() throws Exception {
        Character leader = owner(500_000);
        runs.add(ExpeditionBotsFixture.join(ownerMap, OWNER, REGULAR));
        Character regular = regular(leader);
        dies(regular);
        assertFalse(CompanionBossRevive.gate(regular, NOW), "default switches leave a party regular exactly as today");
        verify(leader, never()).yellowMessage(anyString());
        assertFalse(CompanionBossRevive.waiting(regular));

        System.setProperty("slums.companion.deathrules.regulars", "true"); // the owner asked for regulars too
        assertTrue(CompanionBossRevive.gate(regular, NOW));
        verify(leader).yellowMessage("Clock4 is down, back in 10s (or @botme revive Clock4 for 70,000 mesos)");
        CompanionBossRevive.Outcome paid = CompanionBossRevive.reviveNow(leader, regular, NOW + 1_000);
        assertTrue(paid.revived());
        assertTrue(paid.told().endsWith("is back up - 70,000 mesos."), paid.told());
        verify(leader).gainMeso(eq(-70_000), eq(true), eq(false), eq(true)); // a regular's leader pays
        assertTrue(CompanionBossRevive.resting(regular, NOW + 2_000), "and it rests like an alt does");
    }

    /** A party regular in the run: not an owned alt, followed by the player who pays for it. */
    Character regular(Character leader) {
        Character r = mock(Character.class);
        when(r.getId()).thenReturn(REGULAR);
        when(r.getName()).thenReturn("Clock4");
        when(r.isOwnedAlt()).thenReturn(false);
        when(r.getClient()).thenReturn(mock(BotClient.class));
        when(r.getLevel()).thenReturn(70);
        when(r.getMap()).thenReturn(ownerMap);
        when(r.getPosition()).thenReturn(new Point(20, 0));
        when(r.getEventInstance()).thenReturn(eim);
        when(r.isAlive()).thenReturn(true);
        when(r.getHp()).thenReturn(0);
        when(r.getMp()).thenReturn(100);
        when(r.getCurrentMaxHp()).thenReturn(MAX_HP);
        when(r.getCurrentMaxMp()).thenReturn(MAX_MP);
        FollowerBot follower = mock(FollowerBot.class);
        when(follower.leaderId()).thenReturn(OWNER);
        CharacterStorage.addActiveBot(REGULAR, follower);
        var storage = mock(net.server.PlayerStorage.class);
        when(storage.getCharacterById(OWNER)).thenReturn(leader);
        var w = mock(net.server.world.World.class);
        when(w.getPlayerStorage()).thenReturn(storage);
        world.when(SoloMaplingUtilities::world).thenReturn(w);
        return r;
    }
}
