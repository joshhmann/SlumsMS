package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.Reputation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Ticket 181 stage 7: friend groups, read off the bond graph and kept in memory only.
 *
 * <p><b>Detection.</b> A group is a connected component, of at least {@link #MIN_SIZE} bots, of the graph whose edges are
 * pairs that are FRIEND or CLOSE_FRIEND <em>both ways</em> (each bot's own edge toward the other, so a one-sided crush is
 * not a friendship). The pilot is the universe. Recomputed at most every {@link #RECOMPUTE_MS}, on whichever decision asks
 * first; between recomputes every reader gets the same snapshot. Cost: one stage read per pilot pair, a few hundred reads.
 *
 * <p><b>What a group does</b> (always as a bias on an existing decision, never an order):
 * <ul>
 *   <li>{@link #adjust}: a group mate's SPEND_TIME and INVITE score {@link #MATE_BONUS} higher, so friends spend time
 *       together; {@code SocialDecider.candidates} lists group mates first.</li>
 *   <li>The group's gathering place is the most common favourite of its members at the group's hour ({@link Group#spot}),
 *       and its recurring event is the daily hour the members' evening hangouts centre on ({@link Group#eventHour}); a
 *       member's {@link SocialRoutine#spotFor} leans toward that place all day and strongly at that hour.</li>
 *   <li><b>Conflict between groups.</b> The tension of two groups is the mean, over every cross pair and both directions,
 *       of the larger of resentment and 0.8 x rivalry. Above {@link #TENSION_FLOOR} it adds up to
 *       {@link #MAX_CONFLICT_BONUS} to the ARGUE and AVOID scores of members toward members of the other group: the odds
 *       move, the act's own preconditions (a real grudge, norms, per-pair limits) still apply.</li>
 *   <li>{@link #reputation}: what the group's members' circles hold about a subject, through
 *       {@link Reputation#known} (no store of its own).</li>
 * </ul>
 * {@code -Dslums.socialgroups=false} (or the pilot off): no groups.
 */
public final class SocialGroups {

    public static final String SWITCH = "slums.socialgroups";
    public static final int MIN_SIZE = 3;
    public static final long RECOMPUTE_MS = 3 * 60_000L;
    /** SPEND_TIME and INVITE toward a group mate. */
    public static final double MATE_BONUS = 0.10;
    /** The most the conflict between two groups adds to ARGUE or AVOID. */
    public static final double MAX_CONFLICT_BONUS = 0.12;
    /** Below this tension two groups are not in conflict. */
    public static final double TENSION_FLOOR = 0.10;

    private SocialGroups() {
    }

    /** On unless {@code -Dslums.socialgroups=false}; also needs the pilot. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(SWITCH)) && SocialPilot.enabled();
    }

    /** One friend group: sorted member ids, the place it gathers (index into the pilot maps) and its daily hour. */
    public record Group(int id, int[] members, int spot, double eventHour) {
        public boolean has(int botId) {
            return Arrays.binarySearch(members, botId) >= 0;
        }

        public int size() {
            return members.length;
        }
    }

    /** The groups at one moment, who is in which, and the tension between each pair of groups. */
    public record Snapshot(long at, List<Group> groups, Map<Integer, Group> byBot, Map<Long, Double> tension) {
        static final Snapshot EMPTY = new Snapshot(0, List.of(), Map.of(), Map.of());
    }

    // ── pure detection ──

    /**
     * The groups: connected components of at least {@link #MIN_SIZE} bots over the mutual friend edges. {@code friendsOf}
     * answers, for one bot, the bots it counts as a friend (its own directed edge). An edge needs both directions.
     * Components are sorted by their smallest member; members are sorted.
     */
    public static List<int[]> components(Collection<Integer> universe, IntFunction<Collection<Integer>> friendsOf) {
        Map<Integer, List<Integer>> mutual = new HashMap<>();
        Map<Integer, java.util.Set<Integer>> out = new HashMap<>();
        for (int a : universe) {
            out.put(a, new java.util.HashSet<>(friendsOf.apply(a)));
        }
        for (int a : universe) {
            for (int b : out.get(a)) {
                java.util.Set<Integer> back = out.get(b);
                if (a != b && back != null && back.contains(a)) {
                    mutual.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                }
            }
        }
        List<int[]> groups = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        List<Integer> order = new ArrayList<>(universe);
        order.sort(Comparator.naturalOrder());
        for (int start : order) {
            if (!seen.add(start) || !mutual.containsKey(start)) {
                continue;
            }
            List<Integer> comp = new ArrayList<>();
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                int x = queue.poll();
                comp.add(x);
                for (int y : mutual.getOrDefault(x, List.of())) {
                    if (seen.add(y)) {
                        queue.add(y);
                    }
                }
            }
            if (comp.size() >= MIN_SIZE) {
                int[] ids = comp.stream().mapToInt(Integer::intValue).sorted().toArray();
                groups.add(ids);
            }
        }
        groups.sort(Comparator.comparingInt(g -> g[0]));
        return groups;
    }

    /**
     * Tension between two groups: the mean over every cross pair and both directions of max(resentment, 0.8 x rivalry),
     * 0..1. Pure over {@code edge}.
     */
    public static double tension(int[] a, int[] b, BiFunction<Integer, Integer, Axes> edge) {
        double sum = 0;
        int n = 0;
        for (int x : a) {
            for (int y : b) {
                Axes xy = edge.apply(x, y), yx = edge.apply(y, x);
                sum += Math.max(xy.resentment(), 0.8 * xy.rivalry()) + Math.max(yx.resentment(), 0.8 * yx.rivalry());
                n += 2;
            }
        }
        return n == 0 ? 0 : Math.min(1, sum / n);
    }

    /** The circular mean of hours of the day (members' evening hours), 0..24. */
    static double circularMeanHour(double[] hours) {
        double s = 0, c = 0;
        for (double h : hours) {
            double a = h / 24.0 * 2 * Math.PI;
            s += Math.sin(a);
            c += Math.cos(a);
        }
        double mean = Math.atan2(s, c) / (2 * Math.PI) * 24.0;
        return (mean + 24) % 24;
    }

    // ── live ──

    /** Where the universe and the edges come from: the game in production, fakes in tests. */
    interface Source {
        Collection<Integer> universe();

        Collection<Integer> friendsOf(int bot);

        Axes edge(int from, int to);

        /** The routine profile of {@code bot} now, or null when its mind is not in memory. */
        SocialRoutine.Profile profile(int bot, long now);
    }

    private static final Source GAME = new Source() {
        @Override
        public Collection<Integer> universe() {
            return SocialPilot.members();
        }

        @Override
        public Collection<Integer> friendsOf(int bot) {
            List<Integer> out = new ArrayList<>(4);
            for (var v : BotBonds.bondsOf(bot)) {
                if (SocialPilot.isFriendStage(BotBonds.stage(bot, v.otherId()))) {
                    out.add(v.otherId());
                }
            }
            return out;
        }

        @Override
        public Axes edge(int from, int to) {
            return BotBonds.axes(from, to);
        }

        @Override
        public SocialRoutine.Profile profile(int bot, long now) {
            return SocialRoutine.profileOf(bot, now);
        }
    };

    private static volatile Source source = GAME;
    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    private static volatile long computedAt = -1;
    private static final Object LOCK = new Object();
    private static int recomputes;

    /** The snapshot as of the last recompute (never recomputes). */
    public static Snapshot peek() {
        return snapshot;
    }

    /** The snapshot, recomputed first when the last one is older than {@link #RECOMPUTE_MS}. */
    public static Snapshot current(long now) {
        if (!enabled()) {
            return Snapshot.EMPTY;
        }
        if (fresh(now)) {
            return snapshot;
        }
        synchronized (LOCK) {
            if (fresh(now)) {
                return snapshot;
            }
            snapshot = compute(now, source);
            computedAt = now;
            recomputes++;
            return snapshot;
        }
    }

    /** Whether the snapshot is younger than {@link #RECOMPUTE_MS} (a clock that went backwards recomputes). */
    private static boolean fresh(long now) {
        long at = computedAt;
        return at >= 0 && now >= at && now - at < RECOMPUTE_MS;
    }

    /** How many times the snapshot was recomputed (the throttle's evidence). */
    public static int recomputeCount() {
        return recomputes;
    }

    static Snapshot compute(long now, Source src) {
        Collection<Integer> universe = src.universe();
        List<int[]> comps = components(universe, src::friendsOf);
        int spots = SocialPilot.maps().length;
        List<Group> groups = new ArrayList<>(comps.size());
        Map<Integer, Group> byBot = new HashMap<>();
        for (int[] members : comps) {
            int id = members[0];
            double[] evenings = new double[members.length];
            SocialRoutine.Profile[] profiles = new SocialRoutine.Profile[members.length];
            int known = 0;
            for (int i = 0; i < members.length; i++) {
                SocialRoutine.Profile p = src.profile(members[i], now);
                profiles[i] = p;
                if (p != null) {
                    evenings[known++] = SocialRoutine.eveningHour(p);
                }
            }
            double hour = known == 0 ? 19.5 : circularMeanHour(Arrays.copyOf(evenings, known));
            int[] votes = new int[Math.max(1, spots)];
            for (SocialRoutine.Profile p : profiles) {
                if (p != null) {
                    votes[favourite(p, hour, votes.length)]++;
                }
            }
            int spot = 0;
            for (int i = 1; i < votes.length; i++) {
                if (votes[i] > votes[spot]) {
                    spot = i;
                }
            }
            Group g = new Group(id, members, spot, hour);
            groups.add(g);
            for (int m : members) {
                byBot.put(m, g);
            }
        }
        Map<Long, Double> tension = new TreeMap<>();
        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                double t = tension(groups.get(i).members(), groups.get(j).members(), src::edge);
                if (t >= TENSION_FLOOR) {
                    tension.put(pairKey(groups.get(i).id(), groups.get(j).id()), t);
                }
            }
        }
        return new Snapshot(now, List.copyOf(groups), Map.copyOf(byBot), Map.copyOf(tension));
    }

    /** A member's favourite place at the group's hour: the largest of {@link SocialRoutine#placeWeights}. */
    static int favourite(SocialRoutine.Profile p, double hour, int spots) {
        double[] w = SocialRoutine.placeWeights(p, SocialRoutine.at(p, hour), Math.min(spots, p.spotTaste().length));
        int best = 0;
        for (int i = 1; i < w.length; i++) {
            if (w[i] > w[best]) {
                best = i;
            }
        }
        return best;
    }

    static long pairKey(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    // ── what the decisions read ──

    /** The group {@code botId} is in now, or null. */
    public static Group groupOf(int botId, long now) {
        return current(now).byBot().get(botId);
    }

    /** What {@code SocialRoutine.spotFor} reads for this bot's group, or null when it has none. */
    public static SocialRoutine.GroupPlan planFor(int botId, long now) {
        Group g = groupOf(botId, now);
        return g == null ? null : new SocialRoutine.GroupPlan(g.spot(), g.eventHour());
    }

    /** Whether the two bots are in one group now. */
    public static boolean sameGroup(int a, int b, long now) {
        Group g = groupOf(a, now);
        return g != null && g.has(b);
    }

    /** The current tension between the groups of two bots, 0 when either has no group or they share one. */
    public static double tensionBetween(int a, int b, long now) {
        Snapshot s = current(now);
        Group x = s.byBot().get(a), y = s.byBot().get(b);
        if (x == null || y == null || x == y) {
            return 0;
        }
        return s.tension().getOrDefault(pairKey(x.id(), y.id()), 0.0);
    }

    /** What the groups add to the score of {@code kind} from {@code actor} toward {@code target}: 0 .. {@link #MAX_CONFLICT_BONUS}. */
    public static double adjust(SocialActKind kind, int actor, int target, long now) {
        boolean mates = kind == SocialActKind.SPEND_TIME || kind == SocialActKind.INVITE;
        if (!mates && kind != SocialActKind.ARGUE && kind != SocialActKind.AVOID || !enabled()) {
            return 0; // the common case: not one of the four acts, nothing read
        }
        return mates ? (sameGroup(actor, target, now) ? MATE_BONUS : 0) : conflictBonus(tensionBetween(actor, target, now));
    }

    /** The conflict bonus of a tension: 0 below the floor, then 0.4 per unit above it, never above the maximum. */
    public static double conflictBonus(double tension) {
        return tension < TENSION_FLOOR ? 0 : Math.min(MAX_CONFLICT_BONUS, 0.4 * (tension - TENSION_FLOOR) + 0.02);
    }

    /** Group mates first (stable), then the rest: for a decision tick's candidate list. */
    public static void mateFirst(int botId, List<Integer> ids, long now) {
        Group g = enabled() ? groupOf(botId, now) : null;
        if (g == null || ids.size() < 2) {
            return;
        }
        ids.sort(Comparator.comparingInt(id -> g.has(id) ? 0 : 1)); // List.sort is stable
    }

    // ── reputation (through the existing circle API, no store) ──

    /** What a group's circles hold about a subject: mean favour and grudge over the members, and how many know anything. */
    public record GroupReputation(int subject, double favour, double grudge, int membersKnowing) {
        /** Favour minus grudge: above 0 the group thinks well of the subject. */
        public double standing() {
            return favour - grudge;
        }
    }

    public static GroupReputation reputation(Group g, int subjectId) {
        double fav = 0, grudge = 0;
        int knowing = 0;
        for (int m : g.members()) {
            Reputation.Known k = Reputation.known(m, subjectId);
            if (k.any()) {
                knowing++;
                fav += k.positive();
                grudge += k.negative();
            }
        }
        int n = Math.max(1, g.size());
        return new GroupReputation(subjectId, fav / n, grudge / n, knowing);
    }

    // ── observability ──

    /** The inspect view of {@code botId}'s group ({@code /bots/{cid}}): null when it is in none (or the pilot is off). */
    public static Map<String, Object> view(int botId, long now) {
        Group g = groupOf(botId, now);
        if (g == null) {
            return null;
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", g.id());
        m.put("members", Arrays.stream(g.members()).boxed().toList());
        m.put("gathersAt", SocialPilot.maps()[Math.min(g.spot(), SocialPilot.maps().length - 1)]);
        m.put("eventHour", Math.round(g.eventHour() * 10) / 10.0);
        return m;
    }

    /** A line per group for stats and the report: id, size, place, hour. */
    public static List<String> describe(long now) {
        List<String> out = new ArrayList<>();
        for (Group g : current(now).groups()) {
            out.add("group " + g.id() + " size " + g.size() + " spot " + g.spot() + String.format(" event %.1fh", g.eventHour()));
        }
        return out;
    }

    /** Test seam: where the universe and the edges come from (null restores the game). Forgets the snapshot. */
    static void useForTest(Source test) {
        source = test != null ? test : GAME;
        resetForTest();
    }

    static void resetForTest() {
        snapshot = Snapshot.EMPTY;
        computedAt = -1;
        recomputes = 0;
    }
}
