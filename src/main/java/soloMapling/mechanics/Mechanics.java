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

    /**
     * Henesys PQ's own switch, on top of {@code -Dslums.mechanics}: row {@code flag:} value of {@code content/hpq.yaml}.
     * A row per switch keeps the hill off while the towers play, and the lobby reads it as {@code
     * -Dslums.mechanics.hpq.lobby} on top.
     */
    public static final String HPQ_FLAG = "slums.mechanics.hpq";

    /**
     * Cafe PQ's own switch, shared by its six rows: the six areas are one loop behind six doors (the Computer on
     * 193000000 asks which area, {@code scripts/npc/1052013.js:44-56}), so a server that can play one can play them all,
     * and a per-area switch would only make it possible to be half on. Each area still has its own lobby switch
     * ({@code slums.mechanics.cafepqN.lobby}) and its own {@code .runs} count, which is the knob that actually differs.
     */
    public static final String CAFE_FLAG = "slums.mechanics.cafepq";

    /** Pirate PQ's own switch ({@code -Dslums.mechanics.piratepq}): one PQ, one switch. */
    public static final String PIRATE_FLAG = "slums.mechanics.piratepq";

    /** Treasure PQ's own switch ({@code -Dslums.mechanics.treasurepq}). */
    public static final String TREASURE_FLAG = "slums.mechanics.treasurepq";

    /** DelliBattle's own switch ({@code -Dslums.mechanics.dellibattle}). */
    public static final String DELLI_FLAG = "slums.mechanics.dellibattle";

    /** Magatia PQ's own switch ({@code -Dslums.mechanics.magatiapq}). */
    public static final String MAGATIA_FLAG = "slums.mechanics.magatiapq";

    /**
     * Amoria PQ's bot-membership switch: {@code -Dslums.mechanics.amoriapq.botcount} (default <b>{@code false}</b>).
     *
     * <p>Amoria's event and its two host NPCs skip bots in every count - {@code scripts/event/AmoriaPQ.js:105} says why:
     * "bots cannot climb the puzzle platforms nor hold the stage items, so they never count as party members". That
     * judgement is right about the puzzle and wrong about the party: a party of three humans and three bots cannot start
     * the quest because the eligibility list comes back empty. With this switch on, {@code getEligibleParty} counts a bot
     * as a member, so a bot's presence satisfies the party-size arithmetic; the three counters that decide what the party
     * must physically do are untouched (see {@code AmoriaBotCountTest}, which pins both halves).
     *
     * <p>It is deliberately NOT part of {@link #FLAG}: it changes a live party quest's admission for real players too, so
     * it is a server operator's decision, off unless asked for. Scripts read it through
     * {@code Java.type('soloMapling.mechanics.Mechanics').amoriaBotCount()} - the same host-class access 314 scripts
     * already use.
     */
    public static final String AMORIA_BOTCOUNT_FLAG = "slums.mechanics.amoriapq.botcount";

    /** Whether Amoria PQ counts a bot as a party member. Off by default; see {@link #AMORIA_BOTCOUNT_FLAG}. */
    public static boolean amoriaBotCount() {
        return Boolean.getBoolean(AMORIA_BOTCOUNT_FLAG);
    }

    /** Ellin PQ's own switch ({@code -Dslums.mechanics.ellinpq}). */
    public static final String ELLIN_FLAG = "slums.mechanics.ellinpq";

    /** LudiMaze PQ's own switch: one PQ, one row, one flag. */
    public static final String LUDIMAZE_FLAG = "slums.mechanics.ludimazepq";

    /**
     * The run chatter switch: {@code -Dslums.mechanics.chatter} (default <b>{@code true}</b>, the owner asked for it,
     * 2026-11-03: "outcomes once bots exit out they say things like woo we beat or dang we tried but failed"). When a
     * run ends, the bots that played it leave themselves a win or a loss line to say on their own tick
     * ({@link RunChatter}). It is chat only; {@code -Dslums.mechanics.chatter=false} records nothing and the bots end a
     * run as silently as they did before.
     */
    public static final String CHATTER_FLAG = "slums.mechanics.chatter";

    /**
     * The real-run quota: {@code -Dslums.mechanics.realruns=N} (ticket 164, phase 2 of {@code SIMULATED-RUNS-PLAN.md}). How
     * many bot-only runs may play for real at once, world-wide. <b>Unset, empty, unparsable or negative: unlimited</b>,
     * which is today's behaviour exactly (every bot-only run holds its bots and plays); {@code 0} admits none. A run with a
     * real player in it, and a GM's or QA's staged run, never counts against it and is never refused ({@link RealRunPool}).
     */
    public static final String REAL_RUNS_FLAG = "slums.mechanics.realruns";

    /**
     * How many simulated runs may be in flight at once: {@code -Dslums.mechanics.simruns=N} (default {@value #DEFAULT_SIM_RUNS}).
     * A simulated run only exists where {@link #REAL_RUNS_FLAG} refused a bot-only run, so this is moot while that switch is
     * unset. {@code 0}: nothing is simulated, a refused run is simply not made.
     */
    public static final String SIM_RUNS_FLAG = "slums.mechanics.simruns";
    public static final int DEFAULT_SIM_RUNS = 8;

    /**
     * How many real bot-only runs of a content and tier a simulated run needs to be sampled from:
     * {@code -Dslums.mechanics.simfloor=N} (default {@value #DEFAULT_SIM_FLOOR}). Below it the run waits; it is never faked.
     */
    public static final String SIM_FLOOR_FLAG = "slums.mechanics.simfloor";
    public static final int DEFAULT_SIM_FLOOR = 5;
    public static final String DIR_PROPERTY = "slums.mechanics.dir";
    public static final String DEFAULT_DIR = "src/main/java/soloMapling/mechanics/content";

    private Mechanics() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    /** True unless {@code -Dslums.mechanics.chatter=false}: bots say something about the run they just left. */
    public static boolean chatterEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(CHATTER_FLAG, "true").trim());
    }

    /** The real-run quota, or a negative number for unlimited ({@link #REAL_RUNS_FLAG}). Read on every call. */
    public static int realRunCapacity() {
        String raw = System.getProperty(REAL_RUNS_FLAG);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The most simulated runs in flight ({@link #SIM_RUNS_FLAG}); never negative. */
    public static int simulatedRunCapacity() {
        return nonNegative(SIM_RUNS_FLAG, DEFAULT_SIM_RUNS);
    }

    /** The real runs a simulated one needs to sample ({@link #SIM_FLOOR_FLAG}); at least 1, so nothing is invented from nothing. */
    public static int simulatedFloor() {
        return Math.max(1, nonNegative(SIM_FLOOR_FLAG, DEFAULT_SIM_FLOOR));
    }

    private static int nonNegative(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** True when a real boss expedition may arm a run. */
    public static boolean bossesEnabled() {
        return Boolean.getBoolean(BOSS_FLAG);
    }

    /**
     * True when this boss row may arm a run: {@link #bossesEnabled()} plus, for a row that names one, its own flag
     * ({@code flag: slums.mechanics.nwill}). The boss lane keeps its own switch rather than {@link #FLAG} so a server can
     * let bots fight expeditions without handing them the party quests, and a row's own flag is the second key: the four
     * Normal-tier fights share one script shape and one switch would be all or nothing.
     */
    public static boolean bossEnabledFor(MechanicsModel.Content content) {
        // one implementation of "and the row's own flag, if it names one": rowFlagOn is that fact for every kind
        return bossesEnabled() && rowFlagOn(content);
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

    /**
     * True when a row that names its own switch ({@code flag: slums.chaos.zakum}) is allowed to arm: the flag is opt-in
     * on top of whatever lane switch the row's kind already needs. A row without a flag is always allowed.
     */
    public static boolean rowFlagOn(MechanicsModel.Content content) {
        if (content == null || content.flag() == null || content.flag().isBlank()) {
            return true;
        }
        return Boolean.getBoolean(content.flag());
    }

    public static String dataDir() {
        return System.getProperty(DIR_PROPERTY, DEFAULT_DIR);
    }
}
