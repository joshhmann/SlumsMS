package soloMapling.companions;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 ticket 13: an owned alt fights what its party fights - a mob its owner (or the party, or itself) damaged, or one
 * that just hit its owner or itself - near its owner, and never a stranger's mob (KS manners).
 */
class OwnedAltCombatTest {

    static final long NOW = 50_000_000L;
    static final int OWNER = 1, ALT = 2, STRANGER = 3, BOT = 1_000_000_123;
    private static final AtomicInteger oids = new AtomicInteger(100);
    private final List<Character> chars = new ArrayList<>();

    @AfterEach
    void forget() {
        OwnedAltCombat.forget(OWNER);
        OwnedAltCombat.forget(ALT);
    }

    MapleMap map(List<Monster> mobs) {
        MapleMap m = mock(MapleMap.class);
        when(m.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        when(m.getCharacters()).thenReturn(chars);
        return m;
    }

    static Character at(MapleMap map, int id, int x, boolean alt) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(map);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        when(c.isOwnedAlt()).thenReturn(alt);
        return c;
    }

    static Monster mob(int x, Integer... damagedBy) {
        Monster m = mock(Monster.class);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, 0));
        when(m.getObjectId()).thenReturn(oids.incrementAndGet());
        when(m.getName()).thenReturn("Snail");
        Map<Integer, Long> dealt = new HashMap<>();
        for (int id : damagedBy) {
            dealt.put(id, 10L);
            when(m.lastHitBy(id)).thenReturn(NOW - 1_000);
        }
        when(m.damageSnapshot()).thenReturn(dealt);
        return m;
    }

    @Test
    void itGoesForTheMobItsOwnerIsFighting() {
        Monster quiet = mob(20);
        Monster owners = mob(200, OWNER);
        MapleMap f = map(List.of(quiet, owners));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        assertSame(owners, OwnedAltCombat.target(owner, alt, party, NOW), "not the nearer quiet mob: it never starts alone");
        assertTrue(OwnedAltCombat.mayHit(quiet, f, party, NOW), "its area attacks may still catch a free mob");
    }

    @Test
    void aMobThatHitTheAltIsItsFightTooForAFewSeconds() {
        Monster biter = mob(40);
        MapleMap f = map(List.of(biter));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        assertNull(OwnedAltCombat.target(owner, alt, party, NOW));
        OwnedAltCombat.noteHit(alt, biter, NOW);
        assertSame(biter, OwnedAltCombat.target(owner, alt, party, NOW + 1_000));
        assertNull(OwnedAltCombat.target(owner, alt, party, NOW + OwnedAltCombat.HIT_MEMORY_MS + 1));
    }

    /** Audit b135: a touch on the owner is let go when it is old, the mob died, or the owner's alts went home. */
    @Test
    void anOwnersTouchIsReleased() {
        Monster biter = mob(40);
        MapleMap f = map(List.of(biter));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        OwnedAltCombat.noteHit(alt, biter, NOW); // an alt's touch is always kept
        assertTrue(OwnedAltCombat.recentlyHit(ALT, biter, NOW + 1_000));
        when(biter.isAlive()).thenReturn(false);
        assertFalse(OwnedAltCombat.recentlyHit(ALT, biter, NOW + 1_000), "dead: let go");
        assertTrue(OwnedAltCombat.touchesKept(ALT) == 0, "and forgotten (its object id can come back)");
        Monster other = mob(80);
        OwnedAltCombat.noteHit(alt, other, NOW);
        Monster later = mob(90);
        OwnedAltCombat.noteHit(alt, later, NOW + OwnedAltCombat.HIT_MEMORY_MS + 1);
        assertTrue(OwnedAltCombat.touchesKept(ALT) == 1, "the old touch went when a new one came");
        OwnedAltCombat.retainOwners(Set.of(), Set.of());
        assertTrue(OwnedAltCombat.touchesKept(ALT) == 0, "nothing kept for characters no longer out");
    }

    @Test
    void ownerHitsAreOnlyRememberedForOwnersWithAltsOut() {
        Monster biter = mob(40);
        MapleMap f = map(List.of(biter));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        OwnedAltCombat.noteHit(owner, biter, NOW); // no alts out: nothing kept (a busy map costs nothing)
        assertNull(OwnedAltCombat.target(owner, alt, OwnedAltCombat.partyIds(alt, owner), NOW));
    }

    @Test
    void neverAStrangersMob() {
        Monster theirs = mob(30, STRANGER);
        Monster shared = mob(60, OWNER, STRANGER);
        MapleMap f = map(List.of(theirs, shared));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        OwnedAltCombat.noteHit(alt, theirs, NOW); // even one that bumped into the alt
        assertNull(OwnedAltCombat.target(owner, alt, party, NOW), "a real player outside the party hit both");
        assertFalse(OwnedAltCombat.mayHit(theirs, f, party, NOW), "and its area attacks leave them alone");
        assertFalse(OwnedAltCombat.mayHit(shared, f, party, NOW));
    }

    @Test
    void aBotsMobIsTheBotsUnlessThePartyWasOnItToo() {
        Monster botOnly = mob(30, BOT);
        Monster both = mob(60, BOT, OWNER);
        MapleMap f = map(List.of(botOnly, both));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        assertFalse(OwnedAltCombat.mayHit(botOnly, f, party, NOW));
        assertSame(both, OwnedAltCombat.target(owner, alt, party, NOW));
    }

    /** Audit 163 (A3): a population bot that was on a mob before the party keeps it; one that came later does not. */
    @Test
    void theEarlierClaimWinsBetweenThePartyAndAPopulationBot() {
        Monster botFirst = mob(30, BOT, OWNER);
        when(botFirst.hitStreakStart(BOT)).thenReturn(NOW - 5_000);
        when(botFirst.hitStreakStart(OWNER)).thenReturn(NOW - 1_000);
        Monster partyFirst = mob(60, BOT, OWNER);
        when(partyFirst.hitStreakStart(OWNER)).thenReturn(NOW - 5_000);
        when(partyFirst.hitStreakStart(BOT)).thenReturn(NOW - 1_000);
        MapleMap f = map(List.of(botFirst, partyFirst));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        assertTrue(OwnedAltCombat.strangers(botFirst, f, party, NOW), "the bot was on it first: its mob");
        assertFalse(OwnedAltCombat.strangers(partyFirst, f, party, NOW), "the bot joined the party's fight");
        assertSame(partyFirst, OwnedAltCombat.target(owner, alt, party, NOW));
    }

    /** Audit 163: a stranger's hit long ago does not fence a mob off from the party for ever. */
    @Test
    void aStrangersOldHitLapses() {
        Monster m = mob(30, OWNER, STRANGER);
        when(m.lastHitBy(STRANGER)).thenReturn(NOW - 60_000);
        MapleMap f = map(List.of(m));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        assertFalse(OwnedAltCombat.strangers(m, f, OwnedAltCombat.partyIds(alt, owner), NOW));
    }

    @Test
    void aMobAStrangerStandsByBeforeThePartyTouchedItIsTheirs() {
        Monster m = mob(400);
        MapleMap f = map(List.of(m));
        Character stranger = at(f, STRANGER, 450, false);
        when(stranger.getClient()).thenReturn(mock(client.Client.class)); // a real player, not a bot
        chars.add(stranger);
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        chars.add(owner);
        chars.add(alt);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        OwnedAltCombat.noteHit(alt, m, NOW);
        assertTrue(OwnedAltCombat.strangers(m, f, party, NOW));
        assertNull(OwnedAltCombat.target(owner, alt, party, NOW));
    }

    @Test
    void onlyByItsOwner() {
        Monster far = mob(OwnedAltCombat.LEASH_X_PX + 50, OWNER);
        MapleMap f = map(List.of(far));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        assertNull(OwnedAltCombat.target(owner, alt, OwnedAltCombat.partyIds(alt, owner), NOW));
        Character elsewhere = at(map(List.of(mob(5, OWNER))), OWNER, 0, false);
        assertNull(OwnedAltCombat.target(elsewhere, alt, OwnedAltCombat.partyIds(alt, elsewhere), NOW),
                "its owner is on another map: it follows");
    }

    @Test
    void anOldHitOfTheOwnersIsNotTheirFightAnyMore() {
        // QA run 3 (TEST 2026-10-29): a mob the owner hit in an earlier fight stayed "engaged" for good
        Monster old = mob(40, OWNER);
        when(old.lastHitBy(OWNER)).thenReturn(NOW - OwnedAltCombat.ENGAGED_MS - 1);
        MapleMap f = map(List.of(old));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        assertNull(OwnedAltCombat.target(owner, alt, OwnedAltCombat.partyIds(alt, owner), NOW));
    }

    @Test
    void notAMobOnAnotherLedgeNorOneItGaveUpOn() {
        Monster above = mob(40, OWNER);
        when(above.getPosition()).thenReturn(new Point(40, -240));
        Monster level = mob(200, OWNER);
        MapleMap f = map(List.of(above, level));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        BotAttackDriver.Ledges before = BotAttackDriver.ledges;
        try {
            BotAttackDriver.ledges = (map, a, b) -> Math.abs(a.y - b.y) > 90;
            assertSame(level, OwnedAltCombat.target(owner, alt, party, NOW), "the nearer one is up on a platform");
            assertNull(OwnedAltCombat.target(owner, alt, party, NOW, m -> m == level), "left alone after a stall");
        } finally {
            BotAttackDriver.ledges = before;
        }
    }

    @Test
    void aLowLedgeTheDriverWillNotFaceIsNotATargetEither() {
        // t-oa-1 (TEST 2026-10-29): its own rule called a ledge 50 px below "the same" (only > 90 px counted), the
        // driver's did not; the alt stood at that mob while the driver found nothing to face, so it never looted
        Monster below = mob(40, OWNER);
        when(below.getPosition()).thenReturn(new Point(40, 50));
        MapleMap f = map(List.of(below));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        BotAttackDriver.Ledges before = BotAttackDriver.ledges;
        try {
            BotAttackDriver.ledges = (map, a, b) -> a.y != b.y; // the nav graph: another region
            assertFalse(BotAttackDriver.onAttackableSurface(f, alt.getPosition(), below));
            assertNull(OwnedAltCombat.target(owner, alt, OwnedAltCombat.partyIds(alt, owner), NOW),
                    "it follows instead of standing at a mob it cannot swing at");
        } finally {
            BotAttackDriver.ledges = before;
        }
    }

    /** Audit (A2): an area swing never catches a party-engaged mob beyond the 650 px owner leash. */
    @Test
    void anAreaSwingKeepsToTheOwnerLeash() {
        Monster near = mob(600, OWNER);
        Monster far = mob(700, OWNER);   // engaged, 690 px from the alt's... 700 px from the owner
        MapleMap f = map(List.of(near, far));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 300, true);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        assertTrue(OwnedAltCombat.driverMayFace(near, f, owner, alt, party, NOW));
        assertFalse(OwnedAltCombat.driverMayFace(far, f, owner, alt, party, NOW),
                "within the alt's 700 px seek range, but past the owner leash");
    }

    @Test
    void theDriverOnlyFacesThePartysFightNeverAQuietWildMob() {
        // t-oa-1: the attack driver faced the nearest wild mob (mayHit alone), not the alt's target
        Monster quiet = mob(20);
        Monster owners = mob(200, OWNER);
        MapleMap f = map(List.of(quiet, owners));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
        assertFalse(OwnedAltCombat.driverMayFace(quiet, f, owner, alt, party, NOW));
        assertTrue(OwnedAltCombat.driverMayFace(owners, f, owner, alt, party, NOW));
    }

    @Test
    void theNearestEngagedMobFirst() {
        Monster a = mob(300, OWNER), b = mob(-80, ALT), c = mob(500, OWNER);
        MapleMap f = map(List.of(a, b, c));
        Character owner = at(f, OWNER, 0, false), alt = at(f, ALT, 10, true);
        assertSame(b, OwnedAltCombat.target(owner, alt, OwnedAltCombat.partyIds(alt, owner), NOW));
    }
}
