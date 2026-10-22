package soloMapling.ArtificialPlayer.BotConvo;

/**
 * Ticket 59's rollout switches: system properties {@code solomapling.convo.<name>}, read on every use so a GM's
 * {@code -D} change or a test takes effect at once. Everything defaults on; {@code forceBank} defaults off.
 * <ul>
 *   <li>{@code contract}, {@code sense}, {@code card}, {@code persona}, {@code styler}, {@code timing}, {@code bank},
 *   {@code threads}, {@code router}, {@code quality}: one per part (docs/tickets/59, section 10)</li>
 *   <li>{@code forceBank}: chat replies from the bank and scripts only (no LLM), to compare (!qa cv-7)</li>
 * </ul>
 */
public final class ConvoSwitches {

    private ConvoSwitches() {
    }

    /** Whether part {@code name} is on (default true). */
    public static boolean on(String name) {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.convo." + name, "true"));
    }

    /** Whether the LLM is forced off for chat (default false). */
    public static boolean forceBank() {
        return "true".equalsIgnoreCase(System.getProperty("solomapling.convo.forceBank", "false"));
    }

    /** A number under {@code solomapling.convo.<name>}. */
    public static double num(String name, double fallback) {
        return prop("solomapling.convo." + name, fallback);
    }

    /** A number under a full property name. */
    public static double prop(String property, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(property, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A string under {@code solomapling.convo.<name>}. */
    public static String str(String name, String fallback) {
        String v = System.getProperty("solomapling.convo." + name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
