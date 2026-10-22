package soloMapling.autoqa;

import java.util.List;
import java.util.Map;

/**
 * Everything the check runner needs from the server, in one place: {@link GameAutoQaWorld} on the server, a scripted
 * fake in the tests ({@code AutoQaRunnerTest}). The runner itself only talks, waits and reads lines.
 */
public interface AutoQaWorld {

    /** Why checks cannot run here (no {@code !qa} suite, no database ...), or null when they can. */
    String unavailableReason();

    /**
     * Synthetic player 1 (a GM, runs {@code !qa}) or 2 (a plain player), logged in on channel 1 if it is not yet.
     * Throws {@link IllegalStateException} with the reason when the login fails.
     */
    Actor actor(int slot);

    /** Logs every synthetic player out (end of a run). */
    void logoutAll();

    /** The lines the {@code !qa} runs of this character wrote since the time (inclusive). */
    List<AutoQaLine> qaLines(int gmId, long sinceMs);

    /** A character's name by id (staged bots), or null. */
    String nameOf(int characterId);

    long now();

    void sleep(long ms) throws InterruptedException;

    /** A server-side fact for a check (see {@link GameAutoQaWorld#probe}): lines of evidence, first line the verdict. */
    List<String> probe(String what, Actor actor, String... args);

    /** The LLM judge (may be unavailable). */
    LlmJudge judge();

    /** The last health sweep's result, or null. */
    Map<String, Object> lastHealth();

    /** A synthetic player: talks through the real packet handlers, hears what its client would. */
    interface Actor {
        int id();

        String name();

        int slot();

        /** Says a line in map chat (or a command: "!qa run af-1", "@affinity") through GeneralChatHandler. */
        void say(String text);

        /** Whispers a character through WhisperHandler. */
        void whisper(String target, String text);

        /** Buddy, party, guild chat (0, 1, 2) through MultiChatHandler. */
        void multiChat(int type, String text);

        /** Invites a character to a party (creates one first if needed). */
        void partyInvite(String name);

        /** Accepts the last party invite received; false when there was none. */
        boolean acceptPartyInvite();

        /** Invites a character to the player's guild. */
        void guildInvite(String name);

        /** Accepts the last guild invite received; false when there was none. */
        boolean acceptGuildInvite();

        /** Sends a buddy request. */
        void buddyAdd(String name);

        /** What this player's client received since the time. */
        List<AutoQaLine> heard(long sinceMs);

        int mapId();

        boolean isOnline();
    }
}
