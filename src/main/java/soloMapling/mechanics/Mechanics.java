package soloMapling.mechanics;

/**
 * Ticket 130 (1.7.0 headline "Bots play the mechanics"): bots run party quests, bosses and other content that needs
 * logic (stage puzzles, coupons and passes, platforms and ropes, NPC questions, jump puzzles, boss phases).
 *
 * <p><b>Scaffold only.</b> Everything in this package is switched off behind {@code -Dslums.mechanics} (default
 * {@code false}) and nothing in the running server calls it yet: no bot behaviour changes until a later phase wires
 * the orchestrator in. Design: {@code docs/tickets/130-bots-play-mechanics.md}.
 *
 * <p>Stage data (one YAML file per piece of content) lives in {@value #DEFAULT_DIR}, named in its {@code index.txt}
 * and read through {@code soloMapling.DataFiles} so a jar-only install finds it ({@link StageData#load()}); override
 * with {@code -Dslums.mechanics.dir} (a folder on disk, listed).
 */
public final class Mechanics {

    /** The switch. Off unless the server is started with {@code -Dslums.mechanics=true}. */
    public static final String FLAG = "slums.mechanics";
    public static final String DIR_PROPERTY = "slums.mechanics.dir";
    public static final String DEFAULT_DIR = "src/main/java/soloMapling/mechanics/content";

    private Mechanics() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    public static String dataDir() {
        return System.getProperty(DIR_PROPERTY, DEFAULT_DIR);
    }
}
