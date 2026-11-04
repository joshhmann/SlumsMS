package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch.Inputs;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch.Mode;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three simulated days of a 300-bot population under the real {@link ModeSwitch#decide} policy, the real per-bot
 * stay, and need dynamics that follow the shape of the brain's own needs (company need grows while grinding and is
 * met among people; fatigue grows with the session; boredom with time in a mode; goals hold a bot at its work).
 * It is a policy simulation with synthetic needs, not a run of the live brain; what it shows is that the policy
 * moves bots in both directions without thrashing, keeps its caps, and lets personality decide who spends time where.
 */
class ModeSwitchSimulationTest {

    private static final int BOTS = 300;
    private static final int MINUTES = 3 * 24 * 60;
    private static final long MIN_MS = 60_000L;

    @AfterEach
    void restore() {
        SocialSim.reset();
    }

    private static final class Sim {
        final Mode[] mode = new Mode[BOTS];
        final long[] since = new long[BOTS];
        final long[][] stamps = new long[BOTS][];
        final int[] stampCount = new int[BOTS];
        final double[] social = new double[BOTS], fatigue = new double[BOTS], boredom = new double[BOTS];
        final double[] progress = new double[BOTS], grinder = new double[BOTS], socialite = new double[BOTS],
                explorer = new double[BOTS], pull = new double[BOTS];
        final long[] minutesIn = new long[BOTS * 3];
        final List<long[]> switchLog = new ArrayList<>(); // bot, minute, from, to
        int toLight, toTraining, lightToLight;
        long gateClosed;
        long minGapMs = Long.MAX_VALUE;
        int maxPerHour;
    }

    private static Sim run(long seed) {
        AtomicLong clock = new AtomicLong(0);
        SocialSim.useForTest(clock::get, seed);
        Sim s = new Sim();
        Random setup = new Random(seed ^ 0xABCDEFL);
        for (int b = 0; b < BOTS; b++) {
            // Personalities: a third grinders, a third socializers, the rest explorers, with goals on about half.
            int kind = b % 3;
            s.grinder[b] = kind == 0 ? 0.55 : 0.1;
            s.socialite[b] = kind == 1 ? 0.55 : 0.1;
            s.explorer[b] = kind == 2 ? 0.55 : 0.1;
            s.pull[b] = kind == 1 ? 0.6 : kind == 0 ? 0.0 : 0.25;
            s.progress[b] = setup.nextDouble() < 0.5 ? 0.0 : 0.3 + 0.65 * setup.nextDouble();
            s.mode[b] = Mode.TRAINING; // a fresh session starts as training (BotRoster.resumeChoice)
            s.since[b] = 0;
            s.stamps[b] = new long[ModeSwitch.MAX_PER_HOUR];
            s.social[b] = setup.nextDouble() * 0.3;
        }
        for (int minute = 1; minute <= MINUTES; minute++) {
            clock.set(minute * MIN_MS);
            long now = clock.get();
            for (int b = 0; b < BOTS; b++) {
                step(s, b, now);
                s.minutesIn[b * 3 + s.mode[b].ordinal()]++;
                if (now - s.since[b] < 0) throw new AssertionError("time went backwards");
                Random rng = SocialSim.random(1_000_000_000L + b, "sim");
                int lastHour = 0;
                for (long t : s.stamps[b]) if (t != 0 && now - t < ModeSwitch.HOUR_MS) lastHour++;
                s.maxPerHour = Math.max(s.maxPerHour, lastHour);
                Inputs in = new Inputs(s.mode[b], now - s.since[b], ModeSwitch.dwellFor(1_000_000_000 + b), lastHour,
                        s.social[b], s.progress[b], s.fatigue[b], s.boredom[b], s.grinder[b], s.socialite[b],
                        s.explorer[b], s.pull[b]);
                Mode target = ModeSwitch.decide(in, rng.nextDouble(), rng.nextDouble());
                if (target == null) continue;
                if (rng.nextDouble() < 0.4) { // the gate: a trade, a chat, a trip, a field map holds the bot
                    s.gateClosed++;
                    continue;
                }
                long gap = now - s.since[b];
                s.minGapMs = Math.min(s.minGapMs, gap);
                s.switchLog.add(new long[]{b, minute, s.mode[b].ordinal(), target.ordinal()});
                if (s.mode[b] == Mode.TRAINING) s.toLight++;
                else if (target == Mode.TRAINING) s.toTraining++;
                else s.lightToLight++;
                // keep the last MAX_PER_HOUR switch times, as the executor's ring does
                System.arraycopy(s.stamps[b], 1, s.stamps[b], 0, s.stamps[b].length - 1);
                s.stamps[b][s.stamps[b].length - 1] = now;
                s.mode[b] = target;
                s.since[b] = now;
                s.boredom[b] = 0;
                if (target != Mode.TRAINING) s.fatigue[b] = Math.max(0, s.fatigue[b] - 0.5);
            }
        }
        return s;
    }

