package soloMapling.ArtificialPlayer.BotConvo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ticket 59, owner decision 6: the conservative auto-tune, in one reviewable, hand-editable file
 * ({@code data/convo/tune.json}):
 * <ul>
 *   <li>{@code dontSay}: phrases a model reply may not contain (a hit falls back to the bank). Only added from a bad
 *   rating's quoted note ({@code !rate bad too formal "certainly"}) or by hand.</li>
 *   <li>{@code badExamples}: the newest 20 bad-rated model lines with notes; the newest 3 go in the prompt as "never
 *   write lines like".</li>
 *   <li>{@code goodExamples}: the newest 10 good-rated lines; 2 go in the prompt as voice examples.</li>
 * </ul>
 * {@code !rate reload} re-reads it after a hand edit.
 */
public final class ConvoTune {

    private static final Logger log = LoggerFactory.getLogger(ConvoTune.class);

    static final int MAX_BAD = 20;
    static final int MAX_GOOD = 10;
    static final int MAX_DONT_SAY = 100;

    /** One rated example. */
    public record Example(String line, String note, long at) {
    }

    private static final List<String> dontSay = new ArrayList<>();
    private static final List<Example> bad = new ArrayList<>();
    private static final List<Example> good = new ArrayList<>();
    private static volatile Path file;
    private static volatile boolean loaded;

    private ConvoTune() {
    }

    static Path file() {
        Path f = file;
        return f != null ? f : Path.of(ConvoSwitches.str("dataDir", "data/convo"), "tune.json");
    }

    /** Test seam: the file to use (null = the default), forgetting what was loaded. */
    public static synchronized void useFileForTest(Path f) {
        file = f;
        loaded = false;
        dontSay.clear();
        bad.clear();
        good.clear();
    }

    private static synchronized void ensureLoaded() {
        if (!loaded) {
            reload();
        }
    }

    /** Reads the file again (after a hand edit). Returns a one-line summary. */
    public static synchronized String reload() {
        loaded = true;
        dontSay.clear();
        bad.clear();
        good.clear();
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return "no tune file yet (" + f + ")";
        }
        try {
            if (Json.parse(Files.readString(f)) instanceof Map<?, ?> m) {
                if (m.get("dontSay") instanceof List<?> l) {
                    l.forEach(x -> dontSay.add(String.valueOf(x).toLowerCase(Locale.ROOT)));
                }
                readExamples(m.get("badExamples"), bad);
                readExamples(m.get("goodExamples"), good);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Tune file {} unreadable; using none", f, e);
        }
        return "tune: " + dontSay.size() + " don't-say, " + bad.size() + " bad, " + good.size() + " good examples";
    }

    private static void readExamples(Object raw, List<Example> into) {
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> e && e.get("line") instanceof String line) {
                    into.add(new Example(line, e.get("note") instanceof String n ? n : "",
                            e.get("at") instanceof Number n ? n.longValue() : 0));
                }
            }
        }
    }

    private static void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("about", "Ticket 59 auto-tune: reviewable and hand-editable. dontSay = phrases a model reply may not "
                + "contain; badExamples/goodExamples = rated lines (newest last). !rate reload after an edit.");
        m.put("dontSay", new ArrayList<>(dontSay));
        m.put("badExamples", examples(bad));
        m.put("goodExamples", examples(good));
        try {
            Path f = file();
            Files.createDirectories(f.toAbsolutePath().getParent());
            Files.writeString(f, Json.write(m));
        } catch (IOException | RuntimeException e) {
            log.warn("Tune file not written", e);
        }
    }

    private static List<Object> examples(List<Example> list) {
        List<Object> out = new ArrayList<>();
        for (Example e : list) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("line", e.line());
            x.put("note", e.note());
            x.put("at", e.at());
            out.add(x);
        }
        return out;
    }

    /** A rated model line: kept as an example; a bad one's quoted note phrases join the don't-say list. */
    public static synchronized void rated(boolean goodLine, String line, String note, long at) {
        ensureLoaded();
        List<Example> list = goodLine ? good : bad;
        list.removeIf(e -> e.line().equalsIgnoreCase(line));
        list.add(new Example(line, note == null ? "" : note, at));
        while (list.size() > (goodLine ? MAX_GOOD : MAX_BAD)) {
            list.remove(0);
        }
        if (!goodLine && note != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]{2,40})\"").matcher(note);
            while (m.find() && dontSay.size() < MAX_DONT_SAY) {
                String phrase = m.group(1).trim().toLowerCase(Locale.ROOT);
                if (!phrase.isEmpty() && !dontSay.contains(phrase)) {
                    dontSay.add(phrase);
                }
            }
        }
        save();
    }

    /** The don't-say phrase a model reply contains, or null. */
    public static synchronized String violation(String reply) {
        ensureLoaded();
        if (reply == null) {
            return null;
        }
        String t = reply.toLowerCase(Locale.ROOT);
        for (String p : dontSay) {
            if (t.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /** What the tune file adds to the voice part of the prompt ("" for nothing). Short. */
    public static synchronized String promptLines() {
        ensureLoaded();
        StringBuilder s = new StringBuilder();
        if (!good.isEmpty()) {
            s.append("Lines that sound right here: ").append(quoted(good, 2)).append(". ");
        }
        if (!bad.isEmpty()) {
            s.append("Never write lines like: ").append(quoted(bad, 3)).append(". ");
        }
        if (!dontSay.isEmpty()) {
            s.append("Never say: ").append(String.join(", ", dontSay.subList(Math.max(0, dontSay.size() - 8),
                    dontSay.size()))).append(". ");
        }
        return s.toString();
    }

    private static String quoted(List<Example> list, int newest) {
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, list.size() - newest); i < list.size(); i++) {
            out.add('"' + list.get(i).line() + '"');
        }
        return String.join(" / ", out);
    }

    public static synchronized List<String> dontSay() {
        ensureLoaded();
        return List.copyOf(dontSay);
    }
}
