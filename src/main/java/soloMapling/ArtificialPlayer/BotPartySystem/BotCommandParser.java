package soloMapling.ArtificialPlayer.BotPartySystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ticket 14: short orders to the bots in your party, typed in chat - "jason follow", "1 stop", "fire grind", or
 * just "grind" for all of them. Ported from cosmic_agents' {@code AgentCommandParser} (a target token - full name,
 * name prefix or slot number - then the command), with the command words of a party companion and a looser fit
 * for how players type: the target may also come last ("stop 1"), a name may be matched by a piece of it ("fire"
 * for xFirex), and a line only counts as an order when it is the order and nothing more ("jason follow me pls"
 * yes, "jason follow the money lol" no) - anything else stays natural chat.
 *
 * <p>1.6.2 ticket 99 (nutnnut adoption): the companion command set of nutnnut/Cosmic {@code BotChatManager} and
 * {@code BotCommandParser} - patrol, sentry, move here, formations (8fd725404), trades by category, "show me your
 * hat", drop, "any upgrades?", "request?", "trade upgrades", "need pot" - read here for every party bot (owned alts
 * and bot friends alike). They are only read when the caller allows them ({@link #parse(String, List, Set)}), each
 * behind its own switch; {@link #parse(String, List)} reads the ticket 14 three exactly as before.
 *
 * <p>Pure: it knows the targets it is given (the player's party bots, in slot order) and nothing else.
 */
public final class BotCommandParser {

    /** What the bots are told to do. */
    public enum Verb {
        /** Follow me / come here: a stationed bot becomes a follower again, a staying one walks again. */
        FOLLOW,
        /** Stop / stay / wait: a follower stops where it is until told to follow. */
        STAY,
        /** Grind / train here: a follower stations on this map and trains. */
        GRIND,
        // ── 1.6.2 ticket 99 ──
        /** Patrol / roam: walk back and forth around this spot, fighting what comes. */
        PATROL,
        /** Sentry / guard / camp: hold this spot and fight only what comes within reach. */
        SENTRY,
        /** Move here: walk to where the player stands now, then hold there. */
        MOVE_HERE,
        /** A formation ({@link Formation#parse}); arg is the formation, or "snap" to take places again now. */
        FORMATION,
        /** Trade the player a category ("pots", "pots:hp", "scrolls", "use", "etc", "equips", "ammo", "rocks",
         * "mesos:N", "slot:hat", "name:red potion"). */
        TRADE,
        /** Show a worn item in a trade window (arg "slot:hat"). */
        SHOW,
        /** Drop a category on the ground (same args as TRADE, no mesos). */
        DROP,
        /** Any upgrades? What of its spare gear would help the player or a sibling. */
        UPGRADES,
        /** Request? What of the player's spare gear would help it. */
        REQUEST,
        /** Trade upgrades: hand over the spare gear that helps the player (and siblings). */
        TRADE_UPGRADES,
        /** Need pot(s) / ammo / rocks: whoever has spare offers some (arg "hp", "mp", "pots", "ammo", "rocks"). */
        NEED
    }

    /** The ticket 14 orders: what {@link #parse(String, List)} reads. */
    public static final Set<Verb> BASE = EnumSet.of(Verb.FOLLOW, Verb.STAY, Verb.GRIND);
    /** Movement and formations (COMPANION_ORDERS_ENABLED). */
    public static final Set<Verb> MOVEMENT = EnumSet.of(Verb.PATROL, Verb.SENTRY, Verb.MOVE_HERE, Verb.FORMATION);
    /** Item orders (COMPANION_TRADES_ENABLED). */
    public static final Set<Verb> ITEMS = EnumSet.of(Verb.TRADE, Verb.SHOW, Verb.DROP);
    /** Gear sharing (COMPANION_UPGRADES_ENABLED). */
    public static final Set<Verb> UPGRADE = EnumSet.of(Verb.UPGRADES, Verb.REQUEST, Verb.TRADE_UPGRADES);
    /** Supply sharing (COMPANION_SUPPLIES_ENABLED). */
    public static final Set<Verb> SUPPLY = EnumSet.of(Verb.NEED);

    /** One party bot that can be told: its id and name, at its slot (1-based position in the list). */
    public record Target(int id, String name) {
    }

    /**
     * A parsed order. {@code targets} is who it is for (every bot for an untargeted order); when the name matched
     * more than one bot, {@code targets} is empty and {@code ambiguous} lists the candidates. {@code arg} is what
     * the order is about (a category, a formation), or null.
     */
    public record Order(Verb verb, List<Target> targets, List<Target> ambiguous, boolean everyone, String arg) {
        public Order(Verb verb, List<Target> targets, List<Target> ambiguous, boolean everyone) {
            this(verb, targets, ambiguous, everyone, null);
        }

        public boolean isAmbiguous() {
            return targets.isEmpty() && !ambiguous.isEmpty();
        }
    }

    /** A command word with what it is about. */
    record Cmd(Verb verb, String arg) {
    }

    /** cosmic_agents' tuning: a prefix needs two letters, a slot goes up to five (a party has six seats). */
    static final int MIN_PREFIX = 2;
    static final int MIN_PIECE = 3;
    static final int MAX_SLOT = 5;
    /** Longest line read as a ticket 14 order, and as a 1.6.2 one (item names make trade lines longer). */
    static final int MAX_WORDS_BASE = 7;
    static final int MAX_WORDS = 8;

    private static final Map<String, Verb> VERBS = Map.ofEntries(
            Map.entry("follow", Verb.FOLLOW), Map.entry("fm", Verb.FOLLOW), Map.entry("come", Verb.FOLLOW),
            Map.entry("regroup", Verb.FOLLOW),
            Map.entry("stop", Verb.STAY), Map.entry("stay", Verb.STAY), Map.entry("wait", Verb.STAY),
            Map.entry("hold", Verb.STAY), Map.entry("halt", Verb.STAY),
            Map.entry("grind", Verb.GRIND), Map.entry("train", Verb.GRIND), Map.entry("farm", Verb.GRIND),
            Map.entry("hunt", Verb.GRIND), Map.entry("station", Verb.GRIND),
            // 1.6.2 ticket 99 (nutnnut/Cosmic BotChatManager PATROL_PATTERN, FARM_HERE_PATTERN, MOVE_HERE_PATTERN)
            Map.entry("patrol", Verb.PATROL), Map.entry("roam", Verb.PATROL), Map.entry("wander", Verb.PATROL),
            Map.entry("sentry", Verb.SENTRY), Map.entry("guard", Verb.SENTRY), Map.entry("camp", Verb.SENTRY),
            Map.entry("defend", Verb.SENTRY), Map.entry("anchor", Verb.SENTRY),
            Map.entry("move", Verb.MOVE_HERE));

    /** Words that may ride along with the command word without making the line something else. */
    private static final Set<String> FILLER = Set.of("me", "here", "there", "with", "us", "back", "on", "up",
            "following", "a", "bit", "sec", "min", "now", "rn", "pls", "plz", "please", "ok", "okay", "k", "kk",
            "thx", "ty", "asap", "too", "again", "this", "map", "spot", "for", "moment");
    /** 1.6.2: the same, plus what rides along with the new commands ("patrol around", "spread out"). */
    private static final Set<String> FILLER_MORE;

    static {
        Set<String> more = new HashSet<>(FILLER);
        more.addAll(Set.of("around", "the", "area", "mode", "out", "formation", "guys", "everyone"));
        FILLER_MORE = Set.copyOf(more);
    }

    /** Words before an order that are only a greeting ("hey jason follow", "ok grind"). */
    private static final Set<String> LEAD_IN = Set.of("hey", "yo", "oi", "ok", "okay", "k", "kk", "alright", "pls",
            "plz", "please", "now", "lets");
    /** Everyday words never read as a piece of a bot's name ("we grind" is not an order to Wendy). */
    private static final Set<String> NOT_A_NAME = Set.of("we", "u", "you", "ya", "yo", "i", "im", "me", "lets",
            "to", "can", "should", "gonna", "imma", "pls", "plz", "so", "and", "then", "just", "dont", "do", "no",
            "not", "ok", "okay", "any", "got", "need", "show", "trade", "drop", "give", "what", "whats", "anyone");
    /** Target words that mean every party bot. */
    private static final Set<String> EVERYONE = Set.of("all", "everyone", "everybody", "guys", "yall", "team",
            "party", "bots");

    // ── item words (1.6.2 ticket 99, after nutnnut/Cosmic BotChatManager's *_WORDS) ──
    /** Words between "trade" and what is traded ("trade me your pots"). */
    private static final Set<String> OWNER_WORDS = Set.of("me", "us", "your", "ur", "my", "all", "some", "the",
            "of", "yo");
    /** Category word -> category. */
    private static final Map<String, String> CATEGORIES = Map.ofEntries(
            Map.entry("pot", "pots"), Map.entry("pots", "pots"), Map.entry("potion", "pots"),
            Map.entry("potions", "pots"), Map.entry("supplies", "pots"),
            Map.entry("scroll", "scrolls"), Map.entry("scrolls", "scrolls"),
            Map.entry("use", "use"), Map.entry("consumables", "use"),
            Map.entry("etc", "etc"), Map.entry("misc", "etc"),
            Map.entry("equip", "equips"), Map.entry("equips", "equips"), Map.entry("equipment", "equips"),
            Map.entry("gear", "equips"),
            Map.entry("ammo", "ammo"), Map.entry("arrow", "ammo"), Map.entry("arrows", "ammo"),
            Map.entry("bolt", "ammo"), Map.entry("bolts", "ammo"), Map.entry("star", "ammo"),
            Map.entry("stars", "ammo"), Map.entry("bullet", "ammo"), Map.entry("bullets", "ammo"),
            Map.entry("rock", "rocks"), Map.entry("rocks", "rocks"));
    /** Equip slot word -> the slot word every caller knows ({@code TradeCategories.SLOTS}). */
    static final Map<String, String> SLOT_WORDS = Map.ofEntries(
            Map.entry("hat", "hat"), Map.entry("helm", "hat"), Map.entry("helmet", "hat"),
            Map.entry("face", "face"), Map.entry("eye", "eye"), Map.entry("glasses", "eye"),
            Map.entry("earring", "earring"), Map.entry("earrings", "earring"),
            Map.entry("top", "top"), Map.entry("shirt", "top"), Map.entry("overall", "top"),
            Map.entry("bottom", "pants"), Map.entry("pants", "pants"),
            Map.entry("shoes", "shoes"), Map.entry("boots", "shoes"),
            Map.entry("gloves", "gloves"), Map.entry("glove", "gloves"),
            Map.entry("cape", "cape"), Map.entry("shield", "shield"), Map.entry("offhand", "shield"),
            Map.entry("weapon", "weapon"), Map.entry("wep", "weapon"),
            Map.entry("pendant", "pendant"), Map.entry("belt", "belt"), Map.entry("medal", "medal"),
            Map.entry("ring", "ring"));
    /** A one-word "item name" that is chat, not an item ("trade later"). */
    private static final Set<String> NOT_AN_ITEM = Set.of("later", "soon", "tmrw", "tomorrow", "sometime", "it",
            "that", "this", "them", "those", "stuff", "things", "u", "you", "ya", "pls", "plz", "now", "rn", "lol",
            "maybe", "first", "back", "too", "with", "w");
    private static final Set<String> SUPPLY_HP = Set.of("hp", "health", "red", "reds");
    private static final Set<String> SUPPLY_MP = Set.of("mp", "mana", "blue", "blues");

    private BotCommandParser() {
    }

    /**
     * Reads a chat line as a ticket 14 order (follow, stay, grind) to the given party bots.
     *
     * @param slots the player's party bots in slot order (slot 1 first)
     * @return the order, or empty when the line is not one (it stays natural chat)
     */
    public static Optional<Order> parse(String line, List<Target> slots) {
        return parse(line, slots, BASE);
    }

    /** Reads a chat line as an order, knowing only the {@code allowed} commands (the rest stays chat). */
    public static Optional<Order> parse(String line, List<Target> slots, Set<Verb> allowed) {
        if (line == null || slots == null || slots.isEmpty() || allowed == null || allowed.isEmpty()) {
            return Optional.empty();
        }
        String text = line.toLowerCase(Locale.ROOT);
        boolean extended = !BASE.containsAll(allowed);
        if (extended) {
            text = text.replaceAll("(\\d)[.,](\\d)", "$1_$2"); // "1.5m", "100,000"
        }
        text = text.replaceAll(extended ? "[^a-z0-9_ ]" : "[^a-z0-9 ]", " ").trim();
        List<String> words = new ArrayList<>(Arrays.asList(text.split("\\s+")));
        words.removeIf(String::isEmpty);
        // A bot named like a lead-in word ("Hey") is still a name: only strip what is not one.
        while (words.size() > 1 && LEAD_IN.contains(words.get(0)) && exact(words.get(0), slots) == null) {
            words.remove(0);
        }
        int n = words.size();
        if (words.isEmpty() || n > (extended ? MAX_WORDS : MAX_WORDS_BASE)) {
            return Optional.empty();
        }
        // "grind" / "follow me" / "trade pots": every bot.
        Optional<Order> untargeted = command(words, 0, n, allowed, false)
                .map(c -> new Order(c.verb(), List.copyOf(slots), List.of(), true, c.arg()));
        if (untargeted.isPresent()) {
            return untargeted;
        }
        // "jason follow", "1 stop", "fire, grind here", "jason trade red potion".
        Optional<Order> first = targeted(words.get(0), command(words, 1, n, allowed, true), slots);
        if (first.isPresent()) {
            return first;
        }
        // "stop 1", "follow me jason".
        Optional<Order> last = targeted(words.get(n - 1), command(words, 0, n - 1, allowed, true), slots);
        if (last.isPresent()) {
            return last;
        }
        // "trade red potion": an item by name, for every bot (the one holding it answers).
        return command(words, 0, n, allowed, true)
                .map(c -> new Order(c.verb(), List.copyOf(slots), List.of(), true, c.arg()));
    }

    private static Optional<Order> targeted(String token, Optional<Cmd> cmd, List<Target> slots) {
        if (cmd.isEmpty()) {
            return Optional.empty();
        }
        Verb verb = cmd.get().verb();
        String arg = cmd.get().arg();
        if (EVERYONE.contains(token) && exact(token, slots) == null) {
            return Optional.of(new Order(verb, List.copyOf(slots), List.of(), true, arg));
        }
        List<Target> matched = resolve(token, slots);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matched.size() == 1
                ? new Order(verb, matched, List.of(), false, arg)
                : new Order(verb, List.of(), matched, false, arg));
    }

    /** The command at {@code from} with only filler after it, up to {@code to}. */
    private static Optional<Cmd> command(List<String> words, int from, int to, Set<Verb> allowed, boolean byName) {
        if (from >= to) {
            return Optional.empty();
        }
        List<String> w = words.subList(from, to);
        Cmd cmd = null;
        if (!BASE.containsAll(allowed)) {
            cmd = itemCommand(w, allowed, byName);
            if (cmd == null) {
                cmd = formation(w, allowed);
            }
        }
        if (cmd == null) {
            cmd = simple(w, allowed);
        }
        return Optional.ofNullable(cmd).filter(c -> allowed.contains(c.verb()));
    }

    /** One command word and filler: "follow me", "patrol around", "move here", "go here". */
    private static Cmd simple(List<String> w, Set<Verb> allowed) {
        String head = w.get(0);
        Verb verb = VERBS.get(head);
        int from = 1;
        if (verb == null && head.equals("go") && w.size() >= 2 && (w.get(1).equals("here") || w.get(1).equals("there"))) {
            verb = Verb.MOVE_HERE; // "go here" (a bare "go" is chat)
            from = 2;
        }
        if (verb == null || !allowed.contains(verb)) {
            return null;
        }
        // the ticket 14 orders keep their own filler, so a line that was chat before stays chat
        if (!fillerOnly(w, from, BASE.contains(verb) ? FILLER : FILLER_MORE)) {
            return null;
        }
        return new Cmd(verb, null);
    }

    /**
     * A formation (nutnnut/Cosmic 8fd725404): "formation spread", "formation left 80", "spread out", "stack up",
     * "tight", "loose", "split", "snap". Left, right, random and stagger need the word "formation".
     */
    private static Cmd formation(List<String> w, Set<Verb> allowed) {
        if (!allowed.contains(Verb.FORMATION)) {
            return null;
        }
        boolean named = w.get(0).equals("formation") || w.get(0).equals("form");
        List<String> rest = named ? w.subList(1, w.size()) : w;
        if (rest.isEmpty()) {
            return null;
        }
        String type = rest.get(0);
        if (!named && !Formation.BARE_WORDS.contains(type)) {
            return null;
        }
        if (type.equals("snap")) {
            return fillerOnly(rest, 1) ? new Cmd(Verb.FORMATION, Formation.SNAP) : null;
        }
        String size = rest.size() > 1 && !FILLER_MORE.contains(rest.get(1)) ? rest.get(1) : null;
        Formation f = Formation.parse(type, size);
        if (f == null || !fillerOnly(rest, size == null ? 1 : 2)) {
            return null;
        }
        return new Cmd(Verb.FORMATION, f.toArg());
    }

    /** Trades, drops, shows, upgrades and supply calls. Null when the words are none of them. */
    private static Cmd itemCommand(List<String> w, Set<Verb> allowed, boolean byName) {
        String head = w.get(0);
        // "need pots", "low on mana pots", "out of arrows", "running low on pots"; "need anything" is a request
        int at = needAt(w);
        if (at > 0 && at < w.size()) {
            if (w.get(at).equals("anything") || w.get(at).equals("something")) {
                return fillerOnly(w, at + 1) ? new Cmd(Verb.REQUEST, null) : null;
            }
            String need = supplyKind(w, at, false, FILLER_MORE);
            return need != null ? new Cmd(Verb.NEED, need) : null;
        }
        // "any upgrades?", "upgrades", "got upgrades for me", "any better gear"
        int u = (head.equals("any") || head.equals("got")) ? 1 : 0;
        if (u + 1 < w.size() && w.get(u).equals("better") && "equips".equals(CATEGORIES.get(w.get(u + 1)))) {
            return fillerOnly(w, u + 2) ? new Cmd(Verb.UPGRADES, null) : null;
        }
        if (u < w.size() && (w.get(u).equals("upgrades") || w.get(u).equals("upgrade"))) {
            return fillerOnly(w, u + 1) ? new Cmd(Verb.UPGRADES, null) : null;
        }
        // "request", "what do you need", "wishlist"
        if ((head.equals("request") || head.equals("wishlist")) && fillerOnly(w, 1)) {
            return new Cmd(Verb.REQUEST, null);
        }
        if (w.size() >= 4 && head.equals("what") && w.get(1).equals("do") && (w.get(2).equals("you")
                || w.get(2).equals("u")) && w.get(3).equals("need") && fillerOnly(w, 4)) {
            return new Cmd(Verb.REQUEST, null);
        }
        // "show me your hat", "show hat", "let me see your hat", "can i see ur hat", "lemme see your hat"
        int s = showAt(w);
        if (s > 0) {
            int i = skip(w, s, OWNER_WORDS);
            if (i < w.size() && SLOT_WORDS.containsKey(w.get(i)) && fillerOnly(w, i + 1)) {
                return new Cmd(Verb.SHOW, "slot:" + SLOT_WORDS.get(w.get(i)));
            }
            return null;
        }
        boolean trade = head.equals("trade");
        boolean give = head.equals("give") || head.equals("gimme") || head.equals("pass");
        boolean drop = head.equals("drop") || head.equals("toss");
        if (!trade && !give && !drop) {
            return null;
        }
        int i = skip(w, 1, OWNER_WORDS);
        if (i >= w.size()) {
            return null; // "trade me": a bare invite stays chat
        }
        String word = w.get(i);
        if (!drop && (word.equals("upgrades") || word.equals("upgrade")) && fillerOnly(w, i + 1)) {
            return new Cmd(Verb.TRADE_UPGRADES, null);
        }
        Verb verb = drop ? Verb.DROP : Verb.TRADE;
        String mesos = mesos(w, i);
        if (mesos != null) {
            return drop ? null : new Cmd(verb, mesos);
        }
        if (word.equals("mesos") || word.equals("meso") || word.equals("money")) {
            return null; // "trade mesos": how many is a question, not an item
        }
        // "trade hp pots", "trade mana potions"
        // ("red potion" is an item: only hp/health and mp/mana name the kind here)
        boolean hp = word.equals("hp") || word.equals("health");
        boolean mp = word.equals("mp") || word.equals("mana");
        if (i + 1 < w.size() && (hp || mp) && "pots".equals(CATEGORIES.get(w.get(i + 1))) && fillerOnly(w, i + 2)) {
            return new Cmd(verb, "pots:" + (hp ? "hp" : "mp"));
        }
        String category = CATEGORIES.get(word);
        if (category != null) {
            return fillerOnly(w, i + 1) ? new Cmd(verb, category) : null;
        }
        if (SLOT_WORDS.containsKey(word) && fillerOnly(w, i + 1)) {
            return new Cmd(verb, "slot:" + SLOT_WORDS.get(word));
        }
        if (!byName || give) {
            return null; // "give me a sec" is chat: give takes categories only
        }
        List<String> name = new ArrayList<>(w.subList(i, w.size()));
        while (!name.isEmpty() && FILLER_MORE.contains(name.get(name.size() - 1))) {
            name.remove(name.size() - 1);
        }
        if (name.isEmpty() || (name.size() == 1 && NOT_AN_ITEM.contains(name.get(0)))
                || name.stream().anyMatch(NOT_AN_ITEM::contains) && name.size() <= 2) {
            return null;
        }
        return new Cmd(verb, "name:" + String.join(" ", name));
    }

    /** Where the supply word starts in "need ...", "low on ...", "out of ...", "running low on ..."; 0 = none. */
    private static int needAt(List<String> w) {
        String head = w.get(0);
        if (head.equals("need") || head.equals("nned") || head.equals("needs")) {
            return skip(w, 1, Set.of("some", "more", "a", "few", "any"));
        }
        if (w.size() >= 2 && (head.equals("low") && w.get(1).equals("on") || head.equals("out") && w.get(1).equals("of"))) {
            return skip(w, 2, Set.of("some", "my", "all"));
        }
        if (w.size() >= 3 && head.equals("running") && w.get(1).equals("low") && w.get(2).equals("on")) {
            return skip(w, 3, Set.of("some", "my"));
        }
        return 0;
    }

    /**
     * "hp", "mp", "pots", "ammo" or "rocks" for the supply words at {@code at}, filler after; else null. An ask of a
     * bot friend ({@code ask}) also takes "scrolls" and leaves rocks out.
     */
    private static String supplyKind(List<String> w, int at, boolean ask, Set<String> filler) {
        if (at >= w.size()) {
            return null;
        }
        String word = w.get(at);
        if (SUPPLY_HP.contains(word) || SUPPLY_MP.contains(word)) {
            String kind = SUPPLY_HP.contains(word) ? "hp" : "mp";
            int next = at + 1;
            if (next < w.size() && "pots".equals(CATEGORIES.get(w.get(next)))) {
                next++;
            }
            return fillerOnly(w, next, filler) ? kind : null;
        }
        String category = CATEGORIES.get(word);
        boolean fits = category != null && (category.equals("pots") || category.equals("ammo")
                || (ask ? category.equals("scrolls") : category.equals("rocks")));
        if (!fits) {
            return null;
        }
        return fillerOnly(w, at + 1, filler) ? category : null;
    }

    // ── 1.6.2: asking a bot friend for something ──

    /** Words around an ask that do not change it ("got any pots to spare?", "need pots bro"). */
    private static final Set<String> ASK_FILLER;
    /** Words between the ask and the thing ("spare me a few pots", "got any extra pots"). */
    private static final Set<String> ASK_BRIDGE = Set.of("any", "some", "extra", "spare", "a", "few", "me", "us",
            "of", "ur", "your", "more", "couple", "the");

    static {
        Set<String> more = new HashSet<>(FILLER_MORE);
        more.addAll(Set.of("to", "spare", "extra", "lol", "bro", "man", "dude", "by", "chance", "maybe", "friend",
                "buddy", "pal", "u", "you", "ya", "i", "could", "can", "use", "have", "left", "on", "hand"));
        ASK_FILLER = Set.copyOf(more);
    }

    /**
     * 1.6.2 (owner, grill 2026-10-30, "bot friends handing over items: yes, small and capped"): what a player asks a
     * bot for - "got any pots?", "can u spare a pot", "need pots", "any scrolls?", "u have any arrows", "can i get
     * some mana pots", "low on hp pots". The companions' "need pot" words ({@link #parse}) plus the ways a player asks
     * someone who is not their companion. "hp", "mp", "pots", "ammo" or "scrolls"; null when the line is anything
     * else (it stays natural chat). Pure.
     */
    public static String supplyAsk(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String text = line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim();
        List<String> w = new ArrayList<>(Arrays.asList(text.split("\\s+")));
        w.removeIf(String::isEmpty);
        while (w.size() > 1 && LEAD_IN.contains(w.get(0))) {
            w.remove(0);
        }
        if (w.isEmpty() || w.size() > 10) {
            return null;
        }
        // "need pots", "low on mana", "out of arrows": the companions' words
        int at = needAt(w);
        if (at > 0) {
            return supplyKind(w, at, true, ASK_FILLER);
        }
        int i = 0;
        boolean modal = Set.of("can", "could", "would", "will", "do", "does", "may").contains(w.get(0));
        if (modal) {
            i++;
        }
        boolean me = false;
        if (i < w.size() && Set.of("u", "you", "ya", "yu").contains(w.get(i))) {
            i++;                         // "u got any pots", "can u spare a pot", "do you have pots"
        } else if (modal && i < w.size() && w.get(i).equals("i")) {
            i++;                         // "can i get a pot", "could i have some pots"
            me = true;
        }
        if (i >= w.size()) {
            return null;
        }
        String verb = w.get(i);
        boolean asks = switch (verb) {
            case "got", "have", "has", "spare", "gimme" -> !me || verb.equals("have");
            case "get", "borrow", "grab" -> me;
            case "give" -> i + 1 < w.size() && (w.get(i + 1).equals("me") || w.get(i + 1).equals("us"));
            case "any" -> i == 0;        // "any pots?", "any scrolls"
            default -> false;
        };
        if (!asks) {
            return null;
        }
        int from = verb.equals("any") ? i : i + 1;
        return supplyKind(w, skip(w, from, ASK_BRIDGE), true, ASK_FILLER);
    }

    /** Index after "show (me)", "let me see", "lemme see", "can i see", "show us"; 0 = not a show. */
    private static int showAt(List<String> w) {
        String head = w.get(0);
        if (head.equals("show")) {
            return w.size() > 1 && (w.get(1).equals("me") || w.get(1).equals("us")) ? 2 : 1;
        }
        if (w.size() >= 3 && head.equals("let") && w.get(1).equals("me") && w.get(2).equals("see")) {
            return 3;
        }
        if (w.size() >= 2 && head.equals("lemme") && w.get(1).equals("see")) {
            return 2;
        }
        if (w.size() >= 3 && head.equals("can") && w.get(1).equals("i") && w.get(2).equals("see")) {
            return 3;
        }
        return 0;
    }

    /** "100k", "1_5m" (1.5m), "100_000", "100000 mesos", "mesos 100k" -> "mesos:N"; else null. */
    static String mesos(List<String> w, int i) {
        int at = i;
        if (w.get(at).equals("mesos") || w.get(at).equals("meso")) {
            at++;
            if (at >= w.size()) {
                return null; // "trade mesos": how many?
            }
        }
        long amount = mesoAmount(w.get(at));
        if (amount <= 0) {
            return null;
        }
        int next = at + 1;
        if (next < w.size() && (w.get(next).equals("mesos") || w.get(next).equals("meso"))) {
            next++;
        }
        return fillerOnly(w, next) ? "mesos:" + amount : null;
    }

    /** A meso amount token ("100k", "1_5m", "250000", "100_000"); 0 when it is not one. */
    static long mesoAmount(String token) {
        if (!token.matches("\\d+(_\\d+)*[kmb]?")) {
            return 0;
        }
        char last = token.charAt(token.length() - 1);
        char unit = java.lang.Character.isLetter(last) ? last : 0;
        String digits = unit == 0 ? token : token.substring(0, token.length() - 1);
        String[] groups = digits.split("_");
        boolean thousands = groups.length > 1;
        for (int g = 1; g < groups.length; g++) {
            thousands &= groups[g].length() == 3;
        }
        double value;
        try {
            if (thousands && unit == 0) {
                value = Double.parseDouble(String.join("", groups));
            } else if (groups.length == 2) {
                value = Double.parseDouble(groups[0] + "." + groups[1]);
            } else if (groups.length == 1) {
                value = Double.parseDouble(groups[0]);
            } else {
                return 0;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
        double mult = switch (unit) {
            case 'k' -> 1_000d;
            case 'm' -> 1_000_000d;
            case 'b' -> 1_000_000_000d;
            default -> 1d;
        };
        long amount = Math.round(value * mult);
        return amount > 0 && amount <= Integer.MAX_VALUE ? amount : 0;
    }

    private static int skip(List<String> w, int from, Set<String> words) {
        int i = from;
        while (i < w.size() && words.contains(w.get(i))) {
            i++;
        }
        return i;
    }

    private static boolean fillerOnly(List<String> w, int from) {
        return fillerOnly(w, from, FILLER_MORE);
    }

    private static boolean fillerOnly(List<String> w, int from, Set<String> filler) {
        for (int i = from; i < w.size(); i++) {
            if (!filler.contains(w.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Who a target token names, best match first: the full name, a slot number, a name prefix (two letters or
     * more), then a piece inside the name (three or more - "fire" for xFirex). Several bots at the first matching
     * tier is an ambiguity; none is no target.
     */
    static List<Target> resolve(String token, List<Target> slots) {
        Target named = exact(token, slots);
        if (named != null) {
            return List.of(named);
        }
        if (token.chars().allMatch(java.lang.Character::isDigit)) {
            if (token.length() > 1) {
                return List.of();
            }
            int slot = token.charAt(0) - '0';
            return slot >= 1 && slot <= Math.min(MAX_SLOT, slots.size()) ? List.of(slots.get(slot - 1)) : List.of();
        }
        if (token.length() < MIN_PREFIX || NOT_A_NAME.contains(token)) {
            return List.of();
        }
        List<Target> prefixed = new ArrayList<>();
        for (Target t : slots) {
            if (t.name().toLowerCase(Locale.ROOT).startsWith(token)) {
                prefixed.add(t);
            }
        }
        if (!prefixed.isEmpty() || token.length() < MIN_PIECE) {
            return prefixed;
        }
        List<Target> pieces = new ArrayList<>();
        for (Target t : slots) {
            if (t.name().toLowerCase(Locale.ROOT).contains(token)) {
                pieces.add(t);
            }
        }
        return pieces;
    }

    private static Target exact(String token, List<Target> slots) {
        for (Target t : slots) {
            if (t.name().equalsIgnoreCase(token)) {
                return t;
            }
        }
        return null;
    }

    /** The "which one?" a bot asks when a name matched several: "1 Jane / 2 Jason", by slot. */
    public static String whichOne(List<Target> ambiguous, List<Target> slots) {
        StringBuilder text = new StringBuilder("which one? ");
        for (int i = 0; i < ambiguous.size(); i++) {
            if (i > 0) {
                text.append(" / ");
            }
            text.append(slots.indexOf(ambiguous.get(i)) + 1).append(' ').append(ambiguous.get(i).name());
        }
        return text.toString();
    }
}
