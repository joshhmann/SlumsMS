package soloMapling.companions;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 boss lane (ticket 130): inside an event instance an owned alt goes for what its party is fighting wherever it
 * stands on the map, instead of only inside the {@link OwnedAltCombat#LEASH_X_PX} px box around its owner. On the Zakum
 * altar the arms are well outside that box, and the alt stood still while its owner fought them. Outside an instance
 * the box is exactly what it always was.
 */
class OwnedAltInstanceFightTest {

    private static final long NOW = 50_000_000L;
    private static final int OWNER = 1, ALT = 2;
    private static final AtomicInteger oids = new AtomicInteger(500);

    /** Round 9: the instance leash is a switch like any other, so a test that wants it turns it on. */
    @BeforeEach
    void switchOn() {
        System.setProperty(OwnedAltCombat.INSTANCE_LEASH_SWITCH, "true");
    }

    @AfterEach
    void switchBack() {
        System.clearProperty(OwnedAltCombat.INSTANCE_LEASH_SWITCH);
    }

    private static MapleMap map(List<Monster> mobs) {
        MapleMap m = mock(MapleMap.class);
        when(m.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        when(m.getCharacters()).thenReturn(List.of());
        return m;
    }

    private static Character at(MapleMap map, int id, int x, boolean alt) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(map);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        when(c.isOwnedAlt()).thenReturn(alt);
        return c;
    }

    /** The same character, but inside an event instance (a boss room or a PQ stage). */
    private static Character inInstance(Character c) {
        when(c.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        return c;
    }

    private static Monster mob(int x, Integer... damagedBy) {
        Monster m = mock(Monster.class);
        when(m.isAlive()).thenReturn(true);
        when(m.isBoss()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, 0));
        when(m.getObjectId()).thenReturn(oids.incrementAndGet());
        when(m.getName()).thenReturn("Zakum's Arm 1");
        Map<Integer, Long> dealt = new HashMap<>();
        for (int id : damagedBy) {
            dealt.put(id, 10L);
            when(m.lastHitBy(id)).thenReturn(NOW - 1_000);
        }
        when(m.damageSnapshot()).thenReturn(dealt);
        return m;
    }

    @Test
    void insideAnInstanceAnAltFacesTheArmItsPartyFightsPastTheLeash() {
        Monster arm = mob(900, OWNER);
        MapleMap f = map(List.of(arm));
        Character owner = at(f, OWNER, 0, false);
        Character alt = inInstance(at(f, ALT, 0, true));

        assertTrue(OwnedAltCombat.driverMayFace(arm, f, owner, alt, Set.of(OWNER, ALT), NOW),
                "an alt in an event instance faces the arm its party fights, wherever the owner stands");
    }

    @Test
    void insideAnInstanceTheAltPicksTheFarArmAsItsTarget() {
        Monster arm = mob(900, OWNER);
        MapleMap f = map(List.of(arm));
        Character owner = at(f, OWNER, 0, false);
        Character alt = inInstance(at(f, ALT, 0, true));

        assertSame(arm, OwnedAltCombat.target(owner, alt, Set.of(OWNER, ALT), NOW),
                "the alt walks to the arm the party fights instead of following its owner");
    }

    @Test
    void insideAnInstanceAQuietMobFarAwayIsStillNotTheAltsFight() {
        Monster quiet = mob(900);
        MapleMap f = map(List.of(quiet));
        Character owner = at(f, OWNER, 0, false);
        Character alt = inInstance(at(f, ALT, 0, true));

        assertFalse(OwnedAltCombat.driverMayFace(quiet, f, owner, alt, Set.of(OWNER, ALT), NOW),
                "an instance does not make every mob on the map the alt's: only the party's own fight");
    }

    @Test
    void insideAPartyQuestInstanceAStrangersMobIsNotTheAlts() {
        // an alt in a PQ instance fights the party's fight, not a stranger's: a real player outside the party who is
        // hitting the mob keeps it (the one KS rule, OwnedAltCombat.strangers), instance or not
        Monster theirs = mob(300, 3);
        MapleMap f = map(List.of(theirs));
        Character owner = at(f, OWNER, 0, false);
        Character alt = inInstance(at(f, ALT, 0, true));

        assertFalse(OwnedAltCombat.driverMayFace(theirs, f, owner, alt, Set.of(OWNER, ALT), NOW),
                "a stranger's mob is not the party's fight, even inside an instance");
        assertSame(null, OwnedAltCombat.target(owner, alt, Set.of(OWNER, ALT), NOW),
                "and the alt does not pick it either");
    }

    @Test
    void outsideAnInstanceTheOwnerBoxStillHolds() {
        Monster far = mob(900, OWNER);
        MapleMap f = map(List.of(far));
        Character owner = at(f, OWNER, 0, false);
        Character alt = at(f, ALT, 0, true);

        assertFalse(OwnedAltCombat.driverMayFace(far, f, owner, alt, Set.of(OWNER, ALT), NOW),
                "in the open world the 650 px leash is unchanged");
        assertFalse(OwnedAltCombat.driverMayFace(far, f, owner, alt, Set.of(OWNER, ALT), NOW), "and it stays that way");
    }

    /** Round 9: off unless a server turns it on (the release conf carries the on line). */
    @Test
    void theInstanceLeashIsOffUnlessAServerTurnsItOn() {
        System.clearProperty(OwnedAltCombat.INSTANCE_LEASH_SWITCH);
        Monster arm = mob(900, OWNER);
        MapleMap f = map(List.of(arm));
        Character owner = at(f, OWNER, 0, false);
        Character alt = inInstance(at(f, ALT, 0, true));
        assertFalse(OwnedAltCombat.instanceLeashOn(), "-Dslums.altfight.instanceleash is off by default");
        assertFalse(OwnedAltCombat.driverMayFace(arm, f, owner, alt, Set.of(OWNER, ALT), NOW),
                "untouched, an instance still reads the owner's box");
    }

    @Test
    void theSwitchOffKeepsTheOwnerBoxInsideAnInstanceToo() {
        System.setProperty(OwnedAltCombat.INSTANCE_LEASH_SWITCH, "false");
        Monster arm = mob(900, OWNER);
        MapleMap f = map(List.of(arm));
        Character owner = at(f, OWNER, 0, false);
        Character alt = inInstance(at(f, ALT, 0, true));

        assertFalse(OwnedAltCombat.driverMayFace(arm, f, owner, alt, Set.of(OWNER, ALT), NOW),
                "-Dslums.altfight.instanceleash=false restores the leash everywhere");
    }
}
