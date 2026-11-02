package soloMapling.mechanics;

/**
 * Ticket 130 (1.7.0 headline "Bots play the mechanics"): bots run party quests, bosses and other content that needs
 * logic (stage puzzles, coupons and passes, platforms and ropes, NPC questions, jump puzzles, boss phases).
 *
 * <p>Everything in this package is switched off behind {@code -Dslums.mechanics} (default {@code false}). With it
 * on: {@code EventManager.startInstance} hands a party with bots or owned alts that starts an event with stage data
 * (Kerning PQ) to {@link MechanicsRuns}, which drives the bots through every stage; {@code !mechanics} (GM 3) stages
 * runs, and {@link KpqLobby} lets bot-only parties start KPQ themselves (its own switch, {@code
 * -Dslums.mechanics.lobby}). Design: {@code docs/tickets/130-bots-play-mechanics.md}.
 *
 * <p>Stage data (one YAML file per piece of content) lives in {@value #DEFAULT_DIR}, named in its {@code index.txt}
 * and read through {@code soloMapling.DataFiles} so a jar-only install finds it ({@link StageData#load()}); override
 * with {@code -Dslums.mechanics.dir} (a folder on disk, listed).
 */
public final class Mechanics {

    /** The switch. Off unless the server is started with {@code -Dslums.mechanics=true}. */
    public static final String FLAG = "slums.mechanics";

    /**
     * The boss switch: {@code -Dslums.mechanics.boss} (default {@code false}). Its own switch, not a value of
     * {@link #FLAG}: a server may let bots fight real boss expeditions (Zakum, later Horntail) without handing them
     * the party quests.
     */
    public static final String BOSS_FLAG = "slums.mechanics.boss";

    /**
     * The Orbis PQ switch: {@code -Dslums.mechanics.opq} (default {@code false}). OPQ is its own row ({@code
     * content/opq.yaml}, {@code flag: slums.mechanics.opq}) and its own switch, so the row can stay off while KPQ
     * plays: the lobby, admission and stage code is one per quest, and OPQ is the newest and least walked of them.
     */
    public static final String OPQ_FLAG = "slums.mechanics.opq";

    /** Ludi PQ's own switch, on top of {@code -Dslums.mechanics}: Row {@code flag:} value of {@code content/lpq.yaml}. */
    public static final String LPQ_FLAG = "slums.mechanics.lpq";
    public static final String DIR_PROPERTY = "slums.mechanics.dir";
    public static final String DEFAULT_DIR = "src/main/java/soloMapling/mechanics/content";

    private Mechanics() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    /** True when a real boss expedition may arm a run. */
    public static boolean bossesEnabled() {
        return Boolean.getBoolean(BOSS_FLAG);
    }

    /**
     * True when this content may arm a run: {@code -Dslums.mechanics} plus, for a row that names one, its own flag
     * ({@link MechanicsModel.Content#flag()}: {@code flag: slums.mechanics.opq}). Boss rows keep their own switch
     * ({@link #bossesEnabled()}) and are asked before this.
     */
    public static boolean enabledFor(MechanicsModel.Content content) {
        if (content == null || content.flag() == null || content.flag().isBlank()) {
            return enabled();
        }
        return enabled() && Boolean.getBoolean(content.flag());
    }

    public static String dataDir() {
        return System.getProperty(DIR_PROPERTY, DEFAULT_DIR);
    }
}
