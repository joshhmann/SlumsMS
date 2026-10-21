package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotAffinityTest {

    static final int BOT = BotStore.BOT_ID_BASE + 7;
    static final int BOT2 = BotStore.BOT_ID_BASE + 8;
    static final int PLAYER = 42;
    static final long HOUR = TimeUnit.HOURS.toMillis(1);
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final AffinityStore.Memory store = new AffinityStore.Memory();
    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    final List<String> notes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(store, Runnable::run, clock::get, id -> true);
        BotAffinity.useNotesForTest((slot, player, text) -> notes.add(slot + ":" + player + ":" + text));
        AffinityReactions.resetForTest();
        AffinityHooks.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
        BotAffinity.useNotesForTest(null);
        System.clearProperty("solomapling.affinity.hostility");
    }

    private static Character chr(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMapId()).thenReturn(100000000);
        return c;
    }

    private void load() {
        BotAffinity.load(PLAYER);
    }

    private boolean ev(int bot, AffinityEvent e) {
        boolean r = BotAffinity.record(bot, PLAYER, e, e.delta(), "Player", "Bot", 100000000);
        clock.addAndGet(TimeUnit.MINUTES.toMillis(31)); // past every cooldown
        return r;
    }

    // ── rules ──

    @Test
    void aFriendNeedsHistoryNotOneNiceMoment() {
        load();
        ev(BOT, AffinityEvent.FAME);
        ev(BOT, AffinityEvent.GIFT);
        assertEquals(AffinityState.NEUTRAL, BotAffinity.state(BOT, PLAYER), "score is there but only 2 kind things");
        ev(BOT, AffinityEvent.RESCUE);
        assertEquals(AffinityState.FRIEND, BotAffinity.state(BOT, PLAYER));
        assertTrue(notes.contains("7:42:" + BotAffinity.NOTE_FRIEND), "a new friend is noted in memory");
    }

    @Test
    void oneKsIsNotARivalryButRepeatedKsIs() {
        load();
        ev(BOT, AffinityEvent.KS);
        assertEquals(AffinityState.NEUTRAL, BotAffinity.state(BOT, PLAYER));
        for (int i = 0; i < 4; i++) {
            ev(BOT, AffinityEvent.KS);
        }
        assertEquals(AffinityState.RIVAL, BotAffinity.state(BOT, PLAYER));
        assertTrue(notes.contains("7:42:" + BotAffinity.NOTE_KS));
    }

    @Test
    void aNemesisNeedsALongHistory() {
        load();
        for (int i = 0; i < 4; i++) {
            ev(BOT, AffinityEvent.DEFAME);
        }
        assertEquals(AffinityState.RIVAL, BotAffinity.state(BOT, PLAYER), "-72 but only 4 hostile things");
        ev(BOT, AffinityEvent.KS);
        assertEquals(AffinityState.NEMESIS, BotAffinity.state(BOT, PLAYER));
    }

    @Test
    void cooldownsCountASpamOnce() {
        load();
        assertTrue(BotAffinity.record(BOT, PLAYER, AffinityEvent.BUFF, 3, "P", "B", 0));
        assertFalse(BotAffinity.record(BOT, PLAYER, AffinityEvent.BUFF, 3, "P", "B", 0));
        clock.addAndGet(TimeUnit.MINUTES.toMillis(6));
        assertTrue(BotAffinity.record(BOT, PLAYER, AffinityEvent.BUFF, 3, "P", "B", 0));
    }

    @Test
    void grudgesFadeOverDaysFriendshipsOverWeeks() {
        Tie grudge = new Tie(1, PLAYER, -40, 0, 3, 0);
        assertEquals(AffinityState.RIVAL, AffinityRules.state(grudge, 0, false));
        assertEquals(AffinityState.RIVAL, AffinityRules.state(grudge, 12 * HOUR, false));
        assertEquals(AffinityState.NEUTRAL, AffinityRules.state(grudge, 3 * DAY, false));
        Tie nemesis = new Tie(1, PLAYER, -85, 0, 8, 0);
        assertEquals(AffinityState.NEMESIS, AffinityRules.state(nemesis, 0, false));
        assertEquals(AffinityState.NEUTRAL, AffinityRules.state(nemesis, 7 * DAY, false), "even a nemesis fades in a week");
        Tie friend = new Tie(1, PLAYER, 60, 5, 0, 0);
        assertEquals(AffinityState.FRIEND, AffinityRules.state(friend, 7 * DAY, false));
        assertEquals(AffinityState.NEUTRAL, AffinityRules.state(friend, 21 * DAY, false));
    }

    @Test
    void aFadedHistoryStartsOver() {
        Tie t = new Tie(1, PLAYER, -30, 0, 9, 0);
        AffinityRules.apply(t, AffinityEvent.KS, 30 * DAY, false);
        assertEquals(1, t.hostile(), "old hostile count forgotten once the score faded");
    }

    @Test
    void hearsayNudgesButNeverMakesAState() {
        load();
        for (int i = 0; i < 10; i++) {
            BotAffinity.onHearsay(7, PLAYER, "heard: " + BotAffinity.NOTE_KS);
            clock.addAndGet(2 * HOUR);
        }
        Tie t = BotAffinity.load(PLAYER).ties.get(7);
        assertTrue(t.rawScore() < 0 && t.rawScore() >= -AffinityRules.HEARSAY_CAP);
        assertEquals(AffinityState.NEUTRAL, BotAffinity.state(BOT, PLAYER));
        assertTrue(notes.isEmpty(), "gossip never writes a first-hand note");
        assertEquals(AffinityEvent.HEARSAY_GOOD, BotAffinity.hearsayOf(BotAffinity.NOTE_FRIEND));
        assertNull(BotAffinity.hearsayOf("likes pink capes"));
    }

    // ── @botdrama ──

    @Test
    void dramaOffMakesEveryBotNeutralAndHostileEventsStopCounting() {
        load();
        BotAffinity.stage(BOT, PLAYER, AffinityState.NEMESIS);
        assertTrue(BotAffinity.hostile(BOT, PLAYER));
        Character player = chr(PLAYER, "Player");
        BotAffinity.setOptedOut(player, true);
        assertTrue(store.optedOut(PLAYER), "stored");
        assertEquals(AffinityState.NEUTRAL, BotAffinity.state(BOT, PLAYER));
        assertFalse(ev(BOT2, AffinityEvent.KS), "a KS does not count while off");
        assertTrue(ev(BOT2, AffinityEvent.FAME), "kind things still count");
        assertFalse(BotAffinity.forceGrudge(BOT, PLAYER));
        BotAffinity.setOptedOut(player, false);
        assertTrue(BotAffinity.hostile(BOT, PLAYER), "on again: the old grudge is back (it still fades with time)");
        BotAffinity.unload(PLAYER);
        BotAffinity.setOptedOut(player, true);
        assertTrue(BotAffinity.optedOut(PLAYER), "set before the ties were loaded: loads them and applies it");
    }

    @Test
    void hostilitySwitchKeepsScoresButStopsHostileStates() {
        load();
        BotAffinity.stage(BOT, PLAYER, AffinityState.RIVAL);
        System.setProperty("solomapling.affinity.hostility", "false");
        assertEquals(AffinityState.NEUTRAL, BotAffinity.state(BOT, PLAYER));
    }

    // ── storage ──

    @Test
    void tiesPersistAndComeBackAtTheNextLogin() {
        load();
        ev(BOT, AffinityEvent.KS);
        ev(BOT, AffinityEvent.KS);
        assertEquals(1, store.size());
        BotAffinity.unload(PLAYER);
        assertFalse(BotAffinity.record(BOT, PLAYER, AffinityEvent.KS, -6, null, null, 0), "not loaded: not counted");
        load();
        Tie t = BotAffinity.load(PLAYER).ties.get(7);
        assertEquals(2, t.hostile());
        assertEquals(-12, Math.round(t.rawScore()));
    }

    @Test
    void fadedRowsAreDroppedAtLoadAndTiesAreBounded() {
        store.save(new Tie(1, PLAYER, -10, 0, 1, clock.get() - 60 * DAY));
        for (int i = 2; i < 60; i++) {
            store.save(new Tie(i, PLAYER, i, 1, 0, clock.get()));
        }
        BotAffinity.PlayerTies pt = BotAffinity.load(PLAYER);
        assertFalse(pt.ties.containsKey(1), "faded to nothing");
        assertEquals(BotAffinity.maxTiesPerPlayer(), pt.ties.size());
        assertFalse(pt.ties.containsKey(2), "the weakest went first");
        assertEquals(BotAffinity.maxTiesPerPlayer(), store.size());
    }

    @Test
    void botBotAndPlayerPlayerPairsAreIgnored() {
        load();
        assertFalse(BotAffinity.record(chr(BOT, "a"), chr(BOT2, "b"), AffinityEvent.PARTY));
        assertFalse(BotAffinity.record(chr(PLAYER, "a"), chr(43, "b"), AffinityEvent.PARTY));
        assertTrue(BotAffinity.record(chr(PLAYER, "p"), chr(BOT, "b"), AffinityEvent.PARTY), "either order");
    }

    // ── GM tools ──

    @Test
    void stagingGrudgeAndDescribe() {
        load();
        assertTrue(BotAffinity.stage(BOT, PLAYER, AffinityState.FRIEND));
        assertTrue(BotAffinity.describe(BOT, PLAYER).startsWith("friend"));
        assertTrue(BotAffinity.forceGrudge(BOT, PLAYER));
        assertTrue(BotAffinity.hostile(BOT, PLAYER));
        assertTrue(BotAffinity.describe(BOT, PLAYER).startsWith("rival"));
        assertTrue(BotAffinity.describeAll(PLAYER, id -> "Bot" + id).get(0).contains("rival"));
        BotAffinity.stage(BOT, PLAYER, AffinityState.NEUTRAL);
        assertEquals(0, store.size(), "neutral drops the row");
        BotAffinity.stage(BOT, PLAYER, AffinityState.RIVAL);
        BotAffinity.forgetBot(BOT);
        assertEquals(0, store.size());
        assertEquals(AffinityState.NEUTRAL, BotAffinity.state(BOT, PLAYER));
    }

    // ── hooks ──

    @Test
    void aTradeWithNothingBackIsAGift() {
        load();
        Character bot = chr(BOT, "Bot");
        Character player = chr(PLAYER, "Player");
        Item item = mock(Item.class);
        AffinityHooks.onTrade(player, List.of(item), 0, bot, List.of(), 0);
        Tie t = BotAffinity.load(PLAYER).ties.get(7);
        assertEquals(AffinityEvent.GIFT.delta(), Math.round(t.rawScore()));
        clock.addAndGet(HOUR);
        AffinityHooks.onTrade(bot, List.of(item), 0, player, List.of(), 5000);
        t = BotAffinity.load(PLAYER).ties.get(7);
        assertEquals(2, t.kind(), "a real trade counts too");
    }

    @Test
    void fameAndDefameMoveIt() {
        load();
        AffinityHooks.onFame(chr(PLAYER, "P"), chr(BOT, "B"), -1);
        assertTrue(BotAffinity.load(PLAYER).ties.get(7).rawScore() < 0);
        AffinityHooks.onFame(chr(PLAYER, "P"), chr(BOT2, "B"), 1);
        assertTrue(BotAffinity.load(PLAYER).ties.get(8).rawScore() > 0);
        AffinityHooks.onFame(chr(BOT, "B"), chr(PLAYER, "P"), 1);
        assertNull(BotAffinity.load(PLAYER).ties.get(-BotStore.BOT_ID_BASE + PLAYER), "a bot faming a player is not an event");
    }

    // ── chat tone ──

    @Test
    void chatToneRules() {
        ChatTriage.Result talk = ChatTriage.rules("where do u train");
        assertEquals(ChatTone.Tone.KIND, ChatTone.rule("ty so much <3", ChatTriage.rules("ty so much <3")).orElseThrow());
        assertEquals(ChatTone.Tone.KIND, ChatTone.rule("gg nice one", talk).orElseThrow());
        assertEquals(ChatTone.Tone.RUDE, ChatTone.rule("ur trash noob", talk).orElseThrow());
        assertEquals(ChatTone.Tone.RUDE, ChatTone.rule("ty noob", talk).orElseThrow(), "rude wins");
        assertEquals(ChatTone.Tone.PLAIN, ChatTone.rule("hi", ChatTriage.rules("hi")).orElseThrow());
        assertTrue(ChatTone.rule("where do u train", talk).isEmpty(), "left to the model");
        assertEquals(ChatTone.Tone.PLAIN, ChatTone.of("where do u train", talk, null).join(), "no model: plain");
        assertEquals(ChatTone.Tone.RUDE, ChatTone.fromModel(new ChatDecide.Answer("rude", 0.9), 0.6));
        assertEquals(ChatTone.Tone.PLAIN, ChatTone.fromModel(new ChatDecide.Answer("rude", 0.5), 0.6), "unsure: plain");
        assertEquals(ChatTone.Tone.PLAIN, ChatTone.fromModel(null, 0.6));
    }

    // ── reactions ──

    @Test
    void actionsAreRateLimitedPerPairAndWorldWide() {
        long now = clock.get();
        assertTrue(AffinityReactions.take(AffinityReactions.Action.TAUNT, BOT, PLAYER, now));
        assertFalse(AffinityReactions.take(AffinityReactions.Action.TAUNT, BOT, PLAYER, now + 60_000), "pair cooldown");
        assertTrue(AffinityReactions.take(AffinityReactions.Action.TAUNT, BOT, PLAYER,
                now + AffinityReactions.cooldownMs(AffinityReactions.Action.TAUNT)));
        int cap = AffinityReactions.perHour(AffinityReactions.Action.INVITE);
        for (int i = 0; i < cap; i++) {
            assertTrue(AffinityReactions.take(AffinityReactions.Action.INVITE, BOT + i, PLAYER, now));
        }
        assertFalse(AffinityReactions.take(AffinityReactions.Action.INVITE, BOT + cap, PLAYER, now), "world cap");
        assertTrue(AffinityReactions.take(AffinityReactions.Action.INVITE, BOT + cap, PLAYER, now + HOUR + 1));
    }

    @Test
    void oneBotDefamePerPlayerPerDay() {
        long now = clock.get();
        assertTrue(AffinityReactions.mayDefame(PLAYER, now));
    }

    @Test
    void thePromptNeverCarriesANumber() {
        load();
        Character player = chr(PLAYER, "Player");
        assertEquals("", AffinityReactions.promptLine(BOT, player));
        for (AffinityState s : List.of(AffinityState.FRIEND, AffinityState.RIVAL, AffinityState.NEMESIS)) {
            BotAffinity.stage(BOT, PLAYER, s);
            String line = AffinityReactions.promptLine(BOT, player);
            assertFalse(line.isBlank());
            assertFalse(line.matches(".*\\d.*"), line);
        }
    }

    @Test
    void theDialoguePackHasEveryNode() {
        for (String node : List.of("FriendHello", "FriendInvite", "FriendHelp", "RivalTaunt", "NemesisTaunt",
                "KsAnnounce", "Refuse", "Defame")) {
            String line = AffinityReactions.pick(node, java.util.Map.of("PLAYER", "Kitty"));
            assertTrue(line != null && !line.contains("{"), node + ": " + line);
        }
        for (String node : List.of("GossipBad", "GossipGood")) {
            String line = AffinityReactions.pick(node, java.util.Map.of("OTHER", "Kitty"));
            assertTrue(line != null && line.contains("Kitty"), node);
        }
    }
}
