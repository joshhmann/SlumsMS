package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePolicy;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 7: the pilot's membership, hold, placement and counters. New code, so the fail-before is that none of it
 * existed. The director's use of the hold is in {@code soloMapling.ArtificialPlayer.SocialPilotDirectorTest}.
 */
class SocialPilotTest {

    static final int BASE = BotStore.BOT_ID_BASE;

    @BeforeEach
    void setUp() {
        System.clearProperty(SocialPilot.SWITCH);
        System.clearProperty(SocialPilot.SIZE);
        System.clearProperty(SocialPilot.MAP);
        SocialPilot.resetForTest();
        SocialSim.useForTest(() -> 1_000_000_000_000L, 5);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SocialPilot.SWITCH);
        System.clearProperty(SocialPilot.SIZE);
        System.clearProperty(SocialPilot.MAP);
        SocialPilot.resetForTest();
        SocialSim.reset();
    }

    private static List<Integer> slots(int from, int to) {
        List<Integer> out = new ArrayList<>();
        for (int i = from; i <= to; i++) out.add(i);
        return out;
    }

    // ── who ──

    @Test
    void theSelectionIsTheLowestHashesAndTheSameEveryTime() {
        List<Integer> a = SocialPilot.select(slots(1, 300), 20, List.of());
        List<Integer> b = SocialPilot.select(slots(1, 300), 20, List.of());
        assertEquals(20, a.size());
        assertEquals(a, b, "deterministic");
        List<Integer> byRank = new ArrayList<>(slots(1, 300));
        byRank.sort((x, y) -> Long.compare(SocialPilot.rank(x), SocialPilot.rank(y)));
        assertEquals(byRank.subList(0, 20), a, "exactly the 20 lowest ranks, sorted by rank");
        assertEquals(20, new HashSet<>(a).size());
    }

    @Test
    void theOrderOfTheRosterDoesNotMatter() {
        List<Integer> shuffled = new ArrayList<>(slots(1, 300));
        java.util.Collections.shuffle(shuffled, new java.util.Random(9));
        assertEquals(SocialPilot.select(slots(1, 300), 20, List.of()), SocialPilot.select(shuffled, 20, List.of()));
    }

    @Test
    void aMemberStaysWhileActiveAndNewBotsNeverDisplaceIt() {
        List<Integer> first = SocialPilot.select(slots(1, 100), 20, List.of());
        // 300 bots enrolled later, some with a lower rank than a member: the pilot keeps its people
        List<Integer> later = SocialPilot.select(slots(1, 400), 20, first);
        assertEquals(new HashSet<>(first), new HashSet<>(later));
        // but a fresh boot (nothing remembered) on the bigger roster picks by rank again, deterministically
        List<Integer> fresh = SocialPilot.select(slots(1, 400), 20, List.of());
        assertEquals(20, fresh.size());
    }

    @Test
    void aMemberWhoLeavesIsReplacedByTheNextLowestRankAndNobodyElseMoves() {
        List<Integer> first = SocialPilot.select(slots(1, 200), 20, List.of());
        int gone = first.get(7);
        List<Integer> active = new ArrayList<>(slots(1, 200));
        active.remove((Integer) gone);
        List<Integer> next = SocialPilot.select(active, 20, first);
        Set<Integer> kept = new HashSet<>(first);
        kept.remove(gone);
        assertTrue(next.containsAll(kept), "the other 19 stay");
        assertFalse(next.contains(gone));
        assertEquals(20, next.size());
        List<Integer> byRank = new ArrayList<>(active);
        byRank.removeAll(first);
        byRank.sort((x, y) -> Long.compare(SocialPilot.rank(x), SocialPilot.rank(y)));
        Set<Integer> added = new HashSet<>(next);
        added.removeAll(kept);
        assertEquals(Set.of(byRank.get(0)), added, "the replacement is the lowest rank among the rest");
    }

    @Test
    void aShrunkSizeKeepsTheLowestRanksAndASmallRosterTakesEveryone() {
        List<Integer> twenty = SocialPilot.select(slots(1, 200), 20, List.of());
        List<Integer> five = SocialPilot.select(slots(1, 200), 5, twenty);
        assertEquals(twenty.subList(0, 5), five);
        assertEquals(Set.of(3, 4, 5), new HashSet<>(SocialPilot.select(List.of(3, 4, 5), 20, List.of())));
        assertTrue(SocialPilot.select(slots(1, 200), 0, twenty).isEmpty());
    }

    @Test
    void theRosterTickFillsTheMembersAndOnlyReadsTheRosterWhenDue() {
        System.setProperty(SocialPilot.SIZE, "6");
        int[] reads = {0};
        SocialPilot.maybeStep(1_000L, () -> {
            reads[0]++;
            return slots(1, 50);
        });
        assertEquals(6, SocialPilot.members().size());
        for (int id : SocialPilot.members()) {
            assertTrue(id > BASE && id <= BASE + 50, "character ids");
        }
        Set<Integer> before = Set.copyOf(SocialPilot.members());
        SocialPilot.maybeStep(2_000L, () -> {
            reads[0]++;
            return slots(1, 50);
        });
        assertEquals(1, reads[0], "the roster is read at most every ten minutes");
        SocialPilot.maybeStep(1_000L + SocialPilot.REFRESH_MS, () -> {
            reads[0]++;
            return slots(1, 50);
        });
        assertEquals(2, reads[0]);
        assertEquals(before, SocialPilot.members(), "the same roster gives the same members");
    }

    // ── the hold ──

    @Test
    void aPilotBotIsHeldInsideItsWindowAndOnlyThere() {
        SocialPilot.setMembersForTest(List.of(BASE + 5));
        // window 09:00 for 10 hours
        assertTrue(SocialPilot.holds(BASE + 5, 9 * 60, 10 * 60, 9 * 60));
        assertTrue(SocialPilot.holds(BASE + 5, 9 * 60, 10 * 60, 18 * 60 + 59));
        assertFalse(SocialPilot.holds(BASE + 5, 9 * 60, 10 * 60, 19 * 60), "the window ended");
        assertFalse(SocialPilot.holds(BASE + 5, 9 * 60, 10 * 60, 8 * 60 + 59));
        // a window across midnight: 20:00 for 8 hours
        assertTrue(SocialPilot.holds(BASE + 5, 20 * 60, 8 * 60, 2 * 60));
        assertFalse(SocialPilot.holds(BASE + 5, 20 * 60, 8 * 60, 5 * 60));
        assertFalse(SocialPilot.holds(BASE + 5, 9 * 60, 0, 10 * 60), "no window, no hold");
        assertFalse(SocialPilot.holds(BASE + 6, 9 * 60, 10 * 60, 10 * 60), "not a member");
    }

    @Test
    void theLoginBoostIsForMembersOnly() {
        SocialPilot.setMembersForTest(List.of(BASE + 5));
        assertEquals(SocialPilot.LOGIN_BOOST, SocialPilot.loginBoost(5));
        assertEquals(1.0, SocialPilot.loginBoost(6));
    }

    // ── placement ──

    @Test
    void theGatheringMapsAreHenesysFamilyByDefaultAndFollowTheConfiguredMap() {
        assertArrayEquals(new int[]{100000000, 100000100, 100000200, 100000202}, SocialPilot.maps());
        assertTrue(SocialPilot.isPilotMap(100000100));
        assertFalse(SocialPilot.isPilotMap(103000000));
        System.setProperty(SocialPilot.MAP, "103000000"); // Kerning City: one map
        assertArrayEquals(new int[]{103000000}, SocialPilot.maps());
        System.setProperty(SocialPilot.MAP, "not a number");
        assertEquals(100000000, SocialPilot.homeMap(), "a broken value is the default");
        System.setProperty(SocialPilot.MAP, "424242"); // not a town: just that map
        assertArrayEquals(new int[]{424242}, SocialPilot.maps());
    }

    private static void assertArrayEquals(int[] a, int[] b) {
        assertEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(b));
    }

    @Test
    void theHeadroomIsThePilotSizeAndSizeFollowsTheProperty() {
        assertEquals(20, SocialPilot.size());
        assertEquals(20, SocialPilot.headroom());
        System.setProperty(SocialPilot.SIZE, "7");
        assertEquals(7, SocialPilot.size());
        System.setProperty(SocialPilot.SIZE, "-4");
        assertEquals(0, SocialPilot.size());
        System.setProperty(SocialPilot.SIZE, "900");
        assertEquals(200, SocialPilot.size());
    }

    @Test
    void theHangOutBiasIsForPilotBotsAndStaysUnderTheCap() {
        SocialPilot.setMembersForTest(List.of(BASE + 5));
        LifePolicy.Situation member = new LifePolicy.Situation(null, false, false, 0, false, null, 0, BASE + 5);
        LifePolicy.Situation other = new LifePolicy.Situation(null, false, false, 0, false, null, 0, BASE + 6);
        LifePolicy.Context ctx = null;
        // a null context is a decision with nothing to bias
        assertEquals(0, SocialPilot.LIFE_BIAS.bonus(LifeDecision.Activity.HANG_OUT, ctx, member));
        assertEquals(0, SocialPilot.LIFE_BIAS.bonus(LifeDecision.Activity.HANG_OUT, null, null));
        assertTrue(SocialPilot.HANG_OUT_PULL <= LifePolicy.MAX_PLUGIN_BIAS);
        assertEquals(0, SocialPilot.LIFE_BIAS.bonus(LifeDecision.Activity.GRIND, ctx, other));
    }

    // ── counters ──

    @Test
    void theCountersCountPilotBotsOnlyAndStatsHasTheDocumentedKeys() {
        SocialPilot.setMembersForTest(List.of(BASE + 5, BASE + 6));
        SocialPilot.useProbeForTest(new SocialPilot.Probe() {
            public boolean online(int id) {
                return id == BASE + 5;
            }

            public String mode(int id) {
                return "social";
            }
        });
        SocialPilot.onAct(BASE + 5, SocialActKind.GIVE_GIFT, true);
        SocialPilot.onAct(BASE + 5, SocialActKind.AVOID, true);
        SocialPilot.onAct(BASE + 6, SocialActKind.ARGUE, true);
        SocialPilot.onAct(BASE + 6, SocialActKind.APOLOGIZE, false); // carried out, did not land
        SocialPilot.onAct(BASE + 99, SocialActKind.GREET, true);      // not a pilot bot
        SocialPilot.onMemory(BASE + 5);
        SocialPilot.onMemory(BASE + 99);
        SocialPilot.onIntent(BASE + 5, true);
        SocialPilot.onIntent(BASE + 6, false);
        SocialPilot.onIntent(BASE + 99, true);
        Map<String, Long> s = SocialPilot.stats();
        assertEquals(2, s.get("members"));
        assertEquals(1, s.get("online"));
        assertEquals(1, s.get("mode.social"));
        assertEquals(0, s.get("mode.training"));
        assertEquals(3, s.get("acts.landed"));
        assertEquals(4, s.get("acts.carriedOut"));
        assertEquals(1, s.get("gifts"));
        assertEquals(1, s.get("avoids"));
        assertEquals(1, s.get("conflicts.arguments"));
        assertEquals(1, s.get("memories"));
        assertEquals(1, s.get("intents.kept"));
        assertEquals(1, s.get("intents.broken"));
        assertEquals(0, s.get("acts.GREET"));
        for (String key : List.of("online", "held", "friendships.edges", "friendships.formed", "friendships.mutualPairs", "conflicts.rivalEdges",
                "restartsSurvived", "placements", "routineBiases", "groups", "groupedBots", "acts.ACCEPT")) {
            assertTrue(s.containsKey(key), key);
        }
    }

    // ── off ──

    @Test
    void withThePilotOffThereAreNoMembersNoHoldNoPlacementAndNoCounting() {
        SocialPilot.setMembersForTest(List.of(BASE + 5));
        System.setProperty(SocialPilot.SWITCH, "false");
        assertFalse(SocialPilot.enabled());
        assertTrue(SocialPilot.members().isEmpty());
        assertFalse(SocialPilot.isPilot(BASE + 5));
        assertFalse(SocialPilot.holds(BASE + 5, 0, 1440, 100));
        assertEquals(1.0, SocialPilot.loginBoost(5));
        assertEquals(0, SocialPilot.destination(BASE + 5, 1L));
        SocialPilot.onAct(BASE + 5, SocialActKind.GREET, true);
        SocialPilot.onMemory(BASE + 5);
        SocialPilot.onIntent(BASE + 5, true);
        Map<String, Long> s = SocialPilot.stats();
        assertEquals(0, s.get("members"));
        assertEquals(0, s.get("acts.landed"));
        assertEquals(0, s.get("memories"));
        assertEquals(0, s.get("intents.kept"));
        SocialPilot.maybeStep(5L, () -> slots(1, 30));
        assertTrue(SocialPilot.members().isEmpty(), "the roster tick selects nobody");
        assertNotEquals(null, SocialPilot.LIFE_BIAS);
    }
}
