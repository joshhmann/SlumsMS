package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * rc6 say-do: the ledger judges claims by what the bot then did, and the claims bots make in ambient chatter come
 * true (ClaimActions): "brb pots" starts a resupply trip, "gtg" a logout, and a claim the bot would not back is never
 * said. A fake game stands in for the server.
 */
class SayDoTest {

    /** The fake world: who is online, where. */
    static final class World implements SayDo.StateSource {
        final Set<Integer> offline = new HashSet<>();
        int map = 100000000;
        boolean partied;

        @Override
        public SayDo.Start start(Character bot, Character target, ClaimRules.Claim claim) {
            return new SayDo.Start(map, 0, 0, partied, target == null ? -1 : map, 0, 0, false, false);
        }

        @Override
        public SayDo.Now now(int botId, int targetId) {
            return offline.contains(botId) ? null : new SayDo.Now(true, map, 0, 0, -1, 0, 0, 0);
        }
    }

    /** The fake behaviours: each records what it was asked to do, and reports the event like the game does. */
    static final class Game implements ClaimActions.Game {
        final World world;
        final List<String> did = new ArrayList<>();
        boolean grinding = true;
        boolean roster = true;

        Game(World world) {
            this.world = world;
        }

        @Override
        public boolean canRestock(Character bot) {
            return grinding;
        }

        @Override
        public void restock(Character bot) {
            did.add("restock");
            SayDo.event(bot, SayDo.Ev.RESUPPLY, 100000000); // TrainingBot does this as it leaves for town
        }

        @Override
        public boolean canIdle(Character bot) {
            return !grinding;
        }

        @Override
        public void idle(Character bot, long ms) {
            did.add("idle " + (ms >= 30_000 && ms <= 90_000));
            SayDo.event(bot, SayDo.Ev.IDLE, world.map);
        }

        @Override
        public boolean canLogOut(Character bot) {
            return roster;
        }

        @Override
        public void logOutSoon(Character bot, long ms) {
            did.add("logout " + (ms <= SayDo.windowMs(ClaimRules.Kind.LEAVING)));
            world.offline.add(bot.getId()); // the director's logout ran
        }

        @Override
        public boolean canTravel(Character bot, int mapId) {
            return false;
        }

        @Override
        public void travel(Character bot, int mapId) {
            did.add("travel " + mapId);
        }

        @Override
        public boolean partied(Character bot) {
            return world.partied;
        }

        @Override
        public boolean holdsOrWants(Character bot, ClaimRules.Claim claim) {
            return false;
        }

        boolean takes;

        @Override
        public boolean takesTrade(Character bot, Character from) {
            return takes;
        }
    }

    World world;
    Game game;
    List<SayDo.Resolved> sunk;

