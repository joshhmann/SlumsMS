package soloMapling.ArtificialPlayer.BotConvo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 1.6.0 slice S4: no made-up people in bot chatter. The thread model used to invent players to gossip about
 * ("did u see xxslayerxx try to ks me", "Did you see PartyKing married RibbonQueen") - nobody on the server has those
 * names, which a real player spots at once. Every thread line goes through {@link #check} before it is said: each
 * token shaped like a character name must be a character that exists (online now, or a bot or player the name
 * registry holds, {@code BotNames.isTaken}) or one the caller allows (the two speakers and the people the gossip is
 * really about). Otherwise the line is rewritten without the name ("did u see some guy try to ks me"), or dropped
 * when it named two or more strangers.
 *
 * <p>Name-shaped, by rules (no model): a legal name ({@code [A-Za-z0-9]{3,12}}) that is
 * <ul>
 *   <li>xX-wrapped ({@code xxslayerxx}, {@code xSniperGodx}, {@code XXDLUFFYXX}) or starts with {@code xx};</li>
 *   <li>camel-cased inside ({@code PartyKing}) and not a word;</li>
 *   <li>letters then digits ({@code slayer99}), or leet that is no word read back ({@code k1ll3r});</li>
 *   <li>a non-word right after "did u see / heard / ask / named ..." or right before "tried / got scammed / keeps
 *   ...", or with a Korean {@code -nim} on it;</li>
 *   <li>a capitalised non-word in the middle of a sentence ({@code Stabbyman}).</li>
 * </ul>
 * "Word" is the built-in list below, every word used at least twice in the chat line bank, and the maps, mobs and
 * items {@link ConvoEntities} knows.
 */
public final class NameGuard {

    /** The outcome: the line to say (null = drop it), the strangers it named, and whether it was rewritten. */
    public record Verdict(String line, List<String> unknown, boolean rewritten) {
        public boolean dropped() {
            return line == null;
        }

        public boolean clean() {
            return unknown.isEmpty();
        }
    }

    /** Whether a name belongs to a real character (a seam: tests and the transcript report give their own). */
    @FunctionalInterface
    public interface Known {
        boolean known(String name);
    }

    private static final Pattern LEGAL = Pattern.compile("[A-Za-z0-9]{3,12}");
    private static final Pattern XWRAP = Pattern.compile("(?i)x{1,2}[a-z0-9]{2,}x{1,2}|xx[a-z0-9]{3,}");
    private static final Pattern CAMEL = Pattern.compile(".*[a-z][A-Z].*");
    private static final Pattern DIGITS = Pattern.compile("(?i)[a-z]{3,}[0-9]{2,}");
    private static final Pattern LEET = Pattern.compile("(?i)[a-z]+[0-9][a-z0-9]*[a-z]");
    private static final Pattern STAT = Pattern.compile("(?i)(lv|lvl|level|ch|ap|sp|hp|mp|x|pq|wa|ma|att|def|lf|gs)[0-9]+[a-z]?");

    /** The word before a name: "did u see X", "heard X", "ask X", "named X". */
    static final Set<String> BEFORE = Set.of("see", "saw", "c", "seen", "heard", "hear", "ask", "asked", "tell", "told",
            "named", "called", "met", "meet", "add", "added", "whisper", "whispered", "pm", "w/", "vs");
    /** The word after a name: "X tried", "X got scammed", "X keeps ksing". */
    static final Set<String> AFTER = Set.of("tried", "got", "keeps", "kept", "ksed", "ksd", "scammed", "died", "quit",
            "married", "flexing", "flexin", "spamming", "spammin", "dropped", "lost", "killed", "fell", "dcd", "dced",
            "said", "says", "blamed", "ditched", "cried", "left", "joined", "sold", "bought", "stole");

    /** Words that are never names on their own (function words, chat slang, jobs, places); the bank adds the rest. */
    static final Set<String> BASE = Set.copyOf(java.util.Arrays.asList(("the a an and or but if so to of in on at by for with from about into over "
            + "after before up down out off again then once here there when where why how what who whom which this that "
            + "these those i me my mine we us our you u ur your yours he him his she her hers it its they them their "
            + "theirs someone somebody anyone anybody everyone everybody nobody noone guy guys dude dudes bro bros man "
            + "girl girls ppl people player players noob noobs nub nubs newb n00b scrub nerd pro pros some any all most "
            + "no not nah yes ya yea yeah yep ok okay k kk lol lmao lolz rofl xd haha hehe jaja kekeke omg wtf idk tbh "
            + "brb gtg afk gg ez rip gz grats ty thx pls plz sry srry nvm imo btw np hi hey yo sup hello bye cya "
            + "is am are was were be been being have has had do does did done got get gets getting go goes going went "
            + "gone come came coming see saw seen look looking said say says tell told ask asked know knew think "
            + "thought want wanted need needed like liked make made take took give gave try tried keep kept let "
            + "just still even also too very really rly much many more less few lot lots again always never ever "
            + "now today yesterday tonight earlier later soon last next first new old big small good bad best worst "
            + "nice cool sick insane crazy funny lucky sad mad rich poor broke cheap expensive free "
            + "level lvl lv job class guild guilds party pt pq kpq lpq opq hpq cpq apq boss bosses mob mobs map maps "
            + "town fm market channel ch cc server patch drop drops item items gear equip scroll scrolls pot pots "
            + "mesos meso mesoz nx smega smegas megaphone hack hacker hackers bot bots gm gms admin ks ksing ksed ksr "
            + "scam scammer scammers scammed trade trades trading buy sell selling buying price prices "
            + "warrior warriors fighter page spearman crusader knight hero paladin dk magician mage mages wizard "
            + "cleric priest bishop archer archers hunter crossbowman ranger sniper bowmaster marksman thief thieves "
            + "assassin bandit hermit sin sins dit dits nl shadower chief pirate pirates brawler gunslinger marauder "
            + "outlaw buccaneer corsair bucc sair beginner beginners noblesse "
            + "henesys ellinia perion kerning lith sleepywood nautilus orbis ludi ludibrium aqua leafre mulung "
            + "amoria nlc omega ariant magatia zakum zak horntail ht pap papulatus pianus krexel bigfoot jr "
            + "balrog crimson balrog snail snails slime slimes pig pigs mushroom mushrooms shroom shrooms stump "
            + "stumps zombie zombies ant tunnel ants coolie wraith wraiths skeles golem golems drake drakes "
            + "zhelm pink bean fame famed rumor rumors rumour guildmate guildie guildies buddy buddies friend "
            + "friends crew reply abt tht wat dat dis leet maplestory maple wife husband couple ex "
            + "glove gloves shield helmet hat sword claw wand staff bow crossbow dagger spear polearm axe "
            + "mace knuckle gun cape shoes overall pie stars star throwing teddy teddies goby gobies skelegon "
            + "skelegons stirge stirges timer timers grim lama wagner bamboo").split(" ")));

    private static volatile Known known = NameGuard::worldKnows;
    private static volatile Set<String> vocabulary;

    private NameGuard() {
    }

    // ── the check ──

    /** {@link #check(String, Collection, Known)} against the world's characters. */
    public static Verdict check(String line, Collection<String> allowed) {
        return check(line, allowed, known);
    }

    /**
     * The line with every stranger's name taken out: kept as it is when it names nobody unknown, rewritten when it
     * named one stranger ("some guy" in their place), dropped (null) when it named two or more.
     */
    public static Verdict check(String line, Collection<String> allowed, Known isKnown) {
        if (line == null || line.isBlank()) {
            return new Verdict(line, List.of(), false);
        }
        Set<String> ok = new HashSet<>();
        if (allowed != null) {
            for (String a : allowed) {
                if (a != null && !a.isBlank()) {
                    ok.add(a.toLowerCase(Locale.ROOT));
                    ok.add(bare(a.toLowerCase(Locale.ROOT)));
                }
            }
        }
        Set<String> things = gameWords(line);
        List<Token> strangers = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Token t : candidates(tokens(line))) {
            String lower = t.name.toLowerCase(Locale.ROOT);
            if (ok.contains(lower) || ok.contains(bare(lower)) || things.contains(lower) || isKnown.known(t.name)
                    || !bare(lower).equals(lower) && isKnown.known(bare(t.name))) {
                continue;
            }
            strangers.add(t);
            names.add(t.name);
        }
        CHECKED.incrementAndGet();
        if (strangers.isEmpty()) {
            return new Verdict(line, List.of(), false);
        }
        String rewritten = names.size() > 1 ? null : rewrite(line, strangers);
        (rewritten == null ? DROPPED : REWRITTEN).incrementAndGet();
        return new Verdict(rewritten, List.copyOf(names), rewritten != null);
    }

    private static final java.util.concurrent.atomic.AtomicLong CHECKED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong REWRITTEN = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DROPPED = new java.util.concurrent.atomic.AtomicLong();

    /** Since boot: thread lines checked, rewritten without a made-up name, and dropped (bot-life.json "nameGuard"). */
    public static Map<String, Long> counts() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        out.put("checked", CHECKED.get());
        out.put("rewritten", REWRITTEN.get());
        out.put("dropped", DROPPED.get());
        return out;
    }

    /** The name-shaped tokens of a line (original spelling, suffixes stripped), whether known or not. */
    public static List<String> candidates(String line) {
        List<String> out = new ArrayList<>();
        for (Token t : candidates(tokens(line))) {
            out.add(t.name);
        }
        return out;
    }

    /** One word of a line: its raw text, its name part ("jiracandy" of "jiracandy-nim,"), and its neighbours. */
    record Token(int index, String raw, String name, boolean nim, boolean possessive, String before, String after,
                 boolean sentenceStart) {
    }

    static List<Token> tokens(String line) {
        String[] raw = line.trim().split("\\s+");
        List<Token> out = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            String w = raw[i].replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
            boolean nim = false;
            boolean possessive = false;
            String lw = w.toLowerCase(Locale.ROOT);
            if (lw.endsWith("-nim") || lw.endsWith("nim") && raw[i].contains("-")) {
                w = w.substring(0, lw.lastIndexOf("nim")).replaceAll("-+$", "");
                nim = true;
            } else if (lw.endsWith("'s") || lw.endsWith("’s")) {
                w = w.substring(0, w.length() - 2);
                possessive = true;
            }
            String before = i == 0 ? "" : clean(raw[i - 1]);
            String after = i + 1 >= raw.length ? "" : clean(raw[i + 1]);
            boolean start = i == 0 || raw[i - 1].matches(".*[.!?:]$");
            out.add(new Token(i, raw[i], w, nim, possessive, before, after, start));
        }
        return out;
    }

    private static String clean(String w) {
        return w.toLowerCase(Locale.ROOT).replaceAll("^[^a-z0-9/']+|[^a-z0-9/']+$", "").replace("'", "");
    }

    static List<Token> candidates(List<Token> tokens) {
        List<Token> out = new ArrayList<>();
        for (Token t : tokens) {
            if (nameShaped(t)) {
                out.add(t);
            }
        }
        return out;
    }

    static boolean nameShaped(Token t) {
        String w = t.name;
        if (!LEGAL.matcher(w).matches() || w.chars().allMatch(Character::isDigit)) {
            return false;
        }
        String lower = w.toLowerCase(Locale.ROOT);
        if (STAT.matcher(lower).matches()) {
            return false;
        }
        if (XWRAP.matcher(w).matches() && w.length() >= 5) {
            return true;
        }
        boolean word = word(lower);
        if (CAMEL.matcher(w).matches() && !w.equals(w.toUpperCase(Locale.ROOT)) && !word) {
            return true;
        }
        if (DIGITS.matcher(w).matches() && !word) {
            return true;
        }
        if (LEET.matcher(w).matches() && !word(unleet(lower))) {
            return true;
        }
        if (word || game(lower)) {
            return false;
        }
        if (w.length() < 3 || w.chars().anyMatch(Character::isDigit)) {
            return false;
        }
        if (t.nim || BEFORE.contains(t.before) || AFTER.contains(t.after)) {
            return true;
        }
        // a capitalised non-word mid-sentence ("I heard Stabbyman lost his helmet"); ALL CAPS is shouting, not a name
        return !t.sentenceStart && w.length() >= 4 && Character.isUpperCase(w.charAt(0))
                && !w.substring(1).equals(w.substring(1).toUpperCase(Locale.ROOT));
    }

    static String unleet(String w) {
        return w.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's')
                .replace('7', 't').replace('8', 'b');
    }

    /** A plain word: the built-in list or the line bank's vocabulary (plurals and -ing forms too). */
    static boolean word(String lower) {
        Set<String> v = vocabulary();
        if (v.contains(lower)) {
            return true;
        }
        for (String suffix : new String[]{"s", "es", "ing", "in", "ed", "d", "z", "y"}) {
            if (lower.length() > suffix.length() + 2 && lower.endsWith(suffix)
                    && v.contains(lower.substring(0, lower.length() - suffix.length()))) {
                return true;
            }
        }
        return false;
    }

    /** A map, mob or item name (String.wz, once {@link ConvoEntities} is warm), a place alias, or a typo of one. */
    static boolean game(String lower) {
        try {
            if (!ConvoEntities.find(lower).isEmpty()) {
                return true;
            }
        } catch (RuntimeException | LinkageError e) {
            // no names: the aliases below still answer
        }
        for (String place : ConvoEntities.MAP_ALIASES.keySet()) {
            if (place.length() >= 6 && typoOf(lower, place)) {
                return true; // "Heensys", "Kernig"
            }
        }
        return false;
    }

    /** Same first letter, at most one letter more or less, and (but for that letter) the same letters: a typo. */
    static boolean typoOf(String w, String of) {
        if (w.isEmpty() || w.charAt(0) != of.charAt(0) || Math.abs(w.length() - of.length()) > 1 || w.equals(of)) {
            return false;
        }
        int[] diff = new int[128];
        for (char c : w.toCharArray()) {
            diff[c & 127]++;
        }
        for (char c : of.toCharArray()) {
            diff[c & 127]--;
        }
        int off = 0;
        for (int d : diff) {
            off += Math.abs(d);
        }
        return off <= 1;
    }

    /** Every word of the maps, mobs and items the line names ("maple wagner" makes "wagner" a thing, not a person). */
    static Set<String> gameWords(String line) {
        Set<String> out = new HashSet<>();
        try {
            for (ConvoEntities.Entity e : ConvoEntities.find(line).all()) {
                out.addAll(java.util.Arrays.asList(e.name().split(" ")));
            }
        } catch (RuntimeException | LinkageError e) {
            // no names yet
        }
        return out;
    }

    /** The name inside its xX wrapping ("XxNayrbxX" -> "Nayrb"): a player can write a real name that way. */
    private static String bare(String lower) {
        String b = lower.replaceAll("^[xX]+|[xX]+$", "");
        return b.isEmpty() ? lower : b;
    }

    /** The line with the stranger's name replaced ("some guy", "some guy's"), or null if nothing sensible is left. */
    static String rewrite(String line, List<Token> strangers) {
        String[] raw = line.trim().split("\\s+");
        for (Token t : strangers) {
            String lead = t.raw.replaceAll("^([^A-Za-z0-9]*).*$", "$1").replace("@", "");
            String tail = t.raw.replaceAll("^.*?([^A-Za-z0-9]*)$", "$1");
            String who = t.possessive ? "some guy's" : (t.before.equals("that") ? "guy" : "some guy");
            if (t.sentenceStart && Character.isUpperCase(firstLetter(line))) {
                who = Character.toUpperCase(who.charAt(0)) + who.substring(1);
            }
            raw[t.index] = lead + who + (t.possessive ? "" : tail);
        }
        String out = String.join(" ", raw).replaceAll("\\s+", " ").trim();
        return out.length() > VoiceStyler.MAX || out.replaceAll("(?i)some guy('s)?", "").isBlank() ? null : out;
    }

    private static char firstLetter(String line) {
        for (char c : line.toCharArray()) {
            if (Character.isLetter(c)) {
                return c;
            }
        }
        return 'a';
    }

    // ── what counts as a word, and who exists ──

    static Set<String> vocabulary() {
        Set<String> v = vocabulary;
        if (v == null) {
            synchronized (NameGuard.class) {
                v = vocabulary;
                if (v == null) {
                    v = buildVocabulary(ChatBank.get());
                    vocabulary = v;
                }
            }
        }
        return v;
    }

    /** The built-in words plus every plain word the bank uses in at least two lines (a one-off is maybe a name). */
    static Set<String> buildVocabulary(ChatBank bank) {
        Set<String> v = new HashSet<>(BASE);
        if (bank == null) {
            return Set.copyOf(v);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < bank.size(); i++) {
            String row = bank.row(i);
            if (row == null) {
                continue;
            }
            Set<String> inLine = new HashSet<>();
            for (String w : row.split("[^A-Za-z']+")) {
                String lw = w.replace("'", "");
                if (lw.length() >= 2 && !CAMEL.matcher(w).matches()) {
                    inLine.add(lw.toLowerCase(Locale.ROOT));
                }
            }
            for (String w : inLine) {
                seen.merge(w, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            if (e.getValue() >= 2 && !XWRAP.matcher(e.getKey()).matches()) {
                v.add(e.getKey());
            }
        }
        return Set.copyOf(v);
    }

    /** Online now (any channel), or a name a bot or a real player's character holds. */
    static boolean worldKnows(String name) {
        try {
            for (client.Character c : soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getAllCharacters()) {
                if (c != null && name.equalsIgnoreCase(c.getName())) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // the world is not up: the registry answers
        }
        try {
            return soloMapling.ArtificialPlayer.BotNames.isTaken(name);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Test seam: who exists, and the vocabulary (nulls restore the real ones). */
    public static void useForTest(Known testKnown, Set<String> testVocabulary) {
        known = testKnown != null ? testKnown : NameGuard::worldKnows;
        vocabulary = testVocabulary;
    }
}
