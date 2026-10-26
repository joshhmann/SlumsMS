package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/**
 * 1.6.0 slice S4: what the bots' circles think of someone - a reputation, kept as <b>aggregates</b>, not as notes per
 * listener (social-life audit §5: per-listener bot-about-bot notes would grow without bound).
 *
 * <p>For each subject (a real player or a bot) and each <i>group</i> that knows of them - a bot guild, a crew
 * ({@code BotBonds.crewOf}, keyed by its lowest member) or, for a bot with neither, the bot alone - five counters:
 * {@link Mark#SCAM}, {@link Mark#KS}, {@link Mark#GIFT}, {@link Mark#FAVOR} and {@link Mark#PQ}. A bot's first-hand
 * experience ({@link #witnessed}: an affinity event with a player, a bond event with a bot, an S3 memory row at boot)
 * counts for every group the bot is in. Gossip ({@link #spread}) moves the counters from the teller's groups to the
 * listener's one step at a time along a bond (friends and crew, see {@code BotGossip}), never past the teller's own
 * count and at most {@link #MAX_HOPS} groups away from whoever saw it. Counters fade: a grudge (SCAM, KS) halves every
 * {@link #GRUDGE_HALF_LIFE_MS} (2 weeks), a kindness every {@link #FAVOUR_HALF_LIFE_MS} (6 weeks), the owner's
 * defaults of the audit follow-up.
 *
 * <p>What it is for: grounded gossip (a thread brings up a real KS of a real player, see {@code BotGossip#factFor}),
 * and {@link #wary}: a bot whose circle heard a subject KSes or scams treats them a little more carefully (the
 * listener's affinity takes the existing capped {@code HEARSAY_BAD} nudge when the news reaches it).
 *
 * <p>{@code @botdrama off}: nothing is counted about the player, and {@link #forget} drops what was. Bounds:
 * {@link #MAX_SUBJECTS} subjects (least recently touched goes), {@link #MAX_GROUPS_PER_SUBJECT} groups each (the
 * faintest goes), {@link #MAX_SUBJECTS_PER_GROUP} subjects indexed per group, {@link #MAX_DRAMA} bond-drama items. All
 * memory; the boot read ({@link #loadAsync}) rebuilds players' reputations from the first-hand S3 rows.
 */
public final class Reputation {

    private static final Logger log = LoggerFactory.getLogger(Reputation.class);

    /** What a subject did, as the bots count it. */
    public enum Mark {
        SCAM(true), KS(true), GIFT(false), FAVOR(false), PQ(false);

        public final boolean negative;

