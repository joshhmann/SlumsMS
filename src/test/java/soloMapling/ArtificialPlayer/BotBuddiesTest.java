package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.BuddyList;
import client.BuddylistEntry;
import client.Character;
import client.Client;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBuddies.Familiarity;
import soloMapling.ArtificialPlayer.BotBuddies.Gate;
import soloMapling.ArtificialPlayer.BotBuddies.Link;
import soloMapling.ArtificialPlayer.BotBuddies.OfferCheck;
import soloMapling.ArtificialPlayer.BotBuddies.Row;
import soloMapling.ArtificialPlayer.BotBuddies.Target;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ticket 49: bots as buddies - the decision rules, the bot's side of the rows, the request flows. */
class BotBuddiesTest {

    private static final int BOT = BotStore.BOT_ID_BASE + 9001;
    private static final int OTHER_BOT = BotStore.BOT_ID_BASE + 9002;
    private static final int PLAYER = 7;
    private static final long TODAY = BotBuddies.today(System.currentTimeMillis());

    private final MemoryRows table = new MemoryRows();
    private final Map<Integer, Character> online = new HashMap<>();
    private Familiarity judged = new Familiarity(true, false, 4, false, 0.2);
    private double roll = 0.0;

    @BeforeEach
    void seams() {
        BotBuddies.useForTest(table, id -> id == BOT ? "Ressurectme" : null, online::get,
                (bot, playerId, player) -> judged, () -> roll);
    }

    @AfterEach
    void real() {
        BotBuddies.useForTest(null, null, null, null, null);
        CharacterStorage.removeActiveBot(BOT);
        CharacterStorage.removeActiveBot(OTHER_BOT);
    }

    // ── the rules ──

    @Test
    void aBotSaysYesMoreOftenToSomeoneItKnows() {
        double stranger = BotBuddies.acceptChance(new Familiarity(false, false, 0, false, 0.2));
        double known = BotBuddies.acceptChance(new Familiarity(true, false, 0, false, 0.2));
        double talking = BotBuddies.acceptChance(new Familiarity(true, false, 3, false, 0.2));
        double partied = BotBuddies.acceptChance(new Familiarity(true, false, 3, true, 0.2));
        assertEquals(0.2, stranger, 1e-9, "a stranger: the base chance");
        assertTrue(stranger < known && known < talking && talking <= partied);
        assertTrue(partied <= 0.95, "never certain");
        assertEquals(0, BotBuddies.acceptChance(new Familiarity(true, true, 5, true, 0.4)), 1e-9, "wary: never");
        assertTrue(BotBuddies.acceptChance(new Familiarity(false, false, 0, false, 0.4))
                > BotBuddies.acceptChance(new Familiarity(false, false, 0, false, 0.05)), "sociable bots say yes more");
    }

    @Test
    void autoQaCertaintyAcceptsEveryRequestButStillNeverWhenWaryOrFull() {
        Familiarity stranger = new Familiarity(false, false, 0, false, 0.2);
        Row refused = new Row(BOT, PLAYER, Link.REFUSED, TODAY);
        assertFalse(BotBuddies.accepts(stranger, 0, refused, TODAY, 0.99), "normal rules");
        BotBuddies.certainFor(60_000);
        try {
            assertTrue(BotBuddies.accepts(stranger, 0, refused, TODAY, 0.99), "t-bb-2 staging: certain");
            assertFalse(BotBuddies.accepts(new Familiarity(true, true, 4, true, 0.3), 0, null, TODAY, 0.0), "wary");
            assertFalse(BotBuddies.accepts(stranger, BotBuddies.maxPlayerBuddies(), null, TODAY, 0.0), "at the cap");
        } finally {
            BotBuddies.certainFor(0);
        }
        assertFalse(BotBuddies.accepts(stranger, 0, null, TODAY, 0.99), "it expires back to the rules");
    }

