package soloMapling.ArtificialPlayer.BotConvo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ticket 156 (1.6.1, owner 2026-10-30 "fix the bots state of mind"): one snapshot of what a bot really knows when it
 * answers a player, and a checker that holds every reply to it.
 * <ul>
 *   <li><b>Snapshot</b> ({@link Snapshot}): built once per reply (no per-tick cost) by
 *   {@code BotStateOfMind.capture}: where the bot is (map, region, town or field, the monsters on it), what it is
 *   doing and planning ({@code !botwhy}'s next step and destination), its level and job, who it talks to (met
 *   before or not, the notes it holds, their real level and job), the chat's last lines, and an open trade offer.</li>
 *   <li><b>Prompt</b> ({@link #prompt}): the snapshot in a few lines plus the rule "only claim places, monsters,
 *   levels, history and prices that are in here".</li>
 *   <li><b>Checker</b> ({@link #check}): a reply (model or scripted) is rejected when it names a map/region or monster
 *   the snapshot does not hold (b099), says "again"/"remember"/"you came back" to someone the bot never met or
 *   invents their goals (b100), puts the bot somewhere or at a level it is not, or grinding in town (b101), or
 *   repeats its asking price while the player's offer is open (b102). The caller regenerates once, then falls back
 *   to a safe scripted line.</li>
 * </ul>
 * Switches: {@code -Dsolomapling.convo.stateOfMind=false} leaves the snapshot out of the prompt,
 * {@code -Dsolomapling.convo.grounding=false} turns the checker off (both default on). Rejects are logged at DEBUG
 * with the reason and counted ({@link #stats}) for chat quality's "grounding rejects".
 */
public final class StateOfMind {

    private static final Logger log = LoggerFactory.getLogger(StateOfMind.class);

    private StateOfMind() {
    }

    // ── switches ──

    /** Whether the snapshot goes into the chat model's prompt (default on). */
    public static boolean promptOn() {
        return ConvoSwitches.on("stateOfMind");
    }

    /** Whether replies are checked against the snapshot (default on). */
    public static boolean checkOn() {
        return ConvoSwitches.on("grounding");
    }

    // ── the snapshot ──

    /** A place: its map id, the name players see, and its region (the town it belongs to). */
    public record Place(int id, String name, int region) {
    }

    /** An open trade offer: the bot's asking price for an item and the player's answer (an amount, or -1: "no"). */
    public record Offer(String item, long asking, long offered) {
        public boolean refusal() {
            return offered <= 0;
        }
    }

    /** What the bot really knows while it answers. Build with {@link #builder}. */
    public record Snapshot(String botName, int botLevel, String botJob, Place here, List<String> mobsHere,
                           String activity, String nextStep, Place heading, List<String> goals,
                           List<Place> knownPlaces, List<String> knownMobs, boolean playerIsBot, String playerName,
                           int playerLevel, String playerJob, boolean met, List<String> notes,
                           List<String> lastLines, List<String> playerLines, Offer offer) {

        /** In a town: nothing to fight on this map. */
        public boolean town() {
            return mobsHere.isEmpty();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fills a {@link Snapshot}; everything is optional. */
    public static final class Builder {
        private String botName = "";
        private int botLevel;
        private String botJob = "beginner";
        private Place here;
        private final List<String> mobsHere = new ArrayList<>();
        private String activity;
        private String nextStep;
        private Place heading;
        private final List<String> goals = new ArrayList<>();
        private final List<Place> knownPlaces = new ArrayList<>();
        private final List<String> knownMobs = new ArrayList<>();
        private boolean playerIsBot;
        private String playerName = "";
        private int playerLevel;
        private String playerJob = "beginner";
        private boolean met;
        private final List<String> notes = new ArrayList<>();
        private final List<String> lastLines = new ArrayList<>();
        private final List<String> playerLines = new ArrayList<>();
        private Offer offer;

        public Builder bot(String name, int level, String job) {
            botName = name == null ? "" : name;
            botLevel = level;
            botJob = job == null ? "beginner" : job;
            return this;
        }

        public Builder here(Place place, List<String> mobs) {
            here = place;
            addNames(mobsHere, mobs);
            return this;
        }

        public Builder doing(String activityPhrase, String step) {
            activity = activityPhrase;
            nextStep = step;
            return this;
        }

        public Builder heading(Place place, List<String> mobs) {
            heading = place;
            addNames(knownMobs, mobs);
            return this;
        }

        public Builder goal(String goal) {
            if (goal != null && !goal.isBlank()) {
                goals.add(goal);
            }
            return this;
        }

        /** A place the bot may talk about (a training spot for its level, a place the game's answer names). */
        public Builder knows(Place place, List<String> mobs) {
            if (place != null) {
                knownPlaces.add(place);
            }
            addNames(knownMobs, mobs);
            return this;
        }

        public Builder player(String name, int level, String job, boolean bot) {
            playerName = name == null ? "" : name;
            playerLevel = level;
            playerJob = job == null ? "beginner" : job;
            playerIsBot = bot;
            return this;
        }

        public Builder met(boolean before, List<String> remembered) {
            met = before;
            if (remembered != null) {
                notes.addAll(remembered);
            }
            return this;
        }

        /** A line of this chat, oldest first; {@code byPlayer} for the player's own lines. */
        public Builder line(String text, boolean byPlayer) {
            if (text != null && !text.isBlank()) {
                lastLines.add(text);
                if (byPlayer) {
                    playerLines.add(text);
                }
            }
            return this;
        }

        public Builder offer(Offer open) {
            offer = open;
            return this;
        }

        public Snapshot build() {
            List<String> last = lastLines.size() > 6 ? lastLines.subList(lastLines.size() - 6, lastLines.size())
                    : lastLines;
            return new Snapshot(botName, botLevel, botJob, here, List.copyOf(new LinkedHashSet<>(mobsHere)), activity,
                    nextStep, heading, List.copyOf(goals), List.copyOf(knownPlaces),
                    List.copyOf(new LinkedHashSet<>(knownMobs)), playerIsBot, playerName, playerLevel, playerJob, met,
                    List.copyOf(notes), List.copyOf(last), List.copyOf(playerLines), offer);
        }

        private static void addNames(List<String> into, List<String> names) {
            if (names == null) {
                return;
            }
            for (String n : names) {
                if (n != null && !n.isBlank()) {
                    into.add(n.trim());
                }
            }
        }
    }

    // ── regions ──

    private static final IntUnaryOperator NO_REGION = id -> -1;
    private static volatile IntUnaryOperator regionOf = NO_REGION;

    /** The region (town) lookup the game installs ({@code MapMobIndex.returnMapId} based); -1 = unknown. */
    public static void useRegions(IntUnaryOperator lookup) {
        regionOf = lookup == null ? NO_REGION : lookup;
    }

    /** The region of a map: itself for a town or an alias, else the lookup's answer (or -1). */
    static int region(int mapId) {
        if (TOWNS.contains(mapId)) {
            return mapId;
        }
        try {
            int r = regionOf.applyAsInt(mapId);
            if (r == 999_999_999) {
                return mapId; // no return map: it is a town of its own
            }
            return r <= 0 ? -1 : r;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Towns and hubs everyone knows: naming one in passing is world knowledge, not a claim. */
    static final Set<Integer> TOWNS = Set.copyOf(new LinkedHashSet<>(ConvoEntities.MAP_ALIASES.values()).stream()
            .filter(id -> id != 105050000).toList()); // Ant Tunnel is a dungeon, not a town

    /** Aliases that name an activity, not a place ("kpq later" is a plan, not a claim to be in Kerning). */
    private static final Set<String> ACTIVITY_ALIASES = Set.of("kpq", "lpq", "opq");

    // ── the prompt ──

    /** The snapshot as prompt text: short, because every line costs time on the 2-slot local model. */
    public static String prompt(Snapshot s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("\nYOUR STATE (the truth right now). Only claim places, monsters, levels, "
                + "history and prices that are in here; for anything else say idk or stay vague. ");
        b.append("You: level ").append(s.botLevel()).append(' ').append(s.botJob());
        if (s.here() != null) {
            b.append(", on ").append(s.here().name());
            b.append(s.town() ? " (a town: no monsters here, you are not grinding right now)"
                    : "; monsters here: " + String.join(", ", s.mobsHere()));
        }
        if (s.activity() != null && !s.activity().isBlank()) {
            b.append("; doing: ").append(s.activity());
        }
        b.append(". ");
        if (s.nextStep() != null && !s.nextStep().isBlank()) {
            b.append("Next: ").append(s.nextStep());
            if (s.heading() != null) {
                b.append(" (").append(s.heading().name()).append(')');
            }
            b.append(". ");
        }
        if (!s.goals().isEmpty()) {
            b.append("Goals: ").append(String.join("; ", s.goals().subList(0, Math.min(2, s.goals().size()))))
                    .append(". ");
        }
        List<String> places = new ArrayList<>();
        for (Place p : s.knownPlaces()) {
            if (p.name() != null && !places.contains(p.name())) {
                places.add(p.name());
            }
        }
        if (!places.isEmpty()) {
            b.append("Other places you know: ").append(String.join(", ", places));
            if (!s.knownMobs().isEmpty()) {
                b.append(" (monsters there: ").append(String.join(", ", s.knownMobs().subList(0,
                        Math.min(6, s.knownMobs().size())))).append(')');
            }
            b.append(". ");
        }
        b.append(s.playerName()).append(": level ").append(s.playerLevel()).append(' ').append(s.playerJob())
                .append(s.playerIsBot() ? ", another player you see around" : "").append(". ");
        if (s.met()) {
            b.append("You have met them before");
            if (!s.notes().isEmpty()) {
                b.append("; you remember: ").append(String.join("; ", s.notes().subList(0,
                        Math.min(3, s.notes().size()))));
            }
            b.append(". ");
        } else {
            b.append("You have NEVER met them: no \"again\", \"remember\", \"last time\" or \"you came back\", and do "
                    + "not guess their goals or level. ");
        }
        if (s.offer() != null) {
            Offer o = s.offer();
            b.append("Open offer: you asked ").append(price(o.asking())).append(" for ").append(o.item()).append(", ")
                    .append(o.refusal() ? "they said it is too much" : "they offered " + price(o.offered()))
                    .append(". Answer their offer (take it, counter or say no and why); never just repeat your price. ");
        }
        return b.toString();
    }

    private static String price(long v) {
        try {
            return soloMapling.Economy.PriceBook.shorthand(v);
        } catch (RuntimeException | LinkageError e) {
            return String.valueOf(v);
        }
    }

    // ── the checker ──

    /** Why a reply was rejected. */
    public enum Reason { PLACE, MOB, FAMILIAR, LEVEL, LOCATION, ACTIVITY, PRICE }

    /** The checker's answer: ok, or the reason and what it caught. */
    public record Verdict(Reason reason, String detail) {
        public static final Verdict OK = new Verdict(null, null);

        public boolean ok() {
            return reason == null;
        }

        @Override
        public String toString() {
            return ok() ? "ok" : reason.name().toLowerCase(Locale.ROOT) + ": " + detail;
        }
    }

    // single-word map names that are just words ("attic", "light", "tunnel")
    private static final Set<String> GENERIC_MAP_WORDS = Set.of("attic", "bedroom", "cafeteria", "chimney", "darkness",
            "direction", "harmony", "hideout", "library", "light", "marketplace", "station", "tunnel", "snowball",
            "galley", "forecastle", "penthouse", "pilothouse");
    // multi-word map names that are everyday phrases
    private static final Set<String> GENERIC_MAP_PHRASES = Set.of("dead end", "in flight", "second floor",
            "ground floor", "living room", "front yard", "back yard", "main room", "secret room", "small park",
            "safety zone", "off limits", "lost time", "three doors", "critical error", "defeat monsters",
            "another door", "another path", "another entrance", "training room", "town hall", "general store",
            "department store", "gm room", "ox quiz", "forbidden time", "twisted time", "unknown space",
            "unseen danger", "burning heat", "cold wind", "hot sand", "warm sand", "warm shade", "windy place",
            "starting place", "secure location", "closed area", "prohibited area", "overlooked area", "transfer area",
            "sunset sky", "deep waters", "shallow sea", "small well", "small bridge", "small alley", "small attic",
            "dark cave", "ice cave", "hidden cave", "clang clang", "lorang lorang", "explorer video", "wedding hall",
            "conference room", "audience room", "weird room", "secret grove", "dangerous croco", "regular sauna",
            "vip sauna", "free market entrance", "hidden street", "victoria road");

    /**
     * Monster families players name by their last word ("golems", "boars", "shrooms"). Only these single words are
     * claims: most one-word monster names are plain words ("will", "night", "lucky", "boss").
     */
    static final Set<String> FAMILIES = Set.of("boar", "golem", "mushroom", "shroom", "slime", "snail", "stump",
            "drake", "wraith", "skeleton", "zombie", "lupin", "ligator", "croco", "yeti", "pepe", "werewolf",
            "lycanthrope", "sentinel", "octopus", "kobold", "ratz", "wyvern", "necki", "jr necki", "evil eye",
            "curse eye", "cold eye", "surgeon eye", "bubbling", "pixie", "lorang", "tortie", "panda", "sakura",
            "cellion", "lioner", "grupin", "chronos", "platoon", "trixter", "drumming bunny", "sand rat", "scorpion",
            "jr cellion", "hector", "white fang", "leatty", "dark leatty", "stirge", "jr wraith", "buffy", "tick");

    private static final Map<String, String> FAMILY_SYNONYMS = Map.of("shroom", "mushroom", "mushroom", "shroom");

    /** A name a reply mentions. */
    record Mention(String name, int id, boolean mob, int start, int end) {
    }

    private static final Pattern FUTURE = Pattern.compile("\\b(later|gonna|going|goin|heading|headed|head|omw|after|"
            + "tmr|tomorrow|tmrw|next|then|wanna|want|should|try|maybe|prob|probably|might|thinking|will|ill|plan|"
            + "planning|soon|tonight|was|used|yesterday|earlier|before|last)\\b");
    private static final Pattern SELF_CUE = Pattern.compile("\\b(im|i am|just|still|currently|rn|grinding|grindin|"
            + "grind|training|train|hunting|farming|chilling|chillin|camping|afk|sitting|stuck|here|been|leveling|"
            + "lvling|sniping)\\b");
    private static final Pattern SECOND = Pattern.compile("\\b(u|you|ya|ur|your|youre|yall)\\b");

    private static final Pattern GRIND_CLAIM = Pattern.compile("^\\s*(?:(?:im|i am|still|just|been|currently|rn|ya|"
            + "yea|yeah|yh|lol|lmao|nah|sup|ok|tbh|meh|eh|oh|kinda|out here|over here)\\s+)*"
            + "(grinding|grindin|sniping|farming|hunting|leveling|lvling|training)\\b"
            + "(?!\\s+(is|was|sucks|sux|gets|takes|later|after|tmr|tomorrow|soon))");
    private static final Pattern NOT_NOW = Pattern.compile("\\b(later|after|soon|gonna|tmr|tomorrow|was|earlier|"
            + "yesterday|before|then|next)\\b");

    private static final String NOT_UNIT = "(?!\\s*(?:%|(?:k|m|mil|b|min|mins|minute|minutes|sec|secs|hr|hrs|hour|hours|"
            + "percent|x|meso|mesos|pot|pots|times|ppl|people|of|more|away|day|days|yr|yrs|year|years|am|pm|ch|"
            + "channel|slots?|hp|mp|str|dex|int|luk|att|matk|wa|ma|stars?)\\b))";
    private static final Pattern SELF_LEVEL = Pattern.compile("\\b(?:im|i am|my (?:lvl|level|lv)(?: is)?)\\s+"
            + "((?:(?:only|almost|nearly|like|just|stuck at|at|lvl|level|lv|now|finally|already)\\s+)*)(\\d{1,3})\\b"
            + NOT_UNIT);
    private static final Pattern SELF_HIT = Pattern.compile("(?<!after |when |once |til |till |until |before |if |to )"
            + "\\b(?:just hit|finally hit|i hit)\\s+(?:(?:lvl|level|lv)"
            + "\\s*)?(\\d{1,3})\\b" + NOT_UNIT);
    private static final Pattern THEIR_LEVEL = Pattern.compile("\\b(?:ur|your|youre|you are|u r|u are)\\s+(?:(?:only|"
            + "almost|like|already|still|now)\\s+)?(?:lvl|level|lv)\\s*(\\d{1,3})\\b");
    private static final Pattern GRATS = Pattern.compile("\\b(?:grats|gratz|congrats|gz|grz) on (?:(?:lvl|level|lv)"
            + "\\s*)?(\\d{1,3})\\b" + NOT_UNIT);
    private static final Pattern THEIR_GOAL = Pattern.compile("\\b(?:grinding|grindin|grind|push|pushing|leveling|"
            + "lvling|road|goin|going)\\s+(to|toward|towards|til|till|until|at)\\s+(?:(?:lvl|level|lv)\\s*)?(\\d{1,3})"
            + "\\b" + NOT_UNIT);

    private static final List<Pattern> FAMILIAR = List.of(
            Pattern.compile("^\\W*(?:(?:oh|o|ah|ayy*|well)\\W+)?(?:yo+|hey+|hi+|hello|sup|oi|heya|hiya|ello|howdy)\\b"
                    + "[\\s,!.~^]*(?:(?:u|you|ya)\\s+)?again\\b"),
            Pattern.compile("\\b(?:u|you|ya)\\s+again\\b"),
            Pattern.compile("\\b(?:good|nice|great) (?:to )?see (?:u|you|ya)\\b"),
            Pattern.compile("\\bsee (?:u|you|ya) again\\b"),
            Pattern.compile("\\b(?:u|you|ya)\\s+(?:came|come|r|are|is|re)\\s+back\\b"),
            Pattern.compile("\\byoure back\\b|\\bwelcome back\\b|\\bback again\\b"),
            Pattern.compile("\\bremember me\\b|\\b(?:u|you) remember\\b|\\bi remember (?:u|you|ur|your|when)\\b"
                    + "|\\bremember when\\b"),
            Pattern.compile("\\blast time\\b|\\blong time no see\\b|\\bmissed (?:u|you|ya)\\b|\\bbeen a while\\b"),
            Pattern.compile("\\b(?:u|you|ya)\\s+still\\b(?!\\s+(?:there|here|around|online|afk|awake|alive|ok|okay|"
                    + "good|busy|on|mad|lagging)\\b)"),
            Pattern.compile("\\bstill\\s+(?:on that|grinding|grindin|thinking|working on|going for|in for|saving|trying"
                    + "|doing that|goin for)\\b(?=[^.!]*\\?)"),
            Pattern.compile("\\bhow(?:s| is| was)\\s+(?:ur|your)\\s+[^?.!]{0,25}\\b(?:goal|wedding|plan)\\b"),
            Pattern.compile("\\bour (?:wedding|date|deal|chat|talk)\\b|\\b(?:as|like) (?:u|you) said\\b"
                    + "|\\b(?:u|you) (?:told|said to) me\\b"));

    private static final Pattern ENGAGE = Pattern.compile("\\b(ok|okay|k|kk|deal|sure|fine|can do|could do|would do|"
            + "id do|how about|hbu|meet|lowest|last price|final|firm|cant|cannot|no|nah|nope|lower|cheaper|only|"
            + "too low|too much|best i can|take it|sold|dont need|no need|worth|fair|already|counter|go down|"
            + "go lower|alright|bet)\\b");

    /**
     * Whether {@code reply} only claims what {@code s} holds. Null or a blank reply, or no snapshot: ok. A reply to
     * another bot skips the familiarity rule (bots greet each other loosely).
     */
    public static Verdict check(String reply, Snapshot s) {
        if (reply == null || reply.isBlank() || s == null) {
            return Verdict.OK;
        }
        String text = words(reply);
        if (text.isEmpty()) {
            return Verdict.OK;
        }
        Verdict v = familiar(text, s);
        if (!v.ok()) {
            return v;
        }
        v = levels(text, s);
        if (!v.ok()) {
            return v;
        }
        if (s.here() != null) { // without a known map (a staged or test bot) there is nothing to hold places to
            v = places(text, s);
            if (!v.ok()) {
                return v;
            }
            v = grindingInTown(text, s);
            if (!v.ok()) {
                return v;
            }
        }
        return offer(reply, text, s);
    }

    /** Lower case, apostrophes dropped ("i'm" -> "im"), other punctuation kept for clauses. */
    static String words(String line) {
        return line.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "")
                .replace("{player}", " ").replaceAll("\\s+", " ").trim();
    }

    private static Verdict familiar(String text, Snapshot s) {
        if (s.met() || s.playerIsBot()) {
            return Verdict.OK;
        }
        for (Pattern p : FAMILIAR) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return new Verdict(Reason.FAMILIAR, "\"" + m.group().trim() + "\" to someone it never met");
            }
        }
        return Verdict.OK;
    }

    private static Verdict levels(String text, Snapshot s) {
        if (s.botLevel() > 0) {
            Matcher m = SELF_LEVEL.matcher(text);
            while (m.find()) {
                int n = Integer.parseInt(m.group(2));
                String mods = m.group(1) == null ? "" : m.group(1);
                if (n < 1 || n > 250) {
                    continue;
                }
                boolean almost = mods.contains("almost") || mods.contains("nearly");
                boolean fits = almost ? n > s.botLevel() && n - s.botLevel() <= 5
                        : mods.contains("like") ? Math.abs(n - s.botLevel()) <= 2 : n == s.botLevel();
                if (!fits) {
                    return new Verdict(Reason.LEVEL, "says level " + n + ", is " + s.botLevel());
                }
            }
            m = SELF_HIT.matcher(text);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= 250 && n != s.botLevel()) {
                    return new Verdict(Reason.LEVEL, "says it hit " + n + ", is " + s.botLevel());
                }
            }
        }
        if (s.playerLevel() > 0) {
            Matcher m = THEIR_LEVEL.matcher(text);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n != s.playerLevel()) {
                    return new Verdict(Reason.LEVEL, "calls them level " + n + ", they are " + s.playerLevel());
                }
            }
            m = GRATS.matcher(text);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n != s.playerLevel()) {
                    return new Verdict(Reason.LEVEL, "grats on " + n + ", they are " + s.playerLevel());
                }
            }
            if (SECOND.matcher(text).find() || text.contains("?")) {
                m = THEIR_GOAL.matcher(text);
                while (m.find()) {
                    int n = Integer.parseInt(m.group(2));
                    boolean at = "at".equals(m.group(1));
                    if (at ? n != s.playerLevel() : n <= s.playerLevel()) {
                        return new Verdict(Reason.LEVEL, "their grind " + m.group(1) + " " + n + ", they are "
                                + s.playerLevel());
                    }
                }
            }
        }
        return Verdict.OK;
    }

    private static Verdict places(String text, Snapshot s) {
        List<Mention> found = mentions(text);
        if (found.isEmpty()) {
            return Verdict.OK;
        }
        Set<String> echoed = new java.util.HashSet<>();
        for (String l : s.playerLines()) {
            for (Mention m : mentions(words(l))) {
                echoed.add(m.mob() ? family(m.name()) : m.name());
                if (!m.mob() && m.id() > 0) {
                    echoed.add("#" + m.id());
                    echoed.add("@" + region(m.id()));
                }
            }
        }
        for (Mention m : found) {
            if (m.mob()) {
                if (!mobKnown(m.name(), s) && !echoed.contains(family(m.name()))) {
                    return new Verdict(Reason.MOB, "\"" + m.name() + "\" is not on its map or in its plans");
                }
                continue;
            }
            int region = region(m.id());
            boolean self = selfClaim(text, m);
            if (self) {
                if (s.here() == null) {
                    continue;
                }
                boolean there = m.id() == s.here().id() || region > 0 && region == s.here().region()
                        || nameMatch(m.name(), s.here().name()) || fm(m.id()) && fm(s.here().id());
                if (!there) {
                    return new Verdict(Reason.LOCATION, "says it is at \"" + m.name() + "\", is on "
                            + s.here().name());
                }
                continue;
            }
            if (TOWNS.contains(m.id()) || m.id() == 910000000) {
                continue; // a town or the FM in passing: world knowledge
            }
            if (!placeKnown(m, region, s) && !echoed.contains("#" + m.id())
                    && !(region > 0 && echoed.contains("@" + region))) {
                return new Verdict(Reason.PLACE, "\"" + m.name() + "\" is not where it is, plans to go or knows");
            }
        }
        return Verdict.OK;
    }

    /** The Free Market and its rooms. */
    private static boolean fm(int mapId) {
        return mapId >= 910000000 && mapId <= 910000022;
    }

    private static boolean placeKnown(Mention m, int region, Snapshot s) {
        List<Place> all = new ArrayList<>(s.knownPlaces());
        if (s.here() != null) {
            all.add(s.here());
        }
        if (s.heading() != null) {
            all.add(s.heading());
        }
        for (Place p : all) {
            if (p.id() == m.id() || nameMatch(m.name(), p.name()) || region > 0 && region == p.region()) {
                return true;
            }
        }
        return false;
    }

    private static boolean nameMatch(String said, String place) {
        if (said == null || place == null) {
            return false;
        }
        String p = ConvoEntities.norm(place);
        return !p.isEmpty() && (p.equals(said) || p.contains(said) && said.length() >= 5);
    }

    /** Whether the words before a place put the bot there now ("im in", "grinding", "chillin in"). */
    static boolean selfClaim(String text, Mention m) {
        int start = Math.max(clauseStart(text, m.start()), 0);
        String before = text.substring(start, m.start());
        String[] w = before.trim().split("\\s+");
        String window = String.join(" ", java.util.Arrays.copyOfRange(w, Math.max(0, w.length - 5), w.length));
        String clause = text.substring(start, clauseEnd(text, m.end()));
        return SELF_CUE.matcher(window).find() && !SECOND.matcher(window).find() && !FUTURE.matcher(clause).find();
    }

    private static int clauseStart(String text, int at) {
        int best = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ',' || c == '.' || c == '!' || c == '?' || c == ';') {
                best = i + 1;
                break;
            }
        }
        int but = text.lastIndexOf(" but ", at);
        return Math.max(best, but < 0 ? 0 : but + 5);
    }

    private static int clauseEnd(String text, int at) {
        for (int i = at; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' || c == '.' || c == '!' || c == '?' || c == ';') {
                return i;
            }
        }
        return text.length();
    }

    private static boolean mobKnown(String said, Snapshot s) {
        String fam = family(said);
        List<String> all = new ArrayList<>(s.mobsHere());
        all.addAll(s.knownMobs());
        for (String mob : all) {
            String n = ConvoEntities.norm(mob);
            if (n.equals(said) || n.equals(fam)) {
                return true;
            }
            if (FAMILIES.contains(said) || FAMILIES.contains(fam)) {
                String syn = FAMILY_SYNONYMS.get(fam);
                if ((" " + n + " ").contains(" " + fam + " ") || n.endsWith(fam)
                        || syn != null && ((" " + n + " ").contains(" " + syn + " ") || n.endsWith(syn))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "wild boars" -> "wild boar", "golems" -> "golem" (a name as said, plural dropped). */
    static String family(String name) {
        if (name == null) {
            return "";
        }
        if (name.endsWith("es") && FAMILIES.contains(name.substring(0, name.length() - 2))) {
            return name.substring(0, name.length() - 2);
        }
        return name.endsWith("s") && !name.endsWith("ss") ? name.substring(0, name.length() - 1) : name;
    }

    /** The maps and monsters a line names (longest first; items consume their words so "x cap" is not a mob). */
    static List<Mention> mentions(String text) {
        String norm = ConvoEntities.norm(text);
        if (norm.isEmpty()) {
            return List.of();
        }
        // offsets of each normalised word inside `text` (for clause and self-claim windows)
        String[] words = norm.split(" ");
        int[] at = new int[words.length];
        int cursor = 0;
        String spaced = text.replaceAll("[^a-z0-9 ]", " ");
        for (int i = 0; i < words.length; i++) {
            int idx = spaced.indexOf(words[i], cursor);
            while (idx > 0 && Character.isLetterOrDigit(spaced.charAt(idx - 1))) {
                idx = spaced.indexOf(words[i], idx + 1);
            }
            at[i] = Math.max(idx, 0);
            cursor = at[i] + words[i].length();
        }
        ConvoEntities.Names n = ConvoEntities.names();
        boolean[] used = new boolean[words.length];
        List<Mention> out = new ArrayList<>();
        for (int len = Math.min(5, words.length); len >= 1; len--) {
            for (int i = 0; i + len <= words.length; i++) {
                if (anyUsed(used, i, len)) {
                    continue;
                }
                String gram = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + len));
                Mention hit = match(gram, len, n, words, i);
                if (hit == null) {
                    continue;
                }
                for (int k = i; k < i + len; k++) {
                    used[k] = true;
                }
                if (hit.id() == Integer.MIN_VALUE) {
                    continue; // an item name: consumed, not a claim
                }
                int end = at[i + len - 1] + words[i + len - 1].length();
                out.add(new Mention(hit.name(), hit.id(), hit.mob(), at[i], end));
            }
        }
        out.sort(java.util.Comparator.comparingInt(Mention::start));
        return out;
    }

    private static Mention match(String gram, int len, ConvoEntities.Names n, String[] words, int i) {
        if (len >= 2 && n != null && n.items().containsKey(gram) && !n.mobs().containsKey(gram)
                && !n.maps().containsKey(gram) && !ConvoEntities.MAP_ALIASES.containsKey(gram)) {
            return new Mention(gram, Integer.MIN_VALUE, false, 0, 0);
        }
        Integer alias = ConvoEntities.MAP_ALIASES.get(gram);
        if (alias != null) {
            if (ACTIVITY_ALIASES.contains(gram)) {
                return null;
            }
            if (gram.equals("sleepy")) { // "im sleepy" is a mood; "sleepy dungeon", "in sleepy" is the place
                String next = i + 1 < words.length ? words[i + 1] : "";
                String prev = i > 0 ? words[i - 1] : "";
                if (Set.of("dungeon", "dungeons", "dung").contains(next)) {
                    return new Mention("sleepy dungeon", 105050000, false, 0, 0); // the dungeon, not the town
                }
                if (!"wood".equals(next)
                        && !Set.of("in", "at", "to", "near", "grinding", "grindin", "from").contains(prev)) {
                    return null;
                }
            }
            return new Mention(gram, alias, false, 0, 0);
        }
        if (n == null) {
            return familyMention(gram, len);
        }
        if (!gram.startsWith("to ") && !gram.startsWith("the ") && !GENERIC_MAP_PHRASES.contains(gram)) {
            Integer map = n.maps().get(gram);
            if (map != null && (len > 1 || gram.length() >= 5 && !GENERIC_MAP_WORDS.contains(gram))) {
                return new Mention(gram, map, false, 0, 0);
            }
        }
        if (len > 1) {
            Integer mob = n.mobs().get(gram);
            String single = gram;
            if (mob == null && gram.endsWith("s")) {
                single = gram.substring(0, gram.length() - 1);
                mob = n.mobs().get(single);
            }
            if (mob != null) {
                return new Mention(single, mob, true, 0, 0);
            }
        }
        if (len > 1 && n.items().containsKey(gram)) {
            return new Mention(gram, Integer.MIN_VALUE, false, 0, 0);
        }
        return familyMention(gram, len);
    }

    private static Mention familyMention(String gram, int len) {
        if (len > 2) {
            return null;
        }
        String single = family(gram);
        if (FAMILIES.contains(gram) || FAMILIES.contains(single)) {
            return new Mention(FAMILIES.contains(gram) ? gram : single, -1, true, 0, 0);
        }
        return null;
    }

    private static boolean anyUsed(boolean[] used, int from, int len) {
        for (int k = from; k < from + len; k++) {
            if (used[k]) {
                return true;
            }
        }
        return false;
    }

    private static Verdict grindingInTown(String text, Snapshot s) {
        if (!s.town() || s.here() == null || "grinding".equals(s.activity())) {
            return Verdict.OK;
        }
        for (String clause : text.split("[,.!?;]|\\bbut\\b")) {
            Matcher m = GRIND_CLAIM.matcher(clause);
            if (m.find() && !NOT_NOW.matcher(clause).find()) {
                return new Verdict(Reason.ACTIVITY, "says \"" + clause.trim() + "\" standing in " + s.here().name());
            }
        }
        return Verdict.OK;
    }

    private static Verdict offer(String reply, String text, Snapshot s) {
        Offer o = s.offer();
        if (o == null || o.asking() <= 0) {
            return Verdict.OK;
        }
        List<Long> prices = soloMapling.Economy.HaggleBrain.prices(reply);
        boolean repeats = false;
        boolean counter = false;
        for (long p : prices) {
            if (near(p, o.asking())) {
                repeats = true;
            } else if (o.offered() <= 0 || !near(p, o.offered())) {
                counter = true;
            }
        }
        if (repeats && !counter && !ENGAGE.matcher(text).find()) {
            return new Verdict(Reason.PRICE, "repeats its price " + o.asking() + " over the open offer"
                    + (o.refusal() ? " (they said no)" : " of " + o.offered()));
        }
        return Verdict.OK;
    }

    private static boolean near(long a, long b) {
        return Math.abs(a - b) <= Math.max(1, b / 50);
    }

    // ── a safe line ──

    private static final List<String> NEUTRAL = List.of("lol ya", "hm idk tbh", "ya fr", "true lol", "haha ya", "idk lol");
    private static final Pattern ASKS_DOING = Pattern.compile("\\b(wat|what|wut|wht|whatcha|watcha|wyd|where)\\b.*\\b("
            + "doin|doing|up to|u at|you at|r u|are you|u train|you train|u grind)\\b|\\bwyd\\b|\\bwhere (r|are) (u|you)\\b");

    /**
     * A reply that the snapshot backs: the answer to an open offer, what it is doing when asked, else a short
     * neutral line. Always passes {@link #check}.
     */
    public static String safeLine(Snapshot s, String asked, java.util.Random rng) {
        if (s != null && s.offer() != null) {
            Offer o = s.offer();
            if (o.refusal()) {
                return price(o.asking()) + " is already cheap for " + o.item() + " tbh, cant go lower";
            }
            return o.offered() >= o.asking() ? "lol its only " + price(o.asking()) + " for " + o.item() + ", trade me"
                    : "nah cant do " + price(o.offered()) + " for " + o.item() + ", " + price(o.asking())
                    + " is my price";
        }
        if (s != null && s.here() != null && asked != null && ASKS_DOING.matcher(words(asked)).find()) {
            String place = s.here().name() == null ? "" : s.here().name().toLowerCase(Locale.ROOT);
            String line = s.town() ? "just chillin in " + place + " rn" : !s.mobsHere().isEmpty()
                    ? "killing " + s.mobsHere().get(0).toLowerCase(Locale.ROOT) + "s here" : "just here in " + place;
            if (check(line, s).ok()) {
                return line;
            }
        }
        if (asked != null && GREETING.matcher(words(asked)).find()) {
            return HELLO.get(rng.nextInt(HELLO.size()));
        }
        return NEUTRAL.get(rng.nextInt(NEUTRAL.size()));
    }

    private static final Pattern GREETING = Pattern.compile("^\\W*(hi+|hey+|yo+|sup|hello|hiya|heya|ello|howdy|"
            + "wassup|whats up|hows it going)\\b");
    private static final List<String> HELLO = List.of("hey", "yo", "hii", "sup", "hey hey");

    // ── counts ──

    private static final LongAdder checked = new LongAdder();
    private static final LongAdder rejected = new LongAdder();
    private static final LongAdder retried = new LongAdder();
    private static final LongAdder retryPassed = new LongAdder();
    private static final LongAdder fellBack = new LongAdder();
    private static final LongAdder checkNanos = new LongAdder();
    private static final LongAdder snapshots = new LongAdder();
    private static final LongAdder snapshotNanos = new LongAdder();
    private static final Map<Reason, LongAdder> byReason = new java.util.EnumMap<>(Reason.class);

    static {
        for (Reason r : Reason.values()) {
            byReason.put(r, new LongAdder());
        }
    }

    /**
     * {@link #check} plus the counting and the DEBUG log line ({@code who}: "Bot -> player", {@code source}: llm,
     * scripted, retry).
     */
    public static Verdict judge(String reply, Snapshot s, String who, String source) {
        long t0 = System.nanoTime();
        Verdict v = check(reply, s);
        checkNanos.add(System.nanoTime() - t0);
        checked.increment();
        if (!v.ok()) {
            rejected.increment();
            byReason.get(v.reason()).increment();
            log.debug("Grounding reject ({}) {} [{}]: \"{}\"", v, who, source, reply);
        }
        return v;
    }

    public static void snapshotBuilt(long nanos) {
        snapshots.increment();
        snapshotNanos.add(nanos);
    }

    public static void retried(boolean passed) {
        retried.increment();
        if (passed) {
            retryPassed.increment();
        }
    }

    public static void fellBack() {
        fellBack.increment();
    }

    /** Since boot: replies checked and rejected (by reason), retries, fallbacks, and the average cost in µs. */
    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        long c = checked.sum();
        long n = snapshots.sum();
        m.put("checked", c);
        m.put("rejected", rejected.sum());
        Map<String, Long> reasons = new LinkedHashMap<>();
        byReason.forEach((r, a) -> {
            if (a.sum() > 0) {
                reasons.put(r.name().toLowerCase(Locale.ROOT), a.sum());
            }
        });
        m.put("byReason", reasons);
        m.put("retried", retried.sum());
        m.put("retryPassed", retryPassed.sum());
        m.put("fellBack", fellBack.sum());
        m.put("avgCheckMicros", c == 0 ? 0 : checkNanos.sum() / c / 1000);
        m.put("snapshots", n);
        m.put("avgSnapshotMicros", n == 0 ? 0 : snapshotNanos.sum() / n / 1000);
        return m;
    }

    static void resetStatsForTest() {
        for (LongAdder a : List.of(checked, rejected, retried, retryPassed, fellBack, checkNanos, snapshots,
                snapshotNanos)) {
            a.reset();
        }
        byReason.values().forEach(LongAdder::reset);
    }
}
