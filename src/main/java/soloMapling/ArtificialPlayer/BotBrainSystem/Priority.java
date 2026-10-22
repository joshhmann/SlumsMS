package soloMapling.ArtificialPlayer.BotBrainSystem;

/**
 * Ticket 59, owner decision 7: who an LLM call is for, highest first. The router ({@code ChatRouter}) gives the local
 * model's slots to higher priorities first, sends only {@link #PLAYER} to the cloud overflow, and lower priorities
 * fall back to the line bank and scripts when the local model has no spare slot.
 */
public enum Priority {
    /** A reply to a real player's line to the bot (map chat, whisper). */
    PLAYER,
    /** Party, buddy, guild or partner channel replies. */
    PARTNER,
    /** Bot-to-bot threads on a map with a real player. */
    THREAD,
    /** Greetings on sight and other ambient lines. */
    AMBIENT
}
