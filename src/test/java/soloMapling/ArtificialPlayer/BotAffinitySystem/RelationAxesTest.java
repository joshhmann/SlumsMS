package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 2, the pure part: the axis table, the character scaling, per-axis decay with the familiarity floor,
 * the legacy derivation and the stage hysteresis. Nothing here touches BotBonds or a store.
 */
class RelationAxesTest {

    static final long DAY = TimeUnit.DAYS.toMillis(1);
    static final long MIN = TimeUnit.MINUTES.toMillis(1);

    @AfterEach
    void tearDown() {
        for (String k : new String[]{"solomapling.bonds.party", "solomapling.bonds.gift", "solomapling.axes.affection.halfLifeDays",
                "solomapling.axes.familiarity.floor"}) {
            System.clearProperty(k);
        }
    }

    private static double[] v(double fam, double trust, double aff, double resp, double attr, double riv, double res,
                              double obl) {
        return new double[]{fam, trust, aff, resp, attr, riv, res, obl};
    }

    // ── the table ──

    @Test
    void everyEventKindHasARowAndTheRowsSayWhatTheEventMeans() {
        for (BondEvent e : BondEvent.values()) {
            double[] row = AxisTable.row(e);
            assertFalse(AxisTable.isZero(row), e + " has an axis effect");
            if (e.hostile()) {
                assertTrue(row[Axis.AFF] < 0 && row[Axis.REN] > 0 && row[Axis.RIV] > 0, e + " sours the holder");
            } else {
                assertTrue(row[Axis.AFF] > 0 && row[Axis.REN] == 0, e + " warms the holder");
            }
            assertTrue(row[Axis.FAM] > 0, e + ": familiarity grows with every interaction");
        }
        for (AffinityEvent e : AffinityEvent.values()) {
            double[] row = AxisTable.row(e);
            assertFalse(AxisTable.isZero(row), e + " has an axis effect");
            if (!e.hearsay()) {
                assertTrue(row[Axis.FAM] > 0, e + ": familiarity grows with every interaction");
            }
            if (e.hostile()) {
                assertTrue(row[Axis.REN] > 0 && row[Axis.AFF] < 0 && row[Axis.TRU] < 0, e + " sours the bot");
            }
        }
        assertTrue(AxisTable.row(AffinityEvent.ROMANCE)[Axis.ATT] > 0, "romance moves attraction");
        assertTrue(AxisTable.row(AffinityEvent.APOLOGY)[Axis.REN] < 0, "an apology releases resentment");
        assertTrue(AxisTable.row(AffinityEvent.SCAM)[Axis.TRU] < AxisTable.row(AffinityEvent.KS)[Axis.TRU],
                "a scam breaks trust harder than a KS");
        assertTrue(AxisTable.row(AffinityEvent.FAME)[Axis.RES] > AxisTable.row(AffinityEvent.FAME)[Axis.AFF],
                "fame is admiration more than liking");
    }

    @Test
    void aNeutralCharacterIsScaledByExactlyTheEventWeight() {
        for (BondEvent e : BondEvent.values()) {
            assertArrayEquals(AxisTable.row(e), AxisTable.scaled(e, Persona.NEUTRAL), 1e-12, e.name());
        }
        double[] big = AxisTable.scaled(AffinityEvent.GIFT, 2.0, Persona.NEUTRAL);
        assertEquals(2 * AxisTable.row(AffinityEvent.GIFT)[Axis.AFF], big[Axis.AFF], 1e-12, "a gift worth twice counts twice");
    }