        Mark(boolean negative) {
            this.negative = negative;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The mark an affinity event leaves (a player's deed toward a bot), or null; hearsay never counts. */
        public static Mark of(AffinityEvent e) {
            if (e == null || e.hearsay()) {
                return null;
            }
            return switch (e) {
                case SCAM -> SCAM;
                case KS -> KS;
                case GIFT -> GIFT;
                case FAVOR -> FAVOR;
                default -> null;
            };
        }

        /** The mark a bond event leaves (one bot's deed toward another), or null. */
        public static Mark of(BondEvent e) {
            if (e == null) {
                return null;
            }
            return switch (e) {
                case KS -> KS;
                case GIFT -> GIFT;
                case PQ -> PQ;
                default -> null;
            };
        }

        /** The mark an S3 memory kind stands for, or null. */
        public static Mark of(BotMemory.Kind k) {
            if (k == null) {
                return null;
            }
            return switch (k) {
                case SCAM -> SCAM;
                case GIFT -> GIFT;
                case FAVOR -> FAVOR;
                case PQ, BOSS -> PQ;
                default -> null;
            };
        }
    }

    public static final long GRUDGE_HALF_LIFE_MS = TimeUnit.DAYS.toMillis(14);
    public static final long FAVOUR_HALF_LIFE_MS = TimeUnit.DAYS.toMillis(42);
    /** Seen first-hand is hop 0; a group that heard it from one that saw it is hop 1; and one more. */
    public static final int MAX_HOPS = 2;
    static final int MAX_SUBJECTS = 2_000;
    static final int MAX_GROUPS_PER_SUBJECT = 24;
    static final int MAX_SUBJECTS_PER_GROUP = 24;
    static final int MAX_GROUP_INDEX = 6_000;
    static final int MAX_DRAMA = 200;
    public static final long DRAMA_TTL_MS = TimeUnit.HOURS.toMillis(6);
    /** A count below this is forgotten. */
    static final double FAINT = 0.2;

    private static final int MARKS = Mark.values().length;

    /** One group's counters about one subject. Guarded by the class lock. */
    static final class Entry {
        final double[] count = new double[MARKS];
        final long[] at = new long[MARKS];
        /** Per mark: who it was done to (the bot that saw it first-hand), as the group tells it. */
        final int[] to = new int[MARKS];
        int hops = MAX_HOPS + 1;

        double value(Mark m, long now) {
            int i = m.ordinal();
            if (count[i] <= 0) {
                return 0;
            }
            long age = Math.max(0, now - at[i]);
            return count[i] * Math.pow(0.5, (double) age / (m.negative ? GRUDGE_HALF_LIFE_MS : FAVOUR_HALF_LIFE_MS));
        }

        double total(long now) {
            double t = 0;
            for (Mark m : Mark.values()) {
                t += value(m, now);
            }
            return t;
        }
    }

    /** What a bot's circle knows about a subject: decayed counts per mark, who it was done to, how far it travelled. */
    public record Known(int subjectId, Map<Mark, Double> counts, Map<Mark, Integer> to, int hops) {

        public static Known none(int subjectId) {
            return new Known(subjectId, Map.of(), Map.of(), MAX_HOPS + 1);
        }

        public double count(Mark m) {
            return counts.getOrDefault(m, 0.0);
        }

        public boolean any() {
            return !counts.isEmpty();
        }

        public double negative() {
            return count(Mark.SCAM) * 2 + count(Mark.KS);
        }

        public double positive() {
            return count(Mark.GIFT) + count(Mark.FAVOR) + count(Mark.PQ);
        }

        /** The mark that stands out (the largest count; a grudge wins a tie), or null when nothing is known. */
        public Mark top() {
            Mark best = null;
            double bestValue = 0;
            for (Mark m : Mark.values()) {
                double v = count(m) * (m.negative ? 1.25 : 1);
                if (v > bestValue) {
                    best = m;
                    bestValue = v;
                }
            }
            return best;
        }

        /** Who {@code m} was done to (a bot character id), or 0. */
        public int victim(Mark m) {
            return to.getOrDefault(m, 0);
        }
    }

    /** A bond between two bots that just changed state (fresh beef, a new friendship): bot-on-bot drama to tell. */
    public record Drama(int botId, int otherId, AffinityState state, BondEvent cause, long at) {
    }

    /** The groups a bot belongs to (a seam for tests). */
    @FunctionalInterface
    public interface Groups {
        long[] of(int botCharId);
    }

    private static final Object LOCK = new Object();
    /** subject -> group key -> counters; access order, so the least recently touched subject goes first. */
    private static final LinkedHashMap<Integer, Map<Long, Entry>> subjects = new LinkedHashMap<>(256, .75f, true);
    /** group key -> the subjects it knows of, newest first. */
    private static final LinkedHashMap<Long, Deque<Integer>> byGroup = new LinkedHashMap<>(256, .75f, true);
    private static final Deque<Drama> drama = new ArrayDeque<>();

    private static volatile Groups groups = Reputation::worldGroups;
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile IntPredicate optedOut = id -> !BotHelpers.isArtificial(id)
            && soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.optedOut(id);
    private static volatile boolean loaded;
    private static volatile boolean loading;

    private Reputation() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.reputation", "true"));
    }

    static long now() {
        return clock.getAsLong();
    }

    // ── groups ──

