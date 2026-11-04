package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.SocialGate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Ticket 271 stage 2 through {@link BotBonds}: directed axes, stages with hysteresis, significance-based eviction, the
 * read API, persistence (restart round trip, legacy rows) and the background tier.
 */
class BotBondsAxesTest {

    static final int A = BotStore.BOT_ID_BASE + 1, B = BotStore.BOT_ID_BASE + 2, C = BotStore.BOT_ID_BASE + 3;
    static final long MIN = TimeUnit.MINUTES.toMillis(1);
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final BondStore.Memory store = new BondStore.Memory();
    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);

    @BeforeEach
    void setUp() {
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotBonds.useForTest(store, Runnable::run, clock::get, id -> new double[]{.25, .25}, (id, p) -> { }, id -> null);
        Persona.useForTest(null);
        SocialGate.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        Persona.useForTest(null);
        SocialSim.reset();
        SocialGate.resetForTest();
        for (String k : new String[]{"solomapling.bonds.k", "slums.bonds.background", "slums.bonds.background.maps",
                "slums.bonds.background.pairs"}) {
            System.clearProperty(k);
        }
    }

    private void rec(int a, int b, BondEvent e, int times) {
        for (int i = 0; i < times; i++) {
            BotBonds.record(a, b, e, clock.get(), true);
        }
    }

    // ── directedness ──

    @Test
    void oneAsymmetricEventMakesTwoDirectionsDiverge() {
        BotBonds.together(A, B, BondEvent.PARTY);
        assertEquals(BotBonds.axes(A, B), BotBonds.axes(B, A), "a shared event: both directions equal");

        // B takes A's mob: only the victim's edge moves
        BotBonds.record(A, B, BondEvent.KS, clock.get(), true);
        Axes aboutB = BotBonds.axes(A, B);
        Axes aboutA = BotBonds.axes(B, A);
        assertNotEquals(aboutB, aboutA);
        assertTrue(aboutB.resentment() > 0 && aboutB.rivalry() > 0, "the victim resents: " + aboutB);
        assertTrue(aboutB.affection() < aboutA.affection(), "and likes less");
        assertTrue(aboutB.trust() < aboutA.trust());
        assertEquals(0, aboutA.resentment(), 0, "the thief resents nothing");
        assertEquals(AxisTable.row(BondEvent.PARTY)[Axis.AFF], aboutA.affection(), 1e-12, "the thief's edge is untouched");
    }

    @Test
    void aKsOnAFreshPairSoursOnlyTheVictimsEdge() {
        BotBonds.record(A, B, BondEvent.KS, clock.get(), true);
        assertTrue(BotBonds.axes(A, B).resentment() > 0);
        assertEquals(Axes.NONE, BotBonds.axes(B, A), "the thief has no edge at all");
        assertEquals(Stage.STRANGER, BotBonds.stage(B, A));
    }

    @Test
    void aGiftWarmsTheReceiverMoreThanTheGiverAndOnlyTheReceiversScoreMoves() {
        MapleMap m = BotBondsTest.map(100);
        Character giver = BotBondsTest.bot(A, m, null), receiver = BotBondsTest.bot(B, m, null);
        BotBonds.onTrade(giver, true, receiver, false);
        Axes got = BotBonds.axes(B, A);
        Axes gave = BotBonds.axes(A, B);
        assertTrue(got.affection() > gave.affection() && gave.affection() > 0,
                "the receiver is fonder (" + got.affection() + ") than the giver (" + gave.affection() + ")");
        assertTrue(got.obligation() > 0.1, "the receiver feels it owes the giver");
        assertTrue(gave.obligation() < 0, "the giver feels it is owed");
        assertEquals(BondEvent.GIFT.delta(), BotBonds.score(B, A), 1e-9, "the receiver's score moved as before");
        assertEquals(0, BotBonds.score(A, B), 0, "the giver's score did not move: every old consumer sees what it saw");
        assertEquals(AffinityState.NEUTRAL, BotBonds.state(A, B));
    }

    @Test
    void aliceTrustsBobButDislikesHimWhileBobAdmiresHerAndCannotTell() {
        // Bob's edge about Alice: a PQ together a few times (respect, affection, trust)
        BotBonds.record(B, A, BondEvent.PQ, clock.get(), true);
        BotBonds.record(B, A, BondEvent.PQ, clock.get(), true);
        BotBonds.record(B, A, BondEvent.PQ, clock.get(), true);
        Axes bobBefore = BotBonds.axes(B, A);

        // Alice's edge about Bob: fair trades (trust), then a run of spats (dislike, resentment)
        rec(A, B, BondEvent.TRADE, 4);
        rec(A, B, BondEvent.SPAT, 3);
        Axes alice = BotBonds.axes(A, B);
        assertTrue(alice.trust() > 0.05, "Alice still trusts Bob: " + alice);
        assertTrue(alice.affection() < -0.03, "and dislikes him: " + alice);
        assertTrue(alice.resentment() > 0.1);

        Axes bobAfter = BotBonds.axes(B, A);
        assertEquals(bobBefore, bobAfter, "Bob's edge did not move when Alice soured: no omniscience");
        assertTrue(bobAfter.respect() > 0.1 && bobAfter.affection() > 0.1, "Bob admires and likes Alice: " + bobAfter);
    }

    // ── the character ──

    @Test
    void aBotsTraitsScaleHowItsEdgeMoves() {
        Persona.useForTest(id -> id == A ? new Persona(.25, .25, 1.0, .5, 1.0, .5) : new Persona(.25, .25, 0.0, .5, 0.0, .5));
        BotBonds.record(A, C, BondEvent.GIFT, clock.get(), true);
        BotBonds.record(B, C, BondEvent.GIFT, clock.get(), true);
        assertTrue(BotBonds.axes(A, C).affection() > BotBonds.axes(B, C).affection(),
                "an agreeable, empathic bot is more touched by the same gift");
        assertEquals(BondEvent.GIFT.delta(), BotBonds.score(A, C), 1e-9, "the legacy score is scaled by Bartle only");
        assertEquals(BotBonds.score(A, C), BotBonds.score(B, C), 1e-9);
    }

    // ── stages through BotBonds ──

    private void friendsFromParties(int a, int b) {
        for (int i = 0; i < 8; i++) {
            BotBonds.record(a, b, BondEvent.PARTY, clock.get(), true);
        }
    }

    @Test
    void stagesRiseWithEventsAndCoolWithTimeWithoutBecomingEstranged() {
        assertEquals(Stage.STRANGER, BotBonds.stage(A, B));
        friendsFromParties(A, B);
        assertEquals(Stage.FRIEND, BotBonds.stage(A, B));
        assertEquals(AffinityState.FRIEND, BotBonds.state(A, B), "the legacy state agrees");
        clock.addAndGet(30 * DAY);
        assertEquals(Stage.FRIEND, BotBonds.stage(A, B), "hysteresis: a month of nothing leaves a friend a friend");
        clock.addAndGet(400 * DAY);
        assertEquals(Stage.ACQUAINTANCE, BotBonds.stage(A, B), "a year later: faded, not estranged");
        assertTrue(BotBonds.axes(A, B).familiarity() >= Axis.familiarityFloor() - 1e-9, "still knows them");
        assertEquals(Stage.ACQUAINTANCE, BotBonds.stage(A, B), "and stays there; time never raises it");
    }

    @Test
    void aBrokenFriendshipIsEstrangedAndStaysSoUntilItHeals() {
        friendsFromParties(A, B);
        assertEquals(Stage.FRIEND, BotBonds.stage(A, B));
        rec(A, B, BondEvent.KS, 7);
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, B));
        assertEquals(Stage.STRANGER, BotBonds.stage(B, A), "the thief has no edge to be estranged in");
        clock.addAndGet(21 * DAY); // resentment .56 halves every 14 days: .56 -> .2 in 21 days, still above .15
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, B), "hysteresis: leaves only once the grudge is nearly gone");
        clock.addAndGet(60 * DAY);
        assertEquals(Stage.ACQUAINTANCE, BotBonds.stage(A, B), "healed by time, they are acquaintances again");
    }

    @Test
    void aSouredStrangerIsARivalNotEstranged() {
        rec(A, B, BondEvent.SPAT, 10);
        assertEquals(Stage.RIVAL, BotBonds.stage(A, B));
    }

    @Test
    void aCooledStageIsKeptOnTheBondAndFlushed() {
        friendsFromParties(A, B);
        BotBonds.flush();
        assertEquals(Stage.FRIEND, store.row(A, B).stage);
        clock.addAndGet(70 * DAY); // warmth under 0.18, score still about 15: cooled, not faded away
        assertEquals(Stage.ACQUAINTANCE, BotBonds.stage(A, B));
        BotBonds.flush();
        assertEquals(Stage.ACQUAINTANCE, store.row(A, B).stage, "the cooled stage is written with the next batch");
    }

    // ── eviction ──

    @Test
    void anEstrangedEdgeOutlivesFadedNeutralOnesAtTheCap() {
        int x = BotStore.BOT_ID_BASE + 500;
        friendsFromParties(A, x);
        rec(A, x, BondEvent.KS, 7);
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, x));
        double xScore = Math.abs(BotBonds.score(A, x));
        for (int i = 0; i < BotBonds.maxBonds(); i++) { // exactly fills the list with weakish neutral acquaintances
            rec(A, BotStore.BOT_ID_BASE + 600 + i, BondEvent.PARTY_TIME, 3);
        }
        double neutralScore = Math.abs(BotBonds.score(A, BotStore.BOT_ID_BASE + 600));
        assertTrue(xScore < neutralScore, "the legacy rule (weakest |score| goes) would have dropped the estranged edge: "
                + xScore + " vs " + neutralScore);
        assertEquals(BotBonds.maxBonds(), BotBonds.sizeOf(A));
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, x), "the estranged edge is still held");
        assertTrue(BotBonds.axes(A, x).resentment() > 0.4);
        BotBonds.flush();
        assertNotNull(store.row(A, x));
    }

    @Test
    void rivalAndCloseAndEstrangedAllBeatFadedNeutralWhenTheListIsTight() {
        System.setProperty("solomapling.bonds.k", "4");
        int estranged = BotStore.BOT_ID_BASE + 700, rival = BotStore.BOT_ID_BASE + 701, close = BotStore.BOT_ID_BASE + 702;
        friendsFromParties(A, estranged);
        rec(A, estranged, BondEvent.KS, 7);
        rec(A, rival, BondEvent.SPAT, 10);
        rec(A, close, BondEvent.PQ, 10);
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, estranged));
        assertEquals(Stage.RIVAL, BotBonds.stage(A, rival));
        assertEquals(Stage.CLOSE_FRIEND, BotBonds.stage(A, close));
        for (int i = 0; i < 10; i++) {
            rec(A, BotStore.BOT_ID_BASE + 800 + i, BondEvent.PARTY_TIME, 1);
        }
        assertEquals(4, BotBonds.sizeOf(A));
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, estranged));
        assertEquals(Stage.RIVAL, BotBonds.stage(A, rival));
        assertEquals(Stage.CLOSE_FRIEND, BotBonds.stage(A, close));
        Set<Integer> held = new HashSet<>();
        BotBonds.bondsOf(A).forEach(v -> held.add(v.otherId()));
        assertTrue(held.containsAll(List.of(estranged, rival, close)), held.toString());
    }

    @Test
    void aFadedNeutralBondIsStillDroppedFirstAndAFadedGrudgeTheScoreForgotIsNot() {
        // the score of this bond is forgotten (a year), resentment is not (set fresh): not "faded"
        Bond kept = new Bond(A, B, 0, 0, 2, clock.get(), new double[]{0.5, 0, -0.1, 0, 0, 0.3, 0.6, 0}, Stage.RIVAL);
        assertFalse(BondRules.faded(kept, clock.get()), "resentment still holds a feeling");
        assertTrue(BondRules.faded(kept, clock.get() + 400 * DAY), "a year later it is gone too");
        Bond nothing = new Bond(A, B, 2, 1, 0, clock.get(), new double[]{0.2, 0, 0.01, 0, 0, 0, 0, 0}, Stage.ACQUAINTANCE);
        assertTrue(BondRules.faded(nothing, clock.get()), "familiarity alone does not keep a bond");
    }

    @Test
    void theGmDescriptionShowsStageAndAxes() {
        friendsFromParties(A, B);
        rec(A, C, BondEvent.KS, 2);
        List<String> lines = BotBonds.describe(A, id -> "n" + (id - BotStore.BOT_ID_BASE), 6);
        String stages = lines.get(lines.size() - 1);
        assertTrue(stages.startsWith("Stages: n2 friend (fam 0.64 trust 0.32 aff 0.48"), stages);
        assertTrue(stages.contains("n3 stranger") || stages.contains("n3 acquaintance"), stages);
        assertTrue(stages.contains("res 0.16"), stages);
    }

    // ── read API ──

    @Test
    void theReadApiGivesImmutableViewsAndNoneForNoBond() {
        assertEquals(Axes.NONE, BotBonds.axes(A, B));
        assertEquals(Stage.STRANGER, BotBonds.stage(A, B));
        rec(A, B, BondEvent.PARTY, 3);
        Axes view = BotBonds.axes(A, B);
        double aff = view.affection();
        double[] copy = view.toArray();
        copy[Axis.AFF] = 99;
        assertEquals(aff, view.affection(), 0, "toArray is a copy");
        assertEquals(aff, BotBonds.axes(A, B).affection(), 0, "and the bond did not change");
        assertEquals(view.get(Axis.AFFECTION), view.affection(), 0);
        rec(A, B, BondEvent.PARTY, 1);
        assertEquals(aff, view.affection(), 0, "a view taken earlier does not move with the bond");
        assertTrue(BotBonds.axes(A, B).affection() > aff);
        System.setProperty("solomapling.bonds", "false");
        try {
            assertEquals(Axes.NONE, BotBonds.axes(A, B), "switched off: nothing");
            assertEquals(Stage.STRANGER, BotBonds.stage(A, B));
        } finally {
            System.clearProperty("solomapling.bonds");
        }
    }

    @Test
    void axesFadeOnReadWithoutAnyWrite() {
        rec(A, B, BondEvent.KS, 4);
        int writes = store.writes;
        double fresh = BotBonds.axes(A, B).resentment();
        clock.addAndGet(14 * DAY);
        assertEquals(fresh / 2, BotBonds.axes(A, B).resentment(), 1e-9, "resentment halves in two weeks");
        assertEquals(writes, store.writes, "reading wrote nothing");
    }

    // ── persistence ──

    @Test
    void axesAndStageSurviveAFlushAndARestart() {
        friendsFromParties(A, B);
        rec(A, C, BondEvent.KS, 2);
        rec(B, C, BondEvent.GIFT, 3);
        Axes ab = BotBonds.axes(A, B), ac = BotBonds.axes(A, C), bc = BotBonds.axes(B, C);
        Stage stageAb = BotBonds.stage(A, B);
        assertEquals(Stage.FRIEND, stageAb);
        assertTrue(BotBonds.flush() >= 3);
        assertEquals(Stage.FRIEND, store.row(A, B).stage);
        assertTrue(store.row(A, B).axes[Axis.AFF] > 0, "the store holds the axes");

        BotBonds.useForTest(store, Runnable::run, clock::get, id -> new double[]{.25, .25}, (id, p) -> { }, id -> null);
        assertEquals(Axes.NONE, BotBonds.axes(A, B), "forgotten until loaded");
        assertTrue(BotBonds.loadNow());
        assertAxesEqual(ab, BotBonds.axes(A, B));
        assertAxesEqual(ac, BotBonds.axes(A, C));
        assertAxesEqual(bc, BotBonds.axes(B, C));
        assertEquals(stageAb, BotBonds.stage(A, B));
        assertEquals(AffinityState.FRIEND, BotBonds.state(A, B));

        // the hysteresis memory survives too: an estranged edge reloads as estranged, not as a fresh rival
        rec(A, B, BondEvent.KS, 7);
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, B));
        BotBonds.flush();
        BotBonds.useForTest(store, Runnable::run, clock::get, id -> new double[]{.25, .25}, (id, p) -> { }, id -> null);
        assertTrue(BotBonds.loadNow());
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, B));
        rec(A, B, BondEvent.PARTY, 1);
        assertEquals(Stage.ESTRANGED, BotBonds.stage(A, B), "one kind event does not end an estrangement");
    }

    private static void assertAxesEqual(Axes expected, Axes actual) {
        for (Axis a : Axis.values()) {
            assertEquals(expected.get(a), actual.get(a), 1e-6, a.name()); // the table stores floats
        }
    }

    @Test
    void oldRowsWithoutAxesLoadWithAxesDerivedFromScoreKindAndHostile() {
        long now = clock.get();
        store.rows.put(BotBonds.key(A, B), new Bond(A, B, 60, 5, 0, now)); // a pre-migration row
        store.rows.put(BotBonds.key(B, C), new Bond(B, C, -40, 0, 3, now));
        assertTrue(BotBonds.loadNow());
        Axes friend = BotBonds.axes(A, B);
        assertTrue(friend.affection() > 0.9 && friend.trust() > 0.5 && friend.resentment() == 0, friend.toString());
        assertEquals(Stage.FRIEND, BotBonds.stage(A, B));
        assertEquals(AffinityState.FRIEND, BotBonds.state(A, B), "the legacy state is read as before");
        Axes grudge = BotBonds.axes(B, C);
        assertTrue(grudge.resentment() > 0.6 && grudge.affection() < 0, grudge.toString());
        assertEquals(Stage.RIVAL, BotBonds.stage(B, C));
        assertEquals(AffinityState.RIVAL, BotBonds.state(B, C));
        // the next event on a derived edge builds on the derived axes
        double before = friend.affection();
        BotBonds.record(A, B, BondEvent.PARTY, now, true);
        assertTrue(BotBonds.axes(A, B).affection() > before);
    }

    @Test
    void theStoredStageOfALegacyRowIsDerivedNotTrusted() {
        Bond old = new Bond(A, B, 0, 0, 0, clock.get());
        assertEquals(Stage.STRANGER, old.rawStage());
        assertEquals(Axes.NONE, old.rawAxes());
        Bond snapshot = new Bond(A, B, 60, 5, 0, clock.get()).snapshot();
        assertEquals(Stage.FRIEND, snapshot.rawStage());
    }

    // ── background tier ──

    private static Map<Integer, MapleMap> quietMaps(int... ids) {
        Map<Integer, MapleMap> out = new HashMap<>();
        for (int id : ids) {
            out.put(id, BotBondsTest.map(id));
        }
        return out;
    }

    private static List<Character> crowd(MapleMap map, int firstSlot, int n) {
        List<Character> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(BotBondsTest.bot(BotStore.BOT_ID_BASE + firstSlot + i, map, null));
        }
        return out;
    }

    private static int bondsHeld(List<Character> bots) {
        int n = 0;
        for (Character c : bots) {
            n += BotBonds.sizeOf(c.getId());
        }
        return n;
    }

    @Test
    void unwatchedBotsFormBondsInABoundedBackgroundTier() {
        SocialSim.useForTest(clock::get, 7);
        BotBonds.useForTest(store, Runnable::run, null, id -> new double[]{.25, .25}, (id, p) -> { }, id -> null); // SocialSim clock
        BotBonds.useObservedForTest(mapId -> mapId == 100);
        Map<Integer, MapleMap> maps = quietMaps(100, 201, 202, 203, 204, 205);
        List<Character> bots = new ArrayList<>(crowd(maps.get(100), 1000, 20));
        List<List<Character>> quiet = new ArrayList<>();
        int slot = 2000;
        for (int id : new int[]{201, 202, 203, 204, 205}) {
            List<Character> here = crowd(maps.get(id), slot, 30);
            slot += 100;
            quiet.add(here);
            bots.addAll(here);
        }
        assertTrue(BotBonds.background());
        assertEquals(2, BotBonds.backgroundMaps());
        assertEquals(1, BotBonds.backgroundPairsPerMap());

        BotBonds.step(bots, SocialSim.random(0, "bonds-step"));
        int[] bg = BotBonds.backgroundStats();
        assertEquals(2, bg[1], "two unobserved maps per step");
        assertEquals(2, bg[0], "one pair each");
        int quietBonds = 0;
        int mapsTouched = 0;
        for (List<Character> here : quiet) {
            int n = bondsHeld(here);
            quietBonds += n;
            if (n > 0) {
                mapsTouched++;
            }
        }
        assertEquals(4, quietBonds, "two pairs, both directions: bounded");
        assertEquals(2, mapsTouched);

        // over simulated hours the bounded tier still reaches every unwatched map, never more than 2 in one step
        Set<Integer> reached = new HashSet<>();
        for (int step = 0; step < 90; step++) {
            clock.addAndGet(31 * MIN);
            BotBonds.step(bots, SocialSim.random(0, "bonds-step"));
            assertTrue(BotBonds.backgroundStats()[1] <= 2);
            assertTrue(BotBonds.backgroundStats()[0] <= 2);
            for (int q = 0; q < quiet.size(); q++) {
                if (bondsHeld(quiet.get(q)) > 0) {
                    reached.add(q);
                }
            }
        }
        assertEquals(5, reached.size(), "every unwatched map gets bonds in time");
        for (List<Character> here : quiet) { // and no bot ends up past the cap
            for (Character c : here) {
                assertTrue(BotBonds.sizeOf(c.getId()) <= BotBonds.maxBonds());
            }
        }
        // the new bonds are real edges with axes and a stage
        int someone = -1;
        for (List<Character> here : quiet) {
            for (Character c : here) {
                if (BotBonds.sizeOf(c.getId()) > 0) {
                    someone = c.getId();
                }
            }
        }
        int other = BotBonds.bondsOf(someone).get(0).otherId();
        assertTrue(BotBonds.axes(someone, other).familiarity() > 0);
        assertNotEquals(Stage.FRIEND, BotBonds.stage(someone, other), "a nod is not a friendship");
    }

    @Test
    void theBackgroundTierUsesTheSimulatedClockForItsCooldowns() {
        SocialSim.useForTest(clock::get, 3);
        BotBonds.useForTest(store, Runnable::run, null, id -> new double[]{.25, .25}, (id, p) -> { }, id -> null);
        BotBonds.useObservedForTest(mapId -> false);
        MapleMap quiet = BotBondsTest.map(300);
        List<Character> two = crowd(quiet, 3000, 2);
        BotBonds.step(two, new Random(1));
        double first = BotBonds.axes(two.get(0).getId(), two.get(1).getId()).familiarity();
        assertTrue(first > 0);
        clock.addAndGet(MIN);
        BotBonds.step(two, new Random(1));
        assertEquals(first, BotBonds.axes(two.get(0).getId(), two.get(1).getId()).familiarity(), 1e-12,
                "NEAR has a 30 minute pair cooldown on the simulated clock");
        clock.addAndGet(31 * MIN);
        BotBonds.step(two, new Random(1));
        assertTrue(BotBonds.axes(two.get(0).getId(), two.get(1).getId()).familiarity() > first);
    }

    @Test
    void guildMatesOnAnUnwatchedMapGetTheGuildSeed() {
        BotBonds.useObservedForTest(mapId -> false);
        MapleMap quiet = BotBondsTest.map(300);
        List<Character> two = crowd(quiet, 3100, 2);
        for (Character c : two) {
            when(c.getGuildId()).thenReturn(5);
        }
        BotBonds.step(two, new Random(1));
        assertEquals(AxisTable.row(BondEvent.GUILD)[Axis.OBL], BotBonds.axes(two.get(0).getId(), two.get(1).getId()).obligation(),
                1e-12, "GUILD, not NEAR, was the event");
    }

    @Test
    void theBackgroundTierSwitchesOffAndClampsItsKnobs() {
        BotBonds.useObservedForTest(mapId -> false);
        List<Character> two = crowd(BotBondsTest.map(300), 3200, 2);
        System.setProperty("slums.bonds.background", "false");
        assertFalse(BotBonds.background());
        BotBonds.step(two, new Random(1));
        assertEquals(0, bondsHeld(two), "off: an unwatched map is never sampled (the old behaviour)");
        assertEquals(0, BotBonds.backgroundStats()[1]);

        System.clearProperty("slums.bonds.background");
        System.setProperty("slums.bonds.background.maps", "0");
        BotBonds.step(two, new Random(1));
        assertEquals(0, bondsHeld(two), "zero maps: nothing");

        System.setProperty("slums.bonds.background.maps", "500");
        assertEquals(20, BotBonds.backgroundMaps());
        System.setProperty("slums.bonds.background.pairs", "50");
        assertEquals(BotBonds.pairsPerMap(), BotBonds.backgroundPairsPerMap(), "never above the observed tier's rate");
        System.setProperty("slums.bonds.background.pairs", "-4");
        assertEquals(0, BotBonds.backgroundPairsPerMap());
        System.setProperty("slums.bonds.background.maps", "2");
        System.setProperty("slums.bonds.background.pairs", "1");
        BotBonds.step(two, new Random(1));
        assertTrue(bondsHeld(two) > 0, "back on");
    }

    @Test
    void aMapWithOneBotAndWatchedMapsAreNotBackgroundCandidates() {
        BotBonds.useObservedForTest(mapId -> mapId == 100);
        List<Character> bots = new ArrayList<>();
        bots.addAll(crowd(BotBondsTest.map(100), 3300, 10));      // watched: the observed tier's job
        bots.addAll(crowd(BotBondsTest.map(400), 3400, 1));       // a lone bot
        BotBonds.step(bots, new Random(1));
        assertEquals(0, BotBonds.backgroundStats()[1], "no unwatched map holds two bots");
        assertEquals(0, BotBonds.sizeOf(BotStore.BOT_ID_BASE + 3400));
        assertTrue(BotBonds.stats().contains("background 0 pairs on 0 maps"), BotBonds.stats());
    }
}
