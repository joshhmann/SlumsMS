package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

class RudeStreakTest {

    static final int BOT = BotStore.BOT_ID_BASE + 7;
    static final int BOT2 = BotStore.BOT_ID_BASE + 8;
    static final int BOT3 = BotStore.BOT_ID_BASE + 9;
    static final int BOT4 = BotStore.BOT_ID_BASE + 10;
    static final int PLAYER = 42;
    static final int HENESYS = 100000000;
    static final int ELLINIA = 101000000;
    static final long MIN = TimeUnit.MINUTES.toMillis(1);

    static final ChatTriage.Result BY_MODEL = triage(ChatTriage.Con.HARASSMENT, ChatTriage.Source.DECIDE);
    static final ChatTriage.Result BY_RULE = triage(ChatTriage.Con.HARASSMENT, ChatTriage.Source.RULE);
    static final ChatTriage.Result NORMAL = triage(ChatTriage.Con.NORMAL, ChatTriage.Source.DECIDE);

    final AffinityStore.Memory store = new AffinityStore.Memory();
    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    final List<String> notes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RudeStreak.reset();
        BotAffinity.useForTest(store, Runnable::run, clock::get, id -> true);
        BotAffinity.useNotesForTest((slot, player, text) -> notes.add(slot + ":" + player + ":" + text));
    }

    @AfterEach
    void tearDown() {
        RudeStreak.reset();
        BotAffinity.useForTest(null, null, null, null);
        BotAffinity.useNotesForTest(null);
    }

    private static ChatTriage.Result triage(ChatTriage.Con con, ChatTriage.Source source) {
        return new ChatTriage.Result(ChatTriage.Kind.TALK, ChatTriage.Intent.values()[0], ChatTriage.Source.DECIDE,
                con, source, 0.85);
    }

    private static Character chr(int id, String name, boolean bot) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMapId()).thenReturn(HENESYS);
        if (bot) {
            when(c.getClient()).thenReturn(mock(BotClient.class));
        }
        return c;
    }

    private RudeStreak.Rude rude(int bot, int map, long at) {
        return RudeStreak.onLine(bot, PLAYER, map, BY_MODEL, "ur trash noob", at);
    }

    @Test
    void onlyTheModelsHarassmentCountsADeathWishSlurOrThreatIsHarassmentFromTheFirstLine() {
        assertTrue(RudeStreak.counts(BY_MODEL));
        assertFalse(RudeStreak.counts(BY_RULE), "rule harassment keeps the old, full reaction");
        assertFalse(RudeStreak.counts(NORMAL));
        assertFalse(RudeStreak.counts(triage(ChatTriage.Con.SCAM, ChatTriage.Source.DECIDE)));
        assertNull(RudeStreak.onLine(BOT, PLAYER, HENESYS, BY_RULE, "kys", 0));
    }

    @Test
    void oneRudeLineIsLoneTheSecondToTheSameBotWithinTenMinutesIsRepeated() {
        long t = clock.get();
        assertEquals(RudeStreak.Step.LONE, rude(BOT, HENESYS, t).step());
        assertEquals(RudeStreak.Step.LONE, rude(BOT2, HENESYS, t + MIN).step(), "another bot keeps its own count");
        assertEquals(RudeStreak.Step.REPEATED, rude(BOT, HENESYS, t + 9 * MIN).step());
    }

    @Test
    void rudeLinesMoreThanTenMinutesApartStayLone() {
        long t = clock.get();
        rude(BOT, HENESYS, t);
        assertEquals(RudeStreak.Step.LONE, rude(BOT, HENESYS, t + 11 * MIN).step());
    }

    @Test
    void anApologyToTheBotStartsItsCountOver() {
        long t = clock.get();
        rude(BOT, HENESYS, t);
        assertNull(RudeStreak.onLine(BOT, PLAYER, HENESYS, NORMAL, "sorry for ksing u", t + MIN));
        assertEquals(RudeStreak.Step.LONE, rude(BOT, HENESYS, t + 2 * MIN).step());
        assertTrue(RudeStreak.apologises("my bad lol"));
        assertTrue(RudeStreak.apologises("SRY"));
        assertFalse(RudeStreak.apologises("ur trash noob"));
        assertFalse(RudeStreak.apologises("mbappe is goated"), "a word boundary, not any 'mb'");
    }

    @Test
    void rudeToThreeBotsOnOneMapWithinTenMinutesSpreads() {
        long t = clock.get();
        assertFalse(rude(BOT, HENESYS, t).spread());
        assertFalse(rude(BOT2, ELLINIA, t + MIN).spread(), "another map does not add up");
        assertFalse(rude(BOT2, HENESYS, t + 2 * MIN).spread());
        assertFalse(rude(BOT2, HENESYS, t + 3 * MIN).spread(), "the same bot twice is still two bots");
        assertTrue(rude(BOT3, HENESYS, t + 4 * MIN).spread());
        assertFalse(rude(BOT4, HENESYS, t + 30 * MIN).spread(), "the window passed: the count starts over");
    }

    @Test
    void theMapHearsItEveryBotThereCoolsALittleAndKnowsForAnHour() {
        BotAffinity.load(PLAYER);
        Character player = chr(PLAYER, "Mean", false);
        Character bystander = chr(BOT4, "Bystander", true);
        Character friend = chr(43, "Friend", false);
        long t = clock.get();
        RudeStreak.spread(List.of(bystander, friend, player), player, t);
        assertEquals(AffinityEvent.HEARSAY_BAD.delta(), BotAffinity.score(BOT4, PLAYER), 0.001);
        assertTrue(notes.isEmpty(), "hearing it is not a first-hand note");
        assertTrue(RudeStreak.hasHeard(BOT4, PLAYER, t + 59 * MIN));
        assertFalse(RudeStreak.hasHeard(BOT4, PLAYER, t + 61 * MIN), "it fades after an hour");
        assertFalse(RudeStreak.hasHeard(BOT, PLAYER, t), "a bot that was not there knows nothing");
        assertTrue(RudeStreak.promptLine(BOT4, player, t + MIN).contains("Mean"));
        assertEquals("", RudeStreak.promptLine(BOT4, player, t + 61 * MIN));
        RudeStreak.spread(List.of(bystander), player, t + 50 * MIN);
        assertTrue(RudeStreak.hasHeard(BOT4, PLAYER, t + 100 * MIN), "still at it: the hour starts again");
    }

    @Test
    void withBotDramaOffNobodyOnTheMapHearsIt() {
        BotAffinity.load(PLAYER);
        Character player = chr(PLAYER, "Mean", false);
        BotAffinity.setOptedOut(player, true);
        Character bystander = chr(BOT4, "Bystander", true);
        RudeStreak.spread(List.of(bystander), player, clock.get());
        assertFalse(RudeStreak.hasHeard(BOT4, PLAYER, clock.get()));
        assertEquals(0, BotAffinity.score(BOT4, PLAYER), 0.001);
        assertEquals("", RudeStreak.promptLine(BOT4, player, clock.get()));
    }
}
