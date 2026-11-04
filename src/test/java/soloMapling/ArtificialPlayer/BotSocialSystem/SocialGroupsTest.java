package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axis;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.Reputation;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 7: friend groups. New code, so the fail-before is that none of it existed. Components of the mutual
 * friend graph, the minimum size, the mutual requirement, the recompute throttle, what a group adds to decisions and
 * that it is bounded, the conflict between groups, the reputation read, and the switch.
 */
class SocialGroupsTest extends SocialActTestBase {

    static final int B = BotStore.BOT_ID_BASE;

    /** A fake bond graph: directed friend edges, directed hostility, one profile for everyone. */
    static final class Graph implements SocialGroups.Source {
        final List<Integer> universe = new ArrayList<>();
        final Map<Integer, Set<Integer>> friends = new HashMap<>();
        final Map<Long, double[]> hostile = new HashMap<>(); // directed {resentment, rivalry}
        SocialRoutine.Profile profile;

        Graph(int... ids) {
            for (int id : ids) universe.add(B + id);
        }

        Graph mutual(int a, int b) {
            friends.computeIfAbsent(B + a, k -> new java.util.HashSet<>()).add(B + b);
            friends.computeIfAbsent(B + b, k -> new java.util.HashSet<>()).add(B + a);
            return this;
        }

        Graph oneWay(int a, int b) {
            friends.computeIfAbsent(B + a, k -> new java.util.HashSet<>()).add(B + b);
            return this;
        }

        Graph grudge(int from, int to, double resentment, double rivalry) {
            hostile.put(((long) (B + from) << 32) | ((B + to) & 0xffffffffL), new double[]{resentment, rivalry});
            return this;
        }

        @Override
        public Collection<Integer> universe() {
            return universe;
        }

        @Override
        public Collection<Integer> friendsOf(int bot) {
            return friends.getOrDefault(bot, Set.of());
        }

        @Override
        public Axes edge(int from, int to) {
            double[] v = new double[Axis.COUNT];
            double[] h = hostile.get(((long) from << 32) | (to & 0xffffffffL));
            if (h != null) {
                v[Axis.REN] = h[0];
                v[Axis.RIV] = h[1];
            }
            return Axes.of(v);
        }

        @Override
        public SocialRoutine.Profile profile(int bot, long now) {
            return profile;
        }
    }

    static Set<Integer> idsOf(int[] members) {
        Set<Integer> out = new java.util.TreeSet<>();
        for (int m : members) out.add(m - B);
        return out;
    }

    @BeforeEach
    void groupsSetUp() {
        System.clearProperty(SocialGroups.SWITCH);
        System.clearProperty(SocialPilot.SWITCH);
        SocialGroups.useForTest(null);
        SocialRoutine.resetForTest();
        SocialRoutine.useZoneForTest(ZoneOffset.UTC);
        SocialPilot.resetForTest();
    }

    @AfterEach
    void groupsTearDown() {
        System.clearProperty(SocialGroups.SWITCH);
        System.clearProperty(SocialPilot.SWITCH);
        SocialGroups.useForTest(null);
        SocialRoutine.resetForTest();
        SocialPilot.resetForTest();
        Reputation.useForTest(null, null, null);
    }

    // ── detection ──

    @Test
    void aTriangleOfMutualFriendsIsAGroupAndAPairIsNot() {
        Graph g = new Graph(1, 2, 3, 4, 5).mutual(1, 2).mutual(2, 3).mutual(1, 3).mutual(4, 5);
        List<int[]> groups = SocialGroups.components(g.universe(), g::friendsOf);
        assertEquals(1, groups.size(), "the pair 4-5 is under the minimum size of " + SocialGroups.MIN_SIZE);
        assertEquals(Set.of(1, 2, 3), idsOf(groups.get(0)));
    }