    /** Group key kinds: a key is the kind ORed with a guild id, a crew's lowest member or a lone bot's id. */
    public static final long GUILD = 1L << 40, CREW = 2L << 40, SOLO = 3L << 40;

    /** A bot's groups: its guild (if any), and its crew, or itself when it has no crew. */
    static long[] worldGroups(int botCharId) {
        List<Long> out = new ArrayList<>(2);
        try {
            var m = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.membership(botCharId);
            if (m != null && m.guildId() > 0) {
                out.add(GUILD | m.guildId());
            }
        } catch (RuntimeException | LinkageError e) {
            // no guild roster: the crew still counts
        }
        int anchor = botCharId;
        try {
            List<Integer> crew = soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.crewOf(botCharId);
            for (int id : crew) {
                anchor = Math.min(anchor, id);
            }
            out.add(crew.size() > 1 ? CREW | (anchor & 0xffffffffL) : SOLO | (botCharId & 0xffffffffL));
        } catch (RuntimeException | LinkageError e) {
            out.add(SOLO | (botCharId & 0xffffffffL));
        }
        long[] keys = new long[out.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = out.get(i);
        }
        return keys;
    }

    /** A group key in words, for logs ("guild 12", "crew of 1000000007", "bot 1000000007"). */
    static String groupName(long key) {
        long kind = key & ~0xffffffffL;
        long id = key & 0xffffffffL;
        return kind == GUILD ? "guild " + id : kind == CREW ? "crew of " + id : "bot " + id;
    }

    // ── recording ──

    /** Bot {@code botCharId} saw (suffered or enjoyed) {@code subjectId} doing {@code mark}: its groups count it. */
    public static void witnessed(int botCharId, int subjectId, Mark mark, long now) {
        if (!enabled() || mark == null || botCharId == subjectId || !BotHelpers.isArtificial(botCharId)
                || optedOut.test(subjectId)) {
            return;
        }
        long[] keys = groups.of(botCharId);
        synchronized (LOCK) {
            Map<Long, Entry> bySubject = subject(subjectId);
            for (long key : keys) {
                Entry e = bySubject.computeIfAbsent(key, k -> new Entry());
                int i = mark.ordinal();
                e.count[i] = e.value(mark, now) + 1;
                e.at[i] = now;
                e.to[i] = botCharId;
                e.hops = 0;
                index(key, subjectId);
            }
            boundGroups(bySubject, now);
            boundSubjects();
        }
    }

    /** {@code BotAffinity.record}: a real player's deed toward a bot (KS, scam, gift, favor). */
    public static void onAffinity(int botCharId, int playerId, AffinityEvent event, long now) {
        Mark m = Mark.of(event);
        if (m != null) {
            witnessed(botCharId, playerId, m, now);
        }
    }

    /** {@code BotBonds.record}: bot {@code botId}'s feeling about bot {@code otherId} moved by a deed of the other's. */
    public static void onBond(int botId, int otherId, BondEvent event, long now) {
        Mark m = Mark.of(event);
        if (m != null) {
            witnessed(botId, otherId, m, now);
        }
    }

    /** {@code BotBonds}: a bond changed state; a new rivalry or friendship is drama worth telling for a few hours. */
    public static void onBondState(int botId, int otherId, AffinityState after, BondEvent cause, long now) {
        if (!enabled() || after == null || after == AffinityState.NEUTRAL) {
            return;
        }
        synchronized (LOCK) {
            drama.removeIf(d -> d.botId() == botId && d.otherId() == otherId);
            drama.addLast(new Drama(botId, otherId, after, cause, now));
            while (drama.size() > MAX_DRAMA) {
                drama.pollFirst();
            }
        }
    }

    private static Map<Long, Entry> subject(int subjectId) {
        return subjects.computeIfAbsent(subjectId, k -> new HashMap<>());
    }

