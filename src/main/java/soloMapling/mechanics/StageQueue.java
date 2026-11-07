package soloMapling.mechanics;

import client.Character;
import client.command.commands.gm3.MechanicsCommand;
import net.server.channel.Channel;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.server.ExecutorServiceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static soloMapling.BotLogger.log;

/**
 * 1.6.6 mechanics-pq-staging, TEST only: the stage queue behind {@code tools/ops/mechanics-stage}. The tool appends one
 * line per request to {@code data/mechanics/stage-queue.txt} ({@code <id>TAB<utc stamp>TAB<request>}); the server takes
 * them in arrival order and runs each exactly as {@code !mechanics pq ROW for NAME ...} runs, with
 * {@value #ISSUER} as the issuer.
 *
 * <p>Why it exists: QA's party leader is a level-0 pretend player ({@code AqClient}, a {@code SyntheticClient}) who is
 * not a GM and has no game client of its own, so nobody can type the {@code for NAME} form for it. The tool needs no
 * login, and the run it stages is the run the command stages - one method, {@link MechanicsCommand#runPq}.
 *
 * <p>On only with {@code -Dslums.qa=true} <b>and</b> {@code -Dslums.mechanics=true}; a live server has neither. It polls
 * every {@value #POLL_SECONDS} s, takes one request per poll (a staging is still going while it spawns the regulars and
 * starts the quest, and a second one waits for it), and drops what it cannot run: a line that does not parse, names no
 * row whose own switch is on, or names a character who is not online is logged once and never retried.
 *
 * <p>The file is the operator's, append only; the server renames it away ({@code ATOMIC_MOVE}) and merges its lines into
 * {@code stage-queue.work}, which only the server writes, so a request appended while a poll is reading it is not lost -
 * the same rule as the auto-QA run queue ({@code soloMapling.autoqa.AutoQaRunQueue}).
 */
public final class StageQueue {

    /** The switch: {@code -Dslums.qa} (QA itself), on top of {@code -Dslums.mechanics}. */
    public static final String FLAG = "slums.qa";
    public static final String QUEUE_FILE = "stage-queue.txt";
    static final String WORK_FILE = "stage-queue.work";
    /** Who the [Mechanics] lines name as the issuer: no one types a queued request. */
    public static final String ISSUER = "stage-queue";
    static final long POLL_SECONDS = 30;

    private static StageQueue instance;

    private final Path dir;
    /** How a request's leader is found; a test replaces it, everything else is the real path. */
    Function<String, Character> lookup = MechanicsCommand::findOnline;
    private boolean watching;
    private volatile boolean staging;

    StageQueue(Path dir) {
        this.dir = dir;
    }

    /**
     * Where the queue file lives: {@code data/mechanics} under the working directory (the same place the server keeps
     * its other data), {@code -Dslums.mechanics.stagequeue.dir} to override.
     */
    public static Path dataDir() {
        return Path.of(System.getProperty("slums.mechanics.stagequeue.dir", "data/mechanics"));
    }

    /** True when the queue may run at all: QA on, mechanics on. Read on every poll. */
    public static boolean enabled() {
        return Boolean.getBoolean(FLAG) && Mechanics.enabled();
    }

    public static synchronized StageQueue get() {
        if (instance == null) {
            instance = new StageQueue(dataDir());
        }
        return instance;
    }

    /** Starts the 30 s poll when both switches are on. Safe to call more than once. */
    public static synchronized void boot() {
        if (!enabled()) {
            return;
        }
        get().start();
    }

    private synchronized void start() {
        if (watching) {
            return;
        }
        watching = true;
        ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log("[Mechanics] PQ stage queue poll failed: " + e);
            }
        }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        log("[Mechanics] PQ stage queue on: " + dir.resolve(QUEUE_FILE) + ", every " + POLL_SECONDS + " s");
    }

    /**
     * One poll: takes and starts at most one request, off the scheduler thread (staging blocks). Null when the switches
     * are off, nothing waited, or a staging is still going - the next poll looks again.
     */
    String tick() {
        if (!enabled() || staging) {
            return null;
        }
        String request = takeNext();
        if (request == null) {
            return null;
        }
        staging = true;
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                log("[Mechanics] PQ staging from the stage queue: " + request + " -> " + run(request));
            } catch (RuntimeException e) {
                log("[Mechanics] PQ stage queue request failed: " + request + ": " + e);
            } finally {
                staging = false;
            }
        });
        return request;
    }

    /** Requests waiting (the server's work file plus operator lines not yet merged into it). */
    int pending() {
        return lines(dir.resolve(WORK_FILE)).size() + lines(dir.resolve(QUEUE_FILE)).size();
    }

    /** The next request in arrival order, removed from the queue; null when there is none. A blank line is skipped. */
    String takeNext() {
        try {
            Path queue = dir.resolve(QUEUE_FILE);
            Path work = dir.resolve(WORK_FILE);
            if (Files.isRegularFile(queue)) {
                Path moved = dir.resolve(QUEUE_FILE + ".merge");
                Files.move(queue, moved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(work, Files.readString(moved), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                Files.deleteIfExists(moved);
            }
            List<String> all = lines(work);
            while (!all.isEmpty()) {
                String req = requestOf(all.remove(0));
                if (req.isEmpty()) {
                    continue;
                }
                Files.write(work, all);
                return req;
            }
            Files.deleteIfExists(work);
        } catch (IOException e) {
            log("[Mechanics] PQ stage queue could not be read in " + dir + ": " + e);
        }
        return null;
    }

    /**
     * Runs one queue request exactly as the command would: {@code pq ROW for NAME [N] [rangedK]}. Returns the outcome
     * sentence; a request that cannot run is "dropped: ..." and is never retried. Blocking.
     */
    String run(String request) {
        String[] p = request.trim().split("\\s+");
        MechanicsCommand.PqRequest req = p.length < 2 || !"pq".equalsIgnoreCase(p[0])
                ? null : MechanicsCommand.parsePq(p, 1);
        if (req == null) {
            return "dropped: not a pq request (pq ROW for NAME [N] [rangedK])";
        }
        if (req.leaderName() == null) {
            return "dropped: no leader to stage for (for NAME is required: nobody types it)";
        }
        Content row = MechanicsCommand.pqRow(req.rowId());
        if (row == null) {
            return "dropped: no party quest row called " + req.rowId();
        }
        String refused = MechanicsCommand.pqSwitchOff(row);
        if (refused != null) {
            return "dropped: " + refused;
        }
        Character leader = lookup.apply(req.leaderName());
        if (leader == null) {
            return "dropped: nobody called " + req.leaderName() + " is online";
        }
        Channel on = leader.getClient().getChannelServer();
        if (on == null) {
            return "dropped: " + leader.getName() + " is on no channel";
        }
        return MechanicsCommand.runPq(row, on, leader, ISSUER, req, line -> log("[Mechanics] " + line));
    }

    /** The request part of a queue line: the third TAB field, or the whole line for a plain one. */
    static String requestOf(String line) {
        String[] f = line.split("\t", 3);
        return (f.length == 3 ? f[2] : line).trim();
    }

    private static List<String> lines(Path f) {
        if (!Files.isRegularFile(f)) {
            return new ArrayList<>();
        }
        try {
            List<String> out = new ArrayList<>();
            for (String l : Files.readAllLines(f)) {
                if (!l.isBlank()) {
                    out.add(l);
                }
            }
            return out;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }
}
