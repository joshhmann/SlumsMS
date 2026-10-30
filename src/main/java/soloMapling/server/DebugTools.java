package soloMapling.server;

import soloMapling.autoqa.AutoQa;
import soloMapling.qa.QaSuite;

import java.util.function.Function;

/**
 * Ticket 157: the switch for the TEST-only debug tools - {@code !navprobe}, {@code !env lod pin}, {@code !botstatus},
 * the nav region in {@code !pos} and the gear-pick reason in {@code !botwhy}. On wherever the QA suite or auto-QA is
 * on (the test server), off by default, {@code -Dslums.debugtools=true|false} forces it either way. The path trace on
 * stuck has its own switch ({@code -Dslums.navTrace}), since it costs memory on every watched bot.
 *
 * <p>1.6.2 ships the two switches that follow this class ({@code -Dslums.killcalib} and {@code -Dslums.inspect}) on for
 * live as well, plus {@code -Dslums.navTrace} (owner 2026-10-30, docs/release/1.6.2.conf); the remaining debug tools
 * stay off there because live runs neither the QA suite nor auto-QA.
 */
public final class DebugTools {

    public static final String PROPERTY = "slums.debugtools";

    private DebugTools() {
    }

    /** Whether the debug tools are on for this server. */
    public static boolean isEnabled() {
        String forced = System.getProperty(PROPERTY);
        if (forced != null) {
            return "true".equalsIgnoreCase(forced);
        }
        return QaSuite.isEnabled() || AutoQa.isEnabled();
    }

    /** The same rule for a set of -D properties (the unit test reads live's and the test server's command lines). */
    public static boolean enabledFor(Function<String, String> property) {
        String forced = property.apply(PROPERTY);
        if (forced != null) {
            return "true".equalsIgnoreCase(forced);
        }
        return QaSuite.enabledFor(property) || "true".equalsIgnoreCase(property.apply(AutoQa.PROPERTY));
    }

    /**
     * A debug feature with its own switch that follows this one unless forced: {@code -D<property>=true|false} wins,
     * else it is on exactly where the debug tools are (the test server), off by default on live - where 1.6.2 forces
     * it on for kill calibration ({@code slums.killcalib}) and the bot inspect endpoint ({@code slums.inspect}).
     */
    public static boolean featureOn(String property) {
        String forced = System.getProperty(property);
        if (forced != null) {
            return "true".equalsIgnoreCase(forced);
        }
        return isEnabled();
    }

    /** {@link #featureOn} for a set of -D properties (tests read live's and the test server's command lines). */
    public static boolean featureFor(String property, Function<String, String> props) {
        String forced = props.apply(property);
        if (forced != null) {
            return "true".equalsIgnoreCase(forced);
        }
        return enabledFor(props);
    }
}
