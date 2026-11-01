package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 163: 1.6.3's TEST boot logged 3,000 roster bots in at 8 a second, and the population went to 4,562 bots
 * under a 3,500 {@code BOT_POPULATION_PEAK}. The boot fill plans the whole 3,000 in one tick; the pace then takes
 * six minutes to carry it, and every tick in between planned the shortfall again because the planner counts only
 * bots that are already in the world: {@code online + on the way} had no upper bound.
 */
class BotPopulationOvershootTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    /** 1.6.3's TEST: 20 s ticks, a fill pace of 8 a second, so one tick carries 160 logins. */
    private static final int TICK_SECONDS = 20;
    private static final int PER_SECOND = 8;
    private static final int BUDGET = PER_SECOND * TICK_SECONDS;
    /** The environment at boot on TEST that day: waves 1-7, 322 bots (none of them roster members). */
    private static final int ENVIRONMENT = 322;
    private static final int POPULATION_PEAK = 3500;
    private static final int ROSTER_PEAK = 3000;
    /** TEST's roster that day: "[BotRoster] Bot roster holds 9089 members", 10:53:15. */
    private static final int ROSTER_SIZE = 9089;
    private static final long NOW =
            ZonedDateTime.of(2026, 11, 1, 10, 53, 0, 0, UTC).toInstant().toEpochMilli();

    /**
     * The boot fill as it happened: a roster whose logged-out members all want in, a pace of 8 a second, and the
     * director's ticks planning into the queue.
     */
    private static final class Fill {
        final boolean[] online;
        final boolean[] onTheWay;
        final long[] loginAt;
        final int target;
        int peakOnline;
        int peakTotal;

        Fill(int rosterSize, int target) {
            online = new boolean[rosterSize];
            onTheWay = new boolean[rosterSize];
            loginAt = new long[rosterSize];
            this.target = target;
        }

        int onlineCount() {
            int n = 0;
            for (boolean b : online) {
                n += b ? 1 : 0;
            }
            return n;
        }

        int onTheWayCount() {
            int n = 0;
            for (boolean b : onTheWay) {
                n += b ? 1 : 0;
            }
            return n;
        }

        List<BotPopulationDirector.Candidate> candidates() {
            List<BotPopulationDirector.Candidate> list = new ArrayList<>();
            for (int i = 0; i < online.length; i++) {
                list.add(new BotPopulationDirector.Candidate(i, online[i], true, loginAt[i], 0, 0, 1440, 240, true));
            }
            return list;
        }

        /**
         * One tick: plan, queue through {@link BotPopulationDirector.LoginPace}'s dedupe, then 20 s of the pace.
         *
         * @param withLoginsOnTheWay true for the tick as the fixed director plans it; false for 1.6.3's, which
         *                           counted only bots in the world
         */
        void tick(int maxLogins, boolean withLoginsOnTheWay, Random rng) {
            int pending = withLoginsOnTheWay ? onTheWayCount() : 0;
            BotPopulationDirector.Plan plan =
                    BotPopulationDirector.plan(candidates(), Map.of(), target, 600, NOW, maxLogins,
                            pending, 25, rng);
            for (int botId : plan.logouts()) {
                online[botId] = false; // BotLogout.request: the bot leaves within the tick
            }
            for (int botId : plan.logins()) {
                if (!onTheWay[botId]) {
                    onTheWay[botId] = true; // LoginPace.offer: a bot already queued is not queued twice
                }
            }
            for (int started = Math.min(onTheWayCount(), BUDGET); started > 0; started--) {
                for (int i = 0; i < onTheWay.length; i++) {
                    if (onTheWay[i]) {
                        onTheWay[i] = false;
                        online[i] = true;
                        loginAt[i] = NOW;
                        break;
                    }
                }
            }
            int up = onlineCount();
            peakOnline = Math.max(peakOnline, up);
            peakTotal = Math.max(peakTotal, up + ENVIRONMENT);
        }
    }

    @Test
    void theBootFillNeverPutsMoreBotsOnlineThanThePopulationCapAllows() {
        assertEquals(ROSTER_PEAK, BotChannels.directorPeak(ROSTER_PEAK, POPULATION_PEAK, ENVIRONMENT),
                "the director's peak leaves the environment its room");
        Fill fill = new Fill(ROSTER_SIZE, ROSTER_PEAK);
        Random rng = new Random(163);
        fill.tick(Integer.MAX_VALUE, true, rng); // the boot fill: the whole target in one plan
        for (int t = 0; t < 40; t++) {
            fill.tick(BUDGET, true, rng);
        }
        assertEquals(ROSTER_PEAK, fill.onlineCount(), "the fill lands on the target and stays there");
        assertEquals(ROSTER_PEAK, fill.peakOnline, "and never passes it on the way");
        assertTrue(fill.peakTotal <= POPULATION_PEAK,
                "total bots online " + fill.peakTotal + " over the population cap " + POPULATION_PEAK);
    }

    /**
     * The 1.6.3 behaviour, kept as the number behind the report: without the "on the way" term every tick of a slow
     * fill plans the shortfall it can still see and queues bots the pace has not carried yet - a random pick of 160 a
     * tick, of which most are new because the roster is 9,089 and only 3,000 are wanted. The queue never empties, so
     * the world passes the cap: 4,685 roster bots online and 5,007 with the environment, against 3,500. TEST's own
     * boot peaked at 4,562 (the director had begun logging out by then).
     */
    @Test
    void planningFromBotsOnlineAloneOverrunsThePopulationCap() {
        Fill fill = new Fill(ROSTER_SIZE, ROSTER_PEAK);
        Random rng = new Random(163);
        fill.tick(Integer.MAX_VALUE, false, rng);
        for (int t = 0; t < 40; t++) {
            fill.tick(BUDGET, false, rng);
        }
        assertTrue(fill.peakTotal > POPULATION_PEAK,
                "1.6.3 planned past the cap: peak online " + fill.peakOnline + " + environment " + ENVIRONMENT
                        + " against a cap of " + POPULATION_PEAK);
    }

    @Test
    void theRoomUnderTheTargetIsTheShortfallLessTheLoginsOnTheirWay() {
        assertEquals(0, BotPopulationDirector.roomUnderTarget(ROSTER_PEAK, 0, 3000),
                "a queued boot fill leaves no room");
        assertEquals(0, BotPopulationDirector.roomUnderTarget(ROSTER_PEAK, 2880, 120),
                "3,000 on the way plus 2,880 in the world is the target already");
        assertEquals(120, BotPopulationDirector.roomUnderTarget(ROSTER_PEAK, 2880, 0));
        assertEquals(0, BotPopulationDirector.roomUnderTarget(ROSTER_PEAK, 2880, 200), "never negative");
        assertEquals(0, BotPopulationDirector.roomUnderTarget(ROSTER_PEAK, 3100, 0), "already past the target");
        assertEquals(1000, BotPopulationDirector.roomUnderTarget(ROSTER_PEAK, 1950, 50));
    }

    @Test
    void thePopulationCapCountsBotsOnTheirWayAndZeroMeansNoCap() {
        assertTrue(BotPopulationDirector.withinPopulationCap(ENVIRONMENT, 3000, POPULATION_PEAK),
                "322 environment + 3,000 on the way is 3,322, under the 3,500 cap");
        assertFalse(BotPopulationDirector.withinPopulationCap(ENVIRONMENT, 3179, POPULATION_PEAK));
        assertTrue(BotPopulationDirector.withinPopulationCap(ENVIRONMENT, 3178, POPULATION_PEAK),
                "the cap is inclusive: 3,500 bots is allowed, 3,501 is not");
        assertTrue(BotPopulationDirector.withinPopulationCap(9000, 0, 0), "0 = no cap");
        assertTrue(BotPopulationDirector.withinPopulationCap(9000, 0, -1), "negative = no cap");
    }
}
