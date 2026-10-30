package soloMapling.qa;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Lets tests in other packages turn QaLog topics on (a run watching every map) and read the lines written. */
public final class QaLogTestAccess {
    private static Runnable unlisten = () -> { };

    private QaLogTestAccess() {
    }

    /** Starts a run listening to the topics; each line lands in sink as "TOPIC message". Close with {@link #reset}. */
    public static void listen(Set<QaLog.Topic> topics, Path logDir, List<String> sink) throws Exception {
        listen(1, Set.of(), topics, logDir, sink);
    }

    /** As above, for a run GM gmId started that watches only these maps (empty = every map). */
    public static void listen(int gmId, Set<Integer> maps, Set<QaLog.Topic> topics, Path logDir, List<String> sink)
            throws Exception {
        QaRun run = new QaRun("t-1", gmId, "Gm" + gmId, Instant.parse("2026-10-30T00:00:00Z"), logDir, topics, maps,
                Set.of());
        run.open(List.of("header"));
        unlisten = QaLog.listen((gm, scenario, kind, message) -> sink.add(kind + " " + message));
        QaLog.attach(run);
    }

    public static void reset() {
        unlisten.run();
        unlisten = () -> { };
        QaLog.reset();
    }
}
