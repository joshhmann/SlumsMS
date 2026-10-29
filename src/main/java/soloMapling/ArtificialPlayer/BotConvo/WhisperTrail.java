package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.6.1: the last few whisper lines between ONE bot and ONE player (both directions), kept so the intent+con model
 * reads a whisper with the earlier lines of that whisper chat, as it does in map chat. Trails live in a map keyed by
 * (bot, player), outside the 60 s conversation session, and go when either side logs out or they go stale; lines older than {@link #EXPIRY_MS}
 * are never sent. At most {@link ChatDecide#MAX_EARLIER} lines are held.
 */
public final class WhisperTrail {

    /** A whisper older than this no longer counts as part of the chat. */
    public static final long EXPIRY_MS = 10 * 60_000L;

    private record Entry(ChatDecide.Role role, String text, long at) {
    }

    private record Key(int botId, int playerId) {
    }

    /** One small trail per (bot, player) pair that has whispered lately; outside the 60 s conversation session. */
    private static final java.util.Map<Key, WhisperTrail> trails = new java.util.concurrent.ConcurrentHashMap<>();
    /** How often the sweep may run (it runs on access, so no timer is needed). */
    static final long SWEEP_MS = 3 * 60_000L;
    private static volatile long lastSweep;

    /** The pair's trail, created on first use. */
    public static WhisperTrail of(int botId, int playerId, long now) {
        sweep(now);
        return trails.computeIfAbsent(new Key(botId, playerId), k -> new WhisperTrail());
    }

    /** Drops trails with nothing fresh left; at most once per {@link #SWEEP_MS}. */
    public static void sweep(long now) {
        if (now - lastSweep < SWEEP_MS) {
            return;
        }
        lastSweep = now;
        trails.entrySet().removeIf(e -> e.getValue().earlier(now).isEmpty());
    }

    /** A bot left the world. */
    public static void dropBot(int botId) {
        trails.keySet().removeIf(k -> k.botId() == botId);
    }

    /** A player logged out. */
    public static void dropPlayer(int playerId) {
        trails.keySet().removeIf(k -> k.playerId() == playerId);
    }

    static int size() {
        return trails.size();
    }

    static void clearForTest() {
        trails.clear();
        lastSweep = 0;
    }

    private final ArrayDeque<Entry> lines = new ArrayDeque<>(ChatDecide.MAX_EARLIER + 1);

    /** Adds a line; {@code fromPlayer} false means the bot said it. */
    public synchronized void add(boolean fromPlayer, String text, long now) {
        if (text == null || text.isBlank()) {
            return;
        }
        lines.addLast(new Entry(fromPlayer ? ChatDecide.Role.PLAYER : ChatDecide.Role.YOU, text, now));
        while (lines.size() > ChatDecide.MAX_EARLIER) {
            lines.removeFirst();
        }
    }

    /** The lines still fresh at {@code now}, oldest first (newest last). */
    public synchronized List<ChatDecide.Earlier> earlier(long now) {
        List<ChatDecide.Earlier> out = new ArrayList<>(lines.size());
        for (Entry e : lines) {
            if (now - e.at() <= EXPIRY_MS) {
                out.add(new ChatDecide.Earlier(e.role(), e.text()));
            }
        }
        return out;
    }
}