    @Test
    void oneSidedFriendshipsAreNoGroup() {
        // 1 counts 2, 2 counts 3, 3 counts 1: nobody counts them back
        Graph g = new Graph(1, 2, 3).oneWay(1, 2).oneWay(2, 3).oneWay(3, 1);
        assertTrue(SocialGroups.components(g.universe(), g::friendsOf).isEmpty());
        // one more edge back makes 1-2 mutual: still only a pair
        g.oneWay(2, 1);
        assertTrue(SocialGroups.components(g.universe(), g::friendsOf).isEmpty());
        // 2-3 mutual as well: now 1-2-3 is a chain of mutual friends, a group
        g.oneWay(3, 2);
        assertEquals(Set.of(1, 2, 3), idsOf(SocialGroups.components(g.universe(), g::friendsOf).get(0)));
    }

    @Test
    void aChainIsOneGroupAndSeparateClustersAreSeparate() {
        Graph chain = new Graph(1, 2, 3, 4, 5, 6, 7, 8).mutual(1, 2).mutual(2, 3).mutual(3, 4).mutual(5, 6).mutual(6, 7).mutual(5, 7);
        List<int[]> groups = SocialGroups.components(chain.universe(), chain::friendsOf);
        assertEquals(2, groups.size());
        assertEquals(Set.of(1, 2, 3, 4), idsOf(groups.get(0)), "sorted by smallest member");
        assertEquals(Set.of(5, 6, 7), idsOf(groups.get(1)));
    }

    @Test
    void friendsOutsideTheUniverseAreIgnoredAndTheOrderOfTheUniverseDoesNotMatter() {
        Graph g = new Graph(1, 2, 3).mutual(1, 2).mutual(2, 3).mutual(1, 3).mutual(3, 99).mutual(2, 99);
        List<int[]> a = SocialGroups.components(g.universe(), g::friendsOf);
        List<Integer> reversed = new ArrayList<>(g.universe());
        java.util.Collections.reverse(reversed);
        List<int[]> b = SocialGroups.components(reversed, g::friendsOf);
        assertEquals(1, a.size());
        assertEquals(idsOf(a.get(0)), idsOf(b.get(0)));
        assertEquals(Set.of(1, 2, 3), idsOf(a.get(0)), "bot 99 is not part of this pilot");
    }

