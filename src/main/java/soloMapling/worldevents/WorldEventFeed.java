package soloMapling.worldevents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static soloMapling.BotLogger.log;

/**
 * The public announcements feed (ticket 21 + the monitor's {@code announce-feed}): one JSON line per player-facing
 * world event moment, appended to {@code -Dslumsms.announceFile}. The monitor turns each new line into one Discord
 * post (Cody's voice, its own rate cap and dedupe); this side only records facts, never wording.
 * <p>
 * Off unless the property is set. Only boss sightings, treasure hunts and hot spots are written, at their start and
 * (boss, treasure) when a real player wins - never bot chatter, megaphones, invasions or the wandering NPC. A treasure
 * hunt's start line names the region only, like the in-game notice, so Discord never gives the map away.
 * <p>
 * Writes happen on one daemon thread with a small bounded queue: a call from the game thread never blocks or throws,
 * and a full queue or a write error drops the line (logged).
 */
public final class WorldEventFeed {

    public static final String PROPERTY = "slumsms.announceFile";
    static final Set<EventTemplate.Kind> KINDS = Set.of(EventTemplate.Kind.BOSS_SIGHTING,
            EventTemplate.Kind.TREASURE_HUNT, EventTemplate.Kind.HOT_SPOT);
    private static final int QUEUE = 64;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE), r -> {
                Thread t = new Thread(r, "WorldEventFeed");
                t.setDaemon(true);
                return t;
            }, (r, pool) -> log("[WorldEvents] announce feed queue full; line dropped"));

    static {
        WRITER.allowCoreThreadTimeOut(true);
    }

    private WorldEventFeed() {
    }

    /** The feed file, or null when the feed is off. Read each time so a test or a restart can change it. */
    static Path file() {
        String p = System.getProperty(PROPERTY, "").trim();
        return p.isEmpty() ? null : Path.of(p);
    }

    public static void started(ActiveEvent e, long now) {
        submit(startLine(e, now));
    }

    public static void won(ActiveEvent e, long now) {
        submit(wonLine(e, now));
    }

    /** The start line, or null when this kind is not announced. Pure (tested). */
    static Map<String, Object> startLine(ActiveEvent e, long now) {
        if (e == null || !KINDS.contains(e.template.kind())) {
            return null;
        }
        Map<String, Object> m = base(e, "start", now);
        switch (e.template.kind()) {
            case BOSS_SIGHTING -> {
                m.put("boss", e.subject);
                m.put("map", e.mapName);
            }
            case TREASURE_HUNT -> m.put("region", e.regionName); // never the map: that is the hunt
            case HOT_SPOT -> {
                m.put("map", e.mapName);
                m.put("bonus", Math.round((e.template.expBonus() - 1) * 100));
            }
            default -> {
            }
        }
        m.put("minutes", Math.max(1, (e.endsAt - now + 30_000) / 60_000));
        return m;
    }

    /** The win line (a real player won a boss sighting or a treasure hunt), or null. Pure (tested). */
    static Map<String, Object> wonLine(ActiveEvent e, long now) {
        if (e == null || e.winnerName == null || !KINDS.contains(e.template.kind())
                || e.template.kind() == EventTemplate.Kind.HOT_SPOT) {
            return null;
        }
        Map<String, Object> m = base(e, "won", now);
        m.put("player", e.winnerName);
        m.put("map", e.mapName);
        if (e.template.kind() == EventTemplate.Kind.BOSS_SIGHTING) {
            m.put("boss", e.subject);
        }
        return m;
    }

    private static Map<String, Object> base(ActiveEvent e, String type, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Unique per server run and moment: event ids restart at 1 after a reboot, the start time does not repeat.
        m.put("id", "we-" + e.startedAt + "-" + e.id + "-" + type);
        m.put("at", now / 1000);
        m.put("type", type);
        m.put("kind", e.template.kind().name().toLowerCase(java.util.Locale.ROOT));
        m.put("event", e.template.id());
        m.put("name", e.template.name());
        m.put("ch", e.channel);
        m.put("by", "scheduler".equals(e.startedBy) ? "scheduler" : "gm");
        return m;
    }

    static String toJson(Map<String, Object> line) throws JsonProcessingException {
        return JSON.writeValueAsString(line);
    }

    private static void submit(Map<String, Object> line) {
        Path path = file();
        if (path == null || line == null) {
            return;
        }
        try {
            WRITER.execute(() -> write(path, line));
        } catch (RuntimeException ex) {
            log("[WorldEvents] announce feed: " + ex);
        }
    }

    /** Appends one line; opened per write so log rotation or a deleted file needs no restart. */
    static void write(Path path, Map<String, Object> line) {
        try {
            byte[] bytes = (toJson(line) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException ex) {
            log("[WorldEvents] announce feed write to " + path + " failed: " + ex);
        }
    }

    /** Tests: wait until the lines queued so far are written (one writer thread, so FIFO). */
    static void drainForTest() throws Exception {
        WRITER.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }
}
