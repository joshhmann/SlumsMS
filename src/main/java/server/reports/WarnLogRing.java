package server.reports;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ticket 131: the last {@link #CAPACITY} WARN/ERROR server log lines, in memory, so @bug can attach the ones that
 * mention the reporter. Attached to the root logger at boot ({@link #install()}); loggers with their own files and no
 * additivity (chat, trades, BotLog) never reach it. Each line is cut to {@link #MAX_LINE} characters (plus the
 * exception's class and message, no stack). Nothing is filtered on the way in; {@link #linesFor} filters by
 * character and drops anything {@link BugPrivacy} calls sensitive at report time.
 */
public final class WarnLogRing extends AbstractAppender {
    public static final int CAPACITY = 500;
    public static final int MAX_LINE = 400;
    public static final String NAME = "SlumsBugRing";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    public record Line(long time, String level, String text) {
        public String render() {
            return TIME.format(Instant.ofEpochMilli(time)) + " " + level + " " + text;
        }
    }

    private static final WarnLogRing INSTANCE = new WarnLogRing(CAPACITY);
    private static volatile boolean installed;

    private final int capacity;
    private final ArrayDeque<Line> lines;

    WarnLogRing(int capacity) {
        super(NAME, null, null, true, Property.EMPTY_ARRAY);
        this.capacity = capacity;
        this.lines = new ArrayDeque<>(capacity);
    }

    public static WarnLogRing get() {
        return INSTANCE;
    }

    /** Adds the ring to the root logger at WARN. Safe to call twice; never throws (a report just has no log lines). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            if (!(LogManager.getContext(false) instanceof LoggerContext ctx)) {
                return;
            }
            Configuration config = ctx.getConfiguration();
            INSTANCE.start();
            config.addAppender(INSTANCE);
            config.getRootLogger().addAppender(INSTANCE, Level.WARN, null);
            ctx.updateLoggers();
            installed = true;
        } catch (RuntimeException | LinkageError e) {
            // a missing log4j core only costs the log lines in reports
        }
    }

    @Override
    public void append(LogEvent event) {
        if (event == null || !event.getLevel().isMoreSpecificThan(Level.WARN)) {
            return;
        }
        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        int dot = logger.lastIndexOf('.');
        String msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        Throwable t = event.getThrown();
        StringBuilder sb = new StringBuilder(Math.min(MAX_LINE + 64, 64 + msg.length()));
        sb.append(dot >= 0 ? logger.substring(dot + 1) : logger).append(" - ").append(msg);
        if (t != null) {
            sb.append(" [").append(t.getClass().getName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            sb.append(']');
        }
        add(new Line(event.getTimeMillis(), event.getLevel().name(), cut(sb.toString())));
    }

    static String cut(String s) {
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= MAX_LINE ? one : one.substring(0, MAX_LINE) + "...";
    }

    synchronized void add(Line line) {
        while (lines.size() >= capacity) {
            lines.pollFirst();
        }
        lines.addLast(line);
    }

    synchronized List<Line> snapshot() {
        return new ArrayList<>(lines);
    }

    public synchronized int size() {
        return lines.size();
    }

    /**
     * Lines since {@code sinceMs} that mention the character by name (whole word, any case) or by id ("chr 123",
     * "id=123", "character #123" and the like: a bare number would match anything), minus every sensitive line;
     * oldest first, at most {@code max}, keeping the newest.
     */
    public List<String> linesFor(String name, int charId, long sinceMs, int max) {
        Pattern who = whoPattern(name, charId);
        List<String> out = new ArrayList<>();
        for (Line l : snapshot()) {
            if (l.time() < sinceMs || !who.matcher(l.text()).find() || !BugPrivacy.serverLineAllowed(l.text())) {
                continue;
            }
            out.add(l.render());
        }
        return out.size() <= max ? out : new ArrayList<>(out.subList(out.size() - max, out.size()));
    }

    static Pattern whoPattern(String name, int charId) {
        String byName = name == null || name.isBlank() ? null : "(?<![A-Za-z0-9])" + Pattern.quote(name) + "(?![A-Za-z0-9])";
        String byId = "(?:chr|char|character|cid|charid|characterid|player|id)\\s*[#:=]?\\s*" + charId + "(?!\\d)";
        return Pattern.compile("(?i)" + (byName == null ? byId : byName + "|" + byId));
    }
}
