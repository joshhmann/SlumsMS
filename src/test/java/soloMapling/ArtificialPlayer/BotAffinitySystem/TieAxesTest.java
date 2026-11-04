package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 271 stage 2 for bot-to-player ties: the same axes and stages as bonds, through {@link BotAffinity}. */
class TieAxesTest {

    static final int BOT = BotStore.BOT_ID_BASE + 7;
    static final int BOT2 = BotStore.BOT_ID_BASE + 8;
    static final int PLAYER = 42;
    static final long MIN = TimeUnit.MINUTES.toMillis(1);
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final AffinityStore.Memory store = new AffinityStore.Memory();
    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(store, Runnable::run, clock::get, id -> true);
        BotAffinity.useNotesForTest((slot, player, text) -> { });
        Persona.useForTest(id -> Persona.NEUTRAL); // exact table values: a slot's Bartle point would scale them
        AffinityReactions.resetForTest();
        AffinityHooks.resetForTest();
        BotAffinity.load(PLAYER);
    }

    @AfterEach
    void tearDown() {
        BotAffinity.useForTest(null, null, null, null);
        BotAffinity.useNotesForTest(null);
        Persona.useForTest(null);
    }

    private void ev(int bot, AffinityEvent e) {
        BotAffinity.record(bot, PLAYER, e, e.delta(), "Player", "Bot", 100000000);
        clock.addAndGet(31 * MIN);
    }

    @Test
    void eventsMoveTheBotsAxesAboutThePlayerAndStagesFollow() {
        assertEquals(Axes.NONE, BotAffinity.axes(BOT, PLAYER));
        assertEquals(Stage.STRANGER, BotAffinity.stage(BOT, PLAYER));
        for (AffinityEvent e : new AffinityEvent[]{AffinityEvent.RESCUE, AffinityEvent.GIFT, AffinityEvent.PARTY,
                AffinityEvent.FAVOR, AffinityEvent.RESCUE, AffinityEvent.GIFT}) {
            ev(BOT, e);
        }
        Axes a = BotAffinity.axes(BOT, PLAYER);
        assertTrue(a.obligation() > 0.3, "the bot feels it owes the player: " + a);
        assertTrue(a.trust() > 0.15 && a.affection() > 0.2);
        assertEquals(Stage.FRIEND, BotAffinity.stage(BOT, PLAYER), "after six kind things (the legacy state needs only three)");
        assertEquals(Axes.NONE, BotAffinity.axes(BOT2, PLAYER), "another bot's edge is its own");
        assertEquals(AffinityState.FRIEND, BotAffinity.state(BOT, PLAYER), "the legacy state still agrees");
    }

    @Test
    void ksesMakeARivalAndDefamesAndScamsBreakTrustMostAndAnApologyReleasesTheGrudge() {
        for (int i = 0; i < 6; i++) {
            ev(BOT, AffinityEvent.KS);
        }
        assertEquals(Stage.RIVAL, BotAffinity.stage(BOT, PLAYER));
        double grudge = BotAffinity.axes(BOT, PLAYER).resentment();
        ev(BOT, AffinityEvent.SCAM);
        assertTrue(BotAffinity.axes(BOT, PLAYER).trust() < -0.2, "a scam breaks trust");
        ev(BOT, AffinityEvent.APOLOGY);
        assertTrue(BotAffinity.axes(BOT, PLAYER).resentment() < grudge, "the apology released resentment");
    }

    @Test
    void aBrokenFriendshipWithAPlayerIsEstranged() {
        for (AffinityEvent e : new AffinityEvent[]{AffinityEvent.PARTY, AffinityEvent.GIFT, AffinityEvent.RESCUE,
                AffinityEvent.PARTY, AffinityEvent.FAVOR}) {
            ev(BOT, e);
        }
        assertEquals(Stage.FRIEND, BotAffinity.stage(BOT, PLAYER));
        ev(BOT, AffinityEvent.DEFAME);
        ev(BOT, AffinityEvent.DEFAME);
        ev(BOT, AffinityEvent.DROP_STEAL);
        assertEquals(Stage.ESTRANGED, BotAffinity.stage(BOT, PLAYER));
        clock.addAndGet(400 * DAY);
        assertTrue(BotAffinity.stage(BOT, PLAYER) != Stage.FRIEND);
    }

    @Test
    void romanceBuildsAttractionAndABigGiftCountsMoreThanASmallOne() {
        for (int i = 0; i < 4; i++) {
            ev(BOT, AffinityEvent.ROMANCE);
        }
        assertTrue(BotAffinity.axes(BOT, PLAYER).attraction() > 0.25);
        BotAffinity.record(BOT2, PLAYER, AffinityEvent.GIFT, AffinityEvent.GIFT.delta(), "P", "B", 1);
        BotAffinity.record(BOT, PLAYER, AffinityEvent.GIFT, 2 * AffinityEvent.GIFT.delta(), "P", "B", 1);
        double small = BotAffinity.axes(BOT2, PLAYER).affection();
        double big = BotAffinity.axes(BOT, PLAYER).affection() - 4 * AxisTable.row(AffinityEvent.ROMANCE)[Axis.AFF];
        assertEquals(2 * small, big, 2e-3, "a gift worth twice the delta moves the axes twice as far");
    }

    @Test
    void hearsayNeverMovesTrustAffectionOrResentmentPastItsCap() {
        for (int i = 0; i < 30; i++) {
            ev(BOT, AffinityEvent.HEARSAY_BAD);
            clock.addAndGet(60 * MIN);
        }
        Axes a = BotAffinity.axes(BOT, PLAYER);
        assertTrue(Math.abs(a.trust()) <= AffinityRules.HEARSAY_AXIS_CAP + 1e-9 && Math.abs(a.affection()) <= AffinityRules.HEARSAY_AXIS_CAP + 1e-9
                && a.resentment() <= AffinityRules.HEARSAY_AXIS_CAP + 1e-9, a.toString());
        assertEquals(0, a.familiarity(), 0, "hearsay is not an interaction");
        assertTrue(BotAffinity.stage(BOT, PLAYER) == Stage.STRANGER, "gossip alone never makes a rival or a friend");
    }

    @Test
    void axesAndStageSurviveTheStoreAndOldTiesLoadDerived() {
        for (int i = 0; i < 4; i++) {
            ev(BOT, AffinityEvent.PARTY);
            ev(BOT, AffinityEvent.GIFT);
        }
        Axes before = BotAffinity.axes(BOT, PLAYER);
        Stage stage = BotAffinity.stage(BOT, PLAYER);
        assertNotNull(store.rows.values().iterator().next());
        store.save(new Tie(BOT2 - BotStore.BOT_ID_BASE, PLAYER, 60, 5, 0, clock.get())); // a pre-migration row
        store.save(new Tie(9, PLAYER, -40, 0, 3, clock.get()));

        BotAffinity.useForTest(store, Runnable::run, clock::get, id -> true); // a restart: memory forgotten
        assertEquals(Axes.NONE, BotAffinity.axes(BOT, PLAYER));
        BotAffinity.load(PLAYER);
        Axes after = BotAffinity.axes(BOT, PLAYER);
        for (Axis a : Axis.values()) {
            assertEquals(before.get(a), after.get(a), 1e-6, a.name());
        }
        assertEquals(stage, BotAffinity.stage(BOT, PLAYER));
        assertTrue(BotAffinity.axes(BOT2, PLAYER).affection() > 0.9, "the old friend row derived its axes");
        assertEquals(Stage.FRIEND, BotAffinity.stage(BOT2, PLAYER));
        assertEquals(Stage.RIVAL, BotAffinity.stage(BotStore.BOT_ID_BASE + 9, PLAYER));
        assertEquals(AffinityState.RIVAL, BotAffinity.state(BotStore.BOT_ID_BASE + 9, PLAYER));
    }

    @Test
    void theCapKeepsEstrangedAndRivalTiesOverFadedNeutralOnes() {
        BotAffinity.PlayerTies pt = new BotAffinity.PlayerTies(PLAYER);
        long now = clock.get();
        int max = BotAffinity.maxTiesPerPlayer();
        Tie estranged = new Tie(1, PLAYER, 2, 0, 3, now, new double[]{0.6, 0, 0.05, 0, 0, 0.2, 0.55, 0}, Stage.ESTRANGED);
        Tie rival = new Tie(2, PLAYER, -4, 0, 3, now, new double[]{0.3, -0.1, -0.2, 0, 0, 0.3, 0.45, 0}, Stage.RIVAL);
        pt.ties.put(1, estranged);
        pt.ties.put(2, rival);
        for (int i = 3; i < 3 + max; i++) { // faded neutral acquaintances, each with a bigger |score| than the grudges
            pt.ties.put(i, new Tie(i, PLAYER, 5 + (i % 7), 1, 0, now, new double[]{0.1, 0.01, 0.03, 0, 0, 0, 0, 0}, Stage.ACQUAINTANCE));
        }
        List<Tie> dropped = BotAffinity.bound(pt, now, -1);
        assertEquals(max, pt.ties.size());
        assertEquals(2, dropped.size());
        assertTrue(pt.ties.containsKey(1) && pt.ties.containsKey(2), "estranged and rival stay");
        assertFalse(dropped.contains(estranged) || dropped.contains(rival));
    }
}
