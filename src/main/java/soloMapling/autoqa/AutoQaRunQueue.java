package soloMapling.autoqa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.6.2 b016: a queue of auto-QA run requests. tools/ops/autoqa-run appends one line per request to
 * {@code run-queue.txt} ({@code <id>TAB<utc stamp>TAB<request>}, one atomic append); the server takes them in order,
 * one per finished run. The operator-side file is only ever appended to; the server first renames it away (atomic) and
 * adds its lines to {@code run-queue.work}, which only the server writes, so no request is lost or reordered. A legacy
 * {@code run-request.txt} (older tool) is still honoured: its request runs ahead of the queue.
 */
final class AutoQaRunQueue {

    static final String QUEUE_FILE = "run-queue.txt";
    static final String WORK_FILE = "run-queue.work";

    private AutoQaRunQueue() {
    }

    /** The request part of a queue line: the third TAB field, or the whole line for a plain (legacy) one. */
    static String requestOf(String line) {
        String[] f = line.split("\t", 3);
        return (f.length == 3 ? f[2] : line).trim();
    }

    /** Requests waiting (in the server's work file plus not yet merged operator lines). */
    static int pending(Path dir) {
        return lines(dir.resolve(WORK_FILE)).size() + lines(dir.resolve(QUEUE_FILE)).size();
    }

    /**
     * The next request to run, removed from the queue; null when there is none. Call it only when no run is going.
     * Legacy {@code run-request.txt} first, then the queue in arrival order.
     */
    static String takeNext(Path dir) {
        try {
            String legacy = AutoQaService.takeRequest(dir.resolve(AutoQaService.REQUEST_FILE));
            if (legacy != null) {
                return legacy;
            }
            Path queue = dir.resolve(QUEUE_FILE);
            Path work = dir.resolve(WORK_FILE);
            if (Files.isRegularFile(queue)) {
                Path moved = dir.resolve(QUEUE_FILE + ".merge");
                Files.move(queue, moved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                String add = Files.readString(moved);
                Files.writeString(work, add, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.deleteIfExists(moved);
            }
            List<String> all = lines(work);
            while (!all.isEmpty()) {
                String line = all.remove(0);
                String req = requestOf(line);
                if (req.isEmpty()) {
                    continue;
                }
                Files.write(work, all);
                return req;
            }
            Files.deleteIfExists(work);
            return null;
        } catch (IOException e) {
            AutoQa.log.warn("Auto-QA could not read the run queue in {}", dir, e);
            return null;
        }
    }

    private static List<String> lines(Path f) {
        try {
            if (!Files.isRegularFile(f)) {
                return new ArrayList<>();
            }
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