    @Test
    void theCharacterScalesWhatLands() {
        Persona agreeable = new Persona(0.25, 0.25, 1.0, 0.5, 0.5, 0.5);
        Persona prickly = new Persona(0.25, 0.25, 0.0, 0.5, 0.5, 0.5);
        double warm = AxisTable.row(BondEvent.PARTY)[Axis.AFF];
        assertTrue(AxisTable.scaled(BondEvent.PARTY, agreeable)[Axis.AFF] > warm);
        assertTrue(AxisTable.scaled(BondEvent.PARTY, prickly)[Axis.AFF] < warm);
        assertTrue(AxisTable.scaled(BondEvent.KS, prickly)[Axis.REN] > AxisTable.scaled(BondEvent.KS, agreeable)[Axis.REN],
                "an agreeable bot lets go faster");

        Persona jealous = new Persona(0.25, 0.25, 0.5, 1.0, 0.5, 0.5);
        Persona content = new Persona(0.25, 0.25, 0.5, 0.0, 0.5, 0.5);
        assertTrue(AxisTable.scaled(BondEvent.KS, jealous)[Axis.RIV] > AxisTable.scaled(BondEvent.KS, content)[Axis.RIV]);

        Persona loyal = new Persona(0.25, 0.25, 0.5, 0.5, 0.5, 1.0);
        Persona fickle = new Persona(0.25, 0.25, 0.5, 0.5, 0.5, 0.0);
        assertTrue(AxisTable.scaled(BondEvent.KS, loyal)[Axis.TRU] > AxisTable.scaled(BondEvent.KS, fickle)[Axis.TRU],
                "a loyal bot loses less trust to a slip (the delta is negative)");
        assertTrue(AxisTable.scaled(BondEvent.GIFT, loyal)[Axis.OBL] > AxisTable.scaled(BondEvent.GIFT, fickle)[Axis.OBL]);

        Persona empathic = new Persona(0.25, 0.25, 0.5, 0.5, 1.0, 0.5);
        Persona cold = new Persona(0.25, 0.25, 0.5, 0.5, 0.0, 0.5);
        assertTrue(AxisTable.scaled(BondEvent.GIFT, empathic)[Axis.AFF] > AxisTable.scaled(BondEvent.GIFT, cold)[Axis.AFF]);

        // Bartle, as the legacy score: a Socializer warms x1.8, a Killer sours x1.8
        Persona socializer = new Persona(0.9, 0.0, 0.5, 0.5, 0.5, 0.5);
        Persona killer = new Persona(0.0, 0.9, 0.5, 0.5, 0.5, 0.5);
        assertEquals(1.8 * warm, AxisTable.scaled(BondEvent.PARTY, socializer)[Axis.AFF], 1e-12);
        assertEquals(1.8 * AxisTable.row(BondEvent.KS)[Axis.AFF], AxisTable.scaled(BondEvent.KS, killer)[Axis.AFF], 1e-12);
        assertEquals(AxisTable.row(BondEvent.PARTY)[Axis.FAM], AxisTable.scaled(BondEvent.PARTY, socializer)[Axis.FAM], 1e-12,
                "familiarity is not scaled");
    }

    @Test
    void anOperatorWeightTunesTheAxesWithTheScoreAndZeroSwitchesTheEventOff() {
        assertEquals(1.0, BondEvent.PARTY.weight(), 1e-12);
        System.setProperty("solomapling.bonds.party", "12");
        assertEquals(2.0, BondEvent.PARTY.weight(), 1e-12);
        assertEquals(2 * AxisTable.row(BondEvent.PARTY)[Axis.AFF], AxisTable.scaled(BondEvent.PARTY, Persona.NEUTRAL)[Axis.AFF], 1e-12);
        System.setProperty("solomapling.bonds.party", "0");
        Bond b = new Bond(1, 2, 0, 0, 0, 0);
        assertFalse(BondRules.apply(b, BondEvent.PARTY, 0, 0, false), "tuned to nothing: no score, no axes");
        assertEquals(1.0, BondEvent.GIFT_GIVEN.weight(), 1e-12, "an event with no score effect always weighs 1");
    }

    // ── applying events ──