    /** One minute of need dynamics. */
    private static void step(Sim s, int b, long now) {
        s.boredom[b] = Math.min(1, s.boredom[b] + 1.0 / 45);
        switch (s.mode[b]) {
            case TRAINING -> {
                s.social[b] = Math.min(1, s.social[b] + 1.0 / 40);
                s.fatigue[b] = Math.min(1, s.fatigue[b] + 1.0 / 120);
            }
            case SOCIAL -> {
                s.social[b] = Math.max(0.02, s.social[b] - 0.06);
                s.fatigue[b] = Math.max(0, s.fatigue[b] - 0.01);
            }
            case TOWN -> {
                s.social[b] = Math.max(0.15, s.social[b] - 0.03);
                s.fatigue[b] = Math.max(0, s.fatigue[b] - 0.01);
            }
        }
    }

    @Test
    void threeDaysMoveBotsBothWaysWithoutThrashing() {
        Sim s = run(2026);
        int total = s.switchLog.size();
        double perBotDay = total / (double) BOTS / 3.0;
        System.out.printf("mode-switch simulation: %d bots, 3 days: %d switches (%.1f per bot-day), "
                        + "training>light %d, light>training %d, light>light %d, gate closed %d, min stay %.1f min, "
                        + "max in one hour %d%n",
                BOTS, total, perBotDay, s.toLight, s.toTraining, s.lightToLight, s.gateClosed,
                s.minGapMs / 60_000.0, s.maxPerHour);
        assertTrue(s.toLight > 0 && s.toTraining > 0, "both directions must occur");
        assertTrue(perBotDay >= 1.0 && perBotDay <= 12.0, "a few switches per bot-day, not none and not thrash: " + perBotDay);
        assertTrue(s.maxPerHour <= ModeSwitch.MAX_PER_HOUR, "hourly cap: " + s.maxPerHour);
        assertTrue(s.minGapMs >= (long) (ModeSwitch.MIN_DWELL_MS * 0.75), "minimum stay: " + s.minGapMs);
    }

    @Test
    void personalityDecidesWhoSpendsTimeWhere() {
        Sim s = run(2026);
        double[][] share = new double[3][3]; // kind -> mode -> minutes
        for (int b = 0; b < BOTS; b++) {
            for (int m = 0; m < 3; m++) share[b % 3][m] += s.minutesIn[b * 3 + m];
        }
        double[] training = new double[3];
        for (int k = 0; k < 3; k++) {
            double all = share[k][0] + share[k][1] + share[k][2];
            training[k] = share[k][Mode.TRAINING.ordinal()] / all;
        }
        System.out.printf("share of time in training: grinders %.2f, socializers %.2f, explorers %.2f%n",
                training[0], training[1], training[2]);
        assertTrue(training[0] > training[1] + 0.15, "grinders train more than socializers: " + training[0] + " vs " + training[1]);
        assertTrue(training[0] > training[2], "grinders train more than explorers");
        assertTrue(training[1] < 0.9, "socializers do spend time out of training");
    }

    @Test
    void sameSeedSameHistoryDifferentSeedDifferentHistory() {
        Sim a = run(7), b = run(7), c = run(8);
        assertEquals(a.switchLog.size(), b.switchLog.size());
        for (int i = 0; i < a.switchLog.size(); i++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(a.switchLog.get(i), b.switchLog.get(i));
        }
        assertTrue(a.switchLog.size() != c.switchLog.size() || !java.util.Arrays.equals(a.minutesIn, c.minutesIn),
                "a different seed must give a different history");
    }
}
