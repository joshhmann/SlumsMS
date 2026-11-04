package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotMemory;

import java.util.List;

/**
 * What the social acts see of the game and do to it: positions, availability, a chat bubble, an emote, a walk, and the
 * bot-to-bot memory. Everything the registry ({@link SocialActions}) and the decision tick ({@link SocialDecider})
 * touch in the world goes through here, so a simulation or a test plugs in a recording fake and the real game plugs in
 * {@code BotConvo.GameSocialWorld} (character positions, {@code SocialCommands.BotSpeak/BotEmote},
 * {@code GCMovement.move}, {@code BotMemory}).
 *
 * <p>Reads are memory only and cheap; a bot decides from what is on its own map within sight, never from a global
 * list. Every method must be safe to call from a bot's own thread and must not throw (return null / false).
 */
public interface SocialWorld {

    /** One character as the acts see it: where it stands and who it is. {@code player}: a real player, never a target. */
    record Body(int id, String name, long mapKey, int mapId, int x, int y, int level, boolean player) {
        /** Horizontal and vertical distance to another body (px); {@code Integer.MAX_VALUE} on different maps. */
        public int dx(Body o) {
            return mapKey != o.mapKey ? Integer.MAX_VALUE : Math.abs(x - o.x);
        }

        public int dy(Body o) {
            return mapKey != o.mapKey ? Integer.MAX_VALUE : Math.abs(y - o.y);
        }
    }

    /** The character, or null when it is offline or not on a map. */
    Body body(int charId);

    /** The other bots on {@code charId}'s map within {@code radiusX} x {@code radiusY} px of it (never {@code charId}). */
    List<Body> nearby(int charId, int radiusX, int radiusY);

    /** The other bots on {@code viewerCharId}'s map within the box around the point {@code (x, y)} (who stands with whom). */
    List<Body> around(int viewerCharId, int x, int y, int radiusX, int radiusY);

    /**
     * Whether the bot may take part in something social now: not in a talk, a chat with a player, a scene, a trade or
     * on a commitment (the existing {@code BotTalk.free} and log-out gates).
     */
    boolean free(int charId);

    /** Whether the bot is idle enough to be moved (loitering in a town, resting): stricter than {@link #free}. */
    boolean idle(int charId);

    /** The bot says a line in map chat (the bubble). False when it could not. */
    boolean say(int charId, String text);

    /** The bot shows an emote. */
    void emote(int charId, int emote);

    /** The bot walks to {@code (x, y)} on its own map (the movement system's own pathing). */
    void walkTo(int charId, int x, int y);

    /** The bot's own favourite place to stand: a point at least {@code gapPx} from {@code (fromX)} on its ground. */
    default int awayX(Body me, int fromX, int gapPx) {
        int side = me.x() >= fromX ? 1 : -1;
        return fromX + side * gapPx;
    }

    /** What the bot remembers about {@code subjectId}, best first for the query (memory only; empty when not cached). */
    List<BotMemory.Note> recall(int botCharId, int subjectId, BotMemory.Query query, long now, int limit);

    /** The bot writes a note about {@code subjectId} (written in the background; the text names nobody). */
    void remember(int botCharId, int subjectId, BotMemory.Kind kind, String text, double valence, int mapId, long now);

    /** Whether the two are in one party (spending time then counts as PARTY_TIME). */
    default boolean sameParty(int a, int b) {
        return false;
    }

}