    @Test
    void anEventMovesTheScoreAsBeforeAndTheAxesByTheTable() {
        Bond b = new Bond(1, 2, 0, 0, 0, 0);
        assertTrue(BondRules.apply(b, BondEvent.PARTY, 6, 1000, false));
        assertEquals(6, b.score, 1e-12, "the legacy score is moved by the delta alone");
        double[] row = AxisTable.row(BondEvent.PARTY);
        assertArrayEquals(row, b.axes, 1e-12);
        assertEquals(Stage.ACQUAINTANCE, b.stage, "one event, one rung: stranger to acquaintance");
        // an axis-only event moves axes, never the score or the counters
        Bond g = new Bond(1, 2, 0, 0, 0, 0);
        assertTrue(BondRules.apply(g, BondEvent.GIFT_GIVEN, 0, 1000, false));
        assertEquals(0, g.score, 1e-12);
        assertEquals(0, g.kind);
        assertEquals(0, g.hostile);
        assertTrue(g.axes[Axis.AFF] > 0 && g.axes[Axis.OBL] < 0);
    }

    @Test
    void axesStayInRangeHoweverOftenTheEventRepeats() {
        Bond b = new Bond(1, 2, 0, 0, 0, 0);
        for (int i = 0; i < 400; i++) {
            BondRules.apply(b, BondEvent.PQ, 8, 1000, true);
        }
        for (Axis a : Axis.values()) {
            assertTrue(b.axes[a.ordinal()] <= a.max && b.axes[a.ordinal()] >= a.min, a.name());
        }
        assertEquals(1.0, b.axes[Axis.FAM], 1e-12);
        assertEquals(1.0, b.axes[Axis.AFF], 1e-12);
    }

    // ── decay ──

    @Test
    void eachAxisFadesByItsOwnHalfLife() {
        assertEquals(0.25, Axis.AFFECTION.faded(0.5, 42 * DAY), 1e-9, "liking halves in six weeks");
        assertEquals(-0.25, Axis.AFFECTION.faded(-0.5, 14 * DAY), 1e-9, "dislike halves in two weeks");
        assertEquals(0.25, Axis.RESENTMENT.faded(0.5, 14 * DAY), 1e-9);
        assertEquals(0.25, Axis.RIVALRY.faded(0.5, 28 * DAY), 1e-9);
        assertEquals(0.25, Axis.ATTRACTION.faded(0.5, 21 * DAY), 1e-9);
        assertEquals(0.25, Axis.TRUST.faded(0.5, 56 * DAY), 1e-9);
        assertEquals(-0.25, Axis.TRUST.faded(-0.5, 28 * DAY), 1e-9, "broken trust heals faster than it was built");
        assertEquals(0.25, Axis.RESPECT.faded(0.5, 84 * DAY), 1e-9);
        assertEquals(0.25, Axis.OBLIGATION.faded(0.5, 21 * DAY), 1e-9);
        assertEquals(0.5, Axis.AFFECTION.faded(0.5, -5), 1e-12, "no fade backwards in time");
        System.setProperty("solomapling.axes.affection.halfLifeDays", "10");
        assertEquals(0.25, Axis.AFFECTION.faded(0.5, 10 * DAY), 1e-9, "tunable");
    }

    @Test
    void familiarityFadesTowardAFloorAndNeverBelowIt() {
        double floor = Axis.familiarityFloor();
        assertEquals(0.08, floor, 1e-12);
        assertEquals(floor, Axis.FAMILIARITY.faded(0.9, 100_000 * DAY), 1e-6, "a met pair keeps knowing each other");
        assertTrue(Axis.FAMILIARITY.faded(0.9, 120 * DAY) > floor + 0.4, "and fades slowly on the way");
        assertEquals(0.03, Axis.FAMILIARITY.faded(0.03, 400 * DAY), 1e-12, "under the floor stays put (it is not pulled up)");
        // through a bond: a friendship that faded for years still knows the other
        Bond b = new Bond(1, 2, 0, 0, 0, 0);
        for (int i = 0; i < 6; i++) {
            BondRules.apply(b, BondEvent.PARTY, 6, 0, true);
        }
        double[] later = BondRules.axesAt(b, 5000 * DAY);
        assertEquals(floor, later[Axis.FAM], 1e-6);
        assertTrue(later[Axis.AFF] < 1e-6, "the liking is gone");
    }

