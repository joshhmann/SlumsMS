package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.maps.MapleMap;
import soloMapling.qa.QaLog;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 1.6.0 player likeness (audit #9): a bot a real player can see moves like a player. Every instant reposition of a
 * bot on its own map (a recovery teleport, a camp "hard return", a "set down beside the NPC") and every fallback warp
 * of a trip that could not walk its hop asks here first. While a real player is on the bot's map instance (the
 * channel's map, a hidden GM counts) the answer is no: the caller walks instead, or waits, or gives up. With nobody
 * there the snap is invisible and stays as cheap as before.
 *
 * <p>The check is the live player list of one map instance, read only when a snap is about to happen (a rare event),
 * so it costs nothing per tick. Switch: {@code -Dsolomapling.walkWhenWatched=false} restores the old snaps.
 *
 * <p>Counters feed {@code !qa run lk-1} and the scorecard row "teleports while a player watches": {@link #averted()}
 * snaps turned into walks, {@link #snappedWatched()} snaps that still happened in front of a player (the switch off,
 * or a bot that fell out of the map, where walking is impossible).
 */
public final class WatchGate {

    public static final String PROP = "solomapling.walkWhenWatched";

    private static final AtomicLong AVERTED = new AtomicLong();
    private static final AtomicLong SNAPPED_WATCHED = new AtomicLong();
    private static final AtomicLong SNAPPED_UNWATCHED = new AtomicLong();

    /** Test seam: "a real player is on this map instance right now". */
    static volatile Predicate<MapleMap> realPlayerOn = ObserverTracker::hasRealPlayerNow;

    private WatchGate() {
    }

    /** On unless {@code -Dsolomapling.walkWhenWatched=false}. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP, "true").trim());
    }

    /** A real player is on this bot's map instance (same channel) right now. */
    public static boolean watched(Character bot) {
        MapleMap map = bot == null ? null : bot.getMap();
        return map != null && realPlayerOn.test(map);
    }

    /**
     * May this bot be snapped (teleported in place, or warped off a map it never walked out of) now? True when the
     * switch is off or nobody real is on its map; false when a player could see it, in which case the caller must
     * walk, wait or give up instead. {@code site} names the caller for the log.
     */
    public static boolean mayReposition(Character bot, String site) {
        if (bot == null) {
            return true;
        }
        boolean watched = watched(bot);
        if (!watched) {
            SNAPPED_UNWATCHED.incrementAndGet();
            return true;
        }
        if (!enabled()) {
            noteSnappedWhileWatched(bot, site);
            return true;
        }
        AVERTED.incrementAndGet();
        if (QaLog.on(QaLog.Topic.LIKENESS)) { // !qa lk-1
            QaLog.map(QaLog.Topic.LIKENESS, bot.getMapId(), "WATCHED WALK " + bot.getName() + " " + site
                    + " on map " + bot.getMapId() + ": a player is here, so no snap");
        }
        return false;
    }

    /** A snap that happened although a player was on the map (a recovery that cannot walk). */
    public static void noteSnappedWhileWatched(Character bot, String site) {
        SNAPPED_WATCHED.incrementAndGet();
        if (bot != null && QaLog.on(QaLog.Topic.LIKENESS)) { // !qa lk-1
            QaLog.map(QaLog.Topic.LIKENESS, bot.getMapId(), "WATCHED SNAP " + bot.getName() + " " + site
                    + " on map " + bot.getMapId());
        }
    }

    public static long averted() {
        return AVERTED.get();
    }

    public static long snappedWatched() {
        return SNAPPED_WATCHED.get();
    }

    /** One line for {@code !qa lk-1}. */
    public static String describe() {
        return String.format(Locale.ROOT, "walkWhenWatched=%s walked-instead=%d snapped-while-watched=%d unwatched-snaps=%d",
                enabled() ? "on" : "off", AVERTED.get(), SNAPPED_WATCHED.get(), SNAPPED_UNWATCHED.get());
    }

    static void resetForTest() {
        AVERTED.set(0);
        SNAPPED_WATCHED.set(0);
        SNAPPED_UNWATCHED.set(0);
        realPlayerOn = ObserverTracker::hasRealPlayerNow;
    }
}
