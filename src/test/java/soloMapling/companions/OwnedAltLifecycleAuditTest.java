package soloMapling.companions;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 1.6.2 audit 163 #5-#7 (b114, b117) and b132: the alt lifecycle around a channel change and a failed save. */
class OwnedAltLifecycleAuditTest {

    static Character owner(int id) { return OwnedAltServiceTest.owner(id); }

    /** #5 (b114): a channel change saves the alts off the packet thread, and the owner hears it after the save. */
    @Test void aChannelChangeSavesTheAltsOffThePacketThread() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2); db.add(3);
        List<Runnable> pool = new ArrayList<>();
        OwnedAltService s = new OwnedAltService(db, () -> true, pool::add);
        Character owner = owner(1);
        s.summon(owner, "Alt2"); s.summon(owner, "Alt3");
        assertEquals(2, s.ownerChangingChannel(owner), "two alts going home");
        assertEquals(0, db.saves.get(), "nothing saved on the calling (packet) thread");
        assertEquals(1, pool.size());
        Character arrived = owner(1); // the owner's character on the new channel is a new object
        s.ownerArrived(arrived);
        verify(arrived, never()).yellowMessage(anyString()); // not "saved" before it is
        assertThrows(IllegalStateException.class, () -> s.summon(arrived, "Alt2"), "still being saved");
        pool.remove(0).run();
        assertEquals(2, db.saves.get());
        verify(arrived).yellowMessage(OwnedAltService.WENT_HOME);
        assertFalse(db.online.contains(2));
    }

    /** b132: a channel change whose save failed says so, never "went home (saved)". */
    @Test void aFailedChannelChangeSaveIsNotReportedAsSaved() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("database unavailable"); };
        s.ownerChangingChannel(owner);
        s.ownerArrived(owner);
        verify(owner, never()).yellowMessage(OwnedAltService.WENT_HOME);
        verify(owner).yellowMessage(OwnedAltService.HOME_FAILED);
    }

    /** #6 (b117): a channel change that never arrives stops blocking @botme after a minute. */
    @Test void aLostChannelChangeStopsBlockingBotme() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        AtomicLong now = new AtomicLong(1_000_000);
        OwnedAltService s = new OwnedAltService(db, () -> true, () -> true, Runnable::run, now::get);
        Character owner = owner(1);
        s.ownerChangingChannel(owner); // then the client drops: ownerArrived never comes
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt2"));
        assertTrue(e.getMessage().contains("changing channel"));
        now.addAndGet(OwnedAltService.TRANSITION_TTL_MS + 1);
        assertNotNull(s.summon(owner, "Alt2"), "no restart needed");
    }

    /** #7 (b117): a FAILED alt is retried by itself, and after the cap it leaves the world and frees its slot. */
    @Test void aFailedAltIsRetriedThenTakenOutOfTheWorld() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        db.friends.addAll(Set.of(100, 101, 102, 103));
        OwnedAltService s = new OwnedAltService(db, () -> true);
        List<Runnable> later = new ArrayList<>();
        s.retryWith(later::add);
        Character owner = owner(1);
        s.summon(owner, "Alt2");
        assertFalse(s.hasCompanionRoom(1), "4 bot friends + the alt");
        db.saving = () -> { throw new IllegalStateException("database unavailable"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
        assertEquals(1, later.size(), "a retry is queued, not left for a restart");
        assertTrue(db.online.contains(2), "still out while it may yet be saved");
        later.remove(0).run();
        assertEquals(1, later.size());
        later.remove(0).run(); // third failure: give up
        assertTrue(later.isEmpty());
        assertEquals(3, db.saves.get());
        assertFalse(db.online.contains(2), "no ghost left standing in the world");
        assertTrue(s.hasCompanionRoom(1), "its companion slot is free again");
        assertThrows(IllegalStateException.class, () -> s.beginLogin(2), "its login stays blocked for a GM");
        assertEquals(0, s.dismissAll(owner, false), "nothing more to dismiss");
        verify(owner).yellowMessage(contains("could not be saved"));
    }

    /** #7: a retry that succeeds sends the alt home normally. */
    @Test void aRetryThatSucceedsSendsTheAltHome() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        List<Runnable> later = new ArrayList<>();
        s.retryWith(later::add);
        Character owner = owner(1);
        s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("blip"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
        db.saving = () -> {};
        later.remove(0).run();
        assertFalse(db.online.contains(2));
        verify(owner).yellowMessage("Alt2 was saved and went home.");
        try (var login = s.beginLogin(2)) { assertNotNull(login); }
    }

    /**
     * Audit B15 (MEDIUM "alt summons can exceed the 5-companion cap"): the counted scenario the report asked for. Alts,
     * bot-friend reservations and live bot friends are one id set under one monitor; five in all, whatever the order.
     */
    @Test void fiveCompanionsInAllWhateverTheOrder() throws Exception {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory();
        for (int id = 2; id <= 7; id++) db.add(id);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        for (int id = 2; id <= 5; id++) s.summon(owner, "Alt" + id);
        assertTrue(s.reserveCompanion(1, 500), "a bot friend takes the fifth place");
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt6"), "no sixth: 4 alts + 1 friend");
        db.friends.add(500); // the friend became a follower (counted by the backend) while its reservation stands
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt6"), "counted once, still full");
        s.releaseCompanion(500);
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt6"), "the follower still counts");
        db.friends.clear();
        s.summon(owner, "Alt6");
        assertFalse(s.reserveCompanion(1, 501), "five alts: no bot friend");
        // racing: the last place is taken by exactly one of an alt summon and a friend's accept
        assertTrue(s.dismiss(owner, "Alt6"));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            var alt = pool.submit(() -> { go.await(); try { s.summon(owner, "Alt7"); return true; } catch (IllegalStateException e) { return false; } });
            var friend = pool.submit(() -> { go.await(); return s.reserveCompanion(1, 502); });
            go.countDown();
            assertTrue(alt.get() ^ friend.get(), "exactly one of them gets the last place");
            assertFalse(s.hasCompanionRoom(1));
        } finally { pool.shutdownNow(); }
    }

    /** Audit B13: the companion driver is stopped before the save reads the alt, never after. */
    @Test void theDriverStopsBeforeTheSave() {
        List<String> order = new ArrayList<>();
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory() {
            @Override public void closing(Character alt) { order.add("closing"); }
            @Override public void save(Character alt) { order.add("save"); super.save(alt); }
        };
        db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        s.summon(owner, "Alt2");
        assertTrue(s.dismiss(owner, "Alt2"));
        assertEquals(List.of("closing", "save"), order);
    }
}