    // ── legacy rows ──

    @Test
    void aRowWithoutAxesGetsThemFromScoreKindAndHostile() {
        Bond friend = new Bond(1, 2, 60, 5, 0, 0);
        assertTrue(friend.axes[Axis.AFF] > 0.9 && friend.axes[Axis.TRU] > 0.5 && friend.axes[Axis.REN] == 0);
        assertTrue(friend.axes[Axis.FAM] >= 0.25, "five kind things were five interactions");
        assertEquals(Stage.FRIEND, friend.stage);

        Bond rival = new Bond(1, 2, -40, 0, 3, 0);
        assertTrue(rival.axes[Axis.REN] > 0.6 && rival.axes[Axis.AFF] < 0 && rival.axes[Axis.RIV] > 0.3);
        assertEquals(Stage.RIVAL, rival.stage);

        Bond nothing = new Bond(1, 2, 0, 0, 0, 0);
        assertArrayEquals(new double[Axis.COUNT], nothing.axes, 0);
        assertEquals(Stage.STRANGER, nothing.stage);

        Tie tie = new Tie(1, 2, 60, 5, 0, 0);
        assertArrayEquals(friend.axes, tie.axes, 0, "bots and players share one derivation");
        assertEquals(Stage.FRIEND, tie.stage);
    }

    // ── stages ──

    @Test
    void stagesAreEnteredHighAndLeftLow() {
        // acquaintance -> friend needs warmth 0.30, a friend stays at 0.25
        double[] warm25 = v(0.5, 0.2, 0.35, 0.1, 0, 0, 0, 0); // warmth .6*.35+.3*.2+.1*.1 = .28
        assertEquals(0.28, Axes.warmth(warm25), 1e-9);
        assertEquals(Stage.ACQUAINTANCE, Stage.step(Stage.ACQUAINTANCE, warm25, true), "0.28 does not make a friend");
        assertEquals(Stage.FRIEND, Stage.step(Stage.FRIEND, warm25, true), "but a friend at 0.28 stays one");
        double[] warm35 = v(0.5, 0.3, 0.45, 0.1, 0, 0, 0, 0);
        assertEquals(Stage.FRIEND, Stage.step(Stage.ACQUAINTANCE, warm35, true));
        double[] warm15 = v(0.5, 0.1, 0.2, 0.05, 0, 0, 0, 0); // .155
        assertEquals(Stage.ACQUAINTANCE, Stage.step(Stage.FRIEND, warm15, true), "under 0.18 the friendship ends");

        // close friend: 0.62 in, 0.45 out
        double[] close = v(0.8, 0.7, 0.8, 0.5, 0, 0, 0, 0);
        assertEquals(Stage.CLOSE_FRIEND, Stage.step(Stage.FRIEND, close, true));
        double[] fifty = v(0.8, 0.5, 0.55, 0.3, 0, 0, 0, 0); // warmth .51
        assertEquals(Stage.FRIEND, Stage.step(Stage.FRIEND, fifty, true), "0.51 is not enough to enter");
        assertEquals(Stage.CLOSE_FRIEND, Stage.step(Stage.CLOSE_FRIEND, fifty, true), "but enough to stay");

        // romantic: attraction 0.45 in, 0.25 out
        double[] spark = v(0.6, 0.4, 0.5, 0.2, 0.5, 0, 0, 0);
        assertEquals(Stage.ROMANTIC, Stage.step(Stage.FRIEND, spark, true));
        double[] cooled = v(0.6, 0.4, 0.5, 0.2, 0.3, 0, 0, 0);
        assertEquals(Stage.ROMANTIC, Stage.step(Stage.ROMANTIC, cooled, true));
        assertEquals(Stage.FRIEND, Stage.step(Stage.FRIEND, cooled, true), "0.3 attraction does not start a romance");
        double[] over = v(0.6, 0.4, 0.5, 0.2, 0.2, 0, 0, 0);
        assertEquals(Stage.FRIEND, Stage.step(Stage.ROMANTIC, over, true));

        // rival: in at rivalry 0.35, out under 0.18
        assertEquals(Stage.RIVAL, Stage.step(Stage.ACQUAINTANCE, v(0.3, 0, -0.1, 0, 0, 0.36, 0.1, 0), true));
        assertEquals(Stage.RIVAL, Stage.step(Stage.RIVAL, v(0.3, 0, -0.1, 0, 0, 0.25, 0.1, 0), true));
        assertEquals(Stage.ACQUAINTANCE, Stage.step(Stage.RIVAL, v(0.3, 0, -0.05, 0, 0, 0.1, 0.1, 0), true));
        assertEquals(Stage.FRIEND, Stage.step(Stage.FRIEND, v(0.5, 0.3, 0.5, 0.1, 0, 0.4, 0.1, 0), true),
                "a friendly rival is still a friend");
    }

