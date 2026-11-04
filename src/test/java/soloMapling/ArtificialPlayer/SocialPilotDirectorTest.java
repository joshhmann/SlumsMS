package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialPilot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 271 stage 7: the population director's side of the pilot. A pilot bot inside its own play window is built as
 * the director's candidates are (a zero session length, no leave-now permission); the pure {@code plan} then never ends
 * its session. New code, so the fail-before is that nothing held a bot; the rest of the director is unchanged.
 */
class SocialPilotDirectorTest {

    static final long NOW = TimeUnit.DAYS.toMillis(200);
    static final int NOON = 12 * 60;

    @BeforeEach
    void setUp() {
        System.clearProperty(SocialPilot.SWITCH);
        System.setProperty(SocialPilot.SIZE, "5");
        SocialPilot.maybeStep(1_000L, () -> slots(40));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SocialPilot.SWITCH);
        System.clearProperty(SocialPilot.SIZE);
        System.setProperty(SocialPilot.SWITCH, "false"); // switched off, the pilot forgets its members and its roster clock
        SocialPilot.maybeStep(2L, () -> List.of());
        System.clearProperty(SocialPilot.SWITCH);
    }

    private static List<Integer> slots(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(i);
        return out;
    }

    /** A candidate the way {@code BotPopulationDirector.candidates()} builds it, window 09:00 for 10 hours, session 60 minutes. */
    private static BotPopulationDirector.Candidate candidate(int slot, boolean online, int minuteOfDay) {
        int windowStart = 9 * 60, windowLen = 10 * 60, session = 60;
        boolean hold = online && SocialPilot.holds(BotStore.BOT_ID_BASE + slot, windowStart, windowLen, minuteOfDay);
        return new BotPopulationDirector.Candidate(slot, online, true, NOW - TimeUnit.HOURS.toMillis(8), 0, windowStart, windowLen,
                hold ? 0 : session, !hold && online);
    }

    private static Set<Integer> pilotSlots() {
        Set<Integer> out = new HashSet<>();
        for (int id : SocialPilot.members()) out.add(id - BotStore.BOT_ID_BASE);
        return out;
    }

    @Test
    void aPilotBotInsideItsWindowIsNeitherSessionEndedNorTrimmed() {
        Set<Integer> pilot = pilotSlots();
        assertTrue(pilot.size() == 5, "five pilot bots");
        List<BotPopulationDirector.Candidate> roster = new ArrayList<>();
        for (int slot = 1; slot <= 40; slot++) {
            roster.add(candidate(slot, true, NOON));
        }
        // every session is eight hours old (over its hour), and the curve wants far fewer bots than are online
        BotPopulationDirector.Plan plan = BotPopulationDirector.plan(roster, Map.of(), 10, NOON, NOW, Integer.MAX_VALUE, 0, 100, new Random(1));
        for (int slot : pilot) {
            assertFalse(plan.logouts().contains(slot), "pilot bot " + slot + " stays online");
        }
        assertTrue(plan.logouts().size() >= 30, "everybody else goes: " + plan.logouts().size());
    }

    @Test
    void theSameBotsAreOrdinaryOnceTheirWindowIsOver() {
        Set<Integer> pilot = pilotSlots();
        int after = 20 * 60; // 20:00: the window (09:00 + 10 h) ended at 19:00
        List<BotPopulationDirector.Candidate> roster = new ArrayList<>();
        for (int slot = 1; slot <= 40; slot++) {
            roster.add(candidate(slot, true, after));
        }
        BotPopulationDirector.Plan plan = BotPopulationDirector.plan(roster, Map.of(), 10, after, NOW, Integer.MAX_VALUE, 0, 100, new Random(1));
        for (int slot : pilot) {
            assertTrue(plan.logouts().contains(slot), "pilot bot " + slot + " leaves like any bot once its window is over");
        }
    }

    @Test
    void withThePilotOffNobodyIsHeld() {
        System.setProperty(SocialPilot.SWITCH, "false");
        Set<Integer> pilot = new HashSet<>();
        for (int slot = 1; slot <= 40; slot++) {
            assertFalse(SocialPilot.holds(BotStore.BOT_ID_BASE + slot, 9 * 60, 10 * 60, NOON));
            pilot.add(slot);
        }
        List<BotPopulationDirector.Candidate> roster = new ArrayList<>();
        for (int slot = 1; slot <= 40; slot++) {
            roster.add(candidate(slot, true, NOON));
        }
        BotPopulationDirector.Plan plan = BotPopulationDirector.plan(roster, Map.of(), 10, NOON, NOW, Integer.MAX_VALUE, 0, 100, new Random(1));
        assertTrue(plan.logouts().containsAll(pilot), "every session over its length ends");
    }

    @Test
    void aPilotBotIsLoggedInAheadOfTheOthers() {
        Set<Integer> pilot = pilotSlots();
        int pilotPicks = 0, otherPicks = 0;
        for (int seed = 0; seed < 800; seed++) {
            List<BotPopulationDirector.Candidate> roster = new ArrayList<>();
            for (int slot = 1; slot <= 40; slot++) {
                roster.add(candidate(slot, false, NOON));
            }
            BotPopulationDirector.Plan plan = BotPopulationDirector.plan(roster, Map.of(), 20, NOON, NOW, 1, 0, 100, new Random(seed));
            for (int slot : plan.logins()) {
                if (pilot.contains(slot)) pilotPicks++;
                else otherPicks++;
            }
        }
        // 5 of 40 are pilot bots: 12.5% by chance, about 30% with a login weight of 3
        double share = pilotPicks / (double) (pilotPicks + otherPicks);
        assertTrue(share > 0.22, "the pilot is logged in first more often than by chance: " + share);
        assertTrue(share < 0.6, "but not always, the others still log in: " + share);
    }
}
