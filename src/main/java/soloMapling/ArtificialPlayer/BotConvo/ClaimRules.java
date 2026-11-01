package soloMapling.ArtificialPlayer.BotConvo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rc6 say-do (owner goal: "a bot should do what it says"): the action claims a chat line carries - "omw", "going to
 * orbis", "brb pots", "S> ilbis", "gtg" - by cheap rules only. Pure: the same rules tag the line bank at pack time,
 * guard replies, pick ambient lines and feed the say-do ledger ({@link SayDo}) and the daily chat-quality report.
 * <p>
 * The rules want a first-person claim: "selling ilbis" and "S> ilbis" claim, "gl selling tho" and "someone is selling"
 * do not; "brb" claims, "he said brb" (in quotes) does not; "going to orbis" claims, "going to lose my mind" does not
 * (no place). A claim is {@link Claim#weak()} when the words often mean something else ("coming" alone, "here you go",
 * "im out"); the daily report may ask the judge about those, and the auto-QA expectation counts only strong ones.
 */
public final class ClaimRules {

    /** What a line says the bot will do (or is doing). */
    public enum Kind {
        COME("come/omw"),
        GO_TO("go to <place>"),
        FOLLOW("follow/lead"),
        PARTY_OFFER("party: will invite"),
        PARTY_ASK("party: inv me / LFG"),
        BUFF("buff/heal"),
        SELL("sell (S>)"),
        BUY("buy (B>)"),
        TRADE("trade me"),
        RESUPPLY("brb pots / restock"),
        AWAY("brb/afk"),
        LEAVING("gtg/logging off"),
        TRAIN_AT("train at <place>"),
        GIFT("gift"),
        LEAVE_GROUP("leave guild/party"),
        /**
         * "trade me", "open a trade": the bot asks the reader to open a trade (an ad's "S> ilbis trade me", a quote's
         * "just open a trade with me"). Like {@link #PARTY_ASK} it is judged by its premise: the bot takes a trade
         * when one is opened (a merchant in trade mode, a bot that trades in person, a drop game taking bets).
         * Appended last: bank packs keep claim bits by ordinal.
         */
        TRADE_ASK("trade me (ask)");

        public final String label;

