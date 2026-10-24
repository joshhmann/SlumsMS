package soloMapling.qa;

/**
 * How long QA staging and auto-QA windows are allowed to run, as a fraction of the real thing.
 *
 * <p>Auto-QA stages bot behaviour and then waits for it: a pixel-spread sample, a buddy accept, a KS war. A full
 * run spends most of its wall clock in those waits, so the suite can be told to run its windows shorter. The scale
 * comes from {@code -Dslums.qa.timeScale=<0.1..1.0>} (default 1.0, unparseable or out of range clamped), or from
 * {@link #beginRun(double)} for one run started with {@code --fast}.
 *
 * <p><b>Only QA staging and auto-QA check code may read this.</b> A tuned wait is evidence about the behaviour the
 * check staged; a production timer, cooldown or expiry is the thing under test, and shortening it would change what
 * the check observes rather than how long it takes to observe it. Anything a real player can time therefore stays
 * real, and the checks that assert on a delay, a cooldown or an expiry are never scaled at all.
 *
 * <p>The scale is never silent: every window that uses it prints the seconds it actually waited, and a run records
 * the scale it ran under ({@link #label()}).
 */
public final class QaTiming {

    /** The system property a run (or a manual server start) sets: {@code -Dslums.qa.timeScale=0.4}. */
    public static final String PROPERTY = "slums.qa.timeScale";

    /** The scale {@code tools/ops/autoqa-run --fast} and {@code !autoqa run ... --fast} use. */
    public static final double FAST_SCALE = 0.4;

    static final double MIN_SCALE = 0.1;
    static final double MAX_SCALE = 1.0;

    /** The scale of the run in flight ({@code --fast}); null means "use the property". */
    private static volatile Double runScale;

    private QaTiming() {
    }

    /** The scale in force: the run's own scale when a scaled run is in flight, else the property. */
    public static double scale() {
        Double run = runScale;
        return run == null ? parse(System.getProperty(PROPERTY)) : run;
    }

    /** {@code real} milliseconds scaled to this run's clock, never negative. */
    public static long ms(long real) {
        return Math.max(0, Math.round(real * scale()));
    }

    /** {@code real} seconds scaled to this run's clock, never negative, and never rounded away to 0. */
    public static int seconds(int real) {
        if (real <= 0) {
            return real;
        }
        return Math.max(1, (int) Math.round(real * scale()));
    }

    /** True when the run is shortened, i.e. a window shown to the reader is not the real one. */
    public static boolean fast() {
        return scale() < MAX_SCALE - 1e-9;
    }

    /** The scale as evidence shows it: {@code 1.0x}, {@code 0.4x}. */
    public static String label() {
        return String.format(java.util.Locale.ROOT, "%.1fx", scale());
    }

    /** Short suffix for a window's own line: {@code ""} at 1.0, {@code " (fast lane 0.4x)"} when shortened. */
    public static String note() {
        return fast() ? " (fast lane " + label() + ")" : "";
    }

    /** Starts a run at {@code scale} (clamped); {@link #endRun()} restores the property. */
    public static void beginRun(double scale) {
        runScale = clamp(scale);
    }

    public static void endRun() {
        runScale = null;
    }

    static double parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAX_SCALE;
        }
        try {
            return clamp(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return MAX_SCALE;
        }
    }

    static double clamp(double scale) {
        if (!Double.isFinite(scale)) {
            return MAX_SCALE;
        }
        return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
    }
}
