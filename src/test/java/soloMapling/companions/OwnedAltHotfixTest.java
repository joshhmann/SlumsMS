package soloMapling.companions;

import client.Character;
import net.server.world.PartyOperation;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.0.4 hotfix (owner 2026-10-28): owned alts follow their owner, and the safety around it - a channel change sends
 * them home, leaving the party sends them home, @botme here, and @botme/@dismiss off the packet thread.
 */
class OwnedAltHotfixTest {
    static Character owner(int id) { return OwnedAltServiceTest.owner(id); }

    static class Backend extends OwnedAltServiceTest.Memory {
        final List<Integer> broughtHere = new CopyOnWriteArrayList<>();
        final Set<String> botFriends = new HashSet<>();
        @Override public void bringHere(Character owner, Character alt) { broughtHere.add(alt.getId()); }
        @Override public boolean botFriendNamed(int ownerId, String name) { return botFriends.contains(name); }
    }

    // ── H1: a channel change sends the alts home ─────────────────────────────────────────────────

    @Test void channelChangeSavesAndSendsEveryAltHomeAndSaysSoOnArrival() {
        Backend db = new Backend(); db.add(2); db.add(3);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        s.summon(owner, "Alt2"); s.summon(owner, "Alt3");
        assertEquals(2, s.ownerChangingChannel(owner));
        assertEquals(2, db.saves.get(), "each alt saved once, as @dismiss");
        assertFalse(db.online.contains(2)); assertFalse(db.online.contains(3));
        verify(owner, never()).yellowMessage(anyString());
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt2"), "not while the owner is between channels");
        s.ownerArrived(owner);
        verify(owner).yellowMessage(OwnedAltService.WENT_HOME);
        assertTrue(OwnedAltService.WENT_HOME.startsWith("Your alts went home."));
        s.ownerArrived(owner);
        verify(owner, times(1)).yellowMessage(OwnedAltService.WENT_HOME); // told once
        s.summon(owner, "Alt2"); // and callable again on the new channel
        try (var login = s.beginLogin(3)) { assertFalse(db.online.contains(3)); }
    }