    @Test
    void aBotTurnsDownAtItsCapWhenWaryAndWhenAskedAgainRightAfterANo() {
        Familiarity friend = new Familiarity(true, false, 4, true, 0.3);
        assertTrue(BotBuddies.accepts(friend, 0, null, TODAY, 0.1));
        assertFalse(BotBuddies.accepts(friend, 0, null, TODAY, 0.99), "the roll");
        assertFalse(BotBuddies.accepts(friend, BotBuddies.maxPlayerBuddies(), null, TODAY, 0.0), "at the cap");
        assertFalse(BotBuddies.accepts(new Familiarity(true, true, 4, true, 0.3), 0, null, TODAY, 0.0), "wary");
        Row refused = new Row(BOT, PLAYER, Link.REFUSED, TODAY);
        assertFalse(BotBuddies.accepts(friend, 0, refused, TODAY, 0.0), "asked again the same day");
        assertTrue(BotBuddies.accepts(friend, 0, new Row(BOT, PLAYER, Link.REFUSED, TODAY - 2), TODAY, 0.0));
    }

    private static OfferCheck offer(Row existing, int sentToday, int worldHour, double roll) {
        return new OfferCheck(true, true, false, 4, existing, TODAY, sentToday, worldHour, 0, false, false, 0.2, roll);
    }

    @Test
    void aBotAsksOnlyAPlayerItKnowsAfterARealChatAndNeverAgainAfterANo() {
        assertEquals(Gate.OK, BotBuddies.offerGate(offer(null, 0, 0, 0.01)));
        assertEquals(Gate.ROLL, BotBuddies.offerGate(offer(null, 0, 0, 0.5)), "rare: most good chats end there");
        assertEquals(Gate.NOT_VISIBLE, BotBuddies.offerGate(new OfferCheck(false, true, false, 4, null, TODAY, 0, 0, 0,
                false, false, 0.2, 0)), "a hidden GM is never asked");
        assertEquals(Gate.STRANGER, BotBuddies.offerGate(new OfferCheck(true, false, false, 4, null, TODAY, 0, 0, 0,
                false, false, 0.2, 0)));
        assertEquals(Gate.WARY, BotBuddies.offerGate(new OfferCheck(true, true, true, 4, null, TODAY, 0, 0, 0,
                false, false, 0.2, 0)));
        assertEquals(Gate.SHORT_CHAT, BotBuddies.offerGate(new OfferCheck(true, true, false, 1, null, TODAY, 0, 0, 0,
                false, false, 0.2, 0)));
        assertEquals(Gate.ALREADY, BotBuddies.offerGate(new OfferCheck(true, true, false, 4, null, TODAY, 0, 0, 0,
                false, true, 0.2, 0)), "already on the player's list");
        assertEquals(Gate.ALREADY, BotBuddies.offerGate(offer(new Row(BOT, PLAYER, Link.MUTUAL, 0), 0, 0, 0)));
        assertEquals(Gate.CLOSED, BotBuddies.offerGate(offer(new Row(BOT, PLAYER, Link.DECLINED, TODAY - 90), 0, 0, 0)));
        assertEquals(Gate.CLOSED, BotBuddies.offerGate(offer(new Row(BOT, PLAYER, Link.DELETED, TODAY - 90), 0, 0, 0)));
        assertEquals(Gate.TOO_SOON, BotBuddies.offerGate(offer(new Row(BOT, PLAYER, Link.SENT, TODAY - 2), 0, 0, 0)));
        assertEquals(Gate.OK, BotBuddies.offerGate(offer(new Row(BOT, PLAYER, Link.SENT, TODAY - 8), 0, 0, 0)),
                "one retry a week after an ignored request");
        assertEquals(Gate.CLOSED, BotBuddies.offerGate(offer(new Row(BOT, PLAYER, Link.RESENT, TODAY - 30), 0, 0, 0)),
                "and only one");
        assertEquals(Gate.PLAYER_DAILY_CAP, BotBuddies.offerGate(offer(null, 1, 0, 0)));
        assertEquals(Gate.WORLD_CAP, BotBuddies.offerGate(offer(null, 0, 6, 0)));
        assertEquals(Gate.BOT_FULL, BotBuddies.offerGate(new OfferCheck(true, true, false, 4, null, TODAY, 0, 0,
                BotBuddies.maxPlayerBuddies(), false, false, 0.2, 0)));
        assertEquals(Gate.PLAYER_FULL, BotBuddies.offerGate(new OfferCheck(true, true, false, 4, null, TODAY, 0, 0, 0,
                true, false, 0.2, 0)));
    }