    @Test
    void estrangedIsABrokenFriendshipNotAFadedOne() {
        // was a friend, now resentment is high and affection fell
        double[] soured = v(0.7, 0.1, 0.1, 0, 0, 0.2, 0.4, 0);
        assertEquals(Stage.ESTRANGED, Stage.step(Stage.FRIEND, soured, true));
        assertEquals(Stage.ESTRANGED, Stage.step(Stage.CLOSE_FRIEND, soured, true));
        assertEquals(Stage.ESTRANGED, Stage.step(Stage.ROMANTIC, soured, true));
        // affection gone negative alone is enough
        assertEquals(Stage.ESTRANGED, Stage.step(Stage.FRIEND, v(0.7, 0, -0.1, 0, 0, 0, 0.05, 0), true));
        // a stranger or an acquaintance with the same numbers is a rival, never estranged
        assertEquals(Stage.RIVAL, Stage.step(Stage.ACQUAINTANCE, soured, true));
        // a friendship that merely faded to nothing drops to acquaintance
        assertEquals(Stage.ACQUAINTANCE, Stage.step(Stage.FRIEND, v(0.3, 0.01, 0.02, 0, 0, 0, 0, 0), true));
        // a friend whose warmth collapses with a grudge behind it did not "fade": it broke
        assertEquals(Stage.ESTRANGED, Stage.step(Stage.FRIEND, v(0.5, 0.05, 0.1, 0, 0, 0, 0.2, 0), true));
        assertEquals(Stage.ESTRANGED, Stage.step(Stage.ROMANTIC, v(0.5, 0.0, 0.1, 0, 0.2, 0, 0.2, 0), true));
        // estranged is sticky until the resentment cools AND affection is no longer negative
        assertEquals(Stage.ESTRANGED, Stage.step(Stage.ESTRANGED, v(0.5, 0, 0.02, 0, 0, 0, 0.2, 0), true));
        assertEquals(Stage.ESTRANGED, Stage.step(Stage.ESTRANGED, v(0.5, 0, -0.1, 0, 0, 0, 0.05, 0), true));
        assertEquals(Stage.ACQUAINTANCE, Stage.step(Stage.ESTRANGED, v(0.5, 0, 0.0, 0, 0, 0, 0.05, 0), true));
    }