    @Test void channelChangeWithoutAltsSaysNothing() {
        Backend db = new Backend(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        assertEquals(0, s.ownerChangingChannel(owner));
        s.ownerArrived(owner);
        verify(owner, never()).yellowMessage(anyString());
        assertEquals(0, db.saves.get());
    }

    @Test void aSummonStillLoadingWhenTheOwnerChangesChannelIsRefusedAndNeverAttached() throws Exception {
        Backend db = new Backend(); db.add(2);
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        OwnedAltService s = new OwnedAltService(new Backend() {
            { add(2); }
            @Override public Character load(OwnedAltService.Candidate row, Character o) {
                loading.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return super.load(row, o);
            }
            @Override public void attach(Character o, Character alt) { db.online.add(alt.getId()); }
        }, () -> true);
        Character owner = owner(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Character> summon = worker.submit(() -> s.summon(owner, "Alt2"));
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            s.ownerChangingChannel(owner);
            release.countDown();
            ExecutionException e = assertThrows(ExecutionException.class, () -> summon.get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause().getMessage().contains("changed channel"), e.getCause().getMessage());
            assertFalse(db.online.contains(2), "never attached to the channel being left");
            try (var login = s.beginLogin(2)) { assertNotNull(login); } // reservation released
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    // ── M2: out of the party, out of the world ───────────────────────────────────────────────────

    @Test void anAltLeavingOrExpelledFromThePartyGoesHomeSaved() {
        Backend db = new Backend(); db.add(2); db.add(3);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        s.summon(owner, "Alt2"); s.summon(owner, "Alt3");
        s.onPartyChange(PartyOperation.EXPEL, 2, List.of(1, 2, 3));
        assertFalse(db.online.contains(2)); assertTrue(db.online.contains(3));
        verify(owner).yellowMessage("Alt2 is no longer in your party, so it went home (saved).");
        s.onPartyChange(PartyOperation.LEAVE, 3, List.of(1, 3));
        assertFalse(db.online.contains(3));
        assertEquals(2, db.saves.get());
    }

    @Test void theOwnerLeavingOrADisbandSendsTheirAltsHome() {
        Backend db = new Backend(); db.add(2); db.add(3);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        s.summon(owner, "Alt2");
        s.onPartyChange(PartyOperation.LEAVE, 1, List.of(1, 2, 50));
        assertFalse(db.online.contains(2), "owner left the party the alt is in");
        s.summon(owner, "Alt3");
        s.onPartyChange(PartyOperation.DISBAND, 1, List.of(1, 3));
        assertFalse(db.online.contains(3));
        assertEquals(2, db.saves.get());
    }

    @Test void otherPartyChangesLeaveAltsAlone() {
        Backend db = new Backend(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        s.summon(owner, "Alt2");
        s.onPartyChange(PartyOperation.LEAVE, 50, List.of(1, 2, 50)); // someone else left
        s.onPartyChange(PartyOperation.JOIN, 2, List.of(1, 2));
        s.onPartyChange(PartyOperation.DISBAND, 70, List.of(70, 71)); // another party
        assertTrue(db.online.contains(2));
        assertEquals(0, db.saves.get());
    }

    @Test void aDismissalsOwnPartyLeaveDoesNotSaveTwice() {
        Backend db = new Backend(); db.add(2);
        OwnedAltService[] ref = new OwnedAltService[1];
        Character owner = owner(1);
        // detach leaves the party, which comes back through onPartyChange while the alt is being saved
        db.detaching = () -> ref[0].onPartyChange(PartyOperation.LEAVE, 2, List.of(1, 2));
        OwnedAltService s = ref[0] = new OwnedAltService(db, () -> true);
        s.summon(owner, "Alt2");
        assertTrue(s.dismiss(owner, "Alt2"));
        assertEquals(1, db.saves.get());
    }

    // ── @botme here ──────────────────────────────────────────────────────────────────────────────

    @Test void botmeHereBringsTheOwnersActiveAltsButNeverIntoAnInstance() {
        Backend db = new Backend(); db.add(2); db.add(3);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        assertThrows(IllegalStateException.class, () -> s.bringHere(owner), "no alts with you");
        s.summon(owner, "Alt2"); s.summon(owner, "Alt3");
        assertThrows(IllegalStateException.class, () -> s.bringHere(owner(9)), "someone else's alts are not yours");
        client.Client oc = owner.getClient();
        when(oc.getChannel()).thenReturn(2);
        // the alts' private clients: Alt2 on the owner's channel, Alt3 somewhere else
        Character alt2 = altNamed(db, s, owner, "Alt2"), alt3 = altNamed(db, s, owner, "Alt3");
        client.Client c2 = mock(client.Client.class), c3 = mock(client.Client.class);
        when(c2.getChannel()).thenReturn(2); when(c3.getChannel()).thenReturn(4);
        when(alt2.getClient()).thenReturn(c2); when(alt3.getClient()).thenReturn(c3);
        assertEquals(List.of("Alt2"), s.bringHere(owner));
        assertEquals(List.of(2), db.broughtHere);
        verify(owner).yellowMessage("Alt3 is on another channel.");
        when(owner.getEventInstance()).thenReturn(mock(scripting.event.EventInstanceManager.class));
        IllegalStateException inside = assertThrows(IllegalStateException.class, () -> s.bringHere(owner));
        assertTrue(inside.getMessage().contains("instance"));
        assertEquals(List.of(2), db.broughtHere);
    }

    private static Character altNamed(Backend db, OwnedAltService s, Character owner, String name) {
        // the Memory backend's loaded alts are mocks; find the one for this name through @dismiss's matching
        for (Character c : db.loaded) if (c.getName().equals(name)) return c;
        throw new AssertionError(name);
    }

    @Test void dismissKnowsABotFriendFromAnAlt() {
        Backend db = new Backend(); db.botFriends.add("Buddy");
        OwnedAltService s = new OwnedAltService(db, () -> true);
        assertTrue(s.isBotFriend(owner(1), "Buddy"));
        assertFalse(s.isBotFriend(owner(1), "Nobody"));
    }

    // ── M1: off the packet thread, the reservation still decides ────────────────────────────────

    @Test void twoSummonsRacingOffThePacketThreadLoadTheAltOnce() throws Exception {
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger entered = new java.util.concurrent.atomic.AtomicInteger();
        Backend db = new Backend() {
            @Override public Character load(OwnedAltService.Candidate row, Character o) {
                entered.incrementAndGet();
                loading.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return super.load(row, o);
            }
        };
        db.add(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        OwnedAltService s = new OwnedAltService(db, () -> true, pool);
        Character owner = owner(1);
        List<String> said = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        Runnable summon = () -> {
            try { said.add(s.summon(owner, "Alt2").getName() + " joined"); }
            catch (IllegalStateException e) { said.add(e.getMessage()); }
            finally { done.countDown(); }
        };
        try {
            s.runAsync(summon);
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            s.runAsync(summon);
            // the caller (the packet thread) was never held by the load; the second summon never reached a load
            Thread.sleep(200);
            assertEquals(1, entered.get());
            release.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(1, entered.get(), "one load");
            assertEquals(1, said.stream().filter(m -> m.equals("Alt2 joined")).count(), said.toString());
            assertEquals(1, said.stream().filter(m -> m.contains("already online or still being saved")).count(), said.toString());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void capMessageCountsBotFriends() {
        Backend db = new Backend(); db.add(2);
        db.friends.addAll(Set.of(100, 101, 102, 103, 104));
        OwnedAltService s = new OwnedAltService(db, () -> true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.summon(owner(1), "Alt2"));
        assertTrue(e.getMessage().contains("up to five"));
        assertTrue(e.getMessage().contains("bot friends"));
    }
}