    @Test
    void theBotsSideOfAPairRoundTripsThroughTheStockColumns() {
        for (Link link : Link.values()) {
            Row row = new Row(BOT, PLAYER, link, link == Link.MUTUAL || link == Link.REQUESTED ? 0 : TODAY);
            assertTrue(row.group().length() <= 17, "fits buddies.group: " + row.group());
            assertEquals(row, Row.of(BOT, PLAYER, row.pending(), row.group(), null));
        }
        assertEquals(0, new Row(BOT, PLAYER, Link.MUTUAL, 0).pending(), "a buddy is a stock buddy row");
        assertEquals(1, new Row(BOT, PLAYER, Link.REQUESTED, 0).pending(), "a request is a stock pending row");
        assertEquals(2, new Row(BOT, PLAYER, Link.SENT, TODAY).pending(), "bookkeeping is never counted as a buddy");
        assertNull(Row.of(BOT, PLAYER, 2, "Default Group", null), "a row this class did not write");
    }

    @Test
    void buddyChatGoesToTheNamedBotOrTheOnlyOneOnline() {
        Target ress = new Target(BOT, "Ressurectme");
        Target kim = new Target(OTHER_BOT, "Kimchi");
        assertEquals(ress, BotBuddies.addressee("hey wya", List.of(ress), -1));
        assertEquals(kim, BotBuddies.addressee("kimchi where r u", List.of(ress, kim), -1));
        assertNull(BotBuddies.addressee("anyone wanna pq", List.of(ress, kim), -1), "not clear: nobody piles on");
        assertEquals(kim, BotBuddies.addressee("lol ok", List.of(ress, kim), OTHER_BOT), "the one they are talking to");
        assertNull(BotBuddies.addressee("ressurectme kimchi hi", List.of(ress, kim), -1));
        assertNull(BotBuddies.addressee("hi", List.of(), -1));
    }

    // ── flows ──

    private static Character player(int id, String name, boolean hidden) {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        BuddyList list = new BuddyList(20);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(client);
        when(chr.getBuddylist()).thenReturn(list);
        when(chr.isHidden()).thenReturn(hidden);
        when(client.getPlayer()).thenReturn(chr);
        when(client.getChannel()).thenReturn(2);
        return chr;
    }

