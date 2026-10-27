package soloMapling.autoqa;

import soloMapling.autoqa.SocialTraceEntry.Event;
import soloMapling.autoqa.SocialTraceEntry.Line;
import soloMapling.autoqa.SocialTraceEntry.Stage;
import soloMapling.autoqa.SocialTraceEntry.Status;
import soloMapling.autoqa.SocialTraceEntry.Verdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 1.6.0 (owner 2026-10-26): the social trace's stage rules - pure, so {@code SocialTraceJudgeTest} pins them. Given an
 * entry's raw facts it says, per stage, ok / fail / none (no code does this step) / n/a, with a short reason.
 *
 * <p>The DECIDE table is what the code does today (read 2026-10-26): a bot thread changes the two bots' bond, and
 * since 1.6.0 a GOSSIP listener keeps the story ({@code BotGossip.keep}, act "kept") and a RIVALRY thread may escalate
 * ({@code BotBotRivalry.decideEscalation}, act "escalate"); the thread records that decision, or why there was none
 * ({@link #THREAD_ACTS}). No LFG thread becomes a party or PQ trip, no TRADE thread a trade. When one of those links is
 * built, it records its decision the same way and ACT starts judging it.
 */
public final class SocialTraceJudge {

    /** ACT: how long after an episode its follow-up may come. */
    public static final long ACT_WINDOW_MS = TimeUnit.MINUTES.toMillis(15);
    /** SPREAD: how long a third bot has to repeat a story. */
    public static final long SPREAD_WINDOW_MS = TimeUnit.MINUTES.toMillis(60);
    /** The thread prompt asks for lines under 60 characters. */
    public static final int THREAD_MAX_CHARS = 60;
    /** Drama lines are pack lines: the chat's own limit. */
    public static final int DRAMA_MAX_CHARS = 90;

    /** What the rules need from the game, as functions (the tests pass plain ones). */
    public record Rules(Function<String, String> contentProblem, BiFunction<String, String, List<String>> stiff) {
    }

    /** A decision an episode can lead to in code today: what it is, and the event kinds that show it was carried out. */
    public record Decision(String decision, Set<String> actKinds) {
    }

    private SocialTraceJudge() {
    }

    static String iso(long ms) {
        return ms <= 0 ? null : Instant.ofEpochMilli(ms).toString();
    }

    // ── closing ──

    /** When an entry closes: after its SPREAD window when it has a story to spread, else after its ACT window. */
    static long closesAt(long startedAt, boolean spreads) {
        return startedAt + (spreads ? SPREAD_WINDOW_MS : ACT_WINDOW_MS);
    }

    /** Whether the entry is due to close at {@code now}. */
    static boolean due(SocialTraceEntry e, long now) {
        return now >= e.closesAt;
    }

    // ── the verdicts ──

    static EnumMap<Stage, Verdict> judge(SocialTraceEntry e, Rules rules) {
        EnumMap<Stage, Verdict> v = new EnumMap<>(Stage.class);
        synchronized (e) {
            boolean thread = "thread".equals(e.kind);
            v.put(Stage.TRIGGER, trigger(e));
            v.put(Stage.COMPOSE, thread ? composeThread(e) : composeDrama(e));
            v.put(Stage.DELIVER, deliver(e, rules, thread));
            v.put(Stage.SENSE, sense(e, rules, thread));
            Decision d = decision(e);
            v.put(Stage.DECIDE, decide(e, d));
            v.put(Stage.ACT, act(e, d));
            v.put(Stage.REMEMBER, remember(e));
            v.put(Stage.SPREAD, spread(e));
        }
        return v;
    }

    static Verdict trigger(SocialTraceEntry e) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("why", e.trigger);
        d.put("map", e.mapId);
        d.put("channel", e.channel);
        d.put("watchers", e.watchers);
        if ("thread".equals(e.kind) && e.watchers.isEmpty()) {
            return new Verdict(Status.FAIL, "no real player on the map (threads only start in front of one)", d);
        }
        if (e.trigger == null || e.trigger.isBlank()) {
            return new Verdict(Status.FAIL, "started with no reason recorded", d);
        }
        return new Verdict(Status.OK, e.topic + ": " + e.trigger, d);
    }

    static Verdict composeThread(SocialTraceEntry e) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", e.source);
        d.put("latencyMs", e.composeMs);
        d.put("fallback", e.fallback);
        if (!e.nameGuard.isEmpty()) {
            d.put("nameGuard", List.copyOf(e.nameGuard));
        }
        if (e.source == null) {
            return new Verdict(Status.FAIL, "never composed: the model's reply never came back and no fallback ran", d);
        }
        if ("llm".equals(e.source)) {
            return new Verdict(Status.OK, "llm in " + e.composeMs + " ms", d);
        }
        if (e.modelLines >= 0) {
            d.put("modelLines", e.modelLines);
            return new Verdict(Status.FAIL, "model reply unusable (" + e.modelLines + " usable line(s)), "
                    + e.source + " used", d);
        }
        return new Verdict(Status.OK, e.source + " (" + (e.fallback == null ? "no model" : e.fallback) + ")", d);
    }

    static Verdict composeDrama(SocialTraceEntry e) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", e.source);
        if (e.planned == 0) {
            return new Verdict(Status.NA, "no line in this kind of event", d);
        }
        if (e.lines.isEmpty() && !e.dropped.isEmpty()) {
            return new Verdict(Status.FAIL, "no line: " + e.dropped.get(0), d);
        }
        return new Verdict(Status.OK, e.source == null ? "scripted pack line" : e.source, d);
    }

    private static final Pattern TRUNCATED = Pattern.compile("(?i)([,:;\\-]|\\b(and|or|the|to|a|but|so|of|with|my|ur|your))\\s*$");

    static Verdict deliver(SocialTraceEntry e, Rules rules, boolean thread) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("planned", e.planned);
        d.put("said", e.lines.size());
        if (!e.dropped.isEmpty()) {
            d.put("dropped", List.copyOf(e.dropped));
        }
        List<Long> gaps = new ArrayList<>();
        long prev = e.startedAt;
        for (Line l : e.lines) {
            gaps.add(l.at() - prev);
            prev = l.at();
        }
        d.put("gapsMs", gaps);
        if (!thread && e.planned == 0) {
            return new Verdict(Status.NA, "nothing to say in this kind of event", d);
        }
        if (thread && e.source == null) {
            return new Verdict(Status.FAIL, "never composed", d);
        }
        if (e.lines.isEmpty()) {
            return new Verdict(Status.FAIL, e.dropped.isEmpty() ? "nothing said (no bank line or script fit)"
                    : "nothing said: " + e.dropped.get(0), d);
        }
        List<String> flags = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Line l : e.lines) {
            String t = l.text() == null ? "" : l.text().trim();
            if (t.isEmpty()) {
                flags.add("empty line from " + l.who());
                continue;
            }
            String problem = rules.contentProblem().apply(t);
            if (problem != null) {
                flags.add("filter (" + problem + "): \"" + t + "\"");
            }
            if (!seen.add(norm(t))) {
                flags.add("said twice: \"" + t + "\"");
            }
            if (TRUNCATED.matcher(t).find()) {
                flags.add("looks cut off: \"" + t + "\"");
            }
        }
        for (String r : e.repeats) {
            flags.add("repeat of an earlier thread line: \"" + r + "\"");
        }
        if (thread && e.lines.size() < 2) {
            flags.add(0, "only " + e.lines.size() + " line said" + (e.dropped.isEmpty() ? "" : " (" + e.dropped.get(0) + ")"));
        }
        d.put("flags", flags);
        if (!flags.isEmpty()) {
            return new Verdict(Status.FAIL, flags.get(0), d);
        }
        long span = e.lines.get(e.lines.size() - 1).at() - e.startedAt;
        return new Verdict(Status.OK, e.lines.size() + " line(s) in " + Math.round(span / 1000.0) + " s"
                + (e.dropped.isEmpty() ? "" : ", " + e.dropped.size() + " dropped"), d);
    }

    // ── SENSE ──

    /** Words that show a line is about its thread's topic. */
    static final Map<String, Pattern> TOPIC_WORDS = Map.of(
            "LFG", words("party|pq|kpq|lpq|opq|cpq|lfg|lf|lfm|join|need|ppl|people|lv|lvl|level|kerning|ludi|orbis|"
                    + "carnival|inv|invite|come|go|run|runs"),
            "TRADE", words("sell|selling|sold|buy|buying|bought|s>|b>|wts|wtb|price|offer|meso|mesos|\\d+k|\\d+m|fm|"
                    + "trade|cheap|scroll|scrolls|ilbi|ilbis|gloves|nx|pc|shop|store|lowball|worth|cost"),
            "TRAINING", words("train|training|grind|grinding|exp|xp|map|spot|lvl|level|leveling|mob|mobs|hunt|ant|"
                    + "tunnel|golem|golems|ks|ch\\d*|channel|pots|potions|dmg|damage"),
            "GOSSIP", words("heard|hear|did|lol|omg|really|srsly|fr|news|apparently|guess|rumor|said|says|ks|ksed|"
                    + "scam|scammed|drama|wild|crazy|wow|no way"),
            "GUILD_BEEF", words("guild|guilds|war|wars|clan|gm|alliance|members|turf|map|maps"),
            "RIVALRY", words("ks|ksing|ksed|noob|nub|gear|map|spot|dmg|damage|lvl|level|beef|find|steal|weak|trash|"
                    + "mine|ur|bad|loser|own"),
            "FRIEND_DRAMA", words("quit|quitting|quits|back|broke|break|boom|boomed|villain|drama|left|leave|lol|omg|"
                    + "again|crazy|wild|heard|done"));

    private static Pattern words(String alternatives) {
        return Pattern.compile("(?i)(?<![a-z0-9])(" + alternatives + ")(?![a-z0-9])");
    }

    private static final Set<String> STOP = Set.of("with", "that", "this", "from", "have", "about", "into", "their",
            "they", "them", "were", "been", "after", "again", "runs", "run-ins", "times");

    static Verdict sense(SocialTraceEntry e, Rules rules, boolean thread) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (e.lines.isEmpty()) {
            return new Verdict(Status.NA, "nothing said to judge", d);
        }
        String all = joined(e.lines);
        List<String> flags = new ArrayList<>();
        if (thread) {
            Pattern topic = TOPIC_WORDS.get(e.topic);
            boolean onTopic = topic != null && topic.matcher(all).find() || mentionsAbout(all, e.about)
                    || mentionsAny(all, e.factNames);
            if (!onTopic) {
                flags.add("off topic: no line is about " + e.topic.toLowerCase(Locale.ROOT).replace('_', ' ')
                        + (e.about == null ? "" : " (" + e.about + ")"));
            }
            if (e.factPerson && !e.factNames.isEmpty() && !mentionsAny(all, e.factNames)) {
                flags.add("the story's people are never named (" + String.join(", ", e.factNames) + ")");
            }
            if (!e.nameGuard.isEmpty()) {
                flags.add("named someone outside the story (name guard " + e.nameGuard.size() + "x)");
            }
            String band = lfgBand(e);
            if (band != null) {
                flags.add(band);
            }
            if (e.lines.size() > 4) {
                flags.add(e.lines.size() + " lines (the prompt asks for 2-4)");
            }
        }
        int max = thread ? THREAD_MAX_CHARS : DRAMA_MAX_CHARS;
        for (Line l : e.lines) {
            String t = l.text() == null ? "" : l.text();
            if (t.length() > max) {
                flags.add("long line (" + t.length() + " > " + max + " chars): \"" + t + "\"");
            }
            for (String s : rules.stiff().apply(t, l.voice())) {
                flags.add("stiff (" + s + "): \"" + t + "\"");
            }
        }
        d.put("flags", flags);
        if (!flags.isEmpty()) {
            return new Verdict(Status.FAIL, flags.get(0), d);
        }
        return new Verdict(Status.OK, thread ? "on topic, names fit, length and style ok" : "length and style ok", d);
    }

    /** LifePq's lobby bands (21-30 Kerning, 35-50 Ludi, 51-70 Orbis); an LFG label outside them names a PQ nobody runs. */
    static String lfgBand(SocialTraceEntry e) {
        if (!"LFG".equals(e.topic) || e.about == null || e.minLevel <= 0) {
            return null;
        }
        int lv = e.minLevel;
        String fits = lv >= 21 && lv <= 30 ? "Kerning PQ" : lv >= 35 && lv <= 50 ? "Ludi PQ"
                : lv >= 51 && lv <= 70 ? "Orbis PQ" : null;
        if (fits == null) {
            return "LFG for " + e.about + " at level " + lv + ": no PQ lobby takes that level";
        }
        return fits.equalsIgnoreCase(e.about) ? null : "LFG for " + e.about + " at level " + lv + " (fits " + fits + ")";
    }

    static boolean mentionsAbout(String text, String about) {
        if (about == null) {
            return false;
        }
        String low = text.toLowerCase(Locale.ROOT);
        for (String w : about.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 4 && !STOP.contains(w) && low.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static boolean mentionsAny(String text, List<String> names) {
        String low = text.toLowerCase(Locale.ROOT);
        for (String n : names) {
            if (n != null && n.length() >= 2 && low.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String joined(List<Line> lines) {
        StringBuilder s = new StringBuilder();
        for (Line l : lines) {
            s.append(l.text()).append('\n');
        }
        return s.toString();
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    // ── DECIDE ──

    /**
     * Thread topics: what the code does after the thread (2026-10-26). Every topic: {@code BotBonds.onThread} and
     * nothing else, so none has a decision.
     */
    static final Map<String, String> THREAD_NO_PATH = Map.of(
            "LFG", "no code path turns an LFG thread into a party or a PQ trip (bots never invite bots to a PQ)",
            "TRADE", "no code path turns a TRADE thread into a trade (there is no bot-to-bot trade at all)",
            "TRAINING", "no code path moves either bot to the spot a TRAINING thread names",
            "GOSSIP", "the listener kept no story (nothing was said, or the thread never finished)",
            "RIVALRY", "no escalation was rolled (nothing was said, or the thread never finished)",
            "GUILD_BEEF", "no code path feeds a GUILD_BEEF thread into the guild war (only the bond sours)",
            "FRIEND_DRAMA", "no code path records who heard the drama (it is spoken only)");

    /** Drama kinds with a follow-up in code, and the event that shows it happened. */
    static final Map<String, Decision> DRAMA_DECISIONS = Map.of(
            "KS_SPAT", new Decision("KS spat -> the KSer answers back (rivals always, others half the time)",
                    Set.of("answer")),
            "CRUSH", new Decision("crush -> a couple once the bond is a friendship", Set.of("couple")),
            "COUPLE", new Decision("couple -> party up / hop channel / stand together", Set.of("party", "hop")),
            "REBOUND", new Decision("rebound -> a new couple: party up / hop channel", Set.of("party", "hop")),
            "BREAKUP", new Decision("breakup -> drama news for their friends", Set.of("news")),
            "GUILD_FEUD", new Decision("feud -> one leaves the guild after enough fights", Set.of("leave")),
            "POACH", new Decision("poach -> the bot switches guild", Set.of("switch")));

    static final Map<String, String> DRAMA_NO_PATH = Map.of(
            "CALLOUT", "a callout megaphone has no follow-up (no avoidance, no fight)",
            "TEASE", "a tease is speech only",
            "VILLAIN_NOTICE", "a friend noticing the villain turn is speech only (its SPAT bond is the only effect)",
            "JEALOUS", "jealousy is speech plus a SPAT bond; a breakup only follows if the bond turns hostile",
            "FLIRT", "a crush line is speech only",
            "JAB", "a guild jab is speech only (no bond or war effect)");

    /** Thread topics whose decision is recorded by the code ({@code SocialTrace.threadDecided}), and its act kinds. */
    static final Map<String, Set<String>> THREAD_ACTS = Map.of(
            "GOSSIP", Set.of("kept"),
            "RIVALRY", Set.of("escalate"));

    static Decision decision(SocialTraceEntry e) {
        if ("thread".equals(e.kind)) {
            return e.decided == null ? null : new Decision(e.decided, THREAD_ACTS.getOrDefault(e.topic, Set.of()));
        }
        return DRAMA_DECISIONS.get(e.topic);
    }

    static Verdict decide(SocialTraceEntry e, Decision d) {
        Map<String, Object> data = new LinkedHashMap<>();
        String intent = intent(e);
        data.put("intent", intent);
        data.put("decision", d == null ? null : d.decision());
        if (d != null) {
            return new Verdict(Status.OK, d.decision(), data);
        }
        String why = "thread".equals(e.kind) ? (e.decideWhy != null ? e.decideWhy : THREAD_NO_PATH.get(e.topic))
                : DRAMA_NO_PATH.get(e.topic);
        return new Verdict(Status.NONE, why == null ? "no code path turns this into a decision" : why, data);
    }

    private static final Pattern ITEM = Pattern.compile("(?i)(?:s>+|b>+|wts|wtb|selling|buying|sell|buy)\\s*:?\\s*"
            + "([a-z][a-z' \\-]{2,30}?)(?:\\s+(?:for|@|\\d)|[,!.?]|$)");

    /** What the talk itself said the two would do (the decision a player would expect), or null. */
    static String intent(SocialTraceEntry e) {
        String all = joined(e.lines);
        return switch (e.topic) {
            case "LFG" -> TOPIC_WORDS.get("LFG").matcher(all).find()
                    ? "plan a party for " + (e.about == null ? "a PQ" : e.about) : null;
            case "TRADE" -> {
                var m = ITEM.matcher(all);
                yield m.find() ? "offer " + m.group(1).trim() : TOPIC_WORDS.get("TRADE").matcher(all).find()
                        ? "talk a trade" : null;
            }
            case "TRAINING" -> e.about == null ? "train somewhere" : "train: " + e.about;
            case "GOSSIP", "FRIEND_DRAMA" -> e.factAbout == null ? null
                    : "story now known by " + e.bName + ": " + e.factAbout;
            case "RIVALRY", "GUILD_BEEF", "KS_SPAT", "CALLOUT", "JAB" -> "escalate";
            default -> null;
        };
    }

    // ── ACT ──

    static Verdict act(SocialTraceEntry e, Decision d) {
        Map<String, Object> data = new LinkedHashMap<>();
        long end = e.startedAt + ACT_WINDOW_MS;
        List<Object> inWindow = new ArrayList<>();
        Map<String, List<String>> nextLife = new LinkedHashMap<>();
        boolean carried = false;
        Event done = null;
        Event dropped = null;
        for (Event ev : e.events) {
            if (ev.at() < e.startedAt || ev.at() > end) {
                continue;
            }
            if (inWindow.size() < 30) {
                inWindow.add(Map.of("at", iso(ev.at()), "who", e.nameOf(ev.botId()), "kind", ev.kind(),
                        "text", ev.text()));
            }
            if ("life".equals(ev.kind())) {
                List<String> l = nextLife.computeIfAbsent(e.nameOf(ev.botId()), k -> new ArrayList<>());
                if (l.size() < 3) {
                    l.add(ev.text());
                }
            }
            if (!carried && d != null && d.actKinds().contains(ev.kind())) {
                carried = true;
                done = ev;
            }
            if (dropped == null && d != null && ev.kind().endsWith("-drop")) {
                dropped = ev;
            }
        }
        data.put("windowMin", ACT_WINDOW_MS / 60_000);
        data.put("nextLife", nextLife);
        data.put("events", inWindow);
        data.put("end", e.endState);
        String observed = observed(e);
        if (observed != null) {
            data.put("observed", observed);
        }
        if (d == null) {
            return new Verdict(Status.NONE, "no decision to carry out" + (observed == null ? "" : "; seen anyway: "
                    + observed), data);
        }
        if (carried) {
            return new Verdict(Status.OK, done.kind() + " after " + Math.round((done.at() - e.startedAt) / 1000.0)
                    + " s: " + done.text(), data);
        }
        return new Verdict(Status.FAIL, "not carried out within " + ACT_WINDOW_MS / 60_000 + " min (" + d.decision()
                + ")" + (dropped == null ? "" : ": " + dropped.text()), data);
    }

    /** Something the two did together anyway (a shared party, the same PQ map), from the close-time read-out. */
    static String observed(SocialTraceEntry e) {
        Object party = e.endState.get("sameParty");
        if (Boolean.TRUE.equals(party)) {
            return "in one party at close";
        }
        for (Event ev : e.events) {
            if ("party".equals(ev.kind()) && ev.at() <= e.startedAt + ACT_WINDOW_MS) {
                return "party: " + ev.text();
            }
        }
        return null;
    }

    // ── REMEMBER ──

    static Verdict remember(SocialTraceEntry e) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (!e.remembered.isEmpty()) {
            d.put("written", List.copyOf(e.remembered));
        }
        if ("thread".equals(e.kind) && e.remembered.isEmpty()) {
            d.put("memory", "no LifeMemory.talked episode (nothing said, the pair's talk cooldown, or not finished)");
        }
        String notes = e.remembered.isEmpty() ? "" : "; " + String.join("; ", e.remembered);
        if (e.before == null || e.after == null) {
            return new Verdict(e.remembered.isEmpty() ? Status.NONE : Status.OK, e.remembered.isEmpty()
                    ? "no bond read and nothing written" : e.remembered.get(0), d);
        }
        d.put("bondBefore", bond(e.before));
        d.put("bondAfter", bond(e.after));
        double moved = Math.abs(e.after.ab() - e.before.ab()) + Math.abs(e.after.ba() - e.before.ba());
        String text = e.aName + "->" + e.bName + " " + fmt(e.before.ab()) + "->" + fmt(e.after.ab()) + " ("
                + e.after.stateAb() + "), " + e.bName + "->" + e.aName + " " + fmt(e.before.ba()) + "->"
                + fmt(e.after.ba()) + " (" + e.after.stateBa() + ")";
        if (moved < 0.01) {
            return new Verdict(Status.FAIL, "bond unchanged (pair cooldown, bonds off, or a weaker bond dropped): "
                    + text + notes, d);
        }
        return new Verdict(Status.OK, "bond " + text + notes, d);
    }

    private static Map<String, Object> bond(SocialTraceEntry.Bond b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ab", Math.round(b.ab() * 100) / 100.0);
        m.put("ba", Math.round(b.ba() * 100) / 100.0);
        m.put("stateAb", b.stateAb());
        m.put("stateBa", b.stateBa());
        return m;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ── SPREAD ──

    static Verdict spread(SocialTraceEntry e) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (e.spreadKey == null) {
            return new Verdict(Status.NA, "nothing a third bot would repeat", d);
        }
        d.put("story", e.spreadKey);
        d.put("windowMin", SPREAD_WINDOW_MS / 60_000);
        if (!e.retold.isEmpty()) {
            d.put("retold", List.copyOf(e.retold));
        }
        if (!e.spread.isEmpty()) {
            d.put("repeats", List.copyOf(e.spread));
        }
        if ("GOSSIP".equals(e.topic)) { // 1.6.0: the listener keeps the story; only a real retelling of it counts
            if (!e.retold.isEmpty()) {
                return new Verdict(Status.OK, "retold: " + e.retold.get(0), d);
            }
            return new Verdict(Status.FAIL, "nobody retold the story it heard within " + SPREAD_WINDOW_MS / 60_000
                    + " min" + (e.spread.isEmpty() ? "" : " (only an independent telling: " + e.spread.get(0) + ")"), d);
        }
        if (!e.spread.isEmpty()) {
            return new Verdict(Status.OK, "repeated: " + e.spread.get(0), d);
        }
        return new Verdict(Status.FAIL, "no third bot repeated it within " + SPREAD_WINDOW_MS / 60_000
                + " min (only independent pulls of the same news or drama count here)", d);
    }

    /**
     * Whether a later thread repeats this entry's story: a different teller (neither of this entry's two bots) whose
     * grounding story names the same people or is the same story.
     */
    static boolean repeats(SocialTraceEntry earlier, int tellerId, String about, List<String> names) {
        if (earlier.spreadKey == null || earlier.involves(tellerId)) {
            return false;
        }
        if (about != null && norm(about).equals(norm(earlier.spreadKey))) {
            return true;
        }
        List<String> subjects = earlier.spreadNames;
        if (subjects.isEmpty() || names == null) {
            return false;
        }
        Set<String> theirs = new HashSet<>();
        for (String n : names) {
            theirs.add(n.toLowerCase(Locale.ROOT));
        }
        for (String s : subjects) {
            if (!theirs.contains(s.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
