package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One check while it runs: the world, the synthetic players, when it started, the marks its steps set, the bots its
 * scenario staged ({@code {bot}}, {@code {bot2}} ...) and other variables, and the notes that become its evidence.
 */
public final class CheckRun {

    static final Pattern BOT_ID = Pattern.compile("\\b(1\\d{9})\\b");

    final Check check;
    final AutoQaWorld world;
    final long startMs;
    private final Map<String, Long> marks = new LinkedHashMap<>();
    private final Map<String, String> vars = new LinkedHashMap<>();
    private final Set<Integer> stagedBots = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();
    private final Map<Integer, AutoQaWorld.Actor> actors = new LinkedHashMap<>();
    private final List<String> started = new ArrayList<>(); // scenarios to stop at cleanup, per actor slot
    int judgeCalls;
    private long stepStartMs;
    private long previousStepStartMs;

    CheckRun(Check check, AutoQaWorld world) {
        this.check = check;
        this.world = world;
        this.startMs = world.now();
    }

    public AutoQaWorld world() {
        return world;
    }

    public AutoQaWorld.Actor actor(int slot) {
        return actors.computeIfAbsent(slot, s -> {
            AutoQaWorld.Actor a = world.actor(s);
            vars.put(s == 1 ? "me" : "p" + s, a.name());
            return a;
        });
    }

    boolean hasActor(int slot) {
        return actors.containsKey(slot);
    }

    void startedScenario(int slot) {
        started.add(String.valueOf(slot));
    }

    List<Integer> slotsWithScenario() {
        List<Integer> out = new ArrayList<>();
        for (String s : new LinkedHashSet<>(started)) {
            out.add(Integer.parseInt(s));
        }
        return out;
    }

    /** The runner calls this as each step starts. */
    void stepStarting() {
        previousStepStartMs = stepStartMs == 0 ? startMs : stepStartMs;
        stepStartMs = world.now();
    }

    /**
     * When the step before the current one started: a wait looks at lines from there on, so a line the previous
     * step caused (the bot answered while the player was still typing) is not missed.
     */
    public long previousStepStart() {
        return previousStepStartMs == 0 ? startMs : previousStepStartMs;
    }

    public void mark(String name) {
        marks.put(name, world.now());
    }

    /** When the mark was set; the check's start when it never was. */
    public long markTime(String name) {
        return name == null ? startMs : marks.getOrDefault(name, Long.MAX_VALUE);
    }

    public void note(String line) {
        notes.add(line);
    }

    public List<String> notes() {
        return notes;
    }

    public void set(String var, String value) {
        vars.put(var, value);
    }

    public String var(String var) {
        return vars.get(var);
    }

    /** A scenario's staging line "VAR name=value" (logged "STAGE told GM: VAR name=value"): sets {name}. */
    static final Pattern STAGE_VAR = Pattern.compile("(?:^|told GM: )VAR ([a-z][a-z0-9]*)=(\\S.*)$");

    /**
     * Remembers staged bot ids found in the lines ({@code {bot}} is the first, then {@code {bot2}} ...) and the
     * variables a scenario's staging hands over ({@link #STAGE_VAR}).
     */
    void learnBots(List<AutoQaLine> lines) {
        for (AutoQaLine l : lines) {
            boolean staging = l.isQa() && ("STAGE".equals(l.kind()) || "PERSONA".equals(l.kind())
                    || "AFFINITY".equals(l.kind()) && l.message().startsWith("STAGED bot "));
            if (!staging) {
                continue;
            }
            Matcher v = "STAGE".equals(l.kind()) ? STAGE_VAR.matcher(l.message()) : null;
            if (v != null && v.find()) {
                vars.putIfAbsent(v.group(1), v.group(2).trim());
                continue;
            }
            Matcher m = BOT_ID.matcher("AFFINITY".equals(l.kind()) ? l.message().split(" -> ")[0] : l.message());
            while (m.find()) {
                int id = Integer.parseInt(m.group(1));
                if (stagedBots.add(id)) {
                    int n = stagedBots.size();
                    String name = world.nameOf(id);
                    if (name != null) {
                        vars.putIfAbsent(n == 1 ? "bot" : "bot" + n, name);
                        String all = vars.get("staged");
                        vars.put("staged", (all == null ? "" : all + "|") + Pattern.quote(name));
                    }
                }
            }
        }
    }

    public List<Integer> stagedBots() {
        return new ArrayList<>(stagedBots);
    }

    /** Replaces {me}, {p2}, {bot}, {bot2} ... and any variable a step set. Unknown ones stay as they are. */
    public String fill(String text) {
        String out = text;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    static final Pattern UNFILLED = Pattern.compile("\\{[a-z][a-z0-9]*}");

    /**
     * The pattern with its placeholders filled, or null (with a note) when one is still unknown - a staged bot that
     * never showed up must make the expectation fail cleanly, not throw "Illegal repetition".
     */
    public Pattern compile(String regex) {
        String filled = fill(regex);
        Matcher m = UNFILLED.matcher(filled);
        if (m.find()) {
            lines(); // learn the staged bots logged since, then try again
            filled = fill(regex);
            m = UNFILLED.matcher(filled);
        }
        if (m.find(0)) {
            note("unknown " + m.group() + " in /" + regex + "/ (no staged bot or variable by that name)");
            return null;
        }
        return Pattern.compile(filled);
    }

    /** Every line of this check so far: the GM's qa lines and what each player heard, in time order. */
    public List<AutoQaLine> lines() {
        List<AutoQaLine> all = new ArrayList<>();
        AutoQaWorld.Actor gm = actors.get(1);
        if (gm != null) {
            all.addAll(world.qaLines(gm.id(), startMs));
        }
        for (AutoQaWorld.Actor a : actors.values()) {
            all.addAll(a.heard(startMs));
        }
        all.sort(Comparator.comparingLong(AutoQaLine::timeMs));
        learnBots(all);
        return all;
    }

    /** The lines matching the pattern (against "KIND message", placeholders filled) between two marks. */
    public List<AutoQaLine> matching(String regex, String afterMark, String beforeMark) {
        Pattern p = compile(regex);
        if (p == null) {
            return List.of();
        }
        long from = afterMark == null ? startMs : markTime(afterMark);
        long to = beforeMark == null ? Long.MAX_VALUE : markTime(beforeMark);
        List<AutoQaLine> out = new ArrayList<>();
        for (AutoQaLine l : lines()) {
            if (l.timeMs() >= from && l.timeMs() < to && p.matcher(l.text()).find()) {
                out.add(l);
            }
        }
        return out;
    }
}