    static Character bot(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMapId()).thenReturn(100000000);
        return c;
    }

    @BeforeEach
    void setUp() {
        world = new World();
        game = new Game(world);
        sunk = new ArrayList<>();
        SayDo.useForTest(world, sunk::add);
        ClaimActions.useForTest(game);
    }

    @AfterEach
    void tearDown() {
        SayDo.useForTest(null, null);
        ClaimActions.useForTest(null);
    }

    private static List<String> said = new ArrayList<>();

    private boolean ambient(Character bot, String line) {
        return ClaimActions.sayBacked(bot, line, l -> {
            said.add(l);
            SayDo.said(bot, l);
        });
    }

    @Test
    void brbPotsStartsAResupplyTripAndCountsAsBacked() {
        Character b = bot(1_000_000_001, "potter");
        assertTrue(ambient(b, "brb pots"));
        assertEquals(List.of("restock"), game.did);
        List<SayDo.Resolved> r = SayDo.resolveDue(false);
        assertEquals(1, r.size());
        assertTrue(r.get(0).backed(), r.get(0).why());
        assertEquals(ClaimRules.Kind.RESUPPLY, r.get(0).kind());
        assertEquals(1L, ((Map<?, ?>) SayDo.stats().get("byKind")).containsKey("resupply") ? 1L : 0L);
    }

    @Test
    void gtgLogsOutWithinTheWindowAndCountsAsBacked() {
        Character b = bot(1_000_000_002, "sleepy");
        assertTrue(ambient(b, "gtg night all"));
        assertEquals(List.of("logout true"), game.did);
        List<SayDo.Resolved> r = SayDo.resolveDue(false);
        assertEquals(1, r.size());
        assertTrue(r.get(0).backed());
        assertEquals("logged out", r.get(0).why());
    }

    @Test
    void aClaimLineIsNeverSaidWhenItsActionIsRefused() {
        Character b = bot(1_000_000_003, "grinder");
        game.grinding = true; // mid-fight: no "brb"
        said.clear();
        for (int i = 0; i < 20; i++) {
            assertFalse(ambient(b, "brb dinner"));
        }
        game.roster = false;
        assertFalse(ambient(b, "gtg"));
        assertFalse(ambient(b, "omw"), "nobody to come to in ambient chatter");
        assertTrue(said.isEmpty());
        assertTrue(game.did.isEmpty());
        assertTrue(ambient(b, "lol this map is packed"), "a line with no claim is fine");
    }

    @Test
    void brbFromAnIdleBotGoesIdle() {
        Character b = bot(1_000_000_004, "townie");
        game.grinding = false;
        assertTrue(ambient(b, "brb dinner"));
        assertEquals(List.of("idle true"), game.did);
        assertTrue(SayDo.resolveDue(false).get(0).backed());
    }

    @Test
    void anUnbackedClaimIsUnbackedAfterItsWindow() {
        Character b = bot(1_000_000_005, "liar");
        SayDo.said(b, null, "omw", "llm");
        assertTrue(SayDo.resolveDue(false).isEmpty(), "still inside its window");
        SayDo.advanceForTest(SayDo.windowMs(ClaimRules.Kind.COME) + 1);
        List<SayDo.Resolved> r = SayDo.resolveDue(false);
        assertEquals(1, r.size());
        assertFalse(r.get(0).backed());
        assertEquals("llm", r.get(0).source());
        assertEquals(1, sunk.size(), "the verdict went to the sink (the JSONL file on auto-QA servers)");
        Map<?, ?> bySource = (Map<?, ?>) SayDo.stats().get("bySource");
        assertEquals(0L, ((Map<?, ?>) bySource.get("llm")).get("backed"));
    }

    @Test
    void theSameLineThroughTwoHooksIsOneClaimAndAnEarlierTripStillCounts() {
        Character b = bot(1_000_000_006, "walker");
        SayDo.event(b, SayDo.Ev.WALK, 104000000); // the walk started a moment before the reply was typed
        SayDo.said(b, null, "ok omw", "reply-llm");
        SayDo.said(b, "ok omw"); // the map chat hook sees the same line
        List<SayDo.Resolved> r = SayDo.resolveDue(false);
        assertEquals(1, r.size());
        assertTrue(r.get(0).backed());
        assertEquals("reply-llm", r.get(0).source());
    }

    @Test
    void judgeRules() {
        ClaimRules.Claim ask = ClaimRules.classify("inv me").get(0);
        SayDo.Start alone = new SayDo.Start(1, 0, 0, false, -1, 0, 0, false, false);
        SayDo.Start inParty = new SayDo.Start(1, 0, 0, true, -1, 0, 0, false, false);
        assertTrue(SayDo.judge(ask, alone, null, List.of(), false).backed());
        assertFalse(SayDo.judge(ask, inParty, null, List.of(), false).backed());
        ClaimRules.Claim away = ClaimRules.classify("brb").get(0);
        SayDo.Now still = new SayDo.Now(true, 1, 0, 0, -1, 0, 0, 0);
        SayDo.Now chatting = new SayDo.Now(true, 1, 0, 0, -1, 0, 0, 2);
        assertNull(SayDo.judge(away, alone, still, List.of(), false).backed(), "a brb is judged at the window's end");
        assertTrue(SayDo.judge(away, alone, still, List.of(), true).backed());
        assertFalse(SayDo.judge(away, alone, chatting, List.of(), true).backed());
        ClaimRules.Claim go = ClaimRules.classify("going to orbis").get(0);
        assertTrue(SayDo.judge(go, alone, null, List.of(new SayDo.Event(SayDo.Ev.MAP, 200000100, 0)), false).backed());
        assertFalse(SayDo.judge(go, alone, still, List.of(new SayDo.Event(SayDo.Ev.MAP, 101000000, 0)), true).backed());
        ClaimRules.Claim sell = ClaimRules.classify("S> ilbis 4m").get(0);
        assertTrue(SayDo.judge(sell, new SayDo.Start(1, 0, 0, false, -1, 0, 0, true, false), null, List.of(), false).backed());
        assertEquals("does not hold 'ilbis'", SayDo.judge(sell, alone, still, List.of(), true).why());
    }

    /**
     * rc6 auto-QA run of 21095df97: every "trade me" (426) was UNBACKED "no trade" because nobody opened one. The ask
     * is judged like "inv me": by its premise - the bot takes a trade when one is opened - or by a trade window.
     */
    @Test
    void tradeMeIsBackedWhenTheBotTakesTrades() {
        ClaimRules.Claim ask = ClaimRules.classify("just trade me").get(0);
        assertEquals(ClaimRules.Kind.TRADE_ASK, ask.kind());
        SayDo.Start takes = new SayDo.Start(1, 0, 0, false, -1, 0, 0, true, false);
        SayDo.Start not = new SayDo.Start(1, 0, 0, false, -1, 0, 0, false, false);
        SayDo.Now now = new SayDo.Now(true, 1, 0, 0, -1, 0, 0, 0);
        assertEquals("takes a trade", SayDo.judge(ask, takes, now, List.of(), false).why());
        assertNull(SayDo.judge(ask, not, now, List.of(), false).backed(), "a window may still open");
        assertEquals("trade window", SayDo.judge(ask, not, now,
                List.of(new SayDo.Event(SayDo.Ev.TRADE, 1, 0)), false).why());
        assertEquals("would not take a trade", SayDo.judge(ask, not, now, List.of(), true).why());
        assertFalse(SayDo.judge(ask, not, now, List.of(), true).backed());
        ClaimRules.Claim promise = ClaimRules.classify("ill trade u for it").get(0);
        assertFalse(SayDo.judge(promise, takes, now, List.of(), true).backed(), "a promise needs the window itself");
    }

    @Test
    void ambientTradeMeOnlyFromABotThatTakesTrades() {
        Character b = bot(1_000_000_010, "scammer");
        game.takes = false;
        assertFalse(ambient(b, "trade me any equip and ill upgrade it for free no scam i swear"));
        game.takes = true;
        assertTrue(ambient(b, "drop game still running! trade me to get in!"));
    }

    @Test
    void scriptedTradeAdsAreLabelledAds() {
        assertEquals("ad", SayDo.labelFor("scripted", ClaimRules.classify("S> work gloves")));
        assertEquals("llm", SayDo.labelFor("llm", ClaimRules.classify("S> work gloves")));
    }

    @Test
    void replyGuardKeepsOnlyWhatTheActionBacks() {
        Set<ClaimRules.Kind> none = EnumSet.noneOf(ClaimRules.Kind.class);
        assertNull(ClaimActions.guardReply("ok omw", none), "a refused walk: no omw");
        assertEquals("sure", ClaimActions.guardReply("sure, inv me", none));
        assertEquals("sure, inv me", ClaimActions.guardReply("sure, inv me",
                ClaimActions.allowedFor(soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_ACCEPTED)));
        assertEquals("ok omw", ClaimActions.guardReply("ok omw",
                ClaimActions.allowedFor(soloMapling.ArtificialPlayer.BotChatActions.Outcome.COME_YES)));
        assertEquals("nah im good, grinding solo is faster xp for me",
                ClaimActions.guardReply("nah im good, grinding solo is faster xp for me brb", none));
        assertEquals("lol ya", ClaimActions.guardReply("lol ya", none), "nothing to take out");
    }
}
