package soloMapling.qa;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 1.6.7 boss-feed-lines: observe-only lines about boss fights, for the QA analyser and for reading a fight afterwards.
 * <p>
 * Every line goes to two sinks: the QA feed (QaLog topic MECHANICS, only while a runner polls, with the fight's human
 * members as the line's characters) and the server log at INFO under the logger name {@value #LOGGER_NAME}, which
 * is always on. Nothing here changes a fight. Lines name characters by id only; the one name is the run's leader tag
 * the PQ lines already carry, built by the event scripts.
 * <p>
 * Skill lines (summon, immune, heal) fire for any {@code isBoss()} monster and are rate limited per monster and kind
 * ({@link #FEED_GAP_MS} for the feed, {@link #LOG_GAP_MS} for the log); one {@code BOSS skills} summary on the boss's
 * death covers what the limits skipped.
 */
public final class BossFeed {

    public static final String LOGGER_NAME = "BossFeed";
    public static final long FEED_GAP_MS = 3_000;
    public static final long LOG_GAP_MS = 30_000;
    public static final int PIANUS_MAP = 230040420;
    public static final int PIANUS_R = 8510000;
    public static final int PIANUS_L = 8520000;
    private static final int BLOODY_BOOM = 8510100;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final int HEAL = 0, SUMMON = 1, IMMUNE = 2;

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);
    private static volatile Consumer<String> logSink = LOG::info;
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static final ThreadLocal<Boolean> GM_SPAWN = new ThreadLocal<>();
    private static final Map<Monster, Tally> TALLIES = Collections.synchronizedMap(new WeakHashMap<>());

    private BossFeed() {
    }

    /** What one boss has done so far, and when each kind was last written to each sink. */
    private static final class Tally {
        long spawnedAt;
        int heals, summons, summonedMobs, immunes;
        long healHp;
        final long[] lastFeed = {NEVER, NEVER, NEVER};
        final long[] lastLog = {NEVER, NEVER, NEVER};
    }

    /** True while the QA feed wants these lines. The log copy does not depend on it. */
    public static boolean on() {
        return QaLog.on(QaLog.Topic.MECHANICS);
    }

    /** An event boss line: both sinks. The members named to the feed are the instance's human players. */
    public static void line(EventInstanceManager eim, String text) {
        if (text == null) {
            return;
        }
        toLog(text);
        if (on() && eim != null) {
            int[] ids = eim.getPlayers().stream().filter(c -> !c.isBot()).mapToInt(Character::getId).toArray();
            QaLog.chars(QaLog.Topic.MECHANICS, 0, ids, text);
        }
    }

    /**
     * A field boss line: both sinks. The members named to the feed are the human players on the map. The line ends with
     * the channel ("ch 3"): a field boss map can be loaded on several channels at once, each with its own boss (TEST,
     * 9 Nov: two Pianus spawns 4 s apart were two channels, not two bosses on one map).
     */
    public static void field(MapleMap map, String text) {
        if (text == null) {
            return;
        }
        String line = text + channelSuffix(map);
        toLog(line);
        toFeed(map, line);
    }

    private static String channelSuffix(MapleMap map) {
        try {
            return map == null || map.getChannelId() <= 0 ? "" : " ch " + map.getChannelId();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void toLog(String text) {
        try {
            logSink.accept(text);
        } catch (RuntimeException ignored) {
            // an observer never breaks the fight
        }
    }

    private static void toFeed(MapleMap map, String text) {
        if (!on()) {
            return;
        }
        int[] ids = map == null ? new int[0]
                : map.getAllPlayers().stream().filter(c -> !c.isBot()).mapToInt(Character::getId).toArray();
        QaLog.chars(QaLog.Topic.MECHANICS, map == null ? 0 : map.getId(), ids, text);
    }

    // ── field boss: Pianus ────────────────────────────────────────────────────────────────────────

    /** Runs {@code spawn} with the spawn source marked as a GM command ({@code !pianus}). */
    public static void asGm(Runnable spawn) {
        GM_SPAWN.set(Boolean.TRUE);
        try {
            spawn.run();
        } finally {
            GM_SPAWN.remove();
        }
    }

    /** MapleMap.spawnMonster calls this for every boss; only Pianus writes a line. */
    public static void spawned(MapleMap map, Monster boss) {
        String label = pianusLabel(boss.getId());
        if (label == null) {
            return;
        }
        tally(boss).spawnedAt = clock.getAsLong();
        field(map, "BOSS spawn " + label + " map " + PIANUS_MAP + " source "
                + (GM_SPAWN.get() != null ? "gm" : "timer"));
    }

    /** Monster.killBy calls this for every boss: the Pianus lines, then the skill summary of any boss that used skills. */
    public static void killed(Monster boss, int damagers) {
        Tally t = TALLIES.remove(boss);
        String label = label(boss);
        MapleMap map = boss.getMap();
        if (pianusLabel(boss.getId()) != null) {
            long after = t == null || t.spawnedAt == 0 ? 0 : (clock.getAsLong() - t.spawnedAt) / 1000;
            field(map, "BOSS kill " + label + " after " + after + " s, damagers " + damagers);
            field(map, "BOSS reward " + label + " exp " + boss.getExp() + " to " + damagers + " members");
        }
        if (t != null && (t.heals + t.summons + t.immunes) > 0) {
            field(map, "BOSS skills " + label + " heals " + t.heals + " (+" + t.healHp + " hp), summons " + t.summons
                    + " (" + t.summonedMobs + " mobs), immunes " + t.immunes);
        }
    }

    // ── skills of any boss ────────────────────────────────────────────────────────────────────────

    /** The boss healed itself (MobSkill HEAL_M); {@code gained} is the HP it actually got back. */
    public static void healed(Monster boss, long gained) {
        Tally t = tally(boss);
        t.heals++;
        t.healHp += Math.max(0, gained);
        skillLine(boss, t, HEAL, () -> {
            long max = Math.max(1, boss.getMaxHp());
            return "BOSS heal " + label(boss) + " +" + Math.max(0, gained) + " hp (now " + (boss.getHp() * 100 / max) + "%)";
        });
    }

    /** One use of a summon skill; {@code mobs} is how many mobs it actually spawned, {@code firstMobId} the first. */
    public static void summoned(Monster boss, int mobs, int firstMobId) {
        if (mobs <= 0) {
            return;
        }
        Tally t = tally(boss);
        t.summons++;
        t.summonedMobs += mobs;
        skillLine(boss, t, SUMMON, () -> "BOSS summon " + label(boss) + " "
                + (firstMobId == BLOODY_BOOM ? "bloody-boom" : "mob " + firstMobId) + " " + mobs);
    }

    /** A weapon or magic immunity was applied for {@code seconds}. */
    public static void immune(Monster boss, boolean weapon, long seconds) {
        Tally t = tally(boss);
        t.immunes++;
        skillLine(boss, t, IMMUNE, () -> "BOSS immune " + label(boss) + " " + (weapon ? "weapon" : "magic") + " " + seconds + " s");
    }

    private static void skillLine(Monster boss, Tally t, int kind, java.util.function.Supplier<String> text) {
        long now = clock.getAsLong();
        String line = null;
        if (now - t.lastLog[kind] >= LOG_GAP_MS) {
            t.lastLog[kind] = now;
            line = text.get() + channelSuffix(boss.getMap());
            toLog(line);
        }
        if (now - t.lastFeed[kind] >= FEED_GAP_MS && on()) {
            t.lastFeed[kind] = now;
            toFeed(boss.getMap(), line != null ? line : text.get() + channelSuffix(boss.getMap()));
        }
    }

    private static Tally tally(Monster boss) {
        return TALLIES.computeIfAbsent(boss, b -> new Tally());
    }

    /** "pianus R" / "pianus L", or "mob &lt;id&gt;" for any other boss. */
    static String label(Monster boss) {
        String p = pianusLabel(boss.getId());
        return p != null ? p : "mob " + boss.getId();
    }

    private static String pianusLabel(int mobId) {
        return mobId == PIANUS_R ? "pianus R" : mobId == PIANUS_L ? "pianus L" : null;
    }

    // ── tests ─────────────────────────────────────────────────────────────────────────────────────

    /** Test seam: where the INFO copy goes (default: the {@value #LOGGER_NAME} logger) and the clock. */
    public static void useForTests(Consumer<String> log, LongSupplier now) {
        logSink = log == null ? LOG::info : log;
        clock = now == null ? System::currentTimeMillis : now;
        TALLIES.clear();
    }
}