    @Test
    void theGroupGetsItsPlaceByVoteAndItsHourFromItsMembers() {
        Graph g = new Graph(1, 2, 3).mutual(1, 2).mutual(2, 3).mutual(1, 3);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.9, 0.3, 0.1, 0.5, 0.3), 4);
        SocialGroups.Snapshot s = SocialGroups.compute(T0, g);
        assertEquals(1, s.groups().size());
        SocialGroups.Group group = s.groups().get(0);
        assertEquals(B + 1, group.id());
        assertEquals(SocialRoutine.eveningHour(g.profile), group.eventHour(), 1e-6, "identical members: their own hour");
        int expected = SocialGroups.favourite(g.profile, group.eventHour(), 4);
        assertEquals(expected, group.spot());
        assertTrue(group.has(B + 2) && !group.has(B + 9));
        assertEquals(3, s.byBot().size());
        // the circular mean crosses midnight correctly
        assertEquals(0.0, SocialGroups.circularMeanHour(new double[]{23, 1}) % 24, 1e-6);
        assertEquals(12.0, SocialGroups.circularMeanHour(new double[]{11, 13}), 1e-6);
    }

    // ── the throttle ──

    @Test
    void theSnapshotIsRecomputedAtMostEveryFewMinutes() {
        Graph g = new Graph(1, 2, 3).mutual(1, 2).mutual(2, 3).mutual(1, 3);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        SocialGroups.useForTest(g);
        long t = T0;
        SocialGroups.current(t);
        assertEquals(1, SocialGroups.recomputeCount());
        for (int i = 0; i < 50; i++) {
            SocialGroups.groupOf(B + 1, t + i * 3_000L); // 50 decisions in 150 seconds
            SocialGroups.adjust(SocialActKind.SPEND_TIME, B + 1, B + 2, t + i * 3_000L);
        }
        assertEquals(1, SocialGroups.recomputeCount(), "one recompute for all of them");
        g.friends.clear(); // the friendships end...
        assertNotNull(SocialGroups.groupOf(B + 1, t + SocialGroups.RECOMPUTE_MS - 1), "...but the group stands until the next recompute");
        assertNull(SocialGroups.groupOf(B + 1, t + SocialGroups.RECOMPUTE_MS), "the next recompute sees it");
        assertEquals(2, SocialGroups.recomputeCount());
        SocialGroups.current(t); // a clock that went backwards recomputes
        assertEquals(3, SocialGroups.recomputeCount());
    }

    // ── what a group adds to a decision ──

    @Test
    void groupMatesAreWorthMoreToSpendTimeWithAndToInviteButNotToArgueWith() {
        Graph g = new Graph(1, 2, 3, 4).mutual(1, 2).mutual(2, 3).mutual(1, 3);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        SocialGroups.useForTest(g);
        long now = now();
        assertEquals(SocialGroups.MATE_BONUS, SocialGroups.adjust(SocialActKind.SPEND_TIME, B + 1, B + 2, now), 1e-12);
        assertEquals(SocialGroups.MATE_BONUS, SocialGroups.adjust(SocialActKind.INVITE, B + 1, B + 3, now), 1e-12);
        assertEquals(0, SocialGroups.adjust(SocialActKind.SPEND_TIME, B + 1, B + 4, now), "a bot in no group");
        assertEquals(0, SocialGroups.adjust(SocialActKind.ARGUE, B + 1, B + 2, now), "mates get no conflict bonus");
        assertEquals(0, SocialGroups.adjust(SocialActKind.CONVERSE, B + 1, B + 2, now), "only the two acts the brief names");
        assertEquals(0, SocialGroups.adjust(SocialActKind.SPEND_TIME, B + 4, B + 1, now));
    }

    @Test
    void theRankingReallyReadsIt() {
        world.put(B + 1, "A", 100, 0);
        world.put(B + 2, "B", 140, 0);
        mem(B + 1);
        mem(B + 2);
        SocialTestKit.befriend(B + 1, B + 2, false, now());
        SocialTestKit.befriend(B + 2, B + 1, false, now());
        SocialContext c = SocialActions.build(B + 1, B + 2, now());
        double plain = SocialActions.rank(c, new Random(1)).stream().filter(s -> s.kind() == SocialActKind.SPEND_TIME).findFirst().orElseThrow().utility();
        Graph g = new Graph(1, 2, 3).mutual(1, 2).mutual(2, 3).mutual(1, 3);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        SocialGroups.useForTest(g);
        double grouped = SocialActions.rank(c, new Random(1)).stream().filter(s -> s.kind() == SocialActKind.SPEND_TIME).findFirst().orElseThrow().utility();
        assertEquals(SocialGroups.MATE_BONUS, grouped - plain, 1e-9);
        System.setProperty(SocialGroups.SWITCH, "false");
        SocialGroups.resetForTest();
        double off = SocialActions.rank(c, new Random(1)).stream().filter(s -> s.kind() == SocialActKind.SPEND_TIME).findFirst().orElseThrow().utility();
        assertEquals(plain, off, 1e-12, "with the switch off the ranking is the old one");
    }

    @Test
    void theCandidatesListGroupMatesFirstAndKeepsTheOrderOtherwise() {
        Graph g = new Graph(1, 2, 3).mutual(1, 2).mutual(2, 3).mutual(1, 3);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        SocialGroups.useForTest(g);
        List<Integer> nearest = new ArrayList<>(List.of(B + 7, B + 3, B + 8, B + 2, B + 9));
        SocialGroups.mateFirst(B + 1, nearest, now());
        assertEquals(List.of(B + 3, B + 2, B + 7, B + 8, B + 9), nearest, "mates in their order, then the rest in theirs");
        List<Integer> alone = new ArrayList<>(List.of(B + 7, B + 3));
        SocialGroups.mateFirst(B + 7, alone, now()); // 7 is in no group
        assertEquals(List.of(B + 7, B + 3), alone);
    }

    @Test
    void theDecisionTickSeesGroupMatesFirstOnlyWhileGroupsAreOn() {
        // bot 1 stands at 0; bot 4 is nearest (not a mate), 2 and 3 (mates) farther
        world.put(B + 1, "A", 0, 0);
        world.put(B + 4, "D", 100, 0);
        world.put(B + 2, "B", 300, 0);
        world.put(B + 3, "C", 500, 0);
        for (int id : new int[]{1, 2, 3, 4}) mem(B + id);
        for (int other : new int[]{2, 3, 4}) SocialTestKit.meet(B + 1, B + other, now());
        Graph g = new Graph(1, 2, 3, 4).mutual(1, 2).mutual(2, 3).mutual(1, 3);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        SocialGroups.useForTest(g);
        assertEquals(List.of(B + 2, B + 3, B + 4), SocialDecider.candidates(B + 1, world, now()), "mates first, nearest first within each");
        System.setProperty(SocialGroups.SWITCH, "false");
        assertEquals(List.of(B + 4, B + 2, B + 3), SocialDecider.candidates(B + 1, world, now()), "as before: nearest first");
    }

    // ── conflict between groups ──

    @Test
    void twoGroupsInConflictRaiseArgueAndAvoidBetweenTheirMembersAndNeverPastTheBound() {
        Graph g = new Graph(1, 2, 3, 4, 5, 6).mutual(1, 2).mutual(2, 3).mutual(1, 3).mutual(4, 5).mutual(5, 6).mutual(4, 6);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        for (int a : new int[]{1, 2, 3}) {
            for (int b : new int[]{4, 5, 6}) {
                g.grudge(a, b, 0.5, 0.2).grudge(b, a, 0.4, 0.3);
            }
        }
        SocialGroups.useForTest(g);
        long now = now();
        double tension = SocialGroups.tensionBetween(B + 1, B + 5, now);
        assertTrue(tension > 0.3, "the groups are at odds: " + tension);
        double argue = SocialGroups.adjust(SocialActKind.ARGUE, B + 1, B + 5, now), avoid = SocialGroups.adjust(SocialActKind.AVOID, B + 6, B + 2, now);
        assertTrue(argue > 0 && avoid > 0, "both acts move toward the other group's members");
        assertTrue(argue <= SocialGroups.MAX_CONFLICT_BONUS && avoid <= SocialGroups.MAX_CONFLICT_BONUS, "bounded: " + argue);
        assertEquals(0, SocialGroups.adjust(SocialActKind.ARGUE, B + 1, B + 2, now), "inside one's own group nothing");
        assertEquals(0, SocialGroups.adjust(SocialActKind.GREET, B + 1, B + 5, now), "only ARGUE and AVOID");
        assertEquals(tension, SocialGroups.tensionBetween(B + 5, B + 1, now), 1e-12, "symmetric");
    }

    @Test
    void theConflictBonusIsZeroBelowTheFloorMonotoneAndNeverAboveTheBound() {
        assertEquals(0, SocialGroups.conflictBonus(0));
        assertEquals(0, SocialGroups.conflictBonus(SocialGroups.TENSION_FLOOR - 0.001));
        double last = 0;
        for (double t = 0; t <= 1.0001; t += 0.01) {
            double b = SocialGroups.conflictBonus(t);
            assertTrue(b >= last - 1e-12, "monotone at " + t);
            assertTrue(b >= 0 && b <= SocialGroups.MAX_CONFLICT_BONUS + 1e-12, "bounded at " + t + ": " + b);
            last = b;
        }
        assertEquals(SocialGroups.MAX_CONFLICT_BONUS, SocialGroups.conflictBonus(1.0), 1e-12);
    }

    @Test
    void aMildDislikeBetweenGroupsIsNoConflict() {
        Graph g = new Graph(1, 2, 3, 4, 5, 6).mutual(1, 2).mutual(2, 3).mutual(1, 3).mutual(4, 5).mutual(5, 6).mutual(4, 6);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        g.grudge(1, 4, 0.9, 0.9); // one bot hates one other: the groups as wholes are not in conflict
        SocialGroups.useForTest(g);
        assertEquals(0, SocialGroups.adjust(SocialActKind.ARGUE, B + 1, B + 4, now()), SocialGroups.tensionBetween(B + 1, B + 4, now()) + "");
    }

    // ── reputation through the circle API ──

    @Test
    void aGroupsReputationIsReadFromTheMembersCircles() {
        Reputation.useForTest(id -> new long[]{Reputation.SOLO | (id & 0xffffffffL)}, clock::get, id -> false);
        int subject = B + 77;
        Reputation.witnessed(B + 1, subject, Reputation.Mark.GIFT, now());
        Reputation.witnessed(B + 2, subject, Reputation.Mark.FAVOR, now());
        Reputation.witnessed(B + 3, subject, Reputation.Mark.KS, now());
        SocialGroups.Group g = new SocialGroups.Group(B + 1, new int[]{B + 1, B + 2, B + 3}, 0, 19.5);
        SocialGroups.GroupReputation r = SocialGroups.reputation(g, subject);
        assertEquals(3, r.membersKnowing());
        assertEquals(2.0 / 3, r.favour(), 1e-9);
        assertEquals(1.0 / 3, r.grudge(), 1e-9);
        assertTrue(r.standing() > 0);
        assertEquals(0, SocialGroups.reputation(g, B + 78).membersKnowing());
    }

    // ── off ──

    @Test
    void withTheSwitchOffThereAreNoGroupsAndNoAdjustment() {
        Graph g = new Graph(1, 2, 3).mutual(1, 2).mutual(2, 3).mutual(1, 3);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        SocialGroups.useForTest(g);
        assertEquals(1, SocialGroups.current(now()).groups().size());
        System.setProperty(SocialGroups.SWITCH, "false");
        assertTrue(SocialGroups.current(now()).groups().isEmpty());
        assertEquals(0, SocialGroups.adjust(SocialActKind.SPEND_TIME, B + 1, B + 2, now()));
        List<Integer> ids = new ArrayList<>(List.of(B + 9, B + 2));
        SocialGroups.mateFirst(B + 1, ids, now());
        assertEquals(List.of(B + 9, B + 2), ids);
        System.clearProperty(SocialGroups.SWITCH);
        System.setProperty(SocialPilot.SWITCH, "false");
        assertTrue(SocialGroups.current(now()).groups().isEmpty(), "the pilot off means no groups either");
        assertFalse(SocialGroups.enabled());
    }

    @Test
    void theInspectViewNamesTheGroupOrIsNull() {
        Graph g = new Graph(1, 2, 3).mutual(1, 2).mutual(2, 3).mutual(1, 3);
        g.profile = SocialRoutine.Profile.of(5, SocialRoutineTest.mind(0.5, 0.5, 0.5, 0.5, 0.5), 4);
        SocialGroups.useForTest(g);
        Map<String, Object> view = SocialGroups.view(B + 2, now());
        assertNotNull(view);
        assertEquals(B + 1, view.get("id"));
        assertEquals(List.of(B + 1, B + 2, B + 3), view.get("members"));
        assertTrue(SocialPilot.isPilotMap((Integer) view.get("gathersAt")));
        assertNull(SocialGroups.view(B + 9, now()));
    }
}
