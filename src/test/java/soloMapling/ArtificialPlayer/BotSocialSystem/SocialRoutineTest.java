package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch.Mode;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialContext.Self;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;
import soloMapling.ArtificialPlayer.BotStore;

import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 7: the routine is a bias, never a script. New code, so the fail-before is that none of it existed;
 * what is pinned: the shape of a day, the bounds of every bias, needs overriding the schedule, the neutral overload of
 * {@code ModeSwitch.decide}, similar minds picking the same place, a group's event pulling its members, and the switch.
 */
class SocialRoutineTest {

    static final long DAY = 86_400_000L;
    static final long T0 = 100 * DAY; // midnight UTC
    static final int[] MAPS = {100000000, 100000100, 100000200, 100000202};

    @BeforeEach
    void setUp() {
        System.clearProperty(SocialRoutine.SWITCH);
        System.clearProperty(SocialPilot.SWITCH);
        SocialRoutine.resetForTest();
        SocialRoutine.useZoneForTest(ZoneOffset.UTC);
        SocialPilot.resetForTest();
        SocialSim.useForTest(() -> T0, 3);
        LifeMemoryStore.useConnectionSourceForTest(null);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SocialRoutine.SWITCH);
        System.clearProperty(SocialPilot.SWITCH);
        SocialRoutine.resetForTest();
        SocialPilot.resetForTest();
        SocialSim.reset();
        LifeMemoryStore.useConnectionSourceForTest(null);
    }

    static Self mind(double soc, double amb, double cur, double conf, double agr) {
        Map<Trait, Double> t = new EnumMap<>(Trait.class);
        for (Trait x : Trait.values()) t.put(x, 0.5);
        t.put(Trait.SOCIABILITY, soc);
        t.put(Trait.AMBITION, amb);
        t.put(Trait.CURIOSITY, cur);
        t.put(Trait.CONFIDENCE, conf);
        t.put(Trait.AGREEABLENESS, agr);
        return Self.flat(0.5, 0.2).with(t, Map.of(), Map.of());
    }

    static SocialRoutine.Profile profile(int id, Self self) {
        return SocialRoutine.Profile.of(id, self, 4);
    }

    // ── the shape of a day ──

    @Test
    void aBumpPeaksAtItsCentreFallsToZeroAndWrapsPastMidnight() {
        assertEquals(1.0, SocialRoutine.bump(12, 12, 3), 1e-9);
        assertEquals(0.0, SocialRoutine.bump(15, 12, 3), 1e-9);
        assertEquals(0.0, SocialRoutine.bump(9, 12, 3), 1e-9);
        assertEquals(0.5, SocialRoutine.bump(13.5, 12, 3), 1e-9);
        assertEquals(1.0, SocialRoutine.bump(23.5, 23.5, 4), 1e-9);
        assertTrue(SocialRoutine.bump(0.5, 23.5, 4) > 0.5, "an hour after 23:30 is near the peak across midnight");
        assertEquals(SocialRoutine.bump(1.0, 23.0, 4), SocialRoutine.bump(23.0, 1.0, 4), 1e-9, "symmetric");
    }

    @Test
    void everyWeightOfEveryHourIsBetweenZeroAndOne() {
        for (double soc = 0; soc <= 1; soc += 0.25) {
            for (double amb = 0; amb <= 1; amb += 0.25) {
                SocialRoutine.Profile p = profile(7, mind(soc, amb, 0.5, 0.5, 0.5));
                for (double h = 0; h < 24; h += 0.5) {
                    SocialRoutine.Day d = SocialRoutine.at(p, h);
                    for (double v : new double[]{d.work(), d.gather(), d.evening(), d.rest(), d.company()}) {
                        assertTrue(v >= 0 && v <= 1, "weight " + v + " at " + h);
                    }
                }
            }
        }
    }

    @Test
    void anAmbitiousBotWorksLongerAndASociableOneGathersMore() {
        SocialRoutine.Profile driven = profile(7, mind(0.3, 0.95, 0.5, 0.5, 0.5)), idle = profile(7, mind(0.3, 0.05, 0.5, 0.5, 0.5));
        double drivenWork = 0, idleWork = 0;
        for (double h = 0; h < 24; h += 0.5) {
            drivenWork += SocialRoutine.at(driven, h).work();
            idleWork += SocialRoutine.at(idle, h).work();
        }
        assertTrue(drivenWork > idleWork * 1.3, "work hours " + drivenWork + " vs " + idleWork);
        SocialRoutine.Profile social = profile(7, mind(0.95, 0.5, 0.5, 0.5, 0.5)), loner = profile(7, mind(0.05, 0.5, 0.5, 0.5, 0.5));
        double socialCompany = 0, lonerCompany = 0;
        for (double h = 0; h < 24; h += 0.5) {
            socialCompany += SocialRoutine.at(social, h).company();
            lonerCompany += SocialRoutine.at(loner, h).company();
        }
        assertTrue(socialCompany > lonerCompany * 1.3, "company hours " + socialCompany + " vs " + lonerCompany);
    }

    @Test
    void differentBotsHaveTheirOwnPhase() {
        Self same = mind(0.5, 0.5, 0.5, 0.5, 0.5);
        double lo = 99, hi = -99;
        for (int id = 1; id <= 60; id++) {
            double shift = profile(id, same).shift();
            lo = Math.min(lo, shift);
            hi = Math.max(hi, shift);
            assertTrue(Math.abs(shift) <= 2.25 + 1e-9);
        }
        assertTrue(hi - lo > 2.5, "chronotypes spread over hours: " + lo + ".." + hi);
    }

    @Test
    void needsOverrideTheHour() {
        SocialRoutine.Profile p = profile(7, mind(0.5, 0.9, 0.5, 0.5, 0.5));
        double noon = 11.0 + p.shift(); // the middle of its work window
        assertEquals(SocialRoutine.Kind.WORK, SocialRoutine.doing(p, mind(0.5, 0.9, 0.5, 0.5, 0.5).with(Map.of(), Map.of(), Map.of(Need.REST, 0.1)), noon).kind());
        Self tired = mind(0.5, 0.9, 0.5, 0.5, 0.5).with(Map.of(), Map.of(), Map.of(Need.REST, 0.95));
        assertEquals(SocialRoutine.Kind.REST, SocialRoutine.doing(p, tired, noon).kind());
        Self lonely = mind(0.5, 0.9, 0.5, 0.5, 0.5).with(Map.of(), Map.of(Emotion.LONELINESS, 0.8), Map.of());
        assertEquals(SocialRoutine.Kind.GATHER, SocialRoutine.doing(p, lonely, noon).kind());
    }

    // ── the bias into the mode decision ──

    @Test
    void everyBiasIsFiniteAndInsideItsBand() {
        for (double soc = 0; soc <= 1; soc += 0.25) {
            for (double amb = 0; amb <= 1; amb += 0.25) {
                for (double need = 0; need <= 1; need += 0.5) {
                    Self self = mind(soc, amb, 0.5, 0.5, 0.5).with(Map.of(), Map.of(Emotion.LONELINESS, need), Map.of(Need.COMPANIONSHIP, need, Need.REST, 1 - need));
                    SocialRoutine.Profile p = profile(3, self);
                    for (double h = 0; h < 24; h += 0.25) {
                        SocialRoutine.ModeBias b = SocialRoutine.modeBias(p, self, h);
                        assertTrue(b.towardTown() >= SocialRoutine.BIAS_MIN && b.towardTown() <= SocialRoutine.BIAS_MAX, "town " + b.towardTown());
                        assertTrue(b.towardTraining() >= SocialRoutine.BIAS_MIN && b.towardTraining() <= SocialRoutine.BIAS_MAX, "train " + b.towardTraining());
                    }
                }
            }
        }
        assertEquals(1.0, SocialRoutine.clampBias(Double.NaN), "a broken number is neutral");
        assertEquals(SocialRoutine.BIAS_MAX, new SocialRoutine.ModeBias(99, 99).towardTown());
        assertEquals(SocialRoutine.BIAS_MIN, new SocialRoutine.ModeBias(-5, 0).towardTraining());
    }

    @Test
    void theHoursPullTheWayTheirNamesSay() {
        Self calm = mind(0.8, 0.8, 0.5, 0.5, 0.5);
        SocialRoutine.Profile p = profile(7, calm);
        SocialRoutine.ModeBias atWork = SocialRoutine.modeBias(p, calm, 11.0 + p.shift());
        SocialRoutine.ModeBias atEvening = SocialRoutine.modeBias(p, calm, 19.5 + p.shift());
        assertTrue(atWork.towardTraining() > 1 && atWork.towardTown() < 1, "in its work window it leans to training: " + atWork);
        assertTrue(atEvening.towardTown() > 1 && atEvening.towardTraining() < 1, "in the evening it leans to town: " + atEvening);
    }

    @Test
    void aLonelyOrWornOutBotIsHeldLessToItsSchedule() {
        Self calm = mind(0.8, 0.8, 0.5, 0.5, 0.5);
        Self lonely = calm.with(Map.of(), Map.of(Emotion.LONELINESS, 0.5), Map.of(Need.COMPANIONSHIP, 0.95));
        SocialRoutine.Profile p = profile(7, calm);
        double h = 11.0 + p.shift();
        double calmPull = Math.abs(SocialRoutine.modeBias(p, calm, h).towardTraining() - 1);
        double lonelyPull = Math.abs(SocialRoutine.modeBias(p, lonely, h).towardTraining() - 1);
        assertTrue(lonelyPull < calmPull * 0.6, "the schedule's hold loosens: " + lonelyPull + " vs " + calmPull);
    }

    private static ModeSwitch.Inputs inputs(Mode current, double social, double progress, double fatigue, double boredom) {
        return new ModeSwitch.Inputs(current, 3_600_000L, 1_200_000L, 0, social, progress, fatigue, boredom, 0.3, 0.6, 0.2, 0);
    }

    @Test
    void theNeutralOverloadIsTheOldPolicyExactly() {
        Random r = new Random(11);
        for (int i = 0; i < 2000; i++) {
            ModeSwitch.Inputs in = new ModeSwitch.Inputs(Mode.values()[r.nextInt(3)], r.nextInt(4_000_000), 1_200_000L, r.nextInt(4),
                    r.nextDouble(), r.nextDouble(), r.nextDouble(), r.nextDouble(), r.nextDouble(), r.nextDouble(), r.nextDouble(),
                    r.nextDouble() * 0.5);
            double a = r.nextDouble(), b = r.nextDouble();
            assertEquals(ModeSwitch.decide(in, a, b), ModeSwitch.decide(in, a, b, SocialRoutine.ModeBias.NONE));
        }
    }

    @Test
    void aBiasMovesADecisionNearTheThresholdAndOnlyThere() {
        // a training bot whose pull just misses the threshold: social 0.6 -> pull 0.33 + 0.05 + 0.06 = 0.44, weight 1.1 -> 0.48,
        // minus 0.3 x grinder 0.3 = 0.39
        ModeSwitch.Inputs near = inputs(Mode.TRAINING, 0.6, 0, 0.2, 0.3);
        assertNull(ModeSwitch.decide(near, 0.0, 0.5), "as is, it stays");
        assertNotEquals(null, ModeSwitch.decide(near, 0.0, 0.5, new SocialRoutine.ModeBias(1.6, 1)), "the evening bias tips it to town");
        assertNull(ModeSwitch.decide(near, 0.0, 0.5, new SocialRoutine.ModeBias(0.5, 1)), "the work bias holds it");
        // far from the threshold nothing changes
        ModeSwitch.Inputs far = inputs(Mode.TRAINING, 0.05, 0.9, 0.0, 0.0);
        assertNull(ModeSwitch.decide(far, 0.0, 0.5, new SocialRoutine.ModeBias(1.8, 1)), "a bot with a goal is not pulled by the clock");
        ModeSwitch.Inputs hungry = inputs(Mode.TRAINING, 1.0, 0.0, 0.8, 0.8);
        assertNotEquals(null, ModeSwitch.decide(hungry, 0.0, 0.5, new SocialRoutine.ModeBias(0.8, 1)), "a lonely, tired bot goes in spite of a work-hour bias");
        // and back: a town bot with some pull to its goal
        ModeSwitch.Inputs back = inputs(Mode.TOWN, 0.1, 0.6, 0.1, 0.4);
        Mode unbiased = ModeSwitch.decide(back, 0.0, 0.9);
        assertNotEquals(null, ModeSwitch.decide(back, 0.0, 0.9, new SocialRoutine.ModeBias(1, 1.8)));
        assertTrue(unbiased == null || unbiased == Mode.TRAINING);
        assertNull(ModeSwitch.decide(back, 0.0, 0.9, new SocialRoutine.ModeBias(1, 0.5)), "the evening keeps it in town");
    }

    // ── the place ──

    @Test
    void thePickIsAPureFunctionStableForHalfAnHour() {
        Self self = mind(0.7, 0.4, 0.6, 0.5, 0.5);
        SocialRoutine.Profile p = profile(7, self);
        long t = T0 + 19 * 3_600_000L + 60_000L;
        SocialRoutine.Spot a = SocialRoutine.spotFor(7, p, self, t, MAPS, null);
        assertEquals(a, SocialRoutine.spotFor(7, p, self, t, MAPS, null), "same inputs, same answer");
        // inside one 30-minute bucket: same
        long bucketStart = t / SocialRoutine.BUCKET_MS * SocialRoutine.BUCKET_MS;
        for (long off = 0; off < SocialRoutine.BUCKET_MS; off += 5 * 60_000L) {
            assertEquals(a.index(), SocialRoutine.spotFor(7, p, self, bucketStart + off, MAPS, null).index());
        }
        assertTrue(a.mapId() == MAPS[a.index()]);
    }

    private static int[] histogram(Self self, int ids, long from, SocialRoutine.GroupPlan plan) {
        int[] count = new int[MAPS.length];
        for (int id = 1; id <= ids; id++) {
            SocialRoutine.Profile p = profile(id, self);
            for (int b = 0; b < 8; b++) {
                count[SocialRoutine.spotFor(id, p, self, from + b * SocialRoutine.BUCKET_MS, MAPS, plan).index()]++;
            }
        }
        return count;
    }

    @Test
    void botsWithSimilarMindsPickTheSamePlaceAndDifferentMindsDont() {
        Self crowd = mind(0.95, 0.5, 0.1, 0.5, 0.3), curious = mind(0.15, 0.2, 0.95, 0.5, 0.7);
        long evening = T0 + 19 * 3_600_000L;
        int[] crowdPicks = histogram(crowd, 150, evening, null), curiousPicks = histogram(curious, 150, evening, null);
        int crowdTop = argmax(crowdPicks), curiousTop = argmax(curiousPicks);
        assertNotEquals(crowdTop, curiousTop, "different tastes, different favourite place: " + java.util.Arrays.toString(crowdPicks)
                + " vs " + java.util.Arrays.toString(curiousPicks));
        assertTrue(crowdPicks[crowdTop] > 150 * 8 * 0.35, "similar minds agree: " + java.util.Arrays.toString(crowdPicks));
        // the same thing seen as the chance two bots of one kind stand in the same place in the same half hour
        double same = 0, cross = 0;
        int n = 0;
        for (int id = 1; id <= 80; id++) {
            for (int b = 0; b < 4; b++) {
                long t = evening + b * SocialRoutine.BUCKET_MS;
                int x = SocialRoutine.spotFor(id, profile(id, crowd), crowd, t, MAPS, null).index();
                int y = SocialRoutine.spotFor(id + 1000, profile(id + 1000, crowd), crowd, t, MAPS, null).index();
                int z = SocialRoutine.spotFor(id + 2000, profile(id + 2000, curious), curious, t, MAPS, null).index();
                if (x == y) same++;
                if (x == z) cross++;
                n++;
            }
        }
        assertTrue(same / n > cross / n + 0.15, "meeting chance for like minds " + same / n + " vs unlike " + cross / n);
    }

    private static int argmax(int[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    @Test
    void aGroupsEventPullsItsMembersToItsPlaceAtItsHourAndOnlyBiasesAfterwards() {
        Self self = mind(0.5, 0.5, 0.5, 0.5, 0.5);
        int target = 3;
        SocialRoutine.GroupPlan plan = new SocialRoutine.GroupPlan(target, 19.5);
        long atEvent = T0 + 19 * 3_600_000L + 30 * 60_000L, atNoon = T0 + 12 * 3_600_000L;
        int[] without = histogram(self, 150, atEvent, null), with = histogram(self, 150, atEvent, plan);
        int[] noonWith = histogram(self, 150, atNoon, plan), noonWithout = histogram(self, 150, atNoon, null);
        assertTrue(with[target] > without[target] * 1.5, "at the event hour the group place gains: " + with[target] + " vs " + without[target]);
        assertTrue(with[target] - without[target] > noonWith[target] - noonWithout[target],
                "more at the event hour than at noon: " + (with[target] - without[target]) + " vs " + (noonWith[target] - noonWithout[target]));
        assertTrue(with[target] < 150 * 8, "a bias, not a command: not everyone goes");
        assertTrue(java.util.Arrays.stream(with).filter(c -> c > 0).count() >= 3, "the others still spread");
    }

    @Test
    void aStarvedBotLeansToItsGroupAndTheSquare() {
        Self fed = mind(0.5, 0.5, 0.5, 0.5, 0.5).with(Map.of(), Map.of(), Map.of(Need.COMPANIONSHIP, 0.1));
        Self starved = mind(0.5, 0.5, 0.5, 0.5, 0.5).with(Map.of(), Map.of(Emotion.LONELINESS, 0.6), Map.of(Need.COMPANIONSHIP, 0.95));
        SocialRoutine.GroupPlan plan = new SocialRoutine.GroupPlan(2, 19.5);
        long t = T0 + 15 * 3_600_000L;
        assertTrue(histogram(starved, 150, t, plan)[2] > histogram(fed, 150, t, plan)[2], "a lonely bot goes where its friends gather");
    }

    // ── the real readers ──

    @Test
    void theProductionReadersUseTheMindAndAreNeutralForNonMembers() {
        int id = BotStore.BOT_ID_BASE + 42;
        LifeMemoryStore.of(id);
        long now = T0 + 19 * 3_600_000L;
        assertEquals(SocialRoutine.ModeBias.NONE, SocialRoutine.modeBias(id, now), "not in the pilot");
        assertEquals(0, SocialRoutine.preferredMap(id, now));
        assertEquals(0, SocialPilot.destination(id, now));
        SocialPilot.setMembersForTest(List.of(id));
        SocialRoutine.ModeBias b = SocialRoutine.modeBias(id, now);
        assertNotEquals(SocialRoutine.ModeBias.NONE, b, "a pilot bot at some hours is biased");
        int map = SocialRoutine.preferredMap(id, now);
        assertTrue(SocialPilot.isPilotMap(map), "a Henesys gathering map: " + map);
        assertEquals(map, SocialPilot.destination(id, now));
        long bucketStart = now - now % SocialRoutine.BUCKET_MS;
        assertEquals(SocialRoutine.preferredMap(id, bucketStart), SocialRoutine.preferredMap(id, bucketStart + SocialRoutine.BUCKET_MS - 1),
                "stable within the half hour");
        assertTrue(SocialRoutine.townPull(id, now) >= 0 && SocialRoutine.townPull(id, now) <= 1);
        // a bot whose memory is not loaded gets nothing (memory only: never a table read)
        SocialPilot.setMembersForTest(List.of(id, BotStore.BOT_ID_BASE + 43));
        assertEquals(SocialRoutine.ModeBias.NONE, SocialRoutine.modeBias(BotStore.BOT_ID_BASE + 43, now));
        assertEquals(0, SocialRoutine.preferredMap(BotStore.BOT_ID_BASE + 43, now));
    }

    @Test
    void theSwitchesTurnItOff() {
        int id = BotStore.BOT_ID_BASE + 42;
        LifeMemoryStore.of(id);
        SocialPilot.setMembersForTest(List.of(id));
        long now = T0 + 19 * 3_600_000L;
        assertNotEquals(SocialRoutine.ModeBias.NONE, SocialRoutine.modeBias(id, now));
        System.setProperty(SocialRoutine.SWITCH, "false");
        assertEquals(SocialRoutine.ModeBias.NONE, SocialRoutine.modeBias(id, now));
        assertEquals(0, SocialRoutine.preferredMap(id, now));
        assertEquals(0.0, SocialRoutine.townPull(id, now));
        System.clearProperty(SocialRoutine.SWITCH);
        System.setProperty(SocialPilot.SWITCH, "false");
        assertEquals(SocialRoutine.ModeBias.NONE, SocialRoutine.modeBias(id, now));
        assertEquals(0, SocialPilot.destination(id, now));
    }

    @Test
    void theHourIsReadInTheConfiguredZone() {
        assertEquals(0.0, SocialRoutine.hourOf(T0), 1e-9);
        assertEquals(13.5, SocialRoutine.hourOf(T0 + 13 * 3_600_000L + 30 * 60_000L), 1e-9);
        SocialRoutine.useZoneForTest(java.time.ZoneId.of("Asia/Tokyo"));
        assertEquals(9.0, SocialRoutine.hourOf(T0), 1e-9);
    }
}
