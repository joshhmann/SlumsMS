package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.6.1: the last few whisper lines between ONE bot and ONE player (both directions), kept so the intent+con model
 * reads a whisper with the earlier lines of that whisper chat, as it does in map chat. One small trail lives in each
 * conversation, so it is per (bot, player) and goes when the conversation ends; lines older than {@link #EXPIRY_MS}
 * are never sent. At most {@link ChatDecide#MAX_EARLIER} lines are held.
 */
public final class WhisperTrail {

    /** A whisper older than this no longer counts as part of the chat. */
    public static final long EXPIRY_MS = 10 * 60_000L;

    private record Entry(ChatDecide.Role role, String text, long at) {
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