        Kind(String label) {
            this.label = label;
        }

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind parse(String s) {
            try {
                return s == null ? null : valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * One claim. {@code mapId} is the place it names (GO_TO, TRAIN_AT), else -1; {@code item} the words after S>/B>
     * or "selling"; {@code phrase} the words that made it a claim.
     */
    public record Claim(Kind kind, int mapId, String item, String phrase, boolean weak) {
        @Override
        public String toString() {
            return kind.tag() + (mapId > 0 ? "@" + mapId : "") + (item != null && !item.isEmpty() ? "[" + item + "]" : "")
                    + (weak ? "?" : "");
        }
    }

    private static final String ME = "(?:^|\\bi'?m |\\bi am |\\bim |\\bok(?:ay)? |\\balr(?:ight|ite)? |\\bnow |\\bso |\\bk |"
            + "\\bwell |\\blol |\\byeah |\\bya |\\bbut |, |\\. )";

    private static final Pattern COME = Pattern.compile("\\b(omw|otw|on my way|be there in|be right there|brt|"
            + "heading (?:over|there|ur way|your way)|ill come|i'?ll come|coming over|comin over|coming to (?:u|you|ya)|"
            + "be there soon|walking over)\\b");
    private static final Pattern COME_WEAK = Pattern.compile("(?:^|\\b(?:im|i'?m|i am|k|ok|okay|kk|yeah|ya|sure|alright|yes|yep|ye|and)\\s+)(coming|comin)\\b"
            + "(?! (?:up|home|for|in|back|soon|out))");
    private static final Pattern COME_NOT = Pattern.compile("\\b(?:are|r|you|u|who'?s|whos|anyone|is|ppl|people|he'?s|"
            + "she'?s|they'?re|it'?s|its|update|patch|event|winter|summer)\\s+(?:\\w+\\s+)?(coming|comin)\\b");

    private static final Pattern GO_TO = Pattern.compile(ME + "(?:\\w+ )?(?:going|goin|gonna go|gonna head|heading|headed|"
            + "head(?:ing)? back|off|omw|on my way|walking|going back|goin back|gotta go|need to go|bout to go|about to go)"
            + "(?: back)? (?:to |2 |towards )?(?:the |a )?([a-z0-9' ]{2,40})");
    private static final Pattern GO_TO_ANYWHERE = Pattern.compile("\\b(?:going|goin|heading|headed|omw)(?: back)? (?:to|2) "
            + "(?:the )?([a-z0-9' ]{2,40})");

    private static final Pattern FOLLOW = Pattern.compile("\\b(follow me|this way|come with me|ill show (?:u|you)|"
            + "i'?ll show (?:u|you)|lead the way|ill lead|i'?ll lead|ill take (?:u|you)|i'?ll take (?:u|you) there)\\b");

    private static final Pattern PARTY_OFFER = Pattern.compile("\\b(ill inv|i'?ll inv|ill invite|i'?ll invite|"
            + "inviting (?:u|you)|inv(?:ite)? sent|sent (?:u |you )?(?:an? )?inv(?:ite)?|invited (?:u|you)|"
            + "ill party (?:u|you)|i'?ll party (?:u|you)|sending (?:u |you )?(?:an? )?inv)\\b");
    private static final Pattern PARTY_ASK = Pattern.compile("(?:^|[\\s,.!])(inv me|invite me|inv pls|inv plz|"
            + "lf party|lfp|lfg|lf\\d|lf (?:more|members|ppl|people|\\d)|looking for (?:a )?(?:party|pt|group)|"
            + "need (?:a )?(?:party|pt)|any party|party me|pt me)(?:[\\s,.!?]|$)");

    private static final Pattern BUFF = Pattern.compile("\\b(here(?:'?s)? (?:a |some |ur |your )?(?:buffs?|heal|haste|hs|"
            + "bless)|buffing (?:u|you|ya)|buffed (?:u|you|ya)|healing (?:u|you|ya)|healed (?:u|you|ya)|heal(?:ing)? u up|"
            + "ill buff|i'?ll buff|ill heal|i'?ll heal|hs for (?:u|you)|haste for (?:u|you)|buffs? for (?:u|you))\\b");

    private static final Pattern SELL = Pattern.compile("(?:^|[\\s(\\[])(?:s>|s >|wts|selling|sell(?:in)?g?:)\\s*((?:[^,.!?]|(?<=\\d)[,.](?=\\d)){0,40})");
    private static final Pattern SELL_FIRST = Pattern.compile("^(?:s>|s >|wts|selling|sell(?:in)?g?:)|"
            + "\\b(?:i'?m|im|i am|ok|now) selling\\b|(?:^|[,|/;]\\s*|\\b(?:also|and|im|i'?m)\\s)s>|(?:^|\\bi'?m |\\bim )wts\\b");
    private static final Pattern BUY = Pattern.compile("(?:^|[\\s(\\[])(?:b>|b >|wtb|buying|lf)\\s*((?:[^,.!?]|(?<=\\d)[,.](?=\\d)){0,40})");
    private static final Pattern BUY_FIRST = Pattern.compile("^(?:b>|b >|wtb|buying(?! (?:one|before|first|is|it|that|this|"
            + "stuff|things)\\b))|\\b(?:i'?m|im|i am|ok|now) buying\\b|"
            + "(?:^|\\s)b>|(?:^|\\bi'?m |\\bim )wtb\\b");

    /** A first-person promise to trade ("ill trade u", "sending a trade"): only a trade window backs it. */
    private static final Pattern TRADE = Pattern.compile("\\b(trade u|trade you|ill trade|i'?ll trade|i will trade|"
            + "(?:ill|i'?ll|i will|gonna|ima) (?:send|open) (?:u |you )?(?:a )?trade|sending (?:u |you )?(?:a )?trade|"
            + "opening (?:a )?trade)\\b");
    /** An ask: the reader is to open the trade ("trade me", "just open a trade with me", "send a trade"). */
    private static final Pattern TRADE_ASK = Pattern.compile("\\b(trade me|trades me|open (?:a |the )?trade|"
            + "send (?:me )?(?:a )?trade|accept (?:the |my )?trade)\\b");

    private static final Pattern RESUPPLY = Pattern.compile("(brb (?:buy(?:ing)? |get(?:ting)? |grab(?:bing)? )?(?:some |more )?(?:pots|potions|pot|elixirs|arrows|stars)\\b|"
            + "(?:^|\\bbrb |\\bi'?m |\\bim |\\bi am |\\bgotta |\\bgonna |\\bneed to |\\bgoing to |\\bgo |\\blet me |\\blemme |\\bima |\\bi'?ll |\\bill |\\bfirst |\\bthen |, |\\. )" + "(?:buy|get|grab|restock|refill)(?:ing)? (?:on |up on |some |more |my |up )?(?:pots|potions|pot|elixirs|arrows|stars)\\b|"
            + "(?:^|\\bbrb |\\bi'?m |\\bim |\\bi am |\\bgotta |\\bgonna |\\bneed to |\\bgoing to |\\bgo |\\blet me |\\blemme |\\bima |\\bi'?ll |\\bill |\\bfirst |\\bthen |, |\\. )" + "restock(?:ing)?\\b|(?:^|\\bi'?m |\\bim |\\bi am |\\bi'?ve |\\bive )(?:out of|low on|no more|almost out of) "
            + "(?:pots|potions|mana pots|hp pots|arrows|stars)\\b)");

    private static final Pattern AWAY = Pattern.compile("(?:^(?:afk|bbs)\\b|\\b(brb|be right back|bbs|"
            + "(?:go|going|gonna go|gonna be|im|i'?m|i am) afk|hold on|one sec|1 sec|gimme a sec|give me a sec|"
            + "sec\\b(?! (?:job|class))|(?:mom|mum|dad)'?s calling|calling me|getting food|grab(?:bing)? (?:a |some )?"
            + "(?:snack|food|drink|water|dinner|lunch)|walk my dog|need to pee|bathroom))");

    private static final Pattern LEAVING = Pattern.compile("\\b(gtg|g2g|got to go(?=$|[\\s,.!]+(?:bye|cya|now|guys|all|sorry|sry|lol|irl|$)|[,.!]|$)|"
            + "gotta go(?=$|[\\s,.!]+(?:bye|cya|now|guys|all|sorry|sry|lol|irl|$)|[,.!]|$)|logging (?:off|out)|"
            + "(?:gonna|going to|ima|i'?ll|time to|bout to|about to) log(?: off| out)?|night all|gn all|gn guys|good ?night (?:all|guys|everyone|ppl)|"
            + "(?:going|off|heading) to (?:bed|sleep)|off to bed|cya tmr|cya tomorrow|see (?:u|you|ya) tmr|"
            + "see (?:u|you|ya) tomorrow|im off for|i'?m off for|calling it a night|im out for (?:the )?(?:night|today)|"
            + "i should (?:go to )?sleep)\\b");

    private static final Pattern TRAIN_AT = Pattern.compile("(?:^|\\bi'?m |\\bim |\\bi am |\\bgonna |\\bgoing to |\\bima |\\bbeen |\\bme |\\bstill |\\bjust )(?:training|grinding|"
            + "leveling|lvling|hunting|farming|grind|train|lvl|level|camping)(?: \\w+)? (?:at|in|@) (?:the )?([a-z0-9' ]{2,40})");

    private static final Pattern GIFT = Pattern.compile("\\b(here take this|take this(?! portal| way| road)|have this|"
            + "free for (?:u|you)|gift for (?:u|you)|giving (?:u|you)(?! \\{price}| \\d)|dropped (?:it|some) for (?:u|you)|"
            + "here (?:u|you) go)\\b");

    private static final Pattern LEAVE_GROUP = Pattern.compile("((?:^|\\bi'?m |\\bim |\\bi am |, |\\bok )out(?! of|-| ?damage)"
            + "\\b|\\bi quit\\b|\\b(?:quitting|leaving|quit|leave) (?:the |this |my )?(?:guild|party|pt)\\b|\\bgquit\\b)");

    /** Places by name, longest first (the convo aliases: "ant tunnel" before "ant"). */
    private static final List<Map.Entry<String, Integer>> PLACES;

    static {
        Map<String, Integer> m = new LinkedHashMap<>(ConvoEntities.MAP_ALIASES);
        m.put("the fm", 910000000);
        m.put("market", 910000000);
        m.put("henesys park", 100000200);
        m.put("park", 100000200);
        m.put("pig beach", 104010001);
        m.put("ant tunnels", 105050000);
        m.put("ant", 105050000);
        m.put("kerning city", 103000000);
        m.put("lith harbour", 104000000);
        m.put("ellinia", 101000000);
        m.put("victoria", 104000000);
        m.put("singapore", 540000000);
        m.put("cbd", 540000000);
        m.put("showa", 801000000);
        m.put("zipangu", 800000000);
        m.put("mushroom shrine", 800000000);
        m.put("ludi", 220000000);
        m.put("temple of time", 270000000);
        PLACES = new ArrayList<>(m.entrySet());
        PLACES.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getKey().length()).reversed());
    }

