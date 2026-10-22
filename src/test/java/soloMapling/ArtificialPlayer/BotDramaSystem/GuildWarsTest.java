package soloMapling.ArtificialPlayer.BotDramaSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuildWarsTest {

    static final long DAY = TimeUnit.DAYS.toMillis(1);
    final GuildWarStore.Memory store = new GuildWarStore.Memory();
    final AtomicLong clock = new AtomicLong(3000 * DAY);

    @BeforeEach
    void setUp() {
        GuildWars.useForTest(store, clock::get);
    }

    @AfterEach
    void tearDown() {
        GuildWars.useForTest(null, null);
    }

    @Test
    void turfGoesToTheSideWithMoreMembersOnEachGrindMap() {
        Map<Integer, int[]> presence = new HashMap<>();
        presence.put(100, new int[]{2, 1});
        presence.put(200, new int[]{0, 3});
        presence.put(300, new int[]{1, 1});
        GuildWarRules.Turf t = GuildWarRules.turf(presence, 7, 9);
        assertEquals(1, t.pointsA());
        assertEquals(1, t.pointsB());
        assertEquals(7, t.holder().get(100));
        assertEquals(9, t.holder().get(200));
        assertNull(t.holder().get(300), "a tie: nobody holds it");
    }

    @Test
    void aPlayerGuildGetsAWarOnlyWhenHalfItsOnlineMembersAreRivals() {
        assertTrue(GuildWarRules.declareOnPlayers(1, 1));
        assertTrue(GuildWarRules.declareOnPlayers(4, 2));
        assertFalse(GuildWarRules.declareOnPlayers(5, 2));
        assertFalse(GuildWarRules.declareOnPlayers(3, 0), "no rival, no war");
        assertFalse(GuildWarRules.declareOnPlayers(0, 0), "nobody online (or all opted out)");
    }

    @Test
    void winnersAndDaysLeft() {
        assertEquals(7, GuildWarRules.winner(7, 30, 9, 12));
        assertEquals(9, GuildWarRules.winner(7, 3, 9, 12));
        assertEquals(0, GuildWarRules.winner(7, 5, 9, 5));
        assertEquals(7, GuildWarRules.daysLeft(10 * DAY, 3 * DAY));
        assertEquals(1, GuildWarRules.daysLeft(3 * DAY + 1, 3 * DAY));
        assertEquals(0, GuildWarRules.daysLeft(DAY, 3 * DAY));
    }

    @Test
    void warsAndAlliancesAreStoredAndReadBack() {
        GuildWarStore.War w = GuildWars.openForTest(GuildWarStore.Kind.WAR, 7, 9, false);
        GuildWarStore.War a = GuildWars.openForTest(GuildWarStore.Kind.ALLIANCE, 7, 11, false);
        assertEquals(clock.get() + GuildWarRules.warMs(), w.endsAt(), "a week");
        assertEquals(List.of(11), GuildWars.alliesOf(7));
        assertNotNull(GuildWars.warBetween(9, 7));
        GuildWars.useForTest(store, clock::get); // a restart
        assertEquals(2, GuildWars.active().size());
        GuildWars.endQuietly(GuildWars.byId(a.id()));
        assertTrue(GuildWars.alliesOf(7).isEmpty());
        assertEquals(1, GuildWars.active().size());
    }

    @Test
    void aFailedReadKeepsNothing() {
        store.failing = true;
        assertTrue(GuildWars.active().isEmpty());
        assertNull(GuildWars.warBetween(7, 9));
    }
}
