package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.Locale;

/**
 * How a bot feels about a real player (ticket 58, owner decisions 2026-10-21). Neutral is the default. It never shows
 * as a number or a label to players: only in how the bot behaves. GMs see it with {@code !affinity}.
 */
public enum AffinityState {
    NEUTRAL, FRIEND, RIVAL, NEMESIS;

    public boolean hostile() {
        return this == RIVAL || this == NEMESIS;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "friend", "rival", "nemesis", "neutral" (and a few spellings); null when it is none of them. */
    public static AffinityState parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "friend", "friends", "f" -> FRIEND;
            case "rival", "r" -> RIVAL;
            case "nemesis", "nem", "n" -> NEMESIS;
            case "neutral", "none", "reset", "0" -> NEUTRAL;
            default -> null;
        };
    }
}
