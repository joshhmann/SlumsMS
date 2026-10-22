package soloMapling.qa;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One GM's running {@code !qa} scenario: who started it, what it watches, the cleanups that undo what it staged and
 * its log file. Cleanups run last-in first-out when the run stops ({@code !qa stop}, the timeout, or a refused stage),
 * each on its own so one failing does not keep the rest from running; every outcome is written to the log.
 */
public final class QaRun {

    static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    static final DateTimeFormatter HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** Something to undo at stop: a label for the log and the action. */
    record Cleanup(String label, Runnable action) {
    }

    private final String scenarioId;
    private final int gmId;
    private final String gmName;
    private final Instant started;
    private final Path logPath;
    private final Set<QaLog.Topic> topics;
    private final Set<Integer> maps;
    private final Set<String> claims;
    private final Deque<Cleanup> cleanups = new ArrayDeque<>();
    private final Map<QaLog.Topic, Integer> counts = new EnumMap<>(QaLog.Topic.class);
    private final List<Runnable> cancels = new ArrayList<>();
    private BufferedWriter out;
    private boolean stopped;
    /** The log file failed (a full disk): no more lines, but stop() still runs every cleanup (review 2026-10-22). */
    private boolean logBroken;

    QaRun(String scenarioId, int gmId, String gmName, Instant started, Path logDir, Set<QaLog.Topic> topics,
          Set<Integer> maps, Set<String> claims) {
        this.scenarioId = scenarioId;
        this.gmId = gmId;
        this.gmName = gmName;
        this.started = started;
        this.logPath = logDir.resolve(fileName(scenarioId, started));
        this.topics = topics.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(topics));
        this.maps = Set.copyOf(maps);
        this.claims = Set.copyOf(claims);
    }

    static String fileName(String scenarioId, Instant started) {
        return scenarioId + "-" + FILE_TIME.format(started) + ".log";
    }

    public String scenarioId() {
        return scenarioId;
    }

    public int gmId() {
        return gmId;
    }

    public String gmName() {
        return gmName;
    }

    public Instant started() {
        return started;
    }

    public Path logPath() {
        return logPath;
    }

    public Set<QaLog.Topic> topics() {
        return topics;
    }

    public Set<Integer> maps() {
        return maps;
    }

    public Set<String> claims() {
        return claims;
    }

    public Duration elapsed(Instant now) {
        return Duration.between(started, now);
    }

    synchronized boolean isStopped() {
        return stopped;
    }

    /* A line passes when the run listens to its topic and the line is for this run's maps or this run's GM. */
    boolean accepts(QaLog.Topic topic, int mapId, int ownerId) {
        if (!topics.contains(topic)) {
            return false;
        }
        if (ownerId != 0 && ownerId != gmId) {
            return false;
        }
        return mapId == 0 || maps.isEmpty() || maps.contains(mapId);
    }

    /** Registers something to undo when the run stops. Cleanups run in reverse order. */
    public synchronized void onStop(String label, Runnable action) {
        cleanups.push(new Cleanup(label, action));
    }

    synchronized int pendingCleanups() {
        return cleanups.size();
    }

    synchronized void addCancel(Runnable cancel) {
        cancels.add(cancel);
    }

    /** Opens the log file and writes the header. */
    synchronized void open(List<String> header) throws IOException {
        Files.createDirectories(logPath.getParent());
        out = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8);
        out.write("# !qa " + scenarioId + " - " + gmName + " (" + gmId + ")");
        out.newLine();
        out.write("# started " + HEADER_TIME.format(started));
        out.newLine();
        for (String h : header) {
            out.write("# " + h);
            out.newLine();
        }
        out.flush();
    }

    /** A staging or bookkeeping line ("STAGE", "STOP", "TICK" ...). */
    public void note(String kind, String message) {
        write(kind, message);
    }

    void event(QaLog.Topic topic, String message) {
        synchronized (this) {
            counts.merge(topic, 1, Integer::sum);
        }
        write(topic.name(), message);
    }

    synchronized Map<QaLog.Topic, Integer> counts() {
        return new EnumMap<>(counts);
    }

    private synchronized void write(String kind, String message) {
        if (!stopped) {
            QaLog.toListeners(gmId, scenarioId, kind, message); // auto-QA reads the run as it goes
        }
        if (out == null || stopped || logBroken) {
            return;
        }
        try {
            out.write(LINE_TIME.format(Instant.now()));
            out.write(' ');
            out.write(kind);
            out.write(' ');
            out.write(message.replace('\n', ' '));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            logBroken = true; // a full disk must not break the hook that called us, nor skip the cleanups at stop
        }
    }

    /**
     * Ends the run: cancels its timers, runs the cleanups (last first), writes a summary and closes the file.
     * Returns one line per cleanup for the GM. Safe to call twice; the second call does nothing.
     */
    List<String> stop(String reason, Instant now) {
        List<Cleanup> todo;
        List<Runnable> cancel;
        synchronized (this) {
            if (stopped) {
                return List.of();
            }
            todo = new ArrayList<>(cleanups);
            cleanups.clear();
            cancel = new ArrayList<>(cancels);
            cancels.clear();
        }
        for (Runnable c : cancel) {
            try {
                c.run();
            } catch (RuntimeException ignored) {
                // a timer that already fired
            }
        }
        write("STOP", reason + " after " + human(elapsed(now)));
        List<String> results = new ArrayList<>();
        for (Cleanup c : todo) {
            String line;
            try {
                c.action().run();
                line = "undone: " + c.label();
            } catch (RuntimeException e) {
                line = "cleanup failed: " + c.label() + " (" + e + ")";
            }
            write("CLEANUP", line);
            results.add(line);
        }
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<QaLog.Topic, Integer> e : counts().entrySet()) {
            summary.append(summary.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
        }
        write("SUMMARY", summary.length() == 0 ? "no events logged" : summary + " events");
        synchronized (this) {
            stopped = true;
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing more to write
                }
                out = null;
            }
        }
        return results;
    }

    static String human(Duration d) {
        long s = Math.max(0, d.getSeconds());
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }
}
