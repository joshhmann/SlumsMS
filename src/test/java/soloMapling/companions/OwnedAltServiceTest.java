package soloMapling.companions;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedAltServiceTest {
    static Character owner(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getAccountID()).thenReturn(7);
        when(c.getWorld()).thenReturn(1);
        when(c.isLoggedin()).thenReturn(true);
        when(c.getClient()).thenReturn(mock(Client.class));
        return c;
    }
    static class Memory implements OwnedAltService.Backend {
        List<OwnedAltService.Candidate> candidates = new ArrayList<>();
        Set<Integer> online = ConcurrentHashMap.newKeySet();
        AtomicInteger saves = new AtomicInteger(), loads = new AtomicInteger();
        Runnable saving = () -> {}, detaching = () -> {};
        Set<Integer> friends = new HashSet<>();
        public List<OwnedAltService.Candidate> list(Character owner) { return candidates; }
        public boolean online(int id) { return online.contains(id); }
        final List<Character> loaded = new java.util.concurrent.CopyOnWriteArrayList<>();
        public Character load(OwnedAltService.Candidate row, Character owner) {
            loads.incrementAndGet();
            Character alt = mock(Character.class);
            loaded.add(alt);
            when(alt.getId()).thenReturn(row.id());
            when(alt.getName()).thenReturn(row.name());
            when(alt.getAccountID()).thenReturn(row.accountId());
            when(alt.getWorld()).thenReturn(row.world());
            return alt;
        }
        public void attach(Character owner, Character alt) { online.add(alt.getId()); }
        public void save(Character alt) { order.add("save " + alt.getId()); saves.incrementAndGet(); saving.run(); }
        public void detach(Character alt) { detaching.run(); online.remove(alt.getId()); }
        public Set<Integer> companions(int ownerId) { return friends; }
        final List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void leaveEvent(Character alt) { order.add("leaveEvent " + alt.getId()); }
        void add(int id) { candidates.add(new OwnedAltService.Candidate(id, 7, 1, "Alt" + id)); }
    }
    @Test void validatesAccountWorldOnlineAndIdentity() {
        Memory db = new Memory(); db.add(2);
        db.candidates.add(new OwnedAltService.Candidate(3, 8, 1, "Other"));
        db.candidates.add(new OwnedAltService.Candidate(4, 7, 0, "Elsewhere"));
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Other"));
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Elsewhere"));
        db.online.add(2);
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt2"));
        db.online.clear();
        s.summon(owner, "Alt2");
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt2"));
        assertEquals(1, db.loads.get());
    }
    @Test void disabledByDefaultAndSharedFiveSlotLimit() {
        Memory db = new Memory(); db.add(2); db.add(3);
        Character owner = owner(1);
        assertThrows(IllegalStateException.class, () -> new OwnedAltService(db, () -> false).summon(owner, "Alt2"));
        OwnedAltService s = new OwnedAltService(db, () -> true);
        db.friends.addAll(Set.of(100,101,102,103));
        s.summon(owner, "Alt2");
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt3"));
        assertFalse(s.reserveCompanion(1, 104));
        assertTrue(s.reserveCompanion(9, 105));
        assertFalse(s.reserveCompanion(20, 105), "one friend reservation cannot belong to two owners");
    }
    // Owner 2026-10-26: five companions in all, owned alts and bot friends together.
    @Test void fiveAltsFillTheCompanionCap() {
        Memory db = new Memory();
        for (int id = 2; id <= 7; id++) db.add(id);
        Character owner = owner(1);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        for (int id = 2; id <= 6; id++) s.summon(owner, "Alt" + id);
        assertFalse(s.hasCompanionRoom(1));
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt7"), "a sixth alt");
        assertFalse(s.reserveCompanion(1, 200), "a bot friend on top of five alts");
        s.dismiss(owner, "Alt2");
        assertTrue(s.hasCompanionRoom(1));
        assertTrue(s.reserveCompanion(1, 200), "a dismissed alt frees its place for a friend");
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt7"), "and the friend fills it again");
    }
    @Test void loginSavesAndRemovesBeforeAllowingReloadAndReservesUntilRegistration() {
        Memory db = new Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1); s.summon(owner, "Alt2");
        try (var login = s.beginLogin(2)) {
            assertFalse(db.online.contains(2));
            assertEquals(1, db.saves.get());
            assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt2"));
            assertThrows(IllegalStateException.class, () -> s.beginLogin(2));
        }
        assertEquals(0, s.dismissAll(owner, false));
        assertEquals(1, db.saves.get());
    }
    @Test void dismissSaveRacingLoginRefusesWithoutReloadOrSecondSave() throws Exception {
        Memory db = new Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1); s.summon(owner, "Alt2");
        CountDownLatch saving = new CountDownLatch(1), finish = new CountDownLatch(1);
        db.saving = () -> {
            saving.countDown();
            try { assertTrue(finish.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> dismissed = worker.submit(() -> s.dismiss(owner, "Alt2"));
            assertTrue(saving.await(10, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> s.beginLogin(2));
            assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
            finish.countDown(); dismissed.get(10, TimeUnit.SECONDS);
            try (var login = s.beginLogin(2)) { assertFalse(db.online.contains(2)); }
            assertEquals(1, db.saves.get());
        } finally { finish.countDown(); worker.shutdownNow(); }
    }
    @Test void failedSaveKeepsLoginBlockedAndAllowsExplicitRetry() {
        Memory db = new Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1); s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("database unavailable"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
        assertThrows(IllegalStateException.class, () -> s.beginLogin(2));
        db.saving = () -> {};
        assertEquals(1, s.dismissAll(owner, true));
        try (var login = s.beginLogin(2)) { assertFalse(db.online.contains(2)); }
    }
    @Test void logoutClosesOwnerSessionAndSavesEveryAltExactlyOnce() {
        Memory db = new Memory(); db.add(2); db.add(3);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1); s.summon(owner, "Alt2"); s.summon(owner, "Alt3");
        assertEquals(2, s.dismissAll(owner, true));
        assertEquals(0, s.dismissAll(owner, true));
        assertEquals(2, db.saves.get());
        assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt2"));
    }
    @Test void failedSaveCanBeRetriedBySameOwnerAfterRelog() {
        Memory db = new Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1); s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("unavailable"); };
        assertThrows(IllegalStateException.class, () -> s.dismissAll(owner, true));
        db.saving = () -> {};
        assertTrue(s.dismiss(owner(1), "Alt2"));
        try (var login = s.beginLogin(2)) { assertFalse(db.online.contains(2)); }
    }
    @Test void ownerLogoutDuringLoadCannotPublishAnOrphanAlt() throws Exception {
        CountDownLatch loading = new CountDownLatch(1), finish = new CountDownLatch(1);
        Memory db = new Memory() {
            @Override public Character load(OwnedAltService.Candidate row, Character owner) {
                loading.countDown();
                try { assertTrue(finish.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return super.load(row, owner);
            }
        };
        db.add(2); OwnedAltService s = new OwnedAltService(db, () -> true); Character owner = owner(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> load = worker.submit(() -> s.summon(owner, "Alt2"));
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> s.summon(owner, "Alt2"));
            assertThrows(IllegalStateException.class, () -> s.beginLogin(2));
            assertEquals(0, s.dismissAll(owner,true));
            finish.countDown();
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class,
                    () -> load.get(10, TimeUnit.SECONDS)).getCause());
            assertTrue(db.online.isEmpty()); assertEquals(0,db.saves.get());
            try (var login = s.beginLogin(2)) { assertTrue(db.online.isEmpty()); }
        } finally { finish.countDown(); worker.shutdownNow(); }
    }

    @Test void cleanupRetryAfterCommittedSaveNeverSavesTwice() {
        Memory db = new Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true); Character owner = owner(1);
        s.summon(owner,"Alt2");
        db.detaching = () -> { throw new IllegalStateException("cleanup failed"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner,"Alt2"));
        assertThrows(IllegalStateException.class, () -> s.beginLogin(2));
        db.detaching = () -> {};
        assertTrue(s.dismiss(owner,"Alt2")); assertEquals(1,db.saves.get());
    }


    /** 1.6.2 (bot audit K6): an alt sent home leaves its event first, so it is saved in town and ends no PQ. */
    @Test void anAltLeavesItsEventBeforeItIsSaved() {
        Memory db = new Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = owner(1);
        s.summon(owner, "Alt2");
        s.dismiss(owner, "Alt2");
        assertEquals(List.of("leaveEvent 2", "save 2"), db.order);
    }
}
