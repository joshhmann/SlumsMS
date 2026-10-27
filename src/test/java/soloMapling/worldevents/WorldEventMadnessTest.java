package soloMapling.worldevents;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 madness (owner, TEST 2026-10-27: "none of the current playerbots in town are participating, there should be
 * madness, even lower levels should get in"): an invasion calls lv 1-40, most called bots go at once (not through the
 * brain's offer, which turned 76 of 76 down), the closest go first, arrivals stop under the map's bot cap, the town
 * bots on the map fight or run, and the deaths stay a handful (paced and capped).
 */
class WorldEventMadnessTest {

    static final int HENESYS = 100000000;
    private static final int BASE = BotStore.BOT_ID_BASE + 870_000;
    private static final java.util.function.Predicate<TrainingBot> BRAIN = WorldEventReactions.brain;
    private final List<Integer> added = new ArrayList<>();

    @BeforeEach
    void clean() {
        WorldEventSteer.clearForTest();
        WorldEvents.getInstance().resetForTest();
        InvasionTownCrowd.free = bot -> true;
    }

    @AfterEach
    void after() {
        added.forEach(CharacterStorage::removeActiveBot);
        added.forEach(id -> BotDeaths.clearBot(id));
        WorldEventReactions.brain = BRAIN;
        WorldEventSteer.clearForTest();
        WorldEvents.getInstance().resetForTest();
    }

    static ActiveEvent invasion(int id, int cap) {
        EventTemplate t = new EventTemplate("henesys_invasion", EventTemplate.Kind.INVASION, "Henesys Invasion", 2,
                30, 600, 1, 40, cap, List.of(EventTemplate.Spot.map(HENESYS)),
                List.of(new EventTemplate.Wave(2110200, 20, 0)), 6300005, 0, 1, List.of(), true,
                EventTemplate.Slot.HEADLINE, 150_000, List.of(), 0);
        return new ActiveEvent(id, t, t.spots().get(0), 1, System.currentTimeMillis(), "test", 1, 40);
    }

    static TrainingBot trainer(int id, int mapId) {
        TrainingBot tb = mock(TrainingBot.class);
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getLevel()).thenReturn(5);
        when(tb.getChr()).thenReturn(chr);
        when(tb.lifeRuntime()).thenReturn(mock(LifeRuntime.class));
        return tb;
    }

    // ── the band ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void theShippedHenesysInvasionCallsLevelOneToForty() throws Exception {
        EventTemplates.Result r;
        try (FileReader in = new FileReader(EventTemplates.DEFAULT_PATH)) {
            r = EventTemplates.parse(in);
        }
        EventTemplate henesys = r.templates().stream().filter(t -> t.id().equals("henesys_invasion")).findFirst()
                .orElseThrow();
        int[] band = WorldEvents.band(henesys, henesys.spots().get(0));
        assertTrue(WorldEventReactions.levelFits(1, band[0], band[1]), "a lv 1 answers");
        assertTrue(WorldEventReactions.levelFits(40, band[0], band[1]));
        assertFalse(WorldEventReactions.levelFits(41, band[0], band[1]), "a lv 41 would not bother");
        assertTrue(WorldEvents.crowdCap(henesys) > 40, "more than the old 40");
        assertTrue(WorldEvents.crowdCap(henesys) <= soloMapling.ArtificialPlayer.BotMapCap.DEFAULT_WORLD_EVENT_MAP_MAX
                - 40, "the crowd leaves room for the town's own bots under WORLD_EVENT_MAP_MAX");
    }

    // ── who goes, and how fast ────────────────────────────────────────────────────────────────────

    @Test
    void mostCalledBotsGoAtOnceAndTheRestAreOffered() {
        assertTrue(WorldEventReactions.goesDirectly(EventTemplate.Kind.INVASION, 0.1));
        assertTrue(WorldEventReactions.goesDirectly(EventTemplate.Kind.INVASION, 0.74));
        assertFalse(WorldEventReactions.goesDirectly(EventTemplate.Kind.INVASION, 0.76));
        assertFalse(WorldEventReactions.goesDirectly(EventTemplate.Kind.GIANT_CAKE, 0.1), "a cake still asks the brain");

        WorldEventReactions.brain = tb -> true; // every bot runs its brain (the TEST case: 0 joined, 76 offered)
        ActiveEvent e = invasion(51, 45);
        List<TrainingBot> pool = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            pool.add(trainer(BASE + i, 100010000));
        }
        int[] took = WorldEventReactions.take(e, pool, new Random(7), 200, Integer.MAX_VALUE, Map.of(),
                System.currentTimeMillis());
        int joined = took[0];
        int offered = took[1];
        assertEquals(200, joined + offered);
        assertTrue(joined > 130 && joined < 170, "about 75% go at once: " + joined);
        assertEquals(joined, e.botsJoined());
        int steered = 0;
        for (TrainingBot tb : pool) {
            if (WorldEventSteer.mapFor(tb.getChr().getId()) == HENESYS) {
                steered++;
            }
        }
        assertEquals(joined, steered, "every direct joiner is on its way");
    }

    @Test
    void theCrowdCapAndTheMapsRoomHold() {
        WorldEventReactions.brain = tb -> false;
        ActiveEvent e = invasion(52, 45);
        List<TrainingBot> pool = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pool.add(trainer(BASE + i, 100010000));
        }
        // rolls under the direct share always go: use a seed and count; the crowd cap stops it at the room given
        int[] took = WorldEventReactions.take(e, pool, new Random(3), 10, Integer.MAX_VALUE, Map.of(), 0);
        assertEquals(10, took[0], "the crowd cap");

        WorldEventSteer.clearForTest();
        ActiveEvent f = invasion(53, 45);
        List<TrainingBot> mixed = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            mixed.add(trainer(BASE + 200 + i, 100010000)); // a walk away
        }
        for (int i = 0; i < 6; i++) {
            mixed.add(trainer(BASE + 300 + i, HENESYS)); // already in town
        }
        int[] got = WorldEventReactions.take(f, mixed, new Random(5), 45, 3, Map.of(), 0);
        int walkers = 0;
        int townies = 0;
        for (int id : f.joinedBots) {
            if (id >= BASE + 300) {
                townies++;
            } else {
                walkers++;
            }
        }
        assertTrue(walkers <= 3, "no more walk-ons than the map has room for: " + walkers);
        assertTrue(townies >= 4, "bots already on the map still join: " + townies);
        assertEquals(got[0], f.botsJoined());

        // the map's room: its cap less the reserve, the bots on it and those on their way
        assertEquals(45 - 30 - 4, WorldEventReactions.mapRoom(50, 30, 4));
        assertEquals(0, WorldEventReactions.mapRoom(50, 48, 0));
        assertEquals(Integer.MAX_VALUE, WorldEventReactions.mapRoom(0, 80, 0), "0 = no cap");
    }

    @Test
    void theClosestBotsAreCalledFirst() {
        Map<Integer, Integer> hops = Map.of(100010000, 1, 100020000, 2, 104040000, 4);
        assertEquals(0, WorldEventReactions.tier(HENESYS, HENESYS, hops));
        assertEquals(1, WorldEventReactions.tier(100010000, HENESYS, hops));
        assertEquals(2, WorldEventReactions.tier(100020000, HENESYS, hops));
        assertEquals(WorldEventReactions.FAR_TIER, WorldEventReactions.tier(104040000, HENESYS, hops));
        assertEquals(WorldEventReactions.FAR_TIER, WorldEventReactions.tier(200000000, HENESYS, hops));

        WorldEventReactions.brain = tb -> false;
        ActiveEvent e = invasion(54, 45);
        List<TrainingBot> pool = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pool.add(trainer(BASE + i, 104040000)); // far
        }
        for (int i = 0; i < 5; i++) {
            pool.add(trainer(BASE + 100 + i, 100010000)); // next door
        }
        WorldEventReactions.take(e, pool, new Random(11), 3, Integer.MAX_VALUE, hops, 0);
        for (int id : e.joinedBots) {
            assertTrue(id >= BASE + 100, "a next-door bot goes before a far one: " + id);
        }
        assertEquals(3, e.botsJoined());
    }

    // ── the town bots on the map ──────────────────────────────────────────────────────────────────

    private MapleMap henesys(List<Character> here) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(HENESYS);
        when(m.getChannelId()).thenReturn(1);
        when(m.getAllPlayers()).thenReturn(here);
        when(m.getReturnMapId()).thenReturn(HENESYS);
        return m;
    }

    private Character chr(int n, MapleMap map) {
        Character chr = spy(Character.getDefault(new BotClient(0, 0)));
        chr.setID(BASE + n);
        chr.setName("Town" + n);
        chr.setPosition(new Point(100 + n, 200));
        doReturn(map).when(chr).getMap();
        doReturn(HENESYS).when(chr).getMapId();
        return chr;
    }

    private <T extends BotSM> T running(T bot) throws Exception {
        Field state = BotSM.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(bot, BotSM.BotState.RUNNING);
        bot.setRunning(true);
        CharacterStorage.addActiveBot(bot.getChr().getId(), bot);
        added.add(bot.getChr().getId());
        return bot;
    }

    @Test
    void theTownBotsOnTheMapFightOrRunAndOnlyThey() throws Exception {
        List<Character> here = new ArrayList<>();
        MapleMap map = henesys(here);
        List<BotSM> town = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Character c = chr(i, map);
            here.add(c);
            town.add(running(i % 2 == 0 ? new TownWandererBot(c) : new SocialBot(c)));
        }
        Character merchantChr = chr(50, map);
        here.add(merchantChr);
        BotSM merchant = mock(SellingMerchantBot.class);
        when(merchant.getChr()).thenReturn(merchantChr);
        CharacterStorage.addActiveBot(merchantChr.getId(), merchant);
        added.add(merchantChr.getId());
        Character busyChr = chr(51, map);
        here.add(busyChr);
        BotSM busy = running(new TownWandererBot(busyChr));
        InvasionTownCrowd.free = bot -> bot != busy; // talking to a player, say

        ActiveEvent e = invasion(61, 45);
        int took = InvasionTownCrowd.rally(e, map, System.currentTimeMillis(), new Random(9));
        assertEquals(20, took, "every free town bot reacts");
        assertFalse(InvasionTownCrowd.holds(merchantChr.getId()), "a merchant keeps its shop");
        assertFalse(InvasionTownCrowd.holds(busyChr.getId()), "a committed bot stays with its player");
        int fighters = 0;
        for (BotSM b : town) {
            int id = b.getChr().getId();
            assertTrue(InvasionTownCrowd.holds(id));
            if (InvasionTownCrowd.roleOf(id) == InvasionTownCrowd.Role.FIGHT) {
                fighters++;
            }
        }
        assertTrue(fighters >= 5 && fighters <= 15, "about half fight: " + fighters);
        assertEquals(0, InvasionTownCrowd.rally(e, map, System.currentTimeMillis(), new Random(9)), "once each");

        // the event ends: each goes back to its day on its own next tick
        InvasionTownCrowd.release(e.id);
        for (BotSM b : town) {
            assertFalse(InvasionTownCrowd.handles(b), "let go, its own state machine runs");
            assertFalse(InvasionTownCrowd.holds(b.getChr().getId()));
        }
    }

    @Test
    void aCommitmentOrLeavingTheMapEndsItAtOnce() throws Exception {
        List<Character> here = new ArrayList<>();
        MapleMap map = henesys(here);
        Character c = chr(1, map);
        here.add(c);
        BotSM bot = running(new SocialBot(c));
        ActiveEvent e = invasion(62, 45);
        InvasionTownCrowd.rally(e, map, System.currentTimeMillis(), new Random(1));
        assertTrue(InvasionTownCrowd.holds(c.getId()));
        InvasionTownCrowd.free = b -> false; // a player starts talking to it
        assertFalse(InvasionTownCrowd.handles(bot));
        assertFalse(InvasionTownCrowd.holds(c.getId()));
        assertNull(InvasionTownCrowd.roleOf(c.getId()));
    }

    @Test
    void aRunnerHeadsForTheEdgeAwayFromTheInvader() {
        List<GCMovement.Ledge> ground = List.of(new GCMovement.Ledge(1, -1000, 200, -400, 300),
                new GCMovement.Ledge(2, 100, 1600, 850, 250));
        Point at = new Point(500, 250);
        assertEquals(1600 - 30, InvasionTownCrowd.edge(ground, at, new Point(300, 250)).x, "invader left: go right");
        assertEquals(-1000 + 30, InvasionTownCrowd.edge(ground, at, new Point(900, 250)).x, "invader right: go left");
        assertEquals(1600 - 30, InvasionTownCrowd.edge(ground, new Point(1200, 250), null).x, "else the nearer end");
        assertNull(InvasionTownCrowd.edge(List.of(), at, null));
        assertEquals(InvasionTownCrowd.Role.FIGHT, InvasionTownCrowd.roleFor(0.2));
        assertEquals(InvasionTownCrowd.Role.FLEE, InvasionTownCrowd.roleFor(0.8));
    }

    // ── the invaders hit every bot standing in them ────────────────────────────────────────────────

    static server.life.Monster invader(int oid, int x, int y) {
        server.life.Monster m = mock(server.life.Monster.class);
        when(m.getObjectId()).thenReturn(oid);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, y));
        when(m.getPADamage()).thenReturn(80);
        when(m.getName()).thenReturn("Horny Mushroom");
        return m;
    }

    @Test
    void theInvadersHitEveryBotInThemWhateverMovesIt() throws Exception {
        // owner QA 2026-10-27: "did see bots not get hurt while mobs were walking through em" - the movement driver
        // only touched bots it steps, so a seated, resting or old-engine town bot was never hit
        List<Character> here = new ArrayList<>();
        MapleMap map = henesys(here);
        Character seated = chr(1, map);         // an AFK camper on a chair, right in the mobs
        doReturn(3010000).when(seated).getChair();
        Character social = chr(2, map);         // an old-engine social bot (never under the movement driver)
        Character far = chr(3, map);
        far.setPosition(new Point(2000, 200)); // nowhere near an invader
        Character alt = chr(4, map);
        doReturn(true).when(alt).isOwnedAlt();  // a player's alt: its owner decides
        here.addAll(List.of(seated, social, far, alt));

        ActiveEvent e = invasion(71, 45);
        var mob = invader(901, 140, 210);
        var player = invader(902, 3000, 200);
        e.mobOids.add(mob.getObjectId());
        e.mobOids.add(player.getObjectId());
        when(map.getMonsterByOid(901)).thenReturn(mob);
        when(map.getMonsterByOid(902)).thenReturn(player);

        List<Integer> hit = new ArrayList<>();
        InvasionSwarmTouch.observed = id -> true;
        InvasionSwarmTouch.hitter = (ev, c, m, now) -> hit.add(c.getId());
        long now = 70_000_000;
        assertEquals(2, InvasionSwarmTouch.beat(e, map, now));
        assertTrue(hit.contains(seated.getId()) && hit.contains(social.getId()), "both bots in the mobs: " + hit);
        assertFalse(hit.contains(far.getId()) || hit.contains(alt.getId()));
        assertEquals(0, InvasionSwarmTouch.beat(e, map, now + 1_000), "i-frames: one hit per 2.5 s");
        assertEquals(2, InvasionSwarmTouch.beat(e, map, now + InvasionSwarmTouch.IFRAME_MS));
        InvasionSwarmTouch.observed = id -> false;
        assertEquals(0, InvasionSwarmTouch.beat(e, map, now + 10 * InvasionSwarmTouch.IFRAME_MS),
                "offscreen, as the driver: nobody is hit where nobody sees it");

        assertEquals(mob, InvasionSwarmTouch.reaching(new Point(100, 200), List.of(mob, player)));
        assertNull(InvasionSwarmTouch.reaching(new Point(1000, 200), List.of(mob, player)));
        assertTrue(InvasionSwarmTouch.due(null, now));
        assertFalse(InvasionSwarmTouch.due(now - 1_000, now));
    }

    // ── the event map's own ceiling (WORLD_EVENT_MAP_MAX) ───────────────────────────────────────────

    @Test
    void anInvasionsMapTakesWorldEventMapMaxOnlyWhileItRuns() {
        // owner 2026-10-27: a real client held 620 bots on one map and only lagged at ~1,000; default 200
        assertEquals(200, soloMapling.ArtificialPlayer.BotMapCap.DEFAULT_WORLD_EVENT_MAP_MAX);
        int usual = soloMapling.ArtificialPlayer.BotMapCap.cap(HENESYS);
        soloMapling.ArtificialPlayer.BotMapCap.raise(1, HENESYS);
        try {
            assertEquals(Math.max(usual, soloMapling.ArtificialPlayer.BotMapCap.worldEventMapMax()),
                    soloMapling.ArtificialPlayer.BotMapCap.capOn(1, HENESYS));
            assertEquals(usual, soloMapling.ArtificialPlayer.BotMapCap.capOn(2, HENESYS), "only that instance");
            assertEquals(soloMapling.ArtificialPlayer.BotMapCap.cap(104040000),
                    soloMapling.ArtificialPlayer.BotMapCap.capOn(1, 104040000), "only that map");
        } finally {
            soloMapling.ArtificialPlayer.BotMapCap.lower(1, HENESYS);
        }
        assertEquals(usual, soloMapling.ArtificialPlayer.BotMapCap.capOn(1, HENESYS), "back to the usual cap");
        // arrivals stop 5 under it: 150 called + the town's own still fit
        assertEquals(200 - 5 - 60 - 10, WorldEventReactions.mapRoom(200, 60, 10));
    }

    // ── deaths: a handful, spread out ──────────────────────────────────────────────────────────────

    @Test
    void invasionDeathsArePacedAndCapped() {
        long now = 50_000_000;
        assertTrue(WorldEvents.swarmRoll(0, now, 3, now - WorldEvents.SWARM_EVENT_GAP_MS, 0.1));
        assertFalse(WorldEvents.swarmRoll(0, now, 3, now - 2_000, 0.1), "one at a time: the event's pace");
        assertFalse(WorldEvents.swarmRoll(0, now, WorldEvents.SWARM_DEATHS_PER_EVENT, 0, 0.1), "the cap");
        assertTrue(WorldEvents.SWARM_DEATHS_PER_EVENT <= 24, "a handful to a crowd's worth, never dozens upon dozens");
        // a 10-minute invasion can lose at most this many bots
        assertTrue(10 * 60_000L / WorldEvents.SWARM_EVENT_GAP_MS <= 40);
    }
}
