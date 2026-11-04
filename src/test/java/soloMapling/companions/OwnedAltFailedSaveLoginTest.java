package soloMapling.companions;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AL-1 (ticket 183): after three failed saves the character's failed mark used to lock it out of login and @botme
 * until the server restarted. The mark now goes at login, and a GM can drop it with `!alts clear <id>`.
 */
class OwnedAltFailedSaveLoginTest {

    /** Three failing saves (the give-up cap), then the player logs in: no restart anywhere in sight. */
    @Test void aGivenUpAltLogsInAgainWithoutARestart() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        List<Runnable> later = new ArrayList<>();
        OwnedAltService s = new OwnedAltService(db, () -> true);
        s.retryWith(later::add);
        Character owner = OwnedAltServiceTest.owner(1);
        s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("database unavailable"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
        later.remove(0).run();
        later.remove(0).run(); // third failure: given up, out of the world
        assertTrue(later.isEmpty());
        assertEquals(3, db.saves.get());
        assertFalse(db.online.contains(2), "the ghost is gone");
        db.saving = () -> {};
        try (var login = s.beginLogin(2)) {
            assertNotNull(login, "its login is not held for the life of the process");   // AL-1: threw before
        }
        assertEquals(3, db.saves.get(), "the login reads the last save, it never saves the failed copy again");
        assertTrue(s.clearFailed(2).startsWith("No failed save"), "and the mark is gone with it");
    }

    /** After that login the character is summonable again - @botme no longer answers "still being saved". */
    @Test void theClearedAltCanBeCalledAgainRightAway() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        List<Runnable> later = new ArrayList<>();
        OwnedAltService s = new OwnedAltService(db, () -> true);
        s.retryWith(later::add);
        Character owner = OwnedAltServiceTest.owner(1);
        s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("database unavailable"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
        later.remove(0).run();
        later.remove(0).run();
        db.saving = () -> {};
        try (var login = s.beginLogin(2)) { assertNotNull(login); }
        assertNotNull(s.summon(owner, "Alt2"));
    }

    /** A login that arrives while the alt is still being retried stays blocked: the retry owns it. */
    @Test void aLoginDuringARetryIsStillBlocked() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        List<Runnable> later = new ArrayList<>();
        OwnedAltService s = new OwnedAltService(db, () -> true);
        s.retryWith(later::add);
        Character owner = OwnedAltServiceTest.owner(1);
        s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("database unavailable"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
        assertThrows(IllegalStateException.class, () -> s.beginLogin(2));
    }

    /** `!alts clear <id>` drops the mark on a GM's word, before the player tries to log in. */
    @Test void aGmClearDropsTheFailedMark() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        List<Runnable> later = new ArrayList<>();
        OwnedAltService s = new OwnedAltService(db, () -> true);
        s.retryWith(later::add);
        Character owner = OwnedAltServiceTest.owner(1);
        s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("database unavailable"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
        later.remove(0).run();
        later.remove(0).run();
        assertTrue(s.clearFailed(2).contains("Alt2"), "the GM sees whose save was cleared");
        db.saving = () -> {};
        try (var login = s.beginLogin(2)) { assertNotNull(login); }
        assertEquals(3, db.saves.get(), "clearing never saves again");
    }

    /** Clearing an alt that is not held says so, and clearing a healthy one changes nothing. */
    @Test void clearingSomethingThatIsNotHeldSaysNothingToClear() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        Character owner = OwnedAltServiceTest.owner(1);
        s.summon(owner, "Alt2");
        assertEquals("No failed save is holding character 2.", s.clearFailed(2));
        assertEquals("No failed save is holding character 99.", s.clearFailed(99));
        assertEquals(1, s.dismissAll(owner, true), "the alt is still out, untouched by the clear");
        assertEquals(1, db.saves.get());
    }

    /** A mid-retry alt is not cleared under the retry's feet: it is still out of the world and being saved. */
    @Test void clearingAnAltThatIsStillBeingRetriedWaitsForTheRetry() {
        OwnedAltServiceTest.Memory db = new OwnedAltServiceTest.Memory(); db.add(2);
        OwnedAltService s = new OwnedAltService(db, () -> true);
        s.retryWith(r -> { }); // the retry never runs: the alt stays out with its retry pending
        Character owner = OwnedAltServiceTest.owner(1);
        s.summon(owner, "Alt2");
        db.saving = () -> { throw new IllegalStateException("database unavailable"); };
        assertThrows(IllegalStateException.class, () -> s.dismiss(owner, "Alt2"));
        assertTrue(s.clearFailed(2).contains("still being saved again"));
        assertEquals(1, db.online.size(), "still out of the world until the retry or the give-up");
    }
}
