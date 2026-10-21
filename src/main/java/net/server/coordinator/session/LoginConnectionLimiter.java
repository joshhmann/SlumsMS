package net.server.coordinator.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-IP gate for new connections to the login server: at most {@code maxSessions} login-server sessions open at once
 * from one address, and at most {@code maxConnects} new connections from it within {@code windowMillis}.
 * <p>
 * This replaces Cosmic's "one login session per remote host" rule, which refused a second client from the same address
 * (two friends behind one home router, the whole LAN - LAN and loopback addresses all map to HOST - or two clients on
 * one PC) for as long as the first one sat on the login/character-select screens. Same-machine multi-clienting is still
 * caught by the HWID check when the account logs in (DETERRED_MULTICLIENT).
 * <p>
 * A limit of 0 or less turns that check off. Thread-safe.
 */
public final class LoginConnectionLimiter {

    public enum Refusal {
        TOO_MANY_SESSIONS("too many open login sessions from this address"),
        TOO_MANY_CONNECTS("too many new connections from this address in the window");

        public final String reason;

        Refusal(String reason) {
            this.reason = reason;
        }
    }

    private final Map<String, Set<Long>> openSessions = new HashMap<>();
    private final Map<String, Deque<Long>> recentConnects = new HashMap<>();

    /**
     * Records a new connection when it is allowed.
     *
     * @return null when the connection may proceed (and now counts against the address), else why it was refused
     */
    public synchronized Refusal tryAcquire(String ip, long sessionId, int maxSessions, int maxConnects, long windowMillis, long now) {
        Deque<Long> connects = recentConnects.get(ip);
        if (connects != null) {
            while (!connects.isEmpty() && now - connects.peekFirst() >= windowMillis) {
                connects.pollFirst();
            }
        }

        Set<Long> sessions = openSessions.get(ip);
        if (maxSessions > 0 && sessions != null && sessions.size() >= maxSessions) {
            return Refusal.TOO_MANY_SESSIONS;
        }
        if (maxConnects > 0 && windowMillis > 0 && connects != null && connects.size() >= maxConnects) {
            return Refusal.TOO_MANY_CONNECTS;
        }

        openSessions.computeIfAbsent(ip, k -> new HashSet<>()).add(sessionId);
        if (maxConnects > 0 && windowMillis > 0) {
            recentConnects.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(now);
        }
        return null;
    }

    /** Frees the session's slot; safe to call more than once. */
    public synchronized void release(String ip, long sessionId) {
        Set<Long> sessions = openSessions.get(ip);
        if (sessions != null && sessions.remove(sessionId) && sessions.isEmpty()) {
            openSessions.remove(ip);
        }
    }

    /** Drops connect history older than the window so idle addresses do not accumulate. */
    public synchronized void purge(long windowMillis, long now) {
        recentConnects.values().removeIf(connects -> {
            while (!connects.isEmpty() && now - connects.peekFirst() >= windowMillis) {
                connects.pollFirst();
            }
            return connects.isEmpty();
        });
    }

    public synchronized int openSessions(String ip) {
        Set<Long> sessions = openSessions.get(ip);
        return sessions == null ? 0 : sessions.size();
    }

    /** Open login sessions per address, sorted by address. */
    public synchronized Map<String, Integer> snapshot() {
        Map<String, Integer> out = new TreeMap<>();
        openSessions.forEach((ip, sessions) -> out.put(ip, sessions.size()));
        return out;
    }

    public synchronized void clear() {
        openSessions.clear();
        recentConnects.clear();
    }
}