    private static void index(long key, int subjectId) {
        Deque<Integer> known = byGroup.computeIfAbsent(key, k -> new ArrayDeque<>());
        known.remove(subjectId);
        known.addFirst(subjectId);
        while (known.size() > MAX_SUBJECTS_PER_GROUP) {
            known.pollLast();
        }
        if (byGroup.size() > MAX_GROUP_INDEX) {
            Iterator<Long> it = byGroup.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    private static void boundGroups(Map<Long, Entry> bySubject, long now) {
        while (bySubject.size() > MAX_GROUPS_PER_SUBJECT) {
            Long faintest = null;
            double low = Double.MAX_VALUE;
            for (Map.Entry<Long, Entry> e : bySubject.entrySet()) {
                double t = e.getValue().total(now);
                if (t < low) {
                    low = t;
                    faintest = e.getKey();
                }
            }
            bySubject.remove(faintest);
        }
    }

    private static void boundSubjects() {
        while (subjects.size() > MAX_SUBJECTS) {
            Iterator<Integer> it = subjects.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    // ── reading ──

    /** What bot {@code botCharId}'s circle knows about {@code subjectId}: per mark, the most any of its groups knows. */
    public static Known known(int botCharId, int subjectId) {
        if (!enabled() || optedOut.test(subjectId)) {
            return Known.none(subjectId);
        }
        long[] keys = groups.of(botCharId);
        long now = now();
        synchronized (LOCK) {
            Map<Long, Entry> bySubject = subjects.get(subjectId);
            if (bySubject == null) {
                return Known.none(subjectId);
            }
            return merge(subjectId, bySubject, keys, now);
        }
    }

    private static Known merge(int subjectId, Map<Long, Entry> bySubject, long[] keys, long now) {
        Map<Mark, Double> counts = new java.util.EnumMap<>(Mark.class);
        Map<Mark, Integer> to = new java.util.EnumMap<>(Mark.class);
        int hops = MAX_HOPS + 1;
        for (long key : keys) {
            Entry e = bySubject.get(key);
            if (e == null) {
                continue;
            }
            for (Mark m : Mark.values()) {
                double v = e.value(m, now);
                if (v >= FAINT && v > counts.getOrDefault(m, 0.0)) {
                    counts.put(m, v);
                    if (e.to[m.ordinal()] != 0) {
                        to.put(m, e.to[m.ordinal()]);
                    }
                    hops = Math.min(hops, e.hops);
                }
            }
        }
        return counts.isEmpty() ? Known.none(subjectId) : new Known(subjectId, Map.copyOf(counts), Map.copyOf(to), hops);
    }

    /**
     * Whether this bot's circle knows {@code subjectId} as a KSer or scammer (grudges clearly outweigh kindness): the
     * small wariness hook later slices read (fewer invites, a colder greeting).
     */
    public static boolean wary(int botCharId, int subjectId) {
        Known k = known(botCharId, subjectId);
        return k.negative() >= 2 && k.negative() > k.positive() * 1.5;
    }

    /** The subjects this bot's circle knows something about, newest first (at most 2 x {@link #MAX_SUBJECTS_PER_GROUP}). */
    public static List<Integer> subjectsKnownBy(int botCharId) {
        if (!enabled()) {
            return List.of();
        }
        long[] keys = groups.of(botCharId);
        List<Integer> out = new ArrayList<>();
        synchronized (LOCK) {
            for (long key : keys) {
                Deque<Integer> known = byGroup.get(key);
                if (known == null) {
                    continue;
                }
                for (int s : known) {
                    if (!out.contains(s)) {
                        out.add(s);
                    }
                }
            }
        }
        out.removeIf(optedOut::test);
        return out;
    }

    /** Totals over every group: what the whole bot world counts about this subject (decayed). */
    public static Map<Mark, Double> totals(int subjectId) {
        Map<Mark, Double> out = new java.util.EnumMap<>(Mark.class);
        long now = now();
        synchronized (LOCK) {
            Map<Long, Entry> bySubject = subjects.get(subjectId);
            if (bySubject == null) {
                return out;
            }
            for (Entry e : bySubject.values()) {
                for (Mark m : Mark.values()) {
                    double v = e.value(m, now);
                    if (v > 0) {
                        out.merge(m, v, Double::sum);
                    }
                }
            }
        }
        return out;
    }

    /** Per group, the counts about this subject, for {@code !botwhy}/QA ("crew of 1000000007: ks 1.0 hop 0"). */
    public static List<String> describe(int subjectId) {
        List<String> out = new ArrayList<>();
        long now = now();
        synchronized (LOCK) {
            Map<Long, Entry> bySubject = subjects.get(subjectId);
            if (bySubject == null) {
                return out;
            }
            for (Map.Entry<Long, Entry> g : bySubject.entrySet()) {
                StringBuilder s = new StringBuilder(groupName(g.getKey())).append(':');
                for (Mark m : Mark.values()) {
                    double v = g.getValue().value(m, now);
                    if (v >= FAINT) {
                        s.append(' ').append(m.key()).append(' ').append(String.format(Locale.ROOT, "%.1f", v));
                    }
                }
                out.add(s.append(" hop ").append(g.getValue().hops).toString());
            }
        }
        return out;
    }

    /** Bond drama of the last {@link #DRAMA_TTL_MS} that involves one of these bots, newest first. */
    public static List<Drama> dramaAbout(java.util.Collection<Integer> bots, long now) {
        List<Drama> out = new ArrayList<>();
        synchronized (LOCK) {
            Iterator<Drama> it = drama.descendingIterator();
            while (it.hasNext()) {
                Drama d = it.next();
                if (now - d.at() > DRAMA_TTL_MS) {
                    continue;
                }
                if (bots.contains(d.botId()) || bots.contains(d.otherId())) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    // ── spreading ──

    /**
     * Gossip along a bond: the teller passes one subject its circle knows to the listener's circle. The subject is
     * drawn by weight - count, a grudge times {@code grudgeBias} (a Killer teller's bias for bad news, 1 = none) - and
     * each of its marks moves at most one step toward the teller's count. Returns the subject passed on, or 0.
     */
    public static int spread(int tellerCharId, int listenerCharId, double grudgeBias, long now, Random rng) {
        if (!enabled() || tellerCharId == listenerCharId) {
            return 0;
        }
        long[] from = groups.of(tellerCharId);
        long[] to = groups.of(listenerCharId);
        synchronized (LOCK) {
            List<Integer> candidates = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            double sum = 0;
            for (long key : from) {
                Deque<Integer> known = byGroup.get(key);
                if (known == null) {
                    continue;
                }
                for (int s : known) {
                    if (candidates.contains(s) || s == listenerCharId || optedOut.test(s)) {
                        continue;
                    }
                    Map<Long, Entry> bySubject = subjects.get(s);
                    if (bySubject == null) {
                        continue;
                    }
                    Known k = merge(s, bySubject, from, now);
                    if (!k.any() || k.hops() >= MAX_HOPS) {
                        continue;
                    }
                    double w = k.negative() * Math.max(0, grudgeBias) + k.positive();
                    if (w <= 0) {
                        continue;
                    }
                    candidates.add(s);
                    weights.add(w);
                    sum += w;
                }
            }
            if (candidates.isEmpty()) {
                return 0;
            }
            double roll = rng.nextDouble() * sum;
            int pick = candidates.get(candidates.size() - 1);
            for (int i = 0; i < candidates.size(); i++) {
                roll -= weights.get(i);
                if (roll < 0) {
                    pick = candidates.get(i);
                    break;
                }
            }
            Map<Long, Entry> bySubject = subjects.get(pick);
            Known source = merge(pick, bySubject, from, now);
            boolean moved = false;
            for (long key : to) {
                boolean shared = false;
                for (long f : from) {
                    shared |= f == key;
                }
                if (shared) {
                    continue; // the listener's circle is the teller's: it knows already
                }
                Entry e = bySubject.computeIfAbsent(key, k -> new Entry());
                for (Mark m : Mark.values()) {
                    double have = e.value(m, now);
                    double tell = source.count(m);
                    if (tell >= FAINT && have + 0.5 < tell) {
                        int i = m.ordinal();
                        e.count[i] = Math.min(tell, have + 1);
                        e.at[i] = now;
                        e.to[i] = source.victim(m);
                        moved = true;
                    }
                }
                e.hops = Math.min(e.hops, source.hops() + 1);
                index(key, pick);
            }
            boundGroups(bySubject, now);
            return moved ? pick : 0;
        }
    }

    // ── @botdrama off ──

    /** {@code @botdrama off}: every group forgets the player (and the index points at nothing about them). */
    public static int forget(int subjectId) {
        synchronized (LOCK) {
            Map<Long, Entry> gone = subjects.remove(subjectId);
            for (Deque<Integer> known : byGroup.values()) {
                known.remove(subjectId);
            }
            return gone == null ? 0 : gone.size();
        }
    }

    // ── boot read ──

    /** S3's first-hand rows about players that make a reputation: scams, gifts, favors, PQs and the KS feeling note. */
    static final String LOAD = "SELECT BotId, AboutCharacterId, Kind, Note, CreatedAt FROM botmemory WHERE Source = 'DIRECT'"
            + " AND (Kind IN ('SCAM', 'GIFT', 'FAVOR', 'PQ', 'BOSS') OR Note = ?) ORDER BY CreatedAt DESC LIMIT 20000";

    /** Once, off the game thread (the gossip step calls it): rebuilds players' reputations from the S3 rows. */
    public static void loadAsync() {
        if (loaded || loading || !enabled()) {
            return;
        }
        loading = true;
        soloMapling.server.ExecutorServiceManager.runAsync(() -> {
            try {
                int n = load();
                log.info("Reputation: {} first-hand memories counted", n);
                loaded = true;
            } catch (RuntimeException | SQLException e) {
                log.debug("Reputation boot read failed; it fills from live events", e);
                loaded = true; // a missing column (no migration 030) never heals by retrying
            } finally {
                loading = false;
            }
        });
    }

    static int load() throws SQLException {
        record Row(int bot, int about, Mark mark, long at) {
        }
        List<Row> rows = new ArrayList<>();
        String ks = soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.NOTE_KS;
        try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(LOAD)) {
            ps.setString(1, ks);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Mark m = ks.equals(rs.getString("Note")) ? Mark.KS : Mark.of(BotMemory.Kind.parse(rs.getString("Kind")));
                    if (m != null) {
                        rows.add(new Row(BotStore.BOT_ID_BASE + rs.getInt("BotId"), rs.getInt("AboutCharacterId"), m,
                                rs.getLong("CreatedAt")));
                    }
                }
            }
        }
        for (int i = rows.size() - 1; i >= 0; i--) { // the newest 20,000, counted oldest first so each fades right
            Row r = rows.get(i);
            witnessed(r.bot(), r.about(), r.mark(), r.at());
        }
        return rows.size();
    }

    // ── test seams ──

    /** Test seam: groups, clock and opt-outs (nulls restore the real ones); forgets everything. */
    public static void useForTest(Groups testGroups, LongSupplier testClock, IntPredicate testOptedOut) {
        groups = testGroups != null ? testGroups : Reputation::worldGroups;
        clock = testClock != null ? testClock : System::currentTimeMillis;
        optedOut = testOptedOut != null ? testOptedOut : id -> !BotHelpers.isArtificial(id)
                && soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.optedOut(id);
        resetForTest();
    }

    public static void resetForTest() {
        synchronized (LOCK) {
            subjects.clear();
            byGroup.clear();
            drama.clear();
        }
        loaded = false;
        loading = false;
    }

    static int subjectCount() {
        synchronized (LOCK) {
            return subjects.size();
        }
    }

    /** How many groups know of this subject. */
    static int groupsOf(int subjectId) {
        synchronized (LOCK) {
            Map<Long, Entry> bySubject = subjects.get(subjectId);
            return bySubject == null ? 0 : bySubject.size();
        }
    }
}
