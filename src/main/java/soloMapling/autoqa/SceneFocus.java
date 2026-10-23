package soloMapling.autoqa;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rc6 inference checks: do the bots on a staged scene stay on the scene? Pure functions over a check's lines (or a
 * {@code !qa} log read with {@link #parseQaLog}), so they are unit-tested on real-looking log lines
 * ({@code SceneFocusTest}) and turned into expectations in {@link AutoQaCatalog}:
 * <ul>
 *   <li><b>Weddings (we-1):</b> the share of guest lines during the ceremony that are about the wedding (a keyword set,
 *   the couple's names), whether the guests stayed to the end, whether the officiant, the vows, the rings and the
 *   "married" came in order, and the longest gap between those beats.</li>
 *   <li><b>Guild wars (gw-1):</b> kills and jabs per side, and how many lines of the two guilds' members talk about
 *   the war.</li>
 *   <li><b>KS wars (rv-2):</b> which called bots really took a mob from the player.</li>
 * </ul>
 */
public final class SceneFocus {

    private SceneFocus() {
    }

    // ── reading a !qa log ──

    private static final Pattern QA_LINE = Pattern.compile("^(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) (\\S+) (.*)$");

    /** A {@code logs/qa/<id>-<UTC>.log} file as lines ("HH:mm:ss.SSS KIND message"; headers skipped). */
    public static List<AutoQaLine> parseQaLog(List<String> fileLines) {
        List<AutoQaLine> out = new ArrayList<>();
        long dayOffset = 0;
        long last = -1;
        for (String l : fileLines) {
            Matcher m = QA_LINE.matcher(l);
            if (!m.matches()) {
                continue;
            }
            long t = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))
                    .toSecondOfDay() * 1000L + Integer.parseInt(m.group(4));
            if (last >= 0 && t + dayOffset < last) {
                dayOffset += 86_400_000L; // past midnight
            }
            last = t + dayOffset;
            out.add(new AutoQaLine(last, "qa", m.group(5), m.group(6)));
        }
        return out;
    }

    /** A chat line as speaker and text: {@code BOT_CHAT name: text}, a heard {@code CHAT name: text}. */
    record Said(long at, String who, String text) {
    }

    private static Said said(AutoQaLine l) {
        int i = l.message().indexOf(": ");
        if (i <= 0) {
            return null;
        }
        return new Said(l.timeMs(), l.message().substring(0, i).trim(), l.message().substring(i + 2));
    }

    // ── weddings ──

    /** Words that make a line about the wedding. */
    static final Pattern WEDDING_WORDS = Pattern.compile("(?i)\\b(wedding|weddings|wed|married|marry|marriage|"
            + "couple|bride|brides|groom|grooms|rings?|vows?|i do|cake|bouquet|tissues?|cry|crying|cute|cutest|adorable|"
            + "congrats?|congratulations|grats|gratz|kiss|forever|altar|dressed|dress|tux|tuxedo|happy for|aww+|love|"
            + "lovely|ceremony|officiant|gathered|after ?party|party after|claps?|beautiful|honeymoon|newlyweds?|husband|"
            + "wife|spouse|soulmates?|together|chapel|amoria|invite|invited|guests?|reception|toast|speech|gz|gratz|flowers?|throws|t_t|<3|\\^\\^)\\b"
            + "|<3|t_t|\\*claps\\*|;_;");

    /** Whether a line is about the wedding: a wedding word, or the couple's names (or "them"/"those two"). */
    public static boolean weddingTopic(String text, Collection<String> coupleNames) {
        if (text == null) {
            return false;
        }
        if (WEDDING_WORDS.matcher(text).find()) {
            return true;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String n : coupleNames) {
            if (n != null && n.length() >= 3 && t.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return t.matches(".*\\b(them|those two|these two|the two of them|they)\\b.*");
    }

    /** The we-1 measures. {@code gaps}: seconds between the ceremony beats, in order. */
    public record Wedding(boolean found, String groom, String bride, String officiant, int guestLines, int onTopic,
                          List<String> offTopic, int guests, int stayed, boolean guestsKnown, List<String> left,
                          boolean inOrder, String order, List<Long> gaps, long maxGapSeconds, boolean usedHeard) {
        public double share() {
            return guestLines == 0 ? 1 : (double) onTopic / guestLines;
        }
    }

    static final Pattern BEGINS = Pattern.compile("WEDDING #(\\d+) begins: (\\S+) & (\\S+), (\\d+) guests");
    static final Pattern CAST = Pattern.compile("WEDDING #\\d+ cast: officiant (\\S+); guests (.*)$");
    static final Pattern AT_END = Pattern.compile("WEDDING #\\d+ guests at the end: (\\d+) of (\\d+) on the map(?:; left: (.*))?");
    static final Pattern OFFICIANT_LINE = Pattern.compile("(?i)(we are gathered|step up to the altar|exchange the rings|"
            + "i now pronounce|congratulations .* <3|dearly beloved)");

    /**
     * The wedding in a check's lines. Chat is what player 1 heard on the ceremony map ({@code heard:1 CHAT}) when the
     * check has it, else the {@code BOT_CHAT} lines of the run's log; the couple's and the officiant's own lines are
     * not guest lines.
     */
    public static Wedding wedding(List<AutoQaLine> lines) {
        Long begin = null, end = null;
        String groom = null, bride = null, officiant = null;
        int guestCount = -1;
        Set<String> guestNames = new LinkedHashSet<>();
        Integer stayed = null, ofGuests = null;
        List<String> left = new ArrayList<>();
        for (AutoQaLine l : lines) {
            if (!"COUPLE".equals(l.kind())) {
                continue;
            }
            Matcher m = BEGINS.matcher(l.message());
            if (m.find() && begin == null) {
                begin = l.timeMs();
                groom = m.group(2);
                bride = m.group(3);
                guestCount = Integer.parseInt(m.group(4));
                continue;
            }
            m = CAST.matcher(l.message());
            if (m.find()) {
                officiant = m.group(1);
                for (String g : m.group(2).split(",\\s*")) {
                    if (!g.isBlank() && !"-".equals(g.trim())) {
                        guestNames.add(g.trim());
                    }
                }
                continue;
            }
            m = AT_END.matcher(l.message());
            if (m.find()) {
                stayed = Integer.parseInt(m.group(1));
                ofGuests = Integer.parseInt(m.group(2));
                if (m.group(3) != null) {
                    for (String g : m.group(3).split(",\\s*")) {
                        left.add(g.trim());
                    }
                }
                continue;
            }
            if (begin != null && end == null && l.message().matches("WEDDING #\\d+ ends.*")) {
                end = l.timeMs();
            }
        }
        if (begin == null) {
            return new Wedding(false, null, null, null, 0, 0, List.of(), 0, 0, false, List.of(), false,
                    "no WEDDING begins line", List.of(), 0, false);
        }
        long from = begin, to = end == null ? Long.MAX_VALUE : end;
        if (officiant == null) { // an older log without the cast line: the speaker of the officiant's set lines
            for (AutoQaLine l : lines) {
                Said s = "COUPLE".equals(l.kind()) && l.message().startsWith("SAY ") ? said(new AutoQaLine(l.timeMs(),
                        l.source(), l.kind(), l.message().substring(4))) : null;
                if (s != null && OFFICIANT_LINE.matcher(s.text()).find()) {
                    officiant = s.who();
                    break;
                }
            }
        }
        boolean heard = lines.stream().anyMatch(l -> "heard:1".equals(l.source()) && "CHAT".equals(l.kind())
                && l.timeMs() >= from && l.timeMs() <= to);
        Set<String> notGuests = new HashSet<>();
        for (String n : new String[]{groom, bride, officiant}) {
            if (n != null) {
                notGuests.add(n.toLowerCase(Locale.ROOT));
            }
        }
        List<String> couple = List.of(groom, bride);
        int guestLines = 0, onTopic = 0;
        List<String> off = new ArrayList<>();
        for (AutoQaLine l : lines) {
            if (l.timeMs() < from || l.timeMs() > to) {
                continue;
            }
            boolean chat = heard ? "heard:1".equals(l.source()) && "CHAT".equals(l.kind()) : "BOT_CHAT".equals(l.kind());
            if (!chat) {
                continue;
            }
            Said s = said(l);
            if (s == null || notGuests.contains(s.who().toLowerCase(Locale.ROOT)) || s.who().startsWith("AutoQa")) {
                continue;
            }
            guestLines++;
            if (weddingTopic(s.text(), couple)) {
                onTopic++;
            } else if (off.size() < 8) {
                off.add(s.who() + ": " + s.text());
            }
        }
        // the beats: officiant opener, groom's vow, bride's vow, rings, married
        Long tOff = null, tGroom = null, tBride = null, tRings = null, tMarried = null;
        for (AutoQaLine l : lines) {
            if (!"COUPLE".equals(l.kind()) || l.timeMs() < from || l.timeMs() > to) {
                continue;
            }
            String msg = l.message();
            if (msg.startsWith("SAY ")) {
                Said s = said(new AutoQaLine(l.timeMs(), l.source(), l.kind(), msg.substring(4)));
                if (s == null) {
                    continue;
                }
                if (tOff == null && s.who().equalsIgnoreCase(String.valueOf(officiant))
                        && OFFICIANT_LINE.matcher(s.text()).find()) {
                    tOff = s.at();
                } else if (tGroom == null && s.who().equalsIgnoreCase(groom)) {
                    tGroom = s.at();
                } else if (tBride == null && s.who().equalsIgnoreCase(bride)) {
                    tBride = s.at();
                }
            } else if (tRings == null && msg.startsWith("RINGS ")) {
                tRings = l.timeMs();
            } else if (tMarried == null && msg.startsWith("MARRIED ")) {
                tMarried = l.timeMs();
            }
        }
        Long[] beats = {tOff, tGroom, tBride, tRings, tMarried};
        String[] names = {"officiant", "groom's vow", "bride's vow", "rings", "married"};
        boolean inOrder = true;
        StringBuilder order = new StringBuilder();
        List<Long> gaps = new ArrayList<>();
        long maxGap = 0;
        Long prev = begin;
        for (int i = 0; i < beats.length; i++) {
            if (beats[i] == null) {
                inOrder = false;
                order.append(order.length() == 0 ? "" : " ").append("missing ").append(names[i]).append(';');
                continue;
            }
            if (prev != null && beats[i] < prev) {
                inOrder = false;
                order.append(order.length() == 0 ? "" : " ").append(names[i]).append(" out of order;");
            }
            if (i > 0 && prev != null) {
                long g = Math.max(0, (beats[i] - prev) / 1000);
                gaps.add(g);
                maxGap = Math.max(maxGap, g);
            }
            prev = beats[i];
        }
        if (inOrder) {
            order.append("officiant -> vows -> rings -> married");
        }
        boolean known = stayed != null && ofGuests != null;
        int guests = known ? ofGuests : Math.max(0, guestCount - 1);
        return new Wedding(true, groom, bride, officiant, guestLines, onTopic, off, guests, known ? stayed : guests,
                known, left, inOrder, order.toString().trim(), gaps, maxGap, heard);
    }

    // ── guild wars ──

    public record War(boolean found, String sideA, String sideB, Map<String, Integer> kills, Map<String, Integer> jabs,
                      Map<String, Integer> warTalk, boolean killsLogged, List<String> examples) {
    }

    static final Pattern DECLARED = Pattern.compile("WAR DECLARED (\\S+) (?:vs|on player guild) (\\S+)");
    static final Pattern MEMBER = Pattern.compile("^(?:WAR TAUNT |TURF |KILL |JAB |MEGA JAB )?(\\S+) \\[([^\\]]+)]");
    static final Pattern WAR_WORDS = Pattern.compile("(?i)\\b(war|wars|turf|guild|guilds|gg|ez|losers?|our map|"
            + "their map|hold|holds|holding|score|winning|losing|lost|win|beat|owned|pwned|noobs?|scrubs?|trash|"
            + "stuck|weak|carry|carried|bring it|come at|fight|fighting|ks|ksing)\\b");

    /** Kills and jabs per side, and the war talk of the two sides' members. */
    public static War guildWar(List<AutoQaLine> lines) {
        String a = null, b = null;
        for (AutoQaLine l : lines) {
            Matcher m = DECLARED.matcher(l.message());
            if ("GUILD_WAR".equals(l.kind()) && m.find()) {
                a = m.group(1);
                b = m.group(2);
                break;
            }
        }
        if (a == null) {
            return new War(false, null, null, Map.of(), Map.of(), Map.of(), false, List.of());
        }
        Map<String, String> sideOf = new LinkedHashMap<>();
        Map<String, Integer> kills = new LinkedHashMap<>(Map.of(a, 0, b, 0));
        Map<String, Integer> jabs = new LinkedHashMap<>(Map.of(a, 0, b, 0));
        Map<String, Integer> talk = new LinkedHashMap<>(Map.of(a, 0, b, 0));
        boolean killsLogged = false;
        List<String> examples = new ArrayList<>();
        for (AutoQaLine l : lines) {
            if (!"GUILD_WAR".equals(l.kind()) && !"BOT_GUILD".equals(l.kind())) {
                continue;
            }
            Matcher m = MEMBER.matcher(l.message());
            if (!m.find()) {
                continue;
            }
            String who = m.group(1), guild = m.group(2);
            if (!guild.equals(a) && !guild.equals(b)) {
                continue;
            }
            sideOf.put(who, guild);
            String msg = l.message();
            if (msg.startsWith("KILL ")) {
                kills.merge(guild, 1, Integer::sum);
                killsLogged = true;
            } else if (msg.startsWith("JAB ") || msg.startsWith("MEGA JAB ") || msg.startsWith("WAR TAUNT ")) {
                jabs.merge(guild, 1, Integer::sum);
                talk.merge(guild, 1, Integer::sum);
                if (examples.size() < 4) {
                    examples.add(msg);
                }
            }
        }
        for (AutoQaLine l : lines) {
            if (!"BOT_CHAT".equals(l.kind()) && !"CHAT".equals(l.kind())) {
                continue;
            }
            Said s = said(l);
            if (s == null || !sideOf.containsKey(s.who())) {
                continue;
            }
            String other = sideOf.get(s.who()).equals(a) ? b : a;
            boolean jabLine = lines.stream().anyMatch(j -> j.timeMs() == l.timeMs() && j.message().contains(s.text())
                    && j.message().startsWith("JAB "));
            if (jabLine) {
                continue; // counted as a jab already
            }
            if (WAR_WORDS.matcher(s.text()).find() || s.text().toLowerCase(Locale.ROOT).contains(other.toLowerCase(Locale.ROOT))) {
                talk.merge(sideOf.get(s.who()), 1, Integer::sum);
                if (examples.size() < 6) {
                    examples.add(s.who() + ": " + s.text());
                }
            }
        }
        return new War(true, a, b, kills, jabs, talk, killsLogged, examples);
    }

    // ── KS wars ──

    public record KsWar(boolean found, List<String> called, Map<String, Integer> took) {
        public int tookAny() {
            int n = 0;
            for (String c : called) {
                if (took.getOrDefault(c, 0) > 0) {
                    n++;
                }
            }
            return n;
        }
    }

    static final Pattern CALLS = Pattern.compile("KS WAR \\S+ calls \\d+ bot\\(s\\) on \\S+ \\(([^)]*)\\)");
    static final Pattern TAKES = Pattern.compile("^KS (\\S+) takes ");

    /** The bots the KS war called and how many mobs each took ({@code AFFINITY KS <bot> takes ...}). */
    public static KsWar ksWar(List<AutoQaLine> lines) {
        List<String> called = null;
        long from = 0;
        for (AutoQaLine l : lines) {
            Matcher m = CALLS.matcher(l.message());
            if ("RIVALRY".equals(l.kind()) && m.find()) {
                called = new ArrayList<>();
                for (String n : m.group(1).split(",\\s*")) {
                    if (!n.isBlank()) {
                        called.add(n.trim());
                    }
                }
                from = l.timeMs();
                break;
            }
        }
        if (called == null) {
            return new KsWar(false, List.of(), Map.of());
        }
        Map<String, Integer> took = new LinkedHashMap<>();
        for (String c : called) {
            took.put(c, 0);
        }
        for (AutoQaLine l : lines) {
            Matcher m = TAKES.matcher(l.message());
            if ("AFFINITY".equals(l.kind()) && l.timeMs() >= from && m.find() && took.containsKey(m.group(1))) {
                took.merge(m.group(1), 1, Integer::sum);
            }
        }
        return new KsWar(true, called, took);
    }

    // ── as expectations ──

    /** we-1: under 60% of guest lines on topic, or over half the guests gone early, or beats out of order, FAIL. */
    public static final double WEDDING_MIN_ON_TOPIC = 0.60;
    public static final long WEDDING_MAX_GAP_S = 60;
    public static final int WEDDING_MIN_GUEST_LINES = 5;

    static Check.Expect weddingFocus() {
        return Expects.of("wedding focus: >= " + Math.round(WEDDING_MIN_ON_TOPIC * 100) + "% of guest lines on topic, "
                + "most guests stay, beats in order, no gap over " + WEDDING_MAX_GAP_S + " s", r -> {
            Wedding w = wedding(r.lines());
            if (!w.found()) {
                return Check.Verdict.fail("no wedding in the log");
            }
            List<String> ev = new ArrayList<>();
            List<String> bad = new ArrayList<>();
            ev.add("guest lines on topic: " + w.onTopic() + " of " + w.guestLines() + " ("
                    + Math.round(w.share() * 100) + "%, from " + (w.usedHeard() ? "what the player heard" : "BOT_CHAT") + ")");
            if (w.guestLines() >= WEDDING_MIN_GUEST_LINES && w.share() < WEDDING_MIN_ON_TOPIC) {
                bad.add("only " + Math.round(w.share() * 100) + "% of guest lines about the wedding");
                for (String o : w.offTopic()) {
                    ev.add("  off topic: " + o);
                }
            }
            ev.add("guests at the end: " + (w.guestsKnown() ? w.stayed() + " of " + w.guests() : "not logged")
                    + (w.left().isEmpty() ? "" : " (left: " + String.join(", ", w.left()) + ")"));
            if (w.guestsKnown() && w.guests() > 0 && (w.guests() - w.stayed()) * 2 > w.guests()) {
                bad.add((w.guests() - w.stayed()) + " of " + w.guests() + " guests left early");
            }
            ev.add("order: " + w.order() + "; gaps " + w.gaps() + " s");
            if (!w.inOrder()) {
                bad.add("ceremony out of order: " + w.order());
            }
            if (w.maxGapSeconds() > WEDDING_MAX_GAP_S) {
                bad.add("a " + w.maxGapSeconds() + " s gap between ceremony beats");
            }
            if (!bad.isEmpty()) {
                ev.add(0, String.join("; ", bad));
                return new Check.Verdict(Check.Status.FAIL, ev);
            }
            return new Check.Verdict(Check.Status.PASS, ev);
        });
    }

    /** gw-1: both sides jab (and, where kills are logged, kill) and talk about the war. */
    static Check.Expect warFocus() {
        return Expects.of("war focus: both sides fight (kills, jabs) and talk about the war", r -> {
            War w = guildWar(r.lines());
            if (!w.found()) {
                return Check.Verdict.fail("no WAR DECLARED line");
            }
            List<String> ev = new ArrayList<>();
            List<String> bad = new ArrayList<>();
            for (String side : List.of(w.sideA(), w.sideB())) {
                ev.add(side + ": " + w.kills().get(side) + " kill(s), " + w.jabs().get(side) + " jab(s)/taunt(s), "
                        + w.warTalk().get(side) + " war line(s)");
                if (w.jabs().get(side) == 0 && w.warTalk().get(side) == 0) {
                    bad.add(side + " never said a word about the war");
                }
                if (w.killsLogged() && w.kills().get(side) == 0) {
                    bad.add(side + " killed nothing");
                }
            }
            if (!w.killsLogged()) {
                ev.add("no KILL lines: kills not judged");
            }
            for (String e : w.examples()) {
                ev.add("  " + e);
            }
            if (!bad.isEmpty()) {
                ev.add(0, String.join("; ", bad));
                return new Check.Verdict(Check.Status.FAIL, ev);
            }
            return new Check.Verdict(Check.Status.PASS, ev);
        });
    }

    /** rv-2: at least half the called bots really took a mob from the player. */
    static Check.Expect ksFocus() {
        return Expects.of("KS focus: at least half the called bots take a mob from the player", r -> {
            KsWar k = ksWar(r.lines());
            if (!k.found()) {
                return Check.Verdict.fail("no KS WAR call");
            }
            String line = k.tookAny() + " of " + k.called().size() + " called bots took mobs " + k.took();
            return k.tookAny() * 2 >= k.called().size() && k.tookAny() > 0 ? Check.Verdict.pass(line)
                    : Check.Verdict.fail(line);
        });
    }
}