    private ClaimRules() {
    }

    /** The text as the rules read it: lowercase, curly quotes straightened, quoted speech removed. */
    static String norm(String line) {
        if (line == null) {
            return "";
        }
        String t = line.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"');
        t = t.replaceAll("\"[^\"]*\"", " ").replaceAll("(?<![a-z])'[^']{2,}'(?![a-z])", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** True when the line carries at least one claim. */
    public static boolean carriesClaim(String line) {
        return !classify(line).isEmpty();
    }

    /** Every claim in the line (at most one per kind), strongest first. */
    public static List<Claim> classify(String line) {
        String t = norm(line);
        List<Claim> out = new ArrayList<>();
        if (t.isEmpty()) {
            return out;
        }
        Matcher m;
        boolean resupply = false;
        if ((m = find(RESUPPLY, t)) != null) {
            // "i need to go buy some pots after that": a plan for later, not a "brb"
            out.add(new Claim(Kind.RESUPPLY, -1, null, m.group(1), LATER.matcher(t).find()));
            resupply = true;
        }
        if ((m = find(LEAVING, t)) != null) {
            out.add(new Claim(Kind.LEAVING, -1, null, m.group(1), false));
        }
        boolean placeClaim = false;
        Claim go = goTo(t);
        if (go != null) {
            out.add(go);
            placeClaim = true;
        }
        if ((m = find(TRAIN_AT, t)) != null && aboutMe(t, m)) {
            int map = place(m.group(1));
            if (map > 0) {
                out.add(new Claim(Kind.TRAIN_AT, map, null, m.group().trim(), false));
                placeClaim = true;
            }
        }
        if (!placeClaim) {
            if ((m = find(COME, t)) != null) {
                out.add(new Claim(Kind.COME, -1, null, m.group(1), false));
            } else if ((m = find(COME_WEAK, t)) != null && !COME_NOT.matcher(t).find()) {
                out.add(new Claim(Kind.COME, -1, null, m.group(1), t.endsWith("?")));
            }
        }
        if ((m = find(FOLLOW, t)) != null) {
            out.add(new Claim(Kind.FOLLOW, -1, null, m.group(1), m.group(1).equals("this way")));
        }
        if ((m = find(PARTY_OFFER, t)) != null) {
            out.add(new Claim(Kind.PARTY_OFFER, -1, null, m.group(1), false));
        } else if ((m = find(PARTY_ASK, t)) != null) {
            out.add(new Claim(Kind.PARTY_ASK, -1, null, m.group(1), false));
        }
        boolean buff = false;
        if ((m = find(BUFF, t)) != null) {
            out.add(new Claim(Kind.BUFF, -1, null, m.group(1), false));
            buff = true;
        }
        if (SELL_FIRST.matcher(t).find() && (m = find(SELL, t)) != null) {
            out.add(new Claim(Kind.SELL, -1, item(m.group(1)), "selling", false));
        }
        if (BUY_FIRST.matcher(t).find() && (m = find(BUY, t)) != null) {
            out.add(new Claim(Kind.BUY, -1, item(m.group(1)), "buying", false));
        }
        if ((m = find(TRADE, t)) != null) {
            out.add(new Claim(Kind.TRADE, -1, null, m.group(1), false));
        } else if ((m = find(TRADE_ASK, t)) != null) {
            out.add(new Claim(Kind.TRADE_ASK, -1, null, m.group(1), false));
        }
        if (!resupply && (m = find(AWAY, t)) != null) {
            String phrase = m.group(1) == null ? t.split(" ")[0] : m.group(1);
            boolean weak = phrase.equals("sec") || phrase.equals("hold on") || phrase.equals("one sec")
                    || phrase.equals("1 sec");
            if (!(weak && out.stream().anyMatch(c -> c.kind() == Kind.COME))) { // "be there in a sec" is a COME
                out.add(new Claim(Kind.AWAY, -1, null, phrase, weak));
            }
        }
        if (!buff && (m = find(GIFT, t)) != null) {
            String g = m.group(1);
            out.add(new Claim(Kind.GIFT, -1, null, g, g.matches("here (?:u|you) go") || g.equals("take this")));
        }
        if ((m = find(LEAVE_GROUP, t)) != null) {
            String g = m.group(1);
            String before = t.substring(0, m.start(1)).trim();
            if (!before.matches(".*\\b(she|he|they|u|you|someone|who|ppl|people|them|him|her|\\w+ed)$")) {
                out.add(new Claim(Kind.LEAVE_GROUP, -1, null, g.trim(), g.trim().endsWith("out")));
            }
        }
        out.sort(Comparator.comparing(Claim::weak));
        return out;
    }

    private static final Pattern LATER = Pattern.compile("\\b(after (?:that|this)|later|tomorrow|tmr|next time)\\b");

    /**
     * "still/just/been training at X" claims only about the speaker: "im still training at ant" does, "lv 40 sin still
     * training at henesys? lol" and "u still training at kerning?" (a roast, a question to someone else) do not.
     */
    private static boolean aboutMe(String t, Matcher m) {
        String hit = t.substring(m.start()).trim();
        if (!hit.matches("^(?:still|just|been) .*")) {
            return true;
        }
        String before = t.substring(0, m.start()).trim();
        return before.isEmpty() || before.matches(".*(?:^|[\\s,.!])(?:i|im|i'?m|i am|ive|i'?ve|me|and|so|ya|yeah|lol)$");
    }

    private static final Pattern NEG = Pattern.compile("\\b(no|not|dont|don'?t|cant|can'?t|cannot|never|wont|won'?t|"
            + "nah|aint|ain'?t|stop|wasnt|wasn'?t|isnt|isn'?t)\\b");

    /** True when one of the three words before {@code at} negates the claim ("no buff for u", "not coming"). */
    static boolean negated(String t, int at) {
        String before = t.substring(0, Math.max(0, at)).trim();
        String[] w = before.isEmpty() ? new String[0] : before.split(" ");
        StringBuilder last = new StringBuilder();
        for (int i = Math.max(0, w.length - 3); i < w.length; i++) {
            last.append(w[i]).append(' ');
        }
        return NEG.matcher(last).find();
    }

    /** The first match of {@code p} in {@code t} that is not negated, or null. */
    private static Matcher find(Pattern p, String t) {
        Matcher m = p.matcher(t);
        while (m.find()) {
            int g = m.groupCount() >= 1 && m.start(1) >= 0 ? m.start(1) : m.start();
            if (!negated(t, g)) {
                return m;
            }
        }
        return null;
    }

    private static Claim goTo(String t) {
        Matcher m = GO_TO.matcher(t);
        while (m.find()) {
            int map = place(m.group(1));
            if (map > 0) {
                return new Claim(Kind.GO_TO, map, null, m.group().trim(), false);
            }
        }
        m = GO_TO_ANYWHERE.matcher(t);
        while (m.find()) {
            int map = place(m.group(1));
            if (map > 0) {
                // "thinking about heading to orbis after this": a plan, not a promise
                boolean weak = t.contains("thinking about") || t.contains("maybe") || t.contains("might")
                        || t.contains("should") || t.contains("later") || t.contains("tomorrow");
                return new Claim(Kind.GO_TO, map, null, m.group().trim(), weak);
            }
        }
        return null;
    }

    /** The map a place name at the start of {@code words} names, or -1. */
    public static int place(String words) {
        if (words == null) {
            return -1;
        }
        String w = " " + words.trim().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        if (w.startsWith(" the ")) {
            w = w.substring(4);
        }
        int alias = -1;
        int aliasWords = 0;
        for (Map.Entry<String, Integer> e : PLACES) {
            if (w.startsWith(" " + e.getKey() + " ") || w.startsWith(" the " + e.getKey() + " ")) {
                alias = e.getValue();
                aliasWords = e.getKey().split(" ").length;
                break;
            }
        }
        // A real map's full name that runs past the alias wins: "henesys hunting ground i" is 104040000, not the
        // town Henesys (t-cv-1 judged "lvling at Henesys Hunting Ground I" as never going to 100000000).
        String[] ws = w.trim().split(" ");
        ConvoEntities.Names n = ConvoEntities.names();
        if (n != null) {
            for (int len = Math.min(6, ws.length); len > Math.max(1, aliasWords); len--) {
                Integer id = n.maps().get(String.join(" ", java.util.Arrays.copyOfRange(ws, 0, len)));
                if (id != null) {
                    return id;
                }
            }
        }
        if (alias > 0 && ws.length > aliasWords && FIELD_AFTER_TOWN.matcher(
                String.join(" ", java.util.Arrays.copyOfRange(ws, aliasWords, ws.length))).lookingAt()) {
            return -1; // a field named after the town ("kerning city subway") the name table doesn't know: no claim
        }
        return alias;
    }

    /** Words that make a town's name the name of a field next to it ("henesys hunting ground", "perion street"). */
    private static final Pattern FIELD_AFTER_TOWN = Pattern.compile("(?:hunting ground|construction site|subway|"
            + "dungeon|field|forest|street|tower|outskirts|swamp|road|east|west|north|south|hill|culvert|"
            + "rocky|cave|deep|tree)\\b");

    /** The tails merchant ads end with ("hotties only", "dont waste my time"): not part of the item's name. */
    static final String AD_TAIL = "\\b(?:just trade me|trade me|you offer|pm me|no lowballs?|no noobs|no scammers|pros only|"
            + "hotties only|baddies only|no nx h0es|no weebs|english only|no spanish|serious offers only|"
            + "don'?t waste my time|legit only|no time wasters|asap|fast)\\b.*$";

    static String item(String words) {
        if (words == null) {
            return "";
        }
        String w = words.replaceFirst("^[>\\s]+", "") // "S>> ilbis", "Selling> ilbis"
                .replaceFirst("^\\d+(?:[.,]\\d+)*[kmb]?\\s+(?=[a-z])", "") // "10k nx code", "10,000 nx code": a count
                .replaceAll(AD_TAIL, "")
                .replaceAll("\\b(for|at|@|pm me|pm|cheap|offer|offers|c/o|b/o|\\d+(?:[.,]\\d+)?[kmb]?(?![%\\d]))\\b.*$", "")
                .replaceAll("[^a-z0-9% ]", " ").replaceAll("\\s+", " ").trim();
        return w.length() > 30 ? w.substring(0, 30).trim() : w;
    }

    /** Whether two maps are in the same town area ({@code id / 1,000,000}, the Free Market rooms count as the FM). */
    public static boolean sameArea(int a, int b) {
        if (a <= 0 || b <= 0) {
            return false;
        }
        if (isFm(a) || isFm(b)) {
            return isFm(a) && isFm(b);
        }
        return a / 1_000_000 == b / 1_000_000;
    }

    static boolean isFm(int map) {
        return map >= 910000000 && map <= 910000099;
    }
}