    private Character liveBot(int id, String name, World world) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(new BotClient(0, 1));
        when(chr.getBuddylist()).thenReturn(new BuddyList(20));
        when(chr.getWorldServer()).thenReturn(world);
        BotSM sm = mock(BotSM.class);
        when(sm.getChr()).thenReturn(chr);
        CharacterStorage.addActiveBot(id, sm);
        return chr;
    }

    @Test
    void anOfflineBotKeepsTheRequestForItsNextLogin() {
        int channel = BotBuddies.playerRequests(player(PLAYER, "Josh", false), new Target(BOT, "Ressurectme"));
        assertEquals(-1, channel, "shown offline until it answers");
        assertEquals(Link.REQUESTED, table.pair(BOT, PLAYER).link());
        assertTrue(table.botsWithRows().contains(BOT));
    }

    @Test
    void aRequestNeverTouchesTheTableOnThePacketThread() {
        java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();
        BotBuddies.useWriterForTest(queued::add);
        int channel = BotBuddies.playerRequests(player(PLAYER, "Josh", false), new Target(BOT, "Ressurectme"));
        assertEquals(-1, channel);
        assertNull(table.pair(BOT, PLAYER), "nothing read or written on the caller's thread");
        assertEquals(1, queued.size());
        queued.poll().run();
        assertEquals(Link.REQUESTED, table.pair(BOT, PLAYER).link());
    }

    @Test
    void askingAgainRightAfterANoIsIgnored() {
        table.put(new Row(BOT, PLAYER, Link.REFUSED, TODAY));
        BotBuddies.playerRequests(player(PLAYER, "Josh", false), new Target(BOT, "Ressurectme"));
        assertEquals(Link.REFUSED, table.pair(BOT, PLAYER).link());
        table.put(new Row(BOT, PLAYER, Link.REFUSED, TODAY - 3));
        BotBuddies.playerRequests(player(PLAYER, "Josh", false), new Target(BOT, "Ressurectme"));
        assertEquals(Link.REQUESTED, table.pair(BOT, PLAYER).link(), "days later they may ask again");
    }

    @Test
    void theAutoQaCertaintyOverridesARefusalFromAnEarlierRun() {
        // TEST 2026-10-24: t-bb-2's fixture made the accept certain, but a no from an earlier run within
        // refusalDays still swallowed the request, so the runner saw "pending" all over again.
        table.put(new Row(BOT, PLAYER, Link.REFUSED, TODAY));
        BotBuddies.certainFor(60_000);
        try {
            BotBuddies.playerRequests(player(PLAYER, "Josh", false), new Target(BOT, "Ressurectme"));
            assertEquals(Link.REQUESTED, table.pair(BOT, PLAYER).link(), "the run asks again");
        } finally {
            BotBuddies.certainFor(0);
        }
    }

    @Test
    void anAcceptingBotShowsOnlineOnTheAskersList() {
        World world = mock(World.class);
        Character bot = liveBot(BOT, "Ressurectme", world);
        Character josh = player(PLAYER, "Josh", false);
        online.put(PLAYER, josh);
        josh.getBuddylist().put(new BuddylistEntry("Ressurectme", "Default Group", BOT, -1, true));
        table.put(new Row(BOT, PLAYER, Link.REQUESTED, 0));

        roll = 0.0;
        BotBuddies.decide(BOT, PLAYER, "Josh");

        assertEquals(Link.MUTUAL, table.pair(BOT, PLAYER).link());
        assertTrue(bot.getBuddylist().containsVisible(PLAYER), "the bot has them, so the stock online lookups work");
        verify(world).buddyChanged(PLAYER, BOT, "Ressurectme", 1, BuddyList.BuddyOperation.ADDED);
    }

    @Test
    void aDecliningBotSaysNothingAndRemembersTheNo() {
        World world = mock(World.class);
        Character bot = liveBot(BOT, "Ressurectme", world);
        Character josh = player(PLAYER, "Josh", false);
        online.put(PLAYER, josh);
        josh.getBuddylist().put(new BuddylistEntry("Ressurectme", "Default Group", BOT, -1, true));
        table.put(new Row(BOT, PLAYER, Link.REQUESTED, 0));

        judged = new Familiarity(false, false, 0, false, 0.2);
        roll = 0.9;
        BotBuddies.decide(BOT, PLAYER, "Josh");

        assertEquals(Link.REFUSED, table.pair(BOT, PLAYER).link());
        assertFalse(bot.getBuddylist().contains(PLAYER));
        verify(world, never()).buddyChanged(anyInt(), anyInt(), any(), anyInt(), any());
        verify(josh.getClient(), never()).sendPacket(any());
    }

    @Test
    void aRequestThePlayerWithdrewIsDropped() {
        liveBot(BOT, "Ressurectme", mock(World.class));
        table.put(new Row(BOT, PLAYER, Link.REQUESTED, 0));
        BotBuddies.decide(BOT, PLAYER, "Josh"); // offline, and their saved list no longer has the bot
        assertNull(table.pair(BOT, PLAYER));
    }

    @Test
    void anOfflineAskerStillGetsAnAnswerKeptForTheirNextLogin() {
        World world = mock(World.class);
        Character bot = liveBot(BOT, "Ressurectme", world);
        table.playerSide.add(List.of(PLAYER, BOT));
        table.put(new Row(BOT, PLAYER, Link.REQUESTED, 0));
        BotBuddies.decide(BOT, PLAYER, "Josh");
        assertEquals(Link.MUTUAL, table.pair(BOT, PLAYER).link());
        assertTrue(bot.getBuddylist().containsVisible(PLAYER));
        verify(world, never()).buddyChanged(anyInt(), anyInt(), any(), anyInt(), any());
    }

    @Test
    void aLoggingInBotLoadsItsBuddiesShowsOnlineAndAnswersWhatCameWhileAway() {
        World world = mock(World.class);
        Character bot = liveBot(BOT, "Ressurectme", world);
        table.names.put(PLAYER, "Josh");
        table.names.put(8, "Mira");
        table.put(new Row(BOT, PLAYER, Link.MUTUAL, 0));
        table.put(new Row(BOT, 8, Link.REQUESTED, 0));
        table.put(new Row(BOT, 9, Link.DECLINED, TODAY)); // bookkeeping only
        online.put(PLAYER, player(PLAYER, "Josh", false));

        List<Row> toAnswer = BotBuddies.loadBuddies(bot);

        assertEquals(List.of(8), toAnswer.stream().map(Row::playerId).toList());
        assertTrue(bot.getBuddylist().containsVisible(PLAYER));
        assertFalse(bot.getBuddylist().contains(8));
        assertFalse(bot.getBuddylist().contains(9));
        assertEquals(2, bot.getBuddylist().get(PLAYER).getChannel(), "Josh is online on channel 2");
        verify(world).loggedOn(eq("Ressurectme"), eq(BOT), eq(1), any());
    }

    @Test
    void aBotWithoutBuddiesCostsNothingAtLogin() {
        World world = mock(World.class);
        Character bot = liveBot(BOT, "Ressurectme", world);
        assertTrue(BotBuddies.loadBuddies(bot).isEmpty());
        verify(world, never()).loggedOn(any(), anyInt(), anyInt(), any());
    }

    @Test
    void removingABotEndsItBothWaysAndItNeverAsksAgain() {
        table.put(new Row(BOT, PLAYER, Link.MUTUAL, 0));
        table.playerSide.add(List.of(PLAYER, BOT));
        BotBuddies.recordRemoval(PLAYER, BOT);
        assertEquals(Link.DELETED, table.pair(BOT, PLAYER).link());
        assertFalse(table.playerHas(PLAYER, BOT));
        assertEquals(Gate.CLOSED, BotBuddies.offerGate(offer(table.pair(BOT, PLAYER), 0, 0, 0)));

        table.put(new Row(BOT, PLAYER, Link.SENT, TODAY));
        BotBuddies.recordRemoval(PLAYER, BOT); // the client's deny is a delete
        assertEquals(Link.DECLINED, table.pair(BOT, PLAYER).link());

        table.put(new Row(BOT, PLAYER, Link.REQUESTED, 0));
        BotBuddies.recordRemoval(PLAYER, BOT); // they withdrew their own request
        assertNull(table.pair(BOT, PLAYER));
    }

    @Test
    void aBotSendsARequestOnceAndAtMostOneBotADayAsksAPlayer() {
        World world = mock(World.class);
        Character bot = liveBot(BOT, "Ressurectme", world);
        Character josh = player(PLAYER, "Josh", false);
        online.put(PLAYER, josh);
        roll = 0.0;

        assertEquals(Gate.OK, BotBuddies.maybeOffer(BOT, PLAYER, 4));
        assertEquals(Link.SENT, table.pair(BOT, PLAYER).link());
        assertTrue(bot.getBuddylist().containsVisible(PLAYER));
        assertTrue(josh.getBuddylist().contains(BOT) && !josh.getBuddylist().containsVisible(BOT),
                "the stock pending request on the player's side");
        verify(josh.getClient()).sendPacket(any());

        assertEquals(Gate.ALREADY, BotBuddies.maybeOffer(BOT, PLAYER, 4), "it is on their list, waiting");
        josh.getBuddylist().remove(BOT);
        assertEquals(Gate.TOO_SOON, BotBuddies.maybeOffer(BOT, PLAYER, 4));

        liveBot(OTHER_BOT, "Kimchi", world);
        assertEquals(Gate.PLAYER_DAILY_CAP, BotBuddies.maybeOffer(OTHER_BOT, PLAYER, 4));

        BotBuddies.recordAccept(josh, BOT);
        assertEquals(Link.MUTUAL, table.pair(BOT, PLAYER).link());
    }

    @Test
    void aBusyPlayerIsNotAskedAndNoPendingRequestIsStored() {
        liveBot(BOT, "Ressurectme", mock(World.class));
        Character busy = player(PLAYER, "Josh", false);
        online.put(PLAYER, busy);
        when(busy.getTrade()).thenReturn(mock(server.Trade.class));
        assertEquals(Gate.NOT_VISIBLE, BotBuddies.maybeOffer(BOT, PLAYER, 6));
        assertNull(table.pair(BOT, PLAYER));
        verify(busy.getClient(), never()).sendPacket(any());
    }

    @Test
    void aHiddenGmIsNeverAsked() {
        liveBot(BOT, "Ressurectme", mock(World.class));
        Character gm = player(PLAYER, "Gm", true);
        online.put(PLAYER, gm);
        assertEquals(Gate.NOT_VISIBLE, BotBuddies.maybeOffer(BOT, PLAYER, 6));
        assertNull(table.pair(BOT, PLAYER));
        verify(gm.getClient(), never()).sendPacket(any());
    }

    @Test
    void buddyChatReachesOnlyAMutualBotBuddy() {
        World world = mock(World.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        Character bot = liveBot(BOT, "Ressurectme", world);
        when(storage.getCharacterById(BOT)).thenReturn(bot);
        Character josh = player(PLAYER, "Josh", false);
        when(josh.getWorldServer()).thenReturn(world);
        // Not the bot's buddy (it never accepted): nothing reaches its brain, nothing is sent.
        BotBuddies.buddyChat(josh, new int[]{BOT}, "where r u");
        verify(josh.getClient(), never()).sendPacket(any());
    }

    /** The {@code buddies} table in memory. */
    static final class MemoryRows implements BotBuddies.Rows {
        record Stored(int characterId, int buddyId, int pending, String group) {
        }

        final List<Stored> table = new ArrayList<>();
        final Set<List<Integer>> playerSide = new HashSet<>();
        final Map<Integer, String> names = new HashMap<>();

        @Override
        public synchronized List<Row> ofBot(int botCharId) {
            List<Row> out = new ArrayList<>();
            for (Stored s : table) {
                if (s.characterId() == botCharId) {
                    Row row = Row.of(botCharId, s.buddyId(), s.pending(), s.group(), names.getOrDefault(s.buddyId(), "p" + s.buddyId()));
                    if (row != null) {
                        out.add(row);
                    }
                }
            }
            return out;
        }

        @Override
        public synchronized Row pair(int botCharId, int playerId) {
            for (Stored s : table) {
                if (s.characterId() == botCharId && s.buddyId() == playerId) {
                    return Row.of(botCharId, playerId, s.pending(), s.group(), null);
                }
            }
            return null;
        }

        @Override
        public synchronized void put(Row row) {
            remove(row.botCharId(), row.playerId());
            table.add(new Stored(row.botCharId(), row.playerId(), row.pending(), row.group()));
        }

        @Override
        public synchronized void remove(int botCharId, int playerId) {
            table.removeIf(s -> s.characterId() == botCharId && s.buddyId() == playerId);
        }

        @Override
        public synchronized int mutualCount(int botCharId) {
            return (int) table.stream().filter(s -> s.characterId() == botCharId && s.pending() == 0).count();
        }

        @Override
        public synchronized int sentOn(int playerId, long day) {
            return (int) table.stream().filter(s -> s.buddyId() == playerId && s.pending() == 2
                    && (("sent:" + day).equals(s.group()) || ("resent:" + day).equals(s.group()))).count();
        }

        @Override
        public synchronized boolean playerHas(int playerId, int botCharId) {
            return playerSide.contains(List.of(playerId, botCharId));
        }

        @Override
        public synchronized void removePlayerSide(int playerId, int botCharId) {
            playerSide.remove(List.of(playerId, botCharId));
        }

        @Override
        public synchronized Set<Integer> botsWithRows() {
            Set<Integer> out = new HashSet<>();
            for (Stored s : table) {
                out.add(s.characterId());
            }
            return out;
        }
    }
}