    @Test
    void aStageMovesOneRungAtATimeAndTimeNeverRaisesIt() {
        double[] glowing = v(0.9, 0.8, 0.9, 0.6, 0, 0, 0, 0);
        assertEquals(Stage.ACQUAINTANCE, Stage.step(Stage.STRANGER, glowing, true), "no jump to close friend");
        assertEquals(Stage.FRIEND, Stage.step(Stage.ACQUAINTANCE, glowing, true));
        assertEquals(Stage.CLOSE_FRIEND, Stage.step(Stage.FRIEND, glowing, true));
        assertEquals(Stage.STRANGER, Stage.step(Stage.STRANGER, glowing, false), "time alone makes no friends");
        assertEquals(Stage.ACQUAINTANCE, Stage.step(Stage.ACQUAINTANCE, glowing, false));
        assertEquals(Stage.CLOSE_FRIEND, Stage.settle(Stage.STRANGER, glowing, true), "settle walks the rungs (legacy rows)");
        assertEquals(Stage.ACQUAINTANCE, Stage.settle(Stage.CLOSE_FRIEND, v(0.5, 0, 0.05, 0, 0, 0, 0, 0), false),
                "decay walks down: close friend, friend, acquaintance");
        assertEquals(Stage.STRANGER, Stage.ofCode(99));
        for (Stage s : Stage.values()) {
            assertEquals(s, Stage.ofCode(s.code()));
        }
    }

    @Test
    void aBondWalksTheLadderThroughRealEventsAndBreaksIntoEstrangement() {
        Bond b = new Bond(1, 2, 0, 0, 0, 0);
        long t = 0;
        Stage last = Stage.STRANGER;
        java.util.List<Stage> seen = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            t += 31 * MIN;
            BondRules.apply(b, BondEvent.PARTY, 6, t, false);
            if (b.stage != last) {
                seen.add(b.stage);
                last = b.stage;
            }
        }
        assertEquals(java.util.List.of(Stage.ACQUAINTANCE, Stage.FRIEND), seen, "gradual: the rungs in order");
        double scoreBefore = b.score;
        for (int i = 0; i < 7; i++) {
            t += 6 * MIN;
            BondRules.apply(b, BondEvent.KS, -6, t, false);
        }
        assertEquals(Stage.ESTRANGED, b.stage, "a friendship broken by repeated KS");
        assertTrue(Math.abs(b.score) < scoreBefore / 4, "the score barely shows it: " + b.score);
        assertTrue(b.axes[Axis.REN] > 0.5);
    }

    @Test
    void aFriendshipThatFadedAwayIsNotEstranged() {
        Bond b = new Bond(1, 2, 0, 0, 0, 0);
        long t = 0;
        for (int i = 0; i < 8; i++) {
            t += 31 * MIN;
            BondRules.apply(b, BondEvent.PARTY, 6, t, false);
        }
        assertEquals(Stage.FRIEND, b.stage);
        assertEquals(Stage.FRIEND, BondRules.stageAt(b, t + 10 * DAY), "ten days is nothing");
        Stage after = BondRules.stageAt(b, t + 400 * DAY);
        assertEquals(Stage.ACQUAINTANCE, after, "faded, not broken");
        assertNotNull(after);
    }

    // ── significance ──

    @Test
    void significanceRanksBrokenRivalAndCloseAboveFadedNeutral() {
        double[] faded = v(0.1, 0.01, 0.03, 0, 0, 0, 0, 0);
        double neutral = Axes.significance(9, faded, Stage.ACQUAINTANCE);
        double estranged = Axes.significance(5, v(0.6, 0, 0.1, 0, 0, 0.3, 0.55, 0), Stage.ESTRANGED);
        double rival = Axes.significance(-28, v(0.3, -0.1, -0.2, 0, 0, 0.3, 0.45, 0), Stage.RIVAL);
        double close = Axes.significance(80, v(0.9, 0.8, 0.9, 0.5, 0, 0, 0, 0), Stage.CLOSE_FRIEND);
        assertTrue(estranged > neutral && rival > neutral && close > neutral);
        assertTrue(Axes.significance(0, v(0.5, 0, 0, 0, 0.8, 0, 0, 0), Stage.ROMANTIC) > neutral);
    }
}
