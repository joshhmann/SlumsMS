package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotRivalryTest {

    static final int PLAYER = 5151;
    static final int BOT = BotStore.BOT_ID_BASE + 91;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final AtomicLong clock = new AtomicLong(2000 * DAY + 1000);

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotRivalry.resetForTest(clock::get);
        DramaKit.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
        BotRivalry.resetForTest(null);
        DramaKit.resetForTest();
    }

    static Character chr(int id, String name, int map) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMapId()).thenReturn(map);
        return c;
    }

    static RivalRoasts.Facts facts(int level, int mobLevel, int deaths, int weaponLevel, int ks) {
        return new RivalRoasts.Facts("Bob", level, "fighter", "ant tunnel", mobLevel, deaths, "sword", weaponLevel, ks);
    }

    static List<String> nodes(RivalRoasts.Facts f) {
        return RivalRoasts.options(f).stream().map(RivalRoasts.Roast::node).toList();
    }

    @Test
    void roastsUseRealFactsJuiciestFirst() {
        assertEquals("RoastOverleveled", nodes(facts(45, 25, 0, -1, 0)).get(0), "lv 45 still in ant tunnel");
        assertFalse(nodes(facts(30, 25, 0, -1, 0)).contains("RoastOverleveled"), "a fair map for the level");
        assertTrue(nodes(facts(30, 25, 3, -1, 0)).contains("RoastDeaths"));
        assertTrue(nodes(facts(60, -1, 0, 30, 0)).contains("RoastGear"), "a lv 30 weapon at 60");
        assertTrue(nodes(facts(60, -1, 0, -1, 4)).contains("RoastKs"));
        assertEquals("RoastLevel", nodes(facts(30, -1, 0, -1, 0)).get(nodes(facts(30, -1, 0, -1, 0)).size() - 1));
        RivalRoasts.Roast r = RivalRoasts.pick(facts(45, 25, 3, -1, 0), new Random(3));
        assertTrue(r.node().equals("RoastOverleveled") || r.node().equals("RoastDeaths"));
        assertEquals("45", r.tokens().get("LEVEL"));
        assertEquals("ant tunnel", r.tokens().get("MAP"));
    }

    @Test
    void theModelPromptCarriesTheFactsAndTheRules() {
        String p = RivalRoasts.prompt("Amy", facts(45, 25, 2, 30, 3), true);
        assertTrue(p.contains("level 45") && p.contains("ant tunnel") && p.contains("2 deaths today"));
        assertTrue(p.contains("gameplay only"));
        assertTrue(p.contains("Never: real-life insults, slurs, looks, family"));
    }

    @Test
    void deathsAreCountedPerDay() {
        Character p = chr(PLAYER, "Bob", 1);
        BotRivalry.onDeath(p);
        BotRivalry.onDeath(p);
        assertEquals(2, BotRivalry.deathsToday(PLAYER));
        clock.addAndGet(DAY);
        assertEquals(0, BotRivalry.deathsToday(PLAYER));
    }

    @Test
    void botDramaOffStopsEveryRivalScene() {
        Character p = chr(PLAYER, "Bob", 100);
        Character b = chr(BOT, "Amy", 100);
        BotAffinity.setOptedOut(p, true);
        assertTrue(BotRivalry.startKsWar(b, p, 3, true).contains("@botdrama off"));
        assertTrue(BotRivalry.callout(b, p, true).contains("@botdrama off"));
        assertEquals("skipped (@botdrama off)", BotRivalry.roast(b, p, null));
        BotRivalry.arm(b, p);
        assertNull(BotRivalry.armedFor(PLAYER), "never armed to follow");
    }

    @Test
    void anArmedRivalFollowsOnlyFromTheMapItWasOn() {
        Character p = chr(PLAYER, "Bob", 100);
        Character b = chr(BOT, "Amy", 100);
        BotRivalry.arm(b, p);
        assertEquals(100, BotRivalry.armedFor(PLAYER).mapId());
        BotRivalry.onMapChanged(p, 555); // came from some other map: not armed for that
        assertEquals(100, BotRivalry.armedFor(PLAYER).mapId());
        BotRivalry.onMapChanged(p, 100); // left the map the rival is on: the arm is used (follow or not)
        assertNull(BotRivalry.armedFor(PLAYER));
    }

    @Test
    void anApologyCalmsMockingEscalatesAndOtherLinesWait() {
        Character p = chr(PLAYER, "Bob", 100);
        Character b = chr(BOT, "Amy", 100);
        BotRivalry.Arc arc = BotRivalry.arcForTest(BOT, PLAYER, clock.get() + 60_000);
        assertFalse(BotRivalry.answer(arc, b, p, "where is the henesys market"), "not an answer yet");
        assertTrue(BotRivalry.arcOpen(PLAYER));
        assertTrue(BotRivalry.answer(arc, b, p, "ok sorry my bad"));
        assertFalse(BotRivalry.arcOpen(PLAYER));
        BotRivalry.Arc second = BotRivalry.arcForTest(BOT, PLAYER, clock.get() + 60_000);
        assertTrue(BotRivalry.answer(second, b, p, "lol cry more"));
        assertFalse(BotRivalry.arcOpen(PLAYER));
    }

    @Test
    void theRevengeFollowPromptOnlyForThatBot() {
        Character p = chr(PLAYER, "Bob", 100);
        BotRivalry.arcForTest(BOT, PLAYER, clock.get() + 60_000);
        assertTrue(BotRivalry.promptLine(BOT, p).contains("apology"));
        assertEquals("", BotRivalry.promptLine(BOT + 1, p));
    }
}
