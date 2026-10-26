package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaLog;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * 1.6.0 slice S7: a crush comes before a couple (bot with bot only; bots never court players here - that is
 * {@code BotRomance}, opt-in). A bot falls for one of its bot friends (a FRIEND bond, slice S2): a Socializer more
 * easily. While the crush lasts it shows small signs when both stand on a map a real player can see - it walks over to
 * stand near its crush, says something cheesy now and then, and gets jealous when its crush stands with one of its
 * rivals ("why is {crush} talking to {rival}", a spat on their bond). A crush that has lasted {@link #RIPE_MS}
 * (12 h) and is returned as friendship can become a couple ({@link BotCouples} forms couples only from ripe crushes).
 * It ends after {@link #TTL_MS} (3 days) unripe, when the admirer pairs up or the bond turns rival; a crush on a bot
 * that paired up with someone else lingers until then (its partner's jealousy, {@link BotCouples}).
 *
 * <p>Bounded: at most {@link #maxCrushes()} (two per couple slot), {@link #NEW_PER_HOUR} (3) new ones an hour from one
 * roll every {@link #ROLL_MS} (10 min) over at most {@link #SAMPLE} online bots. Signs at most every
 * {@link #SIGN_GAP_MS} per crush, jealousy every {@link #JEALOUS_GAP_MS}. Memory only: after a restart crushes grow
 * again from the stored friendships.
 */
public final class BotCrushes {

    private static final Logger log = LoggerFactory.getLogger(BotCrushes.class);

    /** One crush: {@code admirer} likes {@code target} (character ids). */
    public record Crush(int admirer, int target, long since, boolean staged) {
    }

    static final long ROLL_MS = TimeUnit.MINUTES.toMillis(10);
    static final int NEW_PER_HOUR = 3;
    static final int SAMPLE = 40;
    static final long RIPE_MS = TimeUnit.HOURS.toMillis(12);
    static final long TTL_MS = TimeUnit.DAYS.toMillis(3);
    static final long SIGN_GAP_MS = TimeUnit.MINUTES.toMillis(4);
    static final long JEALOUS_GAP_MS = TimeUnit.MINUTES.toMillis(15);
    static final int NEAR_PX = 150;

    private static final Map<Integer, Crush> byAdmirer = new ConcurrentHashMap<>();
    private static final Map<String, Long> nextAt = new ConcurrentHashMap<>();
    private static final java.util.Deque<Long> started = new java.util.ArrayDeque<>();
    private static volatile long lastRollAt;
    private static volatile IntFunction<Character> online = BotCrushes::liveBot;
    private static volatile IntToDoubleFunction socialPull = BotCrushes::seededSocialPull;
    /** Whether a real player (or a listening QA run) sees this bot's map. */
    private static volatile java.util.function.Predicate<Character> watched = BotCrushes::realPlayerSees;
    /** How a bot walks over (a seam for tests). */
    private static volatile java.util.function.BiConsumer<Character, Point> walk = (bot, to) ->
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.move(bot, to.x, to.y);
    /** How a bot says a line (a seam for tests). */
    private static volatile java.util.function.BiConsumer<Character, String> speak = (bot, line) -> BotSpeak(bot, line);

    private BotCrushes() {
    }

    public static boolean enabled() {
        return BotCouples.enabled() && !"false".equalsIgnoreCase(System.getProperty("solomapling.couples.crushes", "true"));
    }

    /** Two crushes per couple slot (about 40 at 3,000 bots). */
    static int maxCrushes() {
        return 2 * BotCouples.maxCouples();
    }

    // ── queries ──

    /** This bot's crush, or null. */
    public static Crush crushOf(int admirerCharId) {
        return byAdmirer.get(admirerCharId);
    }

    /** Whether this bot has a crush (the brain's HAS_CRUSH plan fact). */
    public static boolean hasCrush(int charId) {
        return enabled() && byAdmirer.containsKey(charId);
    }

    /** The bots with a crush on this one. */
    public static List<Integer> admirersOf(int targetCharId) {
        List<Integer> out = new ArrayList<>();
        for (Crush c : byAdmirer.values()) {
            if (c.target() == targetCharId) {
                out.add(c.admirer());
            }
        }
        return out;
    }

    public static List<Crush> all() {
        List<Crush> out = new ArrayList<>(byAdmirer.values());
        out.sort((x, y) -> Long.compare(x.since(), y.since()));
        return out;
    }

    // ── starting and ending ──

    /**
     * {@code admirer} gets a crush on {@code target} now. Null when either is in a couple, the admirer has a crush
     * already, it is the same bot, or (not staged) the world holds {@link #maxCrushes()}.
     */
    public static Crush start(int admirer, int target, boolean staged, long now) {
        if (!enabled() || admirer == target || !BotHelpers.isArtificial(admirer) || !BotHelpers.isArtificial(target)
                || BotCouples.coupleOf(admirer) != null || BotCouples.coupleOf(target) != null) {
            return null;
        }
        synchronized (byAdmirer) {
            if (byAdmirer.containsKey(admirer) || !staged && byAdmirer.size() >= maxCrushes()) {
                return null;
            }
            Crush c = new Crush(admirer, target, now, staged);
            byAdmirer.put(admirer, c);
            BotCouples.qa("CRUSH START " + name(admirer) + " -> " + name(target) + (staged ? " (staged)" : ""));
            return c;
        }
    }

    /** The crush ends ({@code why} for the log). */
    public static Crush end(int admirer, String why) {
        Crush c = byAdmirer.remove(admirer);
        if (c != null) {
            BotCouples.qa("CRUSH OVER " + name(c.admirer()) + " -> " + name(c.target()) + ": " + why);
        }
        return c;
    }

    /** A staged crush "has lasted" long enough now (QA: no 12 h wait). */
    public static boolean ripenNow(int admirer) {
        Crush c = byAdmirer.get(admirer);
        if (c == null) {
            return false;
        }
        byAdmirer.put(admirer, new Crush(c.admirer(), c.target(), c.since() - RIPE_MS, c.staged()));
        return true;
    }

    // ── the rules (pure) ──

    /** The chance per roll that a bot falls for its top friend: 0.08 x (0.5 + Socializer pull). */
    static double fallChance(double social) {
        return Math.min(0.5, 0.08 * (0.5 + Math.max(0, social)));
    }

    /** A crush that may become a couple: lasted {@link #RIPE_MS}, and the crush counts the admirer as a friend. */
    static boolean ripe(Crush c, boolean returned, long now) {
        return now - c.since() >= RIPE_MS && returned;
    }

    static boolean expired(Crush c, long now) {
        return now - c.since() >= TTL_MS;
    }

    // ── the minute step (from BotCouples.step) ──

    static void step(long now, Random rng) {
        if (!enabled()) {
            return;
        }
        for (Crush c : List.copyOf(byAdmirer.values())) {
            String why = endReason(c, now);
            if (why != null) {
                end(c.admirer(), why);
                Character a = online.apply(c.admirer());
                if (a != null && a.getMap() != null && watched.test(a) && !"together".equals(why)) {
                    say(a, "Over", name(c.target()), null);
                }
                continue;
            }
            Character a = online.apply(c.admirer());
            Character t = online.apply(c.target());
            if (a != null && t != null) {
                signs(c, a, t, now, rng);
            }
        }
        if (now - lastRollAt >= ROLL_MS) {
            lastRollAt = now;
            roll(now, rng);
        }
    }

    /**
     * Why a crush ends now, or null: too old, the admirer paired up, or it soured. A crush whose bot paired up with
     * someone else lingers until it fades: that is where a couple's jealousy comes from.
     */
    static String endReason(Crush c, long now) {
        BotCouples.Couple ct = BotCouples.coupleOf(c.target());
        BotCouples.Couple ca = BotCouples.coupleOf(c.admirer());
        if (ct != null && ca != null && ct.id() == ca.id()) {
            return "together";
        }
        if (ca != null) {
            return "found someone else";
        }
        if (BotBonds.state(c.admirer(), c.target()).hostile()) {
            return "it turned sour";
        }
        if (!c.staged() && expired(c, now)) {
            return "it faded";
        }
        return null;
    }

    /** Whether the crush counts the admirer as a friend (or has a crush on it too). */
    static boolean returned(Crush c) {
        Crush back = byAdmirer.get(c.target());
        return BotBonds.friend(c.target(), c.admirer()) || back != null && back.target() == c.admirer();
    }

    /** The oldest crush ripe for a couple with both bots online (BotCouples forms it), or null. */
    static Crush ripeOnline(long now) {
        for (Crush c : all()) {
            if (!c.staged() && ripe(c, returned(c), now) && BotCouples.coupleOf(c.target()) == null
                    && online.apply(c.admirer()) != null
                    && online.apply(c.target()) != null) {
                return c;
            }
        }
        return null;
    }

    /** The same as {@link #ripeOnline} for one staged crush (QA). */
    static boolean ripeStaged(Crush c, long now) {
        return c != null && ripe(c, returned(c), now);
    }

    /** One roll: a few online bots may fall for their top bot friend. */
    static void roll(long now, Random rng) {
        if (byAdmirer.size() >= maxCrushes()) {
            return;
        }
        List<BotSM> bots = new ArrayList<>(CharacterStorage.getAllBots().values());
        Collections.shuffle(bots, rng);
        for (int i = 0; i < Math.min(SAMPLE, bots.size()); i++) {
            BotSM sm = bots.get(i);
            Character chr = sm.getChr();
            if (chr == null || !(sm instanceof TrainingBot || sm instanceof SocialBot) || chr.getLevel() < 15
                    || byAdmirer.containsKey(chr.getId()) || BotCouples.coupleOf(chr.getId()) != null
                    || BotRoster.loadedMember(chr.getId() - BotStore.BOT_ID_BASE) == null
                    || soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.ofBot(chr.getId()) != null) {
                continue;
            }
            int target = topFriend(chr);
            if (target <= 0 || rng.nextDouble() >= fallChance(socialPull.applyAsDouble(chr.getId()))) {
                continue;
            }
            if (!hourly(now)) {
                return;
            }
            start(chr.getId(), target, false, now);
        }
    }

    /** The strongest bot friend online that could date this bot (level 15+, within 20 levels, single), or 0. */
    static int topFriend(Character chr) {
        for (int f : BotBonds.friendsOf(chr.getId())) {
            Character other = online.apply(f);
            if (other == null || BotCouples.coupleOf(f) != null) {
                continue;
            }
            if (BotCouples.compatible(new BotCouples.Candidate(chr.getId(), chr.getGender(), chr.getLevel(), 0, 0),
                    new BotCouples.Candidate(f, other.getGender(), other.getLevel(), 0, 0))) {
                return f;
            }
        }
        return 0;
    }

    private static boolean hourly(long now) {
        synchronized (started) {
            while (!started.isEmpty() && now - started.peekFirst() > TimeUnit.HOURS.toMillis(1)) {
                started.pollFirst();
            }
            if (started.size() >= NEW_PER_HOUR) {
                return false;
            }
            started.addLast(now);
            return true;
        }
    }

    /**
     * The small signs, on a watched map with both there: jealousy when the crush stands with one of the admirer's
     * rivals, else walking over to stand near it and now and then a cheesy line.
     */
    static String signs(Crush c, Character a, Character t, long now, Random rng) {
        if (a.getMap() == null || a.getMap() != t.getMap() || !watched.test(a)) {
            return null;
        }
        boolean qa = c.staged() && QaLog.on(QaLog.Topic.COUPLE);
        Character rival = rivalNear(a, t);
        if (rival != null && take("jealous:" + c.admirer(), now, qa ? 30_000 : JEALOUS_GAP_MS)) {
            String line = say(a, "Jealous", t.getName(), rival.getName());
            BotBonds.record(a.getId(), rival.getId(), BondEvent.SPAT, now, false);
            BotCouples.qa("CRUSH JEALOUS " + a.getName() + " (likes " + t.getName() + ") at " + rival.getName() + ": " + line);
            return "jealous";
        }
        if (!take("sign:" + c.admirer(), now, qa ? 20_000 : SIGN_GAP_MS)) {
            return null;
        }
        Point pa = a.getPosition();
        Point pt = t.getPosition();
        if (pa != null && pt != null && BotCouples.apart(pa, pt)) {
            Point to = new Point(pt.x + (pa.x < pt.x ? -40 : 40), pt.y);
            walk.accept(a, to);
            BotCouples.qa("CRUSH NEAR " + a.getName() + " walks over to stand near " + t.getName());
            return "near";
        }
        if (qa || rng.nextDouble() < 0.5) {
            String line = say(a, "Line", t.getName(), null);
            BotCouples.qa("CRUSH LINE " + a.getName() + " -> " + t.getName() + ": " + line);
            return "line";
        }
        return null;
    }

    /** One of the admirer's rivals standing within {@link #NEAR_PX} of its crush, or null. */
    static Character rivalNear(Character a, Character t) {
        Point pt = t.getPosition();
        if (pt == null) {
            return null;
        }
        for (int r : BotBonds.rivalsOf(a.getId())) {
            Character rival = online.apply(r);
            if (rival != null && rival.getMap() == t.getMap() && rival.getPosition() != null
                    && rival.getPosition().distance(pt) <= NEAR_PX) {
                return rival;
            }
        }
        return null;
    }

    /** Says a line of the crush pack ({@code CoupleDialogue.yaml}, root {@code Crush}) that ContentGuard passes. */
    static String say(Character bot, String node, String partner, String other) {
        String line = line(node, partner, other);
        if (line != null && bot.getMap() != null) {
            speak.accept(bot, line);
        }
        return line;
    }

    static String line(String node, String partner, String other) {
        Map<String, String> t = new java.util.HashMap<>();
        if (partner != null) {
            t.put("PARTNER", partner);
        }
        if (other != null) {
            t.put("OTHER", other);
        }
        for (int i = 0; i < 4; i++) {
            String line = BotCouples.pick("Crush", node, t);
            if (line != null && ContentGuard.ok(line)) {
                return line;
            }
        }
        return null;
    }

    private static boolean take(String key, long now, long gapMs) {
        Long next = nextAt.get(key);
        if (next != null && now < next) {
            return false;
        }
        nextAt.put(key, now + gapMs);
        return true;
    }

    static String name(int charId) {
        Character c = online.apply(charId);
        if (c != null) {
            return c.getName();
        }
        String n = soloMapling.ArtificialPlayer.BotBuddies.nameOf(charId);
        return n == null ? "#" + charId : n;
    }

    private static Character liveBot(int charId) {
        BotSM sm = CharacterStorage.getBotById(charId);
        return sm == null ? null : sm.getChr();
    }

    private static double seededSocialPull(int charId) {
        soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory m =
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore.peek(charId);
        return (m != null ? m.social() : soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.forCharacter(charId))
                .socialPull();
    }

    private static boolean realPlayerSees(Character bot) {
        try {
            for (Character c : bot.getMap().getAllPlayers()) {
                if (!BotHelpers.isBot(c) && BotHelpers.isVisibleRealPlayer(c)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    // ── GM / QA ──

    /** {@code !qa cr-1}: the signs of every crush whose two bots are online, now (gaps still apply). What happened. */
    public static List<String> signsNow(long now) {
        List<String> out = new ArrayList<>();
        for (Crush c : all()) {
            Character a = online.apply(c.admirer());
            Character t = online.apply(c.target());
            if (a != null && t != null) {
                String what = signs(c, a, t, now, java.util.concurrent.ThreadLocalRandom.current());
                out.add(a.getName() + " -> " + t.getName() + ": " + (what == null ? "nothing this time" : what));
            }
        }
        return out;
    }

    /** {@code !qa cr-1}: a staged crush becomes a couple now if it is ripe and returned. What happened. */
    public static String coupleNow(int admirer, long now) {
        Crush c = byAdmirer.get(admirer);
        if (c == null) {
            return "no crush";
        }
        if (!ripeStaged(c, now)) {
            return "not ripe yet (" + TimeUnit.MILLISECONDS.toMinutes(now - c.since()) + " min old, returned "
                    + returned(c) + ")";
        }
        BotCouples.Couple k = BotCouples.formCrush(c, now);
        return k == null ? "could not form a couple" : "couple #" + k.id();
    }

    /** GM lines: every crush and its age. */
    public static List<String> describe(long now) {
        List<String> out = new ArrayList<>();
        for (Crush c : all()) {
            out.add(name(c.admirer()) + " -> " + name(c.target()) + ", " + TimeUnit.MILLISECONDS.toHours(now - c.since())
                    + " h" + (ripe(c, returned(c), now) ? ", ripe" : "") + (c.staged() ? ", staged" : ""));
        }
        out.add("max " + maxCrushes() + " crushes");
        return out;
    }

    // ── test seams ──

    static void useForTest(IntFunction<Character> testOnline, IntToDoubleFunction testSocial,
                           java.util.function.Predicate<Character> testWatched) {
        online = testOnline != null ? testOnline : BotCrushes::liveBot;
        socialPull = testSocial != null ? testSocial : BotCrushes::seededSocialPull;
        watched = testWatched != null ? testWatched : BotCrushes::realPlayerSees;
        boolean quiet = testOnline != null;
        speak = quiet ? (bot, line) -> { } : (bot, line) -> BotSpeak(bot, line);
        walk = quiet ? (bot, to) -> { } : (bot, to) -> soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.move(bot, to.x, to.y);
        byAdmirer.clear();
        nextAt.clear();
        synchronized (started) {
            started.clear();
        }
        lastRollAt = 0;
    }

    /** Public GM/QA: forget a staged bot's crushes (as admirer and as crush). */
    public static void forgetBot(int charId) {
        byAdmirer.remove(charId);
        byAdmirer.values().removeIf(c -> c.target() == charId);
        nextAt.keySet().removeIf(k -> k.endsWith(":" + charId));
    }
}
