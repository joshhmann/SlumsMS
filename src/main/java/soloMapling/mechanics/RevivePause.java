package soloMapling.mechanics;

import java.util.Locale;

/**
 * Ticket 130 (owner decision 2026-11-02, "pause for revive"): a human death in a boss expedition must not end the run.
 *
 * <p>The scripts unregister a dead player so it can revive and walk back in ({@code scripts/event/ZakumBattle.js:140-160},
 * {@code scripts/event/HorntailBattle.js:140-160}), and {@link Orchestrator#memberLost} used to read that as "a member
 * left" and abort. With this on it holds the run instead: no stage advances, no member is warped, every bot waits with a
 * reason naming who it is waiting for, and the run resumes the moment that member is back in the instance. If the window
 * runs out the run ends the way it ends today, saying it was the revive window and not a solver failure.
 *
 * <p>Switch {@code -Dslums.bossfight.revivepause} (default <b>on</b>: the expiry path is today's behaviour, so a server
 * that turns it off gets exactly what it had before). The window is {@code -Dslums.bossfight.revivepause.ms} (default
 * {@value #DEFAULT_WINDOW_MS} ms, five minutes: enough to warp out, revive and walk back through the altar), and one row
 * may override it with {@code -Dslums.bossfight.revivepause.ms.<row id>} (for example
 * {@code -Dslums.bossfight.revivepause.ms.zakumbattle=600000}).
 *
 * <p>Party quests never pause: this is an expedition rule. A KPQ or OPQ run whose member leaves ends as it always did.
 */
public final class RevivePause {

    public static final String FLAG = "slums.bossfight.revivepause";
    public static final String WINDOW_PROPERTY = "slums.bossfight.revivepause.ms";
    public static final long DEFAULT_WINDOW_MS = 5 * 60_000L;

    private RevivePause() {
    }

    /**
     * True unless the server turned it off. Read with a default of {@code "true"} rather than
     * {@link Boolean#getBoolean}, which cannot express a switch that is on out of the box.
     */
    public static boolean on() {
        String value = System.getProperty(FLAG);
        return value == null || value.isBlank() || !"false".equalsIgnoreCase(value.trim());
    }

    /** The window for this row's runs, in ms: its own override, else the shared one, else five minutes. */
    public static long windowMs(MechanicsModel.Content content) {
        String perRow = content == null ? null
                : WINDOW_PROPERTY + "." + content.id().toLowerCase(Locale.ROOT);
        long ms = perRow == null ? longProperty(WINDOW_PROPERTY, DEFAULT_WINDOW_MS)
                : longProperty(perRow, longProperty(WINDOW_PROPERTY, DEFAULT_WINDOW_MS));
        return Math.max(0, ms);
    }

    private static long longProperty(String key, long fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
